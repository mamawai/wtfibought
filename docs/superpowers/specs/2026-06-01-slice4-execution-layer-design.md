# 执行层精简 + 顺势/均值回归厘清（量化重构 Slice 4）设计

- 日期: 2026-06-01
- 作者: 马鸣阳 / Claude
- 状态: 设计草案，等用户 review（**本刀只设计、不改代码**）
- 关联: 「量化分析+策略 agent trader 重构」第 4 刀。路线图：Slice 2(measure) → Slice 2b(切 live) → Slice 3(链上,已完成) → **Slice 4(执行层)**。上游测量尺见 `2026-05-29-forecast-eval-harness-design.md`；A/B 回测方法论见 `2026-05-27-maslope-refactor-design.md`。

---

## 0. 本刀定位与特殊性（必读）

Slice 1–3 全程**不碰 live 生产类**，在 `agent.research` 包内 measure-first。**Slice 4 是头一刀直接改 live 执行层**（`agent.trading.*`，管真实仓位），性质完全不同，三条铁律：

1. **数据驱动，不拍脑袋**：执行层每个旋钮都按现场盈亏调过，主观"我觉得更好"不算数。沿用 maslope spec 口径——**以回测数据为准**，A/B 验证不劣化才 go。
2. **feature-flag 灰度**：所有行为变化挂 toggle，baseline（现状）与 candidate（精简后）互斥可切，绝不一次性替换。
3. **依赖回测能力**：本刀的行为级改动**必须能回测**才能验证；而回测需要 K 线底座（当前 Binance 拉不到，见 [[binance-unreachable-onchain-sources-ok]]）+ 一个**口径诚实**的回测引擎。后者是个真问题（见 §3.D）。

> **Sequencing 提醒**：路线图里 Slice 2b（把 live 多智能体图切到 6/12/24h）排在 Slice 4 之前，且 2b 被定为"测出 edge 再做"，目前未做。本刀的 §3.A（可观测性收口）零行为变化、零回测依赖，**现在就能落**；§3.B/C（regime 路由 + 旋钮精简）是行为变化，**须等回测能力就绪**，建议排在 2b 之后或与之并行。

---

## 1. 背景：执行层现状与三大问题

执行层 = **入场 4 策略 + sizing + 出场（双引擎）**，全在 `agent.trading.*`。`DeterministicTradingExecutor` 顶层驱动"退出→开仓"周期。

```
开仓: EntryDecisionEngine
      ├─ 4 策略并行出候选: BreakoutEntryStrategy / TrendContinuationEntryStrategy(顺势)
      │                    MeanReversionEntryStrategy(均值回归) / MaSlopeEntryStrategy
      ├─ 二层共振: BreakoutConfluenceGate / MeanReversionConfluenceGate / TrendConfluenceGate
      ├─ FLAT 覆盖 + MaSlope 质量过滤
      ├─ 加权择优: selectionScore = score × pathWeight(0.7~1.0)
      └─ EntryRiskSizingService: 费率/RR/保证金/回撤/低波动/强平缓冲 → 下单, ExitPlan 锁定
出场: PlaybookExitEngine(新, 按 ExitPlan.path 走 4 playbook)  ←toggle→  ExitDecisionEngine(@Deprecated legacy, 信号/风险票)
```

### 问题 1：旋钮爆炸（"精简"的对象）

`entry`+`exit` 包内 **121 个 `static final` 常数**（spec 说的 ~150 还含 confluence gate / executor / 内联裸数字）。分布：

| 文件 | 命名常数 | 角色 |
|---|---|---|
| `MaSlopeExitPlaybook` | 21 | MaSlope 出场触发 |
| `MaSlopeEntryStrategy` | 17 | MaSlope 入场（拐点/MACD reclaim） |
| `EntryRiskSizingService` | 16 | 费率/RR/保证金/回撤/低波动/强平 |
| `MeanReversionEntryStrategy` | 12 | BB%B/RSI 阈值、强弱档 |
| `ExitDecisionEngine`(@Deprecated) | 10 | legacy 风险票/时间/进度阈值 |
| `BreakoutExitPlaybook` | 10 | 突破出场 |
| `TrendExitPlaybook` / `BreakoutEntryStrategy` | 8 / 8 | — |
| `MeanReversionExitPlaybook` | 7 | — |
| `TrendContinuationEntryStrategy` / `EntryDecisionEngine` | 5 / 5 | 策略权重/路径优先级/风险缩放 |

**更糟的是大量内联裸数字**（没进常量）：趋势打分加性权重 `+1.2/+1.0/+0.7…`、`isRsiTrendHealthy` 的 `42/58/78/22`、量能 `1.2`、legacy 出场各 path 时间 `45/90/60/120`、trail `0.35/0.45/0.10`、reversal 置信度 `0.82/0.65`…真实旋钮数远超 121，且**散落看不全**。

### 问题 2：顺势 vs 均值回归纠缠（"厘清"的对象）

4 策略**每根 bar 并行全跑**，各用**互不可比**的打分：

