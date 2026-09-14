import i18n from '../i18n';
import { fmtNum } from './utils';
import {Coins, Cpu, Flag, Fuel, HardDrive, MemoryStick, Rocket, type LucideProps} from 'lucide-react';
import type {ComponentType} from "react";
import {Bnb, Btc, Doge, Eth, Hype, Sol, Xrp, Zec} from './coinIcons';
import type {MarketId} from './marketSession';

export interface CoinCfg {
  symbol: string;
  /** 展示名。BTC/SOXL 这类本身就是代号，写死字面量；黄金/闪迪这类要随语言变的写成 getter（见 COIN_MAP） */
  name: string;
  pair: string;
  tvSymbol: string;
  futuresTvSymbol: string;
  priceDecimals?: number;
  // lucide 内置图标与下方自定义函数组件都满足该形态（React 19 无需 forwardRef）
  icon: ComponentType<LucideProps>;
  colorClass: string;
  bgClass: string;
  hoverBgClass: string;
  gradientClass: string;
  chartColor: string;
  // 实物换算（如 XAU 黄金盎司/克数）
  unitLabel?: string;
  unitFactor?: number;
  // 分类：不填=crypto；commodity=大宗商品（金/油）；tradfi=美股/ETF 永续（均为纯合约）
  category?: 'crypto' | 'commodity' | 'tradfi';
  // 纯合约标的（无现货）：交易页只出合约，不出现货买卖
  futuresOnly?: boolean;
  // 标的所在的股票市场：填了才在页头显示"当前交易时段"按钮（合约本身 7×24，但流动性跟着标的市场走）
  market?: MarketId;
}

/**
 * 要翻译的展示名走 getter 而不是存一份 key：全站十来处直接读 cfg.name（行情表、交易面板、成交记录），
 * 改成 nameKey 就得跟着全改一遍。getter 每次读现查词表，等价于"存 key 渲染时再 t()"，
 * 却不用动任何调用方 —— 关键是**别写成模块顶层常量**，那会在加载那一刻定死、切语言不跟着变。
 */
