package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibsim.dto.AuthModeDTO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 登录模式：开了哪些登录方式，OAuth 的带上授权地址
     */
    AuthModeDTO mode();

    /**
     * 处理LinuxDo回调，完成登录
     * @return 登录Token
     */
    String handleLinuxDoCallback(String code);

    /**
     * 处理Google回调，完成登录
     * @return 登录Token
     */
    String handleGoogleCallback(String code);

    /**
     * 仅管理员直登：确保 admin(id=1) 存在并登录，返回 Token
     */
    String localLogin();

    /**
     * 邀请码注册：校验格式 → 原子扣码 → 建用户（赠初始资金）→ 注册即登录
     * @return 登录Token
     */
    String register(String username, String password, String inviteCode);

    /**
     * 账号密码登录
     * @return 登录Token
     */
    String passwordLogin(String username, String password);

    /**
     * 获取当前登录用户信息
     */
    UserDTO getCurrentUser();

    /**
     * 退出登录
     */
    void logout();
}
