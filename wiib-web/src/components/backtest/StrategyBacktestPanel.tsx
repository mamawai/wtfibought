import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { FlaskConical, Pause, Play, RotateCcw } from 'lucide-react';
import { ApiError, backtestApi } from '../../api';
import { BacktestChart, type ChartTradeMark } from './BacktestChart';
import { EquityChart } from '../EquityChart';
import { Stepper } from '../Stepper';
import { useToast } from '../ui/use-toast';
import { DatePicker } from '../ui/date-picker';
import { Pager } from '../ui/pager';
import { Seg } from '../ui/seg';
import { getCoinPriceDecimals } from '../../lib/coinConfig';
import { aggregateBars, barIndexAt, IV_OPTIONS, ivLabel } from '../../lib/klineAgg';
import { STRATEGIES, strategyDisplay } from '../../lib/strategyCatalog';
import { cn, fmtDateTime, fmtNum } from '../../lib/utils';
import type {
  BacktestEvent, BacktestResultPayload, BacktestTaskStatus,
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
const SYMBOLS = ['BTCUSDT', 'ETHUSDT'];
const TRADES_PAGE = 10;

const dayStartUtc = (yyyyMmDd: string) => Date.parse(`${yyyyMmDd}T00:00:00Z`);
const toDateInput = (ms: number) => new Date(ms).toISOString().slice(0, 10);
/** 带号金额，涨绿跌红 */
const signedCls = (v: number) => (v >= 0 ? 'up' : 'dn');

// ==================== 工作记录时间线 ====================

/** 一条事件：前面一颗点（类型定色），一行主信息 + 一行细节 + 时刻；点了图表跳到那根 */
function EventItem({ e, onJump }: { e: BacktestEvent; onJump: (barTimeMs: number) => void }) {
  const { t } = useTranslation('strategy');
  const d: Record<string, unknown> = e.data;
  const num = (v: unknown) => (v == null ? '—' : fmtNum(Number(v)));
  const sideText = (s: unknown) => (s === 'LONG' ? t('side.long') : t('side.short'));
  const sideCls = (s: unknown) => (s === 'LONG' ? 'up' : 'dn');
  /** 后端枚举先查词表，查不到原样吐后端值 */
  const enumText = (map: Record<string, string>, v: unknown) => {
    const raw = String(v);
    return map[raw] ? t(map[raw]) : raw;
  };

  let color: string | undefined; let title: ReactNode; let detail: ReactNode = null;
  switch (e.type) {
    case 'TASK_START':
      color = 'var(--color-foreground)';
      title = t('backtest.ev.taskStart');
      detail = <Trans ns="strategy" i18nKey="backtest.ev.taskStartDetail"
        values={{ bars: String(d['bars']), warmup: String(d['warmupBars']), leverage: String(d['leverage']) }}
        components={[<b className="num" key="b" />, <b className="num" key="w" />, <b className="num" key="l" />]} />;
      break;
    case 'SIGNAL':
      title = <>{t('backtest.ev.signal')} {String(d['orderType'])} <span className={sideCls(d['side'])}>{sideText(d['side'])}</span> @ <span className="num">{num(d['entryRef'])}</span></>;
      detail = <>SL <span className="num dn">{num(d['sl'])}</span> · TP <span className="num up">{num(d['tp'])}</span>{d['reason'] != null && <> · {String(d['reason'])}</>}</>;
      break;
    case 'ORDER_CANCELLED':
      title = t('backtest.ev.cancelled');
      detail = <>{String(d['orderType'])} {sideText(d['side'])} @ <span className="num">{num(d['entryRef'])}</span></>;
      break;
    case 'ENTRY_FILL':
      color = d['side'] === 'LONG' ? 'var(--color-gain)' : 'var(--color-loss)';
      title = <>{t('backtest.ev.entry')} <span className={sideCls(d['side'])}>{sideText(d['side'])} {String(d['leverage'])}x</span> @ <span className="num">{num(d['price'])}</span></>;
      detail = <>{t('backtest.ev.qty')} <span className="num">{num(d['qty'])}</span> · {String(d['orderType'])} · SL <span className="num dn">{num(d['sl'])}</span> · TP <span className="num up">{num(d['tp'])}</span></>;
      break;
    case 'ENTRY_REJECTED':
      color = 'var(--color-warning)';
      title = <>{t('backtest.ev.rejected')} · {enumText(REJECT_REASON, d['reason'])}</>;
      detail = <>{String(d['orderType'])} {sideText(d['side'])} @ <span className="num">{num(d['price'])}</span></>;
      break;
    case 'EXIT': {
      const pnl = Number(d['pnl']);
      color = pnl >= 0 ? 'var(--color-gain)' : 'var(--color-loss)';
      title = <>
        {enumText(EXIT_LABEL, d['reason'])} <span className={cn('num', signedCls(pnl))}>{pnl >= 0 ? '+' : ''}{fmtNum(pnl)}</span>
        {d['rMultiple'] != null && <span className="num"> ({Number(d['rMultiple']) >= 0 ? '+' : ''}{Number(d['rMultiple']).toFixed(2)}R)</span>}
      </>;
      detail = <Trans ns="strategy" i18nKey="backtest.ev.exitDetail"
        values={{ entry: num(d['entry']), exit: num(d['exit']), bars: String(d['holdBars']) }}
        components={[<span className="num" key="i" />, <span className="num" key="o" />, <span className="num" key="h" />]} />;
      break;
    }
    case 'TASK_DONE':
      color = 'var(--color-foreground)';
      title = t('backtest.ev.done', { count: Number(d['totalTrades']) });
      break;
    case 'TASK_FAILED':
      color = 'var(--color-loss)';
      title = <span className="dn">{t('backtest.failed')}</span>;
      detail = <span className="dn">{String(d['message'])}</span>;
      break;
    default:
      title = e.type;
  }

  return (
    <button type="button" className="te" style={color ? { '--c': color } as CSSProperties : undefined}
            onClick={() => e.barTimeMs > 0 && onJump(e.barTimeMs)}>
      <b>{title}</b>{detail != null && <> <span>{detail}</span></>}
      {e.barTimeMs > 0 && <time className="num">{fmtDateTime(e.barTimeMs)}</time>}
    </button>
  );
}

/** 汇总里的一格：标签、数、底下一行小字 */
function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div>
      <span>{label}</span>
      <b className="num">{value}</b>
      {sub != null && <small className="num">{sub}</small>}
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
  const [leverage, setLeverage] = useState(5);

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

  const [tradePage, setTradePage] = useState(1);

  const afterRef = useRef(-1);                 // events 游标
  const taskRef = useRef<string | null>(null); // 轮询循环里判断任务是否被切换
  const klinesBusyRef = useRef<string | null>(null);   // 正在拉哪个任务的 K 线
  const klinesDoneRef = useRef<string | null>(null);   // 该任务 K 线已拉全，轮询不再重拉
  const balanceRef = useRef(100000);           // 权益曲线基线（cumPnl = equity - 基线）
  const timelineRef = useRef<HTMLDivElement>(null);
  const followRef = useRef(true);              // 时间线是否贴底跟随

  const running = status?.state === 'RUNNING';
  const queued = status?.state === 'QUEUED';
  const busy = running || queued || submitting;
  const done = status?.state === 'DONE';
  const warmupBars = status?.warmupBars ?? 0;
  // 结果区用任务自己的币种；表单那个币种只管下一次提交
  const viewSymbol = status?.symbol ?? symbol;

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
    if (klinesBusyRef.current === tid || klinesDoneRef.current === tid) return;
    klinesBusyRef.current = tid;
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
      // 只清自己的占位（上个任务这轮可能收尾得晚）
      if (klinesBusyRef.current === tid) klinesBusyRef.current = null;
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
      let resultLoaded = false;
      let failing = false;     // 连续失败中：只在头一次弹提示
      for (;;) {
        if (!active || taskRef.current !== taskId) return;
        let statusOk = false;
        try {
          const st = await backtestApi.status(taskId);
          statusOk = true;
          if (!active || taskRef.current !== taskId) return;
          setStatus(st);
          if (st.totalBars > 0) void loadKlines(taskId);
          await pullEvents();
          if (st.state === 'DONE') {
            if (!resultLoaded) {
              const r = await backtestApi.result(taskId);
              if (!active || taskRef.current !== taskId) return;
              setResult(r);
              resultLoaded = true;
            }
            // K 线拉全才收工；还在拉或拉失败了就留在循环里，下一轮接着拉
            if (st.totalBars === 0 || klinesDoneRef.current === taskId) return;
          }
          if (st.state === 'FAILED') return;
          failing = false;     // 这一轮全都拉成了才算恢复
        } catch (e) {
          if (!active) return;
          // 任务不存在（服务重启/被淘汰）：status 回 500。清存档提示重跑（events/result 的 500 不算）
          if (!statusOk && e instanceof ApiError && e.code === 500) {
            toast(e.message || t('backtest.toast.pollFailed'), 'error');
            sessionStorage.removeItem(STORE_KEY);
            setTask(null);
            setStatus(null);
            return;
          }
          // 断网、网关超时这类：提示一次，照常按间隔重试
          if (!failing) toast((e as Error).message || t('backtest.toast.pollFailed'), 'error');
          failing = true;
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
    setSubmitting(true);
    try {
      const { taskId: tid } = await backtestApi.run({
        strategyId, symbol, fromMs, toMs, initialBalance: bal, leverage,
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
      setTradePage(1);
      sessionStorage.setItem(STORE_KEY, JSON.stringify({ taskId: tid, balance: bal }));
      setTask({ id: tid });
    } catch (e) {
      toast((e as Error).message || t('backtest.toast.submitFailed'), 'error');
    } finally {
      setSubmitting(false);
    }
  }, [fromDate, toDate, balance, leverage, strategyId, symbol, toast, t]);

  // ---- 跳转（事件/成交行 → 图表游标） ----
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

  // 标记贴到所在蜡烛的 openTime 上（5m 也要）：后端成交时间是当根 5m 的收盘时刻（openTime+299999），
  // 不落在蜡烛 openTime 上 LWC 会画到下一根
  const aggMarks = useMemo<ChartTradeMark[]>(() => {
    if (aggBars.length === 0) return marks;
    const loadedEnd = aggBars[aggBars.length - 1][0] + ivMin * 60_000;
    return marks
      // K 线还在分段拉、没拉到这笔的先不标，拉到了再贴（否则会全堆在已拉到的最后一根上）
      .filter(m => m.time < loadedEnd)
      .map(m => {
        const i = barIndexAt(aggBars, m.time);
        return { ...m, barIndex: i, time: aggBars[i][0] };
      });
  }, [marks, aggBars, ivMin]);

  // ---- 成交分页 ----
  const trades = result?.trades ?? [];
  const pageCount = Math.max(1, Math.ceil(trades.length / TRADES_PAGE));
  const page = Math.min(tradePage, pageCount);
  const pagedTrades = trades.slice((page - 1) * TRADES_PAGE, page * TRADES_PAGE);

  /** 认不出的 id 原样显示：后端加了策略而前端词表还没登记时不至于空白 */
  const strategyName = (id: string) => {
    const m = strategyDisplay(id);
    return m ? t(m.nameKey) : id;
  };
  const mech = strategyDisplay(strategyId);

  const s = result?.summary;
  // 滑杆在 5m 空间（撮合口径）；图表游标映射到聚合空间
  const sliderCursor = cursor == null ? bars.length : Math.floor(cursor);
  const chartCursor = cursor == null ? aggBars.length
    : cursorTime != null ? barIndexAt(aggBars, cursorTime) + 1 : 0;
  const progressPct = status && status.totalBars > 0 ? Math.round((status.barsDone / status.totalBars) * 100) : 0;
  const started = bars.length > 0 || events.length > 0;

  return (
    <>
      {/* ===== 配置台 ===== */}
      <div className="blk cfg">
        <div className="fld">
          <div className="fl">{t('backtest.form.strategy')}</div>
          <Seg value={strategyId} onChange={setStrategyId} disabled={busy} label={t('backtest.form.strategy')}
               options={STRATEGIES.map(m => ({ value: m.id, label: t(m.nameKey) }))} />
        </div>
        <div className="fld">
          <div className="fl">{t('backtest.form.symbol')}</div>
          <Seg value={symbol} onChange={setSymbol} disabled={busy} label={t('backtest.form.symbol')}
               options={SYMBOLS.map(sym => ({ value: sym, label: sym.replace('USDT', '') }))} />
        </div>
        <div className="fld">
          <div className="fl">{t('backtest.form.from')}</div>
          <DatePicker value={fromDate} disabled={busy} onChange={setFromDate} />
        </div>
        <div className="fld">
          <div className="fl">{t('backtest.form.to')}</div>
          <DatePicker value={toDate} disabled={busy} onChange={setToDate} />
        </div>
        <div className="fld bal">
          <label className="fl" htmlFor="bt-bal">{t('backtest.form.balance')}</label>
          <label className="inp">
            <input id="bt-bal" inputMode="decimal" value={balance} disabled={busy} onChange={e => setBalance(e.target.value)} />
            <span className="u">U</span>
          </label>
        </div>
        <div className="fld">
          <div className="fl">{t('backtest.form.leverage')}</div>
          <Stepper value={leverage} onChange={setLeverage} min={1} max={100} disabled={busy}
                   label={t('backtest.form.leverage')} lessLabel={t('backtest.form.less')} moreLabel={t('backtest.form.more')} />
        </div>
        <button type="button" className="btn ink lg go3" onClick={() => void handleRun()} disabled={busy}>
          <Play className="ic" />
          {queued ? t('backtest.run.queued') : running ? t('backtest.run.running') : t('backtest.run.start')}
        </button>
        {mech && <p className="desc">{t(mech.mechKey)}</p>}
      </div>

      {/* 排队、进度、失败 */}
      {queued && status && (
        <div className="prog">
          <span><Trans ns="strategy" i18nKey="backtest.queueHint" values={{ pos: status.queuePos }} components={[<b className="num" key="p" />]} /></span>
        </div>
      )}
      {running && status && (
        <div className="prog">
          <span className="num">{t('backtest.progress', { done: fmtNum(status.barsDone, 0), total: fmtNum(status.totalBars, 0), pct: progressPct })}</span>
          <i><b style={{ width: `${progressPct}%` }} /></i>
        </div>
      )}
      {status?.state === 'FAILED' && <p className="prog dn">{status.error || t('backtest.failed')}</p>}

      {!task && !started && (
        <div className="empty idle">
          <span className="ib0"><FlaskConical className="ic" /></span>
          <b>{t('backtest.idleTitle')}</b>
          <span>{t('backtest.idleHint')}</span>
        </div>
      )}

      {/* ===== 汇总指标 ===== */}
      {s && (
        <div className="st8">
          <Stat label={t('backtest.stat.netProfit')}
                value={<span className={signedCls(s.netProfit)}>{s.netProfit >= 0 ? '+' : ''}{fmtNum(s.netProfit)}</span>}
                sub={`${s.returnPct >= 0 ? '+' : ''}${(s.returnPct * 100).toFixed(1)}%`} />
          <Stat label={t('backtest.stat.winRate')} value={s.totalTrades > 0 ? `${(s.winRate * 100).toFixed(1)}%` : '—'}
                sub={t('backtest.stat.winLoss', { wins: s.wins, losses: s.losses })} />
          <Stat label={t('backtest.stat.pf')} value={s.profitFactor >= 9999 ? '∞' : s.profitFactor.toFixed(2)}
                sub={`Sharpe ${s.sharpeRatio.toFixed(2)}`} />
          <Stat label={t('backtest.stat.maxDd')} value={<span className="dn">-{(s.maxDrawdownPct * 100).toFixed(1)}%</span>} />
          <Stat label={t('backtest.stat.avgR')} value={`${s.avgR >= 0 ? '+' : ''}${s.avgR.toFixed(2)}`}
                sub={t('backtest.stat.hold', { hours: (s.avgHoldBars * 5 / 60).toFixed(1) })} />
          <Stat label={t('backtest.stat.fees')} value={fmtNum(s.totalFees)} />
          <Stat label={t('backtest.stat.trades')} value={s.totalTrades} />
          <Stat label={t('backtest.stat.finalEquity')} value={fmtNum(s.finalEquity, 0)} />
        </div>
      )}

      {/* ===== 主区：图表 + 工作记录 ===== */}
      {started && (
        <div className="main">
          <div>
            <div className="cvh">
              <span className="num">{viewSymbol.replace('USDT', '')} · {ivLabel(ivMin)}{status ? ` · ${strategyName(status.strategyId)}` : ''}</span>
              <Seg value={String(ivMin)} label={t('player.interval')} options={IV_OPTIONS.map(o => ({ value: String(o.min), label: o.label }))}
                   onChange={v => {
                     const min = Number(v);
                     setIvMin(min);
                     if (bars.length > 0) toast(t('backtest.toast.ivSwitched', { iv: ivLabel(min) }), 'info');
                   }} />
              {cursorTime != null && <span className="num">{t('backtest.replayAt', { time: fmtDateTime(cursorTime) })}</span>}
              <span className="sp" />
              <span className="num">{bars.length > 0 ? t('backtest.barsLoaded', { bars: fmtNum(bars.length, 0) }) : t('backtest.waitingBars')}</span>
            </div>
            <BacktestChart bars={aggBars} marks={aggMarks} cursor={chartCursor}
                           symbol={viewSymbol} decimals={getCoinPriceDecimals(viewSymbol)} bucketSec={ivMin * 60} />

            {/* 回放控制条：跑完且有数据才开放 */}
            {done && bars.length > 0 && (
              <div className="player">
                <button type="button" className="ib" onClick={() => setPlaying(p => !p)}
                        aria-label={playing ? t('player.pause') : t('player.play')} title={playing ? t('player.pause') : t('player.play')}>
                  {playing ? <Pause className="ic" /> : <Play className="ic" />}
                </button>
                <button type="button" className="ib" onClick={() => { setPlaying(false); setCursor(null); }}
                        aria-label={t('player.reset')} title={t('player.reset')}>
                  <RotateCcw className="ic" />
                </button>
                <Seg value={String(speed)} onChange={v => setSpeed(Number(v))} label={t('player.speed')}
                     options={SPEEDS.map(sp => ({ value: String(sp.bps), label: t(sp.labelKey) }))} />
                <input type="range" min={warmupBars} max={bars.length} value={sliderCursor}
                       onChange={e => { setPlaying(false); setCursor(Number(e.target.value)); }} aria-label={t('player.progress')} />
              </div>
            )}

            {equityPoints.length > 0 && (
              <section className="sec">
                <div className="sec-h"><h2>{t('backtest.equityTitle')}</h2></div>
                <EquityChart points={equityPoints} />
              </section>
            )}

            {trades.length > 0 && (
              <section className="sec">
                <div className="sec-h">
                  <h2>{t('backtest.trades.title')}<small>{t('backtest.trades.count', { count: trades.length })}</small></h2>
                  <span className="r">{t('backtest.trades.jumpTip')}</span>
                </div>
                <div className="tw">
                  <table className="tbl hov min-w-[620px]">
                    <thead>
                      <tr>
                        <th>{t('backtest.trades.col.open')}</th>
                        <th>{t('backtest.trades.col.side')}</th>
                        <th className="r">{t('backtest.trades.col.entry')}</th>
                        <th className="r">{t('backtest.trades.col.exit')}</th>
                        <th>{t('backtest.trades.col.reason')}</th>
                        <th className="r">{t('backtest.trades.col.hold')}</th>
                        <th className="r">{t('backtest.trades.col.pnl')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {/* 循环变量避开 t：与 i18n 的 t 同名会遮蔽 */}
                      {pagedTrades.map((tr, i) => (
                        <tr key={(page - 1) * TRADES_PAGE + i} onClick={() => jumpToTime(tr.closeTime)}>
                          {/* 键盘从这颗跳到图表；鼠标点整行 */}
                          <td className="num mute">
                            <button type="button" onClick={e => { e.stopPropagation(); jumpToTime(tr.closeTime); }}>
                              {fmtDateTime(tr.openTime)}
                            </button>
                          </td>
                          <td>
                            <span className={cn('tag sm', tr.side === 'LONG' ? 'g' : 'r')}>
                              {tr.side === 'LONG' ? t('side.long') : t('side.short')} {tr.leverage}x
                            </span>
                          </td>
                          <td className="r num">{fmtNum(tr.entryPrice)}</td>
                          <td className="r num">{fmtNum(tr.exitPrice)}</td>
                          <td>{EXIT_LABEL[tr.exitReason] ? t(EXIT_LABEL[tr.exitReason]) : tr.exitReason}</td>
                          <td className="r num mute">{((tr.closeBarIndex - tr.openBarIndex) * 5 / 60).toFixed(1)}h</td>
                          <td className="r num pn">
                            <span className={signedCls(tr.pnl)}>{tr.pnl >= 0 ? '+' : ''}{fmtNum(tr.pnl)}</span>
                            {tr.rMultiple != null && <small> {tr.rMultiple >= 0 ? '+' : ''}{Number(tr.rMultiple).toFixed(2)}R</small>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pager page={page} pages={pageCount} onChange={setTradePage} />
              </section>
            )}
          </div>

          {/* 右：工作记录时间线 */}
          <aside>
            <div className="sec-h">
              <h2>{t('backtest.timeline.title')}<small>{t('backtest.timeline.count', { count: visibleEvents.length })}</small></h2>
            </div>
            <div ref={timelineRef} className="tl"
                 onScroll={e => {
                   const el = e.currentTarget;
                   followRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
                 }}>
              {visibleEvents.length === 0
                ? <p className="mute">{t('backtest.timeline.waiting')}</p>
                : visibleEvents.map(e => <EventItem key={e.seq} e={e} onJump={jumpToTime} />)}
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
