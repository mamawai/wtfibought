package com.mawai.wiibquant.external.hyperliquid;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AccountState;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AssetCtx;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Bucket;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.SubAccount;
import com.mawai.wiibquant.whale.WhaleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（docs/hyperliquid-whale.md §11）：对一个真实地址打一遍四个 info 接口 + 下载排行榜。
 * 重点核对 metaAndAssetCtxs：两个数组按下标对齐、openInterest 是币数量、markPx 与持仓里的开仓价/强平价同量级。
 * <p>
 * 地址默认取 WIIB_HL_ADDRESS，没给就用 2026-09-14 抓 fixture 用的那个（持 BTC/ETH/HYPE 三仓，ETH 有强平价）。
 * 打真接口、下载 37MB，默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false \
 *   -Dtest=HyperliquidClientRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class HyperliquidClientRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(HyperliquidClientRealRunTest.class);

    private static String address() {
        String v = System.getenv("WIIB_HL_ADDRESS");
        return v == null || v.isBlank() ? "0xbb34960afec64f3f1cc78b0c9c342c4657021696" : v.trim();
    }

    @Test
    void 四个接口加排行榜各打一遍_核对metaAndAssetCtxs() {
        HyperliquidClient c = new HyperliquidClient(new WhaleProperties());
        String addr = address();

        List<AssetCtx> ctxs = c.metaAndAssetCtxs(Bucket.POLL);
        Map<String, AssetCtx> byCoin = ctxs.stream().collect(Collectors.toMap(AssetCtx::coin, Function.identity(), (a, b) -> a));
        log.info("[RealRun] metaAndAssetCtxs {} 个币", ctxs.size());
        for (String coin : List.of("BTC", "ETH", "SOL", "XRP", "DOGE")) {
            AssetCtx x = byCoin.get(coin);
            assertThat(x).as(coin).isNotNull();
            log.info("[RealRun]   {} markPx={} openInterest={} 名义={}", coin, x.markPx(), x.openInterest(),
                    x.openInterest().multiply(x.markPx()).setScale(0, java.math.RoundingMode.HALF_UP));
        }
        // 下标错位的话 BTC 会拿到别的币的价格：BTC 标记价必须比 ETH 高一个量级以上
        assertThat(byCoin.get("BTC").markPx()).isGreaterThan(byCoin.get("ETH").markPx().multiply(BigDecimal.TEN));

        AccountState state = c.clearinghouseState(addr, Bucket.POLL);
        log.info("[RealRun] clearinghouseState {} 净值={} 持仓 {} 个", addr, state.accountValue(), state.positions().size());
        for (Position p : state.positions()) {
            log.info("[RealRun]   {} szi={} entry={} value={} lev={} liq={} upnl={}", p.coin(), p.szi(), p.entryPx(),
                    p.positionValue(), p.leverage(), p.liquidationPx(), p.unrealizedPnl());
            AssetCtx x = byCoin.get(p.coin());
            if (x == null) {
                continue;
            }
            // 开仓价与标记价同量级（±50% 内）；有强平价的也同量级
            assertThat(ratio(p.entryPx(), x.markPx())).as(p.coin() + " entry/mark").isBetween(0.5, 2.0);
            if (p.liquidationPx() != null) {
                assertThat(ratio(p.liquidationPx(), x.markPx())).as(p.coin() + " liq/mark").isBetween(0.2, 5.0);
            }
            // positionValue = |szi| × 当前价，与 openInterest 一样按币数量计
            assertThat(ratio(p.positionValue(), p.szi().abs().multiply(x.markPx()))).as(p.coin() + " value/(szi×mark)").isBetween(0.9, 1.1);
        }

        String role = c.userRole(addr, Bucket.POOL);
        log.info("[RealRun] userRole {} = {}", addr, role);
        assertThat(role).isIn("user", "agent", "vault", "subAccount", "missing");

        List<SubAccount> subs = c.subAccounts(addr, Bucket.POOL);
        log.info("[RealRun] subAccounts {} 个", subs.size());
        for (SubAccount s : subs) {
            log.info("[RealRun]   {} 净值={} 持仓 {} 个", s.address(), s.state().accountValue(), s.state().positions().size());
        }

        List<String> rows = c.leaderboard(new BigDecimal("1000000"));
        log.info("[RealRun] 排行榜 ≥$1M 的 {} 行", rows.size());
        assertThat(rows.size()).isBetween(1000, 10000);

        log.info("[RealRun] 桶余量 POOL={} POLL={}", c.availableWeight(Bucket.POOL), c.availableWeight(Bucket.POLL));
    }

    private static double ratio(BigDecimal a, BigDecimal b) {
        return a.doubleValue() / b.doubleValue();
    }
}
