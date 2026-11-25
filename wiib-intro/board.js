'use strict';
/* ============================================================
   intro.wtfibought.com · 示例数据那两块：首屏下面的行情板、01 的 trader 看板
   依赖 flow.js 顶上的全站共用段（LANG · T · esc · fmt · fnum · signed · 新加坡时间 · pnlHtml · putText · MOBILE · SHOT · REDUCED · 数位滚动 · ICONS · icons）
   与 mock-data.js（MOCK · MARKET · MOCK_NOW · HOUR · mockSeeded）；renderMarket / renderDash 由 intro.js 的 boot 调
   看板照主站 trader 工作台（ArenaDetail + components/arena），类名尽量同主站，样式在 board.css
   ============================================================ */

/* 全站图标表里没有、看板要用的几个（lucide 0.563） */
Object.assign(ICONS, {
  'chevron-left': '<path d="m15 18-6-6 6-6"/>',
  'lock': '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  'circle-help': '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>'
});

/* 看板词表（叫法同主站 i18n ai.json / home.json）；en 写成 [单数, 复数] 的走 fmtN */
const DL = {
  market: { zh: '市场', en: 'Markets' },
  sample: { zh: '示例数据 · 非实时', en: 'Sample data · not live' },
  all: { zh: '全部', en: 'All' },
  back: { zh: '返回竞技场', en: 'Back to arena' },
  running: { zh: '运行中', en: 'Running' },
  meta: {
    beat: { zh: '心跳 {iv}', en: 'heartbeat {iv}' }, window: { zh: '{win} 醒', en: 'awake {win}' }, round: { zh: '第 {n} 局', en: 'round {n}' }
  },
  equity: { zh: '权益', en: 'Equity' },
  initial: { zh: '初始 10,000', en: 'starts at 10,000' },
  hb: {
    beat: { zh: '心跳', en: 'Heartbeat' },
    beatWin: { zh: '{from}–{to} 醒 · 每天 {n} 次', en: 'awake {from}–{to} · {n} a day' },
    next: { zh: '下次心跳', en: 'Next heartbeat' },
    last: { zh: '上次醒来', en: 'Last wake' },
    watch: { zh: '盯盘', en: 'Watching' },
    watchN: { zh: '{n} 条', en: '{n}' },
    watchMix: { zh: '看价格 {p} · 看时间 {t}', en: 'price {p} · time {t}' }
  },
  left: {
    hm: { zh: '还有 {h} 小时 {m} 分', en: 'in {h}h {m}m' }, ms: { zh: '还有 {m}:{s}', en: 'in {m}:{s}' }, m: { zh: '还有 {m} 分钟', en: 'in {m} min' }
  },
  thisRound: { zh: '本局', en: 'This round' },
  dayN: { zh: '第 {n} 天', en: 'Day {n}' },
  closedCount: { zh: '做完的交易', en: 'Closed trades' },
  tradesN: { zh: '{n} 笔', en: ['{n} trade', '{n} trades'] },
  winRate: { zh: '胜率', en: 'Win rate' },
  maxDd: { zh: '最大回撤', en: 'Max drawdown' },
  tokensOf: { zh: '{scope} TOKEN', en: '{scope} tokens' },
  rangeDays: { zh: '{n}天', en: '{n}d' },
  rangeAll: { zh: '全部', en: 'All' },
  curve: {
    title: { zh: '{round}净值', en: '{round} equity' },
    base: { zh: '初始 {v}', en: 'start {v}' },
    vsBase: { zh: '比初始 {diff} · {pct}', en: '{diff} · {pct} vs start' },
    aria: { zh: '净值曲线，左右方向键逐点查看', en: 'Equity curve. Use the left and right arrow keys to step through points' },
    open: { zh: '开 {coin} {side}', en: 'opened {coin} {side}' },
    close: { zh: '{coin} {how} {pnl}', en: '{coin} {how} {pnl}' }
  },
  timeline: { zh: '它的每次思考', en: 'Every wake' },
  tradesTitle: { zh: '做完的交易', en: 'Closed trades' },
  filter: {
    all: { zh: '全部', en: 'All' }, acted: { zh: '动过仓位的', en: 'Touched positions' }, woken: { zh: '被叫醒的', en: 'Woken up' },
    debrief: { zh: '单笔复盘', en: 'Debriefs' }, review: { zh: '统一复盘', en: 'Unified reviews' }
  },
  // 是什么叫醒它的（时间线上的标签）
  kind: { TRADE: { zh: '例行 · {iv}', en: 'Routine · {iv}' }, WATCH: { zh: '盯盘触发', en: 'Watch hit' }, ALERT: { zh: '剧烈波动', en: 'Sharp move' } },
  onlySymbol: { zh: '只处理 {s}', en: '{s} only' },
  debrief: { zh: '单笔复盘', en: 'Debrief' },
  review: { zh: '统一复盘', en: 'Unified review' },
  lookedAt: { zh: '看了', en: 'Read' },
  toolCalls: { zh: '{n}次工具', en: ['{n} tool call', '{n} tool calls'] },
  modelCalls: { zh: '{n}次模型', en: ['{n} model call', '{n} model calls'] },
  expandFull: { zh: '展开全文', en: 'Read all' },
  collapse: { zh: '收起', en: 'Collapse' },
  loadMore: { zh: '加载更早', en: 'Load earlier' },
  rejected: { zh: '·被拒', en: ' · rejected' },
  tool: {
    open_position: { zh: '开仓', en: 'Open position' }, close_position: { zh: '平仓', en: 'Close position' },
    set_stop_loss: { zh: '改止损', en: 'Change stop' }, set_watch: { zh: '盯盘条件', en: 'Watch conditions' },
    klines: { zh: 'K线', en: 'Candles' }, kline_structure: { zh: 'K线结构', en: 'Structure' }, indicators: { zh: '指标', en: 'Indicators' },
    market_snapshot: { zh: '市场快照', en: 'Market snapshot' }, funding_history: { zh: '资金费', en: 'Funding fee' },
    orderbook_depth: { zh: '盘口', en: 'Order book' },
    round_detail: { zh: '轮次详情', en: 'Round detail' }, news_archive: { zh: '历史快讯', en: 'News archive' }
  },
  // 交易动作那一行的参数
  args: {
    long: { zh: '做多', en: 'Long' }, short: { zh: '做空', en: 'Short' },
    qty: { zh: '{n}张', en: '{n} contracts' }, limit: { zh: '限价 {p}', en: 'Limit {p}' },
    sl: { zh: '止损{p}', en: 'SL {p}' }, tp: { zh: '止盈{p}', en: 'TP {p}' },
    watches: { zh: '{n} 条盯盘条件', en: ['{n} watch condition', '{n} watch conditions'] }
  },
  marketEntry: { zh: '市价', en: 'Market' },
  entryStatus: { filled: { zh: '已成交', en: 'Filled' }, pending: { zh: '待成交', en: 'Pending' } },
  long: { zh: '多', en: 'Long' }, short: { zh: '空', en: 'Short' },
  manner: { takeProfit: { zh: '止盈带走', en: 'taken by target' }, stopLoss: { zh: '止损带走', en: 'taken by stop' }, manual: { zh: '主动平仓', en: 'closed by hand' } },
  openedAt: { zh: '{t} 开', en: 'opened {t}' }, closedAt: { zh: '{t} 平', en: 'closed {t}' }, held: { zh: '持有 {d}', en: 'held {d}' },
  basis: { zh: '依据：{s}', en: 'Basis: {s}' },
  invalidation: { zh: '失效条件', en: 'Invalidation' },
  entry: { zh: '入场', en: 'Entry' }, origSl: { zh: '下单时止损', en: 'SL at entry' }, target: { zh: '目标', en: 'Target' },
  revisions: { zh: '修订 {n} 次', en: ['revised once', 'revised {n}×'] },
  revType: { moveStop: { zh: '移动止损', en: 'stop moved' } },
  openDecision: { zh: '开仓决策', en: 'Entry decision' }, closeDecision: { zh: '平仓决策', en: 'Exit decision' },
  debriefAfter: { zh: '平仓后 {d}', en: '{d} after the close' },
  moreTrades: { zh: '本局做完 {n} 笔，示例里只列出其中 {k} 笔', en: '{n} closed this round; the sample lists {k} of them' },
  dur: { dh: { zh: '{d}天{h}时', en: '{d}d {h}h' }, d: { zh: '{d}天', en: '{d}d' }, hm: { zh: '{h}时{m}分', en: '{h}h {m}m' }, h: { zh: '{h}时', en: '{h}h' } },
  hold: {
    title: { zh: '手上的仓位', en: 'Holdings' },
    positionsN: { zh: '{n} 个持仓', en: ['{n} position', '{n} positions'] }, ordersN: { zh: '{n} 个挂单', en: ['{n} order', '{n} orders'] },
    floating: { zh: '浮动 {v}', en: 'floating {v}' },
    long: { zh: '做多 {lev}×', en: 'Long {lev}×' }, short: { zh: '做空 {lev}×', en: 'Short {lev}×' },
    ofMargin: { zh: '占保证金 {pct}', en: '{pct} of margin' },
    qtyAt: { zh: '{qty} {coin} · 开在 {price}', en: '{qty} {coin} · entered at {price}' },
    now: { zh: '现价 {price}', en: 'now {price}' }, sl: { zh: '止损 {price}', en: 'stop {price}' }, tp: { zh: '止盈 {price}', en: 'target {price}' },
    trigger: { zh: '{move} 触发', en: '{move} to trigger' }, locked: { zh: '锁利润', en: 'locks in profit' },
    limitLong: { zh: '挂单 · 限价做多 {lev}×', en: 'Order · limit long {lev}×' }, limitShort: { zh: '挂单 · 限价做空 {lev}×', en: 'Order · limit short {lev}×' },
    notFilled: { zh: '还没成交', en: 'Not filled' },
    qtyLimit: { zh: '{qty} {coin} · 挂在 {price}', en: '{qty} {coin} · resting at {price}' },
    fillIn: { zh: '现价 {now}，{move} 就成交。', en: 'Now {now}; fills after {move}.' },
    afterFill: { zh: '成交后止损 {sl}、止盈 {tp}。', en: 'Then stop {sl}, target {tp}.' },
    why: { zh: '为什么开', en: 'Why' }, inval: { zh: '看错的标志', en: 'Wrong if' },
    revised: { zh: '改过 {n} 次', en: ['Revised once', 'Revised {n} times'] }
  },
  move: { up: { zh: '再涨 {pct}%', en: 'up {pct}%' }, down: { zh: '再跌 {pct}%', en: 'down {pct}%' } },
  watch: {
    title: { zh: '它的盯盘', en: 'Its watches' },
    sum: { zh: '{n} 条 · 离得近的排前面', en: '{n} · nearest first' },
    desc: {
      zh: '它给自己定的盯盘条件：价格到了、K 线收在某个价位、或者到了某个时间，就会把它叫醒。醒来它先核实是不是真的到了，再按当时写的备注处理。带预案的条件命中时，平台先替它把动作做掉（移止损、平仓或撤单）。',
      en: 'Conditions it set for itself: a price, a candle closing past a level, or a set time wakes it. It first checks the condition is real, then acts on the note it left. When a condition carrying a standing order fires, the platform carries the action out first (move a stop, close, or cancel an order).'
    },
    preset: { zh: '预案：命中后自动市价平掉 {target} 的 {pct}%，再叫醒它', en: 'Standing order: when it fires, {pct}% of {target} is closed at market, then it is woken' },
    soon: { zh: '快到了', en: 'close' },
    note: { zh: '备注：{v}', en: 'Note: {v}' },
    setAt: { zh: '{time} 定的', en: 'set {time}' },
    touchBelow: { zh: '价格跌到 {price}', en: 'price falls to {price}' },
    closeAbove: { zh: '{period} K 线收在 {price} 以上', en: '{period} candle closes above {price}' },
    closeBelow: { zh: '{period} K 线收在 {price} 以下', en: '{period} candle closes below {price}' },
    at: { zh: '到 {time}', en: 'at {time}' }
  },
  period: { '15m': { zh: '15 分钟', en: '15m' }, '4h': { zh: '4 小时', en: '4h' } },
  help: { zh: '说明', en: 'About this' },
  notesTitle: { zh: '笔记', en: 'Notes' },
  memoryShort: { zh: '经验账本', en: 'Ledger' },
  lastAt: { zh: '最近 {time}', en: 'Last {time}' },
  unfold: { zh: '展开完整看板', en: 'Expand the board' },
  fold: { zh: '收起看板', en: 'Collapse the board' }
};

