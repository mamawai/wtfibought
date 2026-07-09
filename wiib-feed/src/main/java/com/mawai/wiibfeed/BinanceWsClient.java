package com.mawai.wiibfeed;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.health.StreamHealthPublisher;
import com.mawai.wiibfeed.health.WsConnectionRegistry;
import com.mawai.wiibfeed.stream.StreamHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
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
 * 本类只负责装配 + 进程级启停。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWsClient implements SmartLifecycle {

    private final BinanceProperties props;
    private final List<StreamHandler> handlers;
    private final WsConnectionRegistry registry;
    private final StreamHealthPublisher healthPublisher;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final List<WsConnection> connections = new ArrayList<>();

    @PostConstruct
    public void init() {
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

        // 每个 handler 组装成一条连接，再回填连接+调度器（兜底轮询/读连接状态用）
        for (StreamHandler h : handlers) {
            WsConnection conn = new WsConnection(h.name(), h::buildUrl, h::onMessage,
                    h::onConnected, h::onDisconnected,
                    httpClient, scheduler, shutdown, h.maxIdleSeconds());
            // 接状态回调 + 登记，都在 connect() 之前，确保首帧 CONNECTING/CONNECTED 也被捕获推送
            conn.setOnStatusChange(healthPublisher::publish);
            registry.register(conn);
            h.bind(conn, scheduler);
            connections.add(conn);
        }
        connections.forEach(WsConnection::connect);
    }

    @Override
    public void stop() {
        shutdown.set(true);
        connections.forEach(WsConnection::close);
        // fallback 轮询任务跑在 scheduler 上，shutdownNow 一并取消
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    @Override public boolean isRunning() { return !shutdown.get() && scheduler != null; }
    @Override public int getPhase() { return 1; }
    @Override public void start() { /* init via @PostConstruct */ }
}
