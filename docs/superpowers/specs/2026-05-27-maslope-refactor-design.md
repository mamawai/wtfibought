# MaSlope 重构设计（拐点入场 + 信号衰减出场）

- 日期: 2026-05-27
- 作者: 马鸣阳 / Claude（brainstorming 协作）
- 状态: 设计中，等待用户 review

## 0. 阅读约定

- **结构层**：规则形式（用哪些信号、怎么组合）。本文档定下来。
- **参数层**：所有阈值标记为 `PARAM_X`，本文档不拍数值，留到回测网格搜索决定。每个 PARAM 都附"数据驱动定值的方法"。
- **避免过拟合**：本文档**不引用任何单一交易日的案例**做设计依据。所有规则的合理性来自"普适市场理论 + 现有指标的物理含义"。

## 1. 背景

### 1.1 当前 MaSlope 的统计问题

不同 60d 窗口绩效方差巨大：

| 窗口 | 交易数 | 胜率 | PF | 净盈亏 |
|---|---|---|---|---|
| 2026-01-27 → 2026-03-27（单边大跌） | 14 | 21.4% | 0.01 | -725U（-72.5% 几乎爆仓） |
| 2026-02 → 2026-03（探底反弹） | 15 | 53.3% | 2.25 | 正 |
| 2026-03 → 2026-05（反弹/震荡） | 24 | 41.7% | 3.22 | 正 |
| 90d 全窗口 | 38 | 47.4% | 2.73 | 正 |

**症结**：策略在不同市场 regime 之间表现差异巨大。一次极端 regime 就足以把账户打到爆仓边缘。实盘交易**无法预知**当前 regime，所以"在某些 regime 表现差"等同于"实盘风险不可控"。

### 1.2 根因：架构层面

代码审查发现两个**结构性**问题：

**A. 入场端追势而不是拐点**

现 `MaSlopeEntryStrategy` 4 mode × 3 quality 共 12 种入场路径，本质都是"已确认趋势的延续"：
- LAUNCH：MA7 已强斜率延伸内
- PULLBACK_RECLAIM：下跌中反弹后再次跌破 MA7（**追跌**）
- MACD_EARLY / EARLY_MA25_LAG：早期信号，但要求 MA25 ≥ 0

入场点都在趋势主体已经走出 5-15 根 K 线之后，**没有"刚发生反转/刚发生拐点"的早期识别**。结果就是在单边大跌中反复追跌、反弹中反复追多。

**B. 出场端 R 倍数主导，纯信号出场被抢占**

`MaSlopeExitPlaybook` 有 9 个触发器，按优先级：

| 优先级 | 触发器 | 类型 |
|---|---|---|
| 2 | EARLY_KILL | R+Score 联合 |
| 3-4 | FAST_FAIL_DEEP/SHALLOW | R+Score 联合 |
| 5 | **SIGNAL_REVERSE** | **纯信号** ← 想要的逻辑 |
| 6 | EXTINGUISH | 信号+时间 |
| 7 | NO_PROGRESS_60M | 时间+R |

**SIGNAL_REVERSE 在回测里 0 次触发**——优先级太低 + 条件太严（要求 `stronglyAgainst` AND 高周期不支持），出场都被 EARLY_KILL/FAST_FAIL 抢占。

### 1.3 已有但未充分利用的资产

`MaSlopeFailureEvaluator`（`maslope/` 目录下）已经实现了 6 类信号衰减检测：
- `CLOSE_BELOW_MA7` / `CLOSE_ABOVE_MA7`（价格丢 MA7）
- `MA7_SLOPE_WEAKER`（MA7 斜率比入场弱）
- `SPREAD_DELTA_WEAK`（MA7/MA25 间距收敛）
- `MACD_WEAK`（MACD 反向）
- `DI_FLIPPED`（DI 反转）
- `CLOSE_TREND_AGAINST`（收盘趋势反向）

返回 `FailureScore.score()`（0-6 分）。这是**信号衰减出场的现成实现**，只是被 R 倍数门栓住没充分发挥。

## 2. 设计目标

### 2.1 主目标

| 维度 | 现状 | 期望 |
|---|---|---|
| 不同 60d 窗口的 PF 方差 | 极大（0.01 ~ 3.22） | **显著缩小** |
| 单一窗口最大回撤 | 75%（接近爆仓） | **任意 60d 窗口 ≤ 30%** |
| 整体 PF | 60d 1.18-3.22 不稳 | **多窗口都 ≥ 1.5** |
| 入场 quality | 追势为主 | **拐点为主** |
| 出场 trigger | R 倍数主导 | **信号衰减主导** |

