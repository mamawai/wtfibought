import type { FuturesBracket } from '../../types';

// ===== 交易常量 =====
export const COMMISSION_RATE = 0.001;           // 现货手续费率
export const FUTURES_COMMISSION_RATE = 0.0004;  // 合约手续费率
export const POSITION_PCTS = [0.25, 0.5, 0.75, 1];
export const FUTURES_LEVERAGE_OPTIONS = [1, 10, 25, 50, 75, 100, 150];
export const SPOT_LEVERAGE_OPTIONS = Array.from({ length: 10 }, (_, i) => i + 1);

/** 止损/止盈编辑行（输入框原始字符串，提交时再 parse） */
export interface SLTPRow { price: string; quantity: string }

export function roundHalfUp2(n: number): number {
  return Math.round((n + Number.EPSILON) * 100) / 100;
}

export function roundCeil2(n: number): number {
  return Math.ceil((n - 1e-9) * 100) / 100;
}

export function getStepPrecision(step: number): number {
  const s = String(step);
  const i = s.indexOf('.');
  return i >= 0 ? s.length - i - 1 : 0;
}

export function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(2)}%`;
}

/** 数量向下对齐步长（含浮点误差容忍）：0.11×50=5.500000000000001 → 5.5 */
export function floorToStep(qty: number, step: number): number {
  if (!(step > 0)) return qty;
  return Number((Math.floor(qty / step + 1e-9) * step).toFixed(getStepPrecision(step)));
}

/** 持仓百分比→数量字符串：100% 精确全量（避免尾差平不干净），其余档按 step 对齐 */
export function qtyByPct(posQty: number, pct: number, step: number): string {
  if (pct >= 100) return String(posQty);
  if (pct <= 0) return '';
  const q = Math.round((posQty * pct / 100) / step) * step;
  return q > 0 ? q.toFixed(getStepPrecision(step)).replace(/0+$/, '').replace(/\.$/, '') : '';
}

export function findFuturesBracket(brackets: FuturesBracket[] | undefined, notional: number): FuturesBracket | null {
  if (!brackets || brackets.length === 0) return null;
  return brackets.find(b => notional >= b.notionalFloor && notional < b.notionalCap) ?? brackets[brackets.length - 1];
}

function calcLiqPriceByBracket(side: 'LONG' | 'SHORT', notional: number, margin: number, quantity: number, bracket: FuturesBracket): number {
  if (side === 'LONG') return (notional - margin - bracket.maintAmount) / (quantity * (1 - bracket.mmr));
  return (notional + margin + bracket.maintAmount) / (quantity * (1 + bracket.mmr));
}

export function estimateFuturesLiqPrice(brackets: FuturesBracket[] | undefined, side: 'LONG' | 'SHORT', entryPrice: number, margin: number, quantity: number): { price: number; bracket: FuturesBracket } | null {
  if (!brackets || brackets.length === 0) return null;
  const notional = entryPrice * quantity;
  const entryBracket = findFuturesBracket(brackets, notional)!;
  const entryBracketPrice = calcLiqPriceByBracket(side, notional, margin, quantity, entryBracket);
  for (let i = 0; i < brackets.length; i++) {
    const bracket = brackets[i];
    const price = calcLiqPriceByBracket(side, notional, margin, quantity, bracket);
    const liqNotional = price * quantity;
    const last = i === brackets.length - 1;
    if (liqNotional >= bracket.notionalFloor && (last || liqNotional < bracket.notionalCap)) {
      return { price, bracket };
    }
  }
  // 全档位无落点（保证金极大致负强平价）：取开仓档位算出值，调用方需判断负值显示 N/A
  return { price: entryBracketPrice, bracket: entryBracket };
}

/** 合约开仓预估：orderQty 是实际下单币量（已乘杠杆、按步长取整），算仓位价值/保证金/手续费/合计，取整口径同后端 */
export function calcFuturesOpenEstimate(orderQty: number, price: number, leverage: number) {
  const positionValue = roundHalfUp2(price * orderQty);
  const margin = roundCeil2(positionValue / leverage);
  const commission = roundHalfUp2(positionValue * FUTURES_COMMISSION_RATE);
  const fundingFee = roundHalfUp2(positionValue * 0.0001);
  const totalCost = roundHalfUp2(margin + commission);
  return { positionValue, margin, commission, fundingFee, totalCost };
}

/**
 * 步长网格上二分，找"成本 ≤ 预算"的最大数量。cost 是按后端取整口径算的真成本（随数量单调不减）；
 * unitCost 是每个币的连续近似成本，只用来定上界：取整最多让真成本比它少约 0.01，预算多放 0.02 兜住
 */
function maxQtyWithinBudget(budget: number, unitCost: number, step: number, cost: (qty: number) => number): number {
  if (!(budget > 0) || !(unitCost > 0) || !(step > 0)) return 0;

  const precision = getStepPrecision(step);
  let left = 0;
  let right = Math.floor((budget + 0.02) / unitCost / step) + 1;
  let best = 0;

  while (left <= right) {
    const mid = Math.floor((left + right) / 2);
    const qty = Number((mid * step).toFixed(precision));
    if (cost(qty) <= budget + 1e-9) {
      best = qty;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }

  return best;
}

/** 合约预算内能开的最大下单量（按步长对齐）：保证金+手续费按后端取整口径 ≤ 预算 */
export function calcMaxAffordableOrderQty(budget: number, price: number, leverage: number, step: number): number {
  return maxQtyWithinBudget(budget, price * (1 / leverage + FUTURES_COMMISSION_RATE), step,
    qty => calcFuturesOpenEstimate(qty, price, leverage).totalCost);
}

/**
 * 下单量 → USDT 单位的保证金输入（两位小数）。面板会 金额÷价×杠杆 再按步长向下取整，这里保证取整后不超过这个单量：
 * 向上取到分正好落回这个单量就用它；金额精度不够分辨一个步长时会多出一截，退回向下取到分
 */
export function orderQtyToMarginUsdt(orderQty: number, price: number, leverage: number, step: number): number {
  if (!(orderQty > 0) || !(price > 0)) return 0;
  const exact = orderQty * price / leverage;
  const up = roundCeil2(exact);
  if (floorToStep((up / price) * leverage, step) <= orderQty) return up;
  return Math.floor(exact * 100 + 1e-9) / 100;
}

/**
 * 下单量 → 币单位的保证金输入（保证金币数，常比步长细：BTC 150x 一个步长只要 0.0000067 个币）。
 * 面板会 输入×杠杆 再按步长向下取整：从步长的小数位起逐位加，取第一个向上取整后正好落回这个单量的
 */
export function orderQtyToMarginCoin(orderQty: number, leverage: number, step: number): number {
  if (!(orderQty > 0)) return 0;
  const base = getStepPrecision(step);
  for (let p = base; ; p++) {
    const scale = 10 ** p;
    const coin = Math.ceil(orderQty / leverage * scale - 1e-9) / scale;
    // 杠杆最多三位数，比步长多 3 位小数一定落得回去
    if (floorToStep(coin * leverage, step) === orderQty || p >= base + 3) return coin;
  }
}

/** 现货/bStock 买入的现金占用（限价买冻结也是这个数，杠杆=1），取整同后端：成交额四舍五入到分，杠杆单保证金向上取到分，手续费四舍五入到分 */
export function calcSpotBuyCashNeed(qty: number, price: number, leverage: number): number {
  const amount = roundHalfUp2(price * qty);
  const commission = roundHalfUp2(amount * COMMISSION_RATE);
  return (leverage > 1 ? roundCeil2(amount / leverage) : amount) + commission;
}

/** 现货下单预估：orderQty 是实际下单币量（已乘杠杆、按步长取整），取整同后端 */
export function calcSpotOrderEstimate(orderQty: number, price: number, leverage: number) {
  const amount = roundHalfUp2(price * orderQty);
  const commission = roundHalfUp2(amount * COMMISSION_RATE);
  // 杠杆单自己掏的那份；不加杠杆就是成交额
  const margin = leverage > 1 ? roundCeil2(amount / leverage) : amount;
  return { amount, margin, commission };
}

/** 预算内能买的最大总数量（已含杠杆，按步长对齐），现金占用按后端取整口径算 */
export function calcMaxSpotBuyQty(budget: number, price: number, leverage: number, step: number): number {
  return maxQtyWithinBudget(budget, price * (1 / leverage + COMMISSION_RATE), step,
    qty => calcSpotBuyCashNeed(qty, price, leverage));
}
