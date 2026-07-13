export interface Stock {
  id: number;
  code: string;
  name: string;
  industry?: string;
  price: number;
  openPrice?: number;
  highPrice?: number;
  lowPrice?: number;
  prevClose?: number;
  change: number;
  changePct: number;
  marketCap?: number;
  peRatio?: number;
  companyDesc?: string;
  trendList?: number[];
}

export interface Quote {
  code: string;
  name: string;
  price: number;
  open: number;
  high: number;
  low: number;
  prevClose: number;
  timestamp: number;
}

export interface User {
  id: number;
  username: string;
  avatar?: string;
  balance: number;
  frozenBalance: number;
  positionMarketValue: number;
  pendingSettlement: number;
  marginLoanPrincipal: number;
  marginInterestAccrued: number;
  bankrupt: boolean;
  bankruptCount: number;
  bankruptResetDate?: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
}

export interface Position {
  id: number;
  stockId: number;
  stockCode: string;
  stockName: string;
  quantity: number;
  avgCost: number;
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

export interface OrderRequest {
  stockId: number;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  leverageMultiple?: number;
  useBuffId?: number;
}

export interface Order {
  orderId: number;
  stockCode: string;
  stockName: string;
  orderSide: string;
  orderType: string;
  status: string;
  quantity: number;
  limitPrice?: number;
  filledPrice?: number;
  filledAmount?: number;
  commission?: number;
  triggerPrice?: number;
  triggeredAt?: string;
  expireAt?: string;
  createdAt: string;
}

export interface DayTick {
  time: string;
  price: number;
}

export interface Kline {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
}

export interface Settlement {
  id: number;
  orderId: number;
  amount: number;
  settleTime?: string;
  createdAt: string;
  status: string;
}

// MyBatis-Plus分页结果
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface News {
  id: number;
  stockCode: string;
  title: string;
  content: string;
  newsType: string;
  publishTime: string;
}


export interface RankingItem {
  rank: number;
  userId: number;
  username: string;
  avatar?: string;
  totalAssets: number;
  profitPct: number;
  hardcoreProfit: number;
  buffProfit: number;
}

// ========== 期权相关类型 ==========
export interface OptionChainItem {
  contractId: number;
  stockId: number;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
}

export interface OptionQuote {
  contractId: number;
  stockCode: string;
  stockName: string;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
  premium: number;
  intrinsicValue: number;
  timeValue: number;
  spotPrice: number;
  sigma: number;
}

export interface OptionPosition {
  positionId: number;
  contractId: number;
  stockId: number;
  stockCode: string;
  stockName: string;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
  quantity: number;
  avgCost: number;
  currentPremium: number;
  marketValue: number;
  pnl: number;
  spotPrice: number;
}

export interface OptionOrder {
  orderId: number;
  stockName: string;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
  orderSide: 'BTO' | 'STC';
  quantity: number;
  filledPrice: number;
  filledAmount: number;
  commission: number;
  status: string;
}

export interface OptionOrderRequest {
  contractId: number;
  quantity: number;
}

export interface OptionOrderResult {
  orderId: number;
  status: string;
  filledPrice: number;
  filledAmount: number;
  commission: number;
}

// ========== Buff相关类型 ==========
export interface UserBuff {
  id: number;
  buffType: string;
  buffName: string;
  rarity: 'COMMON' | 'RARE' | 'EPIC' | 'LEGENDARY';
  extraData?: string;
  expireAt: string;
  isUsed: boolean;
}

export interface BuffStatus {
  canDraw: boolean;
  todayBuff: UserBuff | null;
}

// ========== Blackjack相关类型 ==========
export interface BlackjackStatus {
  chips: number;
  todayConverted: number;
  convertable: number;
  todayConvertLimit: number;
  totalHands: number;
  totalWon: number;
  totalLost: number;
  biggestWin: number;
  dailyPool: number;
  activeGame: GameState | null;
}

export interface GameState {
  phase: 'PLAYER_TURN' | 'DEALER_TURN' | 'SETTLED';
  playerHands: HandInfo[];
  activeHandIndex: number;
  dealerCards: string[];
  dealerScore: number | null;
  chips: number;
  insurance: number | null;
  actions: string[];
  results: HandResult[] | null;
}

export interface HandInfo {
  cards: string[];
  bet: number;
  score: number;
  isBust: boolean;
  isBlackjack: boolean;
  isDoubled: boolean;
}

export interface HandResult {
  handIndex: number;
  result: 'WIN' | 'LOSE' | 'PUSH' | 'BLACKJACK';
  payout: number;
  net: number;
}

export interface ConvertResult {
  chips: number;
  balance: number;
  todayConverted: number;
}

// ========== 矿工游戏类型 ==========
export interface MinesGameState {
  gameId: number;
  betAmount: number;
  revealed: number[];
  minePositions: number[] | null;
  result: 'SAFE' | 'MINE' | 'CASHED_OUT' | null;
  currentMultiplier: number;
  nextMultiplier: number | null;
  potentialPayout: number;
  payout: number | null;
  phase: 'PLAYING' | 'SETTLED';
  balance: number;
}

export interface MinesStatus {
  balance: number;
  activeGame: MinesGameState | null;
}

// ========== 视频扑克类型 ==========
export interface VideoPokerGameState {
  gameId: number;
  betAmount: number;
  cards: string[];
  heldPositions: number[];
  handRank: string;
  multiplier: number;
  payout: number;
  phase: 'DEALING' | 'SETTLED';
  balance: number;
}

export interface VideoPokerStatus {
  balance: number;
  activeGame: VideoPokerGameState | null;
}

// ========== 加密货币行情类型 ==========
export interface CryptoPrice {
  price: string;
  ts: string;
}

// ========== 加密货币交易类型 ==========
export interface CryptoOrderRequest {
  symbol: string;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  leverageMultiple?: number;
  useBuffId?: number;
}

export interface CryptoPosition {
  id: number;
  symbol: string;
  quantity: number;
  frozenQuantity: number;
  avgCost: number;
  totalDiscount: number;
}

// bStock 代币化美股：静态信息(bstock 表) + 实时行情
export interface BStock {
  id: number;
  symbol: string;        // NVDABUSDT
  ticker: string;        // NVDA
  name: string;          // 英伟达
  nameEn?: string;
  industry?: string;
  description?: string;
  ceo?: string;
  homepage?: string;
  marketCap?: number;
  peRatio?: number;
  dividendYield?: number;
  multiplier?: number;
  week52High?: number;
  week52Low?: number;
  // 实时
  price?: number;
  changePct?: number;
  high?: number;
  low?: number;
  volume?: number;
}

export interface CryptoOrder {
  orderId: number;
  symbol: string;
  orderSide: string;
  orderType: string;
  quantity: number;
  leverage: number;
  limitPrice?: number;
  filledPrice?: number;
  filledAmount?: number;
  commission?: number;
  triggerPrice?: number;
  triggeredAt?: string;
  status: string;
  expireAt?: string;
  createdAt: string;
}

// ========== 永续合约类型 ==========
export interface FuturesSLItem { price: number; quantity: number }
export interface FuturesTPItem { price: number; quantity: number }
export interface FuturesSLEntry { id: string; price: number; quantity: number }
export interface FuturesTPEntry { id: string; price: number; quantity: number }

export interface FuturesOpenRequest {
  symbol: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  leverage: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  stopLosses?: FuturesSLItem[];
  takeProfits?: FuturesTPItem[];
}

export interface FuturesCloseRequest {
  positionId: number;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
}

export interface FuturesAddMarginRequest {
  positionId: number;
  amount: number;
}

export interface FuturesIncreaseRequest {
  positionId: number;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
}

export interface FuturesReduceMarginRequest {
  positionId: number;
  amount: number;
}

export interface FuturesBracket {
  tier: number;
  notionalFloor: number;
  notionalCap: number;
  maxLeverage: number;
  mmr: number;
  maintAmount: number;
}

export interface FuturesStopLossRequest {
  positionId: number;
  stopLosses: FuturesSLItem[];
}

export interface FuturesTakeProfitRequest {
  positionId: number;
  takeProfits: FuturesTPItem[];
}

export interface FuturesPosition {
  id: number;
  userId: number;
  symbol: string;
  side: 'LONG' | 'SHORT';
  leverage: number;
  quantity: number;
  entryPrice: number;
  margin: number;
  fundingFeeTotal: number;
  stopLosses?: FuturesSLEntry[];
  takeProfits?: FuturesTPEntry[];
  status: string;
  closedPrice?: number;
  closedPnl?: number;
  createdAt: string;
  updatedAt: string;
  currentPrice: number;
  markPrice: number;
  positionValue: number;
  unrealizedPnl: number;
  unrealizedPnlPct: number;
  effectiveMargin: number;
  maintenanceMargin: number;
  liquidationPrice: number;
  fundingFeePerCycle: number;
}

export interface FuturesOrder {
  orderId: number;
  userId: number;
  positionId?: number;
  symbol: string;
  orderSide: string;
  orderType: string;
  quantity: number;
  leverage: number;
  limitPrice?: number;
  frozenAmount?: number;
  filledPrice?: number;
  filledAmount?: number;
  marginAmount?: number;
  commission?: number;
  realizedPnl?: number;
  status: string;
  expireAt?: string;
  createdAt: string;
  isAiTrader?: boolean;
}

// ========== BTC 5min 涨跌预测 ==========

export interface PredictionRound {
  id?: number;
  windowStart: number;
  startPrice?: string;
  endPrice?: string;
  outcome?: string;
  upPrice?: string;
  downPrice?: string;
  status: string;
  remainingSeconds: number;
  serverTimeMs?: number;
  officialNowTimeMs?: number;
  officialStartTimeMs?: number;
  officialEndTimeMs?: number;
}

export interface PredictionBet {
  id: number;
  roundId: number;
  windowStart?: number;
  side: string;
  contracts: number;
  cost: number;
  avgPrice: number;
  payout?: number;
  currentValue?: number;
  status: string;
  createdAt: string;
}

export interface PredictionPnl {
  totalBets: number;
  activeBets: number;
  wonBets: number;
  lostBets: number;
  totalCost: number;
  realizedPnl: number;
  activeCost: number;
  activeValue: number;
  totalPnl: number;
  winRate: number;
}

export interface PredictionBuyRequest {
  side: 'UP' | 'DOWN';
  amount: number;
}

export interface PredictionBetLive {
  username: string;
  avatar?: string;
  side?: string;
  outcome?: string;
  price?: number;
  size?: number;
  amount?: number;
  source?: string;
  ts: number;
}

// ========== 资产快照类型 ==========
export interface AssetSnapshot {
  date: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
  stockProfit: number;
  cryptoProfit: number;
  futuresProfit: number;
  optionProfit: number;
  predictionProfit: number;
  gameProfit: number;
  dailyProfit: number;
  dailyProfitPct: number;
  dailyStockProfit: number;
  dailyCryptoProfit: number;
  dailyFuturesProfit: number;
  dailyOptionProfit: number;
  dailyPredictionProfit: number;
  dailyGameProfit: number;
}

export interface CategoryAverages {
  stockProfit: number;
  cryptoProfit: number;
  futuresProfit: number;
  optionProfit: number;
  predictionProfit: number;
  gameProfit: number;
}

// ========== AI Agent 类型 ==========
export interface BehaviorAnalysisReport {
  overview: {
    totalAssets: number;
    totalProfitPct: number;
    distribution: { category: string; value: number }[];
    trend: { date: string; totalAssets: number }[];
  };
  tradeBehavior: {
    stock: { positionCount: number; orderCount: number; totalBuyAmount: number; preference: string };
    crypto: { positionCount: number; totalBuyAmount: number; totalSellAmount: number; leverageUsage: string };
    futures: { realizedPnl: number; orderCount: number; direction: string; avgLeverage: number; stopLossRate: number; liquidationCount: number };
    option: { totalBtoAmount: number; totalStcAmount: number };
    prediction: { frequency: number; netProfit: number; winRate: number; directionPreference: string };
  };
  gameBehavior: {
    blackjack: { totalHands: number; totalWon: number; totalLost: number; biggestWin: number; todayConverted: number };
    mines: { frequency: number; netProfit: number };
    videoPoker: { frequency: number; netProfit: number };
  };
  riskProfile: {
    riskLevel: string;
    bankruptCount: number;
    maxDrawdown: string;
    bankruptAt: string;
  };
  suggestions: string[];
}

export interface AiKeyConfig {
  id?: number;
  configName: string;
  apiKey: string;
  baseUrl: string;
  model?: string;
  /** 思考档位 none/low/medium/high；空=不传走模型默认 */
  reasoningEffort?: string;
  /** 上游协议 openai=/v1/chat/completions，responses=/v1/responses；空=openai */
  apiProtocol?: string;
  enabled?: boolean;
}

// 功能位→LLM配置的指针：更换LLM只改configId，模型名归属AiKeyConfig
export interface AiModelAssignment {
  id?: number;
  functionName: string;
  configId: number;
}


export interface ForceOrder {
  id: number;
  symbol: string;
  side: string;
  price: number;
  avgPrice: number;
  quantity: number;
  amount: number;
  status: string;
  tradeTime: string;
  createdAt: string;
}

// ========== Graph 观测 ==========
export interface GraphNodeMetric {
  node: string;
  successCount: number;
  errorCount: number;
  meanMs: number;
  maxMs: number;
}

// feed WS 流健康（Admin 面板：状态展示 + 手动重试）
export interface FeedStreamHealth {
  name: string;
  status: 'CONNECTED' | 'CONNECTING' | 'RECONNECTING' | 'DISCONNECTED';
  lastMessageAt: number;   // epoch millis，前端本地算"距上次数据 Xs"
  reconnectAttempt: number;
}

// ========== P7 研判工作台 ==========
/** 快照时间线曲线点（/ai/quant/snapshots/series） */
export interface QuantSnapshotSeriesPoint {
  closeTime: number;
  lastPrice: number;
  h6SigmaBps: number | null;
  h12SigmaBps: number | null;
  h24SigmaBps: number | null;
  volState: string | null;
  fragilityScore: number | null;
  fragilityLevel: string | null;
  /** H6 已验证实际波幅 |return| bps；到期验证才有，曲线尾部 6h 天然缺 */
  realizedAbsBps: number | null;
}

/** 深研判（quant_deep_analysis 实体透传） */
export interface QuantDeepAnalysisView {
  id: number;
  symbol: string;
  closeTime: number;
  triggerSource: string;
  snapshotId: number | null;
  narrative: string;
  /** {bullPct, rangePct, bearPct} 和=100 */
  scenariosJson: string;
  noDirection: boolean;
  invalidation: string;
  bullArgument: string;
  bearArgument: string;
  judgeReasoning: string;
  newsContext: string | null;
  createdAt: string;
}

/** 记分卡（/ai/quant/scorecard） */
export interface ScorecardHorizon {
  horizon: string;
  samples: number;
  avgQlike: number;
  avgBaselineQlike: number;
  /** (baseline-forecast)/baseline，>0=跑赢基准 */
  qlikeImprovement: number;
  qlikeWinRate: number;
  volStateHitRate: number;
}
export interface Scorecard {
  symbol: string;
  windowDays: number;
  runningDays: number;
  totalSamples: number;
  horizons: ScorecardHorizon[];
  note: string | null;
}

/** 工作台 SSE 事件（与 ChatWorkbenchController 协议一一对应） */
export type WorkbenchEvent =
  | { type: 'session'; sessionId: string }
  | { type: 'agent_start'; node: string; agent: string }
  | { type: 'token'; text: string }
  | { type: 'hitl_request'; sessionId: string; symbol: string; reason: string; resumeMessage: string }
  | { type: 'done'; sessionId: string; answer: string }
  | { type: 'error'; message: string };

// ========== 策略账户监控 ==========
/** 已平仓历史（静态字段快照，无实时价字段） */
export type StrategyClosedPosition = Pick<FuturesPosition,
  'id' | 'userId' | 'symbol' | 'side' | 'leverage' | 'quantity' | 'entryPrice'
  | 'margin' | 'fundingFeeTotal' | 'status' | 'closedPrice' | 'closedPnl'
  | 'createdAt' | 'updatedAt'> & { memo?: string };

export interface StrategyAccountView {
  strategyId: string;
  accountUserId: number | null;
  /** false=sim 未启动或账户异常，整栏渲染空态 */
  available: boolean;
  balance: number | null;
  unrealizedPnl: number | null;
  equity: number | null;
  cumPnl: number | null;
  tradeCount: number;
  winCount: number;
  winRate: number;
  positions: FuturesPosition[];
  closedPositions: StrategyClosedPosition[];
}

/** 最新数值快照（quant_snapshot 实体透传，/ai/quant/snapshots/latest） */
export interface QuantSnapshotView {
  id: number;
  symbol: string;
  closeTime: number;
  lastPrice: number;
  /** {H6:{sigmaBps,percentile,tier,volState,lowCut,highCut,regime,regimeConfidence},H12,H24} */
  volLegsJson: string;
  regime: string | null;
  regimeConfidence: number | null;
  fragilityScore: number;
  fragilityLevel: string;
  fragilityDirection: string;
  fragilityHeadline: string;
  signalPanelJson: string;
  qualityFlagsJson: string;
  createdAt: string;
}
