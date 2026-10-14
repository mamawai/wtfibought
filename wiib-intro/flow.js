'use strict';
/* ============================================================
   agent-flow 面板：两块 harness 的信号路径动画
   宽屏 = 泳道图（绝对坐标）；窄屏 = 单轨 stepper（一条总线，卡片按信号顺序竖排）
   ============================================================ */

/* ---------- 基础（全站共用，intro.js 也用这几样） ---------- */
const REDUCED = matchMedia('(prefers-reduced-motion: reduce)').matches;
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { } }
};
let LANG = store.get('wiib-intro-lang') || ((navigator.language || '').startsWith('zh') ? 'zh' : 'en');
if (LANG !== 'zh' && LANG !== 'en') LANG = 'zh';
const T = o => typeof o === 'string' ? o : (o[LANG] ?? o.zh);

function el(tag, cls, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  return e;
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const UI = {
  trace: 'TRACE',
  st: { idle: { zh: 'IDLE · 待机', en: 'IDLE' }, run: { zh: 'RUN · 运行中', en: 'RUNNING' }, sleep: { zh: 'SLEEP · 休眠', en: 'SLEEPING' }, hitl: { zh: 'HITL · 等用户确认', en: 'HITL · AWAITING USER' }, paused: { zh: 'PAUSED · 已暂停', en: 'PAUSED' }, bg: { zh: 'BG · 后台子运行在跑', en: 'BG · CHILD RUNS ACTIVE' }, wake: { zh: 'WAKE · 唤醒轮', en: 'WAKE · FOLLOW-UP TURN' } },
  play: { zh: '播放', en: 'Play' }, pause: { zh: '暂停', en: 'Pause' }, restart: { zh: '重放', en: 'Replay' },
  auto: { zh: '轮播', en: 'AUTO' },
  reduced: { zh: '系统开了「减少动态效果」，展示静态全图。', en: 'Reduced motion is on — showing the static diagram.' },
  legend: {
    title: { zh: '图例', en: 'LEGEND' },
    pulse: { zh: '脉冲 = 数据在走', en: 'pulse = data moving' },
    led: { zh: 'LED = 闸门在过', en: 'LED = a gate passing' },
    back: { zh: '虚线 = 回边 / 下一次唤醒', en: 'dashed = feedback edge' },
    bad: { zh: '红脉冲 = 被拒', en: 'red pulse = rejected' },
    click: { zh: '点节点看说明', en: 'click a node to read' }
  }
};

/* ---------- 面板配置（label 全部中英双写；事实以 docs/agent-harness 为准） ---------- */
const PANELS = [
  {
    id: 'trader',
    num: 'HARNESS 01',
    title: { zh: 'trader agent', en: 'trader agent' },
    sub: { zh: '唯一会动真账本的 · 配套每日复盘', en: 'touches the real ledger · with daily review' },
    w: 1180, h: 648,
    lanes: [
      { x: 216, y: 34, t: { zh: '一次唤醒 · ONE WAKEUP', en: 'ONE WAKEUP' } },
      { x: 216, y: 436, t: { zh: '日终交接 · END OF DAY', en: 'END OF DAY' } }
    ],
    nodes: [
      { id: 'clock', x: 16, y: 16, w: 170, h: 88, tag: 'CLOCK', icon: 'clock',
        title: { zh: 'K 线收盘事件', en: 'Candle close' },
        sub: { zh: '例行 · 对齐 interval 边界', en: 'routine · interval-aligned' },
        detail: { zh: 'TraderScheduler 以 5m K 线收盘事件为唯一时钟，对齐到各 trader 选定的 interval（5m/15m/1h/4h）边界；同一边界多币多次触发按边界去重。边界撞上 High 级数据公布时刻，先过财经日历等待闸，最多等 30 秒拿到实际值再发。丢失的 K 线不补跑——陈旧信号没有意义。', en: 'TraderScheduler uses 5m candle-close events as its sole clock, aligned to each trader’s chosen interval boundary. Duplicate fires on one boundary are deduped. When a boundary lands on a High-impact data release, the econ-calendar gate waits up to 30s for the actual value first. Lost candles are never replayed — stale signals are worthless.' } },
      { id: 'sentinel', x: 16, y: 120, w: 170, h: 104, tag: 'SENTRY', icon: 'activity',
        title: { zh: '波动哨兵', en: 'Volatility sentinel' },
        rows: [
          { id: 'amp', t: { zh: '5min 振幅 > 阈值×灵敏度', en: 'range > thresh × sens' } },
          { id: 'hold', t: { zh: '持仓/挂单才惊动 · 冷静期', en: 'holders only · cooldown' } }
        ],
        detail: { zh: '盯 markPrice tick（约 1s 一跳）的 5 分钟滚动振幅，超过「币基准阈值 × trader 灵敏度」且该 trader（1h/4h 档）持有该币仓位或挂单才触发警报唤醒；另有 5 分钟冷静期与预算预检，唤醒时段外也不叫。空仓者不惊动，它是例行唤醒的补充而非替代。', en: 'Watches the 5-minute rolling range of markPrice ticks. Fires an alert wakeup only when range exceeds the per-symbol threshold × trader sensitivity AND that (1h/4h) trader holds a position or order in the symbol; a 5-minute cooldown and a budget precheck apply, and nothing fires outside the wake window. Flat traders are never disturbed.' } },
      { id: 'manual', x: 16, y: 240, w: 170, h: 76, tag: 'MANUAL', icon: 'mouse-pointer-click',
        title: { zh: '手动唤醒 / 点播复盘', en: 'Manual wake / on-demand review' },
        detail: { zh: '主人从动作面板（或研判工作台弹出的表单）手动唤醒；不受唤醒时段过滤，但同样过互斥和预算预检——距下一根 K 线不足 30 秒不让点。', en: 'The owner triggers a wakeup from the action panel (or a form pushed into chat). It skips the wake-window filter but still passes the mutex and the budget precheck — less than 30s to the next candle and the button stays locked.' } },
      { id: 'sched', x: 216, y: 62, w: 196, h: 150, tag: 'SCHED', icon: 'timer',
        title: { zh: 'TraderScheduler 准入', en: 'Scheduler admission' },
        rows: [
          { id: 'mutex', led: true, t: { zh: '每 trader 互斥 · 忙则 SKIPPED', en: 'mutex · busy = SKIPPED' } },
          { id: 'window', led: true, t: { zh: '唤醒时段过滤（北京时间）', en: 'wake window (Beijing time)' } },
          { id: 'budget', led: true, t: { zh: '预算 ≤600s · 边界前 5s 截止', en: 'budget ≤600s · −5s deadline' } },
          { id: 'hand', led: true, t: { zh: '日终交接窗口内一律拒', en: 'blocked during handover' } }
        ],
        detail: { zh: '准入四件事：每 trader 互斥（上一轮没跑完，新信号记 SKIPPED 不排队）、唤醒时段过滤（时段外静默跳过）、预算计算——截止到下一边界前 5 秒、上限 600 秒，距边界不足 30 秒判「触发过晚」放弃且不算失败；日终交接的停工窗口里四个入口一律拒。不设全局并发上限，只按 trader 互斥。', en: 'Four checks: per-trader mutex (a busy trader records SKIPPED, no queueing), the wake-window filter, and the budget — capped at 600s, ending 5s before the next boundary; under 30s left means “too late”, abandoned without counting as a failure; during the daily-handover blackout all four entries are refused. There is no global concurrency cap, only the per-trader mutex.' } },
      // 前三个进系统提示词，后五个进开场白（user 消息）
      { id: 'asm', x: 442, y: 62, w: 196, h: 148, tag: 'PROMPT', icon: 'file-text',
        title: { zh: '系统提示词 + 开场白', en: 'System prompt + opening' },
        chips: [
          { id: 'c1', t: { zh: '平台规则', en: 'platform rules' } },
          { id: 'c2', t: { zh: '经验账本', en: 'experience ledger' } },
          { id: 'c4', t: { zh: '交易指令', en: 'owner playbook' } },
          { id: 'c5', t: { zh: '事件·账户', en: 'events+account' } },
          { id: 'c6', t: { zh: '上轮结论', en: 'last conclusion' } },
          { id: 'c7', t: { zh: '论点战绩', en: 'play stats' } },
          { id: 'c8', t: { zh: '财经日历', en: 'econ calendar' } },
          { id: 'c9', t: { zh: '主人留言', en: 'owner note' } }
        ],
        detail: { zh: '每次唤醒现读现拼，零热更新机制。系统提示词放平台规则（只讲事实：环境、工具、规格、护栏、收尾格式）、经验账本和主人的交易指令——方法与纪律只来自主人，平台不带交易观点。状态进开场白：上次醒来后的事件、账户（持仓附自上次决策以来的波动）、上一轮结论、论点战绩、财经日历，主人留言压在最末。', en: 'Reassembled fresh on every wakeup, no hot-reload machinery. The system prompt carries the platform rules (facts only: environment, tools, specs, guardrails, closing format), the experience ledger and the owner’s playbook — method and discipline come only from the owner. State goes into the opening message: events since the last wake, the account (each position with what it went through since the last decision), the last conclusion, play stats, the econ calendar, with the owner’s note last.' } },
      { id: 'agent', x: 668, y: 84, w: 180, h: 104, tag: 'REACT', icon: 'cpu',
        title: { zh: 'ReactLoop 决策循环', en: 'ReactLoop' },
        counter: { label: { zh: '模型调用', en: 'model calls' }, max: 12 },
        rows: [ { id: 'stateless', t: { zh: '无状态 · 不强制 tool_choice', en: 'stateless · tool_choice never forced' } } ],
        detail: { zh: '一次唤醒 = 一个无状态 ReactLoop 会话（自写的 ReAct 循环），BYOK 模型（主人的 key，AES-GCM 加密存库）。不强制 tool_choice（Opus 5.5 这类模型不收 any / required）：「先读事实、用工具求证」写在开场白里。单轮模型调用上限 12 次——会并行的模型两三次就取完数，不并行的一轮一个，多币求证要这个余量；最后一次能执行工具时贴一句收尾提示，下一次直接给结论。连同 600 秒预算与连败 5 次暂停，停止条件都在模型之外。', en: 'One wakeup = one stateless ReactLoop session (a hand-written ReAct loop) on the owner’s BYOK model (key stored AES-GCM encrypted). tool_choice is never forced (models like Opus 5.5 reject any / required): “read the facts, verify with tools” lives in the opening message. Cap of 12 model calls per wake — parallel callers finish in two or three, serial ones need the headroom to check several symbols; the last tool-capable call gets a wrap-up hint so the next one concludes. With the 600s budget and pause-after-5-failures, every stop condition lives outside the model.' } },
      { id: 'data', x: 888, y: 16, w: 180, h: 196, tag: 'TOOLS', icon: 'database',
        title: { zh: '数据工具 ×8', en: 'Data tools ×8' },
        rows: [
          { id: 'klines', t: 'klines' }, { id: 'kstruct', t: 'kline_structure' },
          { id: 'ind', t: 'indicators' }, { id: 'snap', t: 'market_snapshot' },
          { id: 'iv', t: 'option_iv' }, { id: 'fund', t: 'funding_history' },
          { id: 'depth', t: 'orderbook_depth' }, { id: 'news', t: 'news_search' }
        ],
        detail: { zh: 'K 线 / 结构 / 指标 / 行情快照（资金费偏离、持仓量变化、多空比、清算压力、恐贪等归一化信号）/ 期权 IV / 资金费历史 / 盘口深度 / BlockBeats 快讯。中性事实，不带平台观点。', en: 'Candles / structure / indicators / market snapshot (normalized signals: funding deviation, OI change, long-short ratios, liquidation pressure, fear&greed) / option IV / funding history / orderbook depth / BlockBeats news. Neutral facts, no platform opinions.' } },
      { id: 'guard', x: 888, y: 232, w: 180, h: 100, tag: 'GUARD', icon: 'shield-check',
        title: { zh: 'TradeGuard 硬校验', en: 'TradeGuard' },
        rows: [
          { id: 'spec', t: { zh: '杠杆/保证金/仓位/双开', en: 'lev/margin/slots/hedge' } },
          { id: 'veto', t: { zh: '止损止盈必填 · 越界即拒', en: 'SL+TP required · veto' } }
        ],
        detail: { zh: '开仓入口一票否决：币种白名单、杠杆区间、保证金占比、同币杠杆一致、单仓模式、禁对冲都来自主人的设定；止损和止盈必填且方向要对，限价偏离现价不超过 5%。越界一律拒绝而不是悄悄截断——平台把数字改小模型不知道，后面的止损计算全是错的。拒因原样返回，还把能用的数量区间算好给它，模型改了就能重试；所有调用（含被拒的）都记入动作轨迹。', en: 'A veto at the open: symbol whitelist, leverage range, margin share, same-symbol leverage, single-position mode and no-hedge all come from the owner; stop-loss and take-profit are both required and must sit on the right side, and a limit price may not stray more than 5% from mark. Out-of-range orders are rejected, never silently clamped — a size the model didn’t choose poisons every stop calculation after it. The reason goes back verbatim with the allowed quantity range worked out, so the model can fix and retry; every call, rejected ones included, lands in the action trace.' } },
      { id: 'trade', x: 888, y: 348, w: 180, h: 176, tag: 'TOOLS', icon: 'wrench',
        title: { zh: '交易工具 ×7', en: 'Trade tools ×7' },
        rows: [
          { id: 'acct', t: 'get_account' }, { id: 'open', t: 'open_position' },
          { id: 'close', t: 'close_position' }, { id: 'sl', t: 'set_stop_loss' },
          { id: 'tp', t: 'set_take_profit' }, { id: 'plan', t: 'write_plan' },
          { id: 'cancel', t: 'cancel_order' }
        ],
        detail: { zh: '非 Spring bean：每次唤醒 new 一个，绑定该 trader 的 sim 子账户与币种白名单。开仓要带止损、止盈、论点标签、数据引用和失效条件，计划随开仓落库、之后不可改；write_plan 只给没有计划的持仓补立。平仓要写一句理由。什么时候退出由主人的交易指令定，默认四条路：止损带走、到目标位、失效条件触发、主人留言让离场。', en: 'Not Spring beans: constructed fresh per wakeup, bound to this trader’s sim sub-account and symbol whitelist. An open carries its stop, target, thesis tag, data citation and invalidation; the plan is stored with the position and never edited — write_plan only backfills a position that has none. A close needs a one-line reason. When to exit is the owner’s playbook; the default lists four ways out: stop-loss, target, invalidation, or the owner’s note saying leave.' } },
      { id: 'sim', x: 888, y: 544, w: 180, h: 88, tag: 'SIM', icon: 'server',
        title: { zh: 'wiib-sim 子账户', en: 'wiib-sim account' },
        sub: { zh: '模拟盘账本 · 唯一事实源', en: 'the ledger of record' },
        detail: { zh: '真实开平仓落在 wiib-sim 的子账户上，与真人、量化策略走同一套账本规则：真实档位杠杆、真实资金费、自动强平。', en: 'Real opens and closes land on a wiib-sim sub-account under the same ledger rules as humans and quant strategies: real leverage tiers, real funding, automatic liquidation.' } },
      { id: 'dec', x: 658, y: 246, w: 200, h: 118, tag: 'OUTPUT', icon: 'scroll-text',
        title: { zh: 'ai_trader_decision', en: 'ai_trader_decision' },
        rows: [
          { id: 'full', t: { zh: '决策全文 + 动作轨迹 + 权益', en: 'full reasoning + actions + equity' } },
          { id: 'plan2', t: { zh: '计划 → ai_trader_plan 归档不删', en: 'plans → ai_trader_plan, archived' } }
        ],
        badge: { zh: '全站公开', en: 'PUBLIC' },
        detail: { zh: '推理全文、交易动作、开仓论点与失效条件全部公开上决策时间线；计划了结归档不删，是每日 reviewer 复盘「论点 → 结局」配对的原料。思考过程另走一条线：逐字吐字和每次工具的入参回执实时推给主人（唤醒现场），也落库可回看，但只有主人看得到——公开的只到决策正文为止。', en: 'Full reasoning, trade actions, theses and invalidation conditions all go public on the decision timeline. Settled plans are archived, never deleted — they are the raw material for the daily reviewer’s thesis→outcome pairing. The process runs on a separate line: token-by-token output and every tool’s arguments and results stream live to the owner and are kept for replay, but only the owner can see them — the public part stops at the decision text.' } },
      { id: 'rev', x: 216, y: 460, w: 200, h: 124, tag: 'REVIEW', icon: 'file-text',
        title: { zh: 'reviewer workflow', en: 'reviewer workflow' },
        sub: { zh: '日线边界 · 自己看自己', en: 'daily · self-review' },
        rows: [
          { id: 'r1', t: { zh: '案例事实表代码算好', en: 'case facts precomputed' } },
          { id: 'r2', t: { zh: '单次调用 · 无工具', en: 'single call · no tools' } },
          { id: 'r3', t: { zh: '经验更新 → 账本记账', en: 'ledger updates → booked by code' } }
        ],
        detail: { zh: '不是 agent：素材由代码算齐（战绩表 / 本局论点战绩 / 按单子类型分组的案例事实表与路径指标 / 本期行情 / 账本现状），模型只负责解读，没机会挑对自己有利的行情。复盘按单子类型做案例：亏单答失败怎么界定、失败后怎么处理，赢单答赢在判断还是运气、少赚了多少，界定必须是可观察的价格、根数或时间。产出落进经验账本：支持 / 反对由代码计数，假设攒够支持升为规律，反对多了或久不验证就举旗、交第二次调用复审，退役不删；主人可钉住、退役、否决。窗口内没了结也没开仓的一天直接跳过。', en: 'Not an agent: materials come precomputed in code (scorecard, this round’s play stats, case fact tables with path metrics grouped by setup type, the day’s market, the ledger as it stands); the model only interprets — it never cherry-picks. Reviews are case studies by setup type: a losing trade answers how failure is defined and what to do after it, a winner answers judgement or luck and what was left on the table, and every definition must be an observable price, candle count or time. The output lands in the experience ledger: code counts support and opposition, a hypothesis becomes a rule once support piles up, too much opposition or a long silence raises a flag for a second audit call, retired entries are kept; the owner can pin, retire or veto. A day with nothing settled and nothing opened is skipped.' } }
    ],
    edges: [
      { id: 'ck-sch', from: 'clock', to: 'sched', pts: [[186, 60], [201, 60], [201, 112], [216, 112]] },
      { id: 'sn-sch', from: 'sentinel', to: 'sched', pts: [[186, 172], [216, 172]] },
      { id: 'mn-sch', from: 'manual', to: 'sched', pts: [[186, 278], [201, 278], [201, 192], [216, 192]] },
      { id: 'sch-asm', from: 'sched', to: 'asm', pts: [[412, 136], [442, 136]] },
      { id: 'asm-ag', from: 'asm', to: 'agent', pts: [[638, 136], [668, 136]] },
      { id: 'ag-data', from: 'agent', to: 'data', pts: [[848, 116], [888, 116]] },
      { id: 'ag-guard', from: 'agent', to: 'guard', pts: [[848, 164], [868, 164], [868, 282], [888, 282]] },
      { id: 'guard-trade', from: 'guard', to: 'trade', pts: [[978, 332], [978, 348]] },
      { id: 'trade-sim', from: 'trade', to: 'sim', pts: [[978, 524], [978, 544]] },
      { id: 'ag-dec', from: 'agent', to: 'dec', pts: [[758, 188], [758, 246]] },
      { id: 'dec-rev', from: 'dec', to: 'rev', pts: [[758, 364], [758, 522], [416, 522]] },
      { id: 'notes-asm', from: 'rev', to: 'asm', cls: 'back', pts: [[316, 460], [316, 412], [540, 412], [540, 210]] }
    ],
    foot: [
      { zh: '日线交接：交易 ▸ 全体复盘 · 停工窗口拒绝一切唤醒', en: 'Daily handover: trade ▸ all reviews · blackout window rejects every wakeup' },
      { zh: '连败 5 次 → PAUSED · 权益 < 初始 1% → LIQUIDATED', en: '5 straight failures → PAUSED · equity < 1% of seed → LIQUIDATED' }
    ]
  },
  {
    id: 'chat',
    num: 'HARNESS 02',
    title: { zh: 'chat agent', en: 'chat agent' },
    sub: { zh: '研判工作台 · 单 ReAct 主 agent + 后台子运行', en: 'research workbench · one ReAct main agent + background child runs' },
    w: 1180, h: 600,
    lanes: [
      { x: 556, y: 22, t: { zh: '后台子运行 · CHILD RUNS', en: 'CHILD RUNS · BACKGROUND' } }
    ],
    nodes: [
      { id: 'user', x: 16, y: 64, w: 150, h: 76, tag: 'USER', icon: 'user',
        title: { zh: '用户提问', en: 'User question' },
        sub: { zh: 'BYOK · SSE 流式', en: 'BYOK · SSE streaming' },
        detail: { zh: '全员开放的 BYOK 对话：模型和 key 都是用户自己的，按 CHAT_MAIN 绑定取端点，没绑就用默认端点。', en: 'Open to everyone, BYOK: the model and key are the user’s own, resolved via the CHAT_MAIN binding or the default endpoint.' } },
      { id: 'gates', x: 16, y: 174, w: 150, h: 130, tag: 'GATE', icon: 'shield-check',
        title: { zh: '四道准入', en: 'Four gates' },
        rows: [
          { id: 'g1', led: true, t: { zh: '端点解析 · 2201', en: 'endpoint · 2201' } },
          { id: 'g2', led: true, t: { zh: '叶子缓存 LRU32 · 2202', en: 'leaf LRU32 · 2202' } },
          { id: 'g3', led: true, t: { zh: '用户并发闸 · 2203', en: 'user gate · 2203' } },
          { id: 'g4', led: true, t: { zh: '全局闸 10 槽 · 2204', en: 'global ×10 · 2204' } }
        ],
        detail: { zh: '四道准入全在建流之前：解析端点、按配置指纹取/建叶子（LRU 32，指纹含 userId 做数据隔离）、用户并发闸、全局 10 槽。全过了才建 SSE 流——一旦响应变成 event-stream，再报错前端就拿不到结构化错误码了。', en: 'All four gates run before the stream exists: endpoint resolution, leaf fetch/build by config fingerprint (LRU 32; userId is in the fingerprint for data isolation), the per-user gate, the 10-slot global gate. Only then is the SSE stream created — once the response is an event-stream, errors can no longer carry structured codes.' } },
      { id: 'main', x: 226, y: 120, w: 270, h: 300, tag: 'MAIN AGENT', icon: 'zap',
        title: { zh: '主 agent · 深模型', en: 'main agent · deep model' },
        counter: { label: { zh: '模型调用', en: 'model calls' }, max: 8 },
        chips: [
          { id: 'cmk', t: 'market ×4' }, { id: 'cnw', t: 'news_search' }, { id: 'ctr', t: 'trader_* ×5' },
          { id: 'cfm', t: 'wake / review / note' }, { id: 'cbh', t: 'analyze_my_behavior' },
          { id: 'cda', t: 'run_deep_analysis' }, { id: 'cdg', t: 'delegate' }
        ],
        rows: [
          { id: 'par', t: { zh: '同批 tool_call 并发执行', en: 'same-batch tool calls run in parallel' } },
          { id: 'inbox', t: { zh: '收件箱 · 调模型前先排报告', en: 'inbox · reports queued before each call' } },
          { id: 'ws', t: { zh: '服务端搜索 · 端点勾选才有', en: 'server search · if the endpoint allows' } }
        ],
        stream: 3,
        detail: { zh: '一轮只有这一个 agent：深模型 + 16 个工具的 ReactLoop，流式作答。取什么数它自己定，同一批 tool_call 在虚拟线程上并发、回执按原顺序接回；不强制 tool_choice（Opus 5.5 这类模型不收强制），「先取数再答」写在系统提示词里。用户自己的 trader 由它直接查：五个只读工具按 userId 烤死在叶子里——这也是 userId 必须进缓存指纹的原因。wake / review / note 三个动作只往对话里推表单，按下按钮的是用户；run_deep_analysis 当场烧钱，走 HITL 闸，批准后转后台子运行；读得多的活用 delegate 派给后台 worker。每次调模型前先把收件箱里到的子运行报告排进上下文。', en: 'The only agent in a turn: a ReactLoop on the deep model with 16 tools, streaming its answer. It decides what to fetch; tool calls in one batch run in parallel on virtual threads and their results are paired back in order. tool_choice is never forced (models like Opus 5.5 reject it); “fetch first, then answer” lives in the system prompt. It reads the user’s own trader directly through five read-only tools baked to the userId inside the leaf — which is why userId must be part of the cache fingerprint. The three trader actions merely push a form into the chat (the user presses the button); run_deep_analysis burns money at once, so it passes the HITL gate and then runs as a background child run; heavy reading goes to background workers via delegate. Before every model call, the inbox of arrived child-run reports is appended to the context.' } },
      { id: 'cr', x: 556, y: 40, w: 230, h: 150, tag: 'CHILD RUNS', icon: 'layers',
        title: 'ChildRunRegistry',
        rows: [
          { id: 'c1', t: { zh: '用户开关 · 上限 3 · 全局 20', en: 'user switch · limit 3 · global 20' } },
          { id: 'c2', t: { zh: '轮在跑 → 进收件箱', en: 'turn running → inbox' } },
          { id: 'c3', t: { zh: '没轮 → 落库标 pending', en: 'no turn → stored, pending' } }
        ],
        detail: { zh: '主 agent 派出去的长活都在这里登记：深研判（批准后）与 delegate 派的 worker 共用一套运行时，区别只在任务书是代码写死还是模型现写。派发前三道检查：用户开关（只管 delegate，深研判是用户点过头的）→ 该用户在跑数不超上限（默认 3，可调到 5）→ 全局 20 个的信号量（子运行在对话名额之外，行情配额得另有一道闸）；拒了把拒因原样回给模型，它自己做。报告回来只有一条规则：会话正挂着一轮就进收件箱，没挂就在会话锁里直接落进上下文并标 pending。超时、失败也回灌一条失败报告，取消的不回灌。', en: 'Every slow job the main agent hands off is registered here: deep analysis (once approved) and delegate workers share one runtime; the only difference is whether the brief is hard-coded or written by the model. Three checks before dispatch: the user’s switch (delegate only — deep analysis was approved on the card) → this user’s running count under the limit (3 by default, up to 5) → a global semaphore of 20 (child runs sit outside the chat slot, so the market-data quota needs its own gate); a refusal goes back to the model verbatim and it does the work itself. One rule for returning reports: if a turn is attached the report goes into its inbox, otherwise it is appended to the context under the session lock and marked pending. Timeouts and failures still deliver a failure report; cancelled runs deliver nothing.' } },
      { id: 'wr', x: 836, y: 40, w: 170, h: 92, tag: 'WORKER', icon: 'globe',
        title: { zh: 'researcher · 浅模型', en: 'researcher · light model' },
        rows: [
          { id: 'rn', t: 'news_search' },
          { id: 'rw', t: { zh: '联网搜索 · 端点允许时', en: 'web search · if allowed' } }
        ],
        detail: { zh: '调研员预设：浅模型 + news_search + 服务端搜索许可（看浅模型那条端点）。任务书是它唯一的用户消息，看不到主对话；先调快讯再按需联网，报告先结论后依据、每条事实注明来源。没有 delegate、没有动作工具——深度只有一层。独享账本，用量只打日志不进主轮的账。', en: 'The researcher preset: light model + news_search + the server-search grant (taken from the light-model endpoint). The brief is its only user message; it never sees the main conversation. Flashes first, then the web as needed; the report leads with conclusions and cites a source for every fact. No delegate, no action tools — depth is one level. It has its own usage ledger, logged only, never mixed into the turn’s bill.' } },
      { id: 'wa', x: 836, y: 150, w: 170, h: 92, tag: 'WORKER', icon: 'candlestick-chart',
        title: { zh: 'analyst · 浅模型', en: 'analyst · light model' },
        rows: [
          { id: 'am', t: { zh: '行情 ×4 · 指标 ×3', en: 'market ×4 · indicators ×3' } },
          { id: 'ab', t: { zh: '只读 · 不许再派', en: 'read-only · no further dispatch' } }
        ],
        detail: { zh: '行情分析员预设：浅模型 + 行情 4 个 + 指标 3 个（klines / indicators / kline_structure）。按任务书涉及的币和周期逐个取数，不凭记忆给数字；简报先结论后数据、给出失效条件。同样看不到主对话、没有 delegate。', en: 'The analyst preset: light model + 4 market tools + 3 indicator tools (klines / indicators / kline_structure). It fetches symbol by symbol and timeframe by timeframe as the brief requires and never quotes a number from memory; the brief leads with conclusions, backs them with the data and names an invalidation. It too is blind to the main conversation and has no delegate.' } },
      { id: 'da', x: 836, y: 260, w: 170, h: 92, tag: 'CHILD', icon: 'scale',
        title: { zh: '深研判子运行', en: 'deep-analysis run' },
        rows: [
          { id: 'd1', t: 'Bull ∥ Bear → Judge' },
          { id: 'd2', t: { zh: '3 次深模型调用', en: '3 deep-model calls' } }
        ],
        detail: { zh: 'run_deep_analysis 过了 HITL 闸门不再同步跑：spawn 一个子运行立刻返回 RUNNING，主 agent 先答能答的。子运行里 Bull 与 Bear 在虚拟线程上并行辩论，再由 Judge 裁决，阶段进度挂在过程轨的子运行条目上；裁决作为报告回灌，主 agent 被唤醒补答。', en: 'Once through the HITL gate, run_deep_analysis no longer runs inline: it spawns a child run and returns RUNNING at once, and the main agent answers what it can. Inside, Bull and Bear argue in parallel on virtual threads, then the Judge rules; stage progress shows on the child-run entry of the process rail. The verdict comes back as a report and the main agent is woken to follow up.' } },
      { id: 'hitl', x: 1016, y: 190, w: 150, h: 184, tag: 'HITL', icon: 'shield-check', hitl: true,
        title: 'run_deep_analysis',
        rows: [
          { id: 'hsym', t: { zh: '标的 ETHUSDT', en: 'symbol ETHUSDT' } },
          { id: 'hcost', t: { zh: '烧 3 次深模型调用', en: 'costs 3 deep calls' } },
          { id: 'hkey', t: { zh: '键 = 会话+工具+标的', en: 'key = session+tool+symbol' } }
        ],
        btns: { ok: { zh: '确认执行', en: 'Approve' }, no: { zh: '取消', en: 'Dismiss' } },
        detail: { zh: '闸门不在工具体内，是主 agent 的 ReactLoop 执行工具前先问的一道关（ApprovalGate）：授权键 = sessionId + 工具名 + 归一化标的。卡片上写 ETHUSDT、模型改口要 BTCUSDT 时键不匹配，重新弹卡——判断发生在信息完整的那一层。', en: 'The gate is not inside the tool: the main agent’s ReactLoop asks it before running any tool (ApprovalGate). Approval key = sessionId + tool + normalized symbol. If the card says ETHUSDT and the model switches to BTCUSDT, the key mismatches and a new card pops — the judgment lives at the layer that has full information.' } },
      { id: 'hist', x: 226, y: 460, w: 270, h: 100, tag: 'STORE', icon: 'database',
        title: { zh: '会话历史', en: 'Session history' },
        rows: [
          { id: 'ow', t: { zh: '终态整体覆盖写', en: 'final state overwrites whole' } },
          { id: 'cp', t: { zh: '>32k tokens 浅模型压缩', en: '>32k tokens → light-model summary' } },
          { id: 'nx', t: { zh: '下一轮从这里起跑', en: 'the next turn starts here' } }
        ],
        detail: { zh: '一轮的终态（含压缩替换后的历史）整体覆盖写入 workbench_chat_context，裸 JSON 落库，下一轮从这里起跑。历史估算超过 32k tokens 时，浅模型把老消息总结成一段替换原文，只留最近几条原样。', en: 'The turn’s final state — compressed history included — overwrites workbench_chat_context wholesale as plain JSON; the next turn starts from there. Past roughly 32k tokens the light model folds the older messages into one summary and keeps only the last few verbatim.' } },
      { id: 'wake', x: 16, y: 444, w: 150, h: 108, tag: 'WAKE', icon: 'activity',
        title: { zh: '唤醒轮 · /wake', en: 'follow-up · /wake' },
        rows: [
          { id: 'w1', t: 'done.pending = true' },
          { id: 'w2', t: { zh: '前端本地空闲时发起', en: 'fired when the client is idle' } },
          { id: 'w3', t: { zh: '答案带【补充回答】标头', en: 'answer headed [Follow-up]' } }
        ],
        detail: { zh: '报告落进上下文而没有哪一轮答过，后端就标 pending，随 done 帧与 status 接口一起下发。唤醒由前端发起：本地没有轮在跑、排队消息也发完了，才 POST /wake；不欠就 2208（别的标签页先接走了，或后端重启丢了标记）。唤醒轮不落 user 行，用户侧消息是一条唤醒指令，答案带【补充回答】标头、不给重新生成；其余与普通轮一样收尾。', en: 'When a report lands in the context and no turn has answered it, the backend marks pending and sends it with the done frame and the status endpoint. The client starts the follow-up: only when nothing runs locally and the queue is drained does it POST /wake; if nothing is owed the call gets 2208 (another tab took it, or a restart lost the flag). The follow-up turn stores no user row — its user-side message is a wake instruction — and its answer carries the [Follow-up] header and cannot be regenerated; otherwise it ends like any other turn.' } }
    ],
    edges: [
      { id: 'u-g', from: 'user', to: 'gates', pts: [[91, 140], [91, 174]] },
      { id: 'g-m', from: 'gates', to: 'main', pts: [[166, 250], [226, 250]] },
      { id: 'm-cr', from: 'main', to: 'cr', pts: [[496, 160], [526, 160], [526, 115], [556, 115]] },
      { id: 'cr-wr', from: 'cr', to: 'wr', pts: [[786, 86], [836, 86]] },
      { id: 'cr-wa', from: 'cr', to: 'wa', pts: [[786, 130], [811, 130], [811, 196], [836, 196]] },
      { id: 'cr-da', from: 'cr', to: 'da', pts: [[786, 160], [801, 160], [801, 306], [836, 306]] },
      { id: 'cr-m', from: 'cr', to: 'main', cls: 'back', pts: [[556, 170], [516, 170], [516, 300], [496, 300]] },
      { id: 'cr-h', from: 'cr', to: 'hist', cls: 'back', pts: [[671, 190], [671, 510], [496, 510]] },
      { id: 'm-h', from: 'main', to: 'hist', pts: [[361, 420], [361, 460]] },
      { id: 'h-w', from: 'hist', to: 'wake', cls: 'back', pts: [[226, 510], [166, 510]] },
      { id: 'w-m', from: 'wake', to: 'main', cls: 'back', pts: [[91, 444], [91, 430], [200, 430], [200, 380], [226, 380]] },
      { id: 's-u', from: 'main', to: 'user', cls: 'answer', pts: [[361, 120], [361, 16], [91, 16], [91, 64]] }
    ],
    foot: [
      { zh: '长活不在轮里等：深研判 / 调研 / 行情分析派成后台子运行 · 报告回灌上下文 · 本地空闲时唤醒轮补答', en: 'Slow work never blocks a turn: deep analysis / research / market analysis run as child runs · reports flow back into the context · a follow-up turn answers once the client is idle' },
      { zh: '主 agent 与每个 worker 各自 ≤8 次调用 · 同批工具并发 · 未吐帧才重订阅 · 不挂兜底模型', en: 'the main agent and each worker count their own ≤8 calls · same-batch tools in parallel · re-subscribe only before the first frame · no fallback model' }
    ]
  }
];

/* 窄屏 stepper：卡片顺序 + 缩进层级。所有边都走左侧同一条总线，不再分左右廊道 */
const STEP = {
  trader: {
    order: ['clock', 'sentinel', 'manual', 'sched', 'asm', 'agent', 'data', 'guard', 'trade', 'sim', 'dec', 'rev'],
    indent: { data: 1, guard: 1, trade: 1, sim: 2 },
    groups: [
      { from: 'clock', to: 'manual', t: { zh: '三种唤醒源 · 任一即可', en: 'three wake sources' } },
      { from: 'data', to: 'sim', t: { zh: '决策循环里被调用', en: 'called from inside the loop' } },
      { from: 'rev', to: 'rev', t: { zh: '日终交接 · 一天一次', en: 'end of day · once daily' } }
    ]
  },
  chat: {
    order: ['user', 'gates', 'main', 'cr', 'wr', 'wa', 'da', 'hist', 'wake'],
    indent: { cr: 1, wr: 2, wa: 2, da: 2, wake: 1 },
    float: { hitl: { over: 'main', dy: 0, inset: 0 } },   // 窄屏卡片只剩标题，HITL 卡直接盖在主 agent 上
    groups: [
      { from: 'cr', to: 'da', t: { zh: '后台子运行 · 并行', en: 'child runs · in parallel' } }
    ]
  }
};

/* ---------- 渲染 ---------- */
/* 导线：折线直角拐弯 */
function orthPath(pts) {
  return pts.map(([x, y], i) => `${i ? 'L' : 'M'} ${x} ${y}`).join(' ');
}

function renderPanel(cfg, mount) {
  const panel = el('section', 'panel');
  panel.id = 'panel-' + cfg.id;

  const head = el('div', 'panel-head');
  const led = el('span', 'led');
  const title = el('div', 'panel-title');
  title.innerHTML = `<span class="pn">${cfg.num}</span><b>${esc(T(cfg.title))}</b><small>${esc(T(cfg.sub))}</small>`;
  const status = el('div', 'panel-status');
  const transport = el('div', 'transport');
  const bPlay = el('button', 'ibtn'); bPlay.innerHTML = '<svg data-icon="pause"></svg>'; bPlay.title = T(UI.pause);
  const bRe = el('button', 'ibtn'); bRe.innerHTML = '<svg data-icon="rotate-ccw"></svg>'; bRe.title = T(UI.restart);
  const bSpd = el('button', 'ibtn txt num'); bSpd.textContent = '1.0×';
  transport.append(bPlay, bRe, bSpd);
  head.append(led, title, status, transport);

  const wrap = el('div', 'canvas-wrap');
  const canvas = el('div', 'canvas');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'wires');
  // 总线底轨：只在 stepper 模式画
  const rail = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  rail.setAttribute('class', 'rail');
  svg.appendChild(rail);
  const paths = {};
  for (const eg of cfg.edges) {
    const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    if (eg.cls) p.classList.add(eg.cls);
    svg.appendChild(p);
    paths[eg.id] = p;
  }
  canvas.appendChild(svg);

  const view = { cfg, panel, led, status, bPlay, bRe, bSpd, svg, rail, paths, nodes: {}, tlines: null, canvas, wrap, box: {}, mode: null, lanes: [], groups: [], legend: null };

  for (const ln of cfg.lanes || []) {
    const d = el('div', 'lane-label' + (ln.right ? ' right' : ''), esc(T(ln.t)));
    canvas.appendChild(d);
    view.lanes.push({ cfg: ln, el: d });
  }

  for (const n of cfg.nodes) {
    const d = el('div', 'node' + (n.hitl ? ' hitl-card' : ''));
    let inner = `<div class="nhead"><svg data-icon="${n.icon}"></svg><span class="ntag">${n.tag}</span><span class="ndot"></span></div>`;
    inner += `<div class="ntitle">${esc(T(n.title))}</div>`;
    if (n.sub) inner += `<div class="nsub">${esc(T(n.sub))}</div>`;
    if (n.counter) inner += `<div class="ncount"><b data-c>0</b><span>/ ${n.counter.max} · ${esc(T(n.counter.label))}</span></div>`;
    if (n.rows) inner += `<div class="nrows${n.rows.every(r => typeof r.t === 'string') ? ' cols' : ''}">${n.rows.map(r => `<div class="nrow" data-row="${r.id}">${r.led ? '<span class="rled"></span>' : '<span class="rled" style="opacity:.15"></span>'}<span>${esc(T(r.t))}</span></div>`).join('')}</div>`;
    if (n.chips) inner += `<div class="nchips">${n.chips.map(c => `<span class="nchip" data-chip="${c.id}">${esc(T(c.t))}</span>`).join('')}</div>`;
    if (n.stream) inner += `<div class="nstream">${'<div class="sline"></div>'.repeat(n.stream)}</div>`;
    if (n.badge) inner += `<span class="badge-pub">${esc(T(n.badge))}</span>`;
    if (n.btns) inner += `<div class="hitl-btns"><button class="hitl-btn ok">${esc(T(n.btns.ok))}</button><button class="hitl-btn">${esc(T(n.btns.no))}</button></div>`;
    d.innerHTML = inner;
    if (!n.hitl) d.addEventListener('click', ev => { ev.stopPropagation(); showPop(view, n); });
    canvas.appendChild(d);
    view.nodes[n.id] = d;
  }

  // 图例：trader 图左下角那块空地，只在宽屏出现
  if (cfg.id === 'trader') {
    const lg = UI.legend;
    const legend = el('div', 'legend');
    legend.innerHTML = `<div class="lg-t">${esc(T(lg.title))}</div>
      <div class="lg-row"><span class="lg-pulse"></span>${esc(T(lg.pulse))}</div>
      <div class="lg-row"><span class="lg-led"></span>${esc(T(lg.led))}</div>
      <div class="lg-row"><span class="lg-dash"></span>${esc(T(lg.back))}</div>
      <div class="lg-row"><span class="lg-pulse bad"></span>${esc(T(lg.bad))}</div>
      <div class="lg-row"><svg data-icon="mouse-pointer-click"></svg>${esc(T(lg.click))}</div>`;
    canvas.appendChild(legend);
    view.legend = legend;
  }
  // stepper 的分组括注
  for (const g of (STEP[cfg.id].groups || [])) {
    const d = el('div', 'step-group', `<span>${esc(T(g.t))}</span>`);
    canvas.appendChild(d);
    view.groups.push({ cfg: g, el: d });
  }
  wrap.appendChild(canvas);

  const trace = el('div', 'trace');
  trace.innerHTML = `<div class="trace-head">${UI.trace}<span class="led run"></span></div>`;
  const tlines = el('div', 'trace-lines');
  trace.appendChild(tlines);
  view.tlines = tlines;

  const foot = el('div', 'panel-foot');
  foot.innerHTML = cfg.foot.map(f => `<span>${esc(T(f))}</span>`).join('');

  panel.append(head, wrap, trace, foot);
  mount.appendChild(panel);
  applyLayout(view);
  return view;
}

