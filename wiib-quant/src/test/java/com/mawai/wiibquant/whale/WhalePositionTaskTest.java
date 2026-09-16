package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.FakeHyperliquid;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient;
import com.mawai.wiibquant.mapper.WhaleAddressMapper;
import com.mawai.wiibquant.mapper.WhalePositionMapper;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮询流程与失败语义：metaAndAssetCtxs 失败不写快照；成功率不足不写快照；变化才写仓位；已平写 szi=0；
 * 失败地址不算已平；启动基线来自表；仓位层过滤只作用于聚合不作用于变化流。
 */
class WhalePositionTaskTest {

    private static final String EMPTY_STATE = "{\"marginSummary\":{\"accountValue\":\"5000000\"},\"assetPositions\":[]}";
    private static final String META = "[{\"universe\":[{\"name\":\"BTC\"},{\"name\":\"SOL\"},{\"name\":\"ETH\"}]},"
            + "[{\"markPx\":\"80000\",\"openInterest\":\"36000\"},{\"markPx\":\"100\",\"openInterest\":\"5000000\"},"
            + "{\"markPx\":\"2500\",\"openInterest\":\"1000000\"}]]";

    private final WhaleAddressMapper addressMapper = mock(WhaleAddressMapper.class);
    private final WhaleSnapshotMapper snapshotMapper = mock(WhaleSnapshotMapper.class);
    private final WhalePositionMapper positionMapper = mock(WhalePositionMapper.class);
    private final WhaleProperties props = new WhaleProperties();

    /** 假上游：地址 → clearinghouseState JSON（"ERROR" 表示这次失败）；meta 为 null 表示 metaAndAssetCtxs 失败 */
    private final Map<String, String> states = new HashMap<>();
    private String meta = META;
    private long now = 1_000_600_123L;

    @BeforeEach
    void coins() {
        props.setCoins(List.of("BTC", "ETH"));
    }

    private WhalePositionTask task() {
        HyperliquidClient c = FakeHyperliquid.client(props, this::route, _ -> {
            throw new UnsupportedOperationException();
        });
        WhalePositionTask t = new WhalePositionTask(c, addressMapper, snapshotMapper, positionMapper, props);
        t.nowMs = () -> now;
        return t;
    }

    private String route(String body) {
        JsonNode o = MAPPER.readTree(body);
        if ("metaAndAssetCtxs".equals(o.path("type").asString(null))) {
            if (meta == null) {
                throw new IllegalStateException("HTTP 500");
            }
            return meta;
        }
        String s = states.getOrDefault(o.path("user").asString(null), EMPTY_STATE);
        if ("ERROR".equals(s)) {
            throw new IllegalStateException("HTTP 500");
        }
        return s;
    }

    /** 账户状态：(币, szi, 开仓价, 名义) 四元组 */
    private static String state(String... quads) {
        StringBuilder ps = new StringBuilder();
        for (int i = 0; i < quads.length; i += 4) {
            if (i > 0) {
                ps.append(',');
            }
            ps.append("{\"type\":\"oneWay\",\"position\":{\"coin\":\"").append(quads[i]).append("\",\"szi\":\"").append(quads[i + 1])
                    .append("\",\"leverage\":{\"type\":\"cross\",\"value\":10},\"entryPx\":\"").append(quads[i + 2])
                    .append("\",\"positionValue\":\"").append(quads[i + 3]).append("\",\"unrealizedPnl\":\"0\",\"liquidationPx\":\"70000\"}}");
        }
        return "{\"marginSummary\":{\"accountValue\":\"5000000\"},\"assetPositions\":[" + ps + "]}";
    }

    private static WhalePositionMapper.Latest latest(String address, String coin, String szi, String entry) {
        WhalePositionMapper.Latest l = new WhalePositionMapper.Latest();
        l.setAddress(address);
        l.setCoin(coin);
        l.setSzi(new BigDecimal(szi));
        l.setEntryPx(entry == null ? null : new BigDecimal(entry));
        return l;
    }

    private List<WhalePositionMapper.Row> positionRows() {
        ArgumentCaptor<WhalePositionMapper.Row> captor = ArgumentCaptor.forClass(WhalePositionMapper.Row.class);
        verify(positionMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private Map<String, WhaleSnapshotMapper.Row> snapshotRows() {
        ArgumentCaptor<WhaleSnapshotMapper.Row> captor = ArgumentCaptor.forClass(WhaleSnapshotMapper.Row.class);
        verify(snapshotMapper, atLeastOnce()).insert(captor.capture());
        Map<String, WhaleSnapshotMapper.Row> out = new HashMap<>();
        captor.getAllValues().forEach(r -> out.put(r.getCoin(), r));
        return out;
    }

    @Test
    void metaAndAssetCtxs失败_本轮什么都不写() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1"));
        states.put("0xa1", state("BTC", "1", "80000", "80000"));
        meta = null;

        task().run();

        verify(snapshotMapper, never()).insert(any());
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void 成功率不足一半_本轮什么都不写() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1", "0xa2", "0xa3"));
        states.put("0xa1", state("BTC", "1", "80000", "80000"));
        states.put("0xa2", "ERROR");
        states.put("0xa3", "ERROR");

        task().run();

        verify(snapshotMapper, never()).insert(any());
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void 池子为空_不写() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of());

        task().run();

        verify(snapshotMapper, never()).insert(any());
        verify(positionMapper, never()).selectLatest();
    }

    @Test
    void 每币一行快照_槽对齐整10分钟_只算配置里的币_没仓位的币也写() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1", "0xa2"));
        states.put("0xa1", state("BTC", "2", "78000", "160000", "SOL", "1000", "100", "100000"));
        states.put("0xa2", state("BTC", "-3", "79000", "240000"));

