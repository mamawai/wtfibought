package com.mawai.wiibservice.invitecode.controller;

import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.invitecode.dto.LinuxDoInviteRecordsVO;
import com.mawai.wiibservice.invitecode.service.LinuxDoInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/invite")
@RequiredArgsConstructor
@Tag(name = "邀请码接口")
public class InviteCodeController {

    private final LinuxDoInviteService linuxDoInviteService;

    @Operation(summary = "邀请码库存状态")
    @GetMapping("/status")
    public Result<Boolean> status() {
        return Result.ok(linuxDoInviteService.hasStock());
    }

    @Operation(summary = "申请邀请码")
    @PostMapping("/apply")
    public Result<Boolean> apply(@RequestParam String email) {
        try {
            linuxDoInviteService.apply(email);
            return Result.ok(true);
        } catch (Exception e) {
            log.error("邀请码申请失败: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "验证领取邀请码")
    @GetMapping("/verify")
    public Result<String> verify(@RequestParam String token) {
        try {
            return Result.ok(linuxDoInviteService.verify(token));
        } catch (Exception e) {
            log.error("邀请码验证失败: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "最近领取记录")
    @GetMapping("/records")
    public Result<LinuxDoInviteRecordsVO> records(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(linuxDoInviteService.getRecentRecords(limit));
    }

    @Operation(summary = "点击领取需求")
    @PostMapping("/click")
    public Result<Void> click() {
        linuxDoInviteService.click();
        return Result.ok();
    }

    @Operation(summary = "获取点击数")
    @GetMapping("/clicks")
    public Result<Long> getClicks() {
        return Result.ok(linuxDoInviteService.getClickCount());
    }
}
