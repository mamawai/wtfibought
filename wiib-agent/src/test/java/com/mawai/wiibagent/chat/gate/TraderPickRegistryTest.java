package com.mawai.wiibagent.chat.gate;

import com.mawai.wiibcommon.entity.AiTrader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 选择卡：点选、超时叫停本轮、点停止不再等、同一批共用一张卡、推卡抛异常不卡死、卡推不出去不等、删会话清理。
 * 工具线程真阻塞在 {@link TraderPickRegistry#pick} 上，所以每个用例都让它跑在另一个线程里。
 */
class TraderPickRegistryTest {

    private static final String SESSION = "wb-1-x";
    private static final List<TraderPickRegistry.Candidate> CANDIDATES = List.of(
            new TraderPickRegistry.Candidate(7L, "Alpha", AiTrader.STATUS_RUNNING, new BigDecimal("1.20")),
            new TraderPickRegistry.Candidate(8L, "Beta", AiTrader.STATUS_PAUSED, new BigDecimal("-3.40")));

    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
    private final TraderPickRegistry registry = new TraderPickRegistry(runRegistry);
    /** 推出去的选择卡 */
    private final List<ObjectNode> cards = new CopyOnWriteArrayList<>();
    /** 第一张卡推出去时完成 */
    private final CompletableFuture<ObjectNode> firstCard = new CompletableFuture<>();
    /** 这一轮的中断信号 */
    private final CompletableFuture<Void> cancel = new CompletableFuture<>();

    @BeforeEach
    void startTurn() {
        runRegistry.start(SESSION, (event, data) -> {
            if ("trader_pick".equals(event)) {
                cards.add(data);
                firstCard.complete(data);
            }
            return true;
        }, cancel);
    }

    @AfterEach
    void finishTurn() {
        runRegistry.finish(SESSION);
    }

    private static AiTrader beta() {
        AiTrader t = new AiTrader();
        t.setId(8L);
        t.setName("Beta");
        return t;
    }

    /** 一个工具线程：在里面发起选择 */
    private record Waiter(Thread thread, CompletableFuture<AiTrader> result) {
    }

    private Waiter pickAsync() {
        CompletableFuture<AiTrader> result = new CompletableFuture<>();
        Thread thread = Thread.startVirtualThread(() -> result.complete(registry.pick(SESSION, CANDIDATES)));
        return new Waiter(thread, result);
    }

    /** 等到这个工具线程停在卡上（挂起在等结果） */
    private static void parked(Waiter waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waiter.thread().getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime()).as("工具线程没停在卡上").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private String requestId() throws Exception {
        return firstCard.get(5, TimeUnit.SECONDS).path("requestId").asString(null);
    }

    /** 卡上带倒计时和候选；点了就放行，并记成本会话的当前 trader；对不上号的回执不算 */
    @Test
    void 点选放行并记成当前() throws Exception {
        Waiter waiter = pickAsync();
        String requestId = requestId();

        assertThat(firstCard.get().path("seconds").asInt()).isEqualTo(TraderPickRegistry.WAIT_SECONDS);
        assertThat(firstCard.get().get("traders")).hasSize(2);
        assertThat(firstCard.get().get("traders").get(1).path("name").asString(null)).isEqualTo("Beta");
        assertThat(registry.complete(SESSION, "别的卡", beta())).isFalse();
        assertThat(registry.complete(SESSION, requestId, beta())).isTrue();

        assertThat(waiter.result().get(5, TimeUnit.SECONDS).getId()).isEqualTo(8L);
        assertThat(registry.current(SESSION)).isEqualTo(8L);
        assertThat(cancel).isNotDone();
    }

    /** 等满了没人选：回 null 并叫停这一轮；之后再点那张卡算过期 */
    @Test
    void 超时没选叫停本轮() throws Exception {
        registry.waitMs = 50;
        Waiter waiter = pickAsync();
        String requestId = requestId();

        assertThat(waiter.result().get(5, TimeUnit.SECONDS)).isNull();
        assertThat(cancel).isDone();
        assertThat(registry.complete(SESSION, requestId, beta())).isFalse();
        assertThat(registry.current(SESSION)).isNull();
    }

    /** 等的时候用户点了停止：马上不等了，不用熬到超时 */
    @Test
    void 点停止马上结束等待() throws Exception {
        Waiter waiter = pickAsync();
        requestId();

        runRegistry.requestCancel(SESSION);

        assertThat(waiter.result().get(5, TimeUnit.SECONDS)).isNull();
    }

    /** 同一批里两个查询都要选：只推一张卡，点一次两个都放行 */
    @Test
    void 同批共用一张卡() throws Exception {
        Waiter first = pickAsync();
        String requestId = requestId();
        Waiter second = pickAsync();
        parked(second);

        assertThat(registry.complete(SESSION, requestId, beta())).isTrue();

        assertThat(first.result().get(5, TimeUnit.SECONDS).getId()).isEqualTo(8L);
        assertThat(second.result().get(5, TimeUnit.SECONDS).getId()).isEqualTo(8L);
        assertThat(cards).hasSize(1);
    }

    /** 推卡时抛了异常：这张卡照样按时收场，同一会话下一次选择不会卡死 */
    @Test
    void 推卡抛异常后下次选择不卡死() throws Exception {
        registry.waitMs = 50;
        AtomicBoolean broken = new AtomicBoolean(true);
        runRegistry.start(SESSION, (event, data) -> {
            if (broken.getAndSet(false)) {
                throw new IllegalStateException("通道写坏了");
            }
            return true;
        }, cancel);

        assertThatThrownBy(() -> registry.pick(SESSION, CANDIDATES)).isInstanceOf(IllegalStateException.class);

        assertThat(pickAsync().result().get(5, TimeUnit.SECONDS)).isNull();
    }

    /** 卡推不出去（通道断了）：不白等，当场算没选到 */
    @Test
    void 卡推不出去不等() {
        runRegistry.finish(SESSION);

        assertThat(registry.pick(SESSION, CANDIDATES)).isNull();
        assertThat(cards).isEmpty();
    }

    /** 删会话：记着的当前 trader 一并清掉 */
    @Test
    void 删会话清掉当前trader() {
        registry.remember(SESSION, 7L);

        registry.purgeSession(SESSION);

        assertThat(registry.current(SESSION)).isNull();
    }
}
