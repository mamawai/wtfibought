package com.mawai.wiibquant.market.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibquant.external.deribit.DeribitClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MarketDataServiceTest {

    /** 一根合法 K 线：只为让 data_available=true，不足 30 根所以指标计算会跳过 */
    private static final String ONE_KLINE = "[[1,\"1\",\"2\",\"1\",\"1.5\",\"10\",2,\"15\",5,\"6\"]]";
    private static final String WS_DEPTH = "{\"bids\":[[\"1\",\"1\"]],\"asks\":[[\"2\",\"1\"]]}";

    /** 拨表用的基准时刻。固定值而非 currentTimeMillis：年龄上限的边界用例不该受运行时刻影响 */
    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR_MS = 3_600_000L;

    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
    private final ForceOrderService forceOrderService = mock(ForceOrderService.class);
    private final DepthStreamCache depthStreamCache = mock(DepthStreamCache.class);
    private final DeribitClient deribitClient = mock(DeribitClient.class);
    private final OrderFlowAggregator orderFlowAggregator = mock(OrderFlowAggregator.class);

    private MarketDataService service(long ttlMillis) {
        return new MarketDataService(binanceRestClient, forceOrderService, depthStreamCache,
                deribitClient, orderFlowAggregator, KlineInterval.M5, ttlMillis);
    }

    @Test
    void unavailableWhenCollectReturnsNothing() {
        // 全部 mock 默认返回 null → collect 判 data_available=false → 组装降级为 unavailable
        MarketAssembly assembly = service(60_000).assemble("BTCUSDT");

        assertThat(assembly.available()).isFalse();
        assertThat(assembly.snapshot()).isNull();
    }

    @Test
    void cachesAssemblyWithinTtl() {
        stubCollectableSymbol();
        MarketDataService service = service(60_000);

        MarketAssembly first = service.assemble("BTCUSDT");
        MarketAssembly second = service.assemble("BTCUSDT");

        // TTL 内同一实例直接复用，不重复采集
        assertThat(second).isSameAs(first);
    }

    /** 一次抖动不该让接下来 60s 都报没数据：采不到的结果不进缓存，下一次照样去采 */
    @Test
    void 不可用的组装结果不进缓存() {
        MarketDataService service = service(60_000);

        service.assemble("BTCUSDT");
        service.assemble("BTCUSDT");

        verify(binanceRestClient, times(2)).getFutures24hTicker("BTCUSDT");
    }

    /** 整装快照活不过 5m 边界：边界前 10s 组装的，边界后 5s 还在 TTL 内也得重采——里面的指标、regime 都按收盘算 */
    @Test
    void 整装快照活不过5m边界() {
        stubCollectableSymbol();
        MarketDataService service = service(60_000);
        long bar = T0 + 100_000L;   // T0 之后第一个 5m 边界
        service.nowMs = () -> bar - 10_000L;
        service.assemble("BTCUSDT");
        service.nowMs = () -> bar + 5_000L;
        service.assemble("BTCUSDT");

        verify(binanceRestClient, times(2)).getFutures24hTicker("BTCUSDT");
    }

    @Test
    void refreshesWhenTtlExpired() {
        MarketDataService service = service(0); // ttl=0 → 每次都过期

        MarketAssembly first = service.assemble("BTCUSDT");
        MarketAssembly second = service.assemble("BTCUSDT");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void normalizesSymbol() {
        stubCollectableSymbol();
        MarketDataService service = service(60_000);

        MarketAssembly a = service.assemble(" btcusdt ");
        MarketAssembly b = service.assemble("BTCUSDT");

        assertThat(a.symbol()).isEqualTo("BTCUSDT");
        assertThat(b).isSameAs(a); // 归一化后命中同一缓存
    }

    /**
     * TTL 过期瞬间 N 个线程同时 miss，必须只有一个真打 HTTP——配额按 IP 算，N 倍击穿会连累策略轨。
     */
    @Test
    void 并发未命中时只真采集一次() throws Exception {
        // 把采集卡住 300ms，逼所有线程都挤进在途表，而不是靠调度侥幸串行后命中缓存
        when(binanceRestClient.getFutures24hTicker("BTCUSDT")).thenAnswer(inv -> {
            Thread.sleep(300);
            return null;
        });
        MarketDataService service = service(60_000);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<MarketAssembly> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    results.add(service.assemble("BTCUSDT"));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        start.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        // 直接验请求次数，这才是"只采集一次"的证据；同一性断言只是顺带
        verify(binanceRestClient, times(1)).getFutures24hTicker("BTCUSDT");
        assertThat(results).hasSize(threads);
        MarketAssembly first = results.getFirst();
        assertThat(results).allMatch(a -> a == first);
    }

    /** 资金费历史和盘口在 ReAct 循环里会被反复调，必须跟快照一样吃 TTL 缓存，不能每次真发请求 */
    @Test
    void 资金费历史与盘口在TTL内复用不重复请求() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30)).thenReturn("[{\"fundingRate\":\"0.0001\"}]");
        when(binanceRestClient.getFuturesOrderbook("BTCUSDT", 10)).thenReturn("{\"bids\":[],\"asks\":[]}");
        MarketDataService service = service(60_000);

        service.fundingHistory("BTCUSDT");
        service.fundingHistory("btcusdt");
        service.orderbook("BTCUSDT");
        service.orderbook(" BTCUSDT ");

        // 归一化后命中同一缓存键，四次调用只应打两个真请求
        verify(binanceRestClient, times(1)).getFundingRateHistory("BTCUSDT", 30);
        verify(binanceRestClient, times(1)).getFuturesOrderbook("BTCUSDT", 10);
        // 归一化要是坏了会多出 "btcusdt"/" BTCUSDT " 那几组调用，上面两条 times(1) 照样绿，这条才拦得住
        verifyNoMoreInteractions(binanceRestClient);
    }

    /** TTL=0 时每次都过期，必须真发请求——否则缓存就成了永久缓存 */
    @Test
    void 资金费历史TTL过期后重新请求() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30)).thenReturn("[]");
        MarketDataService service = service(0);

        service.fundingHistory("BTCUSDT");
        service.fundingHistory("BTCUSDT");

        verify(binanceRestClient, times(2)).getFundingRateHistory("BTCUSDT", 30);
    }

    /**
     * 采集线程炸了不能把等待者一起拖走：赢家自己把异常抛出去，等待者拿降级快照继续走，
     * 否则一个 symbol 的畸形数据会顺着 join() 打断所有等它的对话/交易线程。
     */
    @Test
    void 采集失败时等待者拿到降级快照() throws Exception {
        stubSlowCollectableSymbol();
        when(orderFlowAggregator.hasData("BTCUSDT")).thenThrow(new IllegalStateException("aggTrade 数据结构坏了"));
        MarketDataService service = service(60_000);

        Outcome outcome = assembleFromTwoThreads(service);

        assertThat(outcome.allFinished()).isTrue();
        assertThat(outcome.errors()).hasSize(1);
        assertThat(outcome.errors().getFirst()).hasMessageContaining("aggTrade 数据结构坏了"); // 赢家原样抛出
        assertThat(outcome.results()).singleElement()
                .matches(a -> !a.available()); // 等待者降级，没被别人的异常打断
    }

    /**
     * 采集线程撞上 Error（爆栈/OOM/类初始化失败）时等待者也必须被唤醒，且拿到的是 Error 本尊：
     * join() 既没超时也不可中断，漏一次 complete 就是一批 interrupt 都杀不掉的僵尸线程；
     * 而若改用兜底壳异常给终局，等待者日志里就只剩那个壳子，真凶（爆栈）彻底看不见。
     */
    @Test
    void 采集线程遇Error时等待者不被挂死() throws Exception {
        ListAppender<ILoggingEvent> logs = captureServiceLogs();
        stubSlowCollectableSymbol();
        when(orderFlowAggregator.hasData("BTCUSDT")).thenThrow(new StackOverflowError("递归爆栈"));
        MarketDataService service = service(60_000);

        Outcome outcome = assembleFromTwoThreads(service);

        assertThat(outcome.allFinished()).isTrue(); // 等待者挂死的话这里 15 秒后是 false
        assertThat(outcome.errors()).hasSize(1);
        assertThat(outcome.errors().getFirst()).isInstanceOf(StackOverflowError.class);
        assertThat(outcome.results()).singleElement().matches(a -> !a.available());
        // 等待者对外一律降级成 unavailable，根因只在它那条 warn 日志里露面，只能打在日志上
        assertThat(waiterFailureCause(logs)).isEqualTo(StackOverflowError.class.getName());
    }

    /** 盘口有 WS 快照就绝不打 REST——WS 是 100ms 级且不吃 Binance 配额，REST 只是断流兜底 */
    @Test
    void 盘口优先用WS快照() {
        when(depthStreamCache.getFreshDepth("BTCUSDT", 2000)).thenReturn(WS_DEPTH);
        MarketDataService service = service(60_000);

        String depth = service.orderbook("btcusdt");

        assertThat(depth).isEqualTo(WS_DEPTH);
        verify(binanceRestClient, never()).getFuturesOrderbook(anyString(), anyInt());
    }

    /**
     * 熔断冷却 120s 比 TTL 60s 长：取不到新数据时资金费必须兜过期那份，
     * 否则内存里明明躺着够用的数据却要黑掉 60 秒（资金费 8h 才结算一次，这点过期无所谓）。
     * <p>
     * "无所谓"只在兜底上限之内成立，超了照样返回 null，见 {@link #资金费缓存超过8小时后不再兜()}。
     */
    @Test
    void 资金费取不到新数据时兜过期缓存() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30))
                .thenReturn("[{\"fundingRate\":\"0.0001\"}]")
                .thenReturn(null); // 第二次模拟熔断中
        MarketDataService service = service(0); // ttl=0 → 第二次必然过期，直接触发重取

        String first = service.fundingHistory("BTCUSDT");
        String second = service.fundingHistory("BTCUSDT");

        assertThat(second).isEqualTo(first);
    }

    /** 资金费上下文是 funding_history 里最后一个裸奔的真请求，ReAct 循环反复取必须吃缓存 */
    @Test
    void 资金费上下文在TTL内复用不重复请求() {
        when(binanceRestClient.getPremiumIndex("BTCUSDT")).thenReturn("{\"markPrice\":\"100\"}");
        MarketDataService service = service(60_000);

        service.premiumIndex("BTCUSDT");
        service.premiumIndex("btcusdt");
        service.premiumIndex(" BTCUSDT ");

        verify(binanceRestClient, times(1)).getPremiumIndex("BTCUSDT");
        // 归一化要是坏了会多出 "btcusdt"/" BTCUSDT " 那两组调用，上面 times(1) 照样绿，这条才拦得住
        verifyNoMoreInteractions(binanceRestClient);
    }

    /**
     * 熔断期（上游返回 null）必须回退到过期缓存：不兜的话资金费上下文一 null 就把整条
     * funding_history 拖垮，历史那边的兜底跟着白做——两者共用同一个熔断器，会同时进入熔断。
     * <p>
     * 但<b>不同时到期</b>：两边的兜底上限是两个数（premium 15min / funding 8h），
     * 所以整机停摆时先空掉的一定是 premium。别把这里读成"同进同退"。
     */
    @Test
    void 资金费上下文取不到新数据时兜过期缓存() {
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100\"}")
                .thenReturn(null); // 第二次模拟熔断中
        MarketDataService service = service(0); // ttl=0 → 第二次必然过期，直接触发重取

        String first = service.premiumIndex("BTCUSDT");
        String stale = service.premiumIndex("BTCUSDT");

        assertThat(first).contains("100");
        assertThat(stale).isEqualTo(first);
    }

    /** 从没成功过就没有过期那份可兜，只能如实返回 null——不能凭空给模型造标记价 */
    @Test
    void 资金费上下文从未成功过时返回null() {
        when(binanceRestClient.getPremiumIndex("BTCUSDT")).thenReturn(null);

        assertThat(service(60_000).premiumIndex("BTCUSDT")).isNull();
    }

    /**
     * 兜过期不是无限期的：资金费超过 8 小时就跨了结算周期，那份数据是实质错误的，
     * 宁可当没有（走 available:false），也不能拿上一周期的费率冒充本周期。
     * <p>
     * 拨表而不是真等：{@code nowMs} 同时管 TTL 和年龄上限，一次拨表就复刻了生产上
     * "TTL 过期 → 重取 → 熔断中取不到 → 看年龄" 这条完整路径。
     */
    @Test
    void 资金费缓存超过8小时后不再兜() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30))
                .thenReturn("[{\"fundingRate\":\"0.0001\"}]")
                .thenReturn(null); // 第二次模拟熔断中
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;

        assertThat(service.fundingHistory("BTCUSDT")).contains("0.0001"); // 这一步把缓存写在 T0
        service.nowMs = () -> T0 + 8 * HOUR_MS + 1; // 越线 1ms：钉的是"越线之后不兜"

        assertThat(service.fundingHistory("BTCUSDT")).isNull();
    }

    /**
     * 边界点本身：年龄<b>恰好等于</b>上限时不兜（判据是严格小于）。
     * <p>
     * 单独一条是因为上面那条越线 1ms、下面那条差 1 秒，都够不到这个点——
     * 把 {@code <} 写成 {@code <=} 时它俩全绿。别指望盘口那条老用例代劳：
     * 它走真实时钟且 {@code maxStale=0}，两次调用落在同一毫秒、age 恰好为 0 才顺带红的，
     * 机器慢一点就漏。
     */
    @Test
    void 资金费缓存年龄恰好等于上限时不再兜() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30))
                .thenReturn("[{\"fundingRate\":\"0.0001\"}]")
                .thenReturn(null);
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;

        assertThat(service.fundingHistory("BTCUSDT")).contains("0.0001");
        service.nowMs = () -> T0 + 8 * HOUR_MS;

        assertThat(service.fundingHistory("BTCUSDT")).isNull();
    }

    /**
     * 上限之内还是要兜：熔断冷却 120s 比 TTL 60s 长，不兜等于内存里躺着够用的数据却白黑掉 60 秒。
     * 卡在差 1 秒不满 8 小时，防的是"上限判断写反 / 写成恒不兜"——那样 serve-stale 就整个废了。
     */
    @Test
    void 资金费缓存不满8小时仍然兜() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30))
                .thenReturn("[{\"fundingRate\":\"0.0001\"}]")
                .thenReturn(null);
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;

        String first = service.fundingHistory("BTCUSDT");
        service.nowMs = () -> T0 + 8 * HOUR_MS - 1000;

        assertThat(service.fundingHistory("BTCUSDT")).isEqualTo(first);
    }

    /** 标记价是实时价，上限只有一刻钟：超了就是拿旧价冒充现价，宁可报不可用 */
    @Test
    void 资金费上下文缓存超过15分钟后不再兜() {
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100\"}")
                .thenReturn(null);
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;

        assertThat(service.premiumIndex("BTCUSDT")).contains("100");
        service.nowMs = () -> T0 + 15 * 60_000L + 1;

        assertThat(service.premiumIndex("BTCUSDT")).isNull();
    }

    /**
     * 两个调用点各传各的上限，不是同一个数：挑 1 小时这个点——对资金费（8h）还在窗口内，
     * 对标记价（15min）早就超龄了。上面三条其实已经间接钉住，这条把契约摆在明面上：
     * 谁把两处并成一个常量，这里当场红。
     */
    @Test
    void 资金费与资金费上下文的兜底上限不是同一个数() {
        when(binanceRestClient.getFundingRateHistory("BTCUSDT", 30))
                .thenReturn("[{\"fundingRate\":\"0.0001\"}]")
                .thenReturn(null);
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100\"}")
                .thenReturn(null);
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;
        service.fundingHistory("BTCUSDT");
        service.premiumIndex("BTCUSDT");

        service.nowMs = () -> T0 + HOUR_MS;

        assertThat(service.fundingHistory("BTCUSDT")).contains("0.0001"); // 8h 内，照兜
        assertThat(service.premiumIndex("BTCUSDT")).isNull();            // 15min 外，不兜
    }

    /** 盘口反过来：agent 拿它挂限价单，宁可报不可用也不能喂两分钟前的挂单墙 */
    @Test
    void 盘口取不到新数据时不兜过期缓存() {
        when(binanceRestClient.getFuturesOrderbook("BTCUSDT", 10))
                .thenReturn("{\"bids\":[],\"asks\":[]}")
                .thenReturn(null);
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;

        service.orderbook("BTCUSDT");
        service.nowMs = () -> T0 + 3_000L;   // 2s 新鲜期已过，重取失败也不兜
        String second = service.orderbook("BTCUSDT");

        assertThat(second).isNull();
    }

    /** REST 兜底的盘口只存 2s，和 WS 快照的新鲜度一个口径；60s 的通用 TTL 对盘口太长 */
    @Test
    void 盘口REST兜底只存2秒() {
        when(binanceRestClient.getFuturesOrderbook("BTCUSDT", 10)).thenReturn("{\"bids\":[],\"asks\":[]}");
        MarketDataService service = service(60_000);
        service.nowMs = () -> T0;
        service.orderbook("BTCUSDT");
        service.nowMs = () -> T0 + 1_999L;
        service.orderbook("BTCUSDT");
        service.nowMs = () -> T0 + 2_000L;
        service.orderbook("BTCUSDT");

        verify(binanceRestClient, times(2)).getFuturesOrderbook("BTCUSDT", 10);
    }

    /** 资金费上下文活不过 8h 结算边界：结算后费率和下次结算时间都换了，TTL 没到也得重取 */
    @Test
    void 资金费上下文活不过8h结算边界() {
        when(binanceRestClient.getPremiumIndex("BTCUSDT")).thenReturn("{\"markPrice\":\"100\"}");
        MarketDataService service = service(60_000);
        long settle = T0 + 6_400_000L;   // T0 之后第一个 8h 结算边界
        service.nowMs = () -> settle - 10_000L;
        service.premiumIndex("BTCUSDT");
        service.nowMs = () -> settle + 5_000L;
        service.premiumIndex("BTCUSDT");

        verify(binanceRestClient, times(2)).getPremiumIndex("BTCUSDT");
    }

    /** 等待者的根因不进任何返回值，只落在 join() 那条 warn 日志里，装个 appender 才验得到 */
    private ListAppender<ILoggingEvent> captureServiceLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(MarketDataService.class)).addAppender(appender);
        return appender;
    }

    @AfterEach
    void 摘掉日志采集器() {
        ((Logger) LoggerFactory.getLogger(MarketDataService.class)).detachAndStopAllAppenders();
    }

    /**
     * 按"带异常"筛而不按文案筛，改文案也不会失配。
     * <p>
     * 限定语别丢：<b>装了 appender 的那条用例</b>只走 assemble()，这条路上带 throwable 的日志
     * 只有 join() 那条。MarketDataService.rawCached 的"取数失败"同样带 throwable——
     * 本类别处确实有走到它的用例，只是那些用例没装 appender，所以筛不到一起。
     * findFirst() 是顺序敏感的：哪天装了 appender 的用例也走 rawCached，就会筛出错的那条，
     * 断言当场红（除非那条恰好也是 SOE），而不是筛错了还悄悄绿。
     */
    private static String waiterFailureCause(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
                .filter(e -> e.getThrowableProxy() != null)
                .map(e -> e.getThrowableProxy().getClassName())
                .findFirst()
                .orElseThrow(() -> new AssertionError("等待者根本没走到失败分支"));
    }

    /** K线+ticker 齐活让 data_available=true，组装结果可用、会进缓存 */
    private void stubCollectableSymbol() {
        when(binanceRestClient.getFuturesKlines(eq("BTCUSDT"), anyString(), anyInt(), any()))
                .thenReturn(ONE_KLINE);
        when(binanceRestClient.getFutures24hTicker("BTCUSDT")).thenReturn("{\"lastPrice\":\"65000\"}");
    }

    /** K线+ticker 齐活让 data_available=true 走进特征构建；ticker 拖 300ms 是为了撑开在途窗口 */
    private void stubSlowCollectableSymbol() {
        when(binanceRestClient.getFuturesKlines(eq("BTCUSDT"), anyString(), anyInt(), any()))
                .thenReturn(ONE_KLINE);
        when(binanceRestClient.getFutures24hTicker("BTCUSDT")).thenAnswer(inv -> {
            Thread.sleep(300);
            return "{\"lastPrice\":\"65000\"}";
        });
    }

    /**
     * symbol 入口不校验白名单，模型问什么就缓存什么。裸 Map 只增不减时，
     * 连问上千个不存在的币就能把内存撑起来——这里连问 200 个，验 LRU 真在淘汰。
     */
    @Test
    void assemblyCacheIsBounded() {
        // 可用的才进缓存，所以每个假币都得采得到
        when(binanceRestClient.getFuturesKlines(anyString(), anyString(), anyInt(), any())).thenReturn(ONE_KLINE);
        when(binanceRestClient.getFutures24hTicker(anyString())).thenReturn("{\"lastPrice\":\"65000\"}");
        MarketDataService service = service(600_000);      // TTL 拉长，确保不是靠过期而是靠淘汰
        for (int i = 0; i < 200; i++) {
            service.assemble("FAKE" + i + "USDT");
        }

        assertThat(cacheSize(service, "cache")).isLessThanOrEqualTo(32);
    }

    @Test
    void rawCacheIsBounded() {
        when(binanceRestClient.getFundingRateHistory(anyString(), anyInt())).thenReturn("[]");
        when(binanceRestClient.getPremiumIndex(anyString())).thenReturn("{}");
        MarketDataService service = service(600_000);
        for (int i = 0; i < 200; i++) {
            service.fundingHistory("FAKE" + i + "USDT");
            service.premiumIndex("FAKE" + i + "USDT");
        }

        assertThat(cacheSize(service, "rawCache")).isLessThanOrEqualTo(128);
    }

    private static int cacheSize(MarketDataService service, String field) {
        try {
            var f = MarketDataService.class.getDeclaredField(field);
            f.setAccessible(true);
            return ((Map<?, ?>) f.get(service)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("取不到缓存字段 " + field, e);
        }
    }

    private record Outcome(boolean allFinished, List<MarketAssembly> results, List<Throwable> errors) {}

    /** 两个线程错开 50ms 进 assemble：第二个必然撞在第一个的在途窗口里，当上等待者 */
    private Outcome assembleFromTwoThreads(MarketDataService service) throws Exception {
        List<MarketAssembly> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            Thread th = new Thread(() -> {
                try {
                    results.add(service.assemble("BTCUSDT"));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
            Thread.sleep(50);
        }
        return new Outcome(done.await(15, TimeUnit.SECONDS), results, errors);
    }
}
