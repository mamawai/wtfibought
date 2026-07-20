import axios from 'axios';
import type { TnOverview, TnTrade, TnDailyCell, TnEquityPoint, TnFillStats, TnManualOrderReq, TnOrderResult, TnAck } from '../types/testnet';
import type { User, PageResult, RankingItem, CommentItem, NotificationItem, BuffStatus, UserBuff, BlackjackStatus, GameState, ConvertResult, MinesStatus, MinesGameState, VideoPokerStatus, VideoPokerGameState, CryptoPrice, CryptoOrderRequest, CryptoOrder, CryptoPosition, BStock, FuturesOpenRequest, FuturesCloseRequest, FuturesAddMarginRequest, FuturesReduceMarginRequest, FuturesIncreaseRequest, FuturesStopLossRequest, FuturesTakeProfitRequest, FuturesAdjustLeverageRequest, FuturesCrossAccount, WalletTransferPreview, FuturesPosition, FuturesOrder, FuturesBracket, PredictionRound, PredictionBet, PredictionBuyRequest, PredictionBetLive, PredictionPnl, AssetSnapshot, CategoryAverages, BehaviorAnalysisReport, ForceOrder, AiKeyConfig, AiModelAssignment, InviteCode, WorkbenchEvent, QuantSnapshotView, QuantSnapshotSeriesPoint, QuantDeepAnalysisView, Scorecard, StrategyAccountView, StrategySignalState, FeedStreamHealth, WorkbenchSessionSummary, WorkbenchChatMessage } from '../types';

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
});

// 请求拦截器：添加Token到Header
api.interceptors.request.use((config) => {
  const stored = localStorage.getItem('wiib-user');
  if (stored) {
    try {
      const { state } = JSON.parse(stored);
      if (state?.token) {
        config.headers['satoken'] = state.token;
      }
    } catch { /* ignore */ }
  }
  return config;
});

// 响应拦截器：检查code并提取data字段
api.interceptors.response.use(
  (res) => {
    const { code, msg, data } = res.data;
    if (code === 401) {
      localStorage.removeItem('wiib-user');
      window.location.href = '/login';
      return Promise.reject(new Error(msg || '未登录'));
    }
    if (code !== 0) {
      return Promise.reject(new Error(msg || '请求失败'));
    }
    return data;
  },
  (err) => {
    const msg = err.response?.data?.msg || err.response?.data?.message || err.message;
    console.error('API错误:', msg);
    return Promise.reject(new Error(msg));
  }
);

// ========== 认证接口 ==========
export const authApi = {
  // 登录模式：两个开关都关时走管理员直登
  mode: () => api.get<unknown, { linuxDoEnabled: boolean; passwordLoginEnabled: boolean }>('/auth/mode'),
  // LinuxDo OAuth回调
  linuxDoCallback: (code: string) => api.get<unknown, string>('/auth/callback/linuxdo', { params: { code } }),
  // 管理员直登（仅所有正式登录方式都未启用时可用）
  localLogin: () => api.post<unknown, string>('/auth/login/local'),
  // 邀请码注册（成功即登录，返回 token）
  register: (username: string, password: string, inviteCode: string) =>
    api.post<unknown, string>('/auth/register', { username, password, inviteCode }),
  // 账号密码登录
  passwordLogin: (username: string, password: string) =>
    api.post<unknown, string>('/auth/login/password', { username, password }),
  // 获取当前用户信息
  current: () => api.get<unknown, User>('/auth/current'),
  // 退出登录
  logout: () => api.post<unknown, void>('/auth/logout'),
};

// ========== 用户接口 ==========
export const userApi = {
  portfolio: () => api.get<unknown, User>('/user/portfolio'),
  assetHistory: (days = 30) => api.get<unknown, AssetSnapshot[]>('/user/asset-history', { params: { days } }),
  assetRealtime: () => api.get<unknown, AssetSnapshot>('/user/asset-realtime'),
  categoryAverages: (days = 30) => api.get<unknown, CategoryAverages>('/user/category-averages', { params: { days } }),
  // 重置账户：清空交易与游戏数据回到初始资金，每周一次，需逐字输入用户名确认
  resetAccount: (confirmUsername: string) =>
    api.post<unknown, void>('/user/reset', { confirmUsername }),
};

