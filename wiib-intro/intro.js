'use strict';
/* ============================================================
   intro.wtfibought.com · 站点脚本
   文案词表 / 首屏（示例卡、事实行、圆点网）/ 一次唤醒六步 / 主题 / 顶栏 / 章节动效 / 03 复盘时间线 / 装配
   依赖 flow.js（全站共用段 · icons · mountFlowPanels）、mock-data.js（MOCK · MOCK_NOW · HOUR）、board.js（renderMarket · renderDash）
   ============================================================ */

/* ---------- 文案：短句、直白，事实以 README / docs 为准 ---------- */
const COPY = {
  docTitle: { zh: 'WhatIfIBought · 让你的模型替你试一遍', en: 'WhatIfIBought · Let your model try it for you' },
  navDash: { zh: '看板', en: 'Board' }, navWake: { zh: '流程', en: 'Flow' }, navReview: { zh: '复盘', en: 'Review' },
  navChat: { zh: '对话', en: 'Chat' }, navAlpha: { zh: '挖因子', en: 'Alpha' }, navBase: { zh: '底层', en: 'Engine' },
  navCta: { zh: '前往 WIIB', en: 'Go to WIIB' },

  heroKicker: { zh: 'AGENTIC TRADING', en: 'AGENTIC TRADING' },
  heroSub: { zh: '让<b>你的模型</b>替你试一遍，<b>每一步决策</b>都可见', en: 'Let <b>your model</b> try it for you, and see <b>every decision</b> it makes' },
  ctaMain: { zh: '前往 WIIB', en: 'Go to WIIB' },
  ctaGh: { zh: '源码', en: 'Source' },

  s1h: { zh: '每个 trader 一页，谁都能看', en: 'One page per trader, open to anyone' },
  s1lead: { zh: '心跳、净值曲线、实时持仓盈亏、它的盯盘、每一次思考，还有单笔复盘、统一复盘和经验账本。只有逐字吐字的唤醒现场和工具回执留给主人自己看。', en: 'Heartbeat, equity curve, live position PnL, its watches and every wakeup, plus debriefs, unified reviews and the experience ledger. Only the live token stream and the tool receipts stay with the owner.' },

  s2h: { zh: '一次唤醒是怎么跑的', en: 'What one wakeup looks like' },
  s2lead: { zh: '例行心跳、盯盘命中、持仓变化、剧烈波动、权益回落，加上你手动叫醒，六路都能叫醒它。一个 trader 可以几轮同时跑：一轮看整盘，每个币各一轮只管它。', en: 'A heartbeat, a watch hit, a position change, a sharp move, an equity drawdown, or you by hand: six ways to wake it. One trader can run several rounds at once, one over the whole book and one per coin that minds only that coin.' },
  s2hint: { zh: '点节点看说明，顶上可以切场景。', en: 'Click a node to read about it, and switch scenes on top.' },

  s3h: { zh: '复盘分两层，经验由代码记账', en: 'Two layers of review, and code keeps the ledger' },
  s3lead: { zh: '一笔单子平仓满 24 小时，先单独复盘这一笔；每天新加坡时间 08:00 再统一复盘一次。复盘有自己的位子，不挡交易。', en: 'Once a trade has been closed for 24 hours it gets a debrief of its own; every day at 08:00 Singapore time a unified review looks across trades. Reviews have their own slot and never hold up trading.' },
  tk1t: { zh: '平仓', en: 'Closed' }, tk1s: { zh: '止损 · 止盈 · 主动平', en: 'stop · target · by hand' },
  tk2t: { zh: '单笔复盘', en: 'Debrief' }, tk2s: { zh: '满 24 小时，或主人点立即复盘', en: 'after 24 h, or the owner taps Review now' },
  tk3t: { zh: '统一复盘', en: 'Unified review' }, tk3s: { zh: '每天 08:00 · 跨单子', en: 'daily at 08:00 · across trades' },
  tk4t: { zh: '经验账本', en: 'Experience ledger' }, tk4s: { zh: '代码记账 · 转正 / 降级 / 退役', en: 'booked in code · promote / demote / retire' },
  tk5t: { zh: '下一轮唤醒', en: 'Next wakeup' }, tk5s: { zh: '注入规律和执行缺口', en: 'rules and execution gaps injected' },
  r1t: { zh: '单笔复盘：回到当时去查', en: 'Debrief: back to the moment' },
  r1p: { zh: '带只读工具的 agent，查得到当时的 K 线、指标、结构和快讯。只判这一笔：怎么走的、问题出在哪，再归到账本里的哪几条；账本它自己不碰。', en: 'An agent with read-only tools that can pull the candles, indicators, structure and news as they stood back then. It judges this one trade only, how it played out and where it went wrong, then files it under ledger entries without touching the ledger itself.' },
  r1k: { zh: '<div><span>触发</span><b>平仓满 24 小时 · 立即复盘</b></div><div><span>工具</span><b>只读 ×6 · 能看历史时刻</b></div><div><span>上限</span><b>16 次调用 · 1800 秒</b></div>', en: '<div><span>Trigger</span><b>24 h after close · Review now</b></div><div><span>Tools</span><b>6 read-only · any past moment</b></div><div><span>Limits</span><b>16 calls · 1800 s</b></div>' },
  r2t: { zh: '统一复盘：跨单子找分界线', en: 'Unified review: find the dividing line' },
  r2p: { zh: '一次调用，没有工具，素材由代码算齐。按单子类型把赢的和亏的摆在一起，看差在哪；再审一遍机会和布防。', en: 'One call and no tools; code lays out the material. Winners and losers of each setup type sit side by side to show what set them apart, then it goes over missed chances and standing watches.' },
  r2k: { zh: '<div><span>时间</span><b>每天新加坡 08:00 · 也能点播</b></div><div><span>收单</span><b>做完单笔复盘的，一篇最多 20 笔</b></div><div><span>没单子</span><b>静默跳过，不烧钱</b></div>', en: '<div><span>When</span><b>daily 08:00 SGT · or on demand</b></div><div><span>Takes</span><b>debriefed trades, up to 20</b></div><div><span>Nothing new</span><b>skipped quietly, no tokens</b></div>' },
  r3t: { zh: '经验账本：钱由代码记', en: 'The ledger: code keeps the money' },
  r3p: { zh: '模型只负责归类，钱、笔数、天数由代码按名下单子记。名下满 5 笔、来自至少 3 个平仓日、合计站在它这边才转成规律；转正后又满 5 笔合计反过来就降回假设。', en: 'The model only files trades; code books the money, count and days of the trades under each entry. A hypothesis becomes a rule with 5 trades from at least 3 closing days and the total on its side, and 5 more adding up the other way demote it.' },
  r3k: { zh: '<div><span>注入</span><b>规律 · 破满 2 笔的执行缺口</b></div><div><span>退役</span><b>连续 10 篇没有新单子</b></div><div><span>主人</span><b>可钉住、退役、恢复、删除</b></div>', en: '<div><span>Injected</span><b>rules · gaps broken twice</b></div><div><span>Retired</span><b>10 reviews with no new trade</b></div><div><span>Owner</span><b>pin, retire, restore, delete</b></div>' },

  s4h: { zh: '有问题，问 chat agent', en: 'Ask the chat agent' },
  s4lead: { zh: '问行情、问新闻、问你自己的 trader。它自己取数作答，读得多的活派后台子 agent 并行去查，报告回来再补答。花钱的操作先问你；对 trader 的操作只弹表单，按钮在你手里。', en: 'Ask about prices, news or your own trader. It fetches the data itself, hands heavy reading to background sub-agents and follows up when their reports land. Costly actions ask first; trader actions only show a form, and you press the button.' },

  sAh: { zh: '让模型挖因子，代码判真假', en: 'The model mines factors, and code judges them' },
  sAlead: { zh: '生成角色提假设、写因子表达式。硬门只看搜索段，没过带着数字重写，过了再交评审角色；评审打回带着理由和改法，holdout 只给留下的算一次。入不入库由人点，入库的因子交给因子交易员。', en: 'The generator proposes hypotheses and writes factor expressions. Hard gates read only the search span: a failure comes back with the numbers, a pass goes to the reviewer. A send-back carries the reason and a fix, and the holdout is scored once, for survivors only. A human decides what enters the library, and the library feeds the factor trader.' },

  s5h: { zh: '交易流是 tick 级别', en: 'Tick-level trade flow' },
  s5lead: { zh: '行情按 tick 接入 Binance 和 Polymarket，规则按真实交易所来。真人、AI、策略走同一本账。', en: 'Prices stream tick by tick from Binance and Polymarket, and the rules match the real venues. Humans, AI and strategies share one ledger.' },
  b1t: { zh: '真实行情', en: 'Live prices' },
  b1p: { zh: '美股、加密现货与永续、大宗商品、BTC 5 分钟预测，全部按 tick 级实时价成交。', en: 'US equities, crypto spot and perps, commodities and BTC 5-minute prediction, all filled at tick-level live prices.' },
  b1k: { zh: '<div>bStock ×10 · BTC ETH DOGE SOL XRP BNB ZEC HYPE</div><div>黄金 · 原油 · 美股永续 · Polymarket 盘口</div>', en: '<div>bStock ×10 · BTC ETH DOGE SOL XRP BNB ZEC HYPE</div><div>Gold · crude · stock perps · Polymarket books</div>' },
  b2t: { zh: '真实规则', en: 'Real rules' },
  b2p: { zh: '1–150x 真实档位，资金费按各合约的结算时刻真收真付，保证金不够自动强平。', en: 'Real 1–150x tiers, funding charged at each contract’s own settlement times, and automatic liquidation when margin runs out.' },
  b2k: { zh: '<div>maker 0.02% · taker 0.04%</div><div>全仓 / 逐仓 · 多空双向</div>', en: '<div>0.02% maker · 0.04% taker</div><div>Cross / isolated · long and short</div>' },
  b3t: { zh: '同一本账', en: 'One ledger' },
  b3p: { zh: '每一笔资金变动都记流水。真人、AI、策略一套规则，成绩能对账。', en: 'Every balance change is recorded. Humans, AI and strategies follow one set of rules, so results reconcile.' },
  b3k: { zh: '<div>44 种业务类型，按类型筛选</div><div>仓位历史一行一笔，可展开</div>', en: '<div>44 business types, filterable</div><div>One row per position, expandable</div>' },
  b4t: { zh: '三个进程', en: 'Three processes' },
  b4p: { zh: 'feed 接行情，sim 记账交易，agent 跑 AI 和策略。一个挂了不影响另外两个。', en: 'feed ingests prices, sim keeps the ledger, agent runs the AI and strategies. One crash does not take the others down.' },
  b4k: { zh: '<div>:8081 · :8080 · :8082</div><div>开源 AGPL-3.0，自己能跑一套</div>', en: '<div>:8081 · :8080 · :8082</div><div>AGPL-3.0, self-hostable</div>' },
  pullQ: { zh: '重点不在预测准不准，而在于<mark>能看到模型怎么想</mark>。', en: 'The point is not whether it predicts well, but that <mark>you can see how it thinks</mark>.' },

  s6h: { zh: '除了 AI 交易，还能做什么', en: 'Beyond the AI trader' },
  m1t: { zh: '自己模拟交易', en: 'Trade it yourself' }, m1p: { zh: '美股、加密、合约、大宗、BTC 预测，一笔虚拟资金随便练', en: 'Equities, crypto, perps, commodities and BTC prediction on a virtual balance' },
  m2t: { zh: '回测练习', en: 'Replay practice' }, m2p: { zh: '随机截一段历史行情手动复盘，AI 局中提示、局后点评', en: 'Replay a random slice of history by hand, with AI hints and a review after' },
  m3t: { zh: '量化策略', en: 'Quant strategies' }, m3p: { zh: 'FIBO / SQZMOM / TURTLE 三个策略在跑，账户看板可见', en: 'FIBO / SQZMOM / TURTLE run live, each with an account board' },
  m4t: { zh: 'chat agent', en: 'Chat agent' }, m4p: { zh: '研判工作台：问行情、问新闻、问自己的 trader', en: 'The research workbench for prices, news and your own trader' },
  m5t: { zh: '排行榜与社区', en: 'Leaderboard & community' }, m5p: { zh: '总资产 / 盈利双榜、用户主页、留言板', en: 'Asset and profit boards, profiles and comments' },
  m6t: { zh: '素白界面', en: 'Plain white UI' }, m6p: { zh: '亮暗主题、中英双语、能装成 PWA', en: 'Light and dark, bilingual, installable as a PWA' },

  closeH: { zh: '拿一笔模拟资金，让你的模型上场', en: 'Take a simulated balance and put your model in' },
  closeLead: { zh: '登录就有虚拟资金。接上模型和 key，拨到运行，下一次心跳它就醒。', en: 'Sign in and the balance is there. Plug in a model and key, switch it on, and it wakes on the next heartbeat.' },
  disclaimer: { zh: '所有数据均为模拟资金，不构成投资建议', en: 'All funds are simulated · not investment advice' },
  footTag: { zh: '让你的模型替你试一遍<br>每一步决策都可见', en: 'Let your model try it for you<br>Every decision visible' },
  footProduct: { zh: '产品', en: 'Product' }, footArena: { zh: '竞技场', en: 'Arena' }, footChat: { zh: '研判工作台', en: 'Workbench' },
  footCode: { zh: '源码', en: 'Code' }, footDocs: { zh: '架构文档', en: 'Architecture' },
  footContact: { zh: '联系', en: 'Contact' }
};

