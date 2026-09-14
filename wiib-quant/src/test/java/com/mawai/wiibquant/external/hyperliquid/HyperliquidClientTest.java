package com.mawai.wiibquant.external.hyperliquid;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AccountState;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AssetCtx;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Bucket;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.SubAccount;
import com.mawai.wiibquant.whale.WhaleProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hyperliquid 客户端：解析用 2026-09-14 抓下来的真实回包（metaAndAssetCtxs 截前 6 个币、排行榜截 4 行，形状原样）；
 * 令牌桶与在途信号量用注入时钟测回血、冻结、限并发。
 */
class HyperliquidClientTest {

    private static final String ADDR = "0xbb34960afec64f3f1cc78b0c9c342c4657021696";

    /** 假时钟：sleeper 直接把时间拨过去，同时记下每次要睡多久 */
    private final AtomicLong clock = new AtomicLong();
    private final List<Long> sleeps = new ArrayList<>();

    private static String fixture(String name) {
        try (InputStream in = HyperliquidClientTest.class.getResourceAsStream("/hyperliquid/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String type(String requestBody) {
        int i = requestBody.indexOf("\"type\":\"") + 8;
        return requestBody.substring(i, requestBody.indexOf('"', i));
    }

    /** 按请求 type 回对应 fixture 的假源 */
    private HyperliquidClient client(int poolPerMinute, int pollPerMinute, int maxInFlight) {
        WhaleProperties props = new WhaleProperties();
        props.getPool().setWeightPerMinute(poolPerMinute);
        props.getPoll().setWeightPerMinute(pollPerMinute);
        props.setMaxInFlight(maxInFlight);
        HyperliquidClient c = new HyperliquidClient(props);
        c.nowMs = clock::get;
        c.sleeper = ms -> {
            sleeps.add(ms);
            clock.addAndGet(ms);
        };
        c.post = body -> fixture(type(body) + ".json");
        c.get = _ -> new ByteArrayInputStream(fixture("leaderboard.json").getBytes(StandardCharsets.UTF_8));
        return c;
    }

    private HyperliquidClient client() {
        return client(500, 300, 8);
    }

    // ---------- 解析 ----------

    @Test
    void clearinghouseState_净值与仓位_强平价可空() {
        AccountState s = client().clearinghouseState(ADDR, Bucket.POLL);

        assertThat(s.accountValue()).isEqualByComparingTo("2145949.909393");
        assertThat(s.positions()).hasSize(3);
        Position eth = s.positions().get(1);
        assertThat(eth.coin()).isEqualTo("ETH");
        assertThat(eth.szi()).isEqualByComparingTo("3600.0");
        assertThat(eth.entryPx()).isEqualByComparingTo("2234.88");
        assertThat(eth.positionValue()).isEqualByComparingTo("9081360.0");
        assertThat(eth.leverage()).isEqualTo(6);
        assertThat(eth.liquidationPx()).isEqualByComparingTo("1478.6972359402");
        assertThat(eth.unrealizedPnl()).isEqualByComparingTo("1035776.38252");
        // 10 倍全仓 BTC 实测也会回 null：进多空统计不进强平分桶
        assertThat(s.positions().getFirst().coin()).isEqualTo("BTC");
        assertThat(s.positions().getFirst().liquidationPx()).isNull();
    }

    @Test
    void metaAndAssetCtxs_两数组按下标对齐_含下架币() {
        List<AssetCtx> ctxs = client().metaAndAssetCtxs(Bucket.POLL);

        assertThat(ctxs).hasSize(6);
        assertThat(ctxs.getFirst().coin()).isEqualTo("BTC");
        assertThat(ctxs.getFirst().markPx()).isEqualByComparingTo("77850.0");
        // openInterest 是币数量：36582 个 BTC，名义 ≈ $2.85B
        assertThat(ctxs.getFirst().openInterest()).isEqualByComparingTo("36582.6904");
        assertThat(ctxs.get(5).coin()).isEqualTo("SOL");
        assertThat(ctxs.get(5).markPx()).isEqualByComparingTo("101.57");
        // 下架币占着下标，OI 为 0
        assertThat(ctxs.get(3).coin()).isEqualTo("MATIC");
        assertThat(ctxs.get(3).openInterest()).isEqualByComparingTo("0");
    }

    @Test
    void subAccounts_内联合约状态() {
        List<SubAccount> subs = client().subAccounts(ADDR, Bucket.POOL);

        assertThat(subs).hasSize(7);
        assertThat(subs.getFirst().address()).isEqualTo("0xd1967879e77c85acab1ea1b6209d29c57e48c063");
        assertThat(subs.getFirst().state().accountValue()).isEqualByComparingTo("0.003935");
        assertThat(subs.getFirst().state().positions()).isEmpty();
    }

    @Test
    void subAccounts_无子账户回null_转空表() {
        HyperliquidClient c = client();
        c.post = _ -> "null";

        assertThat(c.subAccounts(ADDR, Bucket.POOL)).isEmpty();
    }

    @Test
    void userRole() {
        assertThat(client().userRole(ADDR, Bucket.POOL)).isEqualTo("user");
    }

    @Test
    void 排行榜流式解析_只留阈值以上的地址_阈值为零全留() {
        List<String> rows = client().leaderboard(new BigDecimal("1000000"));

        // fixture 4 行：两行百万级、一行带非 ASCII displayName 的 100 美元、一行 98 美元
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).isEqualTo("0x85ecf584f25db6f146718b86d493e33c5af72052");
        assertThat(client().leaderboard(BigDecimal.ZERO)).hasSize(4);
    }

    @Test
    void 排行榜坏行_只丢这一行() {
        HyperliquidClient c = client();
        String feed = """
                {"leaderboardRows":[
                  {"ethAddress":"0xaaa","accountValue":"abc","prize":0},
                  {"ethAddress":"0xbbb","prize":0},
                  {"ethAddress":"0xccc","accountValue":"2000000.5","prize":0}
                ]}""";
        c.get = _ -> new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8));

