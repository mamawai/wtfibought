package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 全仓安全带的数学与失效语义。带 = "价格不出此区间数学上保证爆不了"的区间，
 * 半宽推导（安全系数 0.7、占用制流出下界 /2）见 {@link CrossBandRegistry} 类注释。
 */
class CrossBandRegistryTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";

    private final CrossBandRegistry registry = new CrossBandRegistry();

    // ==================== 带宽公式 ====================

    @Test
    void 带宽取权益缓冲与占用下界的较小者() {
        // equity−mm=990，(usedMargin−mm)/2=45 → 取 45；0.7×45/2000 = 0.01575
        BigDecimal x = CrossBandRegistry.halfWidth(new BigDecimal("1000"), new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("2000"));
        assertThat(x).isEqualByComparingTo("0.01575");
    }

    @Test
    void 深度回撤时权益缓冲更小_取权益缓冲() {
        // equity 30 − mm 10 = 20 < 45 → 0.7×20/2000 = 0.007
        BigDecimal x = CrossBandRegistry.halfWidth(new BigDecimal("30"), new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("2000"));
        assertThat(x).isEqualByComparingTo("0.007");
    }

    @Test
    void 缓冲耗尽或占用低于维持保证金_不建带() {
        // equity = mm：贴线
        assertThat(CrossBandRegistry.halfWidth(new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("2000"))).isEqualByComparingTo("0");
        // usedMargin < mm：占用下界为负
        assertThat(CrossBandRegistry.halfWidth(new BigDecimal("1000"), new BigDecimal("5"),
                new BigDecimal("10"), new BigDecimal("2000"))).isEqualByComparingTo("0");
    }

    @Test
    void ranges按半宽展开参考价() {
        Map<String, double[]> r = CrossBandRegistry.ranges(
                Map.of(SYM, new BigDecimal("100")), new BigDecimal("0.01575"));
        assertThat(r.get(SYM)[0]).isCloseTo(98.425, within(1e-9));
        assertThat(r.get(SYM)[1]).isCloseTo(101.575, within(1e-9));
    }

    // ==================== 触发判定 ====================

    @Test
    void 无带或出带要查_带内跳过() {
        assertThat(registry.shouldCheck(UID, SYM, 100.0)).isTrue(); // 无带

        registry.put(UID, registry.epoch(UID), Map.of(SYM, new double[]{98.425, 101.575}));
        assertThat(registry.shouldCheck(UID, SYM, 100.0)).isFalse();  // 带内
        assertThat(registry.shouldCheck(UID, SYM, 103.0)).isTrue();   // 出带（上穿）
        assertThat(registry.shouldCheck(UID, SYM, 98.0)).isTrue();    // 出带（下穿）
        assertThat(registry.shouldCheck(UID, "ETHUSDT", 100.0)).isTrue(); // 带里没这个 symbol
    }

    @Test
    void 资金变动后旧带作废() {
        registry.put(UID, registry.epoch(UID), Map.of(SYM, new double[]{98.425, 101.575}));
        registry.bump(UID);
        assertThat(registry.shouldCheck(UID, SYM, 100.0)).isTrue();
    }

    @Test
    void 精查期间发生资金变动_迟到的回填无效() {
        long e0 = registry.epoch(UID);          // 精查开始：捕获纪元
        registry.bump(UID);                     // 快照读取期间账户变动
        registry.put(UID, e0, Map.of(SYM, new double[]{98.425, 101.575})); // 迟到回填，快照已脏
        assertThat(registry.shouldCheck(UID, SYM, 100.0)).isTrue();
    }

    @Test
    void 移除后恢复每tick必查() {
        registry.put(UID, registry.epoch(UID), Map.of(SYM, new double[]{98.425, 101.575}));
        registry.remove(UID);
        assertThat(registry.shouldCheck(UID, SYM, 100.0)).isTrue();
    }

    // ==================== 事务双跳（零窗口） ====================

    @Test
    void 事务内bump_立即作废且提交后再次作废() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            long e0 = registry.epoch(UID);
            registry.bump(UID);
            // 立即跳：事务内旧带即刻不可信
            assertThat(registry.epoch(UID)).isNotEqualTo(e0);

            // 提交前用（可能是未提交前旧数据算出的）带回填——此刻纪元合法
            registry.put(UID, registry.epoch(UID), Map.of(SYM, new double[]{98.425, 101.575}));
            assertThat(registry.shouldCheck(UID, SYM, 100.0)).isFalse();

            // 提交：afterCompletion 再跳一次，把上面那条带杀掉（防"提交前重建自旧快照"竞态）
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            assertThat(registry.shouldCheck(UID, SYM, 100.0)).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
