package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.UserDTO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 处理LinuxDo回调，完成登录
     * @return 登录Token
     */
    String handleLinuxDoCallback(String code);

    /**
     * LinuxDo OAuth 是否启用——前端据此决定展示 OAuth 登录还是管理员直登
     */
    boolean isLinuxDoEnabled();

    /**
     * 仅管理员直登：确保 admin(id=1) 存在并登录，返回 Token
     */
    String localLogin();

    /**
     * 获取当前登录用户信息
     */
    UserDTO getCurrentUser();

    /**
     * 退出登录
     */
    void logout();
}
