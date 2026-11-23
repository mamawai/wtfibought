import type { Lang } from '../i18n';

/** 一条更新：新增 / 优化 / 修复，一句标题加一句说明，中英各一份 */
export interface ReleaseItem {
  kind: 'new' | 'better' | 'fix';
  title: Record<Lang, string>;
  desc: Record<Lang, string>;
}

/** 一个版本；version 前面加 v 就是 GitHub 上的 tag */
export interface Release {
  version: string;
  date: string;
  items: ReleaseItem[];
}

/**
 * 更新日志，新的在前。每发一版在最前面加一条：只写用户看得出来的变化，最多 5 条，每条说明一句话。
 * 先把 tag 推上 GitHub 再部署，弹窗里「完整发布说明」链的是 tag 页
 */
export const RELEASES: Release[] = [
  {
    version: '3.3.0',
    date: '2026-11-23',
    items: [
      {
        kind: 'new',
        title: { zh: '一个账号最多 3 个 Trader', en: 'Up to three Traders per account' },
        desc: { zh: '可以同时开 3 个 Trader，各用各的账户；对话里提到 Trader 时会让你选是哪一个。', en: 'Run up to three Traders side by side, each on its own account; chat asks which one you mean.' },
      },
      {
        kind: 'new',
        title: { zh: '单笔复盘', en: 'Per-trade reviews' },
        desc: { zh: '每笔单子平仓满 24 小时单独复盘一次，每天再出一篇统一复盘。', en: 'Each trade is reviewed on its own 24 hours after it closes, plus one combined review a day.' },
      },
      {
        kind: 'new',
        title: { zh: '权益回落警报', en: 'Equity drawdown alert' },
        desc: { zh: '权益从最高点回落到你设的比例时，提前叫醒 Trader 看一次整盘。', en: 'When equity falls from its high by the percentage you set, the Trader is woken early to look over everything.' },
      },
      {
        kind: 'better',
        title: { zh: '唤醒按币并行', en: 'Wake-ups run per symbol' },
        desc: { zh: '盯盘命中和警报只叫醒处理那一个币的一轮，几轮可以同时跑；心跳只留 1h 和 4h。', en: 'A watch hit or an alert wakes a round for that one symbol and several can run at once; heartbeats are now 1h or 4h only.' },
      },
      {
        kind: 'better',
        title: { zh: '经验账本按真实单子记账', en: 'Experience ledger scored on real trades' },
        desc: { zh: '每条经验名下记的是真实单子的盈亏，单子够了自动转正，亏回去自动降级。', en: 'Each lesson is scored on the actual trades filed under it, then promoted or demoted automatically.' },
      },
    ],
  },
  {
    version: '3.2.0',
    date: '2026-11-17',
    items: [
      {
        kind: 'new',
        title: { zh: '合约盘口', en: 'Futures order book' },
        desc: { zh: '交易页能看按档位合并的买卖盘。', en: 'The trading page shows bids and asks merged by price step.' },
      },
      {
        kind: 'new',
        title: { zh: 'Trader 分档入场和反手', en: 'Scaled entries and reversals' },
        desc: { zh: '一笔单可以分几档进场，也能直接反手。', en: 'A Trader can enter in several tranches, or flip a position in one move.' },
      },
      {
        kind: 'new',
        title: { zh: 'Trader 联网搜索', en: 'Web search for Traders' },
        desc: { zh: 'Trader 的开关和模型端点都开了联网搜索，它下决定前会先查消息。', en: 'With search on for both the Trader and its endpoint, it looks things up before it decides.' },
      },
      {
        kind: 'better',
        title: { zh: '全站换新版式', en: 'New look across the site' },
        desc: { zh: '首页、行情、持仓、竞技场等页面全部重排，登录页重做。', en: 'Home, markets, portfolio and the arena are re-laid out; the login page is rebuilt.' },
      },
    ],
  },
  {
    version: '3.1.0',
    date: '2026-11-07',
    items: [
      {
        kind: 'new',
        title: { zh: 'Google 登录', en: 'Google sign-in' },
        desc: { zh: '可以直接用 Google 账号登录。', en: 'Log in with your Google account.' },
      },
      {
        kind: 'new',
        title: { zh: 'Trader 盯盘条件', en: 'Watch conditions for Traders' },
        desc: { zh: 'Trader 能自己设盯盘条件，命中就被叫醒，不用等下一次心跳。', en: 'A Trader sets its own watch conditions and wakes the moment one hits.' },
      },
      {
        kind: 'better',
        title: { zh: 'Trader 配置台和工作台重做', en: 'Rebuilt Trader setup and workbench' },
        desc: { zh: '可选币扩到 8 个，持仓盈亏跟着实时标记价走。', en: 'Eight symbols to choose from, and position PnL follows the live mark price.' },
      },
      {
        kind: 'better',
        title: { zh: '研判对话能查更多数据', en: 'More data in analysis chat' },
        desc: { zh: '直接查 K 线指标、持仓量和多空比、财经日历、大户持仓。', en: 'Candles, indicators, open interest, long/short ratios, the economic calendar and whale positions.' },
      },
      {
        kind: 'better',
        title: { zh: '合约撮合更贴近实盘', en: 'Futures matching closer to live' },
        desc: { zh: '限价单能成交就当场成交，强平按盘口价并收清算费。', en: 'Fillable limit orders fill at once; liquidations use book prices plus a fee.' },
      },
    ],
  },
  {
    version: '3.0.0',
    date: '2026-10-23',
    items: [
      {
        kind: 'new',
        title: { zh: '因子挖掘', en: 'Alpha factor mining' },
        desc: { zh: '顶栏新入口：看因子库，和因子交易员的持仓、净值。', en: 'Top bar entry: the factor library, the factor trader\'s positions and equity.' },
      },
      {
        kind: 'new',
        title: { zh: '交易指令生成器', en: 'Trading instruction generator' },
        desc: { zh: '帮你写 Trader 用的交易指令。', en: 'Helps you write the trading instructions your Trader follows.' },
      },
      {
        kind: 'new',
        title: { zh: '对话能派子 agent', en: 'Sub-agents in chat' },
        desc: { zh: '调研和行情分析可以派给后台子 agent，报告回来再补答。', en: 'Research and market analysis run in background sub-agents, then it follows up.' },
      },
      {
        kind: 'better',
        title: { zh: '对话面板重做', en: 'Rebuilt chat panel' },
        desc: { zh: '悬浮球换成橙色星球，过程可以逐条展开看。', en: 'An orange planet replaces the floating ball, and each step can be expanded.' },
      },
      {
        kind: 'better',
        title: { zh: '资金费按各合约的结算时刻收', en: 'Funding on each contract\'s schedule' },
        desc: { zh: '不再统一一个时间，跟 Binance 每个合约自己的结算时刻走。', en: 'Each contract now follows its own Binance settlement times.' },
      },
    ],
  },
];

/** 最新一版的版本号，顶栏图标拿它跟看过的那版比 */
export const LATEST_VERSION = RELEASES[0].version;

/** 这一版在 GitHub 上的完整发布说明 */
export const releaseUrl = (version: string) => `https://github.com/mamawai/wtfibought/releases/tag/v${version}`;
