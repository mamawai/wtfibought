package com.mawai.wiibfeed.health;

import com.mawai.wiibfeed.WsConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WS 连接注册表：BinanceWsClient 装配期把各条行情连接登记进来（含交易员那两条按集合订的，集合空时是 IDLE），作为流健康快照 + 手动重试的单一入口。
 * <p>照 {@code StrategyAccountRegistry} 惯例（@Component + 并发容器 + 单一来源）。用 CopyOnWriteArrayList
 * 保留登记顺序，让前端列表顺序稳定不跳；仅登记 Binance 常连流，Polymarket（CLOB 轮次主动断连）不入册。
 */
@Slf4j
@Component
public class WsConnectionRegistry {

    private final List<WsConnection> connections = new CopyOnWriteArrayList<>();
    /** 上一份快照的版本号 */
    private long lastVersion;

    public void register(WsConnection conn) {
        connections.add(conn);
        log.info("[流健康] 登记 WS 连接 {}", conn.name());
    }

    /**
     * 全量健康快照（登记顺序）。版本号严格递增：取快照那一刻的毫秒，同一毫秒就在上一份上加 1；
     * 用墙钟起算，feed 重启后也比重启前的大。推送经 sim 中继会乱序、进面板拉的也可能比推的晚到，前端按它丢掉旧的
     */
    public synchronized StreamHealthSnapshot snapshot() {
        lastVersion = Math.max(lastVersion + 1, System.currentTimeMillis());
        return new StreamHealthSnapshot(lastVersion, connections.stream().map(StreamHealth::of).toList());
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
