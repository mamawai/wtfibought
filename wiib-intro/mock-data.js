'use strict';
/* ============================================================
   示例数据：一只虚构 trader（kestrel）的公开主页 + 首屏轮流上场的几只示例 trader + 首屏行情
   全部手编，只为展示版面与信息密度；不是实时，不对应任何真实账户
   格式照现行口径：决策正文是 [本轮结论] + 有变化的币各一段，单币轮只写自己那段；
   单笔复盘是【单笔复盘】+【归类】，统一复盘是【本期复盘】+【经验更新】+ 代码追加的【代码记账】；
   经验账本是代码渲染的文本（ExperienceLedger.render）：只有规律和破满 2 笔的执行缺口
   ============================================================ */

const HOUR = 3600 * 1000;
/* 新加坡时间 → 毫秒：mockAt('09-02 10:00') */
const mockAt = s => Date.parse(`2026-${s.replace(' ', 'T')}:00+08:00`);
/* 净值曲线从这一刻起，每小时一个点 */
const MOCK_T0 = mockAt('08-25 00:00');
/* 看板上的「现在」：心跳倒计时、到时刻的盯盘从这一刻往后走 */
const MOCK_NOW = Date.parse('2026-09-02T10:35:42+08:00');

/* 净值锚点 [小时序号, 权益]：起步平、拉升、一次 6.80% 回撤（08/28 20:00 → 08/29 18:00 ETH 止损）、再创新高。
   时间线上那几轮的权益都是锚点，曲线正好穿过它们 */
const EQ_ANCHORS = [
  [0, 10000], [18, 10064], [30, 9962], [44, 10118], [58, 10486], [72, 10982],
  [84, 11326], [92, 11480], [100, 11105], [108, 10842], [109, 10842.40], [114, 10699.40],
  [129, 10918.30], [146, 11236], [153, 11251.80], [159, 11318.90], [161, 11590], [172, 11720],
  [182, 11660], [188, 11668.58], [189, 11659.10], [194, 11712], [200, 11785.96], [201, 11748.52], [202, 11842.60]
];

function mockSeeded(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}

/* 按锚点 [小时序号, 权益] 从 t0 起每小时一个点 */
function buildEquityCurve(anchors, t0, seed) {
  const rnd = mockSeeded(seed);
  const pts = [];
  const last = anchors[anchors.length - 1][0];
  let ai = 0;
  for (let h = 0; h <= last; h++) {
    while (h > anchors[ai + 1][0]) ai++;
    const [h0, v0] = anchors[ai], [h1, v1] = anchors[ai + 1];
    const k = (h - h0) / (h1 - h0);
    const sm = k * k * (3 - 2 * k);            // 平滑插值，拐点不生硬
    const base = v0 + (v1 - v0) * sm;
    // 噪声在锚点处收成 0，锚点上的值原样落在曲线上；前后两个锚点一样（没仓位）的那段不加
    const noise = v0 === v1 ? 0 : (rnd() - .5) * 2 * base * .0045 * Math.sin(k * Math.PI);
    pts.push({ t: t0 + h * HOUR, v: Math.round((base + noise) * 100) / 100 });
  }
  return pts;
}

