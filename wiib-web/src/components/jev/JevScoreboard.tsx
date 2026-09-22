import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';
import { pct } from './format';
import type { JevCalibrationBucket, JevCheckpointBrier, JevPredictionStats } from '../../types';

const TH = 'text-[12px] font-semibold mute text-right px-2 pb-2 border-b border-foreground first:pl-0 first:text-left last:pr-0';
const TD = 'px-2 py-2 border-b border-border text-[13.5px] text-right first:pl-0 first:text-left last:pr-0';

function brier(v?: number): string {
  return v == null ? '--' : v.toFixed(3);
}

/** 三列 Brier，每行最低的标橙；合计行取总体统计 */
export function BrierTable({ rows, total }: { rows: JevCheckpointBrier[]; total?: JevPredictionStats }) {
  const { t } = useTranslation(['community']);
  const all: JevCheckpointBrier[] = [...rows];
  if (total && total.scored > 0) {
    all.push({ checkpoint: 'total', n: total.scored, brierModel: total.brierModel, brierJev: total.brierJev, brierMkt: total.brierMkt });
  }
  if (all.length === 0) {
    return <p className="text-[13px] mute py-1">{t('prediction.jev.noScored')}</p>;
  }
  return (
    <table className="num w-full border-collapse">
      <thead>
        <tr>
          <th className={TH}>{t('prediction.jev.colCheckpoint')}</th>
          <th className={TH}>{t('prediction.jev.colN')}</th>
          <th className={TH}>{t('prediction.jev.colModel')}</th>
          <th className={TH}>{t('prediction.jev.colJev')}</th>
          <th className={TH}>{t('prediction.jev.colMkt')}</th>
        </tr>
      </thead>
      <tbody>
        {all.map(r => {
          const isTotal = r.checkpoint === 'total';
          const vals = [r.brierModel, r.brierJev, r.brierMkt];
          const best = Math.min(...vals.filter((v): v is number => v != null));
          return (
            <tr key={r.checkpoint} className={isTotal ? 'font-semibold' : undefined}>
              <td className={TD}>{isTotal ? t('prediction.jev.total') : t('prediction.jev.checkpointSec', { s: r.checkpoint.slice(1) })}</td>
              <td className={cn(TD, 'mute')}>{r.n}</td>
              {vals.map((v, i) => (
                <td key={i} className={cn(TD, v === best ? 'font-bold text-primary' : 'mute')}>{brier(v)}</td>
              ))}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** 一档：上面一行档位、样本、预测与实际，下面整宽一根条——条是实际涨的比例，竖线是平均预测 */
export function CalibrationRow({ b }: { b: JevCalibrationBucket }) {
  const { t } = useTranslation(['community']);
  // width_bucket(p, 0, 1, 5)：1..5 对应 0-20% … 80-100%
  const lo = (b.bucket - 1) * 20;
  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-[13px] num">
        <span><b className="font-semibold">{lo}–{lo + 20}%</b> <span className="mute">{t('prediction.jev.calRow', { n: b.n })}</span></span>
        <span className="mute">{t('prediction.jev.calVals', { mean: pct(b.meanP), hit: pct(b.hitRate) })}</span>
      </div>
      <div className="relative h-2 mt-1.5 bg-border">
        <div className="absolute inset-y-0 left-0 bg-foreground/60" style={{ width: `${b.hitRate * 100}%` }} />
        <div className="absolute -inset-y-1 w-0.5 bg-primary" style={{ left: `calc(${b.meanP * 100}% - 1px)` }} />
      </div>
    </div>
  );
}