/* 首屏左下一行事实：说法 + 出处 */
const FACTS = [
  { k: { zh: '真实行情', en: 'Live prices' }, v: 'Binance · Polymarket' },
  { k: { zh: '真实资金费率', en: 'Funding' }, v: { zh: '按结算时刻', en: 'as settled' } },
  { k: { zh: '杠杆档位', en: 'Leverage' }, v: '1–150x' },
  { k: { zh: 'BYOK · 开源', en: 'BYOK · open source' }, v: 'AGPL-3.0' }
];

/* 首屏卡的词（几只示例 trader 的数据在 mock-data.js 的 HERO_TRADERS） */
const HC = {
  sample: { zh: '示例 trader · 非实时', en: 'Sample trader · not live' },
  pick: { zh: '看 {name}', en: 'Show {name}' },
  day: { zh: '第 {n} 天', en: 'Day {n}' },
  running: { zh: '运行中', en: 'Running' },
  meta: { zh: '{model} · 心跳 {iv} · {win} · 第 {round} 局', en: '{model} · heartbeat {iv} · {win} · round {round}' },
  win: { zh: '{win} 醒', en: 'awake {win}' }, allDay: { zh: '全天唤醒', en: 'wakes all day' },
  equity: { zh: '权益', en: 'Equity' }, initial: { zh: '初始', en: 'starts at' },
  replay: { zh: '回放这一局', en: 'Replaying this round' },
  next: { zh: '下次心跳', en: 'Next heartbeat' }, left: { zh: '还有', en: 'Time left' },
  more: { zh: '看它的完整看板', en: 'See its full board' },
  // 看板只挂了 kestrel、手机不挂看板：其余按钮去竞技场
  arena: { zh: '去竞技场看真的', en: 'See real ones in the arena' },
  // 是什么叫醒它的（叫法同主站时间线）
  kind: {
    TRADE: { zh: '例行 · {iv}', en: 'Routine · {iv}' }, WATCH: { zh: '盯盘触发', en: 'Watch hit' }, EVENT: { zh: '持仓有变化', en: 'Position changed' },
    ALERT: { zh: '剧烈波动', en: 'Sharp move' }, DRAWDOWN: { zh: '权益回落', en: 'Equity drawdown' }, MANUAL: { zh: '主人叫醒的', en: 'Woken by owner' },
    DEBRIEF: { zh: '单笔复盘', en: 'Debrief' }, REVIEW: { zh: '统一复盘', en: 'Unified review' }
  },
  only: { zh: '只处理 {s}', en: '{s} only' },
  long: { zh: '多', en: 'long' }, short: { zh: '空', en: 'short' }
};
/* 卡上时间线的圆点：例行灰、盯盘橙、持仓有变化 / 剧烈波动 / 权益回落黄、主人叫醒蓝、两种复盘空心圈（同主站 KIND_TONE） */
const HC_TONE = { WATCH: 'o', EVENT: 'w', ALERT: 'w', DRAWDOWN: 'w', MANUAL: 'b', DEBRIEF: 'rv', REVIEW: 'rv' };