/* 节点详情浮层 */
let popEl = null;
function closePop() { if (popEl) { popEl.remove(); popEl = null; } }
function showPop(view, n) {
  closePop();
  const box = view.box[n.id];
  const pop = el('div', 'pop');
  pop.innerHTML = `<div class="pt">${esc(T(n.title))}<button class="px" aria-label="close"><svg data-icon="x"></svg></button></div><div class="pd">${esc(T(n.detail || n.sub || ''))}</div>`;
  if (view.mode === 'step') {
    // 窄屏：浮层压在节点自己身上，宽度跟卡片走
    pop.style.left = box.x + 'px';
    pop.style.width = box.w + 'px';
    pop.style.top = (box.y + 24) + 'px';
  } else {
    const cw = view.canvas.clientWidth;
    let left = box.x + box.w + 12;
    if (left + 250 > cw) left = Math.max(8, box.x - 262);
    pop.style.left = left + 'px';
    pop.style.top = Math.min(box.y, view.canvas.clientHeight - 150) + 'px';
  }
  pop.addEventListener('click', ev => ev.stopPropagation());
  pop.querySelector('.px').addEventListener('click', closePop);
  view.canvas.appendChild(pop);
  popEl = pop;
  icons(pop);
}
document.addEventListener('click', closePop);

