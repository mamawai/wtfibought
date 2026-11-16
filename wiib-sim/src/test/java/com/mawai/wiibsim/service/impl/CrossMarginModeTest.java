package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.service.AssetValuationService;
import com.mawai.wiibsim.service.CrossMarginService.CrossAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全仓占用制核心口径：equity/available/maxOutflow/强平线/估值分叉/模式归一 */
class CrossMarginModeTest {

    private static FuturesPosition pos(String mode, BigDecimal margin) {
        FuturesPosition p = new FuturesPosition();
        p.setMarginMode(mode);
        p.setSide("LONG");
        p.setEntryPrice(new BigDecimal("100"));
        p.setQuantity(BigDecimal.ONE);
        p.setMargin(margin);
        return p;
    }

    @Test
    void 占用制三公式_钱包10000开2000占用_浮盈500() {
        CrossAccount acc = new CrossAccount(new BigDecimal("10000"), new BigDecimal("500"),
                new BigDecimal("2000"), BigDecimal.ZERO, new BigDecimal("50"),
                List.of(pos(FuturesPosition.CROSS, new BigDecimal("2000"))), Map.of());

        assertThat(acc.equity()).isEqualByComparingTo("10500");          // 余额+浮盈
        assertThat(acc.available()).isEqualByComparingTo("8500");        // 浮盈开仓额度天然成立
        assertThat(acc.liquidatable()).isFalse();
    }

    @Test
    void 挂单占用计入可用扣减() {
        CrossAccount acc = new CrossAccount(new BigDecimal("10000"), BigDecimal.ZERO,
                new BigDecimal("2000"), new BigDecimal("1500"), new BigDecimal("50"),
                List.of(pos(FuturesPosition.CROSS, new BigDecimal("2000"))), Map.of());

        assertThat(acc.available()).isEqualByComparingTo("6500");
    }

    @Test
    void 最大可流出_浮盈只抬开仓额度_抬不了现金() {
        // 钱包1000、全仓占用100、浮盈500 → 可用1400，但钱包里的现金只有1000
        CrossAccount acc = new CrossAccount(new BigDecimal("1000"), new BigDecimal("500"),
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("10"),
                List.of(pos(FuturesPosition.CROSS, new BigDecimal("100"))), Map.of());

        assertThat(acc.available()).isEqualByComparingTo("1400");
        assertThat(acc.maxOutflow()).isEqualByComparingTo("1000"); // 浮盈得平仓兑现才划得走
    }

    @Test
    void 最大可流出_有占用时卡在可用额度() {
        // 钱包1000、全仓占用100、无浮盈。旧口径按维持保证金判能划走990，
        // 占用制承诺给全仓仓位兜底的那100形同虚设——这就是逐仓能开走全部钱的根因
        CrossAccount acc = new CrossAccount(new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("10"),
                List.of(pos(FuturesPosition.CROSS, new BigDecimal("100"))), Map.of());

        assertThat(acc.maxOutflow()).isEqualByComparingTo("900");
    }

    @Test
    void 最大可流出_浮亏吃穿可用_归零不返负() {
        CrossAccount acc = new CrossAccount(new BigDecimal("1000"), new BigDecimal("-950"),
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("10"),
                List.of(pos(FuturesPosition.CROSS, new BigDecimal("100"))), Map.of());

        assertThat(acc.available()).isEqualByComparingTo("-50");
        assertThat(acc.maxOutflow()).isEqualByComparingTo("0");
    }

    @Test
    void 强平线_净值跌破维持保证金_等于也爆() {
        // 浮亏把净值打到恰好等于MM：10000-9950=50 == MM 50 → 爆
        CrossAccount atLine = new CrossAccount(new BigDecimal("10000"), new BigDecimal("-9950"),
                new BigDecimal("2000"), BigDecimal.ZERO, new BigDecimal("50"),
                List.of(pos(FuturesPosition.CROSS, new BigDecimal("2000"))), Map.of());
        assertThat(atLine.liquidatable()).isTrue();

        // 无持仓永不判爆（哪怕余额为负——那是穿仓落地走破产，不走强平）
        CrossAccount empty = new CrossAccount(new BigDecimal("-10"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), Map.of());
        assertThat(empty.liquidatable()).isFalse();
    }

    @Test
    void 估值分叉_全仓只算浮盈亏_逐仓保证金加浮盈亏() {
        BigDecimal mark = new BigDecimal("110"); // entry 100, qty 1 → 浮盈10

        FuturesPosition cross = pos(FuturesPosition.CROSS, new BigDecimal("2000"));
        FuturesPosition isolated = pos(FuturesPosition.ISOLATED, new BigDecimal("2000"));
        FuturesPosition legacy = pos(null, new BigDecimal("2000")); // 存量老数据视为逐仓

        assertThat(AssetValuationService.futuresPositionValue(cross, mark)).isEqualByComparingTo("10");
        assertThat(AssetValuationService.futuresPositionValue(isolated, mark)).isEqualByComparingTo("2010");
        assertThat(AssetValuationService.futuresPositionValue(legacy, mark)).isEqualByComparingTo("2010");
        // 全仓缺价：浮盈亏按0，不把占用当资产
        assertThat(AssetValuationService.futuresPositionValue(cross, null)).isEqualByComparingTo("0");
    }

    @Test
    void 模式归一_缺省全仓_非法值拒绝() {
        assertThat(FuturesHelper.normalizeMarginMode(null)).isEqualTo(FuturesPosition.CROSS);
        assertThat(FuturesHelper.normalizeMarginMode("")).isEqualTo(FuturesPosition.CROSS);
        assertThat(FuturesHelper.normalizeMarginMode("ISOLATED")).isEqualTo(FuturesPosition.ISOLATED);
        assertThat(FuturesHelper.normalizeMarginMode("CROSS")).isEqualTo(FuturesPosition.CROSS);
        assertThatThrownBy(() -> FuturesHelper.normalizeMarginMode("cross"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }
}
