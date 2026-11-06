package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * 交易员集合：feed 按它另开两条连接订行情（markPrice、miniTicker）。
 * = 非 feed 币的 OPEN 仓位所在的币 ∪ 交易员登记的、今天要下单的币（登记 2 小时后过期）。
 * 每分钟按库里的仓位和登记重算一次，变了才写 Redis set；登记那一刻也算一次，feed 10 秒内就能订上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderSymbolService {

    /** feed 读的集合 */
    static final String SYMBOLS_KEY = "futures:trader:symbols";
    /** 登记：member 是币，score 是过期时刻（毫秒） */
    static final String WATCH_KEY = "futures:trader:watch";
    static final long WATCH_TTL_MS = 2 * 3_600_000L;

    private final StringRedisTemplate redis;
    private final FuturesPositionMapper positionMapper;
    private final BinanceProperties binanceProperties;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    public void watch(Collection<String> symbols) {
        double expireAt = nowMs.getAsLong() + WATCH_TTL_MS;
        for (String s : symbols) {
            redis.opsForZSet().add(WATCH_KEY, s, expireAt);
        }
        refresh();
    }

    /** sim 盯着的全部合约币：feed 配置里的 + 交易员的币（交易员的币没有成交 Stream，只靠巡检） */
    public Set<String> allFuturesSymbols() {
        Set<String> symbols = new LinkedHashSet<>(binanceProperties.getAllFuturesSymbols());
        symbols.addAll(redis.opsForSet().members(SYMBOLS_KEY));
        return symbols;
    }

    /** 登记那一刻和每分钟定时各算一次，串行 */
    public synchronized void refresh() {
        redis.opsForZSet().removeRangeByScore(WATCH_KEY, Double.NEGATIVE_INFINITY, nowMs.getAsLong());
        SortedSet<String> wanted = new TreeSet<>(redis.opsForZSet().range(WATCH_KEY, 0, -1));
        wanted.addAll(positionMapper.openSymbols());
        // feed 配置里的币它本来就订着
        wanted.removeAll(binanceProperties.getAllFuturesSymbols());
        Set<String> current = redis.opsForSet().members(SYMBOLS_KEY);
        if (wanted.equals(current)) return;
        List<String> added = wanted.stream().filter(s -> !current.contains(s)).toList();
        List<String> removed = current.stream().filter(s -> !wanted.contains(s)).sorted().toList();
        if (!added.isEmpty()) redis.opsForSet().add(SYMBOLS_KEY, added.toArray(String[]::new));
        if (!removed.isEmpty()) redis.opsForSet().remove(SYMBOLS_KEY, removed.toArray());
        log.info("交易员集合 {} 个：+{} -{}", wanted.size(), added, removed);
    }
}
