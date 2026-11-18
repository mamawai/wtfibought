import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Lock, RefreshCw } from 'lucide-react';
import { alpha, CANDIDATE_STATUSES, type Overview, type StatusCounts } from '../api';
import { localTime } from '../format';
import { useFail } from '../toast';
import { RunBadge, StatusBadge } from '../components/FactorCard';
import { Button, Card, Empty, Spinner, Stat, Strip } from '../components/ui';

/** 挖掘页顶上的三个数：只读和管理员都有 */
export function OverviewStats({ trials, factors, observing }: { trials: number; factors: number; observing: number }) {
  const { t } = useTranslation();
  return (
    <Strip cols={3} boxed>
      <Stat label={t('overview.trials')} hint={t('overview.trialsHint')}>{trials}</Stat>
      <Stat label={t('overview.observing')}>{observing}</Stat>
      <Stat label={t('overview.factors')}>{factors}</Stat>
    </Strip>
  );
}

/** 一轮各状态几条，按流程先后排 */
export function StatusCountBadges({ counts }: { counts: StatusCounts }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {CANDIDATE_STATUSES.filter(s => counts[s]).map(s => <StatusBadge key={s} status={s} count={counts[s]} />)}
    </div>
  );
}

function AdminOnly() {
  const { t } = useTranslation();
  return (
    <p className="flex items-center gap-1.5 text-[12.5px] text-muted">
      <Lock className="w-3 h-3" />{t('overview.adminOnly')}
    </p>
  );
}

function useOverview(active: boolean) {
  const fail = useFail();
  const [overview, setOverview] = useState<Overview | null>(null);
  const load = useCallback(() => alpha.overview().then(setOverview).catch(fail), [fail]);
  useEffect(() => {
    if (active) void load();
  }, [active, load]);
  return { overview, load };
}

/** 只读的挖掘页：只有数字，每轮各状态候选几条 */
export function GuestMine({ active }: { active: boolean }) {
  const { t } = useTranslation();
  const { overview, load } = useOverview(active);
  if (!overview) return <Spinner />;
  return (
    <div className="space-y-14">
      <OverviewStats trials={overview.trials} factors={overview.factors} observing={overview.observing} />
      <Card title={t('run.list')}
            actions={<Button variant="icon" icon={RefreshCw} aria-label={t('common.refresh')} onClick={() => void load()} />}>
        {overview.runs.length === 0 ? <Empty>{t('run.noRun')}</Empty> : (
          <div className="tw">
            <table className="tbl">
              <thead>
                <tr>
                  <th>{t('run.col.time')}</th>
                  <th>{t('run.col.status')}</th>
                  <th className="r">{t('run.col.k')}</th>
                  <th>{t('overview.candidates')}</th>
                </tr>
              </thead>
              <tbody>
                {overview.runs.map(r => (
                  <tr key={r.id}>
                    <td className="num whitespace-nowrap" title={r.id}>{localTime(r.created_at)}</td>
                    <td><RunBadge status={r.status} /></td>
                    <td className="r num">{r.k}</td>
                    <td><StatusCountBadges counts={r.counts} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="mt-4"><AdminOnly /></div>
      </Card>
    </div>
  );
}