/* 首屏下面的六步：一次唤醒的闭环（示例故事里 09/01 21:00 那轮开 ETH 空）；cap 进格子小字，at + trace 进底下一行日志 */
const LOOP_STEPS = [
  { tag: 'CLOCK', zh: 'K 线收盘', en: 'Candle close', cap: { zh: '例行 · 对齐 1h / 4h', en: 'routine · on 1h / 4h' }, at: '21:00:00',
    trace: { zh: '1h 边界到达 · 例行唤醒', en: '1h boundary reached · routine wakeup' } },
  { tag: 'SCHED', zh: '准入分发', en: 'Admit and route', cap: { zh: '时段 · 分轮 · 预算', en: 'hours · rounds · budget' }, at: '21:00:00',
    trace: { zh: '在唤醒时段内 · 没有整盘轮在跑，起一轮整盘轮 · 单轮 900 秒', en: 'inside the waking hours · no book round running, start one · 900 s cap' } },
  { tag: 'PROMPT', zh: '拼开场白', en: 'Build the opener', cap: { zh: '整盘 / 单币 · 分段', en: 'whole book / one coin · parts' }, at: '21:00:01',
    trace: { zh: '系统提示词（规则 + 经验账本 + 主人指令）+ 开场白：先处置承诺，再看整盘', en: 'system prompt (rules + ledger + owner brief) + opener: settle commitments, then the whole book' } },
  { tag: 'REACT', zh: '模型决策', en: 'Model decides', cap: { zh: 'ReAct · 最多 12 次', en: 'ReAct · 12 calls max' }, at: '21:00:09',
    trace: { zh: '模型调用 3/12 · klines market_snapshot funding_history 同批并发', en: 'model call 3/12 · klines market_snapshot funding_history in one parallel batch' } },
  { tag: 'GUARD', zh: '校验下单', en: 'Check and order', cap: { zh: '越界 · 直接拒', en: 'out of range · rejected' }, at: '21:00:38',
    trace: { zh: '25x 被拒（主人区间 3–10x）· 改 8x 通过 · open_position ETHUSDT', en: '25x rejected (owner range 3–10x) · 8x passes · open_position ETHUSDT' } },
  { tag: 'SIM', zh: '落账公开', en: 'Book and publish', cap: { zh: '记账 · 公开', en: 'ledger · public' }, at: '21:00:39',
    trace: { zh: '成交落账 · 决策全文进 ai_trader_decision · 公开', en: 'filled and booked · full decision into ai_trader_decision · public' } }
];
/* 每一步停多久：当前格底下那根细条走满这么久就跳下一格 */
const STEP_MS = 2000;

/* ---------- 文案落地 ---------- */
function fillCopy() {
  document.documentElement.lang = LANG === 'zh' ? 'zh-CN' : 'en';
  document.title = T(COPY.docTitle);
  document.querySelectorAll('[data-t]').forEach(n => {
    const c = COPY[n.getAttribute('data-t')];
    if (c) n.innerHTML = T(c);
  });
  // 按钮是个固定图标，点了切到哪种语言靠 title/aria 说，别拿 textContent 把 svg 冲掉
  const langBtn = document.getElementById('btn-lang');
  const next = LANG === 'zh' ? 'English' : '中文';
  langBtn.title = next;
  langBtn.setAttribute('aria-label', next);
}
function renderFacts() {
  document.getElementById('facts').innerHTML = FACTS.map(f => `<span><b>${esc(T(f.k))}</b>${esc(T(f.v))}</span>`).join('');
}

/* ---------- 首屏卡 + 背景里那道山：几只示例 trader 轮流上场 ----------
   每只先把这一局回放一遍：山脊从左往右长出来，卡上的天数、收益率、权益跟着走，唤醒一条条滑进来；
   放到「现在」停一会儿、卡上走心跳倒计时，山散成烟，换下一只。指针停在卡上、焦点在卡里就不换；
   减弱动效、截图不放，只摆 kestrel 的「现在」，点圆点直接换 */
const HERO_STILL = REDUCED || SHOT;
/* 回放节奏（毫秒）：走到第一条唤醒、之后每条唤醒、最后一条走到「现在」、停在「现在」、散掉 */
const RP_FIRST = 1200, RP_EVENT = 460, RP_END = 500, RP_HOLD = 3800, RP_FADE = 700;
const DAY_MS = 24 * HOUR;
/* 每只的回放脚本：关键帧 { 第几毫秒, 光标落在哪个时刻 }，开头一帧、每条唤醒一帧、「现在」一帧 */
const HERO_KEYS = HERO_TRADERS.map(tr => {
  const keys = [{ at: 0, t: tr.t0 }, ...tr.events.map((ev, k) => ({ at: RP_FIRST + k * RP_EVENT, t: ev.at }))];
  keys.push({ at: keys[keys.length - 1].at + RP_END, t: MOCK_NOW });
  return keys;
});
/* 轮到第几只 / replay 回放中、now 停在「现在」、fade 散场 / 这一段走了几毫秒 / 光标的时刻 / 山脊长到哪（0~1）/ 滑进来几条唤醒了 /
   山聚着多少（1 聚齐、0 散成烟）/ 散完换哪只 / 指针或焦点在卡上 / 第几次上场（换了一只，山从头长） */
const SHOW = { next: 0, hold: false, gen: 0 };
enterTrader(0);
/* 卡上的「现在」：从 MOCK_NOW 起每秒走一下，页面在后台就停（同看板的心跳，两边对得上）；切语言重画时接着走 */
let heroNow = MOCK_NOW, heroTimer = 0;

