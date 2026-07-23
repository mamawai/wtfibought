package com.mawai.wiibsim.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import com.mawai.wiibcommon.market.TradeFilterDefaults.Filter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易过滤器注册表（数量步长 / 最小数量 / 最小名义额），对齐 Binance exchangeInfo。
 * <p>
 * 启动时异步拉官方 exchangeInfo 覆盖 {@link TradeFilterDefaults} 快照（拉不到用快照，不挡启动、不依赖外网）；
 * 只认快照里已配置的 symbol，官方新上的标的不会被动混入。
 * <p>
 * 校验语义对齐 Binance：开仓单（含同向并入）步长必须对齐、名义额 ≥ minNotional 否则拒单；
 * 平仓/止盈损是 reduce-only，豁免 minNotional；平仓数量=持仓全量时豁免步长（存量尘埃仓要能平干净）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeFilterRegistry {

    private final BinanceRestClient restClient;

    private volatile Map<String, Filter> futuresFilters = TradeFilterDefaults.FUTURES;
    private volatile Map<String, Filter> spotFilters = TradeFilterDefaults.SPOT;

    @PostConstruct
    void init() {
        Thread.startVirtualThread(this::refreshFromBinance);
    }

    void refreshFromBinance() {
        Map<String, Filter> fut = parseFilters(restClient.getFuturesExchangeInfo(),
                TradeFilterDefaults.FUTURES, "MIN_NOTIONAL", "notional");
        if (fut != null) {
            futuresFilters = fut;
            log.info("合约交易过滤器已按官方exchangeInfo刷新: {}", fut);
        } else {
            log.warn("合约exchangeInfo拉取/解析失败，沿用默认快照");
        }

        Map<String, Filter> spot = parseFilters(restClient.getSpotExchangeInfo(List.copyOf(TradeFilterDefaults.SPOT.keySet())),
                TradeFilterDefaults.SPOT, "NOTIONAL", "minNotional");
        if (spot != null) {
            spotFilters = spot;
            log.info("现货交易过滤器已按官方exchangeInfo刷新: {}", spot);
        } else {
            log.warn("现货exchangeInfo拉取/解析失败，沿用默认快照");
        }
    }

    /** 解析 exchangeInfo：只覆盖 defaults 已配置的 symbol，缺失/解析异常的单币回退默认；整体失败返回 null */
    private Map<String, Filter> parseFilters(String json, Map<String, Filter> defaults,
                                             String notionalFilterType, String notionalField) {
        if (json == null) return null;
        try {
            JSONObject root = JSON.parseObject(json);
            JSONArray symbols = root.getJSONArray("symbols");
            if (symbols == null) return null; // 地理拦截等异常响应 {"code":0,"msg":...}
            Map<String, Filter> result = new HashMap<>(defaults);
            for (int i = 0; i < symbols.size(); i++) {
                JSONObject s = symbols.getJSONObject(i);
                String symbol = s.getString("symbol");
                if (!defaults.containsKey(symbol)) continue;
                BigDecimal step = null;
                BigDecimal minQty = null;
                BigDecimal minNotional = null;
                for (Object o : s.getJSONArray("filters")) {
                    JSONObject filter = (JSONObject) o;
                    String type = filter.getString("filterType");
                    if ("LOT_SIZE".equals(type)) {
                        step = filter.getBigDecimal("stepSize");
                        minQty = filter.getBigDecimal("minQty");
                    } else if (notionalFilterType.equals(type)) {
                        minNotional = filter.getBigDecimal(notionalField);
                    }
                }
                if (step != null && minQty != null && minNotional != null) {
                    result.put(symbol, new Filter(step.stripTrailingZeros(), minQty.stripTrailingZeros(),
                            minNotional.stripTrailingZeros()));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("exchangeInfo解析失败: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Filter> allFutures() {
        return futuresFilters;
    }

    public Map<String, Filter> allSpot() {
        return spotFilters;
    }

    /** 合约开仓单校验（市价传现价、限价传挂单价；同向并入的加仓单同样过这道） */
    public void validateFuturesOrder(String symbol, BigDecimal qty, BigDecimal price) {
        validateOrder(futuresFilters.get(symbol), symbol, qty, price);
    }

    /** 现货买入校验（市价传现价、限价传挂单价） */
    public void validateSpotBuy(String symbol, BigDecimal qty, BigDecimal price) {
        validateOrder(spotFilters.get(symbol), symbol, qty, price);
    }

    private void validateOrder(Filter f, String symbol, BigDecimal qty, BigDecimal price) {
        if (f == null) return; // 未配置 symbol 不设限（合约侧档位注册表另行把关）
        if (qty.compareTo(f.minQty()) < 0) {
            throw new BizException(ErrorCode.TRADE_STEP_INVALID.getCode(),
                    symbol + " 最小下单数量 " + plain(f.minQty()));
        }
        if (qty.remainder(f.stepSize()).signum() != 0) {
            throw new BizException(ErrorCode.TRADE_STEP_INVALID.getCode(),
                    symbol + " 数量需为 " + plain(f.stepSize()) + " 的整数倍");
        }
        if (price != null && price.multiply(qty).compareTo(f.minNotional()) < 0) {
            throw new BizException(ErrorCode.TRADE_MIN_NOTIONAL.getCode(),
                    symbol + " 最小下单金额 " + plain(f.minNotional()) + " USDT");
        }
    }

    /** 合约平仓数量校验：reduce-only 豁免名义额；全量平仓豁免步长（存量尘埃仓能平干净） */
    public void validateFuturesClose(String symbol, BigDecimal closeQty, BigDecimal positionQty) {
        validateReduce(futuresFilters.get(symbol), symbol, closeQty, positionQty);
    }

    /** 现货卖出校验：全量卖出豁免步长与名义额（尘埃持仓能清干净），部分卖出只查步长 */
    public void validateSpotSell(String symbol, BigDecimal qty, BigDecimal holdingQty) {
        validateReduce(spotFilters.get(symbol), symbol, qty, holdingQty);
    }

    private void validateReduce(Filter f, String symbol, BigDecimal qty, BigDecimal totalQty) {
        if (f == null) return;
        if (totalQty != null && qty.compareTo(totalQty) == 0) return;
        if (qty.remainder(f.stepSize()).signum() != 0) {
            throw new BizException(ErrorCode.TRADE_STEP_INVALID.getCode(),
                    symbol + " 数量需为 " + plain(f.stepSize()) + " 的整数倍（全部平出不受限）");
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
