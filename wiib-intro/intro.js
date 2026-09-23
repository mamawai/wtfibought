'use strict';
/* ============================================================
   intro.wtfibought.com · 站点脚本
   文案词表 / 主题 / 顶栏章节导航 / 首屏网点烟雾 / 一次唤醒六步 / 行情板 / 模拟看板 / 装配
   依赖 flow.js（LANG · T · el · esc · icons · REDUCED · store · mountFlowPanels）与 mock-data.js（MOCK · MARKET · HOUR · mockSeeded）
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
  marketTitle: { zh: '市场', en: 'Markets' },
  marketTag: { zh: '示例数据 · 非实时', en: 'Sample data · not live' },

  s1h: { zh: 'trader 看板', en: 'The trader board' },
  s1lead: { zh: '每个 trader 一页<br>净值曲线 持仓 交易计划 决策时间线 复盘和学习笔记<br>谁都能看', en: 'One page per trader<br>Equity curve positions trade plans decision timeline review and learning notes<br>Anyone can read it' },
  s1note: { zh: '示例数据 · 非实时', en: 'Sample data · not live' },

  s2h: { zh: '一次唤醒是怎么跑的', en: 'What one wakeup looks like' },
  s2lead: { zh: 'K 线收盘把它叫醒<br>过闸 拼提示词 查数据 下单过校验 落账 公开 跑完就睡', en: 'A candle close wakes it<br>Gates prompt data a checked order the ledger then public' },
  s2hint: { zh: '点节点看说明 顶上可以切场景', en: 'Click a node to read and switch scenes on top' },

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

  s4h: { zh: '有问题 问 chat agent', en: 'Ask the chat agent' },
  s4lead: { zh: '问行情 问新闻 问你自己的 trader<br>它派几个专家并行查数据 再汇总回答<br>花钱的操作先问你<br>对 trader 的操作只弹表单 按钮在你手里', en: 'Ask about prices news or your own trader<br>It sends experts to fetch data in parallel then writes the answer<br>Costly actions ask first<br>Trader actions only show a form and you press the button' },

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
  pullQ: { zh: '重点不在预测准不准 而在于<mark>能看到模型怎么想</mark>', en: 'The point is not whether it predicts well but that <mark>you can see how it thinks</mark>' },

  s6h: { zh: '除了 AI 交易 还能做什么', en: 'Beyond the AI trader' },
  m1t: { zh: '自己模拟交易', en: 'Trade it yourself' }, m1p: { zh: '美股 加密 合约 大宗 BTC 预测<br>一笔虚拟资金随便练', en: 'Equities crypto perps commodities BTC prediction<br>Practise with a virtual balance' },
  m2t: { zh: '回测练习', en: 'Replay practice' }, m2p: { zh: '随机截一段历史行情手动复盘<br>AI 局中提示 局后点评', en: 'Replay a random slice of history by hand<br>The AI hints mid-session and grades you after' },
  m3t: { zh: '量化策略', en: 'Quant strategies' }, m3p: { zh: 'FIBO / SQZMOM / TURTLE 三个策略在跑<br>账户看板可见', en: 'FIBO / SQZMOM / TURTLE run live<br>Each with its own account board' },
  m4t: { zh: 'chat agent', en: 'Chat agent' }, m4p: { zh: '研判工作台<br>问行情 问新闻 问自己的 trader', en: 'The research workbench<br>Ask about prices news or your own trader' },
  m5t: { zh: '排行榜与社区', en: 'Leaderboard & community' }, m5p: { zh: '总资产 / 盈利双榜 用户主页 留言板', en: 'Assets and profit leaderboards profiles comments' },
  m6t: { zh: '海报风界面', en: 'Poster-style UI' }, m6p: { zh: '亮暗主题 中英双语 能装成 PWA', en: 'Light and dark bilingual installable as a PWA' },

  closeH: { zh: '拿一笔模拟资金 让你的模型上场', en: 'Take a simulated balance and put your model in' },
  closeLead: { zh: '登录就有虚拟资金<br>接上模型和 key 下一根 K 线它就醒', en: 'Sign in and the balance is there<br>Plug in a model and key and it wakes on the next candle' },
  disclaimer: { zh: '所有数据均为模拟资金 不构成投资建议', en: 'All funds are simulated · not investment advice' },
  footTag: { zh: '让你的模型替你试一遍<br>每一步决策都可见', en: 'Let your model try it for you<br>Every decision visible' },
  footProduct: { zh: '产品', en: 'Product' }, footArena: { zh: '竞技场', en: 'Arena' }, footChat: { zh: '研判工作台', en: 'Workbench' },
  footCode: { zh: '源码', en: 'Code' }, footDocs: { zh: '架构文档', en: 'Architecture' },
  footContact: { zh: '联系', en: 'Contact' }
};

/* 首屏规格：左边说法 右边出处 */
const STRIP = [
  { zh: '真实行情', en: 'Live prices', sub: 'BINANCE · POLYMARKET' },
  { zh: '真实资金费率', en: 'Real funding', sub: 'EVERY 8H' },
  { zh: '真实杠杆档位', en: 'Real leverage tiers', sub: '1–150X' },
  { zh: 'BYOK', en: 'BYOK', sub: 'YOUR MODEL · YOUR KEY' },
  { zh: '开源', en: 'Open source', sub: 'MIT LICENSE' }
];

/* 首屏六步：一次唤醒的闭环；cap 进格子小字，trace 进底下一行 */
const LOOP_STEPS = [
  { tag: 'CLOCK', zh: 'K 线收盘', en: 'Candle close', cap: { zh: '例行 · 对齐边界', en: 'routine · boundary' }, trace: { zh: '1h 边界到达 例行唤醒', en: '1h boundary hit · routine wakeup' } },
  { tag: 'SCHED', zh: '四道准入', en: 'Four gates', cap: { zh: '互斥 · 预算', en: 'mutex · budget' }, trace: { zh: '互斥 时段 预算 600s 交接窗口 全过', en: 'mutex · window · budget 600s · handover · all clear' } },
  { tag: 'PROMPT', zh: '拼提示词', en: 'Build prompt', cap: { zh: '现读 · 现拼', en: 'read · assembled fresh' }, trace: { zh: '现读现拼 账户状态 + 复盘笔记 + 财经日历', en: 'assembled fresh: account + notes + econ calendar' } },
  { tag: 'REACT', zh: '模型决策', en: 'Model decides', cap: { zh: 'ReAct · 最多 12 次', en: 'ReAct · 12 calls max' }, trace: { zh: '模型调用 3/12 klines market_snapshot funding', en: 'model call 3/12 · klines market_snapshot funding' } },
  { tag: 'GUARD', zh: '校验下单', en: 'Check and order', cap: { zh: '越界 · 直接拒', en: 'veto · no clamp' }, trace: { zh: '25x 被拒 改 8x 通过 open_position ETHUSDT', en: '25x rejected · 8x passed · open_position ETHUSDT' } },
  { tag: 'SIM', zh: '落账公开', en: 'Ledger and public', cap: { zh: '记账 · 公开', en: 'ledger · public' }, trace: { zh: '成交落账 决策全文进 ai_trader_decision 公开', en: 'filled · full decision into ai_trader_decision · public' } }
];
/* 每一步停多久：当前格顶上那条墨线走满这么久就跳下一格 */
const STEP_MS = 2000;

