/**
 * 成交量分布（VP）：可见范围内每个价格档成交了多少，主动买 / 主动卖分开。
 * 用低一档周期的子 K 线近似：每根子 K 的量按它 [low, high] 与每档的重叠长度占比摊进去。
 */

/** 算 VP 用到的一根 K 线；CandleChart 的 Bar 天然满足 */
export interface VpBar { openMs: number; high: number; low: number; volume: number; buy: number; }

export interface VpRow { lo: number; hi: number; vol: number; buy: number; }

export interface VolumeProfile {
  rows: VpRow[];
  /** 量最大那档的下标 */
  poc: number;
  /** 价值区（70% 成交量）的下标范围，含两端 */
  vaLo: number;
  vaHi: number;
  maxVol: number;
}

/** 行数可选档 */
export const VP_ROWS = [24, 48, 70] as const;

/** 子周期候选，从细到粗 */
const SUB_IVS = [
  { iv: '1m', ms: 60_000 }, { iv: '5m', ms: 300_000 }, { iv: '15m', ms: 900_000 },
  { iv: '1h', ms: 3_600_000 }, { iv: '4h', ms: 14_400_000 },
] as const;

/** 可见范围内子 K 最多这么多根，再多换粗一档 */
const MAX_SUB_BARS = 3000;
/** 一块的根数 = 后端回源的大档 */
export const CHUNK_BARS = 500;
const VALUE_AREA = 0.7;

/** 选子周期：比主图细、可见范围内不超过 3000 根的最细一档；选不出返回 null，直接用主图 K 线 */
export function pickSubInterval(spanMs: number, mainMs: number): typeof SUB_IVS[number] | null {
  for (const s of SUB_IVS) {
    if (s.ms >= mainMs) return null;
    if (spanMs / s.ms <= MAX_SUB_BARS) return s;
  }
  return null;
}

/** 块号：块 k 管 [k*C, (k+1)*C)，C = 500 根子 K 的时长。请求按整块对齐，后端缓存大家共用 */
export const chunkOf = (ms: number, subMs: number) => Math.floor(ms / (CHUNK_BARS * subMs));
/** 块 k 最后 1ms：带这个 endTime 拉 500 根正好是这一块 */
export const chunkEndMs = (k: number, subMs: number) => (k + 1) * CHUNK_BARS * subMs - 1;

/** 把 bars 的量摊进 [lo, hi] 等分的 rowCount 档；没量或价格区间为空返回 null */
export function computeProfile(bars: VpBar[], lo: number, hi: number, rowCount: number): VolumeProfile | null {
  if (!(hi > lo)) return null;
  const step = (hi - lo) / rowCount;
  const vol = new Array<number>(rowCount).fill(0), buy = new Array<number>(rowCount).fill(0);
  for (const b of bars) {
    if (!(b.volume > 0)) continue;
    const buyShare = b.buy / b.volume;
    const span = b.high - b.low;
    const r0 = Math.max(0, Math.floor((b.low - lo) / step));
    const r1 = Math.min(rowCount - 1, Math.floor((b.high - lo) / step));
    for (let r = r0; r <= r1; r++) {
      // 一字线整根落一档；否则按与这档重叠的长度占比分
      const share = span > 0 ? (Math.min(b.high, lo + (r + 1) * step) - Math.max(b.low, lo + r * step)) / span : 1;
      vol[r] += b.volume * share;
      buy[r] += b.volume * share * buyShare;
    }
  }

  let poc = 0;
  for (let r = 1; r < rowCount; r++) if (vol[r] > vol[poc]) poc = r;
  const maxVol = vol[poc];
  if (!(maxVol > 0)) return null;

  // 价值区：从 POC 往两边扩，每次并入量大的那一侧，够 70% 停
  const total = vol.reduce((s, v) => s + v, 0);
  let vaLo = poc, vaHi = poc, acc = maxVol;
  while (acc < total * VALUE_AREA && (vaLo > 0 || vaHi < rowCount - 1)) {
    const down = vaLo > 0 ? vol[vaLo - 1] : -1;
    const up = vaHi < rowCount - 1 ? vol[vaHi + 1] : -1;
    if (up >= down) acc += vol[++vaHi]; else acc += vol[--vaLo];
  }

  const rows = vol.map((v, r) => ({ lo: lo + r * step, hi: lo + (r + 1) * step, vol: v, buy: buy[r] }));
  return { rows, poc, vaLo, vaHi, maxVol };
}
