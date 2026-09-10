package com.mawai.wiibagent.chat;

import com.mawai.wiibagent.controller.ChatWorkbenchController;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibcommon.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门开了之后 {@code /chat} 对所有登录用户可用，入口靠三道准入把关：没配置 / 建不出模型 / 没名额。
 * <p>
 * 三道都得在把 emitter 交给 MVC 之前拦住——交出去响应就成了 event-stream，错误只能推 error 事件，
 * 前端拿不到 code，也就没法把"去配置端点"和"稍后再试"区别对待。所以这里断的是抛出的业务错误码。
 * <p>
 * 错误码写死成 2201-2204 的字面量：它是前端契约，而且 Java 枚举<b>不校验 code 重复</b>，
 * 谁哪天顺手写成 1600 段（Crypto 占着）编译测试全过，只在运行时把"无法获取实时价格"
 * 弹成"去配置 LLM 端点"。
 */
class ChatWorkbenchAdmissionTest {

    private final ChatAgentFactory factory = mock(ChatAgentFactory.class);
    private final LlmEndpointService llmConfigService = mock(LlmEndpointService.class);
    private final ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);

    private ChatWorkbenchController controller(ChatConcurrencyGate gate) {
        return controller(gate, Executors.newVirtualThreadPerTaskExecutor());
    }

    private ChatWorkbenchController controller(ChatConcurrencyGate gate, ExecutorService streamExecutor) {
        // mock runner 默认返回 null，streamer 会在 result.cancelled() 上 NPE——真跑到 run 的用例要正常收尾
        when(turnRunner.run(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatTurnRunner.TurnResult.COMPLETED);
        ChatHistoryService history = mock(ChatHistoryService.class);
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        ApprovalRegistry approvals = new ApprovalRegistry();
        ChatYieldCoordinator coordinator = new ChatYieldCoordinator();
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, runRegistry, coordinator,
                approvals, ChatTestEndpoints.PROMPTS);
        return new ChatWorkbenchController(factory, llmConfigService, approvals,
                history, mock(ChatContextStore.class), streamer, mock(ChatTurnRewinder.class),
                runRegistry, gate, new MessageCatalog(), coordinator,
                ChatTestEndpoints.PROMPTS, ChatTestEndpoints.zhLang(), streamExecutor);
    }

    private static void chat(ChatWorkbenchController controller, long userId) {
        ChatWorkbenchController.WorkbenchChatRequest request =
                new ChatWorkbenchController.WorkbenchChatRequest();
        request.setMessage("深度研判 BTC");
        controller.chat(userId, request, mock(HttpServletResponse.class));
    }

    private static void assertRejectedWithCode(int code, ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", code);
    }

    /**
     * 被拒的请求一个名额都不许占。闸门排在配置检查<b>前面</b>的话，
     * 没配置的用户每点一次发送就吃掉一个全局名额且永不归还（那条路上根本走不到 release），
     * 满 10 次之后 /chat 对所有人回 2204、重启才能恢复。
     * 限额取 1 就是为了让漏掉的那一个立刻现形
     */
    @Test
    void 没配置端点时拒绝并给出配置缺失码() {
        when(llmConfigService.chatEndpoints(1L)).thenReturn(null);
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);

        assertRejectedWithCode(2201, () -> chat(controller(gate), 1L));

        assertThat(gate.tryAcquire(9L)).as("被拒的请求不能占住名额").isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 配置能过保存校验但仍可能建不出模型（协议对不上等），这类错误必须在建流前暴露 */
    @Test
    void 建不出模型时拒绝并给出配置无效码() {
        when(llmConfigService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        when(factory.leavesFor(any(), any())).thenThrow(new IllegalStateException("对话叶子构建失败"));
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);

        assertRejectedWithCode(2202, () -> chat(controller(gate), 1L));

        assertThat(gate.tryAcquire(9L)).as("被拒的请求不能占住名额").isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 拒因要分得清：这条给的是"你已有一轮在跑"，不是下面那条"人满了" */
    @Test
    void 本人已有一轮在跑时拒绝() {
        when(llmConfigService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        gate.tryAcquire(1L); // 这个用户自己的上一轮还占着名额

        assertRejectedWithCode(2203, () -> chat(controller(gate), 1L));
    }

    @Test
    void 全局名额满时拒绝() {
        when(llmConfigService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);
        gate.tryAcquire(2L); // 唯一的名额被别人占着

        assertRejectedWithCode(2204, () -> chat(controller(gate), 1L));
    }

    /**
     * 名额漏了是全套设计里唯一不可恢复的失败模式：漏满就对所有人永久拒绝。
     * 这条钉的是那个唯一的泄漏窗口——名额已经拿到、任务却没提交出去。
     */
    @Test
    void 任务提交失败时当场还回名额() {
        when(llmConfigService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        when(factory.leavesFor(any(), any())).thenReturn(null); // 跑不到用它的那一步
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);
        ExecutorService closed = Executors.newVirtualThreadPerTaskExecutor();
        closed.shutdown(); // 关掉的执行器 execute 必被拒
        ChatWorkbenchController controller = controller(gate, closed);

        assertThatThrownBy(() -> chat(controller, 1L)).isInstanceOf(RejectedExecutionException.class);

        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 一轮正常跑完也要还，否则同一个人第二句话就再也发不出去了 */
    @Test
    void 一轮跑完把名额还回去() throws Exception {
        when(llmConfigService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        when(factory.leavesFor(any(), any())).thenReturn(null);   // runner 是 mock，一帧不吐就返回
        CountDownLatch released = new CountDownLatch(1);
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1) {
            @Override
            public void release(long userId) {
                super.release(userId);
                released.countDown();
            }
        };

        chat(controller(gate), 1L);

        assertThat(released.await(30, TimeUnit.SECONDS)).as("名额被还回来").isTrue();
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

}