const beatMs = tr => tr.interval === '4h' ? 4 * HOUR : HOUR;
/* 光标在第 ms 毫秒落在哪个时刻：两帧之间缓进缓出，每到一条唤醒顿一下 */
function replayTime(keys, ms) {
  let k = 0;
  while (k < keys.length - 2 && ms >= keys[k + 1].at) k++;
  const a = keys[k], b = keys[k + 1], s = Math.min(1, (ms - a.at) / (b.at - a.at));
  return a.t + (b.t - a.t) * s * s * (3 - 2 * s);
}
/* 那一刻的权益：曲线每小时一个点，中间按直线插，过了最后一个点就是最后那个值 */
function equityAt(curve, t) {
  const f = Math.min(curve.length - 1, Math.max(0, (t - curve[0].t) / HOUR)), i = Math.floor(f);
  return curve[i].v + (curve[Math.min(curve.length - 1, i + 1)].v - curve[i].v) * (f - i);
}
/* 下一次心跳：下一根 K 线收盘，落在唤醒时段外的跳过（新加坡时间，两端都算，起点晚于终点就跨到次日） */
function nextBeat(tr, now) {
  const iv = beatMs(tr);
  let t = Math.floor(now / iv) * iv + iv;
  if (!tr.wakeWindow) return t;
  const [from, to] = tr.wakeWindow.split('–').map(s => { const [h, m] = s.split(':'); return h * 60 + +m; });
  const inWin = x => { const m = (x + 8 * HOUR) % DAY_MS / 60000; return from <= to ? m >= from && m <= to : m >= from || m <= to; };
  while (!inWin(t)) t += iv;
  return t;
}
/* 卡上时间线那一行的时刻：和光标同一天写时:分，不同天写月/日 */
const recTime = (at, ref) => sgDt(at).day === sgDt(ref).day ? fmtHm(at) : sgDt(at).day;
/* 卡上时间线的一行：时刻、是什么叫醒它的（单币轮带上只处理哪个币，单笔复盘带上复的哪一笔）、干了什么 */
function recHtml(tr, ev, ref) {
  const kind = ev.kind === 'TRADE' ? fmt(HC.kind.TRADE, { iv: tr.interval })
    : ev.kind === 'DEBRIEF' ? `${T(HC.kind.DEBRIEF)} · ${ev.sym} ${T(ev.side === 'LONG' ? HC.long : HC.short)}`
    : T(HC.kind[ev.kind]) + (ev.sym ? ` · ${fmt(HC.only, { s: ev.sym.replace('USDT', '') })}` : '');
  const tone = HC_TONE[ev.kind];
  return `<li${tone ? ` class="${tone}"` : ''}><time data-at="${ev.at}">${recTime(ev.at, ref)}</time><b>${esc(kind)}</b><span>${esc(T(ev.d))}</span></li>`;
}

/* 首屏卡：顶上一排（切换圆点、示例字样、第几天）一直在，底下 .hc-in 是这一只的，换只时淡出淡进 */
function renderHeroCard() {
  const card = document.getElementById('hcard');
  card.innerHTML = `<div class="hc-top">
      <span class="hc-pg">${HERO_TRADERS.map((tr, i) => `<button type="button" data-go="${i}" aria-label="${esc(fmt(HC.pick, { name: tr.name }))}"></button>`).join('')}</span>
      <span>${T(HC.sample)}</span><span class="tag sm" data-day></span>
    </div>
    <div class="hc-in"></div>`;
  // 切语言时正赶上散场：直接让下一只上场
  if (SHOW.phase === 'fade') enterTrader(SHOW.next);
  fillHeroCard();
  clearInterval(heroTimer);
  if (HERO_STILL) return;
  heroTimer = setInterval(() => {
    if (document.hidden) return;
    heroNow += 1000;
    if (SHOW.phase === 'now') paintHero();
  }, 1000);
}
/* 把这一只摆进卡里：名字、模型这些不动的，加上光标此刻的那些；已经滑进来的唤醒取最新三条 */
function fillHeroCard() {
  const card = document.getElementById('hcard'), tr = HERO_TRADERS[SHOW.i], toDash = tr.dash && !MOBILE.matches;
  const ref = SHOW.phase === 'replay' ? SHOW.t : heroNow;
  card.querySelectorAll('[data-go]').forEach((b, i) => b.setAttribute('aria-pressed', i === SHOW.i));
  const win = tr.wakeWindow ? fmt(HC.win, { win: tr.wakeWindow }) : T(HC.allDay);
  card.querySelector('.hc-in').innerHTML = `<div class="hc-nm"><b>${esc(tr.name)}</b><span class="tag g"><i class="live"></i>${T(HC.running)}</span></div>
    <div class="hc-meta">${esc(fmt(HC.meta, { model: tr.model, iv: tr.interval, win, round: tr.round }))}</div>
    <b class="big nf hc-pnl" data-pnl></b>
    <div class="hc-eq"><span>${T(HC.equity)} <b data-eq></b></span><span>${T(HC.initial)} ${fnum(tr.seed, 0)}</span></div>
    <div class="hc-beat">
      <div class="hc-rp"><span>${T(HC.replay)}</span><b data-at></b></div>
      <div class="hc-nx"><span>${T(HC.next)}</span><b data-next></b></div>
      <div class="hc-lf"><span>${T(HC.left)}</span><b data-left></b></div>
      <span class="bar"><i data-bar></i></span>
    </div>
    <ol class="hc-rec">${tr.events.slice(Math.max(0, SHOW.shown - 3), SHOW.shown).reverse().map(ev => recHtml(tr, ev, ref)).join('')}</ol>
    <a class="btn wide" href="${toDash ? '#s-dash' : 'https://wtfibought.com/arena'}">${T(toDash ? HC.more : HC.arena)}<svg data-icon="${toDash ? 'chevron-down' : 'arrow-up-right'}"></svg></a>`;
  icons(card);
  paintHero();
}
/* 卡上跟着光标走的：第几天、收益率、权益；回放时写回放到哪、细条是回放走到哪，停在「现在」时是心跳倒计时、细条是这一拍走到哪 */
function paintHero() {
  const card = document.getElementById('hcard'), tr = HERO_TRADERS[SHOW.i], rp = SHOW.phase === 'replay';
  const t = rp ? SHOW.t : heroNow, eq = equityAt(tr.curve, t);
  putText(card.querySelector('[data-day]'), fmt(HC.day, { n: Math.floor((t - tr.t0) / DAY_MS) + 1 }));
  const pnl = card.querySelector('[data-pnl]'), html = pnlHtml((eq / tr.seed - 1) * 100);
  if (pnl.innerHTML !== html) { pnl.innerHTML = html; pnl.className = `big nf hc-pnl ${html[0] === '-' ? 'dn' : 'up'}`; }
  putText(card.querySelector('[data-eq]'), fnum(eq));
  const beat = card.querySelector('.hc-beat'), bar = card.querySelector('[data-bar]');
  beat.classList.toggle('rp', rp);
  if (rp) {
    const keys = HERO_KEYS[SHOW.i];
    putText(card.querySelector('[data-at]'), fmtDt(SHOW.t));
    bar.style.transform = `scaleX(${(SHOW.ms / keys[keys.length - 1].at).toFixed(4)})`;
    return;
  }
  const nb = nextBeat(tr, heroNow), sec = Math.floor((nb - heroNow) / 1000), h = Math.floor(sec / 3600), p2 = n => String(n).padStart(2, '0');
  putText(card.querySelector('[data-next]'), fmtHm(nb));
  putText(card.querySelector('[data-left]'), `${h ? h + ':' : ''}${p2(Math.floor(sec % 3600 / 60))}:${p2(sec % 60)}`);
  // 不在唤醒时段里的，离下一拍超过一拍长，细条空着
  bar.style.transform = `scaleX(${Math.max(0, 1 - (nb - heroNow) / beatMs(tr)).toFixed(4)})`;
}
/* 一条唤醒滑进来：整列往下挪一格、新的一条淡进来，挤到第四格的那条挪完再摘；时刻按这一条重写（跨了天的改写月/日） */
function popRec(tr, ev) {
  const ol = document.querySelector('#hcard .hc-rec');
  ol.insertAdjacentHTML('afterbegin', recHtml(tr, ev, ev.at));
  ol.querySelectorAll('time').forEach(tm => { tm.textContent = recTime(+tm.dataset.at, ev.at); });
  const li = ol.firstElementChild, ease = 'cubic-bezier(.16, 1, .3, 1)';
  li.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 380, easing: ease });
  ol.animate([{ transform: `translateY(-${li.offsetHeight}px)` }, { transform: 'none' }], { duration: 380, easing: ease })
    .onfinish = () => { while (ol.children.length > 3) ol.lastElementChild.remove(); };
}

