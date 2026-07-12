import { fmtNum } from './utils';
import {Bitcoin, Coins, type LucideProps} from 'lucide-react';
import type {ComponentType} from "react";
import {Doge, Eth, Sol, Xrp} from './coinIcons';

export interface CoinCfg {
  symbol: string;
  name: string;
  pair: string;
  tvSymbol: string;
  futuresTvSymbol: string;
  minQty: number;
  priceDecimals?: number;
  // lucide 内置图标与下方自定义函数组件都满足该形态（React 19 无需 forwardRef）
  icon: ComponentType<LucideProps>;
  colorClass: string;
  bgClass: string;
  hoverBgClass: string;
  gradientClass: string;
  chartColor: string;
  desc: string;
  // 实物换算（如 PAXG 黄金克数）
  unitLabel?: string;
  unitFactor?: number;
}

export const COIN_MAP: Record<string, CoinCfg> = {
  BTCUSDT: {
    symbol: 'BTCUSDT', name: 'BTC', pair: 'BTC / USDT', tvSymbol: 'BINANCE:BTCUSD', futuresTvSymbol: 'BTCUSDT26M2026',
    minQty: 0.00001, icon: Bitcoin,
    colorClass: 'text-orange-500', bgClass: 'bg-orange-500/10', hoverBgClass: 'hover:bg-orange-500/20', gradientClass: 'from-orange-500/5',
    chartColor: '#f97316', desc: '比特币 / USDT 模拟交易',
  },
  PAXGUSDT: {
    symbol: 'PAXGUSDT', name: 'PAXG', pair: 'PAXG / USDT', tvSymbol: 'BINANCE:PAXGUSD', futuresTvSymbol: 'BINANCE:PAXGUSD',
    minQty: 0.001, icon: Coins,
    colorClass: 'text-yellow-500', bgClass: 'bg-yellow-500/10', hoverBgClass: 'hover:bg-yellow-500/20', gradientClass: 'from-yellow-500/5',
    chartColor: '#eab308', desc: 'PAX Gold / USDT · 1枚=1盎司黄金（31.1035克）',
    unitLabel: '克', unitFactor: 31.1035,
  },
  ETHUSDT: {
    symbol: 'ETHUSDT', name: 'ETH', pair: 'ETH / USDT', tvSymbol: 'BINANCE:ETHUSD', futuresTvSymbol: 'ETHUSDT26M2026',
    minQty: 0.0001, icon: Eth,
    colorClass: 'text-indigo-400', bgClass: 'bg-indigo-500/10', hoverBgClass: 'hover:bg-indigo-500/20', gradientClass: 'from-indigo-500/5',
    chartColor: '#818cf8', desc: '以太坊 / USDT 模拟交易',
  },
  DOGEUSDT: {
    symbol: 'DOGEUSDT', name: 'DOGE', pair: 'DOGE / USDT', tvSymbol: 'BINANCE:DOGEUSDT', futuresTvSymbol: 'BINANCE:DOGEUSDT',
    minQty: 1, priceDecimals: 5, icon: Doge,
    colorClass: 'text-amber-500', bgClass: 'bg-amber-500/10', hoverBgClass: 'hover:bg-amber-500/20', gradientClass: 'from-amber-500/5',
    chartColor: '#f59e0b', desc: '狗狗币 / USDT 模拟交易',
  },
  SOLUSDT: {
    symbol: 'SOLUSDT', name: 'SOL', pair: 'SOL / USDT', tvSymbol: 'BINANCE:SOLUSDT', futuresTvSymbol: 'BINANCE:SOLUSDT',
    minQty: 0.01, icon: Sol,
    colorClass: 'text-purple-500', bgClass: 'bg-purple-500/10', hoverBgClass: 'hover:bg-purple-500/20', gradientClass: 'from-purple-500/5',
    chartColor: '#a855f7', desc: 'Solana / USDT 模拟交易',
  },
  XRPUSDT: {
    symbol: 'XRPUSDT', name: 'XRP', pair: 'XRP / USDT', tvSymbol: 'BINANCE:XRPUSDT', futuresTvSymbol: 'BINANCE:XRPUSDT',
    minQty: 1, priceDecimals: 4, icon: Xrp,
    colorClass: 'text-sky-500', bgClass: 'bg-sky-500/10', hoverBgClass: 'hover:bg-sky-500/20', gradientClass: 'from-sky-500/5',
    chartColor: '#0ea5e9', desc: '瑞波币 / USDT 模拟交易',
  },
};

export const COIN_LIST = Object.values(COIN_MAP);
export const DEFAULT_SYMBOL = 'BTCUSDT';

export function getCoin(symbol?: string): CoinCfg {
  return COIN_MAP[symbol ?? ''] ?? COIN_MAP[DEFAULT_SYMBOL];
}

export function getCoinPriceDecimals(symbol?: string): number {
  return getCoin(symbol).priceDecimals ?? 2;
}

export function formatCoinPrice(symbol: string | undefined, value?: number | null): string {
  return fmtNum(value, getCoinPriceDecimals(symbol));
}
