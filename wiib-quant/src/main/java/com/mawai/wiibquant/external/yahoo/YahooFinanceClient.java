package com.mawai.wiibquant.external.yahoo;

import com.mawai.wiibquant.external.JsonGetClient;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Yahoo Finance 图表接口（非官方、免 key）：美股指数与期货、美元指数、美债、VIX、黄金、原油的报价 */
@Component
public class YahooFinanceClient extends JsonGetClient {

    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";

    public YahooFinanceClient() {
        super("Yahoo", 10000);
    }

    /** 一个代码最近 5 个交易日的日线：meta 里有现价、相对昨收的涨跌幅、5 日窗口前一天的收盘 */
    public String chart5d(String symbol) {
        return getBody(UriComponentsBuilder.fromUriString(BASE_URL).pathSegment(symbol)
                .queryParam("range", "5d").queryParam("interval", "1d")
                .build().encode().toUri());
    }
}
