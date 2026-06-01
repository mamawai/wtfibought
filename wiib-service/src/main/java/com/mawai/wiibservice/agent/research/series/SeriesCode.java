package com.mawai.wiibservice.agent.research.series;

/**
 * research 层链下时点序列类型。
 * 本刀只回填 {@link #FUNDING}、{@link #FEAR_GREED}——它们历史够深（年级），能对齐 180 天+ 样本外。
 * OI / taker / 大户的 {@code /futures/data/*} 接口只给最近 ~30 天，撑不起历史回测，留作将来前向/live eval（届时新增枚举即可）。
 */
public enum SeriesCode {
    /** 资金费率：Binance /fapi/v1/fundingRate，8h 一个点，历史数年。 */
    FUNDING,
    /** 恐惧贪婪指数：alternative.me，日级，历史回溯到 2018；全市场序列，symbol 用 'GLOBAL'。 */
    FEAR_GREED
}
