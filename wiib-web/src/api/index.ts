import axios from 'axios';
import type { Stock, User, Position, OrderRequest, Order, DayTick, Kline, Settlement, PageResult, News, RankingItem, OptionChainItem, OptionQuote, OptionPosition, OptionOrder, OptionOrderRequest, OptionOrderResult, BuffStatus, UserBuff, BlackjackStatus, GameState, ConvertResult, MinesStatus, MinesGameState, VideoPokerStatus, VideoPokerGameState, CryptoPrice, CryptoOrderRequest, CryptoOrder, CryptoPosition, FuturesOpenRequest, FuturesCloseRequest, FuturesAddMarginRequest, FuturesIncreaseRequest, FuturesStopLossRequest, FuturesTakeProfitRequest, FuturesPosition, FuturesOrder, PredictionRound, PredictionBet, PredictionBuyRequest, PredictionBetLive, PredictionPnl, AssetSnapshot, CategoryAverages, BehaviorAnalysisReport, CryptoAnalysisReport, QuantLatestSignal, QuantForecastCycle, QuantVerificationSummary, GroupedVerificationSummary, ForceOrder, AiKeyConfig, AiModelAssignment, LatestCryptoResult, AiTradingDashboard, AiTradingDecision, TradingRuntimeConfig, QuantRuntimeConfig, SprintCDashboard } from '../types';

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
});

type ChatHistoryItem = { role: string; content: string };

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
  // LinuxDo OAuth回调
  linuxDoCallback: (code: string) => api.get<unknown, string>('/auth/callback/linuxdo', { params: { code } }),
  // 获取当前用户信息
  current: () => api.get<unknown, User>('/auth/current'),
  // 退出登录
  logout: () => api.post<unknown, void>('/auth/logout'),
};

