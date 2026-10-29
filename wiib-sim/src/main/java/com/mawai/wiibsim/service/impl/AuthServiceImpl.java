package com.mawai.wiibsim.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.i18n.RequestLang;
import com.mawai.wiibsim.config.GoogleConfig;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.config.OAuthClientConfig;
import com.mawai.wiibsim.dto.AuthModeDTO;
import com.mawai.wiibsim.dto.GoogleUserInfo;
import com.mawai.wiibsim.dto.LinuxDoUserInfo;
import com.mawai.wiibsim.mapper.InviteCodeMapper;
import com.mawai.wiibsim.service.AuthService;
import com.mawai.wiibsim.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 认证服务实现
 * LinuxDo、Google 走 OAuth 2.0 授权码登录，另有账号密码登录和管理员直登
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /** 用户名允许的字符（正则字符集）和最长字数：注册校验、Google 名字生成共用 */
    private static final String USERNAME_CHARS = "A-Za-z0-9_\\u4e00-\\u9fa5";
    private static final int USERNAME_MAX = 20;
    /** AI Trader 每局 sim 子账户的用户名前缀（wiib-agent TraderService 建号），真人不给 */
    private static final String TRADER_ACCOUNT_PREFIX = "ai_trader_";

    private final UserService userService;
    private final LinuxDoConfig linuxDoConfig;
    private final RestTemplate linuxDoRestTemplate;
    private final GoogleConfig googleConfig;
    private final RestTemplate googleRestTemplate;
    private final InviteCodeMapper inviteCodeMapper;
    /** 登录多半发生在未登录态，语言只能取自请求头（见 RequestLang） */
    private final MessageCatalog messages;

    public AuthServiceImpl(
            UserService userService,
            LinuxDoConfig linuxDoConfig,
            @Qualifier("linuxDoRestTemplate") RestTemplate linuxDoRestTemplate,
            GoogleConfig googleConfig,
            @Qualifier("googleRestTemplate") RestTemplate googleRestTemplate,
            InviteCodeMapper inviteCodeMapper,
            MessageCatalog messages
    )
    {
        this.userService = userService;
        this.linuxDoConfig = linuxDoConfig;
        this.linuxDoRestTemplate = linuxDoRestTemplate;
        this.googleConfig = googleConfig;
        this.googleRestTemplate = googleRestTemplate;
        this.inviteCodeMapper = inviteCodeMapper;
        this.messages = messages;
    }

    @Value("${trading.initial-balance:10000}")
    private BigDecimal initialBalance;

    @Value("${auth.password-login.enabled:false}")
    private boolean passwordLoginEnabled;

    @Value("${auth.password-login.admin-password:}")
    private String adminPassword;

    /**
     * 密码模式下管理员账号(id=1)的引导：邀请码只有管理员能生成，直登又被禁用，
     * id=1 没密码则没人能生成码、也就没人能注册。配置 admin-password 后启动时
     * 保证 id=1 存在（全新库自动建 admin；已有库 id=1 就是首个用户）并把密码同步上去。
     */
    @PostConstruct
    void ensureAdminPassword() {
        if (!passwordLoginEnabled || adminPassword.isBlank()) return;
        userService.ensureAdminUser();
        User admin = userService.getById(1L);
        if (admin.getPasswordHash() == null || !BCrypt.checkpw(adminPassword, admin.getPasswordHash())) {
            admin.setPasswordHash(BCrypt.hashpw(adminPassword));
            userService.updateById(admin);
            log.info("admin 密码已按配置同步");
        }
    }

    /**
     * 处理LinuxDo回调，完成登录
     * 流程：code换token -> token获取用户信息 -> 创建/更新用户 -> 登录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleLinuxDoCallback(String code) {
        try {
            // 1. 用code换取access_token
            String accessToken = exchangeToken(linuxDoConfig, linuxDoRestTemplate, code);

            // 2. 用token获取用户信息
            LinuxDoUserInfo userInfo = getUserInfo(linuxDoConfig, linuxDoRestTemplate, accessToken, LinuxDoUserInfo.class);
            if (userInfo.getId() == null) {
                throw new BizException(messages.get("auth.oauth.userIdEmpty"));
            }

            String linuxDoId = String.valueOf(userInfo.getId());
            String username = userInfo.getUsername();
            String avatar = userInfo.getAvatarUrl();

            // 3. 查找或创建用户
            User user = userService.findByLinuxDoId(linuxDoId);
            if (user == null) {
                // 首次登录，创建用户并赠送初始资金
                user = new User();
                user.setLinuxDoId(linuxDoId);
                user.setUsername(username);
                user.setAvatar(avatar);
                user.setBalance(initialBalance);
                // agent 语言初值取建号那一刻的界面语言，之后只在配置页改
                user.setLang(RequestLang.current().code());
                try {
                    userService.save(user);
                    // 建号走 INSERT，记账切面（只切 atomic* 资金方法）抓不到，必须显式补这一笔，
                    // 否则新用户一落库就是 SUM(delta)=0 而 balance=10000
                    userService.recordInitialGrant(user.getId(), initialBalance);
                } catch (DuplicateKeyException e) {
                    // 本意是"并发回调抢先创建，重查后继续登录"（那一笔 INITIAL_GRANT 由抢先者记了，不重复记）。
                    // 但在 PG 上这条恢复路径其实走不通：本方法带 @Transactional，save 撞唯一键后
                    // 整个事务已 aborted，紧跟的 findByLinuxDoId 会直接失败——要真恢复得先开 SAVEPOINT。
                    // 属既存问题，不在本次范围；无论走哪支都不会重复记账，故不动逻辑只把注释写准。
                    user = userService.findByLinuxDoId(linuxDoId);
                    if (user == null) throw e;
                }
                log.info("新用户注册: {} LinuxDoId={}", username, linuxDoId);
            } else {
                // 更新用户信息
                user.setUsername(username);
                user.setAvatar(avatar);
                try {
                    userService.updateById(user);
                } catch (DuplicateKeyException e) {
                    // LinuxDo 侧改名撞上本地注册用户名：跳过本次资料更新，保留旧名照常登录
                    log.warn("LinuxDo 用户名 {} 已被本地账号占用，保留旧资料 UserId={}", username, user.getId());
                }
            }

            // 4. 登录（Sa-Token）
            StpUtil.login(user.getId());
            String token = StpUtil.getTokenValue();

            log.info("用户登录成功: {} UserId={}", username, user.getId());

            return token;
        } catch (BizException e) {
            throw e;   // 内部已带具体错误码/文案，别降级成通用系统错误
        } catch (Exception e) {
            log.error("LinuxDo登录失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(),
                    messages.get("auth.loginFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
    }

    /**
     * 处理Google回调，完成登录。按 sub 认人；
     * 用户名只在建号时用 Google 名字生成一次，之后登录只同步头像
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleGoogleCallback(String code) {
        try {
            String accessToken = exchangeToken(googleConfig, googleRestTemplate, code);
            GoogleUserInfo userInfo = getUserInfo(googleConfig, googleRestTemplate, accessToken, GoogleUserInfo.class);
            if (StrUtil.isBlank(userInfo.getSub())) {
                throw new BizException(messages.get("auth.oauth.userIdEmpty"));
            }
            String googleId = userInfo.getSub();
            String picture = userInfo.getPicture();

            User user = userService.findByGoogleId(googleId);
            if (user == null) {
                // 首次登录，创建用户并赠送初始资金
                user = new User();
                user.setGoogleId(googleId);
                user.setUsername(googleUsername(userInfo.getName()));
                user.setAvatar(picture);
                user.setBalance(initialBalance);
                // 同 LinuxDo 首登：agent 语言初值取界面语言
                user.setLang(RequestLang.current().code());
                userService.save(user);
                // 同 LinuxDo 首登：建号是 INSERT，切面抓不到，初始资金得自己补记
                userService.recordInitialGrant(user.getId(), initialBalance);
                log.info("新用户注册: {} GoogleId={}", user.getUsername(), googleId);
            } else if (picture != null && !picture.equals(user.getAvatar())) {
                // 只更头像这一列，不整行写回
                userService.lambdaUpdate()
                        .eq(User::getId, user.getId())
                        .set(User::getAvatar, picture)
                        .update();
            }

            StpUtil.login(user.getId());
            log.info("用户登录成功: {} UserId={}", user.getUsername(), user.getId());
            return StpUtil.getTokenValue();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google登录失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(),
                    messages.get("auth.loginFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
    }

    /**
     * Google 名字转站内用户名：撞名或不到 2 个字，就截短后补 _4位随机数（补完不超上限）
     */
    String googleUsername(String name) {
        String base = sanitizeUsername(name);
        String username = base;
        while (username.length() < 2 || userService.findByUsername(username) != null) {
            username = StrUtil.sub(base, 0, USERNAME_MAX - 5) + "_" + RandomUtil.randomNumbers(4);
        }
        return username;
    }

    /**
     * 名字洗成注册规则允许的字符：é 这类先拆掉音标，只留字母、数字、下划线、中文，空格变下划线，截到上限；
     * 洗完是空的、或者撞上 AI Trader 子账户的前缀，就叫 user
     */
    static String sanitizeUsername(String name) {
        String s = Normalizer.normalize(StrUtil.nullToEmpty(name), Normalizer.Form.NFKD)
                .replaceAll("[^" + USERNAME_CHARS + "\\s]", "")
                .trim()
                .replaceAll("\\s+", "_");
        s = StrUtil.sub(s, 0, USERNAME_MAX);
        return s.isEmpty() || s.startsWith(TRADER_ACCOUNT_PREFIX) ? "user" : s;
    }

    /**
     * 获取当前登录用户信息
     */
    @Override
    public UserDTO getCurrentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userService.getUserPortfolio(userId);
    }

    /**
     * 退出登录
     */
    @Override
    public void logout() {
        StpUtil.logout();
        log.info("用户退出登录");
    }

    @Override
    public AuthModeDTO mode() {
        return new AuthModeDTO(linuxDoConfig.buildAuthorizeUrl(), googleConfig.buildAuthorizeUrl(), passwordLoginEnabled);
    }

    /**
     * 仅管理员直登：LinuxDo、Google、密码登录都没开时，任何人点"进入"即以 admin(id=1) 登录
     * 开了任一正式登录方式则拒绝，避免公网部署被绕过成 admin
     */
    @Override
    public String localLogin() {
        if (linuxDoConfig.isEnabled() || googleConfig.isEnabled() || passwordLoginEnabled) {
            throw new BizException(messages.get("auth.localLoginDisabled"));
        }
        userService.ensureAdminUser();   // 幂等，保证 id=1 存在
        StpUtil.login(1L);
        String token = StpUtil.getTokenValue();
        log.info("管理员直登成功 UserId=1");
        return token;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String register(String username, String password, String inviteCode) {
        if (!passwordLoginEnabled) {
            throw new BizException(messages.get("auth.passwordLoginDisabled"));
        }
        String name = username == null ? "" : username.trim();
        if (!name.matches("[" + USERNAME_CHARS + "]{2," + USERNAME_MAX + "}")) {
            throw new BizException(messages.get("auth.usernameFormat"));
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BizException(messages.get("auth.passwordLength"));
        }
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BizException(messages.get("auth.inviteCodeRequired"));
        }
        // 原子扣码：无效/停用/次数用完都返回 null；后面用户名冲突时事务回滚，次数不白扣
        Long codeId = inviteCodeMapper.consume(inviteCode.trim());
        if (codeId == null) {
            throw new BizException(messages.get("auth.inviteCodeInvalid"));
        }
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(BCrypt.hashpw(password));
        user.setInviteCodeId(codeId);
        user.setBalance(initialBalance);
        // 同 OAuth 首登：agent 语言初值取界面语言
        user.setLang(RequestLang.current().code());
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(messages.get("auth.usernameTaken"));
        }
        // 同 OAuth 首登：建号是 INSERT，切面抓不到，初始资金得自己补记
        userService.recordInitialGrant(user.getId(), initialBalance);
        StpUtil.login(user.getId());
        log.info("邀请码注册成功: {} UserId={} inviteCodeId={}", name, user.getId(), codeId);
        return StpUtil.getTokenValue();
    }

    @Override
    public String passwordLogin(String username, String password) {
        if (!passwordLoginEnabled) {
            throw new BizException(messages.get("auth.passwordLoginDisabled"));
        }
        User user = username == null ? null : userService.findByUsername(username.trim());
        // 统一文案，不暴露"账号存在但密码错"；OAuth 用户 password_hash 为空，同样拒绝
        if (user == null || user.getPasswordHash() == null
                || password == null || !BCrypt.checkpw(password, user.getPasswordHash())) {
            throw new BizException(messages.get("auth.badCredentials"));
        }
        StpUtil.login(user.getId());
        log.info("密码登录成功: {} UserId={}", user.getUsername(), user.getId());
        return StpUtil.getTokenValue();
    }

    /**
     * 用授权码换取access_token
     */
    private String exchangeToken(OAuthClientConfig provider, RestTemplate restTemplate, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", provider.getClientId());
        form.add("client_secret", provider.getClientSecret());
        form.add("redirect_uri", provider.getRedirectUri());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        String response;
        try {
            response = restTemplate.postForObject(provider.getTokenUrl(), entity, String.class);
        } catch (RestClientResponseException e) {
            throw new BizException(messages.get("auth.oauth.tokenFailed", Map.of("reason", e.getResponseBodyAsString())));
        }

        if (response == null) {
            throw new BizException(messages.get("auth.oauth.tokenEmpty"));
        }

        String accessToken = MAPPER.readTree(response).path("access_token").asString(null);
        if (accessToken == null) {
            throw new BizException(messages.get("auth.oauth.tokenFailed", Map.of("reason", response)));
        }

        return accessToken;
    }

    /**
     * 用access_token获取用户信息
     */
    private <T> T getUserInfo(OAuthClientConfig provider, RestTemplate restTemplate, String accessToken, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        T userInfo;
        try {
            userInfo = restTemplate.exchange(
                    provider.getUserUrl(),
                    HttpMethod.GET,
                    entity,
                    type
            ).getBody();
        } catch (RestClientResponseException e) {
            throw new BizException(messages.get("auth.oauth.userInfoFailed", Map.of("reason", e.getResponseBodyAsString())));
        } catch (RestClientException e) {
            throw new BizException(messages.get("auth.oauth.userInfoParseFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }

        if (userInfo == null) {
            throw new BizException(messages.get("auth.oauth.userInfoEmpty"));
        }
        return userInfo;
    }
}
