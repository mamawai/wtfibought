package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 断连之后这一轮怎么收场。
 * <p>
 * 语义是"<b>照跑、照攒、照落库，只是不再发帧</b>"：用户切页/刷新只断了 SSE，
 * 后台这轮还在烧他的 token，答案必须进历史——前端回来靠 /status + 历史回放补。
 * 把攒答案那行挪进 {@code if (!channel.isClosed())} 里，代码照跑什么都不报错，
 * 只是断连过的那一轮在历史里变成一条空回答，而且只有真用户切页才复现得出来。
 */
class ChatWorkbenchStreamTest {

    private static final String SESSION = "wb-1-stream";

    /** 记账用的 emitter：断连后 SseChannel 一帧都不该往这儿写 */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> raw = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> {
                if (d.getData() instanceof String s) raw.add(s);
            });
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void 断连后答案照样攒起来落进历史但不再发帧() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        // runner 分两帧把答案交出来，streamer 的 sink 得把它们攒全
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            Consumer<String> sink = inv.getArgument(5);   // leaves/userId/session/message/intent 之后才是答案 sink
            sink.accept("前半段");
            sink.accept("后半段");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        ChatYieldCoordinator coordinator = new ChatYieldCoordinator();
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, historyService,
                mock(WorkbenchRunRegistry.class), coordinator, new ApprovalRegistry(), ChatTestEndpoints.PROMPTS);

        RecordingEmitter emitter = new RecordingEmitter();
        SseChannel channel = new SseChannel(emitter);
        channel.markClosed();   // 用户切页：连接已经断了，这一轮才刚开始

        // run() 要拿叶子清账本、取模型名落库，给不了 null；这条用例不看模型本身，深浅共用一个装饰器
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        ChatAgentFactory.Leaves leaves =
                new ChatAgentFactory.Leaves("test", model, model, Map.of(), null, AgentLang.ZH);

        streamer.run(channel, 1L, SESSION, "看看行情", leaves, coordinator.openTurn(1L), null, null, null);

        // 答案完整进历史——这是断连用户唯一还拿得到东西的途径
        verify(historyService).append(eq(SESSION), eq(1L), eq("assistant"), eq("前半段后半段"), any(), any());
        // 但一帧都没往断掉的通道里写
        assertThat(emitter.raw).isEmpty();
    }
}
