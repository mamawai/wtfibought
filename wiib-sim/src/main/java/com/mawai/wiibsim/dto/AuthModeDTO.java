package com.mawai.wiibsim.dto;

/**
 * 登录模式开关：前端据此决定展示哪些登录入口。
 * passwordLoginEnabled=账号密码+邀请码注册；两者都关才展示管理员直登。
 */
public record AuthModeDTO(boolean linuxDoEnabled, boolean passwordLoginEnabled) {
}
