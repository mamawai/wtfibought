package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 账本业务类型。label 是账单页直接显示的中文，前端不再维护一份映射。
 * <p>
 * 【改名警告】常量名就是 user_ledger.biz_type 列里存的字符串——走 MyBatis-Plus 默认的
 * name() ↔ VARCHAR 映射，没有 @EnumValue 兜着。上线后只准加新的，不准改名、不准删：
 * 一改名，库里旧名字的历史行读回来 Enum.valueOf 找不到常量，直接抛
 * IllegalArgumentException，整页账单 500。中文说法要调就只改 label，名字别动。
 * 真要下线一个类型，先把库里那个名字的行 UPDATE 成别的类型，再删常量。
 */
@Getter
@RequiredArgsConstructor
public enum LedgerBizType {

    // ===== 合约 =====
    FUTURES_OPEN_MARGIN("合约开仓保证金"),
    FUTURES_OPEN_FEE("合约开仓手续费"),
    FUTURES_CLOSE_SETTLE("合约平仓结算"),
    FUTURES_CLOSE_FEE("合约平仓手续费"),
    FUTURES_LIMIT_FREEZE("合约限价单冻结"),
    FUTURES_LIMIT_UNFREEZE("合约撤单解冻"),
    FUTURES_LIMIT_DEDUCT("合约限价单成交"),
    FUTURES_LIMIT_REFUND("合约限价单退差额"),
    FUTURES_ADD_MARGIN("合约追加保证金"),
    FUTURES_REDUCE_MARGIN("合约减少保证金"),
    FUTURES_LEVERAGE_RELEASE("合约调杠杆释放"),
    FUTURES_LIQUIDATION_RETURN("合约强平返还"),
    FUNDING_FEE_PAY("资金费支出"),
    FUNDING_FEE_RECV("资金费收入"),
    FUNDING_FEE_FROM_MARGIN("资金费扣保证金"),
    CROSS_SETTLE("全仓结算"),

    // ===== 现货 / B股 =====
    SPOT_BUY("现货买入"),
    SPOT_BUY_LEVERAGE("现货杠杆买入"),
    SPOT_LIMIT_FREEZE("现货限价单冻结"),
    SPOT_LIMIT_UNFREEZE("现货撤单解冻"),
    SPOT_LIMIT_DEDUCT("现货限价单成交"),
    SPOT_LIMIT_REFUND("现货限价单退差额"),
    SPOT_SETTLE("现货卖出到账"),
    BSTOCK_SETTLE("B股结算到账"),

    // ===== 游戏 =====
    MINES_BET("扫雷下注"),
    MINES_CASHOUT("扫雷兑现"),
    POKER_BET("视频扑克下注"),
    POKER_PAYOUT("视频扑克派彩"),
    PREDICTION_BUY("预测市场买入"),
    PREDICTION_SELL("预测市场卖出"),
    PREDICTION_SETTLE("预测市场结算"),
    PREDICTION_REFUND("预测市场退款"),
    BLACKJACK_CONVERT("21点积分转出"),

    // ===== 钱包划转 =====
    WALLET_TRANSFER_OUT("划转转出"),
    WALLET_TRANSFER_IN("划转转入"),

    // ===== 杠杆 =====
    MARGIN_LOAN("杠杆借款"),
    MARGIN_INTEREST_ACCRUE("杠杆计息"),
    MARGIN_REPAY_INTEREST("杠杆还息"),
    MARGIN_REPAY_PRINCIPAL("杠杆还本"),
    CASH_INFLOW_CREDIT("结算入余额"),

    // ===== 其它 =====
    INITIAL_GRANT("初始资金"),
    BANKRUPT_CLEAR("爆仓清零"),
    BANKRUPT_RESET("破产恢复"),
    /** 兜底：切面没拿到任何标注时用，配 WARN 日志和调用方类名，账平但语义待补 */
    UNKNOWN("未分类");

    private final String label;

    /**
     * 账单页筛选下拉的分组名。按常量名前缀归组，跟上面那几行分节注释是同一套划分。
     * <p>
     * 没做成每个常量一个构造参数，是不想为一个纯展示用的分组去动这 45 行——
     * 前缀本来就是分节的实际依据。代价：加新类型时若用了没见过的前缀，这里不报错，
     * 只是落进"其它"，下拉里位置怪一点，不影响筛选本身。用了新前缀就来补一行。
     */
    public String getGroup() {
        String n = name();
        if (n.startsWith("FUTURES_") || n.startsWith("FUNDING_") || n.startsWith("CROSS_")) return "合约";
        if (n.startsWith("SPOT_") || n.startsWith("BSTOCK_")) return "现货";
        if (n.startsWith("MINES_") || n.startsWith("POKER_")
                || n.startsWith("PREDICTION_") || n.startsWith("BLACKJACK_")) return "游戏";
        if (n.startsWith("WALLET_TRANSFER_")) return "划转";
        if (n.startsWith("MARGIN_") || n.startsWith("CASH_")) return "杠杆";
        return "其它";
    }
}
