package com.mawai.wiibagent.chat.gate;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 服务关停提前收尾：给在跑的每一轮发停止信号，和用户点停止同一条路 */
class WorkbenchRunRegistryTest {

    @Test
    void 提前收尾给每一轮发停止信号() {
        WorkbenchRunRegistry registry = new WorkbenchRunRegistry();
        CompletableFuture<Void> a = new CompletableFuture<>();
        CompletableFuture<Void> b = new CompletableFuture<>();
        registry.start("wb-1-a", (event, data) -> true, a);
        registry.start("wb-2-b", (event, data) -> true, b);
        assertThat(registry.count()).isEqualTo(2);

        registry.cut();

        assertThat(a).isDone();
        assertThat(b).isDone();
        // 轮自己收尾时才摘登记：半截答案落库之前都还算在跑
        assertThat(registry.count()).isEqualTo(2);
    }
}
