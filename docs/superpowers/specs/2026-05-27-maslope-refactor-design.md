# MaSlope 重构实验设计（本地 A/B 4-cell）

- 日期: 2026-05-27
- 作者: 马鸣阳 / Claude（brainstorming + codex review 修订）
- 状态: 实验设计已对齐，等待用户 review

> **本文档替换 v1**：v1（同日早一版）写的是"删除现有 mode 一次性重写"。经 codex review 反驳，认定该方案在尚未验证新设计有效性的前提下风险过高。改为本地 A/B 4-cell 实验：保留现有逻辑作 baseline，新逻辑作 candidate，feature flag 切换，**用回测数据决定去留**。
>
> v1 的方向（拐点入场 + 信号衰减出场 + 震荡诊断）保留，**实施方式**从"重写替换"改为"实验设计 + 数据驱动决策"。

## 0. 阅读约定

- **本文档不上 shadow 模式**（策略未上线，无需生产影子）
- **不删除任何现有逻辑**。所有现有 mode、R 风控、出场触发器全保留
- 新逻辑作为可切换的 candidate，通过 `feature flag` 与现有逻辑互斥
- 阈值标记为 `PARAM_X`，本文档不拍数值
- **以回测数据为准，不以主观判断为准**

## 1. 背景

### 1.1 当前 MaSlope 的核心问题

跨窗口表现极端不稳定（ETH 3m, 50x, 400U/仓）：

| 窗口 | 交易数 | 胜率 | PF | 净盈亏 | MaxDD |
|---|---|---|---|---|---|
| 2026-01-27 → 2026-03-27（单边大跌） | 14 | 21.4% | 0.01 | **-725U** | **75%** |
| 2026-02-25 → 2026-03-27（探底反弹） | 15 | 53.3% | 2.25 | 正 | 较低 |
| 2026-03-27 → 2026-05-27（反弹/震荡） | 24 | 41.7% | 3.22 | **+1465U** | 较低 |

同一组 `LAUNCH/PULLBACK_RECLAIM` mode 在两个窗口的表现：

| Mode | Mar-May 60d | Jan-Mar 60d |
|---|---|---|
| LAUNCH | 14 笔, WR 36%, **+810U** | 7 笔, WR 14%, **-342U** |
| PULLBACK_RECLAIM | 9 笔, WR 44%, **+642U** | 7 笔, WR 29%, **-383U** |

R 风控触发器（FAST_FAIL + EARLY_KILL）在 Jan-Mar 14 笔失败中触发了 10 次，但**账户依然 -72.5%**。说明：R 风控能限单笔，限不了系统性连续假信号入场。

**核心问题**：策略在某些 regime 有效（如 Mar-May 反弹/震荡），在另一些 regime 灾难（如 Jan-Mar 单边大跌）。实盘无法预知当前 regime，所以"某 regime 有效"不能算稳健。

### 1.2 用户描述的新设计方向

**拐点入场**（用户原话精炼）：
- MACD 金叉/水上金叉**刚发生**（最近 1-2 根 K 线内）
- MA7 斜率**刚由负转正**
- MA25 斜率仍负但**二阶减速**（如 -0.5 → -0.45）
- 时间窗：金叉时 or 金叉后 1 根 K 线

**信号衰减出场**（用户原话精炼）：
- 主趋势确定后**观察信号变化**
- MA7 持续贴近 MA25 → 警惕
- MACD DIF 接近 DEA → 警惕；DIF 反向穿越 DEA → 平仓
- 信号持续减弱到反转 → 退场

### 1.3 已有但未充分利用的资产

`MaSlopeFailureEvaluator`（`maslope/`）已经实现 6 类信号衰减检测：
- 价格丢 MA7 / MA7 斜率弱化 / MA7-MA25 间距收敛 / MACD 反向 / DI 反转 / 收盘趋势反向

返回 `FailureScore.score()`（0-6 分）。但**目前被 R 倍数门栓住**（必须 `profitR ≤ X` AND `score ≥ Y` 才触发），导致纯信号路径在 14 笔 Jan-Mar 交易里 0 次触发。

## 2. 实验目标

### 2.1 主目标

