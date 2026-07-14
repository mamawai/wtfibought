package com.mawai.wiibsim.dto;

/**
 * 登录模式：linuxDoEnabled=true 前端走 LinuxDo OAuth，false 走管理员直登
 */
public record AuthModeDTO(boolean linuxDoEnabled) {
}
