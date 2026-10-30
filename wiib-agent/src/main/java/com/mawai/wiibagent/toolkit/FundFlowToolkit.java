package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.market.service.FundFlowService;
import com.mawai.wiibquant.market.service.FundFlowService.EtfFlows;
import com.mawai.wiibquant.market.service.FundFlowService.Stablecoins;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 资金流工具（chat 主 agent 挂）：BTC 现货 ETF 每日净流入 + 美元稳定币市值增减，取 FundFlowService 缓存的那份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundFlowToolkit {

    private final FundFlowService fundFlows;

    // 描述按语言取自词表 tool.fund_flows，注解这份只当词表缺失时的兜底
    @Tool(name = "fund_flows", description = """
            Money-flow snapshot, two parts, both daily data (amounts in millions of USD, "Musd"):
            - btcSpotEtf: US spot Bitcoin ETFs (TFTC, compiled from issuer disclosures). updatedThrough = the
              last session in the data; daily = net flow per session for the last 10 sessions;
              cumulativeNetFlowMusd = net inflow since the January 2024 launch; totalNetAssetsMusd = total
              net assets; latestPerEtfMusd = the last session's net flow per fund, keyed by fund ticker
              (IBIT, FBTC, GBTC, ...; "BTC" there is the Grayscale Bitcoin Mini Trust, not bitcoin itself).
              Bitcoin ETFs only; ETH ETF flows are not included.
            - stablecoins: USD-pegged stablecoin circulating supply (DefiLlama). total = all USD stablecoins,
              usdt / usdc each: mcapMusd = current supply, change7dMusd / change30dMusd = change vs 7 / 30
              days ago. Net issuance = fiat entering crypto, net redemption = leaving.
            A part that could not be fetched is listed in `unavailable`. attribution is the ETF data's
            source line.""")
    public String fundFlows() {
        EtfFlows etf = fundFlows.etfFlows();
        Stablecoins stables = fundFlows.stablecoins();
        log.info("[FundFlowTool] fund_flows etf={} stablecoins={}", etf != null, stables != null);
        ObjectNode out = MAPPER.createObjectNode();
        if (etf == null && stables == null) {
            out.put("available", false);
            out.put("reason", "fund flow data unavailable");
            return MAPPER.writeValueAsString(out);
        }
        out.put("available", true);
        out.put("timezone", "UTC+8");
        out.put("now", BeijingTime.format(System.currentTimeMillis()));
        List<String> unavailable = new ArrayList<>();
        if (etf == null) {
            unavailable.add("btcSpotEtf");
        } else {
            out.set("btcSpotEtf", MAPPER.valueToTree(etf));
        }
        if (stables == null) {
            unavailable.add("stablecoins");
        } else {
            out.set("stablecoins", MAPPER.valueToTree(stables));
        }
        if (!unavailable.isEmpty()) {
            out.set("unavailable", MAPPER.valueToTree(unavailable));
        }
        return MAPPER.writeValueAsString(out);
    }
}