/* ---------- 小工具 ---------- */
/* 带数量的词条：英文按单复数挑 */
function fmtN(o, n, vars) {
  const s = LANG === 'en' && Array.isArray(o.en) ? o.en[n === 1 ? 0 : 1] : T(o);
  return s.replace(/\{(\w+)\}/g, (_, k) => k === 'n' ? n : vars[k]);
}
/* 时长：几天几时 / 几时几分（同主站 fmtDuration） */
function fmtDur(ms) {
  const min = Math.floor(ms / 60000), d = Math.floor(min / 1440), h = Math.floor(min / 60) % 24, m = min % 60;
  if (d > 0) return fmt(h > 0 ? DL.dur.dh : DL.dur.d, { d, h });
  return fmt(m > 0 ? DL.dur.hm : DL.dur.h, { h, m });
}
const fmtTok = n => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
/* 动作参数里的价：千分位，小数照原样 */
const rawPx = v => v.toLocaleString('en-US', { maximumFractionDigits: 8 });
const signedPct = n => `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;
/* 平仓回执里的盈亏：前面带 USDT 符号 */
const usdt = n => `${n < 0 ? '-₮' : '+₮'}${fnum(Math.abs(n))}`;
const coinName = s => s.replace('USDT', '');
/* 从现价走到 v 还要涨 / 跌多少 */
function moveText(v, mark) {
  const d = (v / mark - 1) * 100;
  return fmt(d >= 0 ? DL.move.up : DL.move.down, { pct: Math.abs(d).toFixed(2) });
}
/* 数字从 0 滚到终值（同主站 useCountUp：0.9s easeOutQuart），html 把数写成要显示的样子 */
function countUp(el, to, html) {
  const t0 = performance.now();
  const step = now => {
    const k = Math.min((now - t0) / 900, 1);
    el.innerHTML = html(to * (1 - Math.pow(1 - k, 4)));
    if (k < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

/* ---------- 币标：官方 logo 原样路径（同主站 lib/coinIcons），只有这三个 ---------- */
let coinSeq = 0;
const COIN_SVG = {
  BTCUSDT: () => ['0 0 64 64', '<path fill="#f7931a" d="m63.033,39.744c-4.274,17.143-21.637,27.576-38.782,23.301-17.138-4.274-27.571-21.638-23.295-38.78,4.272-17.145,21.635-27.579,38.775-23.305,17.144,4.274,27.576,21.64,23.302,38.784z"/><path fill="#fff" d="m46.103,27.444c0.637-4.258-2.605-6.547-7.038-8.074l1.438-5.768-3.511-0.875-1.4,5.616c-0.923-0.23-1.871-0.447-2.813-0.662l1.41-5.653-3.509-0.875-1.439,5.766c-0.764-0.174-1.514-0.346-2.242-0.527l0.004-0.018-4.842-1.209-0.934,3.75s2.605,0.597,2.55,0.634c1.422,0.355,1.679,1.296,1.636,2.042l-1.638,6.571c0.098,0.025,0.225,0.061,0.365,0.117-0.117-0.029-0.242-0.061-0.371-0.092l-2.296,9.205c-0.174,0.432-0.615,1.08-1.609,0.834,0.035,0.051-2.552-0.637-2.552-0.637l-1.743,4.019,4.569,1.139c0.85,0.213,1.683,0.436,2.503,0.646l-1.453,5.834,3.507,0.875,1.439-5.772c0.958,0.26,1.888,0.5,2.798,0.726l-1.434,5.745,3.511,0.875,1.453-5.823c5.987,1.133,10.489,0.676,12.384-4.739,1.527-4.36-0.076-6.875-3.226-8.515,2.294-0.529,4.022-2.038,4.483-5.155zm-8.022,11.249c-1.085,4.36-8.426,2.003-10.806,1.412l1.928-7.729c2.38,0.594,10.012,1.77,8.878,6.317zm1.086-11.312c-0.99,3.966-7.1,1.951-9.082,1.457l1.748-7.01c1.982,0.494,8.365,1.416,7.334,5.553z"/>'],
  ETHUSDT: () => ['0 0 1920 1920', '<path d="m959.8 80.7-539.7 895.6 539.7-245.3z" fill="#8a92b2"/><path d="m959.8 731-539.7 245.3 539.7 319.1z" fill="#62688f"/><path d="m1499.6 976.3-539.8-895.6v650.3z" fill="#62688f"/><path d="m959.8 1295.4 539.8-319.1-539.8-245.3z" fill="#454a75"/><path d="m420.1 1078.7 539.7 760.6v-441.7z" fill="#8a92b2"/><path d="m959.8 1397.6v441.7l540.1-760.6z" fill="#62688f"/>'],
  // 渐变 id 每个图标一份：同页几个 SOL 共用一个 id 时，第一个藏起来整组渐变会失效
  SOLUSDT: () => {
    const id = 'sol-g' + (++coinSeq);
    return ['0 -6.5 101 101', `<defs><linearGradient id="${id}" x1="8.52558" y1="90.0973" x2="88.9933" y2="-3.01622" gradientUnits="userSpaceOnUse"><stop offset="0.08" stop-color="#9945FF"/><stop offset="0.3" stop-color="#8752F3"/><stop offset="0.5" stop-color="#5497D5"/><stop offset="0.6" stop-color="#43B4CA"/><stop offset="0.72" stop-color="#28E0B9"/><stop offset="0.97" stop-color="#19FB9B"/></linearGradient></defs><path fill="url(#${id})" d="M100.48 69.3817L83.8068 86.8015C83.4444 87.1799 83.0058 87.4816 82.5185 87.6878C82.0312 87.894 81.5055 88.0003 80.9743 88H1.93563C1.55849 88 1.18957 87.8926 0.874202 87.6912C0.558829 87.4897 0.31074 87.2029 0.160416 86.8659C0.0100923 86.529 -0.0359181 86.1566 0.0280382 85.7945C0.0919944 85.4324 0.263131 85.0964 0.520422 84.8278L17.2061 67.408C17.5676 67.0306 18.0047 66.7295 18.4904 66.5234C18.9762 66.3172 19.5002 66.2104 20.0301 66.2095H99.0644C99.4415 66.2095 99.8104 66.3169 100.126 66.5183C100.441 66.7198 100.689 67.0067 100.84 67.3436C100.99 67.6806 101.036 68.0529 100.972 68.415C100.908 68.7771 100.737 69.1131 100.48 69.3817ZM83.8068 34.3032C83.4444 33.9248 83.0058 33.6231 82.5185 33.4169C82.0312 33.2108 81.5055 33.1045 80.9743 33.1048H1.93563C1.55849 33.1048 1.18957 33.2121 0.874202 33.4136C0.558829 33.6151 0.31074 33.9019 0.160416 34.2388C0.0100923 34.5758 -0.0359181 34.9482 0.0280382 35.3103C0.0919944 35.6723 0.263131 36.0083 0.520422 36.277L17.2061 53.6968C17.5676 54.0742 18.0047 54.3752 18.4904 54.5814C18.9762 54.7875 19.5002 54.8944 20.0301 54.8952H99.0644C99.4415 54.8952 99.8104 54.7879 100.126 54.5864C100.441 54.3849 100.689 54.0981 100.84 53.7612C100.99 53.4242 101.036 53.0518 100.972 52.6897C100.908 52.3277 100.737 51.9917 100.48 51.723L83.8068 34.3032ZM1.93563 21.7905H80.9743C81.5055 21.7907 82.0312 21.6845 82.5185 21.4783C83.0058 21.2721 83.4444 20.9704 83.8068 20.592L100.48 3.17219C100.737 2.90357 100.908 2.56758 100.972 2.2055C101.036 1.84342 100.99 1.47103 100.84 1.13408C100.689 0.79713 100.441 0.510296 100.126 0.308823C99.8104 0.107349 99.4415 1.24074e-05 99.0644 0L20.0301 0C19.5002 0.000878397 18.9762 0.107699 18.4904 0.313848C18.0047 0.519998 17.5676 0.821087 17.2061 1.19848L0.524723 18.6183C0.267681 18.8866 0.0966198 19.2223 0.0325185 19.5839C-0.0315829 19.9456 0.0140624 20.3177 0.163856 20.6545C0.31365 20.9913 0.561081 21.2781 0.875804 21.4799C1.19053 21.6817 1.55886 21.7896 1.93563 21.7905Z"/>`];
  }
};
function coinIcon(symbol) {
  const [box, body] = COIN_SVG[symbol]();
  return `<svg class="ci" viewBox="${box}" aria-hidden="true">${body}</svg>`;
}

/* ============================================================
   首屏行情板：同主站首页「市场」，四类各两只，价格数位滚动，慢速随机漂移（示例数据）
   ============================================================ */
/* 行情板和看板共用的一份示例报价：code → { price 现价, start 示例价, base 算涨跌幅的昨收 }，同一个币两处是同一个价；切语言重画接着用 */
const QUOTES = new Map(MARKET.flatMap(c => c.rows).map(r => [r.code, { price: r.price, start: r.price, base: r.price / (1 + r.chg / 100) }]));
const quoteChg = q => (q.price - q.base) / q.base * 100;
let marketTimer = 0;

function paintChg(el, q) {
  const c = quoteChg(q);
  el.className = c >= 0 ? 'up' : 'dn';
  putText(el, signedPct(c));
}
/* 把一个币的示例价漂一下（幅度 ±k/2），离示例价最多 ±0.6%，漂不到看板上的止损、挂单和盯盘价；行情板那一行跟着滚 */
function nudgeQuote(code, k, rnd) {
  const q = QUOTES.get(code), v = q.price * (1 + (rnd() - .5) * k);
  q.price = Math.round(Math.min(q.start * 1.006, Math.max(q.start * .994, v)) * 100) / 100;
  const row = document.querySelector(`#mount-market .q[data-code="${code}"]`);
  rollTo(row.querySelector('i'), q.price, 2);
  paintChg(row.querySelector('em'), q);
}

