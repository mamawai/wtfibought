'use strict';
/* ============================================================
   intro.wtfibought.com · 站点脚本
   文案词表 / 主题 / 烟雾背景 / 首屏行情板 / 模拟看板 / 装配
   依赖 flow.js（LANG · T · el · esc · icons · mountFlowPanels）与 mock-data.js（MOCK · MARKET）
   ============================================================ */

/* ---------- 文案：短句、直白，事实以 README / docs 为准 ---------- */
const COPY = {
  docTitle: { zh: 'WhatIfIBought · 让你的模型替你试一遍', en: 'WhatIfIBought · Let your model try it for you' },
  navDash: { zh: '看板', en: 'Board' }, navWake: { zh: '流程', en: 'Flow' }, navEod: { zh: '每日', en: 'Daily' },
  navChat: { zh: '对话', en: 'Chat' }, navBase: { zh: '底层', en: 'Ledger' },
  navCta: { zh: '前往 WIIB', en: 'Go to WIIB' },

  heroKicker: { zh: 'AGENTIC TRADING', en: 'AGENTIC TRADING' },
  heroSub: { zh: '让你的模型替你试一遍 每一步决策都可见', en: 'Let your model try it for you and see every decision' },
  ctaMain: { zh: '前往 WIIB', en: 'Go to WIIB' },
  ctaGh: { zh: '源码', en: 'Source' },
  marketTag: { zh: '示例数据 · 非实时', en: 'SAMPLE · NOT LIVE' },

  s1kick: { zh: '01 / 看板', en: '01 / BOARD' },
  s1h: { zh: 'trader 看板', en: 'The trader board' },
  s1lead: { zh: '每个 trader 一页<br>净值曲线 持仓 交易计划 决策时间线 复盘和学习笔记<br>谁都能看', en: 'One page per trader<br>Equity curve positions trade plans decision timeline review and learning notes<br>Anyone can read it' },
  s1note: { zh: '示例数据 · 非实时', en: 'SAMPLE DATA · NOT LIVE' },

  s2kick: { zh: '02 / 流程', en: '02 / FLOW' },
  s2h: { zh: '一次唤醒是怎么跑的', en: 'What one wakeup looks like' },
  s2lead: { zh: 'K 线收盘把它叫醒<br>过闸 拼提示词 查数据 下单过校验 落账 公开 跑完就睡', en: 'A candle close wakes it<br>Gates prompt data a checked order the ledger then public' },
  s2hint: { zh: '点节点看说明 顶上可以切场景', en: 'Click a node to read and switch scenes on top' },

  s3kick: { zh: '03 / 每日', en: '03 / DAILY' },
  s3h: { zh: '每天复盘一次 再向别人学', en: 'Review daily then learn from others' },
  s3lead: { zh: '每日UTC零时 交易先停<br>先复盘自己 再向别人学 然后接着交易', en: 'Every day at UTC 00:00 trading pauses<br>Review yourself first then learn from others then trade on' },
  e1t: { zh: '先看自己', en: 'Look at yourself first' },
  e1p: { zh: '当天战绩由代码算好 模型只负责解读 不能自己算分<br>先找错 再找亮点', en: 'The day’s stats are computed in code and the model only interprets<br>Errors first highlights second' },
  e1k: { zh: '<div>一天一次 一次调用</div><div>复盘笔记进记忆</div>', en: '<div>Once a day one call</div><div>The review note goes into memory</div>' },
  e2t: { zh: '等大家写完', en: 'Wait for everyone' },
  e2p: { zh: '所有 trader 的复盘都写完 才开始学习<br>这样每个人读到的都是同一份材料', en: 'Learning starts only when every trader has written its review<br>That way everyone reads the same material' },
  e2k: { zh: '<div>复盘和学习期间不交易</div><div>最多丢几根 K 线</div>', en: '<div>No trading during review and learning</div><div>A few candles at most are skipped</div>' },
  e3t: { zh: '向别人学', en: 'Learn from others' },
  e3p: { zh: '向好的 trader 学习 向差的 trader 吸取教训<br>每条学习都要带证据 还要写明不学什么', en: 'Learn from the good traders and take warnings from the bad ones<br>Every lesson needs evidence and a note on what not to learn' },
  e3k: { zh: '<div>只读别人的复盘 不碰账户</div><div>学习笔记进下一次提示词</div>', en: '<div>Reads others’ reviews only and never touches accounts</div><div>The learning note goes into the next prompt</div>' },

  s4kick: { zh: '04 / 对话', en: '04 / CHAT' },
  s4h: { zh: '有问题 问 chat agent', en: 'Ask the chat agent' },
  s4lead: { zh: '问行情 问新闻 问你自己的 trader<br>它派几个专家并行查数据 再汇总回答<br>花钱的操作先问你<br>对 trader 的操作只弹表单 按钮在你手里', en: 'Ask about prices news or your own trader<br>It sends experts to fetch data in parallel then writes the answer<br>Costly actions ask first<br>Trader actions only show a form and you press the button' },

  s5kick: { zh: '05 / 底层', en: '05 / LEDGER' },
  s5h: { zh: '交易流是 tick 级别', en: 'Tick-level trade flow' },
  s5lead: { zh: '行情按 tick 接入 Binance 和 Polymarket 规则按真实交易所来<br>真人 AI 策略走同一本账', en: 'Prices stream tick by tick from Binance and Polymarket and the rules match the real venues<br>Humans AI and strategies share one ledger' },
  b1t: { zh: '真实行情', en: 'Live prices' },
  b1p: { zh: '美股 加密现货与永续 大宗商品 BTC 5 分钟预测<br>全部按 tick 级实时价成交', en: 'US equities crypto spot and perps commodities BTC 5-minute prediction<br>All filled at tick-level live prices' },
  b1k: { zh: '<div>bStock ×10 · BTC ETH DOGE SOL XRP BNB ZEC HYPE</div><div>黄金 · 原油 · Polymarket 盘口</div>', en: '<div>bStock ×10 · BTC ETH DOGE SOL XRP BNB ZEC HYPE</div><div>Gold · crude · Polymarket books</div>' },
  b2t: { zh: '真实规则', en: 'Real rules' },
  b2p: { zh: '1–150x 真实档位 资金费每 8 小时真收真付<br>保证金不够自动强平', en: 'Real 1–150x tiers and funding charged every 8h<br>Automatic liquidation when margin runs out' },
  b2k: { zh: '<div>maker 0.02% · taker 0.04%</div><div>全仓 / 逐仓 · 多空双向</div>', en: '<div>0.02% maker · 0.04% taker</div><div>Cross / isolated · long and short</div>' },
  b3t: { zh: '同一本账', en: 'One ledger' },
  b3p: { zh: '每一笔资金变动都记流水<br>真人 AI 策略一套规则 成绩能对账', en: 'Every balance change is recorded<br>Humans AI and strategies follow one set of rules so results reconcile' },
  b3k: { zh: '<div>44 种业务类型 按类型筛选</div><div>仓位历史一行一笔 可展开</div>', en: '<div>44 business types filterable</div><div>One row per position expandable</div>' },
  b4t: { zh: '三个进程', en: 'Three processes' },
  b4p: { zh: 'feed 接行情 sim 记账交易 agent 跑 AI 和策略<br>一个挂了不影响另外两个', en: 'feed ingests prices sim keeps the ledger agent runs the AI and strategies<br>One crash does not take the others down' },
  b4k: { zh: '<div>:8081 · :8080 · :8082</div><div>开源 MIT 自己能跑一套</div>', en: '<div>:8081 · :8080 · :8082</div><div>MIT licensed and self-hostable</div>' },
  pullQ: { zh: '重点不在预测准不准 而在于能看到模型怎么想', en: 'The point is not whether it predicts well but that you can see how it thinks' },

  s6kick: { zh: '06 / 不止 AI', en: '06 / MORE' },
  s6h: { zh: '除了 AI 交易 还能做什么', en: 'Beyond the AI trader' },
  m1t: { zh: '自己模拟交易', en: 'Trade it yourself' }, m1p: { zh: '美股 加密 合约 大宗 BTC 预测<br>一笔虚拟资金随便练', en: 'Equities crypto perps commodities BTC prediction<br>Practise with a virtual balance' },
  m2t: { zh: '回测练习', en: 'Replay practice' }, m2p: { zh: '随机截一段历史行情手动复盘<br>AI 局中提示 局后点评', en: 'Replay a random slice of history by hand<br>The AI hints mid-session and grades you after' },
  m3t: { zh: '量化策略', en: 'Quant strategies' }, m3p: { zh: 'FIBO / SQZMOM / TURTLE 三个策略在跑<br>账户看板可见', en: 'FIBO / SQZMOM / TURTLE run live<br>Each with its own account board' },
  m4t: { zh: 'chat agent', en: 'Chat agent' }, m4p: { zh: '研判工作台<br>问行情 问新闻 问自己的 trader', en: 'The research workbench<br>Ask about prices news or your own trader' },
  m5t: { zh: '排行榜与社区', en: 'Leaderboard & community' }, m5p: { zh: '总资产 / 盈利双榜 用户主页 留言板', en: 'Assets and profit leaderboards profiles comments' },
  m6t: { zh: '精密终端', en: 'Precision terminal' }, m6p: { zh: '亮暗主题 中英双语 能装成 PWA', en: 'Light and dark bilingual installable as a PWA' },

  closeH: { zh: '拿一笔模拟资金 让你的模型上场', en: 'Take a simulated balance and put your model in' },
  closeLead: { zh: '登录就有虚拟资金<br>接上模型和 key 下一根 K 线它就醒', en: 'Sign in and the balance is there<br>Plug in a model and key and it wakes on the next candle' },
  disclaimer: { zh: '所有数据均为模拟资金 不构成投资建议', en: 'All funds are simulated · not investment advice' },
  footTag: { zh: '让你的模型替你试一遍<br>每一步决策都可见', en: 'Let your model try it for you<br>Every decision visible' },
  footProduct: { zh: '产品', en: 'PRODUCT' }, footArena: { zh: '竞技场', en: 'Arena' }, footChat: { zh: '研判工作台', en: 'Workbench' },
  footCode: { zh: '源码', en: 'CODE' }, footDocs: { zh: '架构文档', en: 'Architecture' },
  footContact: { zh: '联系', en: 'CONTACT' }
};