/* 看板词表（与 wiib-web ai.json 同口径） */
const DL = {
  running: { zh: '运行中', en: 'Running' },
  roundN: { zh: '第 {n} 局', en: 'Round {n}' },
  equity: { zh: '权益', en: 'Equity' }, initial: { zh: '初始 10,000', en: 'starts at 10,000' },
  thisRound: { zh: '本局', en: 'This round' }, dayN: { zh: '第 {n} 天', en: 'Day {n}' },
  closed: { zh: '已了结', en: 'Closed' }, tradesN: { zh: '{n} 笔', en: '{n} trades' },
  winRate: { zh: '胜率', en: 'Win rate' }, maxDd: { zh: '最大回撤', en: 'Max drawdown' },
  tokToday: { zh: '今日 TOKEN', en: 'Tokens today' },
  rangeDays: { zh: '{n}天', en: '{n}d' }, rangeAll: { zh: '全部', en: 'All' },
  curve: { zh: '本局净值', en: 'This round equity' },
  lastWake: { zh: '最近一次唤醒 {t}', en: 'last wake-up {t}' },
  positions: { zh: '当前持仓 / 挂单', en: 'Open positions / orders' },
  colSym: { zh: '币种', en: 'Symbol' }, colQty: { zh: '数量', en: 'Qty' }, colSlTp: { zh: '止损 / 止盈', en: 'SL / TP' }, colUpnl: { zh: '浮盈', en: 'uPnL' },
  entryAt: { zh: '开仓', en: 'Entry' },
  long: { zh: '多', en: 'Long' }, short: { zh: '空', en: 'Short' },
  limitOrder: { zh: '限价挂单', en: 'Limit order' }, limitPrice: { zh: '限价', en: 'Limit' },
  openLong: { zh: '开多', en: 'Open long' }, openShort: { zh: '开空', en: 'Open short' },
  plans: { zh: '交易计划', en: 'Trade plans' }, plansActive: { zh: '生效中 {n}', en: '{n} live' },
  basis: { zh: '依据：', en: 'Basis: ' }, invalidation: { zh: '失效条件', en: 'Invalidation' },
  entry: { zh: '入场', en: 'Entry' }, origSl: { zh: '原始止损', en: 'Initial SL' }, target: { zh: '目标', en: 'Target' }, setAt: { zh: '{t} 立', en: 'set {t}' },
  revisions: { zh: '修订 {n} 次', en: 'revised {n}×' },
  notes: { zh: '笔记', en: 'Notes' }, memory: { zh: '记忆笔记', en: 'Memory' }, learning: { zh: '学习笔记', en: 'Learning' }, lastAt: { zh: '最近 {t}', en: 'Last {t}' },
  timeline: { zh: '决策时间线', en: 'Decision timeline' }, trades: { zh: '已了结交易', en: 'Closed trades' },
  lookedAt: { zh: '看了', en: 'Read' }, expand: { zh: '展开全文', en: 'Read all' }, collapse: { zh: '收起', en: 'Collapse' },
  unfold: { zh: '展开完整看板', en: 'Expand the board' }, fold: { zh: '收起看板', en: 'Collapse the board' },
  decision: { zh: '决策', en: 'Decision' }, alert: { zh: '波动警报', en: 'Volatility alert' },
  review: { zh: '每日复盘', en: 'Daily review' }, learn: { zh: '同侪学习', en: 'Peer learning' },
  rejected: { zh: '·被拒', en: ' · rejected' },
  toolCalls: { zh: '{n}次工具', en: '{n} tool calls' }, modelCalls: { zh: '{n}次模型', en: '{n} model calls' },
  tool: {
    klines: { zh: 'K线', en: 'Candles' }, indicators: { zh: '指标', en: 'Indicators' }, snapshot: { zh: '市场快照', en: 'Market snapshot' },
    funding: { zh: '资金费', en: 'Funding' }, depth: { zh: '盘口', en: 'Order book' }, structure: { zh: 'K线结构', en: 'Structure' },
    peer_insights: { zh: '别人的复盘', en: 'peer_insights' },
    open_position: { zh: '开仓', en: 'Open' }, close_position: { zh: '平仓', en: 'Close' }, set_stop_loss: { zh: '移动止损', en: 'Move stop' },
    write_plan: { zh: '补立计划', en: 'Write plan' }, cancel_order: { zh: '撤单', en: 'Cancel order' }
  },
  manner: { takeProfit: { zh: '止盈带走', en: 'Taken by target' }, stopLoss: { zh: '止损带走', en: 'Taken by stop' }, manual: { zh: '主动平仓', en: 'Closed by hand' } },
  openedAt: { zh: '{t} 开', en: 'opened {t}' }, closedAt: { zh: '{t} 平', en: 'closed {t}' }, held: { zh: '持有 {d}', en: 'held {d}' },
  openDecision: { zh: '开仓决策', en: 'Entry decision' }, closeDecision: { zh: '平仓决策', en: 'Exit decision' }
};
const fmt = (o, vars) => T(o).replace(/\{(\w+)\}/g, (_, k) => vars[k]);
const fnum = (n, d = 2) => n.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
const signed = (n, d = 2) => (n >= 0 ? '+' : '') + fnum(n, d);
const ftok = n => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
const toolName = k => T(DL.tool[k] || { zh: k, en: k });
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
  if (host) host.innerHTML = STRIP.map(s => `<div class="kv"><b>${esc(T(s))}</b><span>${s.sub}</span></div>`).join('');
}

