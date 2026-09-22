package com.mawai.wiibcommon.market;

import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TWAP 按"上一价一直有效"计权：窗口前的最后一价从窗口起点算，窗口内最后一价算到窗口终点 */
class TimeWeightedAverageTest {

    private static Point at(long sec, String price) {
        return new Point(sec * 1000, new BigDecimal(price));
    }

    @Test
    void 两段等长各占一半() {
        BigDecimal twap = TimeWeightedAverage.of(List.of(at(0, "100"), at(30, "200")), 0, 60_000);
        assertThat(twap).isEqualByComparingTo("150");
    }

    @Test
    void 窗口前的最后一价从窗口起点生效() {
        // 100 从 10s 起算 20s，200 从 30s 算 30s：(100×20 + 200×30) / 50 = 160
        BigDecimal twap = TimeWeightedAverage.of(List.of(at(0, "100"), at(30, "200")), 10_000, 60_000);
        assertThat(twap).isEqualByComparingTo("160");
    }

    @Test
    void 窗口内只有一跳就是那一价() {
        assertThat(TimeWeightedAverage.of(List.of(at(45, "123.5")), 0, 60_000)).isEqualByComparingTo("123.5");
    }

    @Test
    void 恰好落在终点的那一跳只当当前价() {
        assertThat(TimeWeightedAverage.of(List.of(at(60, "123.5")), 0, 60_000)).isEqualByComparingTo("123.5");
        // 有前段时它不计权：100 从 0 到 60s 全程
        assertThat(TimeWeightedAverage.of(List.of(at(0, "100"), at(60, "999")), 0, 60_000)).isEqualByComparingTo("100");
    }

    @Test
    void 窗口后的点不算() {
        BigDecimal twap = TimeWeightedAverage.of(List.of(at(0, "100"), at(90, "999")), 0, 60_000);
        assertThat(twap).isEqualByComparingTo("100");
    }

    @Test
    void 没价回null() {
        assertThat(TimeWeightedAverage.of(List.of(), 0, 60_000)).isNull();
        assertThat(TimeWeightedAverage.of(List.of(at(0, "100")), 60_000, 60_000)).isNull();
        // 全部点都在窗口之后，窗口内外都没价
        assertThat(TimeWeightedAverage.of(List.of(at(90, "999")), 0, 60_000)).isNull();
    }
}