// ========== 钱包划转（余额钱包 ⇌ 游戏钱包） ==========
export const walletApi = {
  transfer: (direction: 'TO_GAME' | 'TO_BALANCE', amount: number) =>
    api.post<unknown, { balance: number; gameBalance: number }>('/wallet/transfer', { direction, amount }),
  // 划转预检：余额→游戏 有全仓敞口时算净值/新强平价/最大可转
  transferPreview: (direction: 'TO_GAME' | 'TO_BALANCE', amount: number) =>
    api.get<unknown, WalletTransferPreview>('/wallet/transfer/preview', { params: { direction, amount } }),
};

// ========== 排行榜接口 ==========
export const rankingApi = {
  // 获取排行榜
  list: () => api.get<unknown, RankingItem[]>('/ranking'),
};

// ========== 留言板 ==========
export const commentApi = {
  /** 根评论分页（时间倒序），每条带 childCount + 最多 2 条子评论预览 + 当前用户是否表过态 */
  list: (page = 1, size = 20) =>
    api.get<unknown, CommentItem[]>('/comments', { params: { page, size } }),
  children: (rootId: number, page = 1, size = 10) =>
    api.get<unknown, CommentItem[]>(`/comments/${rootId}/children`, { params: { page, size } }),
  /** 聚焦视图：给一条评论ID，返回它所属的根评论 + 该根下全部子评论。通知跳转靠它，不用算分页位置 */
  context: (commentId: number) =>
    api.get<unknown, CommentItem>(`/comments/context/${commentId}`),
  /** 回复时 rootId 传所属根评论ID（不是被回复的那条子评论ID），replyToUserId 传被回复者 */
  post: (content: string, rootId?: number, replyToUserId?: number) =>
    api.post<unknown, number>('/comments', { content, rootId, replyToUserId }),
  vote: (id: number, type: 'like' | 'dislike') =>
    api.post<unknown, void>(`/comments/${id}/vote`, null, { params: { type } }),
  remove: (id: number) => api.delete<unknown, void>(`/comments/${id}`),
  /** 管理员禁言，days=-1 为永久 */
  mute: (userId: number, days: number) =>
    api.post<unknown, void>('/comments/mute', { userId, days }),
};

// ========== 评论通知 ==========
export const notificationApi = {
  /** 最近 50 条（含已读），前端按 type+commentId 合并后展示 */
  recent: () => api.get<unknown, NotificationItem[]>('/notifications'),
  unread: () => api.get<unknown, number>('/notifications/unread'),
  readAll: () => api.post<unknown, void>('/notifications/read-all'),
};

