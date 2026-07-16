import { useRef } from 'react';
import { X, Plus } from 'lucide-react';
import { Input } from '../ui/input';
import { fmtNum } from '../../lib/utils';
import { getStepPrecision, type SLTPRow } from './futuresMath';

/** 止损/止盈多档编辑器：每档 价格+数量 输入 + 区间滑杆，档间价格单调递增/递减约束 */
export function SLTPEditor({ rows, onChange, label, posQty, minQty, currentPrice, side, liquidationPrice, minPriceStep = 0.01, priceFormatter = fmtNum }: {
  rows: SLTPRow[];
  onChange: (rows: SLTPRow[]) => void;
  label: string;
  posQty: number;
  minQty: number;
  currentPrice: number;
  side: 'LONG' | 'SHORT';
  liquidationPrice?: number;
  minPriceStep?: number;
  priceFormatter?: (value?: number | null) => string;
}) {
  const isSL = label === '止损';
  const dragging = useRef(false);
  // TP LONG / SL SHORT: ascending; TP SHORT / SL LONG: descending
  const ascending = (!isSL && side === 'LONG') || (isSL && side === 'SHORT');

  let rangeMin: number, rangeMax: number;
  if (isSL) {
    if (side === 'LONG') {
      rangeMin = Math.max(0.01, liquidationPrice ?? currentPrice * 0.5);
      rangeMax = currentPrice * 0.999;
    } else {
      rangeMin = currentPrice * 1.001;
      rangeMax = liquidationPrice ?? currentPrice * 1.5;
    }
  } else {
    if (side === 'LONG') {
      rangeMin = currentPrice * 1.001;
      rangeMax = currentPrice * 1.2;
    } else {
      rangeMin = Math.max(0.01, currentPrice * 0.8);
      rangeMax = currentPrice * 0.999;
    }
  }

  const priceStep = currentPrice >= 10000 ? 10 : currentPrice >= 1000 ? 1 : currentPrice >= 100 ? 0.1 : minPriceStep;
  const priceStepPrecision = getStepPrecision(priceStep);
  const inputPriceStepText = minPriceStep.toFixed(getStepPrecision(minPriceStep));
  rangeMin = Math.round(rangeMin / priceStep) * priceStep;
  rangeMax = Math.round(rangeMax / priceStep) * priceStep;

  const getRowRange = (i: number): [number, number] => {
    let rMin = rangeMin, rMax = rangeMax;
    if (i > 0) {
      const prev = parseFloat(rows[i - 1].price) || 0;
      if (prev > 0) {
        if (ascending) rMin = Math.max(rMin, prev + priceStep);
        else rMax = Math.min(rMax, prev - priceStep);
      }
    }
    return [rMin, rMax];
  };

  const total = rows.reduce((s, r) => s + (parseFloat(r.quantity) || 0), 0);
  const over = total > posQty + 1e-9;
  const showSlider = currentPrice > 0 && rangeMin < rangeMax;

  return (
    <div className="space-y-2 p-2.5 rounded-lg bg-accent/30 border border-border/50">
      {rows.map((row, i) => {
        const [rowMin, rowMax] = getRowRange(i);
        const priceVal = parseFloat(row.price) || 0;
        const sliderVal = priceVal >= rowMin && priceVal <= rowMax ? priceVal : ascending ? rowMin : rowMax;
        return (
          <div key={i} className="space-y-1">
            <div className="flex items-center gap-1.5">
              <span className="text-[10px] text-muted-foreground w-3 shrink-0">{i + 1}</span>
              <Input type="number" placeholder={`${label}价`} value={row.price}
                onChange={e => { const n = [...rows]; n[i] = { ...n[i], price: e.target.value }; onChange(n); }}
                step={inputPriceStepText} className="flex-1 h-8 text-xs" />
              <Input type="number" placeholder="数量" value={row.quantity}
                onChange={e => { const n = [...rows]; n[i] = { ...n[i], quantity: e.target.value }; onChange(n); }}
                step={String(minQty)} className="w-24 h-8 text-xs" />
              {rows.length > 1 && (
                <button type="button" onClick={() => onChange(rows.filter((_, j) => j !== i))} className="text-muted-foreground hover:text-red-500 transition-colors shrink-0">
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            {showSlider && rowMin < rowMax && (
              <div className="flex items-center gap-1">
                <span className="text-[9px] text-muted-foreground shrink-0 font-mono">
                  {isSL && ((side === 'LONG' && i === 0) || (side === 'SHORT' && i === rows.length - 1)) ? '强平' : priceFormatter(rowMin)}
                </span>
                <input
                  type="range" min={rowMin} max={rowMax} step={priceStep} value={sliderVal}
                  onPointerDown={() => { dragging.current = true; }}
                  onPointerUp={() => { dragging.current = false; }}
                  onChange={e => {
                    if (!dragging.current) return;
                    const val = Number(e.target.value);
                    const n = [...rows];
                    n[i] = { ...n[i], price: val % 1 === 0 ? String(val) : val.toFixed(priceStepPrecision) };
                    onChange(n);
                  }}
                  className="flex-1 h-1.5 cursor-pointer"
                  style={{ accentColor: isSL ? '#eab308' : '#3b82f6' }}
                />
                <span className="text-[9px] text-muted-foreground shrink-0 font-mono">{priceFormatter(rowMax)}</span>
              </div>
            )}
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
          已分配 {total > 0 ? total.toFixed(8).replace(/0+$/, '').replace(/\.$/, '') : '0'} / {posQty}
        </span>
      </div>
    </div>
  );
}
