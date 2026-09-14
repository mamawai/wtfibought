package com.mawai.wiibquant.whale;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper.Row;
import com.mawai.wiibquant.whale.WhaleAggregator.Side;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 读每币最新快照给两个 GET（docs/hyperliquid-whale.md §7）。覆盖率与近价强平带在这里算，表里不存。
 * <p>
 * summary 只列最新一槽里有仓位的币；/{coin} 对配置外的币、没快照、没仓位都回 null，前端整块不渲染。
 * 开关关掉两个都回空。
 */
@Service
@RequiredArgsConstructor
public class WhaleQueryService {

    /** 近价强平带：现价上下各 5%，展示口径 */
    static final BigDecimal BAND_PCT = new BigDecimal("0.05");
    /** 覆盖率小数位 */
    static final int COVERAGE_SCALE = 4;

    /** 没仓位的一侧：count=0 其余 null */
    private static final Side EMPTY_SIDE = new Side(0, null, null, null, null, null);

    /** 5% 内强平名义合计与名义最大那个桶的下界 */
    public record LiqBand(BigDecimal notional, BigDecimal peakPrice) {
    }

    /** 池内名义 / 全市场持仓量；OI 为 0（币下架）时 null */
    public record Coverage(@JsonProperty("long") BigDecimal longSide, @JsonProperty("short") BigDecimal shortSide) {
    }

    /** summary 里一个币那段；long/short 是 Java 关键字，JSON 键靠注解起名 */
    public record CoinView(String coin, String symbol, BigDecimal price,
                           @JsonProperty("long") Side longSide, @JsonProperty("short") Side shortSide,
                           LiqBand liqAbove, LiqBand liqBelow, BigDecimal hlOpenInterest, Coverage coverage) {
    }

    /** 首页卡：最新一槽；没快照或开关关掉 observedAt/poolSize 为 null、coins 空数组 */
    public record Summary(Long observedAt, Integer poolSize, List<CoinView> coins) {
    }

    /** Coin 页：view 平铺进来 + 槽 + 池子 + 两个分桶 JSON 原样透传 */
    public record CoinDetail(@JsonUnwrapped CoinView view, long observedAt, int poolSize,
                             @JsonRawValue String entryBuckets, @JsonRawValue String liqBuckets) {
    }

    private final WhaleSnapshotMapper snapshotMapper;
    private final WhaleProperties props;

    public Summary summary() {
        if (!props.isEnabled()) {
            return new Summary(null, null, List.of());
        }
        List<Row> rows = props.getCoins().stream().map(snapshotMapper::selectLatest).filter(Objects::nonNull).toList();
        if (rows.isEmpty()) {
            return new Summary(null, null, List.of());
        }
        // 最新一槽 = 各币最近一行里最大的那个；落后的币不进，observedAt 对每个币都成立
        long latest = rows.stream().mapToLong(Row::getObservedAt).max().getAsLong();
        List<Row> inSlot = rows.stream().filter(r -> r.getObservedAt() == latest).toList();
        List<CoinView> coins = inSlot.stream().filter(WhaleQueryService::hasPosition).map(WhaleQueryService::view).toList();
        return new Summary(latest, inSlot.getFirst().getPoolSize(), coins);
    }

    public CoinDetail coin(String coin) {
        if (!props.isEnabled() || !props.getCoins().contains(coin)) {
            return null;
        }
        Row r = snapshotMapper.selectLatest(coin);
        if (r == null || !hasPosition(r)) {
            return null;
        }
        return new CoinDetail(view(r), r.getObservedAt(), r.getPoolSize(), r.getEntryBucketsJson(), r.getLiqBucketsJson());
    }

    private static boolean hasPosition(Row r) {
        return r.getLongCount() > 0 || r.getShortCount() > 0;
    }

    static CoinView view(Row r) {
        JSONObject liq = JSON.parseObject(r.getLiqBucketsJson());
        return new CoinView(r.getCoin(), r.getCoin() + "USDT", r.getPrice(),
                side(r.getLongCount(), r.getLongNotional(), r.getLongWavgEntry(), r.getLongMedianEntry(), r.getLongTop1Share(), r.getLongUpnl()),
                side(r.getShortCount(), r.getShortNotional(), r.getShortWavgEntry(), r.getShortMedianEntry(), r.getShortTop1Share(), r.getShortUpnl()),
                band(liq, r.getPrice(), true), band(liq, r.getPrice(), false), r.getHlOpenInterest(),
                new Coverage(coverage(r.getLongNotional(), r.getHlOpenInterest()), coverage(r.getShortNotional(), r.getHlOpenInterest())));
    }

    private static Side side(int count, BigDecimal notional, BigDecimal wavg, BigDecimal median, BigDecimal top1, BigDecimal upnl) {
        return count == 0 ? EMPTY_SIDE : new Side(count, notional, wavg, median, top1, upnl);
    }

    static BigDecimal coverage(BigDecimal notional, BigDecimal openInterest) {
        return openInterest.signum() == 0 ? null : notional.divide(openInterest, COVERAGE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 近价强平带。桶是 [下界, 下界+宽)，与带 (价, 价×1.05] 或 (价×0.95, 价] 有交集就算；
     * 上方只取空头列、下方只取多头列（空头在上方强平、多头在下方，跨价那个桶两边各取各的列）。合计为 0 回 null。
     */
    static LiqBand band(JSONObject liq, BigDecimal price, boolean above) {
        BigDecimal width = liq.getBigDecimal("width");
        JSONArray buckets = liq.getJSONArray("buckets");
        BigDecimal lo = above ? price : price.multiply(BigDecimal.ONE.subtract(BAND_PCT));
        BigDecimal hi = above ? price.multiply(BigDecimal.ONE.add(BAND_PCT)) : price;
        int col = above ? 2 : 1;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal peakNotional = BigDecimal.ZERO;
        BigDecimal peak = null;
        for (int i = 0; i < buckets.size(); i++) {
            JSONArray b = buckets.getJSONArray(i);
            BigDecimal lower = b.getBigDecimal(0);
            if (lower.compareTo(hi) > 0 || lower.add(width).compareTo(lo) <= 0) {
                continue;
            }
            BigDecimal n = b.getBigDecimal(col);
            total = total.add(n);
            if (n.compareTo(peakNotional) > 0) {
                peakNotional = n;
                peak = lower;
            }
        }
        return total.signum() == 0 ? null : new LiqBand(total, peak);
    }
}