| 策略 | 打分基 | MIN_SCORE | 哲学 |
|---|---|---|---|
| TrendContinuation | `conf×3 + 加性命中(+1.2/+1.0/…)` | 4.2 | 顺势 |
| Breakout | `conf×2.6 + squeeze/量能/BB距…` | 4.8 | 顺势/突破 |
| MeanReversion | `conf×3 + pbExtreme + rsiStretch + votes` | 3.6 | **逆势**回归 |
| MaSlope | 自治（拐点/MACD reclaim） | — | 顺势 |

然后用 `score × 手调路径权重`（Breakout 1.0 / MR 0.9 / MaSlope 0.85 / Trend 0.7）+ 同分 pathPriority 硬比择优。**问题本质**：① 三套打分尺度不可比，权重是"凑出来"的；② **没有顶层 regime 路由**决定"现在到底该顺势还是该回归"——regime 信息（`ctx.regime`=SHOCK/SQUEEZE/RANGE…）只在各策略内部当零碎加减分用，不做族选择。结果就是顺势单和回归单在同一根 bar 抢入场，靠脆弱权重裁决 → 这正是 maslope spec 实测的"某 regime 灵、某 regime 灾难"的根因。

### 问题 3：双出场引擎冗余

`PlaybookExitEngine`（新，按入场锁定 path 走剧本）与 `ExitDecisionEngine`（`@Deprecated`，信号/风险票全平/平半/收紧）**并存**，toggle 切换。两套出场哲学、两套常数，维护面翻倍。

---

## 2. 目标

在**不破坏账户**前提下，让执行层从"150 个手调旋钮 + 4 策略权重硬比"变成"**少而显式的旋钮 + regime 路由 + 单一出场引擎**"，且每步行为变化都有 A/B 回测背书。

硬目标（沿用 maslope spec §2.2 口径，A/B 同窗对比 baseline）：
- 旋钮数（命名常数 + 内联魔法数）**显著下降**且全部集中可见（可量化：精简后 vs 现状的计数）。
- 顺势/均值回归由**显式 regime 路由**决定，不再并行权重硬比。
- 删除冗余的 legacy 出场引擎（或反过来：实测 legacy 更优则删 playbook——数据说话）。
- 8 窗口 × A/B：candidate 的 `finalEquity ≥ baseline`、`MaxDD ≤ baseline+5pp`、`PF ≥ baseline×0.9`，全窗达成才 go。

---

## 3. 设计

### 3.A 可观测性收口（零行为变化，零回测依赖，**现在可做**）

精简的前提是先把 150 旋钮"一眼看全"。**不改任何数值/逻辑**，只做结构整理：

1. 把散落各类的命名常数 + 内联裸数字，按 **{入场|出场} × {顺势族|回归族|突破|sizing|risk|legacy}** 抽进显式分组的 `ExecutionParams`（或每族一个 record），**值原样搬**。
2. 每个旋钮补一行注释：它管什么、当前值、可疑/冗余标记（如"与 X 重复"、"legacy 专用"）。
3. 产出一张**旋钮总账**（spec 附录或一个只读类），后续精简对照它逐项决策。

> 这步是行为保全的（编译期等价），可独立提交、独立验证（现有单测 + 编译），**不需回测**。是 §3.B/C 的地基。

### 3.B regime 路由：厘清顺势 vs 均值回归（行为变化，需回测）

引入顶层 `RegimeRouter`：**先判 regime，再路由到该 regime 的策略族**，取代"4 策略并行 + 权重硬比"。

| regime（`ctx.regime`/`regimeTransition`） | 路由到 | 理由 |
|---|---|---|
| 方向性趋势（DIRECTIONAL/TREND） | 顺势族：Breakout / TrendContinuation / MaSlope | 趋势里逆势回归是送死 |
| 震荡（RANGE） | 回归族：MeanReversion（+ squeeze 突破预备） | 震荡里追趋势反复打脸 |
| 挤压（SQUEEZE） | 突破偏向：Breakout 优先 | 释放方向未知，等突破 |
| 冲击（SHOCK） | 缩量/观望（仅高置信小仓） | 已有逻辑，保留 |

族内仍可打分择优，但**不再跨族用权重硬比**——同一根 bar 要么顺势要么回归，由 regime 决定。这把"涨在低位/跌在高位 = 顺势 or 均值回归"从隐式权重变成显式 if-regime。`pathSelectionWeight`(0.7/0.9/0.85/1.0) 这类纯凑数权重随之可删。

> regime 分类质量是这步的命门——须确认 `ctx.regime` 的判定本身够稳（可能要先回测 regime 分类器的命中率）。

### 3.C 旋钮精简（行为变化，需回测，逐项 A/B）

对照 §3.A 总账逐项消肿，每项独立挂 flag、独立 A/B：
- 统一口径：费率（`ROUND_TRIP_FEE_RATE` 等）、最低 RR、ATR 倍数命名收敛；删跨策略不可比的 `MIN_SCORE`（regime 路由后无需跨族比分）。
- 删冗余：二选一保留出场引擎（§问题3），删 `@Deprecated ExitDecisionEngine` 或 playbook（数据定）。
- 合并近义旋钮：多个"风险缩放/回撤/低波动"系数能否归并成少数几个语义清晰的。

