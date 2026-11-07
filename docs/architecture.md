# 系统架构

这份文档从进程与数据流的视角讲整个平台：三个后端进程如何分工、行情从交易所走到前端经过哪些环节、代码目录怎么摆、并发与一致性靠哪些机制兜住。

wiib-agent 里那套 LLM 装置单独成篇，见 [Agent Harness 架构](./agent-harness/architecture.md)。

## 总览

```mermaid
flowchart TD
    UI["React Web<br/>Home / bStock / Coin / Commodity / TradFi / Portfolio / Prediction<br/>Arena / MyTrader / AI / Backtest / Strategies / Testnet / ForceOrders / EconCalendar<br/>Ledger / Trades / PositionHistory / Ranking / Comments / Games / Me / Admin"]
    EXC["Binance / Polymarket<br/>实时行情源"]
    EXT["Deribit / BlockBeats / TradingView / Hyperliquid<br/>直连取数，不进总线"]
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
  （订阅范围：现货各流订 symbols ∪ stock-symbols；合约各流订 symbols ∪ commodity / tradfi / crypto-futures 三组纯合约；K 线另见下）
  -> spot miniTicker        -> Redis 价格 KV(market:price:)
  -> futures markPrice@1s   -> Redis mark KV(market:markprice: + 写入时刻 market:markprice-at:) + Pub/Sub(feed:price, type=markprice)
  -> futures miniTicker     -> Redis 合约最新价 KV(market:futures-price:) + Pub/Sub(feed:price, type=futures)
                               ↑ 独立物理连接：Binance 端点拆分后 markPrice 与 miniTicker 不能同连
  -> 名单外的币 markPrice@1s / miniTicker（代码里叫"交易员的币"，另两条连接，订阅集合 = Redis set futures:trader:symbols：
                               交易员（wiib-alpha）登记要下单的币 ∪ 不在 feed 配置里的未平仓位，sim 维护，feed 每 10 秒看一次、变了重连）
                            -> 只写 market:markprice:（带写入时刻，sim 市价开仓看它超 30 秒没更新就拒）/ market:futures-price:，不发 feed:price、不推前端
  -> forceOrder(全市场流)    -> 白名单过滤 -> force_order 表(DB, 天然跨进程)
  -> aggTrade（合约 / 现货）  -> Redis Stream(market:orderflow:<sym> / market:spot-trade:<sym>)，sim 按顺序读来判限价/止损/止盈，合约那条 agent 还读侧聚合订单流
  -> depth20@100ms（合约 / 现货）-> Redis KV(market:depth: / market:spot-depth:, DepthStreamCache)，sim 市价成交扫它
  -> K 线（4 条连接，都推前端；只有加密币 5m 往下游送）
       5m      加密币(symbols ∪ crypto-futures-symbols) -> 收盘 bar 落库(kline_history) + Redis Stream(stream:kline:closed)
       5m      非加密纯合约(commodity ∪ tradfi)          -> 只推前端
       15m/1h  全部合约标的                              -> 只推前端（蜡烛图 15m / 1h 档；4h / 1d 没有连接，前端用价格 tick 更新最后一根）
  -> 价格 tick（现货价 / 标记价 / 合约最新价）与 K 线 -> Redis Pub/Sub(ws:broadcast:*) -> sim WsBroadcastRelay -> STOMP /topic/**
  -> WS 断线                 -> 广播断线帧, 重连后发 gap 事件(空窗区间, kind=mark/futures/spot), sim 拉 1m K 线补强平/补触发

wiib-sim / wiib-agent（消费进程）  从 Redis 消费 feed 写入的行情
  sim:   成交 Stream 触发限价/止损/止盈（消费组 sim）+ 标记价强平 + 预测回合消费 + ws:broadcast:* 中继给前端
  agent: K线收盘驱动 交易员唤醒 / 策略信号 / 叙事对账；另订 feed:price 跑波动哨兵、盯盘哨兵与策略触价单
```

