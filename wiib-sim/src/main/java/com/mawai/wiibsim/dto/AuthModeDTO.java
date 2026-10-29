package com.mawai.wiibsim.dto;

/**
 * 登录模式：前端据此决定展示哪些登录入口。
 * 两个授权地址为 null=没开这种登录，有值时前端拼上 state 跳过去；
 * passwordLoginEnabled=账号密码+邀请码注册；三种都关才展示管理员直登。
 */
public record AuthModeDTO(String linuxDoAuthorizeUrl, String googleAuthorizeUrl, boolean passwordLoginEnabled) {
}
