package com.mawai.wiibsim.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.dto.VideoPokerBetRequest;
import com.mawai.wiibcommon.dto.VideoPokerDrawRequest;
import com.mawai.wiibcommon.dto.VideoPokerGameStateDTO;
import com.mawai.wiibcommon.dto.VideoPokerStatusDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.VideoPokerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;

@Tag(name = "视频扑克接口")
@RestController
@RequestMapping("/api/videopoker")
@RequiredArgsConstructor
public class VideoPokerController {

    private final VideoPokerService videoPokerService;

    @GetMapping("/status")
    @Operation(summary = "获取游戏状态")
    public Result<VideoPokerStatusDTO> getStatus() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(videoPokerService.getStatus(userId));
    }

    @PostMapping("/bet")
    @Operation(summary = "下注发牌")
    public Result<VideoPokerGameStateDTO> bet(@RequestBody VideoPokerBetRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        BigDecimal amount = request != null && request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO;
        return Result.ok(videoPokerService.bet(userId, amount));
    }

    @PostMapping("/draw")
    @Operation(summary = "选择HOLD并换牌")
    public Result<VideoPokerGameStateDTO> draw(@RequestBody VideoPokerDrawRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        var held = request != null && request.getHeld() != null ? request.getHeld() : Collections.<Integer>emptyList();
        return Result.ok(videoPokerService.draw(userId, held));
    }
}