两条 Redis 链路分工不同，别混：**`feed:price` 是撮合/执行侧的价格总线**，不面向前端——四个订阅者，sim 的强平（标记价触发逐仓强平和全仓精查；限价单、止损止盈看成交价，走成交 Stream，feed 本身不撮合）、agent 的波动哨兵和盯盘哨兵、策略的触价单；**`ws:broadcast:*` 才是前端拿实时行情的唯一通道**，由 sim 的 `WsBroadcastRelay` 中继成 STOMP（`crypto` / `futures` / `kline` / `prediction` / `stream-health` / `monitor` 六个频道各对一个 `/topic/**`，其中 prediction 来自 Polymarket 那条链、monitor 由 feed 与 agent 各发一份）。多实例部署时前者按进程各自消费，后者靠 Pub/Sub 让每个实例的 WebSocket 都收到。

### Polymarket BTC 预测

```text
Polymarket live-data -> Chainlink BTC 60s TWAP -> /topic/prediction/price（官网当前价：价格线 + 涨跌）
Polymarket CLOB      -> UP/DOWN bid/ask（首条 book 快照种价，买一 0 / 卖一 1 是空档，删掉）-> /topic/prediction/market
5min window rotation -> 回合事件(lock/create/syncopen/settle)走 Redis Stream 保 FIFO
                     -> sim 锁上轮 -> 取开/收盘价 -> 结算下注
```

开/收盘价由 feed 轮询后写缓存（`syncopen` / `settle` 两个事件），sim 只在缓存没命中时才回源 REST——结算基准要全站一份，各算各的会出现同一回合两个价。

结算规则跟 Polymarket（结算源 Chainlink BTC/USD TWAP-60s 数据流）：开 / 收盘价是这条流在开 / 收盘时刻的值，流在 T 时刻 = 截至 T−3 秒的 60 个整秒现货价均价，所以收盘价实际是收盘前 63~3 秒的均价；收盘不低于开盘判 UP（相等算 UP），否则 DOWN；两个价都从 crypto-price 接口带 `twapEnabled=true&twapLookbackSeconds=60` 取（开盘后 2~3 秒就有，feed 每秒取一次），不带参数回的是边界时刻现价、会判反；取不到价才 VOID 退本金。页面当前价、差值、折线图都用 RTDS `crypto_prices_twap_sixty` 这条流，和官网同源；赔率按钮是各自的卖一 / 买一，空档显示 `--`。

手续费按 Polymarket 吃单费公式（`PredictionFee`）：`fee = 份数 × 0.07 × p × (1 − p)`，买卖都按吃单算。

feed 收到盘口消息只记内存，每 50ms 一拍：先看要不要换回合，再把有变化的盘口写 Redis、推给页面；每秒补推一次当前盘口。按消息自带时间戳，CLOB 盘口落后 3 秒、TWAP 落后 5 秒就换一条连接。

### 资金费率

```text
feed FuturesStreamHandler / TraderFuturesStreamHandler  markPrice@1s 推送自带当期费率 r 和下次结算时刻 T
  -> FundingRateWriter  T 变了马上写、没变每 5 秒写一次 -> Redis KV(market:funding:<SYM>, TTL 10min)
sim ScheduledTasks  每分钟
  -> FundingRateService.settleDue()  有仓位的币：Redis 记"上次看到的 T"，feed 把它挪到后面了就是这一期结算了
     -> /fapi/v1/fundingRate 取这一期的实际结算费率（记录没出来下一分钟再试）
     -> 给结算时刻之前开的仓收，先记已结算再收，不重收
  -> 前端查询 query()  只读 feed 写的
```