### 2.2 不追求的（YAGNI）

- 不追求高频。降低交易数（但提高每笔质量）是可接受的代价。
- 不追求短期暴利。稳定性 >> 单窗口净利。
- 不引入新数据源（OI / funding / 微结构等）。这次只用现有 K 线 + 派生指标。

### 2.3 失败标准（什么情况下设计需要重做）

- 多窗口回测后**仍有某个 60d 窗口 PF < 1**
- **OOS 窗口胜率比 IS 窗口下降 > 10%**（过拟合证据）
- **参数 ±20% 性能剧变**（参数不稳定，本质上还是过拟合）

## 3. 核心设计

### 3.1 整体架构

```
MarketContext
    ↓
[ZigzagDetector] ─── 震荡市过滤（新增）
    ↓ 非震荡
[MaSlopeStateClassifier] ─── 复用
    ↓
[新 MaSlopeEntryStrategy] ─── 重写：拐点 confluence
    ↓ 入场
持仓
    ↓
[MaSlopeFailureEvaluator] ─── 复用
    ↓
[新 MaSlopeExitPlaybook] ─── 重写：信号衰减主导
    ↓ 出场
```

### 3.2 模块清单

| 模块 | 路径 | 处理 |
|---|---|---|
| `MaSlopeStateClassifier` | `maslope/` | ✅ 复用（状态分类正确） |
| `MaSlopeFailureEvaluator` | `maslope/` | ✅ 复用（信号衰减检测正确） |
| `MaSlopeKlineStrategy` | `maslope/` | ✅ 复用（K 线通道入口） |
| **`ChoppyMarketDetector`** | `maslope/`（**新增**） | 5 维度震荡评分 |
| **`MaSlopeEntryStrategy`** | `entry/strategy/` | 🔴 **重写**：拐点 confluence |
| **`MaSlopeExitPlaybook`** | `exit/playbook/` | 🔴 **重写**：信号衰减主导 |

接口完全不动（`EntryStrategy` / `ExitPlaybook` 实现接口不变），向后兼容。

## 4. 入场设计（重写 MaSlopeEntryStrategy）

### 4.1 单一拐点 confluence

删除现有所有 mode（CONFIRMED / PRIMARY_KLINE / EARLY_MA25_LAG / MACD_EARLY × LAUNCH / PULLBACK_RECLAIM / MACD_RECLAIM），只保留一种入场路径：**拐点入场**。

#### 4.1.1 必要条件（hardGate，必须全部满足）

记 LONG 入场为例，SHORT 镜像：

| # | 条件 | 物理含义 |
|---|---|---|
| 1 | 最近 `PARAM_MACD_CROSS_LOOKBACK` 根闭合 K 线内发生 `macd_cross == "golden"` | MACD 刚金叉，动能刚转向 |
| 2 | `prev_ma7_slope_atr < 0` AND `curr_ma7_slope_atr > PARAM_MA7_SLOPE_MIN` | MA7 斜率刚由负转正（拐点核心） |
| 3 | `prev_ma25_slope_atr < 0` AND `curr_ma25_slope_atr < 0` AND `|curr_ma25_slope_atr| < |prev_ma25_slope_atr|` | MA25 还在向下，但二阶减速（趋势衰竭信号） |
| 4 | `ChoppyMarketDetector.score(ctx) < PARAM_CHOP_SCORE` | 不在震荡市 |

**关于条件 #1（MACD cross 历史检测）的实现说明**：

当前 `MarketContext.macdCross` 只保存"当前根"的 cross 标记（cross 当根有值，否则 null）。要检测"最近 N 根内的 cross"需要：
- 方案 A（推荐）：`ChoppyMarketDetector` / `EntryStrategy` 内部从 `macd_dif_series_closed` 和 `macd_dea_series_closed` 自己算历史 cross。**需要在 CryptoIndicatorCalculator 输出里增加这两个序列字段**。
- 方案 B：在 `MarketContext` 加一个 `recent_macd_crosses` 字段，由 `BuildFeaturesNode` 和 `MaSlopeKlineStrategy` 都填充。

实现阶段选 A，因为它把"历史 cross 计算"作为入场策略内部细节，不污染 `MarketContext` 全局。

**关于条件 #2 / #3（prev slope 数据来源）的实现说明**：

`MaSlopeStateClassifier.classifyPrimary` 当前返回的 `MaState` 已包含 `ma7SlopeAtr()` 和 `ma7PrevSlopeAtr()`，因此条件 #2 直接可用。

