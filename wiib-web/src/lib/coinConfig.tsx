import {Bitcoin, Coins, type LucideIcon, type LucideProps} from 'lucide-react';
import {forwardRef} from "react";

export interface CoinCfg {
  symbol: string;
  name: string;
  pair: string;
  tvSymbol: string;
  futuresTvSymbol: string;
  minQty: number;
  priceDecimals?: number;
  icon: LucideIcon;
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

const Eth: LucideIcon = forwardRef<SVGSVGElement, LucideProps>(({ className, ...rest }, ref) => (
    <svg ref={ref} xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M12 2L4.5 12.5 12 16.5l7.5-4L12 2z" />
      <path d="M4.5 12.5L12 22l7.5-9.5L12 16.5 4.5 12.5z" />
    </svg>
));

// DOGE 用真实柴犬+金币 PNG（dogecoin.com 官方 logo），不响应 currentColor
const Doge: LucideIcon = forwardRef<SVGSVGElement, LucideProps>(({ className, ...rest }, ref) => (
    <svg ref={ref} xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className} {...rest}>
      <image href="/coin-icons/doge.png" x="0" y="0" width="24" height="24" preserveAspectRatio="xMidYMid meet" />
    </svg>
));

// SOL 三条错位斜杠（Solana 官方 logo 的描边简化版）
const Sol: LucideIcon = forwardRef<SVGSVGElement, LucideProps>(({ className, ...rest }, ref) => (
    <svg ref={ref} xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M7.5 4H21l-3.5 3.5H4L7.5 4z" />
      <path d="M4 10.25h13.5l3.5 3.5H7.5l-3.5-3.5z" />
      <path d="M7.5 16.5H21L17.5 20H4l3.5-3.5z" />
    </svg>
));

// XRP 上下两道喇叭口曲线组成的 X（Ripple 官方 logo 的描边简化版）
const Xrp: LucideIcon = forwardRef<SVGSVGElement, LucideProps>(({ className, ...rest }, ref) => (
    <svg ref={ref} xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M4 4.5h2c1.7 0 3.3.7 4.5 1.9L12 7.8l1.5-1.4C14.7 5.2 16.3 4.5 18 4.5h2" />
      <path d="M4 19.5h2c1.7 0 3.3-.7 4.5-1.9L12 16.2l1.5 1.4c1.2 1.2 2.8 1.9 4.5 1.9h2" />
    </svg>
));

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
  if (value == null || !Number.isFinite(value)) return '-';
  const decimals = getCoinPriceDecimals(symbol);
  return value.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}