/* ---------- 首屏六步：当前格墨线走满就跳下一格（CSS 动画，animationend 接力），底下一行 trace 跟着换 ---------- */
let loopClock = 8 * 3600;   // 假钟，08:00:00 起步，每换一格随手走几十秒
function renderLoop() {
  const host = document.getElementById('loop');
  if (!host) return;
  host.innerHTML = `<ol class="lp-steps" style="--lp-dur:${STEP_MS}ms">${LOOP_STEPS.map((s, i) => `<li class="lp-step">
      <span class="lp-bar"><i></i></span>
      <b class="lp-no num">${String(i + 1).padStart(2, '0')}</b>
      <span class="lp-name">${esc(T(s))}</span>
      <small>${esc(T(s.cap))}</small>
    </li>`).join('')}</ol>
    <div class="lp-trace"><span class="tt num"></span><span class="tg"></span><span class="tm"></span></div>`;
  const steps = [...host.querySelectorAll('.lp-step')];
  const [tt, tg, tm] = host.querySelectorAll('.lp-trace span');
  let cur = 0;
  const show = i => {
    cur = i;
    steps.forEach((s, j) => s.classList.toggle('on', j === i));
    loopClock += 4 + Math.floor(Math.random() * 40);
    tt.textContent = [loopClock / 3600 % 24, loopClock / 60 % 60, loopClock % 60].map(v => String(Math.floor(v)).padStart(2, '0')).join(':');
    tg.textContent = LOOP_STEPS[i].tag;
    tm.textContent = T(LOOP_STEPS[i].trace);
  };
  show(0);
  // 悬停某格就停在那格（墨线满格、trace 换成那一步），移开从那格重新走
  const list = host.querySelector('.lp-steps');
  steps.forEach((s, i) => s.addEventListener('mouseenter', () => { list.classList.add('pinned'); if (i !== cur) show(i); }));
  list.addEventListener('mouseleave', () => {
    list.classList.remove('pinned');
    const s = steps[cur];
    s.classList.remove('on'); void s.offsetWidth; s.classList.add('on');   // 摘了再挂，进度动画才会从头播
  });
  // 不动的场合（减弱动效 / 截图）：墨线满格由 CSS 管，这里只是不接力
  if (REDUCED || SHOT) return;
  // 用属性不用 addEventListener：切语言会重画，监听挂两遍就一次跳两格
  host.onanimationend = e => { if (e.animationName === 'lp-fill') show((cur + 1) % steps.length); };
}

/* ---------- 主题 ---------- */
let smokeDraw = null;   // 烟雾补画一帧：减弱动效时只画一帧，切主题得手动重画
function applyTheme(dark) {
  document.documentElement.classList.toggle('dark', dark);
  const b = document.getElementById('btn-theme');
  b.innerHTML = dark ? '<svg data-icon="sun"></svg>' : '<svg data-icon="moon"></svg>';
  icons(b);
  if (smokeDraw) smokeDraw();
}
const savedTheme = store.get('wiib-intro-theme');
let isDark = savedTheme ? savedTheme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;

/* ---------- 顶栏章节导航：橙方块停在当前章节那项左边，悬停别的项就滑过去 ---------- */
function setupNav() {
  const nav = document.getElementById('head-nav');
  const dot = nav.querySelector('.nav-dot');
  const links = [...nav.querySelectorAll('a')];
  const secs = links.map(a => document.querySelector(a.getAttribute('href')));
  const end = document.getElementById('s-more');   // 导航之外的章节：滚到这里就不再点亮任何一项
  let active = null, hover = null;
  const place = () => {
    const a = hover || active;
    if (!a) { dot.style.opacity = '0'; return; }
    dot.style.left = (a.getBoundingClientRect().left - nav.getBoundingClientRect().left - 13) + 'px';
    dot.style.opacity = '1';
  };
  // 当前章节 = 顶边越过视口 40% 那条线的最后一节；还在首屏就谁都不亮
  const spy = () => {
    const line = innerHeight * .4;
    let next = null;
    secs.forEach((s, i) => { if (s && s.getBoundingClientRect().top < line) next = links[i]; });
    if (end && end.getBoundingClientRect().top < line) next = null;
    if (next === active) return;
    links.forEach(a => a.classList.toggle('on', a === next));
    active = next;
    place();
  };
  let raf = 0;
  addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; spy(); }); }, { passive: true });
  addEventListener('resize', place);
  nav.addEventListener('mouseover', e => { const a = e.target.closest('a'); if (a && a !== hover) { hover = a; place(); } });
  nav.addEventListener('mouseleave', () => { hover = null; place(); });
  spy();
  return place;
}

/* ---------- 首屏网点烟雾：登录页 DitherSmoke 的纯 JS 版，只铺首屏标题带 ----------
   Perlin fbm 域扭曲出烟形 → Bayer 抖色量化成几档灰，像印刷网点；低分辨率画布 CSS pixelated 放大出大颗粒。
   只在纸色和浅灰之间取档，不上橙；鼠标/手指靠近把烟"吹开"一个洞；标题带滚出视口就停；
   reduced-motion 只画一帧；WebGL 不可用就保持纸面 */
