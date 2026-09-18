import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowDownRight, ArrowUpRight, CalendarDays, ChevronRight, Dices, Flag, Gauge,
  History, KeyRound, Loader2, Pause, Play, RotateCcw, Skull, Sparkles, Wallet, X, Zap,
} from 'lucide-react';
import { ApiError, backtestApi, llmEndpointApi } from '../../api';
import { BacktestChart, type ChartTradeMark } from './BacktestChart';
import { EquityChart } from '../EquityChart';
import { LlmEndpointSelect } from '../LlmEndpointSelect';
import { Markdown } from '../Markdown';
import { useToast } from '../ui/use-toast';
import { DatePicker } from '../ui/date-picker';
import { getCoinPriceDecimals } from '../../lib/coinConfig';
import { aggregateBars, barIndexAt, IV_OPTIONS, ivLabel } from '../../lib/klineAgg';
import { cn, fmtDateTime, fmtNum } from '../../lib/utils';
import {
  close, endSession, equity, initialState, open, openPositions, stats, step, unrealized,
  type ReplayFill, type ReplayState, type ReplayTrade, type Side,
} from '../../lib/replayEngine';
import type { LlmEndpointView, ReplayCoachRequest, ReplayCoverage } from '../../types';
import type { TnEquityPoint } from '../../types/testnet';

const M5 = 300_000;
/** 开局前置上下文根数：给玩家一屏"过去"可看，也给画线留参照 */
const CONTEXT_BARS = 200;
const DURATIONS = [3, 7, 14, 28];
/** 自动播放速度档（bar/秒） */
const AUTO_SPEEDS = [1, 3, 10];
const PCT_OPTIONS = [25, 50, 75, 100];
/** 杠杆档：每次开/加仓时现选，对局中随时可换（同向加仓换档 → 仓位显示有效杠杆） */
const LEVERAGE_OPTIONS = [1, 2, 3, 5, 10, 20, 50];

const dayStartUtc = (yyyyMmDd: string) => Date.parse(`${yyyyMmDd}T00:00:00Z`);
const toDateInput = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** 表里一律存词表 key，渲染时现查——存成文案会在模块加载那一刻定死，切语言不跟着变 */
const REASON_LABEL: Record<ReplayTrade['reason'], string> = {
  MANUAL: 'replay.reason.manual', LIQUIDATION: 'replay.reason.liquidation', END: 'replay.reason.end',
};
/** 成交种类 → 图表标记文字（entry 类只有加仓要标出来，首开沿用图表默认的 多/空） */
const FILL_LABEL: Record<ReplayFill['kind'], string | undefined> = {
  OPEN: undefined, ADD: 'replay.fill.add', CLOSE: 'replay.fill.close',
  REDUCE: 'replay.fill.reduce', LIQUIDATION: 'replay.fill.liquidation', END: 'replay.fill.end',
};
const SIDE_LABEL: Record<Side, string> = { LONG: 'side.long', SHORT: 'side.short' };
/** 有效杠杆显示：整数照常，加仓换档产生的小数留一位 */
const fmtLev = (l: number) => `${Number.isInteger(l) ? l : l.toFixed(1)}x`;

/** AI 提示送最近多少根（当前周期）；评估把整局聚合到不超过这个数（后端上限 400） */
const HINT_BARS = 150;
const REVIEW_BARS_MAX = 400;
const round = (v: number, dec: number) => Number(v.toFixed(dec));
/** K 线行 → 教练请求里的一根（价格按币种精度、量保留 2 位，省 token） */
const toCoachBar = (r: number[], label: (ms: number) => string, dec: number) =>
  ({ t: label(r[0]), o: round(r[1], dec), h: round(r[2], dec), l: round(r[3], dec), c: round(r[4], dec), v: round(r[5] ?? 0, 2) });

/** 一次 AI 教练调用的展示态 */
interface AiRun {
  text: string;
  busy: boolean;
  error: string | null;
  /** 2201/2202：没配 LLM 或配置建不出模型，给"去配置"入口 */
  needsConfig: boolean;
  /** 提示发出时的盘面时刻（盲测相对标签），标在卡片上 */
  at?: string;
}

/** AI 输出区：流式正文 + 错误行（配置类错误带去配置入口） */
function AiBody({ run, onGoConfig }: { run: AiRun; onGoConfig: () => void }) {
  const { t } = useTranslation('strategy');
  return (
    <div className="space-y-1.5">
      {run.busy && !run.text && (
        <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <Loader2 className="w-3.5 h-3.5 animate-spin" /> {t('replay.aiThinking')}
        </div>
      )}
      {run.text && <div className="text-xs leading-relaxed"><Markdown content={run.text} /></div>}
      {run.error && (
        <div className="rounded-md border border-warning/40 bg-warning/10 px-2.5 py-1.5 text-[11px] flex items-center gap-2">
          <KeyRound className="w-3.5 h-3.5 text-warning shrink-0" />
          <span className="flex-1">{run.error}</span>
          {run.needsConfig && (
            <button type="button" onClick={onGoConfig} className="font-bold text-primary hover:underline shrink-0">{t('replay.goConfig')}</button>
          )}
        </div>
      )}
    </div>
  );
}

