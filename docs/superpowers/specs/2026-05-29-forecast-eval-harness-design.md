# 预测评估框架 + 预测目标定义（量化重构 Slice 1）

- 日期: 2026-05-29
- 作者: mawai
- 状态: 设计草案，待评审
- 关联: 这是「量化分析+策略 agent trader 重构」的第 1 刀（共多刀，见 §0 路线图）

## 0. 这是什么 / 在整体重构中的位置

整体目标从「预测极短周期（0–30min）涨跌」转向「**6h/12h/24h 中周期、盈利优先·跑赢基准、新闻+链上当一等信号**」。

路线选定 **Approach 1：复用现有骨架 + 精确重建被周期绑死的部分**（理由见 §1.3）。整体拆成多个独立 slice，各自独立 spec：

| Slice | 内容 | 状态 |
|---|---|---|
| **1（本文）** | **预测评估框架 + 预测目标定义**（"测量尺"） | 本文 |
| 2 | 重定向因子/特征到 6/12/24h，接现有多智能体图 | 后续 |
| 3 | 新闻源更换 + 链上数据接入（数据契约） | 后续 |
| 4 | 执行/策略层重建（替换 150+ 手调常数） | 后续 |

**为什么第一刀是"测量尺"**：用户成功标准是「盈利优先·跑赢基准」。在建立基准+样本外验证之前，"跑赢基准"这句话无法度量；现有系统恰恰**全系统零样本外验证、零基准对照**（见 §1.1）。先有尺子，后面每个模型/信号才能判生死，否则只会重演现状的过拟合。

## 1. 背景

### 1.1 现状全貌（通读 `agent/` 后的结论）

**已存在且不差的部分（复用）**：