通过**本地 A/B 回测**找出：
1. 拐点入场（PIVOT）相对现有入场是否更稳健（跨多窗口表现）
2. 信号衰减出场（SIGNAL_DECAY）相对现有出场是否更稳健
3. 二者联合改进是否有协同 / 抵消效应
4. R 风控（FAST_FAIL / EARLY_KILL）在新设计下是否仍有边际价值

### 2.2 评估的硬指标（同时达成才算改善）

**"绩效 ≥ baseline" 的精确口径**（同一窗口内对比 baseline cell）：

```
绩效 ≥ baseline ≡
    finalEquity      ≥ baseline.finalEquity              (硬性，容差 0)
AND maxDrawdownPct   ≤ baseline.maxDrawdownPct + 5pp    (允许 5 个百分点上浮)
AND profitFactor     ≥ baseline.profitFactor × 0.90     (允许 10% 容差)
```

`finalEquity` 不允许下降（账户最终钱是硬指标）。MaxDD 和 PF 给小容差，避免数值微小波动误判。

| 指标 | 目标 |
|---|---|
| 5 个 ETH 窗口中绩效 ≥ baseline 的窗口数 | **全部 5 个** |
| ETH 窗口绩效 > baseline 20%（finalEquity 维度）的窗口数 | **至少 1 个** |
| 任意 ETH 窗口 PF | **≥ 1.0**（不爆仓） |
| 任意 ETH 窗口 MaxDD | **≤ 30%** |
| BTC 窗口绩效 ≥ baseline 的窗口数 | **至少 2/3** |
| **`ETH_90D` 总交易数** | **≥ baseline × 80%**。若 < 80%，必须 PF 提升 ≥ 30% 或 MaxDD 下降 ≥ 30% 补偿 |

**任一不达 → 该 cell 失败，不 go live**。

### 2.3 参考指标（不作硬指标，但报告必须列）

| 指标 | 说明 |
|---|---|
| 各 cell 在不同 regime 的绩效方差 | 反过拟合的真正度量——好策略应该在不同 regime 都不爆仓 |
| 关键 trade 的入场/出场点 K 线层面对比 | 抽样几笔关键 trade 看是否符合"拐点入场"的预期 |
| 拐点入场的 `entryDiagnostics.choppyScore` 分布 | 看 ChoppyDetector 是否对 PIVOT 入场质量有区分度，为下一轮是否升级为 hardGate 提供依据 |

## 3. 实验设计

### 3.1 4-cell 矩阵

| Cell | 入场 | 出场 | 含义 |
|---|---|---|---|
| **A: Baseline** | LEGACY | LEGACY | 当前 MaSlope 原样跑，作对照 |
| **B: New Entry** | PIVOT | LEGACY | 只换入场，看入场改进的边际贡献 |
| **C: New Exit** | LEGACY | SIGNAL_DECAY | 只换出场，看出场改进的边际贡献 |
| **D: New Both** | PIVOT | SIGNAL_DECAY | 联合改进，看协同效应 |

### 3.2 窗口矩阵

| Window ID | 周期 | 日期范围 | 市场特征 |
|---|---|---|---|
| `ETH_JAN_MAR` | 60d | 2026-01-27 → 2026-03-27 | ETH 单边大跌 -38% |
| `ETH_FEB_MAR` | 30d | 2026-02-25 → 2026-03-27 | ETH 探底反弹 |
| `ETH_MAR_MAY` | 60d | 2026-03-27 → 2026-05-27 | ETH 反弹/震荡 |
| `ETH_APR_MAY` | 30d | 2026-04-27 → 2026-05-27 | ETH 最近 30 天 |
| `ETH_90D` | 90d | 2026-02-26 → 2026-05-27 | ETH 混合 regime |
| `BTC_JAN_MAR` | 60d | 2026-01-27 → 2026-03-27 | BTC 同期 |
| `BTC_MAR_MAY` | 60d | 2026-03-27 → 2026-05-27 | BTC 同期 |
| `BTC_90D` | 90d | 2026-02-26 → 2026-05-27 | BTC 同期 |

主矩阵：**4 cell × 8 窗口 = 32 个回测数据点**。

