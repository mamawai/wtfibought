# 部署

这份文档讲**在本地把它跑起来**：环境要求、两个前置检查，以及克隆 → 建库 → 配置 → 构建后端 / 前端 → 启动这六步，本地一键与 Docker Compose 两条路都在里面。

线上建站那半截（反代、证书、静态资源投放）不在本文范围。

## 环境要求

| 依赖 | 最低版本 | 说明 |
|---|---:|---|
| JDK | 25 | pom `<release>` 即 25，低版本编译不过 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20.19+ / 22.12+ | 前端构建（Vite 7 的 engines 门槛，Node 20.0~20.18 跑不起来） |
| PostgreSQL | 14+ | 主数据库（三进程共享） |
| Redis | 6+ | 行情总线、锁、Pub/Sub、会话 |

## 0. 开跑前两个前置检查

**① 当前网络节点能不能访问 Binance API。** 整套行情都吃 Binance 的 WS 与 REST（`stream.binance.com` / `fstream.binance.com` / `api.binance.com` / `fapi.binance.com`），部分地区的出口 IP 会被返 **451 地域封锁**。先自测：

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://api.binance.com/api/v3/ping   # 期望 200
curl -s -o /dev/null -w "%{http_code}\n" https://fapi.binance.com/fapi/v1/ping # 期望 200
```

返回 451 就说明这个节点被封了。此时 feed 连不上流、REST 兜底也拿不到数，**Binance 那条链下游全是空的**：价格不动、K 线不落库、撮合与强平不触发、AI Trader 的行情工具一律 `available=false`。得换节点或让出口走代理，不是配置能绕过去的。（Polymarket 那条 BTC 预测链不吃 Binance，仍能跑。）

**② 本地 PostgreSQL 与 Redis 已经起着。** 三个进程都要连，缺一个起不来。

## 1. 克隆项目

```bash
git clone https://github.com/mamawai/wtfibought.git
cd wtfibought
```

## 2. 初始化数据库

```bash
psql -U postgres -c "CREATE DATABASE wiib;"
psql -U postgres -d wiib -f sql/init.sql      # 业务 + 量化 + AI runtime（34 张表）
psql -U postgres -d wiib -f sql/bstock.sql    # bStock 代币化美股静态表 + 10 只种子
```

两份合计 35 张表。

## 3. 后端配置

密钥与结构分离：`application.yml` 直接入库（只有 `${VAR}` 占位符），真实值只存在根目录 env 文件里。本地开发复制模板填值即可：

```bash
cp .env.example .env.local    # 填 PG_USER / PG_PASSWORD / INTERNAL_API_TOKEN（必填），其余可选
```

启动时按 `本机环境变量 > .env.local > yml 默认值` 解析（机制是各模块 yml 里的 `spring.config.import: optional:file:.env.local[.properties]`，另带一条 `../.env.local` 兼容"IDEA 工作目录=模块"的情形）；线上 Docker 部署同一文件命名为 `.env`（见第 6 节）。

> **`.env` 按 properties 语法解析**：注释必须独立成行，`KEY=value # 说明` 里的 `# 说明` 会被当成值的一部分。

### 必填三项

| 变量 | 为什么必填 |
|---|---|
| `PG_USER` / `PG_PASSWORD` | 无默认值，缺了三进程都报 placeholder 错 |
| `INTERNAL_API_TOKEN` | 进程间 `/internal/**` 鉴权凭据，无默认值。用 `openssl rand -base64 24` 生成一份填进去，三进程读同一份 env，天然同值 |

三个进程必须指向**同一个** PostgreSQL `wiib` 库和**同一个** Redis——读同一份 `.env.local` 就天然一致；分模块起服务时各配各的会静默分裂，查半天查不出来。

### 其余变量速查

都可留空，对应功能降级；踩坑集中在前三条：

| 变量 | 不配会怎样 |
|---|---|
| `WIIB_TRADER_KEY_SECRET` | base64 的 32 字节（`openssl rand -base64 32`）。不配则**用户无法保存 API key**：key 一律 AES-GCM 加密入库，没有密钥就直接报错，不会以明文落库。每环境单独生成勿复用 |
| `PASSWORD_LOGIN_ENABLED` + `ADMIN_PASSWORD` | 开了密码登录就**必须**配 `ADMIN_PASSWORD`。邀请码只有管理员能生成，id=1 没密码就没人进得了 Admin 页——LinuxDo 也没配的话，等于全站没人能注册 |
| `BINANCE_TESTNET_API_KEY` / `BINANCE_TESTNET_SECRET_KEY` | 策略切 `target: testnet` 轨要用，Testnet 看板页也直接吃它（纯实时直拉，不落库不缓存）；不填只有 sim 模拟盘轨可用 |
| `WIIB_TRADER_BASEURL_ALLOWLIST` | BYOK 的 baseUrl 走 SSRF 校验，默认拒绝内网/本机地址。docker 同网络的代理网关（如 `cliproxyapi`）要在这里按主机名放行 |
| `BLOCKBEATS_API_KEY` | 快讯功能降级 |
| `LINUXDO_CLIENT_ID` / `LINUXDO_CLIENT_SECRET` / `LINUXDO_REDIRECT_URI` | 关闭 LinuxDo 登录。回调默认 `http://localhost:3000/login`，与前端 dev 端口绑死，改端口要一起改 |
| `TRADE_ALIAS_SALT` | 全站公开成交流水的匿名昵称哈希盐，默认是硬编码公开串。线上不改等于匿名化形同虚设；同一次部署内必须稳定，改了历史昵称全变 |
| `PG_HOST/PORT/DB`、`REDIS_HOST/PORT/DB/PASSWORD` | 各有默认值（localhost）。docker 部署里 HOST 要填容器名或宿主内网 IP，写 `localhost` 会指向容器自己 |
| `SPRING_PROFILES_ACTIVE=sentinel` + `REDIS_SENTINEL_*` | 切 Redis 哨兵模式（配合 `redis-compose.yml`），不切就走单机 |
| `TZ` | 仅 docker 用，默认 `Asia/Singapore` |

