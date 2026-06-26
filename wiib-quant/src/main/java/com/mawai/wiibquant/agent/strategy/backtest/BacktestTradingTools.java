package com.mawai.wiibquant.agent.strategy.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回测用模拟交易工具。
 * <p>
 * 实现 {@link TradingOperations}，在内存中模拟开仓/平仓/止损止盈触发。
 * BacktestEngine 每根K线调用 {@link #tickBar(BigDecimal, BigDecimal, BigDecimal, int)}
 * 来检查是否触发了挂单（SL/TP）。
 *
 * <h3>模拟精度</h3>
 * <ul>
 *   <li>手续费：LIMIT 开仓 maker；MARKET 开仓、触发式 TP/SL/SCALE 平仓 taker</li>
 *   <li>SL/TP/SCALE：按K线 high/low 判断是否穿越</li>
 *   <li>滑点：触发后成交价暂取触发价（不模拟市价滑点）</li>
 *   <li>保证金：quantity × price / leverage</li>
 * </ul>
 */
@Slf4j
public class BacktestTradingTools implements TradingOperations {

    private static final BigDecimal OPEN_FEE_RATE = new BigDecimal("0.0005");   // taker 万5
    private static final BigDecimal CLOSE_FEE_RATE = new BigDecimal("0.0005");  // taker 万5
    private static final BigDecimal MAKER_FEE_RATE = new BigDecimal("0.0002");  // maker 万2：limit 进场

    private final AtomicLong positionIdSeq = new AtomicLong(1);

    // 状态
    @Getter
    private BigDecimal balance;
    private BigDecimal peakEquity;
    @Getter
    private BigDecimal frozenBalance = BigDecimal.ZERO;
    private final List<FuturesPositionDTO> openPositions = new ArrayList<>();
    private final List<ClosedTrade> closedTrades = new ArrayList<>();
    private final Map<Long, BigDecimal> initialRiskPerUnitByPosition = new HashMap<>();
    private final Map<Long, String> entryDiagnosticsJsonByPosition = new HashMap<>();
    private final Map<Long, String> entryModeByPosition = new HashMap<>();
    private final Map<Long, Boolean> lateContinuationByPosition = new HashMap<>();
    private final Map<Long, Integer> latestFailScoreByPosition = new HashMap<>();
    private final Map<Long, BigDecimal> maxFavorableRByPosition = new HashMap<>();
    private final Map<Long, BigDecimal> maxAdverseRByPosition = new HashMap<>();
    private final Map<Long, BigDecimal> openFeeRateByPosition = new HashMap<>();  // 开仓实际费率(maker/taker)，平仓算总费用时复用
    private final Map<Long, ScaleOut> scaleOutByPosition = new HashMap<>();       // 触发式止盈：价到触发价后市价平仓(taker)

    @Setter
    private int currentBarIndex = 0;
    @Setter
    private LocalDateTime currentTime;
    private final String symbol;

    /**
     * 已平仓交易记录，用于生成 BacktestResult.Trade。
     */
    public record ClosedTrade(
            long positionId,
            int openBarIndex,
            int closeBarIndex,
            LocalDateTime openTime,
            LocalDateTime closeTime,
            String entryDiagnosticsJson,
            String side,
            String strategy,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal quantity,
            int leverage,
            BigDecimal pnl,
            BigDecimal fee,
            BigDecimal rMultiple,
            String exitReason,
            String entryMode,
            Integer failScoreAtExit,
            BigDecimal maxFavorableR,
            BigDecimal maxAdverseR,
            boolean wasLateContinuation
    ) {}

    /** 分批止盈挂单：triggerPrice 触发价、scaleQty 落袋数量(剩余仓止损不动)。 */
    private record ScaleOut(BigDecimal triggerPrice, BigDecimal scaleQty) {}

    public BacktestTradingTools(BigDecimal initialBalance, String symbol) {
        this.balance = initialBalance;
        this.peakEquity = initialBalance;
        this.symbol = symbol;
    }

    // ==================== TradingOperations 实现 ====================

    @Override
    public BigDecimal peakEquity() {
        return peakEquity;
    }

    @Override
    public String openPosition(String side, BigDecimal quantity, Integer leverage,
                                String orderType, BigDecimal limitPrice,
                                BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                                String memo) {
        return openPositionWithResult(side, quantity, leverage, orderType, limitPrice,
                stopLossPrice, takeProfitPrice, memo).message();
    }

    @Override
    public OpenResult openPositionWithResult(String side, BigDecimal quantity, Integer leverage,
                                             String orderType, BigDecimal limitPrice,
                                             BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                                             String memo) {
        if (quantity == null || quantity.signum() <= 0) {
            return OpenResult.fromMessage("开仓失败: 数量无效");
        }

        // LIMIT 单成交于挂单价(limitPrice)走 maker；MARKET 单成交于当前价走 taker。
        boolean isLimit = "LIMIT".equals(orderType);
        BigDecimal price = isLimit && limitPrice != null && limitPrice.signum() > 0
                ? limitPrice : getCurrentPrice();
        if (price == null || price.signum() <= 0) {
            return OpenResult.fromMessage("开仓失败: 价格无效");
        }

        BigDecimal openFeeRate = isLimit ? MAKER_FEE_RATE : OPEN_FEE_RATE;
        BigDecimal notional = quantity.multiply(price);
        BigDecimal margin = notional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.CEILING);
        BigDecimal openFee = notional.multiply(openFeeRate).setScale(8, RoundingMode.HALF_UP);

        BigDecimal totalCost = margin.add(openFee);
        if (totalCost.compareTo(balance) > 0) {
            return OpenResult.fromMessage("开仓失败: 余额不足 需要" + totalCost.toPlainString() + " 可用" + balance.toPlainString());
        }

        // 扣除保证金和手续费
        balance = balance.subtract(totalCost);
        frozenBalance = frozenBalance.add(margin);

        // 创建仓位
        FuturesPositionDTO pos = new FuturesPositionDTO();
        long posId = positionIdSeq.getAndIncrement();
        pos.setId(posId);
        pos.setUserId(0L);
        pos.setSymbol(symbol);
        pos.setSide(side);
        pos.setLeverage(leverage);
        pos.setQuantity(quantity);
        pos.setEntryPrice(price);
        pos.setMargin(margin);
        pos.setFundingFeeTotal(BigDecimal.ZERO);
        pos.setMemo(memo);
        pos.setStatus("OPEN");
        // 回测必须使用推进后的模拟时间，否则 TIME_EXIT 会把刚开的仓误判为超时。
        LocalDateTime now = currentTime != null ? currentTime : LocalDateTime.now();
        pos.setCreatedAt(now);
        pos.setUpdatedAt(now);

        // 设置止损
        List<FuturesStopLoss> slList = new ArrayList<>();
        if (stopLossPrice != null && stopLossPrice.signum() > 0) {
            slList.add(new FuturesStopLoss("sl-" + posId, stopLossPrice, quantity));
        }
        pos.setStopLosses(slList);

        // 设置止盈
        List<FuturesTakeProfit> tpList = new ArrayList<>();
        if (takeProfitPrice != null && takeProfitPrice.signum() > 0) {
            tpList.add(new FuturesTakeProfit("tp-" + posId, takeProfitPrice, quantity));
        }
        pos.setTakeProfits(tpList);
        initialRiskPerUnitByPosition.put(posId, initialRiskPerUnit(price, stopLossPrice));
        maxFavorableRByPosition.put(posId, BigDecimal.ZERO);
        maxAdverseRByPosition.put(posId, BigDecimal.ZERO);
        openFeeRateByPosition.put(posId, openFeeRate);

        openPositions.add(pos);
        log.debug("[Backtest] 开仓成功 {} {} qty={} lev={} entry={} sl={} tp={} memo={}",
                side, symbol, quantity.toPlainString(), leverage, price.toPlainString(),
                stopLossPrice, takeProfitPrice, memo);

        return new OpenResult(true, posId, "开仓成功|posId=" + posId + "|price=" + price.toPlainString());
    }

    @Override
    public String closePosition(Long positionId, BigDecimal quantity) {
        return closePositionWithReason(positionId, quantity, "SIGNAL_CLOSE");
    }

    @Override
    public String closePositionWithReason(Long positionId, BigDecimal quantity, String reason) {
        FuturesPositionDTO pos = findPosition(positionId);
        if (pos == null) {
            return "平仓失败: 仓位不存在 id=" + positionId;
        }

        BigDecimal closeQty = quantity != null ? quantity : pos.getQuantity();
        if (closeQty.compareTo(pos.getQuantity()) > 0) {
            closeQty = pos.getQuantity();
        }

        BigDecimal exitPrice = getCurrentPrice();
        doClose(pos, closeQty, exitPrice, normalizeExitReason(reason));

        return "平仓成功|posId=" + positionId;
    }

    @Override
    public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
        FuturesPositionDTO pos = findPosition(positionId);
        if (pos == null) {
            return "修改止损失败: 仓位不存在";
        }

        List<FuturesStopLoss> slList = pos.getStopLosses();
        if (slList == null) {
            slList = new ArrayList<>();
            pos.setStopLosses(slList);
        }
        slList.clear();
        slList.add(new FuturesStopLoss("sl-" + positionId, stopLossPrice,
                quantity != null ? quantity : pos.getQuantity()));

        log.debug("[Backtest] 修改止损 posId={} newSL={}", positionId, stopLossPrice.toPlainString());
        return "止损修改成功";
    }

    @Override
    public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
        FuturesPositionDTO pos = findPosition(positionId);
        if (pos == null) {
            return "修改止盈失败: 仓位不存在";
        }

        List<FuturesTakeProfit> tpList = pos.getTakeProfits();
        if (tpList == null) {
            tpList = new ArrayList<>();
            pos.setTakeProfits(tpList);
        }
        tpList.clear();
        tpList.add(new FuturesTakeProfit("tp-" + positionId, takeProfitPrice,
                quantity != null ? quantity : pos.getQuantity()));

        return "止盈修改成功";
    }

    /** 兼容旧调用；maker 入场 fill 摩擦已在 StrategyKlineBacktestEngine.limitTouched 内处理。 */
    public void setFillEpsilon(BigDecimal eps) {
        // no-op
    }

    @Override
    public void setScaleOut(Long positionId, BigDecimal triggerPrice, double fraction) {
        if (positionId == null || triggerPrice == null || fraction <= 0) return;
        FuturesPositionDTO pos = findPosition(positionId);
        if (pos == null) return;
        BigDecimal scaleQty = pos.getQuantity().multiply(BigDecimal.valueOf(fraction));   // 比例→绝对量(注册时全仓)
        if (scaleQty.signum() <= 0) return;
        scaleOutByPosition.put(positionId, new ScaleOut(triggerPrice, scaleQty));
    }

    // ==================== 回测专用方法 ====================

    /** 当前K线价格（用于开仓/平仓的成交价） */
    @Setter
    private BigDecimal currentPrice;

    private BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    /**
     * 每根K线调用一次，检查是否触发 SL/TP。
     *
     * @param high  本根K线最高价
     * @param low   本根K线最低价
     * @param close 本根K线收盘价
     * @param barIndex 当前K线序号
     */
    public void tickBar(BigDecimal high, BigDecimal low, BigDecimal close, int barIndex) {
        this.currentPrice = close;
        this.currentBarIndex = barIndex;

        List<FuturesPositionDTO> posSnapshot = new ArrayList<>(openPositions);
        for (FuturesPositionDTO pos : posSnapshot) {
            if (!"OPEN".equals(pos.getStatus())) continue;
            recordExcursion(pos, high, low);
            checkSlTpTrigger(pos, high, low);
        }

        // 更新所有仓位的当前价格和未实现盈亏
        for (FuturesPositionDTO pos : openPositions) {
            if (!"OPEN".equals(pos.getStatus())) continue;
            pos.setCurrentPrice(close);
            pos.setMarkPrice(close);
            BigDecimal pnl = calcUnrealizedPnl(pos, close);
            pos.setUnrealizedPnl(pnl);
        }
    }

    /**
     * 检查SL/TP是否被触发。
     * SL: LONG仓位 → low <= SL价; SHORT仓位 → high >= SL价
     * TP: LONG仓位 → high >= TP价; SHORT仓位 → low <= TP价
     * 同一根K线如果SL和TP同时触发，保守假设SL先触发。
     */
    private void checkSlTpTrigger(FuturesPositionDTO pos, BigDecimal high, BigDecimal low) {
        boolean isLong = "LONG".equals(pos.getSide());

        // 检查止损
        if (pos.getStopLosses() != null && !pos.getStopLosses().isEmpty()) {
            FuturesStopLoss sl = pos.getStopLosses().getFirst();
            boolean slTriggered = isLong
                    ? low.compareTo(sl.getPrice()) <= 0
                    : high.compareTo(sl.getPrice()) >= 0;
            if (slTriggered) {
                doClose(pos, pos.getQuantity(), sl.getPrice(), "SL");
                return;
            }
        }

        // 分批止盈：盘中触及触发价 → 触发式市价部分平仓(taker)，一次性；剩余仓 SL 不动、拉到 TP（无移动保本）
        ScaleOut so = scaleOutByPosition.get(pos.getId());
        if (so != null) {
            // 触发式平仓不排 maker 队列；触及触发价即按触发价模拟成交。
            boolean soTriggered = isLong
                    ? high.compareTo(so.triggerPrice()) >= 0
                    : low.compareTo(so.triggerPrice()) <= 0;
            if (soTriggered) {
                doClose(pos, so.scaleQty().min(pos.getQuantity()), so.triggerPrice(), "SCALE");
                scaleOutByPosition.remove(pos.getId());                 // 一次性；剩余仓SL不动，拉到TP
            }
        }
        if (!"OPEN".equals(pos.getStatus())) return;                    // 分批已全平则不再判TP

        // 检查止盈
        if (pos.getTakeProfits() != null && !pos.getTakeProfits().isEmpty()) {
            FuturesTakeProfit tp = pos.getTakeProfits().getFirst();
            // TP 条件市价单：触及触发价即按触发价模拟成交。
            boolean tpTriggered = isLong
                    ? high.compareTo(tp.getPrice()) >= 0
                    : low.compareTo(tp.getPrice()) <= 0;
            if (tpTriggered) {
                doClose(pos, pos.getQuantity(), tp.getPrice(), "TP");
            }
        }
    }

    /**
     * 执行平仓（全部或部分）。
     */
    private void doClose(FuturesPositionDTO pos, BigDecimal closeQty, BigDecimal exitPrice, String reason) {
        boolean isLong = "LONG".equals(pos.getSide());
        boolean isFullClose = closeQty.compareTo(pos.getQuantity()) >= 0;

        // 当前执行用条件市价平仓：TP/SCALE/SL/强平/信号平仓统一按 taker。
        BigDecimal closeFeeRate = CLOSE_FEE_RATE;
        BigDecimal notional = closeQty.multiply(exitPrice);
        BigDecimal closeFee = notional.multiply(closeFeeRate).setScale(8, RoundingMode.HALF_UP);

        // 计算PnL
        BigDecimal priceDiff = isLong
                ? exitPrice.subtract(pos.getEntryPrice())
                : pos.getEntryPrice().subtract(exitPrice);
        BigDecimal rawPnl = priceDiff.multiply(closeQty);

        // 手续费：仅从PnL扣除平仓费（开仓费已在openPosition时扣除）
        // 但记录总手续费用于报表统计；开仓费率用开仓时实际记录的(maker/taker)
        BigDecimal openRate = openFeeRateByPosition.getOrDefault(pos.getId(), OPEN_FEE_RATE);
        BigDecimal openNotional = closeQty.multiply(pos.getEntryPrice());
        BigDecimal openFeeAlloc = openNotional.multiply(openRate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalFeeForRecord = openFeeAlloc.add(closeFee);
        BigDecimal cashPnl = rawPnl.subtract(closeFee);
        BigDecimal netPnl = rawPnl.subtract(totalFeeForRecord);
        BigDecimal rMultiple = calcRMultiple(pos, exitPrice);

        // 释放保证金
        BigDecimal marginRelease;
        if (isFullClose) {
            marginRelease = pos.getMargin();
        } else {
            BigDecimal ratio = closeQty.divide(pos.getQuantity(), 8, RoundingMode.HALF_UP);
            marginRelease = pos.getMargin().multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        }

        frozenBalance = frozenBalance.subtract(marginRelease);
        // 开仓手续费已在 openPosition 扣过，余额这里只加回保证金和扣平仓费后的现金盈亏。
        balance = balance.add(marginRelease).add(cashPnl);

        // 记录平仓
        closedTrades.add(new ClosedTrade(
                pos.getId(),
                findOpenBarIndex(pos),
                currentBarIndex,
                pos.getCreatedAt(),
                currentTime,
                entryDiagnosticsJsonByPosition.get(pos.getId()),
                pos.getSide(),
                pos.getMemo() != null ? pos.getMemo() : "UNKNOWN",
                pos.getEntryPrice(),
                exitPrice,
                closeQty,
                pos.getLeverage(),
                netPnl,
                totalFeeForRecord,
                rMultiple,
                reason,
                entryModeByPosition.get(pos.getId()),
                latestFailScoreByPosition.get(pos.getId()),
                maxFavorableRByPosition.get(pos.getId()),
                maxAdverseRByPosition.get(pos.getId()),
                Boolean.TRUE.equals(lateContinuationByPosition.get(pos.getId()))
        ));

        if (isFullClose) {
            pos.setStatus("CLOSED");
            pos.setClosedPrice(exitPrice);
            pos.setClosedPnl(netPnl);
            openPositions.remove(pos);
            initialRiskPerUnitByPosition.remove(pos.getId());
            entryDiagnosticsJsonByPosition.remove(pos.getId());
            entryModeByPosition.remove(pos.getId());
            lateContinuationByPosition.remove(pos.getId());
            latestFailScoreByPosition.remove(pos.getId());
            maxFavorableRByPosition.remove(pos.getId());
            maxAdverseRByPosition.remove(pos.getId());
            openFeeRateByPosition.remove(pos.getId());
            scaleOutByPosition.remove(pos.getId());
        } else {
            pos.setQuantity(pos.getQuantity().subtract(closeQty));
            pos.setMargin(pos.getMargin().subtract(marginRelease));
        }

        log.debug("[Backtest] 平仓 {} {} qty={} entry={} exit={} pnl={} reason={}",
                pos.getSide(), symbol, closeQty.toPlainString(),
                pos.getEntryPrice().toPlainString(), exitPrice.toPlainString(),
                netPnl.toPlainString(), reason);
    }

    private FuturesPositionDTO findPosition(Long positionId) {
        if (positionId == null) return null;
        for (FuturesPositionDTO p : openPositions) {
            if (positionId.equals(p.getId())) return p;
        }
        return null;
    }

    private int findOpenBarIndex(FuturesPositionDTO pos) {
        // 如果能从现有closedTrades中推算，就用；否则用当前bar（保守）
        // 开仓的bar index保存在createdAt的一个小技巧中不太好
        // 简单做法：用一个 Map 记录，或者直接用 currentBarIndex - 1
        // 这里用简化方案：记录在 pos.fundingFeeTotal 的 intValue (hack)
        return pos.getFundingFeeTotal() != null ? pos.getFundingFeeTotal().intValue() : 0;
    }

    private BigDecimal calcUnrealizedPnl(FuturesPositionDTO pos, BigDecimal currentPrice) {
        boolean isLong = "LONG".equals(pos.getSide());
        BigDecimal priceDiff = isLong
                ? currentPrice.subtract(pos.getEntryPrice())
                : pos.getEntryPrice().subtract(currentPrice);
        return priceDiff.multiply(pos.getQuantity());
    }

    private BigDecimal initialRiskPerUnit(BigDecimal entryPrice, BigDecimal stopLossPrice) {
        if (entryPrice == null || stopLossPrice == null) return null;
        BigDecimal risk = entryPrice.subtract(stopLossPrice).abs();
        return risk.signum() > 0 ? risk : null;
    }

    private BigDecimal calcRMultiple(FuturesPositionDTO pos, BigDecimal exitPrice) {
        BigDecimal risk = initialRiskPerUnitByPosition.get(pos.getId());
        if (risk == null || risk.signum() <= 0 || exitPrice == null || pos.getEntryPrice() == null) {
            return null;
        }
        BigDecimal priceDiff = "LONG".equals(pos.getSide())
                ? exitPrice.subtract(pos.getEntryPrice())
                : pos.getEntryPrice().subtract(exitPrice);
        return priceDiff.divide(risk, 8, RoundingMode.HALF_UP);
    }

    private void recordExcursion(FuturesPositionDTO pos, BigDecimal high, BigDecimal low) {
        BigDecimal risk = initialRiskPerUnitByPosition.get(pos.getId());
        if (risk == null || risk.signum() <= 0 || high == null || low == null || pos.getEntryPrice() == null) {
            return;
        }
        boolean isLong = "LONG".equals(pos.getSide());
        BigDecimal favorable = isLong
                ? high.subtract(pos.getEntryPrice())
                : pos.getEntryPrice().subtract(low);
        BigDecimal adverse = isLong
                ? low.subtract(pos.getEntryPrice())
                : pos.getEntryPrice().subtract(high);
        BigDecimal favorableR = favorable.divide(risk, 8, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        BigDecimal adverseR = adverse.divide(risk, 8, RoundingMode.HALF_UP).min(BigDecimal.ZERO);
        maxFavorableRByPosition.merge(pos.getId(), favorableR, BigDecimal::max);
        maxAdverseRByPosition.merge(pos.getId(), adverseR, BigDecimal::min);
    }

    private String normalizeExitReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "SIGNAL_CLOSE";
        }
        String trimmed = reason.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    // ==================== 状态查询 ====================

    /**
     * 总权益 = 可用余额 + 冻结（保证金）+ 未实现盈亏。
     */
    public BigDecimal getTotalEquity() {
        BigDecimal unrealized = openPositions.stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal equity = balance.add(frozenBalance).add(unrealized);
        if (peakEquity == null || equity.compareTo(peakEquity) > 0) {
            peakEquity = equity;
        }
        return equity;
    }

    public List<FuturesPositionDTO> getOpenPositions() {
        return openPositions.stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .toList();
    }

    /**
     * 获取指定symbol的开放仓位（BacktestEngine用）。
     */
    public List<FuturesPositionDTO> getOpenPositions(String filterSymbol) {
        return openPositions.stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .filter(p -> filterSymbol == null || filterSymbol.equals(p.getSymbol()))
                .toList();
    }

    public List<ClosedTrade> getClosedTrades() {
        return List.copyOf(closedTrades);
    }

    /**
     * 标记开仓K线索引（在openPosition之后立即调用）。
     * 利用 FuturesPositionDTO.fundingFeeTotal 字段临时存储开仓bar index。
     */
    public void markOpenBarIndex(int barIndex) {
        markOpenBarIndex(barIndex, null);
    }

    public void markOpenBarIndex(int barIndex, String entryDiagnosticsJson) {
        if (!openPositions.isEmpty()) {
            FuturesPositionDTO lastOpened = openPositions.getLast();
            lastOpened.setFundingFeeTotal(BigDecimal.valueOf(barIndex));
            if (entryDiagnosticsJson != null && !entryDiagnosticsJson.isBlank()) {
                entryDiagnosticsJsonByPosition.put(lastOpened.getId(), entryDiagnosticsJson);
                rememberEntryDiagnostics(lastOpened.getId(), entryDiagnosticsJson);
            }
        }
    }

    public void recordExitSignalDiagnostics(Long positionId, Integer failScoreAtExit) {
        if (positionId != null && failScoreAtExit != null) {
            latestFailScoreByPosition.put(positionId, failScoreAtExit);
        }
    }

    private void rememberEntryDiagnostics(Long positionId, String entryDiagnosticsJson) {
        try {
            JSONObject json = JSON.parseObject(entryDiagnosticsJson);
            String entryMode = json.getString("entryMode");
            if (entryMode != null && !entryMode.isBlank()) {
                entryModeByPosition.put(positionId, entryMode);
            }
            Boolean late = json.getBoolean("wasLateContinuation");
            if (late != null) {
                lateContinuationByPosition.put(positionId, late);
            }
        } catch (Exception ignored) {
            // 诊断 JSON 只用于报告，解析失败不影响回测交易。
        }
    }
}
