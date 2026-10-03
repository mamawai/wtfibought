package com.mawai.wiibsim.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内公平锁，全用真实对象跑：用户入口不排队、系统入口排队先到先得、占锁的一放手锁就归排队的系统操作。
 * 每个"操作"是一个独立线程，拿锁和解锁在同一个线程里。
 */
class FairLockRegistryTest {

    private static final String KEY = "futures:pos:1";

    private final FairLockRegistry registry = new FairLockRegistry();

    /** 一个独立线程里的操作：拿锁 → 等放行 → 同一线程解锁 */
    private static final class Op {
        final CountDownLatch done = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Lock lock;
        final Thread thread;

        Op(String name, Supplier<Lock> acquire, List<String> acquiredOrder) {
            thread = new Thread(() -> {
                lock = acquire.get();
                if (lock != null && acquiredOrder != null) acquiredOrder.add(name);
                done.countDown();
                if (lock == null) return;
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                lock.unlock();
            }, name);
            thread.start();
        }

        Op(String name, Supplier<Lock> acquire) {
            this(name, acquire, null);
        }

        void finish() throws InterruptedException {
            release.countDown();
            thread.join(15_000);
        }
    }

    private static void waitUntil(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("等待超时：条件未达成");
            Thread.sleep(5);
        }
    }

    private Op holdAsUser() throws InterruptedException {
        Op holder = new Op("holder", () -> registry.tryLockAsUser(KEY));
        assertThat(holder.done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(holder.lock).isNotNull();
        return holder;
    }

    @Test
    void 有人占着_用户入口立即失败不排队() throws Exception {
        Op holder = holdAsUser();

        long t0 = System.nanoTime();
        assertThat(registry.tryLockAsUser(KEY)).isNull();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
        assertThat(((ReentrantLock) holder.lock).hasQueuedThreads()).isFalse();

        holder.finish();
    }

    @Test
    void 不同key互不影响() throws Exception {
        Op holder = holdAsUser();

        Lock other = registry.tryLockAsUser("futures:pos:2");
        assertThat(other).isNotNull();
        other.unlock();

        holder.finish();
    }

    /**
     * 占锁方放手的同一瞬间用户再来：锁要么已归排队的系统操作，要么空着但系统还在队里，用户都拿不到。
     * 是哪种情况看线程调度，跑 20 轮。
     */
    @Test
    void 系统在排队时_后来的用户入口失败_占锁方放手后锁归系统() throws Exception {
        for (int round = 0; round < 20; round++) {
            systemQueuedRound();
        }
    }

    private void systemQueuedRound() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ReentrantLock> raw = new AtomicReference<>();
        AtomicReference<Lock> retryRight = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            Lock lock = registry.tryLockAsUser(KEY);
            raw.set((ReentrantLock) lock);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            lock.unlock();
            // 放手后立刻以用户身份再抢
            Lock again = registry.tryLockAsUser(KEY);
            retryRight.set(again);
            if (again != null) again.unlock();
        });
        holder.start();
        assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();

        Op system = new Op("system", () -> registry.tryLockAsSystem(KEY));
        waitUntil(() -> raw.get().hasQueuedThreads());

        // 锁还占着、系统在排队：别的用户请求同样失败
        assertThat(registry.tryLockAsUser(KEY)).isNull();

        release.countDown();
        holder.join(5_000);
        assertThat(retryRight.get()).isNull();

        assertThat(system.done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(system.lock).isNotNull();
        system.finish();
    }

    @Test
    void 多个系统操作按到达顺序拿到锁() throws Exception {
        Op holder = holdAsUser();
        ReentrantLock raw = (ReentrantLock) holder.lock;
        List<String> order = new CopyOnWriteArrayList<>();

        Op first = new Op("first", () -> registry.tryLockAsSystem(KEY), order);
        waitUntil(() -> raw.getQueueLength() == 1);
        Op second = new Op("second", () -> registry.tryLockAsSystem(KEY), order);
        waitUntil(() -> raw.getQueueLength() == 2);
        first.release.countDown();
        second.release.countDown();

        holder.finish();
        first.thread.join(5_000);
        second.thread.join(5_000);

        assertThat(order).containsExactly("first", "second");
    }
}
