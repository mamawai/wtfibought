package com.mawai.wiibagent.trader.wakeup;

/** 把到来的事交给旧实例在跑的轮，它的回答 */
public enum Offer {
    /** 插进了在跑的轮 */
    TAKEN,
    /** 还持有这个 trader，但没有能接的轮 */
    NOT_TAKEN,
    /** 已经不持有这个 trader 了 */
    NOT_HELD
}
