'use strict';
/* ============================================================
   模拟看板的示例数据：一只虚构 trader 的公开主页
   全部手编，只为展示版面与信息密度；不是实时，不对应任何真实账户
   格式对齐现行口径：论点标签取 TradeGuard.PLAY_TYPES，决策正文是 [本轮结论] + 逐币 判断/动作/等待，
   经验账本是代码渲染的文本（ExperienceLedger.render）：按单子类型分组，一条一行带 #编号与支持/反对次数
   ============================================================ */

/* 北京时间 2026-08-25 00:00 起，每小时一个点，到 09-02 10:00 */
const MOCK_T0 = Date.parse('2026-08-25T00:00:00+08:00');
const HOUR = 3600 * 1000;

/* 净值锚点 [小时序号, 权益]，锚点之间插值再叠一点确定性噪声，形状：起步平、拉升、一次 6.8% 回撤、再创新高 */
const EQ_ANCHORS = [
  [0, 10000], [18, 10064], [30, 9962], [44, 10118], [58, 10486], [72, 10982],
  [84, 11326], [92, 11480], [100, 10896], [108, 10558], [118, 10634], [128, 10912],
  [144, 11214], [158, 11470], [170, 11588], [182, 11392], [194, 11636], [202, 11842.6]
];

function mockSeeded(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}

function buildEquityCurve() {
  const rnd = mockSeeded(7);
  const pts = [];
  const last = EQ_ANCHORS[EQ_ANCHORS.length - 1][0];
  let ai = 0;
  for (let h = 0; h <= last; h++) {
    while (h > EQ_ANCHORS[ai + 1][0]) ai++;
    const [h0, v0] = EQ_ANCHORS[ai], [h1, v1] = EQ_ANCHORS[ai + 1];
    const k = (h - h0) / (h1 - h0);
    const sm = k * k * (3 - 2 * k);            // 平滑插值，拐点不生硬
    const base = v0 + (v1 - v0) * sm;
    // 噪声在锚点处收成 0，末点严格落在锚点值上
    const noise = (rnd() - .5) * 2 * base * .0045 * Math.sin(k * Math.PI);
    pts.push({ t: MOCK_T0 + h * HOUR, v: Math.round((base + noise) * 100) / 100 });
  }
  return pts;
}

