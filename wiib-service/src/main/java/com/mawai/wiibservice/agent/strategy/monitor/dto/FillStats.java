package com.mawai.wiibservice.agent.strategy.monitor.dto;

/**
 * fill 对账统计 —— 策略唯一生死悬案的答案。
 *
 * <p>回测假设"价格触及挂单价=成交(touch=fill)"，真实 GTX maker 单"触及还要排队"，回测高估了成交。
 * 这里用 testnet 真实订单终态度量：进场单到底有多少被真正回踩成交，多少超时没排到被撤。</p>
 *
 * @param placed         进场 LIMIT 单总数（挂出的）
 * @param filled         成交数
 * @param expired        超时/撤销数（挂了没成交）
 * @param fillRate       成交率 = filled / placed（回测乐观度的直接度量）
 * @param avgFillSeconds 平均挂单→成交时长（秒）
 * @param makerConfirmed 成交价=挂单价的笔数（验证 GTX 零滑点）
 */
public record FillStats(
        int placed,
        int filled,
        int expired,
        double fillRate,
        double avgFillSeconds,
        int makerConfirmed) {}
