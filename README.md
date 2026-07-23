<div align="center">

# WhatIfIBought

**代币化美股、加密现货 / 永续、大宗商品、BTC 预测与 AI 量化的虚拟交易实验平台**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.2.0-blue)](https://github.com/alibaba/spring-ai-alibaba)
[![React](https://img.shields.io/badge/React-19.2-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![Vite](https://img.shields.io/badge/Vite-7.2-646CFF?logo=vite&logoColor=white)](https://vite.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

用户通过 LinuxDo OAuth 登录，用虚拟资金体验代币化美股、加密货币现货 / 永续合约、大宗商品、BTC 5 分钟涨跌预测和小游戏，并观察一套"只预测不下单"的 AI 量化研判。

线上地址：https://wtfibought.com

</div>

---

## 当前定位

WhatIfIBought 是一个偏实验性质的模拟交易系统，后端按业务域拆成 **3 个独立进程**（feed 行情上游 / sim 真人模拟 / quant 量化研究）+ `wiib-common` 共享层，经 **Redis 行情总线**和**共享 PostgreSQL** 协作，进程级故障隔离（一个崩不连累其他）。

- **wiib-feed（:8081）**：统一接入 Binance（现货 + 永续 WS/REST）与 Polymarket，写入 Redis（Stream / KV / Pub-Sub），crypto 永续 5m K 线落库 PostgreSQL。上游进程，不对外。
- **wiib-sim（:8080）**：真人模拟交易 + 游戏 + BTC 预测，账本 = 自研模拟盘 DB，对外提供 REST / WebSocket（**唯一对外进程**，前端连它）。
- **wiib-quant（:8082）**：AI 量化**只预测、不碰模拟盘账本**（结构化预测落库 + 记分卡）；FIBO / LIQFADE / SQZMOM / TURTLE 四策略由 5m K 线收盘驱动，执行目标二选一（`strategy.execution.target=sim|testnet`）：本平台模拟盘的独立量化账户 `quant-<策略ID>`，或 Binance USDT-M Testnet。

> **已下线**：老 GBM 虚拟股票、期权（后端全套已删）。现在的"股票"= **bStock 代币化美股**，走真实 Binance 现货行情。

当前标的：

| 品类 | 标的 |
|---|---|
| 加密现货 / 永续 | `BTC` `ETH` `DOGE` `SOL` `XRP` `BNB` |
| bStock 代币化美股（10） | NVDA · TSLA · MU · SNDK · CRCL · MSTR · AMD · SPCX · QQQ · SOXL（Binance 现货，如 `NVDABUSDT`） |
| 大宗商品 | 黄金 `XAUUSDT` · 原油 `CLUSDT`（TradFi 永续，无现货） |
| 策略实盘篮子 | FIBO: `BTC/ETH` · LIQFADE: `BTC/ETH/DOGE` · SQZMOM: `SOL/DOGE/XRP` · TURTLE: `SOL/ETH/DOGE/BNB` |

> `BNB` 只为 TURTLE 篮子在 feed 侧拉流，**不开放用户交易**；用户可交易的加密标的仍是上表 5 个。

---

## 功能概览

### 交易系统

- **bStock 代币化美股**：真实 Binance 现货行情（价与实时 5m 蜡烛来自 feed→Redis，K 线历史代理 REST），含公司基本面；下单挂靠统一保证金账户。
- **加密货币现货**：BTC/ETH/DOGE/SOL/XRP，接入 Binance 实时行情，市价 / 限价单。
- **永续合约**：全仓 / 逐仓双模式（全仓为主，整个余额钱包为仓位兜底，强平线随净值与全部仓位浮盈亏实时变化），多 / 空双向，1-150 倍分档杠杆（对齐 Binance 档位表），maker 0.02% / taker 0.04%，真实资金费率（每 8h 按 Binance premiumIndex 双向收付，拉取失败回退 0.01%），自动强平。
- **大宗商品**：黄金 / 原油 TradFi 永续。
- **保证金 / 计息 / 爆仓 / 破产恢复**：借款买入统一保证金账户，交易日 17:00 计息 + 爆仓检查，09:00 破产用户幂等恢复。
- **BTC 5 分钟涨跌预测**：接入 Polymarket 盘口 + Chainlink BTC 价格线，5 分钟窗口自动结算（结算基准 = Polymarket 开 / 收盘价，Chainlink 仅前端价格线展示），动态手续费。
- **策略实盘**：FIBO（5m 斐波回撤限价）、LIQFADE（5m 强平瀑布 fade 仅做多，瀑布 / premium 折价 / taker 卖压三签名至少二，1h 时间出场）、SQZMOM（4H 压缩释放仅做空）、TURTLE（4H 通道突破 90 入 / 15 出，盘中触价入场，多空对称，2×ATR 灾难止损不设固定止盈），由 5m K 线收盘驱动；执行 sim（每策略独立账户，与真人同规则、资金隔离）或 Binance Testnet，默认四策略全启跑 sim 轨、仅白名单 symbol。
- **策略监控页**：四策略账户全景（余额 / 权益 / 盈亏 / 持仓 / 已平仓历史）、各策略 × 币种实时信号快照（通道位置 / 压缩计数 / 签名命中），管理员可手动整仓市价平；testnet 轨另有独立看板（总览 / 成交 / 日历格 / 净值曲线 / 成交质量）。

### AI 量化研判（双轨研判工作台）

**卖点不是"预测准"，是"工程可信"**——方向预测已被 walk-forward + 置换检验证伪（不做），vol 预测 / vol-state 经统计验证有 skill（工具化 + 线上记分卡公开战绩）。

- **定时轨**（标的 BTC / ETH）：每 5m 零 LLM 数值快照（vol 三腿 + regime + 脆弱度 + 信号面板）落库，作记分卡预测点；1h 定频 + 波动哨兵插队触发深研判（新闻 → Bull∥Bear → Judge）。
- **对话轨**（管理员专属）：`ChatWorkbenchController` SSE 流式，SupervisorAgent（深模型）动态调度 market / quant / news 子 agent（浅模型），PostgresSaver 续聊 + Store 记忆 + HITL 确认闸。
- **MCP Server**：同一工具层暴露 6 个只读量化工具（SSE 端点，刻意排除新闻与深研判等贵操作），Claude Desktop 等任意 MCP 客户端可直连。
- **研究工具**：策略 K 线 / 组合回测引擎与 walk-forward 样本外评估（vol / regime / direction）REST API。
- 详见下方「AI 量化链路（双轨研判工作台）」。

### 行情与实时数据

- **Binance WS**：现货 miniTicker、永续 markPrice、永续 miniTicker、forceOrder（订全市场流白名单过滤）、aggTrade、depth20、K 线流（crypto 永续 5m 驱动策略 / 预测并落库；15m / 1h、大宗商品与 bStock 现货 5m 仅广播）。
- **Binance REST**：K 线、ticker、funding、OI、多空比、大户持仓、taker 买卖比、盘口。
- **断线韧性**：WS 断开自动切 REST 轮询保价不中断，重连后按离线区间高低价补触发错过的限价单 / 强平；K 线缺口 REST 回补；流健康注册表 + 管理端手动重试。
- **Deribit**：DVOL 与期权 book summary（供 quant 做 IV / vol 上下文，非用户交易）。
- **宏观 / 资金面**：farside ETF 资金流、稳定币流通量、IV / OI 分位、Fear & Greed 指数（供 quant 快照上下文）。
- **Polymarket**：BTC 预测 live-data、UP/DOWN CLOB 盘口。
- **WebSocket**：SockJS + STOMP，前端订阅行情、预测、量化信号等 topic。

### 游戏与社交

- 每日 Buff 抽奖、21 点、Mines、Video Poker。
- 总资产排行榜（总额下拆余额钱包 / 游戏钱包现金，另算硬实力盈亏与 Buff 收益）、每日 00:00 全员资产快照（30 天资产曲线）、用户行为分析 Agent（quant 经 internal API 读 sim 行为数据）。
- **留言板**：全站唯一，根评论 + 子评论两层，赞踩只存计数（去重靠 Redis Set 不落记录表），管理员可删除 / 禁言（`user.muted_until` 到期自动解禁）。赞与回复产生通知，顶栏信封角标 + WebSocket 点对点推送，前端按「类型 + 评论」合并展示。
- **自助重置账户**：清空全部交易与游戏数据回到初始资金，每周一次、需逐字输入用户名确认。清理先注销 Redis 触发索引再事务删表，失败则把索引装回去。

---

## 技术栈

| 层级 | 技术 | 版本 / 说明 |
|---|---|---|
| 后端 | Spring Boot | 3.4.1 |
| 语言 | Java | 21，启用 Virtual Threads |
| AI 框架 | Spring AI Alibaba | BOM / agent-framework 1.1.2.0（StateGraph + SupervisorAgent） |
| LLM 接入 | Spring AI OpenAI Starter | 1.1.2（OpenAI Compatible + Responses API，思考档位可配，配置在 DB） |
| MCP | Spring AI MCP Server (WebMVC/SSE) | 1.1.2 |
| ORM | MyBatis-Plus | 3.5.10 |
| 认证 | Sa-Token | 1.42.0（LinuxDo OAuth + 账号密码 / 邀请码注册，开关控制） |
| 数据库 | PostgreSQL | 共享主库，建表见 `sql/init.sql`（28 张）+ `sql/bstock.sql` |
| 缓存 | Redis + Caffeine | 行情总线、分布式锁、ZSet 索引 + 本地热缓存 |
| 观测 | Actuator + Micrometer Prometheus | Graph 节点本地观测 |
| 前端 | React + TypeScript | React 19.2 / TypeScript 5.9 |
| 构建 | Vite | 7.2 |
| UI | TailwindCSS 4 + ECharts 6 + lightweight-charts + lucide-react | 拟物风自研组件，无 UI 框架依赖 |
| 状态 / 路由 | Zustand + React Router | 5.0 / 7.x |
| 实时通信 | WebSocket | STOMP + SockJS |

---

## 系统架构

```mermaid
flowchart TD
    UI["React Web<br/>bStock / Coin / Commodity / Portfolio / Prediction<br/>AI / Scorecard / Strategies / Testnet / ForceOrders<br/>Games / Ranking / Comments / Me / Admin"]
    EXC["Binance / Polymarket / Deribit<br/>外部行情源"]
    FEED["wiib-feed :8081<br/>交易所 WS/REST 接入"]
    SIM["wiib-sim :8080<br/>真人模拟交易 + 游戏 + 预测<br/>账本 = 自研模拟盘"]
    QUANT["wiib-quant :8082<br/>AI 量化研判 + 策略执行"]
    DB[("PostgreSQL<br/>共享 business + quant + ai runtime")]
    REDIS[("Redis 行情总线<br/>Stream + KV + Pub/Sub<br/>+ lock + zset")]
    TESTNET["Binance USDT-M Testnet<br/>策略实盘账本(target=testnet)"]

    EXC -->|WS / REST| FEED
    FEED -->|写行情| REDIS
    FEED -->|K线落库| DB
    UI <-->|REST / STOMP| SIM
    REDIS -->|消费行情| SIM
    REDIS -->|消费行情| QUANT
    SIM --> DB
    QUANT --> DB
    SIM <-->|"internal API<br/>行为数据 + 量化交易(target=sim)"| QUANT
    QUANT -->|"策略下单(target=testnet)"| TESTNET
```

---

## AI 量化链路（双轨研判工作台）

定位：**方向预测已证伪（不做），vol 预测 / vol-state 有真 skill**（工具化 + 线上记分卡）。深度使用 spring-ai-alibaba 1.1.2.0：确定性用图（StateGraph），认知用 Agent（Supervisor + ReactAgent）。

### 定时轨（StateGraph，图由 MermaidGenerator 自动生成）

```mermaid
---
title: 定时轨：数值快照 + 深研判
---
flowchart TD
    __START__((start))
    __END__((stop))
    build_snapshot("build_snapshot<br/>零LLM: vol三腿(PIT档界)+regime+脆弱度+信号面板")
    persist_snapshot("persist_snapshot<br/>每5m落库,记分卡预测点")
    news_context("news_context<br/>新闻LLM浓缩")
    bull("bull 辩手")
    bear("bear 辩手")
    judge("judge<br/>研判叙事+情景分布+失效条件+无方向态")
    persist_analysis("persist_analysis")
    condition1{"gate: 1h定频/哨兵插队/手动"}
    __START__ --> build_snapshot
    build_snapshot --> persist_snapshot
    persist_snapshot -.-> condition1
    condition1 -.->|deep| news_context
    condition1 -.->|end| __END__
    news_context --> bull
    news_context --> bear
    bull --> judge
    bear --> judge
    judge --> persist_analysis
    persist_analysis --> __END__
```

- 标的 BTC / ETH；快照段每 5m 零 LLM；Bull∥Bear 走框架原生并行边（fan-out/fan-in）。
- 深研判每轮 4 次 LLM 调用（新闻 + Bull + Bear + Judge），1h 定频基线 + 哨兵插队（冷却）——约 200 调用/天。
- 验证闭环：5m K 线收盘事件驱动即时对账到期预测点（每小时 cron 仅断流兜底；QLIKE vs naive 基准 + vol-state 命中，PIT 档界随快照入库）；叙事轨另按 Judge 三情景概率记 Brier（vs 均匀基线）与情景命中。`/quant/scorecard` 出战绩。

### 对话轨（SupervisorAgent 多 agent 编排）

`ChatWorkbenchController`（SSE 流式，调度过程可视化）：`workbench_supervisor`（深模型）动态调度 `market_agent` / `quant_agent` / `news_agent`（浅模型，深浅分层省成本），全部工具与定时轨**共用一个工具层**。横切：PostgresSaver 断点续聊、DatabaseStore 跨会话记忆、`run_deep_analysis` 贵操作 HITL 确认闸、CallLimit + Summarization 控预算。

> 韧性用自研 `ResilientModelInterceptor`（退避重试 + supervisor 兜底模型），**不用**框架自带的 ModelRetry / ModelFallback——后者在 1.1.2.0 会把流式响应的 `Flux` 强转 `Message` 必炸，工作台是全流式路径。同理 SSE 外发需按 `OutputType` 丢弃 `*_FINISHED` 聚合帧（与增量重复），只拿它当分段边界过滤 supervisor 的派发 JSON。

同一工具层的第三个消费方：**MCP Server**（SSE 端点）——Claude Desktop 等任意 MCP 客户端可直连调用只读量化工具。

---

## 实时数据链路

### Binance

```text
wiib-feed（上游进程）  交易所 WS → Redis
  -> spot miniTicker        -> Redis 价格 KV + Pub/Sub(feed:price)
  -> futures markPrice@1s   -> Redis mark/指数价 KV + Pub/Sub(feed:price)
  -> forceOrder(全市场流)    -> 白名单过滤 -> force_order 表(DB, 天然跨进程)
  -> aggTrade               -> Redis Stream(market:orderflow:<sym>, quant 读侧聚合)
  -> depth20@100ms          -> Redis KV(DepthStreamCache)
  -> K 线收盘                -> Redis Stream(stream:kline:closed) + taker买量5m桶 KV + crypto 5m 落库
  -> WS 断线                 -> REST 轮询兜底保价, 重连后按区间高低价补触发限价/强平

wiib-sim / wiib-quant（消费进程）  从 Redis 消费 feed 写入的行情
  sim:   撮合/强平 + 预测回合消费
  quant: K线驱动预测/策略 + 波动哨兵 + LIQFADE 拉 premium/taker KV
```

sim 订阅 `feed:price` 后做撮合（feed 本身不撮合）：价格更新触发现货限价单、永续强平、止损、止盈检查。

### Polymarket BTC 预测

```text
Polymarket live-data -> Chainlink BTC price -> /topic/prediction/price（展示价格线）
Polymarket CLOB      -> UP/DOWN bid/ask     -> /topic/prediction/market
5min window rotation -> 回合事件(lock/create/settle)走 Redis Stream 保 FIFO
                     -> sim 锁上轮 -> 拉 Polymarket 开/收盘价 -> 结算下注
```

动态手续费：`effectiveRate = 0.25 * (p * (1 - p))^2`，clamp 0.1% ~ 2%。

---

## 项目结构

后端 **4 个 Maven module / 3 个独立进程**（+ 共享层），经 Redis 行情总线和共享 PostgreSQL 协作。

```text
whatifibought/                        # Maven 多 module 聚合 reactor
├── pom.xml
├── README.md
├── .env.example                      # 环境配置模板（唯一需手工填值的文件，复制为 .env.local / .env）
├── start-local.ps1 / .bat            # 本地一键启动三服务（bat 为双击入口，转调 ps1）
├── docker-compose.yml                # 三进程编排（无私有值，配置全在 .env）
├── redis-compose.yml                 # Redis 主从 + 哨兵栈（可选）
├── sql/                              # init.sql（28 表）+ bstock.sql（bStock 静态表 + 种子）
├── docs/                             # 设计文档（specs / plans：feed 流健康、FIBO 重构、评论区、UI 风格等）
├── wiib-common/                      # 共享层：被 feed/quant/sim 共同依赖，三者互不直接依赖
│   └── market/ broadcast/ cache/ config/ mapper/ entity/ dto/ enums/ util/ ...
│                                     # 行情通道 / Depth·OrderFlow 缓存 / BinanceRestClient
│                                     # / KlineHistoryStore / ForceOrder mapper / InternalApiFilter
├── wiib-feed/                        # ① 数据流上游进程（:8081）
│   └── BinanceWsClient / PolymarketWsClient / KlineStreamCache / health(内部流健康+重试)
├── wiib-quant/                       # ② 量化 + 策略研究进程（:8082，只分析不碰模拟盘账本）
│   ├── agent/
│   │   ├── quant/                    # 定时轨 StateGraph（快照 + 深研判）+ 节点
│   │   ├── analysis/                 # 深研判持久化 / 记分卡 / vol + 叙事验证
│   │   ├── research/                 # 回测 / 因子 / 预测 / 样本外评估（vol/regime/direction）
│   │   ├── toolkit/                  # 统一工具层（agent/定时轨/MCP 三处共用）
│   │   ├── chat/                     # 对话轨 SupervisorAgent 工作台
│   │   ├── strategy/                 # FIBO/LIQFADE/SQZMOM/TURTLE + 回测引擎
│   │   │                             # + 执行层(testnet|sim) + 账户监控
│   │   ├── llm/                      # ResilientModelInterceptor / Responses API 接入
│   │   └── mcp/ behavior/ binance/ external/ ...
│   │                                 # MCP server / 行为 agent / Testnet 客户端
│   │                                 # / ETF 流爬取 / SimInternalClient
│   ├── controller/ task/ mapper/ config/   # ResearchEval/Strategy/Testnet... / 调度 / DeribitClient
│   └── AGENT_REFACTOR_PLAN.md        # 量化 Agent 双轨改造总纲
├── wiib-sim/                         # ③ 真人模拟交易进程（:8080，账本=自研模拟盘 DB，对外）
│   └── controller/ service/ mapper/ config/ task/
│                                     # 交易(bStock/crypto/futures) / 游戏 / 预测 / 结算 / WS 网关
│                                     # + BehaviorDataController（internal API 供 quant 调）
└── wiib-web/                         # React 前端（拟物风）
    └── src/                          # App.tsx / pages/ components/ hooks/ stores/ api/
```
---

## 并发与一致性

| 机制 | 用途 |
|---|---|
| Virtual Threads | WS 消息处理、量化采集、调度任务、异步广播 |
| Redis 分布式锁 | 订单、仓位、用户、游戏操作互斥 |
| 数据库 CAS | 订单、预测回合、结算状态机 |
| Redis ZSet | 限价单、强平价、止损、止盈触发索引 |
| Redis Pub/Sub | 多实例 WebSocket 广播 |
| Caffeine + Redis | 本地热缓存 + L2 分布式缓存 |
| Micrometer | Graph 节点耗时 / 错误指标 |

---

## 部署

### 环境要求

| 依赖 | 最低版本 | 说明 |
|---|---:|---|
| JDK | 21 | 需要 Virtual Threads |
| Maven | 3.9+ | 后端构建 |
| Node.js | 18+ | 前端构建 |
| PostgreSQL | 14+ | 主数据库（三进程共享） |
| Redis | 6+ | 行情总线、锁、Pub/Sub、会话 |

### 1. 克隆项目

```bash
git clone https://github.com/mamawai/wtfibought.git
cd wtfibought
```

### 2. 初始化数据库

```bash
psql -U postgres -c "CREATE DATABASE wiib;"
psql -U postgres -d wiib -f sql/init.sql      # 业务 + 量化 + AI runtime（28 张表）
psql -U postgres -d wiib -f sql/bstock.sql    # bStock 代币化美股静态表 + 10 只种子
```

### 3. 后端配置

密钥与结构分离：`application.yml` 直接入库（只有 `${VAR}` 占位符），真实值只存在根目录 env 文件里。本地开发复制模板填值即可：

```bash
cp .env.example .env.local    # 填 PG_USER / PG_PASSWORD（必填），其余可选
```

启动时按 `本机环境变量 > .env.local > yml 默认值` 解析；线上 Docker 部署同一文件命名为 `.env`（见第 6 节）。

要点：

- **共享库 / 总线**：三进程指向同一 PostgreSQL `wiib` + 同一 Redis，读同一份 `.env.local`；`INTERNAL_API_TOKEN` 天然一致（进程间 `/internal/**` 鉴权），不填走统一默认值。
- **LLM 配置不在 yml**：唯一来源是 DB（`ai_runtime_config` + `ai_model_assignment`）。启动后用管理员账号进 Admin 页填 LLM（API Key + Base URL + 模型名，Base URL 不含 `/v1`）并给各功能位分配，即时生效、无需重启。
- **quant 必须关掉 Spring AI 的 OpenAI 自动装配**（6 类全关，否则缺 api-key 拒绝启动）：

  ```yaml
  spring:
    ai:
      model: { chat: none, embedding: none, image: none, moderation: none, audio: { speech: none, transcription: none } }
  ```

- **策略实盘执行**（`wiib-quant`，仓库默认即四策略全启、跑 sim 轨）：

  ```yaml
  strategy:
    runtime:   { enabled: true, enabled-ids: FIBO,LIQFADE,SQZMOM,TURTLE }
    execution: { enabled: true, target: sim,
                 symbols: BTCUSDT,ETHUSDT,DOGEUSDT,SOLUSDT,XRPUSDT,BNBUSDT }
  ```

  > 策略由 K 线收盘驱动：`wiib-feed` 的 `binance.symbols` 必须覆盖上面全部标的（缺谁谁永不触发）。
  > `symbols` 是四策略部署篮子的并集；TURTLE 的触价单是 quant 内存态，由 feed 的 futures tick 触发。

### 4. 构建后端

```bash
mvn clean package -DskipTests
# 产物：
#   wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar     :8081 数据上游
#   wiib-quant/target/wiib-quant-0.0.1-SNAPSHOT.jar   :8082 量化策略
#   wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar       :8080 模拟交易（对外）
```

### 5. 构建前端

```bash
cd wiib-web
npm install
npm run build      # 开发：npm run dev（Vite 默认 3000，/api、/ws 代理到 sim :8080，
                   #        /api/ai、/api/testnet、/api/admin/ai-agent 代理到 quant :8082）
```

### 6. 启动

本地一键（Windows，构建 + 依次拉起 feed → sim → quant）：

```powershell
.\start-local.ps1              # 加 -SkipBuild 跳过构建；或直接双击 start-local.bat
```

或手动逐个起（须在仓库根目录执行，`.env.local` 按相对路径解析；IDEA 直接点各模块 Run 也可）：

```bash
java -jar wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar    # :8081 交易所 WS → Redis
java -jar wiib-quant/target/wiib-quant-0.0.1-SNAPSHOT.jar  # :8082 量化研判 + 策略
java -jar wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar      # :8080 模拟交易（对外，前端连它）
```

Docker Compose（三进程全编排；配置放服务器上的 `.env`，与 `.env.example` 同款变量）：

```bash
docker network create wiib-network
docker compose up -d --build
```

> 三服务端口均只绑 127.0.0.1（feed 8081 / sim 8080 / quant 宿主 18082），经 Nginx / Caddy 反代后对外只放 sim。Redis 主从 + 哨兵栈可选 `docker compose -f redis-compose.yml up -d`。

---

## 致谢

<a href="https://linux.do/"><img src="docs/assets/linuxdo.svg" width="40" alt="LinuxDo" /></a>

感谢 [LinuxDo](https://linux.do/) —— 本站登录体系基于 LinuxDo OAuth（Connect），项目的灵感与早期用户也都来自佬友们。真诚、友善、团结、专业，共建你我引以为荣之社区。