        task().run();

        Map<String, WhaleSnapshotMapper.Row> rows = snapshotRows();
        assertThat(rows.keySet()).containsExactlyInAnyOrder("BTC", "ETH");
        WhaleSnapshotMapper.Row btc = rows.get("BTC");
        // 1_000_600_123 离 1_000_800_000 更近
        assertThat(btc.getObservedAt()).isEqualTo(1_000_800_000L);
        assertThat(btc.getPoolSize()).isEqualTo(2);
        assertThat(btc.getPrice()).isEqualByComparingTo("80000");
        assertThat(btc.getHlOpenInterest()).isEqualByComparingTo("2880000000");
        assertThat(btc.getLongCount()).isEqualTo(1);
        assertThat(btc.getLongNotional()).isEqualByComparingTo("160000");
        assertThat(btc.getLongWavgEntry()).isEqualByComparingTo("78000");
        assertThat(btc.getShortCount()).isEqualTo(1);
        assertThat(btc.getShortNotional()).isEqualByComparingTo("240000");
        assertThat(btc.getEntryBucketsJson()).isEqualTo("{\"width\":200,\"buckets\":[[78000,160000,0],[79000,0,240000]]}");
        WhaleSnapshotMapper.Row eth = rows.get("ETH");
        assertThat(eth.getLongCount()).isZero();
        assertThat(eth.getShortCount()).isZero();
        assertThat(eth.getLongWavgEntry()).isNull();
        assertThat(eth.getHlOpenInterest()).isEqualByComparingTo("2500000000");
    }

    @Test
    void 配置的币不在metaAndAssetCtxs里_跳过它_其余照写() {
        props.setCoins(List.of("BTC", "NOPE"));
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1"));

        task().run();

        assertThat(snapshotRows().keySet()).containsExactly("BTC");
    }

    @Test
    void 仓位变化才写_已平写szi0_之后不再写() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1"));
        WhalePositionTask t = task();

        // 第 1 轮：新仓位写一行
        states.put("0xa1", state("BTC", "2", "78000", "160000"));
        t.run();
        assertThat(positionRows()).hasSize(1);
        assertThat(positionRows().getFirst().getSzi()).isEqualByComparingTo("2");
        assertThat(positionRows().getFirst().getEntryPx()).isEqualByComparingTo("78000");
        assertThat(positionRows().getFirst().getLiquidationPx()).isEqualByComparingTo("70000");

        // 第 2 轮：没变（名义随价变了不算），不写
        now += 600_000;
        states.put("0xa1", state("BTC", "2.0", "78000.0", "161000"));
        t.run();
        assertThat(positionRows()).hasSize(1);

        // 第 3 轮：加仓，szi 与均价都变，写
        now += 600_000;
        states.put("0xa1", state("BTC", "3", "78500", "240000"));
        t.run();
        assertThat(positionRows()).hasSize(2);
        assertThat(positionRows().get(1).getSzi()).isEqualByComparingTo("3");

        // 第 4 轮：平了，写 szi=0
        now += 600_000;
        states.put("0xa1", EMPTY_STATE);
        t.run();
        assertThat(positionRows()).hasSize(3);
        WhalePositionMapper.Row closed = positionRows().get(2);
        assertThat(closed.getSzi()).isZero();
        assertThat(closed.getCoin()).isEqualTo("BTC");
        assertThat(closed.getEntryPx()).isNull();
        assertThat(closed.getObservedAt()).isEqualTo(1_000_800_000L + 3 * 600_000);

        // 第 5 轮：还是没仓位，不再写
        now += 600_000;
        t.run();
        assertThat(positionRows()).hasSize(3);
        verify(positionMapper, times(1)).selectLatest();
    }

    @Test
    void 启动基线来自表_重启不制造假变化_szi0的行不算基线() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1", "0xa2"));
        when(positionMapper.selectLatest()).thenReturn(List.of(
                latest("0xa1", "BTC", "2", "78000"),
                latest("0xa2", "ETH", "0", null)));
        states.put("0xa1", state("BTC", "2", "78000", "160000"));
        states.put("0xa2", state("ETH", "10", "2400", "24000"));

        task().run();

        // a1 与表里最近一行一样不写；a2 最近一行是已平，本轮有仓位算新开
        List<WhalePositionMapper.Row> rows = positionRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getAddress()).isEqualTo("0xa2");
        assertThat(rows.getFirst().getCoin()).isEqualTo("ETH");
    }

    @Test
    void 失败地址_基线不动_不算已平() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1", "0xa2"));
        when(positionMapper.selectLatest()).thenReturn(List.of(latest("0xa1", "BTC", "2", "78000")));
        states.put("0xa1", "ERROR");
        states.put("0xa2", state("ETH", "10", "2400", "24000"));

        task().run();

        List<WhalePositionMapper.Row> rows = positionRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getAddress()).isEqualTo("0xa2");
        // 快照照写，pool_size 是成功数
        assertThat(snapshotRows().get("BTC").getPoolSize()).isEqualTo(1);
    }

    @Test
    void 仓位层过滤只作用于聚合_小仓位照进变化流() {
        when(addressMapper.selectPoolAddresses()).thenReturn(List.of("0xa1"));
        states.put("0xa1", state("BTC", "0.5", "78000", "40000"));

        task().run();

        assertThat(snapshotRows().get("BTC").getLongCount()).isZero();
        assertThat(positionRows()).hasSize(1);
        assertThat(positionRows().getFirst().getPositionValue()).isEqualByComparingTo("40000");
    }

    @Test
    void enabled关掉_不跑() {
        props.setEnabled(false);

        task().poll();

        verify(addressMapper, never()).selectPoolAddresses();
    }
}
