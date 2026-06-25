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
     * 获取当前登录用户信息
     */
    UserDTO getCurrentUser();

    /**
     * 退出登录
     */
    void logout();
}