条件 #3 需要 `ma25PrevSlopeAtr`。`MaSlopeStateClassifier` 当前**没有**暴露这个字段。需要扩展 `MaState` record 加一个 `ma25PrevSlopeAtr()` 字段，由分类器在计算 `ma25SlopeAtr` 时一并算出（用同样的回归窗口往前推一根）。这是低风险扩展。

#### 4.1.2 软加分（确认信号，加分进 score 但不阻拦）

入场 `score` 是返回给 `EntryStrategyCandidate` 的信号强度（范围 `[SCORE_MIN, SCORE_MAX]`，沿用现 MaSlope 的 `[0.45, 1.20]`），用于：
- 上层 `EntryRiskSizingService` 据此决定 `positionScale`（仓位缩放）
- 日志和诊断输出
- **不影响是否入场**（入场由 4.1.1 hardGate 决定）

软加分作用：让"有量能 + 收盘强势 + 高周期对齐"的拐点信号获得更大仓位，反之缩小仓位。

基础 score = 1.0。每条加分项满足则乘以对应系数：

| 条件 | 系数 |
|---|---|
| `maAlignment1h == 0` AND `maAlignment15m ≥ 0` | × 1.08 |
| `volumeRatio >= PARAM_VOL_CONFIRM` | × 1.05 |
| `closePositionClosed >= 0.6` | × 1.04 |
| `bollExpanding5 == true` | × 1.04 |
| MACD 金叉发生在零轴上方（`macdDif > 0` AND `macdDea > 0`） | × 1.06 |

最终 score `clamp(score, SCORE_MIN, SCORE_MAX)`。

#### 4.1.3 拒绝信号（独立 reject，不参与上面 confluence）

| 条件 | 拒因 |
|---|---|
| `atr_from_fallback == true` | `MASLOPE_ATR_FALLBACK_REJECT` |
| 主周期或确认周期数据不足 | `MASLOPE_DATA_INSUFFICIENT` |
| `ctx.qualityFlags.contains("STALE_AGG_TRADE")` | `MASLOPE_STALE_AGG_TRADE` |
| `atrSpikeRatio > PARAM_ATR_SPIKE_REJECT` | `MASLOPE_ATR_SPIKE` |

注：`STALE_AGG_TRADE` 在 K 线通道（回测/独立运行）里 `qualityFlags` 为空，**永不触发**；保留是生产 forecast 通道的兜底。

### 4.2 不再使用 4 mode × 3 quality 表

删除：
- `EntryMode` 枚举的所有值
- `EntryQualityMode` 枚举的所有值
- `pullbackReclaimSupports / macdEarlySupports / earlyCandidateDirection / m3TrendContinuationQuality` 等辅助函数（追势用的）

保留辅助函数：
- `EntryStrategySupport.macdSupports / closeTrendSupports / priceAboveEmaSupports`（基础几何判断）
- `klineSupports`（K 线方向支持的综合判断）

## 5. 出场设计（重写 MaSlopeExitPlaybook）

### 5.1 新的优先级顺序

| 优先级 | 触发器 | 触发条件 | 性质 |
|---|---|---|---|
| 1 | **MACD_REVERSAL_CROSS** | 持仓方向反向的 `macd_cross` 刚发生 | 🆕 急刹车（独立强信号） |
| 2 | **SIGNAL_REVERSE** | `FailureScore.score() >= PARAM_EXIT_SCORE` | 🆕 信号衰减主出场 |
| 3 | MACD_NEAR_REVERSAL | `|DIF - DEA| / atrClosed < PARAM_NEAR_DEA` AND DIF/DEA 还在持仓同侧 | 🆕 收紧 SL 到 entry（保本提前） |
| 4 | BREAKEVEN | `profitR >= PARAM_BE_R` → SL 上移到 `entry + PARAM_BE_LOCK_R × R` | ✅ 保留 |
| 5 | PARTIAL_TP | `profitR >= PARAM_PARTIAL_R` → 部分平仓 `PARAM_PARTIAL_RATIO` | ✅ 保留 |
| 6 | TRAIL | `profitR >= PARAM_TRAIL_R` → ATR × `PARAM_TRAIL_MULT` trailing stop | ✅ 保留 |
| 7 | SL | 价格触及硬止损 | ✅ 保留（兜底防失效） |

### 5.2 MACD_NEAR_REVERSAL 的精确动作

