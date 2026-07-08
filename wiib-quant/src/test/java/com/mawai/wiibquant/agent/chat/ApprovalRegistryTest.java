package com.mawai.wiibquant.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRegistryTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();

    @Test
    void pendingIsDrainedOnce() {
        registry.requestApproval("s1", "BTCUSDT", "expensive");

        assertThat(registry.drainPending("s1")).isPresent();
        assertThat(registry.drainPending("s1")).isEmpty(); // drain 后清空
    }

    @Test
    void approvalIsConsumedOnce() {
        registry.approve("s1");

        assertThat(registry.consumeApproval("s1")).isTrue();
        assertThat(registry.consumeApproval("s1")).isFalse(); // 一次授权只放行一次
    }

    @Test
    void rejectClearsApprovalAndPending() {
        registry.approve("s1");
        registry.requestApproval("s1", "BTCUSDT", "expensive");

        registry.reject("s1");

        assertThat(registry.consumeApproval("s1")).isFalse();
        assertThat(registry.drainPending("s1")).isEmpty();
    }

    @Test
    void activeSessionSlotFallback() {
        registry.markActive("wb-1-abc");

        assertThat(registry.activeSession()).isEqualTo("wb-1-abc");
    }
}
