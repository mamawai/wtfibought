import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, Navigate } from 'react-router-dom';
import {
  Activity,
  ArrowLeft,
  BarChart3,
  Brain,
  Database,
  Gauge,
  RefreshCcw,
  Route,
  ShieldAlert,
} from 'lucide-react';
import { adminApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { useUserStore } from '../stores/userStore';
import { cn } from '../lib/utils';
import type {
  SprintCDashboard,
  SprintCFactorCoverage,
  SprintCFactorIrRow,
  SprintCPathStats,
} from '../types';

const DAY_OPTIONS = [7, 14, 30] as const;

const PATH_LABEL: Record<string, string> = {
  BREAKOUT: '突破',
  MR: '均值回归',
  LEGACY_TREND: '趋势兜底',
};

function toNumber(value: number | string | null | undefined) {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function fmtNumber(value: number | string | null | undefined, digits = 2) {
  const n = toNumber(value);
  if (n === null) return '-';
  return n.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

function fmtMoney(value: number | string | null | undefined) {
  const n = toNumber(value);
  if (n === null) return '-';
  const sign = n > 0 ? '+' : '';
  return `${sign}$${fmtNumber(n, 2)}`;
}

function fmtPct(value: number | string | null | undefined) {
  const n = toNumber(value);
  if (n === null) return '-';
  return `${(n * 100).toFixed(1)}%`;
}

function fmtTime(value: string | null | undefined) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function Panel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <section className={cn('rounded-lg border bg-card/80 neu-raised-sm', className)}>
      {children}
    </section>
  );
}

function PanelTitle({ icon, title, right }: { icon: ReactNode; title: string; right?: ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b px-4 py-3">
      <div className="flex items-center gap-2 text-sm font-black">
        <span className="text-primary">{icon}</span>
        {title}
      </div>
      {right}
    </div>
  );
}

function MetricTile({ label, value, sub, icon, tone = 'neutral' }: {
  label: string;
  value: string;
  sub?: string;
  icon: ReactNode;
  tone?: 'neutral' | 'good' | 'bad' | 'warn';
}) {
  return (
    <div className={cn(
      'rounded-lg border bg-background/50 p-3',
      tone === 'good' && 'border-gain/35',
      tone === 'bad' && 'border-loss/35',
      tone === 'warn' && 'border-warning/40',
    )}>
      <div className="flex items-center justify-between gap-3 text-[11px] font-bold text-muted-foreground">
        <span>{label}</span>
        <span className={cn(
          'inline-flex h-7 w-7 items-center justify-center rounded-md border bg-card',
          tone === 'good' && 'text-gain',
          tone === 'bad' && 'text-loss',
          tone === 'warn' && 'text-warning',
          tone === 'neutral' && 'text-primary',
        )}>
          {icon}
        </span>
      </div>
      <div className={cn(
        'mt-2 text-2xl font-black tabular-nums',
        tone === 'good' && 'text-gain',
        tone === 'bad' && 'text-loss',
        tone === 'warn' && 'text-warning',
      )}>
        {value}
      </div>
      {sub && <div className="mt-1 text-[11px] text-muted-foreground">{sub}</div>}
    </div>
  );
}

function PathCard({ path }: { path: SprintCPathStats }) {
  const pnl = toNumber(path.totalPnl);
  const tone = !path.enabled ? 'bad' : pnl !== null && pnl > 0 ? 'good' : pnl !== null && pnl < 0 ? 'warn' : 'neutral';
  return (
    <div className={cn(
      'rounded-lg border bg-background/50 p-3',
      tone === 'good' && 'border-gain/35',
      tone === 'bad' && 'border-loss/35',
      tone === 'warn' && 'border-warning/40',
    )}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-black">{PATH_LABEL[path.path] || path.path}</div>
          <div className="mt-0.5 text-[11px] font-bold text-muted-foreground">{path.path}</div>
        </div>
        <span className={cn(
          'rounded-md border px-2 py-1 text-[11px] font-black',
          path.enabled ? 'border-gain/30 text-gain' : 'border-loss/30 text-loss',
        )}>
          {path.enabled ? '启用' : '禁用'}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
        <MiniStat label="7天胜率" value={fmtPct(path.winRate)} />
        <MiniStat label="样本" value={String(path.samples ?? 0)} />
        <MiniStat label="EV" value={fmtMoney(path.ev)} />
        <MiniStat label="平均持仓" value={`${fmtNumber(path.avgHoldingMinutes, 0)}m`} />
      </div>
      <div className="mt-3 flex items-center justify-between rounded-md border bg-card/70 px-2 py-1.5 text-[11px]">
        <span className="text-muted-foreground">连亏</span>
        <span className={cn('font-black', path.consecutiveLossCount >= 5 && 'text-loss')}>
          {path.consecutiveLossCount}
        </span>
      </div>
      {!path.enabled && (
        <div className="mt-2 rounded-md border border-loss/30 bg-loss/5 px-2 py-1.5 text-[11px] text-loss">
          {path.disabledReason || '路径已禁用'}
        </div>
      )}
    </div>
  );
}

function MiniStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border bg-card/70 px-2 py-1.5">
      <div className="text-[10px] text-muted-foreground">{label}</div>
      <div className="mt-0.5 font-black tabular-nums">{value}</div>
    </div>
  );
}

