package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.campaign.LdcProperties;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import com.mawai.wiibsim.campaign.ldc.LdcClient;
import com.mawai.wiibsim.campaign.ldc.LdcResult;
import com.mawai.wiibsim.campaign.mapper.CampaignRewardMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.dto.LinuxDoUserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 领取：二次 LinuxDo 授权 → 拿最新身份 → 调分发接口。
 * <p>
 * 二次授权为了拿 LinuxDo 侧最新 username（库里可能是保留的旧名，分发接口按 username 校验）；
 * linux_do_id 从不变，核对它把"授权错账号"变成明确报错。
 * 换 token 的三十行不复用 AuthServiceImpl，为了活动整包可删。
 * <p>
 * <b>本类刻意没有 @Transactional</b>，见 {@link #claim} 注释。
 */
@Slf4j
@Service
public class CampaignClaimService {

    private final CampaignService campaignService;
    private final CampaignRewardMapper rewardMapper;
    private final CampaignStatsMapper statsMapper;
    private final LinuxDoConfig linuxDoConfig;
    private final RestTemplate linuxDoRestTemplate;
    private final LdcClient ldcClient;
    private final LdcProperties ldcProperties;
    /** 领奖各步的拦阻文案跟界面语言 */
    private final MessageCatalog messages;

    public CampaignClaimService(CampaignService campaignService,
                                CampaignRewardMapper rewardMapper,
                                CampaignStatsMapper statsMapper,
                                LinuxDoConfig linuxDoConfig,
                                @Qualifier("linuxDoRestTemplate") RestTemplate linuxDoRestTemplate,
                                LdcClient ldcClient,
                                LdcProperties ldcProperties,
                                MessageCatalog messages) {
        this.campaignService = campaignService;
        this.rewardMapper = rewardMapper;
        this.statsMapper = statsMapper;
        this.linuxDoConfig = linuxDoConfig;
        this.linuxDoRestTemplate = linuxDoRestTemplate;
        this.ldcClient = ldcClient;
        this.ldcProperties = ldcProperties;
        this.messages = messages;
    }

    /** 我的奖励行；未结算或不在名单里返回 null */
    public CampaignReward myReward(Long userId) {
        Campaign c = campaignService.current();
        return c == null ? null : rewardMapper.selectMine(c.getId(), userId);
    }

    /**
     * 领取。code 是 /login 页拿到的 OAuth 授权码。
     * <p>
     * 三重防重复：UNIQUE(campaign_id,user_id) + casClaim 的状态检查 + out_trade_no 服务端幂等。
     * 先 CAS 再发钱：只有 CAS 挡得住同一人双击并发。
     * <p>
     * <b>整个方法刻意不加 @Transactional</b>：casClaim 抢锁要即时对别的连接可见，
     * markFailed 不能被异常回滚；三次写各自提交，一致性靠 CAS 状态机。
     * <p>
     * {@link LdcClient#distribute} 最坏 ~2 分钟，虚拟线程下同步等即可；
     * 超时后重领会重发同一 out_trade_no，撞唯一索引即判上次已成功。
     */
    public CampaignReward claim(Long userId, String code) {
        if (!ldcProperties.ready()) throw new BizException(messages.get("campaign.claim.disabled"));

        // 用 current() 不用 requireRunning()：领取发生在活动结束之后，requireRunning() 那时必抛
        Campaign c = campaignService.current();
        if (c == null || !Campaign.STATUS_SETTLING.equals(c.getStatus())) {
            throw new BizException(messages.get("campaign.claim.notSettled"));
        }

        CampaignReward reward = rewardMapper.selectMine(c.getId(), userId);
        if (reward == null) throw new BizException(messages.get("campaign.claim.nothingToClaim"));
        if (CampaignReward.SUCCESS.equals(reward.getStatus())) throw new BizException(messages.get("campaign.claim.alreadyClaimed"));
        // CLAIMED 无超时无自愈：发放中途进程重启会永远停在这里，需人工重置 FAILED（SQL 见 CampaignReward.status 注释）
        if (CampaignReward.CLAIMED.equals(reward.getStatus())) throw new BizException(messages.get("campaign.claim.inProgress"));
        if (reward.getCreatedAt().plusDays(ldcProperties.getClaimDays()).isBefore(LocalDateTime.now())) {
            throw new BizException(messages.get("campaign.claim.windowClosed"));
        }

        // 身份核对必须在 CAS 与发放之前：授权错号在这里抛，状态不动，换号重新授权即可
        LinuxDoUserInfo info = fetchUserInfo(code);
        String authorizedId = String.valueOf(info.getId());

        String bound = statsMapper.selectLinuxDoId(userId);
        if (bound == null || !bound.equals(authorizedId)) {
            throw new BizException(messages.get("campaign.claim.accountMismatch"));
        }

        if (rewardMapper.casClaim(reward.getId(), authorizedId, info.getUsername()) == 0) {
            throw new BizException(messages.get("campaign.claim.stateChanged"));
        }

        // 兜住 distribute 的运行时异常（如配置拼不成 URI），否则这行永远停在 CLAIMED；
        // 落 FAILED 安全：单号不变，重领撞唯一索引即判 SUCCESS
        LdcResult result;
        try {
            result = ldcClient.distribute(
                    authorizedId, info.getUsername(), reward.getLdcAmount(), reward.getOutTradeNo());
        } catch (RuntimeException e) {
            rewardMapper.markFailed(reward.getId(), "发放异常：" + e);
            log.error("活动奖励发放异常 userId={} out_trade_no={}", userId, reward.getOutTradeNo(), e);
            throw new BizException(messages.get("campaign.claim.payoutError"));
        }

        if (result.success()) {
            // tradeNo 为 null 是"命中单号幂等、此前已发放成功"（LdcResult.alreadySent），
            // 照样算领到了 —— 钱确实在对面账上，只是这次没拿到新流水号
            rewardMapper.markSuccess(reward.getId(), result.tradeNo());
            log.info("活动奖励发放成功 userId={} amount={} trade_no={}",
                    userId, reward.getLdcAmount(), result.tradeNo());
        } else {
            rewardMapper.markFailed(reward.getId(), result.errorMsg());
            log.warn("活动奖励发放失败 userId={} : {}", userId, result.errorMsg());
            throw new BizException(messages.get("campaign.claim.payoutFailed", Map.of("reason", String.valueOf(result.errorMsg()))));
        }
        return rewardMapper.selectMine(c.getId(), userId);
    }

    /** code 换 token 再拉用户信息。写法照 AuthServiceImpl:253-318，只是不建号不登录 */
    private LinuxDoUserInfo fetchUserInfo(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", linuxDoConfig.getClientId());
        form.add("client_secret", linuxDoConfig.getClientSecret());
        form.add("redirect_uri", linuxDoConfig.getRedirectUri());

        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String tokenResp;
        try {
            tokenResp = linuxDoRestTemplate.postForObject(
                    linuxDoConfig.getTokenUrl(), new HttpEntity<>(form, tokenHeaders), String.class);
        } catch (RestClientException e) {
            throw new BizException(messages.get("campaign.claim.authFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
        String accessToken = MAPPER.readTree(tokenResp == null ? "{}" : tokenResp).path("access_token").asString(null);
        if (accessToken == null) throw new BizException(messages.get("campaign.claim.authNoToken"));

        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.set("Authorization", "Bearer " + accessToken);
        LinuxDoUserInfo info;
        try {
            info = linuxDoRestTemplate.exchange(linuxDoConfig.getUserUrl(), HttpMethod.GET,
                    new HttpEntity<>(userHeaders), LinuxDoUserInfo.class).getBody();
        } catch (RestClientException e) {
            throw new BizException(messages.get("campaign.claim.userInfoFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
        if (info == null || info.getId() == null) throw new BizException(messages.get("campaign.claim.userInfoEmpty"));
        return info;
    }
}