### 3.D 验证框架（关键依赖）

行为级改动须回测，但有个**真问题**：live 现有回测引擎（`BacktestEngine`/`MaSlopeKlineBacktestEngine`/`SignalReplayBacktestEngine`）正是 Slice 1 指出的"手续费口径漂、信号≠生产、K线每轮重拉不可复现"的那套。所以本刀验证须先解决：

1. **K 线底座**：用 Slice 1 已建的 `kline_history` 落库（可复现），统一手续费口径（实盘=回测一个常数）。
2. **回测对象是"执行层"不是"方向预测器"**：Slice 1–3 的诚实尺子评估的是 direction forecaster；执行层回测要把 entry 策略 + sizing + exit 串起来按 bar 重放。需评估：是扩展 research harness 覆盖执行层，还是修 `MaSlopeKlineBacktestEngine` 的口径。**这本身是 §4 的一个前置任务**。
3. **A/B 4-cell × 8 窗口**：直接复用 maslope spec §3 的矩阵与硬指标。

---

## 4. 任务拆解（标注回测依赖）

| 任务 | 内容 | 依赖回测 | 可否现在做 |
|---|---|---|---|
| **T1** | §3.A 可观测性收口：旋钮总账 + `ExecutionParams` 分组（值原样搬，编译等价） | 否 | ✅ 现在 |
| **T2** | 回测能力就位：`kline_history` 喂执行层回测 + 统一手续费口径 + 选定回测引擎（扩 research harness 或修 live 引擎） | — | 需 K线数据 |
| **T3** | §3.B `RegimeRouter`：regime→策略族路由，feature-flag，A/B 对比 baseline | 是 | 待 T2 |
| **T4** | §3.C 旋钮精简：逐项消肿/统一口径/删冗余权重，每项 A/B | 是 | 待 T2/T3 |
| **T5** | §问题3 出场引擎二选一：A/B 定 playbook vs legacy，删另一套 | 是 | 待 T2 |
| **T6** | 端到端：8 窗口 × 4-cell 跑通，硬指标核对，决定 go/no-go | 是 | 待 T3–T5 |

> **建议先做 T1**（零风险、独立交付、是后续地基），其余待回测能力（T2）就绪——而 T2 的 K 线数据卡在 Binance，与 Slice 2b 的前置条件同源。

---

## 5. 约束与边界

- **不破坏账户**：所有 §3.B/C/D 改动挂 toggle，默认走 baseline；candidate 仅在 A/B 验证达标后才默认开启。
- **向后兼容（对 live 谨慎）**：与 Slice 1–3"对自家 research 可大刀阔斧"相反，本刀对 live 是**保守增量**——保留现有逻辑作 baseline cell，新逻辑作可切换 candidate（同 maslope spec §0）。
- **T1 例外**：可观测性收口是编译期等价重构，不需 flag、不需回测，按行为保全标准做。

## 6. 验收标准

1. T1：旋钮总账完整（覆盖命名+内联），`ExecutionParams` 编译通过、现有单测全绿、行为零变化（diff 只动常数位置不动值）。
2. T3–T5 每步：对应 cell 在 8 窗口的硬指标（finalEquity/MaxDD/PF）全部 ≥ baseline 口径，否则该项 no-go。
3. T6：4-cell × 8 窗口报告齐全，regime 路由后顺势/回归不再同 bar 竞争（日志可证），旋钮数显著下降（给出前后计数）。

## 7. 不在本刀范围 / sequencing 建议

- Slice 2b（live 多智能体图切 6/12/24h）：本刀不含；但其前置（K线数据 + edge 测量）与本刀 T2 同源，建议合并解决。
- 新策略研发（新入场/出场逻辑）：本刀只精简+厘清现有，不发明新策略。
- **建议执行顺序**：先落 T1（现在）→ 解决 K线数据闭环（T2，与 2b 共用）→ 再 T3–T6。

## 8. 方法论参考

- A/B 4-cell、硬指标、regime 方差作为反过拟合度量：`2026-05-27-maslope-refactor-design.md` §2–§3。
- 诚实尺子 / 回测可复现 / 手续费口径统一 / 信号≠生产的坑：`2026-05-29-forecast-eval-harness-design.md` §3、§7。
- López de Prado 回测过拟合、walk-forward purge+embargo：沿用 Slice 1 spec §9。

---

# 附录 A：执行层旋钮总账（T1a 普查 · 2026-06-01）

> **这是什么**：§3.A / T1 拆出的 **T1a（旋钮清单）** 交付物——`agent.trading.*` 执行层全部旋钮的**只读普查**（命名常数 + 内联裸数字），作为 §3.C 精简的底账。**零代码改动**；值与 `文件:行号` 截至 commit `b2e9605`，逐文件实读核对。
> **口径**：本表的"旋钮"= 影响交易行为的数值阈值/权重/比例/时间/计数（命名 `static final` + 嵌在公式里的裸数字 + 2 个运行时开关）。**不计**：path/action 字符串常量、引擎对象实例、`AtomicLong` 计数器、纯结构常量。
> **边界**：`backtest` 包（`BacktestEngine`/`MaSlopeKlineBacktestEngine`/`BacktestTradingTools`…）属 §3.D 验证框架（T2），不在本表逐列；但它与执行层**重复/口径冲突**的常数在 §A.9 点名。

