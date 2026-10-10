# 重构每日复盘：案例复盘 + 经验账本

> 2026-10-10 定稿的实施计划。实施过程中决策若有变动，以这份文档为准同步改。

## Context

线上数据（180 篇成功复盘）说明现在的 reviewer 等于没进化：

- 正文中位 2,620 字、最长 12,146 字；每篇模型输出 1.45 万 token，比输入还多；31% 的复盘窗口内没有一笔了结却仍写 2,000 字。
- "继承靠输出"：每天只回注上一篇、要求把仍成立的教训全部重写，于是全部历史每天用散文再写一遍。gogogo 的记忆逐日重写时与前一天逐行重合 0～3 行，条数 6 条涨到 17 条，26 篇记忆超过 2,000 字预算没人管。
- 记忆大部分是主人指令的复述；真正的发现（追突破 8 笔 1 胜 7 负 -5,105）被"不得与指令相抵触"锁死，只能看见不能动。
- 没有核对账：一条经验被守了几次破了几次没人记；样本数是模型口述。
- 【决策错】【论点错】【运气差】只是标签，不说错在哪、下次怎么判、怎么处理。

目标：复盘按单子类型做案例分析（代码给路径事实，模型答固定几问），产出落进一本由代码记账的经验账本（支持/反对计数、复审、退役不删），trader 每轮注入的是代码渲染的紧凑账本，主人能看能否决。设计在对话里已逐条过过，下面只写执行方案。

## 已定决策（对话中商定；标"默认"的是我建议、未被否决的默认值）

| 决策 | 取值 |
|---|---|
| 复盘正文形态 | 案例分析：每期 ≤2 个案例，按单子类型（playType）分组，赢亏同台；亏单五问（路径 / 为什么没走成 / 失败怎么界定 / 失败后怎么处理 / 下次改什么），赢单五问（路径 / 赢在判断还是运气 / 少赚了多少 / 可复制条件 / 合规吗）；外加"机会审计"一条 |
| 标签 | 决策错 / 论点错 / 运气差 从正文和账本里去掉 |
| 账本条目类型 | 执行缺口、规律、假设、发现；格子：入场条件 / 失败判定 / 失败处理 / 盈利处理 / 风险限制 / 执行缺口；方向：机会型 / 限制型 / 执行型 |
| 结论词 | 经验：支持 / 反对 / 未测试；执行缺口：守 / 破（内部同一枚举） |
| 升级 | 假设 → 规律：支持 ≥ 3 且反对 ≤ 支持/3，代码自动 |
| 降级 | 不自动。代码举旗（近 5 次反对 ≥ 3 / 反对 ≥ 支持且核对 ≥ 4 / 同类单子连续 6 期未测试），模型复审四选一：保留 / 修订 / 拆分 / 退役；复审后保留的再积 3 次结论前不重举 |
| 退役 | 不删行，不再注入 trader，复审时模型能看到退役名单；主人可复活、可彻底删 |
| 上限（默认） | 现役合计 10 条，每类单子 ≤ 3 条，限制型全账户 ≤ 2 条；条目正文 ≤ 80 字（en 160），证据 ≤ 60 字 |
| 限制型能否作观望理由 | 主人开关，默认否 |
| 发现 | 只给主人看（页面），不注入 trader |
| 无交易日 | 窗口内无了结且无开仓动作 → 静默跳过，窗口并入下次 |
| 老记忆迁移（默认） | 每个 trader 第一次跑 v2 时，单独一次调用把旧 memory 压成账本新增行（状态=假设，证据注"迁移自旧记忆"）；主人仍可用重置不带笔记清零 |
| 复审 | 独立的第二次模型调用，只在有旗的那天跑 |
| 存储 | 规则表 + 结论流水表，不用 JSON 列 |

不在本次范围：学习笔记改造（后续照同一账本做）、5m 档位下线（独立小任务）、"发现"自动调整行为的授权开关（后续）。

## 数据模型

`sql/init.sql` 新增 / 修改，同时给线上一次性执行的等价 SQL（项目没有迁移工具，部署文档约定手工 psql）：