/* ---------- 布局：宽屏泳道图 / 窄屏单轨 stepper ---------- */
/* 宽屏用 cfg 里的绝对坐标（cfg.w × cfg.h）。放不下就切 stepper：
   一条竖直总线贴左，卡片按 STEP.order 竖排、按 indent 缩进；
   每条边 = 出卡片头行 → 上总线 → 沿总线走到目标卡头行 → 进卡片。
   所有边共用一条轨，只有点亮的那条会显色，脉冲永远沿轨走，一眼看得出方向。
   高度实测（中英文换行不同，写死必截断）。既不横滚，也不 scale 糊字。 */
const STEP_RAIL = 14;     // 总线 x
const STEP_PAD_L = 30;    // 卡片左边缘（一级）
const STEP_INDENT = 16;   // 每级缩进
const STEP_PAD_R = 12;
const STEP_GAP = 12;      // 卡片竖向间距（窄屏卡片只剩一行标题，间距跟着收）
const STEP_GAP_GROUP = 30; // 分组之间多留一点，给括注
const STEP_TOP = 14;
const STUB_Y = 19;        // 出/入卡片的线落在卡片竖向中线附近（单行卡高约 38）

function applyLayout(view) {
  const cfg = view.cfg;
  // 量 panel 而不是 canvas-wrap：wrap 的 clientWidth 会被自己的滚动条影响，反复横跳
  const avail = Math.max(272, view.panel.clientWidth);
  const mode = avail >= cfg.w ? 'wide' : 'step';
  const changed = mode !== view.mode;
  view.mode = mode;
  view.panel.classList.toggle('step', mode === 'step');

  let CW, CH;
  if (mode === 'wide') {
    CW = cfg.w; CH = cfg.h;
    for (const n of cfg.nodes) {
      const d = view.nodes[n.id];
      d.style.cssText = `left:${n.x}px;top:${n.y}px;width:${n.w}px;height:${n.h}px`;
      view.box[n.id] = { x: n.x, y: n.y, w: n.w, h: n.h };
    }
    for (const eg of cfg.edges) view.paths[eg.id].setAttribute('d', orthPath(eg.pts));
    view.rail.setAttribute('d', '');
    for (const ln of view.lanes) {
      ln.el.style.display = '';
      ln.el.style.left = ln.cfg.x + 'px'; ln.el.style.top = ln.cfg.y + 'px';
    }
    if (view.legend) { view.legend.style.display = ''; view.legend.style.cssText = 'left:16px;top:460px;width:170px;height:124px'; }
    for (const g of view.groups) g.el.style.display = 'none';
  } else {
    const sp = STEP[cfg.id];
    CW = avail;
    const floats = sp.float || {};
    const indentOf = id => (sp.indent && sp.indent[id]) || 0;
    const groupStart = new Set((sp.groups || []).map(g => g.from));
    const groupEnd = new Set((sp.groups || []).map(g => g.to));

    // ① 先写宽度、高度放开，② 一次性量高，③ 再落 top —— 只触发一次回流
    for (const id of sp.order) {
      const x = STEP_PAD_L + indentOf(id) * STEP_INDENT;
      view.nodes[id].style.cssText = `left:${x}px;top:0px;width:${CW - x - STEP_PAD_R}px;height:auto`;
    }
    for (const id in floats) {
      const f = floats[id];
      const base = view.box[f.over] || { x: STEP_PAD_L, w: CW - STEP_PAD_L - STEP_PAD_R };
      view.nodes[id].style.cssText = `left:${STEP_PAD_L + f.inset}px;top:0px;width:${CW - STEP_PAD_L - STEP_PAD_R - f.inset * 2}px;height:auto`;
    }
    const hs = sp.order.map(id => view.nodes[id].offsetHeight);
    let y = STEP_TOP;
    sp.order.forEach((id, i) => {
      if (groupStart.has(id)) y += 22;                 // 括注占位
      const x = STEP_PAD_L + indentOf(id) * STEP_INDENT;
      view.nodes[id].style.top = y + 'px';
      view.box[id] = { x, y, w: CW - x - STEP_PAD_R, h: hs[i] };
      y += hs[i] + (groupEnd.has(id) ? STEP_GAP_GROUP : STEP_GAP);
    });
    CH = y - STEP_GAP + STEP_TOP;
    for (const id in floats) {
      const f = floats[id], base = view.box[f.over], top = base.y + f.dy;
      view.nodes[id].style.top = top + 'px';
      view.box[id] = { x: base.x + f.inset, y: top, w: base.w - f.inset * 2, h: view.nodes[id].offsetHeight };
    }
    // 边：出头行 → 总线 → 目标头行
    for (const eg of cfg.edges) {
      const a = view.box[eg.from], b = view.box[eg.to];
      const ay = a.y + STUB_Y, by = b.y + STUB_Y;
      view.paths[eg.id].setAttribute('d', orthPath([[a.x, ay], [STEP_RAIL, ay], [STEP_RAIL, by], [b.x, by]]));
    }
    // 底轨：从第一张卡头行到最后一张
    const first = view.box[sp.order[0]], last = view.box[sp.order[sp.order.length - 1]];
    view.rail.setAttribute('d', `M ${STEP_RAIL} ${first.y + STUB_Y} V ${last.y + STUB_Y}`);
    for (const ln of view.lanes) ln.el.style.display = 'none';
    if (view.legend) view.legend.style.display = 'none';
    for (const g of view.groups) {
      const from = view.box[g.cfg.from], to = view.box[g.cfg.to];
      g.el.style.display = '';
      g.el.style.left = from.x + 'px';
      g.el.style.top = (from.y - 15) + 'px';
      g.el.style.height = (to.y + to.h - from.y + 15) + 'px';
    }
  }
  view.canvas.style.width = CW + 'px';
  view.canvas.style.height = CH + 'px';
  view.svg.setAttribute('viewBox', `0 0 ${CW} ${CH}`);
  return changed;
}

