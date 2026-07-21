/**
 * 技术指标计算（MACD / RSI），供 K 线副图使用。
 *
 * 口径严格对齐后端 CryptoIndicatorCalculator.java，否则会出现
 * "图上画着金叉、AI 分析说没金叉" 的分裂。三个关键点：
 *   1. EMA 种子用前 period 期 SMA，不是拿第一根收盘价起步
 *   2. RSI 用 RMA(Wilder) 平滑，不是 EMA —— RMA 更"懒"，反应比同 period 的 EMA 慢
 *   3. MACD hist 乘 2（国内画法惯例）
 *
 * 注：后端用 BigDecimal(scale=8)、这里用 double，尾数会有 1e-8 级差异，
 * 画图和判方向都无感；两边"逐位相等"不是本模块的目标，算法口径一致才是。
 *
 * 所有函数返回**与输入等长**的数组，前面预热不足的位置填 null。
 * 这样调用方直接按下标 zip K 线即可，不用做 offset 算术
 * （后端那段 difSeries 对齐正是最容易错的地方）。
 */

/** 某点无值（预热期样本不够）时为 null */
type Series = (number | null)[];

/**
 * 简单移动平均。第 period-1 个下标开始有值。
 * 用滑动窗口增删而非每点重算：99 周期 × 500 根下省掉一个数量级的乘加，
 * double 在价格量级上的累积误差远小于一个 tick。
 */
export function maSeries(data: number[], period: number): Series {
  const out: Series = new Array(data.length).fill(null);
  if (data.length < period) return out;

  let sum = 0;
  for (let i = 0; i < period; i++) sum += data[i];
  out[period - 1] = sum / period;

  for (let i = period; i < data.length; i++) {
    sum += data[i] - data[i - period];
    out[i] = sum / period;
  }
  return out;
}

export interface BollResult {
  upper: Series;
  mid: Series;
  lower: Series;
}

/**
 * 布林带。中轨 = SMA(period)，上下轨 = 中轨 ± mult × 标准差。
 *
 * 标准差用**总体标准差**（除 period，不是 period-1）—— 后端 CryptoIndicatorCalculator.boll
 * 和主流交易软件都是这个口径，用样本标准差会让带宽系统性偏宽。
 */
export function bollSeries(closes: number[], period = 20, mult = 2): BollResult {
  const n = closes.length;
  const mid = maSeries(closes, period);
  const upper: Series = new Array(n).fill(null);
  const lower: Series = new Array(n).fill(null);

  for (let i = period - 1; i < n; i++) {
    const m = mid[i] as number;
    let sumSq = 0;
    for (let j = i - period + 1; j <= i; j++) {
      const d = closes[j] - m;
      sumSq += d * d;
    }
    const band = Math.sqrt(sumSq / period) * mult;
    upper[i] = m + band;
    lower[i] = m - band;
  }
  return { upper, mid, lower };
}

/**
 * 指数移动平均。k = 2/(period+1)，种子取前 period 期 SMA。
 * 第 period-1 个下标开始有值。
 */
export function emaSeries(data: number[], period: number): Series {
  const out: Series = new Array(data.length).fill(null);
  if (data.length < period) return out;

  const k = 2 / (period + 1);
  let sum = 0;
  for (let i = 0; i < period; i++) sum += data[i];
  let cur = sum / period;
  out[period - 1] = cur;

  for (let i = period; i < data.length; i++) {
    cur = data[i] * k + cur * (1 - k);
    out[i] = cur;
  }
  return out;
}

/** avgLoss 为 0 时统一返回 100（含横盘 gain=loss=0 的边界），对齐后端 calcRsiValue */
function rsiValue(avgGain: number, avgLoss: number): number {
  if (avgLoss === 0) return 100;
  return 100 - 100 / (1 + avgGain / avgLoss);
}

/**
 * RSI（Wilder 原始定义）。第 period 个下标开始有值（要 period+1 根才凑得出 period 个涨跌幅）。
 */
export function rsiSeries(closes: number[], period: number): Series {
  const out: Series = new Array(closes.length).fill(null);
  if (closes.length < period + 1) return out;

  // 种子：前 period 期涨幅均值 / 跌幅均值
  let avgGain = 0, avgLoss = 0;
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1];
    if (d > 0) avgGain += d; else avgLoss += -d;
  }
  avgGain /= period;
  avgLoss /= period;
  out[period] = rsiValue(avgGain, avgLoss);

  // 递推：涨只喂 gain、跌只喂 loss，另一边纯衰减（d===0 走 else 分支，喂 0）
  const p1 = period - 1;
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1];
    if (d > 0) {
      avgGain = (avgGain * p1 + d) / period;
      avgLoss = (avgLoss * p1) / period;
    } else {
      avgGain = (avgGain * p1) / period;
      avgLoss = (avgLoss * p1 + -d) / period;
    }
    out[i] = rsiValue(avgGain, avgLoss);
  }
  return out;
}

export interface MacdResult {
  dif: Series;   // EMA(fast) - EMA(slow)
  dea: Series;   // EMA(DIF, signal)
  hist: Series;  // (DIF - DEA) × 2
}

/**
 * MACD 三条线。dif 从下标 slow-1 起有值，dea/hist 从 slow+signal-2 起有值。
 */
export function macdSeries(closes: number[], fast = 12, slow = 26, signal = 9): MacdResult {
  const n = closes.length;
  const emaFast = emaSeries(closes, fast);
  const emaSlow = emaSeries(closes, slow);

  const dif: Series = new Array(n).fill(null);
  for (let i = 0; i < n; i++) {
    const f = emaFast[i], s = emaSlow[i];
    if (f !== null && s !== null) dif[i] = f - s;
  }

  // DEA 是对 DIF 再做一次 EMA。DIF 前段是 null，先切掉预热段算完再摊回原位置；
  // slow > fast，所以 slow-1 往后的 DIF 必然连续有值，切片里不会夹 null。
  const dea: Series = new Array(n).fill(null);
  const difCompact = dif.slice(slow - 1) as number[];
  const deaCompact = emaSeries(difCompact, signal);
  for (let i = 0; i < deaCompact.length; i++) dea[slow - 1 + i] = deaCompact[i];

  const hist: Series = new Array(n).fill(null);
  for (let i = 0; i < n; i++) {
    const d = dif[i], e = dea[i];
    if (d !== null && e !== null) hist[i] = (d - e) * 2;
  }

  return { dif, dea, hist };
}
