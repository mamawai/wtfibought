package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import com.mawai.wiibcommon.market.TradeFilterDefaults.Filter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 交易过滤器注册表（数量步长 / 最小数量 / 最小名义额），对齐 Binance exchangeInfo；合约强平清算费率也从同一份里取。
 * <p>
 * 启动时异步拉、之后每天拉一次官方 exchangeInfo 覆盖 {@link TradeFilterDefaults} 快照（拉不到沿用现有的，不挡启动、不依赖外网）；
 * 合约收全部 USDT 永续，现货只认快照里已配置的 symbol。
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
    private volatile Map<String, BigDecimal> liquidationFees = TradeFilterDefaults.LIQUIDATION_FEE;

    @PostConstruct
    void init() {
        Thread.startVirtualThread(this::refreshFromBinance);
    }

    public void refreshFromBinance() {
        JsonNode futures = symbolsOf(restClient.getFuturesExchangeInfo());
        Map<String, Filter> fut = parseFilters(futures,
                TradeFilterDefaults.FUTURES, FuturesLeverageBracketRegistry::isUsdtPerpetual, "MIN_NOTIONAL", "notional");
        if (fut != null) {
            futuresFilters = fut;
            liquidationFees = parseLiquidationFees(futures);
            log.info("合约交易过滤器与清算费率已按官方exchangeInfo刷新 {}个", fut.size());
        } else {
            log.warn("合约exchangeInfo拉取/解析失败，沿用现有过滤器与清算费率");
        }

        Map<String, Filter> spot = parseFilters(symbolsOf(restClient.getSpotExchangeInfo(List.copyOf(TradeFilterDefaults.SPOT.keySet()))),
                TradeFilterDefaults.SPOT, TradeFilterDefaults.SPOT::containsKey, "NOTIONAL", "minNotional");
        if (spot != null) {
            spotFilters = spot;
            log.info("现货交易过滤器已按官方exchangeInfo刷新: {}", spot);
        } else {
            log.warn("现货exchangeInfo拉取/解析失败，沿用现有过滤器");
        }
    }

    /** exchangeInfo 里的 symbols 数组；拉不到、解析失败、地理拦截等异常响应（{"code":0,"msg":...}）返回 null */
    private JsonNode symbolsOf(String json) {
        if (json == null) return null;
        try {
            JsonNode symbols = MAPPER.readTree(json).path("symbols");
            return symbols.isArray() ? symbols : null;
        } catch (Exception e) {
            log.warn("exchangeInfo解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 只收 wanted 认的 symbol，字段缺的单币回退默认；symbols 为 null 返回 null */
    private Map<String, Filter> parseFilters(JsonNode symbols, Map<String, Filter> defaults, Predicate<String> wanted,
                                             String notionalFilterType, String notionalField) {
        if (symbols == null) return null;
        Map<String, Filter> result = new HashMap<>(defaults);
        for (JsonNode s : symbols) {
            String symbol = s.path("symbol").asString(null);
            if (!wanted.test(symbol)) continue;
            BigDecimal step = null;
            BigDecimal minQty = null;
            BigDecimal minNotional = null;
            for (JsonNode filter : s.path("filters")) {
                String type = filter.path("filterType").asString(null);
                if ("LOT_SIZE".equals(type)) {
                    step = filter.path("stepSize").asDecimal(null);
                    minQty = filter.path("minQty").asDecimal(null);
                } else if (notionalFilterType.equals(type)) {
                    minNotional = filter.path(notionalField).asDecimal(null);
                }
            }
            if (step != null && minQty != null && minNotional != null) {
                result.put(symbol, new Filter(step.stripTrailingZeros(), minQty.stripTrailingZeros(),
                        minNotional.stripTrailingZeros()));
            }
        }
        return result;
    }

    /** 每个 USDT 永续的 liquidationFee；没这个字段的币沿用快照 */
    private Map<String, BigDecimal> parseLiquidationFees(JsonNode symbols) {
        Map<String, BigDecimal> result = new HashMap<>(TradeFilterDefaults.LIQUIDATION_FEE);
        for (JsonNode s : symbols) {
            String symbol = s.path("symbol").asString(null);
            if (!FuturesLeverageBracketRegistry.isUsdtPerpetual(symbol)) continue;
            BigDecimal fee = s.path("liquidationFee").asDecimal(null);
            if (fee != null) result.put(symbol, fee.stripTrailingZeros());
        }
        return result;
    }

    /** 强平清算费 = 费率 × 成交额 */
    public BigDecimal liquidationFee(String symbol, BigDecimal closeValue) {
        return liquidationFeeRate(symbol).multiply(closeValue).setScale(2, RoundingMode.HALF_UP);
    }

    /** 合约强平清算费率；官方没拉到、快照里也没有的币按默认一档 */
    BigDecimal liquidationFeeRate(String symbol) {
        return liquidationFees.getOrDefault(symbol, TradeFilterDefaults.DEFAULT_LIQUIDATION_FEE);
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

    /** 现货买入校验（市价传现价、限价传挂单价）；未配置 symbol 直接拒 */
    public void validateSpotBuy(String symbol, BigDecimal qty, BigDecimal price) {
        Filter f = spotFilters.get(symbol);
        if (f == null) throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
        validateOrder(f, symbol, qty, price);
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