## A.0 计数总览

| 区块 | 文件 | 命名数值常数 | 内联裸数字（聚合处） | 角色 |
|---|---|---:|---:|---|
| 顶层 | `DeterministicTradingExecutor` | 1（+2 开关） | 0 | 初始资金 + 2 灰度开关 |
| 编排 | `EntryDecisionEngine` | 5 | 3 组 | 择优权重 / 风险缩放 / 优先级 |
| 入场 | `BreakoutEntryStrategy` | 8 | 1 组(score) | 突破 |
| 入场 | `MeanReversionEntryStrategy` | 12 | 2 组(score/tp) | 均值回归 |
| 入场 | `TrendContinuationEntryStrategy` | 5 | 1 组(score 加性) | 顺势 |
| 入场 | `MaSlopeEntryStrategy` | 17 | 4 组(score 乘性/仓位/阈值) | 均线斜率（旋钮最重） |
| 入场 | `EntryStrategySupport` | 0 | 1（micro ±0.15） | 共享方向判断 |
| 共振 | `BreakoutConfluenceGate` | 0 | 1 组 | 5 中 3 |
| 共振 | `MeanReversionConfluenceGate` | 0 | 1 组 | 6 中 4 |
| 共振 | `TrendConfluenceGate` | 0 | 1 组 | 6 中 4 |
| sizing | `EntryRiskSizingService` | 16 | 2 组(regime/clamp) | 费率/RR/保证金/回撤/强平 |
| 下单 | `FuturesTradingOperationsAdapter` | 7 | 0 | 真实下单最后防线 |
| 出场 | `PlaybookExitEngine` | 1 | 0 | 新引擎编排 |
| 出场 | `MaSlopeExitPlaybook` | 21 | 极少 | MaSlope 退出（命名最规范） |
| 出场 | `BreakoutExitPlaybook` | 10 | 1（BB 55/45） | 突破退出 |
| 出场 | `TrendExitPlaybook` | 8 | 0 | 顺势退出 |
| 出场 | `MeanReversionExitPlaybook` | 7 | 1（中线 50） | 回归退出 |
| 出场 | `ExitPlanFactory` / `ExitPlanRecovery` | 2 / 2 | 1(synthetic 0.50) | 时限 / 计划恢复 |
| 出场 | `ExitDecisionEngine` `@Deprecated` | 10 | 大量(7 组) | legacy 软退出（默认不走） |
| 子包 | `MaSlopeStateClassifier` | 7 | 3(窗口/种子置信) | 斜率状态机 |
| 子包 | `MaSlopeFailureEvaluator` | 0 | 1 | 失败评分(6 信号) |
| 集中 | `SymbolProfile` | 1 + **15 字段 × 4 币** | — | 已集中化层 |

**合计：执行层核心命名数值常数 ≈ 139 个**（spec §1 估 121；差额是口径——spec 表未含下单适配器/StateClassifier/ExitPlan 工厂恢复）；**＋ 内联裸数字约 30 组（拆开 60+ 个）＋ SymbolProfile 15×4 矩阵 ＋ 2 开关**。真实可调旋钮**远超 spec 的"~150"印象**，§3.C 精简空间主要在内联与 §A.9 的冗余项。

## A.1 顶层 + 编排

**`DeterministicTradingExecutor`**

| 旋钮 | 值 | 位置 | 作用 |
|---|---|---|---|
| `INITIAL_BALANCE` | 100000.00 | :39 | 账户初始 / 回撤基准兜底 |
| `LOW_VOL_TRADING_ENABLED` | `true` (volatile) | :44 | 低波动小仓位交易总开关 |
| `PLAYBOOK_EXIT_ENABLED` | `true` (volatile) | :49 | ⭐出场引擎选择：true=Playbook 新 / false=legacy。**默认 true → legacy 当前不走** |

**`EntryDecisionEngine`** — 命名：择优权重 `BREAKOUT=1.0`(:85) `TREND=0.7`(:86) `MR=0.9`(:87) `MA_SLOPE=0.85`(:88)、`MA_SLOPE_FAILED_WAVE_LAUNCH_LIMIT=2`(:89)。内联：
- `riskStatusScale`(:641-645) 叠乘：`HIGH_DISAGREEMENT ×0.60`、`PARTIAL_DATA ×0.70`、`CAUTIOUS ×0.80`、`DATA_PENALTY ×0.75`、`HIGH_VOL_PENALTY ×0.80`
- `directionRiskScale`(:658,663)：`NO_TRADE 0.85`、反向 `0.85`
- `pathPriority`(:683-688) 同分裁决：`BREAKOUT 4 / MA_SLOPE 3 / LEGACY_TREND 2 / MR 1`；MaSlope 共振直接放行 `(1,1,1)`(:499)

## A.2 入场 4 策略打分

