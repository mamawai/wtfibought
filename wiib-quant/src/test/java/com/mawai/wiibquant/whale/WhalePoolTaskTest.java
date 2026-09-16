package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.FakeHyperliquid;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient;
import com.mawai.wiibquant.mapper.WhaleAddressMapper;
import com.mawai.wiibquant.mapper.WhaleAddressMapper.Known;
import com.mawai.wiibquant.mapper.WhaleAddressMapper.Row;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日认证流程与失败语义：假上游按请求 type/user 回 JSON（走真客户端的解析与令牌桶，假时钟），mapper 用 mock 抓 upsert。
 */
class WhalePoolTaskTest {

    private static final String EMPTY_STATE = "{\"marginSummary\":{\"accountValue\":\"0.0\"},\"assetPositions\":[]}";

    private final WhaleAddressMapper mapper = mock(WhaleAddressMapper.class);
    private final WhaleProperties props = new WhaleProperties();

    /** 假上游：地址 → clearinghouseState JSON；主地址 → subAccounts JSON（缺省 "null"；"ERROR" 表示这次调用失败）；地址 → 角色 */
    private final Map<String, String> states = new HashMap<>();
    private final Map<String, String> subs = new HashMap<>();
    private final Map<String, String> roles = new HashMap<>();
    private String leaderboard = "{\"leaderboardRows\":[]}";
    private boolean leaderboardFails;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** 真客户端 + 假上游 + 假时钟 */
    private HyperliquidClient client() {
        return client(this::route);
    }

    private HyperliquidClient client(Function<String, String> post) {
        return FakeHyperliquid.client(props, post, _ -> {
            if (leaderboardFails) {
                throw new IllegalStateException("HTTP 503");
            }
            return new ByteArrayInputStream(leaderboard.getBytes(StandardCharsets.UTF_8));
        });
    }

    private WhalePoolTask task() {
        WhalePoolTask t = new WhalePoolTask(client(), mapper, props);
        t.nowMs = () -> 1_000L;
        return t;
    }

    private String route(String body) {
        JsonNode o = MAPPER.readTree(body);
        String type = o.path("type").asString(null);
        String user = o.path("user").asString(null);
        requests.add(type + " " + user);
        return switch (type) {
            case "clearinghouseState" -> states.getOrDefault(user, EMPTY_STATE);
            case "subAccounts" -> {
                String s = subs.getOrDefault(user, "null");
                if ("ERROR".equals(s)) {
                    throw new IllegalStateException("HTTP 500");
                }
                yield s;
            }
            case "userRole" -> {
                String r = roles.getOrDefault(user, "user");
                if ("ERROR".equals(r)) {
                    throw new IllegalStateException("HTTP 500");
                }
                yield "{\"role\":\"" + r + "\"}";
            }
            default -> throw new IllegalArgumentException(type);
        };
    }

    /** 账户状态 JSON：净值 + (币, 仓位名义) 对 */
    private static String state(String accountValue, String... coinValuePairs) {
        StringBuilder ps = new StringBuilder();
        for (int i = 0; i < coinValuePairs.length; i += 2) {
            if (i > 0) {
                ps.append(',');
            }
            ps.append("{\"type\":\"oneWay\",\"position\":{\"coin\":\"").append(coinValuePairs[i])
                    .append("\",\"szi\":\"1.0\",\"leverage\":{\"type\":\"cross\",\"value\":10},\"entryPx\":\"1.0\",\"positionValue\":\"")
                    .append(coinValuePairs[i + 1]).append("\",\"unrealizedPnl\":\"0.0\",\"liquidationPx\":null}}");
        }
        return "{\"marginSummary\":{\"accountValue\":\"" + accountValue + "\"},\"assetPositions\":[" + ps + "]}";
    }

    /** subAccounts JSON：子账户地址 → 内联状态 */
    private static String subAccounts(String master, Map<String, String> subStates) {
        return "[" + subStates.entrySet().stream()
                .map(e -> "{\"name\":\"s\",\"subAccountUser\":\"" + e.getKey() + "\",\"master\":\"" + master
                        + "\",\"clearinghouseState\":" + e.getValue() + ",\"spotState\":{}}")
                .collect(Collectors.joining(",")) + "]";
    }

