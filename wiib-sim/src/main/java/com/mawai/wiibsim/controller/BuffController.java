package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.BuffStatusDTO;
import com.mawai.wiibcommon.dto.UserBuffDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.BuffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "每日Buff接口")
@RestController
@RequestMapping("/api/buff")
@RequiredArgsConstructor
public class BuffController {

    private final BuffService buffService;

    @GetMapping("/status")
    @Operation(summary = "获取Buff状态")
    public Result<BuffStatusDTO> getStatus(@CurrentUserId Long userId) {
        return Result.ok(buffService.getStatus(userId));
    }

    @PostMapping("/draw")
    @Operation(summary = "抽奖")
    public Result<UserBuffDTO> draw(@CurrentUserId Long userId) {
        return Result.ok(buffService.draw(userId));
    }
}
