import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Archive, ArchiveRestore, RefreshCw, ScanSearch } from 'lucide-react';
import { alpha, type ComboReport, type Factor, type FactorDetail, type Recheck, type ReevalJob } from '../api';
import { useBusy } from '../busy';
import { cx, HORIZON_BARS, HORIZONS, localTime, num, pct, primaryOf, utcMinute } from '../format';
import { usePoll } from '../poll';
import { useSort } from '../sort';
import { useFail } from '../toast';
import { BriefTable, ExprBlock, GateList, ReviewLine } from '../components/FactorCard';
import { Badge, Button, Card, Drawer, Empty, RawJson, SortTh, Spinner, Tip, Waiting } from '../components/ui';
import { ReevalBlock, ReevalProgress } from './ObservationPanel';

type FactorKey = 'name' | 'approvedAt' | 'ic' | 'icir' | 'holdout' | 'recheck' | 'reeval';

// 表里的主档数字：搜索段、holdout、入库后复查、复评；主档是这条入库时判门用的那一档，复评的是现在的主档
const primaryIc = (f: Factor) => f.brief.horizons[primaryOf(f.brief)]?.meanIc;
const primaryIcir = (f: Factor) => f.brief.horizons[primaryOf(f.brief)]?.icir;
const holdoutIc = (f: Factor) => f.brief.holdout?.horizons[primaryOf(f.brief)]?.meanIc;
const recheckIc = (f: Factor) =>
  f.recheck?.horizons[HORIZON_BARS[primaryOf(f.brief) as (typeof HORIZONS)[number]]]?.mean;
const reevalIc = (f: Factor) => f.reeval?.brief.horizons[primaryOf(f.reeval.brief)]?.meanIc;

const FACTOR_VALUES: Record<FactorKey, (f: Factor) => string | number | null | undefined> = {
  name: f => f.name,
  approvedAt: f => f.approved_at,
  ic: primaryIc,
  icir: primaryIcir,
  holdout: holdoutIc,
  recheck: recheckIc,
  reeval: reevalIc,
};

