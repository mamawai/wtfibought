import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, ChevronDown, Play, Search, TriangleAlert, X } from 'lucide-react';
import { alpha, type ReferenceItem, type ReferenceList } from '../api';
import { useBusy } from '../busy';
import { cx, localTime, num, primaryOf } from '../format';
import { usePoll } from '../poll';
import { useSort } from '../sort';
import { useFail } from '../toast';
import { BriefTable, GateList } from '../components/FactorCard';
import { Button, Card, Empty, Progress, SortTh, Spinner, Waiting } from '../components/ui';

/** Alpha158 改写用的窗口（reference.ALPHA158_WINDOWS），单位 15m bar */
const WINDOWS: [number, string][] = [[4, '1h'], [16, '4h'], [48, '12h'], [96, '24h'], [192, '48h']];

type RefKey = 'name' | 'passed' | 'ic' | 'icir' | 'monotonic' | 'updatedAt';

const REF_VALUES: Record<RefKey, (r: ReferenceItem) => string | number | null | undefined> = {
  name: r => r.name,
  passed: r => (r.passed ? 1 : 0),
  // 主档 IC 按绝对值排：尺子看的是强度
  ic: r => {
    const v = r.brief.horizons[primaryOf(r.brief)]?.meanIc;
    return v == null ? v : Math.abs(v);
  },
  icir: r => r.brief.horizons[primaryOf(r.brief)]?.icir,
  monotonic: r => r.brief.horizons[primaryOf(r.brief)]?.monotonic,
  updatedAt: r => r.updatedAt,
};

