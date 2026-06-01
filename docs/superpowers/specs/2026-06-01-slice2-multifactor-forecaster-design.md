# 6/12/24h 多因子方向预测器 + 测量（量化重构 Slice 2）

- 日期: 2026-06-01
- 作者: mawai
- 状态: 设计草案，待评审
- 关联: 「量化分析+策略 agent trader 重构」第 2 刀；上接 Slice 1（预测评估框架/测量尺，见 `2026-05-29-forecast-eval-harness-design.md`）

## 0. 这是什么 / 在整体重构中的位置

承接 Slice 1 建好的"测量尺"。Slice 1 只接了一个故意简单的 EWMA 动量基线立及格线。Slice 2 在**同一把尺子**下，新增一个**真·多因子 6/12/24h 方向预测器**（K线趋势 + 资金费 + 恐惧贪婪），做样本外评估，回答一个问题：在 EWMA 基线之上，叠加链下信号（持仓/情绪）到底有没有带来**风险调整后的方向 edge**。

**范围重定（重要）**：路线图原把 Slice 2 写作"重定向 live 因子图到 6/12/24h"。评审决定改走 **measure-first**：

- 先在 research 包内造**可低成本回测**的 6/12/24h forecaster，用尺子量出 edge；**全程不碰 live**。
- "切 live 多智能体图"顺延到测量证明有 edge 之后（记为 **Slice 2b** / 后续刀）。
- 理由：①"先量后信"是本重构的核心哲学；②live 那套 LLM agent + 实时特征（订单流/盘口/爆仓/期权 IV）**无法低成本、可复现地历史回测**（成千上万次 LLM 调用 + 实时特征不可从历史 K线重建）。

更新后的路线图（建议回头给 Slice 1 spec §0 补一笔）：**Slice 2(本刀,measure) → Slice 2b(切 live) → Slice 3(新闻+链上) → Slice 4(执行层)**。

## 1. 背景 / 现状关联

- Slice 1 产出（`agent.research` 包，25 单测绿）：`KlineHistoryStore`(1m 落库可复现)、`KlineAggregator`、`TripleBarrierLabeler`、`WalkForwardEvaluator`(滚动原点+purge+embargo)、`RiskAdjustedMetrics`(年化 Sharpe/Calmar/MaxDD)、`BenchmarkCalculator`(buy&hold + 随机排列分位)、`Forecaster` 接口 + `EwmaMomentumForecaster` 基线、`ResearchEvalService` 编排、`EvalReport`、`ResearchEvalController`。
- 现 `Forecaster.forecast(List<KlineBar>)` 只吃价格历史；尺子按方向口径模拟收益（`posReturns = raw·direction`）。
- live 多智能体图（`QuantForecastWorkflow`/`QuantGraphFactory`，8 节点、5 因子 agent、`FeatureSnapshot`、`HorizonForecast` 现 0_10/10_20/20_30）——**本刀绝不触碰**。

## 2. 本刀目标（一句话）

在 Slice 1 的尺子下，造一个**真·多因子 6/12/24h 方向预测器**（趋势 + 资金费 + F&G，等权正交），样本外回答它能否风险调整后跑赢 EWMA 基线 / buy&hold / naive；产出**四线同框**的对比报告。

## 3. 关键设计决策（评审已定）