export const COIN_MAP: Record<string, CoinCfg> = {
  BTCUSDT: {
    symbol: 'BTCUSDT', name: 'BTC', pair: 'BTC / USDT', tvSymbol: 'BINANCE:BTCUSD', futuresTvSymbol: 'BINANCE:BTCUSDT.P',
    icon: Btc,
    colorClass: 'text-orange-500', bgClass: 'bg-orange-500/10', hoverBgClass: 'hover:bg-orange-500/20', gradientClass: 'from-orange-500/5',
    chartColor: '#f97316',
  },
  ETHUSDT: {
    symbol: 'ETHUSDT', name: 'ETH', pair: 'ETH / USDT', tvSymbol: 'BINANCE:ETHUSD', futuresTvSymbol: 'BINANCE:ETHUSDT.P',
    icon: Eth,
    colorClass: 'text-indigo-400', bgClass: 'bg-indigo-500/10', hoverBgClass: 'hover:bg-indigo-500/20', gradientClass: 'from-indigo-500/5',
    chartColor: '#818cf8',
  },
  DOGEUSDT: {
    symbol: 'DOGEUSDT', name: 'DOGE', pair: 'DOGE / USDT', tvSymbol: 'BINANCE:DOGEUSDT', futuresTvSymbol: 'BINANCE:DOGEUSDT.P',
    priceDecimals: 5, icon: Doge,
    colorClass: 'text-amber-500', bgClass: 'bg-amber-500/10', hoverBgClass: 'hover:bg-amber-500/20', gradientClass: 'from-amber-500/5',
    chartColor: '#f59e0b',
  },
  SOLUSDT: {
    symbol: 'SOLUSDT', name: 'SOL', pair: 'SOL / USDT', tvSymbol: 'BINANCE:SOLUSDT', futuresTvSymbol: 'BINANCE:SOLUSDT.P',
    icon: Sol,
    colorClass: 'text-purple-500', bgClass: 'bg-purple-500/10', hoverBgClass: 'hover:bg-purple-500/20', gradientClass: 'from-purple-500/5',
    chartColor: '#a855f7',
  },
  XRPUSDT: {
    symbol: 'XRPUSDT', name: 'XRP', pair: 'XRP / USDT', tvSymbol: 'BINANCE:XRPUSDT', futuresTvSymbol: 'BINANCE:XRPUSDT.P',
    priceDecimals: 4, icon: Xrp,
    // 官方 X 符号是黑/白单色，图标跟前景色走；面板底色/走势线仍用蓝系区分
    colorClass: 'text-foreground', bgClass: 'bg-sky-500/10', hoverBgClass: 'hover:bg-sky-500/20', gradientClass: 'from-sky-500/5',
    chartColor: '#0ea5e9',
  },
  BNBUSDT: {
    symbol: 'BNBUSDT', name: 'BNB', pair: 'BNB / USDT', tvSymbol: 'BINANCE:BNBUSDT', futuresTvSymbol: 'BINANCE:BNBUSDT.P',
    icon: Bnb,
    colorClass: 'text-yellow-400', bgClass: 'bg-yellow-400/10', hoverBgClass: 'hover:bg-yellow-400/20', gradientClass: 'from-yellow-400/5',
    chartColor: '#f0b90b',
  },
  ZECUSDT: {
    symbol: 'ZECUSDT', name: 'ZEC', pair: 'ZEC / USDT', tvSymbol: 'BINANCE:ZECUSDT', futuresTvSymbol: 'BINANCE:ZECUSDT.P',
    icon: Zec,
    colorClass: 'text-amber-400', bgClass: 'bg-amber-400/10', hoverBgClass: 'hover:bg-amber-400/20', gradientClass: 'from-amber-400/5',
    chartColor: '#f4b728',
  },
  // HYPE：Binance 主站只有永续没现货，归 crypto 但按纯合约走
  HYPEUSDT: {
    symbol: 'HYPEUSDT', name: 'HYPE', pair: 'HYPE / USDT', tvSymbol: 'BINANCE:HYPEUSDT.P', futuresTvSymbol: 'BINANCE:HYPEUSDT.P',
    priceDecimals: 3, icon: Hype,
    colorClass: 'text-teal-400', bgClass: 'bg-teal-400/10', hoverBgClass: 'hover:bg-teal-400/20', gradientClass: 'from-teal-400/5',
    chartColor: '#50d2c1', futuresOnly: true,
  },
  XAUUSDT: {
    symbol: 'XAUUSDT', get name() { return i18n.t('market:coinName.XAUUSDT'); }, pair: 'XAU / USDT', tvSymbol: 'TVC:GOLD', futuresTvSymbol: 'BINANCE:XAUUSDT.P',
    icon: Coins,
    colorClass: 'text-yellow-500', bgClass: 'bg-yellow-500/10', hoverBgClass: 'hover:bg-yellow-500/20', gradientClass: 'from-yellow-500/5',
    chartColor: '#eab308', category: 'commodity', futuresOnly: true,
  },
  CLUSDT: {
    symbol: 'CLUSDT', get name() { return i18n.t('market:coinName.CLUSDT'); }, pair: 'CL / USDT', tvSymbol: 'TVC:USOIL', futuresTvSymbol: 'BINANCE:CLUSDT.P',
    icon: Fuel,
    colorClass: 'text-stone-500', bgClass: 'bg-stone-500/10', hoverBgClass: 'hover:bg-stone-500/20', gradientClass: 'from-stone-500/5',
    chartColor: '#78716c', category: 'commodity', futuresOnly: true,
  },
  SNDKUSDT: {
    symbol: 'SNDKUSDT', get name() { return i18n.t('market:coinName.SNDKUSDT'); }, pair: 'SNDK / USDT', tvSymbol: 'NASDAQ:SNDK', futuresTvSymbol: 'BINANCE:SNDKUSDT.P',
    icon: HardDrive,
    colorClass: 'text-red-500', bgClass: 'bg-red-500/10', hoverBgClass: 'hover:bg-red-500/20', gradientClass: 'from-red-500/5',
    chartColor: '#ef4444', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SOXLUSDT: {
    symbol: 'SOXLUSDT', name: 'SOXL', pair: 'SOXL / USDT', tvSymbol: 'AMEX:SOXL', futuresTvSymbol: 'BINANCE:SOXLUSDT.P',
    icon: Cpu,
    colorClass: 'text-emerald-500', bgClass: 'bg-emerald-500/10', hoverBgClass: 'hover:bg-emerald-500/20', gradientClass: 'from-emerald-500/5',
    chartColor: '#10b981', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SKHYNIXUSDT: {
    symbol: 'SKHYNIXUSDT', get name() { return i18n.t('market:coinName.SKHYNIXUSDT'); }, pair: 'SKHYNIX / USDT', tvSymbol: 'KRX:000660', futuresTvSymbol: 'BINANCE:SKHYNIXUSDT.P',
    icon: MemoryStick,
    colorClass: 'text-orange-600', bgClass: 'bg-orange-600/10', hoverBgClass: 'hover:bg-orange-600/20', gradientClass: 'from-orange-600/5',
    chartColor: '#ea580c', category: 'tradfi', futuresOnly: true, market: 'KRX',
  },
  MUUSDT: {
    symbol: 'MUUSDT', get name() { return i18n.t('market:coinName.MUUSDT'); }, pair: 'MU / USDT', tvSymbol: 'NASDAQ:MU', futuresTvSymbol: 'BINANCE:MUUSDT.P',
    icon: Cpu,
    colorClass: 'text-blue-500', bgClass: 'bg-blue-500/10', hoverBgClass: 'hover:bg-blue-500/20', gradientClass: 'from-blue-500/5',
    chartColor: '#3b82f6', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  KORUUSDT: {
    symbol: 'KORUUSDT', name: 'KORU', pair: 'KORU / USDT', tvSymbol: 'AMEX:KORU', futuresTvSymbol: 'BINANCE:KORUUSDT.P',
    icon: Flag,
    colorClass: 'text-rose-500', bgClass: 'bg-rose-500/10', hoverBgClass: 'hover:bg-rose-500/20', gradientClass: 'from-rose-500/5',
    chartColor: '#f43f5e', category: 'tradfi', futuresOnly: true, market: 'US',
  },
  SPCXUSDT: {
    symbol: 'SPCXUSDT', name: 'SpaceX', pair: 'SPCX / USDT', tvSymbol: 'BINANCE:SPCXUSDT.P', futuresTvSymbol: 'BINANCE:SPCXUSDT.P',
    icon: Rocket,
    colorClass: 'text-violet-500', bgClass: 'bg-violet-500/10', hoverBgClass: 'hover:bg-violet-500/20', gradientClass: 'from-violet-500/5',
    chartColor: '#8b5cf6', category: 'tradfi', futuresOnly: true, market: 'US',
  },
};

export const COIN_LIST = Object.values(COIN_MAP).filter(c => !c.category);
export const COMMODITY_LIST = Object.values(COIN_MAP).filter(c => c.category === 'commodity');
export const TRADFI_LIST = Object.values(COIN_MAP).filter(c => c.category === 'tradfi');
export const DEFAULT_SYMBOL = 'BTCUSDT';

export function getCoin(symbol?: string): CoinCfg {
  return COIN_MAP[symbol ?? ''] ?? COIN_MAP[DEFAULT_SYMBOL];
}

export function getCoinPriceDecimals(symbol?: string): number {
  return getCoin(symbol).priceDecimals ?? 2;
}

/** 最小价格步长（由 priceDecimals 推导）：2 位小数 → 0.01 */
export function getCoinPriceStep(symbol?: string): number {
  const d = getCoinPriceDecimals(symbol);
  return Number((1 / 10 ** d).toFixed(d));
}

export function formatCoinPrice(symbol: string | undefined, value?: number | null): string {
  return fmtNum(value, getCoinPriceDecimals(symbol));
}