    private static String leaderboard(String... addrValuePairs) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < addrValuePairs.length; i += 2) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"ethAddress\":\"").append(addrValuePairs[i]).append("\",\"accountValue\":\"")
                    .append(addrValuePairs[i + 1]).append("\",\"windowPerformances\":[[\"day\",{\"pnl\":\"0\"}]],\"prize\":0,\"displayName\":null}");
        }
        return "{\"leaderboardRows\":[" + rows + "]}";
    }

    private static Known known(String address, String parent, String role) {
        Known k = new Known();
        k.setAddress(address);
        k.setParentAddress(parent);
        k.setRole(role);
        return k;
    }

    /** 抓全部 upsert，按地址索引 */
    private Map<String, Row> upserts() {
        ArgumentCaptor<Row> captor = ArgumentCaptor.forClass(Row.class);
        verify(mapper, atLeastOnce()).upsert(captor.capture());
        Map<String, Row> out = new LinkedHashMap<>();
        captor.getAllValues().forEach(r -> out.put(r.getAddress(), r));
        return out;
    }

    private long count(String request) {
        return requests.stream().filter(request::equals).count();
    }

    @Test
    void 排行榜出新候选_展开子账户_过门1的才建行_门2只查过门1的主地址() {
        leaderboard = leaderboard("0xm1", "5000000", "0xm2", "2000000");
        // m1 主账户空、钱在子账户 s1；s2 是个几块钱的子账户
        states.put("0xm1", state("0.0"));
        subs.put("0xm1", subAccounts("0xm1", Map.of("0xs1", state("2000000", "BTC", "500000"), "0xs2", state("10.0"))));
        states.put("0xm2", state("3000000", "ETH", "150000", "SOL", "20000"));

        task().refresh();

        Map<String, Row> rows = upserts();
        assertThat(rows.keySet()).containsExactlyInAnyOrder("0xs1", "0xm2");
        // 门 1 拦下的新账户不建行：m1 净值 0、s2 十块钱
        assertThat(rows).doesNotContainKeys("0xm1", "0xs2");
        Row s1 = rows.get("0xs1");
        assertThat(s1.getParentAddress()).isEqualTo("0xm1");
        assertThat(s1.getRole()).isEqualTo("subAccount");
        assertThat(s1.isInPool()).isTrue();
        assertThat(s1.getRejectReason()).isNull();
        assertThat(s1.getAccountValue()).isEqualByComparingTo("2000000");
        assertThat(s1.getPositionCount()).isEqualTo(1);
        assertThat(s1.getTrackedMaxPosition()).isEqualByComparingTo("500000");
        assertThat(s1.getFirstSeenAt()).isEqualTo(1_000L);
        assertThat(s1.getQualifiedAt()).isEqualTo(1_000L);
        Row m2 = rows.get("0xm2");
        assertThat(m2.getParentAddress()).isNull();
        assertThat(m2.getRole()).isEqualTo("user");
        assertThat(m2.isInPool()).isTrue();
        assertThat(m2.getPositionCount()).isEqualTo(2);
        // SOL 不是盯盘币：最大仓位取 ETH 那笔
        assertThat(m2.getTrackedMaxPosition()).isEqualByComparingTo("150000");
        // 门 2 只查过了门 1 的主地址：m1 没过（净值 0），子账户天然 subAccount 不查
        assertThat(count("userRole 0xm2")).isEqualTo(1);
        assertThat(count("userRole 0xm1")).isZero();
        assertThat(count("userRole 0xs1")).isZero();
        // 每个主地址一次展开 + 一次自身状态
        assertThat(count("subAccounts 0xm1")).isEqualTo(1);
        assertThat(count("clearinghouseState 0xm1")).isEqualTo(1);
        assertThat(count("clearinghouseState 0xm2")).isEqualTo(1);
        // 子账户状态内联在展开里，不单独查
        assertThat(count("clearinghouseState 0xs1")).isZero();
    }

    @Test
    void 排行榜下载失败_只停新增_已知账户照常认证() {
        when(mapper.selectKnown()).thenReturn(List.of(known("0xk1", null, "user")));
        leaderboardFails = true;
        states.put("0xk1", state("1500000"));

        task().refresh();

        Map<String, Row> rows = upserts();
        assertThat(rows.keySet()).containsExactly("0xk1");
        assertThat(rows.get("0xk1").isInPool()).isTrue();
        // role 已知不再花 60 权重
        assertThat(count("userRole 0xk1")).isZero();
        assertThat(count("subAccounts 0xk1")).isEqualTo(1);
    }

    @Test
    void 已知子账户_主地址展开失败时补查_展开成功时不补查() {
        when(mapper.selectKnown()).thenReturn(List.of(known("0xs1", "0xm1", "subAccount")));
        states.put("0xs1", state("1200000", "BTC", "300000"));
        subs.put("0xm1", "ERROR");

        task().refresh();

        assertThat(count("subAccounts 0xm1")).isEqualTo(1);
        assertThat(count("clearinghouseState 0xs1")).isEqualTo(1);
        Row s1 = upserts().get("0xs1");
        assertThat(s1.isInPool()).isTrue();
        assertThat(s1.getParentAddress()).isEqualTo("0xm1");

        // 展开成功：状态内联，不再单独查
        requests.clear();
        subs.put("0xm1", subAccounts("0xm1", Map.of("0xs1", state("1200000", "BTC", "300000"))));
        task().refresh();
        assertThat(count("clearinghouseState 0xs1")).isZero();
    }

    @Test
    void vault_不合格落行_下次榜上再出现也不再碰() {
        leaderboard = leaderboard("0xv", "9000000");
        states.put("0xv", state("9000000", "BTC", "5000000"));
        roles.put("0xv", "vault");

        task().refresh();

        Row v = upserts().get("0xv");
        assertThat(v.getRole()).isEqualTo("vault");
        assertThat(v.getRejectReason()).isEqualTo(WhaleQualifier.VAULT);
        assertThat(v.isInPool()).isFalse();
        assertThat(v.getQualifiedAt()).isNull();

        // 第二天：已知 vault，榜上还有它，一个请求都不打
        requests.clear();
        when(mapper.selectKnown()).thenReturn(List.of(known("0xv", null, "vault")));
        task().refresh();
        assertThat(requests).noneMatch(r -> r.endsWith(" 0xv"));
    }

    @Test
    void 已知账户被门1拦下_照样更新行() {
        when(mapper.selectKnown()).thenReturn(List.of(known("0xk1", null, "user")));
        // 缩水且没仓位：SMALL
        states.put("0xk1", state("500000"));

        task().refresh();

        Row k1 = upserts().get("0xk1");
        assertThat(k1.getRejectReason()).isEqualTo(WhaleQualifier.SMALL);
        assertThat(k1.isInPool()).isFalse();
        assertThat(k1.getQualifiedAt()).isNull();
    }

    @Test
    void 排行榜按候选门槛预筛_净值小仓位大的新账户建行() {
        // 默认候选门槛 30 万：low 差一块不展开；lev 净值 5 万、BTC 仓位 200 万照样进池；flat 净值 5 万没仓位不建行
        leaderboard = leaderboard("0xlow", "299999", "0xlev", "300000", "0xflat", "400000");
        states.put("0xlow", state("50000", "BTC", "2000000"));
        states.put("0xlev", state("50000", "BTC", "2000000"));
        states.put("0xflat", state("50000"));

        task().refresh();

        Map<String, Row> rows = upserts();
        assertThat(rows.keySet()).containsExactly("0xlev");
        assertThat(rows.get("0xlev").isInPool()).isTrue();
        assertThat(rows.get("0xlev").getTrackedMaxPosition()).isEqualByComparingTo("2000000");
        assertThat(count("subAccounts 0xlow")).isZero();
        assertThat(count("clearinghouseState 0xlow")).isZero();
    }

    @Test
    void 入池排序与cap_有仓位优先_超额标OVER_CAP() {
        props.getPool().setCap(1);
        leaderboard = leaderboard("0xrich", "50000000", "0xmid", "2000000");
        states.put("0xrich", state("50000000"));
        states.put("0xmid", state("2000000", "BTC", "500000"));

        task().refresh();

        Map<String, Row> rows = upserts();
        assertThat(rows.get("0xmid").isInPool()).isTrue();
        assertThat(rows.get("0xrich").isInPool()).isFalse();
        assertThat(rows.get("0xrich").getRejectReason()).isEqualTo(WhaleQualifier.OVER_CAP);
        assertThat(rows.get("0xrich").getQualifiedAt()).isNull();
    }

    @Test
    void 角色查询失败_本轮不下结论_行不动() {
        leaderboard = leaderboard("0xm1", "5000000", "0xm2", "5000000");
        states.put("0xm1", state("3000000"));
        states.put("0xm2", state("3000000"));
        roles.put("0xm1", "ERROR");

        task().refresh();

        assertThat(upserts().keySet()).containsExactly("0xm2");
    }

    @Test
    void 单地址请求失败_跳过它_其余照常() {
        when(mapper.selectKnown()).thenReturn(List.of(known("0xk1", null, "user")));
        leaderboard = leaderboard("0xm1", "5000000");
        states.put("0xm1", state("3000000"));
        HyperliquidClient failing = client(body -> {
            if (body.contains("0xk1") && body.contains("clearinghouseState")) {
                throw new IllegalStateException("HTTP 500");
            }
            return route(body);
        });

        new WhalePoolTask(failing, mapper, props).refresh();

        assertThat(upserts().keySet()).containsExactly("0xm1");
    }

    @Test
    void 启动时表空才补跑() throws Exception {
        when(mapper.count()).thenReturn(0);

        task().bootstrap();

        verify(mapper, timeout(5_000)).selectKnown();

        WhaleAddressMapper filled = mock(WhaleAddressMapper.class);
        when(filled.count()).thenReturn(12);
        new WhalePoolTask(client(), filled, props).bootstrap();
        Thread.sleep(100);
        verify(filled, never()).selectKnown();

        // 表还没建：查表抛异常只记 warn，不能把 ApplicationReadyEvent 炸掉拖垮启动
        WhaleAddressMapper broken = mock(WhaleAddressMapper.class);
        when(broken.count()).thenThrow(new IllegalStateException("relation whale_address does not exist"));
        new WhalePoolTask(client(), broken, props).bootstrap();
        Thread.sleep(100);
        verify(broken, never()).selectKnown();
    }

    @Test
    void enabled关掉_定时与启动都不跑() {
        props.setEnabled(false);

        WhalePoolTask t = task();
        t.daily();
        t.bootstrap();

        verify(mapper, never()).selectKnown();
        verify(mapper, never()).upsert(any());
    }
}
