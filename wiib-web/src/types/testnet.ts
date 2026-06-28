// Binance Testnet 模拟盘监测看板 —— 数据契约（字段与后端 dto 一一对应，零偏差）。
// 后端 BigDecimal/Long 经 JSON 序列化为 number。

/** 账户资金快照（空态时各字段可能为 null）。 */
export interface TnAccount {
  walletBalance: number | null;
  marginBalance: number | null;
  unrealizedProfit: number | null;
  availableBalance: number | null;
}

/** 当前持仓。 */
export interface TnPosition {
  symbol: string;
  side: 'LONG' | 'SHORT';
  positionAmt: number;
  entryPrice: number;
  markPrice: number;
  unrealizedProfit: number;
  liquidationPrice: number;
  leverage: number | null;
}

/** 当前挂单。 */
export interface TnOpenOrder {
  symbol: string;
  orderId: number;
  clientOrderId: string;
  side: string;
  type: string; // LIMIT / STOP_MARKET / TAKE_PROFIT_MARKET
  price: number;
  stopPrice: number;
  origQty: number;
  status: string;
  time: number;
}

/** 实时总览。 */
export interface TnOverview {
  account: TnAccount;
  positions: TnPosition[];
  openOrders: TnOpenOrder[];
}

/** 一笔真实成交（fill）。 */
export interface TnTrade {
  id: number;
  orderId: number;
  symbol: string;
  side: string;
  positionSide: string;
  price: number;
  qty: number;
  quoteQty: number;
  realizedPnl: number;
  commission: number;
  commissionAsset: string;
  maker: boolean;
  buyer: boolean;
  time: number;
}

/** 日交易网格一个格子。 */
export interface TnDailyCell {
  date: string; // yyyy-MM-dd（东八区）
  pnl: number; // 当天净盈亏（含手续费）
  tradeCount: number; // 当天平仓笔数
}

/** 权益曲线一个点。 */
export interface TnEquityPoint {
  time: number;
  cumPnl: number; // 累计已实现盈亏（从0起）
}

/** fill 对账统计 —— 策略生死悬案的答案。 */
export interface TnFillStats {
  placed: number; // 进场 LIMIT 单总数
  filled: number; // 成交数
  expired: number; // 超时/撤销数
  fillRate: number; // 成交率 = filled/placed
  avgFillSeconds: number; // 平均挂单→成交时长（秒）
  makerConfirmed: number; // 成交价=挂单价的笔数（零滑点验证）
}

/* ========== 手动交易（接口自检，仅 admin） ========== */

/** 手动下单请求。type=MARKET 忽略 price；type=LIMIT 时 price 必填。 */
export interface TnManualOrderReq {
  symbol: string;
  side: 'BUY' | 'SELL';
  type: 'MARKET' | 'LIMIT';
  quantity: number;
  price?: number;
  leverage?: number;
  timeInForce?: string;
  reduceOnly?: boolean;
}

/** 下单/平仓返回（OrderResponse 子集，结果框展示用）。 */
export interface TnOrderResult {
  orderId: number;
  symbol: string;
  side: string;
  type: string;
  status: string;
  avgPrice: number;
  origQty: number;
  price: number;
}

/** 撤单确认（SimpleAck）。 */
export interface TnAck {
  code: number;
  msg: string;
}
