import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, RefreshCw, Search, TriangleAlert } from 'lucide-react';
import {
  alpha, COVERAGE_COLUMNS, type CoverageColumn, type DataStatus, type GridStatus, type PanelStatus, type RefreshJob,
} from '../api';
import { useBusy } from '../busy';
import { cx, DASH, localTime, pct, utcDay, utcMinute } from '../format';
import { useSort, type SortState } from '../sort';
import { useFail } from '../toast';
import { Badge, Button, Card, Empty, Progress, SortTh, Stat, Strip, Tip, Waiting } from '../components/ui';

export function DataPanel({ status, active, admin, onReload }: {
  status: DataStatus;
  active: boolean;
  admin: boolean;
  onReload: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const fail = useFail();
  const blocked = useBusy(active && admin);
  const [starting, setStarting] = useState(false);
  const { facts, refresh, pool } = status;
  const products = facts?.symbols ?? [];

  const start = async () => {
    setStarting(true);
    try {
      await alpha.refresh();
      await onReload();
    } catch (err) {
      fail(err);
    } finally {
      setStarting(false);
    }
  };

  return (
    <div className="space-y-14">
      <Card title={t('data.title')} hint={t('data.refreshHint')} more={t('data.refreshMore')}
            actions={admin && (
              <Button variant="primary" icon={RefreshCw} loading={starting || refresh.running}
                      disabled={!!blocked?.refresh} onClick={() => void start()}>
                {refresh.running ? t('data.refreshing') : t('data.refresh')}
              </Button>
            )}>
        {!refresh.running && <Waiting why={blocked?.refresh} className="mb-4" />}
        {status.loaded && facts ? (
          <div className="space-y-5">
            <Strip cols={4}>
              <Stat label={t('data.range')} size="sm">{`${facts.from} → ${facts.to}`}</Stat>
              <Stat label={t('data.rows')}>{facts.rows.toLocaleString()}</Stat>
              <Stat label={t('data.liqFrom')} size="sm">{facts.liqFrom ?? t('data.noLiq')}</Stat>
              <Stat label={t('data.holdoutFrom')} hint={t('data.holdoutHint')} size="sm">{facts.holdoutFrom}</Stat>
              <Stat label={t('data.cost')} hint={t('data.costHint')} size="sm">{`${facts.costTiers.join(' / ')} bps`}</Stat>
              <Stat label={t('data.references')}>{status.referenceCount}</Stat>
              <Stat label={t('data.loadedAt')} size="sm">{localTime(status.loadedAt)}</Stat>
            </Strip>
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="mr-1.5 text-[12.5px] text-muted">{t('data.products')}<Tip text={t('data.productsTip')} /></span>
              {facts.symbols.map(s => <Badge key={s} tone="primary">{s}</Badge>)}
            </div>
          </div>
        ) : <Empty>{t('data.none')}</Empty>}
        <RefreshProgress job={refresh} />
      </Card>

      {status.loaded && (
        <Card title={t('data.productTitle')} hint={t('data.productHint')}>
          <PanelTable status={status.symbols} products={products} initial={{ key: 'symbol', dir: 'asc' }} />
        </Card>
      )}

      <PoolCard pool={pool} products={products} />
    </div>
  );
}

/** 刷新进度：阶段、当前币、完成数；跑完留着完成时间和出错信息 */
function RefreshProgress({ job }: { job: RefreshJob }) {
  const { t } = useTranslation();
  if (!job.startedAt) return null;
  return (
    <div className="mt-5 space-y-2.5 border-t border-line pt-4">
      {job.running && job.stage && (
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px]">
            <span className="font-semibold">{t(`data.stage.${job.stage}`)}</span>
            <span className="num">{job.done ?? 0} / {job.total ?? 0}</span>
            {job.symbol && <span className="num text-muted">{t('data.current', { symbol: job.symbol })}</span>}
            <span className="ml-auto text-[12px] text-muted">{t('data.startedAt', { time: localTime(job.startedAt) })}</span>
          </div>
          <Progress done={job.done ?? 0} total={job.total ?? 0} />
        </div>
      )}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-1.5 text-[12.5px] text-muted">
        {job.candidates != null && <span>{t('data.candidates')} <b className="num text-fg">{job.candidates}</b></span>}
        {job.members != null && <span>{t('data.members')} <b className="num text-fg">{job.members}</b></span>}
        {job.thin && Object.keys(job.thin).length > 0 && (
          <span title={Object.entries(job.thin).map(([s, why]) => `${s}: ${why}`).join('\n')}>
            {t('data.thin')} <b className="num text-fg">{Object.keys(job.thin).length}</b>
            <span className="ml-1 num">{Object.keys(job.thin).join(' ')}</span>
          </span>
        )}
        {job.underlyingTypes && (
          <span className="inline-flex flex-wrap items-center gap-1">
            {t('data.underlyingTypes')}
            {job.underlyingTypes.map(u => <Badge key={u}>{u}</Badge>)}
          </span>
        )}
        {!job.running && job.finishedAt && <span>{t('data.finishedAt', { time: localTime(job.finishedAt) })}</span>}
      </div>
      {job.error && (
        <p className="flex items-start gap-1.5 text-[13px] text-loss break-words">
          <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('data.error', { msg: job.error })}
        </p>
      )}
    </div>
  );
}