const SMOKE_PIXEL = 2.5, SMOKE_LEVELS = 4;
const SMOKE_PALETTE = {
  // 纸色 → 最深一档；最深那档只比分行线深一点，压在上面的墨字照样清楚
  light: { bg: [0.980, 0.980, 0.969], ink: [0.855, 0.855, 0.831] },
  dark: { bg: [0.059, 0.063, 0.071], ink: [0.145, 0.153, 0.169] }
};
const SMOKE_VERT = 'attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }';
const SMOKE_FRAG = `
precision mediump float;
uniform vec2  u_res;
uniform float u_time;
uniform vec2  u_mouse;
uniform vec3  u_bg;
uniform vec3  u_ink;
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
  gl_FragColor = vec4(mix(u_bg, u_ink, q), 1.0);
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
  const uRes = u('u_res'), uTime = u('u_time'), uMouse = u('u_mouse'), uBg = u('u_bg'), uInk = u('u_ink');
  const size = () => {
    cv.width = Math.max(1, Math.ceil(cv.clientWidth / SMOKE_PIXEL));
    cv.height = Math.max(1, Math.ceil(cv.clientHeight / SMOKE_PIXEL));
    gl.viewport(0, 0, cv.width, cv.height);
  };
  size();
  const mouse = { x: -1, y: -1, tx: -1, ty: -1 };
  const t0 = performance.now();
  smokeDraw = () => {
    const p = SMOKE_PALETTE[document.documentElement.classList.contains('dark') ? 'dark' : 'light'];
    gl.uniform2f(uRes, cv.width, cv.height);
    gl.uniform1f(uTime, (performance.now() - t0) / 1000);
    gl.uniform2f(uMouse, mouse.x, mouse.y);
    gl.uniform3fv(uBg, p.bg); gl.uniform3fv(uInk, p.ink);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };
  if (REDUCED || SHOT) {
    smokeDraw();
    new ResizeObserver(() => { size(); smokeDraw(); }).observe(cv);
    return;
  }
  // 画布跟着标题带走，指针坐标换到画布自己身上（GL 的 y 从下往上）
  const point = (cx, cy) => {
    const r = cv.getBoundingClientRect();
    mouse.tx = (cx - r.left) / SMOKE_PIXEL;
    mouse.ty = (r.bottom - cy) / SMOKE_PIXEL;
  };
  const onLeave = () => { mouse.tx = -1; mouse.ty = -1; };
  addEventListener('mousemove', e => point(e.clientX, e.clientY));
  document.documentElement.addEventListener('mouseleave', onLeave);
  addEventListener('touchmove', e => { const t = e.touches[0]; if (t) point(t.clientX, t.clientY); }, { passive: true });
  addEventListener('touchend', onLeave);
  let visible = true;
  new IntersectionObserver(es => { visible = es[0].isIntersecting; }).observe(cv);
  new ResizeObserver(size).observe(cv);
  const frame = () => {
    requestAnimationFrame(frame);
    if (document.hidden || !visible) return;
    if (mouse.tx >= 0) {
      if (mouse.x < 0) { mouse.x = mouse.tx; mouse.y = mouse.ty; }
      mouse.x += (mouse.tx - mouse.x) * 0.08;
      mouse.y += (mouse.ty - mouse.y) * 0.08;
    } else { mouse.x = -1; mouse.y = -1; }
    smokeDraw();
  };
  requestAnimationFrame(frame);
}

/* ---------- 浮现：滚进视口才显影；首屏几块按 data-delay 依次浮现 ---------- */
function watchReveal() {
  const io = new IntersectionObserver(es => {
    for (const e of es) if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  }, { threshold: .08, rootMargin: '0px 0px -6% 0px' });
  document.querySelectorAll('.reveal').forEach(n => io.observe(n));
}

/* ---------- 逐字排版：拆成一字一格 → 从基线后面升起 → 荡一道宽度波 ---------- */
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
function riseLetters(root, wave) {
  root.classList.add('risen');
  if (!wave || REDUCED || SHOT) return;
  const chs = root.querySelectorAll('.ch');
  // 升完再荡波；最后一个字荡完就摘掉 .wave，不然动画压着悬停时写的内联宽度
  setTimeout(() => {
    root.classList.add('wave');
    chs[chs.length - 1].addEventListener('animationend', () => root.classList.remove('wave'), { once: true });
  }, 700 + chs.length * 38);
}
/* 鼠标靠近哪个字哪个字变宽变粗：按距离做高斯衰减，离得远的字不动 */
function stretchOnHover(root, band) {
  if (REDUCED || SHOT || !matchMedia('(hover: hover) and (pointer: fine)').matches) return;
  const chs = [...root.querySelectorAll('.ch')];
  let centers = [], raf = 0, px = 0, py = 0;
  // 按页面坐标量一次静止时各字的中心；字变宽后邻居会挪，差这点不影响观感
  const measure = () => {
    centers = chs.map(c => { const r = c.getBoundingClientRect(); return [r.left + r.width / 2 + scrollX, r.top + r.height / 2 + scrollY]; });
  };
  const apply = () => {
    raf = 0;
    const R = parseFloat(getComputedStyle(root).fontSize) * .9;
    chs.forEach((c, i) => {
      const dx = px - centers[i][0], dy = py - centers[i][1];
      const f = Math.exp(-(dx * dx + dy * dy) / (R * R));
      c.style.fontStretch = (70 + 50 * f).toFixed(1) + '%';
      c.style.fontWeight = String(Math.round(700 + 200 * f));
    });
  };
  band.addEventListener('pointerenter', measure);
  band.addEventListener('pointermove', e => { px = e.pageX; py = e.pageY; if (!raf) raf = requestAnimationFrame(apply); });
  band.addEventListener('pointerleave', () => {
    cancelAnimationFrame(raf); raf = 0;
    chs.forEach(c => { c.style.fontStretch = ''; c.style.fontWeight = ''; });
  });
}
/* 页脚巨型字标：按容器宽度算字号，刚好撑满一行 */
function fitFootWord() {
  const w = document.querySelector('.foot-word');
  if (!w) return;
  w.style.fontSize = '100px';
  const r = document.createRange();
  r.selectNodeContents(w.querySelector('.ln'));
  w.style.fontSize = (100 * w.clientWidth / r.getBoundingClientRect().width).toFixed(2) + 'px';
}

/* ---------- 墨线：进视口从左画到右；章节头的编号跟着滚到位 ---------- */
function watchDraw() {
  const still = REDUCED || SHOT;
  document.querySelectorAll('.sec-no').forEach(n => {
    n.innerHTML = n.textContent.trim().split('').map(d => stripHtml(d, still ? d : randDigit())).join('');
  });
  const draw = el => {
    el.classList.add('drawn');
    el.querySelectorAll('.sec-no .dstrip').forEach((st, i) => {
      st.style.transitionDelay = (200 + i * 90) + 'ms';
      st.style.transform = `translateY(-${st.dataset.d}em)`;
    });
  };
  const els = document.querySelectorAll('.drawline');
  if (still) { els.forEach(draw); return; }
  const io = new IntersectionObserver(es => {
    for (const e of es) if (e.isIntersecting) { draw(e.target); io.unobserve(e.target); }
  }, { rootMargin: '0px 0px -12% 0px' });
  els.forEach(el => io.observe(el));
}

/* ---------- 滚动：顶栏底线上的阅读进度方块 + 03 的交接进度线 ---------- */
function setupScrollBits() {
  const rule = document.querySelector('.site-head .rule'), head = rule.querySelector('.rule-head');
  const track = document.getElementById('handover');
  const pillars = track ? [...track.querySelectorAll('.pillar')] : [];
  const hv = track && track.querySelector('.hv-head');
  let raf = 0;
  const update = () => {
    raf = 0;
    const max = document.documentElement.scrollHeight - innerHeight;
    const p = max > 0 ? Math.min(1, scrollY / max) : 0;
    head.style.transform = `translateX(${(p * (rule.clientWidth - 8)).toFixed(1)}px)`;
    if (!track) return;
    // 交接线：三栏顶边到视口 85% 处开始走，到 40% 处走满；手机竖排、减弱动效、截图直接满格
    let q = 1;
    if (!REDUCED && !SHOT && !MOBILE.matches) {
      q = Math.min(1, Math.max(0, (innerHeight * .85 - track.getBoundingClientRect().top) / (innerHeight * .45)));
    }
    // 每栏分到三分之一段：走到哪栏哪栏点亮
    pillars.forEach((el, i) => {
      const lp = Math.min(1, Math.max(0, q * pillars.length - i));
      el.style.setProperty('--lp', lp.toFixed(3));
      el.classList.toggle('lit', lp > 0);
    });
    hv.style.transform = `translateX(${(q * (track.clientWidth - 8)).toFixed(1)}px)`;
    hv.style.opacity = q > 0 && q < 1 ? '1' : '0';
  };
  addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(update); }, { passive: true });
  addEventListener('resize', update);
  MOBILE.addEventListener('change', update);
  update();
}

/* 数字从 0 滚到终值（同 App 的 useCountUp：0.9s easeOutQuart） */
function countUp(el, to, fmtFn, ms = 900) {
  const t0 = performance.now();
  const step = now => {
    const k = Math.min((now - t0) / ms, 1);
    el.textContent = fmtFn(to * (1 - Math.pow(1 - k, 4)));
    if (k < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
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
   首屏行情板：同 App 首页四类各两只，价格数位滚动，慢速随机漂移（示例数据）
   ============================================================ */
let marketTimer = 0;

/* 一位数字的带子：0–9 竖排，start 是起始停在哪一位（滚到 data-d 那一位由调用方写 transform） */
const stripHtml = (d, start, delay = 0) => `<span class="dg"><span class="dstrip" data-d="${d}" style="transform:translateY(-${start}em);transition-delay:${delay}ms">${'0123456789'.split('').map(x => `<i>${x}</i>`).join('')}</span></span>`;
const randDigit = () => Math.floor(Math.random() * 10);

/* 数字 → 数位带子：数字字符变成 0–9 竖排带，逗号小数点原样 */
function digitsHtml(v, dec, from) {
  const s = v.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
  return s.split('').map((ch, i) => /\d/.test(ch) ? stripHtml(ch, from ? randDigit() : ch, i * 40) : `<span class="dc">${ch}</span>`).join('');
}
/* 只滚数位，不重建节点；位数变了才整体重画 */
function rollTo(priceEl, v, dec) {
  const s = v.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
  const strips = priceEl.querySelectorAll('.dstrip');
  const digits = s.replace(/[^\d]/g, '');
  if (strips.length !== digits.length) { priceEl.innerHTML = digitsHtml(v, dec, false); return; }
  strips.forEach((st, i) => { st.style.transitionDelay = '0ms'; st.style.transform = `translateY(-${digits[i]}em)`; });
}
function renderMarket() {
  const mount = document.getElementById('mount-market');
  clearInterval(marketTimer);
  if (!mount) return;
  if (MOBILE.matches) { mount.innerHTML = ''; return; }
  const state = new Map();   // code -> { price, base }
  mount.innerHTML = `<div class="mk-h"><h2>${esc(T(COPY.marketTitle))}</h2><span>${esc(T(COPY.marketTag))}</span></div>
  <div class="mk-grid">${MARKET.map(cat => `<div>
    <div class="mk-cat">${esc(T(cat.title))}<small>${esc(T(cat.sub))}</small><a href="${cat.to}">${LANG === 'zh' ? '全部' : 'All'}</a></div>
    ${cat.rows.map(r => {
      // 涨跌基准价由现价和涨跌幅倒推，之后漂移都按它重算
      state.set(r.code, { price: r.price, base: r.price / (1 + r.chg / 100) });
      return `<div class="mk-row num" data-code="${r.code}">
        <span class="sym"><b>${esc(T(r.name))}</b><small>${esc(T(r.pair))}</small></span>
        <span class="mk-price">${digitsHtml(r.price, 2, !SHOT && !REDUCED)}</span>
        <span class="mk-chg ${r.chg >= 0 ? 'up' : 'dn'}">${signed(r.chg)}%</span>
      </div>`;
    }).join('')}
  </div>`).join('')}</div>`;
  if (SHOT || REDUCED) return;
  // 首帧从随机位滚到真实值
  requestAnimationFrame(() => requestAnimationFrame(() => {
    mount.querySelectorAll('.dstrip').forEach(st => { st.style.transform = `translateY(-${st.dataset.d}em)`; });
  }));
  // 慢速漂移：几秒挑一行动一下。全是编的，页面上标着示例数据
  const rnd = mockSeeded(Date.now() & 0xffff);
  marketTimer = setInterval(() => {
    if (document.hidden) return;
    const rows = [...mount.querySelectorAll('.mk-row')];
    const row = rows[Math.floor(rnd() * rows.length)];
    const st = state.get(row.dataset.code);
    st.price = Math.round(st.price * (1 + (rnd() - .5) * .005) * 100) / 100;   // ±0.25%
    const chg = (st.price - st.base) / st.base * 100;
    rollTo(row.querySelector('.mk-price'), st.price, 2);
    const c = row.querySelector('.mk-chg');
    c.textContent = signed(chg) + '%';
    c.className = 'mk-chg ' + (chg >= 0 ? 'up' : 'dn');
  }, 2600);
}

/* ============================================================
   模拟看板：照 App 的 trader 详情页
   记分牌 → 六格仪表条 → 左主栏（净值 + 时间线）‖ 右侧栏（持仓 + 计划 + 两份笔记）
   ============================================================ */
const RANGES = [{ id: 3, k: 'r3' }, { id: 7, k: 'r7' }, { id: 0, k: 'rAll' }];
let dashRange = 3, dashTab = 'timeline', dashOpen = false, dashCounted = false;

/* 北京时间的日期 / 时刻；轴上的日期跟界面语言走 */
const bj = t => new Date(t + 8 * HOUR);
const hhmm = t => `${String(bj(t).getUTCHours()).padStart(2, '0')}:00`;
const axDay = t => new Date(t).toLocaleDateString(LANG === 'zh' ? 'zh-CN' : 'en-US', { month: 'long', day: 'numeric', timeZone: 'Asia/Shanghai' });
const curveWindow = () => dashRange === 0 ? MOCK.curve : MOCK.curve.slice(-(dashRange * 24 + 1));

function renderDash() {
  const mount = document.getElementById('mount-dash');
  if (!mount) return;
  if (MOBILE.matches) { mount.innerHTML = ''; return; }
  const tr = MOCK.trader;
  // 区间锚在曲线末点：3 天 = 最后 72 小时
  const win = curveWindow();
  const delta = win[win.length - 1].v - win[0].v;
  const deltaPct = delta / win[0].v * 100;
  const rangeLabel = dashRange === 0 ? T(DL.rangeAll) : fmt(DL.rangeDays, { n: dashRange });

  mount.innerHTML = `
  <div class="dash">
    <div class="score">
      <div class="who">
        <b class="cond">${esc(tr.name)}</b>
        <span class="chip up"><i class="dot pulse"></i>${esc(T(DL.running))}</span>
        <span class="m">${esc(tr.model)} · ${tr.interval} · ${tr.wakeWindow} · ${fmt(DL.roundN, { n: tr.round })}</span>
      </div>
      <div class="ret num">
        <b class="cond ${tr.pnlPct >= 0 ? 'up' : 'dn'}">${signed(tr.pnlPct)}%</b>
        <div class="eq"><span>${esc(T(DL.equity))} <b>${fnum(tr.equity)}</b></span><span>${esc(T(DL.initial))}</span></div>
      </div>
    </div>
    <div class="strip num">
      <div><div class="k">${T(DL.thisRound)}</div><div class="v">${fmt(DL.dayN, { n: tr.day })}</div></div>
      <div><div class="k">${T(DL.closed)}</div><div class="v">${fmt(DL.tradesN, { n: tr.closed })}</div></div>
      <div><div class="k">${T(DL.winRate)}</div><div class="v">${tr.winRate}%</div></div>
      <div><div class="k">${T(DL.maxDd)}</div><div class="v dn">${tr.maxDd.toFixed(2)}%</div></div>
      <div><div class="k">${rangeLabel}</div><div class="v ${delta >= 0 ? 'up' : 'dn'}">${signed(delta)} · ${signed(deltaPct)}%</div></div>
      <div><div class="k">${T(DL.tokToday)}</div><div class="v">${ftok(tr.tokensToday)}</div></div>
    </div>
    <div class="dash-grid">
      <div class="dash-main">
        <div>
          <div class="blk-h">
            <h2>${T(DL.curve)}</h2>
            <div class="seg">${RANGES.map(r => `<button type="button" class="${r.id === dashRange ? 'on' : ''}" data-range="${r.id}">${r.id === 0 ? T(DL.rangeAll) : fmt(DL.rangeDays, { n: r.id })}</button>`).join('')}</div>
          </div>
          <div class="eq-chart" id="eq-chart"></div>
        </div>
        <div id="dash-tl">
          <div class="tabs">
            <button type="button" class="tab${dashTab === 'timeline' ? ' on' : ''}" data-tab="timeline">${T(DL.timeline)}</button>
            <button type="button" class="tab${dashTab === 'trades' ? ' on' : ''}" data-tab="trades">${T(DL.trades)}<span class="chip mute num">${MOCK.trades.length}</span></button>
          </div>
          <div>${dashTab === 'timeline' ? MOCK.decisions.map(renderDecision).join('') : MOCK.trades.map(renderTrade).join('')}</div>
        </div>
      </div>
      <div class="dash-side">
        <div>
          <div class="blk-h"><h2>${T(DL.positions)}</h2></div>
          ${renderPositions()}
        </div>
        <div>
          <div class="blk-h"><h2>${T(DL.plans)}<small>${fmt(DL.plansActive, { n: MOCK.plans.length })}</small></h2></div>
          ${MOCK.plans.map(pl => renderPlan(pl)).join('')}
        </div>
        <div>
          <div class="blk-h"><h2>${T(DL.notes)}</h2></div>
          ${renderNote(T(DL.memory), MOCK.memory)}
          ${renderNote(T(DL.learning), MOCK.learning)}
        </div>
      </div>
    </div>
    <div class="dash-more"><button type="button" class="btn sm" data-fold><svg data-icon="chevrons-up"></svg>${esc(T(DL.fold))}</button></div>
    <div class="dash-fold"><button type="button" class="btn fill" data-unfold>${esc(T(DL.unfold))}<svg data-icon="chevrons-down"></svg></button></div>
  </div>`;
  icons(mount);
  drawEquity(document.getElementById('eq-chart'), win);
  setupFold(mount.querySelector('.dash'));
  // 收益率和权益第一次进视口时从 0 滚上来；之后切区间/tab 重画就直接给终值
  if (!dashCounted && !REDUCED && !SHOT) {
    const pctEl = mount.querySelector('.ret > b'), eqEl = mount.querySelector('.ret .eq b');
    pctEl.textContent = signed(0) + '%'; eqEl.textContent = fnum(0);
    const io = new IntersectionObserver(es => {
      if (!es[0].isIntersecting) return;
      io.disconnect(); dashCounted = true;
      countUp(pctEl, tr.pnlPct, v => signed(v) + '%');
      countUp(eqEl, tr.equity, v => fnum(v));
    }, { threshold: .4 });
    io.observe(mount.querySelector('.score'));
  }

  mount.querySelectorAll('[data-range]').forEach(b => b.addEventListener('click', () => { dashRange = Number(b.dataset.range); renderDash(); }));
  mount.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => { dashTab = b.dataset.tab; renderDash(); }));
  // 已了结交易里的开/平仓决策：切回时间线，那两条就在上面
  mount.querySelectorAll('[data-jump]').forEach(b => b.addEventListener('click', () => { dashTab = 'timeline'; renderDash(); }));
  mount.querySelectorAll('.note-row').forEach(b => b.addEventListener('click', () => {
    const body = b.nextElementSibling;
    b.classList.toggle('open') ? slideOpen(body) : slideClose(body);
  }));
  mount.querySelectorAll('[data-more]').forEach(b => b.addEventListener('click', () => {
    const box = b.closest('.dec').querySelector('.reason'), full = box.querySelector('.full');
    const open = box.classList.toggle('open');
    open ? slideOpen(full) : slideClose(full);
    b.textContent = T(open ? DL.collapse : DL.expand);
  }));
}

/* 折叠线压在时间线的 tab 下面：折起时露出记分牌、仪表条、净值曲线和 tab 那一行 */
function setupFold(dash) {
  const tl = dash.querySelector('#dash-tl');
  const fold = dash.querySelector('.dash-fold'), more = dash.querySelector('.dash-more');
  const foldH = () => tl.offsetTop + tl.querySelector('.tabs').offsetHeight + fold.offsetHeight + 8;
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

/* 持仓 / 挂单：侧栏塞不下六列，压成四列 + 副行（数量下开仓价、止损下止盈、浮盈下百分比）；挂单整行浅底 */
function renderPositions() {
  const rows = MOCK.positions.map(p => {
    const long = p.side === 'LONG', up = p.upnl >= 0;
    const pct = p.upnl / (p.entry * p.qty / p.lev) * 100;   // 按保证金算的收益率
    return `<tr>
      <td><b>${p.symbol}</b><span class="sub ${long ? 'up' : 'dn'}">${T(long ? DL.long : DL.short)} ${p.lev}x</span></td>
      <td class="r">${p.qty}<span class="sub">${T(DL.entryAt)} ${fnum(p.entry)}</span></td>
      <td class="r"><span class="dn">${fnum(p.sl)}</span><span class="sub up">${fnum(p.tp)}</span></td>
      <td class="r"><b class="${up ? 'up' : 'dn'}">${signed(p.upnl)}</b><span class="sub ${up ? 'up' : 'dn'}">${signed(pct)}%</span></td>
    </tr>`;
  });
  const orders = MOCK.orders.map(o => {
    const long = o.side.includes('LONG');
    return `<tr class="order">
      <td><b>${o.symbol}</b><span class="sub">${T(DL.limitOrder)}</span></td>
      <td class="r">${o.qty}<span class="sub ${long ? 'up' : 'dn'}">${T(long ? DL.openLong : DL.openShort)} ${o.lev}x</span></td>
      <td class="r" colspan="2">${fnum(o.limit)}<span class="sub">${T(DL.limitPrice)}</span></td>
    </tr>`;
  });
  return `<table class="ptbl num">
    <thead><tr><th>${T(DL.colSym)}</th><th class="r">${T(DL.colQty)}</th><th class="r">${T(DL.colSlTp)}</th><th class="r">${T(DL.colUpnl)}</th></tr></thead>
    <tbody>${rows.join('')}${orders.join('')}</tbody>
  </table>`;
}

/* 交易计划：币对/方向/立的时刻 → 玩法 + 依据 → 失效条件 → 原始价位 → 修订史；embedded=挂在已了结交易里，虚线接上 */
function renderPlan(pl, embedded) {
  const long = pl.side === 'LONG';
  const px = [[DL.entry, pl.entry], [DL.origSl, pl.sl], [DL.target, pl.tp]].filter(([, v]) => v != null);
  const revs = pl.revisions || [];
  return `<div class="plan${embedded ? ' embedded' : ''}">
    <div class="plan-h"><b>${pl.symbol}</b><span class="chip fill ${long ? 'up' : 'dn'}">${T(long ? DL.long : DL.short)}</span><span class="num">${fmt(DL.setAt, { t: pl.setAt })}</span></div>
    <div class="basis"><b>${esc(pl.play)}</b>${pl.basis ? esc(T(DL.basis) + T(pl.basis)) : ''}</div>
    <div class="inv"><b>${T(DL.invalidation)}</b>${esc(T(pl.invalidation))}</div>
    ${px.length ? `<div class="px num">${px.map(([k, v]) => `<span>${T(k)}<b>${fnum(v)}</b></span>`).join('')}</div>` : ''}
    ${revs.length ? `<div class="rev">${revs.map((r, i) => `<p>${i === 0 ? fmt(DL.revisions, { n: revs.length }) + ' · ' : ''}<span class="num">${r.time}</span> ${r.type} <span class="num">${r.change}</span>，${esc(T(r.reason))}</p>`).join('')}</div>` : ''}
  </div>`;
}

/* 笔记一行：标题 + 首句预览 + 最近时间，点开才铺全文 */
function renderNote(title, note) {
  const text = T(note);
  const preview = text.replace(/[#*`>_-]/g, '').replace(/\s+/g, ' ').slice(0, 120);
  return `<button type="button" class="note-row"><b>${esc(title)}</b><span class="pv">${esc(preview)}</span><em class="num">${fmt(DL.lastAt, { t: note.time })}</em></button>
    <div class="note-body"><div>${esc(text)}</div></div>`;
}

