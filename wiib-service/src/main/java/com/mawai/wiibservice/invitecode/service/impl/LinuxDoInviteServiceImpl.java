package com.mawai.wiibservice.invitecode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibservice.invitecode.dto.LinuxDoInviteRecordVO;
import com.mawai.wiibservice.invitecode.dto.LinuxDoInviteRecordsVO;
import com.mawai.wiibservice.invitecode.entity.LinuxdoInviteCode;
import com.mawai.wiibservice.invitecode.mapper.LinuxdoInviteCodeMapper;
import com.mawai.wiibservice.invitecode.service.EmailStrategy;
import com.mawai.wiibservice.invitecode.service.LinuxDoInviteService;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinuxDoInviteServiceImpl implements LinuxDoInviteService {

    private final LinuxdoInviteCodeMapper inviteCodeMapper;
    private final CacheService cacheService;
    private final EmailStrategy emailStrategy;
    private final RedisLockUtil redisLockUtil;

    @Value("${invite.front-url}")
    private String frontUrl;

    private static final String TOKEN_KEY_PREFIX = "LINUXDO_INVITE_TOKEN:";
    private static final String PENDING_EMAIL_PREFIX = "LINUXDO_INVITE_PENDING:";
    private static final String PENDING_DB_PREFIX = "pending:";
    private static final String APPLY_LOCK_KEY = "LINUXDO_INVITE_APPLY_LOCK";
    private static final String CLICK_COUNT_KEY = "LINUXDO_INVITE_CLICK_COUNT";
    private static final long TOKEN_EXPIRE_MINUTES = 30;
    private static final long RELEASE_CHECK_INTERVAL_MINUTES = 31;
    private static final long LOCK_TIMEOUT_SECONDS = 5;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean hasStock() {
        Long count = inviteCodeMapper.selectCount(
                new LambdaQueryWrapper<LinuxdoInviteCode>().isNull(LinuxdoInviteCode::getEmail)
        );
        return count != null && count > 0;
    }

    @Override
    public void apply(String email) {
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new RuntimeException("邮箱格式不正确");
        }

        Long claimed = inviteCodeMapper.selectCount(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .eq(LinuxdoInviteCode::getEmail, email)
        );
        if (claimed != null && claimed > 0) {
            throw new RuntimeException("该邮箱已领取过邀请码");
        }

        String pendingKey = PENDING_EMAIL_PREFIX + email;
        if (cacheService.hasKey(pendingKey)) {
            throw new RuntimeException("您已申请过，请查收邮件");
        }

        String lockValue = redisLockUtil.tryLock(APPLY_LOCK_KEY, LOCK_TIMEOUT_SECONDS);
        if (lockValue == null) {
            throw new RuntimeException("系统繁忙，请稍后重试");
        }
        try {
            LinuxdoInviteCode code = inviteCodeMapper.selectOne(
                    new LambdaQueryWrapper<LinuxdoInviteCode>()
                            .isNull(LinuxdoInviteCode::getEmail)
                            .last("LIMIT 1")
            );
            if (code == null) {
                throw new RuntimeException("邀请码已发完，请关注后续活动");
            }

            String token = UUID.randomUUID().toString().replace("-", "");

            int updated = inviteCodeMapper.update(
                    new LambdaUpdateWrapper<LinuxdoInviteCode>()
                            .eq(LinuxdoInviteCode::getId, code.getId())
                            .isNull(LinuxdoInviteCode::getEmail)
                            .set(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + token)
            );
            if (updated == 0) {
                throw new RuntimeException("系统繁忙，请稍后重试");
            }

            cacheService.set(TOKEN_KEY_PREFIX + token, email, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
            cacheService.set(pendingKey, token, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);

            scheduleReleaseIfStillPending(token);

            Thread.ofVirtual().start(() -> sendInviteEmailAsync(email, token));

            log.info("邀请码申请: email={}, token={}, codeId={}", email, token, code.getId());
        } finally {
            redisLockUtil.unlock(APPLY_LOCK_KEY, lockValue);
        }
    }

    @Override
    public String verify(String token) {
        String tokenKey = TOKEN_KEY_PREFIX + token;
        String email = cacheService.get(tokenKey);
        if (email == null) {
            throw new RuntimeException("链接已失效或无效，请重新申请");
        }

        LinuxdoInviteCode code = inviteCodeMapper.selectOne(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .eq(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + token)
        );
        if (code == null) {
            throw new RuntimeException("邀请码不存在，请重新申请");
        }

        int updated = inviteCodeMapper.update(
                new LambdaUpdateWrapper<LinuxdoInviteCode>()
                        .eq(LinuxdoInviteCode::getId, code.getId())
                        .eq(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + token)
                        .set(LinuxdoInviteCode::getEmail, email)
                        .set(LinuxdoInviteCode::getClaimedAt, LocalDateTime.now())
        );
        if (updated == 0) {
            throw new RuntimeException("领取失败，请重试");
        }

        cacheService.delete(tokenKey);
        cacheService.delete(PENDING_EMAIL_PREFIX + email);

        log.info("邀请码领取成功: email={}, code={}", email, code.getCode());
        return code.getCode();
    }

    /**
     * 虚拟线程睡眠31minutes后，如果该邀请码仍然处于pending状态，则将其释放
     */
    private void scheduleReleaseIfStillPending(String token) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(RELEASE_CHECK_INTERVAL_MINUTES));
                String pendingEmail = PENDING_DB_PREFIX + token;
                int released = inviteCodeMapper.update(
                        new LambdaUpdateWrapper<LinuxdoInviteCode>()
                                .eq(LinuxdoInviteCode::getEmail, pendingEmail)
                                .set(LinuxdoInviteCode::getEmail, null)
                );
                if (released > 0) {
                    log.info("延迟释放未领取pending邀请码: token={}", token);
                }
            } catch (Exception e) {
                log.error("延迟释放pending邀请码失败: token={}", token, e);
            }
        });
    }

    public void sendInviteEmailAsync(String email, String token) {
        String verifyUrl = frontUrl + "?token=" + token;
        String subject = "LinuxDo 邀请码领取";
        String content = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { margin: 0; padding: 0; background: #faf8f5; font-family: 'Noto Sans SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
                        @media only screen and (max-width: 520px) {
                            .card-wrap { padding: 20px 12px !important; }
                            .card-table { border-radius: 12px !important; }
                            .card-header { padding: 24px 24px !important; }
                            .card-body { padding: 28px 24px !important; }
                            .card-footer { padding: 16px 24px !important; }
                            .card-title { font-size: 20px !important; }
                            .btn { width: 100%% !important; padding: 14px 24px !important; }
                        }
                    </style>
                </head>
                <body>
                    <table class="card-wrap" width="100%%" cellpadding="0" cellspacing="0" style="background:#faf8f5;padding:48px 20px;">
                        <tr>
                            <td align="center">
                                <!--[if mso]><table width="460" cellpadding="0" cellspacing="0"><tr><td><![endif]-->
                                <table class="card-table" width="100%%" max-width="460" cellpadding="0" cellspacing="0" style="background:#fff;border:1px solid #e8e2d9;border-radius:16px;overflow:hidden;box-shadow:0 1px 3px rgba(44,36,22,0.04),0 8px 24px rgba(44,36,22,0.06);width:100%%;max-width:460px;">
                                    <tr>
                                        <td class="card-header" style="background:#d4622a;padding:32px 40px;text-align:center;">
                                            <h1 class="card-title" style="margin:0;color:#fff;font-size:22px;font-weight:600;letter-spacing:1px;">LinuxDo 邀请码</h1>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td class="card-body" style="padding:40px;text-align:center;">
                                            <p style="margin:0 0 10px;color:#2c2416;font-size:15px;line-height:1.7;">
                                                您好
                                            </p>
                                            <p style="margin:0 0 28px;color:#7a7060;font-size:14px;line-height:1.7;">
                                                点击下方按钮，领取你的邀请码
                                            </p>
                                            <div style="margin:24px 0;">
                                                <a class="btn" href="%s" style="display:inline-block;background:#d4622a;color:#fff;text-decoration:none;padding:14px 36px;border-radius:10px;font-size:15px;font-weight:500;max-width:240px;">
                                                    领取邀请码 →
                                                </a>
                                            </div>
                                            <p style="margin:24px 0 0;color:#b0a898;font-size:13px;line-height:1.6;">
                                                链接有效期 %d 分钟 · 如非本人操作请忽略此邮件
                                            </p>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td class="card-footer" style="background:#faf8f5;padding:20px 40px;border-top:1px solid #e8e2d9;">
                                            <p style="margin:0;color:#b0a898;font-size:12px;text-align:center;">
                                                mawai · linux.do
                                            </p>
                                        </td>
                                    </tr>
                                </table>
                                <!--[if mso]></td></tr></table><![endif]-->
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """.formatted(verifyUrl, TOKEN_EXPIRE_MINUTES);

        try {
            emailStrategy.send(email, subject, content);
            log.info("邀请邮件发送成功: email={}", email);
        } catch (Exception e) {
            log.error("邀请邮件发送失败: email={}", email, e);
        }
    }

    @Override
    public LinuxDoInviteRecordsVO getRecentRecords(int limit) {
        List<LinuxdoInviteCode> records = inviteCodeMapper.selectList(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .isNotNull(LinuxdoInviteCode::getClaimedAt)
                        .notLike(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + "%")
                        .orderByDesc(LinuxdoInviteCode::getClaimedAt)
                        .last("LIMIT " + Math.clamp(limit, 1, 100))
        );

        List<LinuxDoInviteRecordVO> list = records.stream()
                .map(record -> new LinuxDoInviteRecordVO(
                        maskIdentifier(record.getEmail()),
                        record.getClaimedAt()
                ))
                .collect(Collectors.toList());

        Long total = inviteCodeMapper.selectCount(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .isNotNull(LinuxdoInviteCode::getClaimedAt)
                        .notLike(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + "%")
        );

        return new LinuxDoInviteRecordsVO(list, total);
    }

    @Override
    @RateLimiter(type = RateLimiterType.INVITE_CLICK, permitsPerSecond = 60.00 / 60, bucketCapacity = 60, global = true)
    public void click() {
        cacheService.increment(CLICK_COUNT_KEY, 1);
    }

    @Override
    public long getClickCount() {
        String val = cacheService.get(CLICK_COUNT_KEY);
        return val != null ? Long.parseLong(val) : 0;
    }

    private String maskIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "***";
        }
        int atIndex = identifier.indexOf("@");
        if (atIndex < 0) {
            return "***";
        }
        String localPart = identifier.substring(0, atIndex);
        String domainPart = identifier.substring(atIndex);

        String maskedLocal;
        if (localPart.length() <= 3) {
            maskedLocal = localPart + "*".repeat(3 - localPart.length());
        } else {
            maskedLocal = localPart.substring(0, 3) + "***";
        }
        return maskedLocal + domainPart;
    }
}