### 3.3 R 风控 ablation 子实验

在 Cell A (Baseline) 和 Cell D (New Both) 上额外跑：

| 配置 | EARLY_KILL | FAST_FAIL_SHALLOW | FAST_FAIL_DEEP |
|---|---|---|---|
| `ABL_FULL` | ✅ | ✅ | ✅ |
| `ABL_NO_EARLY_KILL` | ❌ | ✅ | ✅ |
| `ABL_NO_FAST_FAIL` | ✅ | ❌ | ❌ |
| `ABL_NO_R_FILTER` | ❌ | ❌ | ❌ |

主要看 `ETH_JAN_MAR` 窗口（R 风控压力最大的窗口）。

子矩阵：**2 cell × 4 config × 4 个 ETH 窗口 = 32 个回测数据点**。

### 3.4 总实验工作量

实验分两轮，**第二轮仅在第一轮发现 winner cell 时启动**。

#### 第一轮：固定 PARAM 初值

所有 PARAM 用 §8 表里的"初值"，跑：

| 类别 | 数据点 |
|---|---|
| 主矩阵 4-cell（§3.1） | 4 cell × 8 窗口 = 32 |
| R 风控 ablation（§3.3） | 2 cell × 4 config × 4 ETH 窗口 = 32 |
| **第一轮总计** | **64** |

按单跑 8 分钟算，**约 8.5 小时**，脚本批量过夜完成。

第一轮结束后看 §2.2 硬指标：
- 如果某个 cell 满足全部硬指标 → 进入第二轮（参数网格搜索）
- 如果没有任何 cell 满足 → 实验结论：当前结构无效，回到 brainstorming 找别的方向，**不进入第二轮**

#### 第二轮：winner cell 的参数网格搜索（条件触发）

只在第一轮 winner cell 上做小范围网格：

| PARAM | 候选值 |
|---|---|
| `PARAM_MACD_CROSS_LOOKBACK` | 1, 2, 3 |
| `PARAM_EXIT_SCORE` | 2, 3, 4 |

网格 = 3 × 3 = 9 配置 × 5 个 ETH 窗口 = **45 数据点**，约 6 小时。

第二轮目的：找参数稳定区间，不是找最优值。如果所有 9 配置绩效都接近，说明参数稳定；如果只有 1-2 配置达标，说明对参数过敏，视为参数过拟合，**不能 go live**。

#### 第三轮：敏感性测试（条件触发）

仅在第二轮找到稳定参数区间时启动。每个 PARAM ±20%，看主指标变化是否 ≤ 20%。

详 §10.3。

## 4. 代码组织

### 4.1 Feature flag 设计

新建 `MaSlopeExperimentConfig`（注入到 EntryStrategy 和 ExitPlaybook）：

```java
public record MaSlopeExperimentConfig(
    EntryMode entryMode,       // LEGACY | PIVOT
    ExitMode exitMode,         // LEGACY | SIGNAL_DECAY
    boolean enableEarlyKill,   // 默认 true
    boolean enableFastFail,    // 默认 true
    Map<String, Double> params // PARAM_X 的运行时注入
) {
    public enum EntryMode { LEGACY, PIVOT }
    public enum ExitMode { LEGACY, SIGNAL_DECAY }
}
```

通过 JVM 参数注入回测：
```
-Dmaslope.exp.entry=PIVOT
-Dmaslope.exp.exit=SIGNAL_DECAY
-Dmaslope.exp.earlyKill=true
-Dmaslope.exp.fastFail=true
-Dmaslope.exp.params.MACD_CROSS_LOOKBACK=2
```

### 4.2 MaSlopeEntryStrategy 改造

```java
@Override
public EntryStrategyResult build(EntryStrategyContext input) {
    if (config.entryMode() == EntryMode.PIVOT) {
        return buildPivot(input);  // 新逻辑，§5 定义
    }
    return buildLegacy(input);  // 现有逻辑，原封不动
}
```

`buildLegacy` 是把现 build 方法整体重命名，**不改一行实际逻辑**。

### 4.3 MaSlopeExitPlaybook 改造