/* 首屏规格行：图标 + 标签 + 一行小字 */
const STRIP = [
  { icon: 'activity', zh: '真实行情', en: 'Live prices', sub: 'BINANCE · POLYMARKET' },
  { icon: 'percent', zh: '真实资金费率', en: 'Real funding', sub: 'EVERY 8H' },
  { icon: 'layers', zh: '真实杠杆档位', en: 'Real leverage tiers', sub: '1–150X' },
  { icon: 'key', zh: 'BYOK', en: 'BYOK', sub: 'YOUR MODEL · YOUR KEY' },
  { icon: 'github', zh: '开源', en: 'Open source', sub: 'MIT LICENSE' }
];

/* 首屏循环图：一次唤醒的闭环，六步绕一圈；desc 进中心表盘，trace 进底部一行 */
const LOOP_STEPS = [
  { icon: 'clock', tag: 'CLOCK', zh: 'K 线收盘', en: 'Candle close', cap: { zh: '例行 · 对齐边界', en: 'routine · boundary' }, trace: { zh: '1h 边界到达 例行唤醒', en: '1h boundary hit · routine wakeup' } },
  { icon: 'shield-check', tag: 'SCHED', zh: '四道准入', en: 'Four gates', cap: { zh: '互斥 · 预算', en: 'mutex · budget' }, trace: { zh: '互斥 并发闸 3/10 时段 预算 600s 全过', en: 'mutex · gate 3/10 · window · budget 600s · all clear' } },
  { icon: 'file-text', tag: 'PROMPT', zh: '拼提示词', en: 'Build prompt', cap: { zh: '现读 · 现拼', en: 'read · assembled fresh' }, trace: { zh: '现读现拼 账户状态 + 复盘笔记 + 财经日历', en: 'assembled fresh: account + notes + econ calendar' } },
  { icon: 'cpu', tag: 'REACT', zh: '模型决策', en: 'Model decides', cap: { zh: 'ReAct · 最多 8 次', en: 'ReAct · 8 calls max' }, trace: { zh: '模型调用 3/8 klines market_snapshot funding', en: 'model call 3/8 · klines market_snapshot funding' } },
  { icon: 'wrench', tag: 'GUARD', zh: '校验下单', en: 'Check and order', cap: { zh: '越界 · 直接拒', en: 'veto · no clamp' }, trace: { zh: '25x 被拒 改 8x 通过 open_position ETHUSDT', en: '25x rejected · 8x passed · open_position ETHUSDT' } },
  { icon: 'scroll-text', tag: 'SIM', zh: '落账公开', en: 'Ledger and public', cap: { zh: '记账 · 公开', en: 'ledger · public' }, trace: { zh: '成交落账 决策全文进 ai_trader_decision 公开', en: 'filled · full decision into ai_trader_decision · public' } }
];
/* 六个节点绕盘一圈的位置：0 顶 1 右上 2 右下 3 底 4 左下 5 左上，标签朝外 */
const LOOP_POS = ['t', 'r', 'r', 'b', 'l', 'l'];

/* 看板词表（与 wiib-web ai.json 同口径） */
const DL = {
  running: { zh: '运行中', en: 'Running' },
  round: { zh: '第 {n} 局', en: 'Round {n}' },
  equity: { zh: '权益 · 初始 10,000', en: 'Equity · starts at 10,000' },
  thisRound: { zh: '本局', en: 'This round' }, dayN: { zh: '第 {n} 天', en: 'Day {n}' },
  closed: { zh: '已了结', en: 'Closed' }, tradesN: { zh: '{n} 笔', en: '{n} trades' },
  winRate: { zh: '胜率', en: 'Win rate' }, maxDd: { zh: '最大回撤', en: 'Max drawdown' },
  tokToday: { zh: '今日 token', en: 'Tokens today' },
  rangeDelta: { zh: '{n}天变化', en: '{n}d change' }, rangeAllDelta: { zh: '本局变化', en: 'Round change' },
  curve: { zh: '本局净值', en: 'This round equity' },
  r3: { zh: '3天', en: '3d' }, r7: { zh: '7天', en: '7d' }, rAll: { zh: '全部', en: 'All' },
  positions: { zh: '当前持仓 / 挂单', en: 'Open positions / orders' },
  colSym: { zh: '标的', en: 'Symbol' }, colSide: { zh: '方向', en: 'Side' }, colQty: { zh: '数量', en: 'Qty' },
  colEntry: { zh: '开仓', en: 'Entry' }, colMark: { zh: '标记', en: 'Mark' }, colSlTp: { zh: '止损 / 止盈', en: 'SL / TP' }, colUpnl: { zh: '浮盈', en: 'uPnL' },
  long: { zh: '多', en: 'Long' }, short: { zh: '空', en: 'Short' },
  limitOrder: { zh: '限价挂单', en: 'Limit' }, openLong: { zh: '开多', en: 'Open long' }, openShort: { zh: '开空', en: 'Open short' },
  plans: { zh: '交易计划 · 生效中', en: 'Trade plans · live' },
  planTitle: { zh: '交易计划', en: 'Trade plan' }, basis: { zh: '依据：', en: 'Basis: ' }, invalidation: { zh: '失效条件：', en: 'Invalidation: ' },
  entry: { zh: '入场', en: 'Entry' }, origSl: { zh: '原始止损', en: 'Initial SL' }, target: { zh: '目标', en: 'Target' }, setAt: { zh: '{t} 立', en: 'set {t}' },
  memory: { zh: '记忆笔记', en: 'Memory note' }, learning: { zh: '学习笔记', en: 'Learning note' }, lastAt: { zh: '最近 {t}', en: 'last {t}' },
  timeline: { zh: '决策时间线', en: 'Decision timeline' }, trades: { zh: '已了结交易', en: 'Closed trades' },
  lookedAt: { zh: '看了', en: 'Read' }, expand: { zh: '展开全文', en: 'Read all' }, collapse: { zh: '收起', en: 'Collapse' },
  unfold: { zh: '展开完整看板', en: 'Expand the board' }, fold: { zh: '收起看板', en: 'Collapse the board' },
  kind: { DECISION: { zh: '决策', en: 'Decision' }, ALERT: { zh: '波动警报', en: 'Volatility alert' }, REVIEW: { zh: '每日复盘', en: 'Daily review' }, LEARN: { zh: '向别人学', en: 'Peer learning' } },
  rejected: { zh: '·被拒', en: '· rejected' },
  tokTitle: { zh: '{s}s · {tools}次工具 · {calls}次模型', en: '{s}s · {tools} tool calls · {calls} model calls' },
  tool: {
    klines: { zh: 'K线', en: 'Candles' }, indicators: { zh: '指标', en: 'Indicators' }, snapshot: { zh: '市场快照', en: 'Market snapshot' },
    funding: { zh: '资金费', en: 'Funding' }, depth: { zh: '盘口', en: 'Order book' }, structure: { zh: 'K线结构', en: 'Structure' },
    peer_insights: { zh: '别人的复盘', en: 'peer_insights' },
    open_position: { zh: '开仓', en: 'Open' }, close_position: { zh: '平仓', en: 'Close' }, set_stop_loss: { zh: '移动止损', en: 'Move stop' },
    write_plan: { zh: '补立计划', en: 'Write plan' }, cancel_order: { zh: '撤单', en: 'Cancel order' }
  },
  manner: { takeProfit: { zh: '止盈带走', en: 'Take-profit' }, stopLoss: { zh: '止损带走', en: 'Stop-loss' }, manual: { zh: '主动平仓', en: 'Closed by model' } },
  openedAt: { zh: '{t} 开', en: 'opened {t}' }, closedAt: { zh: '{t} 平', en: 'closed {t}' }, held: { zh: '持有 {d}', en: 'held {d}' },
  openDecision: { zh: '开仓决策', en: 'Open decision' }, closeDecision: { zh: '平仓决策', en: 'Close decision' },
  pnlTip: { zh: '较初始', en: 'vs. seed' }
};
const fmt = (o, vars) => T(o).replace(/\{(\w+)\}/g, (_, k) => vars[k]);
const fnum = (n, d = 2) => n.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
const signed = (n, d = 2) => (n >= 0 ? '+' : '') + fnum(n, d);
const ftok = n => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
const MOBILE = matchMedia('(max-width: 900px)');
const SHOT = location.search.includes('shot');

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
function renderStrip() {
  const host = document.getElementById('strip');
  if (host) host.innerHTML = STRIP.map(s => `<div class="sp"><svg data-icon="${s.icon}"></svg><b>${esc(T(s))}</b><small>${s.sub}</small></div>`).join('');
}