function FactorIrTable({ rows }: { rows: SprintCFactorIrRow[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[720px] text-left text-xs">
        <thead className="text-[10px] uppercase text-muted-foreground">
          <tr className="border-b">
            <th className="px-4 py-2">Agent</th>
            <th className="px-3 py-2">Horizon</th>
            <th className="px-3 py-2">Regime</th>
            <th className="px-3 py-2 text-right">Samples</th>
            <th className="px-3 py-2 text-right">IR</th>
            <th className="px-3 py-2 text-right">Win</th>
            <th className="px-3 py-2 text-right">Mean bps</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.agent}-${row.horizon}-${row.regime}`} className="border-b last:border-b-0">
              <td className="px-4 py-2 font-black">{row.agent}</td>
              <td className="px-3 py-2 font-mono text-[11px]">{row.horizon}</td>
              <td className="px-3 py-2">{row.regime}</td>
              <td className="px-3 py-2 text-right tabular-nums">{row.samples}</td>
              <td className={cn('px-3 py-2 text-right font-black tabular-nums', (row.ir ?? 0) >= 0 ? 'text-gain' : 'text-loss')}>
                {fmtNumber(row.ir, 3)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">{fmtPct(row.winRate)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{fmtNumber(row.meanAlignedReturnBps, 2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function CoverageTable({ rows }: { rows: SprintCFactorCoverage[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] text-left text-xs">
        <thead className="text-[10px] uppercase text-muted-foreground">
          <tr className="border-b">
            <th className="px-4 py-2">Factor</th>
            <th className="px-3 py-2">Symbol</th>
            <th className="px-3 py-2">Freq</th>
            <th className="px-3 py-2 text-right">Samples</th>
            <th className="px-3 py-2">Coverage</th>
            <th className="px-3 py-2 text-right">Age</th>
            <th className="px-3 py-2 text-right">Latest</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const pct = Math.round((row.completeness ?? 0) * 100);
            return (
              <tr key={`${row.factorName}-${row.symbol}`} className="border-b last:border-b-0">
                <td className="px-4 py-2 font-black">{row.factorName}</td>
                <td className="px-3 py-2 font-mono text-[11px]">{row.symbol}</td>
                <td className="px-3 py-2">{row.frequency}</td>
                <td className="px-3 py-2 text-right tabular-nums">{row.samples}/{row.expectedSamples}</td>
                <td className="px-3 py-2">
                  <div className="flex items-center gap-2">
                    <div className="h-2 w-28 rounded-full bg-muted">
                      <div
                        className={cn('h-full rounded-full', pct >= 95 ? 'bg-gain' : pct >= 70 ? 'bg-warning' : 'bg-loss')}
                        style={{ width: `${Math.min(100, pct)}%` }}
                      />
                    </div>
                    <span className="w-10 text-right tabular-nums">{pct}%</span>
                  </div>
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{row.latestAgeHours == null ? '-' : `${fmtNumber(row.latestAgeHours, 1)}h`}</td>
                <td className="px-3 py-2 text-right tabular-nums">{fmtNumber(row.latestValue, 4)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function AdminSprintC() {
  const { user } = useUserStore();
  const { toast } = useToast();
  const [days, setDays] = useState<(typeof DAY_OPTIONS)[number]>(7);
  const [data, setData] = useState<SprintCDashboard | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await adminApi.sprintCDashboard(days));
    } catch (e) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [days, toast]);

  useEffect(() => {
    if (user?.id === 1) {
      void load();
    }
  }, [user, load]);

  const accountTone = useMemo(() => {
    const winRate = toNumber(data?.account.cumulative.winRate);
    if (winRate === null) return 'neutral';
    if (winRate >= 0.55) return 'good';
    if (winRate < 0.45) return 'bad';
    return 'warn';
  }, [data]);

  if (!user || user.id !== 1) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-5 md:px-6">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Link
            to="/admin"
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border bg-card text-muted-foreground neu-btn-sm hover:text-primary"
            aria-label="返回Admin"
          >
            <ArrowLeft className="h-4 w-4" />
          </Link>
          <div>
            <h1 className="text-xl font-black tracking-tight">Sprint C Control</h1>
            <div className="text-[11px] font-bold text-muted-foreground">
              {data ? `${fmtTime(data.from)} - ${fmtTime(data.to)}` : '闭环归因 / 熔断 / 外部因子'}
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex rounded-lg border bg-card p-1">
            {DAY_OPTIONS.map(option => (
              <button
                key={option}
                onClick={() => setDays(option)}
                className={cn(
                  'h-8 rounded-md px-3 text-xs font-black transition-colors',
                  days === option ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {option}D
              </button>
            ))}
          </div>
          <button
            onClick={() => void load()}
            className="inline-flex h-10 items-center gap-2 rounded-lg border bg-card px-3 text-xs font-black neu-btn-sm"
            disabled={loading}
          >
            <RefreshCcw className={cn('h-4 w-4', loading && 'animate-spin')} />
            刷新
          </button>
        </div>
      </div>

      {loading && !data ? (
        <div className="grid gap-3 md:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="h-24 animate-pulse rounded-lg border bg-muted/30" />
          ))}
        </div>
      ) : data && (
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <MetricTile
              label="今日开仓"
              value={String(data.account.todayOpenCount)}
              sub={`AI user #${data.account.aiUserId}`}
              icon={<Activity className="h-4 w-4" />}
            />
            <MetricTile
              label="累计胜率"
              value={fmtPct(data.account.cumulative.winRate)}
              sub={`${data.account.cumulative.samples} 笔归因`}
              icon={<BarChart3 className="h-4 w-4" />}
              tone={accountTone}
            />
            <MetricTile
              label="熔断状态"
              value={data.account.breaker.level}
              sub={data.account.breaker.enabled ? '开仓前实时拦截' : '开关关闭'}
              icon={<ShieldAlert className="h-4 w-4" />}
              tone={data.account.breaker.anyActive ? 'bad' : data.account.breaker.enabled ? 'good' : 'warn'}
            />
            <MetricTile
              label="5/7 Shadow"
              value={String(data.shadow5of7.samples ?? 0)}
              sub={`LONG ${data.shadow5of7.longSamples ?? 0} / SHORT ${data.shadow5of7.shortSamples ?? 0}`}
              icon={<Gauge className="h-4 w-4" />}
              tone="neutral"
            />
          </div>

          <div className="grid gap-4 lg:grid-cols-[1.4fr_0.9fr]">
            <Panel>
              <PanelTitle icon={<Route className="h-4 w-4" />} title="路径归因" />
              <div className="grid gap-3 p-3 md:grid-cols-3">
                {data.pathStats.map(path => <PathCard key={path.path} path={path} />)}
              </div>
            </Panel>

            <Panel>
              <PanelTitle icon={<ShieldAlert className="h-4 w-4" />} title="熔断明细" />
              <div className="space-y-2 p-3 text-xs">
                {[
                  ['L1 日亏损', data.account.breaker.l1Reason],
                  ['L2 连亏', data.account.breaker.l2Reason],
                  ['L3 回撤', data.account.breaker.l3Reason],
                ].map(([label, reason]) => (
                  <div key={label} className="flex items-center justify-between gap-3 rounded-md border bg-background/50 px-3 py-2">
                    <span className="font-bold">{label}</span>
                    <span className={cn('text-right font-mono text-[11px]', reason ? 'text-loss' : 'text-muted-foreground')}>
                      {reason || 'CLEAR'}
                    </span>
                  </div>
                ))}
                <div className="rounded-md border bg-card/70 px-3 py-2">
                  <div className="flex items-center justify-between">
                    <span className="font-bold">Peak Equity</span>
                    <span className="font-mono">{data.account.breaker.peakEquity || '-'}</span>
                  </div>
                </div>
                <div className="rounded-md border border-warning/40 bg-warning/5 px-3 py-2 text-warning">
                  Shadow 胜率未计算：当前只落 reasoning，没有 shadow 后验表。
                </div>
              </div>
            </Panel>
          </div>

          <div className="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
            <Panel>
              <PanelTitle icon={<Brain className="h-4 w-4" />} title="LLM 方差代理" />
              <div className="grid grid-cols-2 gap-3 p-3 md:grid-cols-4">
                <MiniStat label="重周期" value={String(data.llmVariance.cycles ?? 0)} />
                <MiniStat label="LOW_CONF" value={String(data.llmVariance.lowConfidenceCycles ?? 0)} />
                <MiniStat label="占比" value={fmtPct(data.llmVariance.lowConfidenceRate)} />
                <MiniStat label="高方差" value={String(data.llmVariance.highVarianceCycles ?? 0)} />
                <MiniStat label="Regime Conf" value={fmtPct(data.llmVariance.avgRegimeConfidence)} />
                <MiniStat label="Regime Std" value={fmtNumber(data.llmVariance.avgRegimeConfidenceStddev, 3)} />
                <MiniStat label="News Std" value={fmtNumber(data.llmVariance.avgNewsConfidenceStddev, 3)} />
                <MiniStat label="Transition" value={String(data.llmVariance.transitionCycles ?? 0)} />
              </div>
            </Panel>

            <Panel>
              <PanelTitle icon={<Database className="h-4 w-4" />} title="Shadow 样本" />
              <div className="grid grid-cols-2 gap-3 p-3">
                <MiniStat label="累计样本" value={String(data.shadow5of7.samples ?? 0)} />
                <MiniStat label="假设胜率" value={fmtPct(data.shadow5of7.hypotheticalWinRate)} />
                <MiniStat label="最早" value={fmtTime(data.shadow5of7.firstSeenAt)} />
                <MiniStat label="最新" value={fmtTime(data.shadow5of7.latestSeenAt)} />
              </div>
            </Panel>
          </div>

          <Panel>
            <PanelTitle icon={<BarChart3 className="h-4 w-4" />} title="因子 IR 排行" />
            <FactorIrTable rows={data.factorIrRanking} />
          </Panel>

          <Panel>
            <PanelTitle icon={<Database className="h-4 w-4" />} title="外部因子完整率" />
            <CoverageTable rows={data.externalFactorCoverage} />
          </Panel>
        </div>
      )}
    </div>
  );
}
