/**
 * trader 配置台与工作台共用的常量和北京时间计算。
 * 数字都跟后端同一份，改一边要改另一边：可选币=agent 的 binance.symbols + crypto-futures-symbols，
 * 币数上限=TraderService.MAX_SYMBOLS，心跳档位=TraderScheduler.WAKE_INTERVALS，
 * 警报基准=VolatilitySentinel.BASE_THRESHOLD_PCT，思考步数=TraderWakeupRunner.MAX_MODEL_CALLS
 */

export const TRADER_SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'DOGEUSDT', 'XRPUSDT', 'BNBUSDT', 'ZECUSDT', 'HYPEUSDT'];
export const MAX_SYMBOLS = 3;
/** 心跳档位 → 分钟 */
export const INTERVAL_MIN: Record<string, number> = { '15m': 15, '1h': 60, '4h': 240 };
export const INTERVALS = Object.keys(INTERVAL_MIN);
/** 波动警报每币基准阈值%（平台下限，只能经系数调高） */
export const ALERT_BASE: Record<string, number> = {
  BTCUSDT: 0.6, BNBUSDT: 0.6, ETHUSDT: 0.7, SOLUSDT: 0.9, DOGEUSDT: 0.9, XRPUSDT: 0.9, HYPEUSDT: 1.2, ZECUSDT: 1.6,
};
/** 波动警报只在这两档生效 */
export const ALERT_INTERVALS = ['1h', '4h'];
/** 一次醒来最多调几次模型 */
export const MAX_MODEL_CALLS = 12;
/** 每局注资 */
export const BASE_EQUITY = 10_000;
/** 每日复盘在北京时间 08:00 */
export const REVIEW_MIN = 8 * 60;

const DAY_MIN = 1440;
const BJ_OFFSET_MIN = 8 * 60;

/** 唤醒时段，分钟数（北京时间，两端都算，from > to 就是跨到次日） */
export interface WakeWin { from: number; to: number }

/** "21:00-08:30" → 分钟；null=全天 */
export function parseWindow(text: string | null): WakeWin | null {
  if (!text) return null;
  const [a, b] = text.split('-');
  return { from: hmToMin(a), to: hmToMin(b) };
}

export function windowText(w: WakeWin | null): string | null {
  return w ? `${minToHm(w.from)}-${minToHm(w.to)}` : null;
}

export function hmToMin(hm: string): number {
  const [h, m] = hm.split(':').map(Number);
  return h * 60 + m;
}

export function minToHm(min: number): string {
  const v = ((min % DAY_MIN) + DAY_MIN) % DAY_MIN;
  return `${String(Math.floor(v / 60)).padStart(2, '0')}:${String(v % 60).padStart(2, '0')}`;
}

/** 某时刻在北京时间是一天里的第几分钟 */
export function bjMinute(ms: number): number {
  return Math.floor(((ms / 60_000 + BJ_OFFSET_MIN) % DAY_MIN + DAY_MIN) % DAY_MIN);
}

export function inWindow(w: WakeWin | null, min: number): boolean {
  if (!w) return true;
  return w.from <= w.to ? min >= w.from && min <= w.to : min >= w.from || min <= w.to;
}

/** 一天里这一档会醒几次 */
export function beatsPerDay(interval: string, w: WakeWin | null): number {
  const step = INTERVAL_MIN[interval];
  let n = 0;
  for (let t = 0; t < DAY_MIN; t += step) if (inWindow(w, t)) n++;
  return n;
}