/* ---------- 首屏循环图：一块斜放的轨道盘，中心是标志，六个节点挂在盘沿外 ----------
   彗星（发光 + 芯）沿盘沿顺时针跑，头触到哪个节点哪个亮；
   盘沿是 SVG 椭圆，节点按弧长等分，芯片和标签是 HTML（字号不随盘缩放）；
   底下一行 trace 跟着换 */
let loopRaf = 0, loopRo = null;
function renderLoop() {
  const host = document.getElementById('loop');
  if (!host) return;
  cancelAnimationFrame(loopRaf);
  if (loopRo) loopRo.disconnect();
  const N = LOOP_STEPS.length;
  host.innerHTML = `
    <svg class="lp-svg">
      <defs>
        <linearGradient id="lp-band" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--fg)" stop-opacity=".10"/><stop offset="1" stop-color="var(--fg)" stop-opacity=".03"/></linearGradient>
        <radialGradient id="lp-glow"><stop offset="0" stop-color="var(--primary)" stop-opacity=".22"/><stop offset="1" stop-color="var(--primary)" stop-opacity="0"/></radialGradient>
      </defs>
      <ellipse class="glow"/>
      <path class="band"/>
      <ellipse class="inner i1"/><ellipse class="inner i2"/>
      <g class="spokes"></g>
      <ellipse class="rim"/>
      <ellipse class="comet-glow" pathLength="1"/><ellipse class="comet-core" pathLength="1"/>
      <g class="arrows"></g>
      <g class="drops"></g>
      <g class="dots"></g>
      <image class="logo" href="logo.png"/>
    </svg>
    ${LOOP_STEPS.map((s, i) => `<div class="lp-node pos-${LOOP_POS[i]}" data-i="${i}"><span class="chip"><svg data-icon="${s.icon}"></svg></span><span class="lbl"><b>${esc(T(s))}</b><small>${esc(T(s.cap))}</small></span></div>`).join('')}
    <div class="lp-trace"><span class="tt">08:00:00</span><span class="tg"></span><span class="tm"></span></div>`;
  icons(host);
  const svg = host.querySelector('.lp-svg');
  const q = c => svg.querySelector(c);
  const rim = q('.rim'), glow = q('.glow'), band = q('.band'), i1 = q('.i1'), i2 = q('.i2'), logo = q('.logo');
  const cg = q('.comet-glow'), cc = q('.comet-core');
  const gSpokes = q('.spokes'), gArrows = q('.arrows'), gDrops = q('.drops'), gDots = q('.dots');
  const nodes = [...host.querySelectorAll('.lp-node')];
  const trace = { tt: host.querySelector('.tt'), tg: host.querySelector('.tg'), tm: host.querySelector('.tm') };
  let fr = [];   // 各节点在盘沿上的弧长分数
  const setEl = (e, cx, cy, rx, ry) => { e.setAttribute('cx', cx); e.setAttribute('cy', cy); e.setAttribute('rx', rx); e.setAttribute('ry', ry); };
  const layout = () => {
    const W = host.clientWidth, H = host.clientHeight;
    if (!W) return;
    const mobile = MOBILE.matches;
    const cx = W / 2, cy = H * .5, rx = Math.min(W * .33, 225), ry = rx * .42, th = Math.max(8, ry * .18);
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    setEl(rim, cx, cy, rx, ry); setEl(cg, cx, cy, rx, ry); setEl(cc, cx, cy, rx, ry);
    setEl(i1, cx, cy, rx * .68, ry * .68); setEl(i2, cx, cy, rx * .38, ry * .38);
    setEl(glow, cx, cy, rx * .9, ry * 1.1);
    band.setAttribute('d', `M ${cx - rx} ${cy} A ${rx} ${ry} 0 0 0 ${cx + rx} ${cy} L ${cx + rx} ${cy + th} A ${rx} ${ry} 0 0 1 ${cx - rx} ${cy + th} Z`);
    const lw = Math.min(rx * .46, 104);
    logo.setAttribute('width', lw); logo.setAttribute('x', cx - lw / 2); logo.setAttribute('y', cy - lw * .3);
    // 节点按弧长等分，从顶点起顺时针：先找顶点的弧长分数
    const len = rim.getTotalLength();
    let top = 0, best = Infinity;
    for (let s = 0; s < 400; s++) { const p = rim.getPointAtLength(len * s / 400); const d = Math.abs(p.x - cx) + (p.y > cy ? 1e9 : 0); if (d < best) { best = d; top = s / 400; } }
    fr = LOOP_STEPS.map((_, i) => (top + i / N) % 1);
    const pts = fr.map(f => rim.getPointAtLength(f * len));
    gSpokes.innerHTML = pts.map(p => `<line x1="${cx}" y1="${cy}" x2="${p.x}" y2="${p.y}"/>`).join('');
    // 芯片挂在盘沿外：按椭圆比例往外推
    const kx = 1 + (mobile ? 46 : 62) / rx, ky = 1 + (mobile ? 40 : 52) / ry;
    const chips = pts.map(p => ({ x: cx + (p.x - cx) * kx, y: cy + (p.y - cy) * ky }));
    gDrops.innerHTML = pts.map((p, i) => `<line x1="${p.x}" y1="${p.y}" x2="${chips[i].x}" y2="${chips[i].y}"/>`).join('');
    gDots.innerHTML = pts.map(p => `<circle cx="${p.x}" cy="${p.y}" r="3.2"/>`).join('');
    // 节点之间各一枚顺时针小箭头，贴着切线
    gArrows.innerHTML = fr.map(f => {
      const m = (f + .5 / N) % 1, a = rim.getPointAtLength(m * len), b = rim.getPointAtLength(((m + .004) % 1) * len);
      const ang = Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI;
      return `<path d="M-4 -3 L3 0 L-4 3 Z" transform="translate(${a.x} ${a.y}) rotate(${ang})"/>`;
    }).join('');
    nodes.forEach((n, i) => {
      const chip = n.querySelector('.chip');
      n.style.left = '0px'; n.style.top = '0px';
      n.style.left = (chips[i].x - chip.offsetLeft - chip.offsetWidth / 2) + 'px';
      n.style.top = (chips[i].y - chip.offsetTop - chip.offsetHeight / 2) + 'px';
    });
  };
  layout();
  loopRo = new ResizeObserver(layout);
  loopRo.observe(host);

  let clock = 8 * 3600;
  const show = i => {
    nodes.forEach((n, j) => n.classList.toggle('on', j === i));
    [...gDots.children].forEach((d, j) => d.classList.toggle('on', j === i));
    clock += 4 + Math.floor(Math.random() * 40);
    trace.tt.textContent = `${String(Math.floor(clock / 3600) % 24).padStart(2, '0')}:${String(Math.floor(clock / 60) % 60).padStart(2, '0')}:${String(clock % 60).padStart(2, '0')}`;
    trace.tg.textContent = LOOP_STEPS[i].tag;
    trace.tm.textContent = T(LOOP_STEPS[i].trace);
  };
  show(0);
  if (REDUCED || SHOT) { cg.style.display = 'none'; cc.style.display = 'none'; return; }
  const PERIOD = 12000, TAIL = .09;
  let t0 = null, active = 0;
  const frame = ts => {
    loopRaf = requestAnimationFrame(frame);
    if (document.hidden || !fr.length) return;
    if (t0 == null) t0 = ts;
    const k = (fr[0] + ((ts - t0) % PERIOD) / PERIOD) % 1;   // 从顶点出发
    for (const c of [cg, cc]) { c.setAttribute('stroke-dasharray', `${TAIL} ${1 - TAIL}`); c.setAttribute('stroke-dashoffset', -(k - TAIL)); }
    // 彗星头刚过哪个节点，哪个亮
    let hit = active;
    fr.forEach((f, i) => { const d = (k - f + 1) % 1; if (d < .02) hit = i; });
    if (hit !== active) { active = hit; show(hit); }
  };
  loopRaf = requestAnimationFrame(frame);
}