| # | 决策 | 取舍依据 |
|---|---|---|
| D1 | **measure-first、增量为主，不碰 live** | 先验证后提交；live LLM+实时图不可低成本回测；向后兼容是默认标准（对自家 research 代码可大刀阔斧，对 live 谨慎） |
| D2 | 预测**方向**（不加波动率刻度），复用尺子 | 收敛工作量；先答"现有因子在 6/12/24h 有没有方向 edge"；跑不赢即转波动率的实证依据 |
| D3 | 特征分阶段：K线 + **资金费 + F&G** | 唯二历史够深(年级)、能对齐 180天+样本外的链下序列；OI/taker/大户 `/futures/data/*` 仅 ~30 天 → 埋钩子、留前向 |
| D4 | **升级 `Forecaster` 接口** 为 `forecast(ResearchFeatures)` | 单接口最干净，纯价格预测器照用（ctx 含 klines）；避免尺子分叉；对自家 research 代码的必要破坏，直接改干净 |
| D5 | 多因子 = **趋势(多均线排列 ma_alignment) + 资金费 + F&G 等权正交** 合成 | 价格类指标共线→堆指标是伪多样，故只取 1 个趋势指标；趋势腿用 `calcAll` 的 `ma_alignment`（多均线排列，比裸 EWMA 符号稳健、震荡自动 FLAT），并使 `IndicatorAdapter` 物尽其用；真多样来自不同信息源(off-chain)；不网格寻优权重(spec 反旋钮)。〔实施修订：趋势腿由初版"复用 EWMA"改为 ma_alignment；A/B 遂为"两个独立预测器比强弱"，而非纯隔离 off-chain 增量〕 |
| D6 | 报告泛化为**多策略同框**（基准算一次、每策略一行） | apples-to-apples、单一"赢没赢"判定、满足四线同框验收 |

## 4. 约束

- **不碰 live 生产类**（沿用 Slice 1 §5.4 清单：`AiTradingScheduler`、`QuantForecastWorkflow`/图/节点/因子 agent、`FeatureSnapshot`、`HorizonForecast`、`DeterministicTradingExecutor`、各 strategy/playbook 等）。
- **只读/纯函数复用**：`CryptoIndicatorCalculator.calcAll`（`KlineBar`→`BigDecimal[]{h,l,c,v}` 适配后调用，零侵入；需 ≥30 根）；`BinanceRestClient` 回填接口。
- 对**自家 research 包**（Slice 1 一周前所写）可破坏性修改：升级 `Forecaster` 接口、泛化 `EvalReport`/`ResearchEvalService`、更新基线及其单测——必要就改干净，不绕（见 D4）。
- 标的 BTCUSDT、ETHUSDT；统计用原生 double/BigDecimal，不引新依赖。
- 资金费 wrapper `getFundingRateHistory` 现状写死 `limit≤100`、无 endTime（仅 ~33 天）→ 加 endTime 翻页**新重载**（additive，不改原方法及其调用点）。

## 5. 设计方案

### 5.1 模块总览（全在 `agent.research`）

| 模块 | 新建/改 | 职责 |
|---|---|---|
| `series/SeriesCode`(枚举 FUNDING / FEAR_GREED；OI/TAKER/TOP_TRADER 预留) | 新建 | 链下序列类型；预留位=未来前向 eval 钩子 |
| 表 `market_series_history(symbol, series_code, ts, value)` + `KlineHistory` 式实体 + Mapper | 新建（追加 `sql/init.sql` 末尾） | 通用单表落库链下时点序列；幂等批插（同 kline_history）；F&G 用 symbol='GLOBAL' |
| `series/MarketSeriesStore`(@Service) | 新建 | 回填（资金费 endTime 翻页 / F&G 一次拉全）/ 加载（按 ts 升序） |
| `series/SeriesAligner` | 新建（纯函数） | as-of join：给定决策点 ts，取序列中 **ts ≤ 决策点的最近值**（无 lookahead）；无数据返回中性默认 |
| `forecast/ResearchFeatures`(record) | 新建 | 升级后的 `Forecaster` 入参：`{ List<KlineBar> barsUpToNow, double fundingRate, int fearGreed }` |
| `forecast/IndicatorAdapter` | 新建（纯函数） | `List<KlineBar>` → `List<BigDecimal[]>{h,l,c,v}` → `calcAll(…, lastClosed=true)`；<30 bar 返回空 Map |
| `forecast/MultiFactorForecaster implements Forecaster` | 新建 | 趋势腿(`ma_alignment` via `IndicatorAdapter`) + 资金费腿 + F&G 腿，各标准化到 [-1,1] 等权 → s；`dir=sign(s)` 带 deadband ε，`conf=min(1,\|s\|)` |
| `forecast/Forecaster`（接口）+ `EwmaMomentumForecaster`（基线）+ 其测试 | **改** | `forecast(List<KlineBar>)` → `forecast(ResearchFeatures)`；基线取 `ctx.barsUpToNow()`，逻辑不变 |
| `eval/StrategyLine`(record) | 新建 | 单策略一行：`{name, RiskAdjustedMetrics, strategyReturn, beatBuyAndHold, naivePercentile, beatNaive, ReturnSeries}` |
| `eval/ComparisonReport`(record) | 新建（取代单策略 `EvalReport` 作顶层产出） | `{symbol, horizonHours, totalHbars, testPoints, buyAndHoldReturn, List<StrategyLine>}` + `toJson()`/`summary()`（四线同框） |
| `eval/ResearchEvalService` | **改** | `evaluate(symbol,horizon,window, List<Forecaster>)`；`evaluateBars` 逐点装配 `ResearchFeatures`、**基准算一次**、每预测器产出一条 `StrategyLine` |
| `controller/ResearchEvalController` | **改** | `/run` 端点同时跑 `[EwmaMomentum, MultiFactor]` 返回 `ComparisonReport`；新增 `/backfill-series` 端点 |

