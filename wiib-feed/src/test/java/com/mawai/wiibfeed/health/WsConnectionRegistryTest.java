package com.mawai.wiibfeed.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 流健康快照的版本号：墙钟起算（feed 重启后照样比重启前大），同一毫秒连取也严格递增 */
class WsConnectionRegistryTest {

    @Test
    void 版本号按墙钟起算_连取严格递增() {
        WsConnectionRegistry registry = new WsConnectionRegistry();
        long before = System.currentTimeMillis();

        long first = registry.snapshot().version();
        long second = registry.snapshot().version();

        assertThat(first).isGreaterThanOrEqualTo(before);
        assertThat(second).isGreaterThan(first);
    }
}