- 多源数据层：Binance 合约/现货 K线、Deribit DVOL/期权、新闻、Fear&Greed、OI、资金费率、多空比、强平流、订单流、深度、稳定币流（`StablecoinFlowService`）、ETF 流（`EtfFlowScraper`）。
- 多智能体预测图（Spring AI Alibaba StateGraph，8 节点）：5 因子 agent（microstructure/momentum/regime/volatility/**news_event**）× 3 horizon + regime 审核 + risk gate + 报告。
- 预测验证器 `VerificationService`：路径分析（MFE/MAE、TP/SL 谁先触），把"方向对但先触止损"判成 LUCKY 不算对——质量评级专业。
- 干净解耦的执行层：LLM 只做分析，确定性 Java 执行，`FuturesTradingOperationsAdapter` 明确「不暴露 Tool，防止 LLM 绕过策略链直接下单」。
- 真实风控：熔断 L1/L2/L3、路径禁用、强平缓冲、交易归因、反思记忆。

**真正坏掉的三处（重建）**：

| # | 问题 | 证据 |
|---|---|---|
| 1 | 预测周期是 0–30 分钟（`0_10/10_20/20_30`），正是要放弃的极短噪声区 | `HorizonForecast`、`VerificationService` 全用 `plusMinutes` |
| 2 | **全系统零样本外验证、零基准对照** | 全仓 grep 无 walkForward/buyAndHold；验证器只统计方向命中率，从不跟"抛硬币/buy&hold"比；`BacktestRunner.parameterScan` 是纯样本内网格搜索 |
| 3 | 执行层 ~150+ 手调魔法常数，按现场盈亏反复拍 | 入场 4 策略 + sizing + 4 出场 playbook |

工程坑：回测 K线不落库（每轮重拉 Binance，9–10h，无法复现）；回测手续费与实盘手续费是两个独立常数（会漂）；`BacktestEngine` 自己 Javadoc 承认其信号 ≠ 生产信号。

### 1.2 目标转向

| 维度 | 旧 | 新 |
|---|---|---|
| 预测周期 | 0–30min | **6h / 12h / 24h** |
| 成功标准 | 方向命中率（无基准） | **风险调整后跑赢双基准（buy&hold + naive）** |
| 信号构成 | 技术因子为主 | 技术 + **新闻 + 链上** 同为一等 |
| 方向预测态度 | 强行预测极短方向 | 接受短周期方向近随机；重心移向**可预测的波动率/风险** |

### 1.3 路线选择：为什么复用而非推倒

从零重建 = 用扔掉 ~70% 周期无关、且正确的资产（数据层、多智能体图框架、验证器、风控/执行）去逃避 ~30% 被周期绑死的部分。不划算。正确做法按"周期相关性"切：周期无关的留，被周期绑死/方法论错的重建。本刀只做"测量尺"，对现有任何资产都是**纯增量、不修改**（向后兼容铁律）。

## 2. 本刀目标（一句话）

建一把**诚实的尺子**：给定任意一个 6/12/24h 预测器，能在**样本外**回答它"是否、以及多大程度上"跑赢 buy&hold 和随机基准（风险调整后）。先用一个**故意简单的基线预测器**把尺子跑通、立下后续一切都必须越过的及格线。

## 3. 方法论依据（每条对应一个设计决策，附来源）

本刀不是凭感觉设计，每个关键选择都有出处：

| 设计决策 | 依据 | 来源 |
|---|---|---|
| **必须跟朴素基准（naive/drift）比，比不过的方法不值得考虑** | FPP §3.1：naive=最后观测值=随机游动预测；"任何新方法都应与简单方法比较……否则不值得考虑" | FPP 3.1 [S1] |
| **训练/测试集划分 + 滚动原点交叉验证（rolling origin）** | FPP §3.4：测试集约占 20% 且至少与最大预测区间等长；时间序列 CV「没有未来观测被用来构建预测」，原点持续前推 | FPP 3.4 [S2] |
| **用 MASE 等比例误差度量（<1 才优于朴素法）** | FPP §3.4：MASE 用 naïve 的 MAE 归一，跨单位可比，<1 优于平均 naïve | FPP 3.4 [S2] |
| **预测残差诊断（零均值、无自相关、Ljung-Box）** | FPP §3.3：残差必要性质=不相关+零均值；Ljung-Box 复合自相关检验 | FPP 3.3 [S3] |
| **接受短周期方向近随机（不强求方向 edge）** | FPP §1.1：可预测性取决于"影响因素了解程度/数据/预测是否反作用于对象"；汇率因有效市场难预测 | FPP 1.1 [S4] |
| **重心移向波动率：方向不可测但波动率可测且聚集** | GARCH 文献：收益符号不可测，但绝对值/平方有显著慢衰减自相关（波动率聚集）；Engle(1982) ARCH、Bollerslev(1986) GARCH | [S5][S6] |
| **三隔栏打标（上=止盈/下=止损/竖=到期，波动率定栏宽）** | López de Prado, AFML 第 3 章：替代"固定时间符号"打标，把路径与止损编进标签，栏宽用滚动波动率 | [S7] |
| **CV 必须 purge + embargo（防标签重叠泄漏）** | López de Prado, AFML 第 7 章：剔除标签与测试集重叠的训练样本（purge）；测试集后再禁一段（embargo） | [S8] |
| **风险调整指标（Sharpe/Calmar），并为多次试验做 Deflated Sharpe** | Bailey & López de Prado(2014) Deflated Sharpe：修正多重检验选择偏差 + 非正态；试过多少组配置必须记录 | [S9] |
| **回测过拟合概率 PBO / 随机无技能分布显著性检验** | Bailey/Borwein/LdP/Zhu：CSCV 估计 PBO（选中策略是否样本外低于中位）；Aronson：data-mining bias，用 bootstrap/Monte-Carlo 排列构造"无技能"分布 | [S10][S11] |

**核心洞见落地**：FPP §1.1 + GARCH 文献共同说明——短周期"涨还是跌"近随机，但"波动多大"可测。因此本刀的打标（三隔栏的栏宽=k·σ）和后续策略的重心，都建立在**波动率可预测**而非**方向可预测**之上。

## 4. 约束

- **向后兼容**：不修改现有 `quant`/`trading` 任何生产类；本刀全部新增代码，跑在新包，离线/按需触发，绝不接入 live `AiTradingScheduler`。
- 标的沿用现有 universe：BTCUSDT、ETHUSDT。
- 回测手续费口径与实盘统一（不再各拍一个常数）；杠杆/方向沿用合约设定。
- 不在本刀引入新外部依赖（统计计算用现有 BigDecimal/原生即可，避免过度设计）。

## 5. 设计方案

### 5.1 模块总览

新增包建议：`com.mawai.wiibservice.agent.research`（"研究/评估"层，与 live `trading` 平行、互不依赖）。

| 模块 | 复用/新建 | 职责 |
|---|---|---|
| **K线落库 + 加载器** `KlineHistoryStore` | 新建（小）+ 新表 `kline_history` | 1m 基础 bar 入库（BTC/ETH，~3 年），按需聚合 6/12/24h；回测从库读，不再每轮重拉 Binance → 可复现。回填用现有 `BinanceRestClient` |
| **三隔栏打标器** `TripleBarrierLabeler` | 新建 | 每个决策点：上栏 +k·σ、下栏 −k·σ、竖栏 H 小时；标签=谁先触 {+1/−1/0}。σ 默认用对数收益的 EWMA 波动率（ATR 备选） |
| **基准模块** `BenchmarkCalculator` | 新建（小） | ①buy&hold 净值；②naive 随机入场 Monte-Carlo 分布（同频同持仓，N=1000），输出策略所处分位 |
| **Walk-forward 评估器** `WalkForwardEvaluator` | 新建 | 滚动原点切分 + purge + embargo；喂入可插拔 `Forecaster`，逐窗样本外评估 |
| **风险调整指标** `RiskAdjustedMetrics` | 新建（research 包，零侵入） | **年化** Sharpe（现有 `BacktestTradingTools` 是 per-trade，不改它）、Calmar=CAGR/MaxDD、MaxDD、命中率 vs 基率；预留 Deflated Sharpe 钩子（记录每次试验收益序列）；可复制其纯计算逻辑但不改调用点 |
| **预测质量层** | **复用** `VerificationService` 路径逻辑 | 分钟→小时泛化，保留 MFE/MAE、TP/SL-first、LUCKY 惩罚 |
| **预测器接口** `Forecaster` + `EwmaMomentumForecaster` | 新建（基线故意简单） | 接口：`(point-in-time 特征) → 方向+置信度`；基线=EWMA 动量符号，只为跑通尺子、立及格线 |
| **报告** | 复用现有报告风格 | 基线 vs buy&hold vs naive，样本外，风险调整，落 `target/` JSON |
| **触发入口** | 新建 IT / `BacktestController` 端点 | 离线/按需跑，不进 live 周期 |

### 5.2 数据流

```
回填: BinanceRestClient → KlineHistoryStore(kline_history 表, 1m)
评估一次:
  KlineHistoryStore.load(symbol, from, to)            // 可复现的历史
    → 聚合到 H（6/12/24h）+ CryptoIndicatorCalculator 算 point-in-time 特征
    → TripleBarrierLabeler 打标（每个决策点的 {+1/-1/0}）
    → WalkForwardEvaluator: 对每个滚动窗 [train | purge+embargo | test]
        · Forecaster 在 train 上拟合/或无状态
        · 在 test 上产出预测 → 模拟交易 → 收益序列
    → RiskAdjustedMetrics: 年化 Sharpe/Calmar/MaxDD/命中率
    → BenchmarkCalculator: buy&hold 曲线 + naive 随机分布分位
    → 报告: 策略 vs 双基准（样本外、风险调整）
```

### 5.3 关键设计选择（已选推荐默认，可在评审改）

- **打标用三隔栏，不用"H 小时后涨跌符号"** [S7]：把风险/路径编进标签，栏宽=波动率倍数，天然衔接现有验证器的 TP/SL-first，也落地"波动率可预测"。默认 k≈1.5、竖栏=H；k 本身是打标参数，**不对它做网格寻优**（避免又造一个过拟合旋钮）。
- **naive = 随机入场分布，不是单一曲线** [S11]：回答"你比瞎猜强、且强到不像运气"（默认超过分布 95 分位才算赢）。这是 Aronson 的 bootstrap/排列检验思路。
- **CV 必须 purge + embargo** [S8]：24h 标签会与相邻样本标签重叠，不切除则泄漏，等于把过拟合病请回来。这是诚实尺子的最低配，不是镀金。默认 embargo ≈ 1 个最大 horizon。
- **风险调整为主，绝对收益为辅** [S9]：主看年化 Sharpe / Calmar，辅看绝对收益与 vs buy&hold 超额。
- **Deflated Sharpe / CPCV 多路径属"记录现在、计算后置"**：本刀只接一个基线（N=1，DSR 平凡），但**现在就建"每次回测记录完整收益序列"的钩子**，等后续 slice 开始试多组配置时，DSR/PBO 立刻可算 [S9][S10]。slice 1 不实现完整 CPCV，只做带 purge+embargo 的滚动原点 walk-forward（够用且更轻）。
- **三个周期都打标都评估，主报告周期默认 12h**（够长让新闻/链上发酵，够短保证样本量）。

### 5.4 与现有代码的复用边界（向后兼容）

- **只读复用**：`CryptoIndicatorCalculator`（特征）、`BinanceRestClient`（回填）、`VerificationService` 的路径分析算法（抽公共方法或复制其纯函数逻辑，不改原类）。
- **默认零侵入**：风险调整指标在 research 包内新建 `RiskAdjustedMetrics`，不修改 `BacktestResult`/`BacktestTradingTools`（它们被现有回测使用）；如需复用其纯计算逻辑，抽取为静态工具，不改原调用点。
- **绝不触碰**：`AiTradingScheduler`、`DeterministicTradingExecutor`、`EntryDecisionEngine`、各 strategy/playbook、live `QuantForecastWorkflow`。

## 6. 验收标准（本刀做完算成功）

1. 能对「任意 `Forecaster` + 任意时间窗 + {6h,12h,24h}」产出一份样本外报告：年化 Sharpe、Calmar、MaxDD、命中率 vs 基率、vs buy&hold 超额、vs naive 随机分布分位。
2. K线落库后，**同一回测可复现**（不依赖 Binance 当时返回）。
3. CV 切分明确实现 purge + embargo，并有单测证明重叠标签被剔除。
4. 基线（EWMA 动量）成绩跑出来——**大概率跑不赢 buy&hold**；这正是基线该有的样子（它存在是为了立标尺，不是为了赢）。报告须同时显示基线、buy&hold、naive 三条，肉眼可比。
5. 全程未修改任何 live 生产类（git diff 仅含 research 包 + 新表 + 测试 + 本 spec）。

## 7. 不在本刀范围 / 后续 slice

- 重定向现有因子 agent / 改 `FeatureSnapshot` / 信号 schema → Slice 2
- 新闻源更换（现 `data-api.coindesk.com`，疑似停服）+ 链上数据接入 → Slice 3
- 执行层 150 常数精简、"涨在低位/跌在高位"= 顺势 vs 均值回归之厘清 → Slice 4
- 完整 CPCV 多路径回测、Deflated Sharpe 实算 → 待开始试多组配置时（记录钩子本刀先埋）

## 8. 实施任务粒度（供 writing-plans 参考）

1. 建 `kline_history` 表 + `KlineHistoryStore`（回填 + 加载 + 聚合），单测覆盖聚合正确性
2. `TripleBarrierLabeler`（含波动率栏宽），单测覆盖三种触栏结果
3. `RiskAdjustedMetrics`（年化 Sharpe/Calmar/MaxDD）+ 收益序列记录钩子，单测对拍已知序列
4. `BenchmarkCalculator`（buy&hold + 随机 Monte-Carlo 分位），单测
5. `WalkForwardEvaluator`（滚动原点 + purge + embargo），单测证明无标签泄漏
6. `Forecaster` 接口 + `EwmaMomentumForecaster` 基线
7. 触发入口（IT 或 `BacktestController` 端点）+ 报告输出
8. 跑 BTC/ETH × {6h,12h,24h} 出首份样本外报告，确认基线 vs 双基准三线可比

每步独立验收、独立提交。

## 9. 参考来源

**《预测：方法与实践》(Forecasting: Principles and Practice, 中文第2版, Hyndman & Athanasopoulos)**
- [S1] §3.1 一些简单的预测方法（naive/seasonal naive/drift 作为基准）— https://otexts.com/fppcn/simple-methods.html
- [S2] §3.4 评估预测精度（训练/测试集、滚动原点交叉验证、MAE/RMSE/MAPE/MASE）— https://otexts.com/fppcn/accuracy.html
- [S3] §3.3 残差诊断（零均值/不相关、Ljung-Box 检验）— https://otexts.com/fppcn/residuals.html
- [S4] §1.1 什么可以被预测（可预测性三因素、有效市场）— https://otexts.com/fppcn/what-can-be-forecast.html

**波动率可预测 / GARCH**
- [S5] Volatility clustering（收益符号不可测、幅度可测）— https://en.wikipedia.org/wiki/Volatility_clustering
- [S6] NYU Stern V-Lab, GARCH Volatility 文档（Engle 1982 ARCH / Bollerslev 1986 GARCH）— https://vlab.stern.nyu.edu/docs/volatility/GARCH ；教材级讲解 https://www.econometrics-with-r.org/16.4-volatility-clustering-and-autoregressive-conditional-heteroskedasticity.html

**López de Prado, *Advances in Financial Machine Learning* (2018)**
- [S7] 三隔栏打标 + meta-labeling（第 3 章）— https://hudsonthames.org/does-meta-labeling-add-to-signal-efficacy-triple-barrier-method/ ；https://deepwiki.com/quantopian/mlfinlab/6.3-triple-barrier-method
- [S8] Purged K-Fold CV + Embargo + CPCV（第 7 章）— https://blog.quantinsti.com/cross-validation-embargo-purging-combinatorial/ ；https://en.wikipedia.org/wiki/Purged_cross-validation

**回测过拟合 / 显著性**
- [S9] Bailey & López de Prado (2014), The Deflated Sharpe Ratio, JPM 40(5):94-107 — https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551 ；PDF https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf
- [S10] Bailey, Borwein, López de Prado & Zhu, The Probability of Backtest Overfitting (CSCV), J. Computational Finance — https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2326253 ；PDF https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf
- [S11] Aronson (2006), Evidence-Based Technical Analysis（data-mining bias、bootstrap/排列检验、走样外验证）— https://www.earnforex.com/guides/book-review-evidence-based-technical-analysis-by-david-aronson/

**其它可参考（未直接引用）**
- 免费在线教材 FPP 英文第 3 版（fable/tidyverts）— https://otexts.com/fpp3/
- Deflated Sharpe 概念页 — https://en.wikipedia.org/wiki/Deflated_Sharpe_ratio