/* ---------- 主题 ---------- */
function applyTheme(dark) {
  document.documentElement.classList.toggle('dark', dark);
  const b = document.getElementById('btn-theme');
  b.innerHTML = dark ? '<svg data-icon="sun"></svg>' : '<svg data-icon="moon"></svg>';
  icons(b);
}
const savedTheme = store.get('wiib-intro-theme');
let isDark = savedTheme ? savedTheme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;

/* ---------- 烟雾背景：登录页 DitherSmoke 的纯 JS 版，fixed 铺满整页 ----------
   Perlin fbm 域扭曲出烟形 → Bayer 抖色量化；低分辨率画布 CSS pixelated 放大出大颗粒。
   鼠标/手指靠近把烟"吹开"一个洞；reduced-motion 只画一帧；WebGL 不可用就保持透明，点阵兜底 */
const SMOKE_PIXEL = 2.5, SMOKE_LEVELS = 4;
const SMOKE_PALETTE = {
  dark: { bg: [0.043, 0.047, 0.059], ink: [0.16, 0.18, 0.23], accent: [0.976, 0.451, 0.086] },
  light: { bg: [0.965, 0.965, 0.957], ink: [0.67, 0.67, 0.64], accent: [0.976, 0.451, 0.086] }
};
const SMOKE_VERT = 'attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }';
const SMOKE_FRAG = `
precision mediump float;
uniform vec2  u_res;
uniform float u_time;
uniform vec2  u_mouse;
uniform vec3  u_bg;
uniform vec3  u_ink;
uniform vec3  u_accent;
vec4 mod289(vec4 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 permute(vec4 x){ return mod289(((x*34.0)+10.0)*x); }
vec4 taylorInvSqrt(vec4 r){ return 1.79284291400159 - 0.85373472095314*r; }
vec2 fade(vec2 t){ return t*t*t*(t*(t*6.0-15.0)+10.0); }
float cnoise(vec2 P){
  vec4 Pi = floor(P.xyxy) + vec4(0.0,0.0,1.0,1.0);
  vec4 Pf = fract(P.xyxy) - vec4(0.0,0.0,1.0,1.0);
  Pi = mod289(Pi);
  vec4 ix = Pi.xzxz; vec4 iy = Pi.yyww;
  vec4 fx = Pf.xzxz; vec4 fy = Pf.yyww;
  vec4 i = permute(permute(ix) + iy);
  vec4 gx = fract(i*(1.0/41.0))*2.0 - 1.0;
  vec4 gy = abs(gx) - 0.5;
  vec4 tx = floor(gx + 0.5);
  gx = gx - tx;
  vec2 g00 = vec2(gx.x, gy.x); vec2 g10 = vec2(gx.y, gy.y);
  vec2 g01 = vec2(gx.z, gy.z); vec2 g11 = vec2(gx.w, gy.w);
  vec4 norm = taylorInvSqrt(vec4(dot(g00,g00), dot(g01,g01), dot(g10,g10), dot(g11,g11)));
  g00 *= norm.x; g01 *= norm.y; g10 *= norm.z; g11 *= norm.w;
  float n00 = dot(g00, vec2(fx.x, fy.x));
  float n10 = dot(g10, vec2(fx.y, fy.y));
  float n01 = dot(g01, vec2(fx.z, fy.z));
  float n11 = dot(g11, vec2(fx.w, fy.w));
  vec2 f = fade(Pf.xy);
  vec2 nx = mix(vec2(n00, n01), vec2(n10, n11), f.x);
  return 2.3 * mix(nx.x, nx.y, f.y);
}
float fbm(vec2 p){
  float v = 0.0, a = 1.0;
  for (int i = 0; i < 4; i++) { v += a*abs(cnoise(p)); p *= 3.0; a *= 0.3; }
  return v;
}
float bayer2(vec2 a){ a = floor(a); return fract(a.x/2.0 + a.y*a.y*0.75); }
float bayer4(vec2 a){ return bayer2(0.5*a)*0.25 + bayer2(a); }
void main(){
  vec2 uv = gl_FragCoord.xy / u_res - 0.5;
  uv.x *= u_res.x / u_res.y;
  float f = fbm(uv + fbm(uv - u_time*0.05));
  if (u_mouse.x >= 0.0) {
    vec2 m = u_mouse / u_res - 0.5;
    m.x *= u_res.x / u_res.y;
    f -= 0.55 * (1.0 - smoothstep(0.0, 0.55, length(uv - m)));
  }
  float d = (bayer4(gl_FragCoord.xy) - 0.5) / ${SMOKE_LEVELS.toFixed(1)};
  float q = clamp(floor((f + d) * ${SMOKE_LEVELS.toFixed(1)}) / ${(SMOKE_LEVELS - 1).toFixed(1)}, 0.0, 1.0);
  vec3 col = mix(mix(u_bg, u_ink, q), u_accent, pow(q, 3.0) * 0.22);
  gl_FragColor = vec4(col, 1.0);
}`;

function startSmoke() {
  const cv = document.getElementById('smoke');
  if (!cv) return;
  const gl = cv.getContext('webgl', { antialias: false, depth: false });
  if (!gl) return;
  const prog = gl.createProgram();
  for (const [type, src] of [[gl.VERTEX_SHADER, SMOKE_VERT], [gl.FRAGMENT_SHADER, SMOKE_FRAG]]) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { console.error('smoke shader:', gl.getShaderInfoLog(s)); return; }
    gl.attachShader(prog, s);
  }
  gl.linkProgram(prog); gl.useProgram(prog);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, 'p');
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const u = n => gl.getUniformLocation(prog, n);
  const uRes = u('u_res'), uTime = u('u_time'), uMouse = u('u_mouse'), uBg = u('u_bg'), uInk = u('u_ink'), uAccent = u('u_accent');
  const size = () => {
    cv.width = Math.max(1, Math.ceil(cv.clientWidth / SMOKE_PIXEL));
    cv.height = Math.max(1, Math.ceil(cv.clientHeight / SMOKE_PIXEL));
    gl.viewport(0, 0, cv.width, cv.height);
  };
  size();
  const mouse = { x: -1, y: -1, tx: -1, ty: -1 };
  const render = t => {
    const p = SMOKE_PALETTE[document.documentElement.classList.contains('dark') ? 'dark' : 'light'];
    gl.uniform2f(uRes, cv.width, cv.height);
    gl.uniform1f(uTime, t);
    gl.uniform2f(uMouse, mouse.x, mouse.y);
    gl.uniform3fv(uBg, p.bg); gl.uniform3fv(uInk, p.ink); gl.uniform3fv(uAccent, p.accent);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };
  // 画布是 fixed 的，视口坐标直接就是画布坐标
  const point = (cx, cy) => { mouse.tx = cx / SMOKE_PIXEL; mouse.ty = (cv.clientHeight - cy) / SMOKE_PIXEL; };
  const onMove = e => point(e.clientX, e.clientY);
  const onTouch = e => { const t = e.touches[0]; if (t) point(t.clientX, t.clientY); };
  const onLeave = () => { mouse.tx = -1; mouse.ty = -1; };
  if (REDUCED) { render(0); }
  else {
    const t0 = performance.now();
    const frame = () => {
      requestAnimationFrame(frame);
      if (document.hidden) return;
      if (mouse.tx >= 0) {
        if (mouse.x < 0) { mouse.x = mouse.tx; mouse.y = mouse.ty; }
        mouse.x += (mouse.tx - mouse.x) * 0.08;
        mouse.y += (mouse.ty - mouse.y) * 0.08;
      } else { mouse.x = -1; mouse.y = -1; }
      render((performance.now() - t0) / 1000);
    };
    requestAnimationFrame(frame);
    window.addEventListener('mousemove', onMove);
    document.documentElement.addEventListener('mouseleave', onLeave);
    window.addEventListener('touchmove', onTouch, { passive: true });
    window.addEventListener('touchend', onLeave);
  }
  new ResizeObserver(() => { size(); if (REDUCED) render(0); }).observe(cv);
}