function renderMarket() {
  const mount = document.getElementById('mount-market');
  clearInterval(marketTimer);
  if (MOBILE.matches) { mount.innerHTML = ''; return; }
  const roll = !SHOT && !REDUCED;
  mount.innerHTML = `<div class="mkt">
    <div class="sec-h"><h2>${esc(T(DL.market))}<small>${esc(T(DL.sample))}</small></h2></div>
    <div class="mk">${MARKET.map(cat => `<div>
      <h3>${esc(T(cat.title))}<span>${esc(T(cat.sub))}</span><a class="go" href="${cat.to}">${esc(T(DL.all))}<svg data-icon="chevron-right"></svg></a></h3>
      ${cat.rows.map(r => {
        const q = QUOTES.get(r.code), c = quoteChg(q);
        return `<a class="q" href="${r.to}" data-code="${r.code}"><b>${esc(T(r.name))}<span>${esc(T(r.pair))}</span></b><i>${digitsHtml(q.price, 2, roll)}</i><em class="${c >= 0 ? 'up' : 'dn'}">${signedPct(c)}</em></a>`;
      }).join('')}
    </div>`).join('')}</div>
  </div>`;
  icons(mount);
  if (!roll) return;
  // 首帧从随机位滚到真实值
  requestAnimationFrame(() => requestAnimationFrame(() => {
    mount.querySelectorAll('.dstrip').forEach(st => { st.style.transform = `translateY(-${st.dataset.d}em)`; });
  }));
  // 慢速漂移：几秒挑一行动一下
  const rnd = mockSeeded(Date.now() & 0xffff), codes = [...QUOTES.keys()];
  marketTimer = setInterval(() => {
    if (!document.hidden) nudgeQuote(codes[Math.floor(rnd() * codes.length)], .005, rnd);
  }, 2600);
}

/* ============================================================
   01 的 trader 看板：带地址栏的窗口框，里面照主站 trader 工作台
   页头 → 心跳块 → 记分条 → 左栏（净值 + 时间线）‖ 右栏（仓位 + 盯盘 + 笔记）；窄桌面单栏，时间线压到最后
   默认折起，只露到页签那一行（单栏时露到净值曲线）
   ============================================================ */
const RANGES = [3, 7, 0];
const FILTERS = ['all', 'acted', 'woken', 'debrief', 'review'];
/* 例行心跳之外的唤醒 */
const WOKEN_KINDS = new Set(['WATCH', 'EVENT', 'ALERT', 'DRAWDOWN', 'MANUAL']);
/* 叫醒原因的标签色：例行灰、盯盘触发橙、剧烈波动黄；决策左边圆点同色 */
const KIND_TONE = { TRADE: '', WATCH: 'o', ALERT: 'w' };
const MANNER_TONE = { takeProfit: 'g', stopLoss: 'r', manual: '' };
/* 不算「动过仓位」的交易动作：布盯盘 */
const NOT_ACTING = new Set(['set_watch']);
/* 推理超过这么多字才收起来，只给「展开全文」 */
const LONG_CHARS = 84;
/* 复盘正文超过这么多行，先只露这么多行（board.css 的 .rvb.clip 同一个数） */
const RV_LINES = 6;
/* 时间线先露几条，「加载更早」每次再多露几条 */
const PAGE = 6;
/* 结论里独占一行的币码 [ETHUSDT] */
const SEG_LINE = /^\s*\[([A-Z0-9]{2,20})\]\s*$/;
/* 盯盘价离现价不到这么多（%）就标「快到了」 */
const NEAR_PCT = 0.35;
/* 净值图的 svg 内部坐标，拉伸铺满 */
const CV_W = 700, CV_H = 280;
const BEAT_MS = { '1h': HOUR, '4h': 4 * HOUR }[MOCK.trader.interval];
const NARROW = matchMedia('(max-width: 1180px)');

/* 看板状态：区间 / 页签 / 筛选 / 时间线露了几条 / 展开没有 / 数字滚过没有 / 看板上的「现在」/ 展开了全文的决策；切语言重画时接着用 */
const DASH = { range: 3, tab: 'timeline', filter: 'all', shown: PAGE, open: false, counted: false, now: MOCK_NOW, reasons: new Set(),
  win: null, timer: 0, ro: null, io: null, hover: null, cv: null };

