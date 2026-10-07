package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务错误码。<b>码在这里，话在 {@code messages/<语言>/error.yml}</b>——枚举名下划线转小驼峰
 * 就是词表 key（BALANCE_NOT_ENOUGH → error.balanceNotEnough）。
 * <p>
 * 渲染只在 {@code GlobalExceptionHandler} 一处发生（按当次请求的界面语言），所以业务代码
 * 一路只传码不传话；日志里出现的也是码，比中文更好 grep。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "error.success"),
    PARAM_ERROR(400, "error.paramError"),
    UNAUTHORIZED(401, "error.unauthorized"),
    FORBIDDEN(403, "error.forbidden"),
    NOT_FOUND(404, "error.notFound"),
    SYSTEM_ERROR(500, "error.systemError"),

    // 业务错误码 1000+
    USER_NOT_FOUND(1001, "error.userNotFound"),
    STOCK_NOT_FOUND(1002, "error.stockNotFound"),
    BALANCE_NOT_ENOUGH(1003, "error.balanceNotEnough"),
    POSITION_NOT_ENOUGH(1004, "error.positionNotEnough"),
    TRADE_QUANTITY_INVALID(1005, "error.tradeQuantityInvalid"),
    MARKET_CLOSED(1006, "error.marketClosed"),
    ORDER_NOT_FOUND(1007, "error.orderNotFound"),
    ORDER_CANNOT_CANCEL(1008, "error.orderCannotCancel"),
    DUPLICATE_REQUEST(1009, "error.duplicateRequest"),
    LIMIT_PRICE_INVALID(1011, "error.limitPriceInvalid"),

    // 并发控制错误码 1100+
    CONCURRENT_UPDATE_FAILED(1101, "error.concurrentUpdateFailed"),
    FROZEN_BALANCE_NOT_ENOUGH(1102, "error.frozenBalanceNotEnough"),
    FROZEN_POSITION_NOT_ENOUGH(1103, "error.frozenPositionNotEnough"),
    ACQUIRE_LOCK_FAILED(1104, "error.acquireLockFailed"),
    ORDER_PROCESSING(1105, "error.orderProcessing"),
    // 1106 专给幂等占位：这笔单确实在跑，结果未知；1105 是抢锁失败，那是确定没成交
    ORDER_IN_FLIGHT(1106, "error.orderInFlight"),

    // 交易限制错误码 1200+
    SLIPPAGE_EXCEEDED(1202, "error.slippageExceeded"),
    RATE_LIMIT_EXCEEDED(1203, "error.rateLimitExceeded"),
    USER_BANKRUPT(1204, "error.userBankrupt"),
    LEVERAGE_ONLY_FOR_MARKET_BUY(1205, "error.leverageOnlyForMarketBuy"),
    LEVERAGE_MULTIPLE_INVALID(1206, "error.leverageMultipleInvalid"),

    // WebSocket错误码 1300+
    WEBSOCKET_CONNECTION_LIMIT(1301, "error.websocketConnectionLimit"),
    WEBSOCKET_AUTH_REQUIRED(1302, "error.websocketAuthRequired"),

    // Crypto错误码 1600+
    CRYPTO_PRICE_UNAVAILABLE(1601, "error.cryptoPriceUnavailable"),
    CRYPTO_SYMBOL_INVALID(1602, "error.cryptoSymbolInvalid"),
    TRADE_STEP_INVALID(1603, "error.tradeStepInvalid"),
    TRADE_MIN_NOTIONAL(1604, "error.tradeMinNotional"),

    // Futures错误码 1750+
    FUTURES_POSITION_NOT_FOUND(1750, "error.futuresPositionNotFound"),
    FUTURES_INSUFFICIENT_BALANCE(1751, "error.futuresInsufficientBalance"),
    FUTURES_INVALID_LEVERAGE(1752, "error.futuresInvalidLeverage"),
    FUTURES_INVALID_QUANTITY(1753, "error.futuresInvalidQuantity"),
    FUTURES_INVALID_STOP_LOSS(1754, "error.futuresInvalidStopLoss"),
    FUTURES_INVALID_TAKE_PROFIT(1757, "error.futuresInvalidTakeProfit"),
    FUTURES_SPLIT_LIMIT(1758, "error.futuresSplitLimit"),
    FUTURES_POSITION_CLOSED(1755, "error.futuresPositionClosed"),
    FUTURES_LIQUIDATED(1756, "error.futuresLiquidated"),
    FUTURES_SYMBOL_NOT_CONFIGURED(1759, "error.futuresSymbolNotConfigured"),
    FUTURES_MARGIN_TOO_LOW(1760, "error.futuresMarginTooLow"),
    FUTURES_CROSS_MARGIN_ADJUST(1761, "error.futuresCrossMarginAdjust"),
    // 逐仓开仓/现货买入/钱包划转不够也抛它——全仓占用的钱谁都不能动，文案别只说"全仓"
    FUTURES_CROSS_AVAILABLE_NOT_ENOUGH(1762, "error.futuresCrossAvailableNotEnough"),
    FUTURES_LEVERAGE_ONLY_UP(1763, "error.futuresLeverageOnlyUp"),
    FUTURES_LEVERAGE_MISMATCH(1764, "error.futuresLeverageMismatch"),
    FUTURES_MARGIN_MODE_CONFLICT(1765, "error.futuresMarginModeConflict"),

    // Prediction错误码 1900+
    PREDICTION_ROUND_LOCKED(1900, "error.predictionRoundLocked"),
    PREDICTION_ROUND_NOT_FOUND(1901, "error.predictionRoundNotFound"),
    PREDICTION_BET_NOT_FOUND(1902, "error.predictionBetNotFound"),
    PREDICTION_PRICE_UNAVAILABLE(1903, "error.predictionPriceUnavailable"),
    PREDICTION_AMOUNT_INVALID(1904, "error.predictionAmountInvalid"),

    // 钱包错误码 1950+
    GAME_BALANCE_NOT_ENOUGH(1950, "error.gameBalanceNotEnough"),
    WALLET_TRANSFER_INVALID(1951, "error.walletTransferInvalid"),

    // 账户重置错误码 2000+（1200/1600 段已被杠杆与 Crypto 占用）
    RESET_TOO_FREQUENT(2001, "error.resetTooFrequent"),
    RESET_NOT_ALLOWED(2002, "error.resetNotAllowed"),

    // 留言板错误码 2100+
    COMMENT_MUTED(2101, "error.commentMuted"),
    COMMENT_CONTENT_INVALID(2102, "error.commentContentInvalid"),
    COMMENT_TOO_FREQUENT(2103, "error.commentTooFrequent"),
    COMMENT_NOT_FOUND(2104, "error.commentNotFound"),
    COMMENT_ALREADY_VOTED(2105, "error.commentAlreadyVoted"),

    // 研判工作台错误码 2200+（1600 段已被 Crypto 占用，别再往那儿塞）
    LLM_CONFIG_MISSING(2201, "error.llmConfigMissing"),
    LLM_CONFIG_INVALID(2202, "error.llmConfigInvalid"),
    CHAT_ALREADY_RUNNING(2203, "error.chatAlreadyRunning"),
    CHAT_CAPACITY_FULL(2204, "error.chatCapacityFull"),
    REPLAY_AI_BUSY(2205, "error.replayAiBusy"),
    CHAT_REGENERATE_UNAVAILABLE(2206, "error.chatRegenerateUnavailable"),
    CHAT_MESSAGE_TOO_LONG(2207, "error.chatMessageTooLong"),
    CHAT_NOTHING_DEFERRED(2208, "error.chatNothingDeferred");

    private final int code;
    /** 界面文案词表的 key，不是文案本身。渲染见 {@code GlobalExceptionHandler} */
    private final String msgKey;

    private static final Map<Integer, ErrorCode> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ErrorCode::getCode, e -> e));

    /**
     * 按码反查。跨服务调用（agent 打 sim internal API）收到的只有码，要成文得先找回枚举。
     * 认不出返回 null——对方比自己新时不该炸，由调用方决定怎么退。
     */
    public static ErrorCode of(int code) {
        return BY_CODE.get(code);
    }
}