**`BreakoutEntryStrategy`** — 命名(8)：`STRONG_FLAT_SCORE 6.0`(:18)、`MAX_LEVERAGE 20`(:20)、`VOLUME_MIN 1.15`(:21)、`VOLUME_STRONG_MIN 1.25`(:22)、`POSITION_SCALE 0.75`(:23)、`MIN_SCORE 4.8`(:24)、`BB_PB_LONG_MIN 88.0`(:25)、`BB_PB_SHORT_MAX 12.0`(:26)。
内联 `score()`(:60-74)：microAgainst `0.35`、基 `conf×2.6`、squeeze `0.8:0.4`、量能强 `min(1.5,(vol-1.25)/0.5+0.7):0.3`、BB 距中 `min(1.2,|bb-50|/50)`、closeTrend `+0.8`、macd `+0.8`、15m `+0.5`、ema `+0.4`、micro `+0.4`。

**`MeanReversionEntryStrategy`** — 命名(12)：`MAX_LEVERAGE 20`(:19)、`POSITION_SCALE 0.55`(:20)、`MIN_CONFIDENCE 0.45`(:21)、`MIN_SCORE 3.6`(:24)、`BB_PB_LONG_MAX 25.0` / `SHORT_MIN 75.0`(:25-26)、`BB_PB_LONG_STRONG 15.0` / `SHORT_STRONG 85.0`(:27-28)、`RSI_LONG_MAX 48.0` / `SHORT_MIN 52.0`(:29-30)、`RSI_LONG_STRONG 38.0` / `SHORT_STRONG 62.0`(:31-32)。
内联：1h 冲突 `scale ×0.70`(:100)；`getScore`(:125-141)：基 `conf×3.0`、pbExtreme `×1.5`、rsiStretch `×1.0`、votes `×0.4` 上限 `1.2`、`RANGE +1.0`、`SQUEEZE +0.5`、`WEAKENING +0.5`、micro `+0.4`、1h 冲突 `-0.6`；`getTpAtrMult` 中线 `50`(:115)。

**`TrendContinuationEntryStrategy`** — 命名(5)：`MIN_CONFIDENCE 0.45`(:20)、`MAX_LEVERAGE 25`(:21)、`MIN_SCORE 4.2`(:22)、`RSI_LONG_MAX 78.0`(:23)、`RSI_SHORT_MIN 22.0`(:24)。
内联：SHOCK 下 `conf<0.70` 拒(:40)；基 `conf×3.0`(:63)；加性命中 regime `+1.2`、1h `+1.2/-0.5`、15m `+1.0/-0.3`、macd `+1.0`、closeTrend `+0.7`、ema `+0.6`、量能≥`1.2` `+0.4`、micro `+0.4`、RSI 健康 `+0.3`(:66-103)；候选 positionScale `1.0`(:122)；`isRsiTrendHealthy` 区间 `42/78`、`22/58`(:134)。

**`MaSlopeEntryStrategy`** — 命名(17，:34-53)：`MAX_LEVERAGE 50`、`MA25_EARLY_LAG_TOLERANCE_ATR 0.0`、`PRICE_DISTANCE_LAUNCH_ATR 2.50`、`PRICE_DISTANCE_PULLBACK_ATR 3.50`、`MACD_RECLAIM_MAX_DISTANCE_ATR 2.60`、`MACD_RECLAIM_MAX_RANGE_ATR 1.60`、`MACD_RECLAIM_MIN_VOLUME 0.95`、`FUNDING_EXTREME 0.60`、`SCORE_MIN 0.45`、`SCORE_MAX 1.20`、`M3_TREND_CONTINUATION_MIN_VOLUME 0.75`(BTC `1.10`)、`..._MAX_DISTANCE_ATR 4.50`(BTC `4.00`)、`..._MAX_RANGE_ATR 2.20`(BTC `1.80`)、`..._FORCE_ADX 45.0`。
内联（最多）：
- `score()` 乘性链(:439-464)：种子 `baseConfidence`(见 §A.7)、confirm `×1.12`、1h `×1.08`、15m `×1.06`、macd `×1.08`、closeTrend3 `×1.06`/普通 `×1.04`、di `×1.04`、ema `×1.03`、水位+新叉 `×1.03`、量能≥1.5 `×1.08`/≥1.2 `×1.04`、bollExp `×1.05`、micro `×1.03`、LATE `×0.85`、RECLAIM `×0.90`、funding `×0.92`、early `×0.90`
- `positionScale()`(:469-478)：`EARLY_MA25_LAG 0.50`、`MACD_EARLY 0.45/0.35`、`LATE 0.60`、default `0.75/0.65`；LATE_CONTINUATION 再 `×0.80`(:149)
- 各 TF 阈值：`adxReject` M3 `25.0`/M15 `16.0`/默认 `18.0`(:532)、early 再 `+2.0`(:211)；`atrSpikeReject` M3 `2.0`/M15 `1.5`/默认 `1.8`(:539)
- 支撑判定：closePos `0.45`(:373)、bollPos `58/42`(:422)、reclaim `0.05/0.20 ATR`(:391,400)、highForce vol `1.20`(:411)、spreadExp `×2.0`(:365)、avg vol `0.90`(:322)、macdEarly spread `±0.05`(:590,595)

