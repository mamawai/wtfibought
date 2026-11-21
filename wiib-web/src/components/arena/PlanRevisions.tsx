import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { fmtDateTime } from '../../lib/utils';
import { planRevisions } from './traderTools';

/** 「改了什么」不超过这么多字就放抬头加粗（价量、止损价这类）；更长的（加仓时被盖掉的旧论点）放到下面 */
const BRIEF_MAX = 60;

/**
 * 计划的修订记录（持仓卡和做完的交易共用，样式见 ArenaDetail.css .revs）：
 * 每条抬头是时刻、类型、改了什么，理由另起一行。labelKey 是「改了几次」那句用哪条词
 */
export function PlanRevisions({ json, labelKey }: { json: string | null; labelKey: 'plan.revisions' | 'wb.hold.revised' }) {
  const { t } = useTranslation('ai');
  const revisions = useMemo(() => planRevisions(json), [json]);
  if (!revisions.length) return null;
  return (
    <div className="revs">
      <span>{t(labelKey, { count: revisions.length })}</span>
      <ul>
        {revisions.map((v, i) => {
          const brief = v.change && v.change.length <= BRIEF_MAX;
          return (
            <li key={i}>
              <div>
                <time>{fmtDateTime(v.time)}</time>
                <span className="tag sm">{v.type}</span>
                {brief && <b>{v.change}</b>}
              </div>
              {v.change && !brief && <p>{v.change}</p>}
              {v.reason && <p>{v.reason}</p>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