> 注：Slice 1 的单策略 `EvalReport` 的字段并入 `StrategyLine`，顶层换成 `ComparisonReport`；`RiskAdjustedMetrics`/`BenchmarkCalculator`/`WalkForwardEvaluator`/`ReturnSeries` 原样复用。

### 5.2 数据流（评估一次）

```
回填: BinanceRestClient → KlineHistoryStore(1m)  +  MarketSeriesStore(funding endTime 翻页 / F&G 拉全)
评估:
  load 1m → KlineAggregator 聚合到 H(6/12/24)
  对每个决策点 i：
    barsUpToNow = hbars[0..i]                        // point-in-time，绝不含未来
    fundingRate = SeriesAligner.asOf(FUNDING, t_i)   // ts ≤ t_i 最近值，无 lookahead
    fearGreed   = SeriesAligner.asOf(FEAR_GREED, t_i)
    ResearchFeatures → 每个 Forecaster.forecast → Forecast(dir,conf)
  WalkForwardEvaluator(滚动原点 + purge + embargo) 取样本外 test 块
  每预测器：模拟收益序列 → RiskAdjustedMetrics → StrategyLine
  基准算一次：buy&hold + 随机排列分位
  → ComparisonReport（多因子 / EWMA基线 / buy&hold / naive 四线）→ target/research-eval JSON
```

### 5.3 多因子合成细节（D5，反过拟合）

- **趋势腿**：取 `IndicatorAdapter` → `calcAll` 的 `ma_alignment`（MA7/MA25/MA99 多均线排列，样本<99 降级 MA7/MA25），输出 ∈ {−1,0,+1}；趋势明确才出方向、震荡给 0。〔实施修订：初版拟复用 EWMA 符号，改为 ma_alignment 使趋势腿更稳健、`IndicatorAdapter` 物尽其用〕
- **资金费腿**：极端正→拥挤多→反向偏空；极端负→反向偏多。标准化 `-tanh(funding / scale)` → [-1,1]（正=偏多）；`scale` 固定默认 ≈ 0.0005（/8h），不调优。
- **F&G 腿**：极贪→偏空、极恐→偏多。标准化 `(50 − fng) / 50` → [-1,1]（正=恐惧时偏多）。
- **合成**：`s = mean(trend, fundingLeg, fgLeg)`（等权）；`dir = s>ε ? +1 : s<−ε ? −1 : 0`；`conf = min(1, |s|)`。
- **无网格寻优**：三腿等权、ε/scale 取固定默认值（`MultiFactorForecaster` 自身常量 `DEFAULT_EPSILON=0.1` / `DEFAULT_FUNDING_SCALE=0.0005`——属预测器参数而非评估参数，故不放 `EvalParams`），不做参数搜索（呼应 spec 反旋钮）。

### 5.4 接口升级与向后兼容处理（D4）

- `Forecaster.forecast(List<KlineBar>)` → `forecast(ResearchFeatures)`；`ResearchFeatures` 含 `barsUpToNow()`，纯价格预测器（基线）改一行即可。
- 同步更新 `EwmaMomentumForecasterTest`（用 `ResearchFeatures` 包 bars 构造入参）。
- 单策略 `EvalReport` → `ComparisonReport` + `StrategyLine`；`ResearchEvalServiceTest` 随之更新断言。
- 以上全是对**自家 research 代码**的修改，不影响任何 live 类。