// ========== 管理接口 ==========
export const adminApi = {
  bankruptcyCheck: () => api.post<unknown, void>('/admin/task/bankruptcy/check'),
  accrueInterest: () => api.post<unknown, void>('/admin/task/margin/accrue-interest'),
  getDailyInterestRate: () => api.get<unknown, number>('/admin/task/margin/daily-interest-rate'),
  setDailyInterestRate: (dailyInterestRate: number) =>
    api.post<unknown, number>('/admin/task/margin/daily-interest-rate', { dailyInterestRate }),
  assetSnapshot: () => api.post<unknown, void>('/admin/task/asset-snapshot'),
  // AI Key管理
  listAiKeys: () => api.get<unknown, AiKeyConfig[]>('/admin/ai-agent/keys'),
  saveAiKey: (key: AiKeyConfig) => api.post<unknown, AiKeyConfig>('/admin/ai-agent/keys', key),
  deleteAiKey: (id: number) => api.delete<unknown, void>(`/admin/ai-agent/keys/${id}`),
  // 模型分配
  listAssignments: () => api.get<unknown, AiModelAssignment[]>('/admin/ai-agent/assignments'),
  saveAssignments: (assignments: AiModelAssignment[]) =>
    api.post<unknown, void>('/admin/ai-agent/assignments', assignments),
  triggerQuant: (symbol: string) =>
    api.post<unknown, string>('/admin/ai-agent/quant/trigger', null, { params: { symbol } }),
  triggerQuantVerification: (symbol: string) =>
    api.post<unknown, string>('/admin/ai-agent/quant/verify/trigger', null, { params: { symbol } }),
  // feed WS 流健康：进面板拉快照 + 手动重试（实时更新走 STOMP /topic/feed/streams）
  feedStreams: () => api.get<unknown, FeedStreamHealth[]>('/monitor/streams'),
  retryFeedStream: (name: string) =>
    api.post<unknown, { ok: boolean; name: string }>(`/monitor/streams/${encodeURIComponent(name)}/retry`),
  // 邀请码管理
  listInviteCodes: () => api.get<unknown, InviteCode[]>('/admin/invite-code/list'),
  generateInviteCodes: (maxUses: number, count: number) =>
    api.post<unknown, InviteCode[]>('/admin/invite-code/generate', { maxUses, count }),
  disableInviteCode: (id: number) => api.post<unknown, void>(`/admin/invite-code/${id}/disable`),
};

// ========== Buff接口 ==========
export const buffApi = {
  // 获取Buff状态
  status: () => api.get<unknown, BuffStatus>('/buff/status'),
  // 抽奖
  draw: () => api.post<unknown, UserBuff>('/buff/draw'),
};

// ========== Blackjack接口 ==========
export const blackjackApi = {
  status: () => api.get<unknown, BlackjackStatus>('/blackjack/status'),
  bet: (amount: number) => api.post<unknown, GameState>('/blackjack/bet', { amount }),
  hit: () => api.post<unknown, GameState>('/blackjack/hit'),
  stand: () => api.post<unknown, GameState>('/blackjack/stand'),
  double: () => api.post<unknown, GameState>('/blackjack/double'),
  split: () => api.post<unknown, GameState>('/blackjack/split'),
  insurance: () => api.post<unknown, GameState>('/blackjack/insurance'),
  forfeit: () => api.post<unknown, GameState>('/blackjack/forfeit'),
  convert: (amount: number) => api.post<unknown, ConvertResult>('/blackjack/convert', { amount }),
};

// ========== 矿工游戏接口 ==========
export const minesApi = {
  status: () => api.get<unknown, MinesStatus>('/mines/status'),
  bet: (amount: number) => api.post<unknown, MinesGameState>('/mines/bet', { amount }),
  reveal: (cell: number) => api.post<unknown, MinesGameState>('/mines/reveal', { cell }),
  cashout: () => api.post<unknown, MinesGameState>('/mines/cashout'),
};

// ========== 视频扑克接口 ==========
export const videoPokerApi = {
  status: () => api.get<unknown, VideoPokerStatus>('/videopoker/status'),
  bet: (amount: number) => api.post<unknown, VideoPokerGameState>('/videopoker/bet', { amount }),
  draw: (held: number[]) => api.post<unknown, VideoPokerGameState>('/videopoker/draw', { held }),
};

// ========== 加密货币行情接口 ==========
const getToken = (): string | undefined => {
  const stored = localStorage.getItem('wiib-user');
  if (!stored) return undefined;
  try { return JSON.parse(stored).state?.token; } catch { return undefined; }
};

type ChatStreamEvent = {
  event: string;
  data: string;
};

const parseChatStreamEvent = (block: string): ChatStreamEvent => {
  let event = 'message';
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue;
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
      continue;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  return { event, data: dataLines.join('\n') };
};