/* 推理全文：[ETHUSDT] 独占一行的分段标记渲染成小芯片，芯片自己占一行，正文从下一行起 */
function renderReason(text) {
  return text.split('\n').map(line => {
    const m = /^\s*\[([A-Z0-9]{2,20})\]\s*$/.exec(line);
    return m ? `<span class="chip mute num">${m[1]}</span>` : esc(line);
  }).join('\n');
}

/* 决策卡：徽章/时间/权益/遥测 → 看了什么 → 交易动作（参数/拒因）→ 推理折叠；复盘/学习左边一道色线，正文直接铺开 */
function renderDecision(d) {
  const log = d.kind === 'REVIEW' || d.kind === 'LEARN';
  // 学习行的正文就是那份学习笔记
  const text = d.kind === 'LEARN' ? T(MOCK.learning) : T(d.reasoning);
  const chips = log
    ? `<span class="chip ${d.kind === 'REVIEW' ? 'rv' : 'ln'}"><svg data-icon="${d.kind === 'REVIEW' ? 'notebook-pen' : 'graduation-cap'}"></svg>${esc(T(d.kind === 'REVIEW' ? DL.review : DL.learn))}</span>`
    : `<span class="chip">${esc(T(DL.decision))}</span>${d.kind === 'ALERT' ? `<span class="chip wn">${esc(T(DL.alert))}</span>` : ''}`;
  // 复盘是单次调用不挂工具，不显示"0次工具"
  const meta = [`${ftok(d.tokens)} tok`, `${d.latency.toFixed(1)}s`, d.kind !== 'REVIEW' && fmt(DL.toolCalls, { n: d.toolCalls }), fmt(DL.modelCalls, { n: d.modelCalls })].filter(Boolean).join(' · ');
  const looked = d.looked.map(k => `<b>${esc(toolName(k))}</b>`).join(' · ');
  const acts = d.actions.map(a => `<div class="dec-act${a.rejected ? ' bad' : ''}">
      <b>${esc(toolName(a.tool))}${a.rejected ? esc(T(DL.rejected)) : ''}</b>
      <span>${esc(T(a.args))}${a.rejected ? `<span class="why">${esc(T(a.rejected))}</span>` : ''}</span>
    </div>`).join('');
  return `<div class="dec${log ? ' log' : ''}${d.kind === 'LEARN' ? ' learn' : ''}">
    <div class="dec-h">${chips}<span class="num">${d.time}</span><span class="eqv">${T(DL.equity)} <b class="num">${fnum(d.equity)}</b></span><span class="meta num">${meta}</span></div>
    ${looked ? `<div class="dec-looked">${T(DL.lookedAt)} ${looked}</div>` : ''}
    ${acts ? `<div class="dec-acts">${acts}</div>` : ''}
    ${log
      ? `<div class="reason log">${esc(text)}</div>`
      : `<div class="reason"><div class="prev">${esc(text.replace(/\[[A-Z0-9]+\]\s*/g, '').replace(/\s+/g, ' '))}</div><div class="full">${renderReason(text)}</div></div>
         <div class="links"><button type="button" class="ulink" data-more>${esc(T(DL.expand))}</button></div>`}
  </div>`;
}