const decById = id => MOCK.decisions.find(d => d.id === id);
const tradeById = id => MOCK.trades.find(r => r.id === id);
const planOf = (symbol, side) => MOCK.plans.find(pl => pl.symbol === symbol && pl.side === side);
const markOf = symbol => QUOTES.get(coinName(symbol)).price;
const sideText = side => T(side === 'LONG' ? DL.long : DL.short);
const toolName = k => T(DL.tool[k]);
const kindLabel = d => d.kind === 'TRADE' ? fmt(DL.kind.TRADE, { iv: MOCK.trader.interval }) : T(DL.kind[d.kind]);
/* 这一轮做成了的交易动作（布盯盘、被拒的不算） */
const doneActs = d => (d.acts || []).filter(a => !a.rejected && !NOT_ACTING.has(a.tool));
function passes(d, f) {
  if (f === 'all') return true;
  if (f === 'debrief') return d.kind === 'DEBRIEF';
  if (f === 'review') return d.kind === 'REVIEW';
  if (f === 'woken') return WOKEN_KINDS.has(d.kind);
  return doneActs(d).length > 0;
}
/* 时间线按当前筛选挑出来的那些 */
const tlList = () => MOCK.decisions.filter(d => passes(d, DASH.filter));

function renderDash() {
  const mount = document.getElementById('mount-dash');
  clearInterval(DASH.timer);
  DASH.ro?.disconnect();
  DASH.io?.disconnect();
  if (MOBILE.matches) { mount.innerHTML = ''; DASH.win = null; return; }
  const tr = MOCK.trader;
  // 收益率、权益第一次进视口才从 0 滚上来、曲线才画出来；滚过了或者不动的场合直接给终值
  const seen = DASH.counted || REDUCED || SHOT;
  mount.innerHTML = `<div class="win${DASH.open ? '' : ' folded'}">
    <div class="win-bar"><i></i><i></i><i></i><span class="addr"><svg data-icon="lock"></svg>wtfibought.com/arena/${esc(tr.name)}</span><span class="tag sm">${esc(T(DL.sample))}</span></div>
    <div class="win-body tw${seen ? ' seen' : ''}">
      ${headHtml(seen)}
      ${beatHtml()}
      <div class="sc">${scoreHtml()}</div>
      <div class="grid2">
        <div class="left">
          <section class="w-curve">
            <div class="sec-h">
              <h2>${esc(fmt(DL.curve.title, { round: T(DL.thisRound) }))}</h2>
              <div class="seg" role="group"><i class="th"></i>${RANGES.map(r => `<button type="button" data-range="${r}" aria-pressed="${r === DASH.range}">${esc(rangeLabel(r))}</button>`).join('')}</div>
            </div>
            <div class="rd"></div>
            <div class="cv" tabindex="0" role="img" aria-label="${esc(T(DL.curve.aria))}"></div>
            <div class="xl"></div>
          </section>
          <section class="w-tl">
            <div class="tl-h"><div class="tabs" role="tablist"><i class="bar2"></i>
              <button type="button" role="tab" data-tab="timeline" aria-selected="${DASH.tab === 'timeline'}">${esc(T(DL.timeline))}</button>
              <button type="button" role="tab" data-tab="trades" aria-selected="${DASH.tab === 'trades'}">${esc(T(DL.tradesTitle))}<em>${tr.closed}</em></button>
            </div></div>
            <div class="chips">${FILTERS.map(f => `<button type="button" class="chip" data-filter="${f}" aria-pressed="${f === DASH.filter}">${esc(T(DL.filter[f]))}<em>${MOCK.decisions.filter(d => passes(d, f)).length}</em></button>`).join('')}</div>
            <div class="cards"></div>
          </section>
        </div>
        <aside class="side">
          ${holdHtml()}
          ${watchSecHtml()}
          ${notesHtml()}
        </aside>
      </div>
      <div class="win-foot"><button type="button" class="btn" data-fold><svg data-icon="chevrons-up"></svg>${esc(T(DL.fold))}</button></div>
    </div>
    <div class="win-fold"><button type="button" class="btn ink" data-unfold>${esc(T(DL.unfold))}<svg data-icon="chevrons-down"></svg></button></div>
  </div>`;
  const win = DASH.win = mount.firstElementChild;
  renderCards();
  drawCurve();
  icons(win);
  const seg = win.querySelector('.seg'), tabs = win.querySelector('.tabs');
  placeBar(seg, false);
  placeBar(tabs, false);
  // 段选滑块、页签墨线按选中那颗的位置摆；钮的大小一变（字体到了、换了语言）就重摆
  DASH.ro = new ResizeObserver(() => { placeBar(seg, false); placeBar(tabs, false); });
  win.querySelectorAll('.seg button, .tabs button').forEach(b => DASH.ro.observe(b));
  layoutDash();
  startLive();
  mount.onclick = onDashClick;
  if (seen) return;
  // 曲线进视口那一刻：收益率和权益从 0 滚上来，曲线从左往右画出来
  const head = win.querySelector('.trhd');
  DASH.io = new IntersectionObserver(es => {
    if (!es[0].isIntersecting) return;
    DASH.io.disconnect();
    DASH.counted = true;
    win.querySelector('.tw').classList.add('seen');
    countUp(head.querySelector('[data-pnl]'), tr.pnlPct, pnlHtml);
    countUp(head.querySelector('[data-eq]'), tr.equity, v => fnum(v));
  }, { rootMargin: '0px 0px -15% 0px' });
  DASH.io.observe(win.querySelector('.cv'));
}

/* 页头：返回链接、名字和运行中、币种 + 模型 · 心跳 · 时段 · 第几局；右边本局收益大数 + 权益 */
function headHtml(seen) {
  const tr = MOCK.trader;
  const meta = [tr.model, fmt(DL.meta.beat, { iv: tr.interval }), fmt(DL.meta.window, { win: tr.wakeWindow }), fmt(DL.meta.round, { n: tr.round })];
  return `<div class="trhd">
    <div>
      <a class="back" href="https://wtfibought.com/arena"><svg data-icon="chevron-left"></svg>${esc(T(DL.back))}</a>
      <div class="nm"><b>${esc(tr.name)}</b><span class="tag g"><i class="live"></i>${esc(T(DL.running))}</span></div>
      <div class="meta"><span class="coins">${tr.symbols.map(coinIcon).join('')}</span><span>${esc(meta.join(' · '))}</span></div>
    </div>
    <div class="pnl">
      <b class="big nf ${tr.pnlPct >= 0 ? 'up' : 'dn'}" data-pnl>${pnlHtml(seen ? tr.pnlPct : 0)}</b>
      <div class="sub"><span>${esc(T(DL.equity))} <b data-eq>${fnum(seen ? tr.equity : 0)}</b></span><span>${esc(T(DL.initial))}</span></div>
    </div>
  </div>`;
}

/* 心跳块：心跳档位 / 下次心跳倒计时（底下一根细条是这一拍走到哪了）/ 上次醒来干了什么 / 在盯几条 */
function beatHtml() {
  const tr = MOCK.trader, [from, to] = tr.wakeWindow.split('–');
  // 上次醒来：最近一次真醒来（复盘不算），带上做成的第一个交易动作
  const last = MOCK.decisions.find(d => d.kind !== 'DEBRIEF' && d.kind !== 'REVIEW');
  const act = doneActs(last)[0];
  const what = [kindLabel(last), act && `${toolName(act.tool)} ${act.symbol}`].filter(Boolean).join(' · ');
  const sameDay = sgDt(last.at).day === sgDt(DASH.now).day;
  const priceN = MOCK.watches.filter(w => w.kind !== 'AT_TIME').length;
  return `<div class="hb blk">
    <div><div class="k">${esc(T(DL.hb.beat))}</div><div class="v">${tr.interval}</div><div class="s">${esc(fmt(DL.hb.beatWin, { from, to, n: tr.beatsPerDay }))}</div></div>
    <div><div class="k">${esc(T(DL.hb.next))}</div><div class="v" data-next></div><div class="s" data-left></div><span class="beat"><i data-beat></i></span></div>
    <div><div class="k">${esc(T(DL.hb.last))}</div><div class="v">${sameDay ? fmtHm(last.at) : fmtDt(last.at)}</div><div class="s">${esc(what)}</div></div>
    <div><div class="k">${esc(T(DL.hb.watch))}</div><div class="v">${esc(fmt(DL.hb.watchN, { n: MOCK.watches.length }))}</div><div class="s">${esc(fmt(DL.hb.watchMix, { p: priceN, t: MOCK.watches.length - priceN }))}</div></div>
  </div>`;
}

/* 记分条六格：第几天 / 做完几笔 / 胜率 / 最大回撤 / 区间内变化（跟着段选走）/ 本局 token */
function scoreHtml() {
  const tr = MOCK.trader, pts = curvePoints();
  const delta = pts[pts.length - 1].v - pts[0].v, pct = delta / pts[0].v * 100;
  const cells = [
    [T(DL.thisRound), fmt(DL.dayN, { n: tr.day })],
    [T(DL.closedCount), fmtN(DL.tradesN, tr.closed)],
    [T(DL.winRate), tr.winRate + '%'],
    [T(DL.maxDd), tr.maxDd.toFixed(2) + '%', 'dn'],
    [rangeLabel(DASH.range), `${signed(delta)} · ${signedPct(pct)}`, delta >= 0 ? 'up' : 'dn'],
    [fmt(DL.tokensOf, { scope: T(DL.thisRound) }), fmtTok(tr.tokensRound)]
  ];
  return cells.map(([k, v, tone]) => `<div><span>${esc(k)}</span><b${tone ? ` class="${tone}"` : ''}>${esc(v)}</b></div>`).join('');
}
const rangeLabel = r => r === 0 ? T(DL.rangeAll) : fmt(DL.rangeDays, { n: r });

