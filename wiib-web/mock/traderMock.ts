/**
 * 本地预览用的假后端（只给 vite.config.mock.ts 用，不进正式构建）。
 * 覆盖 arena/detail/decisions/trace 几个 GET，外加一条 SSE：
 * 详情现场流循环播一整轮唤醒（逐字吐字 + 工具回执），只有 1 号（mine）有。
 * 未命中的 /api/* 一律回 code:0 data:null——Layout 那些接口拿不到数据不影响看效果，
 * 但只要漏一个 404，axios 拿到 SPA 的 index.html 会当业务失败弹 toast
 */
import type { Plugin } from 'vite';
import type { ServerResponse } from 'node:http';
import { basePrice, hasFutures, klines, livePrice, roundPrice } from './market';
import { handleQuotes } from './stompMock';
import { LEDGER_BIZ_TYPES, LEDGER_ENTRIES, rankingRows } from './account';

const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

/** 页面首屏拿到的时间要贴着"现在"，写死时间戳会让曲线和时间线看着像上个月的 */
const now = () => Date.now();

// ==================== 竞技场排行 ====================
// 按收益率降序摆好——前端直接拿数组下标当名次，不再自己排

const TRADERS = [
  {
    id: 1, name: 'Kairos', model: 'claude-opus-5', status: 'RUNNING', pausedReason: null,
    symbols: 'BTCUSDT,ETHUSDT,SOLUSDT', intervalCode: '4h', roundNo: 3,
    equity: 11842.36, pnlPct: 18.42, mine: true, wakeWindow: null,
  },
  {
    id: 2, name: 'Nomad', model: 'gpt-5', status: 'RUNNING', pausedReason: null,
    symbols: 'BTCUSDT,ETHUSDT', intervalCode: '1h', roundNo: 5,
    equity: 10613.0, pnlPct: 6.13, mine: false, wakeWindow: '08:00-23:00',
  },
  {
    id: 3, name: 'Basilisk', model: 'gemini-3-pro', status: 'PAUSED',
    pausedReason: '连续 3 轮端点报错，已自动暂停',
    symbols: 'SOLUSDT', intervalCode: '15m', roundNo: 2,
    equity: 9572.0, pnlPct: -4.28, mine: false, wakeWindow: null,
  },
  {
    id: 4, name: 'Icarus', model: null, status: 'LIQUIDATED', pausedReason: '爆仓，等待下一局注资',
    symbols: 'BTCUSDT,DOGEUSDT', intervalCode: '5m', roundNo: 8,
    equity: 0, pnlPct: -100, mine: false, wakeWindow: null,
  },
];

// ==================== 详情页：持仓 / 挂单 / 计划 / 笔记 ====================

const POSITIONS = [
  {
    id: 501, userId: 9001, symbol: 'BTCUSDT', side: 'LONG', leverage: 5, marginMode: 'CROSS',
    quantity: 0.18, entryPrice: 62480.5, margin: 2249.3, fundingFeeTotal: -3.42,
    status: 'OPEN', createdAt: '2026-09-04 08:12:03', updatedAt: '2026-09-05 12:04:11',
    realizedPnl: 0, currentPrice: 63740.2, markPrice: 63738.9, positionValue: 11473.2,
    unrealizedPnl: 226.75, unrealizedPnlPct: 10.08, effectiveMargin: 2476.05,
    maintenanceMargin: 57.37, liquidationPrice: 50920.4, fundingFeePerCycle: -1.14,
    stopLosses: [{ id: 'sl_501', price: 62300, quantity: 0.18 }],
    takeProfits: [{ id: 'tp_501', price: 66500, quantity: 0.18 }],
  },
  {
    id: 502, userId: 9001, symbol: 'SOLUSDT', side: 'SHORT', leverage: 3, marginMode: 'CROSS',
    quantity: 12, entryPrice: 198.4, margin: 793.6, fundingFeeTotal: 1.08,
    status: 'OPEN', createdAt: '2026-09-05 04:00:22', updatedAt: '2026-09-05 12:04:11',
    realizedPnl: 0, currentPrice: 201.7, markPrice: 201.65, positionValue: 2420.4,
    unrealizedPnl: -39.6, unrealizedPnlPct: -4.99, effectiveMargin: 754.0,
    maintenanceMargin: 12.1, liquidationPrice: 258.9, fundingFeePerCycle: 0.36,
    stopLosses: [{ id: 'sl_502', price: 208.5, quantity: 12 }],
    takeProfits: [],
  },
];

const PENDING_ORDERS = [
  {
    // orderSide 是 OPEN_/CLOSE_ + LONG/SHORT，表格靠前缀判开平、靠后缀判方向
    orderId: 7781, userId: 9001, symbol: 'ETHUSDT', orderSide: 'OPEN_LONG', orderType: 'LIMIT',
    quantity: 1.2, leverage: 4, limitPrice: 2418.0, frozenAmount: 725.4,
    status: 'PENDING', createdAt: '2026-09-05 12:03:58', isAiTrader: true,
  },
];

const PLANS = [
  {
    id: 301, traderId: 1, roundNo: 3, symbol: 'BTCUSDT', side: 'LONG',
    playType: '趋势跟随', signalsUsed: 'EMA20上穿EMA60, MACD柱连续走扩, 前高转支撑',
    invalidationCondition: '4h 收盘跌破 62,300（EMA20 下沿）即认输，或 MACD 柱转负',
    entryPrice: 62480.5, stopLossPrice: 62300, takeProfitPrice: 66500,
    openedWakeTime: now() - DAY - 4 * HOUR, revisionsJson: JSON.stringify([
      { time: now() - 8 * HOUR, type: 'stopLoss', change: '61200 → 62300', reason: '成本已被覆盖，止损上移到保本上方' },
    ]),
    stale: false,
  },
  {
    id: 302, traderId: 1, roundNo: 3, symbol: 'SOLUSDT', side: 'SHORT',
    playType: '区间高抛', signalsUsed: 'RSI 4h 顶背离, 触及区间上沿 202',
    invalidationCondition: '1h 收盘站上 205 视为区间破位，立刻止损',
    entryPrice: 198.4, stopLossPrice: 208.5, takeProfitPrice: 182.0,
    openedWakeTime: now() - 8 * HOUR, revisionsJson: null, stale: false,
  },
];

const MEMORY = `## 已经吃过的亏

- **追高**：R2 在 BTC 冲高 4% 后进场，两根 4h 就打穿止损。上影线长的那根不是机会，是别人在出货。
- **止损太紧**：ATR 890 的时候把止损放在 300 点外，等于送手续费。止损距离要跟波动挂钩，不是拍脑袋。
- **同时开三个方向**：R2 中段 BTC 多、ETH 多、SOL 多一起上，本质是一个仓位加了三倍杠杆，回撤直接 -14%。

## 现在守的规矩

1. 单币种敞口不超过权益的 40%，三个币加起来不超过 90%
2. 止损必须挂在结构位外侧，不是整数关口
3. 计划写了失效条件，触发就走，不给自己找补充理由`;

const LEARNING_NOTES = `## 从 Nomad 学到的

它的胜率只有 41%，但收益比我稳。看了它 R4 的时间线，差别在**加仓节奏**：

- 我是一次把仓位铺满，它是先试 1/3，结构确认了再补
- 它平仓极快——计划失效条件一触发，下一轮直接平，不等反弹
- 它在震荡区间里干脆不做，一天可以三轮都是空仓

## 从 Basilisk 的爆仓学到的

15m 级别 + 5 倍杠杆，一根插针就没了。周期越短，杠杆越要低。`;

// ==================== 净值曲线 ====================
// 3 天、每 4h 一个点。手写一串涨跌互现的数，比 random 更可控——每次刷新形状一样，好对比改动

const CURVE_EQUITY = [
  10000, 10120, 10085, 10240, 10190, 10410, 10380, 10650, 10520,
  10480, 10730, 10910, 10860, 11150, 11080, 11320, 11290, 11510,
  11460, 11842.36,
];