/* ---------- 图标内嵌（lucide 线稿，MIT），不依赖任何 CDN ---------- */
const ICONS = {
  'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
  'moon': '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  'play': '<polygon points="6 3 20 12 6 21 6 3"/>',
  'pause': '<rect width="4" height="16" x="6" y="4" rx="1"/><rect width="4" height="16" x="14" y="4" rx="1"/>',
  'rotate-ccw': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
  'x': '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  'clock': '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
  'activity': '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
  'mouse-pointer-click': '<path d="m9 9 5 12 1.8-5.2L21 14Z"/><path d="M7.2 2.2 8 5.1"/><path d="m5.1 8-2.9-.8"/><path d="M14 4.1 12 6"/><path d="m6 12-1.9 2"/>',
  'timer': '<line x1="10" x2="14" y1="2" y2="2"/><line x1="12" x2="15" y1="14" y2="11"/><circle cx="12" cy="14" r="8"/>',
  'file-text': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/>',
  'cpu': '<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M9 2v2"/><path d="M15 2v2"/><path d="M9 20v2"/><path d="M15 20v2"/><path d="M2 9h2"/><path d="M2 15h2"/><path d="M20 9h2"/><path d="M20 15h2"/>',
  'database': '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 12c0 1.66 4 3 9 3s9-1.34 9-3"/>',
  'shield-check': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
  'wrench': '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>',
  'server': '<rect width="20" height="8" x="2" y="2" rx="2"/><rect width="20" height="8" x="2" y="14" rx="2"/><path d="M6 6h.01"/><path d="M6 18h.01"/>',
  'scroll-text': '<path d="M8 21h12a2 2 0 0 0 2-2v-2H10v2a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v3h4"/><path d="M19 17V5a2 2 0 0 0-2-2H4"/><path d="M15 8h-5"/><path d="M15 12h-5"/>',
  'user': '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  'route': '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
  'candlestick-chart': '<path d="M9 5v4"/><rect width="4" height="6" x="7" y="9" rx="1"/><path d="M9 15v2"/><path d="M17 3v2"/><rect width="4" height="8" x="15" y="5" rx="1"/><path d="M17 13v3"/><path d="M3 3v18h18"/>',
  'newspaper': '<path d="M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-2 2Zm0 0a2 2 0 0 1-2-2v-9c0-1.1.9-2 2-2h2"/><path d="M18 14h-8"/><path d="M15 18h-5"/><path d="M10 6h8v4h-8V6Z"/>',
  'bot': '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
  'zap': '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
  'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
  'arrow-up-right': '<path d="M7 7h10v10"/><path d="M7 17 17 7"/>',
  'arrow-down-right': '<path d="m7 7 10 10"/><path d="M17 7v10H7"/>',
  'github': '<path d="M15 22v-4a4.8 4.8 0 0 0-1-3.5c3 0 6-2 6-5.5.08-1.25-.27-2.48-1-3.5.28-1.15.28-2.35 0-3.5 0 0-1 0-3 1.5-2.64-.5-5.36-.5-8 0C6 2 5 2 5 2c-.3 1.15-.3 2.35 0 3.5A5.403 5.403 0 0 0 4 9c0 3.5 3 5.5 6 5.5-.39.49-.68 1.05-.85 1.65-.17.6-.22 1.23-.15 1.85v4"/><path d="M9 18c-4.51 2-5-2-7-2"/>',
  'layers': '<path d="M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/>',
  'scale': '<path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="M7 21h10"/><path d="M12 3v18"/><path d="M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>',
  'trending-up': '<polyline points="22 7 13.5 15.5 8.5 10.5 2 17"/><polyline points="16 7 22 7 22 13"/>',
  'key': '<path d="m15.5 7.5 2.3 2.3a1 1 0 0 0 1.4 0l2.1-2.1a1 1 0 0 0 0-1.4L19 4"/><path d="m21 2-9.6 9.6"/><circle cx="7.5" cy="15.5" r="5.5"/>',
  'notebook-pen': '<path d="M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4"/><path d="M2 6h4"/><path d="M2 10h4"/><path d="M2 14h4"/><path d="M2 18h4"/><path d="M21.4 4.6a2 2 0 0 0-2.8 0L12 11.2V14h2.8l6.6-6.6a2 2 0 0 0 0-2.8Z"/>',
  'clipboard-list': '<rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/>',
  'chevron-down': '<path d="m6 9 6 6 6-6"/>',
  'chevron-up': '<path d="m18 15-6-6-6 6"/>',
  'wallet': '<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
  'smartphone': '<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/>',
  'flask-conical': '<path d="M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2"/><path d="M6.453 15h11.094"/><path d="M8.5 2h7"/>',
  'line-chart': '<path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/>',
  'message-square': '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
  'globe': '<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"/><path d="M2 12h20"/>',
  'mouse': '<rect x="5" y="2" width="14" height="20" rx="7"/><path d="M12 6v4"/>',
  'chevrons-down': '<path d="m7 6 5 5 5-5"/><path d="m7 13 5 5 5-5"/>',
  'chevron-right': '<path d="m9 18 6-6-6-6"/>',
  'percent': '<line x1="19" x2="5" y1="5" y2="19"/><circle cx="6.5" cy="6.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/>',
  'landmark': '<line x1="3" x2="21" y1="22" y2="22"/><line x1="6" x2="6" y1="18" y2="11"/><line x1="10" x2="10" y1="18" y2="11"/><line x1="14" x2="14" y1="18" y2="11"/><line x1="18" x2="18" y1="18" y2="11"/><polygon points="12 2 20 7 4 7"/>',
  'bitcoin': '<path d="M11.767 19.089c4.924.868 6.14-6.025 1.216-6.894m-1.216 6.894L5.86 18.047m5.908 1.042-.347 1.97m1.563-8.864c4.924.869 6.14-6.025 1.215-6.893m-1.215 6.893-3.94-.694m5.155-6.2L8.29 4.26m5.908 1.042.348-1.97M7.48 20.364l3.126-17.727"/>',
  'gem': '<path d="M6 3h12l4 6-10 13L2 9Z"/><path d="M11 3 8 9l4 13 4-13-3-6"/><path d="M2 9h20"/>',
  'chevrons-up': '<path d="m17 11-5-5-5 5"/><path d="m17 18-5-5-5 5"/>',
  'languages': '<path d="m5 8 6 6"/><path d="m4 14 6-6 2-3"/><path d="M2 5h12"/><path d="M7 2h1"/><path d="m22 22-5-10-5 10"/><path d="M14 18h6"/>'
};
function icons(scope) {
  (scope || document).querySelectorAll('svg[data-icon]').forEach(s => {
    const body = ICONS[s.getAttribute('data-icon')];
    if (!body) return;
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('fill', 'none');
    s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '2');
    s.setAttribute('stroke-linecap', 'round');
    s.setAttribute('stroke-linejoin', 'round');
    s.innerHTML = body;
  });
}