/* ---------- 净值曲线（同主站 EquityCurve）：初始资金一条虚线，末值在它之上整条线绿、之下红，
   曲线和虚线之间铺一层同色往虚线渐隐；窗口里的开平仓点；上面一行读数（指到哪个点读哪个），底下首 / 中 / 末三个时刻 ---------- */
/* 区间锚在曲线末点：3 天 = 最后 72 小时 */
function curvePoints() {
  const c = MOCK.curve;
  if (DASH.range === 0) return c;
  const from = c[c.length - 1].t - DASH.range * 24 * HOUR;
  return c.filter(p => p.t >= from);
}
/* 曲线上的开平仓：做完的交易开、平各一个（平仓按盈亏），手上的仓位开一个；挂到时间上最近的那个点，区间起点之前的不挂 */
function curveEvents(pts) {
  const t0 = pts[0].t, ev = [];
  const put = (at, type, text) => { if (at >= t0) ev.push({ i: Math.round((at - t0) / HOUR), type, text }); };
  for (const r of MOCK.trades) {
    put(r.opened, 'open', fmt(DL.curve.open, { coin: coinName(r.symbol), side: sideText(r.side) }));
    put(r.closed, r.pnl >= 0 ? 'win' : 'loss', fmt(DL.curve.close, { coin: coinName(r.symbol), how: T(DL.manner[r.manner]), pnl: signed(r.pnl) }));
  }
  for (const p of MOCK.positions) put(p.opened, 'open', fmt(DL.curve.open, { coin: coinName(p.symbol), side: sideText(p.side) }));
  return ev;
}

function drawCurve() {
  const win = DASH.win, cv = win.querySelector('.cv'), base = MOCK.trader.seed;
  const pts = curvePoints(), n = pts.length, vals = pts.map(p => p.v);
  let lo = Math.min(...vals, base), hi = Math.max(...vals, base);
  const pad = (hi - lo) * .1;
  lo -= pad; hi += pad;
  const ys = vals.map(v => (hi - v) / (hi - lo) * CV_H), yb = (hi - base) / (hi - lo) * CV_H;
  const line = ys.map((y, i) => `${(i / (n - 1) * CV_W).toFixed(1)},${y.toFixed(1)}`).join(' ');
  const up = vals[n - 1] >= base;
  const at = new Map();
  for (const e of curveEvents(pts)) at.set(e.i, [...(at.get(e.i) || []), e]);
  const x = i => `${i / (n - 1) * 100}%`, y = v => `${v / CV_H * 100}%`;
  DASH.cv = { pts, ys, at, x, y };
  cv.className = `cv ${up ? 'up' : 'dn'}`;
  // 浓的一头贴着曲线：亏的时候曲线在虚线下面，渐变上下对调
  cv.innerHTML = `<svg viewBox="0 0 ${CV_W} ${CV_H}" preserveAspectRatio="none" aria-hidden="true">
      <defs><linearGradient id="tw-eq" x1="0" y1="${up ? 0 : 1}" x2="0" y2="${up ? 1 : 0}"><stop offset="0" stop-color="currentColor" stop-opacity=".14"/><stop offset="1" stop-color="currentColor" stop-opacity="0"/></linearGradient></defs>
      <line class="bl" x1="0" x2="${CV_W}" y1="${yb.toFixed(1)}" y2="${yb.toFixed(1)}"/>
      <polygon fill="url(#tw-eq)" points="${line} ${CV_W},${yb.toFixed(1)} 0,${yb.toFixed(1)}"/>
      <polyline class="ln" points="${line}"/>
    </svg>
    <span class="bt" style="top:${y(yb)}">${esc(fmt(DL.curve.base, { v: fnum(base, 0) }))}</span>
    ${[...at].map(([i, es]) => {
      // 一个点上既开又平：按平仓的盈亏上色
      const close = es.find(e => e.type !== 'open');
      return `<i class="evt${close ? (close.type === 'win' ? ' g' : ' r') : ''}" style="left:${x(i)};top:${y(ys[i])}" title="${esc(es.map(e => e.text).join(' · '))}"></i>`;
    }).join('')}
    <i class="cur"></i><i class="dot"></i>`;
  win.querySelector('.xl').innerHTML = [...new Set([0, Math.floor((n - 1) / 2), n - 1])].map(i => `<span style="left:${x(i)}">${fmtDt(pts[i].t)}</span>`).join('');
  DASH.hover = null;
  paintReadout();
  // 指到哪个点读哪个，左右方向键也能逐点走
  cv.onpointermove = e => {
    const r = cv.getBoundingClientRect();
    setHover(Math.max(0, Math.min(n - 1, Math.round((e.clientX - r.left) / r.width * (n - 1)))));
  };
  cv.onpointerleave = cv.onblur = () => setHover(null);
  cv.onkeydown = e => {
    const d = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
    if (!d) return;
    e.preventDefault();
    setHover(Math.max(0, Math.min(n - 1, (DASH.hover ?? n - 1) + d)));
  };
}
function setHover(i) {
  if (i === DASH.hover) return;
  DASH.hover = i;
  const cv = DASH.win.querySelector('.cv'), { x, y, ys } = DASH.cv;
  cv.classList.toggle('hov', i != null);
  if (i != null) {
    cv.querySelector('.cur').style.left = x(i);
    Object.assign(cv.querySelector('.dot').style, { left: x(i), top: y(ys[i]) });
  }
  paintReadout();
}
/* 读数行：默认最后一个点 */
function paintReadout() {
  const { pts, at } = DASH.cv, i = DASH.hover ?? pts.length - 1, p = pts[i], base = MOCK.trader.seed, diff = p.v - base;
  const evs = DASH.hover != null && at.get(i);
  DASH.win.querySelector('.rd').innerHTML = `<span>${fmtDt(p.t)}</span><span>${esc(T(DL.equity))} <b>${fnum(p.v)}</b></span>`
    + `<span class="${diff >= 0 ? 'up' : 'dn'}">${esc(fmt(DL.curve.vsBase, { diff: signed(diff), pct: signedPct(diff / base * 100) }))}</span>`
    + (evs ? `<span><b>${esc(evs.map(e => e.text).join(' · '))}</b></span>` : '');
}

/* ---------- 时间线：它的每次思考（先露一页，底下「加载更早」）/ 做完的交易 ---------- */
function renderCards() {
  const win = DASH.win, cards = win.querySelector('.cards'), tl = DASH.tab === 'timeline';
  win.querySelector('.w-tl .chips').style.display = tl ? '' : 'none';
  if (tl) {
    const list = tlList();
    cards.innerHTML = `<div>${list.slice(0, DASH.shown).map(decisionHtml).join('')}</div>`
      + (list.length > DASH.shown ? `<button type="button" class="btn wide more" data-load><span>${esc(T(DL.loadMore))}</span></button>` : '');
  } else {
    cards.innerHTML = MOCK.trades.map(tradeHtml).join('') + `<p class="tc-more">${esc(fmt(DL.moreTrades, { n: MOCK.trader.closed, k: MOCK.trades.length }))}</p>`;
  }
  icons(cards);
}

/* 一条决策（主站的 DecisionCard，样式 .dec）：标签 / 时刻 / 权益 / 遥测 → 看了什么 → 交易动作一行一个 → 推理折叠；
   两种复盘是空心圈，正文铺在一块浅灰里，长的先露几行 */
