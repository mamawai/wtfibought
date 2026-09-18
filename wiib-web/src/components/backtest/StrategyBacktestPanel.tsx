import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import {
  AlertTriangle, ArrowDownRight, ArrowUpRight, Bot, CheckCircle2, ChevronLeft, ChevronRight,
  CircleSlash, Crosshair, Gauge, History, Hourglass, Loader2, LogOut, Pause, Play,
  Radar, RotateCcw, ScrollText, XCircle,
} from 'lucide-react';
import { backtestApi } from '../../api';
import { BacktestChart, type ChartTradeMark } from './BacktestChart';
import { EquityChart } from '../EquityChart';
import { useToast } from '../ui/use-toast';
import { DatePicker } from '../ui/date-picker';
import { getCoinPriceDecimals } from '../../lib/coinConfig';
import { aggregateBars, barIndexAt, IV_OPTIONS, ivLabel } from '../../lib/klineAgg';
import { STRATEGIES, strategyDisplay } from '../../lib/strategyCatalog';
import { cn, fmtDateTime, fmtNum } from '../../lib/utils';
import type {
  BacktestEvent, BacktestResultPayload, BacktestTaskStatus, BacktestTrade,
} from '../../types';
import type { TnEquityPoint } from '../../types/testnet';

const STORE_KEY = 'wiib.backtest.task';
const POLL_MS = 800;
/** 回放速度档：bar/秒（5m bar：120/s ≈ 10小时行情每秒）。表里存词表 key，渲染时现查 */
const SPEEDS = [
  { labelKey: 'backtest.speed.slow', bps: 30 }, { labelKey: 'backtest.speed.normal', bps: 120 },
  { labelKey: 'backtest.speed.fast', bps: 600 }, { labelKey: 'backtest.speed.turbo', bps: 3000 },
];
/** 后端枚举 → 词表 key；认不出的原样显示后端值 */
const REJECT_REASON: Record<string, string> = {
  DIRECTIONAL: 'backtest.reject.directional', GAPPED_BEYOND_STOP: 'backtest.reject.gappedBeyondStop',
  RR_TOO_LOW: 'backtest.reject.rrTooLow', QTY_ZERO: 'backtest.reject.qtyZero',
  BALANCE_REJECTED: 'backtest.reject.balanceRejected', PRICE_INVALID: 'backtest.reject.priceInvalid',
};
const EXIT_LABEL: Record<string, string> = {
  SL: 'backtest.exit.sl', TP: 'backtest.exit.tp', SIGNAL_CLOSE: 'backtest.exit.signalClose',
  TIME_EXIT: 'backtest.exit.timeExit', TIMEOUT: 'backtest.exit.timeout',
  TRAILING_STOP: 'backtest.exit.trailingStop', FORCE_CLOSE: 'backtest.exit.forceClose',
  LIQUIDATION: 'backtest.exit.liquidation',
};

const dayStartUtc = (yyyyMmDd: string) => Date.parse(`${yyyyMmDd}T00:00:00Z`);
const toDateInput = (ms: number) => new Date(ms).toISOString().slice(0, 10);

// ==================== 工作记录时间线 ====================