**`EntryStrategySupport`** — `microSupports` 阈值 `±0.15`(:80)。

## A.3 二层共振门（全内联，无命名常数）

| 门 | 票数 | BB%B | RSI | 量能 | micro |
|---|---|---|---|---|---|
| `BreakoutConfluenceGate` | 5 中 3(:25) | — | — | release `≥1.45`(:42) | `0.30`(:54) |
| `MeanReversionConfluenceGate` | 6 中 4(:30) | `≤18 / ≥82`(:39) | `≤42 / ≥58`(:45) | — | `0.30`(:27) |
| `TrendConfluenceGate` | 6 中 4(:29) | — | 健康 `42/78`、`22/58`(:43) | `≥1.20`(:26) | `0.30`(:27) |

## A.4 sizing / risk + 下单适配器

**`EntryRiskSizingService`** — 命名(16)：

| 旋钮 | 值 | 行 | 旋钮 | 值 | 行 |
|---|---|---|---|---|---|
| `ROUND_TRIP_FEE_RATE` | 0.0008 | :32 | `MASLOPE_MAX_MARGIN_PCT` | 1.0 | :49 |
| `ESTIMATED_CLOSE_FEE_RATE` | =RT/2 | :34 | `MASLOPE_MIN_MARGIN` | 400 | :50 |
| `MIN_PROFIT_AFTER_FEE_ATR` | 0.5 | :36 | `DRAWDOWN_THRESHOLD` | 0.85 | :52 |
| `RISK_PER_TRADE` | 0.01 | :41 | `DRAWDOWN_REDUCTION` | 0.7 | :53 |
| `DEFAULT_MIN_ENTRY_RR` | 1.2 | :43 | `LOW_CONFIDENCE_POSITION_SCALE` | 0.5 | :54 |
| `MR_MIN_ENTRY_RR` | 1.0 | :44 | `LOW_VOL_SL_EXPAND_MAX` | 3.0 | :55 |
| `MAX_MARGIN_PCT` | 0.15 | :48 | `LOW_VOL_POSITION_SCALE` | 0.6 | :56 |
| `MAINTENANCE_MARGIN_RATE` | 0.005 | :58 | `MAX_SL_TO_LIQ_DISTANCE_RATIO` | 0.80 | :59 |

内联：`pathRegimeScale`(:131-137) `SHOCK conf≥0.70?0.50:0` / `SQUEEZE breakout0.85:0.65` / 默认 `1.0`；effectiveScale `clamp(0.05,1.0)`(:90)；回撤 leverage `max(5,…)`(:116)；marginBudget `−0.01`(:305)。

**`FuturesTradingOperationsAdapter`**（真实下单最后防线，7）：`MAX_LEVERAGE 50`(:30)、`MIN_LEVERAGE 5`(:31)、`MIN_POSITION_VALUE 5000`(:32)、`MAX_POSITION_RATIO 0.35`(:33)、`MIN_MARGIN_FLOOR 100`(:34)、`MIN_MARGIN_RATIO 0.01`(:35)、`SL_MIN_TOLERANCE 0.0002`(:36)。

## A.5 出场 — Playbook 引擎 + 4 剧本

**`PlaybookExitEngine`**：`FORECAST_STALE_LIMIT 5min`(:44，指标护盾时限)。

**`MaSlopeExitPlaybook`**（21，命名最规范，内联极少）：`BREAKEVEN_R 0.50`、`BREAKEVEN_LOCK_R 0.10`、`TRAIL_R 2.00`、`PARTIAL_R 3.00`、`PARTIAL_R_MILESTONE 300`、`PARTIAL_CLOSE_RATIO 0.30`、`TIME_PROGRESS_R 0.50`、`TIME_LIMIT_MINUTES 90`、`ATR_TRAIL_MULT 2.0`、`FAST_FAIL_DEEP_R -0.45`、`FAST_FAIL_DEEP_SCORE 2`、`FAST_FAIL_SHALLOW_SCORE 3`、`FAST_FAIL_STREAK_THRESHOLD 2`、`EARLY_FAIL_R 0`、`EARLY_FAIL_MINUTES 60`、`EARLY_KILL_MINUTES 9`、`EARLY_KILL_MFE_R 0.15`、`EARLY_KILL_MAE_R -0.20`、`EARLY_KILL_SCORE 3`、`MA7_EXIT_SLOPE_ATR -0.005`、`EXTINGUISH_STREAK_THRESHOLD 2`（:29-51）。

**`BreakoutExitPlaybook`**（10）：`BREAKEVEN_R 1.0`、`BREAKEVEN_LOCK_R 0.10`、`FAIL_TO_PROGRESS_R 0.30`、`PARTIAL_R 2.00`、`PARTIAL_R_MILESTONE 200`、`PARTIAL_CLOSE_RATIO 0.30`、`FAIL_TO_PROGRESS_MINUTES 30`、`VOLUME_EXHAUSTION_MIN_BARS 3`、`VOLUME_EXHAUSTION_RATIO 0.8`、`CHANDELIER_ATR_MULT 2.5`（:24-35）；内联 BB 回中 `55/45`(:112,115)。