/* ---------- 浮现：滚进视口才显影；首屏几块按 data-delay 依次浮现 ---------- */
function watchReveal() {
  const io = new IntersectionObserver(es => {
    for (const e of es) if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  }, { threshold: .08, rootMargin: '0px 0px -6% 0px' });
  document.querySelectorAll('.reveal').forEach(n => io.observe(n));
}

/* ---------- 滚落 / 收起：高度过渡，结束后交还 auto ---------- */
function slideOpen(elm, ms = 480) {
  elm.style.display = 'block';
  const h = elm.scrollHeight;
  elm.style.height = '0px';
  elm.style.overflow = 'hidden';
  requestAnimationFrame(() => {
    elm.style.transition = `height ${ms}ms cubic-bezier(.16, 1, .3, 1)`;
    elm.style.height = h + 'px';
  });
  elm.addEventListener('transitionend', function te() { elm.style.height = ''; elm.style.transition = ''; elm.style.overflow = ''; elm.removeEventListener('transitionend', te); });
}
function slideClose(elm, ms = 360) {
  elm.style.height = elm.scrollHeight + 'px';
  elm.style.overflow = 'hidden';
  requestAnimationFrame(() => {
    elm.style.transition = `height ${ms}ms ease`;
    elm.style.height = '0px';
  });
  elm.addEventListener('transitionend', function te() { elm.style.display = 'none'; elm.style.height = ''; elm.style.transition = ''; elm.style.overflow = ''; elm.removeEventListener('transitionend', te); });
}

/* ============================================================
   首屏行情板：四个品类，价格数位滚动，慢速随机漂移（示例数据）
   ============================================================ */
let marketTimer = 0;
const marketState = new Map();   // code -> { price, chg, dec }

