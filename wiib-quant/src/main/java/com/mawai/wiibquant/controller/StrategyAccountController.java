package com.mawai.wiibquant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.strategy.monitor.StrategyAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 三策略模拟盘账户监控 + 受控平仓。
 *
 * <p>前缀挂 /api/ai 下复用既有网关分流（nginx 零改动）。查看对所有登录用户开放；
 * 平仓是干预性写操作，仅 userId=1（平台所有者）可执行——前端同步只对 id=1 渲染按钮，
 * 此处校验是真正的门，前端隐藏只是体验。</p>
 */
@Slf4j
@Tag(name = "策略账户监控")
@RestController
@RequestMapping("/api/ai/strategies")
@RequiredArgsConstructor
public class StrategyAccountController {

    private final StrategyAccountService strategyAccountService;

    @Data
    public static class ClosePositionRequest {
        private Long positionId;
    }

    @GetMapping("/overview")
    @Operation(summary = "三策略账户全景（余额/权益/盈亏/持仓/已平仓历史）")
    public Result<List<StrategyAccountService.StrategyAccountView>> overview() {
        StpUtil.checkLogin();
        return Result.ok(strategyAccountService.overview());
    }

    @PostMapping("/{strategyId}/close")
    @Operation(summary = "手动整仓市价平（仅 userId=1）")
    @RequireAdmin // 平仓是干预性写操作，仅管理员可执行
    public Result<Void> close(@PathVariable String strategyId, @RequestBody ClosePositionRequest request) {
        if (request == null || request.getPositionId() == null) {
            return Result.fail("positionId 不能为空");
        }
        try {
            strategyAccountService.closePosition(strategyId, request.getPositionId());
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[StrategyAccount] 平仓失败 strategyId={} posId={}", strategyId, request.getPositionId(), e);
            return Result.fail("平仓失败：" + e.getMessage());
        }
    }
}
