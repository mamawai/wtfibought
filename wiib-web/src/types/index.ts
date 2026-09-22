export interface User {
  id: number;
  username: string;
  avatar?: string;
  balance: number;
  /** 游戏钱包：Mines/扑克/21点兑现/预测市场专用，和交易 balance 分离 */
  gameBalance: number;
  frozenBalance: number;
  positionMarketValue: number;
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

/**
 * 榜单排序维度。没有「收益率」这一档——初始资金全站是同一个常数，
 * 收益率跟总资产是同一个序，加进来就是同一张榜换个名字。
 */
export type RankingSort = 'ASSETS' | 'TRADING_PROFIT';

export interface RankingItem {
  rank: number;
  userId: number;
  username: string;
  avatar?: string;
  totalAssets: number;
  profitPct: number;
  /** 交易盈利 = 合约 + 现货 + 预测的净盈亏，不含优惠券省下的钱 */
  tradingProfit: number;
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
  /** 转出后仍可转出的积分，后端算好返回，前端不再自己减保底值 */
  convertable: number;
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

// 交易过滤器：数量步长/最小数量/最小名义额（对齐Binance exchangeInfo，后端注册表下发）
export interface TradeFilter {
  stepSize: number;
  minQty: number;
  minNotional: number;
}

export interface TradeFilterMap {
  futures: Record<string, TradeFilter>;
  spot: Record<string, TradeFilter>;
}

export interface FuturesAddMarginRequest {
  positionId: number;
  amount: number;
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

/** 资金费率（GET /futures/funding-rate）：后端只读结算点缓存，无合约的标的返回 null。rate 是小数，0.0001=0.01% */
export interface FundingRateView {
  symbol: string;
  rate: number;
  /** 结算点拉取时刻 */
  fetchedAt: number;
  /** 下一个 0/8/16 点 */
  nextTime: number;
}

// 币种级调杠杆（对齐Binance）：多空共用杠杆，一次调整作用于该币全部仓位
export interface FuturesAdjustLeverageRequest {
  symbol: string;
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

/** 划转预检（GET /wallet/transfer/preview）：restricted=可转额度被全仓占用压低（含未成交的全仓挂单） */
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

// ==================== 合约仓位历史 ====================

/** 仓位历史里的一笔成交。分批平仓靠它才看得见「0.4@110 / 0.6@120」的过程 */
export interface PositionFill {
  positionId: number;
  orderId: number;
  /** OPEN_LONG/OPEN_SHORT 开或加仓，CLOSE_LONG/CLOSE_SHORT 平仓 */
  orderSide: string;
  orderType: 'MARKET' | 'LIMIT';
  /** FILLED 手动成交，STOP_LOSS/TAKE_PROFIT 止损止盈打到，LIQUIDATED 被强平 */
  status: 'FILLED' | 'STOP_LOSS' | 'TAKE_PROFIT' | 'LIQUIDATED';
  quantity: number;
  price: number;
  amount: number;
  commission: number;
  /** 平仓单才有，开/加仓单为 null */
  realizedPnl: number | null;
  filledAt: string;
}

/**
 * 一笔合约仓位的完整生命周期（开→平）。
 * 跟 FuturesOrder 的区别是粒度：那个是一笔笔委托，这个把同仓位的开/加/分批平合成一条生意。
 */
export interface PositionHistoryItem {
  id: number;
  symbol: string;
  side: 'LONG' | 'SHORT';
  marginMode: FuturesMarginMode;
  leverage: number;
  /** CLOSED 正常平掉（含止盈止损打到） LIQUIDATED 被强平 */
  status: 'CLOSED' | 'LIQUIDATED';
  /** AI 策略标签，手动开的仓为 null */
  memo: string | null;
  /** 开仓均价（多次加仓已按量加权） */
  entryPrice: number;
  /** 已平仓量 = 全部平仓单数量之和 */
  closedQty: number;
  closeAmount: number;
  /** 平仓均价；一单没平过的仓位为 null（破产清零那批），显示"—" */
  closeAvgPrice: number | null;
  /** 累计投入保证金，回报率的分母 */
  investedMargin: number;
  commission: number;
  fundingFeeTotal: number;
  /** 已实现盈亏（净额）：已扣手续费与资金费，口径同排行榜「交易盈利」 */
  realizedPnl: number;
  /** 投资回报率(%)；分母为 0 时 null */
  roiPct: number | null;
  openedAt: string;
  closedAt: string;
  fills: PositionFill[];
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
  createdAt: string;
  isAiTrader?: boolean;
}

/** 反手结果：closed=平掉那笔（带已实现盈亏），opened=反向开的那笔；反向开仓失败时 opened 为 null、openError 带原因（此时已空仓） */
export interface FuturesReverseResult {
  closed: FuturesOrder;
  opened: FuturesOrder | null;
  openError: string | null;
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
    crypto: { positionCount: number; totalBuyAmount: number; totalSellAmount: number; leverageUsage: string };
    bstock: { positionCount: number; totalBuyAmount: number; totalSellAmount: number };
    futures: {
      realizedPnl: number; orderCount: number; direction: string; avgLeverage: number;
      stopLossRate: number; liquidationCount: number;
      byCategory?: {
        crypto: { realizedPnl: number; orderCount: number };
        commodity: { realizedPnl: number; orderCount: number };
        tradfi: { realizedPnl: number; orderCount: number };
      };
    };
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
  /** 思考档位，任意上游认的值（none/low/medium/high/xhigh…）；空=不传走模型默认 */
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
/** 工作台 SSE 事件（与 ChatWorkbenchController 协议一一对应） */
export type WorkbenchEvent =
  | { type: 'session'; sessionId: string }
  | { type: 'agent_start'; node: string; agent: string }
  | { type: 'token'; text: string; agent: string; role: 'answer' | 'process' }
  | { type: 'progress'; text: string }
  // requestId：这张卡的唯一标识，点同意/拒绝时原样回传，服务端据此确认"点的是哪张卡"
  | { type: 'hitl_request'; sessionId: string; symbol: string; reason: string; requestId: string; resumeMessage: string }
  // 模型请求给用户弹一张待填的表单卡；执行权在用户点击，模型只能开卡不能动手。
  // prefill 是模型草拟的初值（留言正文与轮次），可能整个缺席
  | { type: 'form_request'; form: TraderFormKind; prefill?: Record<string, unknown> }
  // 行为分析报告：整份结构化数据只走这条通道给前端渲染成卡片，模型手里是裁剪版
  //（少了 overview.trend 那 30 天逐日快照——对模型是噪音，对卡片是那条资产曲线）
  | { type: 'behavior_report'; report: BehaviorAnalysisReport }
  // 汇总者的服务端搜索过程：searching=开搜（query 可能还没有）、searched=搜完（sources 是命中站点）、
  // cited=正文引用了某来源（只并入后端攒的来源随 done 回来，过程轨不画）
  | { type: 'search'; phase: 'searching' | 'searched' | 'cited'; query?: string | null; sources: SearchSource[] }
  // deferred=true：让位收尾（专家还在取数就来了新消息），answer 只是过渡话术；
  // 真答案由后端补答轮落历史，前端靠 status 轮询等它落库后整体回放补显
  // meta 是本轮读数，让位收尾那条 done 不带（答案还没出，无账可报）。
  // cancelled=用户中断，answer 是"半截 + （已中断）"的定稿，前端要整段用它覆盖屏上那半截
  // deferred=让位收尾（answer 只是过渡话术，question=被让位的原问题）；pending=此刻会话还欠着补答，前端空闲时发起补答轮
  // sources=这一轮搜到/引用的来源（按 url 去重），答案底部展示；没搜过为空数组
  | { type: 'done'; sessionId: string; answer: string; deferred?: boolean; question?: string; cancelled?: boolean; pending?: boolean; meta?: TurnMeta; sources?: SearchSource[] }
  | { type: 'error'; message: string };

/**
 * 功能按钮直发的一轮带的意图（与后端 ChatIntent 同名同值）。
 * 带它的一轮后端不派专家，直接让汇总者调对应工具——按钮已经说明了要什么，不必再让路由猜。
 */
export type ChatIntent = 'BEHAVIOR';

/** trader 动作面板的三张卡 */
export type TraderFormKind = 'note' | 'wake' | 'review';

/** 动作面板一次取齐的状态：三张卡显示什么、按钮点不点得动，全看它 */
export interface TraderActionPanel {
  hasTrader: boolean;
  name: string | null;
  status: string | null;
  pausedReason: string | null;
  /** 上次唤醒的真实时刻（决策行落库时间，不是 K 线边界） */
  lastWakeAt: number | null;
  nextWakeAt: number | null;
  /** null=可唤醒，否则是不能唤醒的原话，直接显示给用户 */
  wakeBlockedReason: string | null;
  lastReviewAt: number | null;
  lastReviewStatus: string | null;
  hasReviewMaterial: boolean;
  reviewBlockedReason: string | null;
  /** 当前待读留言正文，null=没有 */
  note: string | null;
  noteRounds: number;
  noteMaxRounds: number;
  noteMaxChars: number;
}

/** 动作执行结果：ok 只表示这次请求被正常处理，message 一律要显示 */
export interface TraderActionResult {
  ok: boolean;
  message: string;
}

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

/** 会话运行状态（/ai/workbench/sessions/{id}/status）：running=有轮在跑；pending=欠着补答 */
export interface WorkbenchSessionStatus {
  running: boolean;
  pending: boolean;
}

/** 工作台历史会话摘要（/ai/workbench/sessions） */
export interface WorkbenchSessionSummary {
  sessionId: string;
  title: string;
  messageCount: number;
  lastAt: number;
}

/**
 * 一轮的读数：用的哪个端点、烧了多少 token、花了多久。
 * <p>
 * 每一项都可能取不到值 —— 那是"上游端点没报 usage / 这一轮的账不可信"，<b>不是 0</b>，展示层必须区分。
 * 两条来路的空值形状还不一样：SSE 帧不输出 null，字段直接<b>缺席</b>；
 * 历史接口走 Jackson，会老老实实输出 null。所以一律按 `!= null` 判，别判 0、也别只判 undefined。
 */
/** 联网搜索命中/引用的一个来源 */
export interface SearchSource {
  url: string;
  title?: string | null;
}

export interface TurnMeta {
  /** 端点名 · 模型名 */
  modelLabel?: string | null;
  modelCalls?: number | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  latencyMs?: number | null;
}

/** 工作台历史消息（/ai/workbench/sessions/{id}/messages） */
export interface WorkbenchChatMessage {
  id: number;
  role: 'user' | 'assistant' | string;
  content: string;
  createdAt: number;
  /** 只有 assistant 行有；user 行与加列之前的老数据是 null */
  meta?: TurnMeta | null;
  /** 这一轮联网搜索的来源；没搜过或老数据是 null */
  sources?: SearchSource[] | null;
  /**
   * 后端自己写进历史的特殊行的码（服务端 ChatRowKind），普通行为 null：
   * deferred=补答行（不给重新生成）、hitlResume=批准后自动补发的续跑指令（还原成过程轨）。
   * 这两种行的正文是词表文案、跟着语言变，所以判定归后端，前端只认码。
   */
  kind?: 'deferred' | 'hitlResume' | null;
}

/** 我的对话端点配置（BYOK）。key 只回尾 4 位，明文不出服务端 */
/**
 * 用户 BYOK 端点（AI 页「模型配置」维护的一条）：协议+URL+key+模型(+思考档位)。
 * 全站唯一的 BYOK 总配置；对话/交易员/复盘教练从中选。key 只回尾 4 位。
 */
export interface LlmEndpointView {
  id: number;
  name: string;
  apiProtocol: string;
  baseUrl: string;
  model: string;
  /** 任意上游认的档位值（none/low/medium/high/xhigh…），null=不传给上游走模型默认 */
  reasoningEffort: string | null;
  /** 服务端联网搜索（responses / anthropic / gemini 协议端点可开；只有对话汇总者用它） */
  webSearch: boolean;
  apiKeyTail: string;
  /** 默认端点：没按用途绑定的地方都用它 */
  isDefault: boolean;
}

/** 新增/更新/检测/探测共用；更新时 apiKey 传空=沿用已存的 */
export interface LlmEndpointSaveRequest {
  name: string;
  apiProtocol: string;
  baseUrl: string;
  model: string;
  reasoningEffort: string;
  apiKey: string;
  webSearch: boolean;
}

/** 用途 → 端点 id；缺的用途 = 跟随默认端点 */
export type LlmPurpose = 'CHAT_MAIN' | 'CHAT_LIGHT' | 'TRADER';
export type LlmBindings = Partial<Record<LlmPurpose, number>>;

/** 用户 Jev 决策模型配置（一人一份，与端点库分开）；未配置时接口回 null */
export interface JevConfigView {
  baseUrl: string;
  model: string;
  apiKeyTail: string;
}

/** 保存/探测共用；baseUrl/model 留空走默认，已有配置时 apiKey 传空=沿用已存的 */
export interface JevSaveRequest {
  baseUrl: string;
  model: string;
  apiKey: string;
}


/** 词表 key + 占位值，文字在 strategy.json 的 strategies.signals 下；vars.context 选词条变体，vars.count 选单复数 */
export interface SignalText {
  key: string;
  vars: Record<string, string | number>;
}

/** 策略×币种实时信号状态快照（/ai/strategies/signals）：一句话状态 + 有序指标 */
export interface StrategySignalState {
  strategyId: string;
  symbol: string;
  state: SignalText;
  metrics: SignalText[];
}

// ========== AI Trader 竞技场 ==========

/** trader 公开视图（竞技场任何登录用户可见） */
export interface TraderPublicView {
  id: number;
  name: string;
  /** 它当前用的端点的模型名（现解析）；主人把端点删光了为 null */
  model: string | null;
  status: 'PAUSED' | 'RUNNING' | 'LIQUIDATED';
  pausedReason: string | null;
  symbols: string;
  intervalCode: string;
  roundNo: number;
  equity: number;
  pnlPct: number;
  mine: boolean;
  /** 唤醒时段 "HH:mm-HH:mm"（北京时间，两端含，可跨午夜），null=全天；进公开视图是让观众看得懂"半天没决策" */
  wakeWindow: string | null;
}

/** 主人视图：公开视图 + 配置回显（key 只回尾4位） */
export interface TraderOwnerView {
  pub: TraderPublicView;
  /** 显式绑定的端点 id，null=跟随默认端点 */
  llmEndpointId: number | null;
  customPrompt: string | null;
  spec: TraderSpec;
  /** 波动哨兵警报开关（仅 1h/4h 档生效） */
  alertEnabled: boolean;
  /** 警报灵敏度系数 >=1 只能调高：生效阈值 = 每币基准 x 系数 */
  alertThresholdMult: number;
  /** 每日复盘开关：reviewer 日线边界复盘写 REVIEW 行并整理记忆笔记 */
  reviewEnabled: boolean;
  /** 同侪学习开关：learning agent 在全体复盘完成后向同侪学习写 LEARN 行并整理学习笔记 */
  learningEnabled: boolean;
  /** 唤醒时段 "HH:mm-HH:mm"，null=全天 */
  wakeWindow: string | null;
}

/**
 * 仓位规格：主人设的硬参数，模型只能遵守不能评价。
 * 区间是"允许集合"而非上限——配 50~100 时模型选 20 也会被护栏拒。
 */
export interface TraderSpec {
  /** 杠杆区间，1~125；实际可用还受交易所按名义价值分档限制 */
  leverageMin: number;
  leverageMax: number;
  /** 单笔保证金占权益%区间，0.1~100；只约束开新仓，加仓量由模型自己斟酌 */
  marginPctMin: number;
  marginPctMax: number;
  /** 允许同时持有多个仓位；关=全账户至多一仓（挂单也占坑） */
  allowMultiPosition: boolean;
  /** 允许同币多空双开；仅在 allowMultiPosition 开启时有意义 */
  allowHedge: boolean;
}

/** 计划修订记录（revisionsJson 解析后）：修改必须留痕带理由 */
export interface PlanRevision {
  time: number;
  type: string;
  change: string;
  reason: string;
}

/** 持仓交易计划：开仓立的论点/失效条件/原始快照 + 修订历史；当前生效止损止盈以仓位为准 */
export interface AiTraderPlanView {
  id: number;
  traderId: number;
  roundNo: number;
  symbol: string;
  side: 'LONG' | 'SHORT';
  playType: string | null;
  signalsUsed: string | null;
  invalidationCondition: string;
  entryPrice: number | null;
  stopLossPrice: number | null;
  takeProfitPrice: number | null;
  openedWakeTime: number;
  /** [{time,type,change,reason}] */
  revisionsJson: string | null;
  /** 主人标记忽略：true=AI 统计与复盘不再参考；公开战绩/同侪视角照常 */
  stale: boolean | null;
}

/** 交易记录里引用的那一轮决策：id 对应时间线卡；reason 只有平仓有（close_position 的一句话理由） */
export interface TradeDecisionRef {
  id: number;
  wakeTime: number;
  kind: 'TRADE' | 'ALERT' | 'MANUAL';
  reasoning: string | null;
  reason: string | null;
}

/** 已了结交易（当前局）：sim 已平仓位 + 配对的计划 + 开/平仓决策——竞技场"论点→结局"卡的一行 */
export interface TradeRecordView {
  positionId: number;
  symbol: string;
  side: 'LONG' | 'SHORT';
  leverage: number | null;
  entryPrice: number;
  closedPrice: number | null;
  closedPnl: number | null;
  openedAt: number;
  closedAt: number;
  /**
   * 了结方式的语言无关码：takeProfit / stopLoss / manual / liquidated / UNKNOWN
   * （与复盘素材同一套推断）。文案查 ai:trade.closeManner.*，配色也认这个码
   */
  closeMannerKey: string;
  plan: AiTraderPlanView | null;
  openDecision: TradeDecisionRef | null;
  /** 只有主动平仓才有：止损/止盈带走的依据就是计划里的原始止损/目标 */
  closeDecision: TradeDecisionRef | null;
}

/** trader 详情：公开视图 + 实时持仓/挂单 + 本局生效中的交易计划 + 两份笔记 */
export interface TraderDetailView {
  trader: TraderPublicView;
  positions: FuturesPosition[];
  pendingOrders: FuturesOrder[];
  plans: AiTraderPlanView[];
  /** 记忆笔记：reviewer 每日复盘沉淀的，跨局累积；trader 每次唤醒都读 */
  memory: string | null;
  /** 学习笔记：learning agent 向同侪学的，跨局累积；trader 每次唤醒都读 */
  learningNotes: string | null;
  /** 最近一次成功复盘 / 学习的 wakeTime(ms)，没有=null */
  lastReviewAt: number | null;
  lastLearnAt: number | null;
}

/** 每次唤醒一条决策（竞技场时间线） */
export interface AiTraderDecisionView {
  id: number;
  traderId: number;
  roundNo: number;
  wakeTime: number;
  intervalCode: string;
  /**
   * TRADE=例行K线唤醒 ALERT=波动哨兵警报唤醒 MANUAL=主人手动唤醒（对话轨 wake_trader）
   * REVIEW=每日复盘（reasoning=复盘全文，无equity） LEARN=向同侪学习（reasoning=学习全文，无equity）
   */
  kind: 'TRADE' | 'ALERT' | 'MANUAL' | 'REVIEW' | 'LEARN';
  status: 'OK' | 'ERROR' | 'SKIPPED';
  equity: number | null;
  reasoning: string | null;
  /** [{tool,args,status,result/rejected/error}...]；status=unknown 表示重发也没问到结果，可能已成交 */
  actionsJson: string | null;
  toolCalls: number;
  /** 本轮模型调用次数：ReAct 是循环，一次唤醒会调很多次 */
  modelCalls: number | null;
  /** token 合计；null=上游端点没返回 usage（BYOK 网关各不相同），不是 0，展示成「—」 */
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  latencyMs: number | null;
  error: string | null;
  createdAt: string;
  /** 这轮有没有落库的过程轨迹（trace_json 非空）；老决策行没有，不出"过程"按钮 */
  hasTrace: boolean;
}

export interface TraderEquityPoint {
  wakeTime: number;
  equity: number;
}

// ---- 唤醒过程：实时流（/ai/trader/{id}/live、/ai/trader/live）与落库轨迹（trace_json）----

/** 会走实时流的唤醒类型（复盘/学习不进这条通道） */
export type WakeKind = 'TRADE' | 'ALERT' | 'MANUAL';
/** 工具回执状态：按回执前缀 REJECTED: / ERROR: 判，其余 ok */
export type WakeToolStatus = 'ok' | 'rejected' | 'error';

/** 模型发起的一次工具调用；args 是解析后的对象，解析不了就是原字符串 */
export interface WakeToolCall {
  id: string;
  name: string;
  args: Record<string, unknown> | string;
}

/** 一条工具回执；preview 后端已截 2000 字 */
export interface WakeToolResult {
  id: string;
  name: string;
  status: WakeToolStatus;
  preview: string;
}

/** 一次模型调用：文本 + 它发起的工具调用 + 对应回执；startedAt/endedAt 只有落库轨迹带，实时归约的没有 */
export interface WakeCall {
  n: number;
  text: string;
  toolCalls: WakeToolCall[];
  results: WakeToolResult[];
  startedAt?: number;
  endedAt?: number;
}

/** 收尾：totalTokens null=上游没回 usage */
export interface WakeEnd {
  status: 'OK' | 'ERROR';
  error: string | null;
  equity: number;
  latencyMs: number;
  modelCalls: number;
  totalTokens: number | null;
}

/**
 * 一次唤醒的完整过程。实时流按帧归约出来的和 trace 接口原样返回的是同一形状；
 * 两条路都只有主人拿得到，end 跑完才有
 */
export interface WakeTrace {
  v: number;
  kind: WakeKind;
  wakeTime: number;
  startedAt: number;
  budgetSeconds: number;
  equity: number;
  positions: number;
  pendingOrders: number;
  prompt?: { system: string; instruction: string };
  calls: WakeCall[];
  end?: WakeEnd;
}

/** 现场流 SSE 事件（与后端 TraderLiveHub 帧协议一一对应） */
export type TraderLiveEvent =
  | { type: 'run_start'; kind: WakeKind; wakeTime: number; budgetSeconds: number; equity: number; positions: number; pendingOrders: number; startedAt: number }
  | { type: 'prompt'; system: string; instruction: string }
  | { type: 'model_start'; call: number }
  | { type: 'token'; call: number; text: string }
  // text 是这次调用的整段文本
  | { type: 'model_end'; call: number; text: string; toolCalls: WakeToolCall[] }
  | { type: 'tool_result'; call: number; id: string; name: string; status: WakeToolStatus; preview: string }
  // 决策行已落库后发
  | { type: 'run_end'; status: 'OK' | 'ERROR'; error: string | null; equity: number; latencyMs: number; modelCalls: number; totalTokens: number | null; decisionId: number };

/** 创建/改配置入参（apiKey 改配置时传空=不换） */
export interface TraderUpsertRequest {
  name: string;
  symbols: string;
  intervalCode: string;
  customPrompt: string | null;
  /** 端点库里的一条（AI 页模型配置维护），null=跟随用户默认端点 */
  llmEndpointId: number | null;
  spec: TraderSpec;
  alertEnabled: boolean;
  alertThresholdMult: number;
  reviewEnabled: boolean;
  learningEnabled: boolean;
  /** 唤醒时段 "HH:mm-HH:mm"（北京时间），null=全天 */
  wakeWindow: string | null;
}

/** 重要快讯（BlockBeats 缓存透传，plain 为脱 HTML 纯文本） */
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

// ==================== 资金账单 ====================

/** 账本钱包维度。前五个对应 user 表的资金列；POSITION_MARGIN 记的是仓位保证金 */
export type LedgerWallet =
  | 'BALANCE' | 'FROZEN' | 'GAME' | 'LOAN_PRINCIPAL' | 'LOAN_INTEREST' | 'POSITION_MARGIN';

export interface LedgerEntry {
  id: number;
  userId: number;
  wallet: LedgerWallet;
  /** 枚举名，筛选参数传的就是它 */
  bizType: string;
  /** 中文说法，后端平铺下来的，前端不再维护一份映射 */
  bizTypeLabel: string;
  /** 变动额，有符号，正入负出 */
  delta: number;
  /** 该钱包变动后余额 */
  balanceAfter: number;
  /** delta 中含的手续费；仅费与本金同条 SQL 时才有 */
  fee: number | null;
  refType: string | null;
  refId: number | null;
  symbol: string | null;
  remark: string | null;
  createdAt: string;
}

/** 筛选下拉选项，取自后端枚举，避免前端硬编码一份中文映射 */
export interface LedgerBizTypeOption {
  name: string;
  label: string;
  group: string;
}

// ==================== 排行榜用户详情 ====================

/** 详情页的一条持仓。现货与合约共用一个形状，合约专属字段在现货行上为 null */
export interface ProfilePosition {
  symbol: string;
  quantity: number;
  /** 现货=持仓均价，合约=开仓均价 */
  entryPrice: number;
  /** 现货=现价，合约=标记价；取不到价时为 null */
  currentPrice: number | null;
  /** 现货=市值，合约=保证金+未实现盈亏；缺价时为 null */
  value: number | null;
  /** 现货=浮动盈亏，合约=未实现盈亏；缺价时为 null */
  profit: number | null;
  side: 'LONG' | 'SHORT' | null;
  leverage: number | null;
  marginMode: 'CROSS' | 'ISOLATED' | null;
}

export interface UserProfile {
  /** 榜单行原样复用，口径与排行榜完全一致 */
  summary: RankingItem;
  spotPositions: ProfilePosition[];
  futuresPositions: ProfilePosition[];
}

// ==================== 全站成交记录（匿名） ====================

export interface PublicTrade {
  /** SPOT=现货/bStock（共用现货引擎），FUTURES=永续合约 */
  kind: 'SPOT' | 'FUTURES';
  tradeId: number;
  /** 稳定假名，如 "a3f2c1"。同一用户恒定，但反推不回是谁 */
  alias: string;
  /** 策略账户（quant-*）下的单，是机器人不是人 */
  isAi: boolean;
  symbol: string;
  /** 现货 BUY/SELL；合约 OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT */
  orderSide: string;
  quantity: number;
  filledPrice: number;
  filledAmount: number;
  createdAt: string;
}

// ==================== 可视化回测页 ====================

export interface BacktestTaskStatus {
  taskId: string;
  state: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED';
  strategyId: string;
  symbol: string;
  barsDone: number;
  totalBars: number;
  warmupBars: number;
  error: string | null;
  /** 排队第几位；非 QUEUED 恒 0 */
  queuePos: number;
}

/** 手动复盘数据源：本地 5m K 线覆盖范围（随机盲测在此区间抽起点） */
export interface ReplayCoverage {
  symbol: string;
  earliestMs: number;
  latestMs: number;
}

/** 本地历史 K 线区间拉取：rows = [openTime, open, high, low, close, volume] */
export interface HistoryKlinesPayload {
  total: number;
  rows: number[][];
}

/**
 * 复盘 AI 教练请求（对应后端 ReplayCoachRequest）。时间一律是格式化好的标签：
 * 盲测局只给 "D2 14:30" 相对标签，真实日期不能经这条通道泄露给模型。
 */
export interface ReplayCoachRequest {
  mode: 'HINT' | 'REVIEW';
  /** 用哪条 BYOK 端点（复盘配置台从端点库下拉选），缺省=用户默认端点 */
  endpointId?: number;
  symbol: string;
  intervalMin: number;
  blind: boolean;
  /** 复盘段首根的时间标签（更早的 K 线是开局上下文） */
  startAt?: string;
  bars: { t: string; o: number; h: number; l: number; c: number; v: number }[];
  /** 当前权益（HINT）：浮盈亏占权益多少才说得清仓位风险 */
  equity?: number;
  /** leverage=有效杠杆（加仓换档会成小数） */
  positions?: { side: 'LONG' | 'SHORT'; qty: number; entryPrice: number; leverage: number; unrealizedPnl: number }[];
  trades?: {
    side: 'LONG' | 'SHORT'; qty: number; leverage: number; entryPrice: number; exitPrice: number; pnl: number;
    openAt: string; closeAt: string; reason: string; partial: boolean;
  }[];
  stats?: {
    totalTrades: number; wins: number; losses: number; netProfit: number; returnPct: number;
    maxDrawdownPct: number; totalFees: number; initialBalance: number; finalEquity: number;
  };
}

/** 复盘 AI 教练 SSE 事件（工作台协议的子集） */
export type ReplayCoachEvent =
  | { type: 'token'; text: string }
  | { type: 'done'; answer: string }
  | { type: 'error'; message: string };

/** 工作记录事件：seq=任务内游标（=事件表下标），type 见后端 BacktestListener 常量 */
export interface BacktestEvent {
  seq: number;
  barTimeMs: number;
  type: string;
  data: Record<string, unknown>;
}

export interface BacktestEventsPage {
  events: BacktestEvent[];
  nextAfter: number;
  state: string;
}

/** K线分段：rows = [openTime, open, high, low, close, volume]（含预热段） */
export interface BacktestKlinesPage {
  total: number;
  offset: number;
  rows: number[][];
}

export interface BacktestSummary {
  totalTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  profitFactor: number;
  netProfit: number;
  totalFees: number;
  sharpeRatio: number;
  maxDrawdownPct: number;
  avgHoldBars: number;
  avgR: number;
  returnPct: number;
  finalEquity: number;
}

export interface BacktestTrade {
  /** 对应 klines 下标（含预热段偏移），图表 marker 直接定位 */
  openBarIndex: number;
  closeBarIndex: number;
  openTime: number;
  closeTime: number;
  side: 'LONG' | 'SHORT';
  entryPrice: number;
  exitPrice: number;
  quantity: number;
  leverage: number;
  pnl: number;
  fee: number;
  rMultiple: number | null;
  exitReason: string;
  maxFavorableR: number | null;
  maxAdverseR: number | null;
}

export interface BacktestResultPayload {
  taskId: string;
  strategyId: string;
  symbol: string;
  warmupBars: number;
  summary: BacktestSummary;
  trades: BacktestTrade[];
  /** 降采样权益曲线：[closeTimeMs, equity] */
  equity: [number, number][];
}

// ==================== LDC 瓜分活动 ====================

export interface CampaignScoreItem {
  /** 稳定标识：ROI25 / ROI50 / ROI100 / GODLY / SPOT / TRIPLE / PREDICTION / STOP_LOSS_HERO
   *  / PNL_PROFIT / PNL_LOSS / LIQ_TRIGGER / RESET_EXTRA / CHECKIN / STREAK / FIRST_COMMENT / VOTE */
  code: string;
  label: string;
  /** 达成次数；投票那条恒为 0（它按分不按次） */
  count: number;
  score: number;
}

export interface CampaignScore {
  userId: number;
  username: string | null;
  /** 参与名单已按"纯数字 linux_do_id"筛过，故恒为 true。前端不要据此分支 */
  claimable: boolean;
  tradeScore: number;
  dailyScore: number;
  voteScore: number;
  /** 强平扣分，负数 */
  penalty: number;
  finalScore: number;
  items: CampaignScoreItem[];
}

export interface CampaignVoteBoard {
  /** 后端只下发代码，展示名查 market:coinName.*（getCoin(symbol).name）——卡片标题跟界面语言 */
  symbol: string;
  upCount: number;
  downCount: number;
  /** null = 今天还没投这个标的 */
  myDirection: 'UP' | 'DOWN' | null;
}

export interface MyCampaignView {
  campaignId: number;
  campaignName: string;
  startAt: string;
  endAt: string;
  prizePool: number;
  me: CampaignScore;
  /** 有资格参与分配的人的总分，也就是分配公式的分母 */
  eligibleTotal: number;
  estimatedLdc: number;
  /** 我的名次，从 1 起；0 = 还没上榜（一分没有的人不进榜） */
  rank: number;
  participants: number;
  checkedToday: boolean;
  voteBoard: CampaignVoteBoard[];
}

/**
 * 当前活动（/campaign/current），没有进行中的活动时为 null。
 * <p>活动页只用它的 status 判"结算了没"——MyCampaignView 里没有这个字段，
 * 而"还没结算"和"结算了但你一分没分到"在 /reward 里都是 null，不看状态区分不开。
 */
export interface CampaignInfo {
  id: number;
  code: string;
  name: string;
  startAt: string;
  endAt: string;
  prizePool: number;
  /** RUNNING 进行中 / SETTLING 已结算可领取（DONE 收尾后接口直接返回 null） */
  status: string;
}

export interface CampaignReward {
  ldcAmount: number;
  /** PENDING 待领取 / CLAIMED 已授权待发 / SUCCESS 已到账 / FAILED 发放失败（可重领） */
  status: string;
  externalRef: string | null;
  errorMsg: string | null;
  finalScore: number;
}