```sql
ALTER TABLE ai_trader_plan ADD COLUMN entry_ctx_json TEXT;
-- 开仓那一刻的上下文快照：regime、1h atr、止损距离折 ATR 倍数、距最近 High 事件分钟数、北京时间小时、资金费率、入场单类型；复盘分维度对照用

ALTER TABLE ai_trader ADD COLUMN learned_restriction_hold BOOLEAN NOT NULL DEFAULT FALSE;
-- 主人开关：账本里限制型规律能否作为观望理由

CREATE TABLE ai_trader_experience (
    id            BIGSERIAL PRIMARY KEY,
    trader_id     BIGINT NOT NULL,
    round_no      INT NOT NULL,
    play_type     VARCHAR(20),          -- 所属单子类型，NULL=通用
    kind          VARCHAR(12) NOT NULL, -- GAP 执行缺口 / HYPOTHESIS 假设 / RULE 规律 / FINDING 发现
    slot          VARCHAR(16) NOT NULL, -- ENTRY/FAIL_DEF/FAIL_HANDLE/PROFIT_HANDLE/RISK/EXEC
    direction     VARCHAR(12) NOT NULL, -- OPPORTUNITY / RESTRICTION / EXECUTION
    text          VARCHAR(255) NOT NULL,
    evidence      VARCHAR(255),
    supports      INT NOT NULL DEFAULT 0,
    opposes       INT NOT NULL DEFAULT 0,
    untested      INT NOT NULL DEFAULT 0,
    cited_open    INT NOT NULL DEFAULT 0, -- trader 结论里作为开仓依据引用次数
    cited_hold    INT NOT NULL DEFAULT 0, -- 作为观望理由引用次数
    status        VARCHAR(12) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / RETIRED
    flagged       BOOLEAN NOT NULL DEFAULT FALSE,        -- 待复审
    verdicts_since_audit INT NOT NULL DEFAULT 0,         -- 复审节流
    pinned        BOOLEAN NOT NULL DEFAULT FALSE,        -- 主人钉住：不举旗不退役
    source_id     BIGINT,                                 -- 修订/拆分来源条目
    created_review_id BIGINT, retired_review_id BIGINT, retire_reason VARCHAR(255),
    last_verdict  VARCHAR(12), last_checked_at BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ON ai_trader_experience (trader_id, round_no, status);

CREATE TABLE ai_trader_experience_verdict (
    id            BIGSERIAL PRIMARY KEY,
    experience_id BIGINT NOT NULL,
    review_id     BIGINT NOT NULL,      -- ai_trader_decision 的 REVIEW 行
    verdict       VARCHAR(12) NOT NULL, -- SUPPORT / OPPOSE / UNTESTED
    position_ids  VARCHAR(255),         -- 涉及的 sim 仓位 id，逗号分隔
    note          VARCHAR(255),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ON ai_trader_experience_verdict (experience_id, id);
```

实体放 `wiib-common/.../entity/`（`AiTraderExperience`、`AiTraderExperienceVerdict`），mapper 放 `wiib-agent/.../mapper/`，与 `AiTraderPlan` / `AiTraderPlanMapper` 同形。`ai_trader.memory` 列保留，存代码渲染后的账本文本：所有现有读者（`TraderPromptAssembler`、`LearningRunner`、`TraderChatService`、`TraderController.detail`、前端 NotesCard）不用改。

## 实施步骤

### 步骤 1：开仓上下文（不改任何现有行为）

- 新建 `wiib-agent/.../trader/trade/EntryContext.java`：Spring bean，`capture(symbol, side, orderType, entryRef, stopPrice, leverage, nowMs)` 返回 JSON 字符串或 null。
  - regime：`MarketDataService.assemble(symbol)`，`available()` 为真取 `snapshot().regime().name()`（与 `MarketToolkit.marketSnapshot` 同源，带 60s 缓存，不额外打上游）。
  - 1h ATR：`KlineFetcher.fetch(symbol, "1h", 192)` → `CryptoIndicatorCalculator.calcAll(rows, false).get("atr14")`（`atr()` 本身是包私有，走公开的 `calcAll`；行的形状 `[high, low, close, volume]` 同 `IndicatorToolkit.toCalcRows`）。止损距离折 ATR 倍数 = |entry − stop| / atr14。
  - 距最近 High 事件：`EconCalendarMapper.selectWindow(now − 12h, now + 12h)` 取 `eventTime` 最近的一条，记分钟数带符号。
  - 北京时间小时：`WakeWindow.ZONE`。资金费率：`BinanceRestClient.getPremiumIndex(symbol).lastFundingRate`（`TraderWakeupRunner.marketSnapshot` 已这么取）。
  - 任何一项失败只记 warn、该字段缺席，整体失败返回 null。**绝不挡下单。**
