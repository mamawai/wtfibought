/**
 * 手动复盘的撮合内核：纯函数、UI 之外零依赖，方便单独验算。
 *
 * 口径对齐回测引擎（BacktestTradingTools）：
 * - 手续费 taker 万5 双边——复盘里的开/加/平都是"看着收盘价点按钮"，全是市价语义；
 * - 双向持仓（多/空各一个槽位，可同时持有）：同向再开=加仓（均价按数量加权、保证金累加）；
 *   平仓按比例（25%~100%），部分平按比例结算盈亏/退回保证金，每次平记一笔成交；
 * - 全仓口径：权益 = 现金 + Σ(保证金 + 未实现盈亏)；权益 ≤ 0 即两侧一起爆仓（不做维持保证金梯度），
 *   穿仓部分平台兜底，最多亏光本金。
 *   全仓下现金允许暂时为负（一侧兑现亏损、另一侧还挂着浮盈），护栏在权益不在现金。
 */

export const TAKER_FEE = 0.0005;   // 对齐引擎 backtest.takerFeeRate 默认

export type Side = 'LONG' | 'SHORT';

export interface ReplayPosition {
  side: Side;
  qty: number;
  /** 加权均价 */
  entryPrice: number;
  /** 累计从现金划出的保证金 */
  margin: number;
  /** 有效杠杆 = Σ(各笔保证金×各自杠杆)/Σ保证金；杠杆是每次开/加时现选的，加仓换档就成小数 */
  leverage: number;
  /** 累计已付开仓费；部分平按比例摊进那笔成交的 pnl */
  openFeePaid: number;
  /** 首次开仓的回放数组下标/时间（成交记录的"开仓时间"沿用它） */
  openIndex: number;
  openTime: number;
}

/** 一次实际成交（开/加/平/减/爆/结算），图表标记的唯一来源 */
export interface ReplayFill {
  kind: 'OPEN' | 'ADD' | 'CLOSE' | 'REDUCE' | 'LIQUIDATION' | 'END';
  side: Side;
  qty: number;
  price: number;
  index: number;
  time: number;
  /** 平/减类成交的净盈亏（已扣费） */
  pnl?: number;
}

/** 一笔已平成交（部分平也是一笔）；右栏清单与结算统计的口径 */
export interface ReplayTrade {
  side: Side;
  qty: number;
  leverage: number;
  entryPrice: number;
  exitPrice: number;
  /** 净盈亏（已扣本笔应摊的开仓费 + 平仓费，同引擎 trade.pnl 口径）；爆仓那笔扣掉了穿仓兜底的部分 */
  pnl: number;
  /** 本笔摊到的开仓费 + 平仓费 */
  fee: number;
  /** 平掉的是不是仓位的一部分（还剩仓） */
  partial: boolean;
  openIndex: number;
  closeIndex: number;
  openTime: number;
  closeTime: number;
  reason: 'MANUAL' | 'LIQUIDATION' | 'END';
}

export interface ReplayState {
  /** 可用现金（不含已占用保证金） */
  cash: number;
  positions: Record<Side, ReplayPosition | null>;
  trades: ReplayTrade[];
  fills: ReplayFill[];
  liquidated: boolean;
}

export function initialState(balance: number): ReplayState {
  return { cash: balance, positions: { LONG: null, SHORT: null }, trades: [], fills: [], liquidated: false };
}

export function unrealized(pos: ReplayPosition, price: number): number {
  const dir = pos.side === 'LONG' ? 1 : -1;
  return (price - pos.entryPrice) * pos.qty * dir;
}

/** 两侧非空仓位 */
export function openPositions(s: ReplayState): ReplayPosition[] {
  const out: ReplayPosition[] = [];
  if (s.positions.LONG) out.push(s.positions.LONG);
  if (s.positions.SHORT) out.push(s.positions.SHORT);
  return out;
}

/** 按最新价盯市的总权益（全仓口径） */
export function equity(s: ReplayState, price: number): number {
  return openPositions(s).reduce((acc, p) => acc + p.margin + unrealized(p, price), s.cash);
}

/**
 * 开仓/加仓：保证金 = 现金 × 比例 ÷ (1 + 杠杆×费率)。
 * 分母那截是给开仓费留位——100% 仓位时"保证金+手续费"恰好花光现金，不会负余额。
 * 同向已有仓即加仓：均价按数量加权，保证金/开仓费累加，杠杆按保证金加权成有效杠杆，首开时间不变。
 */
export function open(s: ReplayState, side: Side, pct: number, leverage: number,
                     price: number, index: number, time: number): ReplayState {
  if (s.liquidated || price <= 0) return s;
  const margin = s.cash * pct / (1 + leverage * TAKER_FEE);
  const notional = margin * leverage;
  const fee = notional * TAKER_FEE;
  const qty = notional / price;
  if (qty <= 0 || margin <= 0) return s;
  const cur = s.positions[side];
  const pos: ReplayPosition = cur
    ? {
      ...cur,
      qty: cur.qty + qty,
      entryPrice: (cur.entryPrice * cur.qty + price * qty) / (cur.qty + qty),
      margin: cur.margin + margin,
      leverage: (cur.margin * cur.leverage + margin * leverage) / (cur.margin + margin),
      openFeePaid: cur.openFeePaid + fee,
    }
    : { side, qty, entryPrice: price, margin, leverage, openFeePaid: fee, openIndex: index, openTime: time };
  return {
    ...s,
    // 保证金+开仓费 恰好 = 现金×比例（分母那截的用意），用等价式写省得 100% 时剩个 -0.000…
    cash: s.cash * (1 - pct),
    positions: { ...s.positions, [side]: pos },
    fills: [...s.fills, { kind: cur ? 'ADD' : 'OPEN', side, qty, price, index, time }],
  };
}

