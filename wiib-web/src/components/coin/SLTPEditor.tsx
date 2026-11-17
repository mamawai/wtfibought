import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn, fmtNum } from '../../lib/utils';
import { getStepPrecision, floorToStep, qtyByPct, FUTURES_COMMISSION_RATE, POSITION_PCTS, type SLTPRow } from './futuresMath';
import { NumInput, PctRow } from './TradeFields';
import './SLTPEditor.css';

/**
 * 止损/止盈多档编辑器（样式见 SLTPEditor.css）：一档一个价格框（右侧单位显示相对开仓价的涨跌幅）、
 * 一个数量框（右侧单位是币名）和一排仓位百分比，数量手填、点百分比都行；
 * 价格+数量齐全时实时预估触发盈亏（扣平仓手续费）与对应保证金份额的回报率。
 * <p>止损/止盈由 kind 区分，不拿显示文案判——文案会随语言变，判不出来。
 */
export function SLTPEditor({ rows, onChange, kind, posQty, minQty, entryPrice, margin, side, unit, minPriceStep = 0.01, priceFormatter = fmtNum }: {
  rows: SLTPRow[];
  onChange: (rows: SLTPRow[]) => void;
  kind: 'SL' | 'TP';
  posQty: number;
  minQty: number;
  entryPrice: number;
  margin: number;
  side: 'LONG' | 'SHORT';
  /** 数量单位=币名 */
  unit: string;
  minPriceStep?: number;
  priceFormatter?: (value?: number | null) => string;
}) {
  const { t } = useTranslation('trade');
  const isSL = kind === 'SL';
  const inputPriceStepText = minPriceStep.toFixed(getStepPrecision(minPriceStep));
  const qtyStepText = String(minQty);

  const setRow = (i: number, patch: Partial<SLTPRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));

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

  /** 价格框右侧单位：这一档离开仓价多远 */
  const diffPct = (priceStr: string) => {
    const price = parseFloat(priceStr) || 0;
    if (price <= 0 || entryPrice <= 0) return '%';
    const d = (price - entryPrice) / entryPrice * 100;
    return `${d >= 0 ? '+' : ''}${d.toFixed(1)}%`;
  };

  /** 这一档的数量正好是仓位的 25/50/75/100% 里的哪一个 */
  const activePct = (qtyStr: string) => {
    const qty = parseFloat(qtyStr) || 0;
    if (qty <= 0) return null;
    return POSITION_PCTS.find(p => Math.abs(parseFloat(qtyByPct(posQty, p * 100, minQty)) - qty) < 1e-9) ?? null;
  };

  const total = rows.reduce((s, r) => s + (parseFloat(r.quantity) || 0), 0);
  const over = total > posQty + 1e-9;

  /** 新加的一档先填上还没分出去的量 */
  const addRow = () => {
    const left = floorToStep(posQty - total, minQty);
    onChange([...rows, { price: '', quantity: left > 0 ? String(left) : '' }]);
  };

  return (
    <div className="ed">
      {rows.map((row, i) => {
        const est = estimate(row);
        return (
          <div key={i} className="lv">
            <div className="lvr">
              <NumInput
                className="sm"
                value={row.price}
                placeholder={isSL ? t('sltp.slPrice') : t('sltp.tpPrice')}
                step={inputPriceStepText}
                unit={diffPct(row.price)}
                onChange={v => setRow(i, { price: v })}
              />
              {rows.length > 1 && (
                <button type="button" className="xb del" aria-label={t('sltp.removeRow')} onClick={() => onChange(rows.filter((_, j) => j !== i))}>
                  <X className="ic" />
                </button>
              )}
            </div>
            <NumInput
              className="sm"
              value={row.quantity}
              placeholder={qtyStepText}
              step={qtyStepText}
              min={0}
              max={posQty}
              unit={unit}
              onChange={v => setRow(i, { quantity: v })}
            />
            {/* 开仓面板里数量还没填时没有仓位可分，百分比先不出 */}
            {posQty > 0 && (
              <PctRow active={activePct(row.quantity)} onPick={p => setRow(i, { quantity: qtyByPct(posQty, p * 100, minQty) })} />
            )}
            {est && (
              <div className="est">
                <span>{t('sltp.trigger', { price: priceFormatter(parseFloat(row.price)) })}</span>
                <span className={est.value >= 0 ? 'up' : 'dn'}>
                  {t('sltp.estPnl', { value: `${est.value >= 0 ? '+' : ''}${fmtNum(est.value)}` })}
                </span>
                <span className={est.roi >= 0 ? 'up' : 'dn'}>
                  {t('sltp.roi', { roi: `${est.roi >= 0 ? '+' : ''}${est.roi.toFixed(1)}` })}
                </span>
              </div>
            )}
          </div>
        );
      })}
      <div className="alloc">
        {rows.length < 4
          ? <button type="button" className="btn sm" onClick={addRow}>{t('sltp.add')}</button>
          : <span />}
        {/* 各档加起来占了仓位的几成，超了变红；开仓面板里数量还没填时没有可分配的量，不出 */}
        {posQty > 0 && (
          <span className={cn(over && 'dn')}>
            {t('sltp.allocated', { pct: Number((total / posQty * 100).toFixed(1)) })}
          </span>
        )}
      </div>
    </div>
  );
}
