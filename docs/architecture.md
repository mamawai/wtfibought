# 系统架构

这份文档从进程与数据流的视角讲整个平台：三个后端进程如何分工、行情从交易所走到前端经过哪些环节、代码目录怎么摆、并发与一致性靠哪些机制兜住。

wiib-agent 里那套 LLM 装置单独成篇，见 [Agent Harness 架构](./agent-harness/architecture.md)。

## 总览

```mermaid
flowchart TD
    UI["React Web<br/>Home / bStock / Coin / Commodity / TradFi / Portfolio / Prediction<br/>Arena / MyTrader / AI / Backtest / Strategies / Testnet / ForceOrders<br/>Ledger / Trades / PositionHistory / Ranking / Comments / Games / Me / Admin"]
    EXC["Binance / Polymarket<br/>实时行情源"]
    EXT["Deribit / BlockBeats / ETF 流<br/>按需取数，不进总线"]
    FEED["wiib-feed :8081<br/>交易所 WS/REST 接入"]
    SIM["wiib-sim :8080<br/>真人模拟交易 + 游戏 + 预测<br/>账本 = 自研模拟盘"]
    AGENT["wiib-agent :8082<br/>AI 交易员 + 策略执行"]
    DB[("PostgreSQL<br/>共享 business + quant + ai runtime")]
    REDIS[("Redis 行情总线<br/>Stream + KV + Pub/Sub<br/>+ lock + zset")]
    TESTNET["Binance USDT-M Testnet<br/>策略实盘账本(target=testnet)"]

    EXC -->|WS / REST| FEED
    SIM -.->|"REST 回源<br/>资金费率结算 / 预测开收盘价"| EXC
    AGENT -->|REST 直连| EXT
    FEED -->|写行情| REDIS
    FEED -->|K线落库| DB
    UI <-->|"REST / STOMP<br/>其余 /api 与 /ws"| SIM
    UI <-->|"REST / SSE<br/>/api/ai · /api/testnet · /api/admin/ai-agent"| AGENT
    REDIS -->|消费行情| SIM
    REDIS -->|消费行情| AGENT
    SIM --> DB
    AGENT --> DB
    SIM <-->|"internal API<br/>行为数据 + 用户语言<br/>+ AI/策略下单(target=sim)"| AGENT
    SIM -->|"internal API<br/>WS 流健康快照 / 手动重试"| FEED
    AGENT -.->|"策略下单(target=testnet 时)"| TESTNET
```

前端不是只连 sim：AI 页、竞技场、交易员、研判工作台、回测复盘教练、testnet 看板走的都是 agent 的 `/api/ai` / `/api/testnet` / `/api/admin/ai-agent`，其余 `/api` 与 `/ws` 才是 sim。开发期由 Vite proxy 分流，线上由反代按同一套前缀分流——两边规则必须同构，否则本地跑通线上 404。

---

## 实时数据链路

### Binance

```text
wiib-feed（上游进程）  交易所 WS → Redis
  -> spot miniTicker        -> Redis 价格 KV(market:price:) + Pub/Sub(feed:price)
  -> futures markPrice@1s   -> Redis mark KV(market:markprice:) + Pub/Sub(feed:price)
  -> futures miniTicker     -> Redis 合约最新价 KV(market:futures-price:) + Pub/Sub(feed:price)
                               ↑ 独立物理连接：Binance 端点拆分后 markPrice 与 miniTicker 不能同连
  -> forceOrder(全市场流)    -> 白名单过滤 -> force_order 表(DB, 天然跨进程)
  -> aggTrade               -> Redis Stream(market:orderflow:<sym>, 读侧聚合)
  -> depth20@100ms          -> Redis KV(market:depth:, DepthStreamCache)
  -> K 线收盘                -> Redis Stream(stream:kline:closed) + crypto 5m 落库
  -> 所有 tick / K 线        -> Redis Pub/Sub(ws:broadcast:*) -> sim WsBroadcastRelay -> STOMP /topic/**
  -> WS 断线                 -> REST 轮询兜底保价, 重连后按区间高低价补触发限价/强平

wiib-sim / wiib-agent（消费进程）  从 Redis 消费 feed 写入的行情
  sim:   撮合/强平 + 预测回合消费 + ws:broadcast:* 中继给前端
  agent: K线收盘驱动 交易员唤醒 / 策略信号 / 叙事对账；另订 feed:price 跑波动哨兵与策略触价单
```

