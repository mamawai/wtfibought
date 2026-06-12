package com.mawai.wiibservice.agent.strategy.fibo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FiboLevelsTest {

    @Test
    void upLegRetracementLevels() {
        // 上行腿 100→200：0.618位 = 200 - 0.618×100 = 138.2
        FiboLevels.FiboGrid grid = new FiboLevels.FiboGrid(new BigDecimal("100"), new BigDecimal("200"), true);
        assertEquals(0, grid.retracement(0.618).compareTo(new BigDecimal("138.2")));
        assertEquals(0, grid.retracement(0.786).compareTo(new BigDecimal("121.4")));
    }

    @Test
    void downLegRetracementLevels() {
        // 下行腿 200→100：0.618位 = 100 + 0.618×100 = 161.8
        FiboLevels.FiboGrid grid = new FiboLevels.FiboGrid(new BigDecimal("200"), new BigDecimal("100"), false);
        assertEquals(0, grid.retracement(0.618).compareTo(new BigDecimal("161.8")));
    }

    @Test
    void extensionLevels() {
        // 上行腿 100→200(腿长100)：ext(1.0)=腿终点200；ext(1.272)=100+127.2=227.2；ext(1.618)=261.8
        FiboLevels.FiboGrid up = new FiboLevels.FiboGrid(new BigDecimal("100"), new BigDecimal("200"), true);
        assertEquals(0, up.extension(1.0).compareTo(new BigDecimal("200.0")), "ext(1.0) 必须等于腿终点(向后兼容)");
        assertEquals(0, up.extension(1.272).compareTo(new BigDecimal("227.2")));
        assertEquals(0, up.extension(1.618).compareTo(new BigDecimal("261.8")));
        // 下行腿 200→100：ext(1.0)=100；ext(1.618)=200-161.8=38.2
        FiboLevels.FiboGrid down = new FiboLevels.FiboGrid(new BigDecimal("200"), new BigDecimal("100"), false);
        assertEquals(0, down.extension(1.0).compareTo(new BigDecimal("100.0")), "ext(1.0) 必须等于腿终点(向后兼容)");
        assertEquals(0, down.extension(1.618).compareTo(new BigDecimal("38.2")));
    }
}
