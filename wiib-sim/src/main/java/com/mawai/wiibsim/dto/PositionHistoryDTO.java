package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条合约仓位的完整生命周期：从开到平。
 * 字段来自订单聚合而非 futures_position（部分平仓后那张表的 closed_pnl/quantity 是残值），
 * 聚合 SQL 见 {@code FuturesPositionMapper#selectPositionHistory}。
 * 破产清零的仓位没有平仓单：closedQty=0、closeAvgPrice=null，前端按"—"显示，不要填 0。
 */
@Data
public class PositionHistoryDTO {

    private Long id;

    private String symbol;

    /** LONG做多 SHORT做空 */
    private String side;

    /** CROSS全仓 ISOLATED逐仓 */
    private String marginMode;

    private Integer leverage;

    /** CLOSED正常平掉（含止盈止损） LIQUIDATED被强平 */
    private String status;

    /** AI策略标签，手动开的仓为 null */
    private String memo;

    /** 开仓均价。多次加仓已在建仓时按量加权，仓位表这一列本身就是均价 */
    private BigDecimal entryPrice;

    /** 已平仓量 = 全部平仓单数量之和 */
    private BigDecimal closedQty;

    /** 累计平仓成交额，平仓均价的分子 */
    private BigDecimal closeAmount;

    /** 平仓均价 = 累计平仓成交额 / 已平仓量；一单没平过的仓位为 null */
    private BigDecimal closeAvgPrice;

    /** 累计投入保证金 = 开/加仓单实际占用之和，投资回报率的分母 */
    private BigDecimal investedMargin;

    /** 累计手续费（开、平两头都算） */
    private BigDecimal commission;

    /** 强平清算费，没被强平过为 0 */
    private BigDecimal liquidationFee;

    /** 累计资金费 */
    private BigDecimal fundingFeeTotal;

    /**
     * 已实现盈亏（净额）= 各平仓单盈亏 − 手续费 − 强平清算费 − 资金费。
     * 口径跟排行榜「交易盈利」一致：这些费用都是真金白银扣走的，不该从这个数里藏起来。
     */
    private BigDecimal realizedPnl;

    /** 投资回报率(%) = 已实现盈亏 / 累计投入保证金；分母为 0 时 null */
    private BigDecimal roiPct;

    /** 开仓时间 */
    private LocalDateTime openedAt;

    /** 平仓时间。持仓时长 = 平仓 − 开仓，前端自己减，不多占一个字段 */
    private LocalDateTime closedAt;

    /**
     * 这笔仓位的全部成交，按成交时间正序（开/加仓在前，各次平仓在后）。
     * 汇总行只说得出"已平 1.0、均价 116"，分批平的过程要靠这里的
     * 「0.4@110 / 0.6@120」两条才看得见。
     */
    private List<PositionFillDTO> fills;
}