/* 数字 → 数位带子：数字字符变成 0–9 竖排带，逗号小数点原样 */
function digitsHtml(v, dec, from) {
  const s = '$' + v.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
  return s.split('').map((ch, i) => /\d/.test(ch)
    ? `<span class="dg"><span class="dstrip" data-d="${ch}" style="transform:translateY(-${from ? Math.floor(Math.random() * 10) : ch}em);transition-delay:${i * 40}ms">${'0123456789'.split('').map(d => `<i>${d}</i>`).join('')}</span></span>`
    : `<span class="dc">${ch}</span>`).join('');
}
/* 只滚数位，不重建节点；位数变了才整体重画 */
function rollTo(priceEl, v, dec) {
  const s = '$' + v.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
  const strips = priceEl.querySelectorAll('.dstrip');
  const digits = s.replace(/[^\d]/g, '');
  if (strips.length !== digits.length) { priceEl.innerHTML = digitsHtml(v, dec, false); return; }
  strips.forEach((st, i) => { st.style.transitionDelay = '0ms'; st.style.transform = `translateY(-${digits[i]}em)`; });
}
/* 走势线：App 首页 Sparkline 同款——单调三次插值（Fritsch–Carlson，不过冲）+ 面积渐变 + 端点光点 */
function sparkTangents(ys) {
  const n = ys.length, d = [];
  for (let i = 0; i < n - 1; i++) d.push(ys[i + 1] - ys[i]);
  const m = [d[0]];
  for (let i = 1; i < n - 1; i++) m.push(d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2);
  m.push(d[n - 2]);
  for (let i = 0; i < n - 1; i++) {
    if (d[i] === 0) { m[i] = 0; m[i + 1] = 0; continue; }
    const a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
    if (s > 9) { const t = 3 / Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
  }
  return m;
}
const SPK_W = 100, SPK_H = 28, SPK_P = 2;
function sparkGeom(data) {
  const mn = Math.min(...data), mx = Math.max(...data), step = SPK_W / (data.length - 1);
  const ys = data.map(v => SPK_H - SPK_P - ((v - mn) / (mx - mn || 1)) * (SPK_H - SPK_P * 2));
  const m = sparkTangents(ys);
  let line = `M0 ${ys[0].toFixed(2)}`;
  for (let i = 0; i < ys.length - 1; i++) {
    const x0 = i * step, x1 = (i + 1) * step;
    line += ` C${(x0 + step / 3).toFixed(2)} ${(ys[i] + m[i] / 3).toFixed(2)},${(x1 - step / 3).toFixed(2)} ${(ys[i + 1] - m[i + 1] / 3).toFixed(2)},${x1.toFixed(2)} ${ys[i + 1].toFixed(2)}`;
  }
  return { line, endY: ys[ys.length - 1] };
}
function sparkSvg(data, color, gid) {
  const { line, endY } = sparkGeom(data);
  return `<svg class="hm-spark spark-reveal" viewBox="0 0 ${SPK_W} ${SPK_H}" preserveAspectRatio="none">
    <defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".16"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>
    <path class="area" d="${line} L${SPK_W} ${SPK_H} L0 ${SPK_H} Z" fill="url(#${gid})"/>
    <path class="line" d="${line}" stroke="${color}" vector-effect="non-scaling-stroke"/>
    <circle class="spark-dot" cx="${SPK_W}" cy="${endY.toFixed(2)}" r="2" fill="${color}" style="filter:drop-shadow(0 0 2px ${color})"/>
  </svg>`;
}
function renderMarket() {
  const mount = document.getElementById('mount-market');
  clearInterval(marketTimer);
  if (!mount) return;
  if (MOBILE.matches) { mount.innerHTML = ''; return; }
  marketState.clear();
  mount.innerHTML = `<div class="hm-head"><span class="microlabel">MARKETS</span><span class="hm-tag">${esc(T(COPY.marketTag))}</span></div>
  <div class="hm-grid">${MARKET.map(cat => `<div class="hm-card">
    <a class="hm-title" href="${cat.to}"><svg data-icon="${cat.icon}" style="color:${cat.color}"></svg><b>${esc(T(cat.title))}</b><small>${esc(T(cat.sub))}</small><span class="all">${LANG === 'zh' ? '全部' : 'All'}<svg data-icon="chevron-right"></svg></span></a>
    ${cat.rows.map((r, i) => {
      marketState.set(r.code, { price: r.price, chg: r.chg, closes: r.spark.slice(0, -1) });
      const up = r.chg >= 0, color = `var(${up ? '--gain' : '--loss'})`;
      return `<div class="hm-row" data-code="${r.code}">
        <span class="hm-id"><span class="hm-badge">${r.code}</span><span class="hm-name"><b>${esc(T(r.name))}</b><small>${esc(T(r.pair))}</small></span></span>
        <span class="hm-price">${digitsHtml(r.price, 2, !SHOT && !REDUCED)}</span>
        <span class="hm-sparkbox">${sparkSvg(r.spark, color, 'spk' + cat.id + i)}</span>
        <span class="hm-chg ${up ? 'up' : 'dn'}">${signed(r.chg)}%</span>
      </div>`;
    }).join('')}
  </div>`).join('')}</div>`;
  icons(mount);
  if (SHOT || REDUCED) return;
  // 首帧从随机位滚到真实值
  requestAnimationFrame(() => requestAnimationFrame(() => {
    mount.querySelectorAll('.dstrip').forEach(st => { st.style.transform = `translateY(-${st.dataset.d}em)`; });
  }));
  // 慢速漂移：几秒挑一行动一下——数位滚动，走势线末点跟着现价走，涨跌按首根收盘重算。全是编的，页面上标着示例数据
  const rnd = mockSeeded(Date.now() & 0xffff);
  marketTimer = setInterval(() => {
    if (document.hidden) return;
    const rows = [...mount.querySelectorAll('.hm-row')];
    const row = rows[Math.floor(rnd() * rows.length)];
    const st = marketState.get(row.dataset.code);
    st.price = Math.round(st.price * (1 + (rnd() - .5) * .005) * 100) / 100;   // ±0.25%
    st.chg = (st.price - st.closes[0]) / st.closes[0] * 100;
    const up = st.chg >= 0, color = `var(${up ? '--gain' : '--loss'})`;
    rollTo(row.querySelector('.hm-price'), st.price, 2);
    const chg = row.querySelector('.hm-chg');
    chg.textContent = signed(st.chg) + '%';
    chg.className = 'hm-chg ' + (up ? 'up' : 'dn');
    const { line, endY } = sparkGeom([...st.closes, st.price]);
    const svg = row.querySelector('.hm-spark');
    svg.querySelector('.line').setAttribute('d', line);
    svg.querySelector('.line').setAttribute('stroke', color);
    svg.querySelector('.area').setAttribute('d', `${line} L${SPK_W} ${SPK_H} L0 ${SPK_H} Z`);
    svg.querySelector('.spark-dot').setAttribute('cy', endY.toFixed(2));
    svg.querySelector('.spark-dot').setAttribute('fill', color);
  }, 2600);
}

/* ============================================================
   模拟看板
   ============================================================ */
const RANGES = [{ id: 3, k: 'r3' }, { id: 7, k: 'r7' }, { id: 0, k: 'rAll' }];
let dashRange = 3, dashTab = 'timeline', dashOpen = false;

function pill(cls, text, icon) {
  return `<span class="pill ${cls}">${icon ? `<svg data-icon="${icon}" style="width:11px;height:11px"></svg>` : ''}${esc(text)}</span>`;
}
const sideArrow = side => side === 'LONG' ? 'arrow-up-right' : 'arrow-down-right';
const curveWindow = () => dashRange === 0 ? MOCK.curve : MOCK.curve.slice(-(dashRange * 24 + 1));

function renderDash() {
  const mount = document.getElementById('mount-dash');
  if (!mount) return;
  if (MOBILE.matches) { mount.innerHTML = ''; return; }
  const tr = MOCK.trader;
  const up = tr.pnlPct >= 0;
  // 区间锚在曲线末点：3 天 = 最后 72 小时
  const win = curveWindow();
  const delta = win[win.length - 1].v - win[0].v;
  const deltaPct = delta / win[0].v * 100;

  mount.innerHTML = `
  <div class="dash">
    <div class="dash-head">
      <div class="dash-id">
        <div class="avatar"><svg data-icon="bot"></svg></div>
        <div>
          <h3>${esc(tr.name)} ${pill('run', T(DL.running))}</h3>
          <div class="meta"><span>${esc(tr.model)}</span><span class="sep">·</span><span>${tr.interval}</span><span class="sep">·</span><span>${tr.wakeWindow}</span><span class="sep">·</span><span>${fmt(DL.round, { n: tr.round })}</span></div>
        </div>
      </div>
      <div class="dash-hero">
        <div class="big ${up ? 'up' : 'dn'}">${signed(tr.pnlPct)}%</div>
        <div class="eq"><span class="microlabel">${T(DL.equity)}</span><b>${fnum(tr.equity)}</b></div>
      </div>
    </div>
    <div class="dash-strip">
      <div class="cell"><span class="microlabel">${T(DL.thisRound)}</span><b>${fmt(DL.dayN, { n: tr.day })}</b></div>
      <div class="cell"><span class="microlabel">${T(DL.closed)}</span><b>${fmt(DL.tradesN, { n: tr.closed })}</b></div>
      <div class="cell"><span class="microlabel">${T(DL.winRate)}</span><b>${tr.winRate}%</b></div>
      <div class="cell"><span class="microlabel">${T(DL.maxDd)}</span><b class="dn">${fnum(tr.maxDd, 1)}%</b></div>
      <div class="cell"><span class="microlabel">${dashRange === 0 ? T(DL.rangeAllDelta) : fmt(DL.rangeDelta, { n: dashRange })}</span><b class="${delta >= 0 ? 'up' : 'dn'}">${signed(delta)} · ${signed(deltaPct)}%</b></div>
      <div class="cell"><span class="microlabel">${T(DL.tokToday)}</span><b>${ftok(tr.tokensToday)}</b></div>
    </div>
    <div class="dash-grid">
      <div class="dash-main">
        <div class="dcard">
          <div class="dcard-head">
            <span class="microlabel"><svg data-icon="line-chart"></svg>${T(DL.curve)}</span>
            <div class="right">${RANGES.map(r => `<button class="seg${r.id === dashRange ? ' act' : ''}" data-range="${r.id}">${T(DL[r.k])}</button>`).join('')}</div>
          </div>
          <div class="dcard-body"><div class="eq-chart" id="eq-chart"></div></div>
        </div>
        <div class="dcard" id="dash-tl">
          <div class="dcard-head">
            <div class="tabs">
              <button class="tab${dashTab === 'timeline' ? ' act' : ''}" data-tab="timeline">${T(DL.timeline)}</button>
              <button class="tab${dashTab === 'trades' ? ' act' : ''}" data-tab="trades">${T(DL.trades)}<span class="cnt">${MOCK.trades.length}</span></button>
            </div>
          </div>
          <div class="dcard-body" id="dash-list">${dashTab === 'timeline' ? renderTimeline() : renderTrades()}</div>
        </div>
      </div>
      <div class="dash-side">
        <div class="dcard">
          <div class="dcard-head"><span class="microlabel"><svg data-icon="wallet"></svg>${T(DL.positions)}</span></div>
          <div class="ptable-wrap">${renderPositions()}</div>
        </div>
        <div class="dcard">
          <div class="dcard-head"><span class="microlabel"><svg data-icon="clipboard-list"></svg>${T(DL.plans)}</span></div>
          <div class="dcard-body">${MOCK.plans.map(renderPlan).join('')}</div>
        </div>
        <div class="dcard">
          ${renderNote('violet', 'notebook-pen', T(DL.memory), MOCK.memory)}
          ${renderNote('sky', 'graduation-cap', T(DL.learning), MOCK.learning)}
        </div>
      </div>
    </div>
    <div class="dash-more"><button type="button" class="btn" data-fold><svg data-icon="chevrons-up"></svg>${esc(T(DL.fold))}</button></div>
    <div class="dash-fold"><button type="button" class="btn primary" data-unfold>${esc(T(DL.unfold))}<svg data-icon="chevrons-down"></svg></button></div>
  </div>`;
  icons(mount);
  drawEquity(document.getElementById('eq-chart'), win);
  setupFold(mount.querySelector('.dash'));

  mount.querySelectorAll('[data-range]').forEach(b => b.addEventListener('click', () => { dashRange = Number(b.dataset.range); renderDash(); }));
  mount.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => { dashTab = b.dataset.tab; renderDash(); }));
  mount.querySelectorAll('.note button').forEach(b => b.addEventListener('click', () => {
    const note = b.parentElement, body = note.querySelector('.body');
    const open = note.classList.toggle('open');
    open ? slideOpen(body) : slideClose(body);
  }));
  mount.querySelectorAll('.tl-more').forEach(b => b.addEventListener('click', () => {
    const box = b.previousElementSibling, full = box.querySelector('.full');
    const open = box.classList.toggle('open');
    open ? slideOpen(full) : slideClose(full);
    b.innerHTML = open ? `${esc(T(DL.collapse))}<svg data-icon="chevron-up"></svg>` : `${esc(T(DL.expand))}<svg data-icon="chevron-down"></svg>`;
    icons(b);
  }));
}