两条 Redis 链路分工不同，别混：**`feed:price` 是撮合/执行侧的价格总线**，不面向前端——三个订阅者，sim 的撮合与强平（价格更新触发现货限价单、永续强平、止损、止盈检查，feed 本身不撮合）、agent 的波动哨兵、策略的触价单；**`ws:broadcast:*` 才是前端拿实时行情的唯一通道**，由 sim 的 `WsBroadcastRelay` 中继成 STOMP（`crypto` / `futures` / `kline` / `prediction` / `stream-health` / `monitor` 六个频道各对一个 `/topic/**`，其中 prediction 来自 Polymarket 那条链、monitor 由 feed 与 agent 各发一份）。多实例部署时前者按进程各自消费，后者靠 Pub/Sub 让每个实例的 WebSocket 都收到。

### Polymarket BTC 预测

```text
Polymarket live-data -> Chainlink BTC price -> /topic/prediction/price（展示价格线）
Polymarket CLOB      -> UP/DOWN bid/ask     -> /topic/prediction/market
5min window rotation -> 回合事件(lock/create/syncopen/settle)走 Redis Stream 保 FIFO
                     -> sim 锁上轮 -> 取开/收盘价 -> 结算下注
```

开/收盘价由 feed 轮询后写缓存（`syncopen` / `settle` 两个事件），sim 只在缓存没命中时才回源 REST——结算基准要全站一份，各算各的会出现同一回合两个价。

动态手续费：`effectiveRate = 0.25 * (p * (1 - p))^2`，clamp 0.1% ~ 2%。

### 资金费率

```text
sim ScheduledTasks  cron 0 0 0,8,16
  -> FundingRateService.refresh()   一次 premiumIndexAll 全量，只留本平台合约标的
  -> Redis KV(market:funding-rate:<SYM>, TTL 9h)
  -> 扣费 rateForSettlement() / 前端查询 query()  都只读缓存
```

**sim 侧只有结算点这一次调官方资金费率接口**，扣费与前端查询一律读缓存，查询链路绝不打 Binance。拉不到就不动缓存，上一轮的值还能用满 TTL；缓存真空了扣费回退配置里的固定费率（0.01%/8h）。这是个硬约束——在查询链路里"顺手"直连一次 Binance，就等于把限频风险接回了用户请求路径上。

（agent 侧的 `funding_history` / `premiumIndex` 取数工具另有一条只读链路会打 Binance，带熔断与 serve-stale，喂模型用，与 sim 的扣费链路无关。）

---

## 项目结构

后端 5 个 Maven module / 3 个独立进程（wiib-common 与 wiib-quant 是库，不单独起），经 Redis 行情总线和共享 PostgreSQL 协作。另有两个不进 reactor 的前端产物：`wiib-web`（主站，Vite 构建）与 `wiib-intro`（介绍站，纯静态零构建）。