/* 让第 i 只上场：从头回放；不放动效时直接摆到「现在」 */
function enterTrader(i) {
  const tr = HERO_TRADERS[i];
  Object.assign(SHOW, { i, ms: 0, fade: 1, gen: SHOW.gen + 1 },
    HERO_STILL ? { phase: 'now', t: MOCK_NOW, cur: 1, shown: tr.events.length } : { phase: 'replay', t: tr.t0, cur: 0, shown: 0 });
}
/* 散场：山散成烟（SHOW.fade 由 stepShow 往下走），卡上这一只跟着同步淡出 */
function leaveTrader(next) {
  Object.assign(SHOW, { phase: 'fade', ms: 0, next });
  document.querySelector('#hcard .hc-in').animate([{ opacity: 1 }, { opacity: 0, transform: 'translateY(-6px)' }],
    { duration: RP_FADE, easing: 'ease-in', fill: 'forwards' });
}
/* 点切换圆点：散掉这一只，换点的那只（散场中途点的，散完换它）；不放动效时直接换 */
function goTrader(i) {
  if (HERO_STILL) { enterTrader(i); fillHeroCard(); dotsRepaint(); return; }
  if (SHOW.phase === 'fade') SHOW.next = i;
  else leaveTrader(i);
}
/* 每帧走一步：回放推着光标走、唤醒到点就滑进来；停在「现在」够久了就散场（指针、焦点在卡上不计时）；散完下一只上场 */
function stepShow(dt) {
  const tr = HERO_TRADERS[SHOW.i], keys = HERO_KEYS[SHOW.i];
  if (SHOW.phase === 'replay') {
    const end = keys[keys.length - 1].at;
    SHOW.ms = Math.min(end, SHOW.ms + dt);
    SHOW.t = replayTime(keys, SHOW.ms);
    SHOW.cur = Math.min(1, (SHOW.t - tr.t0) / (tr.curve[tr.curve.length - 1].t - tr.t0));
    const due = SHOW.ms < RP_FIRST ? 0 : Math.min(tr.events.length, Math.floor((SHOW.ms - RP_FIRST) / RP_EVENT) + 1);
    while (SHOW.shown < due) popRec(tr, tr.events[SHOW.shown++]);
    if (SHOW.ms >= end) Object.assign(SHOW, { phase: 'now', ms: 0 });
    paintHero();
  } else if (SHOW.phase === 'now') {
    if (!SHOW.hold) SHOW.ms += dt;
    if (SHOW.ms >= RP_HOLD) leaveTrader((SHOW.i + 1) % HERO_TRADERS.length);
  } else {
    SHOW.ms += dt;
    SHOW.fade = Math.max(0, 1 - SHOW.ms / RP_FADE);
    if (SHOW.ms < RP_FADE) return;
    enterTrader(SHOW.next);
    const inner = document.querySelector('#hcard .hc-in');
    inner.getAnimations().forEach(a => a.cancel());
    fillHeroCard();
    inner.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 420, easing: 'cubic-bezier(.16, 1, .3, 1)' });
  }
}
/* 卡上的手势（卡本身不重建，挂一次）：点圆点换只；指针停在卡上、焦点在卡里就不换 */
function setupHeroCard() {
  const card = document.getElementById('hcard');
  card.addEventListener('click', e => { const b = e.target.closest('[data-go]'); if (b) goTrader(+b.dataset.go); });
  card.addEventListener('pointerenter', () => { SHOW.hold = true; });
  card.addEventListener('pointerleave', () => { SHOW.hold = false; });
  card.addEventListener('focusin', () => { SHOW.hold = true; });
  card.addEventListener('focusout', e => { if (!card.contains(e.relatedTarget)) SHOW.hold = false; });
}
/* 开演：首屏在视口里才走，滚出去就停、滚回来接着放；页面切到后台时 rAF 自己停 */
function startShow() {
  if (HERO_STILL) return;
  let raf = 0, last = 0;
  const loop = ts => {
    stepShow(last ? Math.min(100, ts - last) : 0);
    last = ts;
    raf = requestAnimationFrame(loop);
  };
  new IntersectionObserver(es => {
    if (es[0].isIntersecting) { if (!raf) raf = requestAnimationFrame(loop); }
    else { cancelAnimationFrame(raf); raf = 0; last = 0; }
  }).observe(document.getElementById('hero'));
}

/* ---------- 首屏下面的六步：当前格浅灰底、底下细条走进度，走满跳下一格（CSS 动画，animationend 接力）；做完的变墨 ---------- */
function renderLoop() {
  const host = document.getElementById('loop');
  host.innerHTML = `<ol class="lp" style="--lp-dur:${STEP_MS}ms">${LOOP_STEPS.map((s, i) => `<li>
      <b class="lp-no">${String(i + 1).padStart(2, '0')}</b>
      <span class="lp-name">${esc(T(s))}</span>
      <small>${esc(T(s.cap))}</small>
      <span class="lp-bar"><i></i></span>
    </li>`).join('')}</ol>
    <div class="lp-trace"><span class="tt"></span><span class="tg"></span><span class="tm"></span></div>`;
  const list = host.querySelector('.lp'), steps = [...list.children];
  const [tt, tg, tm] = host.querySelectorAll('.lp-trace span');
  const still = REDUCED || SHOT;
  let cur = 0;
  const show = i => {
    cur = i;
    steps.forEach((s, j) => { s.classList.toggle('on', j === i); s.classList.toggle('done', j < i); });
    tt.textContent = LOOP_STEPS[i].at;
    tg.textContent = LOOP_STEPS[i].tag;
    tm.textContent = T(LOOP_STEPS[i].trace);
  };
  // 悬停某格就停在那格（细条满格、日志换成那一步），移开从那格重新走
  steps.forEach((s, i) => s.addEventListener('mouseenter', () => { list.classList.add('pinned'); if (i !== cur) show(i); }));
  // 不动的场合（减弱动效 / 截图）：定格在走完的最后一步
  if (still) { list.classList.add('pinned'); show(steps.length - 1); return; }
  show(0);
  list.addEventListener('mouseleave', () => {
    list.classList.remove('pinned');
    const s = steps[cur];
    s.classList.remove('on'); void s.offsetWidth; s.classList.add('on');   // 摘了再挂，进度动画才会从头播
  });
  // 用属性不用 addEventListener：切语言会重画，监听挂两遍就一次跳两格
  host.onanimationend = e => { if (e.animationName === 'lp-fill') show((cur + 1) % steps.length); };
}

/* ---------- 首屏圆点网：主站登录页 components/fx/DotField 的纯 JS 版，画布只铺首屏 ----------
   一张错开排的圆点网，每个格点一颗点，大小深浅跟着那儿"有多浓"走，最淡也有一颗小浅灰点。浓度两个来源：
   一团慢慢翻滚、往上飘的烟（三档灰，偏右偏下；带 data-clear 的块是有字的地方，烟到跟前压到最淡）；
   左下一道山，山脊是首屏卡上那只示例 trader 的净值走势：上沿两三排高于初始资金的绿点、低于的红点，往下灰点越来越小越淡。
   山跟着卡上的回放从左往右长出来（刚长出来那截从烟里聚拢），换一只时整道山散成烟。
   指针周围推开一个洞。窄屏不画山，烟只留最淡一档 */