/** 单条事件卡：类型定图标与色，正文一行主信息 + 一行细节 */
function EventCard({ e, onJump }: { e: BacktestEvent; onJump: (barTimeMs: number) => void }) {
  const { t } = useTranslation('strategy');
  const d: Record<string, unknown> = e.data;
  const num = (v: unknown) => (v == null ? '—' : fmtNum(Number(v)));
  const sideText = (s: unknown) => (s === 'LONG' ? t('side.long') : t('side.short'));
  const sideCls = (s: unknown) => (s === 'LONG' ? 'text-gain' : 'text-loss');
  /** 后端枚举先查词表，查不到原样吐后端值 */
  const enumText = (map: Record<string, string>, v: unknown) => {
    const raw = String(v);
    return map[raw] ? t(map[raw]) : raw;
  };

  let icon: React.ReactNode; let rail = 'bg-border'; let title: React.ReactNode; let detail: React.ReactNode = null;
  switch (e.type) {
    case 'TASK_START':
      icon = <Radar className="w-3.5 h-3.5 text-primary" />; rail = 'bg-primary';
      title = <span className="font-bold">{t('backtest.ev.taskStart')}</span>;
      detail = <Trans ns="strategy" i18nKey="backtest.ev.taskStartDetail"
        values={{ bars: String(d['bars']), warmup: String(d['warmupBars']), leverage: String(d['leverage']) }}
        components={[<b className="num" key="b" />, <b className="num" key="w" />, <b className="num" key="l" />]} />;
      break;
    case 'SIGNAL':
      icon = <Crosshair className="w-3.5 h-3.5 text-primary" />; rail = 'bg-primary';
      title = <>
        <span className="font-bold">{t('backtest.ev.signal')}</span> {String(d['orderType'])}
        <b className={sideCls(d['side'])}> {sideText(d['side'])}</b> @ <b className="num">{num(d['entryRef'])}</b>
      </>;
      detail = <>SL <span className="num text-loss">{num(d['sl'])}</span> · TP <span className="num text-gain">{num(d['tp'])}</span>{d['reason'] != null && <> · {String(d['reason'])}</>}</>;
      break;
    case 'ORDER_CANCELLED':
      icon = <CircleSlash className="w-3.5 h-3.5 text-muted-foreground" />;
      title = <span className="text-muted-foreground"><span className="font-bold">{t('backtest.ev.cancelled')}</span> {String(d['orderType'])} {sideText(d['side'])} @ <span className="num">{num(d['entryRef'])}</span></span>;
      break;
    case 'ENTRY_FILL': {
      const long = d['side'] === 'LONG';
      icon = long ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />;
      rail = long ? 'bg-gain' : 'bg-loss';
      title = <>
        <span className="font-bold">{t('backtest.ev.entry')}</span>
        <b className={sideCls(d['side'])}> {sideText(d['side'])} {String(d['leverage'])}x</b> @ <b className="num">{num(d['price'])}</b>
      </>;
      detail = <>{t('backtest.ev.qty')} <span className="num">{num(d['qty'])}</span> · {String(d['orderType'])} · SL <span className="num text-loss">{num(d['sl'])}</span> · TP <span className="num text-gain">{num(d['tp'])}</span></>;
      break;
    }
    case 'ENTRY_REJECTED':
      icon = <AlertTriangle className="w-3.5 h-3.5 text-warning" />; rail = 'bg-warning';
      title = <><span className="font-bold">{t('backtest.ev.rejected')}</span> · {enumText(REJECT_REASON, d['reason'])}</>;
      detail = <>{String(d['orderType'])} {sideText(d['side'])} @ <span className="num">{num(d['price'])}</span></>;
      break;
    case 'EXIT': {
      const pnl = Number(d['pnl']);
      const win = pnl >= 0;
      icon = <LogOut className={cn('w-3.5 h-3.5', win ? 'text-gain' : 'text-loss')} />;
      rail = win ? 'bg-gain' : 'bg-loss';
      title = <>
        <span className="font-bold">{enumText(EXIT_LABEL, d['reason'])}</span>
        <b className={cn('num ml-1', win ? 'text-gain' : 'text-loss')}>{win ? '+' : ''}{fmtNum(pnl)}</b>
        {d['rMultiple'] != null && <span className="num text-muted-foreground"> ({Number(d['rMultiple']) >= 0 ? '+' : ''}{Number(d['rMultiple']).toFixed(2)}R)</span>}
      </>;
      detail = <Trans ns="strategy" i18nKey="backtest.ev.exitDetail"
        values={{ entry: num(d['entry']), exit: num(d['exit']), bars: String(d['holdBars']) }}
        components={[<span className="num" key="i" />, <span className="num" key="o" />, <span className="num" key="h" />]} />;
      break;
    }
    case 'TASK_DONE':
      icon = <CheckCircle2 className="w-3.5 h-3.5 text-gain" />; rail = 'bg-gain';
      title = <span className="font-bold">{t('backtest.ev.done', { count: Number(d['totalTrades']) })}</span>;
      break;
    case 'TASK_FAILED':
      icon = <XCircle className="w-3.5 h-3.5 text-loss" />; rail = 'bg-loss';
      title = <span className="font-bold text-loss">{t('backtest.failed')}</span>;
      detail = <span className="text-loss/90 whitespace-normal break-all">{String(d['message'])}</span>;
      break;
    default:
      icon = <ScrollText className="w-3.5 h-3.5 text-muted-foreground" />;
      title = <span>{e.type}</span>;
  }

  return (
    <button
      type="button"
      onClick={() => e.barTimeMs > 0 && onJump(e.barTimeMs)}
      className="w-full text-left flex gap-2 px-2 py-1.5 rounded-md hover:bg-surface-hover/60 transition-colors"
    >
      <span className={cn('w-0.5 self-stretch rounded-full shrink-0', rail)} />
      <span className="mt-0.5 shrink-0">{icon}</span>
      <span className="min-w-0 flex-1">
        <span className="block text-[11px] leading-snug truncate">{title}</span>
        {detail != null && <span className="block text-[10px] text-muted-foreground leading-snug truncate">{detail}</span>}
        {e.barTimeMs > 0 && (
          <span className="block text-[9px] text-muted-foreground/60 num leading-tight mt-px">{fmtDateTime(e.barTimeMs)}</span>
        )}
      </span>
    </button>
  );
}

