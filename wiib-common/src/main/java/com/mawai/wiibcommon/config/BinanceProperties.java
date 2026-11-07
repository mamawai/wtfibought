package com.mawai.wiibcommon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {
    private String wsUrl;
    private String futuresWsUrl;
    private String restBaseUrl;
    private String futuresRestBaseUrl;
    private List<String> symbols;
    /** bStock（代币化美股）符号：纯现货、无合约。单列一组，只并入现货的价、逐笔成交、盘口订阅，不进合约流和强平流。 */
    private List<String> stockSymbols;
    /** 大宗商品（TradFi 永续：黄金/原油）符号：纯合约、无现货。只并入合约流，绝不进现货流。 */
    private List<String> commoditySymbols;
    /** TradFi 合约（美股/ETF 永续：闪迪/美光/SpaceX 等）符号：纯合约、无现货，流处理同大宗商品。 */
    private List<String> tradfiSymbols;
    /** 纯合约加密币（Binance 主站只有永续没现货，如 HYPE）：不进现货流；5m K线按加密币走（落库、进收盘 Stream），盈亏归 crypto 桶。 */
    private List<String> cryptoFuturesSymbols;

    /** 现货订阅全集 = crypto symbols ∪ bStock stockSymbols。现货的价、逐笔成交、盘口流用；两组互斥，直接拼接不去重。 */
    public List<String> getAllSpotSymbols() {
        if (stockSymbols == null || stockSymbols.isEmpty()) {
            return symbols == null ? List.of() : symbols;
        }
        List<String> all = new ArrayList<>(symbols == null ? List.of() : symbols);
        all.addAll(stockSymbols);
        return all;
    }

    /** 合约订阅全集 = crypto symbols ∪ 纯合约标的（大宗商品 + TradFi + 纯合约加密币）。合约各流用。 */
    public List<String> getAllFuturesSymbols() {
        List<String> all = new ArrayList<>(symbols == null ? List.of() : symbols);
        all.addAll(getFuturesOnlySymbols());
        return all;
    }

    /** 纯合约标的全集（无现货）= 非加密纯合约 ∪ cryptoFuturesSymbols。 */
    public List<String> getFuturesOnlySymbols() {
        List<String> all = getNonCryptoFuturesSymbols();
        if (cryptoFuturesSymbols != null) all.addAll(cryptoFuturesSymbols);
        return all;
    }

    /** 加密币全集 = symbols ∪ cryptoFuturesSymbols。5m K线主连接（落库 + 收盘 Stream）、trader 可选币、启动回补都用这份。 */
    public List<String> getAllCryptoSymbols() {
        List<String> all = new ArrayList<>(symbols == null ? List.of() : symbols);
        if (cryptoFuturesSymbols != null) all.addAll(cryptoFuturesSymbols);
        return all;
    }

    /** 非加密的纯合约标的 = commoditySymbols ∪ tradfiSymbols。只广播不落库的 5m K线副连接用。 */
    public List<String> getNonCryptoFuturesSymbols() {
        List<String> all = new ArrayList<>(commoditySymbols == null ? List.of() : commoditySymbols);
        if (tradfiSymbols != null) all.addAll(tradfiSymbols);
        return all;
    }
}
