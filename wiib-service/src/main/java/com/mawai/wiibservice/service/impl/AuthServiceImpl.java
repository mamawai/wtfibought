package com.mawai.wiibservice.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.config.LinuxDoConfig;
import com.mawai.wiibservice.dto.LinuxDoUserInfo;
import com.mawai.wiibservice.service.AuthService;
import com.mawai.wiibservice.service.UserService;
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

    public AuthServiceImpl(
            UserService userService,
            LinuxDoConfig linuxDoConfig,
            @Qualifier("linuxDoRestTemplate") RestTemplate linuxDoRestTemplate
    )
    {
        this.userService = userService;
        this.linuxDoConfig = linuxDoConfig;
        this.linuxDoRestTemplate = linuxDoRestTemplate;
    }

    @Value("${trading.initial-balance:100000}")
    private BigDecimal initialBalance;

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
                userService.updateById(user);
            }

            // 4. 登录（Sa-Token）
            StpUtil.login(user.getId());
            String token = StpUtil.getTokenValue();

            log.info("用户登录成功: {} UserId={}", username, user.getId());

            return token;
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
