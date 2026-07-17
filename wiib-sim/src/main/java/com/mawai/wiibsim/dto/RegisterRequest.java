package com.mawai.wiibsim.dto;

/** 邀请码注册请求 */
public record RegisterRequest(String username, String password, String inviteCode) {
}
