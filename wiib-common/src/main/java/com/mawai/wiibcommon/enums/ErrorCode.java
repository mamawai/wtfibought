package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统错误"),

    // 业务错误码 1000+
    USER_NOT_FOUND(1001, "用户不存在"),
    STOCK_NOT_FOUND(1002, "股票不存在"),
    BALANCE_NOT_ENOUGH(1003, "余额不足"),
    POSITION_NOT_ENOUGH(1004, "持仓不足"),
    TRADE_QUANTITY_INVALID(1005, "交易数量无效"),
    MARKET_CLOSED(1006, "市场已休市"),
    ORDER_NOT_FOUND(1007, "订单不存在"),
    ORDER_CANNOT_CANCEL(1008, "订单无法取消"),
    DUPLICATE_REQUEST(1009, "重复请求"),
    LIMIT_PRICE_INVALID(1011, "限价无效"),

    // 并发控制错误码 1100+
    CONCURRENT_UPDATE_FAILED(1101, "并发更新失败，请重试"),
    FROZEN_BALANCE_NOT_ENOUGH(1102, "冻结余额不足"),
    FROZEN_POSITION_NOT_ENOUGH(1103, "冻结持仓不足"),
    ACQUIRE_LOCK_FAILED(1104, "获取锁失败，请稍后重试"),
    ORDER_PROCESSING(1105, "订单正在处理中，请稍后再试"),

    // 交易限制错误码 1200+
    NOT_IN_TRADING_HOURS(1201, "非交易时段"),
    SLIPPAGE_EXCEEDED(1202, "价格波动过大，请重新下单"),
    RATE_LIMIT_EXCEEDED(1203, "请求过于频繁，请稍后再试"),
    USER_BANKRUPT(1204, "已爆仓，交易已禁用"),
    LEVERAGE_ONLY_FOR_MARKET_BUY(1205, "杠杆仅支持市价买入"),
    LEVERAGE_MULTIPLE_INVALID(1206, "杠杆倍率无效"),

    // WebSocket错误码 1300+
    WEBSOCKET_CONNECTION_LIMIT(1301, "连接数已达上限"),
    WEBSOCKET_AUTH_REQUIRED(1302, "需要登录后连接"),

    // Buff错误码 1400+
    BUFF_ALREADY_DRAWN(1401, "今日已抽奖"),
    BUFF_NOT_FOUND(1402, "Buff不存在"),
    BUFF_EXPIRED(1403, "Buff已过期"),
    BUFF_ALREADY_USED(1404, "Buff已使用"),
    DISCOUNT_NO_LEVERAGE(1405, "使用折扣时不支持杠杆"),

    // Blackjack错误码 1500+
    BJ_GAME_IN_PROGRESS(1501, "有未完成的牌局"),
    BJ_NO_ACTIVE_GAME(1502, "没有进行中的牌局"),
    BJ_CHIPS_NOT_ENOUGH(1503, "积分不足"),
    BJ_INVALID_BET(1504, "下注金额无效"),
    BJ_ACTION_NOT_ALLOWED(1505, "当前不可执行此操作"),
    BJ_CONVERT_LIMIT(1506, "超出每日转出上限"),
    BJ_CONVERT_INSUFFICIENT(1507, "可转出积分不足"),
    BJ_POOL_EXHAUSTED(1508, "今日积分池已耗尽"),

    // Crypto错误码 1600+
    CRYPTO_PRICE_UNAVAILABLE(1601, "无法获取实时价格"),
    CRYPTO_SYMBOL_INVALID(1602, "交易对无效"),

    // Mines错误码 1700+
    MINES_GAME_IN_PROGRESS(1701, "有未完成的矿工游戏"),
    MINES_NO_ACTIVE_GAME(1702, "没有进行中的矿工游戏"),
    MINES_BALANCE_NOT_ENOUGH(1703, "余额不足"),
    MINES_INVALID_BET(1704, "下注金额无效"),
    MINES_INVALID_CELL(1705, "格子编号无效"),
    MINES_CELL_ALREADY_REVEALED(1706, "该格子已翻开"),
    MINES_MUST_REVEAL_FIRST(1707, "至少翻开一个格子才能提现"),

    // Futures错误码 1750+
    FUTURES_POSITION_NOT_FOUND(1750, "仓位不存在"),
    FUTURES_INSUFFICIENT_BALANCE(1751, "余额不足"),
    FUTURES_INVALID_LEVERAGE(1752, "杠杆倍数无效"),
    FUTURES_INVALID_QUANTITY(1753, "数量无效"),
    FUTURES_INVALID_STOP_LOSS(1754, "止损价无效"),
    FUTURES_INVALID_TAKE_PROFIT(1757, "止盈价无效"),
    FUTURES_SPLIT_LIMIT(1758, "分拆目标最多4个"),
    FUTURES_POSITION_CLOSED(1755, "仓位已关闭"),
    FUTURES_LIQUIDATED(1756, "仓位已强平"),
    FUTURES_SYMBOL_NOT_CONFIGURED(1759, "永续合约该币种未配置档位"),
    FUTURES_MARGIN_TOO_LOW(1760, "减少后保证金不足"),
    FUTURES_CROSS_MARGIN_ADJUST(1761, "全仓仓位不支持调整保证金"),
    FUTURES_CROSS_AVAILABLE_NOT_ENOUGH(1762, "全仓可用余额不足"),
    FUTURES_LEVERAGE_ONLY_UP(1763, "逐仓持仓杠杆只能调高，补保证金请用追加保证金"),

    // VideoPoker错误码 1851+
    VP_GAME_IN_PROGRESS(1851, "有未完成的视频扑克"),
    VP_NO_ACTIVE_GAME(1852, "没有进行中的视频扑克"),
    VP_BALANCE_NOT_ENOUGH(1853, "余额不足"),
    VP_INVALID_BET(1854, "下注金额无效"),
    VP_INVALID_HOLD(1855, "HOLD位置无效"),

    // Prediction错误码 1900+
    PREDICTION_ROUND_LOCKED(1900, "回合已锁定"),
    PREDICTION_ROUND_NOT_FOUND(1901, "回合不存在"),
    PREDICTION_BET_NOT_FOUND(1902, "下注不存在"),
    PREDICTION_PRICE_UNAVAILABLE(1903, "无法获取BTC价格"),
    PREDICTION_AMOUNT_INVALID(1904, "金额无效"),

    // 钱包错误码 1950+
    GAME_BALANCE_NOT_ENOUGH(1950, "游戏钱包余额不足"),
    WALLET_TRANSFER_INVALID(1951, "划转金额无效"),
    CROSS_OUTFLOW_BLOCKED(1952, "资金转出后全仓仓位将触发强平，已拒绝"),

    // 账户重置错误码 2000+（1200/1600 段已被杠杆与 Crypto 占用）
    RESET_TOO_FREQUENT(2001, "每周只能重置一次账户"),
    RESET_NOT_ALLOWED(2002, "该账户不可重置"),

    // 留言板错误码 2100+
    COMMENT_MUTED(2101, "你已被禁言"),
    COMMENT_CONTENT_INVALID(2102, "评论内容为空或超过500字"),
    COMMENT_TOO_FREQUENT(2103, "评论过于频繁，请稍后再试"),
    COMMENT_NOT_FOUND(2104, "评论不存在或已删除"),
    COMMENT_ALREADY_VOTED(2105, "你已经表过态了");

    private final int code;
    private final String msg;
}