/**
 * 平仓/减仓（手动/爆仓/收盘清算共用）：pct 为平掉的数量比例，≥1 全平。
 * 部分平按比例：退回等比保证金、兑现等比盈亏、摊等比开仓费；剩余仓位均价不变。
 */
export function close(s: ReplayState, side: Side, pct: number, price: number, index: number, time: number,
                      reason: ReplayTrade['reason'] = 'MANUAL'): ReplayState {
  const pos = s.positions[side];
  if (!pos || pct <= 0) return s;
  const full = pct >= 1;
  const closedQty = full ? pos.qty : pos.qty * pct;
  const ratio = closedQty / pos.qty;
  const dir = pos.side === 'LONG' ? 1 : -1;
  const raw = (price - pos.entryPrice) * closedQty * dir;
  const openFee = pos.openFeePaid * ratio;
  const closeFee = closedQty * price * TAKER_FEE;
  const marginBack = pos.margin * ratio;
  const pnl = raw - openFee - closeFee;
  const trade: ReplayTrade = {
    side: pos.side, qty: closedQty, leverage: pos.leverage,
    entryPrice: pos.entryPrice, exitPrice: price,
    pnl, fee: openFee + closeFee, partial: !full,
    openIndex: pos.openIndex, closeIndex: index, openTime: pos.openTime, closeTime: time,
    reason,
  };
  const rest: ReplayPosition | null = full ? null : {
    ...pos, qty: pos.qty - closedQty, margin: pos.margin - marginBack, openFeePaid: pos.openFeePaid - openFee,
  };
  const kind: ReplayFill['kind'] = reason === 'MANUAL' ? (full ? 'CLOSE' : 'REDUCE') : reason;
  return {
    ...s,
    cash: s.cash + marginBack + raw - closeFee,
    positions: { ...s.positions, [side]: rest },
    trades: [...s.trades, trade],
    fills: [...s.fills, { kind, side, qty: closedQty, price, index, time, pnl }],
  };
}

/**
 * 两侧全平（爆仓/结算）。
 * 爆仓时权益已 ≤0，平完现金必然 ≤0：穿仓那截算平台兜底，现金落地为 0，
 * 差额加回这次强平里亏得最多的那笔，各笔成交 pnl 之和才跟最终权益对得上（整局正好亏光本金）。
 */
function closeAll(s: ReplayState, price: number, index: number, time: number,
                  reason: 'LIQUIDATION' | 'END'): ReplayState {
  let st = s;
  for (const side of ['LONG', 'SHORT'] as const) st = close(st, side, 1, price, index, time, reason);
  if (reason === 'END') return st;
  // 这次强平新增的成交从 s.trades.length 起（多空各至多一笔），成交与 fill 一一对应着追加
  let k = s.trades.length;
  for (let i = k + 1; i < st.trades.length; i++) if (st.trades[i].pnl < st.trades[k].pnl) k = i;
  const pnl = st.trades[k].pnl - st.cash;   // 现金 ≤0，减它就是把穿仓那截加回
  const f = s.fills.length + (k - s.trades.length);
  const trades = st.trades.slice();
  const fills = st.fills.slice();
  trades[k] = { ...trades[k], pnl };
  fills[f] = { ...fills[f], pnl };
  return { ...st, cash: 0, trades, fills, liquidated: true };
}

/** 每根新 bar 收盘调用：盯市 + 爆仓判定。爆仓按当根收盘价强平两侧、本局结束 */
export function step(s: ReplayState, price: number, index: number, time: number): ReplayState {
  if (openPositions(s).length && equity(s, price) <= 0) {
    return closeAll(s, price, index, time, 'LIQUIDATION');
  }
  return s;
}

/** 走完全部 bar 仍有持仓：按最后收盘价清算（同引擎 FORCE_CLOSE 语义） */
export function endSession(s: ReplayState, price: number, index: number, time: number): ReplayState {
  return openPositions(s).length ? closeAll(s, price, index, time, 'END') : s;
}

export interface ReplayStats {
  totalTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  netProfit: number;
  totalFees: number;
  returnPct: number;
  maxDrawdownPct: number;
  finalEquity: number;
}

/**
 * 结算统计，两侧都平完（endSession/爆仓之后）才调。
 * 最终权益就是结算后的现金，净盈亏 = 最终权益 − 本金，也等于各笔成交 pnl 之和；
 * equitySeries = 每根已揭示 bar 收盘后的权益序列（末点是结算后的），只用来算回撤。
 */
export function stats(s: ReplayState, initialBalance: number, equitySeries: number[]): ReplayStats {
  const wins = s.trades.filter(t => t.pnl > 0).length;
  const losses = s.trades.filter(t => t.pnl < 0).length;
  let peak = initialBalance, maxDd = 0;
  for (const eq of equitySeries) {
    if (eq > peak) peak = eq;
    if (peak > 0) maxDd = Math.max(maxDd, (peak - eq) / peak);
  }
  const finalEquity = s.cash;
  const netProfit = finalEquity - initialBalance;
  return {
    totalTrades: s.trades.length,
    wins, losses,
    winRate: s.trades.length ? wins / s.trades.length : 0,
    netProfit,
    totalFees: s.trades.reduce((a, t) => a + t.fee, 0),
    returnPct: initialBalance > 0 ? netProfit / initialBalance : 0,
    maxDrawdownPct: maxDd,
    finalEquity,
  };
}
