package com.mawai.wiibquant.external.hyperliquid;

import com.mawai.wiibquant.whale.WhaleProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * Hyperliquid 公开读接口客户端：四个 info 请求（POST /info，无密钥）+ 排行榜下载（GET，S3 文件）。
 * <p>
 * 限流按 IP 合计 1200 权重/分钟。这里两个权重令牌桶，认证（{@link Bucket#POOL}）与轮询（{@link Bucket#POLL}）各用各的：
 * 容量 = 各自的每分钟额度，按时间线性回血；每次调用带权重与桶名，桶不够就阻塞到够为止。
 * 过了桶再抢在途信号量（≤ max-in-flight），桶满时也只能 8 个一批往外打。
 * 收到 429 两个桶一起冻结 60 秒，本次调用抛 {@link RateLimitedException} 不重试，由调用方按"单次失败"处理。
 * 两条任务共用同一个实例。
 * <p>
 * 测试用 {@link #post} / {@link #get} 换假源，{@link #nowMs} / {@link #sleeper} 换假时钟（EconCalendarCollector 同款注入点）。
 */
@Slf4j
@Component
public class HyperliquidClient {

    /** 哪个桶扣权重：认证任务 POOL，轮询任务 POLL */
    public enum Bucket { POOL, POLL }

    /** 一笔合约仓位。szi 币数量正多负空；liquidationPx 全仓下可能为 null（实测 10 倍全仓 BTC 也会 null） */
    public record Position(String coin, BigDecimal szi, BigDecimal entryPx, BigDecimal positionValue,
                           int leverage, BigDecimal liquidationPx, BigDecimal unrealizedPnl) {
    }

    /** 一个交易账户的合约状态：净值 + 全部仓位 */
    public record AccountState(BigDecimal accountValue, List<Position> positions) {
    }

    /** 子账户：地址 + 内联的合约状态 */
    public record SubAccount(String address, AccountState state) {
    }

    /** 一个币的标记价与全市场持仓量。openInterest 是币数量，名义 = openInterest × markPx */
    public record AssetCtx(String coin, BigDecimal markPx, BigDecimal openInterest) {
    }

    /** 上游回 429：两个桶已冻结 60 秒，本次调用作废 */
    public static final class RateLimitedException extends RuntimeException {
        RateLimitedException() {
            super("Hyperliquid 429");
        }
    }

    static final String INFO_URL = "https://api.hyperliquid.xyz/info";
    /** 各请求权重，官方文档口径 */
    static final int W_CLEARINGHOUSE_STATE = 2;
    static final int W_META_AND_ASSET_CTXS = 20;
    static final int W_SUB_ACCOUNTS = 20;
    static final int W_USER_ROLE = 60;
    static final long FREEZE_MS = 60_000;

    private final WhaleProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final WeightBucket pool;
    private final WeightBucket poll;
    private final Semaphore inFlight;
    /** 429 冻结截止时刻，两个桶共用 */
    private volatile long frozenUntil;

    /** 时钟与睡眠注入点：测试用假时钟测回血与冻结 */
    LongSupplier nowMs = System::currentTimeMillis;
    LongConsumer sleeper = HyperliquidClient::sleep;
    /** info POST 注入点（请求体 JSON → 回包正文）：测试换假源；429 抛 RateLimitedException */
    Function<String, String> post = this::httpPost;
    /** 排行榜 GET 注入点（URL → 正文流） */
    Function<String, InputStream> get = this::httpGet;

    public HyperliquidClient(WhaleProperties props) {
        // 桶额度不能低于单次请求权重
        if (props.getPool().getWeightPerMinute() < W_USER_ROLE || props.getPoll().getWeightPerMinute() < W_META_AND_ASSET_CTXS) {
            throw new IllegalArgumentException("whale.*.weight-per-minute 不能低于单次请求权重（pool ≥ 60，poll ≥ 20）");
        }
        this.props = props;
        this.pool = new WeightBucket(props.getPool().getWeightPerMinute());
        this.poll = new WeightBucket(props.getPoll().getWeightPerMinute());
        this.inFlight = new Semaphore(props.getMaxInFlight());
    }

    // ---------- 四个 info 请求 ----------

    /** 一个地址的合约账户与持仓，权重 2 */
    public AccountState clearinghouseState(String user, Bucket bucket) {
        return parseState(MAPPER.readTree(call(bucket, W_CLEARINGHOUSE_STATE, userRequest("clearinghouseState", user))));
    }

    /** 全部币种的标记价与全市场持仓量，权重 20。含已下架币（OI 为 0），按 universe 下标与 ctx 对齐 */
    public List<AssetCtx> metaAndAssetCtxs(Bucket bucket) {
        ArrayNode root = MAPPER.readValue(call(bucket, W_META_AND_ASSET_CTXS, "{\"type\":\"metaAndAssetCtxs\"}"), ArrayNode.class);
        JsonNode universe = root.get(0).get("universe");
        JsonNode ctxs = root.get(1);
        List<AssetCtx> out = new ArrayList<>(universe.size());
        for (int i = 0; i < universe.size(); i++) {
            JsonNode c = ctxs.get(i);
            out.add(new AssetCtx(universe.get(i).path("name").asString(null),
                    c.path("markPx").asDecimal(null), c.path("openInterest").asDecimal(null)));
        }
        return out;
    }

    /** 一个主地址的全部子账户，内联各自的合约状态，权重 20；无子账户上游回字面量 null，这里回空表 */
    public List<SubAccount> subAccounts(String user, Bucket bucket) {
        String body = call(bucket, W_SUB_ACCOUNTS, userRequest("subAccounts", user));
        if ("null".equals(body.trim())) {
            return List.of();
        }
        ArrayNode arr = MAPPER.readValue(body, ArrayNode.class);
        List<SubAccount> out = new ArrayList<>(arr.size());
        for (JsonNode s : arr) {
            out.add(new SubAccount(s.path("subAccountUser").asString(null), parseState(s.get("clearinghouseState"))));
        }
        return out;
    }

    /** 地址角色 user / agent / vault / subAccount / missing，权重 60 */
    public String userRole(String user, Bucket bucket) {
        return MAPPER.readTree(call(bucket, W_USER_ROLE, userRequest("userRole", user))).path("role").asString(null);
    }

    static AccountState parseState(JsonNode state) {
        BigDecimal accountValue = state.get("marginSummary").path("accountValue").asDecimal(null);
        JsonNode arr = state.get("assetPositions");
        List<Position> positions = new ArrayList<>(arr.size());
        for (JsonNode item : arr) {
            JsonNode p = item.get("position");
            positions.add(new Position(p.path("coin").asString(null), p.path("szi").asDecimal(null),
                    p.path("entryPx").asDecimal(null), p.path("positionValue").asDecimal(null),
                    p.get("leverage").path("value").asInt(0),
                    p.path("liquidationPx").asDecimal(null), p.path("unrealizedPnl").asDecimal(null)));
        }
        return new AccountState(accountValue, List.copyOf(positions));
    }

    private static String userRequest(String type, String user) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("type", type).put("user", user));
    }

    // ---------- 排行榜 ----------

    /**
     * 下载排行榜并流式解析，只留 accountValue ≥ min 的行，只回地址。
     * 走 Jackson JsonParser 逐 token 读；不走令牌桶。
     */
    public List<String> leaderboard(BigDecimal minAccountValue) {
        List<String> out = new ArrayList<>();
        try (InputStream in = get.apply(props.getLeaderboardUrl()); JsonParser p = MAPPER.createParser(in)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                if (!"leaderboardRows".equals(p.currentName())) {
                    p.nextToken();
                    p.skipChildren();
                    continue;
                }
                if (p.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("leaderboardRows 不是数组");
                }
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    String address = null;
                    String valueText = null;
                    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                        String field = p.currentName();
                        p.nextToken();
                        switch (field) {
                            case "ethAddress" -> address = p.getString();
                            case "accountValue" -> valueText = p.getString();
                            default -> p.skipChildren();   // windowPerformances 等整块跳过
                        }
                    }
                    // 坏行只丢这一行
                    try {
                        BigDecimal value = new BigDecimal(valueText);
                        if (address != null && value.compareTo(minAccountValue) >= 0) {
                            out.add(address);
                        }
                    } catch (NumberFormatException | NullPointerException e) {
                        log.warn("[Hyperliquid] 排行榜坏行 address={} accountValue={}", address, valueText);
                    }
                }
                return out;
            }
            throw new IllegalStateException("排行榜里没有 leaderboardRows");
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("排行榜下载/解析失败: " + e.getMessage(), e);
        }
    }

    // ---------- 限流 ----------

    /** 权重令牌桶：容量 = 每分钟额度，按时间线性回血 */
    static final class WeightBucket {
        final int perMinute;
        double tokens;
        long lastRefill;

        WeightBucket(int perMinute) {
            this.perMinute = perMinute;
            this.tokens = perMinute;
        }

        void refill(long now) {
            if (now > lastRefill) {
                tokens = Math.min(perMinute, tokens + (now - lastRefill) * perMinute / 60_000.0);
                lastRefill = now;
            }
        }

        /** 攒够 weight 还要等多久 */
        long msUntil(int weight) {
            return (long) Math.ceil((weight - tokens) * 60_000.0 / perMinute);
        }
    }

    private WeightBucket bucket(Bucket b) {
        return b == Bucket.POOL ? pool : poll;
    }

    /** 扣权重：桶不够或在冻结期就睡到够为止，睡在锁外 */
    private void acquire(WeightBucket b, int weight) {
        while (true) {
            long wait;
            synchronized (b) {
                long now = nowMs.getAsLong();
                b.refill(now);
                long frozen = frozenUntil - now;
                if (frozen <= 0 && b.tokens >= weight) {
                    b.tokens -= weight;
                    return;
                }
                wait = frozen > 0 ? frozen : b.msUntil(weight);
            }
            sleeper.accept(wait);
        }
    }

    private String call(Bucket bucket, int weight, String body) {
        acquire(bucket(bucket), weight);
        inFlight.acquireUninterruptibly();
        try {
            // 过了桶排在信号量后面的请求，赶上别人触发的冻结也别出去
            long frozen = frozenUntil - nowMs.getAsLong();
            if (frozen > 0) {
                sleeper.accept(frozen);
            }
            return post.apply(body);
        } catch (RateLimitedException e) {
            frozenUntil = nowMs.getAsLong() + FREEZE_MS;
            log.warn("[Hyperliquid] 429，两个桶冻结 {}s", FREEZE_MS / 1000);
            throw e;
        } finally {
            inFlight.release();
        }
    }

    /** 当前可用权重（含到此刻的回血），测试看它 */
    double availableWeight(Bucket bucket) {
        WeightBucket b = bucket(bucket);
        synchronized (b) {
            b.refill(nowMs.getAsLong());
            return b.tokens;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待限流额度时被中断", e);
        }
    }

    // ---------- HTTP ----------

    private String httpPost(String body) {
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(INFO_URL))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Hyperliquid 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hyperliquid 请求被中断", e);
        }
        if (resp.statusCode() == 429) {
            throw new RateLimitedException();
        }
        if (resp.statusCode() != 200) {
            String b = resp.body();
            throw new IllegalStateException("Hyperliquid HTTP " + resp.statusCode() + ": "
                    + (b.length() > 200 ? b.substring(0, 200) : b));
        }
        return resp.body();
    }

    private InputStream httpGet(String url) {
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("排行榜下载失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("排行榜下载被中断", e);
        }
        if (resp.statusCode() != 200) {
            try {
                resp.body().close();
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("排行榜 HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
