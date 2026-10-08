package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.enums.LedgerBizType;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 账本语义上下文。两层：
 * <ul>
 *   <li>方法级：@Ledger 或 LedgerTx 进入时压栈、退出弹栈（栈是为了嵌套调用互不干扰）</li>
 *   <li>一次性：mark() 设置，被下一笔资金变动取走后立即失效</li>
 * </ul>
 * 一次性标注刻意做成"消费即清"——漏标只会退化成方法级语义，
 * 不会把上一笔的标注错安到下一笔头上（错标比无标更难查）。
 */
@Slf4j
public final class LedgerCtx {

    /**
     * 一次性标注：类型 + 关联对象。
     * userId/amount 只给"资金费扣仓位保证金"用——那条 SQL 的参数里只有 positionId，
     * 切面拿不到用户和扣款额，必须由调用方带进来。其余场景这两个字段为 null。
     */
    public record Mark(LedgerBizType type, String refType, Long refId,
                       Long userId, BigDecimal amount) {}

    /** 方法级上下文：类型 + 该方法内共享的 symbol */
    static final class Frame {
        final LedgerBizType type;
        String symbol;
        Frame(LedgerBizType type) { this.type = type; }
    }

    /**
     * 刻意不用 withInitial：一读就建栈的话，每笔没标注的资金变动（现在是全部）都会给自己的线程
     * 绑上一个空 ArrayDeque，而 pop() 里的 remove() 只在"弹到空"时才触发——从没 push 过的线程
     * 永远等不到那次 remove，空栈就一直挂在 ThreadLocalMap 上。改成 push 时懒建，读侧判 null。
     */
    private static final ThreadLocal<Deque<Frame>> FRAMES = new ThreadLocal<>();
    private static final ThreadLocal<Mark> ONE_SHOT = new ThreadLocal<>();

    private LedgerCtx() {}

    // ===== 业务代码用 =====

    /** 标注下一笔资金变动的业务类型 */
    public static void mark(LedgerBizType type) {
        ONE_SHOT.set(new Mark(type, null, null, null, null));
    }

    /** 标注下一笔，并关联订单号 */
    public static void mark(LedgerBizType type, Long refId) {
        ONE_SHOT.set(new Mark(type, null, refId, null, null));
    }

    /** 标注下一笔，并关联指定类型的对象 */
    public static void mark(LedgerBizType type, String refType, Long refId) {
        ONE_SHOT.set(new Mark(type, refType, refId, null, null));
    }

    /**
     * 资金费扣仓位保证金专用。那条 SQL 只吃 positionId，切面既拿不到 userId
     * 也拿不到扣款额（Partial 版连金额参数都没有），两者都得调用方带进来。
     */
    public static void markPositionFee(Long userId, Long positionId, BigDecimal amount) {
        ONE_SHOT.set(new Mark(LedgerBizType.FUNDING_FEE_FROM_MARGIN, "POSITION", positionId, userId, amount));
    }

    /**
     * 补充当前方法内所有资金变动的 symbol（账单显示"BTC永续"这类）。
     * symbol 挂在方法级 frame 上，所以只有在 @Ledger 方法或 LedgerTx 段内调用才有效；
     * 没 frame 说明调用方两样都没走，此时静默丢掉 symbol 会让账单少字段还查不出原因，故打 WARN。
     */
    public static void symbol(String symbol) {
        Frame f = currentFrame();
        if (f == null) {
            log.warn("[Ledger] symbol({}) 落空：当前不在 @Ledger 方法或 LedgerTx 段内，没有方法级上下文可挂", symbol);
            return;
        }
        f.symbol = symbol;
    }

    // ===== 切面和 LedgerTx 用 =====

    static void push(LedgerBizType type) {
        Deque<Frame> stack = FRAMES.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            FRAMES.set(stack);
        }
        stack.push(new Frame(type));
    }

    static void pop() {
        Deque<Frame> stack = FRAMES.get();
        if (stack == null) return;
        stack.poll();
        if (stack.isEmpty()) FRAMES.remove();   // 虚拟线程/线程池复用下不留残值
    }

    /** 取走一次性标注，取完即清 */
    static Mark takeMark() {
        Mark m = ONE_SHOT.get();
        if (m != null) ONE_SHOT.remove();
        return m;
    }

    static LedgerBizType currentType() {
        Frame f = currentFrame();
        return f == null ? null : f.type;
    }

    static String currentSymbol() {
        Frame f = currentFrame();
        return f == null ? null : f.symbol;
    }

    /** 读侧统一走这里：栈可能压根没建（没进过 @Ledger 方法或 LedgerTx 段），不能直接 get().peek() */
    private static Frame currentFrame() {
        Deque<Frame> stack = FRAMES.get();
        return stack == null ? null : stack.peek();
    }
}