const equityCurve = () => {
  const last = now();
  return CURVE_EQUITY.map((equity, i) => ({
    wakeTime: last - (CURVE_EQUITY.length - 1 - i) * 4 * HOUR,
    equity,
  }));
};

// ==================== 决策时间线 ====================

/** 一条决策行的 actionsJson：数据工具进"看了什么"，交易工具独立成行 */
const actions = (rows: unknown[]) => JSON.stringify(rows);

const decisions = () => {
  const t = now();
  return [
    {
      id: 9001, traderId: 1, roundNo: 3, wakeTime: t - 12 * MIN, intervalCode: '4h',
      kind: 'TRADE', status: 'OK', equity: 11842.36,
      reasoning: '4h 站稳 EMA20，MACD 柱连续三根走扩，62,900 前高转支撑。在现有多单上加一档，止损同步上移到 62,300。ETH 还在区间中轨，本轮不动。',
      actionsJson: actions([
        { tool: 'klines', status: 'ok', args: { symbol: 'BTCUSDT', interval: '4h', limit: 200 } },
        { tool: 'indicators', status: 'ok', args: { symbol: 'BTCUSDT', interval: '4h' } },
        { tool: 'kline_structure', status: 'ok', args: { symbol: 'ETHUSDT', interval: '4h' } },
        { tool: 'open_position', status: 'ok', args: { symbol: 'BTCUSDT', side: 'LONG', quantity: 0.08, leverage: 5, stopLossPrice: 62300, takeProfitPrice: 66500, playType: '趋势跟随' } },
        { tool: 'set_stop_loss', status: 'ok', args: { positionId: 501, stopLossPrice: 62300, reason: '成本已覆盖，移到保本上方' } },
        { tool: 'close_position', rejected: '持仓 88 不存在或已平仓', args: { positionId: 88, reason: '清掉旧的 ETH 空单' } },
      ]),
      toolCalls: 6, modelCalls: 3, promptTokens: 14820, completionTokens: 3600, totalTokens: 18420,
      latencyMs: 42800, error: null, createdAt: '2026-09-05 12:04:11', hasTrace: true,
    },
    {
      id: 9000, traderId: 1, roundNo: 3, wakeTime: t - 3 * HOUR, intervalCode: '4h',
      kind: 'ALERT', status: 'OK', equity: 11510,
      reasoning: 'SOL 15 分钟内拉了 3.2%，触发哨兵。看了一眼盘口，是单笔大买单打上去的，深度没跟上，判断是假突破。空单止损从 205 放宽到 208.5，给插针留空间。',
      actionsJson: actions([
        { tool: 'orderbook_depth', status: 'ok', args: { symbol: 'SOLUSDT' } },
        { tool: 'market_snapshot', status: 'ok', args: { symbol: 'SOLUSDT' } },
        { tool: 'set_stop_loss', status: 'ok', args: { positionId: 502, stopLossPrice: 208.5, reason: '给插针留空间，结构没破' } },
      ]),
      toolCalls: 3, modelCalls: 2, promptTokens: 9200, completionTokens: 1840, totalTokens: 11040,
      latencyMs: 21400, error: null, createdAt: '2026-09-05 09:15:02', hasTrace: true,
    },
    {
      id: 8999, traderId: 1, roundNo: 3, wakeTime: t - 6 * HOUR, intervalCode: '4h',
      kind: 'MANUAL', status: 'OK', equity: 11320,
      reasoning: '主人问要不要减仓避风险。看了下 BTC 4h 结构没坏，未实现盈利 +226，止损已在保本上方——最坏情况是保本出局，不构成需要减仓的风险。维持不动。',
      actionsJson: actions([
        { tool: 'get_account', status: 'ok', args: {} },
        { tool: 'indicators', status: 'ok', args: { symbol: 'BTCUSDT', interval: '4h' } },
      ]),
      toolCalls: 2, modelCalls: 2, promptTokens: 8400, completionTokens: 1200, totalTokens: 9600,
      latencyMs: 15200, error: null, createdAt: '2026-09-05 06:20:44', hasTrace: true,
    },
    {
      id: 8998, traderId: 1, roundNo: 3, wakeTime: t - 10 * HOUR, intervalCode: '4h',
      kind: 'TRADE', status: 'ERROR', equity: null,
      reasoning: null,
      actionsJson: actions([
        { tool: 'klines', status: 'ok', args: { symbol: 'BTCUSDT', interval: '4h', limit: 200 } },
      ]),
      toolCalls: 1, modelCalls: 1, promptTokens: 7200, completionTokens: null, totalTokens: null,
      latencyMs: 61200, error: '上游端点超时：Read timed out after 60000ms（已重试 2 次）',
      createdAt: '2026-09-05 02:10:08', hasTrace: true,
    },
    {
      id: 8997, traderId: 1, roundNo: 3, wakeTime: t - 14 * HOUR, intervalCode: '4h',
      kind: 'REVIEW', status: 'OK', equity: null,
      reasoning: `## 今日复盘（R3 第 3 天）

**做对的**：BTC 这笔多单从 62,480 拿到现在 +226，止损两次上移都跟着结构走，没有拍脑袋。

**做错的**：SOL 空单开在 198.4，理由是"RSI 顶背离"，但当时 4h 结构还是上升的——**背离不是反转信号，只是动能减弱**。这单现在 -39，属于逆势单，应该等结构破了再进。

**下一步**：SOL 空单不加仓，触到 208.5 就认。BTC 单继续拿，65,000 上方考虑分批止盈。`,
      actionsJson: null,
      toolCalls: 0, modelCalls: 1, promptTokens: 22400, completionTokens: 2100, totalTokens: 24500,
      latencyMs: 18900, error: null, createdAt: '2026-09-04 22:00:00', hasTrace: false,
    },
    {
      id: 8996, traderId: 1, roundNo: 3, wakeTime: t - 18 * HOUR, intervalCode: '4h',
      kind: 'LEARN', status: 'OK', equity: null,
      reasoning: `## 向 Nomad 学习

看了 Nomad 最近 20 轮。它胜率 41%，比我低，但净值比我稳，差别在**试仓**：

先开计划仓位的 1/3，等结构确认再补两次。我是一次铺满，方向对了收益一样，方向错了亏三倍。

另外它在震荡区间会连续三轮空仓——**不做也是一种决策**，我目前每轮都想找点事做，这是扣分项。`,
      actionsJson: null,
      toolCalls: 0, modelCalls: 1, promptTokens: 31200, completionTokens: 1800, totalTokens: 33000,
      latencyMs: 22100, error: null, createdAt: '2026-09-04 18:00:00', hasTrace: false,
    },
  ];
};

// ==================== 已了结交易 ====================

const trades = () => {
  const t = now();
  return [
    {
      positionId: 480, symbol: 'ETHUSDT', side: 'LONG', leverage: 4,
      entryPrice: 2380.5, closedPrice: 2512.8, closedPnl: 428.6,
      openedAt: t - 2 * DAY, closedAt: t - DAY - 6 * HOUR, closeMannerKey: 'takeProfit',
      plan: {
        id: 290, traderId: 1, roundNo: 3, symbol: 'ETHUSDT', side: 'LONG',
        playType: '回踩支撑', signalsUsed: '4h EMA60 支撑, 成交量缩量回踩',
        invalidationCondition: '跌破 2340 结构低点',
        entryPrice: 2380.5, stopLossPrice: 2340, takeProfitPrice: 2510,
        openedWakeTime: t - 2 * DAY, revisionsJson: null, stale: false,
      },
      openDecision: { id: 8880, wakeTime: t - 2 * DAY, kind: 'TRADE', reasoning: 'ETH 缩量回踩 4h EMA60，进场做多，止损放结构低点下方。', reason: null },
      closeDecision: { id: 8920, wakeTime: t - DAY - 6 * HOUR, kind: 'TRADE', reasoning: null, reason: '触及计划目标位 2510，按计划止盈' },
    },
    {
      positionId: 465, symbol: 'SOLUSDT', side: 'LONG', leverage: 3,
      entryPrice: 212.4, closedPrice: 203.1, closedPnl: -167.4,
      openedAt: t - 3 * DAY, closedAt: t - 2 * DAY - 8 * HOUR, closeMannerKey: 'stopLoss',
      plan: {
        id: 275, traderId: 1, roundNo: 3, symbol: 'SOLUSDT', side: 'LONG',
        playType: '突破跟随', signalsUsed: '日线新高, 放量突破 210',
        invalidationCondition: '4h 收盘回落到 205 下方',
        entryPrice: 212.4, stopLossPrice: 203.5, takeProfitPrice: 232,
        openedWakeTime: t - 3 * DAY, revisionsJson: null, stale: false,
      },
      openDecision: { id: 8790, wakeTime: t - 3 * DAY, kind: 'TRADE', reasoning: 'SOL 放量突破 210 前高，跟随进场。', reason: null },
      closeDecision: null,
    },
    {
      positionId: 442, symbol: 'BTCUSDT', side: 'SHORT', leverage: 2,
      entryPrice: 61200, closedPrice: 60480, closedPnl: 143.2,
      openedAt: t - 4 * DAY, closedAt: t - 3 * DAY - 4 * HOUR, closeMannerKey: 'manual',
      plan: null,
      openDecision: { id: 8700, wakeTime: t - 4 * DAY, kind: 'ALERT', reasoning: '哨兵报 BTC 急跌，顺势做空一小档。', reason: null },
      closeDecision: { id: 8740, wakeTime: t - 3 * DAY - 4 * HOUR, kind: 'TRADE', reasoning: null, reason: '下跌动能衰竭，主动了结，不等目标位' },
    },
  ];
};