function decisionHtml(d) {
  const review = d.kind === 'REVIEW' || d.kind === 'DEBRIEF';
  const tone = KIND_TONE[d.kind];
  let tags;
  if (d.kind === 'DEBRIEF') {
    // 复的是哪一笔：币和方向
    const r = tradeById(d.position);
    tags = `<span class="tag ink"><svg data-icon="notebook-pen"></svg>${esc(T(DL.debrief))}</span><span>${r.symbol} ${esc(sideText(r.side))}</span>`;
  } else if (d.kind === 'REVIEW') {
    tags = `<span class="tag ink"><svg data-icon="notebook-pen"></svg>${esc(T(DL.review))}</span>`;
  } else {
    tags = `<span class="tag${tone ? ' ' + tone : ''}">${esc(kindLabel(d))}</span>${d.symbol ? `<span class="tag">${esc(fmt(DL.onlySymbol, { s: coinName(d.symbol) }))}</span>` : ''}`;
  }
  // 统一复盘是单次调用不挂工具，不写「0次工具」
  const meta = [`${fmtTok(d.tokens)} tok`, `${d.latency.toFixed(1)}s`, d.kind !== 'REVIEW' && fmtN(DL.toolCalls, d.toolCalls), fmtN(DL.modelCalls, d.modelCalls)]
    .filter(Boolean).join(' · ');
  const text = T(d.reasoning), open = DASH.reasons.has(d.id);
  // 推理长了整段收起；复盘正文长了先露 RV_LINES 行，底下渐隐
  const long = review ? text.split('\n').length > RV_LINES : text.length > LONG_CHARS;
  const body = review ? `<div class="rvb${long && !open ? ' clip' : ''}"><p>${reviewHtml(text)}</p></div>`
    : !long || open ? `<div class="rs">${blocksHtml(text)}</div>` : '';
  return `<div class="dec ${review ? 'rv' : tone ? 'dot-' + tone : ''}" id="dec-${d.id}">
    <div class="dc-h">${tags}<span>${fmtDt(d.at)}</span>${d.equity != null ? `<span>${esc(T(DL.equity))} <b>${fnum(d.equity)}</b></span>` : ''}<span class="m">${esc(meta)}</span></div>
    ${d.looked ? `<div class="look">${esc(T(DL.lookedAt))} ${d.looked.map(k => `<b>${esc(toolName(k))}</b>`).join(' · ')}</div>` : ''}
    ${d.acts?.length ? `<div class="al">${d.acts.map(actHtml).join('')}</div>` : ''}
    ${body}
    ${long ? `<div class="lk"><button type="button" class="linkb" data-more="${d.id}" aria-expanded="${open}">${esc(T(open ? DL.collapse : DL.expandFull))}</button></div>` : ''}
  </div>`;
}

/* 推理按 [ETHUSDT] 分块，每块前面一枚币码；币码之前的引子（[本轮结论]、总评）自成一块 */
function blocksHtml(text) {
  const blocks = [];
  let sym = null, buf = [];
  const flush = () => { if (buf.join('').trim()) blocks.push({ sym, body: buf.join('\n').trim() }); };
  for (const line of text.split('\n')) {
    const m = SEG_LINE.exec(line);
    if (m) { flush(); sym = m[1]; buf = []; } else buf.push(line);
  }
  flush();
  return blocks.map(b => `<div class="part">${b.sym ? `<span class="tag sm">${b.sym}</span>` : ''}<p>${esc(b.body)}</p></div>`).join('');
}
/* 复盘正文：一行一项照原样换行，段标题（【单笔复盘】【归类】【代码记账】…）加粗 */
const reviewHtml = text => text.split('\n').map(line => esc(line).replace(/^(【[^】]+】|\[[A-Z][A-Z ]*\])/, '<b>$1</b>')).join('\n');

/* 一条交易动作：开仓是一块小面板，平仓写成交价和这次的盈亏，改止损写原来 → 现在，布盯盘写几条；
   被拒的红字带拒因；调工具时写的理由指上去看 */
function actHtml(a) {
  let detail;
  if (a.tool === 'open_position') detail = openPanelHtml(a);
  else if (a.tool === 'close_position') {
    detail = `<span class="cl">${a.symbol} ${esc(fmt(DL.args.qty, { n: a.qty }))} @ ${rawPx(a.filled)}<span class="num ${a.pnl >= 0 ? 'up' : 'dn'}">${usdt(a.pnl)}</span></span>`;
  } else if (a.tool === 'set_stop_loss') detail = `<span>${rawPx(a.from)} → ${rawPx(a.to)}</span>`;
  else detail = `<span>${a.symbol} · ${esc(fmtN(DL.args.watches, a.n))}</span>`;
  return `<div class="act${a.rejected ? ' bad' : ''}">
    <b>${esc(toolName(a.tool))}${a.rejected ? esc(T(DL.rejected)) : ''}</b>
    <div class="ad"${a.reason ? ` title="${esc(T(a.reason))}"` : ''}>${detail}${a.rejected ? `<small>${esc(T(a.rejected))}</small>` : ''}</div>
  </div>`;
}
/* 开仓的小面板：币、方向、杠杆和打法一行；每一档一行（限价还是市价、数量、成交没有）；止损止盈一行 */
function openPanelHtml(a) {
  const rungs = a.tp.map(([p, pct]) => `${rawPx(p)}(${pct}%)`).join(' / ');
  return `<div class="op">
    <div class="op-h"><b>${a.symbol}</b><span class="tag sm ${a.side === 'LONG' ? 'g' : 'r'}">${esc(T(a.side === 'LONG' ? DL.args.long : DL.args.short))}</span><span class="num">${a.lev}x</span><span class="op-p">${a.play}</span></div>
    <div class="op-e num">${a.entries.map(e => `<span>${esc(e.limit ? fmt(DL.args.limit, { p: rawPx(e.limit) }) : T(DL.marketEntry))}</span><span>× ${e.qty}</span><span${e.status === 'filled' ? ' class="ok"' : ''}>${e.status ? esc(T(DL.entryStatus[e.status])) : ''}${e.filled ? ` @ ${rawPx(e.filled)}` : ''}</span>`).join('')}</div>
    <div class="op-f num"><span>${esc(fmt(DL.args.sl, { p: rawPx(a.sl) }))}</span><span>${esc(fmt(DL.args.tp, { p: rungs }))}</span></div>
  </div>`;
}

/* 计划的修订记录：抬头是时刻、类型、改了什么，理由另起一行 */
function revisionsHtml(revs, label) {
  if (!revs.length) return '';
  return `<div class="revs"><span>${esc(fmtN(label, revs.length))}</span><ul>${revs.map(v => `<li>
    <div><time>${fmtDt(v.at)}</time><span class="tag sm">${esc(T(DL.revType[v.type]))}</span><b>${v.change}</b></div>
    <p>${esc(T(v.reason))}</p>
  </li>`).join('')}</ul></div>`;
}

/* 一笔做完的交易（主站的 TradeCard）：头行（币种 · 多空 · 了结方式 · 入场 → 出场 · 盈亏）→ 开 / 平时刻 → 计划 → 跳到时间线那一条；
   止损止盈带走的没有平仓那一轮 */
function tradeHtml(r) {
  const long = r.side === 'LONG';
  const open = decById(r.open), close = r.close && decById(r.close), debrief = decById(r.debrief);
  const closeAct = close && close.acts.find(a => a.tool === 'close_position');
  const jump = (label, d, detail) => `<button type="button" class="btn sm" data-jump="${d.id}"><span>${esc(label)} · ${esc(detail || fmtDt(d.at))}</span><svg data-icon="arrow-right"></svg></button>`;
  return `<div class="tc">
    <div class="tc-h">${coinIcon(r.symbol)}<b>${r.symbol}</b>
      <span class="tag ${long ? 'g' : 'r'}">${esc(sideText(r.side))} ${r.lev}x</span>
      <span class="tag${MANNER_TONE[r.manner] ? ' ' + MANNER_TONE[r.manner] : ''}">${esc(T(DL.manner[r.manner]))}</span>
      <span class="pr">${fnum(r.entry)} → ${fnum(r.exit)}</span>
      <b class="pn ${r.pnl >= 0 ? 'up' : 'dn'}">${signed(r.pnl)}</b>
    </div>
    <div class="tm"><span>${esc(fmt(DL.openedAt, { t: fmtDt(r.opened) }))}</span><span>${esc(fmt(DL.closedAt, { t: fmtDt(r.closed) }))}</span><span>${esc(fmt(DL.held, { d: fmtDur(r.closed - r.opened) }))}</span></div>
    <div class="pl">
      <div><b>${r.play}</b>${esc(fmt(DL.basis, { s: T(r.basis) }))}</div>
      <div class="mute"><b>${esc(T(DL.invalidation))}</b>${esc(T(r.invalidation))}</div>
    </div>
    <div class="lvs"><span>${esc(T(DL.entry))}<b>${fnum(r.entry)}</b></span><span>${esc(T(DL.origSl))}<b>${fnum(r.sl)}</b></span><span>${esc(T(DL.target))}${r.tp.map(([p, pct]) => `<b>${fnum(p)}<small>(${pct}%)</small></b>`).join('')}</span></div>
    ${revisionsHtml(r.revisions, DL.revisions)}
    <div class="jp">
      ${jump(T(DL.openDecision), open)}
      ${close ? jump(T(DL.closeDecision), close) : ''}
      ${jump(T(DL.debrief), debrief, fmt(DL.debriefAfter, { d: fmtDur(debrief.at - r.closed) }))}
      ${closeAct ? `<span>—— ${esc(T(closeAct.reason))}</span>` : ''}
    </div>
  </div>`;
}

