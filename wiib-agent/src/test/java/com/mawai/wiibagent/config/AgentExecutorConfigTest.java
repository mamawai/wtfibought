package com.mawai.wiibagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 对话线程池跟着上下文关：只 shutdown，不等在跑的轮（Spring 推断出的 close() 会一直等） */
class AgentExecutorConfigTest {

    @Test
    void 关上下文不等在跑的轮() {
        CountDownLatch release = new CountDownLatch(1);
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AgentExecutorConfig.class);
        ExecutorService executor = ctx.getBean(ExecutorService.class);
        executor.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), ctx::close);
            assertThat(executor.isShutdown()).isTrue();
            assertThat(executor.isTerminated()).isFalse();
        } finally {
            release.countDown();
        }
    }
}