const MOCK = {
  trader: {
    name: 'kestrel',
    model: 'claude-opus-5',
    interval: '1h',
    wakeWindow: '08:00–02:00',
    beatsPerDay: 19,
    status: 'RUNNING',
    symbols: ['BTCUSDT', 'ETHUSDT', 'SOLUSDT'],
    round: 2,
    seed: 10000,
    equity: 11842.60,
    pnlPct: 18.43,
    day: 9,
    closed: 14,
    winRate: 57,
    maxDd: -6.8,
    tokensRound: 6824300
  },
  curve: buildEquityCurve(EQ_ANCHORS, MOCK_T0, 7),

  /* 现价取首屏行情 MARKET 里的 ETH、BTC（看板上会小幅漂移）：持仓浮盈、挂单离成交多远、盯盘离触发多远都按它算 */
  positions: [
    { symbol: 'ETHUSDT', side: 'SHORT', lev: 8, qty: 2.4, entry: 3142.50, sl: 3120.00, tp: 2990.00, opened: mockAt('09-01 21:00') }
  ],
  /* 挂单带着随单保护：成交后的止损止盈 */
  orders: [
    { symbol: 'BTCUSDT', side: 'OPEN_LONG', lev: 5, qty: 0.06, limit: 78420.00, sl: 77800.00, tp: 81200.00 }
  ],

  /* 交易计划：同币同向有持仓挂持仓卡，没有就挂开仓挂单；revisions 是修订记录，一条一行 */
  plans: [
    {
      symbol: 'ETHUSDT', side: 'SHORT', play: 'PULLBACK',
      basis: {
        zh: '资金费连续 3 期为负（−0.018%），1h 反弹收在 3,142.5，停在前高 3,160 下方；4h ADX 31、−DI 28 > +DI 19，趋势仍向下；盘口 25bp 内卖压 2.1×',
        en: 'funding negative for 3 periods (−0.018%); the 1h bounce closed at 3,142.5, stalling under the prior high 3,160; 4h ADX 31, −DI 28 > +DI 19, trend still down; asks 2.1× bids within 25bp'
      },
      invalidation: { zh: '1h 收盘站上 3,208（前高 + 1.5×ATR）', en: 'a 1h close above 3,208 (prior high + 1.5×ATR)' },
      revisions: [
        { at: mockAt('09-02 08:56'), type: 'moveStop', change: '3260→3208',
          reason: { zh: '振幅放大，止损放到失效位上', en: 'range widened; stop moved onto the invalidation level' } },
        { at: mockAt('09-02 09:40'), type: 'moveStop', change: '3208→3120',
          reason: { zh: '5 分钟收在 3,080 下方，止损压到成本下方锁利润', en: '5m close under 3,080; stop moved under cost to lock in profit' } }
      ]
    },
    {
      symbol: 'BTCUSDT', side: 'LONG', play: 'PULLBACK',
      basis: {
        zh: '4h 回踩通道下沿 78,4xx，结构没坏；不追现价，挂限价等回落',
        en: 'a 4h retest of the channel floor at 78,4xx with the structure intact; no chasing, a limit waits for the dip'
      },
      invalidation: { zh: '4h 收盘跌破 77,800（通道下沿失守）', en: 'a 4h close under 77,800 (channel floor lost)' },
      revisions: []
    }
  ],

  /* 它给自己布的盯盘条件（kind 同 ai_trader_watch）；每条带一句备注，preset 是预案：命中时平台先替它市价平掉这个仓位的 pct%，再叫醒它 */
  watches: [
    { symbol: 'ETHUSDT', kind: 'CLOSE_ABOVE', period: '15m', level: 3080.00, setAt: mockAt('09-02 09:40'),
      note: { zh: '站回 3,080 说明跌破是假的，复查要不要提前离场', en: 'back above 3,080 means the break was fake; review an early exit' } },
    { symbol: 'ETHUSDT', kind: 'TOUCH_BELOW', level: 3010.00, setAt: mockAt('09-02 09:40'),
      note: { zh: '到这儿先落袋一半，剩下的等 2,990', en: 'bank half here; the rest waits for 2,990' },
      preset: { side: 'SHORT', pct: 50 } },
    { symbol: 'BTCUSDT', kind: 'CLOSE_BELOW', period: '4h', level: 77800.00, setAt: mockAt('09-02 10:00'),
      note: { zh: '通道下沿失守，撤掉限价单', en: 'channel floor lost; cancel the limit order' } },
    { symbol: 'BTCUSDT', kind: 'AT_TIME', at: mockAt('09-02 20:25'), setAt: mockAt('09-02 10:00'),
      note: { zh: '数据公布前复查一次挂单', en: 'review the order before the data release' } }
  ],

  /* 经验账本 = 统一复盘后代码渲染的文本，整份覆盖 ai_trader.memory；按条目编号排，同一单子类型归一组。
     #12（08:00 新增的假设）不给 trader 看，所以不在这里 */
  memory: {
    at: mockAt('09-02 08:00'),
    zh: `【经验账本】
〔BREAKOUT〕
#2 规律·限制型 ｜ 4h ADX 低于 20 → 不做突破单，等它走出区间 ｜ 违反 5 笔，赚 1 亏 4，合计 -386.20 USDT
〔通用〕
#3 执行缺口 ｜ 失效条件触发 → 当根收盘就平，不等确认根 ｜ 碰到 5 次，做到 3 次
〔PULLBACK〕
#5 规律·机会型 ｜ 资金费连续 3 期为负且反弹停在前高下方 → 做空反弹结束，不追跌 ｜ 照做 5 笔，赚 4 亏 1，合计 +968.40 USDT`,
    en: `[EXPERIENCE LEDGER]
— BREAKOUT —
#2 rule·restriction | 4h ADX below 20 → no breakout trades; wait for it to leave the range | violated in 5 trades: 1 won, 4 lost, net -386.20 USDT
— general —
#3 execution gap | invalidation triggered → close on that candle's close, no confirmation candle | came up 5 times, done 3 times
— PULLBACK —
#5 rule·opportunity | funding negative 3 periods and the bounce stalls below the prior high → short the end of the bounce, never chase the drop | followed in 5 trades: 4 won, 1 lost, net +968.40 USDT`
  },

  /* 时间线，新在前。kind：TRADE 例行 / WATCH 盯盘触发 / ALERT 剧烈波动 / DEBRIEF 单笔复盘 / REVIEW 统一复盘；
     symbol 是单币轮只处理的那个币，position 是单笔复盘复的那一笔。
     acts 是交易动作：open_position 开仓（entries 各档，rejected 是护栏拒因）、close_position 平仓回执、
     set_stop_loss 原来 → 现在、set_watch 给哪个币布了几条；reason 是调工具时写的那句话 */
  decisions: [
    {
      id: '0902-1000', kind: 'TRADE', at: mockAt('09-02 10:00'), equity: 11842.60,
      tokens: 41200, latency: 38.4, toolCalls: 4, modelCalls: 5,
      looked: ['klines', 'indicators', 'market_snapshot'],
      acts: [{ tool: 'set_watch', symbol: 'BTCUSDT', n: 2 }],
      reasoning: {
        zh: `[本轮结论]
总评：ETH 空单照计划拿着，BTC 限价单继续挂，仓位不动；给 BTC 布了两条盯盘。
[BTCUSDT]
判断：限价 78,420 没成交，现价 78,912，比挂单高 0.6%；4h 回踩通道下沿的结构没变。20:30 有美国 High 级数据。
动作：HOLD，挂单留着。布两条盯盘：4 小时收盘跌破 77,800 就撤单；20:25 数据公布前复查一次挂单。
等待：回落到 78,420 成交；4 小时收盘跌破 77,800 撤单。`,
        en: `[ROUND CONCLUSION]
Overall: the ETH short is held as planned and the BTC limit stays; positions untouched. Two watches set on BTC.
[BTCUSDT]
Judgement: the 78,420 limit is unfilled, mark 78,912 is 0.6% above it; the 4h retest of the channel floor is intact. A US High-impact release lands at 20:30.
Action: HOLD, order stays. Two watches: a 4h close under 77,800 cancels the order; a review at 20:25 before the release.
Waiting: a dip to 78,420 to fill; a 4h close under 77,800 to cancel.`
      }
    },
    {
      id: '0902-0940', kind: 'WATCH', symbol: 'ETHUSDT', at: mockAt('09-02 09:40'), equity: 11815.72,
      tokens: 24800, latency: 21.3, toolCalls: 4, modelCalls: 3,
      looked: ['klines', 'orderbook_depth'],
      acts: [
        { tool: 'set_stop_loss', symbol: 'ETHUSDT', from: 3208, to: 3120,
          reason: { zh: '5 分钟收在 3,080 下方，止损压到成本下方锁利润', en: '5m close under 3,080; stop moved under cost to lock in profit' } },
        { tool: 'set_watch', symbol: 'ETHUSDT', n: 2 }
      ],
      reasoning: {
        zh: `[本轮结论]
[ETHUSDT]
判断：命中的是「5 分钟 K 线收在 3,080 以下」，备注写的是跌破就把止损收到成本下方锁利润。09:35 那根 5 分钟收在 3,076、最低 3,071，是实打实的收盘，不是插针；盘口 25bp 内卖压 1.8×。
动作：止损 3,208 → 3,120，压到成本 3,142.5 下方，这笔最差也是小赚。盯盘换成两条：15 分钟收盘站回 3,080 就复查要不要提前离场；跌到 3,010 由预案先市价平掉一半。
等待：到 2,990 止盈；两条盯盘。`,
        en: `[ROUND CONCLUSION]
[ETHUSDT]
Judgement: the watch that fired is "5m candle closes below 3,080", and its note said to move the stop under cost on the break. The 09:35 5m candle closed at 3,076 with a low of 3,071 — a real close, not a wick; asks 1.8× bids within 25bp.
Action: stop 3,208 → 3,120, under the 3,142.5 cost, so the worst case is a small win. Watches replaced with two: a 15m close back above 3,080 means reviewing an early exit; a drop to 3,010 has a standing order close half at market first.
Waiting: 2,990 for the take-profit; the two watches.`
      }
    },
    {
      id: '0902-0920', kind: 'DEBRIEF', position: 1187, at: mockAt('09-02 09:20'),
      tokens: 18600, latency: 52.0, toolCalls: 5, modelCalls: 6,
      looked: ['round_detail', 'klines', 'news_archive'],
      reasoning: {
        zh: `【单笔复盘】
路径：08/31 15:00 市价多在 79,210，最大浮盈到 79,690（+0.63R）；09/01 19:00 那根 1h 收 78,870，跌破失效位 78,900；20:00 才平在 78,640，实际 −0.75R。现在只看得到平仓后 13 小时 20 分，这段最低 78,050。
方向错了还是被洗掉：首档成交后 24h 最大顺向 +0.63R、最大逆向 −0.42R；原样止损 78,450 在平仓后 6 小时也会被打到，放宽到 2 倍那档还没了结。出场后的走势只看得到 13 小时，先不下结论。
问题出在哪：不在止损宽度，也不在进场；失效条件触发后多拿了一根，19:00 当根平能少亏 69.00（0.3 张 × 230）。
管理动作：减分。照计划 19:00 当根平是 −0.45R，实际 −0.75R。
有没有写下要做却没照做的：有。计划的失效条件是「1h 收盘跌破 78,900」，19:00 那轮看到 78,870 写了「再看一根」，20:00 才平。

【归类】
#2 无
#3 破 1187 ｜ 19:00 收 78,870 已触发失效，等了一根才平
#5 无
#12 无`,
        en: `[DEBRIEF]
Path: long at 79,210 at market on 08/31 15:00, best 79,690 (+0.63R); the 19:00 1h candle on 09/01 closed at 78,870, under the 78,900 invalidation; closed only at 20:00 at 78,640, actual −0.75R. Only 13h 20m after the close are visible so far; the low in that stretch was 78,050.
Wrong direction or shaken out: within 24h of the fill, best +0.63R, worst −0.42R; the original stop at 78,450 would also have been hit 6 hours after the close, and the 2× stop has not settled. With only 13 hours after the exit visible, I am not calling it yet.
Where the problem lay: not the stop width or the entry: one extra candle held after the invalidation fired; closing on the 19:00 candle would have lost 69.00 less (0.3 × 230).
Managing the position: cost. Closing on the 19:00 candle as planned was −0.45R; actual −0.75R.
Anything written down and not done: yes. The plan's invalidation is "a 1h close under 78,900"; at 19:00 I saw 78,870, wrote "one more candle", and closed at 20:00.

[SORTING]
#2 NONE
#3 BROKEN 1187 | the 19:00 close at 78,870 had triggered the invalidation; waited a candle
#5 NONE
#12 NONE`
      }
    },
    {
      id: '0902-0900', kind: 'TRADE', at: mockAt('09-02 09:00'), equity: 11748.52,
      tokens: 19800, latency: 17.2, toolCalls: 2, modelCalls: 2,
      looked: ['klines', 'market_snapshot'],
      acts: [],
      reasoning: {
        zh: `[本轮结论]
总评：ETH 空单的止损 08:56 已经收到失效位 3,208，照计划拿着；BTC 限价单照旧，仓位不动。`,
        en: `[ROUND CONCLUSION]
Overall: the ETH short's stop was already moved onto the 3,208 invalidation at 08:56, held as planned; the BTC limit stays, positions untouched.`
      }
    },
    {
      id: '0902-0856', kind: 'ALERT', symbol: 'ETHUSDT', at: mockAt('09-02 08:56'), equity: 11748.52,
      tokens: 28900, latency: 24.1, toolCalls: 3, modelCalls: 3,
      looked: ['klines', 'orderbook_depth'],
      acts: [
        { tool: 'set_stop_loss', symbol: 'ETHUSDT', from: 3260, to: 3208,
          reason: { zh: '振幅放大，止损放到失效位上', en: 'range widened; stop moved onto the invalidation level' } }
      ],
      reasoning: {
        zh: `[本轮结论]
[ETHUSDT]
判断：5 分钟振幅 2.4%（阈值 1.6% × 灵敏度 1.2），刚收盘的 15 分钟反弹到 3,118，离前高 3,160 还有 1.3%；盘口 25bp 内买盘 1.8× 卖盘，短线有承接，但没过前高，论点没变。
动作：止损 3,260 → 3,208。止损比失效位多留 1.6% 没有意义，就放在失效位上。
等待：1h 收盘站上 3,208 当根就平（#3），不等确认根；到 2,990 止盈。`,
        en: `[ROUND CONCLUSION]
[ETHUSDT]
Judgement: 5-minute range 2.4% (threshold 1.6% × sensitivity 1.2); the 15m candle that just closed bounced to 3,118, 1.3% under the prior high 3,160; bids 1.8× asks within 25bp — short-term support, but the high is not cleared and the thesis stands.
Action: stop 3,260 → 3,208. Leaving 1.6% beyond the invalidation made no sense; the stop sits on it.
Waiting: a 1h close above 3,208 closes it on that candle (#3), no confirmation candle; 2,990 for the take-profit.`
      }
    },
    {
      id: '0902-0800r', kind: 'REVIEW', at: mockAt('09-02 08:00'),
      tokens: 52100, latency: 44.6, modelCalls: 1,
      reasoning: {
        zh: `【本期复盘】
战绩：本批单子 2 笔：2 胜 0 负（胜率 100%），合计净盈亏 +486.10（已计入手续费与资金费）
PULLBACK（2 笔，2 胜，净 +486.10）
分界线：全赢，看不出分界线。共同点：两笔都等反弹停在前高下方、资金费连续为负才进空，没有追跌；1186 在前高下方挂限价进，最大逆向 −0.2R，1184 市价进，最大逆向 −0.6R。
机会：本期最大的波段是 ETH 08/31 09:00 到 09/01 05:00 从 3,168 跌到 3,031；1184 拿到前一段，止盈之后反弹没到前高就接着跌，没有能做的反弹，是条件没出现。
布防：期间布了 3 条，命中 1 条：ETH「15 分钟收盘站回 3,120」08/31 13:15 命中，那轮照备注把止损提到成本，布得好；另两条没碰到。

【经验更新】
新增 PULLBACK 入场条件 机会型 ｜ 反弹停在前高下方 → 在前高下方 0.5% 以内挂限价空，不市价追 ｜ 1186 限价进最大逆向 −0.2R，1184 市价进 −0.6R ｜ 名下 1186

【代码记账】下面这些是代码按名下单子的账做的：
- #5 转为规律（当前段名下 5 笔，3 个平仓日，合计 +968.40）：资金费连续 3 期为负且反弹停在前高下方 → 做空反弹结束，不追跌`,
        en: `[REVIEW]
Scorecard: This batch: 2 trades, 2 won / 0 lost (win rate 100%), net P&L +486.10 (after fees and funding)
PULLBACK (2 trades, 2 won, net +486.10)
Dividing line: all won, so no dividing line. In common: both shorts waited for the bounce to stall below the prior high with funding negative in a row, and neither chased the drop; 1186 went in with a limit under the prior high and saw −0.2R at worst, 1184 went in at market and saw −0.6R.
Opportunity: the period's biggest swing was ETH 3,168 → 3,031 from 08/31 09:00 to 09/01 05:00; 1184 took the first leg, and after its target no bounce reached the prior high before the next leg down — the condition never appeared.
Watches: 3 set, 1 hit: ETH "15m close back above 3,120" fired at 08/31 13:15 and that round moved the stop to cost as the note said — well placed; the other two were never touched.

[LEDGER UPDATES]
ADD PULLBACK ENTRY OPPORTUNITY | bounce stalls below the prior high → rest a limit short within 0.5% under that high, never chase at market | 1186 by limit saw −0.2R at worst, 1184 at market −0.6R | UNDER 1186

[KEPT BY CODE] Done by code from the tally of the trades under each entry:
- #5 promoted to rule (5 trades under it this segment, 3 closing days, net +968.40): funding negative 3 periods and the bounce stalls below the prior high → short the end of the bounce, never chase the drop`
      }
    },
    {
      id: '0902-0800', kind: 'TRADE', at: mockAt('09-02 08:00'), equity: 11785.96,
      tokens: 33600, latency: 29.5, toolCalls: 3, modelCalls: 4,
      looked: ['klines', 'indicators', 'funding_history'],
      acts: [],
      reasoning: {
        zh: `[本轮结论]
总评：ETH 空单照计划拿着，BTC 限价单还挂着，三个币都没有变化。`,
        en: `[ROUND CONCLUSION]
Overall: the ETH short is held as planned, the BTC limit is still resting; no symbol changed.`
      }
    },
    {
      id: '0901-2100', kind: 'TRADE', at: mockAt('09-01 21:00'), equity: 11659.10,
      tokens: 58700, latency: 66.9, toolCalls: 8, modelCalls: 7,
      looked: ['klines', 'market_snapshot', 'funding_history', 'orderbook_depth'],
      acts: [
        { tool: 'open_position', symbol: 'ETHUSDT', side: 'SHORT', lev: 25, play: 'PULLBACK',
          entries: [{ qty: 2.4 }], sl: 3260, tp: [[2990, 100]],
          rejected: { zh: '杠杆必须在3~10倍之间（主人设定，不可协商），你给了25', en: 'leverage must land within 3~10x (set by your owner, not negotiable); you gave 25' } },
        { tool: 'open_position', symbol: 'ETHUSDT', side: 'SHORT', lev: 8, play: 'PULLBACK',
          entries: [{ qty: 2.4, status: 'filled', filled: 3142.5 }], sl: 3260, tp: [[2990, 100]] },
        { tool: 'open_position', symbol: 'BTCUSDT', side: 'LONG', lev: 5, play: 'PULLBACK',
          entries: [{ limit: 78420, qty: 0.06, status: 'pending' }], sl: 77800, tp: [[81200, 100]] },
        { tool: 'set_watch', symbol: 'ETHUSDT', n: 1 }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：开 ETH 空 8x；BTC 在通道下沿挂限价多单等回落。
[ETHUSDT]
判断：1h 收 3,142.5，反弹停在前高 3,160 下方没过去；资金费连续 3 期为负（−0.018%），4h ADX 31、−DI 28 > +DI 19，趋势仍向下；盘口 25bp 内卖压 2.1×。
动作：市价空 2.4 张。25x 被护栏拒（主人区间 3–10x），改 8x 成交在 3,142.5；止损 3,260、目标 2,990。布一条盯盘：5 分钟收在 3,080 以下就把止损收到成本下方。
等待：1h 收盘站上 3,208 就平；到 2,990 止盈。
[BTCUSDT]
判断：平掉多单后回到 78,700 一带，4h 通道下沿在 78,4xx，结构没坏；不追现价。
动作：限价 78,420 挂多 0.06 张 5x，止损 77,800、目标 81,200。
等待：回落成交；4h 收盘跌破 77,800 失效。`,
        en: `[ROUND CONCLUSION]
Overall: short ETH at 8x; a BTC limit long rests at the channel floor for the dip.
[ETHUSDT]
Judgement: 1h close 3,142.5, the bounce stalled under the prior high 3,160; funding negative for 3 periods (−0.018%), 4h ADX 31 with −DI 28 > +DI 19, trend still down; asks 2.1× bids within 25bp.
Action: short 2.4 at market. 25x was rejected by the guard (owner range 3–10x); resubmitted at 8x, filled at 3,142.5. Stop 3,260, target 2,990. One watch: a 5m close under 3,080 moves the stop under cost.
Waiting: a 1h close above 3,208 to exit; 2,990 for the take-profit.
[BTCUSDT]
Judgement: back around 78,700 after the long was closed; the 4h channel floor sits at 78,4xx and the structure is intact; no chasing.
Action: limit long 0.06 at 78,420, 5x, stop 77,800, target 81,200.
Waiting: the dip to fill; a 4h close under 77,800 invalidates it.`
      }
    },
    {
      id: '0901-2000', kind: 'TRADE', at: mockAt('09-01 20:00'), equity: 11668.58,
      tokens: 36300, latency: 31.8, toolCalls: 3, modelCalls: 4,
      looked: ['klines', 'kline_structure'],
      acts: [
        { tool: 'close_position', symbol: 'BTCUSDT', qty: 0.3, filled: 78640, pnl: -171.00,
          reason: { zh: '1h 收盘跌破失效位 78,900', en: '1h close under the 78,900 invalidation' } }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：BTC 失效条件触发，平掉多单，不反手。
[BTCUSDT]
判断：1h 收 78,640，连着两根收在失效位 78,900 下方，突破失败。19:00 那根已经收在 78,870，我写了「再看一根」，这正是 #3 说的，不该等。
动作：平掉 0.3 张，成交 78,640，已实现 −171.00。
等待：无
[ETHUSDT]
判断：1h 反弹到 3,121，离前高 3,160 还有 1.2%；资金费连续 3 期为负（−0.018%）。
动作：HOLD（空仓）
等待：反弹停在 3,140–3,160 且资金费仍为负，再考虑做空。`,
        en: `[ROUND CONCLUSION]
Overall: the BTC invalidation fired; long closed, no reversal.
[BTCUSDT]
Judgement: 1h close 78,640, the second close in a row under the 78,900 invalidation; the breakout failed. The 19:00 candle had already closed at 78,870 and I wrote "one more candle" — exactly what #3 says not to do.
Action: closed 0.3 at 78,640, realized −171.00.
Waiting: none
[ETHUSDT]
Judgement: 1h bounced to 3,121, 1.2% under the prior high 3,160; funding negative for 3 periods (−0.018%).
Action: HOLD (flat)
Waiting: the bounce stalling at 3,140–3,160 with funding still negative before a short.`
      }
    },
    {
      id: '0901-0200', kind: 'DEBRIEF', position: 1182, at: mockAt('09-01 02:10'),
      tokens: 14200, latency: 38.7, toolCalls: 3, modelCalls: 4,
      looked: ['round_detail', 'klines'],
      reasoning: {
        zh: `【单笔复盘】
路径：08/30 09:00 市价多在 178.40，最大逆向 177.10（−0.41R）；20:00 浮盈到 +1R 时止损提到成本；08/31 02:00 止盈 186.90 带走，实际 +2.62R。
对在哪：入场依据「回踩 4h 通道下沿企稳」被证实，之后再没跌回 177.10 下方。
少赚了多少：出场后 4h / 24h 最有利 188.20 / 189.60，比出场价多 2.70，少赚 1.4%。
管理动作：不加分也不减分。止损提到成本之后没再回踩，止损原样那一档也是到目标，+2.62R。
有没有写下要做却没照做的：无。

【归类】
#2 无
#3 无
#5 无`,
        en: `[DEBRIEF]
Path: long at 178.40 at market on 08/30 09:00, worst 177.10 (−0.41R); at +1R at 20:00 the stop was raised to cost; the 186.90 target took it on 08/31 02:00, actual +2.62R.
What was right: the entry reason "a retest of the 4h channel floor holding" was confirmed; price never went back under 177.10.
Left on the table: best 188.20 / 189.60 in the 4h / 24h after the exit, 2.70 above the exit price, 1.4% left.
Managing the position: neither added nor cost. After the stop went to cost there was no retest, and the original-stop rung also reached the target at +2.62R.
Anything written down and not done: none.

[SORTING]
#2 NONE
#3 NONE
#5 NONE`
      }
    },
    {
      id: '0831-1500', kind: 'TRADE', at: mockAt('08-31 15:00'), equity: 11318.90,
      tokens: 47900, latency: 52.6, toolCalls: 6, modelCalls: 6,
      looked: ['klines', 'indicators', 'funding_history', 'orderbook_depth'],
      acts: [
        { tool: 'open_position', symbol: 'BTCUSDT', side: 'LONG', lev: 5, play: 'BREAKOUT',
          entries: [{ qty: 0.3, status: 'filled', filled: 79210 }], sl: 78450, tp: [[80800, 100]] }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：开 BTC 多 5x；ETH、SOL 两笔空单照计划拿着，BTC 走得比它们强。
[BTCUSDT]
判断：1h 放量（1.9×MA20）收在 79,180，站上 79,000 区间上沿；4h ADX 27，高于 #2 的 20，+DI 24 > −DI 15；资金费 +0.004%，不拥挤。
动作：市价多 0.3 张 5x，成交 79,210；止损 78,450、目标 80,800。
等待：1h 收盘跌破 78,900（突破位失守）就平；到 80,800 止盈。`,
        en: `[ROUND CONCLUSION]
Overall: long BTC at 5x; the ETH and SOL shorts are held as planned, BTC is the stronger one.
[BTCUSDT]
Judgement: 1h closed at 79,180 on 1.9× MA20 volume, above the 79,000 range top; 4h ADX 27, above the 20 in #2, +DI 24 > −DI 15; funding +0.004%, not crowded.
Action: long 0.3 at market, 5x, filled at 79,210; stop 78,450, target 80,800.
Waiting: a 1h close under 78,900 (breakout level lost) to exit; 80,800 for the take-profit.`
      }
    },
    {
      id: '0830-1800', kind: 'DEBRIEF', position: 1179, at: mockAt('08-30 18:10'),
      tokens: 12900, latency: 33.1, toolCalls: 2, modelCalls: 3,
      looked: ['round_detail', 'indicators'],
      reasoning: {
        zh: `【单笔复盘】
路径：08/29 13:00 市价空在 3,061，最大浮盈只到 3,049（+0.32R）；17:40 反弹打到止损 3,098 出场，实际 −1.03R。出场后 24h 最高 3,138，没再回到 3,061 下方。
方向错了还是被洗掉：方向错了。首档成交后 24h 最大顺向 +0.32R、最大逆向 −2.08R，止损放宽到 2 倍也是止损。
问题出在哪：不在止损宽度，在进场：4h ADX 18，区间里的跌破没有延续，1.6× 的量只撑了一根。
管理动作：没有动过，实际和止损原样一样。
有没有写下要做却没照做的：无。

【归类】
#2 名下 1179 ｜ 开仓时 4h ADX 18，低于 20 还是做了突破
#3 无
#5 无`,
        en: `[DEBRIEF]
Path: short at 3,061 at market on 08/29 13:00, best only 3,049 (+0.32R); the bounce hit the 3,098 stop at 17:40, actual −1.03R. The high in the 24h after the exit was 3,138, and price never came back under 3,061.
Wrong direction or shaken out: wrong direction. Within 24h of the fill, best +0.32R, worst −2.08R; even a 2× stop gets stopped.
Where the problem lay: not the stop width but the entry: 4h ADX 18, the break out of a range did not follow through, and the 1.6× volume lasted one candle.
Managing the position: untouched; actual equals the original-stop rung.
Anything written down and not done: none.

[SORTING]
#2 UNDER 1179 | 4h ADX 18 at entry, under 20, breakout taken anyway
#3 NONE
#5 NONE`
      }
    },
    {
      id: '0830-0900', kind: 'TRADE', at: mockAt('08-30 09:00'), equity: 10918.30,
      tokens: 39400, latency: 41.0, toolCalls: 5, modelCalls: 5,
      looked: ['klines', 'indicators', 'funding_history'],
      acts: [
        { tool: 'open_position', symbol: 'SOLUSDT', side: 'LONG', lev: 6, play: 'PULLBACK',
          entries: [{ qty: 36, status: 'filled', filled: 178.4 }], sl: 175.2, tp: [[186.9, 100]] }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：开 SOL 多 6x。
[SOLUSDT]
判断：回踩到 4h 通道下沿 178 一带企稳，1h RSI 从 31 拐头向上；资金费 −0.006%，多头不拥挤。
动作：市价多 36 张 6x，成交 178.40；止损 175.20（下沿下方）、目标 186.90（通道中轨）。
等待：15 分钟收盘跌破 175.2 就平；到 186.90 止盈。`,
        en: `[ROUND CONCLUSION]
Overall: long SOL at 6x.
[SOLUSDT]
Judgement: the retest of the 4h channel floor around 178 is holding, 1h RSI turning up from 31; funding −0.006%, longs not crowded.
Action: long 36 at market, 6x, filled at 178.40; stop 175.20 (under the floor), target 186.90 (channel midline).
Waiting: a 15m close under 175.2 to exit; 186.90 for the take-profit.`
      }
    },
    {
      id: '0829-1300', kind: 'TRADE', at: mockAt('08-29 13:00'), equity: 10842.40,
      tokens: 44100, latency: 47.3, toolCalls: 5, modelCalls: 5,
      looked: ['klines', 'indicators', 'orderbook_depth'],
      acts: [
        { tool: 'open_position', symbol: 'ETHUSDT', side: 'SHORT', lev: 8, play: 'BREAKOUT',
          entries: [{ qty: 3.23, status: 'filled', filled: 3061 }], sl: 3098, tp: [[2960, 100]] }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：开 ETH 空 8x。
[ETHUSDT]
判断：1h 放量（1.6×MA20）收在 3,061，跌破 3,070 区间下沿；4h −DI 23 > +DI 18，但 ADX 只有 18，低于 #2 写的 20。这根是这周最大的量，我判断这次能走出区间。
动作：市价空 3.23 张 8x，成交 3,061；止损 3,098（区间内侧）、目标 2,960。
等待：1h 收盘站回 3,090 就平；到 2,960 止盈。`,
        en: `[ROUND CONCLUSION]
Overall: short ETH at 8x.
[ETHUSDT]
Judgement: 1h closed at 3,061 on 1.6× MA20 volume, under the 3,070 range floor; 4h −DI 23 > +DI 18, but ADX is only 18, under the 20 in #2. This is the week's biggest volume candle, and I judge it will leave the range this time.
Action: short 3.23 at market, 8x, filled at 3,061; stop 3,098 (inside the range), target 2,960.
Waiting: a 1h close back above 3,090 to exit; 2,960 for the take-profit.`
      }
    }
  ],

  /* 做完的交易（本局 14 笔，这里只列 3 笔）；open / close / debrief 指向时间线上那一轮，止损止盈带走的没有平仓那一轮 */
  trades: [
    {
      id: 1187, symbol: 'BTCUSDT', side: 'LONG', lev: 5, manner: 'manual', entry: 79210.00, exit: 78640.00, pnl: -171.00,
      opened: mockAt('08-31 15:00'), closed: mockAt('09-01 20:00'),
      play: 'BREAKOUT',
      basis: {
        zh: '1h 放量（1.9×MA20）站上 79,000 区间上沿，4h ADX 27、+DI 24 > −DI 15，资金费 +0.004% 不拥挤',
        en: '1h close above the 79,000 range top on 1.9× MA20 volume; 4h ADX 27, +DI 24 > −DI 15; funding +0.004%, not crowded'
      },
      invalidation: { zh: '1h 收盘跌破 78,900（突破位失守）', en: 'a 1h close under 78,900 (breakout level lost)' },
      sl: 78450.00, tp: [[80800.00, 100]], revisions: [],
      open: '0831-1500', close: '0901-2000', debrief: '0902-0920'
    },
    {
      id: 1182, symbol: 'SOLUSDT', side: 'LONG', lev: 6, manner: 'takeProfit', entry: 178.40, exit: 186.90, pnl: 306.20,
      opened: mockAt('08-30 09:00'), closed: mockAt('08-31 02:00'),
      play: 'PULLBACK',
      basis: {
        zh: '回踩 4h 通道下沿 178 一带企稳，1h RSI 从 31 拐头；资金费 −0.006%',
        en: 'a retest of the 4h channel floor around 178 holding, 1h RSI turning up from 31; funding −0.006%'
      },
      invalidation: { zh: '15m 收盘跌破 175.2（回踩带下沿）', en: 'a 15m close under 175.2 (bottom of the retest band)' },
      sl: 175.20, tp: [[186.90, 100]],
      revisions: [
        { at: mockAt('08-30 20:00'), type: 'moveStop', change: '175.2→178.4', reason: { zh: '浮盈到 +1R，止损提到成本', en: 'up +1R; stop raised to cost' } }
      ],
      open: '0830-0900', debrief: '0901-0200'
    },
    {
      id: 1179, symbol: 'ETHUSDT', side: 'SHORT', lev: 8, manner: 'stopLoss', entry: 3061.00, exit: 3098.00, pnl: -119.60,
      opened: mockAt('08-29 13:00'), closed: mockAt('08-29 18:00'),
      play: 'BREAKOUT',
      basis: {
        zh: '1h 放量（1.6×MA20）跌破 3,070 区间下沿，4h −DI 23 > +DI 18',
        en: '1h close under the 3,070 range floor on 1.6× MA20 volume; 4h −DI 23 > +DI 18'
      },
      invalidation: { zh: '1h 收盘站回 3,090', en: 'a 1h close back above 3,090' },
      sl: 3098.00, tp: [[2960.00, 100]], revisions: [],
      open: '0829-1300', debrief: '0830-1800'
    }
  ]
};