/* 已了结交易：头行（币种·多空·了结方式·入场→出场·盈亏）→ 开/平时刻 → 计划 → 开/平仓决策跳转 */
function renderTrade(r) {
  const long = r.side === 'LONG';
  const manner = { takeProfit: 'up', stopLoss: 'dn', manual: '' }[r.manner];
  const plan = { symbol: r.symbol, side: r.side, setAt: r.opened, play: r.play, invalidation: r.invalidation };
  return `<div class="tc">
    <div class="tc-h num">
      <b>${r.symbol}</b>
      <span class="chip fill ${long ? 'up' : 'dn'}">${T(long ? DL.long : DL.short)} ${r.lev}x</span>
      <span class="chip ${manner}">${esc(T(DL.manner[r.manner]))}</span>
      <span class="px">${fnum(r.entry)} → ${fnum(r.exit)}</span>
      <b class="pnl ${r.pnl >= 0 ? 'up' : 'dn'}">${signed(r.pnl)}</b>
    </div>
    <div class="tc-times num"><span>${fmt(DL.openedAt, { t: r.opened })}</span><span>${fmt(DL.closedAt, { t: r.closed })}</span><span>${fmt(DL.held, { d: r.held })}</span></div>
    ${renderPlan(plan, true)}
    <div class="tc-links">
      <button type="button" class="btn xs" data-jump><span class="num">${T(DL.openDecision)} · ${r.opened}</span><svg data-icon="arrow-right"></svg></button>
      <button type="button" class="btn xs" data-jump><span class="num">${T(DL.closeDecision)} · ${r.closed}</span><svg data-icon="arrow-right"></svg></button>
      ${r.reason ? `<span class="mute">—— ${esc(T(r.reason))}</span>` : ''}
    </div>
  </div>`;
}