/* ---------- 动画引擎 ---------- */
class Abort extends Error { }
class Runner {
  constructor(view, script, scenes) {
    this.v = view; this.script = script; this.scenes = scenes;
    this.pinned = null;   // 钉住的场景序号，null=轮播
    this.speed = 1; this.paused = false; this.runId = 0; this.userPaused = false;
    this.clock = 8 * 3600 * 1000; // 08:00:00 起步的假钟
  }
  chk(id) { if (id !== this.runId) throw new Abort(); }
  async sleep(ms) {
    const id = this.runId, end = performance.now() + ms / this.speed;
    while (performance.now() < end || this.paused) {
      this.chk(id);
      await new Promise(r => setTimeout(r, 40));
      if (this.paused) continue;
    }
    this.chk(id);
  }
  node(nid) { return this.v.nodes[nid]; }
  on(nid) { this.node(nid).classList.add('on'); this.node(nid).classList.remove('done', 'err'); }
  done(nid) { this.node(nid).classList.remove('on', 'err'); this.node(nid).classList.add('done'); }
  err(nid) { this.node(nid).classList.add('err'); }
  clearErr(nid) { this.node(nid).classList.remove('err'); }
  row(nid, rid, cls = 'hit', keep = false) {
    const r = this.node(nid).querySelector(`[data-row="${rid}"]`);
    if (!r) return;
    r.classList.add(cls);
    if (!keep) setTimeout(() => r.classList.remove(cls), 1100 / this.speed);
  }
  ledOk(nid, rid) { const r = this.node(nid).querySelector(`[data-row="${rid}"]`); if (r) r.classList.add('ok'); }
  chip(nid, cid) { const c = this.node(nid).querySelector(`[data-chip="${cid}"]`); if (c) c.classList.add('hit'); }
  count(nid, val) { const b = this.node(nid).querySelector('[data-c]'); if (b) b.textContent = val; }
  hold(nid) { this.node(nid).classList.remove('on'); this.node(nid).classList.add('hold'); }
  unhold(nid) { this.node(nid).classList.remove('hold'); }
  scene(m) { this.sceneText = T(m); this.status(this._st || 'run'); }
  status(k) { this._st = k; this.v.status.textContent = (this.sceneText ? this.sceneText + '   ' : '') + T(UI.st[k]); }
  log(tag, msg, cls) {
    this.clock += 300 + Math.random() * 1500; // 假钟随手走
    const d = new Date(this.clock);
    const tt = `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}:${String(d.getUTCSeconds()).padStart(2, '0')}`;
    const line = el('div', 'tline' + (cls ? ' ' + cls : ''));
    line.innerHTML = `<span class="tt">${tt}</span><span class="tg">${tag}</span><span>${esc(T(msg))}</span>`;
    this.v.tlines.appendChild(line);
    const kids = this.v.tlines.children;
    while (kids.length > 6) kids[0].remove();
    for (let i = 0; i < kids.length - 4; i++) kids[i].classList.add('dim');
  }
  pulse(eid, opts = {}) {
    const path = this.v.paths[eid];
    const id = this.runId;
    path.classList.add('on');
    const len = path.getTotalLength();
    // 脉冲是个小方块，s=边长的一半，坐标按中心换算成左上角
    const s = opts.r || 3.5;
    const c = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    c.setAttribute('width', s * 2); c.setAttribute('height', s * 2);
    c.setAttribute('class', 'pulse' + (opts.cls ? ' ' + opts.cls : ''));
    this.v.svg.appendChild(c);
    const dur = (opts.dur || 650) / this.speed;
    return new Promise(res => {
      let elapsed = 0, last = null;
      const step = ts => {
        if (id !== this.runId) { c.remove(); path.classList.remove('on'); return res(); }
        if (this.paused) { last = null; return requestAnimationFrame(step); }
        if (last == null) last = ts;
        elapsed += ts - last; last = ts;
        const k = Math.min(elapsed / dur, 1);
        const p = path.getPointAtLength((opts.reverse ? 1 - k : k) * len);
        c.setAttribute('x', p.x - s); c.setAttribute('y', p.y - s);
        if (k < 1) requestAnimationFrame(step);
        else { c.remove(); if (!opts.hold) setTimeout(() => { if (id === this.runId) path.classList.remove('on'); }, 350); res(); }
      };
      requestAnimationFrame(step);
    });
  }
  async type(nid, idx, msg) {
    const lines = this.node(nid).querySelectorAll('.sline');
    const target = lines[idx]; if (!target) return;
    const text = T(msg);
    for (let i = 1; i <= text.length; i++) {
      target.textContent = text.slice(0, i);
      await this.sleep(26);
    }
  }
  clearStream(nid) { this.node(nid).querySelectorAll('.sline').forEach(s => s.textContent = ''); }
  hitl(showing) { this.node('hitl').classList.toggle('show', showing); }
  hitlPress() {
    const b = this.node('hitl').querySelector('.hitl-btn.ok');
    if (b) { b.classList.add('pressed'); setTimeout(() => b.classList.remove('pressed'), 900 / this.speed); }
  }
  reset() {
    const v = this.v;
    Object.values(v.nodes).forEach(n => n.classList.remove('on', 'done', 'err', 'hold', 'show'));
    v.panel.querySelectorAll('.nrow').forEach(r => r.classList.remove('hit', 'ok', 'bad'));
    v.panel.querySelectorAll('.nchip').forEach(c => c.classList.remove('hit'));
    v.panel.querySelectorAll('[data-c]').forEach(b => b.textContent = '0');
    v.panel.querySelectorAll('.sline').forEach(s => s.textContent = '');
    Object.values(v.paths).forEach(p => p.classList.remove('on'));
    v.svg.querySelectorAll('.pulse').forEach(c => c.remove());
  }
  async run() {
    const id = ++this.runId;
    this.reset();
    let cycle = 0;
    try {
      while (true) {
        this.chk(id);
        const scene = this.pinned ?? (cycle++ % this.scenes.length);
        if (this.onScene) this.onScene(scene);
        this.v.led.classList.add('run');
        this.status('run');
        await this.script(this, scene);
        this.status('sleep');
        this.v.led.classList.remove('run');
        await this.sleep(2600);
        this.reset();
      }
    } catch (e) { if (!(e instanceof Abort)) throw e; }
  }
  stop() { this.runId++; this.v.led.classList.remove('run'); }
}

