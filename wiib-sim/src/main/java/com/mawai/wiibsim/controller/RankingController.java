package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.RankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "排行榜接口")
@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;

    @GetMapping
    @Operation(summary = "获取排行榜")
    public Result<List<RankingDTO>> getRanking() {
        return Result.ok(rankingService.getRanking());
    }
}
