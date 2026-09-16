package com.mawai.wiibagent.toolkit;
import com.mawai.wiibquant.market.service.KlineFetcher;

import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndicatorToolkitTest {

    private static KlineBar bar(long t, String o, String h, String l, String c, String v) {
        return new KlineBar(t, t + 299_999L, new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), new BigDecimal(v));
    }

    @Test
    void toCalcRowsKeepsCalcAllContract() {
        List<BigDecimal[]> rows = IndicatorToolkit.toCalcRows(
                List.of(bar(1720000000000L, "100", "110", "95", "105", "1000")));

        assertThat(rows).hasSize(1);
        // calcAll 契约：每行 [high, low, close, volume]
        assertThat(rows.get(0)[0]).isEqualByComparingTo("110");
        assertThat(rows.get(0)[1]).isEqualByComparingTo("95");
        assertThat(rows.get(0)[2]).isEqualByComparingTo("105");
        assertThat(rows.get(0)[3]).isEqualByComparingTo("1000");
    }

    @Test
    void intervalValidation() {
        assertThat(IndicatorToolkit.validateInterval("1h")).isNull();
        assertThat(IndicatorToolkit.validateInterval("15m")).isNull();
        assertThat(IndicatorToolkit.validateInterval("3m")).contains("interval");
        assertThat(IndicatorToolkit.validateInterval(null)).contains("interval");
    }

    @Test
    void compactKlineRowsKeepOhlcvOrder() {
        String out = IndicatorToolkit.toCompactRows(
                List.of(bar(1720000000000L, "100.0", "110", "95", "105", "1000")));

        // [openTime,open,high,low,close,volume]，尾随零砍掉
        assertThat(out).isEqualTo("[[1720000000000,100,110,95,105,1000]]");
    }

    // ==================== kline_structure 工具层 ====================

    /**
     * 合成 Binance 原始格式 K 线（12 字段，parseRawFuturesKlines 只读前 7 个）。
     * 用正弦造起伏，好让 swing 检测真有拐点可找——一条直线测不出摆动点。
     */
    private static String syntheticKlines(int n) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            long t = 1_700_000_000_000L + i * 300_000L;
            double base = 100 + Math.sin(i / 5.0) * 10;
            ArrayNode k = arr.addArray();
            k.add(t);
            k.add(String.valueOf(base));
            k.add(String.valueOf(base + 2));
            k.add(String.valueOf(base - 2));
            k.add(String.valueOf(base + 1));
            k.add(String.valueOf(10 + i));
            k.add(t + 299_999L);
        }
        return MAPPER.writeValueAsString(arr);
    }

    /**
     * 上游最多能给 availableBars 根，且按 limit 截断——与真实 Binance 一致。
     * 不看 limit 的桩会让"两个工具取了不同窗口"这类问题在测试里隐身。
     */
    private static IndicatorToolkit toolkitWith(int availableBars) {
        BinanceRestClient client = mock(BinanceRestClient.class);
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull()))
                .thenAnswer(inv -> syntheticKlines(Math.min(inv.getArgument(2), availableBars)));
        return new IndicatorToolkit(new KlineFetcher(client, 60_000));
    }

    /** 上游给不出数据（熔断 / 网络失败）。 */
    private static IndicatorToolkit toolkitUnavailable() {
        BinanceRestClient client = mock(BinanceRestClient.class);
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull())).thenReturn(null);
        return new IndicatorToolkit(new KlineFetcher(client, 60_000));
    }

    private static ToolCallback callback(IndicatorToolkit toolkit, String name) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(toolkit)
                        .build().getToolCallbacks())
                .filter(c -> name.equals(c.getToolDefinition().name()))
                .findFirst().orElseThrow();
    }

    @Test
    void klineStructureExposesOnlySymbolAndIntervalAsRequired() {
        // 三个调优参数必须是可选的：标成必填会逼模型每轮都猜一个值，
        // 而它们是流派选择，没意见时就该走默认
        JsonNode schema = MAPPER.readTree(
                callback(toolkitWith(80), "kline_structure")
                        .getToolDefinition().inputSchema());

        assertThat(schema.get("required")).extracting(JsonNode::asString).containsExactlyInAnyOrder("symbol", "interval");
        assertThat(schema.get("properties").propertyNames())
                .contains("symbol", "interval", "swingWindow", "lastN", "includeBars");
    }

    @Test
    void klineStructureReturnsNeutralBlocks() {
        String out = toolkitWith(80).klineStructure("BTCUSDT", "5m", null, null, null);
        JsonNode o = MAPPER.readTree(out);

        assertThat(o.get("errors")).isEmpty();
        assertThat(o.propertyNames()).contains("meta", "range", "bar_stats", "swings",
                "segments_equal", "segments_swing", "volume", "volatility", "ma_context",
                "levels", "focus_bars");
        assertThat(o.get("swings")).isNotEmpty();
        // 中性事实层的底线：不许出现判读词
        assertThat(out).doesNotContain("bullish", "bearish", "reversal", "breakout");
    }

    @Test
    void klineStructureAtrMatchesIndicatorsTool() {
        // 两个工具的 atr14 必须逐位相同：ATR 走 Wilder RMA、记忆很长，
        // 样本窗口不同就会算出不同的值，模型两边看到对不上的数字无从判断该信谁。
        // 桩按 limit 截断，这条才验得到两边取的是同一个窗口
        IndicatorToolkit toolkit = toolkitWith(300);

        BigDecimal fromStructure = MAPPER.readTree(
                        toolkit.klineStructure("BTCUSDT", "1h", null, null, false))
                .get("volatility").path("atr14").asDecimal(null);
        BigDecimal fromIndicators = MAPPER.readTree(toolkit.indicators("BTCUSDT", "1h"))
                .path("atr14").asDecimal(null);

        assertThat(fromStructure).isEqualByComparingTo(fromIndicators);
    }

    @Test
    void klineStructureDropsFocusBarsOnRequest() {
        String out = toolkitWith(80)
                .klineStructure("BTCUSDT", "5m", null, null, false);

        assertThat(MAPPER.readTree(out).has("focus_bars")).isFalse();
    }

    @Test
    void klineStructureClampsAbsurdSwingWindow() {
        // 窗口开到比数据还长时 swings 会空，但不许炸——夹取后照常出其余字段
        String out = toolkitWith(80)
                .klineStructure("BTCUSDT", "5m", 9999, -5, null);
        JsonNode o = MAPPER.readTree(out);

        assertThat(o.get("errors")).isEmpty();
        assertThat(o.get("meta").path("swing_window").asInt(0)).isEqualTo(50);
    }

    @Test
    void klineStructureDegradesWhenDataUnavailable() {
        // 熔断/网络失败时 getFuturesKlines 给 null，工具要报不可用而不是抛异常
        String out = toolkitUnavailable().klineStructure("BTCUSDT", "5m", null, null, null);

        assertThat(MAPPER.readTree(out).path("available").asBoolean(false)).isFalse();
    }

    @Test
    void klineStructureRejectsBadInterval() {
        String out = toolkitWith(80).klineStructure("BTCUSDT", "1m", null, null, null);

        assertThat(MAPPER.readTree(out).path("reason").asString(null)).contains("interval");
    }
}
