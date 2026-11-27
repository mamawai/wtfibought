package com.mawai.wiibagent.trader.wakeup;

import java.math.BigDecimal;

/**
 * 交接期间和另一个 agent 实例说话。新 → 旧：offer、preset；旧 → 新：handBack、released。
 * 两个实现：走 HTTP 的（分了槽位时），和什么都不做的 {@link #NONE}（没分槽位、单测）
 */
public interface TraderPeer {

    /** 把到来的事交给旧实例在跑的轮 */
    Offer offer(long traderId, Arrival arrival);

    /** 让旧实例执行这条盯盘条件的预案（要过旧实例进程里的账户闸） */
    PresetReply preset(long traderId, long watchId, BigDecimal price, long at);

    /** 旧实例收尾时接不住的事交还给新实例；fresh 是旧实例关口后才到点发的例行（不是没读到重发的） */
    void handBack(long traderId, Arrival arrival, boolean fresh);

    /** 旧实例放手了这个 trader（唤醒那边，或者复盘位） */
    void released(long traderId, Held what);

    /** 放手的是哪一样 */
    enum Held { WAKE, REVIEW }

    /**
     * 预案的回答
     *
     * @param held 旧实例还持有这个 trader（不持有就没执行，调用方本地做）
     * @param wake 执行完要不要叫醒模型
     */
    record PresetReply(boolean held, boolean wake) {
    }

    /** 没有对端：谁都不被持有，交还和放手没人收 */
    TraderPeer NONE = new TraderPeer() {
        @Override
        public Offer offer(long traderId, Arrival arrival) {
            return Offer.NOT_HELD;
        }

        @Override
        public PresetReply preset(long traderId, long watchId, BigDecimal price, long at) {
            return new PresetReply(false, false);
        }

        @Override
        public void handBack(long traderId, Arrival arrival, boolean fresh) {
        }

        @Override
        public void released(long traderId, Held what) {
        }
    };
}