// ==================== 一轮唤醒的完整过程（现场流 + 历史回看共用同一份剧本）====================

const SYSTEM_PROMPT = `你是 Kairos，一个自主交易 agent。

## 身份
你管理一个 10,000 USDT 的虚拟合约账户，可交易 BTCUSDT / ETHUSDT / SOLUSDT 永续合约。
你的每一次决策都会被公开展示，观众看的是你的思考过程，不只是结果。

## 硬规则
1. 单币种敞口不超过账户权益 40%
2. 每一笔开仓必须同时给出止损价，没有止损的开仓会被拒绝
3. 开仓前必须先 write_plan 写下失效条件
4. 杠杆上限 10x，4h 周期建议不超过 5x

## 工具
交易类：open_position / close_position / set_stop_loss / set_take_profit / write_plan / cancel_order / get_account
数据类：klines / kline_structure / indicators / market_snapshot / funding_history / orderbook_depth / option_iv / news_search

不确定就查，不要猜价格。`;

const INSTRUCTION = `## 本轮唤醒
类型：TRADE（4h 例行）
时间：2026-09-05 12:00 (UTC+8)
预算：180 秒

## 账户
权益 11,842.36 USDT（本局起始 10,000，+18.42%）
可用保证金 8,799.46

## 当前持仓
- #501 BTCUSDT LONG 0.18 @ 62,480.5，5x，止损 62,300，止盈 66,500，浮盈 +226.75
- #502 SOLUSDT SHORT 12 @ 198.4，3x，止损 208.5，浮亏 -39.60

## 挂单
- #7781 ETHUSDT 限价买入 1.2 @ 2,418.0

## 上一轮结论
"SOL 空单是逆势单，不加仓，触到 208.5 就认。BTC 单继续拿，65,000 上方分批止盈。"

## 生效计划
- BTCUSDT LONG 趋势跟随，失效条件：4h 收盘跌破 62,300 或 MACD 柱转负
- SOLUSDT SHORT 区间高抛，失效条件：1h 收盘站上 205`;

/** 逐字吐字的三段：模型每次调用出的文本 */
const CALL_TEXTS = [
  '先确认 BTC 的 4h 结构和当前指标，上一轮的多单还在，得先看它有没有走坏。',

  '结构上 4h 已经站稳 EMA20，MACD 柱连续三根走扩，前高 62,900 转成支撑，趋势是延续而不是衰竭。\n\n**决定**：在现有多单上加一档 0.08，止损同步上移到 62,300（保本上方）。ETH 那边还在区间中轨，没有边缘机会，本轮不动它。',

  '仓位已就位，现在 BTC 敞口占权益 38%，贴着 40% 的线但没越。\n\nSOL 空单继续按上一轮的结论拿着，不加仓。下一轮重点看 BTC 能不能有效突破 64,200——站上去就把止盈从 66,500 往上挪。',
];

const KLINES_PREVIEW = `BTCUSDT 4h（最近 6 根，UTC+8）
2026-09-04 16:00  O 62180.0  H 62840.5  L 61920.3  C 62760.1  V 1420.8
2026-09-04 20:00  O 62760.1  H 63020.0  L 62410.7  C 62890.4  V 1188.2
2026-09-05 00:00  O 62890.4  H 63180.2  L 62650.0  C 63080.9  V 1352.6
2026-09-05 04:00  O 63080.9  H 63420.0  L 62940.1  C 63310.5  V 1601.4
2026-09-05 08:00  O 63310.5  H 63980.0  L 63190.8  C 63740.2  V 1842.3
2026-09-05 12:00  O 63740.2  H 63860.0  L 63520.4  C 63690.7  V  742.1 (未收盘)`;

const INDICATORS_PREVIEW = `BTCUSDT 4h 指标快照
RSI(14)     61.2   (中性偏多，未超买)
MACD        DIF 218.4 / DEA 196.1 / HIST +22.3  (柱体连续 3 根走扩)
EMA20       62,980.4
EMA60       61,452.8
BOLL        上 64,210 / 中 62,980 / 下 61,750
ATR(14)     890.2
成交量       较 20 根均量 +34%`;

const STRUCTURE_PREVIEW = `ETHUSDT 4h 结构
区间：2,340 - 2,610（已震荡 9 根）
当前价 2,462，位于区间中轨偏上（45%）
最近高点 2,598 (2026-09-03 08:00)
最近低点 2,356 (2026-09-04 12:00)
判定：区间震荡，无突破信号`;

/** 一轮的帧剧本；delay=距上一帧的毫秒。token 帧在播放时按字拆开发 */
type Frame = { delay: number; event: string; data: Record<string, unknown> };

