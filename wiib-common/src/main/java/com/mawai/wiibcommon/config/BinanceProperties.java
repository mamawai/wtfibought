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
    /** bStock（代币化美股）符号：纯现货、无合约。单列一组，只并入现货价/现货K线订阅，绝不进合约/深度/强平流。 */
    private List<String> stockSymbols;
    /** 大宗商品（TradFi 永续：黄金/原油）符号：纯合约、无现货。只并入合约流，绝不进现货流。 */
    private List<String> commoditySymbols;
    private long fallbackPollInterval;

    /** 现货订阅全集 = crypto symbols ∪ bStock stockSymbols。Spot 价流与现货 K线用；两组互斥，直接拼接不去重。 */
    public List<String> getAllSpotSymbols() {
        if (stockSymbols == null || stockSymbols.isEmpty()) {
            return symbols == null ? List.of() : symbols;
        }
        List<String> all = new ArrayList<>(symbols == null ? List.of() : symbols);
        all.addAll(stockSymbols);
        return all;
    }

    /** 合约订阅全集 = crypto symbols ∪ commoditySymbols（TradFi 永续：金/油，无现货）。合约各流用。 */
    public List<String> getAllFuturesSymbols() {
        if (commoditySymbols == null || commoditySymbols.isEmpty()) {
            return symbols == null ? List.of() : symbols;
        }
        List<String> all = new ArrayList<>(symbols == null ? List.of() : symbols);
        all.addAll(commoditySymbols);
        return all;
    }
}
