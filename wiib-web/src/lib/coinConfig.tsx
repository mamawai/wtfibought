import {Bitcoin, Coins, type LucideIcon, type LucideProps} from 'lucide-react';
import {forwardRef} from "react";

export interface CoinCfg {
  symbol: string;
  name: string;
  pair: string;
  tvSymbol: string;
  futuresTvSymbol: string;
  minQty: number;
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
};

export const COIN_LIST = Object.values(COIN_MAP);
export const DEFAULT_SYMBOL = 'BTCUSDT';

export function getCoin(symbol?: string): CoinCfg {
  return COIN_MAP[symbol ?? ''] ?? COIN_MAP[DEFAULT_SYMBOL];
}