/** 管理员的因子库：在役列表、组合报告、详情与复查、退役与恢复；可以用当前评估器整批复评 */
export function LibraryPanel({ active, loaded }: { active: boolean; loaded: boolean }) {
  const { t } = useTranslation();
  const fail = useFail();
  const blocked = useBusy(active);
  const [factors, setFactors] = useState<Factor[] | null>(null);
  const [retired, setRetired] = useState<Factor[]>([]);
  const [combo, setCombo] = useState<ComboReport | null>(null);
  // 库刚变过，组合报告在后台重算：轮询到算完
  const [computing, setComputing] = useState(false);
  const [job, setJob] = useState<ReevalJob | null>(null);
  const [detail, setDetail] = useState<FactorDetail | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [rechecking, setRechecking] = useState(false);
  const [starting, setStarting] = useState(false);

  const load = useCallback(() => alpha.library()
    .then(r => { setFactors(r.factors); setRetired(r.retired); setCombo(r.combo); setComputing(r.computing); setJob(r.reeval); })
    .catch(fail), [fail]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const running = job?.running ?? false;
  usePoll(active && (running || computing), load, 3000);

  const reeval = async () => {
    setStarting(true);
    try {
      await alpha.libraryReeval();
      await load();
    } catch (err) {
      fail(err);
    } finally {
      setStarting(false);
    }
  };

  const { sorted, sort, toggle } = useSort(factors ?? [], FACTOR_VALUES, { key: 'approvedAt', dir: 'desc' });

  const open = (id: string) => {
    setOpenId(id);
    setDetail(null);
    alpha.factor(id).then(setDetail).catch(err => { fail(err); setOpenId(null); });
  };

  const close = useCallback(() => setOpenId(null), []);

  const recheck = async (id: string) => {
    setRechecking(true);
    try {
      const r = await alpha.recheck(id);
      setDetail(d => (d && d.id === id ? { ...d, recheck: r } : d));
      setFactors(list => list && list.map(f => (f.id === id ? { ...f, recheck: r } : f)));
    } catch (err) {
      fail(err);
    } finally {
      setRechecking(false);
    }
  };

  // 退役、恢复之后列表和详情都重新拿：在役、退役两张表都会变
  const changed = async (id: string) => {
    await load();
    setDetail(await alpha.factor(id));
  };

  const th = (k: FactorKey, label: string, right = true) =>
    <SortTh key={k} k={k} sort={sort} onSort={toggle} right={right}>{label}</SortTh>;

  return (
    <div className="space-y-14">
      {combo ? <ComboCard combo={combo} /> : factors && factors.length > 0 && (computing || !loaded) && (
        <Card><Empty>{computing ? <span className="inline-flex items-center gap-2"><Spinner />{t('library.comboComputing')}</span>
                                : t('library.comboNone')}</Empty></Card>
      )}

      <Card title={t('library.title')} hint={t('library.hint')}
            actions={<Button variant="icon" icon={RefreshCw} aria-label={t('common.refresh')} onClick={() => void load()} />}>
        {!factors ? <Spinner /> : factors.length === 0 ? <Empty>{t('library.empty')}</Empty> : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <p className="max-w-[90ch] text-[12.5px] leading-relaxed text-muted">
                {t('library.reevalHint')}<Tip text={t('library.reevalTip')} />
              </p>
              <Button icon={ScanSearch} loading={starting || running} disabled={!!blocked?.libraryReeval}
                      onClick={() => void reeval()} className="ml-auto">
                {running ? t('library.reevalRunning') : t('library.reeval')}
              </Button>
            </div>
            {!running && <Waiting why={blocked?.libraryReeval} />}
            {job && <ReevalProgress job={job} />}
            <div className="tw">
              <table className="tbl">
                <thead>
                  <tr>
                    {th('name', t('library.col.name'), false)}
                    <th>{t('library.col.expr')}</th>
                    {th('approvedAt', t('library.col.approvedAt'))}
                    {th('ic', t('library.col.ic'))}
                    {th('icir', t('library.col.icir'))}
                    {th('holdout', t('library.col.holdout'))}
                    {th('recheck', t('library.col.recheck'))}
                    {th('reeval', t('library.col.reeval'))}
                  </tr>
                </thead>
                <tbody>
                  {sorted.map(f => (
                    <tr key={f.id} onClick={() => open(f.id)} className="cursor-pointer">
                      <td className="font-mono text-[12.5px] font-bold whitespace-nowrap">{f.name}</td>
                      <td className="max-w-[28rem]"><code className="block truncate text-[12px] text-muted" title={f.expr}>{f.expr}</code></td>
                      <td className="r num whitespace-nowrap">{localTime(f.approved_at)}</td>
                      <td className="r num">{num(primaryIc(f))}</td>
                      <td className="r num">{num(primaryIcir(f), 2)}</td>
                      <td className="r num">{num(holdoutIc(f))}</td>
                      <td className="r num">{num(recheckIc(f))}</td>
                      <td className={cx('r num', f.reeval && !f.reeval.passed && 'text-loss')}>{num(reevalIc(f))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </Card>

      {retired.length > 0 && (
        <Card title={t('library.retiredTitle', { n: retired.length })} hint={t('library.retiredHint')}>
          <div className="tw">
            <table className="tbl">
              <thead>
                <tr>
                  <th>{t('library.col.name')}</th>
                  <th>{t('library.col.expr')}</th>
                  <th className="r">{t('library.col.retiredAt')}</th>
                  <th>{t('library.col.reason')}</th>
                </tr>
              </thead>
              <tbody>
                {retired.map(f => (
                  <tr key={f.id} onClick={() => open(f.id)} className="cursor-pointer">
                    <td className="font-mono text-[12.5px] font-bold whitespace-nowrap text-muted">{f.name}</td>
                    <td className="max-w-[24rem]"><code className="block truncate text-[12px] text-muted" title={f.expr}>{f.expr}</code></td>
                    <td className="r num whitespace-nowrap">{localTime(f.retired_at)}</td>
                    <td className="text-[12.5px]">{f.retire_reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Drawer open={openId !== null} onClose={close}
              title={detail
                ? <div className="flex items-center gap-2.5">
                    <span className="font-mono text-[16px] font-bold">{detail.name}</span>
                    {detail.retired_at == null
                      ? <Badge tone="gain">{t('status.approved')}</Badge>
                      : <Badge tone="muted" icon={Archive}>{t('status.retired')}</Badge>}
                  </div>
                : <Spinner />}>
        {detail && (
          <FactorDetailView f={detail} blocked={blocked?.recheck} rechecking={rechecking}
                            onRecheck={() => void recheck(detail.id)} onChanged={() => changed(detail.id)} />
        )}
      </Drawer>
    </div>
  );
}

/** blocked 是重算入库后表现被挡住的原因 */
function FactorDetailView({ f, blocked, rechecking, onRecheck, onChanged }: {
  f: FactorDetail;
  blocked: string | null | undefined;
  rechecking: boolean;
  onRecheck: () => void;
  onChanged: () => Promise<void>;
}) {
  const { t } = useTranslation();
  return (
    <>
      <ExprBlock expr={f.expr} />
      <dl className="grid gap-x-6 gap-y-3 text-[12.5px] sm:grid-cols-2">
        <div className="sm:col-span-2">
          <dt className="text-muted">{t('library.canonical')}<Tip text={t('library.canonicalTip')} /></dt>
          <dd><code className="break-all">{f.canonical}</code></dd>
        </div>
        <div>
          <dt className="text-muted">{t('library.columns')}</dt>
          <dd className="flex flex-wrap gap-1 pt-1">{f.columns.map(c => <Badge key={c}>${c}</Badge>)}</dd>
        </div>
        <div>
          <dt className="text-muted">{t('library.approvedAt')} · {t('library.runId')}</dt>
          <dd className="num">{localTime(f.approved_at)} · {f.run_id}</dd>
        </div>
      </dl>
      <div className="space-y-1.5">
        <div className="microlabel">{t('library.hypothesis')}</div>
        <p className="text-[14px] leading-relaxed">{f.hypothesis}</p>
      </div>
      {f.review && <ReviewLine review={f.review} />}
      <GateList gates={f.gates} />
      <BriefTable brief={f.brief} />
      {f.reeval && <ReevalBlock r={f.reeval} />}
      <section className="space-y-3 border-t border-fg pt-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[14px] font-extrabold">{t('library.recheck')}</span>
          <span className="text-[12.5px] text-muted">{t('library.recheckHint')}</span>
          <Button icon={RefreshCw} loading={rechecking} disabled={!!blocked} onClick={onRecheck} className="ml-auto">
            {t('library.recheckRun')}
          </Button>
        </div>
        <Waiting why={blocked} />
        {f.recheck ? <RecheckView recheck={f.recheck} primary={primaryOf(f.brief)} /> : <Empty>{t('library.recheckNone')}</Empty>}
      </section>
      <RetireSection f={f} onChanged={onChanged} />
      <RawJson value={f.eval} />
    </>
  );
}

/** 在役的填原因退役；退役的显示时间和原因，可以恢复 */
function RetireSection({ f, onChanged }: { f: FactorDetail; onChanged: () => Promise<void> }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const act = async (call: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await call();
      setReason('');
      await onChanged();
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  };

  if (f.retired_at != null) {
    return (
      <section className="space-y-3 border-t border-fg pt-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[14px] font-extrabold">{t('library.retireSection')}</span>
          <span className="text-[12.5px] text-muted">{t('library.restoreHint')}</span>
          <Button icon={ArchiveRestore} loading={busy} onClick={() => void act(() => alpha.restoreFactor(f.id))}
                  className="ml-auto">
            {t('library.restore')}
          </Button>
        </div>
        <p className="text-[13px]">{t('library.retiredInfo', { time: localTime(f.retired_at), reason: f.retire_reason })}</p>
      </section>
    );
  }
  return (
    <section className="space-y-3 border-t border-fg pt-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[14px] font-extrabold">{t('library.retireSection')}</span>
        <span className="text-[12.5px] text-muted">{t('library.retireHint')}</span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <input value={reason} onChange={e => setReason(e.target.value)} placeholder={t('library.retirePh')} maxLength={500}
               className="input min-w-0 flex-1" />
        <Button icon={Archive} loading={busy} disabled={reason.trim() === ''}
                onClick={() => void act(() => alpha.retireFactor(f.id, reason.trim()))}>
          {t('library.retireRun')}
        </Button>
      </div>
    </section>
  );
}

/** 复查结果：三档截面 IC，primary 是这条判门用的那一档 */
function RecheckView({ recheck, primary }: { recheck: Recheck; primary: string }) {
  const { t } = useTranslation();
  const cell = (h: (typeof HORIZONS)[number]) => recheck.horizons[HORIZON_BARS[h]];
  const rows: { key: string; get: (h: Recheck['horizons'][string]) => string | number }[] = [
    { key: 'meanIc', get: h => num(h.mean) },
    { key: 'icir', get: h => num(h.icir, 2) },
    { key: 'months', get: h => h.months },
    { key: 'monotonic', get: h => num(h.monotonic, 2) },
    { key: 'samples', get: h => h.samples },
  ];
  return (
    <div className="space-y-2">
      <p className="text-[12px] text-muted num">{t('library.recheckAt', { since: utcMinute(recheck.since), at: localTime(recheck.at) })}</p>
      <table className="tbl">
        <thead>
          <tr>
            <th>{t('brief.metric')}</th>
            {HORIZONS.map(h => <th key={h} className={cx('r', h === primary && 'text-primary')}>{h}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.key}>
              <td className="text-muted">{t(`brief.${r.key}`)}</td>
              {HORIZONS.map(h => <td key={h} className="r num">{cell(h) ? r.get(cell(h)) : '—'}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 正负对着中线画的小条 */
function Diverging({ v, max }: { v: number | null; max: number }) {
  const width = v == null || max === 0 ? 0 : Math.min(1, Math.abs(v) / max) * 50;
  return (
    <span className="dvg">
      {v != null && <i className={v >= 0 ? 'pos' : 'neg'} style={{ width: `${width}%` }} />}
    </span>
  );
}

function ComboCard({ combo }: { combo: ComboReport }) {
  const { t } = useTranslation();
  const maxWeight = Math.max(...combo.weights.map(w => Math.abs(w.weight)));
  // 权重是单腿每期净收益，万分之几的量级：显示成 bps 和占绝对值之和的比例
  const totalWeight = combo.weights.reduce((s, w) => s + Math.abs(w.weight), 0);
  // 截面信号按从强到弱排：排头的是组合看多的币
  const latest = useMemo(() => Object.entries(combo.latest).sort(([, a], [, b]) => (b ?? -Infinity) - (a ?? -Infinity)),
    [combo.latest]);
  const maxLatest = Math.max(0, ...latest.map(([, v]) => Math.abs(v ?? 0)));
  return (
    <Card title={t('library.combo', { n: combo.weights.length })} hint={t('library.comboHint')} more={t('library.comboMore')}>
      <div className="grid gap-x-10 gap-y-8 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <BriefTable brief={combo.brief} />
        <div className="space-y-6">
          <table className="tbl">
            <thead>
              <tr><th colSpan={4}>{t('library.weights')}</th></tr>
            </thead>
            <tbody>
              {combo.weights.map(w => (
                <tr key={w.name}>
                  <td className="font-mono text-[12.5px] font-bold">{w.name}</td>
                  <td className="r num">{num(w.weight * 1e4, 1)} bps</td>
                  <td className="r num text-muted">{totalWeight > 0 ? pct(Math.abs(w.weight) / totalWeight, 0) : '-'}</td>
                  <td className="w-32 align-middle"><Diverging v={w.weight} max={maxWeight} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="tw max-h-[28rem] overflow-y-auto">
            <table className="tbl tbl-sticky">
              <thead>
                <tr><th colSpan={3}>{t('library.latest', { time: utcMinute(combo.asOf) })}</th></tr>
              </thead>
              <tbody>
                {latest.map(([s, v]) => (
                  <tr key={s}>
                    <td className="num font-mono text-[12.5px]">{s}</td>
                    <td className="r num">{num(v, 3)}</td>
                    <td className="w-32 align-middle"><Diverging v={v} max={maxLatest} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </Card>
  );
}