/* 格点间距（px）：宽屏 / 窄屏 */
const DOT_CELL = 14, DOT_CELL_NARROW = 11;
/* 这么宽以上才是左右两栏，才画山 */
const DOT_WIDE = 1024;
/* 走势重采样成多少个点 */
const RIDGE_POINTS = 60;
/* 灰点三档的透明度：浅灰、深灰、墨（暗色下是暗灰、亮灰、白） */
const DOT_ALPHA = [0.13, 0.32, 0.85];
/* 每个格点至少这么浓：没烟没山的地方也有一颗最小的浅灰点 */
const DOT_BASE = 0.12;
/* 山脊刚长出来那一截用几秒聚拢 */
const RIDGE_SETTLE_S = 0.45;

/* 山的形状：走势压成 0~1（对数刻度）；base 是初始资金落在 0~1 的哪儿 */
function ridgeShape(values) {
  const logs = values.map(v => Math.log(v / values[0]));
  const lo = Math.min(...logs), range = Math.max(...logs) - lo || 1;
  const land = Array.from({ length: RIDGE_POINTS }, (_, k) => {
    const f = k / (RIDGE_POINTS - 1) * (logs.length - 1), i = Math.floor(f);
    return (logs[i] + (logs[Math.min(logs.length - 1, i + 1)] - logs[i]) * (f - i) - lo) / range;
  });
  return { land, base: -lo / range };
}
/* (整数格点) → [0,1) */
function dotHash(x, y, z) {
  let n = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(z, 1274126177)) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
}
/* 三维平滑噪声，0~1 */
function dotNoise(x, y, z) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  let u = x - xi, v = y - yi, w = z - zi;
  u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v); w = w * w * (3 - 2 * w);
  const a = dotHash(xi, yi, zi), b = dotHash(xi + 1, yi, zi), c = dotHash(xi, yi + 1, zi), d = dotHash(xi + 1, yi + 1, zi);
  const e = dotHash(xi, yi, zi + 1), f = dotHash(xi + 1, yi, zi + 1), g = dotHash(xi, yi + 1, zi + 1), h = dotHash(xi + 1, yi + 1, zi + 1);
  const p = a + (b - a) * u, q = c + (d - c) * u, r = e + (f - e) * u, s = g + (h - g) * u;
  const m = p + (q - p) * v, k = r + (s - r) * v;
  return m + (k - m) * w;
}
/* (x, y) 这儿的烟有多浓：三层噪声叠起来，被另一层噪声推着扭，整体慢慢往上飘 */
function smokeAt(x, y, t) {
  const X = x / 300, Y = y / 300;
  const wx = dotNoise(X * 1.4 + 11, Y * 1.4, t * 0.1) - 0.5, wy = dotNoise(X * 1.4, Y * 1.4 + 7, t * 0.1 + 5) - 0.5;
  return 0.58 * dotNoise(X + wx * 1.6 + t * 0.035, Y + wy * 1.6 - t * 0.05, t * 0.06)
    + 0.28 * dotNoise(X * 2.2 + wx * 2, Y * 2.2 + wy * 2 - t * 0.09, t * 0.1 + 3)
    + 0.14 * dotNoise(X * 4.6, Y * 4.6 - t * 0.14, t * 0.16 + 9);
}

