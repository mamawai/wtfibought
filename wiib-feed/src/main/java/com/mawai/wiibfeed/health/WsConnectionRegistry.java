package com.mawai.wiibfeed.health;

import com.mawai.wiibfeed.WsConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WS 连接注册表：BinanceWsClient 装配期把 8 条行情连接登记进来，作为流健康快照 + 手动重试的单一入口。
 * <p>照 {@code StrategyAccountRegistry} 惯例（@Component + 并发容器 + 单一来源）。用 CopyOnWriteArrayList
 * 保留登记顺序，让前端列表顺序稳定不跳；仅登记 Binance 常连流，Polymarket（CLOB 轮次主动断连）不入册。
 */
@Slf4j
@Component
public class WsConnectionRegistry {

    private final List<WsConnection> connections = new CopyOnWriteArrayList<>();

    public void register(WsConnection conn) {
        connections.add(conn);
        log.info("[流健康] 登记 WS 连接 {}", conn.name());
    }

    /** 全量健康快照（登记顺序）。 */
    public List<StreamHealth> snapshot() {
        return connections.stream().map(StreamHealth::of).toList();
    }

    /** 手动重试指定流：命中即立即重连，返回是否命中（未命中→前端提示流名无效）。 */
    public boolean retry(String name) {
        for (WsConnection c : connections) {
            if (c.name().equals(name)) {
                c.reconnectNow();
                return true;
            }
        }
        log.warn("[流健康] 重试未命中的流名: {}", name);
        return false;
    }
}
