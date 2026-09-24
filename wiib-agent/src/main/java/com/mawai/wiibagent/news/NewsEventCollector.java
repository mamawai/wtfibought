package com.mawai.wiibagent.news;

import com.mawai.wiibagent.runtime.AiAgentRuntime;
import com.mawai.wiibagent.runtime.AiAgentRuntimeManager;
import com.mawai.wiibquant.external.blockbeats.BlockBeatsNewsClient;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快讯采集轨：定时拉 BlockBeats 重要快讯 → 增量去重先存中文 → 轻模型补译英文回填 news_event。
 * 首页快讯卡的数据源，顺带为将来的事件研究攒数据。
 * <p>
 * 译文在这里一次性存好，取用侧（首页快讯卡/对话/深研判/trader 唤醒）按语言直接换字：
 * 不拉第二个数据源，中英用户读的是同一份新闻——拉两个源会让中英用户的 trader 拿到不同的
 * 新闻世界，竞技场净值曲线就没有可比性了。
 * <p>
 * 定时轨走 {@link NewsCache} 而不是直连客户端：BlockBeats 免费额度一次性不回血，
 * 缓存 10 分钟窗口内与对话/深研判共享同一次拉取，采集不额外多烧一份额度。
 * 手动补拉要翻页，只能直连。
 * <p>
 * 中文不等翻译：拉到就入库，额度不白花。translated_model 为 NULL＝待译，每轮补译一批，
 * 模型挂了这些行就一直待译，恢复后自动补上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsEventCollector {

    /**
     * BlockBeats create_time 是"2026-07-09 00:30:12"格式的墙钟时间，按北京时间解析成 epoch。
     * 这是推断不是实测（BlockBeats 中文服务、cn 快讯源，几乎必然如此）；上服务器后拿
     * 最新一条快讯的落库时间对一下当下时刻即可证实，偏差恒定 8 小时就是这里错了。
     */
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每轮最多补译几条，新的先译：平时一轮新增不过 20 一轮译完；大批补拉后按轮慢慢补，100 条约 3 轮 */
    private static final int TRANSLATE_LIMIT = 40;

    /** 手动补拉每页条数：接口单次上限 100，一页拉满少烧几次额度 */
    private static final int BACKFILL_PAGE_SIZE = 100;

    private final NewsCache newsCache;
    private final BlockBeatsNewsClient client;
    private final NewsTranslator translator;
    private final NewsEventMapper newsEventMapper;
    private final AiAgentRuntimeManager runtimeManager;

    @Value("${news.collect.enabled:true}")
    private boolean enabled;

    /**
     * 手动补拉结果。
     *
     * @param fetched  实际拉到最近多少条（接口翻到头会少于要的）
     * @param inserted 其中新存条数，其余是库里已有的
     * @param failed   true=拉到 fetched 条后接口失败，后面没翻
     */
    public record BackfillResult(int fetched, int inserted, boolean failed) {
    }

    /** 周期见 news.collect.interval-ms。initialDelay 让开启动高峰，也给 Admin 配模型留缓冲 */
    @Scheduled(fixedDelayString = "${news.collect.interval-ms:1800000}", initialDelay = 60_000)
    public void collect() {
        if (!enabled) {
            return;
        }
        int inserted = store(newsCache.getFlashes());
        if (inserted > 0) {
            log.info("[NewsCollect] 收 {} 条", inserted);
        }
        translatePending();
    }

    /**
     * 手动补拉最近 count 条：直连 BlockBeats 从最新往旧按页翻（接口只能翻页，不能按时间段取），
     * 只存中文，译文交给定时轮补。断档超出定时拉取窗口时用；每页烧一次额度。
     */
    public BackfillResult backfill(int count) {
        int fetched = 0;
        int inserted = 0;
        for (int page = 1; fetched < count; page++) {
            List<NewsFlash> flashes = client.fetchImportant(page, BACKFILL_PAGE_SIZE);
            if (flashes == null) {
                log.warn("[NewsCollect] 补拉第 {} 页失败，已拉 {} 条新存 {} 条", page, fetched, inserted);
                return new BackfillResult(fetched, inserted, true);
            }
            // 最后一页只取到够数
            List<NewsFlash> take = flashes.subList(0, Math.min(flashes.size(), count - fetched));
            inserted += store(take);
            fetched += take.size();
            // 不满一页＝翻到头了；万一接口实际单页上限更低，也别按页码往后翻，会跳掉中间那段
            if (flashes.size() < BACKFILL_PAGE_SIZE) {
                break;
            }
        }
        log.info("[NewsCollect] 补拉最近 {} 条，新存 {} 条", fetched, inserted);
        return new BackfillResult(fetched, inserted, false);
    }

    /** 挑出没入库的只存中文，返回新存条数 */
    private int store(List<NewsFlash> flashes) {
        int inserted = 0;
        for (NewsFlash f : onlyNew(flashes)) {
            inserted += newsEventMapper.insertIgnore(f.id(), f.title(), f.plainContent(), f.url(), publishedAtMs(f));
        }
        return inserted;
    }

    private void translatePending() {
        List<NewsEventMapper.Untranslated> pending = newsEventMapper.selectUntranslated(TRANSLATE_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        AiAgentRuntime runtime;
        try {
            runtime = runtimeManager.current();
        } catch (IllegalStateException e) {
            log.warn("[NewsCollect] news-translation 功能位未配置，{} 条先存中文待译", pending.size());
            return;
        }
        Map<Long, NewsTranslator.Translated> translated = translator.translate(runtime.newsTranslation(), pending);
        int withText = 0;
        for (Map.Entry<Long, NewsTranslator.Translated> e : translated.entrySet()) {
            NewsTranslator.Translated t = e.getValue();
            newsEventMapper.updateTranslation(e.getKey(), t.titleEn(), t.contentEn(), t.model());
            if (t.titleEn() != null) {
                withText++;
            }
        }
        log.info("[NewsCollect] 补译 {} 条（有译文 {} 条，仍待译 {} 条）",
                translated.size(), withText, pending.size() - translated.size());
    }

    private List<NewsFlash> onlyNew(List<NewsFlash> flashes) {
        if (flashes.isEmpty()) {
            return flashes;
        }
        Set<Long> existing = new HashSet<>(newsEventMapper.selectExistingSourceIds(
                flashes.stream().map(NewsFlash::id).toList()));
        return flashes.stream().filter(f -> !existing.contains(f.id())).toList();
    }

    /** 解析失败退当前时刻：快讯卡上位置差一点仍可用，比整条丢掉强 */
    private static long publishedAtMs(NewsFlash flash) {
        try {
            return LocalDateTime.parse(flash.createTime(), SOURCE_TIME)
                    .atZone(SOURCE_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            log.warn("[NewsCollect] create_time 解析失败 id={} raw={}", flash.id(), flash.createTime());
            return System.currentTimeMillis();
        }
    }
}
