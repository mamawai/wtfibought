package com.mawai.wiibsim.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.dto.MinesBetRequest;
import com.mawai.wiibcommon.dto.MinesGameStateDTO;
import com.mawai.wiibcommon.dto.MinesRevealRequest;
import com.mawai.wiibcommon.dto.MinesStatusDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.MinesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "矿工游戏接口")
@RestController
@RequestMapping("/api/mines")
@RequiredArgsConstructor
public class MinesController {

    private final MinesService minesService;

    @GetMapping("/status")
    @Operation(summary = "获取游戏状态")
    public Result<MinesStatusDTO> getStatus() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(minesService.getStatus(userId));
    }

    @PostMapping("/bet")
    @Operation(summary = "下注开局")
    public Result<MinesGameStateDTO> bet(@RequestBody MinesBetRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        BigDecimal amount = request != null && request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO;
        return Result.ok(minesService.bet(userId, amount));
    }

    @PostMapping("/reveal")
    @Operation(summary = "翻开格子")
    public Result<MinesGameStateDTO> reveal(@RequestBody MinesRevealRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        int cell = request != null && request.getCell() != null ? request.getCell() : -1;
        return Result.ok(minesService.reveal(userId, cell));
    }

    @PostMapping("/cashout")
    @Operation(summary = "提现")
    public Result<MinesGameStateDTO> cashout() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(minesService.cashout(userId));
    }
}
