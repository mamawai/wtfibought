import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { cn, fmtNum } from '../../lib/utils';
import { entryStatus, planEntries } from './traderTools';

/** 计划里的逐档入场安排（持仓卡和做完的交易共用，样式见 ArenaDetail.css .ents）：价 × 量、加仓、成交状态、成交价、理由 */
export function PlanEntries({ json }: { json: string | null }) {
  const { t } = useTranslation('ai');
  const entries = useMemo(() => planEntries(json), [json]);
  if (!entries.length) return null;
  return (
    <div className="ents">
      <span>{t('plan.entries')}</span>
      <ul>
        {entries.map((entry, index) => (
          <li key={entry.orderId ?? index}>
            <b>{entry.limitPrice == null ? t('plan.marketEntry') : fmtNum(entry.limitPrice, 8)} × {fmtNum(entry.quantity, 8)}</b>
            {entry.add && <span>{t('plan.addEntry')}</span>}
            <span className={cn(entry.status === 'UNKNOWN' && 'wn')}>{entryStatus(entry.status)}</span>
            {entry.filledPrice != null && <span>{t('plan.filledAt', { price: fmtNum(entry.filledPrice, 8) })}</span>}
            {entry.reason && <span>{entry.reason}</span>}
          </li>
        ))}
      </ul>
    </div>
  );
}