function PoolCard({ pool, products }: { pool: DataStatus['pool']; products: string[] }) {
  const { t } = useTranslation();
  const months = Object.entries(pool.months);
  const sizes = months.map(([, n]) => n);
  return (
    <Card title={t('pool.title')} hint={t('pool.hint')}>
      {months.length === 0 ? <Empty>{t('pool.empty')}</Empty> : (
        <div className="space-y-6">
          <Strip cols={5}>
            <Stat label={t('pool.symbols')}>{pool.symbols}</Stat>
            <Stat label={t('pool.months')} size="sm">{`${months.length}（${months[0][0]} → ${months[months.length - 1][0]}）`}</Stat>
            <Stat label={t('pool.latest')}>{sizes[sizes.length - 1]}</Stat>
            <Stat label={t('pool.peak')}>{Math.max(...sizes)}</Stat>
            {pool.loaded && (
              <Stat label={t('pool.loaded')} hint={t('pool.loadedHint')} size="sm">
                {t('pool.loadedValue', { symbols: pool.loaded.symbols, points: pool.loaded.gridPoints.toLocaleString(),
                  rows: pool.loaded.rows.toLocaleString() })}
              </Stat>
            )}
          </Strip>
          <GridLine grid={pool.grid} />
          <PoolBars months={months} />
        </div>
      )}
      {Object.keys(pool.status).length > 0 && (
        <div className="mt-10 space-y-3 border-t border-fg pt-4">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className="text-[14px] font-extrabold">{t('pool.status')}</span>
            <span className="text-[12.5px] text-muted">{t('pool.statusHint')}</span>
          </div>
          <PanelTable status={pool.status} products={products} filterable initial={{ key: 'poolMonths', dir: 'desc' }} />
        </div>
      )}
    </Card>
  );
}

/** 参照网格一行：就绪、补算中（进度）、没就绪（G6 先不过） */
function GridLine({ grid }: { grid: GridStatus }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[12.5px]">
      <span className="text-muted">{t('pool.grid')}<Tip text={t('pool.gridTip')} /></span>
      {grid.ready
        ? <Badge tone="gain" icon={Check}>{t('pool.gridReady')}</Badge>
        : <Badge tone="warn" icon={TriangleAlert}>{t('pool.gridNotReady')}</Badge>}
      {grid.running && (
        <span className="num text-muted">
          {t('pool.gridRunning', { done: grid.done ?? 0, total: grid.total ?? 0 })}
          {grid.symbol && <span className="ml-2">{t('data.current', { symbol: grid.symbol })}</span>}
        </span>
      )}
      {grid.error && <span className="text-loss break-words">{t('pool.gridError', { msg: grid.error })}</span>}
    </div>
  );
}

