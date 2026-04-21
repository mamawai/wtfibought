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
export interface CryptoKline {
  openTime: number;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  closeTime: number;
}

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

export interface CryptoAnalysisReport {
  summary: string;
  analysisBasis: string;
  direction: { ultraShort: string; shortTerm: string; mid: string; longTerm: string };
  keyLevels: { support: string[]; resistance: string[] };
  indicators: string;
  importantNews: { title: string; sentiment: string; summary: string }[];
  positionAdvice: { period: string; type: string; entry: string; stopLoss: string; takeProfit: string; riskReward: string }[];
  riskWarnings: string[];
  confidence: number;
  debateSummary?: { bullArgument: string; bearArgument: string; judgeReasoning: string };
  reasoning?: string;
}

export interface QuantSignalDecision {
  horizon: string;
  direction: string;
  confidence: number;
  maxLeverage: number;
  maxPositionPct: number;
  riskStatus: string;
}

export interface QuantLatestSignal {
  cycleId: string;
  symbol: string;
  forecastTime: string;
  overallDecision: string;
  riskStatus: string;
  signals: QuantSignalDecision[];
}

export interface QuantForecastCycle {
  id: number;
  cycleId: string;
  symbol: string;
  forecastTime: string;
  overallDecision: string;
  riskStatus: string;
  reportJson: string | null;
}

export interface QuantForecastVerificationItem {
  id: number;
  cycleId: string;
  symbol: string;
  horizon: string;
  predictedDirection: string;
  predictedConfidence: number;
  actualPriceAtForecast: string;
  actualPriceAfter: string;
  actualChangeBps: number;
  maxFavorableBps: number | null;
  maxAdverseBps: number | null;
  tp1HitFirst: boolean | null;
  predictionCorrect: boolean;
  tradeQuality: string;
  resultSummary: string | null;
  verifiedAt: string;
  createdAt: string;
}

export interface QuantVerificationCycleResult {
  cycleId: string;
  symbol: string;
  forecastTime: string | null;
  overallDecision: string | null;
  riskStatus: string | null;
  verifiedAt: string | null;
  items: QuantForecastVerificationItem[];
}

export interface QuantVerificationSummary {
  total: number;
  correct: number;
  accuracyRate: string;
  cycles: QuantVerificationCycleResult[];
}

export interface GroupedHeavyCycle {
  heavy: QuantVerificationCycleResult;
  lightCycles: QuantVerificationCycleResult[];
  adjustments?: QuantForecastAdjustment[];
}

/** 轻周期对父重周期 forecast 的修正明细（FLIP/SAME_DIR_BOOST/OPPO_WEAK_PENALTY/OPPO_STRONG_PENALTY） */
export interface QuantForecastAdjustment {
  id: number;
  lightCycleId: string;
  heavyCycleId: string;
  symbol: string;
  lightHorizon: string;
  heavyHorizon: string;
  adjustType: 'SAME_DIR_BOOST' | 'OPPO_WEAK_PENALTY' | 'OPPO_STRONG_PENALTY' | 'FLIP';
  lightDirection: string;
  lightConfidence: number;
  prevHeavyDirection: string;
  prevHeavyConfidence: number;
  newHeavyDirection: string;
  newHeavyConfidence: number;
  voteCountAfter: number;
  createdAt: string;
}

export interface GroupedVerificationSummary {
  total: number;
  correct: number;
  accuracyRate: string;
  heavyTotal: number;
  heavyCorrect: number;
  heavyAccuracyRate: string;
  groups: GroupedHeavyCycle[];
}


export interface AiAgentRuntimeConfig {
  apiKey: string;
  baseUrl: string;
  model: string;
}

export interface AiKeyConfig {
  id?: number;
  configName: string;
  apiKey: string;
  baseUrl: string;
  model?: string;
  enabled?: boolean;
}

export interface AiModelAssignment {
  id?: number;
  functionName: string;
  configId: number;
  model: string;
}

export interface TradingRuntimeConfig {
  lowVolTradingEnabled?: boolean;
  drawdownSentinelEnabled?: boolean;
  drawdownWindowMinutes?: number;
  drawdownPnlPctDropThresholdPpt?: number;
  drawdownProfitDrawdownThresholdPct?: number;
  drawdownProfitDrawdownMinBase?: number;
  drawdownCooldownMinutes?: number;
}

export interface QuantRuntimeConfig {
  debateJudgeEnabled?: boolean;
}

export interface LatestCryptoResult {
  status: 'ready' | 'pending';
  message?: string;
  forecastTime?: string;
  cycleId?: string;
  overallDecision?: string;
  riskStatus?: string;
  report?: CryptoAnalysisReport;
  lastForecastTime?: string;
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

// ========== AI Trading 类型 ==========
export interface AiTradingDashboard {
  balance: number;
  frozenBalance: number;
  unrealizedPnl: number;
  totalPnl: number;
  positionCount: number;
  todayTrades: number;
  positions: FuturesPosition[];
}

export interface AiTradingDecision {
  id: number;
  cycleNo: number;
  symbol: string;
  action: string;
  reasoning: string;
  marketContext: string;
  positionSnapshot: string;
  executionResult: string | null;
  balanceBefore: number;
  balanceAfter: number;
  createdAt: string;
}