        assertThat(c.leaderboard(new BigDecimal("1000000"))).containsExactly("0xccc");
    }

    @Test
    void 桶额度低于单次权重_构造直接拒绝() {
        WhaleProperties props = new WhaleProperties();
        props.getPool().setWeightPerMinute(59);

        assertThatThrownBy(() -> new HyperliquidClient(props)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 限流 ----------

    @Test
    void 各请求按权重扣各自的桶() {
        HyperliquidClient c = client();

        c.clearinghouseState(ADDR, Bucket.POLL);
        c.metaAndAssetCtxs(Bucket.POLL);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(300 - 2 - 20);
        assertThat(c.availableWeight(Bucket.POOL)).isEqualTo(500);

        c.subAccounts(ADDR, Bucket.POOL);
        c.userRole(ADDR, Bucket.POOL);
        assertThat(c.availableWeight(Bucket.POOL)).isEqualTo(500 - 20 - 60);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(300 - 2 - 20);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 桶空了睡到攒够_按时间线性回血() {
        // 60/分钟 = 1 权重/秒
        HyperliquidClient c = client(500, 60, 8);
        c.metaAndAssetCtxs(Bucket.POLL);
        c.metaAndAssetCtxs(Bucket.POLL);
        c.metaAndAssetCtxs(Bucket.POLL);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(0);

        // 第 4 个 20 权重要等 20 秒
        c.metaAndAssetCtxs(Bucket.POLL);

        assertThat(sleeps).containsExactly(20_000L);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(0);

        // 30 秒后回血 30，容量封顶 60
        clock.addAndGet(30_000);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(30);
        clock.addAndGet(600_000);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(60);
    }

    @Test
    void 收到429_两个桶一起冻结60秒_本次抛出不重试() {
        HyperliquidClient c = client();
        AtomicInteger calls = new AtomicInteger();
        c.post = body -> {
            if (calls.incrementAndGet() == 1) {
                throw new HyperliquidClient.RateLimitedException();
            }
            return fixture(type(body) + ".json");
        };

        assertThatThrownBy(() -> c.clearinghouseState(ADDR, Bucket.POLL))
                .isInstanceOf(HyperliquidClient.RateLimitedException.class);
        assertThat(calls).hasValue(1);

        // 另一个桶权重充足，也得等冻结过去
        clock.addAndGet(10_000);
        c.userRole(ADDR, Bucket.POOL);

        assertThat(sleeps).containsExactly(50_000L);
        assertThat(calls).hasValue(2);
    }

    @Test
    void 在途请求不超过上限() throws Exception {
        HyperliquidClient c = client(500, 300, 2);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        c.post = body -> {
            int n = inside.incrementAndGet();
            maxInside.accumulateAndGet(n, Math::max);
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
            return fixture(type(body) + ".json");
        };

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofVirtual().start(() -> c.clearinghouseState(ADDR, Bucket.POLL)));
        }
        // 桶里 300 权重够 6 个一起过，但只有 2 个能进假源
        long deadline = System.currentTimeMillis() + 2_000;
        while (inside.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(100);
        assertThat(inside).hasValue(2);

        gate.countDown();
        for (Thread t : threads) {
            t.join(5_000);
        }
        assertThat(maxInside).hasValue(2);
        assertThat(inside).hasValue(0);
        assertThat(c.availableWeight(Bucket.POLL)).isEqualTo(300 - 6 * 2);
    }
}
