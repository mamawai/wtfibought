package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证Controller
 */
@Tag(name = "认证接口")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * LinuxDo回调处理
     */
    @GetMapping("/callback/linuxdo")
    @Operation(summary = "LinuxDo OAuth回调")
    public Result<String> linuxDoCallback(@RequestParam String code) {
        String token = authService.handleLinuxDoCallback(code);
        return Result.ok(token);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前用户信息")
    public Result<UserDTO> getCurrentUser() {
        UserDTO user = authService.getCurrentUser();
        return Result.ok(user);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }
}
