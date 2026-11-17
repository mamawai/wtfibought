/**
 * 管理页（/admin）和 testnet 页（/testnet）的假数据。
 * 这两页只有 1 号用户能进：开页时网址带 ?admin，当前用户就变成 1 号（见 traderMock 里的 wiib-mock-admin）。
 */
export const MIN = 60_000;
export const HOUR = 60 * MIN;
export const DAY = 24 * HOUR;

export const r2 = (v: number) => Math.round(v * 100) / 100;
/** 东八区 yyyy-MM-dd，跟 testnet 页切日的口径一样 */
const cnDate = (ms: number) => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });

// ==================== 管理页 ====================

export const ADMIN_RATE = 0.0005;

export const ADMIN_KEYS = [
  { id: 1, configName: 'Claude 翻译', apiKeyTail: 'x7Qd', baseUrl: 'https://api.anthropic.com', model: 'claude-haiku-5-5', reasoningEffort: 'low', apiProtocol: 'anthropic' },
  { id: 2, configName: 'DeepSeek 备用', apiKeyTail: 'b2Lm', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', apiProtocol: 'openai' },
  { id: 3, configName: 'GPT 轻量', apiKeyTail: '9fKe', baseUrl: 'https://api.openai.com', model: 'gpt-5-mini', reasoningEffort: 'medium', apiProtocol: 'responses' },
];

export const ADMIN_ASSIGNMENTS = [
  { id: 1, functionName: 'news-translation', configId: 1 },
  { id: 2, functionName: 'news-translation-fallback', configId: 2 },
];

export const inviteCodes = () => [
  { id: 14, code: 'K7Q2-MX9D', maxUses: 5, usedCount: 2, enabled: true, createdAt: new Date(Date.now() - 2 * DAY).toISOString() },
  { id: 13, code: 'T4HB-0PWE', maxUses: 1, usedCount: 0, enabled: true, createdAt: new Date(Date.now() - 2 * DAY).toISOString() },
  { id: 12, code: 'R9ZA-6LCN', maxUses: 1, usedCount: 1, enabled: true, createdAt: new Date(Date.now() - 9 * DAY).toISOString() },
  { id: 11, code: 'W2FJ-8YTS', maxUses: 3, usedCount: 1, enabled: false, createdAt: new Date(Date.now() - 20 * DAY).toISOString() },
];

/** feed 的行情流：名字照抄 wiib-feed 里各条流的 name()。一条在重连、一条闲置，别的都连着 */
export const feedStreams = () => ({
  version: Date.now(),
  streams: ['Spot', 'SpotAggTrade', 'SpotDepth', 'Futures', 'FuturesMiniTick', 'AggTrade', 'Depth', 'DepthDiff', 'ForceOrder', 'TraderFutures', 'TraderMiniTick']
    .map(name => (
      name === 'DepthDiff' ? { name, status: 'RECONNECTING', lastMessageAt: Date.now() - 42_000, reconnectAttempt: 3 }
      : name === 'TraderMiniTick' ? { name, status: 'IDLE', lastMessageAt: 0, reconnectAttempt: 0 }
      : { name, status: 'CONNECTED', lastMessageAt: Date.now() - 800, reconnectAttempt: 0 }
    )),
});

/** 某个进程的 JVM 状况：数随时间小幅晃，agent 那台堆吃得紧一些 */
export const jvm = (proc: string) => {
  const seed = proc === 'sim' ? 1 : proc === 'feed' ? 2 : 3;
  const wave = Math.sin(Date.now() / 4000 + seed * 2);
  const heapMax = proc === 'agent' ? 2048 : 1024;
  const heapUsed = Math.round(heapMax * (proc === 'agent' ? 0.72 : 0.34 + seed * 0.06) + wave * 40);
  return {
    heap: { used: heapUsed, committed: Math.round(heapMax * 0.9), max: heapMax },
    nonHeap: { used: 150 + seed * 22, committed: 190 + seed * 24, max: -1 },
    thread: { current: 60 + seed * 14, peak: 78 + seed * 15, daemon: 44 + seed * 11 },
    gc: [
      { name: 'G1 Young Generation', count: 380 * seed, timeMs: 2400 * seed },
      { name: 'G1 Old Generation', count: seed - 1, timeMs: (seed - 1) * 140 },
    ],
    cpuPct: Math.max(1, Math.round(6 + seed * 5 + wave * 4)),
    uptimeSec: 3 * 86400 + seed * 4000 + Math.floor(Date.now() / 1000) % 3600,
  };
};

// ==================== testnet 页 ====================

/** 近两个月的成交：每天 0~3 笔，单数那笔是平仓、带盈亏。同一天每次算出来都一样 */
const tnAllTrades = () => {
  const out = [];
  let id = 5000;
  for (let d = 58; d >= 0; d--) {
    const n = (d * 7 + 3) % 4;
    for (let k = 0; k < n; k++) {
      const symbol = (d + k) % 3 === 0 ? 'ETHUSDT' : 'BTCUSDT';
      const closing = k % 2 === 1;
      const price = symbol === 'BTCUSDT' ? 63000 + (d * 37) % 900 : 2400 + (d * 11) % 60;
      const qty = symbol === 'BTCUSDT' ? 0.002 : 0.05;
      id++;
      out.push({
        id, orderId: 880000 + id, symbol, side: closing ? 'SELL' : 'BUY', positionSide: 'BOTH',
        price, qty, quoteQty: r2(price * qty),
        realizedPnl: closing ? r2(((d * 13 + k * 7) % 17 - 7) * 0.63) : 0,
        commission: r2(price * qty * 0.0002), commissionAsset: 'USDT',
        maker: (d + k) % 4 !== 0, buyer: !closing,
        time: Date.now() - d * DAY - k * 3 * HOUR - 20 * MIN,
      });
    }
  }
  return out;
};

const tnOf = (symbol: string | null) => tnAllTrades().filter(t => !symbol || t.symbol === symbol);

/** 成交列表，新的在前 */
export const tnTrades = (symbol: string | null) => tnOf(symbol).reverse();

/** 日历网格：每天的净盈亏（扣手续费）和平仓笔数 */
export const tnDailyGrid = (symbol: string | null) => {
  const days = new Map<string, { date: string; pnl: number; tradeCount: number }>();
  for (const t of tnOf(symbol)) {
    const date = cnDate(t.time);
    const cell = days.get(date) ?? { date, pnl: 0, tradeCount: 0 };
    cell.pnl = r2(cell.pnl + t.realizedPnl - t.commission);
    if (t.realizedPnl !== 0) cell.tradeCount++;
    days.set(date, cell);
  }
  return [...days.values()];
};

/** 权益曲线：累计已实现盈亏，从 0 起 */
export const tnEquity = (symbol: string | null) => {
  let cum = 0;
  return tnOf(symbol).filter(t => t.realizedPnl !== 0).map(t => ({ time: t.time, cumPnl: (cum = r2(cum + t.realizedPnl)) }));
};

export const TN_FILL_STATS = { placed: 86, filled: 51, expired: 35, fillRate: 51 / 86, avgFillSeconds: 412, makerConfirmed: 49 };

export const tnOverview = () => ({
  account: { walletBalance: 5120.4, marginBalance: 5134.92, unrealizedProfit: 14.52, availableBalance: 4870.1 },
  positions: [
    { symbol: 'BTCUSDT', side: 'LONG', positionAmt: 0.004, entryPrice: 63120.5, markPrice: 63738.9, unrealizedProfit: 2.47, liquidationPrice: 60210.3, leverage: 20 },
    { symbol: 'ETHUSDT', side: 'SHORT', positionAmt: 0.1, entryPrice: 2541.2, markPrice: 2420.7, unrealizedProfit: 12.05, liquidationPrice: 2655.8, leverage: 20 },
  ],
  openOrders: [
    { symbol: 'BTCUSDT', orderId: 990101, clientOrderId: 'wiib-e-1', side: 'BUY', type: 'LIMIT', price: 63200, stopPrice: 0, origQty: 0.002, status: 'NEW', time: Date.now() - 12 * MIN },
    { symbol: 'BTCUSDT', orderId: 990102, clientOrderId: 'wiib-sl-1', side: 'SELL', type: 'STOP_MARKET', price: 0, stopPrice: 62400, origQty: 0.004, status: 'NEW', time: Date.now() - 2 * HOUR },
    { symbol: 'ETHUSDT', orderId: 990103, clientOrderId: 'wiib-tp-1', side: 'BUY', type: 'TAKE_PROFIT_MARKET', price: 0, stopPrice: 2380, origQty: 0.1, status: 'NEW', time: Date.now() - 5 * HOUR },
  ],
  executionTarget: 'sim',
});