// K线数据（直接返回Binance原始数组，不走拦截器解包）；现货/合约仅路径不同
const rawKlines = (path: string) =>
  (symbol = 'BTCUSDT', interval = '1m', limit = 500, endTime?: number) => {
    const token = getToken();
    return axios.get<number[][]>(path, {
      params: { symbol, interval, limit, ...(endTime ? { endTime } : {}) },
      ...(token ? { headers: { satoken: token } } : {}),
    }).then(res => res.data);
  };

export const cryptoApi = {
  klines: rawKlines('/api/crypto/klines'),
  // 最新价格
  price: (symbol = 'BTCUSDT') => api.get<unknown, CryptoPrice>('/crypto/price', { params: { symbol } }),
};

// ========== 加密货币交易接口 ==========
export const cryptoOrderApi = {
  buy: (data: CryptoOrderRequest) => api.post<unknown, CryptoOrder>('/crypto/order/buy', data),
  sell: (data: CryptoOrderRequest) => api.post<unknown, CryptoOrder>('/crypto/order/sell', data),
  cancel: (orderId: number) => api.post<unknown, CryptoOrder>(`/crypto/order/cancel/${orderId}`),
  list: (status?: string, pageNum = 1, pageSize = 10, symbol = 'BTCUSDT') =>
    api.get<unknown, PageResult<CryptoOrder>>('/crypto/order/list', { params: { status, pageNum, pageSize, symbol } }),
  position: (symbol = 'BTCUSDT') => api.get<unknown, CryptoPosition | null>('/crypto/order/position', { params: { symbol } }),
  positions: () => api.get<unknown, CryptoPosition[]>('/crypto/order/positions'),
  live: () => api.get<unknown, CryptoOrder[]>('/crypto/order/live'),
};

// ========== bStock（代币化美股）接口 ==========
// 行情走真实 Binance 现货；交易复用现货引擎（仅现货：市价/限价买卖 + 杠杆借款）
export const bstockApi = {
  list: () => api.get<unknown, BStock[]>('/bstock/list'),
  detail: (symbol: string) => api.get<unknown, BStock>(`/bstock/${symbol}`),
  price: (symbol: string) => api.get<unknown, number>('/bstock/price', { params: { symbol } }),
  klines: rawKlines('/api/bstock/klines'),
  buy: (data: CryptoOrderRequest) => api.post<unknown, CryptoOrder>('/bstock/order/buy', data),
  sell: (data: CryptoOrderRequest) => api.post<unknown, CryptoOrder>('/bstock/order/sell', data),
  cancel: (orderId: number) => api.post<unknown, CryptoOrder>(`/bstock/order/cancel/${orderId}`),
  orders: (status?: string, pageNum = 1, pageSize = 10, symbol?: string) =>
    api.get<unknown, PageResult<CryptoOrder>>('/bstock/order/list', { params: { status, pageNum, pageSize, symbol } }),
  positions: () => api.get<unknown, CryptoPosition[]>('/bstock/order/positions'),
};

