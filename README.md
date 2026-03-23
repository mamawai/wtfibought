# WhatIfIBought

虚拟股票模拟交易平台 —— "如果当初买了会怎样"

用户通过 [LinuxDo](https://linux.do) OAuth 登录，使用虚拟资金在 AI 生成的行情中进行模拟股票/期权/永续合约交易，附带小游戏。

线上地址: https://linuxdo.stockgame.icu

## 功能概览

**交易系统**
- 市价单即时成交（±2%滑点保护）、限价单挂单等待触发
- T+1 资金结算，0.05% 手续费
- 杠杆交易（借款买入、计息、爆仓清算）
- CALL/PUT 期权交易，Black-Scholes 定价，自动到期结算
- 加密货币现货交易（BTC/USDT、PAXG/USDT、ETH/USDT），接入 Binance 实时行情，支持市价/限价单
- 加密货币永续合约（最高 100 倍杠杆，逐仓保证金，多/空双向，分批止损/止盈，0.01%/8h 资金费率，自动强平）
- BTC 5分钟涨跌预测（接入 Polymarket 真实盘口，Chainlink 价格源，动态手续费，自动结算）

**行情系统**
- AI 每日生成 20 只股票的分时行情（1440 个价格点）
- WebSocket(STOMP) 每 10 秒实时推送行情、资产变动、订单状态
- Binance 双 WebSocket 流：现货价格（miniTicker ~1次/秒）+ 永续合约标记价格（markPrice @1s）
- TradingView 嵌入式 K 线图

**游戏与社交**
- 每日 Buff 抽奖（4 种稀有度：交易折扣 / 现金红包 / 赠送股票）
- 小游戏（21 点、翻翻爆金币，积分可转为交易资金）
- 总资产排行榜

## 技术栈

| 层 | 技术 |
|---|------|
| 后端 | Java 21（虚拟线程）+ Spring Boot 3.4 + MyBatis-Plus 3.5 |
| 前端 | React 19 + TypeScript + Vite + TailwindCSS + Ant Design + ECharts |
| 数据库 | PostgreSQL |
| 缓存 | Caffeine（L1 本地热数据）+ Redis（L2 分布式，行情/会话/排行榜/牌局/分布式锁/限流） |
| 实时通信 | STOMP over WebSocket + Redis Pub/Sub（多实例广播） |
| 认证 | LinuxDo OAuth2 + Sa-Token（Redis 持久化会话） |
| 状态管理 | Zustand（仅持久化 token，用户数据按需拉取） |
| 部署 | Docker（eclipse-temurin:21-jre-alpine） |

## 项目结构

```
whatifibought/
├── wiib-common/    # 公共模块（实体、DTO、枚举、工具类、限流切面）
├── wiib-service/   # 业务服务（Spring Boot 主应用，全部后端逻辑）
└── wiib-web/       # 前端（React SPA）
```

## 架构设计

### AI 行情生成：GBM + Jump-Diffusion

AI 不直接生成价格序列，而是输出宏观参数，由数学模型生成微观走势：

```
AI(LLM) → { openPrice, mu, sigma }  →  带跳跃的几何布朗运动  →  1440个价格点
```

1. **AI 生成三个参数**：开盘价（昨收±2%）、日收益率 mu（-0.05~0.05）、日波动率 sigma（按行业区分稳定/波动）
2. **输入上下文**：股票基本面、公司信息、全局市场情绪（随机25-74）、个股情绪（随机5-94）、近10日涨跌趋势
3. **GBM 公式**：`price[i] = price[i-1] * exp((mu - 0.5σ²)dt + σ√dt·z)`，z 截断在 [-4, 4]
4. **跳跃叠加**：稳定行业 0-2 次、波动行业 0-5 次，幅度 ±2%~4%（上限±5%）
5. **每日 21:00 预生成次日数据，9:10 加载到 Redis**

这样 AI 负责"大方向"，GBM 负责"细节"，避免了 LLM 输出不稳定导致的异常价格。

### Redis 数据结构设计

| Key 模式 | 结构 | 用途 |
|---|---|---|
| `tick:{date}:{stockId}` | Hash（field=index, value=price） | 分时价格，O(1) 按 index 查询 |
| `stock:daily:{date}:{stockId}` | Hash（open/high/low/last/prevClose） | 当日 OHLC 汇总 |
| `kline:{stockId}` | Hash（field=date, value="o,h,l,c"） | K线日线缓存 |
| `stock:static:{stockId}` | Hash | 股票静态数据，启动预热 |
| `stock:ids:all` | Set | 全量股票 ID，用于遍历推送 |
| `bj:session:{userId}` | String（序列化对象） | 21点牌局快照，TTL 4h |
| `bj:pool:{date}` | String | 每日积分池余额（200万），TTL 24h |
| `limiter:{type}:{userId}` | Hash | 令牌桶限流状态 |
| `order:execute:{orderId}` | String（分布式锁） | 订单操作互斥，TTL 30s |
| `market:price:{symbol}` | String | 加密货币现货价缓存（如 BTCUSDT） |
| `market:markprice:{symbol}` | String | 永续合约标记价缓存 |
| `crypto:limit:{buy\|sell}:{symbol}` | ZSet（score=limitPrice） | 加密货币限价单索引 |
| `futures:liq:{long\|short}:{symbol}` | ZSet（score=强平价） | 永续合约强平价索引 |
| `futures:sl:{long\|short}:{symbol}` | ZSet（score=止损价） | 永续合约止损价索引 |
| `futures:tp:{long\|short}:{symbol}` | ZSet（score=止盈价） | 永续合约止盈价索引 |
| `futures:limit:{side}:{symbol}` | ZSet（score=限价） | 永续合约限价单索引（open_long/open_short/close_long/close_short） |
| `futures:pos:{positionId}` | String（分布式锁） | 永续合约仓位操作互斥 |
| `mines:session:{userId}` | String（序列化对象） | Mines 牌局快照，TTL 2h |
| `mines:lock:{userId}` | String（分布式锁） | Mines 操作互斥，TTL 20s |
| `chainlink:price:btcusd` | String | Chainlink BTC/USD 实时价格 |
| `prediction:buy:{ws}:{userId}` | String（分布式锁） | 预测买入互斥 |
| `prediction:sell:{betId}` | String（分布式锁） | 预测卖出互斥 |
| `prediction:settle:{ws}` | String（分布式锁） | 预测结算幂等，TTL 60s |

分时数据选用 Hash 而非 List，因为实时行情需按 index O(1) 单点查价。

### WebSocket 实时推送

三层传输链路，支持多实例部署：

```
定时任务(10s) → QuotePushService → Redis Pub/Sub → 各实例 → SimpMessagingTemplate → STOMP → 前端
```

- **行情频道** `ws:broadcast:stock`：全量股票广播，STOMP 自动按客户端订阅过滤
- **用户事件频道** `event:{type}:{userId}`：资产变动/持仓变化/订单状态，精准推送
- 虚拟线程并发 + Semaphore(5) 限流，时间对齐到 10 秒整点

### 加密货币实时行情推送链路

独立于AI模拟行情，接入 Binance 真实市场数据（当前支持 BTCUSDT、PAXGUSDT、ETHUSDT）：

```
Binance WSS ─┬─ Spot流 (miniTicker ~1次/秒)     → 现货价格
              └─ Futures流 (markPrice @1s)        → 标记价格（永续合约强平基准）
        │
        ▼
BinanceWsClient.onSpotMessage() / onFuturesMessage()
        │  解析 symbol + price + event time
        ▼
writeRedisAndPush(symbol, price, ts) / writeFuturesMarkPrice(symbol, markPrice)
        │
        ├─① Caffeine L1 PUT spot/mark:{symbol}          ← 本地热缓存（限价单/强平读取）
        │   Redis SET "market:price:{symbol}"              ← 现货价 L2 缓存
        │   Redis SET "market:markprice:{symbol}"          ← 标记价 L2 缓存
        │
        ├─② Redis PUBLISH "ws:broadcast:crypto"          ← 集群广播
        │       payload: "{symbol}|{\"price\":\"...\",\"ts\":...}"
        │       │
        │       ▼
        │   RedisMessageBroadcastService.onMessage()
        │       │
        │       ▼
        │   SimpMessagingTemplate → /topic/crypto/{symbol}
        │       │
        │       ▼  SockJS + STOMP (端点 /ws/quotes, 心跳15s)
        │       │
        │   useCryptoStream hook (3秒节流)
        │       │
        │       ▼
        │   Coin.tsx  ← 价格显示 + 1D K线实时追加
        │
        ├─③ Virtual Thread → cryptoOrderService.onPriceUpdate()
        │       │  Redis ZSet rangeByScore 匹配现货限价单
        │       ▼
        │   triggerAndExecuteOrder()
        │
        └─④ Virtual Thread → futuresLiquidationService.checkOnPriceUpdate()
                │  Redis ZSet rangeByScore 匹配强平/止损/止盈
                ▼
            forceClose() / batchTriggerStopLoss() / batchTriggerTakeProfit()
```

**数据源 — BinanceWsClient**
- Spot 流订阅 `{symbol}@miniTicker`，Futures 流订阅 `{symbol}@markPrice@1s`
- Java 21 原生 `java.net.http.WebSocket`，两条独立连接
- 配置: `application.yml` → `binance.ws-url / futures-ws-url / symbols`

**写入与广播 — writeRedisAndPush()**
- `market:price:{symbol}` 缓存现货价 + Caffeine L1 热缓存
- `market:markprice:{symbol}` 缓存标记价 + Caffeine L1 热缓存
- Redis Pub/Sub `ws:broadcast:crypto` 广播（集群多实例扇出）
- 虚拟线程异步触发现货限价单检查 + 永续合约强平/止损/止盈检查

**STOMP 推送 — RedisMessageBroadcastService**
- 监听 Redis 频道，解析 `symbol|json`，推到 `/topic/crypto/{symbol}`
- 集群只需一个实例连 Binance WS，所有实例通过 Redis 同步

**前端消费 — useCryptoStream + Coin.tsx**
- `@stomp/stompjs` + `sockjs-client` 连 `/ws/quotes`，订阅 `/topic/crypto/{symbol}`
- 3秒节流(trailing)，避免高频 re-render
- tick 实时追加到 1D K线图：同分钟更新 close/high/low，跨分钟新增K线点

**现货限价单事件驱动 — onPriceUpdate()**
- 买单/卖单索引: `crypto:limit:{buy|sell}:{symbol}` (ZSet, score=limitPrice)
- 每 tick O(logN) `rangeByScore` 匹配，启动时 `rebuildLimitOrderZSets()` 从 DB 重建

**永续合约强平/止损/止盈事件驱动 — checkOnPriceUpdate()**
- 强平索引: `futures:liq:{long|short}:{symbol}` (ZSet, score=liquidationPrice)
- 止损索引: `futures:sl:{long|short}:{symbol}` (ZSet, score=stopLossPrice)
- 止盈索引: `futures:tp:{long|short}:{symbol}` (ZSet, score=takeProfitPrice)
- 标记价格穿过强平/止损价即触发，成交价穿过止盈价即触发
- 启动时 `init()` 从 DB 重建全部索引

**断线容灾**
```
Spot/Futures WS 独立断线检测
WS断线 → 指数退避重连 {1,2,5,10,30}s + REST轮询兜底(5s)
  Spot断线  → getTickerPrice() 轮询现货价
  Futures断线 → getMarkPrice() 轮询标记价
重连成功 → 停止轮询 + recoverMissedLimitOrders() / recoverMissedFutures() 拉最近高低价恢复
```

### 订单撮合引擎

**市价单**：即时成交，从 Redis 读取最新价。买入扣余额加持仓，卖出创建 T+1 结算记录。

**限价单**三阶段状态机：

```
PENDING  →  TRIGGERED  →  FILLED
  ↓(每10s检测价格)  ↓(每10s批量执行)
```

三重并发防护：
1. **Redis 分布式锁**（30s）：订单级互斥
2. **数据库 CAS 乐观锁**：`casUpdateStatus(orderId, expected, new)`
3. **Semaphore 限流**：虚拟线程并发上限

事务与锁顺序：获取锁 → 开启事务 → 执行操作 → 提交事务 → 释放锁。成交后通过虚拟线程异步发布 Spring 事件触发 WebSocket 推送。

### 永续合约交易引擎

加密货币永续合约，逐仓保证金模式，支持多/空双向：

**开仓**
```
1. 计算保证金 margin = (price × qty) / leverage
2. 手续费 commission = positionValue × 0.1%
3. 原子扣款 (margin + commission)
4. 计算强平价并注册到 Redis ZSet 索引
```

**强平价公式**
```
维持保证金率 MMR = 0.5%

LONG:  liqPrice = (entryPrice × qty - margin) / (qty × (1 - MMR))
SHORT: liqPrice = (entryPrice × qty + margin) / (qty × (1 + MMR))
```

**仓位管理**
- 加仓：加权计算新入场价，合并保证金，重算强平价
- 追加保证金：增加保证金降低强平价，0 手续费
- 部分平仓：保证金按比例返还，剩余止损/止盈保留

**止损/止盈**
- 单仓位最多 4 个止损 + 4 个止盈，支持分批平仓
- 止损由标记价格触发（LONG: markPrice ≤ slPrice，SHORT: markPrice ≥ slPrice）
- 止盈由成交价触发（LONG: currentPrice ≥ tpPrice，SHORT: currentPrice ≤ tpPrice）
- Redis ZSet 索引 O(logN) rangeByScore 匹配

**资金费率**
```
每 8 小时扣除：fee = entryPrice × qty × 0.01%
优先扣余额 → 余额不足扣保证金（强平价变近）→ 保证金耗尽触发强平
```

**并发控制**
- `futures:pos:{positionId}` 分布式锁：仓位级互斥
- 数据库 CAS 乐观锁：状态机 OPEN → CLOSED/LIQUIDATED 不可逆
- 虚拟线程异步处理强平/止损/止盈

### 双层缓存架构

Caffeine（L1 本地）+ Redis（L2 分布式），减少热数据的 Redis 往返：

| 缓存 | 容量 | 内容 |
|------|------|------|
| `stockDailyCache` | 500 | 股票日内 OHLC（last/open/high/low/prevClose） |
| `cryptoPriceCache` | 50 | 加密货币价格（`spot:{symbol}` / `mark:{symbol}` 区分现货/标记） |
| `stockStaticCache` | 全量 | 股票静态数据（TTL 10min，启动预热） |

加载策略：Caffeine 未命中 → Redis → DB。实时行情直接双写 Caffeine + Redis。

### BTC 5分钟涨跌预测

接入 Polymarket 真实数据，复刻 BTC 5分钟涨跌预测市场。用户用余额买入 Up/Down 合约，5分钟窗口自动结算。

```
Polymarket WSS ─┬─ LiveData流 (wss://ws-live-data.polymarket.com)
                │   ├─ crypto_prices_chainlink    → BTC实时价格 (Chainlink)
                │   └─ activity (orders_matched)  → 真实交易动态
                │
                └─ CLOB流 (wss://ws-subscriptions-frontend-clob)
                    └─ price_change               → UP/DOWN 实时盘口 bid/ask
        │
        ▼
PolymarketWsClient (SmartLifecycle phase=1, 先于Redis关闭)
        │
        ├─ onChainlinkPrice()
        │   ├─ Caffeine L1 PUT chainlink:btcusd
        │   ├─ Redis SET "chainlink:price:btcusd"
        │   ├─ 追加到 BTC 价格历史点列表 (ConcurrentLinkedDeque, 5min窗口)
        │   └─ Redis PUBLISH "ws:broadcast:prediction" → price|{json}
        │       → /topic/prediction/price → 前端 ECharts 实时折线图
        │
        ├─ onActivity()
        │   ├─ 从 activity 消息发现 UP/DOWN assetId
        │   │   → 首次发现 upAssetId 时触发 CLOB WS 连接
        │   └─ Redis PUBLISH "ws:broadcast:prediction" → activity|{json}
        │       → /topic/prediction/activity → 前端实时交易动态滚动列表
        │
        ├─ onPriceChange() (CLOB WS)
        │   ├─ Caffeine L1 PUT prediction:{side}:bid / prediction:{side}:ask
        │   └─ Redis PUBLISH "ws:broadcast:prediction" → market|{json}
        │       → /topic/prediction/market → 前端买入价格实时更新
        │
        └─ checkRoundRotation() (每秒检测窗口切换)
            │
            ├─ 新窗口到达:
            │   ├─ lockRound(prevWindowStart)         ← OPEN→LOCKED (CAS)
            │   ├─ 取消订阅旧slug, 订阅新slug
            │   ├─ 关闭旧CLOB WS
            │   ├─ clearPredictionPrices() + 广播空盘口
            │   └─ createNewRound()                   ← INSERT IF NOT EXISTS
            │
            ├─ pollOpenPrice() — Virtual Thread
            │   每5s×12次, REST调 Polymarket crypto-price API
            │   拿到openPrice → putPolymarketOpenPrice() → syncOpenPrice()回填DB
            │   → Redis PUBLISH round|{json} → 前端展示 "Price to beat"
            │
            └─ pollClosePrice() — Virtual Thread
                60s后开始, 每10s×6次, REST调 Polymarket crypto-price API
                拿到closePrice (completed=true) → putPolymarketClosePrice()
                → settlePreviousRound()
                    ├─ outcome = endPrice vs startPrice → UP/DOWN/DRAW
                    ├─ WON:  payout = contracts (每张$1)
                    ├─ LOST: payout = 0
                    ├─ DRAW: payout = cost (退本金)
                    └─ 批量 updateBalance() 给赢家
```

**回合生命周期**
```
t=0s                                    t=300s        t=360s
 │◄──────────── 5分钟窗口 ─────────────►│              │
 │                                      │              │
 │  OPEN                                │  LOCKED      │  SETTLED
 │  可买入/卖出                          │  禁止交易     │  结算完成
 │  按Polymarket实时ask价成交            │              │
 │  卖出按实时bid价                      │  pollClosePrice
 │                                      │  60s后开始, 每10s×6次
 │  pollOpenPrice (每5s, 最多12次)       │  ──────────────►
 │  ──────►                             │  → settlePreviousRound()
 │  → syncOpenPrice()回填DB             │
```

**动态手续费** — 跟随 Polymarket 盘口价格动态调整：
```
effectiveRate = 0.25 × (p × (1-p))²，clamp [0.1%, 2%]

p=0.50 (50/50) → rate = 0.39%    ← 最不确定，费率最高
p=0.80 (80/20) → rate = 0.064%   ← 接近确定，费率降到下限0.1%
p=0.95 (95/5)  → rate = 0.014%   ← 极端价格，费率降到下限0.1%
```

**并发控制**
- `prediction:buy:{windowStart}:{userId}` Redis 分布式锁：用户级买入互斥
- `prediction:sell:{betId}` Redis 分布式锁：下注级卖出互斥
- `prediction:settle:{windowStart}` Redis 分布式锁：结算幂等
- CAS 原子操作：casLockRound (OPEN→LOCKED)、casSettleRound (LOCKED→SETTLED)、casSell (ACTIVE→SOLD)

**WS 连接管理 — WsConnection**
```
三重 AtomicBoolean 防重复连接:
  connected   — 是否已连接
  connecting  — CAS入口锁, 防止多个connect()并发
  reconnecting — CAS入口锁, 防止多个scheduleReconnect()并发

close() → sendClose() + abort() 立即断连
scheduleReconnect() → 指数退避 {1,2,5,10,30}s
```

### 期权定价：Black-Scholes

手写实现（无第三方库依赖）：

```
d1 = (ln(S/K) + (r + 0.5σ²)T) / (σ√T)
d2 = d1 - σ√T
CALL = S·N(d1) - K·e^(-rT)·N(d2)
PUT  = K·e^(-rT)·N(-d2) - S·N(-d1)
```

- 无风险利率 3%，CDF 使用 Abramowitz-Stegun 近似（精度 ~1.5e-7）
- 每日生成 5 档行权价的期权链，年化波动率 = 日 sigma × √252

### 限流：分布式令牌桶

基于 Redis Lua 脚本保证原子性：

```lua
-- 补充令牌：tokens = min(capacity, tokens + elapsed * rate)
-- 消费令牌：tokens >= requested 则扣减返回1，否则返回0
```

通过 `@RateLimiter` 注解声明式使用，按用户维度限流。

### 定时任务时间线

```
21:00  AI生成次日行情 + 新闻 + 期权链
09:00  恢复破产用户
09:10  加载当日数据到Redis
09:25  启动行情推送(10s) / 限价单执行(10s) / 排行榜刷新(10min)
09:30  开盘
15:00  收盘，期权到期结算
16:00  清理过期限价单
17:00  杠杆计息 + 爆仓检查
每小时  Crypto 维护（过期限价单 + 孤儿 TRIGGERED 执行）
每小时  Futures 维护（过期限价单 + 孤儿 TRIGGERED 执行）
每8h   永续合约资金费率扣除（00:00 / 08:00 / 16:00）
每1s   Prediction 回合轮换检测（24/7，窗口切换时触发锁定+新回合+价格轮询）
```

冷启动自愈：`@PostConstruct` 检查当前时间，如在交易时段则自动补启遗漏的周期任务。

### 小游戏

**21 点**：Hit / Stand / Double / Split / Insurance / Forfeit。

- DB 管资金统计，Redis 管牌局过程态（序列化整个 Session 对象，TTL 4h）
- 每日积分池 200 万：用户赢则池减少，用户输则池回血，池空则不能开新局
- 积分可 1:1 转出为交易资金（仅超出初始值的部分，每日上限 10 万）
- Redis 分布式锁串行化同一用户所有操作

**翻翻爆金币（Mines）**：5×5 格子藏 5 颗雷，翻得越多倍率越高，随时可提现。

- 倍率公式：`0.99 / P(k)`，P(k) = C(20,k) / C(25,k)，翻 3 格约 2×，翻 10 格约 17.5×，全翻约 52598×
- 下注范围 100~50,000，SecureRandom 生成雷位置
- 同 21 点架构：DB 存账目（mines_game），Redis 存游戏过程态（TTL 2h）
- Redis 分布式锁 `mines:lock:{userId}` 串行化操作

## 前端页面

| 路由 | 功能 |
|------|------|
| `/` | 首页仪表盘（涨跌榜、行情概览） |
| `/stocks` | 股票列表 |
| `/stock/:id` | 股票详情（分时图 + 交易面板） |
| `/stock/:id/kline` | 日K线 |
| `/portfolio` | 持仓与资产概览 |
| `/options` | 期权交易 |
| `/coin` | 加密货币选择（BTC / PAXG / ETH） |
| `/coin/:symbol` | 加密货币详情（现货交易 + 永续合约 + TradingView K线） |
| `/prediction` | BTC 5分钟涨跌预测（实时图表 + 买卖面板 + 交易动态） |
| `/ranking` | 排行榜 |
| `/games` | 小游戏大厅 |
| `/blackjack` | 21点小游戏 |
| `/mines` | 翻翻爆金币 |
| `/me` | 个人中心（主题切换、游戏入口、排行榜） |
| `/admin` | 管理后台 |

## 本地开发

### 环境要求

- JDK 21
- Node.js 18+
- PostgreSQL
- Redis

### 配置文件

项目未提交 `application.yml` 配置文件，需自行创建：

**wiib-common/src/main/resources/application.yml**（可留空）

**wiib-service/src/main/resources/application.yml** 需配置以下内容：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/wiib
    username: your_username
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379

# LinuxDo OAuth2
linuxdo:
  client-id: your_client_id
  client-secret: your_client_secret
  redirect-uri: http://localhost:3000/login

# AI 行情生成
ai:
  model:
    api-key: your_api_key
    api-url: your_api_url
    model-name: your_model_name

# Sa-Token
sa-token:
  token-name: wiib-token
  timeout: 604800
  is-concurrent: true
```

### 数据库初始化

创建 `wiib` 数据库，执行建表脚本和初始数据脚本（20 家虚拟公司 + 20 只股票）。

### 启动

```bash
# 后端
mvn clean package -DskipTests
java -jar wiib-service/target/wiib-service-*.jar

# 前端
cd wiib-web
npm install
npm run dev
# 默认端口 3000，API 代理到 localhost:8080
```

### Docker 部署

```bash
mvn clean package -DskipTests
docker compose up -d --build
```

需确保 PostgreSQL 和 Redis 可达，具体连接信息在 `docker-compose.yml` 中配置。

## 数据库表

| 表 | 说明 |
|---|------|
| user | 用户（余额、冻结余额、杠杆借款、破产状态） |
| company | 虚拟公司（20 家，覆盖各行业） |
| stock | 股票（静态数据，实时价格从 Redis 获取） |
| position | 持仓（用户-股票唯一约束，含冻结数量和平均成本） |
| orders | 订单（市价/限价，BUY/SELL） |
| price_tick_daily | 分时行情（每日 1440 个价格点，NUMERIC 数组） |
| news | AI 生成的股票新闻 |
| settlement | T+1 资金结算 |
| option_contract | 期权合约（CALL/PUT，行权价/到期时间/波动率） |
| option_position | 期权持仓 |
| option_order | 期权订单 |
| option_settlement | 期权结算记录 |
| user_buff | 每日 Buff |
| blackjack_account | 21 点积分账户 |
| crypto_order | 加密货币订单（市价/限价，BUY/SELL） |
| crypto_position | 加密货币持仓（用户-币种唯一约束） |
| futures_position | 永续合约持仓（逐仓保证金、强平价、止损/止盈 JSONB） |
| futures_order | 永续合约订单（开/平/加仓，市价/限价） |
| mines_game | 翻翻爆金币游戏记录（下注/倍率/雷位/结算） |
| prediction_round | 涨跌预测回合（window_start、start/end_price、outcome、状态机 OPEN→LOCKED→SETTLED） |
| prediction_bet | 涨跌预测下注（side UP/DOWN、contracts、cost、payout、状态 ACTIVE/WON/LOST/DRAW/SOLD） |

## License

MIT
