import axios from 'axios';
import type { TnOverview, TnTrade, TnDailyCell, TnEquityPoint, TnFillStats, TnManualOrderReq, TnOrderResult, TnAck } from '../types/testnet';
import type { User, PageResult, RankingItem, BuffStatus, UserBuff, BlackjackStatus, GameState, ConvertResult, MinesStatus, MinesGameState, VideoPokerStatus, VideoPokerGameState, CryptoPrice, CryptoOrderRequest, CryptoOrder, CryptoPosition, BStock, FuturesOpenRequest, FuturesCloseRequest, FuturesAddMarginRequest, FuturesReduceMarginRequest, FuturesIncreaseRequest, FuturesStopLossRequest, FuturesTakeProfitRequest, FuturesPosition, FuturesOrder, FuturesBracket, PredictionRound, PredictionBet, PredictionBuyRequest, PredictionBetLive, PredictionPnl, AssetSnapshot, CategoryAverages, BehaviorAnalysisReport, ForceOrder, AiKeyConfig, AiModelAssignment, GraphNodeMetric, WorkbenchEvent, QuantSnapshotView, QuantSnapshotSeriesPoint, QuantDeepAnalysisView, Scorecard, StrategyAccountView, FeedStreamHealth } from '../types';

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
  // 登录模式：linuxDoEnabled=false 时走管理员直登
  mode: () => api.get<unknown, { linuxDoEnabled: boolean }>('/auth/mode'),
  // LinuxDo OAuth回调
  linuxDoCallback: (code: string) => api.get<unknown, string>('/auth/callback/linuxdo', { params: { code } }),
  // 管理员直登（仅未配置 LinuxDo 时可用）
  localLogin: () => api.post<unknown, string>('/auth/login/local'),
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
};

// ========== 排行榜接口 ==========
export const rankingApi = {
  // 获取排行榜
  list: () => api.get<unknown, RankingItem[]>('/ranking'),
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
  cancel: (orderId: number) => api.post<unknown, FuturesOrder>(`/futures/cancel/${orderId}`),
  addMargin: (data: FuturesAddMarginRequest) => api.post<unknown, void>('/futures/margin', data),
  reduceMargin: (data: FuturesReduceMarginRequest) => api.post<unknown, void>('/futures/margin/reduce', data),
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

// ========== Graph 观测接口 ==========
export const graphObsApi = {
  metrics: () => api.get<unknown, GraphNodeMetric[]>('/admin/graph-obs/metrics'),
};
