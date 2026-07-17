package com.mawai.wiibsim.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.LinuxDoConfig;
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

/**
 * 认证服务实现
 * LinuxDo OAuth 2.0登录流程
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final LinuxDoConfig linuxDoConfig;
    private final RestTemplate linuxDoRestTemplate;
    private final InviteCodeMapper inviteCodeMapper;

    public AuthServiceImpl(
            UserService userService,
            LinuxDoConfig linuxDoConfig,
            @Qualifier("linuxDoRestTemplate") RestTemplate linuxDoRestTemplate,
            InviteCodeMapper inviteCodeMapper
    )
    {
        this.userService = userService;
        this.linuxDoConfig = linuxDoConfig;
        this.linuxDoRestTemplate = linuxDoRestTemplate;
        this.inviteCodeMapper = inviteCodeMapper;
    }

    @Value("${trading.initial-balance:100000}")
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
            String accessToken = exchangeToken(code);

            // 2. 用token获取用户信息
            LinuxDoUserInfo userInfo = getUserInfo(accessToken);

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
                try {
                    userService.save(user);
                } catch (DuplicateKeyException e) {
                    // 并发回调抢先创建，重查后继续登录
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
            throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(), "登录失败: " + e.getMessage());
        }
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
    public boolean isLinuxDoEnabled() {
        return linuxDoConfig.isEnabled();
    }

    /**
     * 仅管理员直登：LinuxDo 和密码登录都没开时，任何人点"进入"即以 admin(id=1) 登录
     * 开了任一正式登录方式则拒绝，避免公网部署被绕过成 admin
     */
    @Override
    public String localLogin() {
        if (linuxDoConfig.isEnabled() || passwordLoginEnabled) {
            throw new BizException("已启用正式登录方式，管理员直登不可用");
        }
        userService.ensureAdminUser();   // 幂等，保证 id=1 存在
        StpUtil.login(1L);
        String token = StpUtil.getTokenValue();
        log.info("管理员直登成功 UserId=1");
        return token;
    }

    @Override
    public boolean isPasswordLoginEnabled() {
        return passwordLoginEnabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String register(String username, String password, String inviteCode) {
        if (!passwordLoginEnabled) {
            throw new BizException("账号密码登录未启用");
        }
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}")) {
            throw new BizException("用户名需为2-20位字母/数字/下划线/中文");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BizException("密码长度需为6-64位");
        }
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BizException("邀请码不能为空");
        }
        // 原子扣码：无效/停用/次数用完都返回 null；后面用户名冲突时事务回滚，次数不白扣
        Long codeId = inviteCodeMapper.consume(inviteCode.trim());
        if (codeId == null) {
            throw new BizException("邀请码无效或已用完");
        }
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(BCrypt.hashpw(password));
        user.setInviteCodeId(codeId);
        user.setBalance(initialBalance);
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            throw new BizException("用户名已存在");
        }
        StpUtil.login(user.getId());
        log.info("邀请码注册成功: {} UserId={} inviteCodeId={}", name, user.getId(), codeId);
        return StpUtil.getTokenValue();
    }

    @Override
    public String passwordLogin(String username, String password) {
        if (!passwordLoginEnabled) {
            throw new BizException("账号密码登录未启用");
        }
        User user = username == null ? null : userService.findByUsername(username.trim());
        // 统一文案，不暴露"账号存在但密码错"；OAuth 用户 password_hash 为空，同样拒绝
        if (user == null || user.getPasswordHash() == null
                || password == null || !BCrypt.checkpw(password, user.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        StpUtil.login(user.getId());
        log.info("密码登录成功: {} UserId={}", user.getUsername(), user.getId());
        return StpUtil.getTokenValue();
    }

    /**
     * 用授权码换取access_token
     */
    private String exchangeToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", linuxDoConfig.getClientId());
        form.add("client_secret", linuxDoConfig.getClientSecret());
        form.add("redirect_uri", linuxDoConfig.getRedirectUri());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        String response;
        try {
            response = linuxDoRestTemplate.postForObject(linuxDoConfig.getTokenUrl(), entity, String.class);
        } catch (RestClientResponseException e) {
            throw new BizException("获取access_token失败: " + e.getResponseBodyAsString());
        }

        if (response == null) {
            throw new BizException("获取access_token失败: 空响应");
        }

        JSONObject json = JSONUtil.parseObj(response);
        if (json.getStr("access_token") == null) {
            throw new BizException("获取access_token失败: " + response);
        }

        return json.getStr("access_token");
    }

    /**
     * 用access_token获取用户信息
     */
    private LinuxDoUserInfo getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        LinuxDoUserInfo userInfo;
        try {
            userInfo = linuxDoRestTemplate.exchange(
                    linuxDoConfig.getUserUrl(),
                    HttpMethod.GET,
                    entity,
                    LinuxDoUserInfo.class
            ).getBody();
        } catch (RestClientResponseException e) {
            throw new BizException("获取用户信息失败: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new BizException("解析用户信息失败: " + e.getMessage());
        }

        if (userInfo == null) {
            throw new BizException("获取用户信息失败: 空响应");
        }

        if (userInfo.getId() == null) {
            throw new BizException("获取用户信息失败: id为空");
        }
        return userInfo;
    }
}
