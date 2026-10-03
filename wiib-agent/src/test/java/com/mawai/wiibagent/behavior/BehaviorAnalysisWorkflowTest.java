package com.mawai.wiibagent.behavior;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.external.sim.SimInternalClient;
import com.openai.errors.OpenAIInvalidDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行为分析改成一次性 workflow 后的契约。
 * <p>最值钱的一条是"7 个端点一个都不能少"——ReAct 版本给不了这个保证：模型漏调一个工具没人知道，
 * 报告照出，只是那一维凭空编。
 */
class BehaviorAnalysisWorkflowTest {

    private static final long USER = 7L;

    /** 与 BehaviorDataCollector.ENDPOINTS 一一对应，顺序即 prompt 段序 */
    private static final List<String> ENDPOINTS = List.of(
            "user-profile", "portfolio-summary", "asset-snapshots", "crypto-stats", "bstock-stats",
            "futures-stats", "prediction-stats");

    private static final List<String> SECTION_NAMES = List.of(
            "用户基础信息", "实时资产概览", "近30日资产快照", "加密货币交易统计", "bStock(代币化美股)交易统计",
            "合约交易统计", "Prediction统计");

    private final SimInternalClient simClient = mock(SimInternalClient.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final BehaviorAnalysisWorkflow workflow =
            new BehaviorAnalysisWorkflow(new BehaviorDataCollector(simClient), new PromptCatalog());

    private static final String MODEL_REPLY = "{\"overview\":{}}";

    @BeforeEach
    void setUp() {
        // 每段 JSON 带上自己的来源路径，方便断言"这一段确实是这个端点的"
        when(simClient.getJson(anyString())).thenAnswer(inv -> jsonFrom(inv.getArgument(0)));
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(MODEL_REPLY));
    }

    private static String path(String endpoint) {
        return "/internal/behavior/" + USER + "/" + endpoint;
    }

    private static String jsonFrom(String path) {
        return "{\"from\":\"" + path + "\"}";
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private Prompt capturedPrompt() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).call(captor.capture());
        return captor.getValue();
    }

    private static String textOf(Prompt prompt, Class<? extends Message> type) {
        return prompt.getInstructions().stream()
                .filter(type::isInstance)
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void 七个端点一个都不能少_且各自的数据都进了prompt() {
        workflow.run(chatModel, USER, AgentLang.ZH, null);

        for (String endpoint : ENDPOINTS) {
            verify(simClient).getJson(path(endpoint));
        }
        verify(simClient, times(ENDPOINTS.size())).getJson(anyString());

        String prompt = textOf(capturedPrompt(), UserMessage.class);
        for (int i = 0; i < ENDPOINTS.size(); i++) {
            assertThat(prompt).contains(SECTION_NAMES.get(i));
            assertThat(prompt).contains(jsonFrom(path(ENDPOINTS.get(i))));
        }
    }

    @Test
    void 只调一次LLM() {
        workflow.run(chatModel, USER, AgentLang.ZH, null);

        // 精确 1 次：多调一次、或退回循环，都会红
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void 单个端点失败_其余段照常进prompt且报告仍出得来() {
        // SimInternalClient 失败返回错误 JSON 不抛，这里照搬它的返回形状
        String errorJson = "{\"error\":\"sim internal api 调用失败: Connection refused\"}";
        when(simClient.getJson(path("crypto-stats"))).thenReturn(errorJson);

        String text = workflow.run(chatModel, USER, AgentLang.ZH, null);

        String prompt = textOf(capturedPrompt(), UserMessage.class);
        // 挂掉那段原样带进去：让模型知道这块没数据，而不是整份报告作废
        assertThat(prompt).contains(errorJson);
        assertThat(prompt).contains(SECTION_NAMES);
        assertThat(text).isEqualTo(MODEL_REPLY);
    }

    /** 韧性挂在 ResilientChatService 上，退化成裸 chatModel.call 就没了——这里用它独有的补救行为钉住。 */
    @Test
    void 走韧性层而不是裸调模型() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new OpenAIInvalidDataException("Error reading response",
                        new IOException("stream was reset: CANCEL")))
                .thenReturn(responseOf(MODEL_REPLY));

        assertThat(workflow.run(chatModel, USER, AgentLang.ZH, null)).isEqualTo(MODEL_REPLY);
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    /**
     * 采集是并发的，进度按"完成一段推一次"给，末尾再补一条"开始生成报告"——
     * 那一条盖住的是最长的一段静默（一次大 prompt 的模型调用），丢了用户会以为卡死。
     * <p>进度现在推给对话 SSE 给用户看（以前只进日志），所以文案必须按语言取词，见下一条。
     */
    @Test
    void 每采完一段推一次进度_最后补一条生成中() {
        List<String> steps = Collections.synchronizedList(new ArrayList<>());

        workflow.run(chatModel, USER, AgentLang.ZH, steps::add);

        assertThat(steps).hasSize(ENDPOINTS.size() + 1);
        assertThat(steps.subList(0, ENDPOINTS.size()))
                .allMatch(s -> s.contains("/" + ENDPOINTS.size()));
        assertThat(steps.getLast()).isEqualTo("数据已齐，正在生成行为分析报告");
    }

    /** 进度文案是给用户看的字，英文用户不许收到中文 */
    @Test
    void 英文用户的进度文案里不许有一个中文字() {
        List<String> steps = Collections.synchronizedList(new ArrayList<>());

        workflow.run(chatModel, USER, AgentLang.EN, steps::add);

        assertThat(steps).hasSize(ENDPOINTS.size() + 1);
        assertThat(steps).allMatch(s -> !hasChinese(s), "英文进度里混进了中文");
        assertThat(steps.getLast()).isEqualTo("Data is in, writing the behaviour report");
    }

    /** 输出结构全靠系统提示里的 JSON Schema，掉了就只剩一段自由发挥的文本 */
    @Test
    void 系统指令带着输出schema一起下发() {
        workflow.run(chatModel, USER, AgentLang.ZH, null);

        String system = textOf(capturedPrompt(), SystemMessage.class);
        assertThat(system).contains("用户行为分析师")
                .contains("\"tradeBehavior\"")
                .contains("\"riskProfile\"")
                .contains("\"suggestions\"");
    }

    /**
     * 英文用户：系统指令、开场白、段标题一个不落全换英文——这条是"全英文就全英文"的验收。
     * 段标题最容易漏（它在采集器那边），漏了就是一份英文提示词里插十行中文段名。
     */
    @Test
    void 英文用户的提示词里不许有一个中文字() {
        workflow.run(chatModel, USER, AgentLang.EN, null);

        Prompt prompt = capturedPrompt();
        String system = textOf(prompt, SystemMessage.class);
        String user = textOf(prompt, UserMessage.class);

        assertThat(system).contains("user-behaviour analyst").contains("\"riskProfile\"");
        assertThat(user).contains("user #" + USER).contains("User basics");
        assertThat(hasChinese(system)).as("英文系统指令里混进了中文").isFalse();
        assertThat(hasChinese(user)).as("英文用户消息里混进了中文").isFalse();
        // schema 与数据段照旧，换语言不能把结构约束换没了
        assertThat(user).contains(jsonFrom(path("futures-stats")));
    }

    private static boolean hasChinese(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }
}
