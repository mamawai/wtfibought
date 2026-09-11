import axios from 'axios';
import i18n, { currentLang, type Lang } from '../i18n';
import type { TnOverview, TnTrade, TnDailyCell, TnEquityPoint, TnFillStats, TnManualOrderReq, TnOrderResult, TnAck } from '../types/testnet';
import type { BacktestTaskStatus, BacktestEventsPage, BacktestKlinesPage, BacktestResultPayload, ReplayCoverage, HistoryKlinesPayload, ReplayCoachRequest, ReplayCoachEvent } from '../types';
import type { LedgerEntry, LedgerBizTypeOption, PublicTrade, UserProfile, PositionHistoryItem, RankingSort } from '../types';
import type { CampaignInfo, CampaignReward, CampaignScore, MyCampaignView } from '../types';
import type { LlmEndpointView, LlmEndpointSaveRequest, LlmBindings, LlmPurpose } from '../types';
import type { User, PageResult, RankingItem, CommentItem, NotificationItem, BuffStatus, UserBuff, BlackjackStatus, GameState, ConvertResult, MinesStatus, MinesGameState, VideoPokerStatus, VideoPokerGameState, CryptoPrice, CryptoOrderRequest, CryptoOrder, CryptoPosition, BStock, FuturesOpenRequest, FuturesCloseRequest, FuturesAddMarginRequest, FuturesReduceMarginRequest, FuturesStopLossRequest, FuturesTakeProfitRequest, FuturesAdjustLeverageRequest, FuturesCrossAccount, WalletTransferPreview, FuturesPosition, FuturesOrder, FuturesReverseResult, FuturesBracket, FundingRateView, TradeFilterMap, PredictionRound, PredictionBet, PredictionBuyRequest, PredictionBetLive, PredictionPnl, AssetSnapshot, CategoryAverages, ForceOrder, AiKeyConfig, AiModelAssignment, InviteCode, ChatIntent, WorkbenchEvent, StrategyAccountView, TraderPublicView, TraderOwnerView, TraderDetailView, AiTraderDecisionView, TraderEquityPoint, TraderUpsertRequest, TraderSpec, StrategySignalState, FeedStreamHealth, WorkbenchSessionSummary, WorkbenchSessionStatus, WorkbenchChatMessage, TraderActionPanel, TraderActionResult, TradeRecordView, TraderLiveEvent, WakeTrace } from '../types';

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
});

/**
 * 业务错误：带上后端的 code，让调用方能按类型分流（比如"没配 key"要引导去配置）。
 * code 显式声明再赋值，不用构造器参数属性——本仓 tsconfig 开了 erasableSyntaxOnly，那个语法编译不过
 */
export class ApiError extends Error {
  readonly code: number;

  constructor(message: string, code: number) {
    super(message);
    this.code = code;
  }
}

/**
 * 界面语言头。后端的报错文案按它查词表现渲染（服务端 RequestLangFilter），
 * 未登录的那些报错（登录失败、邀请码无效）也照样有语言可依——user.lang 那时候还没得查。
 */
const LANG_HEADER = 'X-Lang';