/** 每月池子大小，一月一根柱，从底升起依次登场；最近一月墨色，鼠标指上去变橙、右上角读数 */
function PoolBars({ months }: { months: [string, number][] }) {
  const { t } = useTranslation();
  const [hover, setHover] = useState<[string, number] | null>(null);
  const max = Math.max(...months.map(([, n]) => n));
  return (
    <div>
      <div className="flex items-baseline justify-between text-[12.5px] text-muted">
        <span>{t('pool.sizes')}</span>
        <span className="num font-bold text-fg">{hover ? t('pool.monthTip', { month: hover[0], n: hover[1] }) : ''}</span>
      </div>
      <div className="bars mt-2" onMouseLeave={() => setHover(null)}>
        {months.map(([m, n], i) => (
          <div key={m} className="b" onMouseEnter={() => setHover([m, n])}>
            <i className={cx(hover?.[0] === m ? 'on' : i === months.length - 1 && 'last')}
               style={{ height: `${(n / max) * 100}%`, transitionDelay: `${i * 8}ms, 0ms` }} />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex gap-[2px] text-[11px] text-muted num">
        {months.map(([m]) => (
          <div key={m} className="flex-1 min-w-px whitespace-nowrap">{m.endsWith('-01') ? m.slice(0, 4) : ''}</div>
        ))}
      </div>
    </div>
  );
}

interface PanelRow {
  symbol: string;
  s: PanelStatus;
  product: boolean;
}

type PanelKey = 'symbol' | 'rows' | 'from' | 'to' | 'liqFrom' | 'gaps' | 'poolMonths' | CoverageColumn;

const PANEL_VALUES = {
  symbol: (r: PanelRow) => r.symbol,
  rows: (r: PanelRow) => r.s.rows,
  from: (r: PanelRow) => r.s.from,
  to: (r: PanelRow) => r.s.to,
  liqFrom: (r: PanelRow) => r.s.liqFrom,
  gaps: (r: PanelRow) => r.s.gaps,
  poolMonths: (r: PanelRow) => r.s.poolMonths,
  ...Object.fromEntries(COVERAGE_COLUMNS.map(c => [c, (r: PanelRow) => r.s.coverage[c]])),
} as Record<PanelKey, (r: PanelRow) => string | number | null | undefined>;

/** 覆盖率：数字 + 一截小条 */
function Coverage({ v }: { v: number | undefined }) {
  if (v == null) return <>{DASH}</>;
  return (
    <span className="inline-flex items-center justify-end gap-1.5">
      {pct(v)}
      <span className="mbar"><i style={{ width: `${v * 100}%` }} /></span>
    </span>
  );
}

/** 面板统计表：产品币和全部面板共用；可排序，filterable 时可按币名筛、表身在框里滚 */
function PanelTable({ status, products, filterable, initial }: {
  status: Record<string, PanelStatus>;
  products: string[];
  filterable?: boolean;
  initial: SortState<PanelKey>;
}) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const rows = useMemo(() => Object.entries(status)
    .map(([symbol, s]) => ({ symbol, s, product: products.includes(symbol) })), [status, products]);
  const filtered = useMemo(() => {
    const q = query.trim().toUpperCase();
    return q ? rows.filter(r => r.symbol.includes(q)) : rows;
  }, [rows, query]);
  const { sorted, sort, toggle } = useSort(filtered, PANEL_VALUES, initial);
  const th = (k: PanelKey, label: string, right = true) =>
    <SortTh key={k} k={k} sort={sort} onSort={toggle} right={right}>{label}</SortTh>;

  return (
    <div className="space-y-3">
      {filterable && (
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted" />
            <input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('common.filter')}
                   className="input w-60 pl-8" />
          </div>
          <span className="text-[12.5px] text-muted num">{t('common.shown', { n: filtered.length, total: rows.length })}</span>
        </div>
      )}
      <div className={cx('tw', filterable && 'max-h-[32rem] overflow-y-auto')}>
        <table className="tbl tbl-sticky">
          <thead>
            <tr>
              <th colSpan={6} className="border-b-0" />
              <th colSpan={COVERAGE_COLUMNS.length} className="text-center border-b-line">{t('panel.coverage')}</th>
              <th className="border-b-0" />
            </tr>
            <tr>
              {th('symbol', t('panel.symbol'), false)}
              {th('rows', t('panel.rows'))}
              {th('from', t('panel.from'))}
              {th('to', t('panel.to'))}
              {th('liqFrom', t('panel.liqFrom'))}
              {th('gaps', t('panel.gaps'))}
              {COVERAGE_COLUMNS.map(c => th(c, t(`panel.cov.${c}`)))}
              {th('poolMonths', t('panel.poolMonths'))}
            </tr>
          </thead>
          <tbody>
            {sorted.map(({ symbol, s, product }) => (
              <tr key={symbol}>
                <td className={cx('num whitespace-nowrap font-mono text-[12.5px]', product && 'text-primary font-bold')}
                    title={product ? t('panel.product') : undefined}>{symbol}</td>
                <td className="r num">{s.rows.toLocaleString()}</td>
                <td className="r num whitespace-nowrap">{utcDay(s.from)}</td>
                <td className="r num whitespace-nowrap">{utcMinute(s.to)}</td>
                <td className="r num whitespace-nowrap">{utcDay(s.liqFrom)}</td>
                <td className={cx('r num', s.gaps > 0 && 'text-warn')}>{s.gaps}</td>
                {COVERAGE_COLUMNS.map(c => <td key={c} className="r num"><Coverage v={s.coverage[c]} /></td>)}
                <td className="r num">{s.poolMonths ?? DASH}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {sorted.length === 0 && <Empty>{t('pool.noMatch')}</Empty>}
      </div>
    </div>
  );
}