export function ReferencePanel({ active, admin }: { active: boolean; admin: boolean }) {
  const { t } = useTranslation();
  const fail = useFail();
  const blocked = useBusy(active && admin);
  const [list, setList] = useState<ReferenceList | null>(null);
  const [windows, setWindows] = useState<number[]>([]);
  const [starting, setStarting] = useState(false);
  const [query, setQuery] = useState('');
  const [openName, setOpenName] = useState<string | null>(null);

  const load = useCallback(() => alpha.reference().then(setList).catch(fail), [fail]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const running = list?.job.running ?? false;
  usePoll(running, load, 3000);

  const items = useMemo(() => list?.items ?? [], [list]);
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? items.filter(i => i.name.toLowerCase().includes(q)) : items;
  }, [items, query]);
  const { sorted, sort, toggle } = useSort(filtered, REF_VALUES, { key: 'ic', dir: 'desc' });

  const start = async () => {
    setStarting(true);
    try {
      await alpha.referenceRun(windows.length ? windows : null);
      await load();
    } catch (err) {
      fail(err);
    } finally {
      setStarting(false);
    }
  };

  const toggleWindow = (w: number) =>
    setWindows(ws => (ws.includes(w) ? ws.filter(x => x !== w) : [...ws, w].sort((a, b) => a - b)));

  const th = (k: RefKey, label: string, right = true) =>
    <SortTh key={k} k={k} sort={sort} onSort={toggle} right={right}>{label}</SortTh>;

  if (!list) return <Spinner />;
  const { job } = list;
  const stale = items.filter(i => i.standard !== list.standard).length;

  return (
    <div className="space-y-14">
      <Card title={t('reference.title')} hint={t('reference.hint')}>
        {admin && (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <span className="mr-1 text-[12.5px] text-muted">{t('reference.windows')}</span>
              {/* 选中的窗口填墨 */}
              {WINDOWS.map(([w, label]) => (
                <button key={w} onClick={() => toggleWindow(w)} aria-pressed={windows.includes(w)}
                        className={cx('h-[30px] px-2.5 border border-fg text-[12.5px] font-semibold num cursor-pointer transition-colors',
                          windows.includes(w) ? 'bg-fg text-bg' : 'text-muted hover:bg-panel-2 hover:text-fg')}>
                  {label}<span className="ml-1 opacity-60">({w})</span>
                </button>
              ))}
              <Button variant="primary" icon={Play} loading={starting || running} disabled={!!blocked?.reference}
                      onClick={() => void start()} className="ml-auto">
                {running ? t('reference.running') : t('reference.run')}
              </Button>
            </div>
            <p className="mt-2 text-[12px] text-muted">{t('reference.windowsHint')}</p>
            {!running && <Waiting why={blocked?.reference} className="mt-1" />}
          </>
        )}
        {job.startedAt && (
          <div className={cx('space-y-2', admin && 'mt-5 border-t border-line pt-4')}>
            {running && (
              <>
                <div className="flex items-baseline gap-3 text-[13px]">
                  <span className="num font-semibold">{t('reference.progress', { done: job.done ?? 0, total: job.total ?? 0 })}</span>
                  <span className="ml-auto text-[12px] text-muted">{t('reference.startedAt', { time: localTime(job.startedAt) })}</span>
                </div>
                <Progress done={job.done ?? 0} total={job.total ?? 0} />
              </>
            )}
            {!running && job.finishedAt && (
              <p className="text-[12.5px] text-muted">{t('reference.finishedAt', { time: localTime(job.finishedAt) })}</p>
            )}
            {job.error && (
              <p className="flex items-start gap-1.5 text-[13px] text-loss break-words">
                <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('reference.error', { msg: job.error })}
              </p>
            )}
          </div>
        )}
      </Card>

      <Card>
        {items.length === 0 ? <Empty>{t('reference.empty')}</Empty> : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted" />
                <input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('reference.filter')}
                       className="input w-60 pl-8" />
              </div>
              <span className="text-[12.5px] text-muted num">{t('common.shown', { n: filtered.length, total: items.length })}</span>
              <span className="ml-auto text-[13px] font-semibold num">
                {t('reference.passed', { n: items.filter(i => i.passed).length, total: items.length })}
              </span>
            </div>
            {/* 过没过是按哪版口径判的：有旧版算的就提醒重跑 */}
            {stale > 0 ? (
              <p className="flex items-center gap-1.5 text-[12px] text-warn">
                <TriangleAlert className="w-3 h-3" />{t('reference.standardStale', { n: stale, now: list.standard })}
              </p>
            ) : <p className="text-[12px] text-muted">{t('reference.standard', { id: list.standard })}</p>}
            <div className="tw max-h-[40rem] overflow-y-auto">
              <table className="tbl tbl-sticky">
                <thead>
                  <tr>
                    {th('name', t('reference.col.name'), false)}
                    <th>{t('reference.col.expr')}</th>
                    {th('passed', t('reference.col.passed'))}
                    {th('ic', t('reference.col.ic'))}
                    {th('icir', t('reference.col.icir'))}
                    {th('monotonic', t('reference.col.monotonic'))}
                    {th('updatedAt', t('reference.col.updatedAt'))}
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {sorted.map(item => {
                    const h = item.brief.horizons[primaryOf(item.brief)];
                    const open = openName === item.name;
                    return (
                      <Fragment key={item.name}>
                        <tr onClick={() => setOpenName(open ? null : item.name)} className="cursor-pointer">
                          <td className="font-mono text-[12.5px] font-bold whitespace-nowrap">{item.name}</td>
                          <td className="max-w-[26rem]"><code className="block truncate text-[12px] text-muted" title={item.expr}>{item.expr}</code></td>
                          <td className="r">
                            {item.passed
                              ? <Check className="w-3.5 h-3.5 inline-flex text-gain" />
                              : <X className="w-3.5 h-3.5 inline-flex text-muted" />}
                          </td>
                          <td className="r num">{num(h?.meanIc)}</td>
                          <td className="r num">{num(h?.icir, 2)}</td>
                          <td className="r num">{num(h?.monotonic, 2)}</td>
                          <td className="r num whitespace-nowrap">{localTime(item.updatedAt)}</td>
                          <td className="r"><ChevronDown className={cx('w-3.5 h-3.5 inline-flex text-muted transition-transform duration-300', open && 'rotate-180')} /></td>
                        </tr>
                        {open && (
                          <tr>
                            <td colSpan={8} className="expand">
                              <div className="grid gap-x-8 gap-y-5 xl:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
                                <GateList gates={item.gates} />
                                <BriefTable brief={item.brief} />
                              </div>
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
              {sorted.length === 0 && <Empty>{t('reference.noMatch')}</Empty>}
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}