触发条件细化：
- LONG 持仓：`DIF > DEA` AND `(DIF - DEA) / atrClosed < PARAM_NEAR_DEA`（接近但还未死叉）
- SHORT 持仓：`DIF < DEA` AND `(DEA - DIF) / atrClosed < PARAM_NEAR_DEA`

触发后的动作：
- 计算 `targetSL = entryPrice`（保本位）
- LONG：`newSL = max(currentSL, targetSL)`（SL 只能往上抬，不能下移）
- SHORT：`newSL = min(currentSL, targetSL)`（SL 只能往下移，不能上抬）
- 调用 `ExitPlaybookDecision.moveStop(newSL, "MA_SLOPE_MACD_NEAR_REVERSAL")`

不平仓，只调 SL。后续如果 MACD 真的反向 cross，由优先级 1 的 MACD_REVERSAL_CROSS 平掉。

### 5.3 全部删除的现有触发器

| 删除 | 原因 |
|---|---|
| EARLY_KILL（R+Score 联合） | 抢占了 SIGNAL_REVERSE。改为纯 SIGNAL_REVERSE。 |
| FAST_FAIL_DEEP（R+Score） | 同上 |
| FAST_FAIL_SHALLOW（R+Score） | 同上 |
| NO_PROGRESS_60M | 改用 SIGNAL_REVERSE 触发 |
| NO_0_5R_IN_90M | 改用 SIGNAL_REVERSE 触发 |
| EXTINGUISH | 改用 SIGNAL_REVERSE 触发 |

### 5.4 出场逻辑的核心理念

**不在乎当前 profitR 是多少。只要支撑入场的信号反向，立即出场**。R 倍数只用于：
- 资金保护（BREAKEVEN/SL）
- 收益管理（PARTIAL/TRAIL）

不用于"决定何时认错"。"认错"由 FailureScore 和 MACD 反向决定。

## 6. 震荡市检测（新增 ChoppyMarketDetector）

### 6.1 5 维度评分

`ChoppyMarketDetector.score(MarketContext ctx)` 返回 `int`，范围 0-5。每个维度满足触发条件则 +1 分。

| 维度 | 物理含义 | 触发条件 | 数据来源 |
|---|---|---|---|
| 1 | MA7-MA25 spread 反复翻转 | 过去 `PARAM_CHOP_LOOKBACK` 根内 spread 符号翻转次数 ≥ `PARAM_CHOP_SPREAD_FLIPS` | `ma7SeriesClosed`, `ma25SeriesClosed` |
| 2 | 价格效率比低 | `net / path < PARAM_CHOP_ER`，其中 net = `|close[last] - close[first]|`, path = `Σ|close[i] - close[i-1]|` | `closeSeriesClosed` |
| 3 | ADX 弱 | `adx < PARAM_CHOP_ADX` | `ctx.adx` |
| 4 | 布林带宽窄 | `bollBandwidth < PARAM_CHOP_BBW` | `ctx.bollBandwidth` |
| 5 | 成交量平淡 | `avg(volumeRatioClosedSeries) < PARAM_CHOP_VOL` | `ctx.volumeRatioClosedSeries` |

### 6.2 设计依据（避免过拟合）

每个维度都基于**普适市场理论**，不依赖任何具体案例：

- 维度 1：均线纠缠是震荡的标准定义（趋势市均线必定单边）
- 维度 2：**Efficiency Ratio**（Kaufman 1995, *Smarter Trading*）。趋势市 ER 大，震荡市 ER 小。理论根基扎实。
- 维度 3：ADX 设计就是衡量趋势强度，低 ADX 即弱趋势。Wilder 1978 经典指标。
- 维度 4：布林带宽收窄是震荡前奏（标准技术分析）
- 维度 5：缺乏成交量共识 = 无方向力量

### 6.3 阈值标定的方法

具体每个 `PARAM_CHOP_*` 不在 brainstorming 拍。标定方法：

**Step 1**: 拉 6 个月历史数据（ETH 3m + BTC 3m），用 ADX 分段：
- ADX > 25 持续 ≥ 30 根 = 标注为"趋势市"样本
- ADX < 20 持续 ≥ 30 根 = 标注为"震荡市"样本

**Step 2**: 在标注样本上算每个维度的分布：
- 趋势市：ER 分布、spread 翻转次数分布、BBW 分布、volRatio 分布
- 震荡市：同上

**Step 3**: 找每个维度的"分割点"（最大化两类分布的可分性，如 Fisher 准则）。