const buildRun = (): Frame[] => {
  const startedAt = now();
  return [
    {
      delay: 400, event: 'run_start', data: {
        kind: 'TRADE', wakeTime: startedAt, startedAt, budgetSeconds: 180,
        equity: 11842.36, positions: 2, pendingOrders: 1,
      },
    },
    { delay: 500, event: 'prompt', data: { system: SYSTEM_PROMPT, instruction: INSTRUCTION } },

    // ---- 第 1 次调用：先查数据 ----
    { delay: 600, event: 'model_start', data: { call: 1 } },
    { delay: 0, event: '__tokens', data: { call: 1, text: CALL_TEXTS[0] } },
    {
      delay: 300, event: 'model_end', data: {
        call: 1, text: CALL_TEXTS[0], toolCalls: [
          { id: 'tc_1a', name: 'klines', args: { symbol: 'BTCUSDT', interval: '4h', limit: 200 } },
          { id: 'tc_1b', name: 'indicators', args: { symbol: 'BTCUSDT', interval: '4h' } },
          { id: 'tc_1c', name: 'kline_structure', args: { symbol: 'ETHUSDT', interval: '4h' } },
        ],
      },
    },
    { delay: 900, event: 'tool_result', data: { call: 1, id: 'tc_1a', name: 'klines', status: 'ok', preview: KLINES_PREVIEW } },
    { delay: 700, event: 'tool_result', data: { call: 1, id: 'tc_1b', name: 'indicators', status: 'ok', preview: INDICATORS_PREVIEW } },
    { delay: 1100, event: 'tool_result', data: { call: 1, id: 'tc_1c', name: 'kline_structure', status: 'ok', preview: STRUCTURE_PREVIEW } },

    // ---- 第 2 次调用：下手 ----
    { delay: 500, event: 'model_start', data: { call: 2 } },
    { delay: 0, event: '__tokens', data: { call: 2, text: CALL_TEXTS[1] } },
    {
      delay: 300, event: 'model_end', data: {
        call: 2, text: CALL_TEXTS[1], toolCalls: [
          { id: 'tc_2a', name: 'open_position', args: { symbol: 'BTCUSDT', side: 'LONG', quantity: 0.08, leverage: 5, stopLossPrice: 62300, takeProfitPrice: 66500, playType: '趋势跟随' } },
          { id: 'tc_2b', name: 'set_stop_loss', args: { positionId: 501, stopLossPrice: 62300, reason: '成本已覆盖，移到保本上方' } },
          { id: 'tc_2c', name: 'close_position', args: { positionId: 88, reason: '清掉旧的 ETH 空单' } },
        ],
      },
    },
    {
      delay: 1400, event: 'tool_result', data: {
        call: 2, id: 'tc_2a', name: 'open_position', status: 'ok',
        preview: '已开仓 #503 BTCUSDT LONG 0.08 @ 63,690.7，5x，保证金 1,019.05\n止损 62,300 / 止盈 66,500 已挂\n账户可用保证金 7,780.41',
      },
    },
    {
      delay: 800, event: 'tool_result', data: {
        call: 2, id: 'tc_2b', name: 'set_stop_loss', status: 'ok',
        preview: '持仓 #501 止损已更新：62,300（原 61,200）',
      },
    },
    {
      delay: 600, event: 'tool_result', data: {
        call: 2, id: 'tc_2c', name: 'close_position', status: 'rejected',
        preview: 'REJECTED: 持仓 88 不存在或已平仓。当前持仓：#501 BTCUSDT LONG、#502 SOLUSDT SHORT、#503 BTCUSDT LONG',
      },
    },

    // ---- 第 3 次调用：收尾，不再动手 ----
    { delay: 500, event: 'model_start', data: { call: 3 } },
    { delay: 0, event: '__tokens', data: { call: 3, text: CALL_TEXTS[2] } },
    { delay: 300, event: 'model_end', data: { call: 3, text: CALL_TEXTS[2], toolCalls: [] } },
    {
      delay: 800, event: 'run_end', data: {
        status: 'OK', error: null, equity: 11842.36, latencyMs: 42800,
        modelCalls: 3, totalTokens: 18420, decisionId: 9001,
      },
    },
  ];
};

/** 历史回看那份轨迹：跟现场跑完的形状一致，只是一次性给全 */
const decisionTrace = () => {
  const startedAt = now() - 12 * MIN;
  return {
    v: 1, kind: 'TRADE', wakeTime: startedAt, startedAt, budgetSeconds: 180,
    equity: 11842.36, positions: 2, pendingOrders: 1,
    prompt: { system: SYSTEM_PROMPT, instruction: INSTRUCTION },
    calls: [
      {
        n: 1, text: CALL_TEXTS[0], startedAt: startedAt + 900, endedAt: startedAt + 6200,
        toolCalls: [
          { id: 'tc_1a', name: 'klines', args: { symbol: 'BTCUSDT', interval: '4h', limit: 200 } },
          { id: 'tc_1b', name: 'indicators', args: { symbol: 'BTCUSDT', interval: '4h' } },
          { id: 'tc_1c', name: 'kline_structure', args: { symbol: 'ETHUSDT', interval: '4h' } },
        ],
        results: [
          { id: 'tc_1a', name: 'klines', status: 'ok', preview: KLINES_PREVIEW },
          { id: 'tc_1b', name: 'indicators', status: 'ok', preview: INDICATORS_PREVIEW },
          { id: 'tc_1c', name: 'kline_structure', status: 'ok', preview: STRUCTURE_PREVIEW },
        ],
      },
      {
        n: 2, text: CALL_TEXTS[1], startedAt: startedAt + 6800, endedAt: startedAt + 21400,
        toolCalls: [
          { id: 'tc_2a', name: 'open_position', args: { symbol: 'BTCUSDT', side: 'LONG', quantity: 0.08, leverage: 5, stopLossPrice: 62300, takeProfitPrice: 66500, playType: '趋势跟随' } },
          { id: 'tc_2b', name: 'set_stop_loss', args: { positionId: 501, stopLossPrice: 62300, reason: '成本已覆盖，移到保本上方' } },
          { id: 'tc_2c', name: 'close_position', args: { positionId: 88, reason: '清掉旧的 ETH 空单' } },
        ],
        results: [
          { id: 'tc_2a', name: 'open_position', status: 'ok', preview: '已开仓 #503 BTCUSDT LONG 0.08 @ 63,690.7，5x，保证金 1,019.05\n止损 62,300 / 止盈 66,500 已挂' },
          { id: 'tc_2b', name: 'set_stop_loss', status: 'ok', preview: '持仓 #501 止损已更新：62,300（原 61,200）' },
          { id: 'tc_2c', name: 'close_position', status: 'rejected', preview: 'REJECTED: 持仓 88 不存在或已平仓。' },
        ],
      },
      {
        n: 3, text: CALL_TEXTS[2], startedAt: startedAt + 22000, endedAt: startedAt + 42100,
        toolCalls: [], results: [],
      },
    ],
    end: { status: 'OK', error: null, equity: 11842.36, latencyMs: 42800, modelCalls: 3, totalTokens: 18420 },
  };
};

// ==================== 首页：资产 / 成交 / 快讯 / 爆仓 ====================

