/**
 * 假行情的公共数据源：现价表 + 确定性随机游走 K 线 + 秒级抖动的实时价。
 * REST 的 /klines 和假 STOMP 流都从这儿取数，两边价格才对得上。
 */

/** 有合约的标的现价（crypto + 大宗商品 + TradFi 永续）：K 线末根收盘钉在这儿，实时流也绕着它抖 */
const FUTURES_PRICE: Record<string, number> = {
  BTCUSDT: 63720, ETHUSDT: 2418, SOLUSDT: 201.4, DOGEUSDT: 0.2412, XRPUSDT: 2.184, BNBUSDT: 612.5,
  XAUUSDT: 3486, CLUSDT: 63.4,
  // TradFi 永续
  SNDKUSDT: 118.6, SOXLUSDT: 28.42, SKHYNIXUSDT: 142.3, MUUSDT: 108.7, KORUUSDT: 62.1, SPCXUSDT: 412,
};

/** 代币化美股：纯现货，没有合约也没有资金费率。符号照真后端，带 B 后缀 */
const BSTOCK_PRICE: Record<string, number> = {
  NVDABUSDT: 182.4, TSLABUSDT: 341.2, MUBUSDT: 108.9, SNDKBUSDT: 118.2, CRCLBUSDT: 126.5,
  MSTRBUSDT: 342.8, AMDBUSDT: 168.3, SPCXBUSDT: 186.4, QQQBUSDT: 498.6, SOXLBUSDT: 28.6,
};

const BASE_PRICE: Record<string, number> = { ...FUTURES_PRICE, ...BSTOCK_PRICE };

export const basePrice = (symbol: string) => BASE_PRICE[symbol] ?? 100;

/** 有没有合约：决定资金费率查得到查不到 */
export const hasFutures = (symbol: string) => symbol in FUTURES_PRICE;

/** 按标的价位档取小数位，假订单/假持仓的价格都过一道 */
export const roundPrice = (symbol: string, v: number) => round(v, basePrice(symbol));

const IV_MS: Record<string, number> = {
  '1m': 60_000, '3m': 180_000, '5m': 300_000, '15m': 900_000, '30m': 1_800_000,
  '1h': 3_600_000, '2h': 7_200_000, '4h': 14_400_000, '6h': 21_600_000,
  '8h': 28_800_000, '12h': 43_200_000, '1d': 86_400_000, '3d': 259_200_000, '1w': 604_800_000,
};

const ivMs = (interval: string) => IV_MS[interval] ?? IV_MS['1h'];

/** 价位越低小数位越多，跟前端的显示口径对齐 */
const round = (v: number, ref: number) => {
  const d = ref >= 1000 ? 2 : ref >= 10 ? 3 : ref >= 1 ? 4 : 6;
  return Number(v.toFixed(d));
};

/** 字符串 → 32 位种子 */
const seedOf = (s: string) => {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
};

/** (种子, 第几根) → [0,1)。同一根每次算出来都一样，刷新页面 K 线形状不变 */
const noise = (seed: number, i: number) => {
  let x = (seed ^ Math.imul(i + 0x9e3779b9, 2654435761)) >>> 0;
  x ^= x >>> 16; x = Math.imul(x, 2246822507);
  x ^= x >>> 13; x = Math.imul(x, 3266489909);
  x ^= x >>> 16;
  return (x >>> 0) / 4294967296;
};

/** 往回最多给这么多根，再往前返空，图表翻到头就不再问了 */
const MAX_HISTORY = 3000;

/**
 * 币安风格裸数组：[开盘ms, 开, 高, 低, 收, 量, 收盘ms, 额, 笔数, 主买量, 主买额, 忽略]。
 * 收盘价从末根（钉死在现价）往回倒推，所以任何一根的值只由种子和它的绝对下标决定——
 * 翻历史（endTime）拿到的和首屏那段能严丝合缝接上。
 */
export function klines(symbol: string, interval: string, limit: number, endTime?: number): number[][] {
  const step = ivMs(interval);
  const base = basePrice(symbol);
  const seed = seedOf(`${symbol}|${interval}`);

  const lastIdx = Math.floor(Date.now() / step);              // 当前这根（未收盘）
  const endIdx = endTime ? Math.min(Math.floor(endTime / step), lastIdx) : lastIdx;
  const floorIdx = lastIdx - MAX_HISTORY;
  if (endIdx < floorIdx) return [];

  const n = Math.min(Math.max(Math.floor(limit) || 1, 1), 500);
  const startIdx = Math.max(endIdx - n + 1, floorIdx);

  // 多倒推一根：第一根的开盘价 = 它前一根的收盘价
  const from = startIdx - 1;
  const closes = new Array<number>(lastIdx - from + 1);
  closes[closes.length - 1] = base;
  for (let i = lastIdx; i > from; i--) {
    const drift = (noise(seed, i) - 0.5) * 0.022;             // 单根 ±1.1%
    const prev = closes[i - from] / (1 + drift);
    // 夹在现价上下三成内，免得几百根走下来飘到另一个量级
    closes[i - 1 - from] = Math.min(Math.max(prev, base * 0.72), base * 1.32);
  }

  const volBase = 20_000_000 / base;                          // 名义额量级压在两三千万 USDT
  const rows: number[][] = [];
  for (let i = startIdx; i <= endIdx; i++) {
    const o = closes[i - 1 - from], c = closes[i - from];
    const hi = round(Math.max(o, c) * (1 + noise(seed, i * 2 + 1) * 0.006), base);
    const lo = round(Math.min(o, c) * (1 - noise(seed, i * 3 + 2) * 0.006), base);
    const v = Number(((0.5 + noise(seed, i * 5 + 3)) * volBase).toFixed(3));
    const openTime = i * step;
    const quote = Number((v * c).toFixed(2));
    rows.push([
      openTime, round(o, base), hi, lo, round(c, base), v,
      openTime + step - 1, quote, Math.round(400 + noise(seed, i * 7 + 4) * 3000),
      Number((v * 0.52).toFixed(3)), Number((quote * 0.52).toFixed(2)), 0,
    ]);
  }
  return rows;
}

/**
 * 实时价：三层不同频率的正弦叠在现价上，秒级看着一直在动又跑不出 ±0.3%。
 * 纯时间函数不存状态，多个连接看到的是同一个价。
 */
export function livePrice(symbol: string): number {
  const base = basePrice(symbol);
  const p = (seedOf(symbol) % 1000) / 1000 * Math.PI * 2;     // 每个标的错开相位
  const t = Date.now() / 1000;
  const w = Math.sin(t / 41 + p) * 0.0022 + Math.sin(t / 7.3 + p * 2) * 0.0007 + Math.sin(t / 2.3 + p * 3) * 0.0003;
  return round(base * (1 + w), base);
}

/** 当前这根未收盘 K：OHLC 取生成器那根，收盘换成实时价，高低顺带撑开 */
export function liveBar(symbol: string, interval: string) {
  const row = klines(symbol, interval, 1)[0];
  const c = livePrice(symbol);
  return {
    i: interval, t: row[0], o: row[1],
    h: Math.max(row[2], c), l: Math.min(row[3], c), c,
    v: row[5], q: row[7], x: false,
  };
}