```java
@Override
public ExitPlaybookDecision evaluate(...) {
    if (config.exitMode() == ExitMode.SIGNAL_DECAY) {
        return evaluateSignalDecay(...);  // 新逻辑，§6 定义
    }
    return evaluateLegacy(...);  // 现有逻辑，但 R 风控按 config 开关
}
```

`evaluateLegacy` 内部 EARLY_KILL/FAST_FAIL 触发逻辑加 `if (config.enableEarlyKill())` 等开关。

### 4.4 不动的部分

- `EntryStrategy` / `ExitPlaybook` 接口不变
- `MarketContext` 现有字段不删不改（可新增）
- `MaSlopeStateClassifier` 主分类逻辑不动（新增 `ma25PrevSlopeAtr` 字段供 PIVOT 用）
- `MaSlopeFailureEvaluator` 不动
- `MaSlopeKlineStrategy` 不动
- 现有 LAUNCH / PULLBACK_RECLAIM / MACD_RECLAIM / EARLY_MA25_LAG / MACD_EARLY 全部保留在 `buildLegacy` 内
- 现有 EARLY_KILL / FAST_FAIL / EXTINGUISH / NO_PROGRESS / NO_0_5R_IN_90M 全部保留在 `evaluateLegacy` 内

## 5. PIVOT 入场设计

### 5.1 必要条件（hardGate，必须全部满足）

LONG 入场为例（SHORT 镜像）：

| # | 条件 | 物理含义 |
|---|---|---|
| 1 | 最近 `PARAM_MACD_CROSS_LOOKBACK` 根闭合 K 线内发生 `macd_cross == "golden"` | MACD 刚金叉 |
| 2 | `prev_ma7_slope_atr < 0` AND `curr_ma7_slope_atr > PARAM_MA7_SLOPE_MIN` | MA7 斜率刚由负转正 |
| 3 | `prev_ma25_slope_atr < 0` AND `curr_ma25_slope_atr < 0` AND `|curr_ma25_slope_atr| < |prev_ma25_slope_atr|` | MA25 二阶减速 |

实现说明：
- 条件 1 需要历史 cross 检测。`MarketContext` 当前 `macdCross` 只是单值。**实现方式**：在 `EntryStrategy` 内从 `macd_dif_series_closed` 和 `macd_dea_series_closed` 自己算近 N 根的 cross 位置（CryptoIndicatorCalculator 需增加这两个序列字段输出）。
- 条件 2 的 `prev_ma7_slope_atr` 由 `MaSlopeStateClassifier.classifyPrimary()` 返回的 `MaState.ma7PrevSlopeAtr()` 提供（已存在）。
- 条件 3 的 `prev_ma25_slope_atr` 需要扩展 `MaState` record 增加 `ma25PrevSlopeAtr()` 字段，由分类器在算 `ma25SlopeAtr` 时同窗口往前推一根。低风险扩展。

### 5.2 软加分（不阻拦，但影响 positionScale）

基础 `positionScale = PARAM_PIVOT_BASE_SCALE`。每条加分项满足则乘以系数：

| 条件 | 系数 |
|---|---|
| MACD 金叉在零轴上方（`macdDif > 0` AND `macdDea > 0`） | × 1.10 |
| `volumeRatio >= PARAM_VOL_CONFIRM` | × 1.06 |
| `closePositionClosed >= 0.6` | × 1.04 |
| `bollExpanding5 == true` | × 1.04 |
| `maAlignment1h == 0` AND `maAlignment15m ≥ 0` | × 1.06 |

最终 `clamp(positionScale, MIN, MAX)`，沿用现 MaSlope 的范围。

### 5.3 ChoppyMarketDetector（仅 diagnostics，不阻拦入场）

5 维度震荡评分（详 §7），但**不作为入场 hardGate**。仅作为 diagnostics 字段写入 `entryDiagnostics`，看回测后哪些拐点入场发生在高震荡分环境，作为下一轮调整依据。

### 5.4 PIVOT 拒因（独立 reject）

| 条件 | 拒因 |
|---|---|
| `atr_from_fallback == true` | `MAPIVOT_ATR_FALLBACK_REJECT` |
| 数据不足（序列长度不够） | `MAPIVOT_DATA_INSUFFICIENT` |
| `atrSpikeRatio > PARAM_ATR_SPIKE_REJECT` | `MAPIVOT_ATR_SPIKE` |
| 条件 1/2/3 任一不满足 | `MAPIVOT_NOT_TURNING_POINT` |