容器间寻址（`FEED_INTERNAL_BASE_URL` / `SIM_INTERNAL_BASE_URL`）由 compose 直接注入，不用写进 `.env`。

### 三处不走 env 的配置

- **LLM 配置不在 yml**，分两处，看是谁在烧钱：
  - 平台位：只剩 news-translation（快讯英文译文）一个——后台批量任务，没有"当前用户"可言。配在 DB 的 `ai_runtime_config` + `ai_model_assignment`，管理员进 Admin 页填 API Key + Base URL + 模型名（不含 `/v1`）并分配功能位，即时生效、无需重启。
  - 用户 BYOK：`user_llm_endpoint` 端点库（一人多条：协议 + URL + key + 模型 + 思考档位 + 搜索开关）+ `user_llm_binding` 用途绑定（对话主 / 轻、交易员；没绑的用途落到默认端点），全在 AI 页「模型配置」维护，交易员 / 复盘教练页只从下拉里选。协议四选一：`openai` / `responses` / `anthropic` / `gemini`。
- **wiib-agent 必须关掉 Spring AI 的 OpenAI 自动装配**（6 类全关，否则缺 api-key 拒绝启动）——仓库里已经配好，自己改 yml 时别删：

  ```yaml
  spring:
    ai:
      model: { chat: none, embedding: none, image: none, moderation: none, audio: { speech: none, transcription: none } }
  ```

- **策略实盘执行**（配在 `wiib-agent` 的 yml，代码在 `wiib-quant` 库；仓库默认即三策略全启、跑 sim 轨）：

  ```yaml
  strategy:
    runtime:   { enabled: true, enabled-ids: FIBO,SQZMOM,TURTLE }
    execution: { enabled: true, target: sim,
                 symbols: BTCUSDT,ETHUSDT,DOGEUSDT,SOLUSDT,XRPUSDT,BNBUSDT }
  ```

  > 策略由 K 线收盘驱动：`wiib-feed` 的 `binance.symbols` 必须覆盖上面全部标的（缺谁谁永不触发）。
  > `symbols` 是三策略部署篮子的并集；TURTLE 的触价单是内存态，由 feed 的 futures tick 触发。

## 4. 构建后端

```bash
mvn clean package -pl wiib-feed,wiib-agent,wiib-sim -am -DskipTests
# -am 会带上 wiib-common 与 wiib-quant 两个库，不用单列
# 产物：
#   wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar     :8081 数据上游
#   wiib-agent/target/wiib-agent-0.0.1-SNAPSHOT.jar   :8082 AI 交易员 + 策略
#   wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar       :8080 模拟交易（前端主要连它）
```

## 5. 构建前端

```bash
cd wiib-web
npm install
npm run build      # 开发：npm run dev
```

`npm run dev` 的端口与代理都写死在 `vite.config.ts` 里（不是 Vite 默认值）：

| 前缀 | 去处 |
|---|---|
| `/api/ai`、`/api/testnet`、`/api/admin/ai-agent` | wiib-agent `:8082` |
| 其余 `/api` | wiib-sim `:8080` |
| `/ws` | wiib-sim `:8080`（WebSocket） |

端口固定 3000——`LINUXDO_REDIRECT_URI` 默认指向它，改端口登录回调会失效。

另有一套纯前端预览配置，不需要后端：

```bash
npx vite --config vite.config.mock.ts    # 端口 3001，mock 数据顶掉接口
```

## 6. 启动

本地一键（Windows，构建 + 依次拉起 feed → sim → agent）：

```powershell
.\start-local.ps1              # 加 -SkipBuild 跳过构建；或直接双击 start-local.bat
```

脚本比看上去多做了几件事：先校验根目录有 `.env.local`（缺了直接退出）；构建只打三个进程模块（`-pl ... -am`）；优先用 `$env:JAVA_HOME\bin\java.exe`（裸 `java` 可能指向老版本 JRE，跑不了 25 编译的 jar）；**按端口就绪门控顺序启动**（feed 先起，端口通了再起下一个，默认等 180s，超时只告警不中断）；工作目录固定为仓库根，保证 `.env.local` 命中。

或手动逐个起（须在仓库根目录执行，`.env.local` 按相对路径解析；IDEA 直接点各模块 Run 也可）：

```bash
java -jar wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar    # :8081 交易所 WS → Redis
java -jar wiib-agent/target/wiib-agent-0.0.1-SNAPSHOT.jar  # :8082 AI 交易员 + 策略
java -jar wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar      # :8080 模拟交易（前端主要连它）
```

Docker Compose（三进程全编排；配置放同目录的 `.env`，与 `.env.example` 同款变量）：

```bash
docker network create wiib-network
mvn clean package -pl wiib-feed,wiib-agent,wiib-sim -am -DskipTests   # 必须先打包
docker compose up -d --build
```

> 三个 Dockerfile 都是 `COPY target/*.jar`，**不在容器里编译**，所以 compose 之前那次 mvn 不能省。
> 端口均只绑 `127.0.0.1`（feed 8081 / sim 8080 / agent 宿主 18082，因为宿主 8082 常被占）。
> Redis 主从 + 哨兵栈可选 `docker compose -f redis-compose.yml up -d`。
