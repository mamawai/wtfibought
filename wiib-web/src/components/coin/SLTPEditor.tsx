import { X, Plus } from 'lucide-react';
import { Input } from '../ui/input';
import { PctSlider } from '../PctSlider';
import { fmtNum } from '../../lib/utils';
import { getStepPrecision, qtyByPct, FUTURES_COMMISSION_RATE, type SLTPRow } from './futuresMath';

/**
 * 止损/止盈多档编辑器：价格直接输入，数量按持仓百分比滑杆（0-100 连续，25% 档刻痕可点跳档）；
 * 数量统一以 USDT 名义价值（开仓价×币量）展示；价格+数量齐全时实时预估触发盈亏（扣平仓手续费）
 * 与对应保证金份额的回报率。
 */
export function SLTPEditor({ rows, onChange, label, posQty, minQty, entryPrice, margin, side, minPriceStep = 0.01, priceFormatter = fmtNum }: {
  rows: SLTPRow[];
  onChange: (rows: SLTPRow[]) => void;
  label: string;
  posQty: number;
  minQty: number;
  entryPrice: number;
  margin: number;
  side: 'LONG' | 'SHORT';
  minPriceStep?: number;
  priceFormatter?: (value?: number | null) => string;
}) {
  const isSL = label === '止损';
  const accent = isSL ? '#eab308' : '#3b82f6';
  const inputPriceStepText = minPriceStep.toFixed(getStepPrecision(minPriceStep));

  const pctOf = (qtyStr: string) => {
    const q = parseFloat(qtyStr) || 0;
    return posQty > 0 ? Math.round((q / posQty) * 100) : 0;
  };
  const usdtOf = (qtyStr: string) => (parseFloat(qtyStr) || 0) * entryPrice;

  // 触发即市价平仓：盈亏差价×数量-taker手续费；回报率相对该档占用的保证金份额
  const estimate = (row: SLTPRow) => {
    const price = parseFloat(row.price) || 0;
    const qty = parseFloat(row.quantity) || 0;
    if (price <= 0 || qty <= 0 || entryPrice <= 0 || posQty <= 0) return null;
    const pnl = (price - entryPrice) * qty * (side === 'LONG' ? 1 : -1);
    const fee = price * qty * FUTURES_COMMISSION_RATE;
    const value = pnl - fee;
    const marginShare = margin * qty / posQty;
    const roi = marginShare > 0 ? (value / marginShare) * 100 : 0;
    return { value, roi };
  };

  const total = rows.reduce((s, r) => s + (parseFloat(r.quantity) || 0), 0);
  const over = total > posQty + 1e-9;

  return (
    <div className="space-y-2 p-2.5 rounded-lg bg-accent/30 border border-border/50">
      {rows.map((row, i) => {
        const est = estimate(row);
        const pct = pctOf(row.quantity);
        return (
          <div key={i} className="space-y-1.5">
            <div className="flex items-center gap-1.5">
              <span className="text-[10px] text-muted-foreground w-3 shrink-0">{i + 1}</span>
              <Input type="number" placeholder={`${label}价 (USDT)`} value={row.price}
                onChange={e => { const n = [...rows]; n[i] = { ...n[i], price: e.target.value }; onChange(n); }}
                step={inputPriceStepText} className="flex-1 h-9 sm:h-8 text-xs" />
              {/* 删除按钮手机扩热区（负margin抵消占位），PC 恢复紧凑 */}
              {rows.length > 1 && (
                <button type="button" onClick={() => onChange(rows.filter((_, j) => j !== i))} className="p-2 -m-1 sm:p-0 sm:m-0 text-muted-foreground hover:text-red-500 transition-colors shrink-0">
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            <div className="pl-4.5">
              <PctSlider
                value={pct}
                color={accent}
                onChange={p => {
                  const n = [...rows];
                  n[i] = { ...n[i], quantity: qtyByPct(posQty, p, minQty) };
                  onChange(n);
                }}
              />
            </div>
            <div className="pl-4.5 flex flex-wrap gap-x-3 gap-y-0.5 text-[10px] font-mono tabular-nums">
              <span className="text-muted-foreground">已选 {pct}% ≈{fmtNum(usdtOf(row.quantity))} USDT</span>
              {est && (
                <>
                  <span className="text-muted-foreground">触发价 ${priceFormatter(parseFloat(row.price))}</span>
                  <span className={est.value >= 0 ? 'text-green-500' : 'text-red-500'}>
                    预计盈亏 {est.value >= 0 ? '+' : ''}{fmtNum(est.value)}
                  </span>
                  <span className={est.roi >= 0 ? 'text-green-500' : 'text-red-500'}>
                    回报率 {est.roi >= 0 ? '+' : ''}{est.roi.toFixed(1)}%
                  </span>
                </>
              )}
            </div>
          </div>
        );
      })}
      <div className="flex items-center justify-between">
        {rows.length < 4 ? (
          <button type="button" onClick={() => onChange([...rows, { price: '', quantity: '' }])} className="text-xs text-primary hover:underline flex items-center gap-0.5">
            <Plus className="w-3 h-3" /> 添加
          </button>
        ) : <span />}
        <span className={`text-[11px] ${over ? 'text-red-500' : 'text-muted-foreground'}`}>
          已分配 ≈{fmtNum(total * entryPrice)} / {fmtNum(posQty * entryPrice)} USDT
        </span>
      </div>
    </div>
  );
}