## 6. SIGNAL_DECAY 出场设计

### 6.1 新增触发器（按优先级）

| 优先级 | 触发器 | 触发条件 |
|---|---|---|
| 1 | **MACD_REVERSAL_CROSS** | 持仓方向反向的 `macd_cross` 刚发生 |
| 2 | **SIGNAL_REVERSE** | `FailureScore.score() >= PARAM_EXIT_SCORE` |
| 3 | **MACD_NEAR_REVERSAL** | DIF 接近 DEA（`|DIF - DEA|/atrClosed < PARAM_NEAR_DEA`）AND 还在同侧 AND **当前价已越过 entry**（防止 SL 立即触发）→ 收紧 SL 到 entry |

### 6.2 保留触发器（来自 LEGACY，可被 R 风控开关关闭）

| 优先级 | 触发器 | 是否可关 |
|---|---|---|
| 4 | EARLY_KILL | `config.enableEarlyKill` |
| 5 | FAST_FAIL_DEEP | `config.enableFastFail` |
| 6 | FAST_FAIL_SHALLOW | `config.enableFastFail` |
| 7 | BREAKEVEN | 永远开 |
| 8 | PARTIAL_TP | 永远开 |
| 9 | TRAIL | 永远开 |
| 10 | SL（硬止损） | 永远开 |

### 6.3 关键设计差异

LEGACY 出场：
- EARLY_KILL/FAST_FAIL 在 R+Score 联合时触发（先 R 后 Score）
- SIGNAL_REVERSE 优先级低 + 条件严，几乎不触发

SIGNAL_DECAY 出场：
- MACD 反向 cross + FailureScore 阈值作为**最高优先级**
- EARLY_KILL/FAST_FAIL 沦为兜底（如果信号衰减没及时触发，R 风控接管）
- 两层防护，不是替代关系

### 6.4 MACD_NEAR_REVERSAL 的精确动作

触发条件：
- LONG: `DIF > DEA` AND `(DIF - DEA)/atrClosed < PARAM_NEAR_DEA` AND `currentPrice > entryPrice`
- SHORT: `DIF < DEA` AND `(DEA - DIF)/atrClosed < PARAM_NEAR_DEA` AND `currentPrice < entryPrice`

`currentPrice > entryPrice`（LONG）/ `currentPrice < entryPrice`（SHORT）这个约束**防止 SL 立即触发**：如果当前价还在浮亏区间，SL 移到 entry 等价于市价平仓，等于强制小亏出场，违反"提前保本"的意图。

动作：
- 记 `R_distance = |entryPrice - originalSL|`（每单位风险距离）
- LONG: `targetSL = entryPrice + PARAM_BE_LOCK_R × R_distance`
- SHORT: `targetSL = entryPrice - PARAM_BE_LOCK_R × R_distance`
- LONG: `newSL = max(currentSL, targetSL)`（SL 只能往上抬）
- SHORT: `newSL = min(currentSL, targetSL)`（SL 只能往下移）
- 不平仓，只调 SL。后续若 MACD 真的反向 cross，由优先级 1 平掉

`PARAM_BE_LOCK_R = 0.10` 默认值沿用现 MaSlope 的 `BREAKEVEN_LOCK_R`，目的是**覆盖往返手续费**（50x 杠杆下入场+出场手续费 ≈ 0.1R）。直接把 SL 设在 entry 是"价格保本"，扣手续费后仍是小亏；加 0.1R 锁定才是真保本。

## 7. ChoppyMarketDetector

### 7.1 5 维度评分

`int ChoppyMarketDetector.score(MarketContext ctx)` 返回 0-5。

