package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交易过滤器注册表（对齐Binance exchangeInfo）：
 * <ol>
 *   <li>开仓校验三连：低于minQty拒、步长不整拒、名义额不足拒，合规放行</li>
 *   <li>reduce-only 豁免：平仓/卖出不查名义额；全量平出连步长都豁免（存量尘埃仓能清干净）</li>
 *   <li>官方刷新：正常响应按 symbol 覆盖默认；地理拦截/挂掉沿用默认快照，未配置 symbol 不混入</li>
 * </ol>
 */
class TradeFilterRegistryTest {

    private TradeFilterRegistry defaultRegistry() {
        return new TradeFilterRegistry(mock(BinanceRestClient.class));
    }

    @Test
    void 合约开仓_低于最小数量_步长不整_名义额不足_全拒() {
        TradeFilterRegistry reg = defaultRegistry();

        // BTC 合约：step/minQty 0.001，minNotional 50
        assertThatThrownBy(() -> reg.validateFuturesOrder("BTCUSDT", new BigDecimal("0.0005"), new BigDecimal("100000")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.TRADE_STEP_INVALID.getCode());
        assertThatThrownBy(() -> reg.validateFuturesOrder("BTCUSDT", new BigDecimal("0.0015"), new BigDecimal("100000")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.TRADE_STEP_INVALID.getCode());
        // 0.001 × 40000 = 40 < 50
        assertThatThrownBy(() -> reg.validateFuturesOrder("BTCUSDT", new BigDecimal("0.001"), new BigDecimal("40000")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.TRADE_MIN_NOTIONAL.getCode());

        assertThatCode(() -> reg.validateFuturesOrder("BTCUSDT", new BigDecimal("0.001"), new BigDecimal("60000")))
                .doesNotThrowAnyException();
        // 未配置 symbol 不设限
        assertThatCode(() -> reg.validateFuturesOrder("PEPEUSDT", new BigDecimal("0.000001"), BigDecimal.ONE))
                .doesNotThrowAnyException();
    }

    @Test
    void 平仓reduceOnly_豁免名义额_全量平出豁免步长() {
        TradeFilterRegistry reg = defaultRegistry();

        // 尘埃仓位 0.00234（旧步长时代开出）全量平出：步长豁免
        BigDecimal dust = new BigDecimal("0.00234");
        assertThatCode(() -> reg.validateFuturesClose("BTCUSDT", dust, dust)).doesNotThrowAnyException();
        // 部分平仓步长必须对齐
        assertThatThrownBy(() -> reg.validateFuturesClose("BTCUSDT", new BigDecimal("0.0015"), new BigDecimal("0.01")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.TRADE_STEP_INVALID.getCode());
        // 部分平仓对齐即可，名义额不设限（0.001×任何价都放行）
        assertThatCode(() -> reg.validateFuturesClose("BTCUSDT", new BigDecimal("0.001"), new BigDecimal("0.01")))
                .doesNotThrowAnyException();
    }

    @Test
    void 现货买卖_买入全查_全量卖出豁免() {
        TradeFilterRegistry reg = defaultRegistry();

        // SOL 现货 step 0.001（项目旧值 0.01 已过粗），DOGE 现货 minNotional=1
        assertThatCode(() -> reg.validateSpotBuy("SOLUSDT", new BigDecimal("0.005"), new BigDecimal("2000")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> reg.validateSpotBuy("DOGEUSDT", new BigDecimal("2"), new BigDecimal("0.4")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.TRADE_MIN_NOTIONAL.getCode());

        BigDecimal holding = new BigDecimal("0.00203");
        assertThatCode(() -> reg.validateSpotSell("SOLUSDT", holding, holding)).doesNotThrowAnyException();
        assertThatThrownBy(() -> reg.validateSpotSell("SOLUSDT", new BigDecimal("0.0005"), holding))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.TRADE_STEP_INVALID.getCode());
    }

    @Test
    void 官方刷新_正常覆盖_异常响应沿用默认() {
        BinanceRestClient client = mock(BinanceRestClient.class);
        // 构造官方响应：BTC 步长改 0.01、minNotional 改 100；夹带未配置的 PEPE 不得混入
        when(client.getFuturesExchangeInfo()).thenReturn("""
                {"symbols":[
                  {"symbol":"BTCUSDT","filters":[
                    {"filterType":"LOT_SIZE","stepSize":"0.010","minQty":"0.010"},
                    {"filterType":"MIN_NOTIONAL","notional":"100"}]},
                  {"symbol":"PEPEUSDT","filters":[
                    {"filterType":"LOT_SIZE","stepSize":"1","minQty":"1"},
                    {"filterType":"MIN_NOTIONAL","notional":"5"}]}
                ]}""");
        // 现货侧模拟地理拦截响应（无 symbols 字段）→ 沿用默认
        when(client.getSpotExchangeInfo(anyList())).thenReturn("{\"code\":0,\"msg\":\"restricted\"}");

        TradeFilterRegistry reg = new TradeFilterRegistry(client);
        reg.refreshFromBinance();

        assertThat(reg.allFutures().get("BTCUSDT").stepSize()).isEqualByComparingTo("0.01");
        assertThat(reg.allFutures().get("BTCUSDT").minNotional()).isEqualByComparingTo("100");
        // 未返回的 symbol 保留默认；未配置的 symbol 不混入
        assertThat(reg.allFutures().get("ETHUSDT").minNotional()).isEqualByComparingTo("20");
        assertThat(reg.allFutures()).doesNotContainKey("PEPEUSDT");
        // 现货整体失败 → 默认快照原样
        assertThat(reg.allSpot().get("SOLUSDT").stepSize()).isEqualByComparingTo("0.001");
        assertThat(reg.allSpot().get("DOGEUSDT").minNotional()).isEqualByComparingTo("1");
    }
}