/* 单调保形平滑曲线（Fritsch–Carlson，与 App lib/smoothPath 同算法）：不过冲，不凭空画出不存在的高低点 */
function smoothPath(ys, x0, step) {
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
  let p = `M${x0.toFixed(2)} ${ys[0].toFixed(2)}`;
  for (let i = 0; i < n - 1; i++) {
    const xa = x0 + i * step, xb = x0 + (i + 1) * step;
    p += ` C${(xa + step / 3).toFixed(2)} ${(ys[i] + m[i] / 3).toFixed(2)},${(xb - step / 3).toFixed(2)} ${(ys[i + 1] - m[i + 1] / 3).toFixed(2)},${xb.toFixed(2)} ${ys[i + 1].toFixed(2)}`;
  }
  return p;
}

/* 净值曲线（同 App EquityCurve）：墨线 + 5% 墨面积 + 初始资金虚线；末值挂在末点下方，贴底就翻到上方 */
function drawEquity(host, pts) {
  if (!host) return;
  const seed = MOCK.trader.seed, H = 280, PAD = 8, TAG_H = 38, TAG_GAP = 24;
  const W = host.clientWidth;
  if (!W || pts.length < 2) return;
  const vals = pts.map(p => p.v);
  const min = Math.min(...vals, seed), max = Math.max(...vals, seed);
  const y = v => PAD + (H - 2 * PAD) * (1 - (v - min) / (max - min || 1));
  const step = (W - 2 * PAD) / (pts.length - 1);
  const ys = vals.map(y);
  const line = smoothPath(ys, PAD, step);
  const xEnd = PAD + step * (pts.length - 1);
  const yBase = y(seed), yLast = ys[ys.length - 1], last = pts[pts.length - 1];
  const tagTop = yLast + TAG_GAP + TAG_H > H ? yLast - TAG_GAP - TAG_H : yLast + TAG_GAP;
  // 基准线贴顶时标签放线上方会出界，改挂线下方
  const baseTop = yBase - 18 >= 0 ? yBase - 18 : yBase + 6;
  host.innerHTML = `<svg class="eq-reveal" viewBox="0 0 ${W} ${H}">
      <path d="${line} L${xEnd.toFixed(2)} ${H} L${PAD} ${H} Z" fill="var(--fg)" fill-opacity=".05"/>
      <line x1="0" x2="${W}" y1="${yBase.toFixed(2)}" y2="${yBase.toFixed(2)}" stroke="var(--muted-fg)" stroke-dasharray="2 5"/>
      <path d="${line}" fill="none" stroke="var(--fg)" stroke-width="2"/>
    </svg>
    <span class="lbl eq-late" style="left:0;top:${baseTop.toFixed(0)}px">${esc(T(DL.initial))}</span>
    <div class="tag eq-late num" style="top:${tagTop.toFixed(0)}px"><b>${fnum(last.v)}</b><span>${fmt(DL.lastWake, { t: hhmm(last.t) })}</span></div>
    <span class="lbl" style="left:0;bottom:-20px">${axDay(pts[0].t)}</span>
    <span class="lbl" style="right:0;bottom:-20px">${axDay(last.t)}</span>`;
}
let eqTimer = 0;
addEventListener('resize', () => {
  clearTimeout(eqTimer);
  eqTimer = setTimeout(() => drawEquity(document.getElementById('eq-chart'), curveWindow()), 150);
});