| 维度 | 触发条件 | 数据来源 | 物理含义 |
|---|---|---|---|
| 1 | 过去 `PARAM_CHOP_LOOKBACK` 根内 `ma7-ma25` spread 符号翻转次数 ≥ `PARAM_CHOP_SPREAD_FLIPS` | `ma7/25SeriesClosed` | 均线纠缠 |
| 2 | Efficiency Ratio < `PARAM_CHOP_ER`（净位移/总路径） | `closeSeriesClosed` | 价格走"之"字（Kaufman 1995） |
| 3 | `adx < PARAM_CHOP_ADX` | `ctx.adx` | 趋势强度弱（Wilder 1978） |
| 4 | `bollBandwidth < PARAM_CHOP_BBW` | `ctx.bollBandwidth` | 波动率压缩 |
| 5 | `avg(volumeRatioClosedSeries) < PARAM_CHOP_VOL` | `volumeRatioClosedSeries` | 缺乏方向共识 |

### 7.2 阶段 1：仅 diagnostics

本次实验阶段，ChoppyMarketDetector **不作为入场 hardGate**，只写入 `entryDiagnostics.choppyScore` 字段。回测完看哪些 cell 的成功/失败 trade 在不同 choppy 分时的分布，决定是否升级为过滤器。

### 7.3 阶段 2（数据通过后）

如果阶段 1 数据显示"高 choppy 分时入场胜率显著低"，则在下一轮实验加 ChoppyDetector 作为 PIVOT 入场的 hardGate（`score < PARAM_CHOP_SCORE`）。

## 8. PARAM 清单

| PARAM | 出现位置 | 初值（实验起点） | 调优方法 |
|---|---|---|---|
| `PARAM_MACD_CROSS_LOOKBACK` | §5.1 #1 | 2 | 网格 1/2/3 |
| `PARAM_MA7_SLOPE_MIN` | §5.1 #2 | 0.03（现 MaSlope 同值） | 历史分布 P50-P70 |
| `PARAM_ATR_SPIKE_REJECT` | §5.4 | 2.0 | 现 MaSlope 同值 |
| `PARAM_PIVOT_BASE_SCALE` | §5.2 | 0.65 | 沿用现 MaSlope 默认 |
| `PARAM_VOL_CONFIRM` | §5.2 | 1.2 | 历史 volumeRatio 的 P60 |
| `PARAM_EXIT_SCORE` | §6.1 #2 | 3 | 网格 2/3/4 |
| `PARAM_NEAR_DEA` | §6.4 | 0.05 | 历史 \|DIF-DEA\|/ATR 分布 P75 |
| `PARAM_BE_LOCK_R` | §6.4 / BREAKEVEN | 0.10（沿用现 BREAKEVEN_LOCK_R） | 沿用现 MaSlope，覆盖往返手续费 |
| `PARAM_CHOP_LOOKBACK` | §7.1 | 30 | 固定起点（30 根 = 90 分钟 @ 3m） |
| `PARAM_CHOP_SPREAD_FLIPS` | §7.1 #1 | 2 | 仅 diagnostic 阶段不调 |
| `PARAM_CHOP_ER` | §7.1 #2 | 0.30 | 同上 |
| `PARAM_CHOP_ADX` | §7.1 #3 | 20 | 业内标准 |
| `PARAM_CHOP_BBW` | §7.1 #4 | 2.0 | 同上 |
| `PARAM_CHOP_VOL` | §7.1 #5 | 1.0 | 同上 |

实验起点（initial values）只是为了能跑起来。最终值由网格搜索决定。

## 9. 实施计划

### 阶段 A：基础设施（不改业务逻辑）

1. 扩展 `MaSlopeStateClassifier`：`MaState` 增加 `ma25PrevSlopeAtr()` 字段
2. 扩展 `CryptoIndicatorCalculator.calcAll`：输出 `macd_dif_series_closed` 和 `macd_dea_series_closed`
3. 扩展 `MarketContext.fromKlineIndicators` 和 `parse`：接收上面两个新序列
4. 新建 `MaSlopeExperimentConfig`（默认全 LEGACY，相当于不变）
5. 新建 `ChoppyMarketDetector`（仅 diagnostics 实现）
6. 单元测试

### 阶段 B：LEGACY 路径拆分（行为不变）

