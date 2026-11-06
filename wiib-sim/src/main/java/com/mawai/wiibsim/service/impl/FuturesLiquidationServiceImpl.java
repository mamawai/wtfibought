package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FuturesLiquidationService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.PriceTick;
import com.mawai.wiibsim.service.TradeHit;
import com.mawai.wiibsim.service.TraderSymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

/**
 * 逐仓强平看标记价（markprice tick、标记价补漏、巡检），止损止盈看成交价（{@link #stopHits}）。
 * 三种索引都是摘取即删：摘到的那条路径负责执行或放回，别的路径同时扫也扫不到它。
 * 每小时对账会把库里还在的项补回去，正在执行的项可能被再摘一次，执行都在仓位锁里按库里现状复核，重复摘到也不会重复成交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesLiquidationServiceImpl implements FuturesLiquidationService {

    private final FuturesRiskService futuresRiskService;
    private final CacheService cacheService;
    private final TraderSymbolService traderSymbolService;
    private final FuturesPositionMapper positionMapper;
    private final FuturesPositionIndexService positionIndexService;
    private final CrossLiquidationService crossLiquidationService;

    /** 从止损/止盈索引摘出来的一档，放回用 key/member/score */
    private record Claim(String key, String member, double score, boolean stopLoss) {
        String itemId() {
            return member.substring(member.indexOf(':') + 1);
        }
    }

    /** 同一格摸到、触发价一样的同类档并成一次平仓 */
    private record HitKey(int tick, boolean stopLoss, BigDecimal price) {}

    /** 库里一档的触发价，和它从哪一刻起算 */
    private record Level(BigDecimal price, long since) {}

    // ==================== 强平（标记价） ====================

    @Override
    public void checkOnPriceUpdate(String symbol, BigDecimal markPrice) {
        double mp = markPrice.doubleValue();
        long now = System.currentTimeMillis();
        // 多头 markPrice ≤ 强平价 → score ≥ markPrice；空头反之
        liquidate(cacheService.zRangeByScoreAndRemove(LIQ_LONG_PREFIX + symbol, mp, Double.MAX_VALUE).keySet(),
                markPrice, now);
        liquidate(cacheService.zRangeByScoreAndRemove(LIQ_SHORT_PREFIX + symbol, 0, mp).keySet(), markPrice, now);
    }

    /**
     * 空窗补漏：整段极值先粗筛一遍强平索引（摘取即删），再逐仓位按开仓时间复核——
     * 开仓之后的标记价穿过强平价才算，不算的原样放回
     */
    @Override
    public void recoverGap(String symbol, List<KlineBar> markBars) {
        if (markBars.isEmpty()) return;
        BigDecimal[] all = KlineBar.lowHighAfter(markBars, 0);
        String longKey = LIQ_LONG_PREFIX + symbol;
        String shortKey = LIQ_SHORT_PREFIX + symbol;
        recoverLiq(longKey, cacheService.zRangeByScoreAndRemove(longKey, all[0].doubleValue(), Double.MAX_VALUE),
                markBars, true);
        recoverLiq(shortKey, cacheService.zRangeByScoreAndRemove(shortKey, 0, all[1].doubleValue()), markBars, false);
    }

    private void recoverLiq(String key, Map<String, Double> claimed, List<KlineBar> markBars, boolean isLong) {
        // 钉价是空窗里的价，时刻记成空窗第一根 K 线开盘：按钉价成交，不扫现在的盘口
        long pinAt = markBars.getFirst().openTime();
        for (var e : claimed.entrySet()) {
            FuturesPosition pos = positionMapper.selectById(Long.parseLong(e.getKey()));
            // 仓位没了，索引是过期项，不放回
            if (pos == null || !"OPEN".equals(pos.getStatus())) continue;
            BigDecimal[] range = KlineBar.lowHighAfter(markBars, toEpochMs(pos.getCreatedAt()));
            boolean hit = range != null && (isLong
                    ? range[0].doubleValue() <= e.getValue()
                    : range[1].doubleValue() >= e.getValue());
            if (!hit) {
                cacheService.zAdd(key, e.getKey(), e.getValue());
                continue;
            }
            // 钉价：多头钉区间低点、空头钉高点
            liquidate(Set.of(e.getKey()), isLong ? range[0] : range[1], pinAt);
        }
    }

    /** 摘出来的仓位各起虚拟线程强平；锁内复核不够强平会自己放回索引，抛异常（等锁超时）在这里放回 */
    private void liquidate(Set<String> posIds, BigDecimal price, long priceAt) {
        for (String posId : posIds) {
            Long pid = Long.parseLong(posId);
            Thread.startVirtualThread(() -> {
                try {
                    futuresRiskService.forceClose(pid, price, priceAt);
                } catch (Exception e) {
                    log.error("futures强平失败 posId={}，放回强平索引", pid, e);
                    FuturesPosition pos = positionMapper.selectById(pid);
                    if (pos != null && "OPEN".equals(pos.getStatus())) positionIndexService.registerLiquidation(pos);
                }
            });
        }
    }

    // ==================== 止损止盈（成交价） ====================

    @Override
    public List<TradeHit> stopHits(String symbol, List<PriceTick> ticks) {
        BigDecimal[] range = PriceTick.lowHigh(ticks);
        double low = range[0].doubleValue();
        double high = range[1].doubleValue();
        // 多头止损、空头止盈在下面：价摸到或低于触发价就算；空头止损、多头止盈在上面
        Map<Long, List<Claim>> byPosition = new LinkedHashMap<>();
        claim(SL_LONG_PREFIX + symbol, low, Double.MAX_VALUE, true, byPosition);
        claim(TP_SHORT_PREFIX + symbol, low, Double.MAX_VALUE, false, byPosition);
        claim(SL_SHORT_PREFIX + symbol, 0, high, true, byPosition);
        claim(TP_LONG_PREFIX + symbol, 0, high, false, byPosition);

        List<TradeHit> hits = new ArrayList<>();
        byPosition.forEach((pid, claims) -> {
            try {
                collectStopHits(pid, claims, ticks, hits);
            } catch (Exception e) {
                // 已经从索引摘下来了，判不了就原样放回，下一笔摸到再来
                log.error("futures止损止盈判定失败 posId={}，放回索引", pid, e);
                claims.forEach(c -> cacheService.zAdd(c.key(), c.member(), c.score()));
            }
        });
        return hits;
    }

    private void claim(String key, double min, double max, boolean stopLoss, Map<Long, List<Claim>> byPosition) {
        for (var e : cacheService.zRangeByScoreAndRemove(key, min, max).entrySet()) {
            String member = e.getKey();
            Long pid = Long.parseLong(member.substring(0, member.indexOf(':')));
            byPosition.computeIfAbsent(pid, k -> new ArrayList<>()).add(new Claim(key, member, e.getValue(), stopLoss));
        }
    }

    /** 一个仓位摘出来的档：从档位挂上那一刻起找第一格摸到它的行情，没摸到的原样放回 */
    private void collectStopHits(Long pid, List<Claim> claims, List<PriceTick> ticks, List<TradeHit> hits) {
        FuturesPosition pos = positionMapper.selectById(pid);
        // 仓位没了，索引是过期项，不放回
        if (pos == null || !"OPEN".equals(pos.getStatus())) return;
        boolean isLong = "LONG".equals(pos.getSide());
        long posSince = toEpochMs(pos.getCreatedAt());
        Map<HitKey, List<Claim>> grouped = new LinkedHashMap<>();
        for (Claim c : claims) {
            Level level = level(pos, c, posSince);
            // 库里已经没这档（执行掉了、改掉了），索引是过期项
            if (level == null) continue;
            // 多头止损、空头止盈是价从上往下摸
            boolean below = c.stopLoss() == isLong;
            int i = PriceTick.firstTouch(ticks, level.since(), level.price(), below, false);
            if (i < 0) {
                cacheService.zAdd(c.key(), c.member(), c.score());
                continue;
            }
            HitKey k = new HitKey(i, c.stopLoss(), ticks.get(i).touchPrice(level.price(), below));
            grouped.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
        }
        grouped.forEach((k, group) -> hits.add(new TradeHit(k.tick(), k.stopLoss(), pos.getUserId(),
                () -> execute(pos, k, group, ticks.get(k.tick()).at()))));
    }

    /** 执行一组命中；失败把这组里库里还在的档放回索引，下一笔摸到再来 */
    private void execute(FuturesPosition pos, HitKey k, List<Claim> group, long priceAt) {
        List<String> ids = group.stream().map(Claim::itemId).toList();
        try {
            if (!k.stopLoss()) {
                futuresRiskService.batchTriggerTakeProfit(pos.getId(), ids, k.price(), priceAt);
            } else if (pos.isCross()) {
                // 全仓止损在用户级锁里执行，跟爆仓串行
                crossLiquidationService.triggerStopLoss(pos, ids, k.price(), priceAt);
            } else {
                futuresRiskService.batchTriggerStopLoss(pos.getId(), ids, k.price(), priceAt);
            }
        } catch (Exception e) {
            log.error("futures{}触发失败 posId={} ids={}，放回索引", k.stopLoss() ? "止损" : "止盈", pos.getId(), ids, e);
            FuturesPosition latest = positionMapper.selectById(pos.getId());
            if (latest == null || !"OPEN".equals(latest.getStatus())) return;
            for (Claim c : group) {
                if (level(latest, c, 0L) != null) cacheService.zAdd(c.key(), c.member(), c.score());
            }
        }
    }

    /**
     * 库里的这一档，没了返回 null。since 取档位创建时间与仓位创建时间晚的那个
     * （限价单带的档位是下单时间，早于仓位）；旧数据没档位时间就用仓位的
     */
    private static Level level(FuturesPosition pos, Claim c, long posSince) {
        if (c.stopLoss()) {
            if (pos.getStopLosses() == null) return null;
            for (FuturesStopLoss sl : pos.getStopLosses()) {
                if (c.itemId().equals(sl.getId())) return new Level(sl.getPrice(), since(sl.getCreatedAt(), posSince));
            }
            return null;
        }
        if (pos.getTakeProfits() == null) return null;
        for (FuturesTakeProfit tp : pos.getTakeProfits()) {
            if (c.itemId().equals(tp.getId())) return new Level(tp.getPrice(), since(tp.getCreatedAt(), posSince));
        }
        return null;
    }

    private static long since(Long itemCreatedAt, long posSince) {
        return itemCreatedAt == null ? posSince : Math.max(itemCreatedAt, posSince);
    }

    // ==================== 兜底巡检 ====================

    /**
     * 逐仓强平的兜底巡检，补的是标记价 Pub/Sub 会静默丢消息的那段空窗：sim 订阅连接断开重连期间穿越的价格没人补，
     * 且全程不报错；MatchPriceConsumer 的启动补漏只在进程启动那一次跑。交易员的币不发标记价频道，逐仓强平全靠这里。
     * 全仓侧有 sweepCrossAccounts 每 30 秒兜着；限价单、止损止盈的巡检在 TradeStreamConsumer#sweep。
     * <p>
     * 直接复用 {@link #checkOnPriceUpdate}：强平索引摘取即删，处理过的仓位早不在 ZSet 里，再扫一遍也扫不到，天然幂等。
     */
    @Override
    public void sweepAll() {
        for (String symbol : traderSymbolService.allFuturesSymbols()) {
            try {
                // 标记价，缺了用合约最新价
                checkOnPriceUpdate(symbol, markPrice(cacheService, symbol));
            } catch (BizException e) {
                // 价格缓存还没热（刚启动 / 该标的行情未到），等下一轮即可，不是故障
                log.debug("[FuturesLiq] 兜底巡检跳过 symbol={} 价格不可用", symbol);
            } catch (Exception e) {
                log.error("[FuturesLiq] 兜底巡检失败 symbol={}", symbol, e);
            }
        }
    }
}