/* 首屏轮流上场的示例 trader：第一只就是 kestrel（和看板同一份），后三只只在首屏出现。
   曲线锚点写法同 EQ_ANCHORS，都画到 09/02 10:00；events 是回放时一条条滑进卡里的唤醒，旧在前，最后三条就是停在「现在」时卡上那三条。
   kind：TRADE 例行 / WATCH 盯盘触发 / EVENT 持仓有变化 / ALERT 剧烈波动 / DRAWDOWN 权益回落 / MANUAL 主人叫醒 / DEBRIEF 单笔复盘 / REVIEW 统一复盘；
   sym 是单币轮只处理的那个币，单笔复盘是复的那一笔（带 side） */
const HERO_TRADERS = [
  {
    ...MOCK.trader, t0: MOCK_T0, curve: MOCK.curve, dash: true,
    events: [
      { at: mockAt('08-29 13:00'), kind: 'TRADE', d: { zh: '开 ETH 空 8x，成交 3,061', en: 'Short ETH 8x, filled at 3,061' } },
      { at: mockAt('08-30 18:10'), kind: 'DEBRIEF', sym: 'ETHUSDT', side: 'SHORT', d: { zh: '方向错了：区间里的跌破没延续', en: 'Wrong direction: the break never carried' } },
      { at: mockAt('08-31 15:00'), kind: 'TRADE', d: { zh: '开 BTC 多 5x，成交 79,210', en: 'Long BTC 5x, filled at 79,210' } },
      { at: mockAt('09-01 21:00'), kind: 'TRADE', d: { zh: '25x 被护栏拒，改 8x 开 ETH 空', en: '25x rejected by the guard; ETH short at 8x' } },
      { at: mockAt('09-02 08:00'), kind: 'REVIEW', d: { zh: '#5 转为规律，经验账本更新', en: '#5 promoted to a rule; ledger updated' } },
      { at: mockAt('09-02 08:56'), kind: 'ALERT', sym: 'ETHUSDT', d: { zh: '止损 3,260 → 3,208，放到失效位上', en: 'Stop 3,260 → 3,208, onto the invalidation' } },
      { at: mockAt('09-02 09:20'), kind: 'DEBRIEF', sym: 'BTCUSDT', side: 'LONG', d: { zh: '平仓后 13时20分，主人点的立即复盘', en: 'Owner hit Review now, 13h 20m after close' } },
      { at: mockAt('09-02 09:40'), kind: 'WATCH', sym: 'ETHUSDT', d: { zh: '止损 3,208 → 3,120，收到成本下方', en: 'Stop 3,208 → 3,120, tucked under the entry' } },
      { at: mockAt('09-02 10:00'), kind: 'TRADE', d: { zh: 'BTC 布了两条盯盘，仓位不动', en: 'Two BTC watches set, positions untouched' } }
    ]
  },
  {
    name: 'osprey', model: 'claude-sonnet-5', interval: '1h', wakeWindow: '09:00–23:00', round: 1, seed: 10000,
    t0: mockAt('08-29 00:00'),
    // 冲到 10,390 后一路回落，08/31 10:00 比高点低 6% 触发权益回落
    curve: buildEquityCurve([[0, 10000], [10, 10000], [21, 10180], [26, 10390], [38, 10170], [50, 9950], [58, 9765], [60, 9790],
      [70, 9700], [82, 9600], [96, 9520], [106, 9380]], mockAt('08-29 00:00'), 11),
    events: [
      { at: mockAt('08-29 10:00'), kind: 'TRADE', d: { zh: '抄底开 ZEC 多 5x', en: 'Bought the dip: ZEC long 5x' } },
      { at: mockAt('08-29 21:00'), kind: 'TRADE', d: { zh: '追突破开 ETH 多 5x', en: 'Chased the breakout: ETH long 5x' } },
      { at: mockAt('08-30 14:25'), kind: 'EVENT', sym: 'ZECUSDT', d: { zh: '止损被打到，−1R，不反手', en: 'Stopped out at −1R; no reversal' } },
      { at: mockAt('08-31 10:05'), kind: 'DRAWDOWN', d: { zh: '比高点回落 6%，ETH 减一半', en: 'Down 6% from the peak; ETH cut by half' } },
      { at: mockAt('08-31 14:30'), kind: 'DEBRIEF', sym: 'ZECUSDT', side: 'LONG', d: { zh: '错在进场：4h 还在下降通道里', en: 'Bad entry: the 4h downtrend was intact' } },
      { at: mockAt('09-02 08:00'), kind: 'REVIEW', d: { zh: '#2 待验证：下降通道里不抄底', en: '#2 unproven: no dip-buying in a downtrend' } },
      { at: mockAt('09-02 10:00'), kind: 'TRADE', d: { zh: 'ETH 剩下一半照计划拿着', en: 'The other ETH half held as planned' } }
    ]
  },
  {
    name: 'heron', model: 'gpt-5.1', interval: '4h', wakeWindow: null, round: 1, seed: 10000,
    t0: mockAt('08-20 00:00'),
    // 4h 心跳，动得少：小台阶往上，回撤都浅
    curve: buildEquityCurve([[0, 10000], [8, 10000], [40, 10090], [70, 10040], [92, 10150], [130, 10120], [160, 10330], [190, 10280],
      [227, 10420], [250, 10390], [280, 10690], [300, 10640], [322, 10790]], mockAt('08-20 00:00'), 23),
    events: [
      { at: mockAt('08-20 08:00'), kind: 'TRADE', d: { zh: '4h 回踩企稳，开 BNB 多 4x', en: '4h pullback held: long BNB 4x' } },
      { at: mockAt('08-23 19:40'), kind: 'EVENT', sym: 'XRPUSDT', d: { zh: 'XRP 限价单成交，补一条盯盘', en: 'XRP limit filled; one watch added' } },
      { at: mockAt('08-26 16:00'), kind: 'TRADE', d: { zh: 'BNB 到 +1R，止损提到成本', en: 'BNB at +1R; stop raised to cost' } },
      { at: mockAt('08-29 11:12'), kind: 'MANUAL', d: { zh: '整盘看了一遍，XRP 减一半', en: 'Went over the book; XRP cut by half' } },
      { at: mockAt('09-01 16:05'), kind: 'DEBRIEF', sym: 'BNBUSDT', side: 'LONG', d: { zh: '+2.4R 止盈，出场后又涨了 1.8%', en: '+2.4R at target; 1.8% more after the exit' } },
      { at: mockAt('09-02 08:00'), kind: 'TRADE', d: { zh: 'XRP 拿着，给 BNB 布一条盯盘', en: 'XRP held; one BNB watch set' } },
      { at: mockAt('09-02 08:00'), kind: 'REVIEW', d: { zh: '#3 转为规律：4h 回踩做多', en: '#3 promoted to a rule: 4h pullback longs' } }
    ]
  },
  {
    name: 'wren', model: 'deepseek-chat', interval: '1h', wakeWindow: '21:00–08:30', round: 3, seed: 10000,
    t0: mockAt('08-28 00:00'),
    // 只盯夜盘，来回拉锯，最后小赚
    curve: buildEquityCurve([[0, 10000], [22, 10000], [26, 10130], [30, 10060], [31, 9880], [40, 9910], [47, 9930], [56, 10170],
      [66, 10060], [78, 10240], [90, 9990], [102, 10190], [114, 10080], [124, 10260], [130, 10310]], mockAt('08-28 00:00'), 31),
    events: [
      { at: mockAt('08-28 22:00'), kind: 'TRADE', d: { zh: '夜盘冲高回落，开 HYPE 空 3x', en: 'Night spike faded: short HYPE 3x' } },
      { at: mockAt('08-29 03:20'), kind: 'ALERT', sym: 'DOGEUSDT', d: { zh: '急拉 2.1%，没追，布一条盯盘', en: '2.1% spike; no chase, one watch set' } },
      { at: mockAt('08-29 06:40'), kind: 'EVENT', sym: 'HYPEUSDT', d: { zh: '止损被打到，−1R，先不追', en: 'Stopped out at −1R; staying flat' } },
      { at: mockAt('08-29 23:15'), kind: 'WATCH', sym: 'DOGEUSDT', d: { zh: '15 分钟收上 0.2140，开 DOGE 多 3x', en: '15m close above 0.2140: long DOGE 3x' } },
      { at: mockAt('08-30 06:45'), kind: 'DEBRIEF', sym: 'HYPEUSDT', side: 'SHORT', d: { zh: '被洗掉的：止损宽一倍就到目标', en: 'Shaken out: a 2× stop reached the target' } },
      { at: mockAt('09-02 08:00'), kind: 'TRADE', d: { zh: 'DOGE 多单拿着，HYPE 空仓等', en: 'DOGE long held; HYPE flat, waiting' } },
      { at: mockAt('09-02 08:00'), kind: 'REVIEW', d: { zh: '#4 待验证：夜盘突破等 15 分钟收盘', en: '#4 unproven: wait for a 15m close at night' } }
    ]
  }
];

