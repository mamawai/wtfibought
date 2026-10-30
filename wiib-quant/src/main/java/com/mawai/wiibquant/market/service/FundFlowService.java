package com.mawai.wiibquant.market.service;

import com.mawai.wiibquant.external.defillama.DefiLlamaClient;
import com.mawai.wiibquant.external.tftc.TftcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 资金流两段：BTC 现货 ETF 每日净流入（TFTC）和美元稳定币流通市值（DefiLlama）。都是日频数据，
 * 各缓存一小时；取不到新的兜一天内的旧数据。金额一律折成百万美元。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundFlowService {

    static final long TTL_MS = 3_600_000L;
    static final long MAX_STALE_MS = 86_400_000L;
    /** ETF 每日净流入给最近这么多个交易日 */
    static final int ETF_DAYS = 10;

    public record EtfDay(String date, BigDecimal netFlowMusd) {
    }

    /**
     * @param totalNetAssetsMusd    最近一天的总净资产，上游没给就 null
     * @param cumulativeNetFlowMusd 2024-01-11 上市以来累计净流入
     * @param latestPerEtfMusd      最近一天各基金净流入，为 0 的不列
     * @param attribution           CC BY 4.0 要求的出处
     */
    public record EtfFlows(String updatedThrough, BigDecimal totalNetAssetsMusd, BigDecimal cumulativeNetFlowMusd,
                           List<EtfDay> daily, Map<String, BigDecimal> latestPerEtfMusd, String attribution) {
    }

    /** 市值与相对 7 天前 / 30 天前的增减 */
    public record Coin(BigDecimal mcapMusd, BigDecimal change7dMusd, BigDecimal change30dMusd) {
    }

    /** total 是全部美元稳定币合计 */
    public record Stablecoins(Coin total, Coin usdt, Coin usdc) {
    }

    private record Cached<T>(T value, long at) {
    }

    private final TftcClient tftc;
    private final DefiLlamaClient defiLlama;
    private final AtomicReference<Cached<EtfFlows>> etfSlot = new AtomicReference<>();
    private final AtomicReference<Cached<Stablecoins>> stableSlot = new AtomicReference<>();
    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 取不到也没旧的可兜就 null */
    public EtfFlows etfFlows() {
        return cached(etfSlot, () -> {
            String raw = tftc.etfFlows();
            return raw == null ? null : parseEtf(MAPPER.readTree(raw));
        });
    }

    public Stablecoins stablecoins() {
        return cached(stableSlot, () -> {
            String raw = defiLlama.stablecoins();
            return raw == null ? null : parseStablecoins(MAPPER.readTree(raw));
        });
    }

    /** TTL 内复用；取新的失败（含回包形状对不上）兜一天内的旧值 */
    private <T> T cached(AtomicReference<Cached<T>> slot, Supplier<T> loader) {
        Cached<T> hit = slot.get();
        long now = nowMs.getAsLong();
        if (hit != null && now - hit.at() < TTL_MS) {
            return hit.value();
        }
        T fresh = null;
        try {
            fresh = loader.get();
        } catch (RuntimeException e) {
            log.warn("[FundFlow] 回包解析失败: {}", e.toString());
        }
        if (fresh != null) {
            slot.set(new Cached<>(fresh, now));
            return fresh;
        }
        return hit != null && now - hit.at() < MAX_STALE_MS ? hit.value() : null;
    }

    /**
     * days 从上市第一天起按日排好：netFlowUsd 总净流入、totalNetAssetsUsd 总净资产（可 null）、perEtfUsd 各基金。
     * 还没披露的那天 netFlowUsd 不是数，不算
     */
    static EtfFlows parseEtf(JsonNode root) {
        List<JsonNode> days = new ArrayList<>();
        for (JsonNode d : root.get("days")) {
            if (d.path("netFlowUsd").isNumber()) {
                days.add(d);
            }
        }
        double cumulative = 0;
        for (JsonNode d : days) {
            cumulative += d.get("netFlowUsd").asDouble();
        }
        List<EtfDay> daily = new ArrayList<>();
        for (JsonNode d : days.subList(Math.max(0, days.size() - ETF_DAYS), days.size())) {
            daily.add(new EtfDay(d.get("date").asString(), musd(d.get("netFlowUsd").asDouble())));
        }
        JsonNode latest = days.getLast();
        Map<String, BigDecimal> perEtf = new LinkedHashMap<>();
        latest.get("perEtfUsd").properties().forEach(e -> {
            if (e.getValue().isNumber() && e.getValue().asDouble() != 0) {
                perEtf.put(e.getKey(), musd(e.getValue().asDouble()));
            }
        });
        JsonNode assets = latest.path("totalNetAssetsUsd");
        return new EtfFlows(root.path("updatedThrough").asString(null),
                assets.isNumber() ? musd(assets.asDouble()) : null, musd(cumulative), daily, perEtf,
                root.path("attribution").asString(null));
    }

    /** 只算 pegType=peggedUSD 的；每个币 circulating / circulatingPrevWeek / circulatingPrevMonth 下的 peggedUSD。USDT、USDC 按 gecko_id 认 */
    static Stablecoins parseStablecoins(JsonNode root) {
        double[] total = new double[3];
        double[] usdt = null;
        double[] usdc = null;
        for (JsonNode a : root.get("peggedAssets")) {
            if (!"peggedUSD".equals(a.path("pegType").asString(null))) {
                continue;
            }
            double[] v = {
                    a.path("circulating").path("peggedUSD").asDouble(0),
                    a.path("circulatingPrevWeek").path("peggedUSD").asDouble(0),
                    a.path("circulatingPrevMonth").path("peggedUSD").asDouble(0)};
            for (int i = 0; i < 3; i++) {
                total[i] += v[i];
            }
            String id = a.path("gecko_id").asString(null);
            if ("tether".equals(id)) {
                usdt = v;
            } else if ("usd-coin".equals(id)) {
                usdc = v;
            }
        }
        return new Stablecoins(coin(total), coin(usdt), coin(usdc));
    }

    /** v = {当前, 7 天前, 30 天前}；没找到这个币就 null */
    private static Coin coin(double[] v) {
        return v == null ? null : new Coin(musd(v[0]), musd(v[0] - v[1]), musd(v[0] - v[2]));
    }

    private static BigDecimal musd(double usd) {
        return BigDecimal.valueOf(usd / 1_000_000).setScale(1, RoundingMode.HALF_UP);
    }
}
