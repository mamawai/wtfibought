package com.mawai.wiibagent.replay;

import java.util.List;

/**
 * 手动复盘 AI 教练的一次请求（前端拼好、后端只做校验与成文）。
 * <p>
 * 时间一律是前端格式化好的<b>标签字符串</b>而不是毫秒：盲测局要求模型只看到 "D2 14:30" 这种相对时间，
 * 真实日期绝不能经这条通道泄露给模型（模型认得历史行情，一看日期就能"背答案"）。
 * 结算后（REVIEW）盲测已揭晓，前端会换成真实时间标签，后端不区分。
 *
 * @param mode      HINT=局中盘面提示，REVIEW=结算后评估用户这一局的交易行为
 * @param endpointId 用哪条 BYOK 端点（复盘配置台从端点库下拉选的），空=用户默认端点
 * @param startAt   复盘段首根的时间标签（之前的 K 线是开局给的上下文），可空
 * @param bars      已揭示的 K 线（HINT 取最近一段，REVIEW 取整局按合适周期聚合），上限见 ReplayCoachPrompts
 * @param equity    当前权益（HINT 用；浮盈亏占权益多少才说得清仓位风险），可空
 * @param positions 当前持仓（HINT 用；最多多空各一）
 * @param trades    整局成交（REVIEW 用）
 * @param stats     结算统计（REVIEW 用）
 */
public record ReplayCoachRequest(String mode, Long endpointId, String symbol, Integer intervalMin, Boolean blind,
                                 String startAt, List<Bar> bars, Double equity, List<Position> positions,
                                 List<Trade> trades, Stats stats) {

    public static final String MODE_HINT = "HINT";
    public static final String MODE_REVIEW = "REVIEW";

    /** t=时间标签（盲测相对/真实），o/h/l/c 已按币种精度取整，v=成交量 */
    public record Bar(String t, double o, double h, double l, double c, double v) {
    }

    /** leverage 是有效杠杆（加仓换档会成小数） */
    public record Position(String side, double qty, double entryPrice, double leverage, double unrealizedPnl) {
    }

    /** partial=这笔只平了仓位的一部分（减仓）；leverage 是该仓的有效杠杆 */
    public record Trade(String side, double qty, double leverage, double entryPrice, double exitPrice, double pnl,
                        String openAt, String closeAt, String reason, boolean partial) {
    }

    public record Stats(int totalTrades, int wins, int losses, double netProfit, double returnPct,
                        double maxDrawdownPct, double totalFees, double initialBalance, double finalEquity) {
    }
}