/* ---------- 右栏：手上的仓位，一仓一块；浮盈、条上的现价、离触发多远跟着现价走（paintMarks 填） ---------- */
/* 计划并进对应那一块：为什么开、看错的标志、改过几次 */
function planLinesHtml(pl) {
  return `<div class="pc-why">
    <div><span>${esc(T(DL.hold.why))}</span>${esc(`${pl.play} · ${T(pl.basis)}`)}</div>
    <div><span>${esc(T(DL.hold.inval))}</span>${esc(T(pl.invalidation))}</div>
    ${revisionsHtml(pl.revisions, DL.hold.revised)}
  </div>`;
}
/* 止损（左）到止盈（右）的条上，价 v 落在百分之几：多空都是往右离止盈更近 */
const barAt = (p, v) => Math.max(0, Math.min(100, (p.side === 'LONG' ? (v - p.sl) / (p.tp - p.sl) : (p.sl - v) / (p.sl - p.tp)) * 100));

function holdHtml() {
  const pos = MOCK.positions, ords = MOCK.orders;
  return `<section class="w-hold">
    <div class="sec-h"><h2>${esc(T(DL.hold.title))}<small>${esc(fmtN(DL.hold.positionsN, pos.length))} · ${esc(fmtN(DL.hold.ordersN, ords.length))}</small></h2><b class="flt" data-float></b></div>
    <div class="hold">${pos.map(positionHtml).join('')}${ords.map(orderHtml).join('')}</div>
  </section>`;
}
function positionHtml(p) {
  const long = p.side === 'LONG', coin = coinName(p.symbol);
  // 止损挪过了开仓价（锁了利润）：开仓价在条外，不画那根刻度
  const locked = long ? p.sl > p.entry : p.sl < p.entry;
  return `<div class="blk" data-pos="${p.symbol}">
    <div class="pc-h">${coinIcon(p.symbol)}<b>${coin}</b><span class="tag ${long ? 'g' : 'r'}">${esc(fmt(long ? DL.hold.long : DL.hold.short, { lev: p.lev }))}</span><span class="pn" data-pnl><span></span><em></em></span></div>
    <div class="pc-q">${esc(fmt(DL.hold.qtyAt, { qty: p.qty, coin, price: fnum(p.entry) }))}</div>
    <div class="pc-bar" aria-hidden="true"><i class="f"></i>${locked ? '' : `<i class="en" style="left:${barAt(p, p.entry)}%"></i>`}<i class="nw"><span></span></i></div>
    <div class="pc-ends">
      <span class="dn">${esc(fmt(DL.hold.sl, { price: fnum(p.sl) }))}${locked ? ' · ' + esc(T(DL.hold.locked)) : ''}<em data-sl></em></span>
      <span class="r up">${esc(fmt(DL.hold.tp, { price: fnum(p.tp) }))}<em data-tp></em></span>
    </div>
    ${planLinesHtml(planOf(p.symbol, p.side))}
  </div>`;
}
/* 没成交的开仓挂单：挂在哪、离现价多远就成交、成交后的止损止盈 */
function orderHtml(o) {
  const side = o.side.includes('LONG') ? 'LONG' : 'SHORT', coin = coinName(o.symbol);
  return `<div class="blk" data-ord="${o.symbol}">
    <div class="pc-h">${coinIcon(o.symbol)}<b>${coin}</b><span class="tag">${esc(fmt(side === 'LONG' ? DL.hold.limitLong : DL.hold.limitShort, { lev: o.lev }))}</span><span class="pn wait">${esc(T(DL.hold.notFilled))}</span></div>
    <div class="pc-q">${esc(fmt(DL.hold.qtyLimit, { qty: o.qty, coin, price: fnum(o.limit) }))}</div>
    <p class="pc-ord" data-fill></p>
    ${planLinesHtml(planOf(o.symbol, side))}
  </div>`;
}

/* ---------- 右栏：它的盯盘；看价格的按离现价远近排，看时间的排后面，顺序和离多远由 paintMarks / paintClock 跟着改 ---------- */
function watchCond(w) {
  if (w.kind === 'AT_TIME') return fmt(DL.watch.at, { time: fmtDt(w.at) });
  const price = fnum(w.level);
  if (w.kind === 'TOUCH_BELOW') return fmt(DL.watch.touchBelow, { price });
  return fmt(w.kind === 'CLOSE_ABOVE' ? DL.watch.closeAbove : DL.watch.closeBelow, { period: T(DL.period[w.period]), price });
}
function watchSecHtml() {
  const ws = MOCK.watches;
  return `<section class="w-watch">
    <div class="sec-h"><h2>${esc(T(DL.watch.title))}<small>${esc(fmt(DL.watch.sum, { n: ws.length }))}</small><button type="button" class="tip" aria-label="${esc(T(DL.help))}"><svg data-icon="circle-help"></svg><span class="tb2" role="tooltip">${esc(T(DL.watch.desc))}</span></button></h2></div>
    <div class="was">${ws.map((w, i) => {
      const coin = coinName(w.symbol);
      return `<div class="wa" data-w="${i}">${coinIcon(w.symbol)}
        <div class="b">
          <b>${coin} · ${esc(watchCond(w))}<span class="tag o sm">${esc(T(DL.watch.soon))}</span></b>
          <span>${esc(fmt(DL.watch.note, { v: T(w.note) }))}</span>
          ${w.preset ? `<span>${esc(fmt(DL.watch.preset, { target: `${coin} ${sideText(w.preset.side)}`, pct: w.preset.pct }))}</span>` : ''}
        </div>
        <div class="rr"><b data-away></b><span>${esc(fmt(DL.watch.setAt, { time: fmtDt(w.setAt) }))}</span></div>
      </div>`;
    }).join('')}</div>
  </section>`;
}

/* ---------- 右栏：笔记（经验账本），先露一截，比露出来的长才底下渐隐、给「展开全文」 ---------- */
function notesHtml() {
  return `<section class="w-notes">
    <div class="sec-h"><h2>${esc(T(DL.notesTitle))}</h2></div>
    <div class="blk">
      <div class="nt-h"><b>${esc(T(DL.memoryShort))}</b><span>${esc(fmt(DL.lastAt, { time: fmtDt(MOCK.memory.at) }))}</span></div>
      <div class="nt-b">${esc(T(MOCK.memory))}</div>
      <button type="button" class="linkb nt-more" data-notes aria-expanded="false">${esc(T(DL.expandFull))}</button>
    </div>
  </section>`;
}
/* 收着时正文有没有被截掉；栏宽变了会重新折行，跟着再量 */
function measureNotes() {
  const body = DASH.win.querySelector('.nt-b');
  if (body.classList.contains('open')) return;
  const long = body.scrollHeight > body.clientHeight;
  body.classList.toggle('long', long);
  body.nextElementSibling.style.display = long ? '' : 'none';
}

/* ---------- 实时：时钟一走（心跳倒计时、到时刻的盯盘），现价一走（浮盈、条、挂单离成交多远、盯盘离触发多远和先后） ---------- */
function paintClock() {
  const win = DASH.win, now = DASH.now;
  const next = Math.floor(now / BEAT_MS) * BEAT_MS + BEAT_MS, left = next - now;
  const sec = Math.floor(left / 1000), h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60);
  putText(win.querySelector('[data-next]'), fmtHm(next));
  putText(win.querySelector('[data-left]'), h > 0 ? fmt(DL.left.hm, { h, m }) : fmt(DL.left.ms, { m: String(m).padStart(2, '0'), s: String(sec % 60).padStart(2, '0') }));
  // 这一拍走了多少：离下次心跳越近越满
  win.querySelector('[data-beat]').style.clipPath = `inset(0 ${(left / BEAT_MS * 100).toFixed(2)}% 0 0)`;
  // 到时刻的盯盘：分钟级
  MOCK.watches.forEach((w, i) => {
    if (w.kind !== 'AT_TIME') return;
    const min = Math.ceil((w.at - now) / 60000);
    putText(win.querySelector(`[data-w="${i}"] [data-away]`), min >= 60 ? fmt(DL.left.hm, { h: Math.floor(min / 60), m: min % 60 }) : fmt(DL.left.m, { m: min }));
  });
}