**Step 4**: 总分阈值 `PARAM_CHOP_SCORE`：在标注样本上跑 ROC，找 F1 最大点。

输出：每个 PARAM_CHOP_* 都有"数据来源 + 区分度"的支撑，不是凭直觉拍。

## 7. 参数清单（完整 PARAM 表）

| PARAM | 出现位置 | 物理含义 | 数据驱动定值方法 |
|---|---|---|---|
| `PARAM_MACD_CROSS_LOOKBACK` | 入场 4.1.1 #1 | "刚金叉"的时间窗（K 线数） | 历史 cross 后 1-5 根 K 线内入场的胜率分布对比 |
| `PARAM_MA7_SLOPE_MIN` | 入场 4.1.1 #2 | MA7 翻正后的最小斜率 | 历史 MA7 斜率分布的 P50-P70 |
| `PARAM_CHOP_SCORE` | 入场 4.1.1 #4 | 震荡总分拒入场阈值 | 见 6.3 Step 4 |
| `PARAM_VOL_CONFIRM` | 入场 4.1.2 加分 | 量能放大触发加分 | 历史 volumeRatio 的 P60 |
| `PARAM_ATR_SPIKE_REJECT` | 入场 4.1.3 拒绝 | ATR 突增拒入场（防异常波动） | 现 MaSlope 的 2.0，保留作起点 |
| `PARAM_EXIT_SCORE` | 出场 5.1 #2 | FailureScore 出场阈值 | 多窗口网格：试 2/3/4，找 PF 最大且稳定的 |
| `PARAM_NEAR_DEA` | 出场 5.1 #3 | DIF 接近 DEA 的 ATR 比例 | 历史 cross 前 5 根 K 线的 \|DIF-DEA\|/ATR 分布 P75 |
| `PARAM_BE_R` | 出场 5.1 #4 | 触发保本的 R 倍数 | 沿用现 0.5R 作起点，敏感性测试 0.3-0.7 |
| `PARAM_BE_LOCK_R` | 出场 5.1 #4 | 保本时锁定的 R 倍数 | 沿用现 0.1R 作起点 |
| `PARAM_PARTIAL_R` | 出场 5.1 #5 | 部分止盈的 R 倍数 | 沿用现 3.0R 作起点 |
| `PARAM_PARTIAL_RATIO` | 出场 5.1 #5 | 部分止盈比例 | 沿用现 0.3 作起点 |
| `PARAM_TRAIL_R` | 出场 5.1 #6 | 触发 trailing 的 R 倍数 | 沿用现 2.0R 作起点 |
| `PARAM_TRAIL_MULT` | 出场 5.1 #6 | trailing 距离的 ATR 倍数 | 沿用现 2.0 作起点 |
| `PARAM_CHOP_LOOKBACK` | 震荡 维度 1 | 检测窗口（K 线数） | 30 根 = 90 分钟 3m，作起点 |
| `PARAM_CHOP_SPREAD_FLIPS` | 震荡 维度 1 | spread 翻转次数阈值 | 见 6.3 |
| `PARAM_CHOP_ER` | 震荡 维度 2 | Efficiency Ratio 阈值 | 见 6.3 |
| `PARAM_CHOP_ADX` | 震荡 维度 3 | ADX 弱阈值 | 业内标准 20 作起点 |
| `PARAM_CHOP_BBW` | 震荡 维度 4 | 布林带宽阈值 | 见 6.3 |
| `PARAM_CHOP_VOL` | 震荡 维度 5 | 量能平淡阈值 | 见 6.3 |

**所有 PARAM 都以配置文件形式注入**，不写死在代码里。配置类（如 `MaSlopeRefactorConfig`）持有所有 PARAM，便于网格搜索。

## 8. 实施计划

### 8.1 分阶段实施

**阶段 A：基础设施**
1. 扩展 `MaSlopeStateClassifier`：`MaState` record 增加 `ma25PrevSlopeAtr()`，用同一回归窗口往前推一根计算
2. 扩展 `CryptoIndicatorCalculator.calcAll`：增加 `macd_dif_series_closed` 和 `macd_dea_series_closed` 输出（用于历史 cross 检测）
3. 扩展 `MarketContext.fromKlineIndicators` 和 `parse`：接收上面两个新序列字段
4. 新建 `ChoppyMarketDetector`（`maslope/` 目录），实现 5 维度评分
5. 新建 `MaSlopeRefactorConfig`，集中所有 PARAM 默认值
6. 单元测试：`ChoppyMarketDetector` 在构造样本（趋势 vs 震荡 vs 边界）上的输出