/* ---------- 装配 ---------- */
let navPlace = () => { };
function boot() {
  fillCopy();
  renderStrip();
  renderLoop();
  renderMarket();
  renderDash();
  // 手机只放文字：面板不挂，也省掉动画开销
  if (MOBILE.matches) { stopFlowPanels(); } else { mountFlowPanels(); }
  icons();
  // 导航文案换了语言宽度就变，橙方块重新对位
  navPlace();
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
navPlace = setupNav();
boot();
startSmoke();
watchReveal();
watchDraw();
setupScrollBits();

// 逐字排版：先拆字；字体到了再起跳（Archivo 没到时字宽不对，升起来会跳一下），最多等 0.8s
const heroH = document.querySelector('.hero-h'), footWord = document.querySelector('.foot-word');
splitLetters(heroH);
splitLetters(footWord);
Promise.race([document.fonts ? document.fonts.ready : Promise.resolve(), new Promise(r => setTimeout(r, 800))]).then(() => {
  navPlace();
  fitFootWord();
  requestAnimationFrame(() => riseLetters(heroH, true));
  stretchOnHover(heroH, heroH.closest('.hero-top'));
});
// 页脚字标进视口才升起
new IntersectionObserver((es, io) => {
  if (es[0].isIntersecting) { riseLetters(footWord, false); io.disconnect(); }
}, { rootMargin: '0px 0px -10% 0px' }).observe(footWord);
addEventListener('resize', fitFootWord);