// ========== 永续合约接口 ==========
export const futuresApi = {
  klines: rawKlines('/api/futures/klines'),
  open: (data: FuturesOpenRequest) => api.post<unknown, FuturesOrder>('/futures/open', data),
  close: (data: FuturesCloseRequest) => api.post<unknown, FuturesOrder>('/futures/close', data),
  closeAll: () => api.post<unknown, { closedCount: number; failures: string[] }>('/futures/close-all'),
  cancel: (orderId: number) => api.post<unknown, FuturesOrder>(`/futures/cancel/${orderId}`),
  addMargin: (data: FuturesAddMarginRequest) => api.post<unknown, void>('/futures/margin', data),
  reduceMargin: (data: FuturesReduceMarginRequest) => api.post<unknown, void>('/futures/margin/reduce', data),
  // 持仓调杠杆：全仓双向可调（调低要可用够），逐仓只能调高
  adjustLeverage: (data: FuturesAdjustLeverageRequest) => api.post<unknown, void>('/futures/leverage', data),
  // 全仓账户概览：净值/可用/占用/维持保证金
  crossAccount: () => api.get<unknown, FuturesCrossAccount>('/futures/cross-account'),
  brackets: () => api.get<unknown, Record<string, FuturesBracket[]>>('/futures/brackets'),
  increase: (data: FuturesIncreaseRequest) => api.post<unknown, FuturesOrder>('/futures/increase', data),
  setStopLoss: (data: FuturesStopLossRequest) => api.post<unknown, void>('/futures/stop-loss', data),
  setTakeProfit: (data: FuturesTakeProfitRequest) => api.post<unknown, void>('/futures/take-profit', data),
  positions: (symbol?: string) => api.get<unknown, FuturesPosition[]>('/futures/positions', { params: { symbol } }),
  orders: (status?: string, pageNum = 1, pageSize = 10, symbol?: string) =>
    api.get<unknown, PageResult<FuturesOrder>>('/futures/orders', { params: { status, pageNum, pageSize, symbol } }),
  live: () => api.get<unknown, FuturesOrder[]>('/futures/live'),
  forceOrders: (symbol?: string, pageNum = 1, pageSize = 20) =>
    api.get<unknown, PageResult<ForceOrder>>('/futures/force-orders', { params: { symbol, pageNum, pageSize } }),
};

// ========== BTC 5min 涨跌预测接口 ==========
export const predictionApi = {
  current: () => api.get<unknown, PredictionRound>('/prediction/current'),
  buy: (data: PredictionBuyRequest) => api.post<unknown, PredictionBet>('/prediction/buy', data),
  sell: (betId: number, contracts?: number) =>
    api.post<unknown, PredictionBet>(`/prediction/sell/${betId}`, undefined, { params: contracts ? { contracts } : undefined }),
  bets: (pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<PredictionBet>>('/prediction/bets', { params: { pageNum, pageSize } }),
  rounds: (pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<PredictionRound>>('/prediction/rounds', { params: { pageNum, pageSize } }),
  live: () => api.get<unknown, PredictionBetLive[]>('/prediction/live'),
  priceHistory: () => api.get<unknown, { time: number; price: string }[]>('/prediction/price-history'),
  pnl: () => api.get<unknown, PredictionPnl>('/prediction/pnl'),
};

// ========== AI Agent 接口 ==========
export const aiAgentApi = {
  analyzeBehavior: () =>
    api.post<unknown, BehaviorAnalysisReport>('/ai/analyze-behavior'),
};

// ========== P7 研判工作台 ==========
/** 工作台 SSE：POST /ai/workbench/chat，named events 逐个回调（session/agent_start/token/hitl_request/done/error） */
const streamWorkbenchEvents = async (
  response: Response,
  onEvent: (e: WorkbenchEvent) => void,
) => {
  if (!response.body) throw new Error('响应流不可用');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = (rawEvent: string) => {
    const { event, data } = parseChatStreamEvent(rawEvent);
    if (!data) return;
    try {
      const payload = JSON.parse(data);
      onEvent({ type: event, ...payload } as WorkbenchEvent);
    } catch {
      // 非 JSON 数据块（心跳等）忽略
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r/g, '');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const rawEvent = buffer.slice(0, boundary).trim();
      buffer = buffer.slice(boundary + 2);
      if (rawEvent) dispatch(rawEvent);
      boundary = buffer.indexOf('\n\n');
    }
    if (done) {
      if (buffer.trim()) dispatch(buffer.trim());
      return;
    }
  }
};

export const workbenchApi = {
  chat: async (
    sessionId: string | null,
    message: string,
    onEvent: (e: WorkbenchEvent) => void,
    signal?: AbortSignal,
  ) => {
    const token = getToken();
    const response = await fetch('/api/ai/workbench/chat', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { satoken: token } : {}),
      },
      body: JSON.stringify({ sessionId, message }),
      signal,
    });
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      const payload = await response.json() as { code?: number; msg?: string };
      throw new Error(payload.msg || '请求失败');
    }
    if (!response.ok) throw new Error(`请求失败: ${response.status}`);
    await streamWorkbenchEvents(response, onEvent);
  },
  approve: (sessionId: string, approved: boolean) =>
    api.post<unknown, void>('/ai/workbench/approve', { sessionId, approved }),
  /** 历史会话列表（标题=首条提问，按最后活跃倒序） */
  sessions: () => api.get<unknown, WorkbenchSessionSummary[]>('/ai/workbench/sessions'),
  /** 单会话消息记录；续聊仍走 chat 带同一 sessionId */
  sessionMessages: (sessionId: string) =>
    api.get<unknown, WorkbenchChatMessage[]>(`/ai/workbench/sessions/${sessionId}/messages`),
  /** 删除历史会话（展示记录 + 后端 checkpoint 上下文） */
  deleteSession: (sessionId: string) =>
    api.delete<unknown, void>(`/ai/workbench/sessions/${sessionId}`),
};

