package com.mawai.wiibquant.external.defillama;

import com.mawai.wiibquant.external.JsonGetClient;
import org.springframework.stereotype.Component;

import java.net.URI;

/** DefiLlama 稳定币接口（免 key）：每个稳定币的当前、昨日、上周、上月流通市值，按链拆分 */
@Component
public class DefiLlamaClient extends JsonGetClient {

    private static final URI STABLECOINS_URL = URI.create("https://stablecoins.llama.fi/stablecoins");

    public DefiLlamaClient() {
        super("DefiLlama", 15000);
    }

    /** 全部稳定币（约 500KB） */
    public String stablecoins() {
        return getBody(STABLECOINS_URL);
    }
}
