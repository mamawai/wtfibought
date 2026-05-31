# 预测评估框架 Implementation Plan（量化重构 Slice 1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在新包 `agent.research` 里建一把"诚实的尺子"——能对任意 6/12/24h 预测器做**样本外**评估，回答它是否（且多大程度上）风险调整后跑赢 buy&hold 和随机基准。

**Architecture:** 纯增量、零侵入：所有代码跑在 `com.mawai.wiibservice.agent.research`，离线/按需触发，绝不接 live `AiTradingScheduler`。数据流：`KlineHistoryStore`(1m 落库可复现) → `KlineAggregator`(聚合 6/12/24h) → 每个决策点用 `Forecaster` 出方向 + `TripleBarrierLabeler` 打标 → `WalkForwardEvaluator`(滚动原点+purge+embargo) 切样本外 → `RiskAdjustedMetrics`(年化 Sharpe/Calmar/MaxDD) + `BenchmarkCalculator`(buy&hold + 随机排列分位) → `EvalReport`。

**Tech Stack:** Java 21、Spring Boot 3.4.1、MyBatis-Plus 3.5.10、PostgreSQL、fastjson2、JUnit 5 + AssertJ。无新外部依赖。

**关键约束（来自 spec §4 / §5.4，铁律）：**
- 不修改任何 live 生产类：`AiTradingScheduler`、`DeterministicTradingExecutor`、`EntryDecisionEngine`、各 strategy/playbook、`VerificationService`、`CryptoIndicatorCalculator`、`BacktestEngine/Runner/TradingTools`。
- `VerificationService`/`CryptoIndicatorCalculator` 的关键算法是 private 且 `VerificationService` 死绑 0_10/10_20/20_30 分钟 → 本刀**复制其纯逻辑到 research 包并广义化**（spec §5.4 授权），不改原类。
- 标的：BTCUSDT、ETHUSDT（合约 K 线）。统计计算用原生 double/BigDecimal，不引依赖。

**全局约定：**
- 跑测命令（根 pom `skipTests=true`，无 mvnw，PowerShell）：
  ```
  cd D:\IdeaProjects\whatifibought
  mvn -pl wiib-service test -Dtest=<ClassName> -DskipTests=false
  ```
- 断言：AssertJ `assertThat`；BigDecimal 用 `isEqualByComparingTo("...")`；浮点用 `isCloseTo(x, within(eps))`（`import static org.assertj.core.api.Assertions.within`）。
- 测试为**纯单测**（无 `@SpringBootTest`），放 `src/test/java` 镜像主包路径。

---

## File Structure（决策已锁定）

主代码（全部新建，`wiib-service/src/main/java/com/mawai/wiibservice/`）：

| 文件 | 职责 |
|---|---|
| `agent/research/ForecastHorizon.java` | 枚举 H6/H12/H24：hours / millis / periodsPerYear / fromHours |
| `agent/research/kline/KlineBar.java` | typed OHLCV bar（record），research 内部统一 bar 表示 |
| `agent/research/kline/KlineAggregator.java` | 纯函数：1m bars → N-小时 bars（按 epoch bucket 对齐） |
| `agent/research/kline/KlineHistoryStore.java` | `@Service` extends ServiceImpl：回填(getFuturesKlines 向前翻页+幂等批插) / 加载 / raw JSON 解析 |
| `agent/research/stats/VolatilityEstimator.java` | 纯函数：对数收益的 EWMA 波动率（栏宽 σ） |
| `agent/research/stats/Ema.java` | 纯函数：EMA（seed=SMA，递归）——基线预测器用 |
| `agent/research/label/BarrierLabel.java` | 枚举 UPPER(+1)/LOWER(-1)/VERTICAL(0) |
| `agent/research/label/TripleBarrierLabeler.java` | 纯函数：三隔栏打标（谁先触），栏宽 = entry·(1±k·σ) |
| `agent/research/metrics/ReturnSeries.java` | record：一次试验的逐期收益序列（DSR/PBO 后置钩子） |
| `agent/research/metrics/RiskAdjustedMetrics.java` | 纯函数：年化 Sharpe / Calmar / MaxDD / CAGR / 命中率 |
| `agent/research/benchmark/BenchmarkCalculator.java` | 纯函数：buy&hold 收益 + 随机排列 Monte-Carlo 分位 |
| `agent/research/eval/WalkForwardWindow.java` | record：单个滚动窗 [train | gap | test] |
| `agent/research/eval/WalkForwardEvaluator.java` | 纯函数：滚动原点切分 + purge + embargo（前向 only） |
| `agent/research/forecast/Forecast.java` | record：方向 + 置信度 |
| `agent/research/forecast/Forecaster.java` | 接口：point-in-time 特征 → Forecast |
| `agent/research/forecast/EwmaMomentumForecaster.java` | 故意简单的基线：EWMA(fast)−EWMA(slow) 符号 |
| `agent/research/eval/EvalParams.java` | record：可配置参数（k/lambda/testSize/embargo/...）+ defaults() |
| `agent/research/eval/EvalReport.java` | record：策略 vs buy&hold vs naive 三线 + toJson/summary |
| `agent/research/eval/ResearchEvalService.java` | `@Service`：load→evaluateBars(静态纯核心)→写 target/ JSON |
| `controller/ResearchEvalController.java` | `@RestController` `/api/research/eval`：backfill + run 端点 |

共享模块 entity + mapper：

| 文件 | 职责 |
|---|---|
| `wiib-common/.../entity/KlineHistory.java` | `@TableName("kline_history")` 实体 |
| `wiib-service/.../mapper/KlineHistoryMapper.java` | `extends BaseMapper<KlineHistory>` + 幂等批插 |
| `sql/init.sql`（**追加**，不改既有） | `CREATE TABLE IF NOT EXISTS kline_history ...` |

测试（`wiib-service/src/test/java/.../agent/research/...` 镜像）：每个纯函数类一个 `XxxTest`。

---

## Task 1: K线落库 + 加载 + 聚合

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/ForecastHorizon.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/kline/KlineBar.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/kline/KlineAggregator.java`
- Create: `wiib-common/src/main/java/com/mawai/wiibcommon/entity/KlineHistory.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/mapper/KlineHistoryMapper.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/kline/KlineHistoryStore.java`
- Modify: `sql/init.sql`（文件末尾追加建表语句）
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/kline/KlineAggregatorTest.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/kline/KlineHistoryStoreParseTest.java`

- [ ] **Step 1: 建基础类型 + 聚合器骨架（先让代码可编译）**

`ForecastHorizon.java`：
```java
package com.mawai.wiibservice.agent.research;

/** 预测周期：本刀只评估 6/12/24h 三档。 */
public enum ForecastHorizon {
    H6(6), H12(12), H24(24);

    private final int hours;

    ForecastHorizon(int hours) {
        this.hours = hours;
    }

    public int hours() {
        return hours;
    }

    public long millis() {
        return hours * 3_600_000L;
    }

    /** 一年有多少个该周期：8760 小时 / H。H6=1460, H12=730, H24=365。 */
    public int periodsPerYear() {
        return 8760 / hours;
    }

    public static ForecastHorizon fromHours(int hours) {
        for (ForecastHorizon h : values()) {
            if (h.hours == hours) return h;
        }
        throw new IllegalArgumentException("不支持的周期(仅 6/12/24): " + hours);
    }
}
```

`KlineBar.java`：
```java
package com.mawai.wiibservice.agent.research.kline;

import java.math.BigDecimal;

/**
 * research 层统一的 typed OHLCV bar。
 * 时间为毫秒 epoch；价格/量为 BigDecimal（与全仓口径一致）。
 */
public record KlineBar(
        long openTime,
        long closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}
```

