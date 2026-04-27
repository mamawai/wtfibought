import * as echarts from 'echarts';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, ArrowLeft, Clock, RefreshCw, Zap } from 'lucide-react';
import { graphObsApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { cn } from '../lib/utils';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import type { GraphNodeMetric } from '../types';

const NODE_META: Record<string, { label: string; type: 'llm' | 'data' | 'compute' }> = {
  collect_data:    { label: '数据采集',    type: 'data' },
  build_features:  { label: '特征构建',    type: 'compute' },
  regime_review:   { label: 'Regime审核', type: 'llm' },
  run_factors:     { label: '因子计算',    type: 'compute' },
  run_judges:      { label: '区间裁决',    type: 'compute' },
  debate_judge:    { label: '辩论裁决',    type: 'llm' },
  risk_gate:       { label: '风险门控',    type: 'compute' },
  generate_report: { label: '报告生成',    type: 'llm' },
};

const TYPE_BADGE_CLASS: Record<string, string> = {
  llm:     'bg-blue-500/10 text-blue-400 border-blue-500/20',
  data:    'bg-amber-500/10 text-amber-400 border-amber-500/20',
  compute: 'bg-muted text-muted-foreground border-muted-foreground/20',
};

const TYPE_LABEL: Record<string, string> = { llm: 'LLM', data: '数据', compute: '计算' };

function fmtMs(ms: number): string {
  if (!ms || ms <= 0) return '—';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms.toFixed(0)}ms`;
}

function NodeTypeBadge({ type }: { type: 'llm' | 'data' | 'compute' }) {
  return (
    <Badge variant="outline" className={cn('text-[10px]', TYPE_BADGE_CLASS[type])}>
      {TYPE_LABEL[type]}
    </Badge>
  );
}

function SummaryTile({
  icon, label, value, sub,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <div className="rounded-xl border bg-card/70 p-4 flex items-start gap-3">
      <div className="mt-0.5 shrink-0 text-primary">{icon}</div>
      <div className="min-w-0">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className="mt-0.5 text-2xl font-black tabular-nums">{value}</div>
        {sub && <div className="mt-0.5 text-[10px] text-muted-foreground">{sub}</div>}
      </div>
    </div>
  );
}

function DurationBarChart({ metrics }: { metrics: GraphNodeMetric[] }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!ref.current || metrics.every(m => m.meanMs <= 0)) return;

    const isDark = document.documentElement.classList.contains('dark');
    const chart = echarts.init(ref.current, isDark ? 'dark' : 'light');
    const textColor = isDark ? '#94A3B8' : '#78716C';
    const bgColor = isDark ? '#1E293B' : '#FFFFFF';
    const borderColor = isDark ? '#334155' : '#E7E0D8';

    // Reverse so pipeline flows top→bottom in chart
    const reversed = [...metrics].reverse();
    const labels = reversed.map(m => NODE_META[m.node]?.label ?? m.node);
    const avgData = reversed.map(m => +m.meanMs.toFixed(1));
    const maxData = reversed.map(m => +m.maxMs.toFixed(1));

    chart.setOption({
      backgroundColor: 'transparent',
      grid: { left: 72, right: 60, top: 16, bottom: 24 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: bgColor,
        borderColor,
        textStyle: { color: isDark ? '#F8FAFC' : '#1C1917', fontSize: 12 },
        formatter: (params: { axisValue: string; seriesName: string; value: number }[]) =>
          `${params[0].axisValue}<br/>` +
          params.map(p => `${p.seriesName}: ${fmtMs(p.value)}`).join('<br/>'),
      },
      legend: { right: 0, top: 0, textStyle: { color: textColor, fontSize: 11 } },
      xAxis: {
        type: 'value',
        axisLabel: { formatter: (v: number) => fmtMs(v), color: textColor, fontSize: 10 },
        splitLine: { lineStyle: { color: borderColor } },
      },
      yAxis: {
        type: 'category',
        data: labels,
        axisLabel: { color: textColor, fontSize: 11 },
      },
      series: [
        {
          name: '平均耗时',
          type: 'bar',
          data: avgData,
          barMaxWidth: 20,
          itemStyle: { color: '#6366f1', borderRadius: [0, 4, 4, 0] },
        },
        {
          name: '最大耗时',
          type: 'bar',
          data: maxData,
          barMaxWidth: 20,
          itemStyle: { color: '#f97316', opacity: 0.55, borderRadius: [0, 4, 4, 0] },
        },
      ],
    });

    const obs = new ResizeObserver(() => chart.resize());
    obs.observe(ref.current);
    return () => { obs.disconnect(); chart.dispose(); };
  }, [metrics]);

  return <div ref={ref} style={{ height: 280 }} />;
}

export function GraphObs() {
  const { toast } = useToast();
  const [metrics, setMetrics] = useState<GraphNodeMetric[]>([]);
  const [loading, setLoading] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setMetrics(await graphObsApi.metrics());
      setUpdatedAt(new Date());
    } catch (e) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { void load(); }, [load]);

  // Auto-refresh every 15s
  useEffect(() => {
    const id = setInterval(() => { void load(); }, 15_000);
    return () => clearInterval(id);
  }, [load]);

  const anyData = metrics.some(m => m.successCount > 0 || m.errorCount > 0);
  const totalCycles = anyData ? Math.max(...metrics.map(m => m.successCount)) : 0;
  const totalErrors = metrics.reduce((s, m) => s + m.errorCount, 0);
  const pipelineDurationMs = metrics.reduce((s, m) => s + m.meanMs, 0);
  const slowestNode = anyData
    ? metrics.reduce((a, b) => (a.meanMs >= b.meanMs ? a : b))
    : null;

  return (
    <div className="mx-auto max-w-4xl space-y-4 px-4 py-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Link to="/admin" className="text-muted-foreground hover:text-foreground transition-colors">
          <ArrowLeft className="w-4 h-4" />
        </Link>
        <h1 className="text-lg font-black">Graph 观测</h1>
        <span className="rounded-md border bg-muted px-2 py-0.5 text-[10px] text-muted-foreground font-mono">
          8 nodes · auto-refresh 15s
        </span>
        {updatedAt && (
          <span className="ml-auto text-xs text-muted-foreground">
            {updatedAt.toLocaleTimeString('zh-CN')} 更新
          </span>
        )}
        <Button
          variant="outline"
          size="sm"
          onClick={() => void load()}
          disabled={loading}
          className={updatedAt ? '' : 'ml-auto'}
        >
          <RefreshCw className={cn('w-3.5 h-3.5 mr-1', loading && 'animate-spin')} />
          刷新
        </Button>
      </div>

      {/* Summary tiles */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryTile
          icon={<Activity className="w-4 h-4" />}
          label="完整周期"
          value={String(totalCycles)}
          sub="最大节点成功次数"
        />
        <SummaryTile
          icon={<AlertTriangle className="w-4 h-4 text-loss" />}
          label="节点错误"
          value={String(totalErrors)}
          sub={totalErrors === 0 ? '全部正常' : '需关注'}
        />
        <SummaryTile
          icon={<Clock className="w-4 h-4" />}
          label="流水线耗时"
          value={fmtMs(pipelineDurationMs)}
          sub="各节点均值之和"
        />
        <SummaryTile
          icon={<Zap className="w-4 h-4 text-warning" />}
          label="最慢节点"
          value={slowestNode ? (NODE_META[slowestNode.node]?.label ?? slowestNode.node) : '—'}
          sub={slowestNode ? fmtMs(slowestNode.meanMs) : ''}
        />
      </div>

      {/* Duration bar chart */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-bold">节点耗时分布</CardTitle>
        </CardHeader>
        <CardContent>
          {anyData ? (
            <DurationBarChart metrics={metrics} />
          ) : (
            <div className="flex h-40 items-center justify-center text-sm text-muted-foreground">
              暂无观测数据，需运行一次预测周期后刷新
            </div>
          )}
        </CardContent>
      </Card>

      {/* Node detail table */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-bold">节点明细</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="text-[10px] uppercase text-muted-foreground">
                <tr className="border-b">
                  <th className="px-4 py-2 text-left">节点</th>
                  <th className="px-3 py-2 text-left">类型</th>
                  <th className="px-3 py-2 text-right">成功</th>
                  <th className="px-3 py-2 text-right">错误</th>
                  <th className="px-3 py-2 text-right">均值耗时</th>
                  <th className="px-3 py-2 text-right">最大耗时</th>
                  <th className="px-4 py-2">错误率</th>
                </tr>
              </thead>
              <tbody>
                {metrics.map(m => {
                  const meta = NODE_META[m.node];
                  const total = m.successCount + m.errorCount;
                  const errPct = total > 0 ? (m.errorCount / total) * 100 : 0;
                  return (
                    <tr
                      key={m.node}
                      className="border-b last:border-b-0 transition-colors hover:bg-muted/30"
                    >
                      <td className="px-4 py-2.5 font-bold">{meta?.label ?? m.node}</td>
                      <td className="px-3 py-2.5">
                        {meta && <NodeTypeBadge type={meta.type} />}
                      </td>
                      <td className="px-3 py-2.5 text-right tabular-nums text-gain">
                        {m.successCount > 0 ? m.successCount : '—'}
                      </td>
                      <td
                        className={cn(
                          'px-3 py-2.5 text-right tabular-nums',
                          m.errorCount > 0 ? 'font-bold text-loss' : 'text-muted-foreground',
                        )}
                      >
                        {m.errorCount > 0 ? m.errorCount : '—'}
                      </td>
                      <td className="px-3 py-2.5 text-right tabular-nums">{fmtMs(m.meanMs)}</td>
                      <td className="px-3 py-2.5 text-right tabular-nums text-muted-foreground">
                        {fmtMs(m.maxMs)}
                      </td>
                      <td className="px-4 py-2.5">
                        {total > 0 ? (
                          <div className="flex items-center gap-2">
                            <div className="h-1.5 w-20 rounded-full bg-muted">
                              <div
                                className={cn(
                                  'h-full rounded-full',
                                  errPct === 0
                                    ? 'bg-gain'
                                    : errPct < 5
                                      ? 'bg-warning'
                                      : 'bg-loss',
                                )}
                                style={{ width: `${Math.min(100, errPct)}%` }}
                              />
                            </div>
                            <span className="w-10 text-right tabular-nums">
                              {errPct.toFixed(1)}%
                            </span>
                          </div>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