/* 切明暗时重读颜色重画（不放动效时没有每帧重画，靠它补一张） */
let dotsRepaint = () => { };
function startDots() {
  const host = document.getElementById('hero'), cv = document.getElementById('dots'), cx = cv.getContext('2d');
  // 有字、烟要让开的那几块；山往上不越过标题那块，往下不碰事实行，往右画到卡的左边为止
  const clear = host.querySelectorAll('[data-clear]');
  const copy = host.querySelector('.hero-copy'), facts = document.getElementById('facts'), card = document.getElementById('hcard');
  // 每只示例 trader 一道山，轮到谁画谁
  const shapes = HERO_TRADERS.map(tr => ridgeShape(tr.curve.map(p => p.v)));
  const still = REDUCED || SHOT;
  let W = 0, H = 0, dpr = 1, cell = DOT_CELL, quiet = [];
  // 山占的地方：右边到 landRight（是 0 就不画山）；最高到 landTop，最低到 landLow，再往下到 landFloor 就淡没了
  let landRight = 0, landTop = 0, landLow = 0, landFloor = 0;
  // 指针的视口坐标
  let px = -9999, py = -9999;
  // 每一列山脊是哪一刻（秒）长出来的；换了一只（SHOW.gen 变了）从头记
  let grown = [], gen = -1;
  // 点的三种颜色：墨、绿、红
  let ink = '', green = '', red = '';
  const readColors = () => {
    const css = getComputedStyle(document.documentElement);
    ink = css.getPropertyValue('--color-foreground');
    green = css.getPropertyValue('--color-gain');
    red = css.getPropertyValue('--color-loss');
  };
  // 走势在 x 处的值（0~1）
  const valueAt = (shape, x) => {
    const f = Math.min(1, Math.max(0, x / landRight)) * (RIDGE_POINTS - 1), i = Math.floor(f);
    return shape.land[i] + (shape.land[Math.min(RIDGE_POINTS - 1, i + 1)] - shape.land[i]) * (f - i);
  };
  // 值落在画布的哪个 y（越小越高）
  const yOf = v => landLow - (landLow - landTop) * v;
  // 一堆点一口气画完，三个数一组：x、y、半径
  const fill = dots => {
    cx.beginPath();
    for (let i = 0; i < dots.length; i += 3) {
      cx.moveTo(dots[i] + dots[i + 2], dots[i + 1]);
      cx.arc(dots[i], dots[i + 1], dots[i + 2], 0, Math.PI * 2);
    }
    cx.fill();
  };

  const paint = t => {
    // 轮到的那只：e 是整道山聚着多少（缓过的，散场时往 0 走），loose 是散了多少，front 是山脊长到哪了
    const shape = shapes[SHOW.i], g = SHOW.fade, e = g * g * (3 - 2 * g), loose = 1 - e, front = landRight * SHOW.cur;
    if (gen !== SHOW.gen) { gen = SHOW.gen; grown = []; }
    // 画布跟着首屏走，指针换到画布自己身上
    const hr = host.getBoundingClientRect(), mx = px - hr.left, my = py - hr.top;
    cx.setTransform(dpr, 0, 0, dpr, 0, 0);
    cx.clearRect(0, 0, W, H);
    // 灰点按深浅分三堆，山脊上沿的点按绿红分两堆
    const gray = [[], [], []], ridge = [[], []];
    const wide = W >= DOT_WIDE;
    const rows = Math.ceil(H / cell) + 1, cols = Math.ceil(W / cell) + 1;
    // 初始资金落在第几行：山聚着的时候，那一行在山外头（还没长到的地方也算）画成一排小点
    const bj = landRight > 0 && e > 0.9 ? Math.round(yOf(shape.base) / cell) : -1;
    for (let j = 0; j < rows; j++) {
      for (let i = 0; i < cols; i++) {
        const x = i * cell + (j & 1 ? cell / 2 : 0), y = j * cell;
        // 烟：k 是这个点有多浓（0 没有，1 最大颗）。宽屏往右偏浓，窄屏左右一样
        let k = (smokeAt(x, y, t) + (wide ? 0.16 * (x / W) : 0.08) + 0.1 * (y / H) - 0.58) / 0.32;
        for (const r of quiet) {
          const dx = Math.max(r.left - x, 0, x - r.right), dy = Math.max(r.top - y, 0, y - r.bottom);
          k -= 1.25 * Math.max(0, 1 - Math.hypot(dx, dy) / 130);
        }
        // 山那一带的烟：山聚着的时候只留最淡的一档，不跟山脊抢；还散着就放开一点（散开的点都成了烟），靠近卡那头收回去。窄屏只留最淡的
        if (!wide) k = Math.min(k, 0.44);
        else if (x < landRight) {
          const above = Math.min(1, Math.max(0, (landTop - y) / 90));
          k = Math.min(k + 0.3 * loose * (1 - above) * Math.min(1, (landRight - x) / 120), 0.44 + 0.56 * Math.max(above, loose));
        }
        // 这个点归哪一堆：0~2 是灰的三档，3 绿 4 红
        let pile = k > 0.8 ? 2 : k > 0.44 ? 1 : 0;
        if (e > 0 && x < landRight && y < landFloor) {
          let inside = false;
          if (x < front) {
            if (grown[i] === undefined) grown[i] = still ? -Infinity : t;
            // lo 是这儿散着多少：刚长出来的越新越散，整道山散场时跟着一起散；el 是聚着多少
            const lo = Math.max(loose, 1 - (t - grown[i]) / RIDGE_SETTLE_S), el = 1 - lo;
            // 散着的时候山被推得走样、往下沉：越散偏得越多
            const push = 240 * lo;
            const sx = x + (dotNoise(x / 150, y / 150, t * 0.35) - 0.5) * push;
            const sy = y + (dotNoise(x / 150 + 7, y / 150, t * 0.35 + 3) - 0.5) * push + 60 * lo;
            // depth 是这个点在山脊下面多深。山脊上沿那一圈按盖住多少缩小，边才不是锯齿
            const v = valueAt(shape, sx), depth = sy - yOf(v);
            if (depth > -cell) {
              inside = true;
              // 往上不越过 landTop 多少，往下到 landFloor 淡没
              const m = (depth < 0 ? 1 + depth / cell : Math.exp(-depth / 120)) * Math.min(1, el * 1.8)
                * Math.min(1, Math.max(0, (y - landTop + 30) / 30)) * Math.min(1, (landFloor - y) / 60)
                * (0.88 + 0.24 * dotNoise(x / 120, y / 120, t * 0.25));
              if (m > k) {
                k = m;
                // 上沿聚到一半以上才显出绿红，一块一块地显；没显色的时候是墨点
                const lit = el > 0.5 + 0.35 * (dotNoise(x / 70, y / 70, 3.3) - 0.5);
                pile = depth < 30 ? (lit ? (v >= shape.base ? 3 : 4) : 2) : depth < 92 ? 1 : 0;
              }
            }
          }
          if (!inside && j === bj && k < 0.2) { k = 0.2; pile = 1; }
        }
        const md = Math.hypot(x - mx, y - my);
        if (md < 170) k -= 1.56 * (1 - md / 170) ** 2;
        // 被压没了的地方（有字的、指针推开的、本来就空的）留一颗最小的浅灰点，整屏都是点
        if (k < DOT_BASE) { k = DOT_BASE; pile = 0; }
        (pile > 2 ? ridge[pile - 3] : gray[pile]).push(x, y, cell * 0.46 * Math.min(1, k) ** 0.8);
      }
    }
    cx.fillStyle = ink;
    gray.forEach((dots, i) => { cx.globalAlpha = DOT_ALPHA[i]; fill(dots); });
    cx.globalAlpha = 1;
    cx.fillStyle = green; fill(ridge[0]);
    cx.fillStyle = red; fill(ridge[1]);
  };

  // 块在首屏里的位置：按排版位置算（不算入场动画那一点位移）
  const box = el => {
    let x = 0, y = 0;
    for (let n = el; n !== host; n = n.offsetParent) { x += n.offsetLeft; y += n.offsetTop; }
    return { left: x, top: y, right: x + el.offsetWidth, bottom: y + el.offsetHeight };
  };
  // 量一遍：画布多大、哪几块有字、山能占哪儿
  const measure = () => {
    dpr = Math.min(2, devicePixelRatio || 1);
    W = host.clientWidth; H = host.clientHeight;
    const pw = Math.round(W * dpr), ph = Math.round(H * dpr);
    if (cv.width !== pw || cv.height !== ph) { cv.width = pw; cv.height = ph; }
    cell = W < 700 ? DOT_CELL_NARROW : DOT_CELL;
    quiet = [...clear].map(box);
    const c = box(copy), f = box(facts);
    landTop = c.bottom + 44; landLow = f.top - 70; landFloor = f.top - 16;
    // 窄屏上下排，或者屏幕太矮腾不出地方，就不画山
    landRight = W >= DOT_WIDE && landLow - landTop > 40 ? box(card).left - 28 : 0;
    if (still) paint(20);
  };
  dotsRepaint = () => { readColors(); if (still) paint(20); };
  readColors();
  // 窗口变了、字换了（换语言、字体到了）那几块的大小会变
  const ro = new ResizeObserver(measure);
  ro.observe(host);
  clear.forEach(el => ro.observe(el));
  if (still) return;

  addEventListener('pointermove', e => { px = e.clientX; py = e.clientY; }, { passive: true });
  document.documentElement.addEventListener('pointerleave', () => { px = py = -9999; });
  // 首屏滚出视口就停；页面切到后台时 rAF 自己停
  let raf = 0, visible = false, odd = false;
  const loop = ts => {
    raf = 0;
    if (!visible) return;
    // 烟走得慢，隔一帧画一次就够
    odd = !odd;
    if (odd) paint(ts / 1000);
    raf = requestAnimationFrame(loop);
  };
  new IntersectionObserver(es => {
    visible = es[0].isIntersecting;
    if (visible && !raf) raf = requestAnimationFrame(loop);
  }).observe(host);
}

/* ---------- 主题 ---------- */
function applyTheme(dark) {
  document.documentElement.classList.toggle('dark', dark);
  const b = document.getElementById('btn-theme');
  b.innerHTML = dark ? '<svg data-icon="sun"></svg>' : '<svg data-icon="moon"></svg>';
  icons(b);
  dotsRepaint();
}
const savedTheme = store.get('wiib-intro-theme');
let isDark = savedTheme ? savedTheme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;

/* ---------- 顶栏 ---------- */
/* 章节导航：顶边越过视口 40% 那条线的最后一节点亮；还在首屏、或已滚到导航之外的章节就谁都不亮 */
function setupNav() {
  const nav = document.getElementById('head-nav');
  const links = [...nav.querySelectorAll('a')];
  const secs = links.map(a => document.querySelector(a.getAttribute('href')));
  const end = document.getElementById('s-more');
  let active = null;
  const spy = () => {
    const line = innerHeight * .4;
    let next = null;
    secs.forEach((s, i) => { if (s.getBoundingClientRect().top < line) next = links[i]; });
    if (end.getBoundingClientRect().top < line) next = null;
    if (next === active) return;
    links.forEach(a => a.classList.toggle('on', a === next));
    active = next;
  };
  let raf = 0;
  addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; spy(); }); }, { passive: true });
  spy();
}
/* 字标一个字母一格，往下滚过 8px 收成 WIIB.、底边出一根细线、正中浮出图形 logo（同主站 Layout 的顶栏）。
   返回量字宽的函数：要折的字母各自多宽，滑拢时按它让位，字体到了再量 */