// ========== 股票接口 ==========
export const stockApi = {
  // 获取所有股票列表
  list: () => api.get<unknown, Stock[]>('/stock/list'),
  // 分页查询股票列表
  page: (pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<Stock>>('/stock/page', { params: { pageNum, pageSize } }),
  // 获取股票详情
  detail: (id: number) => api.get<unknown, Stock>(`/stock/${id}`),
  // 获取涨幅榜
  gainers: (limit = 10) => api.get<unknown, Stock[]>('/stock/gainers', { params: { limit } }),
  // 获取跌幅榜
  losers: (limit = 10) => api.get<unknown, Stock[]>('/stock/losers', { params: { limit } }),
  // 获取当日分时数据
  ticks: (stockId: number) => api.get<unknown, DayTick[]>(`/stock/${stockId}/ticks`),
  // 获取历史某天分时数据
  historyTicks: (stockId: number, date: string, signal?: AbortSignal) =>
    api.get<unknown, DayTick[]>(`/stock/${stockId}/history-ticks`, { params: { date }, signal }),
  // 获取日K线
  kline: (stockId: number, days = 30) =>
    api.get<unknown, Kline[]>(`/stock/${stockId}/kline`, { params: { days } }),
};

// ========== 订单接口 ==========
export const orderApi = {
  // 买入下单（市价/限价）
  buy: (data: OrderRequest) => api.post<unknown, Order>('/order/buy', data),
  // 卖出下单（市价/限价）
  sell: (data: OrderRequest) => api.post<unknown, Order>('/order/sell', data),
  // 取消订单
  cancel: (orderId: number) => api.post<unknown, Order>(`/order/cancel/${orderId}`),
  // 查询订单列表（分页）
  list: (status?: string, pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<Order>>('/order/list', { params: { status, pageNum, pageSize } }),
  live: () => api.get<unknown, Order[]>('/order/live'),
};

// ========== 用户接口 ==========
export const userApi = {
  portfolio: () => api.get<unknown, User>('/user/portfolio'),
  positions: () => api.get<unknown, Position[]>('/user/positions'),
  assetHistory: (days = 30) => api.get<unknown, AssetSnapshot[]>('/user/asset-history', { params: { days } }),
  assetRealtime: () => api.get<unknown, AssetSnapshot>('/user/asset-realtime'),
  categoryAverages: (days = 30) => api.get<unknown, CategoryAverages>('/user/category-averages', { params: { days } }),
};

// ========== 结算接口 ==========
export const settlementApi = {
  // 获取待结算列表
  pending: () => api.get<unknown, Settlement[]>('/settlement/pending'),
};

// ========== 新闻接口 ==========
export const newsApi = {
  // 获取股票相关新闻（按日期）
  byStock: (stockCode: string, date?: string) =>
    api.get<unknown, News[]>(`/news/stock/${stockCode}`, { params: { date } }),
};

// ========== 排行榜接口 ==========
export const rankingApi = {
  // 获取排行榜
  list: () => api.get<unknown, RankingItem[]>('/ranking'),
};

// ========== 管理接口 ==========
export interface TaskStatus {
  marketDataTask: boolean;
  orderExecutionTask: boolean;
  settlementTask: boolean;
  rankingTask: boolean;
  isTradingTime: boolean;
  currentTickIndex: number;
}

export interface RefreshStockCacheResult {
  date: string;
  time: string;
  updated: number;
  skipped: number;
}

export const adminApi = {
  taskStatus: () => api.get<unknown, TaskStatus>('/admin/task/status'),
  startMarketPush: () => api.post<unknown, void>('/admin/task/market-push/start'),
  stopMarketPush: () => api.post<unknown, void>('/admin/task/market-push/stop'),
  startSettlement: () => api.post<unknown, void>('/admin/task/settlement/start'),
  stopSettlement: () => api.post<unknown, void>('/admin/task/settlement/stop'),
  expireOrders: () => api.post<unknown, void>('/admin/task/expire-orders'),
  generateData: (offset = 1) =>
    api.post<unknown, void>('/admin/task/generate-data', null, { params: { offset } }),
  loadRedis: () => api.post<unknown, void>('/admin/task/load-redis'),
  refreshStockCache: () => api.post<unknown, RefreshStockCacheResult>('/admin/task/refresh-stock-cache'),
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
  triggerAiTrader: (symbol?: string) =>
    api.post<unknown, string>('/admin/ai-agent/trading/trigger', null, { params: { symbol } }),
  // 交易运行时开关
  getTradingConfig: () =>
    api.get<unknown, TradingRuntimeConfig>('/admin/ai-agent/trading-config'),
  setTradingConfig: (config: TradingRuntimeConfig) =>
    api.post<unknown, TradingRuntimeConfig>('/admin/ai-agent/trading-config', config),
  // 量化运行时开关
  getQuantConfig: () =>
    api.get<unknown, QuantRuntimeConfig>('/admin/ai-agent/quant-config'),
  setQuantConfig: (config: QuantRuntimeConfig) =>
    api.post<unknown, QuantRuntimeConfig>('/admin/ai-agent/quant-config', config),
  sprintCDashboard: (days = 7) =>
    api.get<unknown, SprintCDashboard>('/admin/sprint-c-dashboard', { params: { days } }),
};

// ========== 期权接口 ==========
export const optionApi = {
  // 获取期权链
  chain: (stockId: number) => api.get<unknown, OptionChainItem[]>(`/option/chain/${stockId}`),
  // 获取期权报价
  quote: (contractId: number) => api.get<unknown, OptionQuote>(`/option/quote/${contractId}`),
  // 买入开仓
  buy: (data: OptionOrderRequest) => api.post<unknown, OptionOrderResult>('/option/buy', data),
  // 卖出平仓
  sell: (data: OptionOrderRequest) => api.post<unknown, OptionOrderResult>('/option/sell', data),
  // 获取持仓
  positions: () => api.get<unknown, OptionPosition[]>('/option/positions'),
  // 获取订单
  orders: (status?: string, pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<OptionOrder>>('/option/orders', { params: { status, pageNum, pageSize } }),
  // 手动触发到期期权结算
  processExpirySettlement: () => api.post<unknown, void>('/option/settlement/process'),
  // 生成期权链（管理接口）
  generateChain: (stockId: number, steps = 5) =>
    api.post<unknown, unknown>(`/option/generate-chain/${stockId}`, null, { params: { steps } }),
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

const streamChatResponse = async (
  response: Response,
  onChunk: (chunk: string) => void,
) => {
  if (!response.body) {
    throw new Error('响应流不可用');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r/g, '');

    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const rawEvent = buffer.slice(0, boundary).trim();
      buffer = buffer.slice(boundary + 2);
      if (rawEvent) {
        const event = parseChatStreamEvent(rawEvent);
        if (event.event === 'chunk' && event.data) {
          onChunk(event.data);
        } else if (event.event === 'error') {
          throw new Error(event.data || '回答失败');
        } else if (event.event === 'done') {
          return;
        }
      }
      boundary = buffer.indexOf('\n\n');
    }

    if (done) {
      if (buffer.trim()) {
        const event = parseChatStreamEvent(buffer.trim());
        if (event.event === 'chunk' && event.data) {
          onChunk(event.data);
        } else if (event.event === 'error') {
          throw new Error(event.data || '回答失败');
        }
      }
      return;
    }
  }
};

export const cryptoApi = {
  // K线数据（直接返回Binance原始数组，不走拦截器解包）
  klines: (symbol = 'BTCUSDT', interval = '1m', limit = 500, endTime?: number) => {
    const token = getToken();
    return axios.get<number[][]>(`/api/crypto/klines`, {
      params: { symbol, interval, limit, ...(endTime ? { endTime } : {}) },
      ...(token ? { headers: { satoken: token } } : {}),
    }).then(res => res.data);
  },
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

// ========== 永续合约接口 ==========
export const futuresApi = {
  klines: (symbol = 'BTCUSDT', interval = '1m', limit = 500, endTime?: number) => {
    const token = getToken();
    return axios.get<number[][]>(`/api/futures/klines`, {
      params: { symbol, interval, limit, ...(endTime ? { endTime } : {}) },
      ...(token ? { headers: { satoken: token } } : {}),
    }).then(res => res.data);
  },
  open: (data: FuturesOpenRequest) => api.post<unknown, FuturesOrder>('/futures/open', data),
  close: (data: FuturesCloseRequest) => api.post<unknown, FuturesOrder>('/futures/close', data),
  cancel: (orderId: number) => api.post<unknown, FuturesOrder>(`/futures/cancel/${orderId}`),
  addMargin: (data: FuturesAddMarginRequest) => api.post<unknown, void>('/futures/margin', data),
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
  analyzeCrypto: (symbol?: string) => api.post<unknown, CryptoAnalysisReport>('/ai/analyze-crypto', symbol ? { symbol } : {}),
  latestCryptoReport: (symbol?: string) => api.get<unknown, LatestCryptoResult>('/ai/analyze-crypto/latest', { params: { symbol: symbol || 'BTCUSDT' } }),
  latestSignals: (symbol?: string) => api.get<unknown, QuantLatestSignal>('/ai/quant/signals/latest', { params: { symbol: symbol || 'BTCUSDT' } }),
  forecasts: (symbol?: string, limit = 20) => api.get<unknown, QuantForecastCycle[]>('/ai/quant/forecasts', { params: { symbol: symbol || 'BTCUSDT', limit } }),
  verifications: (symbol?: string, limit = 10) => api.get<unknown, QuantVerificationSummary>('/ai/quant/verifications', { params: { symbol: symbol || 'BTCUSDT', limit } }),
  groupedVerifications: (symbol?: string, limit = 10) => api.get<unknown, GroupedVerificationSummary>('/ai/quant/verifications/grouped', { params: { symbol: symbol || 'BTCUSDT', limit } }),
  chat: async (
    message: string,
    context: string,
    history: ChatHistoryItem[],
    onChunk: (chunk: string) => void,
    signal?: AbortSignal,
  ) => {
    const token = getToken();
    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { satoken: token } : {}),
      },
      body: JSON.stringify({ message, context, history }),
      signal,
    });

    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      const payload = await response.json() as { code?: number; msg?: string };
      throw new Error(payload.msg || '请求失败');
    }
    if (!response.ok) {
      throw new Error(`请求失败: ${response.status}`);
    }

    await streamChatResponse(response, onChunk);
  },
};

// ========== AI Trading 接口 ==========
export const aiTradingApi = {
  dashboard: () => api.get<unknown, AiTradingDashboard>('/ai/trading/dashboard'),
  decisions: (symbol?: string, limit = 20) =>
    api.get<unknown, AiTradingDecision[]>('/ai/trading/decisions', { params: { symbol, limit } }),
  positions: (symbol?: string) =>
    api.get<unknown, FuturesPosition[]>('/ai/trading/positions', { params: { symbol } }),
  orders: (symbol?: string, pageNum = 1, pageSize = 20) =>
    api.get<unknown, PageResult<FuturesOrder>>('/ai/trading/orders', { params: { symbol, pageNum, pageSize } }),
};
