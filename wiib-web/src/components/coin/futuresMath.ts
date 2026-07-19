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

/** 二分逼近：给定余额与杠杆，按现货费率能加仓的最大数量（step 对齐） */
export function calcMaxIncreaseQty(balance: number, price: number, leverage: number, step: number): number {
  if (balance <= 0 || price <= 0 || leverage <= 0 || step <= 0) return 0;
  const precision = getStepPrecision(step);
  let left = 0;
  let right = Math.floor((balance * leverage / price) / step);
  let best = 0;
  while (left <= right) {
    const mid = Math.floor((left + right) / 2);
    const qty = Number((mid * step).toFixed(precision));
    const value = roundHalfUp2(price * qty);
    const margin = roundCeil2(value / leverage);
    const commission = roundHalfUp2(value * COMMISSION_RATE);
    if (margin + commission <= balance + 1e-9) {
      best = qty;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }
  return best;
}

/** 合约开仓预估：以保证金数量（未乘杠杆）算仓位价值/保证金/手续费/合计 */
export function calcFuturesOpenEstimate(marginQty: number, price: number, leverage: number) {
  const orderQty = marginQty * leverage;
  const positionValue = roundHalfUp2(price * orderQty);
  const margin = roundCeil2(positionValue / leverage);
  const commission = roundHalfUp2(positionValue * FUTURES_COMMISSION_RATE);
  const fundingFee = roundHalfUp2(positionValue * 0.0001);
  const totalCost = roundHalfUp2(margin + commission);
  return { orderQty, positionValue, margin, commission, fundingFee, totalCost };
}

/** 二分逼近：预算内（余额×pct）能开的最大保证金数量 */
export function calcMaxAffordableMarginQty(balance: number, pct: number, price: number, leverage: number, step: number): number {
  const budget = balance * pct;
  if (budget <= 0 || price <= 0 || leverage <= 0 || step <= 0) return 0;

  const precision = getStepPrecision(step);
  let left = 0;
  let right = Math.floor((budget / price) / step);
  let best = 0;

  while (left <= right) {
    const mid = Math.floor((left + right) / 2);
    const qty = Number((mid * step).toFixed(precision));
    const estimate = calcFuturesOpenEstimate(qty, price, leverage);
    if (estimate.totalCost <= budget + 1e-9) {
      best = qty;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }

  return best;
}