`KlineAggregator.java`（骨架，先抛异常以制造红）：
```java
package com.mawai.wiibservice.agent.research.kline;

import java.util.List;

/** 把 1 分钟 bars 聚合到任意 N 小时 bars，按 epoch 边界对齐（可复现）。 */
public final class KlineAggregator {

    private KlineAggregator() {
    }

    public static List<KlineBar> aggregate(List<KlineBar> oneMin, long horizonMillis) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

- [ ] **Step 2: 写 `KlineAggregatorTest`（覆盖聚合正确性）**

`KlineAggregatorTest.java`：
```java
package com.mawai.wiibservice.agent.research.kline;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineAggregatorTest {

    // 用 3 分钟(180_000ms)作为聚合周期，保持测试可读；函数对任意 horizonMillis 通用。
    private static final long THREE_MIN = 3 * 60_000L;

    @Test
    void aggregatesByEpochBucketWithCorrectOhlcv() {
        // GIVEN: 5 根 1m bar，前 3 根落在 bucket0 [0,180000)，后 2 根落在 bucket1
        List<KlineBar> oneMin = List.of(
                bar(0,        "100", "105", "99",  "104", "10"),
                bar(60_000,   "104", "108", "103", "107", "12"),
                bar(120_000,  "107", "110", "106", "109", "8"),
                bar(180_000,  "109", "111", "108", "110", "5"),
                bar(240_000,  "110", "112", "109", "111", "7"));

        // WHEN
        List<KlineBar> agg = KlineAggregator.aggregate(oneMin, THREE_MIN);

        // THEN: 2 个聚合 bar，OHLCV 正确，openTime 对齐到 bucket 边界
        assertThat(agg).hasSize(2);

        KlineBar b0 = agg.get(0);
        assertThat(b0.openTime()).isEqualTo(0L);
        assertThat(b0.open()).isEqualByComparingTo("100");   // 首根 open
        assertThat(b0.high()).isEqualByComparingTo("110");   // 三根最高
        assertThat(b0.low()).isEqualByComparingTo("99");     // 三根最低
        assertThat(b0.close()).isEqualByComparingTo("109");  // 末根 close
        assertThat(b0.volume()).isEqualByComparingTo("30");  // 量求和

        KlineBar b1 = agg.get(1);
        assertThat(b1.openTime()).isEqualTo(180_000L);
        assertThat(b1.open()).isEqualByComparingTo("109");
        assertThat(b1.high()).isEqualByComparingTo("112");
        assertThat(b1.low()).isEqualByComparingTo("108");
        assertThat(b1.close()).isEqualByComparingTo("111");
        assertThat(b1.volume()).isEqualByComparingTo("12");
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(KlineAggregator.aggregate(List.of(), THREE_MIN)).isEmpty();
    }

    static KlineBar bar(long openTime, String o, String h, String l, String c, String v) {
        return new KlineBar(openTime, openTime + 59_999L,
                new BigDecimal(o), new BigDecimal(h), new BigDecimal(l),
                new BigDecimal(c), new BigDecimal(v));
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -pl wiib-service test -Dtest=KlineAggregatorTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException: not implemented`）

- [ ] **Step 4: 实现 `KlineAggregator.aggregate`**

```java
package com.mawai.wiibservice.agent.research.kline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 把 1 分钟 bars 聚合到任意 N 小时 bars，按 epoch 边界对齐（可复现）。 */
public final class KlineAggregator {

    private KlineAggregator() {
    }

    /**
     * 入参须按 openTime 升序（store.load 已保证）。按 openTime/horizonMillis 分桶：
     * open=桶内首根 open，high/low=极值，close=末根 close，volume=求和，
     * openTime=对齐到桶左边界(bucket*horizonMillis)。不完整桶照常输出（缺口由上游容忍）。
     */
    public static List<KlineBar> aggregate(List<KlineBar> oneMin, long horizonMillis) {
        if (oneMin == null || oneMin.isEmpty()) return List.of();

        List<KlineBar> out = new ArrayList<>();
        long curBucket = Long.MIN_VALUE;
        long bucketOpenTime = 0, lastCloseTime = 0;
        BigDecimal open = null, high = null, low = null, close = null, vol = BigDecimal.ZERO;

        for (KlineBar b : oneMin) {
            long bucket = Math.floorDiv(b.openTime(), horizonMillis);
            if (bucket != curBucket) {
                if (curBucket != Long.MIN_VALUE) {
                    out.add(new KlineBar(bucketOpenTime, lastCloseTime, open, high, low, close, vol));
                }
                curBucket = bucket;
                bucketOpenTime = bucket * horizonMillis;
                open = b.open();
                high = b.high();
                low = b.low();
                close = b.close();
                vol = b.volume();
                lastCloseTime = b.closeTime();
            } else {
                high = high.max(b.high());
                low = low.min(b.low());
                close = b.close();
                vol = vol.add(b.volume());
                lastCloseTime = b.closeTime();
            }
        }
        if (curBucket != Long.MIN_VALUE) {
            out.add(new KlineBar(bucketOpenTime, lastCloseTime, open, high, low, close, vol));
        }
        return out;
    }
}
```

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=KlineAggregatorTest -DskipTests=false`
Expected: PASS（2 tests）

- [ ] **Step 6: 建表 SQL + 实体 + Mapper + Store + 解析单测**

追加到 `sql/init.sql` 末尾：
```sql
-- ============ kline_history：回测/评估用 1m 基础 K 线落库（research，可复现） ============
CREATE TABLE IF NOT EXISTS kline_history (
    id            BIGSERIAL PRIMARY KEY,
    symbol        VARCHAR(32)   NOT NULL,
    interval_code VARCHAR(8)    NOT NULL,
    open_time     BIGINT        NOT NULL,
    close_time    BIGINT        NOT NULL,
    open          NUMERIC(20,8) NOT NULL,
    high          NUMERIC(20,8) NOT NULL,
    low           NUMERIC(20,8) NOT NULL,
    close         NUMERIC(20,8) NOT NULL,
    volume        NUMERIC(30,8) NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_kline_symbol_interval_opentime UNIQUE (symbol, interval_code, open_time)
);
CREATE INDEX IF NOT EXISTS idx_kline_symbol_time ON kline_history (symbol, interval_code, open_time);
```

`KlineHistory.java`（wiib-common）：
```java
package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 历史 K 线落库实体；created_at 由 DB 默认值填充，实体不映射。 */
@Data
@TableName("kline_history")
public class KlineHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;
    private String intervalCode;
    private Long openTime;
    private Long closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
}
```

`KlineHistoryMapper.java`：
```java
package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.KlineHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KlineHistoryMapper extends BaseMapper<KlineHistory> {

    /** 幂等批插：唯一键冲突直接跳过 → 回填可重复跑、回测可复现。 */
    @Insert("""
            <script>
            INSERT INTO kline_history
              (symbol, interval_code, open_time, close_time, open, high, low, close, volume)
            VALUES
            <foreach collection='list' item='b' separator=','>
              (#{b.symbol}, #{b.intervalCode}, #{b.openTime}, #{b.closeTime},
               #{b.open}, #{b.high}, #{b.low}, #{b.close}, #{b.volume})
            </foreach>
            ON CONFLICT (symbol, interval_code, open_time) DO NOTHING
            </script>
            """)
    int batchInsertIgnore(@Param("list") List<KlineHistory> rows);
}
```

`KlineHistoryStore.java`：
```java
package com.mawai.wiibservice.agent.research.kline;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.KlineHistory;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.KlineHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 1m K 线落库 / 加载。回填走 getFuturesKlines（endTime 向前翻页），幂等批插。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineHistoryStore extends ServiceImpl<KlineHistoryMapper, KlineHistory> {

    private static final String INTERVAL_1M = "1m";
    private static final int PAGE = 1500;          // 合约 klines 单页上限
    private static final int FLUSH = 3000;         // 攒够多少行落一次库

    private final BinanceRestClient binanceRestClient;

    /** 解析 Binance 原始 K 线 JSON → KlineBar（raw 数组: [0]openTime [1]open [2]high [3]low [4]close [5]vol [6]closeTime ...）。 */
    public static List<KlineBar> parseRawFuturesKlines(String json) {
        if (json == null || json.isBlank()) return List.of();
        JSONArray arr = JSON.parseArray(json);
        List<KlineBar> bars = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONArray k = arr.getJSONArray(i);
            bars.add(new KlineBar(
                    k.getLongValue(0),               // openTime
                    k.getLongValue(6),               // closeTime
                    new BigDecimal(k.getString(1)),  // open
                    new BigDecimal(k.getString(2)),  // high
                    new BigDecimal(k.getString(3)),  // low
                    new BigDecimal(k.getString(4)),  // close
                    new BigDecimal(k.getString(5))   // volume
            ));
        }
        return bars;
    }

    /** 回填 [fromMs, toMs) 的 1m K 线。endTime 向前翻页直到越过 fromMs。返回新增/尝试写入行数。 */
    public int backfill(String symbol, long fromMs, long toMs) {
        long cursor = toMs;
        int total = 0;
        List<KlineHistory> buffer = new ArrayList<>(FLUSH);
        while (cursor > fromMs) {
            String json = binanceRestClient.getFuturesKlines(symbol, INTERVAL_1M, PAGE, cursor);
            List<KlineBar> bars = parseRawFuturesKlines(json);
            if (bars.isEmpty()) break;
            long oldest = bars.get(0).openTime();
            for (KlineBar b : bars) {
                if (b.openTime() < fromMs) continue;
                buffer.add(toEntity(symbol, b));
            }
            if (buffer.size() >= FLUSH) {
                total += baseMapper.batchInsertIgnore(buffer);
                buffer.clear();
            }
            if (oldest <= fromMs) break;
            cursor = oldest - 1;   // 下一页：比本页最老 bar 再早 1ms
        }
        if (!buffer.isEmpty()) total += baseMapper.batchInsertIgnore(buffer);
        log.info("backfill {} [{}, {}) 行数={}", symbol, fromMs, toMs, total);
        return total;
    }

    /** 加载 [fromMs, toMs) 的 1m K 线，按 openTime 升序。 */
    public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
        List<KlineHistory> rows = baseMapper.selectList(new LambdaQueryWrapper<KlineHistory>()
                .eq(KlineHistory::getSymbol, symbol)
                .eq(KlineHistory::getIntervalCode, intervalCode)
                .ge(KlineHistory::getOpenTime, fromMs)
                .lt(KlineHistory::getOpenTime, toMs)
                .orderByAsc(KlineHistory::getOpenTime));
        List<KlineBar> bars = new ArrayList<>(rows.size());
        for (KlineHistory r : rows) {
            bars.add(new KlineBar(r.getOpenTime(), r.getCloseTime(),
                    r.getOpen(), r.getHigh(), r.getLow(), r.getClose(), r.getVolume()));
        }
        return bars;
    }

    private static KlineHistory toEntity(String symbol, KlineBar b) {
        KlineHistory e = new KlineHistory();
        e.setSymbol(symbol);
        e.setIntervalCode(INTERVAL_1M);
        e.setOpenTime(b.openTime());
        e.setCloseTime(b.closeTime());
        e.setOpen(b.open());
        e.setHigh(b.high());
        e.setLow(b.low());
        e.setClose(b.close());
        e.setVolume(b.volume());
        return e;
    }
}
```

`KlineHistoryStoreParseTest.java`（纯解析，无 DB/网络）：
```java
package com.mawai.wiibservice.agent.research.kline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineHistoryStoreParseTest {

    @Test
    void parsesRawBinanceKlineArrayKeepingOpen() {
        // Binance 合约 klines 原始格式：[openTime, open, high, low, close, volume, closeTime, ...]
        String json = "[[1700000000000,\"100.0\",\"110.0\",\"95.0\",\"108.0\",\"123.45\",1700000059999,"
                + "\"0\",0,\"0\",\"0\",\"0\"]]";

        List<KlineBar> bars = KlineHistoryStore.parseRawFuturesKlines(json);

        assertThat(bars).hasSize(1);
        KlineBar b = bars.get(0);
        assertThat(b.openTime()).isEqualTo(1700000000000L);
        assertThat(b.closeTime()).isEqualTo(1700000059999L);
        assertThat(b.open()).isEqualByComparingTo("100.0");   // ★ open 被保留（parseKlines 会丢）
        assertThat(b.high()).isEqualByComparingTo("110.0");
        assertThat(b.low()).isEqualByComparingTo("95.0");
        assertThat(b.close()).isEqualByComparingTo("108.0");
        assertThat(b.volume()).isEqualByComparingTo("123.45");
    }

    @Test
    void blankJsonReturnsEmpty() {
        assertThat(KlineHistoryStore.parseRawFuturesKlines("")).isEmpty();
        assertThat(KlineHistoryStore.parseRawFuturesKlines(null)).isEmpty();
    }
}
```

- [ ] **Step 7: 跑解析单测确认绿**

Run: `mvn -pl wiib-service test -Dtest=KlineHistoryStoreParseTest -DskipTests=false`
Expected: PASS（2 tests）
> 注：`backfill`/`load` 是 DB+网络 I/O，不做单测；其正确性在 Task 8 端到端跑回填时验证。建表 SQL 需手动在你的 PostgreSQL 执行（`sql/init.sql` 是手动初始化）。

- [ ] **Step 8: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/ForecastHorizon.java \
        wiib-service/src/main/java/com/mawai/wiibservice/agent/research/kline/ \
        wiib-common/src/main/java/com/mawai/wiibcommon/entity/KlineHistory.java \
        wiib-service/src/main/java/com/mawai/wiibservice/mapper/KlineHistoryMapper.java \
        sql/init.sql \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/kline/
git commit -m "feat(research): kline_history 落库 + 1m→Nh 聚合器（量化重构 slice1 任务1）"
```

---

## Task 2: 三隔栏打标器 + 波动率栏宽

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/stats/VolatilityEstimator.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/label/BarrierLabel.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/label/TripleBarrierLabeler.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/stats/VolatilityEstimatorTest.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/label/TripleBarrierLabelerTest.java`

- [ ] **Step 1: 骨架（VolatilityEstimator + BarrierLabel + TripleBarrierLabeler）**

`BarrierLabel.java`：
```java
package com.mawai.wiibservice.agent.research.label;

/** 三隔栏标签：先触上栏=+1，先触下栏=−1，到期都没触=0。 */
public enum BarrierLabel {
    UPPER(1), LOWER(-1), VERTICAL(0);

    private final int value;

    BarrierLabel(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
```

`VolatilityEstimator.java`（骨架）：
```java
package com.mawai.wiibservice.agent.research.stats;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 对数收益的 EWMA 波动率（每 bar 的分数波动率）。 */
public final class VolatilityEstimator {

    private VolatilityEstimator() {
    }

    public static double ewmaVolatility(List<KlineBar> bars, double lambda) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

`TripleBarrierLabeler.java`（骨架）：
```java
package com.mawai.wiibservice.agent.research.label;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.math.BigDecimal;
import java.util.List;

/** 三隔栏打标：上栏 entry·(1+k·σ)、下栏 entry·(1−k·σ)、竖栏=pathBars 走完。 */
public final class TripleBarrierLabeler {

    private TripleBarrierLabeler() {
    }

    public static BarrierLabel label(BigDecimal entryPrice, double k, double sigma, List<KlineBar> pathBars) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

- [ ] **Step 2: 写 `VolatilityEstimatorTest`**

```java
package com.mawai.wiibservice.agent.research.stats;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VolatilityEstimatorTest {

    @Test
    void constantLogReturnGivesThatReturnAsVol() {
        // 收盘价恒定比率 1.01 → 每期对数收益 = ln(1.01) 恒定 → EWMA 方差 = r² → vol = |r|（与 lambda 无关）
        List<KlineBar> bars = List.of(
                bar("100"), bar("101"), bar("102.01"), bar("103.0301"));

        double vol = VolatilityEstimator.ewmaVolatility(bars, 0.94);

        assertThat(vol).isCloseTo(Math.log(1.01), within(1e-6)); // ≈ 0.00995033
    }

    @Test
    void lessThanTwoBarsGivesZero() {
        assertThat(VolatilityEstimator.ewmaVolatility(List.of(bar("100")), 0.94)).isZero();
        assertThat(VolatilityEstimator.ewmaVolatility(List.of(), 0.94)).isZero();
    }

    static KlineBar bar(String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE);
    }
}
```

- [ ] **Step 3: 写 `TripleBarrierLabelerTest`**

```java
package com.mawai.wiibservice.agent.research.label;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripleBarrierLabelerTest {

    // entry=100000, k=1.5, sigma=0.01 → upper=101500, lower=98500
    private static final BigDecimal ENTRY = new BigDecimal("100000");
    private static final double K = 1.5;
    private static final double SIGMA = 0.01;

    @Test
    void upperTouchedFirstGivesUpper() {
        List<KlineBar> path = List.of(
                bar("101000", "99000", "100000"),   // 都没触
                bar("102000", "100000", "101800"));  // high≥101500 → 上栏
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.UPPER);
    }

    @Test
    void lowerTouchedFirstGivesLower() {
        List<KlineBar> path = List.of(
                bar("101000", "98000", "98200"));    // low≤98500 → 下栏
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.LOWER);
    }

    @Test
    void neitherTouchedGivesVertical() {
        List<KlineBar> path = List.of(
                bar("101000", "99000", "100500"),
                bar("101200", "99500", "100800"));
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.VERTICAL);
    }

    @Test
    void sameBarBothTouchedResolvedByClose() {
        // 同一根同时穿上下栏 → 用 close 相对 entry 决定（close≥entry → 视作先上）
        List<KlineBar> path = List.of(bar("102000", "98000", "100500"));
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.UPPER);
    }

    static KlineBar bar(String high, String low, String close) {
        return new KlineBar(0, 0, new BigDecimal(close),
                new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE);
    }
}
```

- [ ] **Step 4: 跑两个测试确认红**

Run: `mvn -pl wiib-service test -Dtest=VolatilityEstimatorTest,TripleBarrierLabelerTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException`）

- [ ] **Step 5: 实现 `VolatilityEstimator`**

```java
package com.mawai.wiibservice.agent.research.stats;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 对数收益的 EWMA 波动率（每 bar 的分数波动率）。 */
public final class VolatilityEstimator {

    private VolatilityEstimator() {
    }

    /**
     * var_t = lambda·var_{t-1} + (1-lambda)·r_t²，r_t = ln(close_t / close_{t-1})；
     * 首个收益用其平方做种子。返回 sqrt(var)。lambda 典型 0.94（RiskMetrics）。
     */
    public static double ewmaVolatility(List<KlineBar> bars, double lambda) {
        if (bars == null || bars.size() < 2) return 0.0;
        double var = 0.0;
        boolean seeded = false;
        double prevClose = bars.get(0).close().doubleValue();
        for (int i = 1; i < bars.size(); i++) {
            double c = bars.get(i).close().doubleValue();
            double r = Math.log(c / prevClose);
            prevClose = c;
            if (!seeded) {
                var = r * r;
                seeded = true;
            } else {
                var = lambda * var + (1 - lambda) * r * r;
            }
        }
        return Math.sqrt(var);
    }
}
```

- [ ] **Step 6: 实现 `TripleBarrierLabeler`**

```java
package com.mawai.wiibservice.agent.research.label;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.math.BigDecimal;
import java.util.List;

/** 三隔栏打标：上栏 entry·(1+k·σ)、下栏 entry·(1−k·σ)、竖栏=pathBars 走完。 */
public final class TripleBarrierLabeler {

    private TripleBarrierLabeler() {
    }

    /**
     * 顺序扫 pathBars（决策点之后、竖栏之前的路径）：
     * 某根 high≥upper 即上栏先触，low≤lower 即下栏先触；同根都触用 close 相对 entry 裁决；
     * 走完都没触 → VERTICAL。路径越细（如 1m）同根双触越罕见。
     */
    public static BarrierLabel label(BigDecimal entryPrice, double k, double sigma, List<KlineBar> pathBars) {
        if (pathBars == null || pathBars.isEmpty()) return BarrierLabel.VERTICAL;
        BigDecimal upper = entryPrice.multiply(BigDecimal.valueOf(1.0 + k * sigma));
        BigDecimal lower = entryPrice.multiply(BigDecimal.valueOf(1.0 - k * sigma));
        for (KlineBar b : pathBars) {
            boolean upHit = b.high().compareTo(upper) >= 0;
            boolean lowHit = b.low().compareTo(lower) <= 0;
            if (upHit && lowHit) {
                return b.close().compareTo(entryPrice) >= 0 ? BarrierLabel.UPPER : BarrierLabel.LOWER;
            }
            if (upHit) return BarrierLabel.UPPER;
            if (lowHit) return BarrierLabel.LOWER;
        }
        return BarrierLabel.VERTICAL;
    }
}
```

- [ ] **Step 7: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=VolatilityEstimatorTest,TripleBarrierLabelerTest -DskipTests=false`
Expected: PASS（6 tests）

- [ ] **Step 8: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/stats/VolatilityEstimator.java \
        wiib-service/src/main/java/com/mawai/wiibservice/agent/research/label/ \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/stats/VolatilityEstimatorTest.java \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/label/TripleBarrierLabelerTest.java
git commit -m "feat(research): 三隔栏打标器 + EWMA 波动率栏宽（slice1 任务2）"
```

---

## Task 3: 风险调整指标 + 收益序列钩子

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/metrics/ReturnSeries.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/metrics/RiskAdjustedMetrics.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/metrics/RiskAdjustedMetricsTest.java`

- [ ] **Step 1: 建 `ReturnSeries` + `RiskAdjustedMetrics` 骨架**

`ReturnSeries.java`：
```java
package com.mawai.wiibservice.agent.research.metrics;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次试验的逐期收益序列。这是 Deflated Sharpe / PBO 的"现在就埋、计算后置"钩子：
 * 后续 slice 试多组配置时，把每次的 ReturnSeries 收集起来即可算 DSR/PBO。
 */
public record ReturnSeries(
        String label,
        List<BigDecimal> periodReturns,
        int periodsPerYear
) {
}
```

`RiskAdjustedMetrics.java`（骨架）：
```java
package com.mawai.wiibservice.agent.research.metrics;

/** 年化风险调整指标。 */
public record RiskAdjustedMetrics(
        double annualizedSharpe,
        double calmar,
        double maxDrawdown,
        double cagr,
        double hitRate,
        int periods
) {
    public static RiskAdjustedMetrics from(ReturnSeries series) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

- [ ] **Step 2: 写 `RiskAdjustedMetricsTest`**

```java
package com.mawai.wiibservice.agent.research.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RiskAdjustedMetricsTest {

    @Test
    void annualizedSharpeAndHitRate() {
        // returns=[0.03,0.01,0.02,0.02], periodsPerYear=365
        // mean=0.02; 样本方差(n-1)=((0.01)²+(-0.01)²+0+0)/3=6.6667e-5; sd=0.00816497
        // per-period Sharpe=0.02/0.00816497=2.44949; 年化×sqrt(365)=2.44949*19.10497=46.797
        RiskAdjustedMetrics m = RiskAdjustedMetrics.from(series(365, "0.03", "0.01", "0.02", "0.02"));

        assertThat(m.annualizedSharpe()).isCloseTo(46.80, within(0.1));
        assertThat(m.hitRate()).isCloseTo(1.0, within(1e-9));     // 4 期全正
        assertThat(m.maxDrawdown()).isCloseTo(0.0, within(1e-9)); // 单调上行无回撤
        assertThat(m.periods()).isEqualTo(4);
    }

    @Test
    void maxDrawdownAndCalmar() {
        // returns=[0.10,-0.20,0.05], periodsPerYear=365
        // equity: 1.10, 0.88, 0.924；peak=1.10；MaxDD=(1.10-0.88)/1.10=0.2
        // years=3/365；CAGR=0.924^(365/3)-1≈-0.99993；Calmar=CAGR/MaxDD≈-5.0
        RiskAdjustedMetrics m = RiskAdjustedMetrics.from(series(365, "0.10", "-0.20", "0.05"));

        assertThat(m.maxDrawdown()).isCloseTo(0.20, within(1e-9));
        assertThat(m.calmar()).isCloseTo(-5.0, within(0.01));
        assertThat(m.hitRate()).isCloseTo(2.0 / 3.0, within(1e-6)); // 2/3 正
    }

    @Test
    void zeroStdGivesZeroSharpe() {
        // 常数收益 → sd=0 → Sharpe=0（与现有 BacktestTradingTools 口径一致）
        RiskAdjustedMetrics m = RiskAdjustedMetrics.from(series(365, "0.02", "0.02", "0.02"));
        assertThat(m.annualizedSharpe()).isZero();
    }

    static ReturnSeries series(int ppy, String... rs) {
        List<BigDecimal> list = Stream.of(rs).map(BigDecimal::new).toList();
        return new ReturnSeries("test", list, ppy);
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -pl wiib-service test -Dtest=RiskAdjustedMetricsTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException`）

- [ ] **Step 4: 实现 `RiskAdjustedMetrics.from`**

```java
package com.mawai.wiibservice.agent.research.metrics;

import java.math.BigDecimal;
import java.util.List;

/** 年化风险调整指标。 */
public record RiskAdjustedMetrics(
        double annualizedSharpe,
        double calmar,
        double maxDrawdown,
        double cagr,
        double hitRate,
        int periods
) {
    /**
     * 年化 Sharpe = (mean/sd)·sqrt(periodsPerYear)，sd 用样本方差(n-1)、rf=0（与现有口径一致）；
     * MaxDD 由复利净值峰谷；CAGR = equity^(periodsPerYear/n)-1；Calmar = CAGR/MaxDD；命中率=正收益占比。
     */
    public static RiskAdjustedMetrics from(ReturnSeries series) {
        List<BigDecimal> rs = series.periodReturns();
        int n = rs.size();
        if (n == 0) return new RiskAdjustedMetrics(0, 0, 0, 0, 0, 0);

        double[] r = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            r[i] = rs.get(i).doubleValue();
            mean += r[i];
        }
        mean /= n;

        double var = 0;
        for (double x : r) var += (x - mean) * (x - mean);
        var = n > 1 ? var / (n - 1) : 0;
        double sd = Math.sqrt(var);
        double sharpe = sd == 0 ? 0 : (mean / sd) * Math.sqrt(series.periodsPerYear());

        double equity = 1.0, peak = 1.0, maxDD = 0.0;
        int wins = 0;
        for (double x : r) {
            equity *= (1 + x);
            if (equity > peak) peak = equity;
            double dd = peak == 0 ? 0 : (peak - equity) / peak;
            if (dd > maxDD) maxDD = dd;
            if (x > 0) wins++;
        }

        double years = (double) n / series.periodsPerYear();
        double cagr = years > 0 ? Math.pow(equity, 1.0 / years) - 1.0 : 0;
        double calmar = maxDD == 0 ? 0 : cagr / maxDD;
        double hitRate = (double) wins / n;

        return new RiskAdjustedMetrics(sharpe, calmar, maxDD, cagr, hitRate, n);
    }
}
```

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=RiskAdjustedMetricsTest -DskipTests=false`
Expected: PASS（3 tests）

- [ ] **Step 6: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/metrics/ \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/metrics/
git commit -m "feat(research): 年化风险调整指标 + 收益序列钩子（slice1 任务3）"
```

---

## Task 4: 基准模块（buy&hold + 随机排列分位）

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/benchmark/BenchmarkCalculator.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/benchmark/BenchmarkCalculatorTest.java`

- [ ] **Step 1: 骨架**

```java
package com.mawai.wiibservice.agent.research.benchmark;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.math.BigDecimal;
import java.util.List;

/** 双基准：buy&hold 净收益 + 随机入场（排列检验）分位。 */
public final class BenchmarkCalculator {

    private BenchmarkCalculator() {
    }

    public static BigDecimal buyAndHoldReturn(List<KlineBar> bars) {
        throw new UnsupportedOperationException("not implemented");
    }

    public static double permutationPercentile(List<Integer> positions, List<BigDecimal> periodReturns,
                                               int iterations, long seed) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

- [ ] **Step 2: 写 `BenchmarkCalculatorTest`**

```java
package com.mawai.wiibservice.agent.research.benchmark;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BenchmarkCalculatorTest {

    @Test
    void buyAndHoldIsLastOverFirstCloseMinusOne() {
        List<KlineBar> bars = List.of(barClose("100"), barClose("105"), barClose("110"));
        assertThat(BenchmarkCalculator.buyAndHoldReturn(bars)).isEqualByComparingTo("0.1"); // 110/100-1
    }

    @Test
    void buyAndHoldTooFewBarsIsZero() {
        assertThat(BenchmarkCalculator.buyAndHoldReturn(List.of(barClose("100")))).isEqualByComparingTo("0");
    }

    @Test
    void permutationPercentileMatchesTheoreticalFraction() {
        // positions=[1,0,0], returns=[0.05,-0.01,-0.01] → realReturn=0.05（最大）
        // 排列把那个"1"等概率落到 3 个槽：仅落槽0时=0.05（不<real），其余 2/3 落到 -0.01(<real)
        // → 分位 ≈ 2/3 = 0.667
        double pct = BenchmarkCalculator.permutationPercentile(
                List.of(1, 0, 0),
                List.of(new BigDecimal("0.05"), new BigDecimal("-0.01"), new BigDecimal("-0.01")),
                20000, 42L);
        assertThat(pct).isCloseTo(0.667, within(0.03));
    }

    static KlineBar barClose(String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE);
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -pl wiib-service test -Dtest=BenchmarkCalculatorTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException`）

- [ ] **Step 4: 实现 `BenchmarkCalculator`**

```java
package com.mawai.wiibservice.agent.research.benchmark;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;

/** 双基准：buy&hold 净收益 + 随机入场（排列检验）分位。 */
public final class BenchmarkCalculator {

    private BenchmarkCalculator() {
    }

    /** buy&hold = 末根 close / 首根 close − 1。 */
    public static BigDecimal buyAndHoldReturn(List<KlineBar> bars) {
        if (bars == null || bars.size() < 2) return BigDecimal.ZERO;
        BigDecimal first = bars.get(0).close();
        BigDecimal last = bars.get(bars.size() - 1).close();
        if (first.signum() == 0) return BigDecimal.ZERO;
        return last.subtract(first).divide(first, 10, RoundingMode.HALF_UP);
    }

    /**
     * Aronson 排列检验 [S11]：固定真实仓位序列 → realReturn = Σ pos_i·ret_i；
     * 再把仓位序列洗牌 iterations 次（保留下注次数/方向分布，打乱时机），
     * 统计有多少次随机收益 < realReturn → 即真实策略所处分位（同频同持仓的"瞎猜"分布）。
     * seed 固定 → 结果可复现（spec §6.2）。
     */
    public static double permutationPercentile(List<Integer> positions, List<BigDecimal> periodReturns,
                                               int iterations, long seed) {
        int n = positions.size();
        if (n == 0 || iterations <= 0) return 0.0;
        double[] rr = new double[n];
        int[] pos = new int[n];
        double real = 0;
        for (int i = 0; i < n; i++) {
            rr[i] = periodReturns.get(i).doubleValue();
            pos[i] = positions.get(i);
            real += pos[i] * rr[i];
        }
        Random rnd = new Random(seed);
        int lessCount = 0;
        for (int it = 0; it < iterations; it++) {
            int[] p = pos.clone();
            for (int i = n - 1; i > 0; i--) {        // Fisher-Yates 洗牌
                int j = rnd.nextInt(i + 1);
                int t = p[i];
                p[i] = p[j];
                p[j] = t;
            }
            double ret = 0;
            for (int i = 0; i < n; i++) ret += p[i] * rr[i];
            if (ret < real) lessCount++;
        }
        return (double) lessCount / iterations;
    }
}
```

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=BenchmarkCalculatorTest -DskipTests=false`
Expected: PASS（3 tests）

- [ ] **Step 6: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/benchmark/ \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/benchmark/
git commit -m "feat(research): buy&hold + 随机排列分位双基准（slice1 任务4）"
```

---

## Task 5: Walk-forward 评估器（滚动原点 + purge + embargo）

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/WalkForwardWindow.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/WalkForwardEvaluator.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/eval/WalkForwardEvaluatorTest.java`

- [ ] **Step 1: 骨架**

`WalkForwardWindow.java`：
```java
package com.mawai.wiibservice.agent.research.eval;

/** 单个滚动窗：train=[trainStart,trainEnd)，test=[testStart,testEnd)，二者间有 purge+embargo 间隔。 */
public record WalkForwardWindow(int trainStart, int trainEnd, int testStart, int testEnd) {

    public int trainSize() {
        return trainEnd - trainStart;
    }

    public int testSize() {
        return testEnd - testStart;
    }
}
```

`WalkForwardEvaluator.java`（骨架）：
```java
package com.mawai.wiibservice.agent.research.eval;

import java.util.List;

/** 前向 walk-forward 切分：每个 test 块前留 (horizonBars purge + embargo) 间隔，杜绝标签泄漏。 */
public final class WalkForwardEvaluator {

    private WalkForwardEvaluator() {
    }

    public static List<WalkForwardWindow> windows(int total, int testSize, int horizonBars,
                                                  int embargo, int minTrain) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

- [ ] **Step 2: 写 `WalkForwardEvaluatorTest`（证明无标签泄漏）**

```java
package com.mawai.wiibservice.agent.research.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalkForwardEvaluatorTest {

    @Test
    void windowsHaveNoLabelLeakageAndRespectEmbargo() {
        int total = 1000, testSize = 100, horizonBars = 12, embargo = 12, minTrain = 200;

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(total, testSize, horizonBars, embargo, minTrain);

        assertThat(wins).isNotEmpty();
        int prevTestEnd = -1;
        for (WalkForwardWindow w : wins) {
            // 1) 无标签泄漏：最后一个训练样本的标签窗(trainEnd-1 + horizonBars)不得触及 testStart
            assertThat(w.trainEnd() + horizonBars).isLessThanOrEqualTo(w.testStart());
            // 2) embargo 生效：train↔test 间隔严格 = horizonBars + embargo
            assertThat(w.testStart() - w.trainEnd()).isEqualTo(horizonBars + embargo);
            // 3) train 不少于下限、test 在界内、test 块不重叠且前进
            assertThat(w.trainSize()).isGreaterThanOrEqualTo(minTrain);
            assertThat(w.testStart()).isGreaterThan(prevTestEnd);
            assertThat(w.testEnd()).isLessThanOrEqualTo(total);
            assertThat(w.testSize()).isEqualTo(testSize);
            prevTestEnd = w.testEnd();
        }
    }

    @Test
    void tooSmallTotalGivesNoWindows() {
        // total 不足以容纳 minTrain+gap+testSize
        assertThat(WalkForwardEvaluator.windows(100, 50, 12, 12, 200)).isEmpty();
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -pl wiib-service test -Dtest=WalkForwardEvaluatorTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException`）

- [ ] **Step 4: 实现 `WalkForwardEvaluator.windows`**

```java
package com.mawai.wiibservice.agent.research.eval;

import java.util.ArrayList;
import java.util.List;

/** 前向 walk-forward 切分：每个 test 块前留 (horizonBars purge + embargo) 间隔，杜绝标签泄漏。 */
public final class WalkForwardEvaluator {

    private WalkForwardEvaluator() {
    }

    /**
     * total          决策点总数
     * testSize       每个样本外 test 块的大小（决策点数）
     * horizonBars    标签前视长度（purge）：三隔栏标签向前看 horizonBars 个 bar，
     *                不留此间隔则末段训练标签会与 test 重叠 → 泄漏
     * embargo        额外安全带（spec 默认≈1 个 horizon）
     * minTrain       训练集最小规模，不够则跳过该窗
     *
     * train 取 [0, testStart - horizonBars - embargo)（扩张窗），test 块连续前进、互不重叠。
     * 注：本刀前向 only（train 永在 test 之前），完整 CPCV/post-test embargo 推迟到后续 slice（spec §7）。
     */
    public static List<WalkForwardWindow> windows(int total, int testSize, int horizonBars,
                                                  int embargo, int minTrain) {
        List<WalkForwardWindow> out = new ArrayList<>();
        if (testSize <= 0 || total <= 0) return out;
        int gap = horizonBars + embargo;
        for (int testStart = minTrain + gap; testStart + testSize <= total; testStart += testSize) {
            int trainEnd = testStart - gap;
            if (trainEnd < minTrain) continue;
            out.add(new WalkForwardWindow(0, trainEnd, testStart, testStart + testSize));
        }
        return out;
    }
}
```

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=WalkForwardEvaluatorTest -DskipTests=false`
Expected: PASS（2 tests）

- [ ] **Step 6: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/WalkForwardWindow.java \
        wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/WalkForwardEvaluator.java \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/eval/WalkForwardEvaluatorTest.java
git commit -m "feat(research): walk-forward 滚动原点 + purge + embargo（slice1 任务5）"
```

---

## Task 6: 预测器接口 + EWMA 动量基线

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/stats/Ema.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/forecast/Forecast.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/forecast/Forecaster.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/forecast/EwmaMomentumForecaster.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/stats/EmaTest.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/forecast/EwmaMomentumForecasterTest.java`

- [ ] **Step 1: 骨架（Ema + Forecast + Forecaster + EwmaMomentumForecaster）**

`Ema.java`（骨架）：
```java
package com.mawai.wiibservice.agent.research.stats;

import java.math.BigDecimal;
import java.util.List;

/** 指数移动平均：seed=首 period 的 SMA，之后递归。复制自现有口径（CryptoIndicatorCalculator.ema），不改原类。 */
public final class Ema {

    private Ema() {
    }

    public static BigDecimal ema(List<BigDecimal> values, int period) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

`Forecast.java`：
```java
package com.mawai.wiibservice.agent.research.forecast;

/** 预测结果：direction ∈ {+1 多, 0 空仓, −1 空}，confidence ∈ [0,1]。 */
public record Forecast(int direction, double confidence) {

    public static Forecast flat() {
        return new Forecast(0, 0.0);
    }
}
```

`Forecaster.java`：
```java
package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 可插拔预测器。入参是决策点"当下及以前"的 bars（point-in-time，绝不含未来）。 */
public interface Forecaster {

    Forecast forecast(List<KlineBar> historyUpToNow);

    String name();
}
```

`EwmaMomentumForecaster.java`（骨架）：
```java
package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 故意简单的基线：EWMA(fast)−EWMA(slow) 的符号当方向。存在只为立标尺，不为赢。 */
public final class EwmaMomentumForecaster implements Forecaster {

    private final int fast;
    private final int slow;

    public EwmaMomentumForecaster(int fast, int slow) {
        this.fast = fast;
        this.slow = slow;
    }

    @Override
    public Forecast forecast(List<KlineBar> historyUpToNow) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public String name() {
        return "ewma_momentum_" + fast + "_" + slow;
    }
}
```

- [ ] **Step 2: 写 `EmaTest`**

```java
package com.mawai.wiibservice.agent.research.stats;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EmaTest {

    @Test
    void seedIsSmaWhenNoExtraValues() {
        // period=3, values=[2,4,6] → seed=SMA=4，无后续 → 4
        assertThat(Ema.ema(bd("2", "4", "6"), 3)).isEqualByComparingTo("4");
    }

    @Test
    void recursesWithSmoothingFactor() {
        // period=3 → k=2/(3+1)=0.5；seed=4；i=3: ema=4+0.5*(8-4)=6
        assertThat(Ema.ema(bd("2", "4", "6", "8"), 3)).isEqualByComparingTo("6");
    }

    @Test
    void insufficientDataGivesNull() {
        assertThat(Ema.ema(bd("1", "2"), 3)).isNull();
    }

    static List<BigDecimal> bd(String... v) {
        return Stream.of(v).map(BigDecimal::new).toList();
    }
}
```

- [ ] **Step 3: 写 `EwmaMomentumForecasterTest`**

```java
package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EwmaMomentumForecasterTest {

    private final EwmaMomentumForecaster fc = new EwmaMomentumForecaster(3, 9);

    @Test
    void risingTrendGivesLong() {
        Forecast f = fc.forecast(closes(100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110));
        assertThat(f.direction()).isEqualTo(1);
    }

    @Test
    void fallingTrendGivesShort() {
        Forecast f = fc.forecast(closes(110, 109, 108, 107, 106, 105, 104, 103, 102, 101, 100));
        assertThat(f.direction()).isEqualTo(-1);
    }

    @Test
    void insufficientHistoryGivesFlat() {
        Forecast f = fc.forecast(closes(100, 101, 102)); // < slow(9)
        assertThat(f.direction()).isZero();
        assertThat(f.confidence()).isZero();
    }

    static List<KlineBar> closes(double... cs) {
        List<KlineBar> bars = new ArrayList<>();
        for (double c : cs) {
            BigDecimal v = BigDecimal.valueOf(c);
            bars.add(new KlineBar(0, 0, v, v, v, v, BigDecimal.ONE));
        }
        return bars;
    }
}
```

- [ ] **Step 4: 跑测试确认红**

Run: `mvn -pl wiib-service test -Dtest=EmaTest,EwmaMomentumForecasterTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException`）

- [ ] **Step 5: 实现 `Ema`**

```java
package com.mawai.wiibservice.agent.research.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 指数移动平均：seed=首 period 的 SMA，之后递归。复制自现有口径（CryptoIndicatorCalculator.ema），不改原类。 */
public final class Ema {

    private Ema() {
    }

    /** 样本不足 period 返回 null。k=2/(period+1)，ema_t = ema_{t-1} + k·(price_t − ema_{t-1})。 */
    public static BigDecimal ema(List<BigDecimal> values, int period) {
        if (values == null || period <= 0 || values.size() < period) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(values.get(i));
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 12, RoundingMode.HALF_UP);
        BigDecimal k = BigDecimal.valueOf(2.0 / (period + 1));
        for (int i = period; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(k).add(ema);
        }
        return ema;
    }
}
```

- [ ] **Step 6: 实现 `EwmaMomentumForecaster.forecast`**

```java
package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.Ema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 故意简单的基线：EWMA(fast)−EWMA(slow) 的符号当方向。存在只为立标尺，不为赢。 */
public final class EwmaMomentumForecaster implements Forecaster {

    private final int fast;
    private final int slow;

    public EwmaMomentumForecaster(int fast, int slow) {
        this.fast = fast;
        this.slow = slow;
    }

    @Override
    public Forecast forecast(List<KlineBar> historyUpToNow) {
        if (historyUpToNow == null || historyUpToNow.size() < slow) return Forecast.flat();
        List<BigDecimal> closes = historyUpToNow.stream().map(KlineBar::close).toList();
        BigDecimal ef = Ema.ema(closes, fast);
        BigDecimal es = Ema.ema(closes, slow);
        if (ef == null || es == null || es.signum() == 0) return Forecast.flat();
        BigDecimal spread = ef.subtract(es).divide(es, 8, RoundingMode.HALF_UP);
        int dir = spread.signum();
        double conf = Math.min(1.0, Math.abs(spread.doubleValue()) * 100); // 简单缩放，仅诊断用
        return new Forecast(dir, conf);
    }

    @Override
    public String name() {
        return "ewma_momentum_" + fast + "_" + slow;
    }
}
```

- [ ] **Step 7: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=EmaTest,EwmaMomentumForecasterTest -DskipTests=false`
Expected: PASS（6 tests）

- [ ] **Step 8: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/stats/Ema.java \
        wiib-service/src/main/java/com/mawai/wiibservice/agent/research/forecast/ \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/stats/EmaTest.java \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/forecast/
git commit -m "feat(research): Forecaster 接口 + EWMA 动量基线（slice1 任务6）"
```

---

## Task 7: 评估编排 + 报告 + 触发入口

**Files:**
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/EvalParams.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/EvalReport.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/ResearchEvalService.java`
- Create: `wiib-service/src/main/java/com/mawai/wiibservice/controller/ResearchEvalController.java`
- Test: `wiib-service/src/test/java/com/mawai/wiibservice/agent/research/eval/ResearchEvalServiceTest.java`

- [ ] **Step 1: 建 `EvalParams` + `EvalReport`（数据类型，无逻辑）**

`EvalParams.java`：
```java
package com.mawai.wiibservice.agent.research.eval;

/**
 * 评估可配置参数（spec §5.3 默认值，评审可改）。
 * embargoBars 单位=决策点（H-bar）；本刀决策周期=horizon，故标签前视=1 个 H-bar。
 */
public record EvalParams(
        double k,                          // 三隔栏栏宽倍数（不网格寻优）
        double lambda,                     // EWMA 波动率衰减
        int testSize,                      // 每个样本外块大小（决策点数）
        int embargoBars,                   // embargo（决策点数）
        int minTrain,                      // 最小训练规模
        int iterations,                    // 随机排列次数
        double naivePercentileThreshold,   // 超过此分位才算赢过瞎猜
        long seed                          // 随机种子（可复现）
) {
    public static EvalParams defaults() {
        return new EvalParams(1.5, 0.94, 50, 1, 100, 1000, 0.95, 42L);
    }
}
```

`EvalReport.java`：
```java
package com.mawai.wiibservice.agent.research.eval;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;

import java.math.BigDecimal;

/** 样本外评估报告：策略 vs buy&hold vs naive 三线可比（spec §6 验收）。 */
public record EvalReport(
        String symbol,
        int horizonHours,
        int totalHbars,
        int testPoints,
        RiskAdjustedMetrics metrics,
        BigDecimal buyAndHoldReturn,
        BigDecimal strategyReturn,
        double naivePercentile,
        boolean beatBuyAndHold,
        boolean beatNaive,
        ReturnSeries returnSeries
) {
    public String toJson() {
        return JSON.toJSONString(this);
    }

    /** 人眼可比的三线摘要。 */
    public String summary() {
        return String.format(
                "[%s %dh] 样本外 %d 点 | 策略: 收益=%.4f 年化Sharpe=%.2f Calmar=%.2f MaxDD=%.2f%% 命中=%.1f%% "
                        + "| buy&hold=%.4f(%s) | naive分位=%.1f%%(%s)",
                symbol, horizonHours, testPoints,
                strategyReturn.doubleValue(), metrics.annualizedSharpe(), metrics.calmar(),
                metrics.maxDrawdown() * 100, metrics.hitRate() * 100,
                buyAndHoldReturn.doubleValue(), beatBuyAndHold ? "跑赢" : "跑输",
                naivePercentile * 100, beatNaive ? "显著" : "不显著");
    }
}
```

- [ ] **Step 2: 建 `ResearchEvalService` 骨架（含静态纯核心 evaluateBars）**

```java
package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 评估编排：从库加载 1m → 调纯核心 evaluateBars → 写 target/ JSON。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchEvalService {

    private final KlineHistoryStore store;

    public EvalReport evaluate(String symbol, ForecastHorizon horizon, long fromMs, long toMs,
                               Forecaster forecaster, EvalParams params) {
        List<KlineBar> oneMin = store.load(symbol, "1m", fromMs, toMs);
        EvalReport report = evaluateBars(symbol, horizon, oneMin, forecaster, params);
        writeReport(report);
        return report;
    }

    /** 纯核心（无 DB，可单测）：聚合→逐决策点预测+模拟收益→walk-forward 取样本外→指标+双基准→报告。 */
    public static EvalReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                          Forecaster forecaster, EvalParams params) {
        throw new UnsupportedOperationException("not implemented");
    }

    private void writeReport(EvalReport report) {
        throw new UnsupportedOperationException("not implemented");
    }
}
```

- [ ] **Step 3: 写 `ResearchEvalServiceTest`（纯核心，无 DB/网络）**

```java
package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchEvalServiceTest {

    @Test
    void evaluateBarsProducesOutOfSampleReportOnUptrend() {
        // 小参数（testSize=3,minTrain=2,embargo=0,iters=200）→ 只需约 7 个 H6 bar = 7*360 个 1m bar
        EvalParams params = new EvalParams(1.5, 0.94, 3, 0, 2, 200, 0.95, 42L);
        List<KlineBar> oneMin = syntheticUptrend1m(8 * 360); // 8 个 6h 桶，含余量

        EvalReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, new EwmaMomentumForecaster(2, 4), params);

        assertThat(r).isNotNull();
        assertThat(r.symbol()).isEqualTo("BTCUSDT");
        assertThat(r.horizonHours()).isEqualTo(6);
        assertThat(r.testPoints()).isGreaterThan(0);
        assertThat(r.metrics().periods()).isEqualTo(r.testPoints());
        assertThat(r.buyAndHoldReturn().doubleValue()).isGreaterThan(0); // 上行趋势 buy&hold 为正
        assertThat(r.naivePercentile()).isBetween(0.0, 1.0);
        assertThat(r.summary()).contains("BTCUSDT");
    }

    /** 生成 count 根 1m bar，价格每根 +0.1，时间从 0 起每根 +60_000ms。 */
    static List<KlineBar> syntheticUptrend1m(int count) {
        List<KlineBar> bars = new ArrayList<>(count);
        double price = 100.0;
        for (int i = 0; i < count; i++) {
            long t = i * 60_000L;
            BigDecimal o = BigDecimal.valueOf(price);
            BigDecimal c = BigDecimal.valueOf(price + 0.1);
            BigDecimal hi = BigDecimal.valueOf(price + 0.15);
            BigDecimal lo = BigDecimal.valueOf(price - 0.05);
            bars.add(new KlineBar(t, t + 59_999L, o, hi, lo, c, BigDecimal.ONE));
            price += 0.1;
        }
        return bars;
    }
}
```

- [ ] **Step 4: 跑测试确认红**

Run: `mvn -pl wiib-service test -Dtest=ResearchEvalServiceTest -DskipTests=false`
Expected: FAIL（`UnsupportedOperationException`）

- [ ] **Step 5: 实现 `evaluateBars` + `writeReport`**

替换 `ResearchEvalService` 中两个方法体（其余 import 补齐）：
```java
package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.forecast.Forecast;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.kline.KlineAggregator;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchEvalService {

    private static final int LABEL_PURGE_HBARS = 1; // 标签前视=1 个 H-bar（决策周期=horizon）

    private final KlineHistoryStore store;

    public EvalReport evaluate(String symbol, ForecastHorizon horizon, long fromMs, long toMs,
                               Forecaster forecaster, EvalParams params) {
        List<KlineBar> oneMin = store.load(symbol, "1m", fromMs, toMs);
        EvalReport report = evaluateBars(symbol, horizon, oneMin, forecaster, params);
        writeReport(report);
        return report;
    }

    public static EvalReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                          Forecaster forecaster, EvalParams params) {
        List<KlineBar> hbars = KlineAggregator.aggregate(oneMin, horizon.millis());
        int points = Math.max(0, hbars.size() - 1); // 每个决策点 i 需要 i+1 算实现收益

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), LABEL_PURGE_HBARS, params.embargoBars(), params.minTrain());

        List<Integer> positions = new ArrayList<>();
        List<BigDecimal> rawReturns = new ArrayList<>();
        List<BigDecimal> posReturns = new ArrayList<>();
        int firstTest = -1, lastTestExclusive = -1;

        for (WalkForwardWindow w : wins) {
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                List<KlineBar> hist = hbars.subList(0, i + 1); // point-in-time，绝不含未来
                Forecast f = forecaster.forecast(hist);
                BigDecimal entry = hbars.get(i).close();
                BigDecimal next = hbars.get(i + 1).close();
                BigDecimal raw = entry.signum() == 0 ? BigDecimal.ZERO
                        : next.subtract(entry).divide(entry, 10, RoundingMode.HALF_UP);
                positions.add(f.direction());
                rawReturns.add(raw);
                posReturns.add(raw.multiply(BigDecimal.valueOf(f.direction())));
                if (firstTest < 0) firstTest = i;
                lastTestExclusive = i + 2; // 含 i+1 这根 close
            }
        }

        ReturnSeries series = new ReturnSeries(forecaster.name(), posReturns, horizon.periodsPerYear());
        RiskAdjustedMetrics metrics = RiskAdjustedMetrics.from(series);
        double naivePct = positions.isEmpty() ? 0.0
                : BenchmarkCalculator.permutationPercentile(positions, rawReturns, params.iterations(), params.seed());
        BigDecimal buyHold = (firstTest >= 0 && lastTestExclusive <= hbars.size())
                ? BenchmarkCalculator.buyAndHoldReturn(hbars.subList(firstTest, lastTestExclusive))
                : BigDecimal.ZERO;
        BigDecimal stratReturn = compound(posReturns);

        boolean beatBH = stratReturn.compareTo(buyHold) > 0;
        boolean beatNaive = naivePct >= params.naivePercentileThreshold();

        return new EvalReport(symbol, horizon.hours(), hbars.size(), posReturns.size(),
                metrics, buyHold, stratReturn, naivePct, beatBH, beatNaive, series);
    }

    /** 复利净收益 = Π(1+r) − 1。 */
    private static BigDecimal compound(List<BigDecimal> returns) {
        BigDecimal eq = BigDecimal.ONE;
        for (BigDecimal r : returns) eq = eq.multiply(BigDecimal.ONE.add(r));
        return eq.subtract(BigDecimal.ONE);
    }

    private void writeReport(EvalReport report) {
        try {
            Path dir = Path.of("target", "research-eval");
            Files.createDirectories(dir);
            Path file = dir.resolve(String.format("%s-%dh-%d.json",
                    report.symbol(), report.horizonHours(), System.currentTimeMillis()));
            Files.writeString(file, report.toJson());
            log.info("评估报告已写出: {} | {}", file, report.summary());
        } catch (IOException e) {
            log.warn("写评估报告失败（不影响返回）", e);
        }
    }
}
```

- [ ] **Step 6: 跑测试确认绿**

Run: `mvn -pl wiib-service test -Dtest=ResearchEvalServiceTest -DskipTests=false`
Expected: PASS（1 test）

- [ ] **Step 7: 建触发入口 `ResearchEvalController`**

```java
package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.eval.EvalParams;
import com.mawai.wiibservice.agent.research.eval.EvalReport;
import com.mawai.wiibservice.agent.research.eval.ResearchEvalService;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 研究/评估触发入口：离线按需跑，绝不接 live 周期。 */
@RestController
@RequestMapping("/api/research/eval")
@RequiredArgsConstructor
public class ResearchEvalController {

    private final KlineHistoryStore store;
    private final ResearchEvalService evalService;

    /** 回填 1m K 线：最近 fromDays 天。 */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                    @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        return Result.ok(store.backfill(symbol, from, now));
    }

    /** 跑一次样本外评估（EWMA 动量基线）。 */
    @PostMapping("/run")
    public Result<EvalReport> run(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                  @RequestParam(defaultValue = "12") int horizonHours,
                                  @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        EvalReport report = evalService.evaluate(
                symbol, ForecastHorizon.fromHours(horizonHours), from, now,
                new EwmaMomentumForecaster(12, 26), EvalParams.defaults());
        return Result.ok(report);
    }
}
```

- [ ] **Step 8: 编译确认（控制器无独立单测，靠编译 + Task 8 端到端）**

Run: `mvn -pl wiib-service test-compile -DskipTests=false`
Expected: BUILD SUCCESS（无编译错误）

- [ ] **Step 9: Commit**

```bash
git add wiib-service/src/main/java/com/mawai/wiibservice/agent/research/eval/ \
        wiib-service/src/main/java/com/mawai/wiibservice/controller/ResearchEvalController.java \
        wiib-service/src/test/java/com/mawai/wiibservice/agent/research/eval/ResearchEvalServiceTest.java
