package com.mawai.wiibquant.agent.research.series;

/**
 * research 层链下/链上时点序列类型 → factor_history.factor_name 的映射。
 * FUNDING/FEAR_GREED 由 {@link MarketSeriesStore} 回填；ETF_FLOW/STABLECOIN_DELTA 复用 live 采集器全历史回填（Slice3 T1）。
 * 四者历史都够深（年级），能对齐 180 天+ 样本外。
 * OI / taker / 大户的 {@code /futures/data/*} 接口只给最近 ~30 天，撑不起历史回测，留作将来前向/live eval（届时新增枚举即可）。
 */
public enum SeriesCode {
    /** 资金费率：Binance /fapi/v1/fundingRate，8h 一个点，历史数年。 */
    FUNDING("FUNDING_RATE"),
    /** 恐惧贪婪指数：alternative.me，日级，历史回溯到 2018；全市场序列，symbol 用 'GLOBAL'。 */
    FEAR_GREED("FEAR_GREED"),
    /** BTC 现货 ETF 日净流入(USD 百万)：Farside，日级；仅 symbol=BTCUSDT。 */
    ETF_FLOW("BTC_ETF_FLOW"),
    /** 稳定币供给相邻期差值(USD)：DeFiLlama，日级；按 watch symbols 各存一份。 */
    STABLECOIN_DELTA("STABLECOIN_SUPPLY_DELTA");

    private final String factorName;

    SeriesCode(String factorName) {
        this.factorName = factorName;
    }

    /** 落到 factor_history.factor_name 的名字（Slice3 融合后统一存 factor_history；与 live 现有因子名不冲突）。 */
    public String factorName() {
        return factorName;
    }
}
