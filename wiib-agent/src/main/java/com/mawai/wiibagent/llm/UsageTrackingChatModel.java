package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器：把一次运行里所有模型调用的 token 用量累加起来。
 * 装饰器包在最外层，这么写为了把 ReAct 循环里的每一次调用都收进来。
 * <p>
 * <b>getOptions 必须原样透传</b>：返回自己造的 options 会让 ResilientChatService 挂出去的工具列表变成空数组。
 * <p>
 * 账按轮记在 {@link TokenLedger} 上：调用在发起那一刻抓住当时那本账，终止时记回它。
 * {@link #reset()} 整本换新，晚到的入账（用户中断丢下的在途调用）只会落进旧账本，新一轮天然干净。
 * <p>
 * 两种用法：交易员轨每次唤醒 new 一个实例、用完取 {@link #snapshot()}；
 * 对话轨的实例跟着叶子跨轮缓存，靠 {@link #reset()} 划轮边界（同一用户同时只有一轮，见 ChatConcurrencyGate）。
 */
public class UsageTrackingChatModel implements ChatModel {

    /** 上游没返回 usage 时 token 三项为 null——不能拿 0 冒充"没花钱" */
    public record UsageSnapshot(int modelCalls, Long promptTokens, Long completionTokens, Long totalTokens) {

        /**
         * 两条端点（深/浅）的账合并成一轮的总账。
         * null 语义随字段各算各的：两边都没报才是 null，一边报了就用报了的那份。
         */
        public UsageSnapshot merge(UsageSnapshot other) {
            return new UsageSnapshot(modelCalls + other.modelCalls,
                    sum(promptTokens, other.promptTokens),
                    sum(completionTokens, other.completionTokens),
                    sum(totalTokens, other.totalTokens));
        }

        private static Long sum(Long a, Long b) {
            if (a == null) {
                return b;
            }
            return b == null ? a : a + b;
        }
    }

    /** 一轮的账本。ReAct 循环可能跑在虚拟线程上，读写都加锁 */
    private static final class TokenLedger {

        private int calls;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        /** 本轮丢下过在途调用（用户中断）：它们的 token 要么未知、要么在读数之后才到，这本账不能报 */
        private boolean abandoned;

        synchronized void record(Usage usage) {
            calls++;
            if (usage == null) {
                return;
            }
            promptTokens = plus(promptTokens, usage.getPromptTokens());
            completionTokens = plus(completionTokens, usage.getCompletionTokens());
            totalTokens = plus(totalTokens, usage.getTotalTokens());
        }

        synchronized UsageSnapshot snapshot() {
            return new UsageSnapshot(calls, promptTokens, completionTokens, totalTokens);
        }

        synchronized void markAbandoned() {
            abandoned = true;
        }

        synchronized boolean untrusted() {
            return abandoned;
        }

        /**
         * 按字段各算各的（网关只报一半也不丢），"没报告"与"报了 0"合并成 null。
         * <p>
         * 不能靠 null 判断有没有报告：Spring AI 的 ChatResponse 即使没带 usage 也会给一个全 0 的
         * EmptyUsage，DefaultUsage 还会把 null 归一成 0。所以只认正数——真实调用的 prompt token
         * 不可能是 0，全程没见过正数就是上游压根没报。
         */
        private static Long plus(Long acc, Integer add) {
            if (add == null || add <= 0) {
                return acc;
            }
            return acc == null ? add.longValue() : acc + add;
        }
    }

    private final ChatModel delegate;
    /** 当前这一轮的账本；reset 整本换掉，不清字段 */
    private volatile TokenLedger ledger = new TokenLedger();

    public UsageTrackingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public @NonNull ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public @NonNull ChatResponse call(@NonNull Prompt prompt) {
        TokenLedger book = ledger;
        ChatResponse response = delegate.call(prompt);
        book.record(usageOf(response));
        return response;
    }

    @Override
    public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
        // defer：每次订阅（ResilientChatService 的流式重试会重订阅）各自一套 last/recorded，各自入账一次
        return Flux.defer(() -> {
            TokenLedger book = ledger;
            // 流式的 usage 只挂在最后一个 chunk 上，且通常是本次调用的累计值，逐块相加会翻倍。
            // 只留最后见到的那份，流终止时入账一次。
            AtomicReference<Usage> last = new AtomicReference<>();
            AtomicBoolean recorded = new AtomicBoolean();
            return delegate.stream(prompt)
                    .doOnNext(r -> last.set(usageOf(r)))
                    // 入账必须赶在终止信号传给下游之前：消费方一收到 onComplete 就会去读 snapshot()，
                    // 而 doFinally 是信号传播完才跑的——那一次（往往正是最贵的汇总）会漏记
                    .doOnTerminate(() -> recordOnce(book, recorded, last))
                    // 取消不经 doOnTerminate，但 token 照样是真烧掉的，兜在这儿；CAS 保证同一次订阅只入账一遍
                    .doFinally(sig -> recordOnce(book, recorded, last));
        });
    }

    private static void recordOnce(TokenLedger book, AtomicBoolean recorded, AtomicReference<Usage> last) {
        if (recorded.compareAndSet(false, true)) {
            book.record(last.get());
        }
    }

    @Override
    public String call(@NonNull String message) {
        return Objects.requireNonNull(call(new Prompt(message)).getResult()).getOutput().getText();
    }

    @Override
    public String call(@NonNull Message @NonNull ... messages) {
        return Objects.requireNonNull(call(new Prompt(Arrays.asList(messages))).getResult()).getOutput().getText();
    }

    /** 本轮累计 */
    public UsageSnapshot snapshot() {
        return ledger.snapshot();
    }

    /**
     * 换一本新账，划出新一轮的起点。
     * <p>
     * 给"实例跨轮复用"的对话轨用：那边模型被烤进编译好的叶子图、图又按配置指纹缓存，
     * 拿不到"每轮 new 一个"的机会，只能在轮开头换账本。交易员轨每轮新建，不需要调它。
     */
    public void reset() {
        ledger = new TokenLedger();
    }

    /**
     * 标记"这一轮丢下了在途调用"（用户中断时会发生）：答案流被掐断后收尾帧没到、token 未知；
     * 专家批次是阻塞调用掐不断，它们的账在读数之后才到。两种都让本轮的账不能报，见 {@link #untrusted()}。
     * 下一轮不受影响：账本已换新，晚到的只会写进旧的那本。
     */
    public void markAbandoned() {
        ledger.markAbandoned();
    }

    /** 账本被丢下的调用写脏了：宁可不报，也别报个错的（与全站 token null≠0 同口径） */
    public boolean untrusted() {
        return ledger.untrusted();
    }

    private static Usage usageOf(ChatResponse response) {
        return response == null ? null : response.getMetadata().getUsage();
    }
}