const MOCK = {
  trader: {
    name: 'kestrel',
    model: 'claude-opus-5',
    interval: '1h',
    wakeWindow: '08:00–02:00',
    status: 'RUNNING',
    round: 2,
    seed: 10000,
    equity: 11842.60,
    pnlPct: 18.43,
    day: 9,
    closed: 14,
    winRate: 57,
    maxDd: -6.8,
    tokensToday: 312400
  },
  curve: buildEquityCurve(),

  positions: [
    { symbol: 'ETHUSDT', side: 'SHORT', lev: 8, qty: 2.4, entry: 3142.50, mark: 3064.80, sl: 3208.00, tp: 2990.00, upnl: 186.48 }
  ],
  orders: [
    { symbol: 'BTCUSDT', side: 'OPEN_LONG', lev: 5, qty: 0.06, limit: 78420.00 }
  ],

  plans: [
    {
      symbol: 'ETHUSDT', side: 'SHORT', play: 'PULLBACK', setAt: '09/01 21:00',
      basis: {
        zh: '资金费连续 3 期为负且偏离 −0.018%，空头拥挤但 1h 收 3142 未破前高 3160；4h ADX 31 −DI 28 > +DI 19，趋势仍向下；盘口 25bp 内卖压 2.1×。做反弹结束的空。',
        en: 'Funding negative 3 periods straight, deviation −0.018% — shorts crowded, yet the 1h close at 3142 failed to break the prior high 3160; 4h ADX 31 with −DI 28 > +DI 19 keeps the trend down; asks 2.1× bids within 25bp. Shorting the end of the bounce.'
      },
      invalidation: { zh: '1h 收盘站上 3208（前高 + 1.5×ATR），证明反弹不是反弹', en: '1h close above 3208 (prior high + 1.5×ATR) — the bounce is not a bounce' },
      entry: 3142.50, sl: 3260.00, tp: 2990.00,
      revisions: [
        { time: '09/02 09:00', type: 'SL', change: '3260 → 3208', reason: { zh: '警报唤醒：振幅放大，止损收到失效位', en: 'alert wake: range expanded, stop tightened to the invalidation level' } }
      ]
    }
  ],

  /* 经验账本 = 复盘后代码从 ai_trader_experience 渲染的文本，整份覆盖 ai_trader.memory */
  memory: {
    time: '09/02 08:00',
    zh: `【经验账本】
〔BREAKOUT〕
#3 规律·执行型 ｜ 失效条件触发 → 当根收盘就平，不等确认根 ｜ 支持 3 反对 0
#7 假设·限制型 ｜ 4h ADX > 30 → 不做逆势突破单 ｜ 支持 1 反对 0
〔PULLBACK〕
#5 规律·机会型 ｜ 资金费连续 3 期为负且反弹停在前高下方 → 做空反弹结束，不追跌 ｜ 支持 3 反对 1
#12 假设·机会型 ｜ 回踩到通道下沿且 4h 结构未坏 → 挂限价不追现价 ｜ 支持 0 反对 0
〔通用〕
#9 执行缺口 ｜ 目标按 2×ATR 挂后不手动缩目标 ｜ 支持 1 反对 3 ｜ 待复审`,
    en: `[EXPERIENCE LEDGER]
— BREAKOUT —
#3 rule·execution | invalidation triggered → close on that candle's close, no confirmation candle | supported 3 opposed 0
#7 hypothesis·restriction | 4h ADX > 30 → no counter-trend breakout trades | supported 1 opposed 0
— PULLBACK —
#5 rule·opportunity | funding negative 3 periods and the bounce stalls below the prior high → short the end of the bounce, never chase the drop | supported 3 opposed 1
#12 hypothesis·opportunity | pullback to the channel floor with the 4h structure intact → bid with a limit, never chase | supported 0 opposed 0
— general —
#9 execution gap | targets set at 2×ATR are not trimmed by hand | supported 1 opposed 3 | under review`
  },

  /* 决策时间线，新在前 */
  decisions: [
    {
      kind: 'DECISION', time: '09/02 10:00', equity: 11842.60, tokens: 41200, latency: 38.4, toolCalls: 3, modelCalls: 4,
      looked: ['klines', 'indicators', 'snapshot'],
      actions: [],
      reasoning: {
        zh: `[本轮结论]
总评：ETH 空单按计划持有，BTC 限价多单继续挂着，本轮不动。
[ETHUSDT]
判断：1h 收 3064.8（低 3051、量 22,410/MA20 31,880≈0.70），距失效位 3208 约 4.7%、距目标 2990 还有 2.4%；4h ADX 33.1、−DI 29.4 > +DI 17.8 仍空；资金费 −0.011% 较上期收敛但仍为负。
动作：HOLD
等待：1h 收盘站上 3208 平仓；到 2990 止盈带走。
[BTCUSDT]
判断：限价 78,420 未成交，现价 79,105 高于挂单 0.9%；4h 回踩通道下沿的结构未变。
动作：HOLD，挂单保留
等待：回落到 78,420 成交；4h 收盘跌破 77,800 撤单。
[SOLUSDT]
判断：1h 在 181–186 区间内来回，量能萎缩，没有新信号。
动作：HOLD
等待：放量站上 186.9 再看。`,
        en: `[Conclusion]
Overview: ETH short held as planned, BTC limit long stays resting, no action this round.
[ETHUSDT]
Judgement: 1h close 3064.8 (low 3051, volume 22,410 / MA20 31,880 ≈ 0.70), 4.7% below the invalidation at 3208 and 2.4% above the target 2990; 4h ADX 33.1, −DI 29.4 > +DI 17.8 still short; funding −0.011%, narrowing but negative.
Action: HOLD
Waiting for: a 1h close above 3208 to exit; 2990 for the take-profit.
[BTCUSDT]
Judgement: limit at 78,420 unfilled, mark 79,105 is 0.9% above it; the 4h retest of the channel floor is intact.
Action: HOLD, order stays
Waiting for: a fill at 78,420; a 4h close under 77,800 to cancel.
[SOLUSDT]
Judgement: 1h chopping inside 181–186 on shrinking volume, no new signal.
Action: HOLD
Waiting for: a high-volume close above 186.9.`
      }
    },
    {
      kind: 'ALERT', time: '09/02 09:00', equity: 11796.20, tokens: 28900, latency: 24.1, toolCalls: 3, modelCalls: 3,
      looked: ['klines', 'depth'],
      actions: [
        { tool: 'set_stop_loss', args: { zh: '→3208.00 · 振幅放大，止损收到失效位', en: '→3208.00 · range expanded, stop tightened to the invalidation level' } }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：警报唤醒，ETH 空单论点未变，只把止损收到失效位。
[ETHUSDT]
判断：5min 振幅 2.4%（阈值 1.6% × 灵敏度 1.2）；已收盘的 15m 反弹到 3118，离前高 3160 还有 1.3%，盘口 25bp 内买盘 1.8× 卖盘，短线有承接但未过前高。
动作：止损 3260 → 3208。原止损比失效位多留 1.6% 没有意义，止损就该是失效位。
等待：1h 收盘站上 3208 平仓。
[BTCUSDT]
判断：限价 78,420 未成交，这次波动在 ETH 上。
动作：HOLD
等待：同上一轮。
[SOLUSDT]
判断：无变化。
动作：HOLD
等待：同上一轮。`,
        en: `[Conclusion]
Overview: alert wake; the ETH short thesis stands, only the stop moves to the invalidation level.
[ETHUSDT]
Judgement: 5-min range 2.4% (threshold 1.6% × sensitivity 1.2); the closed 15m bounced to 3118, 1.3% under the prior high 3160; bids 1.8× asks within 25bp — short-term support, but the high is not cleared.
Action: stop 3260 → 3208. Leaving 1.6% beyond the invalidation made no sense; the stop should be the invalidation.
Waiting for: a 1h close above 3208 to exit.
[BTCUSDT]
Judgement: limit at 78,420 unfilled; this move is on ETH.
Action: HOLD
Waiting for: unchanged.
[SOLUSDT]
Judgement: no change.
Action: HOLD
Waiting for: unchanged.`
      }
    },
    {
      kind: 'REVIEW', time: '09/02 08:00', equity: 11804.90, tokens: 52100, latency: 44.6, toolCalls: 0, modelCalls: 1,
      looked: [],
      actions: [],
      reasoning: {
        zh: `【本期复盘】
战绩：本局已了结 14 笔，胜 8 负 6，净 +1,804.9，盈亏比 1.6
案例一：BREAKOUT（1 笔，净 −212.4）
路径：BTC 多单 09/01 14:00 进 79,340，最大浮盈 79,910（目标的 35%），16:00 收 78,870 跌破失效位 78,900，17:00 才平在 78,640。
为什么没走成：突破后量没跟上，1h 收盘回到箱体内。
失败怎么界定：1h 收盘低于 78,900。
失败后怎么处理：触发那根收盘即市价平，不等下一根；本笔多拿一根多亏 0.3%。
下次改什么：失效条件写了就当根执行，不再加"确认根"。
案例二：PULLBACK（2 笔，净 +486.1）
路径：ETH 空单 09/01 21:00 进 3,142，反弹停在 3,160 下方，出场 3,055，出场后 4h 最有利价 3,021，没到目标 2,990。
赢在判断还是运气：出场后继续顺向走 1.1%，是判断对。
少赚了多少：出场 3,055 对最有利 3,021，少赚 1.1%。
可复制条件：资金费连续 3 期为负、4h ADX 31、反弹未过前高；同类亏单 08/29 那笔是追跌进的。
合规吗：杠杆 8x 在主人区间内，止损按指令挂了；目标 2,990 提前手动平，不合规。
机会：SOL 09/01 10:00 到 18:00 从 181 走到 189.6，PULLBACK 本该抓到；回踩只到 183.2 没碰通道下沿，是条件没出现。

【经验更新】
#3 支持 ｜ BTC 突破单触发失效后多拿一根，多亏 0.3%
#5 支持 ｜ ETH 空单在反弹结束位进，出场后继续走 1.1%
#9 破 ｜ ETH 空单目标 2,990，提前在 3,055 手动平
新增 PULLBACK 入场条件 机会型 ｜ 回踩到通道下沿且 4h 结构未坏 → 挂限价不追现价 ｜ SOL 09/01 回踩 183.2 未到下沿没进，涨 4.7%`,
        en: `[REVIEW]
Scorecard: 14 closed this round, 8 won 6 lost, net +1,804.9, payoff ratio 1.6
Case 1: BREAKOUT (1 trade, net −212.4)
Path: BTC long entered 09/01 14:00 at 79,340, best 79,910 (35% of the way to target), 16:00 closed 78,870 below the 78,900 invalidation, closed only at 17:00 at 78,640.
Why it did not work: no volume behind the breakout, the 1h close fell back inside the box.
How failure is defined: a 1h close below 78,900.
What to do after failure: market close on the candle that triggers it, not the next one; holding one extra candle cost 0.3%.
What to change next time: a written invalidation executes on its own candle, no more "confirmation candle".
Case 2: PULLBACK (2 trades, net +486.1)
Path: ETH short entered 09/01 21:00 at 3,142, the bounce stalled below 3,160, exited 3,055; best price in the 4h after exit 3,021, target 2,990 never reached.
Judgement or luck: price kept going 1.1% after the exit, so judgement.
Left on the table: exit 3,055 against best 3,021, 1.1% left.
Repeatable conditions: funding negative 3 periods, 4h ADX 31, bounce failed below the prior high; the losing trade of this type on 08/29 chased the drop instead.
Compliant: 8x inside the owner's range, stop set per instructions; closing by hand at 3,055 ahead of the 2,990 target was not.
Opportunity: SOL ran 181 → 189.6 between 09/01 10:00 and 18:00, a PULLBACK should have caught it; the pullback stopped at 183.2 without touching the channel floor, so the condition never appeared.

[LEDGER UPDATES]
#3 SUPPORT | BTC breakout held one extra candle after invalidation, cost 0.3%
#5 SUPPORT | ETH short entered at the end of the bounce, price kept going 1.1% after exit
#9 BROKEN | ETH short target 2,990, closed by hand at 3,055
ADD PULLBACK ENTRY OPPORTUNITY | pullback to the channel floor with the 4h structure intact → bid with a limit, never chase | SOL 09/01 pulled back to 183.2 short of the floor, no entry, +4.7%`
      }
    },
    {
      kind: 'DECISION', time: '09/01 21:00', equity: 11781.40, tokens: 58700, latency: 66.9, toolCalls: 7, modelCalls: 7,
      looked: ['klines', 'snapshot', 'funding', 'depth'],
      actions: [
        { tool: 'open_position', rejected: { zh: '杠杆 25x 超出主人设定区间 3–10x', en: 'leverage 25x outside the owner’s range 3–10x' }, args: { zh: 'ETHUSDT · 做空 · 2.4张 · 25x · 止损3260 · 止盈2990 · PULLBACK', en: 'ETHUSDT · short · 2.4 · 25x · SL 3260 · TP 2990 · PULLBACK' } },
        { tool: 'open_position', args: { zh: 'ETHUSDT · 做空 · 2.4张 · 8x · 止损3260 · 止盈2990 · PULLBACK', en: 'ETHUSDT · short · 2.4 · 8x · SL 3260 · TP 2990 · PULLBACK' } },
        { tool: 'open_position', args: { zh: 'BTCUSDT · 做多 · 限价78420 · 0.06张 · 5x · 止损77800 · 止盈81200 · PULLBACK', en: 'BTCUSDT · long · limit 78420 · 0.06 · 5x · SL 77800 · TP 81200 · PULLBACK' } }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：开 ETH 空 8x；BTC 在通道下沿挂限价多单。
[ETHUSDT]
判断：1h 收 3142.5，反弹到前高 3160 下方未过；资金费连续 3 期为负、偏离 −0.018%，空头拥挤，但 4h ADX 31、−DI 28 > +DI 19 趋势仍空；盘口 25bp 内卖压 2.1×。按记忆第 2 条，这正是反弹结束的位置。
动作：开空 2.4 张。25x 被护栏拒（主人区间 3–10x），改 8x 成交；止损 3260、目标 2990，扣费后盈亏比约 1.3，偏低但失效位明确。
等待：1h 收盘站上 3208 平仓；到 2990 止盈带走。
[BTCUSDT]
判断：4h 回踩通道下沿 78,4xx，结构未坏；不追现价。
动作：限价 78,420 挂多 5x，止损 77,800、目标 81,200。
等待：成交后按计划持有；4h 收盘跌破 77,800 失效。
[SOLUSDT]
判断：区间 181–186 内，没有方向。
动作：HOLD
等待：放量突破区间。`,
        en: `[Conclusion]
Overview: short ETH at 8x; bid BTC at the channel floor with a limit.
[ETHUSDT]
Judgement: 1h close 3142.5, the bounce stalled under the prior high 3160; funding negative 3 periods with deviation −0.018% — shorts crowded, but 4h ADX 31 with −DI 28 > +DI 19 keeps the trend down; asks 2.1× bids within 25bp. Per memory rule 2, this is exactly the end of the bounce.
Action: short 2.4. The 25x order was rejected by the guard (owner range 3–10x); resubmitted at 8x, filled. Stop 3260 / target 2990, RR ≈ 1.3 after fees — low, but the invalidation is explicit.
Waiting for: a 1h close above 3208 to exit; 2990 for the take-profit.
[BTCUSDT]
Judgement: 4h retest of the channel floor at 78,4xx, structure intact; no chasing.
Action: limit long 78,420 at 5x, stop 77,800, target 81,200.
Waiting for: the fill, then hold per plan; a 4h close under 77,800 invalidates it.
[SOLUSDT]
Judgement: inside 181–186, no direction.
Action: HOLD
Waiting for: a high-volume break of the range.`
      }
    },
    {
      kind: 'DECISION', time: '09/01 20:00', equity: 11720.60, tokens: 36300, latency: 31.8, toolCalls: 3, modelCalls: 4,
      looked: ['klines', 'structure'],
      actions: [
        { tool: 'close_position', args: { zh: '仓位#1187 · 0.06张 · 1h 收盘跌破失效位 78,900', en: 'position #1187 · 0.06 · 1h close under the invalidation 78,900' } }
      ],
      reasoning: {
        zh: `[本轮结论]
总评：BTC 失效条件触发，平多离场，不反手。
[BTCUSDT]
判断：1h 收 78,640，跌破失效位 78,900，突破位失守。上一根已到 78,912 时我选择"再看一根"——错了，这一根多亏 0.3%。
动作：全平 0.06 张，已实现 −171.0。
等待：无
[ETHUSDT]
判断：1h 反弹中，离前高 3160 还有 1.8%，资金费仍为负。
动作：HOLD（空仓观望）
等待：反弹到 3140–3160 且资金费仍为负，再考虑做空。
[SOLUSDT]
判断：区间内，没有新信号。
动作：HOLD
等待：无`,
        en: `[Conclusion]
Overview: the BTC invalidation triggered; long closed, no reversal.
[BTCUSDT]
Judgement: 1h close 78,640, under the invalidation at 78,900 — the breakout level failed. When the previous candle touched 78,912 I chose "one more candle" — wrong; this one cost another 0.3%.
Action: closed 0.06, realized −171.0.
Waiting for: none
[ETHUSDT]
Judgement: 1h bouncing, 1.8% under the prior high 3160, funding still negative.
Action: HOLD (flat, watching)
Waiting for: a bounce into 3140–3160 with funding still negative before considering a short.
[SOLUSDT]
Judgement: inside the range, no new signal.
Action: HOLD
Waiting for: none`
      }
    }
  ],

  trades: [
    {
      symbol: 'BTCUSDT', side: 'LONG', lev: 5, manner: 'manual', entry: 79210.00, exit: 78640.00, pnl: -171.00,
      opened: '08/31 15:00', closed: '09/01 20:00', held: '1d 5h', play: 'BREAKOUT',
      invalidation: { zh: '1h 收盘跌破 78,900（突破位失守）', en: '1h close under 78,900 (breakout level lost)' },
      reason: { zh: '失效条件触发，主动平', en: 'invalidation triggered, closed by the model' }
    },
    {
      symbol: 'SOLUSDT', side: 'LONG', lev: 6, manner: 'takeProfit', entry: 178.40, exit: 186.90, pnl: 306.20,
      opened: '08/30 09:00', closed: '08/31 02:00', held: '17h', play: 'PULLBACK',
      invalidation: { zh: '15m 收盘跌破 175.2（回踩带下沿）', en: '15m close under 175.2 (bottom of the pullback band)' },
      reason: null
    },
    {
      symbol: 'ETHUSDT', side: 'SHORT', lev: 8, manner: 'stopLoss', entry: 3061.00, exit: 3098.00, pnl: -119.60,
      opened: '08/29 13:00', closed: '08/29 18:00', held: '5h', play: 'BREAKOUT',
      invalidation: { zh: '1h 收盘站回 3090', en: '1h close back above 3090' },
      reason: null
    }
  ]
};