**阶段 B：入场重写**
1. 新版 `MaSlopeEntryStrategy.build`：单一拐点 confluence
2. 单元测试：在构造样本（拐点 vs 追势 vs 震荡）上的入场决策
3. 接口/调用方不变

**阶段 C：出场重写**
1. 新版 `MaSlopeExitPlaybook.evaluate`：优先级重排，删除 EARLY_KILL/FAST_FAIL/EXTINGUISH/NO_PROGRESS_60M/NO_0_5R_IN_90M，新增 MACD_REVERSAL_CROSS / SIGNAL_REVERSE / MACD_NEAR_REVERSAL
2. 单元测试：每个触发器的边界 case

**阶段 D：回归测试**
1. 跑原有 `MaSlopeKlineBacktestIT`，确认不崩
2. 跑 1 个 60d 窗口，初步看绩效形状（不调参数，用默认值）

**阶段 E：参数标定（Task #18）**
1. 拉 6 个月数据，按 6.3 流程标定 PARAM_CHOP_*
2. 网格搜索：PARAM_EXIT_SCORE (2/3/4) × PARAM_MACD_CROSS_LOOKBACK (1/2/3) × PARAM_CHOP_SCORE (2/3/4)
3. 用 5-8 个不重叠 60d 窗口取每组参数的平均绩效
4. 选最稳定的参数组（不是最高 PF，是 PF 方差最小的）

**阶段 F：敏感性测试（Task #19）**
1. 最优参数组每个 PARAM ±20% 测试
2. OOS 验证：标定数据和验证数据时间隔离
3. 不同币种验证（BTC vs ETH）

**阶段 G：交付（Task #20）**
1. 把全部多窗口绩效 + 参数稳定性 + 关键 trade 拆解，整理成报告
2. 用户判断是否上线，或回头调结构

### 8.2 不动的部分（关键约束）

- `EntryStrategy` 接口不变
- `ExitPlaybook` 接口不变
- `MarketContext` 字段不增不减
- `MaSlopeStateClassifier` 不动
- `MaSlopeFailureEvaluator` 不动（除非阶段 F 发现需要调单维度阈值）
- 回测引擎 `MaSlopeKlineBacktestEngine` 不动
- Risk sizing / position sizing 不动

向后兼容是铁律。

## 9. 风险与开放问题

### 9.1 已知风险

| 风险 | 缓解 |
|---|---|
| **拐点入场频率低**：confluence 4 条全部满足比追势严格，入场数可能从 14 降到 3-5 笔 | 接受。质量优先于数量，宁可少做不做错。 |
| **MACD 急刹车频繁触发**：3m 上 MACD cross 频繁，可能造成"刚入场就出场" | 用 PARAM_MACD_CROSS_LOOKBACK 入场 + 急刹车出场之间的时间间隔最小化（如要求入场后 ≥ 2 根 K 线才检查急刹车） |
| **震荡评分误判**：把弱趋势误判为震荡 | 5.维度 + 多数票（≥3/5）有冗余度，单维度误判不致命 |
| **参数依赖标定数据的代表性**：如果 6 个月数据本身偏向某种 regime | 标定数据要包含至少 1 段牛市 + 1 段熊市 + 1 段震荡 |

### 9.2 开放问题（实现阶段决定）

- **MACD 急刹车的"冷却期"**：入场后 N 根 K 线内不触发急刹车？N 是 0 还是 2？留到回测看
- **SIGNAL_REVERSE 的 streak 要求**：要求连续 K 线满足，还是单次满足？现 EXTINGUISH 是连续 2 次，建议起点也用 2
- **震荡评分的归零条件**：进入震荡 → 拒入场，但什么条件下"震荡结束可以恢复"？建议用同一评分，分数 ≤ 1 视为震荡结束
- **是否要给 ChoppyMarketDetector 出场端用法**：持仓时如果进入震荡 → 收紧 SL？保留可能性但默认关闭

## 10. 成功标准回顾

实现完成后，必须同时满足：

1. ✅ 5-8 个不重叠 60d 窗口，**全部** PF ≥ 1.5
2. ✅ 任意 60d 窗口最大回撤 ≤ 30%
3. ✅ IS/OOS 窗口胜率差 ≤ 10%
4. ✅ 每个 PARAM ±20% 后，PF 变化 ≤ 20%
5. ✅ BTC 和 ETH 同一参数组都不爆仓

任一不满足 → 回头调结构（不是调参数）。

---

**End of spec.**
