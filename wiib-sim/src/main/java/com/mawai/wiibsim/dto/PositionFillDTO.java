package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 仓位历史里的一笔成交明细。
 * <p>
 * 有它，分批平仓才看得见过程：一笔「开 1 → 平 0.4@110 → 平 0.6@120」的仓位，
 * 汇总行只说得出"已平 1.0、均价 116"，这三条明细才说得出是怎么平出来的。
 * 同一仓位的多次加仓同理。
 */
@Data
public class PositionFillDTO {

    /** 归属仓位，服务端按它分组后就没用了，但留着方便排查 */
    private Long positionId;

    private Long orderId;

    /** OPEN_LONG/OPEN_SHORT 开或加仓，CLOSE_LONG/CLOSE_SHORT 平仓 */
    private String orderSide;

    /** MARKET市价 LIMIT限价 */
    private String orderType;

    /**
     * 这笔是怎么成的：FILLED 手动下单成交，STOP_LOSS/TAKE_PROFIT 止损止盈打到，LIQUIDATED 被强平。
     * 哪几笔是自己主动平的、哪几笔是被动触发的，全靠这一列区分。
     */
    private String status;

    private BigDecimal quantity;

    /** 成交价 */
    private BigDecimal price;

    /** 成交额 */
    private BigDecimal amount;

    private BigDecimal commission;

    /** 强平清算费，强平单才有，其余为 null */
    private BigDecimal liquidationFee;

    /** 已实现盈亏，平仓单才有；开/加仓单为 null */
    private BigDecimal realizedPnl;

    /** 成交时间。用 updated_at 不用 created_at——限价单挂上和成交是两个时刻 */
    private LocalDateTime filledAt;
}
