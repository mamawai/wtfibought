export interface User {
  id: number;
  username: string;
  avatar?: string;
  balance: number;
  /** 游戏钱包：Mines/扑克/21点兑现/预测市场专用，和交易 balance 分离 */
  gameBalance: number;
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

// MyBatis-Plus分页结果
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
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
  /** 余额钱包（含冻结）与游戏钱包只是总资产的现金部分，相加 ≠ totalAssets */
  balanceWallet: number;
  gameWallet: number;
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
/** 保证金模式：CROSS=全仓（整个账户净值兜底），ISOLATED=逐仓（只赔本仓保证金） */
export type FuturesMarginMode = 'CROSS' | 'ISOLATED';

export interface FuturesSLItem { price: number; quantity: number }
export interface FuturesTPItem { price: number; quantity: number }
export interface FuturesSLEntry { id: string; price: number; quantity: number }
export interface FuturesTPEntry { id: string; price: number; quantity: number }

export interface FuturesOpenRequest {
  symbol: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  leverage: number;
  marginMode: FuturesMarginMode;
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

export interface FuturesAdjustLeverageRequest {
  positionId: number;
  leverage: number;
}

/** 全仓账户概览（GET /futures/cross-account） */
export interface FuturesCrossAccount {
  balance: number;
  unrealizedPnl: number;
  equity: number;
  available: number;
  usedMargin: number;
  pendingReserved: number;
  maintenanceMargin: number;
  positionCount: number;
}

/** 划转预检（GET /wallet/transfer/preview）：restricted=有全仓敞口，转出会动净值 */
export interface WalletTransferPreviewPosition {
  positionId: number;
  symbol: string;
  side: string;
  estLiqPrice: number;
}
export interface WalletTransferPreview {
  restricted: boolean;
  allowed: boolean;
  maxTransferable?: number;
  equityAfter?: number;
  maintenanceMargin?: number;
  positions?: WalletTransferPreviewPosition[];
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
  marginMode: FuturesMarginMode;
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
  /** 持仓期已实现盈亏：开/加仓手续费+已平部分净盈亏（不含资金费） */
  realizedPnl?: number;
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

// ========== 资产快照类型（五分类：bStock/crypto/大宗商品/预测/游戏） ==========
export interface AssetSnapshot {
  date: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
  bstockProfit: number;
  cryptoProfit: number;
  commodityProfit: number;
  predictionProfit: number;
  gameProfit: number;
  dailyProfit: number;
  dailyProfitPct: number;
  dailyBstockProfit: number;
  dailyCryptoProfit: number;
  dailyCommodityProfit: number;
  dailyPredictionProfit: number;
  dailyGameProfit: number;
}

export interface CategoryAverages {
  bstockProfit: number;
  cryptoProfit: number;
  commodityProfit: number;
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

/** 邀请码（Admin 管理） */
export interface InviteCode {
  id: number;
  code: string;
  maxUses: number;
  usedCount: number;
  enabled: boolean;
  createdAt: string;
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

/** 工作台历史会话摘要（/ai/workbench/sessions） */
export interface WorkbenchSessionSummary {
  sessionId: string;
  title: string;
  messageCount: number;
  lastAt: number;
}

/** 工作台历史消息（/ai/workbench/sessions/{id}/messages） */
export interface WorkbenchChatMessage {
  role: 'user' | 'assistant' | string;
  content: string;
  createdAt: number;
}

/** 策略×币种实时信号状态快照（/ai/strategies/signals）：一句话状态 + 有序指标表 */
export interface StrategySignalState {
  strategyId: string;
  symbol: string;
  state: string;
  metrics: Record<string, string>;
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

// ========== 留言板与通知 ==========

/** 留言板评论。只有两层：rootId 为空是根评论，非空是该根评论下的子评论。 */
export interface CommentItem {
  id: number;
  userId: number;
  username: string;
  avatar?: string;
  rootId?: number;
  replyToUserId?: number;
  /** 子评论展示"回复 @xxx"用 */
  replyToUsername?: string;
  content: string;
  likeCount: number;
  dislikeCount: number;
  /** 已对本条表过态（赞踩共用一次机会），true 时两个按钮都置灰。未登录恒 false */
  voted: boolean;
  /** 根评论专用：子评论总数。列表接口只带前 2 条预览，靠它判断要不要出"查看全部" */
  childCount: number;
  children?: CommentItem[];
  /** 非空即编辑过，显示"已编辑"。自删不写此字段，占位符不会被标成已编辑 */
  updatedAt?: string;
  /** 作者自删的占位符：正文已被覆盖，不能再编辑，但照常可赞可回复 */
  selfDeleted?: boolean;
  createdAt: string;
}

/** 通知条目。前端按 type+commentId 分组合并展示，后端每次事件只管插一行。 */
/** 评论类通知的 type */
export type CommentNotifType = 1 | 2;
/** 交易类通知的 type：3逐仓强平 4止损 5止盈 6全仓爆仓 */
export type TradeNotifType = 3 | 4 | 5 | 6;

export interface NotificationItem {
  id: number;
  /** 1赞 2回复 3逐仓强平 4止损 5止盈 6全仓爆仓 */
  type: CommentNotifType | TradeNotifType;
  isRead: boolean;
  createdAt: string;

  // ---- 评论类专属（交易类为 null）----
  /** 点击跳转目标：赞=自己被赞那条，回复=对方那条回复 */
  commentId: number | null;
  actorId: number | null;
  actorName: string | null;

  // ---- 交易类专属（评论类为 null）----
  /** 全仓爆仓跨多币种，为 null */
  symbol: string | null;
  side: 'LONG' | 'SHORT' | null;
  /** 平掉的数量；type=6 时是"爆掉的仓位数" */
  quantity: number | null;
  /** 触发价；全仓爆仓为 null */
  price: number | null;
  /** 已实现盈亏；type=6 时是净结算额 */
  pnl: number | null;
}
