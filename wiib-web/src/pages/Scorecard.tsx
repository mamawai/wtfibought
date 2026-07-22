import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, CalendarDays, Info, Loader2, RefreshCcw, Target, Trophy } from 'lucide-react';
import { quantApi } from '../api';
import { cn } from '../lib/utils';
import type { Scorecard as ScorecardData, ScorecardHorizon } from '../types';

// 只展示 quant 实际监控的标的（WATCH_SYMBOLS=BTC/ETH）
const SYMBOLS = ['BTCUSDT', 'ETHUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH' };
const HORIZON_LABEL: Record<string, string> = { H6: '6 小时', H12: '12 小时', H24: '24 小时' };
/** vol-state 三分类的随机基线：胜过 33.3% 才叫有 skill */
const VOL_STATE_RANDOM_BASELINE = 1 / 3;

/** 误差对比条：预测误差 vs 基准误差并排，条越短误差越小=越准。 */
function QlikeBars({ h }: { h: ScorecardHorizon }) {
  const max = Math.max(h.avgQlike, h.avgBaselineQlike) || 1;
  const better = h.avgQlike <= h.avgBaselineQlike;
  return (
    <div className="space-y-1.5">
      <div className="text-[10px] font-bold text-muted-foreground">平均预测误差（QLIKE，条越短越准）</div>
      {[
        { label: '本系统', value: h.avgQlike, tone: better ? 'bg-gain/80' : 'bg-loss/80' },
        { label: '基准', value: h.avgBaselineQlike, tone: 'bg-muted-foreground/40' },
      ].map(row => (
        <div key={row.label} className="flex items-center gap-2 text-[10px]">
          <span className="w-10 shrink-0 text-muted-foreground font-bold">{row.label}</span>
          <div className="flex-1 h-2 rounded-full border border-border bg-card-2 overflow-hidden">
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
  const beatsBaseline = impPct >= 0;
  return (
    <div className="rounded-lg pt-card p-4 space-y-3">
      <div className="flex items-center gap-2">
        <span className="text-sm font-black">预测未来 {HORIZON_LABEL[h.horizon] || h.horizon}</span>
        <span className={cn('text-[10px] font-black px-2 py-0.5 rounded-full',
          beatsBaseline ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
          {beatsBaseline ? '✓ 跑赢基准' : '✗ 未跑赢基准'}
        </span>
        <span className="ml-auto text-[10px] text-muted-foreground">{h.samples} 次对账</span>
      </div>

      {/* 主指标：比基准准多少 */}
      <div>
        <div className={cn('text-3xl font-black tabular-nums', impPct >= 0 ? 'text-gain' : 'text-loss')}>
          {impPct >= 0 ? '+' : ''}{impPct.toFixed(1)}%
        </div>
        <div className="text-[10px] text-muted-foreground font-bold">比基准方法更准的幅度（误差降低比例）</div>
      </div>

      <QlikeBars h={h} />

      <div className="grid grid-cols-2 gap-2 pt-1">
        <div className="rounded-md border border-border bg-card px-2.5 py-2">
          <div className={cn('text-sm font-black tabular-nums', winPct >= 50 ? 'text-gain' : 'text-loss')}>{winPct.toFixed(1)}%</div>
          <div className="text-[10px] text-muted-foreground leading-tight mt-0.5">单挑胜率：逐条和基准 PK，本系统更准的占比（&gt;50% 算赢）</div>
        </div>
        <div className="rounded-md border border-border bg-card px-2.5 py-2">
          <div className={cn('text-sm font-black tabular-nums', beatsRandom ? 'text-gain' : 'text-loss')}>{hitPct.toFixed(1)}%</div>
          <div className="text-[10px] text-muted-foreground leading-tight mt-0.5">波动档位命中：低/中/高三档猜中比例（瞎猜=33.3%）</div>
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
          <Link to="/ai" className="border border-border hover:bg-surface-hover w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="返回工作台">
            <ArrowLeft className="w-4 h-4" />
          </Link>
          <div className="w-11 h-11 rounded-lg pt-card flex items-center justify-center bg-primary/10">
            <Trophy className="w-5.5 h-5.5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight">验证记分卡</h1>
            <p className="text-[11px] text-muted-foreground">波动预测准不准，到期对账说了算 · 全自动记录，不可挑样本</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex gap-1">
            {SYMBOLS.map(s => (
              <button key={s} onClick={() => setSymbol(s)}
                className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                  symbol === s ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground')}>
                {SYM_LABEL[s]}
              </button>
            ))}
          </div>
          <div className="flex gap-1">
            {([7, 30] as const).map(d => (
              <button key={d} onClick={() => setDays(d)}
                className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                  days === d ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground')}>
                {d}d
              </button>
            ))}
          </div>
          <button onClick={() => void load(symbol, days)}
            className="border border-border hover:bg-surface-hover w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="刷新">
            <RefreshCcw className={cn('w-4 h-4', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      {/* 这页是什么：先讲人话再上数字 */}
      <div className="rounded-lg border border-border bg-card px-4 py-3 text-xs text-muted-foreground leading-relaxed">
        本系统每 5 分钟发布一次「未来 6 / 12 / 24 小时市场会波动多大」的预测（只预测波动幅度，不预测涨跌方向）。
        预测到期后，自动拿实际行情对答案，并和一个<b className="text-foreground">基准方法</b>
        （"拿最近的波动水平直接当预测"，行业常用的免费对照组）比谁更准。下面就是对账成绩单。
      </div>

      {/* 运行天数横幅（诚实展示：线上时序从新链路上线起算） */}
      {data && (
        <div className="rounded-lg pt-card px-4 py-3 flex items-center gap-3 flex-wrap">
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
        <div className="rounded-lg border border-border bg-card-2 py-14 text-center">
          <p className="text-sm text-muted-foreground">{error}</p>
          <p className="text-[11px] text-muted-foreground/70 mt-1">验证时序随新链路上线积累，首个 H6 结果需运行 6 小时后出现</p>
        </div>
      ) : data && data.horizons.length > 0 ? (
        <div className="grid gap-4 md:grid-cols-3">
          {data.horizons.map(h => <HorizonCard key={h.horizon} h={h} />)}
        </div>
      ) : data ? (
        <div className="rounded-lg border border-border bg-card-2 py-14 text-center text-sm text-muted-foreground">
          窗口内暂无已验证样本
        </div>
      ) : null}

      {/* 口径说明：让"战绩"经得起追问 */}
      <div className="rounded-lg pt-card p-4 space-y-2.5">
        <div className="flex items-center gap-2">
          <Info className="w-4 h-4 text-primary" />
          <span className="text-sm font-black">口径说明</span>
        </div>
        <ul className="text-xs text-muted-foreground leading-relaxed space-y-1.5">
          <li className="flex gap-2"><Target className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
            <span><b className="text-foreground">误差怎么算</b>：用 QLIKE——评估波动预测的标准打分方式，误差越低越准。「比基准准 +X%」= 本系统的平均误差比基准低 X%；这与离线研究阶段（walk-forward + 置换检验 + DM 检验）用的是同一把尺子。</span></li>
          <li className="flex gap-2"><Target className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
            <span><b className="text-foreground">波动档位命中</b>：把未来实际波动分成低/中/高三档（按历史分位划界），预测档位与实际档位一致算命中；瞎猜的期望是 33.3%，持续高于它才叫有真本事。</span></li>
          <li className="flex gap-2"><Target className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
            <span><b className="text-foreground">为什么不晒涨跌方向</b>：方向预测经离线验证无优势（≈抛硬币），永不作为信号；这页只公开被验证过真正有效的能力——波动与风险预测。</span></li>
        </ul>
      </div>
    </div>
  );
}
