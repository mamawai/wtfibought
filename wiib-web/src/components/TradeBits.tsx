import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import { COIN_MAP } from '../lib/coinConfig';
import { getStockBrand } from '../lib/stockConfig';
import { orderSideView } from '../lib/orderSide';

/** 标的的图标：币种走币种配置，认不出的是代币化美股（NVDABUSDT 这种），走股票品牌 */
export function SymIcon({ symbol }: { symbol: string }) {
  const cfg = COIN_MAP[symbol];
  if (cfg) return <cfg.icon className={cn('ci', cfg.colorClass)} />;
  const Brand = getStockBrand(symbol.replace(/BUSDT$/, '')).icon;
  return <Brand className="ci" />;
}

/** 成交方向标签：买绿卖红 */
export function SideTag({ orderSide }: { orderSide: string }) {
  // 方向词表在 labels ns（orderSideView 里查），这里订一份 t 只为切语言时跟着重渲染
  useTranslation('labels');
  const { label, tone } = orderSideView(orderSide);
  return <span className={cn('tag sm', tone === 'buy' ? 'g' : 'r')}>{label}</span>;
}