**`TrendExitPlaybook`**（8）：`BREAKEVEN_R 1.0`、`TRAIL_R 2.00`、`PARTIAL_R 3.00`、`PARTIAL_R_MILESTONE 300`、`PARTIAL_CLOSE_RATIO 0.30`、`TIME_PROGRESS_R 0.50`、`TIME_LIMIT_MINUTES 90`、`ATR_TRAIL_MULT 2.0`（:24-31）。

**`MeanReversionExitPlaybook`**（7）：`BOLL_EXTREME_STEP 5.0`、`NEUTRAL_LOW 45.0`、`NEUTRAL_HIGH 55.0`、`HALF_CLOSE_RATIO 0.50`、`HALF_MIDLINE_PROGRESS 0.50`、`TIME_PROGRESS_R 0.50`、`TIME_LIMIT_MINUTES 30`（:24-30）；内联中线 `50`(:155-159)。

**`ExitPlanFactory`**：`FAST_TIME_LIMIT 30min`、`TREND_TIME_LIMIT 90min`（:11-12）。
**`ExitPlanRecovery`**：`BREAKEVEN_ATR_EPSILON 0.10`、`BREAKEVEN_PRICE_EPSILON 0.0001`（:16-17）；内联 synthetic SL `entry×0.50`(:91)。

## A.6 出场 — legacy `ExitDecisionEngine`（`@Deprecated`，默认不走）

命名(10)：`STRONG_REVERSAL_CONFIDENCE 0.82`(:58)、`SOFT_REVERSAL_CONFIDENCE 0.65`(:60)、`TIGHTEN_SL_PROGRESS 0.35`(:62)、`PARTIAL_PROTECT_PROGRESS 0.45`(:64)、`PARTIAL_PEAK_PROGRESS 0.55`(:66)、`PARTIAL_PEAK_DRAWDOWN 0.30`(:68)、`EARLY_LOSS_SL_PROGRESS 0.70`(:70)、`TIME_EXIT_MINUTES 90`(:72)、`EXTENDED_TIME_EXIT_MINUTES 180`(:74)、`PARTIAL_CLOSE_RATIO 0.50`(:76)。
内联（大量）：assessRisk micro `±0.15`(:247)、lsr `±0.4`(:251)；风险票门限 `≥5全平`(:147)/`≥3亏损`(:155)/strongReversal `marketVotes≥2`(:277)；时间退出各 path `MR45/BO60/默认90` 与 `MR90/BO120/默认180`(:299-307)、progress `<0.20`/`<0.35`(:309,312)；tighten `BO0.25/MR0.45/默认0.35`(:319-323)、riskThreshold `trend1/其它2`(:324)；MR 软止盈 progress `≥0.70`(:333)、boll `45/55`(:335)；突破失败 hold `<5min`(:341)、boll `60/40`(:343-344)；保护止损 MR `profit×0.35`/`atr×0.25`(:473-474)、trail `max(0.20, profitAtr×0.45)`(:484)、maxValid `atr×0.10`(:488)；actionRank `4/4/3/3/2/1`(:528-533)。

## A.7 MaSlope 子包阈值（被入场/出场共享）

**`MaSlopeStateClassifier`**（7）：`MIN_SERIES_SIZE 8`、`MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM 9`、`MA7_SLOPE_MIN_ATR 0.03`、`MA7_SLOPE_STRONG_ATR 0.06`、`MA25_SLOPE_MIN_ATR 0.01`、`SPREAD_MIN_ATR 0.20`、`SPREAD_DELTA_MIN_ATR 0.01`（:14-20）。
内联：线性回归窗口 `5`(:71-73)、spreadDelta 跨度 `/4.0`(:80)、⭐**`baseConfidence` 种子 `0.76`(加速)/`0.70`(普通)(:174)** —— 这是 MaSlope 整条乘性打分链的起点。

**`MaSlopeFailureEvaluator`**：无命名常数；`spreadDeltaWeak` 阈 `0.0`(:89)；评分=6 个失败信号各 `+1`（CLOSE_LOST_MA7 / SLOPE_WEAKER / SPREAD_DELTA_WEAK / MACD_WEAK / DI_FLIPPED / CLOSE_TREND_AGAINST）。

## A.8 集中化层 `SymbolProfile`（15 字段 × 4 币 = 60 值；已是"配置表"形态）

`DEFAULT_PLAYBOOK_HARD_TP_R 5.0`(:26)。

| 字段 | BTC/ETH | PAXG | DOGE |
|---|---|---|---|
| trendSlAtr | 2.6 | 3.5 | 2.8 |
| trendTpAtr | 5.2 | 6.0 | 5.5 |
| revertSlAtr | 2.4 | 3.0 | 2.6 |
| revertTpMinAtr | 3.2 | 2.5 | 3.0 |
| revertTpMaxAtr | 4.2 | 5.0 | 4.0 |
| breakoutSlAtr | 2.8 | 4.0 | 3.0 |
| breakoutTpAtr | 5.8 | 6.0 | 5.5 |
| trailBreakevenAtr | 0.5 | 1.2 | 0.6 |
| trailLockAtr | 1.5 | 2.0 | 1.6 |
| slMinPct | 0.008 | 0.002 | 0.010 |
| slMaxPct | 0.12 | 0.05 | 0.15 |
| partialTpAtr | 1.5 | 2.0 | 1.6 |
| trailGapAtr | 0.8 | 1.2 | 0.9 |
| breakoutHardTpR / trendHardTpR | 5.0 / 5.0 | 5.0 / 5.0 | 5.0 / 5.0 |

