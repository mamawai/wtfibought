import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, CalendarDays, Info, Loader2, RefreshCcw, Target, Trophy } from 'lucide-react';
import { quantApi } from '../api';
import { cn } from '../lib/utils';
import type { Scorecard as ScorecardData, ScorecardHorizon } from '../types';

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'PAXGUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH', PAXGUSDT: 'PAXG' };
const HORIZON_LABEL: Record<string, string> = { H6: '6 小时', H12: '12 小时', H24: '24 小时' };
/** vol-state 三分类的随机基线：胜过 33.3% 才叫有 skill */
const VOL_STATE_RANDOM_BASELINE = 1 / 3;

/** QLIKE 对比条：预测 vs 基准并排，短的赢（QLIKE 越低越好）。 */
function QlikeBars({ h }: { h: ScorecardHorizon }) {
  const max = Math.max(h.avgQlike, h.avgBaselineQlike) || 1;
  const better = h.avgQlike <= h.avgBaselineQlike;
  return (
    <div className="space-y-1.5">
      {[
        { label: '预测', value: h.avgQlike, tone: better ? 'bg-gain/80' : 'bg-loss/80' },
        { label: '基准', value: h.avgBaselineQlike, tone: 'bg-muted-foreground/40' },
      ].map(row => (
        <div key={row.label} className="flex items-center gap-2 text-[10px]">
          <span className="w-7 shrink-0 text-muted-foreground font-bold">{row.label}</span>
          <div className="flex-1 h-2 rounded-full neu-inset overflow-hidden">
            <div className={cn('h-full rounded-full', row.tone)} style={{ width: `${(row.value / max) * 100}%` }} />
          </div>
          <span className="w-12 text-right font-mono tabular-nums">{row.value.toFixed(4)}</span>
        </div>
      ))}
    </div>
  );
}

function HorizonCard({ h }: { h: ScorecardHorizon }) {
  const impPct = h.qlikeImprovement * 100;
  const winPct = h.qlikeWinRate * 100;
  const hitPct = h.volStateHitRate * 100;
  const beatsRandom = h.volStateHitRate > VOL_STATE_RANDOM_BASELINE;
  return (
    <div className="rounded-xl neu-raised-sm p-4 space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-black">{HORIZON_LABEL[h.horizon] || h.horizon}</span>
        <span className="text-[10px] text-muted-foreground">{h.samples} 样本</span>
      </div>

      {/* improvement 大数字：这页的主指标 */}
      <div>
        <div className={cn('text-3xl font-black tabular-nums', impPct >= 0 ? 'text-gain' : 'text-loss')}>
          {impPct >= 0 ? '+' : ''}{impPct.toFixed(1)}%
        </div>
        <div className="text-[10px] text-muted-foreground font-bold">QLIKE 相对基准改善</div>
      </div>

      <QlikeBars h={h} />

      <div className="grid grid-cols-2 gap-2 pt-1">
        <div className="rounded-lg neu-flat px-2.5 py-2">
          <div className={cn('text-sm font-black tabular-nums', winPct >= 50 ? 'text-gain' : 'text-loss')}>{winPct.toFixed(1)}%</div>
          <div className="text-[10px] text-muted-foreground">逐样本胜率</div>
        </div>
        <div className="rounded-lg neu-flat px-2.5 py-2">
          <div className={cn('text-sm font-black tabular-nums', beatsRandom ? 'text-gain' : 'text-loss')}>{hitPct.toFixed(1)}%</div>
          <div className="text-[10px] text-muted-foreground">vol-state 命中 · 随机 33.3%</div>
        </div>
      </div>
    </div>
  );
}

/**
 * 验证记分卡：vol 预测的公开战绩页（QLIKE vs naive 基准 + vol-state 命中）。
 * 诚实约束：时序从 P2a 上线起积累，运行天数如实展示，样本不足时 note 直说。
 */
