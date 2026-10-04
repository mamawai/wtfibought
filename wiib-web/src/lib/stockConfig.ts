import type { ComponentType } from 'react';
import { Landmark, type LucideProps } from 'lucide-react';
import { Amd, Crcl, Mstr, Mu, Nvda, Qqq, Sndk, Soxl, Spcx, Tsla } from './stockIcons';

export interface StockBrand {
  icon: ComponentType<LucideProps>;
  /** 走势线和进交易页过渡光晕的颜色：取 logo 主色，单色 logo 跟前景色 */
  color: string;
}

/** 按股票代号（BStock.ticker）配 logo；bStock 列表是后端表，新上架没配的走下面的兜底 */
const STOCK_BRANDS: Record<string, StockBrand> = {
  NVDA: { icon: Nvda, color: '#76b900' },
  TSLA: { icon: Tsla, color: '#e82127' },
  MU: { icon: Mu, color: 'var(--color-foreground)' },
  SNDK: { icon: Sndk, color: '#e10600' },
  CRCL: { icon: Crcl, color: '#5fbfff' },
  MSTR: { icon: Mstr, color: '#fa660f' },
  AMD: { icon: Amd, color: 'var(--color-foreground)' },
  SPCX: { icon: Spcx, color: 'var(--color-foreground)' },
  // Invesco 深蓝（#000ad2）在暗色主题上看不清，走势线取同色系亮一档
  QQQ: { icon: Qqq, color: '#3b82f6' },
  SOXL: { icon: Soxl, color: '#0063a6' },
};

const FALLBACK: StockBrand = { icon: Landmark, color: 'var(--color-foreground)' };

export function getStockBrand(ticker?: string): StockBrand {
  return STOCK_BRANDS[ticker ?? ''] ?? FALLBACK;
}