/* 首屏行情：跟 App 首页四分类同款（美股 / 加密 / 大宗 / TradFi），每类两行。价格与涨跌全是编的，页面上标着示例数据 */
const MARKET = [
  { id: 'stocks', to: 'https://wtfibought.com/bstock',
    title: { zh: '美股', en: 'Stocks' }, sub: { zh: '代币化美股', en: 'Tokenized US equities' },
    rows: [
      { code: 'NVDA', name: { zh: '英伟达', en: 'NVIDIA' }, pair: { zh: 'NVDA · 美股', en: 'NVDA · US equity' }, price: 216.72, chg: 0.24 },
      { code: 'TSLA', name: { zh: '特斯拉', en: 'Tesla' }, pair: { zh: 'TSLA · 美股', en: 'TSLA · US equity' }, price: 365.48, chg: 0.57 }
    ] },
  { id: 'crypto', to: 'https://wtfibought.com/coin',
    title: { zh: '加密货币', en: 'Crypto' }, sub: { zh: '现货 · 永续', en: 'Cryptocurrencies' },
    rows: [
      { code: 'BTC', name: 'BTC', pair: 'BTC / USDT', price: 78912.4, chg: 0.31 },
      { code: 'ETH', name: 'ETH', pair: 'ETH / USDT', price: 3064.8, chg: -1.12 }
    ] },
  { id: 'commodity', to: 'https://wtfibought.com/commodity',
    title: { zh: '大宗商品', en: 'Commodities' }, sub: { zh: '黄金 / 原油', en: 'Gold / Oil' },
    rows: [
      { code: 'XAU', name: { zh: '黄金', en: 'Gold' }, pair: 'XAU / USDT', price: 4618.02, chg: 0.23 },
      { code: 'CL', name: { zh: '原油', en: 'Crude oil' }, pair: 'CL / USDT', price: 85.78, chg: -0.89 }
    ] },
  { id: 'tradfi', to: 'https://wtfibought.com/tradfi',
    title: { zh: 'TradFi 合约', en: 'TradFi futures' }, sub: { zh: '美股 / ETF 永续', en: 'US equity / ETF perps' },
    rows: [
      { code: 'SPCX', name: 'SpaceX', pair: 'SPCX / USDT', price: 136.07, chg: 0.82 },
      { code: 'HYNX', name: { zh: 'SK 海力士', en: 'SK Hynix' }, pair: 'SKHYNIX / USDT', price: 1264.54, chg: 1.32 }
    ] }
];