> ⚠️ BTC 与 ETH **完全相同**（未差异化）；`hardTpR` 四币全 `5.0`（DEFAULT 直传，从未按币调过）。

## A.9 ⭐ 冗余 / 重复 / 口径不一致清单（§3.C 直接靶子）

按"删/并/统一"价值排序——这些是精简最先该处理的，**每项改动仍须 §3.D 回测背书**：

1. **手续费口径三处分裂**（spec §3.D 点名的真问题）：live `EntryRiskSizingService.ROUND_TRIP_FEE_RATE=0.0008`(:32) ↔ backtest `BacktestTradingTools.OPEN_FEE_RATE 0.0004 + CLOSE 0.0004`(拆开) ↔ `MaSlopeKlineBacktestEngine.ROUND_TRIP_FEE_RATE=0.0008`(:37)。**实盘=回测必须收敛成一个常数**。
2. **6 个 sizing/强平常数 live↔backtest 整组重复**：`MIN_PROFIT_AFTER_FEE_ATR 0.5`、`DEFAULT_MIN_ENTRY_RR 1.2`、`LOW_VOL_SL_EXPAND_MAX 3.0`、`MAINTENANCE_MARGIN_RATE 0.005`、`MAX_SL_TO_LIQ_DISTANCE_RATIO 0.80`（`EntryRiskSizingService` :36/43/55/58/59 ↔ `MaSlopeKlineBacktestEngine` :47-51）→ 抽到共享常量源。
3. **`MAX_LEVERAGE` 五处各异**：Adapter `50` / MaSlope `50` / Trend `25` / Breakout `20` / MR `20`。语义=各策略杠杆上限，应归并成 per-策略表（或并入 SymbolProfile）。
4. **`MIN_SCORE` 跨策略不可比**：Breakout `4.8` / Trend `4.2` / MR `3.6`——§3.B regime 路由落地后，跨族比分消失，这三个可一并删（spec §3.C 已列）。
5. **择优权重 `pathSelectionWeight`(1.0/0.7/0.9/0.85)** 是"凑出来"的——regime 路由后整组可删（spec §3.B）。
6. **`FAILED_WAVE_LAUNCH_LIMIT=2` 重复**：`EntryDecisionEngine.MA_SLOPE_FAILED_WAVE_LAUNCH_LIMIT`(:89) ↔ `MaSlopeKlineBacktestEngine.FAILED_WAVE_LAUNCH_LIMIT`(:52)。
7. **RSI 健康区间 `42/78、22/58` 完全重复**：`TrendContinuationEntryStrategy.isRsiTrendHealthy`(:134) ↔ `TrendConfluenceGate.rsiTrendHealthy`(:43)。
8. **micro 反向阈值不统一**：入场策略 `0.35`（Breakout/MaSlope）↔ 三共振门 `0.30` ↔ support 触发 `0.15`。同一信号三个阈值。
9. **平仓比例分裂**：legacy `PARTIAL_CLOSE_RATIO 0.50` / 各 playbook `0.30` / MR playbook `HALF 0.50`。
10. **退出 R 里程碑/时限在 4 剧本各自定义**：`TRAIL_R 2.00`、`PARTIAL_R 3.00(突破2.00)`、`TIME_PROGRESS_R 0.50`、`ATR_TRAIL_MULT 2.0`、`TIME_LIMIT 90/30` 散在 Trend/MaSlope/Breakout/MR——可抽公共出场参数基类。
11. **BB%B 中性区多处**：MR playbook `NEUTRAL 45/55` ↔ legacy MR 软止盈 `45/55` ↔ 突破 BB 回中 `55/45`。
12. **双出场引擎冗余**（spec 问题3）：`PLAYBOOK_EXIT_ENABLED=true` → legacy `ExitDecisionEngine`（10 命名 + 7 组内联）当前**默认不执行**，是纯维护负担——A/B 验证 playbook 不劣化后即可删 legacy（数据定）。
13. **BTC=ETH 未差异化、`hardTpR` 全 5.0**（§A.8）：SymbolProfile 看似 per-币，实际只有 PAXG/DOGE 真正调过。

> **结论**：执行层"旋钮爆炸"实测坐实——命名 ~139 + 内联 30 组 + SymbolProfile 60 + 开关 2。**精简最大头不是删命名常数，而是 ①统一 live/backtest 重复口径（项 1-2，也是回测可信的前提）②regime 路由后删跨族比分/权重（项 4-5）③合并散落的同义阈值（项 3、7-11）④删 legacy 出场引擎（项 12）**。所有行为级改动按 §3.C 逐项挂 flag + A/B，待 T2 回测能力就绪。
