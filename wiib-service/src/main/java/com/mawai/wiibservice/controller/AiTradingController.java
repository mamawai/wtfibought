package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.mapper.AiTradingDecisionMapper;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.FuturesTradingService;
import com.mawai.wiibservice.task.AiTradingScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Tag(name = "AI交易员面板")
@RestController
@RequestMapping("/api/ai/trading")
@RequiredArgsConstructor
public class AiTradingController {

    private final AiTradingScheduler scheduler;
    private final UserMapper userMapper;
    private final AiTradingDecisionMapper decisionMapper;
    private final FuturesTradingService futuresTradingService;
    private final FuturesOrderMapper futuresOrderMapper;

    @GetMapping("/dashboard")
    @Operation(summary = "AI交易员概览")
    public Result<Map<String, Object>> dashboard() {
        StpUtil.checkLogin();
        Long aiId = scheduler.getAiUserId();
        if (aiId == null || aiId == 0) return Result.fail("AI交易员未初始化");

        User user = userMapper.selectById(aiId);
        if (user == null) return Result.fail("AI账户不存在");

        List<FuturesPositionDTO> positions = futuresTradingService.getUserPositions(aiId, null);

        BigDecimal unrealizedPnl = positions.stream()
                .map(FuturesPositionDTO::getUnrealizedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long todayTrades = futuresOrderMapper.selectCount(
                new LambdaQueryWrapper<FuturesOrder>()
                        .eq(FuturesOrder::getUserId, aiId)
                        .eq(FuturesOrder::getStatus, "FILLED")
                        .ge(FuturesOrder::getCreatedAt, LocalDate.now().atStartOfDay()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balance", user.getBalance());
        data.put("frozenBalance", user.getFrozenBalance());
        data.put("unrealizedPnl", unrealizedPnl);
        data.put("totalPnl", user.getBalance().add(user.getFrozenBalance())
                .add(unrealizedPnl).subtract(AiTradingScheduler.INITIAL_BALANCE));
        data.put("positionCount", positions.size());
        data.put("todayTrades", todayTrades);
        data.put("positions", positions);
        return Result.ok(data);
    }

    @GetMapping("/decisions")
    @Operation(summary = "AI决策历史")
    public Result<List<AiTradingDecision>> decisions(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "20") int limit) {
        StpUtil.checkLogin();
        limit = Math.min(limit, 100);
        List<AiTradingDecision> list;
        if (symbol != null && !symbol.isBlank()) {
            list = decisionMapper.selectRecentBySymbol(symbol, limit);
        } else {
            list = decisionMapper.selectRecent(limit);
        }
        return Result.ok(list);
    }

    @GetMapping("/positions")
    @Operation(summary = "AI当前持仓")
    public Result<List<FuturesPositionDTO>> positions(
            @RequestParam(required = false) String symbol) {
        StpUtil.checkLogin();
        Long aiId = scheduler.getAiUserId();
        if (aiId == null || aiId == 0) return Result.fail("AI交易员未初始化");
        return Result.ok(futuresTradingService.getUserPositions(aiId, symbol));
    }

    @GetMapping("/orders")
    @Operation(summary = "AI历史订单")
    public Result<?> orders(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        StpUtil.checkLogin();
        Long aiId = scheduler.getAiUserId();
        if (aiId == null || aiId == 0) return Result.fail("AI交易员未初始化");
        return Result.ok(futuresTradingService.getUserOrders(aiId, null, pageNum, pageSize, symbol));
    }
}