- `TradeTools` 构造参数加 `EntryContext`；`persistPlan` 里 `plan.setEntryCtxJson(ctx.capture(...))`。`TraderWakeupRunner.tradeToolsFor` 传入 bean。`TraderPlanStore.cover` 覆盖时沿用新计划的 ctx（加仓也记一次）。
- 测试：`EntryContextTest`（mock 四个依赖，验 JSON 字段与失败缺席），`TradeToolsTest` 现有用例补一条"ctx 为 null 照样下单"。

### 步骤 2：事实表 `CaseFileBuilder`（学习侧地基）

新建 `wiib-agent/.../learning/CaseFileBuilder.java`，纯代码可单测，输入窗口内已了结仓位（`ReviewMaterialAssembler.inWindow` 的口径）与本局计划，输出：

- `TradeCase`：身份与结果（币、方向、playType、入场 / 出场价与时刻、持有、净盈亏 = DTO `closedPnl`，它已是扣费扣资金费的净额；`fundingFeeTotal` 单列）、计划（原始止损 / 目标、失效条件、论点、修订史各条的时间与理由）、入场上下文（解析 `entry_ctx_json`）、出场方式（`TradePairing.closeMannerKey`）。
- 路径指标，全部从 `KlineHistoryStore.load(symbol, "5m", from, to)` 算：最大浮盈价位 / 时刻 / 占目标比例、最大逆向价位 / 占止损比例、走到目标 25% 与 50% 的用时、第一次 5m 收盘穿回入场价的时刻、入场到出场每根 1h 的收盘（复用现有 `hourlyBars` 聚合，把它从 `ReviewMaterialAssembler` 挪到这里做包私有静态方法）、出场后 4h 与 24h 的最有利价、出场后是否到过原目标。
- 时间换算：仓位时间是 +8 本地 `LocalDateTime`，统一走 `TradePairing.msOf`。
- 分组：按 playType 聚合成 `SetupGroup`（笔数、胜负、净额、各笔 `TradeCase`），对照表文本由 `ReviewMaterialAssembler` 渲染（词表 `reviewer.label.case.*`）。
- 案例选择：`SetupGroup` 按"本期 ≥ 2 笔优先，其后按 |净额|"排序取前 2。
- 行情块：各币窗口 OHLC 与时刻、低到高幅度（已有 5m 数据，一次 `load` 即可）。
- 测试：`CaseFileBuilderTest` 用合成 5m 序列验每个指标的边界（未到 25%、出场后无数据、空单方向）。

### 步骤 3：经验账本 `ExperienceLedger`

新建 `wiib-agent/.../learning/ExperienceLedger.java`（Spring bean，持两个 mapper + `PromptCatalog`）：

- `active(traderId, roundNo)` / `retired(...)`。
- `apply(traderId, roundNo, reviewId, List<LedgerOp>)`：逐行独立生效。结论行写 verdict 流水并累计计数、`verdicts_since_audit++`；新增校验 playType 合法（`TradeGuard.PLAY_TYPES` 或 null）、有证据、不超上限（超了丢弃并记日志，不中断）；删除只对 HYPOTHESIS 生效且需理由，RULE 一律走复审。
- 升级：`supports ≥ 3 && opposes * 3 ≤ supports` → RULE。
- 举旗：三条规则见上表；`pinned` 不举旗；节流用 `verdicts_since_audit`。
- 复审执行 `applyAudit(id, AuditOp)`：保留 → 清旗、计数器归零；修订 → 新条继承 supports/opposes、`source_id` 指向旧条，旧条 RETIRED；拆分 → 旧条 RETIRED，≥2 条新增各带 `source_id`；退役 → RETIRED + `retire_reason`。
- `render(traderId, roundNo, lang, restrictionHold)`：按 playType 分组，一条一行：`#id 状态 方向 ｜ 正文 ｜ 支持 n 反对 m（近 5 次 …）｜ 待复审`；头部一句说明编号可引用、假设仅供参考、限制型能否作观望理由按开关（文案走 `trader.label.ledger.*`）。
- `recordCitations(traderId, roundNo, ids, hadOpen)`：`cited_open` / `cited_hold` 累加。
- 主人操作：`pin / unpin / retire / revive / delete`。
- 测试：`ExperienceLedgerTest`（mock mapper）覆盖升级阈值、三条举旗、节流、上限丢弃、修订继承、渲染预算。

### 步骤 4：ReviewRunner v2（切换）

