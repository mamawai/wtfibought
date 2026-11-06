package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 交易员的币的合约行情：sim 把"非 feed 币的未平仓位所在的币 ∪ 交易员登记的币"维护成 Redis set futures:trader:symbols，
 * 本 handler 照 {@link FuturesStreamHandler} 的样子再开两条连接（markPrice@1s 走本连接，miniTicker 走 {@link TraderMiniTickStreamHandler}），
 * 地址按集合拼。每 10 秒读一次集合：变了两条连接都换地址重连，空了地址为空、连接闲置。
 * 只写标记价、最新价和写入时刻、当期费率和下次结算时刻；不发撮合价格频道、不写成交 Stream、不推前端、不记空窗：
 * sim 的成交触发和空窗补漏只管 feed 配置里那些币，交易员的币上的限价单、止损止盈、强平靠 sim 30 秒一次的兜底巡检。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderFuturesStreamHandler implements StreamHandler {

    static final String SYMBOLS_KEY = "futures:trader:symbols";
    static final long POLL_MS = 10_000L;

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final FundingRateWriter fundingRateWriter;

    /** 当前按它订的集合，排好序；两条连接拼地址都用它 */
    private volatile List<String> symbols = List.of();
    private volatile WsConnection conn;
    /** 副连接（miniTicker），bind 时由副 handler 交进来 */
    @Setter
    private volatile WsConnection miniTickConn;

    @Override public String name() { return "TraderFutures"; }
    @Override public long maxIdleSeconds() { return 90; }
    @Override public boolean dynamicSymbols() { return true; }

    /** 进程起来先读一次集合，装配时按它拼地址 */
    @PostConstruct
    void init() {
        symbols = readSymbols();
    }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
        Thread.ofVirtual().name("trader-symbols-poll").start(this::poll);
    }

    @Override
    public String buildUrl() {
        return url("markPrice@1s");
    }

    String miniTickUrl() {
        return url("miniTicker");
    }

    private String url(String suffix) {
        List<String> current = symbols;
        return current.isEmpty() ? "" : StreamUrls.combinedUrl(props.getFuturesWsUrl(), "market", current, suffix);
    }

    @Override
    public void onMessage(String raw) {
        handle(raw);
    }

    /** 两条连接共用：只写价格键 */
    void handle(String raw) {
        FuturesStreamHandler.Tick tick = FuturesStreamHandler.parse(raw);
        if (tick == null) return;
        if (tick.markPrice()) {
            cacheService.setMarkPrice(tick.symbol(), tick.price(), System.currentTimeMillis());
            fundingRateWriter.write(tick.symbol(), tick.rate(), tick.nextTime());
        } else {
            cacheService.setFuturesPrice(tick.symbol(), tick.price());
        }
    }

    private void poll() {
        while (true) {
            try {
                Thread.sleep(POLL_MS);
                refresh();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("读交易员集合失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 集合变了就换地址重连；空了 reconnectNow 会按空地址闲置。
     * 比的是连接实际用的地址：建连途中集合变了，这一轮 reconnectNow 被在途的连接短路、换不上，下一轮再比就换上了
     */
    void refresh() {
        List<String> latest = readSymbols();
        if (!latest.equals(symbols)) {
            log.info("交易员集合 {} → {} 个，换地址重连", symbols.size(), latest.size());
            symbols = latest;
        }
        if (!buildUrl().equals(conn.url())) conn.reconnectNow();
        if (!miniTickUrl().equals(miniTickConn.url())) miniTickConn.reconnectNow();
    }

    private List<String> readSymbols() {
        return redisTemplate.opsForSet().members(SYMBOLS_KEY).stream().sorted().toList();
    }
}