/* 折叠线压在时间线卡顶上：折起时只剩头部、仪表条、净值曲线 */
function setupFold(dash) {
  const foldH = () => dash.querySelector('#dash-tl').offsetTop;
  const fold = dash.querySelector('.dash-fold'), more = dash.querySelector('.dash-more');
  const apply = () => {
    if (dashOpen) { dash.style.maxHeight = ''; fold.style.display = 'none'; more.style.display = ''; }
    else { dash.style.maxHeight = foldH() + 'px'; fold.style.display = ''; more.style.display = 'none'; }
  };
  apply();
  fold.querySelector('[data-unfold]').addEventListener('click', () => {
    dashOpen = true;
    dash.classList.add('anim');
    dash.style.maxHeight = dash.scrollHeight + 'px';
    fold.style.display = 'none'; more.style.display = '';
    dash.addEventListener('transitionend', function te() { dash.classList.remove('anim'); dash.style.maxHeight = ''; dash.removeEventListener('transitionend', te); });
  });
  more.querySelector('[data-fold]').addEventListener('click', () => {
    dashOpen = false;
    dash.style.maxHeight = dash.scrollHeight + 'px';
    void dash.offsetHeight;                                   // 先定住当前高度，再过渡到折叠高度
    dash.classList.add('anim');
    dash.style.maxHeight = foldH() + 'px';
    more.style.display = 'none';
    dash.addEventListener('transitionend', function te() { dash.classList.remove('anim'); fold.style.display = ''; dash.removeEventListener('transitionend', te); });
    dash.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
}

function renderPositions() {
  const rows = MOCK.positions.map(p => {
    const long = p.side === 'LONG';
    return `<tr>
      <td><span class="sym ${long ? 'long' : 'short'}"><svg data-icon="${sideArrow(p.side)}"></svg>${p.symbol}</span></td>
      <td>${pill(long ? 'long' : 'short', `${T(long ? DL.long : DL.short)} ${p.lev}x`)}</td>
      <td class="r num">${p.qty}</td>
      <td class="r num">${fnum(p.entry)}<span class="sub">${T(DL.colMark)} ${fnum(p.mark)}</span></td>
      <td class="r num"><span class="dn">${fnum(p.sl)}</span><span class="sub up">${fnum(p.tp)}</span></td>
      <td class="r num ${p.upnl >= 0 ? 'up' : 'dn'}"><b>${signed(p.upnl)}</b></td>
    </tr>`;
  });
  const orders = MOCK.orders.map(o => {
    const long = o.side.includes('LONG');
    return `<tr class="order">
      <td><span class="sym"><svg data-icon="clock"></svg>${o.symbol}</span><span class="sub">${T(DL.limitOrder)}</span></td>
      <td>${pill(long ? 'long' : 'short', `${T(long ? DL.openLong : DL.openShort)} ${o.lev}x`)}</td>
      <td class="r num">${o.qty}</td>
      <td class="r num" colspan="2">${fnum(o.limit)}<span class="sub">${LANG === 'zh' ? '限价' : 'limit'}</span></td>
      <td class="r num">—</td>
    </tr>`;
  });
  return `<table class="ptable">
    <thead><tr><th>${T(DL.colSym)}</th><th>${T(DL.colSide)}</th><th class="r">${T(DL.colQty)}</th><th class="r">${T(DL.colEntry)}</th><th class="r">${T(DL.colSlTp)}</th><th class="r">${T(DL.colUpnl)}</th></tr></thead>
    <tbody>${rows.join('')}${orders.join('')}</tbody>
  </table>`;
}

function renderPlan(pl) {
  const long = pl.side === 'LONG';
  return `<div class="plan">
    <div class="ph"><svg data-icon="${sideArrow(pl.side)}" style="width:13px;height:13px;color:var(${long ? '--gain' : '--loss'})"></svg><span class="sym">${pl.symbol}</span>${pill(long ? 'long' : 'short', T(long ? DL.long : DL.short))}${pill('pri', pl.play)}<span class="set">${fmt(DL.setAt, { t: pl.setAt })}</span></div>
    <p class="basis">${esc(T(DL.basis))}${esc(T(pl.basis))}</p>
    <p><b>${esc(T(DL.invalidation))}</b>${esc(T(pl.invalidation))}</p>
    <div class="nums"><span>${T(DL.entry)} <b>${fnum(pl.entry)}</b></span><span>${T(DL.origSl)} <b>${fnum(pl.sl)}</b></span><span>${T(DL.target)} <b>${fnum(pl.tp)}</b></span></div>
    ${pl.revisions.length ? `<div class="rev">${pl.revisions.map(r => `<span class="t">${r.time}</span><b>${r.type}</b><span class="num">${r.change}</span> — ${esc(T(r.reason))}`).join('<br>')}</div>` : ''}
  </div>`;
}

function renderNote(tone, icon, title, note) {
  const text = T(note);
  const preview = text.replace(/[#*`>_-]/g, '').replace(/\s+/g, ' ').slice(0, 120);
  return `<div class="note ${tone}">
    <button type="button"><svg class="ic" data-icon="${icon}"></svg><span class="microlabel">${esc(title)}</span><span class="preview">${esc(preview)}</span><span class="time">${fmt(DL.lastAt, { t: note.time })}</span><svg class="chev" data-icon="chevron-down"></svg></button>
    <div class="body"><div>${esc(text)}</div></div>
  </div>`;
}

/* 推理全文：[ETHUSDT] 独占一行的分段标记渲染成小标签 */
function renderReason(text) {
  return text.split('\n').map(line => {
    const m = /^\s*\[([A-Z0-9]{2,20})\]\s*$/.exec(line);
    return m ? `<span class="segtag">${m[1]}</span>` : esc(line);
  }).join('\n').replace(/\n(<span class="segtag">)/g, '$1').replace(/(<\/span>)\n/g, '$1');
}

function renderTimeline() {
  return `<div class="tl">${MOCK.decisions.map(d => {
    const kindMeta = { DECISION: 'pri', ALERT: 'pri', REVIEW: 'violet', LEARN: 'sky' }[d.kind];
    const kindIcon = { REVIEW: 'notebook-pen', LEARN: 'graduation-cap' }[d.kind];
    const [day, time] = d.time.split(' ');
    const text = T(d.reasoning);
    const preview = text.replace(/\[[A-Z0-9]+\]\s*/g, '').replace(/\s+/g, ' ');
    const looked = d.looked.map((k, i) => `${i ? '<span class="dot">·</span>' : ''}<b>${esc(T(DL.tool[k] || { zh: k, en: k }))}</b>`).join('');
    const tokTitle = fmt(DL.tokTitle, { s: d.latency, tools: d.toolCalls, calls: d.modelCalls });
    return `<div class="tl-item ${d.kind.toLowerCase()}">
      <div class="tl-time"><b>${time}</b>${day}</div>
      <span class="tl-dot"></span>
      <div class="tl-card">
        <div class="tl-head">
          ${pill(kindMeta, T(DL.kind[d.kind === 'ALERT' ? 'DECISION' : d.kind]), kindIcon)}
          ${d.kind === 'ALERT' ? pill('warn', T(DL.kind.ALERT), 'zap') : ''}
          <span class="eq">${LANG === 'zh' ? '权益' : 'Equity'} <b>${fnum(d.equity)}</b></span>
          <span class="tok" title="${esc(tokTitle)}">${ftok(d.tokens)} tok</span>
        </div>
        ${looked ? `<div class="tl-looked">${esc(T(DL.lookedAt))} ${looked}</div>` : ''}
        ${d.actions.length ? `<div class="tl-actions">${d.actions.map(a => `<div class="tl-act${a.rejected ? ' bad' : ''}"><span class="tool">${esc(T(DL.tool[a.tool] || { zh: a.tool, en: a.tool }))}${a.rejected ? esc(T(DL.rejected)) : ''}</span><span class="args">${esc(T(a.args))}</span>${a.rejected ? `<div class="why">${esc(T(a.rejected))}</div>` : ''}</div>`).join('')}</div>` : ''}
        <div class="tl-reason"><div class="prev">${esc(preview)}</div><div class="full"><div>${renderReason(text)}</div></div></div>
        ${text.length > 90 ? `<button type="button" class="tl-more">${esc(T(DL.expand))}<svg data-icon="chevron-down"></svg></button>` : ''}
      </div>
    </div>`;
  }).join('')}</div>`;
}

function renderTrades() {
  return MOCK.trades.map(r => {
    const long = r.side === 'LONG';
    const tone = { takeProfit: 'gain', stopLoss: 'loss', manual: 'pri' }[r.manner];
    return `<div class="trade">
      <div class="th"><span class="sym"><svg data-icon="${sideArrow(r.side)}" style="color:var(${long ? '--gain' : '--loss'})"></svg>${r.symbol}</span>${pill(long ? 'long' : 'short', `${T(long ? DL.long : DL.short)} ${r.lev}x`)}${pill(tone, T(DL.manner[r.manner]))}<span class="px">${fnum(r.entry)} → ${fnum(r.exit)}</span><span class="pnl" style="color:var(${r.pnl >= 0 ? '--gain' : '--loss'})">${signed(r.pnl)}</span></div>
      <div class="times"><span>${fmt(DL.openedAt, { t: r.opened })}</span><span>${fmt(DL.closedAt, { t: r.closed })}</span><span>${fmt(DL.held, { d: r.held })}</span></div>
      <div class="plan"><div class="ph"><svg data-icon="clipboard-list" style="width:12px;height:12px;color:var(--primary)"></svg><span class="sym" style="color:var(--primary);font-size:11.5px">${T(DL.planTitle)}</span>${pill('pri', r.play)}</div><p><b>${esc(T(DL.invalidation))}</b>${esc(T(r.invalidation))}</p></div>
      <div class="links"><b>${T(DL.openDecision)} · ${r.opened} →</b>&nbsp;&nbsp;<b>${T(DL.closeDecision)} · ${r.closed} →</b>${r.reason ? `&nbsp;&nbsp;— ${esc(T(r.reason))}` : ''}</div>
    </div>`;
  }).join('');
}

/* 净值曲线：手绘 SVG，零轴 = 初始 10,000，末点实心，悬停十字线 */
function drawEquity(host, pts) {
  if (!host) return;
  const seed = MOCK.trader.seed;
  const W = Math.max(320, host.clientWidth), H = host.clientHeight || 280;
  const padL = 52, padR = 16, padT = 14, padB = 26;
  const xs = pts.map(p => p.t), ys = pts.map(p => p.v);
  const x0 = xs[0], x1 = xs[xs.length - 1];
  let yMin = Math.min(...ys, seed), yMax = Math.max(...ys, seed);
  const span = (yMax - yMin) || 1; yMin -= span * .08; yMax += span * .08;
  const X = t => padL + (t - x0) / (x1 - x0) * (W - padL - padR);
  const Y = v => padT + (1 - (v - yMin) / (yMax - yMin)) * (H - padT - padB);
  const last = pts[pts.length - 1];
  const color = last.v >= seed ? 'var(--gain)' : 'var(--loss)';
  // Catmull-Rom → 三次贝塞尔，曲线圆滑
  let d = `M ${X(pts[0].t).toFixed(1)} ${Y(pts[0].v).toFixed(1)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
    const c1x = X(p1.t) + (X(p2.t) - X(p0.t)) / 6, c1y = Y(p1.v) + (Y(p2.v) - Y(p0.v)) / 6;
    const c2x = X(p2.t) - (X(p3.t) - X(p1.t)) / 6, c2y = Y(p2.v) - (Y(p3.v) - Y(p1.v)) / 6;
    d += ` C ${c1x.toFixed(1)} ${c1y.toFixed(1)} ${c2x.toFixed(1)} ${c2y.toFixed(1)} ${X(p2.t).toFixed(1)} ${Y(p2.v).toFixed(1)}`;
  }
  const area = `${d} L ${X(last.t).toFixed(1)} ${(H - padB).toFixed(1)} L ${X(pts[0].t).toFixed(1)} ${(H - padB).toFixed(1)} Z`;
  // y 轴：取整步长（250/500/1000…），刻度落在整数上
  const rawStep = (yMax - yMin) / 4, mag = Math.pow(10, Math.floor(Math.log10(rawStep)));
  const step = [10, 5, 2.5, 2, 1].map(m => m * mag).find(s => s <= rawStep) || mag;
  const yTicks = [];
  for (let v = Math.ceil(yMin / step) * step; v <= yMax; v += step) yTicks.push(v);
  // x 轴：每天 00:00 一刻度
  const xTicks = [];
  for (const p of pts) { const dt = new Date(p.t + 8 * HOUR); if (dt.getUTCHours() === 0) xTicks.push(p.t); }
  if (xTicks.length > 9) { const step = Math.ceil(xTicks.length / 9); xTicks.splice(0, xTicks.length, ...xTicks.filter((_, i) => i % step === 0)); }
  const dayLabel = t => { const dt = new Date(t + 8 * HOUR); return `${String(dt.getUTCMonth() + 1).padStart(2, '0')}/${String(dt.getUTCDate()).padStart(2, '0')}`; };
  const gid = 'eqg' + Math.random().toString(36).slice(2, 7);
  host.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
    <defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".22"/><stop offset="1" stop-color="${color}" stop-opacity=".02"/></linearGradient></defs>
    ${yTicks.map(v => `<line class="gridline" x1="${padL}" x2="${W - padR}" y1="${Y(v).toFixed(1)}" y2="${Y(v).toFixed(1)}"/><text class="lbl" x="${padL - 8}" y="${(Y(v) + 3.5).toFixed(1)}" text-anchor="end">${Math.round(v).toLocaleString('en-US')}</text>`).join('')}
    <line class="gridline zero" x1="${padL}" x2="${W - padR}" y1="${Y(seed).toFixed(1)}" y2="${Y(seed).toFixed(1)}"/>
    ${xTicks.map(t => `<text class="lbl" x="${X(t).toFixed(1)}" y="${H - 8}" text-anchor="middle">${dayLabel(t)}</text>`).join('')}
    <g class="eq-reveal"><path class="area" d="${area}" fill="url(#${gid})"/><path class="line" d="${d}" stroke="${color}"/></g>
    <circle class="dot" cx="${X(last.t).toFixed(1)}" cy="${Y(last.v).toFixed(1)}" r="4" fill="${color}"/>
    <line class="cross" x1="0" x2="0" y1="${padT}" y2="${H - padB}"/>
    <circle class="cross-dot" r="3.5" fill="${color}" stroke="var(--card)" stroke-width="2" opacity="0"/>
  </svg><div class="tip"></div>`;
  const cross = host.querySelector('.cross'), cdot = host.querySelector('.cross-dot'), tip = host.querySelector('.tip');
  host.onmousemove = e => {
    const r = host.getBoundingClientRect();
    const mx = (e.clientX - r.left) / r.width * W;
    let bi = 0, bd = Infinity;
    pts.forEach((p, i) => { const dd = Math.abs(X(p.t) - mx); if (dd < bd) { bd = dd; bi = i; } });
    const p = pts[bi], px = X(p.t), py = Y(p.v);
    cross.setAttribute('x1', px); cross.setAttribute('x2', px);
    cdot.setAttribute('cx', px); cdot.setAttribute('cy', py); cdot.setAttribute('opacity', '1');
    const dt = new Date(p.t + 8 * HOUR);
    const tl = `${dayLabel(p.t)} ${String(dt.getUTCHours()).padStart(2, '0')}:00`;
    const pnl = p.v - seed;
    tip.innerHTML = `${tl}<b style="color:${pnl >= 0 ? 'var(--gain)' : 'var(--loss)'}">${fnum(p.v)}</b>${signed(pnl)} · ${signed(pnl / seed * 100)}% ${esc(T(DL.pnlTip))}`;
    const left = px / W * r.width;
    tip.style.left = Math.min(Math.max(left + 12, 0), r.width - 150) + 'px';
    tip.style.top = Math.max(py / H * r.height - 48, 0) + 'px';
  };
  host.onmouseleave = () => { cdot.setAttribute('opacity', '0'); };
}
let eqTimer = 0;
addEventListener('resize', () => {
  clearTimeout(eqTimer);
  eqTimer = setTimeout(() => { const host = document.getElementById('eq-chart'); if (host) drawEquity(host, curveWindow()); }, 150);
});

/* ---------- 装配 ---------- */
function boot() {
  fillCopy();
  renderStrip();
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

// 截图钩子：?shot 时首屏不再撑满视口、入场动画全关（无头截长图用）；?dark 强制暗色
if (SHOT) document.documentElement.classList.add('shot');
if (location.search.includes('dark')) isDark = true;
applyTheme(isDark);
boot();
startSmoke();
watchReveal();