- 门槛：`review()` 里 `hasNewMaterial` 改为"窗口内有了结仓位或有 `open_position` 动作"（复用 `quietHoldWindow` 前半段的两条查询；去掉振幅那一段与 `QUIET_AMPLITUDE_PCT`）。不满足 → 日志、不写行。`TraderActionService.hasMaterial` 自动跟随。
- 素材（`ReviewMaterialAssembler.assemble` 重写返回新 `ReviewMaterial`）：战绩块（保留 `statsBlock`）、本局 playType 战绩（复用 `PlayStatsAssembler` 的配对口径，或直接从 `SetupGroup` 全局统计）、对照表 + 每笔事实、行情块、账本现役视图（含旗）、退役名单一行一条。**删除**：`timelineBlock`、观望分层（`Hold`、`flushHold`、`waitsBySymbol`、`waitPattern`、`structureSnapshot`、`holdAuditTag` 等）、`pricePathBlock`，连同 `ReviewMaterialAssemblerTest` 里对应用例和 `reviewer.label.timeline*/hold*/path*/wait*` 词表。`DecisionText.splitSegments` 仍被 stale 剔段使用，保留。
- 提示词：重写 `prompts/zh/reviewer.yml` 与 `prompts/en/reviewer.yml` 的 `system`（身份 + 五问 + 输出契约 + 规则：只许引用事实表数字，失败界定与处理必须是可观察的价格 / 根数 / 时间，指令里已有的不写进账本，不许继承旧教训）、新增 `reviewer.audit.system`、`reviewer.migrate.system`、段标记 `reviewer.mark.body / updates`、结论词与类型词表（解析按当前语言那一套认，与现有 `parse` 的纪律一致）。`PromptParityTest` 要求 zh/en key 与占位符对齐。
- 输出契约（解析器只认行首固定词）：

```
【本期复盘】
战绩：<代码行原样>
案例一：<单子类型>（n 笔，净 ±x）
  路径 / 为什么… / … 五行
案例二：…
机会：<一条>
【经验更新】
#<id> <支持|反对|未测试|守|破> ｜ <证据>
新增 <PLAY_TYPE|通用> <格子> <方向> ｜ <条件 → 做法> ｜ <证据>
发现 ｜ <正文> ｜ <证据>
删除 #<id> ｜ <理由>
```

- 复审调用（有旗时）：输入该条一生的结论流水 + 支持 / 反对两组单子的对照表 + 支持率 + 历史最长连败；输出每条一行 `#<id> <保留|修订|拆分|退役> ｜ <理由>`，修订另附 `修订 #<id> ｜ <新正文>`，拆分附 ≥2 行 `新增 … ｜ 来源 #<id>`。
- `callWithTimeout` 抽成接收 system 文本的通用方法，两次调用共用；超时各 600s / 300s；用量两次相加落同一 REVIEW 行。
- 落库：REVIEW 行 `reasoning` = 模型正文 + 经验更新表 + 代码追加的复审结论段；`memory_after` = 渲染后的账本快照；`ai_trader.memory` = 同一份渲染文本（列级更新，沿用现有写法）。
- 降级：【经验更新】一行都解析不出 → REVIEW 行照存、账本不动（现有 `parse` 的语义）；复审调用失败 → 旗保留，下期再审。
- 迁移：`review()` 开头，账本为空且 `trader.memory` 非空且 memory 不是账本渲染格式（渲染文本带固定头标记可识别）→ 先跑一次 `reviewer.migrate` 调用，解析为新增行入账，再继续本期复盘。
- 测试：重写 `ReviewRunnerTest` / `ReviewLoopTest`（真 assembler + 真 ledger + mock 模型：验门槛、素材进提示词、解析入账、降级、复审触发）、`PromptMarkParsingTest` 改认新标记；`WIIB_REAL_RUN=1` 的格式服从真跑测试一个（仿 `TraderConclusionFormatRealRunTest`，两门语言各一轮，看模型是否按契约填表）。

### 步骤 5：trader 侧注入与引用统计

- `TraderPromptAssembler` 不改读法（仍读 `trader.getMemory()`），改词表 `trader.label.memory` 的段头为"经验账本"措辞（zh/en），说明编号引用与假设 / 规律的权重规则，开关文案按 `learned_restriction_hold` 二选一（`TraderRiskConfig` 旁加读法）。
- `TraderWakeupRunner.doWake` 在 `decision.setStatus(OK)` 之后：`DecisionText.conclusionBlock(reasoning)` 里正则 `#(\d+)` 取编号，`hadOpen` = `actions_json` 含成交的 `open_position`，调 `ledger.recordCitations`。失败只记 warn。
- 测试：`TraderPromptAssemblerTest` 补段头两种文案；`TraderWakeupLoopTest` 补引用计数一例。