git commit -m "feat(research): 评估编排+报告+触发入口 ResearchEvalController（slice1 任务7）"
```

---

## Task 8: 跑 BTC/ETH × {6,12,24}h 出首份样本外报告

> 本任务无新代码，是端到端验证 spec §6 验收标准。需要本地 PostgreSQL + 能访问 Binance 合约 API。

- [ ] **Step 1: 在 PostgreSQL 执行建表**

把 Task 1 追加进 `sql/init.sql` 的 `kline_history` 建表语句，在你的库执行一次（`psql` 或客户端）。验证：
```sql
SELECT count(*) FROM kline_history; -- 期望 0（表已建）
```

- [ ] **Step 2: 启动服务并回填（首次先回填短窗跑通）**

启动 `wiib-service`（你的常规启动方式）。先回填最近 ~180 天（成本可控）：
```powershell
curl -X POST "http://localhost:8080/api/research/eval/backfill?symbol=BTCUSDT&fromDays=180"
curl -X POST "http://localhost:8080/api/research/eval/backfill?symbol=ETHUSDT&fromDays=180"
```
Expected: 返回 `{"code":...,"data":<行数>}`，data 约 = 180×1440 ≈ 259200（每标的）。复核：
```sql
SELECT symbol, count(*), min(open_time), max(open_time) FROM kline_history GROUP BY symbol;
```

- [ ] **Step 2.1（可选）: 全量回填 ~3 年**

跑通后如需全量，把 `fromDays` 调到 ~1095 重跑（幂等，已有的跳过）。注意：~315 万行 / 标的，数千次 Binance 请求，耗时与限频成本较大——按需进行。

- [ ] **Step 3: 跑 BTC/ETH × {6,12,24}h 六次评估**

```powershell
foreach ($s in "BTCUSDT","ETHUSDT") {
  foreach ($h in 6,12,24) {
    curl -X POST "http://localhost:8080/api/research/eval/run?symbol=$s&horizonHours=$h&fromDays=180"
  }
}
```
每次返回一份 `EvalReport` JSON，并在 `target/research-eval/` 落一份。

- [ ] **Step 4: 核对验收标准（spec §6）**

逐项确认：
1. 每份报告含：年化 Sharpe、Calmar、MaxDD、命中率、`buyAndHoldReturn`、`naivePercentile`、`beatBuyAndHold`、`beatNaive`——**六项齐全**。
2. 同一时间窗重跑 `/run`，`strategyReturn`/`metrics` **完全一致**（K 线落库 + 固定 seed → 可复现）。
3. 看服务日志里每份报告的 `summary()` 三线（策略 / buy&hold / naive）肉眼可比。
4. 基线 EWMA 动量**大概率 `beatBuyAndHold=false`**——这正是基线该有的样子（它立标尺，不为赢）。
5. `git status` 确认改动**只在** research 包 + `KlineHistory` 实体 + `KlineHistoryMapper` + `sql/init.sql` + 测试；**未碰任何 live 生产类**。

- [ ] **Step 5: 向用户汇报结果**

把六份报告的 `summary()` 三线整理给用户，明确：基线是否如期跑不赢 buy&hold、naive 分位多少、三周期对比。**不写多余文档**（按用户习惯）；如有值得记的非显然结论，再决定是否落 memory。

---

## Self-Review（写完计划后自查，对照 spec）

**1. Spec 覆盖（§8 八步逐一对应）：**
- §8.1 kline_history+Store+聚合单测 → Task 1 ✓
- §8.2 TripleBarrierLabeler+波动率栏宽+三结果单测 → Task 2 ✓（UPPER/LOWER/VERTICAL + 同根双触）
- §8.3 RiskAdjustedMetrics+收益序列钩子+对拍已知序列 → Task 3 ✓（ReturnSeries 即钩子）
- §8.4 BenchmarkCalculator+单测 → Task 4 ✓
- §8.5 WalkForwardEvaluator+证明无泄漏 → Task 5 ✓（断言 trainEnd+horizonBars ≤ testStart）
- §8.6 Forecaster+EwmaMomentumForecaster → Task 6 ✓
- §8.7 触发入口+报告 → Task 7 ✓（ResearchEvalController + EvalReport）
- §8.8 跑 BTC/ETH×{6,12,24}h 出首报告 → Task 8 ✓
- §6 验收 5 条 → Task 8 Step 4 逐条核对 ✓
- §5.4 复用边界（只读/零侵入/绝不触碰）→ 全程复制纯逻辑入 research 包，未改原类 ✓

**2. 占位符扫描：** 无 TBD/TODO；每个 code step 均给完整代码；骨架步骤的 `UnsupportedOperationException` 是 TDD 红态的有意手段，在同任务后续 step 被真实实现替换。✓

**3. 类型一致性（跨任务核对）：**
- `KlineBar(long,long,BigDecimal×5)` — Task1/2/4/6/7 构造一致 ✓
- `ForecastHorizon.millis()/hours()/periodsPerYear()` — Task1 定义、Task7 调用一致 ✓
- `Forecaster.forecast(List<KlineBar>)`/`name()` — Task6 定义、Task7 调用一致 ✓
- `RiskAdjustedMetrics.from(ReturnSeries)` 字段 `annualizedSharpe/calmar/maxDrawdown/cagr/hitRate/periods` — Task3 定义、Task7 `EvalReport.summary()`/测试调用一致 ✓
- `WalkForwardEvaluator.windows(total,testSize,horizonBars,embargo,minTrain)` — Task5 定义、Task7 调用实参顺序一致（`LABEL_PURGE_HBARS, params.embargoBars(), params.minTrain()`）✓
- `BenchmarkCalculator.buyAndHoldReturn/permutationPercentile` 签名 — Task4 定义、Task7 调用一致 ✓
- `EvalParams` 8 字段顺序 — Task7 `defaults()` 与测试 `new EvalParams(...)` 一致 ✓

**一个执行期注意点**（不是计划错误）：`ResearchEvalServiceTest` 用 `EwmaMomentumForecaster(2,4)` + 小参数，需 ≥7 个 H6 bar；`syntheticUptrend1m(8*360)` 给了 8 桶余量，足够；若执行时 `testPoints` 为 0，调大 bar 数或调小 `minTrain`。