function paintMarks() {
  const win = DASH.win;
  let floating = 0;
  for (const p of MOCK.positions) {
    const card = win.querySelector(`[data-pos="${p.symbol}"]`), mark = markOf(p.symbol);
    const pnl = (p.side === 'LONG' ? mark - p.entry : p.entry - mark) * p.qty;
    floating += pnl;
    const pn = card.querySelector('[data-pnl]');
    pn.className = `pn ${pnl >= 0 ? 'up' : 'dn'}`;
    putText(pn.firstElementChild, signed(pnl));
    putText(pn.lastElementChild, fmt(DL.hold.ofMargin, { pct: signedPct(pnl / (p.entry * p.qty / p.lev) * 100) }));
    // 条：开仓价到现价那一段填色，赚绿亏红；竖线是现价
    const pe = barAt(p, p.entry), pm = barAt(p, mark);
    const f = card.querySelector('.f');
    f.className = `f ${pm >= pe ? 'up' : 'dn'}`;
    f.style.clipPath = `inset(0 ${100 - Math.max(pe, pm)}% 0 ${Math.min(pe, pm)}% round 3px)`;
    const nw = card.querySelector('.nw'), lab = nw.firstElementChild;
    nw.style.transform = `translateX(${pm}%)`;
    putText(lab, fmt(DL.hold.now, { price: fnum(mark) }));
    // 现价贴着两头时，标签往里收，不出条
    lab.style.transform = `translateX(${pm > 80 ? 'calc(-100% + 2px)' : pm < 20 ? '0' : '-50%'})`;
    putText(card.querySelector('[data-sl]'), fmt(DL.hold.trigger, { move: moveText(p.sl, mark) }));
    putText(card.querySelector('[data-tp]'), fmt(DL.hold.trigger, { move: moveText(p.tp, mark) }));
  }
  const flt = win.querySelector('[data-float]');
  flt.className = `flt ${floating >= 0 ? 'up' : 'dn'}`;
  putText(flt, fmt(DL.hold.floating, { v: signed(floating) }));
  for (const o of MOCK.orders) {
    const mark = markOf(o.symbol);
    putText(win.querySelector(`[data-ord="${o.symbol}"] [data-fill]`),
      `${fmt(DL.hold.fillIn, { now: fnum(mark), move: moveText(o.limit, mark) })} ${fmt(DL.hold.afterFill, { sl: fnum(o.sl), tp: fnum(o.tp) })}`);
  }
  // 盯盘：看价格的按离现价远近排，离得近标「快到了」；看时间的排后面，按时刻先后；顺序变了才挪节点
  const box = win.querySelector('.was');
  const rows = MOCK.watches.map((w, i) => {
    const el = box.querySelector(`[data-w="${i}"]`);
    if (w.kind === 'AT_TIME') return { el, away: Infinity, at: w.at };
    const mark = markOf(w.symbol), away = Math.abs(w.level / mark - 1) * 100;
    el.classList.toggle('near', away < NEAR_PCT);
    putText(el.querySelector('[data-away]'), moveText(w.level, mark));
    return { el, away, at: 0 };
  });
  rows.sort((a, b) => a.away - b.away || a.at - b.at);
  if (rows.some((r, i) => box.children[i] !== r.el)) box.append(...rows.map(r => r.el));
}

/* 看板时钟每秒走一下，ETH、BTC 现价每 3 秒漂一点（行情板那两行跟着滚）；减弱动效和截图时只画一帧 */
function startLive() {
  paintClock();
  paintMarks();
  if (REDUCED || SHOT) return;
  const rnd = mockSeeded(Date.now() & 0xffff);
  let n = 0;
  DASH.timer = setInterval(() => {
    if (document.hidden) return;
    DASH.now += 1000;
    paintClock();
    if (++n % 3 === 0) { nudgeQuote('ETH', .0016, rnd); nudgeQuote('BTC', .0016, rnd); }
    // 每秒对一遍现价：行情板漂到 ETH、BTC 时看板也跟上
    paintMarks();
  }, 1000);
}

/* ---------- 交互 ---------- */
/* 段选滑块 / 页签墨线摆到选中那颗底下；animate=false 直接到位不滑 */
function placeBar(box, animate) {
  const bar = box.querySelector('.th, .bar2'), on = box.querySelector('[aria-pressed="true"], [aria-selected="true"]');
  bar.style.transition = animate ? '' : 'none';
  bar.style.transform = `translateX(${on.offsetLeft}px)`;
  bar.style.width = on.offsetWidth + 'px';
}

function onDashClick(e) {
  const b = e.target.closest('button');
  if (!b) return;
  const d = b.dataset;
  if (d.range) setRange(Number(d.range));
  else if (d.tab) setTab(d.tab);
  else if (d.filter) setFilter(d.filter);
  else if (d.jump) jumpTo(d.jump);
  else if (d.more) toggleReason(d.more);
  else if ('load' in d) loadMore(b);
  else if ('unfold' in d) unfold();
  else if ('fold' in d) fold();
  else if ('notes' in d) toggleNotes(b);
}

function setRange(r) {
  const win = DASH.win, seg = win.querySelector('.seg');
  DASH.range = r;
  seg.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', Number(b.dataset.range) === r));
  placeBar(seg, true);
  drawCurve();
  win.querySelector('.sc').innerHTML = scoreHtml();
}
/* 换页签、点筛选时看板还折着：底下的内容在折线下面，顺手展开 */
function setTab(t) {
  const tabs = DASH.win.querySelector('.tabs');
  DASH.tab = t;
  tabs.querySelectorAll('button').forEach(b => b.setAttribute('aria-selected', b.dataset.tab === t));
  placeBar(tabs, true);
  renderCards();
  if (!DASH.open) unfold();
}
function setFilter(f) {
  DASH.filter = f;
  DASH.shown = PAGE;
  DASH.win.querySelectorAll('[data-filter]').forEach(b => b.setAttribute('aria-pressed', b.dataset.filter === f));
  renderCards();
  if (!DASH.open) unfold();
}
/* 加载更早：接着往下再露一页，露完了钮就撤掉 */
function loadMore(b) {
  const list = tlList(), box = b.previousElementSibling, from = DASH.shown;
  DASH.shown += PAGE;
  box.insertAdjacentHTML('beforeend', list.slice(from, DASH.shown).map(decisionHtml).join(''));
  icons(box);
  if (DASH.shown >= list.length) b.remove();
}
/* 做完的交易 → 它的开 / 平仓那一轮或单笔复盘：切回时间线、筛选放开，那一条还没露出来就多露几页到它，滚过去，底色亮一下 */
function jumpTo(id) {
  DASH.filter = 'all';
  DASH.shown = Math.max(DASH.shown, Math.ceil((MOCK.decisions.findIndex(d => d.id === id) + 1) / PAGE) * PAGE);
  DASH.win.querySelectorAll('[data-filter]').forEach(b => b.setAttribute('aria-pressed', b.dataset.filter === 'all'));
  setTab('timeline');
  const card = document.getElementById('dec-' + id);
  card.scrollIntoView({ behavior: REDUCED ? 'auto' : 'smooth', block: 'center' });
  // 滚到了再亮：离得远的平滑滚过去要一会儿
  const io = new IntersectionObserver(es => {
    if (!es[0].isIntersecting) return;
    io.disconnect();
    card.classList.add('hl');
  }, { threshold: .6 });
  io.observe(card);
}
function toggleReason(id) {
  if (!DASH.reasons.delete(id)) DASH.reasons.add(id);
  const card = document.getElementById('dec-' + id);
  card.outerHTML = decisionHtml(decById(id));
  icons(document.getElementById('dec-' + id));
}
function toggleNotes(b) {
  const open = b.previousElementSibling.classList.toggle('open');
  b.setAttribute('aria-expanded', open);
  b.textContent = T(open ? DL.collapse : DL.expandFull);
}

/* 折线：页签那一行（单栏时是净值曲线）往下再露一截，压在渐隐层底下 */
function layoutDash() {
  const win = DASH.win, body = win.querySelector('.win-body');
  const anchor = body.querySelector(NARROW.matches ? '.w-curve' : '.tl-h');
  win.style.setProperty('--fold', `${Math.round(anchor.getBoundingClientRect().bottom - body.getBoundingClientRect().top + 110)}px`);
  measureNotes();
}
/* 展开 / 收起：窗口的高度一步到位，用 clip-path 把底边从折线滑到底（收起反过来） */
function clipWin(win, fromH, toH, ms, done) {
  const full = win.offsetHeight;
  // 底边在滑的时候带着窗口的圆角；滑到底时底边让到投影外面
  const clip = h => `inset(-1px -1px ${h >= full ? -100 : full - h}px -1px round 25px)`;
  win.style.transition = 'none';
  win.style.clipPath = clip(fromH);
  void win.offsetHeight;
  win.style.transition = `clip-path ${ms}ms cubic-bezier(.16, 1, .3, 1)`;
  win.style.clipPath = clip(toH);
  win.addEventListener('transitionend', function end(e) {
    if (e.target !== win || e.propertyName !== 'clip-path') return;
    win.removeEventListener('transitionend', end);
    win.style.transition = win.style.clipPath = '';
    if (done) done();
  });
}
function unfold() {
  const win = DASH.win, from = win.offsetHeight;
  DASH.open = true;
  win.classList.remove('folded');
  if (!REDUCED && !SHOT) clipWin(win, from, win.offsetHeight, 700);
}
function fold() {
  const win = DASH.win;
  DASH.open = false;
  win.scrollIntoView({ behavior: REDUCED ? 'auto' : 'smooth', block: 'start' });
  if (REDUCED || SHOT) { win.classList.add('folded'); return; }
  const full = win.offsetHeight;
  win.classList.add('folded');
  const to = win.offsetHeight;
  win.classList.remove('folded');
  // 收到一半又点了展开：那边已经把状态改回去，这里不再折
  clipWin(win, full, to, 480, () => { if (!DASH.open) win.classList.add('folded'); });
}

let dashRz = 0;
addEventListener('resize', () => {
  clearTimeout(dashRz);
  dashRz = setTimeout(() => { if (DASH.win) layoutDash(); }, 150);
});
/* 字体到了字宽会变：折线和笔记重量一次 */
document.fonts.ready.then(() => { if (DASH.win) layoutDash(); });