### 步骤 6：接口与前端

- `TraderController`：`GET /ai/trader/mine/experiences`（主人：现役 + 退役 + 每条最近 10 条结论流水 + 引用计数 + 发现）；`POST /ai/trader/experience/{id}/pin|retire|revive`、`DELETE /ai/trader/experience/{id}`；`UpsertRequest` / `TraderOwnerView` / `TraderService.UpsertReq` / `applyConfig` / `updateConfig` 加 `learnedRestrictionHold`。公开详情 `TraderDetailView.memory` 继续返回渲染文本，竞技场不改。
- 文案：`messages/zh|en/agent.yml` 加 `trader.experience.*`（`AgentMessageParityTest` 对齐）。
- 前端：`types/index.ts` 加 `TraderExperienceView` 等；`api/index.ts` 的 `traderApi` 加五个方法；`MyTrader.tsx` 加开关一项（沿用 `SpecToggle`）与"经验账本"面板（表格：编号 / 类型 / 正文 / 支持 反对 / 近况 / 引用 / 状态 / 操作），发现单独一栏；`i18n/locales/zh|en/ai.json` 加键。UI 遵守项目规矩：lucide 图标、无 emoji、海报数字风格。

### 步骤 7：文档

更新现有两份文档里 reviewer 的段落：`docs/agent-harness/architecture.md`（reviewer workflow 一节与时序图）、`docs/agent-harness/tutorial.md` 第 6 章。不新增文档。

## 关键复用

- 配对与了结方式：`TradePairing.pairAll / closeMannerKey`（`wiib-agent/.../trader/TradePairing.java`）
- 窗口过滤：`ReviewMaterialAssembler.inWindow`；战绩：`statsBlock`；1h 聚合：`hourlyBars`
- 本地 K 线：`KlineHistoryStore.load`（`wiib-common/.../market/KlineHistoryStore.java`）
- 行情快照 / ATR：`MarketDataService.assemble`、`KlineFetcher.fetch`、`CryptoIndicatorCalculator.calcAll`
- 事件：`EconCalendarMapper.selectWindow`
- 词表与解析纪律：`PromptCatalog.get`，标记按当前语言认（`ReviewRunner.parse` 的既有原则）
- 列级更新写 `ai_trader`：`ReviewRunner` 里现有 `LambdaUpdateWrapper` 写法
- 结论块定位：`DecisionText.conclusionBlock`

## 顺序与风险

1 → 2 → 3 不改任何线上行为，可以先合并部署，开始积累 `entry_ctx_json`。4 是切换点，上线后第一天：旧 memory 走迁移调用一次。5、6 可与 4 同批或随后。

风险：模型不按契约填表 → 降级路径保证不污染账本，真跑格式测试先验服从率；弱模型写不出可观察条件 → 解析只管格式，内容质量由复审与主人否决兜；迁移调用把旧记忆里的指令复述搬进账本 → 迁移提示词明说"指令里已有的不搬"，上限 10 条自然截断。

## 验证

- 单测（根 pom 默认 skipTests，按项目约定）：

```
mvn test -pl wiib-agent -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=EntryContextTest,CaseFileBuilderTest,ExperienceLedgerTest,ReviewRunnerTest,ReviewLoopTest,PromptMarkParsingTest,PromptParityTest,AgentMessageParityTest,TraderPromptAssemblerTest,TraderWakeupLoopTest
```

- 真跑格式服从：`WIIB_REAL_RUN=1 mvn test -pl wiib-agent -am -DskipTests=false -Dtest=ReviewContractRealRunTest -Dsurefire.failIfNoSpecifiedTests=false`，两门语言各一轮，看【经验更新】每行能否解析。
- 前端：`npm run build` 通过；`vite.config.mock.ts` 预览账本面板。
- 线上：执行一次性 SQL；部署 wiib-agent；次日 08:00 交接后用只读 SQL 核对：REVIEW 行长度（目标中位 < 1,000 字）、`ai_trader_experience` 有行、`memory` 为渲染文本、无交易 trader 当天无 REVIEW 行、gogogo 的旧记忆被压成 ≤ 10 条。
