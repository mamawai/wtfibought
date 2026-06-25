package com.mawai.wiibquant.agent.strategy.fibo;

import java.math.BigDecimal;

/** 斐波那契回撤位计算。legStart=腿起点价(上行腿=低点)，legEnd=腿终点价。 */
public final class FiboLevels {

    private FiboLevels() {
    }

    public record FiboGrid(BigDecimal legStart, BigDecimal legEnd, boolean isUpLeg) {

        /** ratio 回撤位价格：从 legEnd 往回退 ratio×腿长。上行/下行腿用同一公式（range 带符号）。 */
        public BigDecimal retracement(double ratio) {
            BigDecimal range = legEnd.subtract(legStart);
            return legEnd.subtract(range.multiply(BigDecimal.valueOf(ratio)));
        }

        /**
         * 斐波延伸位：从腿起点沿腿方向投射 e×腿长。e=1→腿终点(前高/前低)，
         * e=1.272/1.618→突破腿终点的更远目标。range 带符号，上行/下行腿通用。
         */
        public BigDecimal extension(double e) {
            BigDecimal range = legEnd.subtract(legStart);
            return legStart.add(range.multiply(BigDecimal.valueOf(e)));
        }
    }
}
