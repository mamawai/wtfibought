package com.mawai.wiibservice.agent.research.label;

import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A2 regime 事后标签：趋势上/下、震荡、SHOCK、空路径。 */
class RegimeLabelerTest {

    @Test
    void strongUptrendIsTrendingUp() {
        // 平滑单调上行：directionality=|净收益|/realizedVol≈√5≈2.24 ≥ q → TRENDING_UP（显式 q=2.0，与 default 调参解耦）
        MarketRegime r = RegimeLabeler.label(bars(100, 101, 102, 103, 104, 105), 0.02, 2.0, 3.0);
        assertThat(r).isEqualTo(MarketRegime.TRENDING_UP);
    }

    @Test
    void strongDowntrendIsTrendingDown() {
        // 平滑单调下行：directionality≈2.24 ≥ q，净收益<0 → TRENDING_DOWN
        MarketRegime r = RegimeLabeler.label(bars(100, 99, 98, 97, 96, 95), 0.02, 2.0, 3.0);
        assertThat(r).isEqualTo(MarketRegime.TRENDING_DOWN);
    }

    @Test
    void choppyIsRanging() {
        // 来回震荡、末尾回到起点 → 净收益≈0 → directionality≈0 ≪ q → RANGING（波动也未达 SHOCK）
        MarketRegime r = RegimeLabeler.label(bars(100, 102, 100, 102, 100), 0.02);
        assertThat(r).isEqualTo(MarketRegime.RANGING);
    }

    @Test
    void extremeVolatilityIsShock() {
        // 实现波动远超基线 → SHOCK 优先（不论方向）
        MarketRegime r = RegimeLabeler.label(bars(100, 110, 90, 110, 90), 0.001);
        assertThat(r).isEqualTo(MarketRegime.SHOCK);
    }

    @Test
    void emptyPathIsRanging() {
        assertThat(RegimeLabeler.label(List.of(), 0.02)).isEqualTo(MarketRegime.RANGING);
    }

    @Test
    void violentIntraPathSwingsAreShockEvenWhenPerBarVolIsModest() {
        // 真实跨口径场景：futurePath 是细粒度 bars，baselineSigma 是单期(H-bar)σ。
        // 整段来回剧烈震荡 → 已实现波动(整段)远超 3×baseline，但每根 bar 收益不极端。
        // 旧实现拿 per-bar σ 比单期 baseline（量纲差 √n）→ 永判不出 SHOCK；新实现用整段已实现波动 → SHOCK。
        List<KlineBar> path = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            double c = (i % 2 == 0) ? 100.0 : 101.0; // ±1% 来回震荡，净位移≈0（非趋势）
            BigDecimal v = BigDecimal.valueOf(c);
            path.add(new KlineBar(0, 0, v, v, v, v, BigDecimal.ONE));
        }

        MarketRegime r = RegimeLabeler.label(path, 0.02); // baseline=典型单期 σ=2%

        assertThat(r).isEqualTo(MarketRegime.SHOCK);
    }

    @Test
    void noisyButNetDirectionalPathClearsModerateThreshold() {
        // 30 步带回调的净上行（×1.02,×1.02,×0.98 循环）：有来回抵消，路径效率不满（directionality≈1.78），
        // 但净方向仍够强 → 越过中等阈值 q=1.5 判 TRENDING_UP。对照 efficientSmooth(directionality=4) 的满效率。
        List<KlineBar> path = new ArrayList<>();
        double price = 100.0;
        path.add(barOf(price));
        for (int i = 0; i < 30; i++) {
            price *= (i % 3 == 2) ? 0.98 : 1.02;
            path.add(barOf(price));
        }

        MarketRegime r = RegimeLabeler.label(path, 0.05, 1.5, 3.0); // q=1.5, shock=3.0

        assertThat(r).isEqualTo(MarketRegime.TRENDING_UP);
    }

    @Test
    void efficientSmoothUptrendIsTrendEvenWhenBaselineIsLarge() {
        // 新语义核心：趋势看「路径效率」directionality=|净对数收益|/realizedVol(path)，与 baseline 无关。
        // 16 步平滑同向 ×1.005（无回调）→ directionality=√16=4，远超阈值；但净收益仅 0.08、被故意调大的 baseline(0.10) 压住。
        // 旧 net/σ 规则：|netReturn|=0.08 < 1×0.10 → 误判 RANGING；新规则：directionality=4 ≥ q → TRENDING_UP。
        List<KlineBar> path = new ArrayList<>();
        double price = 100.0;
        path.add(barOf(price));
        for (int i = 0; i < 16; i++) {
            price *= 1.005;
            path.add(barOf(price));
        }

        MarketRegime r = RegimeLabeler.label(path, 0.10);

        assertThat(r).isEqualTo(MarketRegime.TRENDING_UP);
    }

    static KlineBar barOf(double close) {
        BigDecimal v = BigDecimal.valueOf(close);
        return new KlineBar(0, 0, v, v, v, v, BigDecimal.ONE);
    }

    static List<KlineBar> bars(double... closes) {
        List<KlineBar> out = new ArrayList<>();
        for (double c : closes) {
            BigDecimal v = BigDecimal.valueOf(c);
            out.add(new KlineBar(0, 0, v, v, v, v, BigDecimal.ONE));
        }
        return out;
    }
}
