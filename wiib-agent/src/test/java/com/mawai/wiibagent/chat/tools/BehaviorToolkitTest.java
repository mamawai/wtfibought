package com.mawai.wiibagent.chat.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行为分析工具的三条契约：
 * <ol>
 *   <li>整份报告只走 SSE 给卡片，回模型的是<b>裁剪版</b>——30 天逐日快照对模型是纯噪音</li>
 *   <li>卡推没推出去要如实答给模型（cardShown），否则模型会说"报告已展示"而用户屏幕上一片空白</li>
 *   <li>userId 烤死在实例里，工具没有任何参数——有参数就等于允许模型分析别人的账户</li>
 * </ol>
 */
class BehaviorToolkitTest {

    private static final long USER = 7L;

    private final BehaviorAnalysisService service = mock(BehaviorAnalysisService.class);
    private final WorkbenchRunRegistry registry = mock(WorkbenchRunRegistry.class);
    private final BehaviorToolkit toolkit =
            new BehaviorToolkit(mock(ChatModel.class), service, registry, USER, AgentLang.ZH);

    private static BehaviorAnalysisReport report() {
        BehaviorAnalysisReport r = new BehaviorAnalysisReport();
        BehaviorAnalysisReport.Overview overview = new BehaviorAnalysisReport.Overview();
        overview.setTotalAssets(new BigDecimal("12345.67"));
        overview.setTotalProfitPct(new BigDecimal("23.45"));
        BehaviorAnalysisReport.AssetDistribution d = new BehaviorAnalysisReport.AssetDistribution();
        d.setCategory("crypto");
        d.setValue(new BigDecimal("1000"));
        overview.setDistribution(List.of(d));
        BehaviorAnalysisReport.AssetTrend t = new BehaviorAnalysisReport.AssetTrend();
        t.setDate("2026-08-01");
        t.setTotalAssets(new BigDecimal("11000"));
        overview.setTrend(List.of(t));
        r.setOverview(overview);
        r.setSuggestions(List.of("少用点杠杆"));
        return r;
    }

    private void succeeds() {
        when(service.analyze(anyLong(), any(), any(), any())).thenReturn(Result.ok(report()));
    }

    /** 生产里会话号由 ChatTurnRunner 传给 ReactLoop，循环执行工具时经 ToolContext 交给工具；这里手动摆一个 */
    private static ToolContext ctx(String sessionId) {
        return new ToolContext(sessionId == null ? Map.of() : Map.of(ToolRunContext.SESSION_KEY, sessionId));
    }

    @Test
    void 整份报告推给卡片_回模型的砍掉逐日快照() {
        succeeds();
        when(registry.publishBehaviorReport(any(), any())).thenReturn(true);

        JSONObject out = JSON.parseObject(toolkit.analyzeMyBehavior(ctx(null)));

        ArgumentCaptor<JSONObject> card = ArgumentCaptor.forClass(JSONObject.class);
        verify(registry).publishBehaviorReport(any(), card.capture());
        // 卡片要拿到 trend：那是它画资产曲线的全部依据
        assertThat(card.getValue().getJSONObject("overview").getJSONArray("trend")).isNotEmpty();

        assertThat(out.getString("status")).isEqualTo("OK");
        assertThat(out.getBooleanValue("cardShown")).isTrue();
        JSONObject overview = out.getJSONObject("report").getJSONObject("overview");
        assertThat(overview.containsKey("trend")).as("逐日快照不该喂给模型").isFalse();
        // 裁剪只砍 trend，别把结论一起砍了
        assertThat(overview.getBigDecimal("totalAssets")).isEqualByComparingTo("12345.67");
        assertThat(out.getJSONObject("report").getJSONArray("suggestions")).isNotEmpty();
    }

    @Test
    void 卡片推不出去时如实告诉模型() {
        succeeds();
        // 断连/补答轮/会话已结束都走这条
        when(registry.publishBehaviorReport(any(), any())).thenReturn(false);

        JSONObject out = JSON.parseObject(toolkit.analyzeMyBehavior(ctx(null)));

        assertThat(out.getString("status")).isEqualTo("OK");
        assertThat(out.getBooleanValue("cardShown")).isFalse();
        // 数据照给：卡没了，模型得靠这份自己把结论讲出来
        assertThat(out.getJSONObject("report")).isNotNull();
    }

    @Test
    void 分析失败_不推卡且回执原样交给模型() {
        when(service.analyze(anyLong(), any(), any(), any()))
                .thenReturn(Result.fail("当前分析人数已满，请稍后再试"));

        JSONObject out = JSON.parseObject(toolkit.analyzeMyBehavior(ctx(null)));

        assertThat(out.getString("status")).isEqualTo("FAILED");
        assertThat(out.getString("message")).isEqualTo("当前分析人数已满，请稍后再试");
        verify(registry, never()).publishBehaviorReport(any(), any());
    }

    /** 分析对象只能是工具实例里烤死的那个 userId，模型说了不算 */
    @Test
    void 只分析自己_userId不经模型() {
        succeeds();

        toolkit.analyzeMyBehavior(ctx(null));

        verify(service).analyze(eq(USER), eq(AgentLang.ZH), any(), any());
        // ToolContext 是框架注入的、不进 schema，模型看不见；除它之外不许有任何参数
        assertThat(BehaviorToolkit.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("analyzeMyBehavior"))
                .allSatisfy(m -> assertThat(m.getParameterTypes())
                        .as("工具一旦有模型可填的参数，模型就能填别人的 id").containsOnly(ToolContext.class));
    }

    /** 进度推给对话通道：几十秒静默期里用户只有这几行字可看 */
    @Test
    void 阶段进度经会话通道推出去() {
        when(service.analyze(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> sink = inv.getArgument(3);
            sink.accept("正在采集你的行为数据 3/10");
            return Result.ok(report());
        });

        toolkit.analyzeMyBehavior(ctx("s-1"));

        verify(registry).publishProgress(eq("s-1"), anyString());
    }
}
