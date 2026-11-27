package com.mawai.wiibfeed;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibfeed.health.StreamHealthPublisher;
import com.mawai.wiibfeed.health.WsConnectionRegistry;
import com.mawai.wiibfeed.stream.StreamHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Binance 行情多路 WS 客户端：装配器。
 * 把每个 {@link StreamHandler}（现货/合约/深度/K线…）组装成一条 {@link WsConnection} 并启动——
 * handler 提供"URL 怎么拼、消息怎么解析"，WsConnection 管"连接/重连/看门狗"，二者组合。
 * <p>
 * 进活的口：开口是装配并建连（待命的实例不连币安），关口是断掉全部连接。
 * {@link #ready()}：每条连接要么连上之后收到过业务帧、或者连上已经 5 秒（冷门流可以一直没帧），要么是闲置（按集合订的流集合为空）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWsClient implements Intake {

    /** 连上这么久还没帧的也算好了 */
    private static final long QUIET_READY_MS = 5_000;

    private final BinanceProperties props;
    private final List<StreamHandler> handlers;
    private final WsConnectionRegistry registry;
    private final StreamHealthPublisher healthPublisher;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final List<WsConnection> connections = new ArrayList<>();

    @Override
    public void open(Start how) {
        if (props.getSymbols() == null || props.getSymbols().isEmpty()) {
            log.warn("binance.symbols未配置，跳过WS连接");
            return;
        }
        // 虚拟线程工厂做调度池，轻量不占平台线程
        scheduler = Executors.newScheduledThreadPool(3,
                Thread.ofVirtual().name("binance-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 每个 handler 组装成一条连接，再回填连接（读连接状态/记空窗用）
        for (StreamHandler h : handlers) {
            // buildUrl 为空 = 该流无订阅符号（如没配纯合约标的时的 CommodityKline5m），跳过不建连；按集合订的流照样建，先闲置
            String url = h.buildUrl();
            if ((url == null || url.isBlank()) && !h.dynamicSymbols()) {
                log.info("跳过 {}：无订阅符号", h.name());
                continue;
            }
            WsConnection conn = new WsConnection(h.name(), h::buildUrl, h::onMessage,
                    h::onConnected, h::onDisconnected,
                    httpClient, scheduler, shutdown, h.maxIdleSeconds());
            // 接状态回调 + 登记，都在 connect() 之前，确保首帧 CONNECTING/CONNECTED 也被捕获推送
            conn.setOnStatusChange(healthPublisher::publish);
            conn.setOnDropped(h::onDropped);
            registry.register(conn);
            h.bind(conn);
            h.opening(how);
            connections.add(conn);
        }
        connections.forEach(WsConnection::connect);
    }

    @Override
    public void close() {
        shutdown.set(true);
        connections.forEach(WsConnection::close);
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    @Override
    public boolean ready() {
        long now = System.currentTimeMillis();
        return connections.stream().allMatch(c -> ready(c, now));
    }

    /** 一条连接好了没：闲置的算；连上的要收到过业务帧，或者已经连上 5 秒 */
    static boolean ready(WsConnection c, long now) {
        return switch (c.status()) {
            case IDLE -> true;
            case CONNECTED -> c.lastMessageAt() > c.connectedAt() || now - c.connectedAt() >= QUIET_READY_MS;
            default -> false;
        };
    }
}
