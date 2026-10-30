package com.mawai.wiibquant.external.tftc;

import com.mawai.wiibquant.external.JsonGetClient;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * TFTC 的美国 BTC 现货 ETF 资金流（CC BY 4.0，免 key）：2024-01-11 上市起每个交易日一行，
 * 每天随发行商披露更新两次。上游是 SoSoValue 和 Farside 整理的发行商数据。
 */
@Component
public class TftcClient extends JsonGetClient {

    private static final URI DATA_URL = URI.create("https://www.tftc.io/bitcoin-etf-flows/data.json");

    public TftcClient() {
        super("TFTC", 15000);
    }

    /** 全部交易日的序列（约 200KB） */
    public String etfFlows() {
        return getBody(DATA_URL);
    }
}