// ==================== 汇总指标 tile ====================

function StatTile({ label, value, sub, tone }: {
  label: string; value: React.ReactNode; sub?: React.ReactNode; tone?: 'gain' | 'loss';
}) {
  return (
    <div className="rounded-md border border-border bg-card-2 px-3 py-2.5 min-w-0">
      <div className={cn('text-base md:text-lg font-black num truncate leading-tight',
        tone === 'gain' && 'text-gain', tone === 'loss' && 'text-loss')}>{value}</div>
      <div className="microlabel uppercase mt-1 truncate">{label}{sub != null && <span className="normal-case tracking-normal"> · {sub}</span>}</div>
    </div>
  );
}

// ==================== 模式1面板 ====================

export function StrategyBacktestPanel() {
  const { t } = useTranslation('strategy');
  const { toast } = useToast();

  // ---- 配置 ----
  const [strategyId, setStrategyId] = useState('FIBO');
  const [symbol, setSymbol] = useState('ETHUSDT');
  const [fromDate, setFromDate] = useState(() => toDateInput(Date.now() - 365 * 86_400_000));
  const [toDate, setToDate] = useState(() => toDateInput(Date.now()));
  const [balance, setBalance] = useState('100000');
  const [leverage, setLeverage] = useState('5');

  // ---- 任务态 ----
  // 对象包一层：后端同参数指纹会复用同一 taskId，字符串 state 不变轮询 effect 就不重启，
  // 页面被 handleRun 清空后再也不发请求（切走再回才恢复）——新引用保证每次提交都重启轮询
  const [task, setTask] = useState<{ id: string } | null>(null);
  const [status, setStatus] = useState<BacktestTaskStatus | null>(null);
  const [events, setEvents] = useState<BacktestEvent[]>([]);
  const [bars, setBars] = useState<number[][]>([]);
  const [result, setResult] = useState<BacktestResultPayload | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // ---- 回放 ----
  const [cursor, setCursor] = useState<number | null>(null);   // null=全量视图
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(SPEEDS[1].bps);
  const [ivMin, setIvMin] = useState(5);                       // 图表显示周期（分钟）

  const [tradePage, setTradePage] = useState(0);

  const afterRef = useRef(-1);                 // events 游标
  const taskRef = useRef<string | null>(null); // 轮询循环里判断任务是否被切换
  const klinesBusyRef = useRef(false);
  const klinesDoneRef = useRef<string | null>(null);   // 该任务 K 线已拉全，轮询不再重拉
  const balanceRef = useRef(100000);           // 权益曲线基线（cumPnl = equity - 基线）
  const timelineRef = useRef<HTMLDivElement>(null);
  const followRef = useRef(true);              // 时间线是否贴底跟随

  const running = status?.state === 'RUNNING';
  const queued = status?.state === 'QUEUED';
  const busy = running || queued || submitting;
  const done = status?.state === 'DONE';
  const warmupBars = status?.warmupBars ?? 0;

  // ---- 刷新恢复：taskId 落 sessionStorage，回来接着看 ----
  useEffect(() => {
    const raw = sessionStorage.getItem(STORE_KEY);
    if (!raw) return;
    try {
      const saved = JSON.parse(raw) as { taskId?: string; balance?: number };
      if (saved.taskId) {
        balanceRef.current = saved.balance ?? 100000;
        setTask({ id: saved.taskId });
      }
    } catch { /* ignore */ }
  }, []);

  // ---- K线分段拉取（RUNNING 一开始就能拉，边拉边画） ----
  const loadKlines = useCallback(async (tid: string) => {
    if (klinesBusyRef.current || klinesDoneRef.current === tid) return;
    klinesBusyRef.current = true;
    try {
      let acc: number[][] = [];
      let offset = 0;
      for (;;) {
        const page = await backtestApi.klines(tid, offset, 20_000);
        if (taskRef.current !== tid) return;   // 任务已切换，丢弃
        if (page.rows.length === 0) break;
        acc = acc.concat(page.rows);
        offset += page.rows.length;
        setBars(acc);
        if (offset >= page.total) { klinesDoneRef.current = tid; break; }
      }
    } catch { /* 分段失败下轮 status 触发重试 */ } finally {
      klinesBusyRef.current = false;
    }
  }, []);

  // ---- 主轮询循环：status + events 增量；终态后收尾 ----
  useEffect(() => {
    if (!task) return;
    const taskId = task.id;
    taskRef.current = taskId;
    let active = true;

    const pullEvents = async () => {
      // 单轮最多 4 页防积压（2000×4），正常一轮远拉不满
      for (let i = 0; i < 4; i++) {
        const page = await backtestApi.events(taskId, afterRef.current, 2000);
        if (!active || taskRef.current !== taskId) return;
        if (page.events.length === 0) return;
        afterRef.current = page.nextAfter;
        setEvents(prev => [...prev, ...page.events]);
        if (page.events.length < 2000) return;
      }
    };

    (async () => {
      for (;;) {
        if (!active || taskRef.current !== taskId) return;
        try {
          const st = await backtestApi.status(taskId);
          if (!active || taskRef.current !== taskId) return;
          setStatus(st);
          if (st.totalBars > 0) void loadKlines(taskId);
          await pullEvents();
          if (st.state === 'DONE') {
            const r = await backtestApi.result(taskId);
            if (!active || taskRef.current !== taskId) return;
            setResult(r);
            return;
          }
          if (st.state === 'FAILED') return;
        } catch (e) {
          // 任务不存在（服务重启）：清存档提示重跑
          if (!active) return;
          toast((e as Error).message || t('backtest.toast.pollFailed'), 'error');
          sessionStorage.removeItem(STORE_KEY);
          setTask(null);
          setStatus(null);
          return;
        }
        await new Promise(r => setTimeout(r, POLL_MS));
      }
    })();

    return () => { active = false; };
  }, [task, loadKlines, toast, t]);

  // ---- 回放 rAF ----
  useEffect(() => {
    if (!playing || bars.length === 0) return;
    let raf = 0;
    let last = performance.now();
    const step = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.2);
      last = now;
      setCursor(c => {
        const cur = c == null ? warmupBars : c;
        const next = cur + speed * dt;
        if (next >= bars.length) { setPlaying(false); return null; }   // 播到头回到全量视图
        return next;
      });
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [playing, speed, bars.length, warmupBars]);

  // ---- 时间线可见事件（回放时按游标时刻揭示） ----
  const cursorTime = useMemo(() => {
    if (cursor == null || bars.length === 0) return null;
    const idx = Math.min(Math.floor(cursor), bars.length) - 1;
    return idx >= 0 ? bars[idx][0] : 0;
  }, [cursor, bars]);

  const visibleEvents = useMemo(() => {
    if (cursorTime == null) return events;
    return events.filter(e => e.barTimeMs <= cursorTime || e.type === 'TASK_START');
  }, [events, cursorTime]);

  // 时间线贴底跟随（用户上滚即暂停跟随，滚回底部恢复）
  useEffect(() => {
    const el = timelineRef.current;
    if (el && followRef.current) el.scrollTop = el.scrollHeight;
  }, [visibleEvents.length]);

  // ---- 提交 ----
  const handleRun = useCallback(async () => {
    const fromMs = dayStartUtc(fromDate);
    const toMs = dayStartUtc(toDate) + 86_400_000;   // 含结束日全天
    if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || fromMs >= toMs) {
      toast(t('backtest.toast.badRange'), 'error');
      return;
    }
    const bal = Number(balance) || 100000;
    const lev = Math.max(1, Math.min(100, Number(leverage) || 5));
    setSubmitting(true);
    try {
      const { taskId: tid } = await backtestApi.run({
        strategyId, symbol, fromMs, toMs, initialBalance: bal, leverage: lev,
      });
      // 重置全部任务态
      afterRef.current = -1;
      followRef.current = true;
      balanceRef.current = bal;
      klinesDoneRef.current = null;
      setEvents([]);
      setBars([]);
      setResult(null);
      setStatus(null);
      setCursor(null);
      setPlaying(false);
      setTradePage(0);
      sessionStorage.setItem(STORE_KEY, JSON.stringify({ taskId: tid, balance: bal }));
      setTask({ id: tid });
    } catch (e) {
      toast((e as Error).message || t('backtest.toast.submitFailed'), 'error');
    } finally {
      setSubmitting(false);
    }
  }, [fromDate, toDate, balance, leverage, strategyId, symbol, toast, t]);

  // ---- 跳转（事件卡/成交行 → 图表游标） ----
  const jumpToTime = useCallback((t: number) => {
    if (bars.length === 0) return;
    setPlaying(false);
    setCursor(barIndexAt(bars, t) + 1);
  }, [bars]);

  // ---- 权益曲线（复用 EquityChart：cumPnl = equity - 初始资金） ----
  const equityPoints = useMemo<TnEquityPoint[]>(() => {
    if (!result) return [];
    const base = balanceRef.current || result.equity[0]?.[1] || 0;
    return result.equity.map(([time, eq]) => ({ time, cumPnl: eq - base }));
  }, [result]);

  // ---- 图表通用标记：每笔 trade 拆成进场箭头 + 出场圆点 ----
  // 循环变量避开 t：与 i18n 的 t 同名会遮蔽；依赖带 t，切语言时出场标记文字跟着换
  const marks = useMemo<ChartTradeMark[]>(() => (result?.trades ?? []).flatMap(tr => [
    { barIndex: tr.openBarIndex, time: tr.openTime, side: tr.side, kind: 'entry' as const },
    {
      barIndex: tr.closeBarIndex, time: tr.closeTime, side: tr.side, kind: 'exit' as const,
      pnl: tr.pnl, label: EXIT_LABEL[tr.exitReason] ? t(EXIT_LABEL[tr.exitReason]) : tr.exitReason,
    },
  ]), [result, t]);

  // ---- 显示周期聚合：撮合/回放游标仍在 5m 空间，仅图表按所选周期展示 ----
  // 5m 时 aggregateBars 原样返回同引用，图表增量更新路径不受影响
  const aggBars = useMemo(() => aggregateBars(bars, ivMin), [bars, ivMin]);

  // 标记映射到聚合桶：marker 时间必须落在已有蜡烛的 openTime 上才会渲染
  const aggMarks = useMemo<ChartTradeMark[]>(() => {
    if (ivMin === 5 || aggBars.length === 0) return marks;
    return marks.map(m => {
      const i = barIndexAt(aggBars, m.time);
      return { ...m, barIndex: i, time: aggBars[i][0] };
    });
  }, [marks, aggBars, ivMin]);

  // ---- 成交分页 ----
  const TRADES_PAGE = 10;
  const trades = result?.trades ?? [];
  const pageCount = Math.max(1, Math.ceil(trades.length / TRADES_PAGE));
  const page = Math.min(tradePage, pageCount - 1);
  const pagedTrades = trades.slice(page * TRADES_PAGE, (page + 1) * TRADES_PAGE);

  /** 认不出的 id 原样显示：后端加了策略而前端词表还没登记时不至于空白 */
  const strategyName = (id: string) => {
    const m = strategyDisplay(id);
    return m ? t(m.nameKey) : id;
  };

  const s = result?.summary;
  // 滑杆在 5m 空间（撮合口径）；图表游标映射到聚合空间
  const sliderCursor = cursor == null ? bars.length : Math.floor(cursor);
  const chartCursor = cursor == null ? aggBars.length
    : cursorTime != null ? barIndexAt(aggBars, cursorTime) + 1 : 0;
  const progressPct = status && status.totalBars > 0 ? Math.round((status.barsDone / status.totalBars) * 100) : 0;

  return (
    <div className="space-y-5">
      {/* ===== 配置台 ===== */}
      <div className="rounded-lg pt-card p-4 md:p-5 space-y-4">
        {/* 策略四选一 */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5">
          {STRATEGIES.map(m => {
            const active = strategyId === m.id;
            return (
              <button
                key={m.id}
                type="button"
                disabled={busy}
                onClick={() => setStrategyId(m.id)}
                className={cn(
                  'rounded-lg border p-3 text-left transition-all disabled:opacity-60 min-w-0',
                  active
                    ? 'border-primary bg-primary/5 shadow-[0_0_0_1px_var(--color-primary)]'
                    : 'border-border bg-card-2 hover:bg-surface-hover',
                )}
              >
                <div className="flex items-center gap-2">
                  <span className="w-7 h-7 rounded-md border border-border flex items-center justify-center shrink-0"
                    style={{ background: `${m.accent}1f`, color: m.accent }}>
                    <Bot className="w-4 h-4" />
                  </span>
                  <span className="text-xs font-black truncate">{t(m.nameKey)}</span>
                </div>
                <div className="text-[10px] text-muted-foreground mt-1.5 leading-snug line-clamp-2">{t(m.mechKey)}</div>
              </button>
            );
          })}
        </div>

        {/* 参数行 */}
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <div className="microlabel uppercase mb-1">{t('backtest.form.symbol')}</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              {['BTCUSDT', 'ETHUSDT'].map(sym => (
                <button key={sym} type="button" disabled={busy}
                  onClick={() => setSymbol(sym)}
                  className={cn('px-3 h-9 text-xs font-bold transition-colors disabled:opacity-60',
                    symbol === sym ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                  {sym.replace('USDT', '')}
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="microlabel uppercase mb-1">{t('backtest.form.from')}</div>
            <DatePicker value={fromDate} disabled={busy} onChange={setFromDate}
              className="h-9 px-2.5 rounded-md border border-border bg-input text-xs num disabled:opacity-60" />
          </div>
          <div>
            <div className="microlabel uppercase mb-1">{t('backtest.form.to')}</div>
            <DatePicker value={toDate} disabled={busy} onChange={setToDate}
              className="h-9 px-2.5 rounded-md border border-border bg-input text-xs num disabled:opacity-60" />
          </div>
          <div>
            <div className="microlabel uppercase mb-1">{t('backtest.form.balance')}</div>
            <input type="number" min={1} value={balance} disabled={busy}
              onChange={e => setBalance(e.target.value)}
              className="h-9 w-28 px-2.5 rounded-md border border-border bg-input text-xs num disabled:opacity-60" />
          </div>
          <div>
            <div className="microlabel uppercase mb-1">{t('backtest.form.leverage')}</div>
            <input type="number" min={1} max={100} value={leverage} disabled={busy}
              onChange={e => setLeverage(e.target.value)}
              className="h-9 w-16 px-2.5 rounded-md border border-border bg-input text-xs num disabled:opacity-60" />
          </div>
          <button
            type="button"
            onClick={() => void handleRun()}
            disabled={busy}
            className={cn(
              'ml-auto h-10 px-5 rounded-lg font-black text-sm flex items-center gap-2 transition-all machined',
              'bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98]',
              'disabled:opacity-50 disabled:cursor-not-allowed',
            )}
          >
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
            {queued ? t('backtest.run.queued') : running ? t('backtest.run.running') : t('backtest.run.start')}
          </button>
        </div>

        {/* 排队提示 */}
        {queued && status && (
          <div className="flex items-center gap-2 rounded-md border border-border bg-card-2 px-3 py-2 text-[11px] text-muted-foreground">
            <Hourglass className="w-3.5 h-3.5 text-primary animate-pulse shrink-0" />
            <span>
              <Trans ns="strategy" i18nKey="backtest.queueHint" values={{ pos: status.queuePos }}
                components={[<b className="num text-foreground" key="p" />]} />
            </span>
          </div>
        )}

        {/* 运行进度条 */}
        {running && status && (
          <div className="flex items-center gap-3 pt-1">
            <span className="led shrink-0" />
            <div className="flex-1 h-1.5 rounded-full bg-secondary border border-border overflow-hidden">
              <div className="h-full bg-primary transition-[width] duration-300 rounded-full"
                style={{ width: `${progressPct}%` }} />
            </div>
            <span className="text-[10px] num text-muted-foreground shrink-0">
              {t('backtest.progress', { done: fmtNum(status.barsDone, 0), total: fmtNum(status.totalBars, 0), pct: progressPct })}
            </span>
          </div>
        )}
        {status?.state === 'FAILED' && (
          <div className="flex items-start gap-2 rounded-md border border-loss/30 bg-loss/5 px-3 py-2 text-[11px] text-loss">
            <XCircle className="w-3.5 h-3.5 mt-px shrink-0" />
            <span className="leading-snug">{status.error || t('backtest.failed')}</span>
          </div>
        )}
      </div>

      {/* ===== 主区：图表 + 工作记录 ===== */}
      {(bars.length > 0 || events.length > 0) && (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
          {/* 左：K线 + 回放控制 + 权益 */}
          <div className="space-y-5 min-w-0">
            <div className="rounded-lg pt-card p-3 md:p-4 space-y-3">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="microlabel uppercase">{symbol} · {ivLabel(ivMin)}{status ? ` · ${strategyName(status.strategyId)}` : ''}</span>
                <div className="flex rounded border border-border overflow-hidden">
                  {IV_OPTIONS.map(o => (
                    <button key={o.min} type="button"
                      onClick={() => {
                        if (o.min === ivMin) return;
                        setIvMin(o.min);
                        if (bars.length > 0) toast(t('backtest.toast.ivSwitched', { iv: o.label }), 'info');
                      }}
                      className={cn('px-1.5 h-6 text-[10px] font-bold transition-colors num',
                        ivMin === o.min ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                      {o.label}
                    </button>
                  ))}
                </div>
                {cursorTime != null && (
                  <span className="text-[10px] num text-primary font-bold">{t('backtest.replayAt', { time: fmtDateTime(cursorTime) })}</span>
                )}
                <span className="ml-auto text-[10px] text-muted-foreground num">
                  {bars.length > 0 ? t('backtest.barsLoaded', { bars: fmtNum(bars.length, 0) }) : t('backtest.waitingBars')}
                </span>
              </div>

              <BacktestChart bars={aggBars} marks={aggMarks} cursor={chartCursor}
                symbol={symbol} decimals={getCoinPriceDecimals(symbol)} bucketSec={ivMin * 60} />

              {/* 回放控制条：done 且有数据才开放 */}
              {done && bars.length > 0 && (
                <div className="flex items-center gap-2.5 flex-wrap pt-1 border-t border-border/40">
                  <button
                    type="button"
                    onClick={() => setPlaying(p => !p)}
                    className="w-10 h-10 md:w-8 md:h-8 rounded-lg border border-border hover:bg-surface-hover flex items-center justify-center text-primary machined"
                    aria-label={playing ? t('player.pause') : t('player.play')}
                  >
                    {playing ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
                  </button>
                  <button
                    type="button"
                    onClick={() => { setPlaying(false); setCursor(null); }}
                    className="w-10 h-10 md:w-8 md:h-8 rounded-lg border border-border hover:bg-surface-hover flex items-center justify-center text-muted-foreground hover:text-primary"
                    aria-label={t('player.reset')}
                    title={t('player.reset')}
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                  </button>
                  <div className="flex items-center gap-1">
                    <Gauge className="w-3.5 h-3.5 text-muted-foreground" />
                    {SPEEDS.map(sp => (
                      <button key={sp.bps} type="button" onClick={() => setSpeed(sp.bps)}
                        className={cn('px-2 h-7 rounded text-[10px] font-bold transition-colors',
                          speed === sp.bps ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                        {t(sp.labelKey)}
                      </button>
                    ))}
                  </div>
                  <input
                    type="range"
                    min={warmupBars}
                    max={bars.length}
                    value={sliderCursor}
                    onChange={e => { setPlaying(false); setCursor(Number(e.target.value)); }}
                    className="flex-1 min-w-[120px] accent-(--color-primary)"
                    aria-label={t('player.progress')}
                  />
                </div>
              )}
            </div>

            {/* 权益曲线 */}
            {equityPoints.length > 0 && (
              <div className="rounded-lg pt-card p-3 md:p-4">
                <div className="microlabel uppercase mb-1">{t('backtest.equityTitle')}</div>
                <EquityChart points={equityPoints} />
              </div>
            )}
          </div>

          {/* 右：工作记录时间线 */}
          <div className="rounded-lg pt-card p-3 flex flex-col min-w-0 lg:max-h-[640px] max-h-[420px]">
            <div className="flex items-center gap-1.5 pb-2 border-b border-border/40 shrink-0">
              <ScrollText className="w-3.5 h-3.5 text-primary" />
              <span className="text-[11px] font-black">{t('backtest.timeline.title')}</span>
              <span className="ml-auto text-[10px] text-muted-foreground num">{t('backtest.timeline.count', { count: visibleEvents.length })}</span>
            </div>
            <div
              ref={timelineRef}
              onScroll={e => {
                const el = e.currentTarget;
                followRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
              }}
              className="flex-1 overflow-y-auto py-1 space-y-0.5 overscroll-contain"
            >
              {visibleEvents.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
                  <History className="w-5 h-5 opacity-60" />
                  <span className="text-[11px]">{t('backtest.timeline.waiting')}</span>
                </div>
              ) : (
                visibleEvents.map(e => <EventCard key={e.seq} e={e} onJump={jumpToTime} />)
              )}
            </div>
          </div>
        </div>
      )}

      {/* ===== 汇总指标 ===== */}
      {s && (
        <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-2.5">
          <StatTile label={t('backtest.stat.netProfit')} tone={s.netProfit >= 0 ? 'gain' : 'loss'}
            value={`${s.netProfit >= 0 ? '+' : ''}${fmtNum(s.netProfit)}`}
            sub={`${(s.returnPct * 100).toFixed(1)}%`} />
          <StatTile label={t('backtest.stat.winRate')} tone={s.totalTrades > 0 ? (s.winRate >= 0.5 ? 'gain' : 'loss') : undefined}
            value={s.totalTrades > 0 ? `${(s.winRate * 100).toFixed(1)}%` : '—'}
            sub={t('backtest.stat.winLoss', { wins: s.wins, losses: s.losses })} />
          <StatTile label={t('backtest.stat.pf')} value={s.profitFactor >= 9999 ? '∞' : s.profitFactor.toFixed(2)}
            sub={`Sharpe ${s.sharpeRatio.toFixed(2)}`} />
          <StatTile label={t('backtest.stat.maxDd')} tone="loss" value={`${(s.maxDrawdownPct * 100).toFixed(1)}%`} />
          <StatTile label={t('backtest.stat.avgR')} tone={s.avgR >= 0 ? 'gain' : 'loss'}
            value={`${s.avgR >= 0 ? '+' : ''}${s.avgR.toFixed(2)}`}
            sub={t('backtest.stat.hold', { hours: (s.avgHoldBars * 5 / 60).toFixed(1) })} />
          <StatTile label={t('backtest.stat.fees')} value={fmtNum(s.totalFees)} />
          <StatTile label={t('backtest.stat.trades')} value={s.totalTrades} />
          <StatTile label={t('backtest.stat.finalEquity')} tone={s.finalEquity >= balanceRef.current ? 'gain' : 'loss'}
            value={fmtNum(s.finalEquity, 0)} />
        </div>
      )}

      {/* ===== 成交明细 ===== */}
      {trades.length > 0 && (
        <div className="rounded-lg pt-card p-4 md:p-5 space-y-1">
          <div className="text-[11px] font-black text-muted-foreground tracking-wide flex items-center gap-1.5 pb-1">
            <History className="w-3.5 h-3.5" /> {t('backtest.trades.title')}
            <span className="ml-auto font-bold num">{t('backtest.trades.count', { count: trades.length })}</span>
          </div>
          {/* 循环变量避开 t：与 i18n 的 t 同名会遮蔽 */}
          {pagedTrades.map((tr: BacktestTrade, i: number) => {
            const isLong = tr.side === 'LONG';
            const win = tr.pnl >= 0;
            return (
              <button
                key={page * TRADES_PAGE + i}
                type="button"
                onClick={() => jumpToTime(tr.closeTime)}
                title={t('backtest.trades.jumpTip')}
                className="w-full text-left flex items-center gap-2.5 py-2 px-2 -mx-2 rounded-lg text-[11px] border-b border-border/40 last:border-0 hover:bg-surface-hover/50 transition-colors"
              >
                <span className={cn('w-1 self-stretch rounded-full shrink-0', isLong ? 'bg-gain' : 'bg-loss')} />
                <div className="min-w-0 shrink-0">
                  <div className="font-bold leading-tight">
                    <span className={cn('text-[10px] font-black', isLong ? 'text-gain' : 'text-loss')}>{isLong ? t('side.long') : t('side.short')}</span>
                    <span className="ml-1 text-[10px] text-muted-foreground">{tr.leverage}x</span>
                    <span className="ml-1.5 text-[9px] font-bold px-1 py-px rounded bg-muted text-muted-foreground">
                      {EXIT_LABEL[tr.exitReason] ? t(EXIT_LABEL[tr.exitReason]) : tr.exitReason}
                    </span>
                  </div>
                  <div className="text-[10px] text-muted-foreground num leading-tight mt-0.5">
                    {fmtNum(tr.entryPrice)} → {fmtNum(tr.exitPrice)}
                  </div>
                </div>
                <div className="hidden sm:block text-[10px] text-muted-foreground num shrink-0">
                  {fmtDateTime(tr.openTime)}
                </div>
                <div className="ml-auto text-right shrink-0">
                  <span className={cn('font-black num block leading-tight', win ? 'text-gain' : 'text-loss')}>
                    {win ? '+' : ''}{fmtNum(tr.pnl)}
                  </span>
                  <span className="text-[9px] text-muted-foreground/70 num leading-tight mt-0.5 block">
                    {tr.rMultiple != null ? `${tr.rMultiple >= 0 ? '+' : ''}${Number(tr.rMultiple).toFixed(2)}R · ` : ''}{((tr.closeBarIndex - tr.openBarIndex) * 5 / 60).toFixed(1)}h
                  </span>
                </div>
              </button>
            );
          })}
          {pageCount > 1 && (
            <div className="flex items-center justify-center gap-3 pt-2">
              <button disabled={page === 0} onClick={() => setTradePage(page - 1)}
                className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary disabled:opacity-40"
                aria-label={t('pager.prev')}>
                <ChevronLeft className="w-3.5 h-3.5" />
              </button>
              <span className="text-[11px] font-bold text-muted-foreground num">{page + 1} / {pageCount}</span>
              <button disabled={page >= pageCount - 1} onClick={() => setTradePage(page + 1)}
                className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary disabled:opacity-40"
                aria-label={t('pager.next')}>
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