各币结算间隔不同（多数 8 小时，也有 4 小时、1 小时的），全按 Binance 的实际结算时刻和费率收。**sim 侧只在某个币结算了那一分钟调一次官方结算记录接口**，前端查询一律读 feed 写的缓存，查询链路绝不打 Binance；feed 停了缓存过期，sim 不按旧时刻收，起来后 T 已经挪了照样补上这一期。这是个硬约束——在查询链路里"顺手"直连一次 Binance，就等于把限频风险接回了用户请求路径上。

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
├── sql/                              # init.sql（37 表）+ bstock.sql（bStock 静态表 + 10 只种子）
│
├── wiib-common/                      # 共享层：被 feed/agent/quant/sim 共同依赖
│   └── market/ broadcast/ cache/ aspect/ mapper/ entity/ dto/ enums/ util/ ...
│                                     # 行情通道 / Depth·OrderFlow 缓存 / BinanceRestClient
│                                     # / KlineHistoryStore / ForceOrder mapper / InternalApiFilter
│
├── wiib-feed/                        # ① 数据流上游进程（:8081）
│   ├── stream/                       # 14 个 StreamHandler（现货价 / 现货成交 / 现货深度 / 合约 markPrice
│   │                                 #   / 合约 miniTicker / 合约深度 / 合约成交 / 强平 / 名单外的币两条
│   │                                 #   / K 线四条：加密币 5m、非加密 5m、全部合约 15m 与 1h）
│   │                                 # + GapTracker 断连空窗记录 + MatchPricePublisher
│   └── BinanceWsClient / PolymarketWsClient / KlineStreamCache / monitor
│                                     # / health(内部流健康+重试)
│
├── wiib-agent/                       # ② AI 交易员进程（:8082，AI 下单走 sim 子账户，
│                                     #    策略执行 sim|testnet 可切；量化侧 controller 也由它挂载）
│   │                                 # 纯 LLM harness：五处装置（非 LLM 代码都在 wiib-quant 库里）
│   ├── trader/                       # trader agent：调度/唤醒回路/提示词/交易工具/护栏
│   │                                 # + 计划存取 + 波动哨兵 + 交易员模型工厂
│   │                                 # + 唤醒现场（TraderLiveHub 扇出 / WakeTrace 轨迹，只给主人）
│   ├── learning/                     # reviewer workflow：素材组装（硬事实）+ 复盘回路 + 经验账本
│   ├── chat/                         # chat agent：单 ReAct 主 agent + 后台子运行（run/）+ 对话工具（tools/）
│   │                                 # + HITL 授权闸门 + 并发闸门（gate/）+ 会话上下文存取（store/）
│   ├── behavior/                     # 行为分析 workflow
│   ├── analysis/                     # 深研判（工作台触发）+ 叙事对账 + 手动复盘的 AI 教练提示词
│   ├── toolkit/                      # LLM 工具类（trader / chat 两处共用，取数底座在 quant 的 market/）
│   ├── llm/                          # BYOK 端点库（LlmEndpointService / ByokModelBuilder）
│   │                                 # + 四协议适配（openai 走 Spring AI，responses/anthropic/gemini 自研 ChatModel）
│   │                                 # + ReactLoop 手写 ReAct 循环 / LlmClient / ToolOptions
│   │                                 # + 摘要 / 调用限额 / 上游异常归类（给用户看的一句话）
│   ├── runtime/                      # 平台功能位模型分配（只剩快讯翻译主备两位，Admin 热更）
│   └── controller/ task/ mapper/ monitor/ config/
│                                     # AiAgent/Trader/LlmEndpoint/ReplayCoach 接口 / 快讯采集翻译·叙事对账 / SaToken
│
├── wiib-quant/                       # ③ 量化能力库（非进程，被 wiib-agent 依赖并扫描挂载）
│   ├── market/                       # 行情数据链路：领域事件 / 采集→特征快照 / 取数缓存
│   │                                 # + 指标·结构计算器 + 期权/资金面服务 + 收盘流消费
│   ├── research/                     # 量化研究库：因子/预测/标注/评估/风险指标
│   ├── strategy/                     # FIBO/SQZMOM/TURTLE + 回测引擎
│   │                                 # + 执行层(testnet|sim) + 账户监控
│   ├── whale/                        # Hyperliquid 大户持仓：每日地址池认证 + 10 分钟持仓轮询 + 查询
│   ├── external/                     # 进程外客户端：binance testnet / blockbeats / deribit / hyperliquid
│   │                                 # / sim internal（行为数据 + 合约下单）
│   └── controller/ task/ mapper/     # ResearchEval/Strategy/Testnet/Backtest/Whale 接口 / 日历·K线采集
│
├── wiib-sim/                         # ④ 真人模拟交易进程（:8080，账本=自研模拟盘 DB，对外）
│   ├── ledger/                       # 资金记账：@Ledger + LedgerAspect + LedgerTx（类内事务段压标签）+ 行映射
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