export function Scorecard() {
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [days, setDays] = useState<7 | 30>(7);
  const [data, setData] = useState<ScorecardData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (sym: string, d: number) => {
    setLoading(true);
    setError(null);
    try {
      setData(await quantApi.scorecard(sym, d));
    } catch (e) {
      setData(null);
      setError((e as Error).message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(symbol, days); }, [symbol, days, load]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <Link to="/ai" className="neu-btn-sm w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="返回工作台">
            <ArrowLeft className="w-4 h-4" />
          </Link>
          <div className="w-11 h-11 rounded-xl neu-raised-sm flex items-center justify-center bg-primary/10">
            <Trophy className="w-5.5 h-5.5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight">验证记分卡</h1>
            <p className="text-[11px] text-muted-foreground">vol 预测 QLIKE vs naive 基准 · 每 5m 预测点到期自动验证 · 战绩可审计</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex gap-1">
            {SYMBOLS.map(s => (
              <button key={s} onClick={() => setSymbol(s)}
                className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                  symbol === s ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground')}>
                {SYM_LABEL[s]}
              </button>
            ))}
          </div>
          <div className="flex gap-1">
            {([7, 30] as const).map(d => (
              <button key={d} onClick={() => setDays(d)}
                className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                  days === d ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground')}>
                {d}d
              </button>
            ))}
          </div>
          <button onClick={() => void load(symbol, days)}
            className="neu-btn-sm w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="刷新">
            <RefreshCcw className={cn('w-4 h-4', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      {/* 运行天数横幅（诚实展示：线上时序从新链路上线起算） */}
      {data && (
        <div className="rounded-xl neu-raised-sm px-4 py-3 flex items-center gap-3 flex-wrap">
          <CalendarDays className="w-4.5 h-4.5 text-primary shrink-0" />
          <span className="text-sm font-bold">线上验证已运行 <span className="text-primary text-lg font-black tabular-nums">{data.runningDays}</span> 天</span>
          <span className="text-xs text-muted-foreground">窗口 {data.windowDays}d · 共 {data.totalSamples} 个已验证预测点</span>
          {data.note && <span className="text-[11px] text-warning font-bold ml-auto">{data.note}</span>}
        </div>
      )}

      {loading && !data ? (
        <div className="flex items-center justify-center py-20 gap-2 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" /> 加载战绩...
        </div>
      ) : error ? (
        <div className="rounded-xl neu-inset py-14 text-center">
          <p className="text-sm text-muted-foreground">{error}</p>
          <p className="text-[11px] text-muted-foreground/70 mt-1">验证时序随新链路上线积累，首个 H6 结果需运行 6 小时后出现</p>
        </div>
      ) : data && data.horizons.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-3">
          {data.horizons.map(h => <HorizonCard key={h.horizon} h={h} />)}
        </div>
      ) : data ? (
        <div className="rounded-xl neu-inset py-14 text-center text-sm text-muted-foreground">
          窗口内暂无已验证样本
        </div>
      ) : null}

      {/* 口径说明：让"战绩"经得起追问 */}
      <div className="rounded-xl neu-raised-sm p-4 space-y-2.5">
        <div className="flex items-center gap-2">
          <Info className="w-4 h-4 text-primary" />
          <span className="text-sm font-black">口径说明</span>
        </div>
        <ul className="text-xs text-muted-foreground leading-relaxed space-y-1.5">
          <li className="flex gap-2"><Target className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
            <span><b className="text-foreground">QLIKE</b>：vol 预测的标准损失函数，越低越好；「改善 &gt; 0」= 跑赢 naive 基准（EWMA 类），这是 research 阶段被 walk-forward + 置换检验 + DM 检验验证过的同一口径。</span></li>
          <li className="flex gap-2"><Target className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
            <span><b className="text-foreground">vol-state 命中</b>：LOW/MID/HIGH 三档分类命中率，随机基线 33.3%。</span></li>
          <li className="flex gap-2"><Target className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
            <span><b className="text-foreground">不晒方向</b>：方向预测经离线验证无 edge（≈抛硬币），永不作为信号；这页只公开被验证过有 skill 的腿。</span></li>
        </ul>
      </div>
    </div>
  );
}
