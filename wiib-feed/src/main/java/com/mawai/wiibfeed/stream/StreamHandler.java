package com.mawai.wiibfeed.stream;

import com.mawai.wiibfeed.WsConnection;

import java.net.http.WebSocket;

/**
 * 一条 Binance 数据流的处理器：只管"这条流的 URL 怎么拼、消息怎么解析"。
 * 连接生命周期（连接/重连退避/静默看门狗）仍由 {@link WsConnection} 统一管——二者组合而非继承。
 * 每个实现是 @Component，{@code BinanceWsClient} 收集全部 StreamHandler 后逐个组装成 WsConnection。
 */
public interface StreamHandler {

    /** 连接名（日志用），如 "Spot" "Kline5m" */
    String name();

    /** 静默重连阈值秒：0=关闭检测（稀疏流如 forceOrder 取 0） */
    long maxIdleSeconds();

    /** 拼接订阅 URL */
    String buildUrl();

    /** 处理一帧业务消息 */
    void onMessage(String raw);

    /** 连接成功回调，默认空。价格流重写做"发空窗事件" */
    default void onConnected(WebSocket ws) {}

    /** 断开回调，默认空。价格流重写做"广播断线帧" */
    default void onDisconnected() {}

    /**
     * 装配期回填：把所属连接交给需要的 handler。
     * 默认空——只有要读自身连接状态（填广播 ws 字段）、或要记空窗的价格流才重写。
     */
    default void bind(WsConnection conn) {}
}
