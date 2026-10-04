import { COIN_MAP } from './coinConfig';

/** 四个市场的列表页，顺序同桌面顶栏「市场」下拉；名字在 layout 词表 marketMenu 下 */
export const MARKETS = [
  { to: '/bstock', labelKey: 'marketMenu.stocks' },
  { to: '/coin', labelKey: 'marketMenu.crypto' },
  { to: '/commodity', labelKey: 'marketMenu.commodity' },
  { to: '/tradfi', labelKey: 'marketMenu.tradfi' },
];
export const MARKET_PATHS = MARKETS.map(m => m.to);

/**
 * 当前页属于哪个市场的列表，不在市场里就 null。
 * 大宗、TradFi 的详情页也挂在 /coin/:symbol 下，按币种配置的 category 分回去（同 Coin 页的"返回列表"）
 */
export function marketOf(pathname: string): string | null {
  const m = MARKET_PATHS.find(p => pathname === p || pathname.startsWith(p + '/'));
  if (m !== '/coin' || pathname === '/coin') return m ?? null;
  const cat = COIN_MAP[pathname.slice('/coin/'.length)]?.category;
  return cat === 'commodity' ? '/commodity' : cat === 'tradfi' ? '/tradfi' : '/coin';
}

const LAST_KEY = 'wiib-last-market';

/** 上次看的市场列表，手机底部 Tab 的"市场"从市场外点进来就去这；没看过就股票 */
export function lastMarket(): string {
  try {
    const v = localStorage.getItem(LAST_KEY);
    if (v && MARKET_PATHS.includes(v)) return v;
  } catch { /* 禁存储：当没看过 */ }
  return MARKET_PATHS[0];
}

export function rememberMarket(path: string) {
  try { localStorage.setItem(LAST_KEY, path); } catch { /* 记不住就下次回股票 */ }
}