/* ---------- 剧本：trader 四个场景轮播 ---------- */
const TR_SCENES = [
  { zh: 'A · 例行唤醒 → 开仓', en: 'A · routine wake → open' },
  { zh: 'B · 警报唤醒 → 失效退出', en: 'B · alert wake → invalidation exit' },
  { zh: 'C · 例行唤醒 → 观望', en: 'C · routine wake → wait' },
  { zh: 'D · 日线交接 → 复盘', en: 'D · daily handover → review' }
];

/* 共用前半段：触发 → 准入 → 组装 → 进循环 */
async function trEnter(c, alert, calendar) {
  if (alert) {
    c.on('sentinel');
    c.row('sentinel', 'amp', 'hit'); c.row('sentinel', 'hold', 'hit');
    c.log('SENTRY', { zh: '5min 振幅 2.4% 超阈值 · 持有 ETH → 警报唤醒', en: '5min range 2.4% over threshold · holds ETH → alert wakeup' }, 'warn');
    await c.sleep(700);
    await c.pulse('sn-sch');
  } else if (calendar) {
    // 边界撞上数据公布：先等实际值再发，开场白里的财经日历才带得上刚公布的数
    c.on('clock');
    c.log('CLOCK', { zh: '1h 边界 22:00 撞上 ISM 制造业 PMI 公布 · 先等实际值', en: '1h boundary at 22:00 lands on the ISM PMI release · waiting for the actual' }, 'warn');
    await c.sleep(1100);
    c.log('CLOCK', { zh: '实际值已到（等了 7s）· 例行唤醒发出', en: 'actual value in (waited 7s) · routine wakeup fired' }, 'good');
    await c.sleep(500);
    await c.pulse('ck-sch');
  } else {
    c.on('clock');
    c.log('CLOCK', { zh: '1h 边界到达 · 例行唤醒', en: '1h boundary hit · routine wakeup' });
    await c.sleep(600);
    await c.pulse('ck-sch');
  }
  c.on('sched');
  for (const r of ['mutex', 'window', 'budget', 'hand']) { c.ledOk('sched', r); await c.sleep(280); }
  c.log('SCHED', { zh: '互斥 ✓ 时段 ✓ 预算 600s ✓ 不在交接窗口 ✓', en: 'mutex ✓ window ✓ budget 600s ✓ no handover ✓' });
  await c.sleep(400);
  c.done(alert ? 'sentinel' : 'clock');
  await c.pulse('sch-asm');
  c.done('sched'); c.on('asm');
  for (const ch of ['c1', 'c2', 'c4', 'c5', 'c6', 'c7', 'c8', 'c9']) { c.chip('asm', ch); await c.sleep(120); }
  c.log('PROMPT', { zh: '现读现拼：系统提示词（规则 + 经验账本 + 交易指令）+ 开场白（事件 · 账户 · 上轮结论 · 日历）', en: 'assembled fresh: system prompt (rules + experience ledger + playbook) + opening (events · account · last conclusion · calendar)' });
  await c.sleep(500);
  await c.pulse('asm-ag');
  c.done('asm'); c.on('agent');
  c.count('agent', 1);
}

/* 数据工具一来一回 */
async function trData(c, rowId, n, msg) {
  await c.pulse('ag-data'); c.on('data');
  c.row('data', rowId); c.count('agent', n);
  c.log('TOOL', msg);
  await c.pulse('ag-data', { reverse: true, cls: 'dim' });
  await c.sleep(300);
}

async function traderScript(c, scene) {
  c.scene(TR_SCENES[scene]);

  if (scene === 3) {
    // 日线交接：交易定格 → 全体复盘 → 账本注入下一唤醒
    c.on('clock');
    c.log('CLOCK', { zh: '日线边界（UTC0 / 北京 08:00）', en: 'daily boundary (UTC0 / 08:00 Beijing)' });
    await c.sleep(600);
    await c.pulse('ck-sch');
    c.on('sched');
    c.log('SCHED', { zh: '停工窗口开始 · 四个唤醒入口全拒，K 线事件直接丢弃', en: 'blackout begins · all four wake entries rejected, candle events dropped' }, 'warn');
    await c.sleep(1000);
    c.on('dec');
    c.log('DB', { zh: '素材 = 已定格的一天：决策行 + 已归档计划', en: 'materials = the frozen day: decision rows + archived plans' });
    await c.sleep(600);
    await c.pulse('dec-rev', { dur: 900 });
    c.done('dec'); c.on('rev');
    c.row('rev', 'r1', 'hit');
    c.log('REVIEW', { zh: '案例事实表代码算好 · 战绩只许复述禁止自算', en: 'case facts precomputed · stats quoted, never self-graded' });
    await c.sleep(900);
    c.row('rev', 'r2', 'hit');
    c.log('REVIEW', { zh: '单次调用无工具 · 【本期复盘】+【经验更新】', en: 'one call, no tools · review section + ledger updates' });
    await c.sleep(900);
    c.row('rev', 'r3', 'hit', true);
    c.log('REVIEW', { zh: '经验更新逐行记账 · 渲染文本写回 memory · REVIEW 决策行公开', en: 'ledger updates booked row by row · rendered text back to memory · REVIEW row goes public' }, 'good');
    await c.sleep(700);
    c.log('SCHED', { zh: '等所有 trader 的复盘写完', en: 'wait for every trader’s review' }, 'warn');
    await c.sleep(1100);
    await c.pulse('notes-asm', { dur: 1100 });
    c.done('rev'); c.on('asm');
    c.chip('asm', 'c2');
    c.log('SCHED', { zh: '窗口结束 · 下一根 K 线带着新账本醒来', en: 'blackout over · next candle wakes with a fresh ledger' }, 'good');
    await c.sleep(1300);
    c.done('asm'); c.done('sched'); c.done('clock');
    return;
  }

  await trEnter(c, scene === 1, scene === 0);

  if (scene === 0) {
    c.log('REACT', { zh: '模型调用 1/12 · 先对照开场白里的上一轮结论', en: 'model call 1/12 · check the last conclusion from the opening first' });
    await c.sleep(900);
    await trData(c, 'klines', 2, { zh: 'klines ETHUSDT 1h ×200', en: 'klines ETHUSDT 1h ×200' });
    await trData(c, 'snap', 3, { zh: 'market_snapshot · 资金费偏离 −0.018%', en: 'market_snapshot · funding deviation −0.018%' });
    await trData(c, 'fund', 4, { zh: 'funding_history · 连续 3 期为负', en: 'funding_history · negative 3 periods straight' });
    c.done('data');
    await c.sleep(500);

    // 开仓：先被拒再修正
    c.count('agent', 5);
    await c.pulse('ag-guard'); c.on('guard');
    await c.sleep(400);
    c.err('guard'); c.row('guard', 'veto', 'bad');
    c.log('GUARD', { zh: '拒绝：杠杆 25x 超出主人区间 3–10x', en: 'rejected: 25x outside owner range 3–10x' }, 'bad');
    await c.pulse('ag-guard', { reverse: true, cls: 'bad' });
    await c.sleep(800);
    c.clearErr('guard');
    c.count('agent', 6);
    await c.pulse('ag-guard');
    c.row('guard', 'spec', 'hit');
    c.log('GUARD', { zh: '修正重试 8x · 校验通过', en: 'corrected to 8x · passed' }, 'good');
    c.done('guard');
    await c.pulse('guard-trade'); c.on('trade');
    c.row('trade', 'open');
    await c.pulse('trade-sim'); c.on('sim');
    c.log('SIM', { zh: 'open_position ETHUSDT 空 8x · 止损 3260 · 止盈 2990 · 成交', en: 'open_position ETHUSDT short 8x · SL 3260 · TP 2990 · filled' });
    await c.pulse('trade-sim', { reverse: true, cls: 'dim' });
    c.done('sim');
    await c.sleep(400);
    // 计划跟着开仓一起落库，不再单调 write_plan
    c.log('PLAN', { zh: '计划随开仓落库：PULLBACK · 数据引用 · 失效条件 → ai_trader_plan', en: 'plan stored with the open: PULLBACK · citation · invalidation → ai_trader_plan' });
    c.done('trade');
    await c.sleep(700);
    c.count('agent', 7);
    c.log('REACT', { zh: '模型调用 7/12 · 按固定格式写 [本轮结论]，每个币一段', en: 'model call 7/12 · closing [Conclusion] block, one section per symbol' });
    await c.sleep(800);
    await c.pulse('ag-dec', { dur: 800 });
    c.done('agent'); c.on('dec');
    c.row('dec', 'full', 'hit', true); c.row('dec', 'plan2', 'hit', true);
    c.log('DB', { zh: '决策全文 + 动作轨迹 + 权益 → ai_trader_decision · 公开', en: 'full reasoning + actions + equity → ai_trader_decision · public' }, 'good');
    await c.sleep(1100);
    c.done('dec');
    c.log('SCHED', { zh: '休眠 · 等下一根 K 线', en: 'sleeping until the next candle' }, 'dim');

  } else if (scene === 1) {
    c.log('REACT', { zh: '模型调用 1/12 · 先看在场计划的失效条件', en: 'call 1/12 · check the live plan’s invalidation first' });
    await c.sleep(800);
    await trData(c, 'klines', 2, { zh: 'klines ETHUSDT 15m · 已收盘 3097', en: 'klines ETHUSDT 15m · closed at 3097' });
    c.count('agent', 3);
    c.log('REACT', { zh: '失效条件触发：已收盘的 15m 跌破计划写的 3120', en: 'invalidation hit: the closed 15m broke the plan’s 3120' }, 'warn');
    await c.sleep(700);
    await c.pulse('ag-guard');   // 平仓不过 TradeGuard，它只卡开仓
    await c.pulse('guard-trade');
    c.on('trade'); c.row('trade', 'close');
    await c.pulse('trade-sim'); c.on('sim');
    c.log('SIM', { zh: 'close_position ETHUSDT · 全平 · 理由：失效条件触发 · 已实现 −0.6%', en: 'close_position ETHUSDT · flat · reason: invalidation hit · realized −0.6%' });
    await c.pulse('trade-sim', { reverse: true, cls: 'dim' });
    c.done('sim');
    c.done('trade'); c.done('data');
    await c.sleep(600);
    await c.pulse('ag-dec', { dur: 800 });
    c.done('agent'); c.on('dec');
    c.row('dec', 'full', 'hit', true); c.row('dec', 'plan2', 'hit', true);
    c.log('DB', { zh: '按主人指令里的退出条件离场 → 决策行公开 · 计划归档给 reviewer', en: 'exited on one of the owner’s exit rules → decision public · plan archived for the reviewer' }, 'good');
    await c.sleep(1100);
    c.done('dec');

  } else {
    // 观望：多数唤醒的真实形态
    c.log('REACT', { zh: '模型调用 1/12 · 按主人指令逐币过：持仓去留 · 挂单存废 · 新机会', en: 'call 1/12 · per the playbook, symbol by symbol: positions · orders · new setups' });
    await c.sleep(800);
    await trData(c, 'klines', 2, { zh: 'klines ETHUSDT 1h ×200', en: 'klines ETHUSDT 1h ×200' });
    await trData(c, 'ind', 3, { zh: 'indicators · ATR 收缩，无新信号', en: 'indicators · ATR contracting, no new signal' });
    c.done('data');
    c.log('REACT', { zh: '未触失效 · 未到止盈 · 资金费无异动 → 计划不变', en: 'no invalidation · target not hit · funding quiet → plan stands' });
    await c.sleep(900);
    await c.pulse('ag-dec', { dur: 800 });
    c.done('agent'); c.on('dec');
    c.row('dec', 'full', 'hit', true);
    c.log('DB', { zh: 'WAIT · 不动手也是决策，全文照样公开', en: 'WAIT · doing nothing is still a decision, published in full' }, 'good');
    await c.sleep(1100);
    c.done('dec');
    c.log('SCHED', { zh: '多数唤醒长这样：检验 → 观望', en: 'most wakeups look like this: verify → wait' }, 'dim');
  }
}