const WORDMARK = 'WHATIFIBOUGHT';
const WORDMARK_KEEP = new Set([0, 4, 6, 7]);
function setupHeader() {
  const hdr = document.getElementById('hdr'), logo = document.getElementById('logo');
  logo.innerHTML = [...WORDMARK].map((c, i) => `<span${WORDMARK_KEEP.has(i) ? '' : ' class="fd"'}>${c}</span>`).join('') + '<i>.</i>';
  const onScroll = () => hdr.classList.toggle('collapsed', scrollY > 8);
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();
  // 刷新时页面可能已经在半路：先按位摆好，下一帧再开过渡，不然会从头播一遍
  requestAnimationFrame(() => requestAnimationFrame(() => hdr.classList.add('ready')));
  return () => logo.querySelectorAll('.fd').forEach(s => s.style.setProperty('--w', s.getBoundingClientRect().width + 'px'));
}

/* ---------- 浮现：滚进视口才显影；首屏那几块不等滚动，跟大标题一起按 data-delay 依次浮现（见文件末尾） ---------- */
function watchReveal() {
  const io = new IntersectionObserver(es => {
    for (const e of es) if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  }, { threshold: .08, rootMargin: '0px 0px -6% 0px' });
  document.querySelectorAll('.reveal').forEach(n => { if (!n.closest('#hero')) io.observe(n); });
}

/* ---------- 逐字排版：拆成一字一格，加 .risen 逐字浮现（首屏字体到了就浮现，页脚进视口才浮现） ---------- */
function splitLetters(root) {
  let i = 0;
  root.querySelectorAll('.ln').forEach(ln => {
    const nodes = [...ln.childNodes];
    ln.textContent = '';
    for (const n of nodes) {
      if (n.nodeType !== Node.TEXT_NODE) {   // 橙色句点那个 <i> 原样当一格
        n.classList.add('ch'); n.style.setProperty('--i', i++); ln.append(n);
        continue;
      }
      for (const ch of n.textContent) {
        if (ch === ' ') { ln.append(' '); continue; }
        const s = document.createElement('span');
        s.className = 'ch'; s.textContent = ch; s.style.setProperty('--i', i++);
        ln.append(s);
      }
    }
  });
  root.classList.add('split');
}
/* 页脚巨型字标：按容器宽度算字号，刚好撑满一行 */
function fitFootWord() {
  const w = document.querySelector('.foot-word');
  w.style.fontSize = '100px';
  const r = document.createRange();
  r.selectNodeContents(w.querySelector('.ln'));
  w.style.fontSize = (100 * w.clientWidth / r.getBoundingClientRect().width).toFixed(2) + 'px';
}

/* ---------- 章节编号：拆成数位带子，进视口滚到位 ---------- */
function watchSecNo() {
  const still = REDUCED || SHOT;
  const nos = document.querySelectorAll('.sec-no');
  nos.forEach(n => { n.innerHTML = n.textContent.trim().split('').map(d => stripHtml(d, still ? d : randDigit())).join(''); });
  if (still) return;
  const roll = n => n.querySelectorAll('.dstrip').forEach((st, i) => {
    st.style.transitionDelay = (120 + i * 90) + 'ms';
    st.style.transform = `translateY(-${st.dataset.d}em)`;
  });
  const io = new IntersectionObserver(es => {
    for (const e of es) if (e.isIntersecting) { roll(e.target); io.unobserve(e.target); }
  }, { rootMargin: '0px 0px -12% 0px' });
  nos.forEach(n => io.observe(n));
}

/* ---------- 03 复盘时间线：随滚动从左到右点亮（手机上竖着，从上到下），线头一颗橙点 ---------- */
function setupTrack() {
  const trk = document.getElementById('trk');
  const nodes = [...trk.querySelectorAll('.tk')];
  const line = trk.querySelector('.trk-line'), fill = line.firstElementChild, head = trk.querySelector('.trk-head');
  let raf = 0;
  const update = () => {
    raf = 0;
    // 竖排还是横排：看第二个点在不在第一个下面
    const vert = nodes[1].offsetTop > nodes[0].offsetTop;
    const at = n => vert ? n.offsetTop : n.offsetLeft;
    // 线从第一个点走到最后一个点
    const len = at(nodes[nodes.length - 1]);
    // 时间线顶边到视口 85% 处开始走，到 40% 处走满；减弱动效、截图直接走满
    const q = REDUCED || SHOT ? 1 : Math.min(1, Math.max(0, (innerHeight * .85 - trk.getBoundingClientRect().top) / (innerHeight * .45)));
    const pos = q * len;
    line.style.width = vert ? '' : len + 'px';
    line.style.height = vert ? len + 'px' : '';
    fill.style.transform = `scale${vert ? 'Y' : 'X'}(${q.toFixed(3)})`;
    head.style.transform = vert ? `translateY(${pos.toFixed(1)}px)` : `translateX(${pos.toFixed(1)}px)`;
    nodes.forEach(n => n.classList.toggle('lit', at(n) <= pos + 1));
  };
  const req = () => { if (!raf) raf = requestAnimationFrame(update); };
  addEventListener('scroll', req, { passive: true });
  // 窗口变了、换了语言（竖排时每行高矮会变）都要重量
  new ResizeObserver(req).observe(trk);
}

/* ---------- 装配 ---------- */
function boot() {
  fillCopy();
  renderFacts();
  renderHeroCard();
  renderLoop();
  renderMarket();
  renderDash();
  // 手机只放文字：面板不挂，也省掉动画开销
  if (MOBILE.matches) { stopFlowPanels(); } else { mountFlowPanels(); }
  icons();
}

document.getElementById('btn-theme').addEventListener('click', () => {
  isDark = !isDark; store.set('wiib-intro-theme', isDark ? 'dark' : 'light'); applyTheme(isDark);
});
document.getElementById('btn-lang').addEventListener('click', () => {
  LANG = LANG === 'zh' ? 'en' : 'zh'; store.set('wiib-intro-lang', LANG); boot();
});
// 跨过手机/桌面分界时整体重挂
MOBILE.addEventListener('change', boot);

// 截图钩子：?shot 时入场动画全关（无头截长图用）；?dark 强制暗色
if (SHOT) document.documentElement.classList.add('shot');
if (location.search.includes('dark')) isDark = true;
applyTheme(isDark);
setupNav();
const measureLogo = setupHeader();
boot();
setupHeroCard();
startDots();
watchReveal();
watchSecNo();
setupTrack();

// 逐字排版：先拆字；字体到了大标题再浮现，首屏那几块跟着依次浮现（字体没到时字宽不对，会跳一下），最多等 0.8s；
// 卡浮现出来、停在第 1 天 +0.00% 一会儿，再开始回放
const heroH = document.querySelector('.hero-h'), footWord = document.querySelector('.foot-word');
splitLetters(heroH);
splitLetters(footWord);
Promise.race([document.fonts.ready, new Promise(r => setTimeout(r, 800))]).then(() => {
  measureLogo();
  fitFootWord();
  requestAnimationFrame(() => {
    heroH.classList.add('risen');
    document.querySelectorAll('#hero .reveal').forEach(n => n.classList.add('in'));
  });
  setTimeout(startShow, 900);
});
// 页脚字标进视口才浮现
new IntersectionObserver((es, io) => {
  if (es[0].isIntersecting) { footWord.classList.add('risen'); io.disconnect(); }
}, { rootMargin: '0px 0px -10% 0px' }).observe(footWord);
addEventListener('resize', fitFootWord);
