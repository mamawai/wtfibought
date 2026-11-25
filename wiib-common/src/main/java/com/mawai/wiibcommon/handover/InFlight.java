package com.mawai.wiibcommon.handover;

/** 手上的活：关停时等它清零，等不到就提前收尾 */
public interface InFlight {

    /** 日志里报的名字 */
    String name();

    /** 此刻还有几件在跑 */
    int count();

    /** 等不到它自己结束时，提前收尾 */
    default void cut() {
    }
}