/* ---------- 剧本：chat 三个场景轮播 ---------- */
const CH_SCENES = [
  { zh: 'A · 完整一轮：取数 → HITL → 深研判转后台 → 唤醒补答', en: 'A · full turn: tools → HITL → deep analysis in background → follow-up' },
  { zh: 'B · 派子 agent：researcher ∥ analyst → 报告回灌 → 补充回答', en: 'B · delegate: researcher ∥ analyst → reports back → follow-up' },
  { zh: 'C · 新闻双源：news_search + 联网搜索', en: 'C · dual-source news: news_search + web search' }
];

/* 共用开场：提问 → 四道准入 → SSE → 主 agent 亮起 */
async function chGates(c, qMsg, fast) {
  c.on('user');
  c.log('USER', qMsg);
  await c.sleep(fast ? 400 : 600);
  await c.pulse('u-g');
  c.on('gates');
  for (const g of ['g1', 'g2', 'g3', 'g4']) { c.ledOk('gates', g); await c.sleep(fast ? 180 : 320); }
  c.log('GATE', { zh: '端点 ✓ 叶子缓存命中 ✓ 用户闸 ✓ 全局 3/10 ✓ → SSE 建流', en: 'endpoint ✓ leaf cache hit ✓ user gate ✓ global 3/10 ✓ → SSE opened' });
  await c.sleep(400);
  c.done('gates'); c.done('user');
  await c.pulse('g-m');
  c.on('main');
}

/* 共用收尾：终态覆盖写 */
async function chStore(c, msg) {
  await c.pulse('m-h');
  c.done('main'); c.on('hist');
  c.row('hist', 'ow', 'hit', true);
  c.log('DB', msg, 'good');
  await c.sleep(1000);
  c.done('hist');
}

/* 共用：报告到了没轮在跑 → 落库标 pending → 前端空闲发起唤醒轮，主 agent 重新亮起 */
async function chWake(c, storeMsg) {
  c.row('cr', 'c3', 'hit', true);
  await c.pulse('cr-h');
  c.on('hist'); c.row('hist', 'ow', 'hit', true);
  c.log('DB', storeMsg, 'good');
  await c.sleep(700);
  c.done('hist');
  await c.pulse('h-w');
  c.on('wake'); c.row('wake', 'w1', 'hit', true); c.row('wake', 'w2', 'hit', true);
  c.status('wake');
  c.log('WAKE', { zh: 'done / status 带 pending · 本地没有轮在跑 → POST /wake', en: 'done / status carry pending · nothing running locally → POST /wake' }, 'good');
  await c.sleep(700);
  c.row('wake', 'w3', 'hit', true);
  await c.pulse('w-m');
  c.done('wake'); c.on('main'); c.clearStream('main'); c.count('main', 1);
  c.log('MAIN', { zh: '唤醒轮：不落 user 行 · 用户侧消息是唤醒指令 · 据报告补充回答', en: 'follow-up turn: no user row · the user-side message is a wake instruction · it answers from the reports' });
}

