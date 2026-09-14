package com.mawai.wiibquant.whale;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hyperliquid 大户持仓配置（application.yml 的 whale 块）。方案见 docs/hyperliquid-whale.md §9。
 * 两条任务的 cron 直接写在 @Scheduled 里读 yml，不进这里。
 */
@Data
@Component
@ConfigurationProperties(prefix = "whale")
public class WhaleProperties {

    /** 关掉：两条任务不跑，接口回空 */
    private boolean enabled = true;
    /** 盯盘币，Hyperliquid 币码（BTC 不是 BTCUSDT） */
    private List<String> coins = List.of("BTC", "ETH", "SOL", "XRP", "DOGE");
    /** 排行榜 JSON 地址（未文档化的 S3 文件，随时可能下线） */
    private String leaderboardUrl = "https://stats-data.hyperliquid.xyz/Mainnet/leaderboard";
    /** 在途请求上限 */
    private int maxInFlight = 8;
    private Pool pool = new Pool();
    private Poll poll = new Poll();

    /** 每日认证（WhalePoolTask） */
    @Data
    public static class Pool {
        /** 认证桶每分钟权重预算；与 poll 合计要留在官方 1200 以下 */
        private int weightPerMinute = 800;
        /** 排行榜候选门槛（美元）：榜上净值 ≥ 它的实体才展开认证，越低候选越多、认证越久 */
        private BigDecimal minLeaderboardValue = new BigDecimal("300000");
        /** 门 1：链上合约净值 ≥ 它，或持有名义 ≥ poll.minPositionValue 的盯盘币仓位，二选一 */
        private BigDecimal minAccountValue = new BigDecimal("1000000");
        /** 门 1：持仓数上限，挡做市商 */
        private int maxPositions = 10;
        /** 池子上限：合格账户按"有盯盘币大仓位优先→净值降序"取前 cap 个 */
        private int cap = 1000;
    }

    /** 每 10 分钟轮询（WhalePositionTask） */
    @Data
    public static class Poll {
        /** 轮询桶每分钟权重预算 */
        private int weightPerMinute = 300;
        /** 仓位层过滤：聚合只算名义 ≥ 它的仓位；门 1 的仓位口径与入池排序同一个数 */
        private BigDecimal minPositionValue = new BigDecimal("100000");
    }
}