## 6. 验收标准

1. 一份 `ComparisonReport` 同框含**四线**：多因子 / EWMA基线 / buy&hold / naive（样本外、风险调整：年化 Sharpe/Calmar/MaxDD/命中率 + vs buy&hold 超额 + naive 分位），并给出"多因子是否跑赢基线"判定。
2. `SeriesAligner` 有单测证明 **as-of 无未来泄漏**（决策点取到的 funding/fng 时间戳严格 ≤ 决策点）。
3. `MultiFactorForecaster` 有单测：合成趋势 + 一致 off-chain → 方向符合预期；<30 bar 暖机返回 FLAT。
4. 资金费回填 endTime 翻页正确（能取 ≥180 天）；同窗重跑报告**可复现**。
5. 全程未改任何 live 生产类（git diff 仅 research 包 + `market_series_history` 表/实体/Mapper + 测试 + 本 spec + 资金费 wrapper 的 additive 重载）。
6. 诚实结果：多因子**可能仍跑不赢 buy&hold**——若如此，"叠加 off-chain 未带来方向 edge"本身就是转向波动率(后续)的实证结论；报告须让四线肉眼可比。

## 7. 不在本刀 / 后续

- 切 live 多智能体图到 6/12/24h（改 `FeatureSnapshot`/因子 agent/`HorizonForecast`）→ **Slice 2b**（测出 edge 后）。
- 波动率/风险预测刻度（给尺子加 QLIKE/MSE vs 已实现波动率）→ 后续。
- OI / taker / 大户落库（仅 ~30 天历史）→ 钩子留（`SeriesCode` 预留位），将来用于前向/live eval。
- 新闻源更换 + 链上数据 → Slice 3；执行层 150 常数 → Slice 4。

## 8. 实施任务粒度（供 writing-plans 参考）

1. `market_series_history` 表 + 实体 + Mapper（幂等批插）+ `SeriesCode` + `MarketSeriesStore`（资金费 endTime 翻页 / F&G 拉全 / 加载）；解析单测。
2. `SeriesAligner` as-of join + 单测（证明无 lookahead、缺口取中性）。
3. **升级 `Forecaster` 接口** → `ResearchFeatures`；改 `EwmaMomentumForecaster` + 更新其单测（绿）。
4. `IndicatorAdapter`（`KlineBar`→`calcAll`）+ 对拍单测。
5. `MultiFactorForecaster`（趋势 `ma_alignment` + 资金费 + F&G 等权）+ 单测（方向 / off-chain 逆转 / 暖机 FLAT）。
6. 报告泛化：`StrategyLine` + `ComparisonReport` + `ResearchEvalService.evaluate(List<Forecaster>)` + 改 `evaluateBars` 装配 `ResearchFeatures` + 更新 `ResearchEvalServiceTest`（多策略端到端纯核心）。
7. `ResearchEvalController`：`/run` 跑 `[基线,多因子]` 出 `ComparisonReport` + `/backfill-series` 端点；资金费 wrapper endTime 重载。
8. 端到端：回填 K线+资金费+F&G，跑 BTC/ETH × {6,12,24}h 出四线同框报告（需实环境 DB + Binance，同 Slice1 Task 8）。

每步独立验收、独立提交（纯单测先行 TDD）。

## 9. 参考来源

沿用 Slice 1 spec §9（FPP / GARCH / López de Prado / 回测过拟合）。本刀新增方法论点：

- 资金费率作为持仓拥挤度/成本信号、极端值均值回归 —— 业界常识（perp funding 套利/拥挤度）。
- F&G 极端值反向（contrarian at extremes）—— alternative.me 指数常见用法。
- 因子共线性 → 避免伪多样性、用正交信息源 —— 组合/因子投资常识（与 spec §3 [S9] Deflated Sharpe 对"多重检验/选择偏差"的警惕同源）。
