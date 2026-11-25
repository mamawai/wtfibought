package com.mawai.wiibcommon.handover;

/**
 * 进活的口：定时器、读流、订阅、接新请求这类"新活从这里进来"的地方。
 * 编排按 {@code @Order} 从小到大关口，开口反过来：先关上游，后关下游
 */
public interface Intake {

    /** 开口，开始进活 */
    void open(Start how);

    /** 关口。返回时已经停稳：读循环退出了，最后进来的那件活已经交出去了 */
    void close();
}
