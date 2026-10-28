package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 整轮失败时推给前端的那句话必须先过 {@code LlmErrorMessages}——
 * 上游原文可能几百字符，还带着 key 和完整请求 URL，直接回显 e.getMessage() 就是把它送进用户浏览器。
 */
class LlmErrorSurfaceTest {

    /** 上游原样抛出来的东西：认得出是 401，同时带着一段绝不能外流的 key */
    private static final String RAW =
            "HTTP 401 Unauthorized: key sk-secret-abcdef123456 rejected by gw.example.com";

    @Test
    void 整轮失败时error事件不回显上游原文() {
        List<String> sent = new ArrayList<>();
        SseEmitter emitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                builder.build().forEach(data -> {
                    if (data.getData() instanceof String text) sent.add(text);
                });
            }
        };
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        doThrow(new RuntimeException(RAW)).when(turnRunner)
                .run(any(), anyLong(), any(), any(), any(), any(), any(), any());
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, mock(ChatHistoryService.class),
                mock(WorkbenchRunRegistry.class), new ApprovalRegistry(), ChatTestEndpoints.PROMPTS);

        // run() 要拿叶子清账本，给不了 null；否则 NPE 会先于 runner 抛的那条上游异常，测的就不是这件事了
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        ChatAgentFactory.Leaves leaves = new ChatAgentFactory.Leaves("test", model, model, null, AgentLang.ZH, 256_000);

        streamer.run(new SseChannel(emitter), 1L, "wb-1-boom", "看看行情", leaves, null, null, false);

        String errorEvent = sent.stream().filter(text -> text.startsWith("{") && text.contains("message"))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(errorEvent).contains("API key").doesNotContain("sk-secret-abcdef123456", "gw.example.com");
    }
}