const pad = (n: number) => String(n).padStart(2, '0');
const r2 = (n: number) => Math.round(n * 100) / 100;
/** 后端发的是本地时区的 'yyyy-MM-dd HH:mm:ss' 字符串，mock 照这个形状给 */
const dateStr = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const timeStr = (ms: number) => {
  const d = new Date(ms);
  return `${dateStr(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

const START_CAPITAL = 10000;

/** 30 天净值：起手 10 万，中间有跌破本金的一段，收在 128,406.52 */
const EQUITY_SERIES = [
  10000, 10124, 9987, 9842, 9731, 9915, 10062, 10234, 10118, 9956,
  9872, 10031, 10288, 10412, 10346, 10579, 10724, 10631, 10865, 11042,
  10918, 11206, 11438, 11324, 11691, 11948, 11822, 12176, 11893, 11713.93,
];

/** 一天的资产快照：五分类按固定权重摊，够画拆解饼就行 */
const snap = (date: string, total: number, daily: number) => {
  const profit = total - START_CAPITAL;
  return {
    date, totalAssets: r2(total), profit: r2(profit), profitPct: r2(profit / START_CAPITAL * 100),
    bstockProfit: r2(profit * 0.45), cryptoProfit: r2(profit * 0.3), commodityProfit: r2(profit * 0.1),
    predictionProfit: r2(profit * 0.1), gameProfit: r2(profit * 0.05),
    dailyProfit: r2(daily), dailyProfitPct: r2(daily / (total - daily) * 100),
    dailyBstockProfit: r2(daily * 0.45), dailyCryptoProfit: r2(daily * 0.3), dailyCommodityProfit: r2(daily * 0.1),
    dailyPredictionProfit: r2(daily * 0.1), dailyGameProfit: r2(daily * 0.05),
  };
};

/** 快照写到昨天为止，今天那格由 asset-realtime 补 */
const assetHistory = () => {
  const last = new Date();
  last.setDate(last.getDate() - 1);
  return EQUITY_SERIES.map((v, i) => {
    const d = new Date(last);
    d.setDate(last.getDate() - (EQUITY_SERIES.length - 1 - i));
    return snap(dateStr(d), v, v - (i === 0 ? START_CAPITAL : EQUITY_SERIES[i - 1]));
  });
};

const assetRealtime = () => ({
  ...snap(dateStr(new Date()), 11842.36, 128.43),
  dailyProfitPct: 1.1,
});

/** 月度盈亏格子：null 的日子没快照，那条直接不给 */
const MONTH_PNL: (number | null)[] = [
  312, 128, -460, 890, 215, -90, 1240, 330, null, null, -720, 410, 95, -260, 1580, 240,
  -130, 620, 305, -980, 150, 870, 44, -310, 1120, 520, -75, 390, 230, -640, 1005,
];

const assetDaily = (month: string) => {
  const [y, m] = month.split('-').map(Number);
  if (!y || !m) return [];
  const today = new Date();
  const daysInMonth = new Date(y, m, 0).getDate();
  const isCurrent = y === today.getFullYear() && m === today.getMonth() + 1;
  const last = Math.min(daysInMonth, isCurrent ? today.getDate() - 1 : daysInMonth);
  const rows = [];
  let total = START_CAPITAL;
  for (let day = 1; day <= last; day++) {
    const pnl = MONTH_PNL[day - 1];
    if (pnl == null) continue;
    total += pnl;
    rows.push(snap(`${month}-${pad(day)}`, total, pnl));
  }
  return rows;
};

/** 现货成交（含代币化美股，共用一张表） */
const cryptoLive = () => {
  const t = now();
  return [
    { orderId: 70311, symbol: 'BTCUSDT', orderSide: 'BUY', orderType: 'MARKET', quantity: 0.12, leverage: 1, filledPrice: 109838.5, filledAmount: 13180.62, commission: 13.18, status: 'FILLED', createdAt: timeStr(t - 4 * MIN) },
    { orderId: 70308, symbol: 'NVDAUSDT', orderSide: 'SELL', orderType: 'MARKET', quantity: 40, leverage: 1, filledPrice: 182.4, filledAmount: 7296, commission: 7.3, status: 'FILLED', createdAt: timeStr(t - 17 * MIN) },
    { orderId: 70302, symbol: 'SOLUSDT', orderSide: 'BUY', orderType: 'MARKET', quantity: 25, leverage: 1, filledPrice: 209.6, filledAmount: 5240, commission: 5.24, status: 'FILLED', createdAt: timeStr(t - 43 * MIN) },
  ];
};

/** 合约成交：三条都是 trader 下的，首页那行会挂 AI 角标 */
const futuresLive = () => {
  const t = now();
  const base = { userId: 9001, orderType: 'MARKET', status: 'FILLED', isAiTrader: true };
  return [
    { ...base, orderId: 80412, positionId: 502, symbol: 'ETHUSDT', orderSide: 'OPEN_SHORT', quantity: 2.5, leverage: 3, filledPrice: 4312.48, filledAmount: 10781.2, marginAmount: 3593.73, commission: 4.31, createdAt: timeStr(t - 8 * MIN) },
    { ...base, orderId: 80407, positionId: 505, symbol: 'XAUUSDT', orderSide: 'OPEN_LONG', quantity: 3, leverage: 5, filledPrice: 3486.2, filledAmount: 10458.6, marginAmount: 2091.72, commission: 4.18, createdAt: timeStr(t - 24 * MIN) },
    { ...base, orderId: 80396, positionId: 506, symbol: 'SPCXUSDT', orderSide: 'OPEN_LONG', quantity: 20, leverage: 4, filledPrice: 412, filledAmount: 8240, marginAmount: 2060, commission: 3.3, createdAt: timeStr(t - 56 * MIN) },
  ];
};

/** 快讯：中英两套一起给，切语言不重拉 */
const news = () => {
  const t = now();
  return [
    {
      id: 61241, tags: 'MACRO', url: 'https://www.theblockbeats.info/news/61241', publishedAt: t - 3 * MIN,
      title: '美联储官员：9 月降息概率上升，市场已定价 25bp',
      content: '两位联储官员在讲话中暗示通胀回落速度快于预期，利率期货显示 9 月降息 25 个基点的概率升至 78%。',
      titleEn: 'Fed officials: September cut odds climb, market prices in 25bp',
      contentEn: 'Two Fed officials signalled inflation is cooling faster than expected; rate futures now put the odds of a 25bp cut in September at 78%.',
    },
    {
      id: 61238, tags: 'BTC', url: 'https://www.theblockbeats.info/news/61238', publishedAt: t - 32 * MIN,
      title: 'BTC 突破 11 万美元关口，24 小时全网爆仓 3.2 亿美元',
      content: '突破发生在美股开盘后一小时内，空头爆仓占比超过七成，ETH、SOL 同步走强。',
      titleEn: 'BTC breaks $110,000, $320M liquidated across the market in 24 hours',
      contentEn: 'The breakout landed within an hour of the US equity open, with shorts making up more than 70% of liquidations. ETH and SOL rallied alongside.',
    },
    {
      id: 61233, tags: 'NVDA', url: 'https://www.theblockbeats.info/news/61233', publishedAt: t - 67 * MIN,
      title: '英伟达盘后涨 2%，数据中心营收再超预期',
      content: '第二财季数据中心营收 411 亿美元，同比增长 56%，下季指引高于华尔街一致预期。',
      titleEn: 'Nvidia up 2% after hours as data center revenue beats again',
      contentEn: 'Data center revenue reached $41.1 billion in the second quarter, up 56% year over year, with next-quarter guidance above the Wall Street consensus.',
    },
    {
      id: 61227, tags: 'ETH', url: 'https://www.theblockbeats.info/news/61227', publishedAt: t - 106 * MIN,
      title: '以太坊现货 ETF 单日净流入 4.1 亿美元，创月内新高',
      content: '贝莱德 ETHA 贡献了其中的 2.9 亿美元，连续第 11 个交易日净流入。',
      titleEn: 'Spot Ether ETFs take in $410M in a day, a monthly high',
      contentEn: "BlackRock's ETHA accounted for $290 million of that, its 11th straight session of net inflows.",
    },
  ];
};

/** 全网强平：最近 1 小时 20 笔，SELL（多单被爆）合计 1.24M、BUY（空单被爆）合计 0.42M */
const LIQUIDATIONS = [
  { side: 'SELL', amount: 182400, symbol: 'BTCUSDT', price: 109760.4 },
  { side: 'BUY', amount: 88600, symbol: 'ETHUSDT', price: 4318.6 },
  { side: 'SELL', amount: 96300, symbol: 'ETHUSDT', price: 4306.2 },
  { side: 'SELL', amount: 148700, symbol: 'BTCUSDT', price: 109512.8 },
  { side: 'BUY', amount: 54300, symbol: 'SOLUSDT', price: 210.4 },
  { side: 'SELL', amount: 62150, symbol: 'SOLUSDT', price: 208.9 },
  { side: 'SELL', amount: 210800, symbol: 'BTCUSDT', price: 109180.5 },
  { side: 'BUY', amount: 112400, symbol: 'BTCUSDT', price: 109930.2 },
  { side: 'SELL', amount: 74600, symbol: 'DOGEUSDT', price: 0.2408 },
  { side: 'SELL', amount: 118900, symbol: 'ETHUSDT', price: 4288.4 },
  { side: 'BUY', amount: 39700, symbol: 'DOGEUSDT', price: 0.2436 },
  { side: 'SELL', amount: 55300, symbol: 'SOLUSDT', price: 207.6 },
  { side: 'BUY', amount: 26800, symbol: 'XRPUSDT', price: 2.184 },
  { side: 'SELL', amount: 132500, symbol: 'BTCUSDT', price: 108940.1 },
  { side: 'BUY', amount: 45100, symbol: 'ETHUSDT', price: 4330.8 },
  { side: 'SELL', amount: 47200, symbol: 'XRPUSDT', price: 2.166 },
  { side: 'BUY', amount: 31200, symbol: 'SOLUSDT', price: 211.2 },
  { side: 'SELL', amount: 68450, symbol: 'ETHUSDT', price: 4271.5 },
  { side: 'BUY', amount: 21900, symbol: 'BTCUSDT', price: 110080.6 },
  { side: 'SELL', amount: 42700, symbol: 'DOGEUSDT', price: 0.2391 },
];

const forceOrders = () => {
  const t = now();
  const records = LIQUIDATIONS.map((r, i) => {
    const at = timeStr(t - i * 150_000);   // 2.5 分钟一笔，二十笔铺满 50 分钟
    return {
      id: 33000 - i, symbol: r.symbol, side: r.side, price: r.price, avgPrice: r.price,
      quantity: r2(r.amount / r.price), amount: r.amount, status: 'FILLED', tradeTime: at, createdAt: at,
    };
  });
  return { records, total: records.length, size: 200, current: 1, pages: 1 };
};

/** 下一个整 4 小时点 */
const nextWake = () => {
  const d = new Date();
  d.setMinutes(0, 0, 0);
  d.setHours(Math.floor(d.getHours() / 4) * 4 + 4);
  return d.getTime();
};

// ==================== 交易页 / 持仓页：合约与现货 ====================

/** 分页壳：假数据一页装得下，records 直接全给 */
const page = <T,>(records: T[], size = 10) => ({
  records, total: records.length, size, current: 1, pages: 1,
});

/**
 * 合约委托：按当前 symbol 现算 6 条，挂单中/已成交/止盈/止损/已撤销各来一份。
 * 每个币种页都有单可看，图上的 B/S 标记也是靠这些成交价画的
 */
const futuresOrders = (symbol: string) => {
  const t = now();
  const p = basePrice(symbol);
  const px = (k: number) => roundPrice(symbol, p * k);
  const qty = Number((10000 / p).toFixed(3));      // 一万美金名义额折出来的数量
  const base = { userId: 9001, symbol, leverage: 5, isAiTrader: false };
  return [
    {
      ...base, orderId: 80520, orderSide: 'OPEN_LONG', orderType: 'LIMIT', quantity: qty,
      limitPrice: px(0.985), frozenAmount: 2000, status: 'PENDING', createdAt: timeStr(t - 6 * MIN),
    },
    {
      ...base, orderId: 80512, positionId: 501, orderSide: 'OPEN_LONG', orderType: 'MARKET', quantity: qty,
      filledPrice: px(0.972), filledAmount: r2(qty * px(0.972)), marginAmount: 1944, commission: 3.89,
      status: 'FILLED', createdAt: timeStr(t - 26 * HOUR), isAiTrader: true,
    },
    {
      ...base, orderId: 80498, positionId: 501, orderSide: 'OPEN_LONG', orderType: 'MARKET',
      quantity: Number((qty * 0.5).toFixed(3)),
      filledPrice: px(1.004), filledAmount: r2(qty * 0.5 * px(1.004)), marginAmount: 1004, commission: 2.01,
      status: 'FILLED', createdAt: timeStr(t - 9 * HOUR), isAiTrader: true,
    },
    {
      ...base, orderId: 80471, positionId: 494, orderSide: 'CLOSE_LONG', orderType: 'LIMIT', quantity: qty,
      filledPrice: px(1.043), filledAmount: r2(qty * px(1.043)), commission: 4.17, realizedPnl: 386.4,
      status: 'TAKE_PROFIT', createdAt: timeStr(t - 2 * DAY),
    },
    {
      ...base, orderId: 80455, positionId: 488, orderSide: 'CLOSE_SHORT', orderType: 'MARKET', quantity: qty,
      filledPrice: px(1.021), filledAmount: r2(qty * px(1.021)), commission: 4.08, realizedPnl: -212.7,
      status: 'STOP_LOSS', createdAt: timeStr(t - 3 * DAY),
    },
    {
      ...base, orderId: 80430, orderSide: 'OPEN_SHORT', orderType: 'LIMIT', quantity: qty,
      limitPrice: px(1.062), frozenAmount: 2000, status: 'CANCELLED', createdAt: timeStr(t - 4 * DAY),
    },
  ];
};

/** 现货委托：同样按当前 symbol 现算，成交 / 挂单 / 已撤各一条 */
const spotOrders = (symbol: string) => {
  const t = now();
  const p = basePrice(symbol);
  const px = (k: number) => roundPrice(symbol, p * k);
  const qty = Number((4000 / p).toFixed(5));
  return [
    {
      orderId: 70420, symbol, orderSide: 'BUY', orderType: 'MARKET', quantity: qty, leverage: 1,
      filledPrice: px(0.961), filledAmount: r2(qty * px(0.961)), commission: r2(qty * px(0.961) * 0.001),
      status: 'FILLED', createdAt: timeStr(t - 31 * HOUR),
    },
    {
      orderId: 70415, symbol, orderSide: 'BUY', orderType: 'LIMIT', quantity: Number((qty * 0.5).toFixed(5)),
      leverage: 1, limitPrice: px(0.942), status: 'PENDING', createdAt: timeStr(t - 52 * MIN),
    },
    {
      orderId: 70402, symbol, orderSide: 'SELL', orderType: 'LIMIT', quantity: Number((qty * 0.3).toFixed(5)),
      leverage: 1, limitPrice: px(1.088), status: 'CANCELLED', createdAt: timeStr(t - 2 * DAY),
    },
  ];
};

/** 档位表：一条标准梯子按各币最大杠杆缩放，够开仓面板算强平价、持仓卡显示 MMR */
const ladder = (maxLev: number) => [
  { tier: 1, notionalFloor: 0, notionalCap: 50_000, maxLeverage: maxLev, mmr: 0.004, maintAmount: 0 },
  { tier: 2, notionalFloor: 50_000, notionalCap: 250_000, maxLeverage: Math.min(maxLev, 50), mmr: 0.005, maintAmount: 50 },
  { tier: 3, notionalFloor: 250_000, notionalCap: 1_000_000, maxLeverage: Math.min(maxLev, 25), mmr: 0.01, maintAmount: 1300 },
  { tier: 4, notionalFloor: 1_000_000, notionalCap: 5_000_000, maxLeverage: Math.min(maxLev, 10), mmr: 0.025, maintAmount: 16_300 },
];

const BRACKETS: Record<string, ReturnType<typeof ladder>> = {
  BTCUSDT: ladder(125), ETHUSDT: ladder(100), SOLUSDT: ladder(75),
  XAUUSDT: ladder(50), CLUSDT: ladder(50), DOGEUSDT: ladder(75),
};

/** 交易过滤器：前端本地有同名兜底表，这里给同一份口径 */
const TRADE_FILTERS = {
  futures: {
    BTCUSDT: { stepSize: 0.001, minQty: 0.001, minNotional: 50 },
    ETHUSDT: { stepSize: 0.001, minQty: 0.001, minNotional: 20 },
    SOLUSDT: { stepSize: 0.01, minQty: 0.01, minNotional: 5 },
    DOGEUSDT: { stepSize: 1, minQty: 1, minNotional: 5 },
    XAUUSDT: { stepSize: 0.001, minQty: 0.001, minNotional: 5 },
    CLUSDT: { stepSize: 0.01, minQty: 0.01, minNotional: 5 },
  },
  spot: {
    BTCUSDT: { stepSize: 0.00001, minQty: 0.00001, minNotional: 5 },
    ETHUSDT: { stepSize: 0.0001, minQty: 0.0001, minNotional: 5 },
    SOLUSDT: { stepSize: 0.001, minQty: 0.001, minNotional: 5 },
    DOGEUSDT: { stepSize: 1, minQty: 1, minNotional: 1 },
  },
};

/** 全仓账户：可用 = 余额 − 占用 − 挂单冻结，净值 = 余额 + 浮盈，跟 POSITIONS 对得上 */
const CROSS_ACCOUNT = {
  balance: 11655.21, unrealizedPnl: 187.15, equity: 11842.36, available: 7886.91,
  usedMargin: 3042.9, pendingReserved: 725.4, maintenanceMargin: 69.47, positionCount: 2,
};

/** 现货持仓（crypto_position 一张表混着币和代币化美股，持仓页按 bstock 列表拆） */
const SPOT_POSITIONS = [
  { id: 1101, symbol: 'BTCUSDT', quantity: 0.24, frozenQuantity: 0, avgCost: 61240, totalDiscount: 38.5 },
  { id: 1102, symbol: 'ETHUSDT', quantity: 3.2, frozenQuantity: 0.4, avgCost: 2288.6, totalDiscount: 0 },
  { id: 1103, symbol: 'NVDAUSDT', quantity: 40, frozenQuantity: 0, avgCost: 168.2, totalDiscount: 12.4 },
  { id: 1104, symbol: 'TSLAUSDT', quantity: 12, frozenQuantity: 0, avgCost: 352.6, totalDiscount: 0 },
];

/** 代币化美股：symbol 带 USDT 后缀，ticker 才是股票代号（行情条按市值取前四） */
const BSTOCKS = [
  {
    id: 1, symbol: 'NVDAUSDT', ticker: 'NVDA', name: '英伟达', nameEn: 'NVIDIA', industry: '半导体',
    marketCap: 4.42e12, peRatio: 52.4, week52High: 195.6, week52Low: 86.2,
  },
  {
    id: 2, symbol: 'AAPLUSDT', ticker: 'AAPL', name: '苹果', nameEn: 'Apple', industry: '消费电子',
    marketCap: 3.51e12, peRatio: 34.1, week52High: 248.4, week52Low: 169.2,
  },
  {
    id: 3, symbol: 'TSLAUSDT', ticker: 'TSLA', name: '特斯拉', nameEn: 'Tesla', industry: '汽车',
    marketCap: 1.09e12, peRatio: 78.6, week52High: 412.8, week52Low: 214.3,
  },
  {
    id: 4, symbol: 'QQQUSDT', ticker: 'QQQ', name: '纳指100ETF', nameEn: 'Invesco QQQ Trust', industry: 'ETF',
    marketCap: 3.2e11, peRatio: 32.8, week52High: 512.4, week52Low: 402.1,
  },
];

/** 列表带实时价：价格和涨跌幅现算，行情条拿到就能显示 */
const bstockList = () => BSTOCKS.map(b => {
  const p = livePrice(b.symbol);
  return {
    ...b, price: p, changePct: r2((p / basePrice(b.symbol) - 1) * 100 + 1.2),
    high: roundPrice(b.symbol, p * 1.018), low: roundPrice(b.symbol, p * 0.982),
    volume: Math.round(8_000_000 + b.id * 3_100_000),
  };
});

/** 持仓页五分类 30 日均值 */
const CATEGORY_AVERAGES = {
  bstockProfit: 612.4, cryptoProfit: 408.9, commodityProfit: 136.3,
  predictionProfit: 138.7, gameProfit: 68.2,
};

const PREDICTION_PNL = {
  totalBets: 42, activeBets: 2, wonBets: 24, lostBets: 16,
  totalCost: 1260, realizedPnl: 184.6, activeCost: 60, activeValue: 71.2,
  totalPnl: 195.8, winRate: 57.14,
};

// ==================== SSE 收发 ====================

const sseOpen = (res: ServerResponse) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    // dev server 前面没有 nginx，但带上不亏——有些代理会缓冲整条流，表现为一次性刷出全部帧
    'X-Accel-Buffering': 'no',
  });
};

const send = (res: ServerResponse, event: string, data: unknown) => {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
};

const wait = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

/** 详情页现场流：一轮播完歇 8 秒再来一轮，随时打开都能赶上动画 */
async function playLive(res: ServerResponse, alive: () => boolean) {
  while (alive()) {
    const frame = (event: string, data: Record<string, unknown>) => send(res, event, data);

    for (const f of buildRun()) {
      if (!alive()) return;
      await wait(f.delay);
      if (!alive()) return;
      // 伪事件：把整段文本拆成 token 帧一个个发，这样界面上是"打字"出来的
      if (f.event === '__tokens') {
        const call = f.data.call as number;
        for (const ch of String(f.data.text)) {
          if (!alive()) return;
          frame('token', { call, text: ch });
          await wait(38);
        }
        continue;
      }
      frame(f.event, f.data);
    }
    // 空窗期：现场卡收起来，回到"没在跑"的样子
    for (let i = 0; i < 8 && alive(); i++) {
      await wait(1000);
      res.write(': keepalive\n\n');
    }
  }
}

// ==================== 插件 ====================

const ok = (res: ServerResponse, data: unknown) => {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ code: 0, msg: 'ok', data }));
};

export function traderMock(): Plugin {
  return {
    name: 'wiib-trader-mock',

    // 免登录：守卫只看 localStorage 里的 token，开页前先塞一个假的
    transformIndexHtml() {
      return [{
        tag: 'script',
        injectTo: 'head-prepend' as const,
        // ?dark / ?light 钉死主题，?en 切英文；语言弹层和首页公告也按"已看过"处理，截图省得手点
        children: `localStorage.setItem('wiib-user', JSON.stringify({ state: { token: 'mock-token' }, version: 0 }));
localStorage.setItem('wiib-lang-chosen', '1');
localStorage.setItem('wiib-lang', location.search.includes('en') ? 'en' : 'zh');
localStorage.setItem('wiib-notice-seen', '1');
if (location.search.includes('dark')) localStorage.setItem('theme', 'dark');
else if (location.search.includes('light')) localStorage.setItem('theme', 'light');`,
      }];
    },

    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url || '';
        // 假行情流：SockJS 那几条不在 /api 底下，先让它挑走
        if (handleQuotes(req, res)) return;
        if (!url.startsWith('/api/')) return next();
        const path = url.split('?')[0];
        const q = new URLSearchParams(url.split('?')[1] ?? '');

        // ---- 现场流 SSE ----
        const liveMatch = path.match(/^\/api\/ai\/trader\/(\d+)\/live$/);
        if (liveMatch) {
          sseOpen(res);
          let closed = false;
          req.on('close', () => { closed = true; });
          // 只有 1 号有现场，其余挂着不发（对应"没在唤醒"）
          if (liveMatch[1] === '1') void playLive(res, () => !closed);
          return;
        }

        // ---- 首页：这些要排在兜底前面 ----
        if (path === '/api/user/asset-history') return ok(res, assetHistory());
        if (path === '/api/user/asset-realtime') return ok(res, assetRealtime());
        if (path === '/api/user/asset-daily') {
          return ok(res, assetDaily(q.get('month') ?? dateStr(new Date()).slice(0, 7)));
        }
        if (path === '/api/crypto/order/live') return ok(res, cryptoLive());
        if (path === '/api/futures/live') return ok(res, futuresLive());
        if (path === '/api/futures/force-orders') return ok(res, forceOrders());
        if (path === '/api/buff/status') return ok(res, { canDraw: true, todayBuff: null });
        if (path === '/api/ai/quant/news') return ok(res, news());
        if (path === '/api/ai/trader/mine') {
          return ok(res, {
            pub: TRADERS[0],
            llmEndpointId: 12, customPrompt: null, useDefaultPrompt: true,
            spec: { leverageMin: 2, leverageMax: 10, marginPctMin: 5, marginPctMax: 25, allowMultiPosition: true, allowHedge: false },
            alertEnabled: true, alertThresholdMult: 1,
            reviewEnabled: true, learningEnabled: true, wakeWindow: null,
          });
        }
        if (path === '/api/ai/trader/action-panel') {
          return ok(res, {
            hasTrader: true, name: 'Kairos', status: 'RUNNING', pausedReason: null,
            lastWakeAt: now() - 12 * MIN, nextWakeAt: nextWake(), wakeBlockedReason: null,
            lastReviewAt: now() - 14 * HOUR, lastReviewStatus: 'OK',
            hasReviewMaterial: true, reviewBlockedReason: null,
            note: null, noteRounds: 1, noteMaxRounds: 24, noteMaxChars: 500,
          });
        }

        // ---- 普通 GET ----
        if (path === '/api/ai/trader/arena') return ok(res, TRADERS);
        if (path === '/api/auth/current') {
          return ok(res, {
            id: 9001, username: 'mock', balance: 8799.46, gameBalance: 0, frozenBalance: 725.4,
            positionMarketValue: 13893.6, marginLoanPrincipal: 0, marginInterestAccrued: 0,
            bankrupt: false, bankruptCount: 0, totalAssets: 11842.36, profit: 1842.36, profitPct: 18.42,
          });
        }
        if (/^\/api\/ai\/trader\/\d+\/decisions\/\d+\/trace$/.test(path)) return ok(res, decisionTrace());
        if (/^\/api\/ai\/trader\/\d+\/decisions$/.test(path)) return ok(res, decisions());
        if (/^\/api\/ai\/trader\/\d+\/equity-curve$/.test(path)) return ok(res, equityCurve());
        if (/^\/api\/ai\/trader\/\d+\/trades$/.test(path)) return ok(res, trades());
        if (/^\/api\/ai\/trader\/\d+\/token-usage$/.test(path)) return ok(res, 96560);

        const detailMatch = path.match(/^\/api\/ai\/trader\/(\d+)$/);
        if (detailMatch) {
          const tr = TRADERS.find(x => x.id === Number(detailMatch[1])) ?? TRADERS[0];
          const mine = tr.id === 1;
          return ok(res, {
            trader: tr,
            positions: mine ? POSITIONS : [],
            pendingOrders: mine ? PENDING_ORDERS : [],
            plans: mine ? PLANS : [],
            memory: mine ? MEMORY : null,
            learningNotes: mine ? LEARNING_NOTES : null,
            lastReviewAt: mine ? now() - 14 * HOUR : null,
            lastLearnAt: mine ? now() - 18 * HOUR : null,
          });
        }

        // ---- 交易页：合约 ----
        if (path === '/api/futures/positions') {
          const sym = q.get('symbol');
          return ok(res, sym ? POSITIONS.filter(p => p.symbol === sym) : POSITIONS);
        }
        if (path === '/api/futures/orders') {
          const status = q.get('status'), sym = q.get('symbol') || 'BTCUSDT';
          const rows = futuresOrders(sym).filter(o => !status || o.status === status);
          return ok(res, page(rows, Number(q.get('pageSize')) || 10));
        }
        if (path === '/api/futures/brackets') return ok(res, BRACKETS);
        if (path === '/api/futures/trade-filters') return ok(res, TRADE_FILTERS);
        if (path === '/api/futures/cross-account') return ok(res, CROSS_ACCOUNT);
        if (path === '/api/futures/position-history') return ok(res, page([]));
        // 资金费率：真后端只在 0/8/16 点拉一次写缓存，这里照那个节奏给时间戳；无合约的标的返 null
        if (path === '/api/futures/funding-rate') {
          const sym = q.get('symbol') || '';
          if (!hasFutures(sym)) return ok(res, null);
          const slot = Math.floor(now() / (8 * HOUR)) * 8 * HOUR;
          return ok(res, { symbol: sym, rate: 0.0001, fetchedAt: slot, nextTime: slot + 8 * HOUR });
        }

        // ---- 交易页：现货 ----
        if (path === '/api/crypto/price') {
          const sym = q.get('symbol') || 'BTCUSDT';
          return ok(res, { price: String(livePrice(sym)), ts: String(now()) });
        }
        if (path === '/api/crypto/order/position') {
          const sym = q.get('symbol') || 'BTCUSDT';
          return ok(res, SPOT_POSITIONS.find(p => p.symbol === sym) ?? null);
        }
        if (path === '/api/crypto/order/positions') return ok(res, SPOT_POSITIONS);
        if (path === '/api/crypto/order/list' || path === '/api/bstock/order/list') {
          const status = q.get('status'), sym = q.get('symbol') || 'BTCUSDT';
          const rows = spotOrders(sym).filter(o => !status || o.status === status);
          return ok(res, page(rows, Number(q.get('pageSize')) || 10));
        }
        if (path === '/api/bstock/order/positions') {
          return ok(res, SPOT_POSITIONS.filter(p => BSTOCKS.some(b => b.symbol === p.symbol)));
        }

        // ---- 代币化美股 ----
        if (path === '/api/bstock/list') return ok(res, bstockList());
        if (path === '/api/bstock/price') return ok(res, livePrice(q.get('symbol') || 'NVDAUSDT'));
        const bstockMatch = path.match(/^\/api\/bstock\/([A-Z0-9]+)$/);
        if (bstockMatch) {
          return ok(res, bstockList().find(b => b.symbol === bstockMatch[1]) ?? bstockList()[0]);
        }

        // ---- 账单：类型筛选与 beforeId 游标都走预览接口，浏览器无需另塞测试数据 ----
        if (path === '/api/ledger/biz-types') return ok(res, LEDGER_BIZ_TYPES);
        if (path === '/api/ledger') {
          const bizType = q.get('bizType');
          const beforeId = Number(q.get('beforeId')) || Infinity;
          const limit = Math.max(1, Math.min(Number(q.get('limit')) || 30, 100));
          const rows = LEDGER_ENTRIES.filter(entry => (!bizType || entry.bizType === bizType) && entry.id < beforeId);
          return ok(res, rows.slice(0, limit));
        }

        // ---- 真人排行与用户主页 ----
        if (path === '/api/ranking') {
          const rows = rankingRows(q.get('sort'));
          const size = Math.max(1, Math.min(Number(q.get('pageSize')) || 20, 100));
          const current = Math.max(1, Math.trunc(Number(q.get('pageNum')) || 1));
          return ok(res, {
            records: rows.slice((current - 1) * size, current * size),
            total: rows.length, size, current, pages: Math.ceil(rows.length / size),
          });
        }
        if (path === '/api/ranking/me') return ok(res, rankingRows(q.get('sort')).find(row => row.userId === 9001));
        const rankingUser = path.match(/^\/api\/ranking\/users\/(\d+)(?:\/(trades|position-history))?$/);
        if (rankingUser) {
          if (rankingUser[2]) return ok(res, page([]));
          const summary = rankingRows('ASSETS').find(row => row.userId === Number(rankingUser[1]));
          return ok(res, summary ? { summary, spotPositions: [], futuresPositions: [] } : null);
        }

        // ---- 持仓页 ----
        if (path === '/api/user/portfolio') {
          return ok(res, {
            id: 9001, username: 'mock', balance: 8799.46, gameBalance: 0, frozenBalance: 725.4,
            positionMarketValue: 13893.6, marginLoanPrincipal: 0, marginInterestAccrued: 0,
            bankrupt: false, bankruptCount: 0, totalAssets: 11842.36, profit: 1842.36, profitPct: 18.42,
          });
        }
        if (path === '/api/user/category-averages') return ok(res, CATEGORY_AVERAGES);
        if (path === '/api/prediction/pnl') return ok(res, PREDICTION_PNL);
        // K 线上的快讯标记：跟快讯用同一批，publishedAt 已经贴着现在
        if (path === '/api/ai/quant/news-events') return ok(res, news());

        // K 线不走业务包装（rawKlines 直接吃 res.data），要裸数组；
        // 给成 {code,data} 的话行情条那句 [...list] 会在 then 里抛，控制台一片红
        if (path.endsWith('/klines')) {
          const end = Number(q.get('endTime')) || undefined;
          const rows = klines(q.get('symbol') || 'BTCUSDT', q.get('interval') || '1h', Number(q.get('limit')) || 500, end);
          res.setHeader('Content-Type', 'application/json; charset=utf-8');
          return res.end(JSON.stringify(rows));
        }

        // ---- 写操作：一律成功，不改内存状态；两个要读返回值的给最小形状 ----
        if (req.method !== 'GET') {
          if (path === '/api/futures/close-all') return ok(res, { closedCount: POSITIONS.length, failures: [] });
          const reverseMatch = path.match(/^\/api\/futures\/reverse\/(\d+)$/);
          if (reverseMatch) {
            const pos = POSITIONS.find(p => p.id === Number(reverseMatch[1])) ?? POSITIONS[0];
            const flip = pos.side === 'LONG' ? 'SHORT' : 'LONG';
            const mk = (orderId: number, orderSide: string, realizedPnl?: number) => ({
              orderId, userId: 9001, positionId: pos.id, symbol: pos.symbol, orderSide,
              orderType: 'MARKET', quantity: pos.quantity, leverage: pos.leverage,
              filledPrice: pos.markPrice, filledAmount: r2(pos.quantity * pos.markPrice),
              commission: 2.3, realizedPnl, status: 'FILLED', createdAt: timeStr(now()),
            });
            return ok(res, {
              closed: mk(90001, `CLOSE_${pos.side}`, pos.unrealizedPnl),
              opened: mk(90002, `OPEN_${flip}`),
              openError: null,
            });
          }
          return ok(res, null);
        }

        // 兜底：Layout 的顶栏/通知/行情条那些接口，给个空的就行
        return ok(res, path.endsWith('/list') ? [] : null);
      });
    },
  };
}
