package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 同一个用户的命中按碰到的先后一个个执行（同一格止损在前），不同用户并发 */
class TradeHitTest {

    @Test
    void 同一用户按碰到先后执行_同一格止损在前() {
        List<String> done = new CopyOnWriteArrayList<>();

        TradeHit.runByUser(List.of(
                new TradeHit(2, false, 7L, () -> done.add("第2格止盈")),
                new TradeHit(0, false, 7L, () -> done.add("第0格限价")),
                new TradeHit(2, true, 7L, () -> done.add("第2格止损"))));

        assertThat(done).containsExactly("第0格限价", "第2格止损", "第2格止盈");
    }

    @Test
    void 一项抛异常不连累这个用户后面的项() {
        List<String> done = new CopyOnWriteArrayList<>();

        TradeHit.runByUser(List.of(
                new TradeHit(0, true, 7L, () -> { throw new IllegalStateException("放回索引时库又抖了"); }),
                new TradeHit(1, false, 7L, () -> done.add("第1格止盈"))));

        assertThat(done).containsExactly("第1格止盈");
    }

    @Test
    void 不同用户互不等() {
        CountDownLatch otherUserRan = new CountDownLatch(1);
        AtomicBoolean sawOther = new AtomicBoolean();

        // 用户 7 先排，要是串行执行就会一直等到超时
        TradeHit.runByUser(List.of(
                new TradeHit(0, false, 7L, () -> {
                    try {
                        sawOther.set(otherUserRan.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }),
                new TradeHit(1, false, 8L, otherUserRan::countDown)));

        assertThat(sawOther).isTrue();
    }
}
