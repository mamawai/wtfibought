import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, RefreshCcw, Trophy } from 'lucide-react';
import { quantApi } from '../../api';
import { cn } from '../../lib/utils';
import { ChatPanel } from './ChatPanel';
import { VolTimeline } from './VolTimeline';
import { AnalysisCard } from './AnalysisCard';
import type { QuantDeepAnalysisView, QuantSnapshotSeriesPoint, QuantSnapshotView } from '../../types';

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'PAXGUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH', PAXGUSDT: 'PAXG' };
const WINDOWS = [{ label: '24h', hours: 24 }, { label: '72h', hours: 72 }, { label: '7d', hours: 168 }] as const;
const FRAGILITY_CN: Record<string, string> = { LOW: '平稳', ELEVATED: '偏脆', HIGH: '脆弱', EXTREME: '极脆' };
const FRAGILITY_TONE: Record<string, string> = {
  LOW: 'bg-green-500/15 text-green-600',
  ELEVATED: 'bg-amber-500/15 text-amber-600',
  HIGH: 'bg-orange-500/15 text-orange-600',
  EXTREME: 'bg-red-500/15 text-red-600',
};
const REFRESH_MS = 60_000;

/**
 * 研判工作台：左对话（Supervisor 调度）右数据（快照时间线 + 深研判）。
 * 右栏 60s 轮询——快照 5m 一根、深研判 1h 一次，无需推送级实时性。
 */
export function Workbench() {
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [hours, setHours] = useState<number>(24);
  const [series, setSeries] = useState<QuantSnapshotSeriesPoint[]>([]);
  const [analyses, setAnalyses] = useState<QuantDeepAnalysisView[]>([]);
  const [snapshot, setSnapshot] = useState<QuantSnapshotView | null>(null);
  const [selected, setSelected] = useState<QuantDeepAnalysisView | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (sym: string, h: number) => {
    setLoading(true);
    const [s, a, snap] = await Promise.allSettled([
      quantApi.snapshotSeries(sym, h),
      quantApi.analysisList(sym, 30),
      quantApi.latestSnapshot(sym),
    ]);
    if (s.status === 'fulfilled') setSeries(s.value);
    if (a.status === 'fulfilled') setAnalyses(a.value); else setAnalyses([]);
    if (snap.status === 'fulfilled') setSnapshot(snap.value); else setSnapshot(null);
    setLoading(false);
  }, []);

  useEffect(() => {
    setSelected(null);
    void load(symbol, hours);
    const timer = setInterval(() => void load(symbol, hours), REFRESH_MS);
    return () => clearInterval(timer);
  }, [symbol, hours, load]);

  // 时间窗内的研判点才画标记；详情卡默认展示最新一条
  const windowAnalyses = useMemo(() => {
    if (!series.length) return analyses;
    const from = series[0].closeTime;
    return analyses.filter(a => a.closeTime >= from);
  }, [analyses, series]);
  const displayed = selected ?? analyses[0] ?? null;

  return (
    <div className="grid gap-4 lg:grid-cols-2 items-start">
      <ChatPanel />

      <div className="space-y-4">
        {/* 时间线卡 */}
        <div className="rounded-xl neu-raised-sm p-4 space-y-3">
          <div className="flex items-center gap-2 flex-wrap">
            <Activity className="w-4 h-4 text-primary" />
            <span className="text-sm font-black">快照时间线</span>
            {snapshot && (
              <span className={cn('text-[10px] font-bold px-2 py-0.5 rounded-full', FRAGILITY_TONE[snapshot.fragilityLevel] || FRAGILITY_TONE.LOW)}>
                <AlertTriangle className="w-3 h-3 inline -mt-0.5 mr-0.5" />
                {FRAGILITY_CN[snapshot.fragilityLevel] || snapshot.fragilityLevel} {snapshot.fragilityScore}
              </span>
            )}
            <div className="ml-auto flex items-center gap-1">
              {SYMBOLS.map(s => (
                <button
                  key={s}
                  onClick={() => setSymbol(s)}
                  className={cn(
                    'text-[11px] font-bold px-2 py-1 rounded-lg transition-all',
                    symbol === s ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground',
                  )}
                >
                  {SYM_LABEL[s]}
                </button>
              ))}
              <span className="w-1" />
              {WINDOWS.map(w => (
                <button
                  key={w.hours}
                  onClick={() => setHours(w.hours)}
                  className={cn(
                    'text-[11px] font-bold px-2 py-1 rounded-lg transition-all',
                    hours === w.hours ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground',
                  )}
                >
                  {w.label}
                </button>
              ))}
              <button
                onClick={() => void load(symbol, hours)}
                className="neu-btn-sm w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
                aria-label="刷新"
              >
                <RefreshCcw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
              </button>
            </div>
          </div>

          {series.length === 0 ? (
            <div className="rounded-lg neu-inset py-12 text-center text-sm text-muted-foreground">
              暂无快照数据 · 5m K 线收盘后自动落库
            </div>
          ) : (
            <VolTimeline points={series} analyses={windowAnalyses} onSelectAnalysis={setSelected} />
          )}

          {snapshot?.fragilityHeadline && (
            <p className="text-xs text-muted-foreground leading-relaxed">{snapshot.fragilityHeadline}</p>
          )}
        </div>

        {/* 研判卡 + 战绩入口 */}
        <AnalysisCard analysis={displayed} />

        <Link
          to="/scorecard"
          className="neu-btn-sm rounded-xl px-4 py-3 flex items-center gap-2 text-sm font-bold text-muted-foreground hover:text-primary"
        >
          <Trophy className="w-4 h-4 text-primary" />
          验证记分卡 · vol 预测公开战绩
          <span className="ml-auto text-xs">→</span>
        </Link>
      </div>
    </div>
  );
}
