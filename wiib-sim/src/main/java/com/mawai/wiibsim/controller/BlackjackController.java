package com.mawai.wiibsim.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.dto.BlackjackBetRequest;
import com.mawai.wiibcommon.dto.BlackjackConvertRequest;
import com.mawai.wiibcommon.dto.BlackjackStatusDTO;
import com.mawai.wiibcommon.dto.ConvertResultDTO;
import com.mawai.wiibcommon.dto.GameStateDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.BlackjackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Blackjack接口")
@RestController
@RequestMapping("/api/blackjack")
@RequiredArgsConstructor
public class BlackjackController {

    private final BlackjackService blackjackService;

    @GetMapping("/status")
    @Operation(summary = "获取积分状态")
    public Result<BlackjackStatusDTO> getStatus() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.getStatus(userId));
    }

    @PostMapping("/bet")
    @Operation(summary = "下注开局")
    public Result<GameStateDTO> bet(@RequestBody BlackjackBetRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        long amount = request != null && request.getAmount() != null ? request.getAmount() : 0L;
        return Result.ok(blackjackService.bet(userId, amount));
    }

    @PostMapping("/hit")
    @Operation(summary = "要牌")
    public Result<GameStateDTO> hit() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.hit(userId));
    }

    @PostMapping("/stand")
    @Operation(summary = "停牌")
    public Result<GameStateDTO> stand() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.stand(userId));
    }

    @PostMapping("/double")
    @Operation(summary = "加倍")
    public Result<GameStateDTO> doubleDown() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.doubleDown(userId));
    }

    @PostMapping("/split")
    @Operation(summary = "分牌")
    public Result<GameStateDTO> split() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.split(userId));
    }

    @PostMapping("/insurance")
    @Operation(summary = "买保险")
    public Result<GameStateDTO> insurance() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.insurance(userId));
    }

    @PostMapping("/forfeit")
    @Operation(summary = "放弃牌局")
    public Result<GameStateDTO> forfeit() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(blackjackService.forfeit(userId));
    }

    @PostMapping("/convert")
    @Operation(summary = "积分转出为资金")
    public Result<ConvertResultDTO> convert(@RequestBody BlackjackConvertRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        long amount = request != null && request.getAmount() != null ? request.getAmount() : 0L;
        return Result.ok(blackjackService.convert(userId, amount));
    }
}