/* 首屏行情：跟主站首页「市场」同款（股票 / Crypto / 大宗商品 / TradFi），每类两只。价格与涨跌全是编的，页面上标着示例数据。
   name 是主名，pair 是后面那截灰字；to 是主站上对应的页面 */
const MARKET = [
  { id: 'stocks', to: 'https://wtfibought.com/bstock',
    title: { zh: '股票', en: 'Stocks' }, sub: { zh: '代币化美股', en: 'Tokenized US equities' },
    rows: [
      { code: 'NVDA', name: 'NVDA', pair: { zh: '英伟达', en: 'NVIDIA' }, price: 216.72, chg: 0.24, to: 'https://wtfibought.com/bstock' },
      { code: 'TSLA', name: 'TSLA', pair: { zh: '特斯拉', en: 'Tesla' }, price: 365.48, chg: 0.57, to: 'https://wtfibought.com/bstock' }
    ] },
  { id: 'crypto', to: 'https://wtfibought.com/coin',
    title: { zh: 'Crypto', en: 'Crypto' }, sub: { zh: '加密货币', en: 'Cryptocurrencies' },
    rows: [
      { code: 'BTC', name: 'BTC', pair: 'BTC / USDT', price: 78912.40, chg: 0.31, to: 'https://wtfibought.com/coin/BTCUSDT' },
      { code: 'ETH', name: 'ETH', pair: 'ETH / USDT', price: 3064.80, chg: -1.12, to: 'https://wtfibought.com/coin/ETHUSDT' }
    ] },
  { id: 'commodity', to: 'https://wtfibought.com/commodity',
    title: { zh: '大宗商品', en: 'Commodities' }, sub: { zh: '黄金 / 原油', en: 'Gold / Oil' },
    rows: [
      { code: 'XAU', name: { zh: '黄金', en: 'Gold' }, pair: 'XAU / USDT', price: 4618.02, chg: 0.23, to: 'https://wtfibought.com/coin/XAUUSDT' },
      { code: 'CL', name: { zh: '原油', en: 'Crude oil' }, pair: 'CL / USDT', price: 85.78, chg: -0.89, to: 'https://wtfibought.com/coin/CLUSDT' }
    ] },
  { id: 'tradfi', to: 'https://wtfibought.com/tradfi',
    title: { zh: 'TradFi 合约', en: 'TradFi futures' }, sub: { zh: '美股/ETF 永续', en: 'US equity / ETF perps' },
    rows: [
      { code: 'SPCX', name: 'SpaceX', pair: 'SPCX / USDT', price: 136.07, chg: 0.82, to: 'https://wtfibought.com/coin/SPCXUSDT' },
      { code: 'HYNX', name: { zh: 'SK海力士', en: 'SK Hynix' }, pair: 'SKHYNIX / USDT', price: 1264.54, chg: 1.32, to: 'https://wtfibought.com/coin/SKHYNIXUSDT' }
    ] }
];