1. `MaSlopeEntryStrategy.build` 拆为 `build`（dispatcher）+ `buildLegacy`（现逻辑）
2. `MaSlopeExitPlaybook.evaluate` 拆为 `evaluate`（dispatcher）+ `evaluateLegacy`
3. `evaluateLegacy` 内 EARLY_KILL/FAST_FAIL 调用包 `if (config.enableEarlyKill)` 等
4. 跑现有 `MaSlopeKlineBacktestIT` 在默认配置下，**绩效必须和重构前完全一致**（行为不变验证）

### 阶段 C：PIVOT 入场实现

1. 新增 `buildPivot()` 方法实现 §5
2. 通过 `-Dmaslope.exp.entry=PIVOT` 切换
3. 单元测试

### 阶段 D：SIGNAL_DECAY 出场实现

1. 新增 `evaluateSignalDecay()` 方法实现 §6
2. 通过 `-Dmaslope.exp.exit=SIGNAL_DECAY` 切换
3. 单元测试

### 阶段 E：实验跑批

1. 写脚本批量跑 28 个主矩阵数据点
2. 写脚本批量跑 32 个 R 风控 ablation 数据点
3. 整理对比表（4 cell × 7 窗口 + 2 cell × 4 ablation × 4 窗口）
4. 写报告 markdown

### 阶段 F：评估与决策

1. 对照 §2.2 硬指标找通过的 cell
2. 找参数稳定性（敏感性测试，每个 PARAM ±20%）
3. 给用户报告
4. 用户决定哪个 cell go live

## 10. 验证

### 10.1 行为不变验证（阶段 B 完成时）

默认配置下（`entryMode=LEGACY, exitMode=LEGACY, enableEarlyKill=true, enableFastFail=true`），所有 7 个窗口的回测绩效**必须**和重构前一字不差。差一笔交易就要停下来找原因。

### 10.2 硬指标（阶段 F）

见 §2.2。任一不达 → 该 cell 失败。

### 10.3 参数稳定性

最优 cell 的每个 PARAM ±20%，主指标（PF / MaxDD / 净盈亏）变化 ≤ 20%。否则视为参数过拟合，需调结构。

### 10.4 ablation 解读

| 现象 | 结论 |
|---|---|
| 关闭 EARLY_KILL/FAST_FAIL 后 MaxDD 显著变大 | R 风控有真实价值，保留 |
| 关闭后无显著变化 | R 风控在新设计下冗余，可删 |
| 关闭后 MaxDD 反而变小 | R 风控误杀了真好仓位，应该调阈值或删 |

## 11. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 实验工作量大（60+ 数据点） | 脚本批量跑，过夜完成 |
| PIVOT 路径在所有窗口都不通过 | 用户描述的拐点思想本身可能在 ETH 3m 上无效——这就是要验证的，不是失败 |
| SIGNAL_DECAY 出场太激进，误杀好仓位 | 实验本身会暴露这个问题（PF 下降）。可在阶段 F 调 `PARAM_EXIT_SCORE` |
| Feature flag 增加代码复杂度 | 接受。实验阶段必要，确认 winner cell 后可删掉 LEGACY 分支 |
| 实验后所有 cell 都不显著优于 baseline | 接受。说明现有 MaSlope 已经在拐点+衰减思想能企及的范围内。回到 brainstorming 找别的方向 |

## 12. 与 v1 spec 的差异（修订原因）

| v1（被替换） | v2（本文档） |
|---|---|
| 删除 LAUNCH / PULLBACK_RECLAIM / MACD_RECLAIM 等 mode | **全部保留**，feature flag 切换 |
| 删除 EARLY_KILL / FAST_FAIL / EXTINGUISH | **全部保留**，可独立开关用于 ablation |
| 用 SIGNAL_REVERSE 替代 R 风控 | SIGNAL_REVERSE 与 R 风控**并存**，R 风控作兜底 |
| ChoppyMarketDetector 作 hardGate | **仅 diagnostics**，数据通过后再升级 |
| MA25 仍负但减速 = 反转策略，硬塞入 MaSlope | 拐点路径作为 MaSlope 的 PIVOT mode，与 LEGACY 同框共存（命名问题留待 winner cell 确定后再决定） |
| 8 阶段实施，含上线计划 | 实验设计，不预设上线，由数据决定 |

---

**End of spec v2.**