// 请求拦截器：添加Token到Header
api.interceptors.request.use((config) => {
  config.headers[LANG_HEADER] = currentLang();
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
    // msg 是后端下发的，原样透传；只有后端没给话时才用词表兜底那半句
    if (code === 401) {
      // 带着 token 还 401 = token 过期/失效，清掉回登录页；
      // 游客本来没 token，401 只是这一个请求失败，不弹人
      if (res.config.headers['satoken']) {
        localStorage.removeItem('wiib-user');
        window.location.href = '/login';
      }
      return Promise.reject(new ApiError(msg || i18n.t('errors:unauthorized'), code));
    }
    if (code !== 0) {
      return Promise.reject(new ApiError(msg || i18n.t('errors:requestFailed'), code));
    }
    return data;
  },
  (err) => {
    const msg = err.response?.data?.msg || err.response?.data?.message || err.message;
    console.error('API错误:', msg);
    // 这条分支是 HTTP 层错误（网络断、5xx），响应体不一定是 Result，code 取不到就填 -1
    return Promise.reject(new ApiError(msg, err.response?.data?.code ?? -1));
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
  /** 指定月份逐日快照（首页月度盈亏网格）。month 形如 2026-07；快照只写到昨天，返回里没有今天 */
  assetDaily: (month: string) => api.get<unknown, AssetSnapshot[]>('/user/asset-daily', { params: { month } }),
  categoryAverages: (days = 30) => api.get<unknown, CategoryAverages>('/user/category-averages', { params: { days } }),
  // 重置账户：清空交易与游戏数据回到初始资金。活动期每周首次免费、之后每次扣 30 活动分；
  // 平时每周限 1 次。需逐字输入用户名确认
  resetAccount: (confirmUsername: string) =>
    api.post<unknown, void>('/user/reset', { confirmUsername }),
  /** 详情页公开开关（默认开）。关掉只挡别人看你的持仓与仓位历史，仍照常上排行榜 */
  getProfilePublic: () => api.get<unknown, boolean>('/user/profile-public'),
  setProfilePublic: (profilePublic: boolean) =>
    api.post<unknown, void>('/user/profile-public', { profilePublic }),
  /** agent 提示词语言（zh/en）：只在配置页改，与界面语言（localStorage）互不影响 */
  getLang: () => api.get<unknown, Lang>('/user/lang'),
  setLang: (lang: Lang) => api.put<unknown, void>('/user/lang', { lang }),
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
  /**
   * 排行榜分页。只含有过成交的用户——从没交易过的人挂着初始资金进榜，
   * 排出来是一串一模一样的 10000，把真在交易的人挤到后面。
   * sort 换维度时名次跟着重算，服务端认不出的取值退回 ASSETS。
   */
  list: (sort: RankingSort = 'ASSETS', pageNum = 1, pageSize = 20) =>
    api.get<unknown, PageResult<RankingItem>>('/ranking', { params: { sort, pageNum, pageSize } }),
  /**
   * 当前用户的榜单行，名次跟着 sort 维度走（与 list 同口径）。
   * 分页一次只给 20 条，自己在第几页无从得知，所以按 userId 单独直取。
   * 没上榜返回 null（不是错误）；未登录别调，会 401。
   */
  me: (sort: RankingSort = 'ASSETS') =>
    api.get<unknown, RankingItem | null>('/ranking/me', { params: { sort } }),
  /** 用户详情：榜单行 + 当前持仓。对方关了公开开关时 403（本人除外） */
  userProfile: (userId: number) => api.get<unknown, UserProfile>(`/ranking/users/${userId}`),
  /** 该用户的成交历史分页，同样过隐私门控 */
  userTrades: (userId: number, pageNum = 1, pageSize = 20) =>
    api.get<unknown, PageResult<PublicTrade>>(`/ranking/users/${userId}/trades`, { params: { pageNum, pageSize } }),
  /** 该用户的合约仓位历史分页。跟 userTrades 是两种粒度：那个一行一笔委托，这个一行一笔完整仓位 */
  userPositionHistory: (userId: number, pageNum = 1, pageSize = 20) =>
    api.get<unknown, PageResult<PositionHistoryItem>>(`/ranking/users/${userId}/position-history`, { params: { pageNum, pageSize } }),
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
  /** 编辑自己的评论，不限时。已自删的改不动 */
  edit: (id: number, content: string) =>
    api.put<unknown, void>(`/comments/${id}`, { content }),
  /** 删自己的变占位文案，管理员删别人的才真删——后端按操作对象分流，这里不区分 */
  remove: (id: number) => api.delete<unknown, void>(`/comments/${id}`),
  /** 管理员禁言，days=-1 为永久 */
  mute: (userId: number, days: number) =>
    api.post<unknown, void>('/comments/mute', { userId, days }),
};

// ========== 全站成交记录（匿名） ==========
export const publicTradeApi = {
  /**
   * 全站成交分页。交易者只给稳定假名，接口刻意不收 userId——
   * 收了就能枚举反查假名，匿名白做（后端有反射守卫钉着这条）。
   */
  list: (params: { symbol?: string; kind?: 'SPOT' | 'FUTURES'; pageNum?: number; pageSize?: number } = {}) =>
    api.get<unknown, PageResult<PublicTrade>>('/trades/public', { params }),
};

// ========== 资金账单 ==========
export const ledgerApi = {
  /**
   * 资金流水（游标翻页，id 倒序）。下一页把本页最后一条的 id 传回 beforeId。
   * 到底的判据是**返回空数组**——不能用"条数 < limit"，服务端把 limit 封顶到 100，
   * 传 500 时第一页就会被误判成到底。
   */
  list: (params: { bizType?: string; beforeId?: number; limit?: number } = {}) =>
    api.get<unknown, LedgerEntry[]>('/ledger', { params }),
  /** 筛选下拉选项（中文名+分组都在后端枚举里，前端不硬编码） */
  bizTypes: () => api.get<unknown, LedgerBizTypeOption[]>('/ledger/biz-types'),
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
  // LDC 活动结算：活动过了 end_at 后手动触发，幂等（已结算再点直接返回已有行数）。
  // 结算会把 status 翻成 SETTLING，用户端领取入口随之出现
  settleCampaign: () => api.post<unknown, number>('/campaign/settle'),
  // AI Key管理
  listAiKeys: () => api.get<unknown, AiKeyConfig[]>('/admin/ai-agent/keys'),
  saveAiKey: (key: AiKeyConfig) => api.post<unknown, AiKeyConfig>('/admin/ai-agent/keys', key),
  deleteAiKey: (id: number) => api.delete<unknown, void>(`/admin/ai-agent/keys/${id}`),
  // 模型分配
  listAssignments: () => api.get<unknown, AiModelAssignment[]>('/admin/ai-agent/assignments'),
  saveAssignments: (assignments: AiModelAssignment[]) =>
    api.post<unknown, void>('/admin/ai-agent/assignments', assignments),
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
  // 反手：市价全平该仓位并立刻反向开等量新仓；反向开仓失败不报错，结果里带 openError（此时已空仓）
  reverse: (positionId: number) => api.post<unknown, FuturesReverseResult>(`/futures/reverse/${positionId}`),
  cancel: (orderId: number) => api.post<unknown, FuturesOrder>(`/futures/cancel/${orderId}`),
  addMargin: (data: FuturesAddMarginRequest) => api.post<unknown, void>('/futures/margin', data),
  reduceMargin: (data: FuturesReduceMarginRequest) => api.post<unknown, void>('/futures/margin/reduce', data),
  // 币种级调杠杆：多空共用，同时作用于该币全部仓位；全仓双向可调（调低要可用够），逐仓只能调高
  adjustLeverage: (data: FuturesAdjustLeverageRequest) => api.post<unknown, void>('/futures/leverage', data),
  // 全仓账户概览：净值/可用/占用/维持保证金
  crossAccount: () => api.get<unknown, FuturesCrossAccount>('/futures/cross-account'),
  brackets: () => api.get<unknown, Record<string, FuturesBracket[]>>('/futures/brackets'),
  // 资金费率：后端只读结算点(0/8/16)写下的缓存，不回源交易所；无合约的标的返回 null
  fundingRate: (symbol: string) => api.get<unknown, FundingRateView | null>('/futures/funding-rate', { params: { symbol } }),
  // 交易过滤器（步长/最小数量/最小名义额，合约+现货两套，后端已按官方exchangeInfo刷新）
  tradeFilters: () => api.get<unknown, TradeFilterMap>('/futures/trade-filters'),
  setStopLoss: (data: FuturesStopLossRequest) => api.post<unknown, void>('/futures/stop-loss', data),
  setTakeProfit: (data: FuturesTakeProfitRequest) => api.post<unknown, void>('/futures/take-profit', data),
  positions: (symbol?: string) => api.get<unknown, FuturesPosition[]>('/futures/positions', { params: { symbol } }),
  /**
   * 仓位历史：自己已平/已强平的合约仓位，一行是一笔完整仓位（开仓到全部平掉），每行带成交明细。
   * 跟 orders 的区别是粒度——那个是一笔笔委托流水，这个把同一仓位的开仓、加仓、分批平仓合成一条。
   */
  positionHistory: (pageNum = 1, pageSize = 20, symbol?: string) =>
    api.get<unknown, PageResult<PositionHistoryItem>>('/futures/position-history', { params: { pageNum, pageSize, symbol } }),
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

// ========== P7 研判工作台 ==========
/** SSE 事件流解析：named events 逐个回调（心跳注释帧无 data，忽略）。工作台对话与复盘 AI 教练共用 */
const streamSseEvents = async <E,>(
  response: Response,
  onEvent: (e: E) => void,
) => {
  if (!response.body) throw new Error(i18n.t('errors:streamUnavailable'));
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = (rawEvent: string) => {
    const { event, data } = parseChatStreamEvent(rawEvent);
    if (!data) return;
    try {
      const payload = JSON.parse(data);
      onEvent({ type: event, ...payload } as E);
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

/** fetch 走 SSE 时的鉴权与语言头（satoken 只认 header 不认 cookie） */
const sseHeaders = (): Record<string, string> => {
  const token = getToken();
  return { [LANG_HEADER]: currentLang(), ...(token ? { satoken: token } : {}) };
};

/**
 * fetch 回来的响应交给 SSE 解析。准入失败时后端返回的是普通 JSON（Result），
 * 抛 ApiError 带 code 让调用方分流（2201/2202 引导去配置）；正常返回 event-stream 就逐事件回调。
 */
const consumeSse = async <E,>(response: Response, onEvent: (e: E) => void) => {
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    const payload = await response.json() as { code?: number; msg?: string };
    throw new ApiError(payload.msg || i18n.t('errors:requestFailed'), payload.code ?? -1);
  }
  if (!response.ok) throw new Error(i18n.t('errors:requestFailedStatus', { status: response.status }));
  await streamSseEvents<E>(response, onEvent);
};

/** POST 一个 JSON 请求、以 SSE 收流 */
const postSse = async <E,>(url: string, body: unknown, onEvent: (e: E) => void, signal?: AbortSignal) => {
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...sseHeaders() },
    body: JSON.stringify(body),
    signal,
  });
  await consumeSse<E>(response, onEvent);
};

/** GET 一条 SSE 流（订阅型：trader 现场/竞技场列表），断流由调用方重连 */
const getSse = async <E,>(url: string, onEvent: (e: E) => void, signal?: AbortSignal) => {
  const response = await fetch(url, { credentials: 'include', headers: sseHeaders(), signal });
  await consumeSse<E>(response, onEvent);
};

export const workbenchApi = {
  /**
   * 工作台 SSE：POST /ai/workbench/chat，事件 session/agent_start/token/hitl_request/done/error。
   * intent 只有功能按钮直发时带（后端据此跳过专家派发直奔对应工具）
   */
  chat: (sessionId: string | null, message: string, onEvent: (e: WorkbenchEvent) => void,
         signal?: AbortSignal, intent?: ChatIntent) =>
    postSse<WorkbenchEvent>('/api/ai/workbench/chat', { sessionId, message, intent }, onEvent, signal),
  /**
   * 重新生成会话最后一条回答：后端把模型侧上下文回退到那条提问之前，再用原提问重跑，
   * 事件协议与 chat 完全一致。回不去的会话（末尾不是答案/补答行/本轮压缩过）返回 2206。
   */
  regenerate: (sessionId: string, onEvent: (e: WorkbenchEvent) => void, signal?: AbortSignal) =>
    postSse<WorkbenchEvent>('/api/ai/workbench/regenerate', { sessionId }, onEvent, signal),
  /**
   * 中断在跑的这一轮：后端跑到下一个检查点收尾，半截答案照落库。
   * 返回 false=没有轮在跑（按钮点晚了），前端据此把按钮恢复原状。
   */
  cancel: (sessionId: string) =>
    api.post<unknown, boolean>('/ai/workbench/cancel', { sessionId }),
  /** requestId 从 hitl_request 事件原样回传：卡片被新请求覆盖后点它，服务端会拒掉 */
  approve: (sessionId: string, approved: boolean, requestId: string) =>
    api.post<unknown, void>('/ai/workbench/approve', { sessionId, approved, requestId }),
  /** 历史会话列表（标题=首条提问，按最后活跃倒序） */
  sessions: () => api.get<unknown, WorkbenchSessionSummary[]>('/ai/workbench/sessions'),
  /**
   * 补答轮：让位时交出去的专家批次由这一轮接回，事件协议与 chat 一致（标头是第一帧答案 token）。
   * 后端只在会话空闲时放行：占线回 2203（不做让位握手），没欠账回 2208
   */
  deferred: (sessionId: string, onEvent: (e: WorkbenchEvent) => void, signal?: AbortSignal) =>
    postSse<WorkbenchEvent>('/api/ai/workbench/deferred', { sessionId }, onEvent, signal),
  /** 会话运行状态（切页/刷新回来判断：还在跑→轮询；欠着补答→空闲时发起补答轮） */
  sessionStatus: (sessionId: string) =>
    api.get<unknown, WorkbenchSessionStatus>(`/ai/workbench/sessions/${sessionId}/status`),
  /** 单会话消息记录；续聊仍走 chat 带同一 sessionId */
  sessionMessages: (sessionId: string) =>
    api.get<unknown, WorkbenchChatMessage[]>(`/ai/workbench/sessions/${sessionId}/messages`),
  /** 删除历史会话（展示记录 + 后端续聊上下文） */
  deleteSession: (sessionId: string) =>
    api.delete<unknown, void>(`/ai/workbench/sessions/${sessionId}`),
  /** 清空全部历史会话：skipped=在跑或欠补答的那些，后端保留不删 */
  deleteAllSessions: () =>
    api.delete<unknown, { deleted: number; skipped: number }>('/ai/workbench/sessions'),
};

// ========== 用户 BYOK 端点库（AI 页「模型配置」；对话/交易员/复盘教练从中选） ==========
export const llmEndpointApi = {
  list: () => api.get<unknown, LlmEndpointView[]>('/ai/llm-endpoints'),
  create: (req: LlmEndpointSaveRequest) => api.post<unknown, void>('/ai/llm-endpoints', req),
  /** apiKey 传空=沿用已存的 */
  update: (id: number, req: LlmEndpointSaveRequest) => api.put<unknown, void>(`/ai/llm-endpoints/${id}`, req),
  remove: (id: number) => api.delete<unknown, void>(`/ai/llm-endpoints/${id}`),
  setDefault: (id: number) => api.post<unknown, void>(`/ai/llm-endpoints/${id}/default`),
  bindings: () => api.get<unknown, LlmBindings>('/ai/llm-endpoints/bindings'),
  /** endpointId 传 null=解绑（跟随默认） */
  bind: (purpose: LlmPurpose, endpointId: number | null) =>
    api.post<unknown, void>('/ai/llm-endpoints/bindings', { purpose, endpointId }),
  /** 拉模型清单：编辑已有端点时带 id，apiKey 留空=用它已存的 key */
  listModels: (req: LlmEndpointSaveRequest, id?: number) =>
    api.post<unknown, string[]>('/ai/llm-endpoints/models', req, { params: id != null ? { id } : {} }),
  /** 连通性探测：与保存分离，对应表单里的"测试连通性"按钮 */
  test: (req: LlmEndpointSaveRequest, id?: number) =>
    api.post<unknown, void>('/ai/llm-endpoints/test', req, { params: id != null ? { id } : {} }),
};

/** 快讯（news_event 存档行，首页快讯卡数据源） */
export interface NewsEventItem {
  id: number;
  title: string;
  content: string;
  /** 英文译文；空=没译成，英文界面不展示这条，不拿中文凑 */
  titleEn: string | null;
  contentEn: string | null;
  url: string;
  /** 发稿时刻 epoch 毫秒 */
  publishedAt: number;
}

/** 财经日历一条（TradingView 全球 High 级事件，标题是接口英文原文） */
export interface EconCalendarEvent {
  /** 公布/开始时刻 epoch 毫秒 */
  eventTime: number;
  /** ISO 国家码，如 US / EU */
  country: string;
  /** 影响的货币代码，如 USD */
  currency: string;
  title: string;
  /** 实际值显示文本；null=未公布或讲话/会议类无数值 */
  actual: string | null;
  forecast: string | null;
  previous: string | null;
}

/** 首页日历卡：已公布 / 即将公布两栏，都按时间正序 */
export interface EconCalendarView {
  past: EconCalendarEvent[];
  upcoming: EconCalendarEvent[];
}

export const quantApi = {
  /** 首页财经日历：过去 3 天已公布 / 未来一周即将公布各 6 条 */
  econCalendar: () => api.get<unknown, EconCalendarView>('/ai/quant/econ-calendar'),
  /** 财经日历事件：时间窗内全部（BTC K 线标记数据源） */
  econCalendarEvents: (from: number, to: number) =>
    api.get<unknown, EconCalendarEvent[]>('/ai/quant/econ-calendar/events', { params: { from, to } }),
  /**
   * 快讯（news_event 存档，中英两套一起到）。不带参＝最新 100 条；
   * from/to 都给＝该区间 [from, to) 内按发稿时间倒序最多 300 条（按天翻看用）
   */
  news: (from?: number, to?: number) =>
    api.get<unknown, NewsEventItem[]>('/ai/quant/news', { params: { from, to } }),
};

// ========== AI Trader 竞技场 ==========
export const traderApi = {
  mine: () => api.get<unknown, TraderOwnerView | null>('/ai/trader/mine'),
  /** 平台系统提示词预览（与唤醒组装同一份文本）：规格项多，走 POST 带 body */
  promptTemplate: (intervalCode: string, symbols: string, spec: TraderSpec, wakeWindow: string | null) =>
    api.post<unknown, string>('/ai/trader/prompt-template', { intervalCode, symbols, spec, wakeWindow }),
  create: (req: TraderUpsertRequest) => api.post<unknown, void>('/ai/trader', req),
  updateConfig: (req: TraderUpsertRequest) => api.put<unknown, void>('/ai/trader/config', req),
  start: () => api.post<unknown, void>('/ai/trader/start'),
  pause: () => api.post<unknown, void>('/ai/trader/pause'),
  /** carryNotes=false 不带入复盘/学习笔记（只清生效版本，历届存档保留） */
  reset: (carryNotes: boolean) => api.post<unknown, void>('/ai/trader/reset', { carryNotes }),
  /** 删 trader：决策/计划/每局 sim 子账户全物理删除，不可逆；confirmName 要与 trader 名字一字不差 */
  remove: (confirmName: string) =>
    api.delete<unknown, void>('/ai/trader', { params: { confirmName } }),
  arena: () => api.get<unknown, TraderPublicView[]>('/ai/trader/arena'),
  detail: (id: number) => api.get<unknown, TraderDetailView>(`/ai/trader/${id}`),
  /**
   * 决策时间线；round 传空=当前局。必须按局看——局与局是两个独立子账户，混排对不上净值曲线。
   * from/to 是 wakeTime 区间 [from, to)，按天翻看用；与 before 分页可叠加
   */
  decisions: (id: number, limit = 50, before?: number, round?: number, from?: number, to?: number) =>
    api.get<unknown, AiTraderDecisionView[]>(`/ai/trader/${id}/decisions`, { params: { limit, before, round, from, to } }),
  /** 决策 token 合计；三参数与 decisions 同义（round 空=当前局），整段都没 usage 时返回 null */
  tokenUsage: (id: number, round?: number, from?: number, to?: number) =>
    api.get<unknown, number | null>(`/ai/trader/${id}/token-usage`, { params: { round, from, to } }),
  equityCurve: (id: number, round?: number) =>
    api.get<unknown, TraderEquityPoint[]>(`/ai/trader/${id}/equity-curve`, { params: { round } }),
  /** 已了结交易（只有当前局：每局独立子账户，历史局的子账户查不回来） */
  trades: (id: number) => api.get<unknown, TradeRecordView[]>(`/ai/trader/${id}/trades`),
  /** 标记/取消忽略一笔已了结交易（仅本人、仅CLOSED）：AI 统计与复盘不再参考，公开记录不变 */
  setPlanStale: (planId: number, stale: boolean) => api.post<unknown, void>(`/ai/trader/plan/${planId}/stale`, { stale }),

  // ---- 唤醒过程实时流：只有主人连得进来，非主人后端直接拒 ----
  /**
   * 一只 trader 的现场 SSE：run_start/prompt/model_start/token/model_end/tool_result/run_end。
   * 中途连上后端按当前状态回放，空闲时只有心跳
   */
  live: (id: number, onEvent: (e: TraderLiveEvent) => void, signal?: AbortSignal) =>
    getSse<TraderLiveEvent>(`/api/ai/trader/${id}/live`, onEvent, signal),
  /** 某条决策落库的过程轨迹；没有、或不是自己的 trader 都回 null */
  decisionTrace: (id: number, decisionId: number) =>
    api.get<unknown, WakeTrace | null>(`/ai/trader/${id}/decisions/${decisionId}/trace`),

  // ---- 动作面板：三个动作的唯一执行入口，对话轨只负责把表单卡弹出来 ----
  /** 三张卡的状态一次取齐；每张卡挂载且未落地时拉一次 */
  actionPanel: () => api.get<unknown, TraderActionPanel>('/ai/trader/action-panel'),
  /** 留言：覆盖未读的那条；rounds 空=1 轮，越界由后端钳到 1~24 */
  saveNote: (note: string, rounds?: number) =>
    api.post<unknown, TraderActionResult>('/ai/trader/note', { note, rounds }),
  clearNote: () => api.delete<unknown, TraderActionResult>('/ai/trader/note'),
  /** 手动唤醒：真实执行一次决策，可能开/平仓 */
  wake: () => api.post<unknown, TraderActionResult>('/ai/trader/wake'),
  /** 点播复盘：异步跑；无新素材时后端跳过且不消耗模型调用（仍返回 ok） */
  review: () => api.post<unknown, TraderActionResult>('/ai/trader/review'),
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

// ========== 可视化回测页 ==========
export const backtestApi = {
  /** 提交回测（异步；同一时刻仅一个任务，冲突时后端 fail）。fromMs 含、toMs 不含 */
  run: (req: { strategyId: string; symbol: string; fromMs: number; toMs: number; initialBalance?: number; leverage?: number }) =>
    api.post<unknown, { taskId: string }>('/ai/backtest/run', req),
  status: (taskId: string) =>
    api.get<unknown, BacktestTaskStatus>(`/ai/backtest/tasks/${taskId}/status`),
  /** 工作记录增量拉取：带上次 nextAfter 续拉，不重不漏（断线/刷新恢复同一套） */
  events: (taskId: string, after: number, limit = 500) =>
    api.get<unknown, BacktestEventsPage>(`/ai/backtest/tasks/${taskId}/events`, { params: { after, limit } }),
  klines: (taskId: string, offset: number, limit = 20000) =>
    api.get<unknown, BacktestKlinesPage>(`/ai/backtest/tasks/${taskId}/klines`, { params: { offset, limit } }),
  result: (taskId: string) =>
    api.get<unknown, BacktestResultPayload>(`/ai/backtest/tasks/${taskId}/result`),
  /** 手动复盘数据源：覆盖范围 + 区间 K 线（只读本地 kline_history） */
  historyCoverage: () =>
    api.get<unknown, ReplayCoverage[]>('/ai/backtest/history/coverage'),
  historyKlines: (symbol: string, fromMs: number, toMs: number) =>
    api.get<unknown, HistoryKlinesPayload>('/ai/backtest/history/klines', { params: { symbol, fromMs, toMs } }),
  /** 复盘 AI 教练（SSE：token/done/error）：HINT 局中盘面提示 / REVIEW 结算后评估看法。走用户 BYOK 对话配置 */
  replayCoach: (req: ReplayCoachRequest, onEvent: (e: ReplayCoachEvent) => void, signal?: AbortSignal) =>
    postSse<ReplayCoachEvent>('/api/ai/backtest/replay/coach', req, onEvent, signal),
};

// ========== LDC 瓜分活动 ==========
export const campaignApi = {
  /** 当前活动（RUNNING/SETTLING）；没有则 null。活动页靠 status 分"未结算"与"结算了没分到" */
  current: () => api.get<unknown, CampaignInfo | null>('/campaign/current'),
  /** 我的活动数据；没有进行中的活动返回 null */
  me: () => api.get<unknown, MyCampaignView | null>('/campaign/me'),
  board: () => api.get<unknown, CampaignScore[]>('/campaign/board'),
  /** 签到，返回签到后的最长连续天数 */
  checkin: () => api.post<unknown, number>('/campaign/checkin'),
  vote: (symbol: string, direction: 'UP' | 'DOWN') =>
    api.post<unknown, void>('/campaign/vote', { symbol, direction }),
  /** 我的奖励；未结算、或结算了但分配额为 0（不落行）都返回 null */
  reward: () => api.get<unknown, CampaignReward | null>('/campaign/reward'),
  /**
   * 携带 LinuxDo 二次授权 code 领取，见 Login.tsx 的 state 分流。
   *
   * 【别给它加超时】这一个接口最坏要等 ~2 分钟：服务端对 LDC 分发接口最多重试 8 次 × 15s 超时
   * （那边约一半请求会被误路由成 307，重试是必须的）。上面那个 axios 实例**刻意没有 timeout**——
   * 加个全局 30s 之类的值，这里就会在服务端还在重试时被前端掐断：
   * 用户看到"网络错误"，而库里那一行停在 CLAIMED、钱可能已经发出去了。
   * 真要限时只能单独给这一个调用设，别往 axios.create 里塞全局值。
   */
  claim: (code: string) => api.post<unknown, CampaignReward>('/campaign/claim', null, { params: { code } }),
};