```text
whatifibought/                        # Maven 多 module 聚合 reactor
├── pom.xml
├── .env.example                      # 环境配置模板（唯一需手工填值的文件，复制为 .env.local / .env）
├── start-local.ps1 / .bat            # 本地一键启动三服务（bat 为双击入口，转调 ps1）
├── docker-compose.yml                # 三进程编排（无私有值，配置全在 .env）
├── redis-compose.yml                 # Redis 主从 + 哨兵栈（可选）
├── sql/                              # init.sql（34 表）+ bstock.sql（bStock 静态表 + 10 只种子）
│
├── wiib-common/                      # 共享层：被 feed/agent/quant/sim 共同依赖
│   └── market/ broadcast/ cache/ aspect/ mapper/ entity/ dto/ enums/ util/ ...
│                                     # 行情通道 / Depth·OrderFlow 缓存 / BinanceRestClient
│                                     # / KlineHistoryStore / ForceOrder mapper / InternalApiFilter
│
├── wiib-feed/                        # ① 数据流上游进程（:8081）
│   ├── stream/                       # 11 个 StreamHandler（现货 / 合约 markPrice / 合约 miniTicker
│   │                                 #   / 深度 / 成交 / 强平 / crypto·bStock·商品各周期 K 线）
│   │                                 # + RestFallbackPoller 断线兜底 + MatchPricePublisher
│   └── BinanceWsClient / PolymarketWsClient / KlineStreamCache / monitor
│                                     # / health(内部流健康+重试)
│
├── wiib-agent/                       # ② AI 交易员进程（:8082，AI 下单走 sim 子账户，
│                                     #    策略执行 sim|testnet 可切；量化侧 controller 也由它挂载）
│   │                                 # 纯 LLM harness：六处装置（非 LLM 代码都在 wiib-quant 库里）
│   ├── trader/                       # trader agent：调度/唤醒回路/提示词/交易工具/护栏
│   │                                 # + 计划存取 + 波动哨兵 + 交易员模型工厂
│   │                                 # + 唤醒现场（TraderLiveHub 扇出 / WakeTrace 轨迹，只给主人）
│   ├── learning/                     # reviewer workflow + learning agent：素材组装（硬事实）+ 复盘/学习回路
│   ├── chat/                         # chat agent：router + 子 agent 并行 + summarizer + checkpoint
│   │                                 # + HITL 授权闸门 + 并发闸门
│   ├── behavior/                     # 行为分析 workflow
│   ├── analysis/                     # 深研判（工作台触发）+ 叙事对账 + 手动复盘的 AI 教练提示词
│   ├── toolkit/                      # LLM 工具类（trader / chat 两处共用，取数底座在 quant 的 market/）
│   ├── llm/                          # BYOK 端点库（LlmEndpointService / ByokModelBuilder）
│   │                                 # + 四协议适配（openai 走 Spring AI，responses/anthropic/gemini 自研 ChatModel）
│   │                                 # + ResilientChatService / ToolChoice / CancelSignal
│   │                                 # + 摘要 / 调用限额 / 上游异常归类（给用户看的一句话）
│   ├── runtime/                      # 平台功能位模型分配（只剩 news-translation，Admin 热更）
│   └── controller/ task/ mapper/ monitor/ config/
│                                     # AiAgent/Trader/LlmEndpoint/ReplayCoach 接口 / 快讯采集翻译·叙事对账 / SaToken
│
├── wiib-quant/                       # ③ 量化能力库（非进程，被 wiib-agent 依赖并扫描挂载）
│   ├── market/                       # 行情数据链路：领域事件 / 采集→特征快照 / 取数缓存
│   │                                 # + 指标·结构计算器 + 期权/资金面/跨市场服务 + 收盘流消费
│   ├── research/                     # 量化研究库：因子/预测/标注/评估/风险指标
│   ├── strategy/                     # FIBO/SQZMOM/TURTLE + 回测引擎
│   │                                 # + 执行层(testnet|sim) + 账户监控
│   ├── external/                     # 进程外客户端：binance testnet / blockbeats / deribit
│   │                                 # / ETF 流爬取 / sim internal（行为数据 + 合约下单）
│   └── controller/ task/ mapper/     # ResearchEval/Strategy/Testnet/Backtest 接口 / 日历·K线采集
│
├── wiib-sim/                         # ④ 真人模拟交易进程（:8080，账本=自研模拟盘 DB，对外）
│   ├── ledger/                       # 资金记账切面：@Ledger + LedgerAspect + 行映射
│   └── controller/ service/ mapper/ config/ task/
│                                     # 交易(bStock/crypto/futures) / 游戏 / 预测 / 结算 / WS 网关
│                                     # + 账单·排行·全站成交·仓位历史
│                                     # + BehaviorDataController（internal API 供 agent 调）
│
├── wiib-web/                         # React 前端（「海报数字」设计体系）
│   └── src/                          # App.tsx / pages/ components/ hooks/ stores/ api/ i18n/ lib/ types/
│                                     # index.css 是设计 token 与原子类的唯一出处
│
└── wiib-intro/                       # 项目介绍站 intro.wtfibought.com
                                      # 纯静态 HTML/CSS/JS，无构建无后端无端口，不是 Maven module
```

---

## 并发与一致性

| 机制 | 用途 |
|---|---|
| Virtual Threads | WS 消息处理、量化采集、调度任务、异步广播 |
| Redis 分布式锁 | 订单、仓位、用户、游戏操作互斥 |
| 数据库 CAS | 订单、预测回合、结算状态机 |
| `UPDATE ... RETURNING` | 资金变动与变动后余额同条 SQL 取回，账本不重查、不产生读写间隙 |
| Redis ZSet | 限价单、强平价、止损、止盈触发索引 |
| Redis Pub/Sub | 多实例 WebSocket 广播 |
| Caffeine + Redis | 本地热缓存 + L2 分布式缓存 |
| single-flight | 行情快照组装：N 个会话同时问一个币只真采集一次，其余等同一份结果 |
| 信号量 + 用户集合 | 对话并发闸门（全局 10 轮 + 每用户 1 轮），超限直接拒绝不排队 |
| 熔断 + serve-stale | Binance 收到 429/418 记冷却期，期内不发请求改吐带年龄上限的旧数据（资金费 8h / 标记价 15min / 盘口不兜） |