async function chatScript(c, scene) {
  c.scene(CH_SCENES[scene]);

  if (scene === 0) {
    await chGates(c, { zh: '「ETH 永续现在适合做空吗？给我来个深度研判」', en: '“Is ETH perp a short right now? Run a deep analysis.”' });

    // 第 1 次调用：一批 tool_call 并发取数
    c.count('main', 1);
    c.chip('main', 'cmk'); c.chip('main', 'ctr'); c.row('main', 'par', 'hit', true);
    c.log('MAIN', { zh: 'tool_call ×4：market_snapshot + funding_history + orderbook_depth + trader_positions · 同批并发（虚拟线程）', en: 'tool_call ×4: market_snapshot + funding_history + orderbook_depth + trader_positions · one batch, in parallel (virtual threads)' });
    await c.sleep(1100);
    c.log('TOOL', { zh: '回执按 tool_call 顺序配对成一条接回历史', en: 'results paired back in call order as one message' });
    c.count('main', 2);
    await c.type('main', 0, { zh: '资金费连续 3 期为负，空头已拥挤；', en: 'Funding negative 3 straight periods, shorts crowded;' });

    // HITL：深研判要用户点头，本轮先收尾弹卡
    c.chip('main', 'cda');
    c.status('hitl');
    c.hitl(true);
    c.log('HITL', { zh: 'run_deep_analysis 需确认 · 键 = 会话+工具+标的 · 本轮先收尾、弹卡', en: 'run_deep_analysis needs approval · key = session+tool+symbol · the turn ends and the card pops' }, 'warn');
    await c.sleep(1800);
    c.hitlPress();
    await c.sleep(600);
    c.hitl(false);
    c.status('run');
    c.log('HITL', { zh: '用户已确认 · 前端补发「请继续」· 新一轮里闸门放行', en: 'approved · the client sends “continue” · the gate lets it through in the next turn' }, 'good');
    c.count('main', 1);

    // 批准后不同步跑：转后台子运行，主 agent 先答能答的
    await c.pulse('m-cr');
    c.on('cr'); c.row('cr', 'c1', 'hit', true);
    c.log('CHILD', { zh: 'spawn #1 深研判 ETHUSDT · 开关 ✓ 在跑 0/3 ✓ 全局 4/20 ✓ → 工具立即返回 RUNNING', en: 'spawn #1 deep analysis ETHUSDT · switch ✓ running 0/3 ✓ global 4/20 ✓ → the tool returns RUNNING at once' });
    await c.pulse('cr-da');
    c.on('da'); c.row('da', 'd1', 'hit', true);
    c.count('main', 2);
    await c.type('main', 1, { zh: '盘口 25bp 内买盘压卖盘 3.2×，急跌有接；', en: 'Bids 3.2× asks within 25bp, dips get bought;' });
    c.pulse('s-u', { dur: 1400, cls: 'dim', r: 2.5 });
    await c.type('main', 2, { zh: '深度研判已在后台跑，裁决稍后补上。', en: 'Deep analysis is running in the background; verdict to follow.' });
    c.pulse('s-u', { dur: 1400, cls: 'dim', r: 2.5 });
    c.log('MAIN', { zh: '主 agent 不等、不轮询：先答能答的，本轮结束', en: 'the main agent neither waits nor polls: it answers what it can and the turn ends' });
    await c.sleep(700);
    await chStore(c, { zh: '终态覆盖写 · done.pending=false · 名额释放，期间用户可以继续问', en: 'final state overwritten · done.pending=false · slot released, the user can keep asking meanwhile' });

    // 子运行在跑：Bull ∥ Bear → Judge
    c.status('bg');
    c.log('CHILD', { zh: '#1 Bull ∥ Bear 并行辩论 → Judge 裁决 · 3 次深模型调用 · 进度推到过程轨', en: '#1 Bull ∥ Bear argue in parallel → Judge rules · 3 deep-model calls · progress on the process rail' });
    c.row('da', 'd2', 'hit', true);
    await c.sleep(1800);
    c.done('da');
    c.log('CHILD', { zh: '#1 完成 · 没轮在跑 → 报告以用户侧消息追加进上下文、标 pending', en: '#1 done · no turn running → the report is appended to the context as a user-side message, pending set' }, 'good');
    await chWake(c, { zh: '【后台任务#1 深研判 ETHUSDT 已完成】+ 裁决 JSON 落进 workbench_chat_context', en: '[Background task #1 deep analysis ETHUSDT done] + verdict JSON stored in workbench_chat_context' });
    c.done('cr');
    await c.type('main', 0, { zh: '【补充回答】裁决：bull 30 / range 50 / bear 20，倾向不追空；', en: '[Follow-up] Verdict: bull 30 / range 50 / bear 20 — don’t chase the short;' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    await c.type('main', 1, { zh: '失效条件：4H 收在 3,180 供给区上方。', en: 'Invalidation: a 4H close above the 3,180 supply zone.' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    await c.sleep(700);
    c.status('run');
    await chStore(c, { zh: '唤醒轮与普通轮同款收尾：终态覆盖写 · pending 清零', en: 'a follow-up turn ends like any other: final state overwritten · pending cleared' });
    c.log('GATE', { zh: '闸门释放 · 等下一条消息', en: 'gates released · waiting for the next message' }, 'dim');

  } else if (scene === 1) {
    // 三幕：派两个 worker 并行 → 本轮先答能答的 → 报告分两路回来（落库唤醒 / 轮内收件箱）
    await chGates(c, { zh: '「开两个子 agent：一个查本周 ETF 资金流和监管动态，一个看 BTC / ETH 的四小时结构」', en: '“Spin up two sub-agents: one on this week’s ETF flows and regulation, one on the BTC / ETH 4H structure.”' }, true);
    c.count('main', 1);
    c.chip('main', 'cdg'); c.row('main', 'par', 'hit', true);
    c.log('MAIN', { zh: 'tool_call ×2：delegate(researcher, 任务书 A) ∥ delegate(analyst, 任务书 B) · 任务书自足：目标 / 范围 / 报告要什么', en: 'tool_call ×2: delegate(researcher, brief A) ∥ delegate(analyst, brief B) · each brief stands alone: objective / scope / what the report must contain' });
    await c.pulse('m-cr');
    c.on('cr'); c.row('cr', 'c1', 'hit', true);
    c.log('CHILD', { zh: '三道检查：开关开 ✓ 在跑 0/3 ✓ 全局 2/20 ✓ → spawn #2 #3，各建一个 worker（浅模型 · 独享账本）', en: 'three checks: switch on ✓ running 0/3 ✓ global 2/20 ✓ → spawn #2 #3, one fresh worker each (light model · own ledger)' });
    await Promise.all([
      (async () => {
        await c.pulse('cr-wr'); c.on('wr');
        c.row('wr', 'rn', 'hit', true); await c.sleep(700);
        c.row('wr', 'rw', 'hit', true);
        c.log('WORKER', { zh: '#2 researcher：news_search → 联网补充 · 看不到主对话 · ≤8 次调用', en: '#2 researcher: news_search → web supplement · blind to the main chat · ≤8 calls' });
      })(),
      (async () => {
        await c.pulse('cr-wa'); c.on('wa');
        c.row('wa', 'am', 'hit', true);
        c.log('WORKER', { zh: '#3 analyst：klines ×2 + indicators + kline_structure · 不凭记忆给数字', en: '#3 analyst: klines ×2 + indicators + kline_structure · never quotes a number from memory' });
      })()
    ]);
    c.count('main', 2);
    c.log('MAIN', { zh: '两个 delegate 都回 {runId, status: running} · 主 agent 不等、不轮询、不重复派', en: 'both delegates return {runId, status: running} · the main agent neither waits, polls nor re-dispatches' });
    await c.type('main', 0, { zh: '两项已派后台子 agent 并行去查，先说现在能答的：', en: 'Both parts are with background sub-agents; what I can say now:' });
    c.pulse('s-u', { dur: 1100, cls: 'dim', r: 2.5 });
    await c.type('main', 1, { zh: 'BTC 现价 97,420，4H 仍在区间内。', en: 'BTC at 97,420, still inside the 4H range.' });
    c.pulse('s-u', { dur: 1100, cls: 'dim', r: 2.5 });
    await c.sleep(500);
    await chStore(c, { zh: '本轮结束 · done.pending=false（报告还没到）', en: 'turn ends · done.pending=false (no report yet)' });

    // 幕 2：analyst 先回，没轮在跑 → 落库标 pending → 唤醒轮
    c.status('bg');
    await c.sleep(900);
    c.done('wa');
    c.log('CHILD', { zh: '#3 analyst 完成 · 没轮在跑 → 落库标 pending', en: '#3 analyst done · no turn running → stored, pending set' }, 'good');
    await chWake(c, { zh: '【后台任务#3 行情分析 已完成】追加进上下文', en: '[Background task #3 market analysis done] appended to the context' });
    c.row('main', 'inbox', 'hit', true);

    // 幕 3：researcher 在唤醒轮进行中回来 → 进收件箱，下一次调模型前排进上下文
    await c.sleep(600);
    c.done('wr'); c.row('cr', 'c2', 'hit', true);
    c.log('CHILD', { zh: '#2 researcher 完成 · 轮正在跑 → 进收件箱 · 下一次调模型前排进上下文（不直接落库，由轮自己落）', en: '#2 researcher done · a turn is running → into its inbox · queued before the next model call (the turn stores it, not the registry)' }, 'good');
    await c.pulse('cr-m');
    c.count('main', 2);
    c.done('cr');
    await c.type('main', 0, { zh: '【补充回答】结构：BTC 4H 高点压低，ETH 守住 3,050 需求区；', en: '[Follow-up] Structure: BTC 4H highs stepping down, ETH holding the 3,050 demand zone;' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    await c.type('main', 1, { zh: '资金流：ETF 本周净流出 4.1 亿 [BlockBeats]，SEC 听证推迟 [Web]。', en: 'Flows: ETF net outflow $410M this week [BlockBeats]; SEC hearing postponed [Web].' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    await c.sleep(600);
    c.status('run');
    await chStore(c, { zh: '两份报告一轮答完 · pending 清零', en: 'both reports answered in one turn · pending cleared' });

  } else {
    await chGates(c, { zh: '「今晚有什么可能砸盘的消息？」', en: '“Any news tonight that could dump the market?”' });
    c.count('main', 1); c.chip('main', 'cnw');
    c.log('MAIN', { zh: 'tool_call → news_search · BlockBeats 近 24h 快讯 12 条（按叶子语言给译文）', en: 'tool_call → news_search · 12 BlockBeats flashes from the last 24h (in the leaf’s language)' });
    await c.sleep(900);
    c.count('main', 2);
    c.row('main', 'ws', 'hit', true);
    c.log('MAIN', { zh: '端点支持服务端搜索且勾了开关 → 这次调用捎上搜索许可', en: 'endpoint supports server search and has it on → this call carries the search grant' });
    await c.sleep(700);
    c.log('MAIN', { zh: '服务端搜索关不掉 · 与其硬压不如分工：快讯清单 + 联网补充', en: 'server-side search can’t be switched off — so split the work: flash list + web supplement' });
    await c.type('main', 0, { zh: '[BlockBeats] 美 CPI 今晚 21:30 公布；', en: '[BlockBeats] US CPI 21:30 tonight;' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    await c.type('main', 1, { zh: '[Web] 某 L1 主网升级窗口撞同一时段；', en: '[Web] L1 mainnet upgrade, same window;' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    await c.type('main', 2, { zh: '[BlockBeats+Web] ETF 连续两日净流出。', en: '[BlockBeats+Web] ETF outflows continue.' });
    c.pulse('s-u', { dur: 1200, cls: 'dim', r: 2.5 });
    c.log('MAIN', { zh: '两边都有的合并 · 独有条目带源标签', en: 'overlaps merged · unique items keep their source tag' });
    await c.sleep(700);
    await chStore(c, { zh: '终态覆盖写 workbench_chat_context', en: 'final state overwrites workbench_chat_context' });
  }
}

/* ---------- 装配 ---------- */
const SPEEDS = [1, 1.5, 2];
let runners = [], views = [];

function mountPanel(cfg) {
  const mount = document.getElementById('mount-' + cfg.id);
  if (!mount) return null;
  mount.innerHTML = '';
  const view = renderPanel(cfg, mount);
  const isTrader = cfg.id === 'trader';
  const runner = new Runner(view, isTrader ? traderScript : chatScript, isTrader ? TR_SCENES : CH_SCENES);
  view.runner = runner;
  runners.push(runner);
  views.push(view);

  // 场景选择条：点谁跑谁，AUTO 恢复轮播
  const resume = () => {
    runner.paused = false; runner.userPaused = false;
    view.bPlay.innerHTML = '<svg data-icon="pause"></svg>'; icons(view.bPlay);
  };
  const bar = el('div', 'scene-bar');
  const autoBtn = el('button', 'scene-btn act');
  autoBtn.textContent = T(UI.auto);
  bar.appendChild(autoBtn);
  const sceneBtns = runner.scenes.map(s => {
    const b = el('button', 'scene-btn');
    b.textContent = T(s);
    bar.appendChild(b);
    return b;
  });
  view.panel.insertBefore(bar, view.panel.children[1]);
  runner.onScene = live => {
    autoBtn.classList.toggle('act', runner.pinned == null);
    sceneBtns.forEach((b, i) => {
      b.classList.toggle('act', runner.pinned === i);
      b.classList.toggle('live', live === i);
    });
  };
  autoBtn.addEventListener('click', () => { runner.pinned = null; resume(); runner.run(); });
  sceneBtns.forEach((b, i) => b.addEventListener('click', () => { runner.pinned = i; resume(); runner.run(); }));

  if (REDUCED) {
    // 降级成静态全图：节点、导线、LED 全部点亮，控件停用
    Object.values(view.nodes).forEach(n => n.classList.add('on'));
    Object.values(view.paths).forEach(p => p.classList.add('on'));
    view.panel.querySelectorAll('.nrow').forEach(r => r.classList.add('ok'));
    runner.log('NOTE', UI.reduced);
    view.bPlay.disabled = view.bRe.disabled = view.bSpd.disabled = true;
    bar.querySelectorAll('button').forEach(b => b.disabled = true);
    return view;
  }
  view.bPlay.addEventListener('click', () => {
    runner.paused = !runner.paused;
    runner.userPaused = runner.paused;
    view.bPlay.innerHTML = runner.paused ? '<svg data-icon="play"></svg>' : '<svg data-icon="pause"></svg>';
    view.bPlay.title = runner.paused ? T(UI.play) : T(UI.pause);
    if (runner.paused) runner.status('paused');
    icons(view.bPlay);
  });
  view.bRe.addEventListener('click', () => { runner.paused = false; runner.userPaused = false; view.bPlay.innerHTML = '<svg data-icon="pause"></svg>'; icons(view.bPlay); runner.run(); });
  view.bSpd.addEventListener('click', () => {
    const i = (SPEEDS.indexOf(runner.speed) + 1) % SPEEDS.length;
    runner.speed = SPEEDS[i];
    view.bSpd.textContent = runner.speed.toFixed(1) + '×';
  });
  // 滚出视口自动挂起，省 CPU；用户手动暂停的不抢救
  new IntersectionObserver(es => {
    for (const e of es) {
      if (runner.userPaused) continue;
      runner.paused = !e.isIntersecting;
    }
  }, { threshold: .12 }).observe(view.panel);
  runner.run();
  return view;
}

/* 重排：宽窄切换时把动画从头放一遍，同宽度内的高度变化就地重算不打断 */
function relayoutAll() {
  closePop();
  for (const v of views) {
    const switched = applyLayout(v);
    if (switched && !REDUCED && v.runner) v.runner.run();
  }
}
let rzTimer = 0;
addEventListener('resize', () => { clearTimeout(rzTimer); rzTimer = setTimeout(relayoutAll, 130); });
addEventListener('orientationchange', () => { clearTimeout(rzTimer); rzTimer = setTimeout(relayoutAll, 260); });
if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => relayoutAll()).catch(() => { });

function stopFlowPanels() {
  closePop();
  runners.forEach(r => r.stop());
  runners = []; views = [];
  for (const cfg of PANELS) { const m = document.getElementById('mount-' + cfg.id); if (m) m.innerHTML = ''; }
}
function mountFlowPanels() {
  stopFlowPanels();
  for (const cfg of PANELS) mountPanel(cfg);
  icons();
  relayoutAll();   // 图标换进来之后再量一次高，行高会变
}