interface Session {
  symbol: string;
  blind: boolean;
  /** 回放段首根 openTime(ms)；盲测的时间脱敏基准 */
  startMs: number;
  /** 原始 5m 行（含上下文段）；切周期在此之上前端聚合 */
  raw: number[][];
  balance: number;
}

/**
 * 手动复盘：按所选周期（5m/15m/1h/4h/1d）逐根揭示 K 线，按收盘价开多/开空/加仓/平仓/减仓
 * （合约式双向持仓，杠杆每次下单现选、对局中随时可换，全仓口径）。底层数据一律 5m，对局中可切周期，之后按新周期推进。
 * 撮合在 lib/replayEngine（纯函数），本组件只管节奏与展示。成绩不落库，刷新即失。
 */
export function ReplayPanel() {
  const { t } = useTranslation(['strategy', 'common', 'errors']);
  const { toast } = useToast();

  // ---- 配置 ----
  const [coverage, setCoverage] = useState<ReplayCoverage[]>([]);
  const [symbol, setSymbol] = useState('ETHUSDT');
  const [blind, setBlind] = useState(true);
  const [customDate, setCustomDate] = useState(() => toDateInput(Date.now() - 30 * 86_400_000));
  const [days, setDays] = useState(3);
  const [balance, setBalance] = useState('100000');
  const [loading, setLoading] = useState(false);

  // ---- 本局 ----
  const [session, setSession] = useState<Session | null>(null);
  const [state, setState] = useState<ReplayState>(() => initialState(100_000));
  const [played, setPlayed] = useState(0);      // 已揭示的可播放 bar 数（当前周期口径）
  const [ivMin, setIvMin] = useState(5);        // 回放周期（分钟），对局中可切
  const [finished, setFinished] = useState<'END' | 'LIQUIDATION' | null>(null);
  const [auto, setAuto] = useState(0);          // 0=手动，其余为 bar/秒
  const [openPct, setOpenPct] = useState(100);  // 开/加仓：占可用现金的比例
  const [closePct, setClosePct] = useState(100); // 平/减仓：占该侧仓位数量的比例
  const [leverage, setLeverage] = useState(5);   // 下一次开/加仓用的杠杆，对局中随时换；跨局保留
  /** 结算用权益点（真实时间；只在结算面板展示，盲测不泄露） */
  const eqPointsRef = useRef<TnEquityPoint[]>([]);

  // ---- AI 教练（从用户 BYOK 端点库里选一条；不选=默认端点） ----
  const navigate = useNavigate();
  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  const [aiEndpointId, setAiEndpointId] = useState<number | null>(null);
  const [hint, setHint] = useState<AiRun | null>(null);
  const [review, setReview] = useState<AiRun | null>(null);
  const aiAbortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    llmEndpointApi.list().then(setEndpoints).catch(() => setEndpoints([]));
    return () => aiAbortRef.current?.abort();
  }, []);
  const goConfig = useCallback(() => navigate('/ai'), [navigate]);

  /** 当前周期视图：open < startMs 的桶算上下文（开局即揭示），其余为可播放段 */
  const derived = useMemo(() => {
    if (!session) return null;
    const aggBars = aggregateBars(session.raw, ivMin);
    let ctxCount = 0;
    while (ctxCount < aggBars.length && aggBars[ctxCount][0] < session.startMs) ctxCount++;
    return { aggBars, ctxCount, playable: aggBars.length - ctxCount };
  }, [session, ivMin]);

  // doNext 被键盘/interval 调，用 ref 拿最新闭包
  const stateRef = useRef(state);
  const playedRef = useRef(played);
  const sessionRef = useRef(session);
  const finishedRef = useRef(finished);
  const derivedRef = useRef(derived);
  useEffect(() => { stateRef.current = state; }, [state]);
  useEffect(() => { playedRef.current = played; }, [played]);
  useEffect(() => { sessionRef.current = session; }, [session]);
  useEffect(() => { finishedRef.current = finished; }, [finished]);
  useEffect(() => { derivedRef.current = derived; }, [derived]);

  useEffect(() => {
    backtestApi.historyCoverage().then(setCoverage).catch(() => { /* 配置台显示"暂无数据" */ });
  }, []);

  const cov = coverage.find(c => c.symbol === symbol);

  // ---- 开局 ----
  const handleStart = useCallback(async () => {
    if (!cov) {
      toast(t('replay.toast.noCoverage'), 'error');
      return;
    }
    const bal = Number(balance) || 100000;
    const need = days * 288;
    const minStart = cov.earliestMs + CONTEXT_BARS * M5;
    const maxStart = cov.latestMs - need * M5;
    if (maxStart <= minStart) {
      toast(t('replay.toast.notEnoughDepth'), 'error');
      return;
    }
    let startMs: number;
    if (blind) {
      const slots = Math.floor((maxStart - minStart) / M5);
      startMs = minStart + Math.floor(Math.random() * (slots + 1)) * M5;
    } else {
      const picked = dayStartUtc(customDate);
      if (!Number.isFinite(picked)) {
        toast(t('replay.toast.badDate'), 'error');
        return;
      }
      startMs = Math.min(Math.max(picked, minStart), maxStart);
    }
    setLoading(true);
    try {
      const page = await backtestApi.historyKlines(symbol, startMs - CONTEXT_BARS * M5, startMs + need * M5);
      const rows = page.rows;
      const playable5 = rows.filter(r => r[0] >= startMs).length;
      if (playable5 < 30) {
        toast(t('replay.toast.rangeTooShort'), 'error');
        return;
      }
      eqPointsRef.current = [];
      aiAbortRef.current?.abort();
      setHint(null);
      setReview(null);
      setState(initialState(bal));
      setPlayed(0);
      setIvMin(5);
      setFinished(null);
      setAuto(0);
      setSession({ symbol, blind, startMs, raw: rows, balance: bal });
    } catch (e) {
      toast((e as Error).message || t('replay.toast.klineFailed'), 'error');
    } finally {
      setLoading(false);
    }
  }, [cov, balance, days, blind, customDate, symbol, toast, t]);

  // ---- 逐根推进（键盘/自动播放共用；按当前周期一根一根走，不可回退，重开一局即复位） ----
  const doNext = useCallback(() => {
    const ses = sessionRef.current;
    const d = derivedRef.current;
    if (!ses || !d || finishedRef.current) return;
    const i = d.ctxCount + playedRef.current;
    if (i >= d.aggBars.length) return;
    const bar = d.aggBars[i];
    let st = step(stateRef.current, bar[4], i, bar[0]);
    const newPlayed = playedRef.current + 1;
    let fin: 'END' | 'LIQUIDATION' | null = null;
    if (st.liquidated) {
      fin = 'LIQUIDATION';
    } else if (newPlayed >= d.playable) {
      st = endSession(st, bar[4], i, bar[0]);
      fin = 'END';
    }
    eqPointsRef.current.push({ time: bar[0], cumPnl: equity(st, bar[4]) - ses.balance });
    setState(st);
    setPlayed(newPlayed);
    if (fin) {
      setFinished(fin);
      setAuto(0);
    }
  }, []);

  // ---- 对局中切周期：进度按时间对齐到新周期已完整走完的 bar（只回退不前进，不泄露未来） ----
  const switchIv = (min: number) => {
    const ses = session;
    const d = derived;
    if (!ses || !d || min === ivMin) return;
    const newAgg = aggregateBars(ses.raw, min);
    const newIvMs = min * 60_000;
    let ctx = 0;
    while (ctx < newAgg.length && newAgg[ctx][0] < ses.startMs) ctx++;
    if (finished) {
      // 结算后切周期只是换视图，全量揭示
      setPlayed(newAgg.length - ctx);
      setIvMin(min);
      return;
    }
    const playedToMs = played > 0 ? d.aggBars[d.ctxCount + played - 1][0] + ivMin * 60_000 : ses.startMs;
    let np = 0;
    while (ctx + np < newAgg.length && newAgg[ctx + np][0] + newIvMs <= playedToMs) np++;
    setPlayed(np);
    setIvMin(min);
    setAuto(0);
    // 进度向下对齐会重走一小段，把重叠窗口的旧权益点裁掉，曲线保持单调
    const nextStepMs = newAgg[ctx + np]?.[0] ?? Infinity;
    eqPointsRef.current = eqPointsRef.current.filter(p => p.time < nextStepMs);
    toast(t('replay.toast.ivSwitched', { iv: ivLabel(min) }), 'info');
  };

  // 空格 = 下一根（输入框聚焦时不抢键）
  useEffect(() => {
    if (!session) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.code !== 'Space') return;
      const el = document.activeElement;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) return;
      e.preventDefault();
      doNext();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [session, doNext]);

  // 自动播放
  useEffect(() => {
    if (!auto || !session || finished) return;
    const timer = setInterval(doNext, 1000 / auto);
    return () => clearInterval(timer);
  }, [auto, session, finished, doNext]);

  // ---- 当前价（最后一根已揭示 bar 的收盘；开局时=上下文末根） ----
  const aggBars = derived?.aggBars ?? [];
  const ctxCount = derived?.ctxCount ?? 0;
  const playable = derived?.playable ?? 0;
  const curIdx = session ? ctxCount + played - 1 : 0;
  const curBar = session && aggBars.length > 0 ? aggBars[Math.max(0, Math.min(curIdx, aggBars.length - 1))] : null;
  const curPrice = curBar ? curBar[4] : 0;
  const curEquity = curBar ? equity(state, curPrice) : 0;

  /** 盲测未结算时显示 D{n} HH:mm 相对时间，其余显示真实时间 */
  const fmtReplayTime = useCallback((ms: number) => {
    const ses = sessionRef.current;
    if (ses?.blind && !finishedRef.current) {
      const day = Math.floor((ms - ses.startMs) / 86_400_000) + 1;
      const d = new Date(ms + 8 * 3_600_000);
      return `D${day} ${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
    }
    return fmtDateTime(ms);
  }, []);

  // ---- 交易操作（只能按当前收盘价）：同向再开=加仓；平仓按比例，不足 100% 就是减仓 ----
  const handleOpen = (side: Side) => {
    if (!session || !curBar || finished) return;
    setState(s => open(s, side, openPct / 100, leverage, curPrice, curIdx, curBar[0]));
  };
  const handleClose = (side: Side) => {
    if (!session || !curBar || finished) return;
    setState(s => close(s, side, closePct / 100, curPrice, curIdx, curBar[0], 'MANUAL'));
  };

  // ---- 图表标记：每次成交一条（按成交时间映射到当前周期的桶：开在 14:05 的单切 15m 后落在 14:00 蜡烛上） ----
  const marks = useMemo<ChartTradeMark[]>(() => {
    if (!derived || derived.aggBars.length === 0) return [];
    const bars = derived.aggBars;
    return state.fills.map(f => {
      const i = barIndexAt(bars, f.time);
      const entry = f.kind === 'OPEN' || f.kind === 'ADD';
      const labelKey = FILL_LABEL[f.kind];
      return {
        barIndex: i, time: bars[i][0], side: f.side, kind: entry ? 'entry' : 'exit',
        pnl: f.pnl, label: labelKey ? t(labelKey) : undefined,
      };
    });
    // 依赖带 t：切语言时标记文字跟着换
  }, [state, derived, t]);

  const held = openPositions(state);
  const decimals = session ? getCoinPriceDecimals(session.symbol) : 2;
  const st = finished && session ? stats(state, session.balance, eqPointsRef.current.map(p => p.cumPnl + session.balance)) : null;

  // ---- AI 教练：一次 SSE 调用 → 流式写进 AiRun。同一时刻只跑一个，再点就掐掉上一个 ----
  const runCoach = useCallback(async (req: ReplayCoachRequest, set: (r: AiRun) => void, at?: string) => {
    aiAbortRef.current?.abort();
    const ctrl = new AbortController();
    aiAbortRef.current = ctrl;
    let text = '', err: string | null = null;
    set({ text: '', busy: true, error: null, needsConfig: false, at });
    try {
      await backtestApi.replayCoach(req, e => {
        if (e.type === 'token') { text += e.text; set({ text, busy: true, error: null, needsConfig: false, at }); }
        else if (e.type === 'done') text = e.answer;
        else if (e.type === 'error') err = e.message;
      }, ctrl.signal);
      set({ text, busy: false, error: err ?? (text ? null : t('replay.emptyAnswer')), needsConfig: false, at });
    } catch (e) {
      if (ctrl.signal.aborted) return;   // 用户关卡片/重开一局主动掐的，不算错
      const code = e instanceof ApiError ? e.code : -1;
      set({ text, busy: false, error: (e as Error).message || t('errors:requestFailed'), needsConfig: code === 2201 || code === 2202, at });
    }
  }, [t]);

  /** 卡片上标"用的哪个模型"：选中的那条，没选就是默认那条 */
  const aiEndpoint = endpoints.find(e => e.id === aiEndpointId) ?? endpoints.find(e => e.isDefault) ?? endpoints[0];
  const aiLabel = aiEndpoint ? `${aiEndpoint.name} · ${aiEndpoint.model}` : t('replay.noEndpoint');

  /** 局中提示：最近 HINT_BARS 根已揭示 K 线 + 当前持仓；盲测只给相对时间标签 */
  const askHint = () => {
    if (!session || !derived || !curBar) return;
    const end = ctxCount + played;
    const rows = aggBars.slice(Math.max(0, end - HINT_BARS), end);
    void runCoach({
      mode: 'HINT', endpointId: aiEndpointId ?? undefined, symbol: session.symbol, intervalMin: ivMin,
      blind: session.blind && !finished, startAt: fmtReplayTime(session.startMs),
      bars: rows.map(r => toCoachBar(r, fmtReplayTime, decimals)),
      equity: round(curEquity, 2),
      positions: held.map(p => ({
        side: p.side, qty: round(p.qty, 4), entryPrice: round(p.entryPrice, decimals),
        leverage: round(p.leverage, 1), unrealizedPnl: round(unrealized(p, curPrice), 2),
      })),
    }, setHint, fmtReplayTime(curBar[0]));
  };

  /** 结算后评估：整局 K 线（聚合到 ≤REVIEW_BARS_MAX 根的最细周期）+ 全部成交 + 统计，AI 对着走势评操作行为；日期已揭晓用真实时间 */
  const askReview = () => {
    if (!session || !st) return;
    let rows = session.raw, iv = 5;
    for (const o of IV_OPTIONS) {
      rows = aggregateBars(session.raw, o.min);
      iv = o.min;
      if (rows.length <= REVIEW_BARS_MAX) break;
    }
    if (rows.length > REVIEW_BARS_MAX) rows = rows.slice(rows.length - REVIEW_BARS_MAX);
    void runCoach({
      mode: 'REVIEW', endpointId: aiEndpointId ?? undefined, symbol: session.symbol, intervalMin: iv, blind: false,
      startAt: fmtDateTime(session.startMs),
      bars: rows.map(r => toCoachBar(r, fmtDateTime, decimals)),
      trades: state.trades.map(tr => ({
        side: tr.side, qty: round(tr.qty, 4), leverage: round(tr.leverage, 1),
        entryPrice: round(tr.entryPrice, decimals), exitPrice: round(tr.exitPrice, decimals),
        pnl: round(tr.pnl, 2), openAt: fmtDateTime(tr.openTime), closeAt: fmtDateTime(tr.closeTime), reason: tr.reason, partial: tr.partial,
      })),
      stats: {
        totalTrades: st.totalTrades, wins: st.wins, losses: st.losses, netProfit: round(st.netProfit, 2),
        returnPct: st.returnPct, maxDrawdownPct: st.maxDrawdownPct, totalFees: round(st.totalFees, 2),
        initialBalance: session.balance, finalEquity: round(st.finalEquity, 2),
      },
    }, setReview);
  };

  // ==================== 配置台 ====================
  if (!session) {
    return (
      <div className="rounded-lg pt-card p-4 md:p-5 space-y-4">
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <div className="microlabel uppercase mb-1">{t('replay.form.symbol')}</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              {['BTCUSDT', 'ETHUSDT'].map(sym => (
                <button key={sym} type="button"
                  onClick={() => setSymbol(sym)}
                  className={cn('px-3 h-9 text-xs font-bold transition-colors',
                    symbol === sym ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                  {sym.replace('USDT', '')}
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="microlabel uppercase mb-1">{t('replay.form.mode')}</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              <button type="button" onClick={() => setBlind(true)}
                className={cn('px-3 h-9 text-xs font-bold flex items-center gap-1.5 transition-colors',
                  blind ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                <Dices className="w-3.5 h-3.5" /> {t('replay.form.blind')}
              </button>
              <button type="button" onClick={() => setBlind(false)}
                className={cn('px-3 h-9 text-xs font-bold flex items-center gap-1.5 transition-colors',
                  !blind ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                <CalendarDays className="w-3.5 h-3.5" /> {t('replay.form.custom')}
              </button>
            </div>
          </div>
          {!blind && (
            <div>
              <div className="microlabel uppercase mb-1">{t('replay.form.startDate')}</div>
              <DatePicker value={customDate} onChange={setCustomDate}
                className="h-9 px-2.5 rounded-md border border-border bg-input text-xs num" />
            </div>
          )}
          <div>
            <div className="microlabel uppercase mb-1">{t('replay.form.duration')}</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              {DURATIONS.map(d => (
                <button key={d} type="button" onClick={() => setDays(d)}
                  className={cn('px-3 h-9 text-xs font-bold transition-colors num',
                    days === d ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                  {t('replay.days', { count: d })}
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="microlabel uppercase mb-1">{t('replay.form.balance')}</div>
            <input type="number" min={1} value={balance}
              onChange={e => setBalance(e.target.value)}
              className="h-9 w-28 px-2.5 rounded-md border border-border bg-input text-xs num" />
          </div>
          {/* AI 教练：从 AI 页「模型配置」的端点库里选一条（提示可能每几根点一次，评估一局一次；挑贵的慢的自己掂量） */}
          <div>
            <div className="microlabel uppercase mb-1">{t('replay.form.coach')}</div>
            {/* 原生 select 的自然宽度由最长 option 撑，只给 max-w 压不住，窄屏会顶出横向滚动 */}
            <LlmEndpointSelect endpoints={endpoints} value={aiEndpointId} onChange={setAiEndpointId} className="w-full max-w-[300px]" />
          </div>
        </div>
        {/* flex-wrap + shrink-0：右边那段说明是纯中文长文本，min-content 只有一个字宽，
            不拦着就会把按钮一路压窄；shrink-0 保住按钮整宽，同行放不下时整体换行 */}
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={() => void handleStart()}
            disabled={loading || !cov}
            className={cn(
              'h-10 px-5 rounded-lg font-black text-sm flex items-center gap-2 shrink-0 whitespace-nowrap transition-all machined',
              'bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98]',
              'disabled:opacity-50 disabled:cursor-not-allowed',
            )}
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
            {t('replay.start')}
          </button>
          <span className="text-[10px] text-muted-foreground leading-snug">
            {cov
              ? t('replay.coverage', { from: toDateInput(cov.earliestMs), to: toDateInput(cov.latestMs) })
              : t('replay.coverageLoading')}
            <br />{t('replay.hintLine')}
          </span>
        </div>
      </div>
    );
  }

  // ==================== 对局中 / 结算 ====================
  return (
    <div className="space-y-5">
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        {/* 左：图表 + 播放 + 交易操作 */}
        <div className="space-y-5 min-w-0">
          <div className="rounded-lg pt-card p-3 md:p-4 space-y-3">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="microlabel uppercase">
                {session.symbol} · {ivLabel(ivMin)} · {session.blind && !finished ? t('replay.blind') : t('replay.review')}
              </span>
              <div className="flex rounded border border-border overflow-hidden">
                {IV_OPTIONS.map(o => (
                  <button key={o.min} type="button" onClick={() => switchIv(o.min)}
                    className={cn('px-1.5 h-6 text-[10px] font-bold transition-colors num',
                      ivMin === o.min ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                    {o.label}
                  </button>
                ))}
              </div>
              {curBar && (
                <span className="text-[10px] num text-primary font-bold">{fmtReplayTime(curBar[0])}</span>
              )}
              <span className="ml-auto text-[10px] text-muted-foreground num">
                {t('replay.barsProgress', { played, total: playable })}
              </span>
            </div>

            <BacktestChart
              bars={aggBars}
              marks={marks}
              cursor={ctxCount + played}
              symbol={session.symbol}
              decimals={decimals}
              blindBaseMs={session.blind && !finished ? session.startMs : null}
              bucketSec={ivMin * 60}
            />

            {/* 播放控制 */}
            <div className="flex items-center gap-2.5 flex-wrap pt-1 border-t border-border/40">
              <button
                type="button"
                onClick={doNext}
                disabled={!!finished}
                className={cn(
                  'h-10 px-4 rounded-lg font-black text-sm flex items-center gap-1.5 transition-all machined',
                  'bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98]',
                  'disabled:opacity-40 disabled:cursor-not-allowed',
                )}
              >
                {t('replay.next')} <ChevronRight className="w-4 h-4" />
              </button>
              <div className="flex items-center gap-1">
                <Gauge className="w-3.5 h-3.5 text-muted-foreground" />
                {AUTO_SPEEDS.map(sp => (
                  <button key={sp} type="button" disabled={!!finished}
                    onClick={() => setAuto(a => (a === sp ? 0 : sp))}
                    className={cn('px-2 h-7 rounded text-[10px] font-bold transition-colors num disabled:opacity-40',
                      auto === sp ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                    {t('replay.speed', { n: sp })}
                  </button>
                ))}
                {auto > 0 && (
                  <button type="button" onClick={() => setAuto(0)}
                    className="w-7 h-7 rounded flex items-center justify-center text-primary" aria-label={t('player.pause')}>
                    <Pause className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
              <span className="hidden md:inline text-[10px] text-muted-foreground">{t('replay.spaceHint')}</span>
              {!finished && (
                <div className="ml-auto flex items-center gap-2">
                  {/* AI 提示：只送已揭示的 K 线（盲测下只有相对时间），不泄露未来 */}
                  <button type="button" onClick={askHint} disabled={!!hint?.busy}
                    title={t('replay.aiHintTip', { model: aiLabel })}
                    className="h-8 px-3 rounded-lg border border-primary/40 text-primary hover:bg-primary/10 text-[11px] font-bold flex items-center gap-1 disabled:opacity-50 disabled:cursor-wait">
                    {hint?.busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Sparkles className="w-3.5 h-3.5" />} {t('replay.aiHint')}
                  </button>
                  <button type="button"
                    onClick={() => {
                      // 主动结束：有仓先按当前收盘清算
                      if (curBar) setState(s => endSession(s, curPrice, curIdx, curBar[0]));
                      setFinished('END');
                      setAuto(0);
                    }}
                    className="h-8 px-3 rounded-lg border border-border hover:bg-surface-hover text-[11px] font-bold text-muted-foreground hover:text-foreground flex items-center gap-1">
                    <Flag className="w-3.5 h-3.5" /> {t('replay.endGame')}
                  </button>
                </div>
              )}
            </div>

            {/* 交易操作条：大触区，移动端优先。开仓行常驻（有仓时变加仓）；每个已持方向一行平仓 */}
            {!finished && (
              <div className="space-y-2 pt-1 border-t border-border/40">
                <div className="flex items-stretch gap-2 flex-wrap">
                  {/* 杠杆：只作用于接下来的开/加仓；已有仓位的杠杆不变（加仓换档后按保证金加权成有效杠杆） */}
                  <div className="flex items-center gap-1" title={t('replay.levTip')}>
                    <Zap className="w-3.5 h-3.5 text-muted-foreground" />
                    {LEVERAGE_OPTIONS.map(l => (
                      <button key={l} type="button" onClick={() => setLeverage(l)}
                        className={cn('px-1.5 h-9 rounded text-[10px] font-bold transition-colors num',
                          leverage === l ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                        {l}x
                      </button>
                    ))}
                  </div>
                  <div className="flex items-center gap-1" title={t('replay.openPctTip')}>
                    <Wallet className="w-3.5 h-3.5 text-muted-foreground" />
                    {PCT_OPTIONS.map(p => (
                      <button key={p} type="button" onClick={() => setOpenPct(p)}
                        className={cn('px-2 h-9 rounded text-[10px] font-bold transition-colors num',
                          openPct === p ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                        {p}%
                      </button>
                    ))}
                  </div>
                  {/* 开多/开空成对：外层 flex-wrap 会把这两颗拆开塞进各自的空档，
                      套一层 w-full 强制它俩自成一行左右平分；md:contents 让这层≥768px 不生成盒子，
                      两颗按钮仍是外层 flex 的直接子项，宽屏布局不受影响 */}
                  <div className="w-full flex items-stretch gap-2 md:contents">
                    <button type="button" onClick={() => handleOpen('LONG')}
                      className="flex-1 min-w-[110px] h-11 rounded-lg bg-gain text-white font-black text-[13px] md:text-sm flex items-center justify-center gap-1.5 hover:brightness-105 active:scale-[.98] machined">
                      <ArrowUpRight className="w-4 h-4" /> {state.positions.LONG ? t('replay.addLong') : t('replay.openLong')} {leverage}x @ {fmtNum(curPrice, decimals)}
                    </button>
                    <button type="button" onClick={() => handleOpen('SHORT')}
                      className="flex-1 min-w-[110px] h-11 rounded-lg bg-loss text-white font-black text-[13px] md:text-sm flex items-center justify-center gap-1.5 hover:brightness-105 active:scale-[.98] machined">
                      <ArrowDownRight className="w-4 h-4" /> {state.positions.SHORT ? t('replay.addShort') : t('replay.openShort')} {leverage}x @ {fmtNum(curPrice, decimals)}
                    </button>
                  </div>
                </div>
                {held.length > 0 && (
                  <div className="space-y-1.5">
                    <div className="flex items-center gap-1" title={t('replay.closePctTip')}>
                      <X className="w-3.5 h-3.5 text-muted-foreground" />
                      {PCT_OPTIONS.map(p => (
                        <button key={p} type="button" onClick={() => setClosePct(p)}
                          className={cn('px-2 h-8 rounded text-[10px] font-bold transition-colors num',
                            closePct === p ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                          {p}%
                        </button>
                      ))}
                    </div>
                    {held.map(p => {
                      const upnl = unrealized(p, curPrice);
                      const isLong = p.side === 'LONG';
                      return (
                        <div key={p.side} className="flex items-stretch gap-2 flex-wrap">
                          <div className="flex-1 min-w-[200px] rounded-lg border border-border bg-card-2 px-3 py-1.5 text-[11px] flex items-center gap-2.5">
                            <span className={cn('font-black shrink-0', isLong ? 'text-gain' : 'text-loss')}>
                              {t(SIDE_LABEL[p.side])} {fmtLev(p.leverage)}
                            </span>
                            <span className="num text-muted-foreground truncate">
                              {t('replay.avgEntry', { price: fmtNum(p.entryPrice, decimals), qty: fmtNum(p.qty, 4) })}
                            </span>
                            <span className={cn('num font-black ml-auto shrink-0', upnl >= 0 ? 'text-gain' : 'text-loss')}>
                              {upnl >= 0 ? '+' : ''}{fmtNum(upnl)}
                            </span>
                          </div>
                          <button type="button" onClick={() => handleClose(p.side)}
                            className="min-w-[130px] h-10 px-3 rounded-lg bg-primary text-primary-foreground font-black text-sm flex items-center justify-center gap-1.5 hover:brightness-105 active:scale-[.98] machined">
                            {t(closePct >= 100 ? 'replay.closeAction' : 'replay.reduceAction',
                              { side: t(SIDE_LABEL[p.side]), pct: closePct, price: fmtNum(curPrice, decimals) })}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* AI 盘面提示：流式输出，关掉即掐断请求 */}
          {hint && (
            <div className="rounded-lg pt-card p-3 md:p-4 space-y-2">
              <div className="flex items-center gap-1.5">
                <Sparkles className="w-3.5 h-3.5 text-primary" />
                <span className="text-[11px] font-black">{t('replay.aiPanelTitle')}</span>
                {hint.at && <span className="text-[10px] text-muted-foreground num">· {hint.at}</span>}
                <span className="ml-auto text-[10px] text-muted-foreground truncate max-w-[160px]">{aiLabel}</span>
                {/* p-2 -m-2：图标只有 14px，手指点不中——内边距把命中区撑到 30px，负外边距抵掉占位 */}
                <button type="button" aria-label={t('common:close')}
                  onClick={() => { aiAbortRef.current?.abort(); setHint(null); }}
                  className="p-2 -m-2 text-muted-foreground/60 hover:text-foreground">
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>
              <AiBody run={hint} onGoConfig={goConfig} />
            </div>
          )}

          {/* 结算面板 */}
          {finished && st && (
            <div className="rounded-lg pt-card p-4 md:p-5 space-y-4">
              <div className="flex items-center gap-2">
                {finished === 'LIQUIDATION'
                  ? <><Skull className="w-4 h-4 text-loss" /><span className="text-sm font-black text-loss">{t('replay.liquidatedOut')}</span></>
                  : <><Flag className="w-4 h-4 text-primary" /><span className="text-sm font-black">{t('replay.settled')}</span></>}
                <span className="ml-auto text-[10px] text-muted-foreground num">
                  {/* 盲测揭晓真实区间 */}
                  {fmtDateTime(session.startMs)} ~ {curBar ? fmtDateTime(curBar[0]) : ''}
                </span>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className={cn('text-lg font-black num', st.netProfit >= 0 ? 'text-gain' : 'text-loss')}>
                    {st.netProfit >= 0 ? '+' : ''}{fmtNum(st.netProfit)}
                  </div>
                  <div className="microlabel uppercase mt-1">{t('replay.stat.netProfit', { pct: (st.returnPct * 100).toFixed(1) })}</div>
                </div>
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className="text-lg font-black num">
                    {st.totalTrades > 0 ? `${(st.winRate * 100).toFixed(0)}%` : '—'}
                  </div>
                  <div className="microlabel uppercase mt-1">{t('replay.stat.winRate', { wins: st.wins, losses: st.losses })}</div>
                </div>
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className="text-lg font-black num text-loss">{(st.maxDrawdownPct * 100).toFixed(1)}%</div>
                  <div className="microlabel uppercase mt-1">{t('replay.stat.maxDd')}</div>
                </div>
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className="text-lg font-black num">{fmtNum(st.finalEquity, 0)}</div>
                  <div className="microlabel uppercase mt-1">{t('replay.stat.finalEquity', { fees: fmtNum(st.totalFees, 0) })}</div>
                </div>
              </div>
              {eqPointsRef.current.length > 1 && <EquityChart points={eqPointsRef.current} />}

              {/* AI 评估：一点就评——AI 对照整局 K 线（此时日期已揭晓）与全部成交，逐笔评操作与行为模式，不需要用户先写什么 */}
              <div className="space-y-2 pt-3 border-t border-border/40">
                <div className="flex items-center gap-2 flex-wrap">
                  <button type="button" onClick={askReview} disabled={!!review?.busy}
                    className="h-9 px-4 rounded-lg bg-primary text-primary-foreground font-black text-xs flex items-center gap-1.5 hover:brightness-105 active:scale-[.98] machined disabled:opacity-50 disabled:cursor-wait">
                    {review?.busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Sparkles className="w-3.5 h-3.5" />} {t('replay.aiReview')}
                  </button>
                  <span className="text-[10px] text-muted-foreground">
                    {t('replay.aiReviewHint', { model: aiLabel })}
                  </span>
                </div>
                {review && <AiBody run={review} onGoConfig={goConfig} />}
              </div>

              <button
                type="button"
                onClick={() => { aiAbortRef.current?.abort(); setSession(null); }}
                className="h-10 px-5 rounded-lg font-black text-sm flex items-center gap-2 bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98] machined"
              >
                <RotateCcw className="w-4 h-4" /> {t('replay.playAgain')}
              </button>
            </div>
          )}
        </div>

        {/* 右：权益读数 + 本局交易记录 */}
        <div className="space-y-5 min-w-0">
          <div className="rounded-lg pt-card p-3 grid grid-cols-2 gap-2">
            <div className="rounded-md border border-border bg-card-2 px-3 py-2">
              <div className={cn('text-base font-black num', curEquity >= session.balance ? 'text-gain' : 'text-loss')}>
                {fmtNum(curEquity, 0)}
              </div>
              <div className="microlabel uppercase mt-0.5">{t('replay.equity')}</div>
            </div>
            <div className="rounded-md border border-border bg-card-2 px-3 py-2">
              <div className="text-base font-black num">{fmtNum(state.cash, 0)}</div>
              <div className="microlabel uppercase mt-0.5">{t('replay.cash')}</div>
            </div>
          </div>

          <div className="rounded-lg pt-card p-3 flex flex-col min-w-0 lg:max-h-[560px] max-h-[360px]">
            <div className="flex items-center gap-1.5 pb-2 border-b border-border/40 shrink-0">
              <History className="w-3.5 h-3.5 text-primary" />
              <span className="text-[11px] font-black">{t('replay.tradesTitle')}</span>
              <span className="ml-auto text-[10px] text-muted-foreground num">{t('replay.tradesCount', { count: state.trades.length })}</span>
            </div>
            <div className="flex-1 overflow-y-auto py-1 space-y-0.5 overscroll-contain">
              {state.trades.length === 0 ? (
                <div className="py-10 text-center text-[11px] text-muted-foreground">{t('replay.noTrades')}</div>
              ) : (
                /* 循环变量避开 t：与 i18n 的 t 同名会遮蔽 */
                [...state.trades].reverse().map((tr, i) => {
                  const isLong = tr.side === 'LONG';
                  const win = tr.pnl >= 0;
                  return (
                    <div key={state.trades.length - i}
                      className="flex items-center gap-2.5 py-1.5 px-2 rounded-md text-[11px] border-b border-border/40 last:border-0">
                      <span className={cn('w-1 self-stretch rounded-full shrink-0', isLong ? 'bg-gain' : 'bg-loss')} />
                      <div className="min-w-0">
                        <div className="font-bold leading-tight">
                          <span className={cn('text-[10px] font-black', isLong ? 'text-gain' : 'text-loss')}>{isLong ? t('side.long') : t('side.short')}</span>
                          <span className="ml-1 text-[10px] text-muted-foreground">{fmtLev(tr.leverage)}</span>
                          <span className="ml-1.5 text-[9px] font-bold px-1 py-px rounded bg-muted text-muted-foreground">
                            {tr.partial && tr.reason === 'MANUAL' ? t('replay.reason.reduce') : t(REASON_LABEL[tr.reason])}
                          </span>
                        </div>
                        <div className="text-[10px] text-muted-foreground num leading-tight mt-0.5">
                          {fmtNum(tr.entryPrice, decimals)} → {fmtNum(tr.exitPrice, decimals)} · {fmtNum(tr.qty, 4)}
                        </div>
                        <div className="text-[9px] text-muted-foreground/60 num leading-tight">
                          {fmtReplayTime(tr.openTime)} ~ {fmtReplayTime(tr.closeTime)}
                        </div>
                      </div>
                      <span className={cn('ml-auto font-black num shrink-0', win ? 'text-gain' : 'text-loss')}>
                        {win ? '+' : ''}{fmtNum(tr.pnl)}
                      </span>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
