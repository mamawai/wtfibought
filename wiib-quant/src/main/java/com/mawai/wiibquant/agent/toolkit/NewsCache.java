package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import com.mawai.wiibquant.config.BlockBeatsNewsClient;
import com.mawai.wiibquant.config.BlockBeatsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重要快讯内存缓存（懒加载，全币种共享——新闻已不分币种）。
 * <p>无后台轮询：消费方(深研判/对话)调 {@link #getFlashes()} 时按需拉取——
 * 缓存未过期直接复用（"多币种只拉一次复用"），过期才打一次 API。空闲时零调用/零额度。
 * <p>synchronized：BTC/ETH 深研判整点几乎同时触发时，只放一个线程去拉，其余复用其结果。
 * <p>拉取失败沿用旧缓存（可能已过期），调用方 {@code isEmpty()} 即"无新闻"。
 */
@Slf4j
@Component
public class NewsCache {

    private final BlockBeatsNewsClient client;
    private final BlockBeatsProperties props;

    private volatile List<NewsFlash> flashes = List.of();
    private volatile long lastSuccessAt = 0L;

    public NewsCache(BlockBeatsNewsClient client, BlockBeatsProperties props) {
        this.client = client;
        this.props = props;
    }

    /** 取重要快讯：未过期复用不打 API，过期则拉一次。并发调用只拉一次、其余复用。 */
    public synchronized List<NewsFlash> getFlashes() {
        long now = System.currentTimeMillis();
        if (lastSuccessAt != 0 && now - lastSuccessAt <= props.getExpiryMs()) {
            return flashes;   // 未过期，复用
        }
        List<NewsFlash> fetched = client.fetchImportant();
        if (fetched != null) {
            flashes = fetched;
            lastSuccessAt = now;
            log.info("[NewsCache] 重要快讯刷新 {}条", fetched.size());
        } else {
            log.warn("[NewsCache] 拉取失败，沿用旧缓存({}条)", flashes.size());
        }
        return flashes;
    }
}
