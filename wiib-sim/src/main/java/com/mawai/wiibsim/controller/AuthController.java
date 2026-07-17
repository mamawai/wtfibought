package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.AuthModeDTO;
import com.mawai.wiibsim.dto.PasswordLoginRequest;
import com.mawai.wiibsim.dto.RegisterRequest;
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
     * 登录模式：前端据此决定展示 LinuxDo 登录还是管理员直登
     */
    @GetMapping("/mode")
    @Operation(summary = "获取登录模式")
    public Result<AuthModeDTO> mode() {
        return Result.ok(new AuthModeDTO(authService.isLinuxDoEnabled(), authService.isPasswordLoginEnabled()));
    }

    /**
     * 邀请码注册（注册成功即登录）
     */
    @PostMapping("/register")
    @Operation(summary = "邀请码注册")
    public Result<String> register(@RequestBody RegisterRequest request) {
        String token = authService.register(request.username(), request.password(), request.inviteCode());
        return Result.ok(token);
    }

    /**
     * 账号密码登录
     */
    @PostMapping("/login/password")
    @Operation(summary = "账号密码登录")
    public Result<String> passwordLogin(@RequestBody PasswordLoginRequest request) {
        String token = authService.passwordLogin(request.username(), request.password());
        return Result.ok(token);
    }

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
     * 管理员直登（仅未配置 LinuxDo 时可用）
     */
    @PostMapping("/login/local")
    @Operation(summary = "管理员直登")
    public Result<String> localLogin() {
        String token = authService.localLogin();
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