export const quantApi = {
  latestSnapshot: (symbol?: string) =>
    api.get<unknown, QuantSnapshotView>('/ai/quant/snapshots/latest', { params: { symbol: symbol || 'BTCUSDT' } }),
  snapshotSeries: (symbol?: string, hours = 24) =>
    api.get<unknown, QuantSnapshotSeriesPoint[]>('/ai/quant/snapshots/series', { params: { symbol: symbol || 'BTCUSDT', hours } }),
  latestAnalysis: (symbol?: string) =>
    api.get<unknown, QuantDeepAnalysisView>('/ai/quant/analysis/latest', { params: { symbol: symbol || 'BTCUSDT' } }),
  analysisList: (symbol?: string, limit = 20) =>
    api.get<unknown, QuantDeepAnalysisView[]>('/ai/quant/analysis/list', { params: { symbol: symbol || 'BTCUSDT', limit } }),
  scorecard: (symbol?: string, days = 7) =>
    api.get<unknown, Scorecard>('/ai/quant/scorecard', { params: { symbol: symbol || 'BTCUSDT', days } }),
};

// ========== 策略账户监控 ==========
export const strategyAccountApi = {
  overview: () => api.get<unknown, StrategyAccountView[]>('/ai/strategies/overview'),
  /** 各策略×币种实时信号状态（通道位置/压缩计数/签名命中） */
  signals: () => api.get<unknown, StrategySignalState[]>('/ai/strategies/signals'),
  /** 整仓市价平（后端仅 userId=1 放行） */
  close: (strategyId: string, positionId: number) =>
    api.post<unknown, void>(`/ai/strategies/${strategyId}/close`, { positionId }),
};

// ========== Testnet 模拟盘监测 ==========
export const testnetApi = {
  overview: () => api.get<unknown, TnOverview>('/testnet/overview'),
  trades: (symbol?: string, days = 30) =>
    api.get<unknown, TnTrade[]>('/testnet/trades', { params: { symbol, days } }),
  dailyGrid: (symbol?: string, days = 90) =>
    api.get<unknown, TnDailyCell[]>('/testnet/daily-grid', { params: { symbol, days } }),
  equity: (symbol?: string, days = 90) =>
    api.get<unknown, TnEquityPoint[]>('/testnet/equity', { params: { symbol, days } }),
  fillStats: (symbol?: string, days = 30) =>
    api.get<unknown, TnFillStats>('/testnet/fill-stats', { params: { symbol, days } }),
  // 手动交易（接口自检，后端 admin 门控）
  manualOrder: (req: TnManualOrderReq) =>
    api.post<unknown, TnOrderResult>('/testnet/manual/order', req),
  manualClose: (symbol: string) =>
    api.post<unknown, TnOrderResult>('/testnet/manual/close', null, { params: { symbol } }),
  manualCancelAll: (symbol: string) =>
    api.post<unknown, TnAck>('/testnet/manual/cancel-all', null, { params: { symbol } }),
};

