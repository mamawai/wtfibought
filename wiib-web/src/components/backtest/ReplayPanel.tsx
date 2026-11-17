import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type Dispatch, type SetStateAction } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Play, RotateCcw, SkipForward, Sparkles, X } from 'lucide-react';
import { ApiError, backtestApi, llmEndpointApi } from '../../api';
import { BacktestChart, type ChartTradeMark } from './BacktestChart';
import { EquityChart } from '../EquityChart';
import { LlmEndpointSelect } from '../LlmEndpointSelect';
import { Markdown } from '../Markdown';
import { useToast } from '../ui/use-toast';
import { DatePicker } from '../ui/date-picker';
import { Seg } from '../ui/seg';
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
/**
 * 开局前置上下文根数：给玩家一屏"过去"可看，也给画线留参照。
 * 至少一整天：开局就切 1d 时，开局前得有一根整根走完的日线当上下文，不然只剩跨开局那根（含开局后的行情）
 */
const CONTEXT_BARS = 288;
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
/** 评估最多送多少笔成交（后端上限 300），多了只送最近的 */
const REVIEW_TRADES_MAX = 300;
/** 2205（上一次 AI 调用后端还没收摊）后隔多久自动重试一次 */
const COACH_BUSY_RETRY_MS = 2_000;
/** 2205 最多等多久：后端要等旧连接的下一帧字、或最长 20 秒一次的心跳，才发现它已经断了 */
const COACH_BUSY_WAIT_MS = 25_000;
const round = (v: number, dec: number) => Number(v.toFixed(dec));
/** K 线行 → 教练请求里的一根（价格按币种精度、量保留 2 位，省 token） */
const toCoachBar = (r: number[], label: (ms: number) => string, dec: number) =>
  ({ t: label(r[0]), o: round(r[1], dec), h: round(r[2], dec), l: round(r[3], dec), c: round(r[4], dec), v: round(r[5] ?? 0, 2) });

/** 一次 AI 教练调用的展示态 */
interface AiRun {
  /** 第几次调用：被掐的那次收尾时只动自己这张卡 */
  id: number;
  text: string;
  busy: boolean;
  error: string | null;
  /** 2201/2202：没配 LLM 或配置建不出模型，给"去配置"入口 */
  needsConfig: boolean;
  /** 提示发出时的盘面时刻（盲测相对标签），标在卡片上 */
  at?: string;
}

/** AI 输出块（样式见 Backtest.css .aip）：标题行小脸在想时眼睛转，流式正文 + 错误行（配置类错误带去配置入口）；onClose 给了才有关闭钮 */
function AiBox({ run, title, extra, onGoConfig, onClose, raise }: {
  run: AiRun;
  title: string;
  /** 标题后面的小字：模型名、盘面时刻 */
  extra?: string;
  onGoConfig: () => void;
  onClose?: () => void;
  /** 放在浅灰块里时换白底 */
  raise?: boolean;
}) {
  const { t } = useTranslation(['strategy', 'common']);
  return (
    <div className={cn('aip', raise && 'raise')}>
      <b>
        <span className={cn('cface', run.busy && 'think')} aria-hidden />{title}
        {extra && <small>{extra}</small>}
        {onClose && <button type="button" className="xb" aria-label={t('common:close')} onClick={onClose}><X className="ic" /></button>}
      </b>
      {run.busy && !run.text && <p className="mute">{t('replay.aiThinking')}</p>}
      {run.text && <div><Markdown content={run.text} /></div>}
      {run.error && (
        <p className="err">
          {run.error}
          {run.needsConfig && <button type="button" className="linkb" onClick={onGoConfig}>{t('replay.goConfig')}</button>}
        </p>
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
  // 本局结束那根的开盘时刻，结算面板的结束时间用它（结算后切周期会全量揭示，当前根就不是结束那根了）
  const [endedMs, setEndedMs] = useState(0);
  // 玩家已经看到哪个时刻了（各周期揭示过的 bar 收盘时刻取最大）。切大周期进度会往回退，
  // 当前 bar 收盘早于它时还按旧价成交，就是拿看过的行情下单
  const [revealedMs, setRevealedMs] = useState(0);
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
  const aiSeqRef = useRef(0);
  useEffect(() => {
    llmEndpointApi.list().then(setEndpoints).catch(() => setEndpoints([]));
    return () => aiAbortRef.current?.abort();
  }, []);
  const goConfig = useCallback(() => navigate('/ai'), [navigate]);

  /**
   * 当前周期视图：整根在 startMs 之前走完的桶算上下文（开局即揭示），其余为可播放段。
   * 跨 startMs 的那根桶里聚合了开局之后的 5m，只能算第一根可播放的，算进上下文就是提前揭示未来
   */
  const derived = useMemo(() => {
    if (!session) return null;
    const aggBars = aggregateBars(session.raw, ivMin);
    const ivMs = ivMin * 60_000;
    let ctxCount = 0;
    while (ctxCount < aggBars.length && aggBars[ctxCount][0] + ivMs <= session.startMs) ctxCount++;
    return { aggBars, ivMs, ctxCount, playable: aggBars.length - ctxCount };
  }, [session, ivMin]);

  // doNext 被键盘/interval 调，用 ref 拿最新闭包
  const stateRef = useRef(state);
  const playedRef = useRef(played);
  const sessionRef = useRef(session);
  const finishedRef = useRef(finished);
  const derivedRef = useRef(derived);
  // 已揭示时刻：只在开局和 doNext 里写，写 state 的同时同步写它，doNext 连按时也读得到最新值
  const revealedRef = useRef(0);
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
      revealedRef.current = startMs;
      setRevealedMs(startMs);
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
    const newPlayed = playedRef.current + 1;
    // 回退区里的 bar 先前已经走过、盯过市：只往前挪。再拿它去给之后才开的仓盯市，会冒出"开仓之前就爆仓"
    if (bar[0] + d.ivMs <= revealedRef.current) {
      setPlayed(newPlayed);
      return;
    }
    let st = step(stateRef.current, bar[4], i, bar[0]);
    let fin: 'END' | 'LIQUIDATION' | null = null;
    if (st.liquidated) {
      fin = 'LIQUIDATION';
    } else if (newPlayed >= d.playable) {
      st = endSession(st, bar[4], i, bar[0]);
      fin = 'END';
    }
    // 切大周期后这根 bar 盖住了先前小周期记过的几个点：换成它这一个，曲线时间保持单调
    eqPointsRef.current = eqPointsRef.current.filter(p => p.time < bar[0]);
    eqPointsRef.current.push({ time: bar[0], cumPnl: equity(st, bar[4]) - ses.balance });
    setState(st);
    setPlayed(newPlayed);
    revealedRef.current = Math.max(revealedRef.current, bar[0] + d.ivMs);
    setRevealedMs(revealedRef.current);
    if (fin) {
      setFinished(fin);
      setEndedMs(bar[0]);
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
    while (ctx < newAgg.length && newAgg[ctx][0] + newIvMs <= ses.startMs) ctx++;
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
  /** 进度退在看过的行情里（当前 bar 收盘早于已揭示时刻）：开平仓、结束本局都按旧价成交，一律暂停 */
  const rewound = !finished && curBar != null && curBar[0] + ivMin * 60_000 < revealedMs;

  /** 盲测未结算时显示 D{n} HH:mm 相对时间（天按 UTC+8 日历日数，开局那天 D1、过午夜进位），其余显示真实时间 */
  const fmtReplayTime = useCallback((ms: number) => {
    if (session?.blind && !finished) {
      const dayOf = (v: number) => Math.floor((v + 8 * 3_600_000) / 86_400_000);
      const day = dayOf(ms) - dayOf(session.startMs) + 1;
      const d = new Date(ms + 8 * 3_600_000);
      return `D${day} ${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
    }
    return fmtDateTime(ms);
  }, [session, finished]);

  // ---- 交易操作（只能按当前收盘价）：同向再开=加仓；平仓按比例，不足 100% 就是减仓 ----
  const handleOpen = (side: Side) => {
    if (!session || !curBar || finished || rewound) return;
    setState(s => open(s, side, openPct / 100, leverage, curPrice, curIdx, curBar[0]));
  };
  const handleClose = (side: Side) => {
    if (!session || !curBar || finished || rewound) return;
    setState(s => close(s, side, closePct / 100, curPrice, curIdx, curBar[0], 'MANUAL'));
  };
  /** 主动结束：有仓先按当前收盘清算，结算后的权益补进权益点（回撤和曲线末点都算上平仓费） */
  const handleEnd = () => {
    if (!session || !curBar || finished || rewound) return;
    const settled = endSession(state, curPrice, curIdx, curBar[0]);
    const pts = eqPointsRef.current;
    const lastT = pts.length ? pts[pts.length - 1].time : curBar[0];
    // 切过大周期时末点可能是桶里最后那根 5m，比当前 bar 的 openTime 晚；取大的，曲线不往回画
    pts.push({ time: Math.max(curBar[0], lastT), cumPnl: equity(settled, curPrice) - session.balance });
    setState(settled);
    setFinished('END');
    setEndedMs(curBar[0]);
    setAuto(0);
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
  const runCoach = useCallback(async (req: ReplayCoachRequest, set: Dispatch<SetStateAction<AiRun | null>>, at?: string) => {
    aiAbortRef.current?.abort();
    const ctrl = new AbortController();
    aiAbortRef.current = ctrl;
    const id = ++aiSeqRef.current;
    let text = '', err: string | null = null;
    set({ id, text: '', busy: true, error: null, needsConfig: false, at });
    const call = () => backtestApi.replayCoach(req, e => {
      if (e.type === 'token') { text += e.text; set({ id, text, busy: true, error: null, needsConfig: false, at }); }
      else if (e.type === 'done') text = e.answer;
      else if (e.type === 'error') err = e.message;
    }, ctrl.signal);
    try {
      // 2205：刚掐掉的上一次后端还占着，隔一会儿再试，直到放行或等满 COACH_BUSY_WAIT_MS
      for (let waited = 0; ; waited += COACH_BUSY_RETRY_MS) {
        try {
          await call();
          break;
        } catch (e) {
          if (!(e instanceof ApiError && e.code === 2205) || ctrl.signal.aborted || waited >= COACH_BUSY_WAIT_MS) throw e;
          await new Promise(r => setTimeout(r, COACH_BUSY_RETRY_MS));
          if (ctrl.signal.aborted) throw e;
        }
      }
      set({ id, text, busy: false, error: err ?? (text ? null : t('replay.emptyAnswer')), needsConfig: false, at });
    } catch (e) {
      // 被掐的（关卡片/重开一局/发了另一次）不算错：卡片还是这次的就停转，有字留着、没字收掉
      if (ctrl.signal.aborted) {
        set(r => (r?.id === id ? (r.text ? { ...r, busy: false } : null) : r));
        return;
      }
      const code = e instanceof ApiError ? e.code : -1;
      set({ id, text, busy: false, error: (e as Error).message || t('errors:requestFailed'), needsConfig: code === 2201 || code === 2202, at });
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

  /** 结算后评估：整局 K 线（聚合到 ≤REVIEW_BARS_MAX 根的最细周期）+ 成交（最近 ≤REVIEW_TRADES_MAX 笔）+ 统计，AI 对着走势评操作行为；日期已揭晓用真实时间 */
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
      // 成交只送最近 REVIEW_TRADES_MAX 笔，下面的统计仍是整局的
      trades: state.trades.slice(-REVIEW_TRADES_MAX).map(tr => ({
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
      <div className="blk cfg">
        <div className="fld">
          <div className="fl">{t('replay.form.symbol')}</div>
          <Seg value={symbol} onChange={setSymbol} label={t('replay.form.symbol')}
               options={['BTCUSDT', 'ETHUSDT'].map(sym => ({ value: sym, label: sym.replace('USDT', '') }))} />
        </div>
        <div className="fld">
          <div className="fl">{t('replay.form.mode')}</div>
          <Seg value={blind ? 'blind' : 'custom'} onChange={v => setBlind(v === 'blind')} label={t('replay.form.mode')} options={[
            { value: 'blind', label: t('replay.form.blind') },
            { value: 'custom', label: t('replay.form.custom') },
          ]} />
        </div>
        {!blind && (
          <div className="fld">
            <div className="fl">{t('replay.form.startDate')}</div>
            <DatePicker value={customDate} onChange={setCustomDate} />
          </div>
        )}
        <div className="fld">
          <div className="fl">{t('replay.form.duration')}</div>
          <Seg value={String(days)} onChange={v => setDays(Number(v))} label={t('replay.form.duration')}
               options={DURATIONS.map(d => ({ value: String(d), label: t('replay.days', { count: d }) }))} />
        </div>
        <div className="fld bal">
          <label className="fl" htmlFor="rp-bal">{t('replay.form.balance')}</label>
          <label className="inp">
            <input id="rp-bal" inputMode="decimal" value={balance} onChange={e => setBalance(e.target.value)} />
            <span className="u">U</span>
          </label>
        </div>
        {/* AI 教练：从 AI 页「模型配置」的端点库里选一条（提示可能每几根点一次，评估一局一次；挑贵的慢的自己掂量） */}
        <div className="fld w">
          <div className="fl">{t('replay.form.coach')}</div>
          <LlmEndpointSelect endpoints={endpoints} value={aiEndpointId} onChange={setAiEndpointId} />
        </div>
        <button type="button" className={cn('btn ink lg go3', loading && 'busy')} onClick={() => void handleStart()} disabled={loading || !cov}>
          <Play className="ic" /><span>{t('replay.start')}</span>
        </button>
        <p className="desc">
          {cov
            ? t('replay.coverage', { from: toDateInput(cov.earliestMs), to: toDateInput(cov.latestMs) })
            : t('replay.coverageLoading')}
          <br />{t('replay.hintLine')}
        </p>
      </div>
    );
  }

  // ==================== 对局中 / 结算 ====================
  const blindNow = session.blind && !finished;
  return (
    <>
      {/* 结算：成绩 + 权益曲线 + AI 评估 + 再来一局 */}
      {finished && st && (
        <div className="blk done">
          <h3 className={cn(finished === 'LIQUIDATION' && 'dn')}>{finished === 'LIQUIDATION' ? t('replay.liquidatedOut') : t('replay.settled')}</h3>
          {/* 盲测揭晓真实区间 */}
          <p className="num">{session.symbol.replace('USDT', '')} · {fmtDateTime(session.startMs)} ~ {fmtDateTime(endedMs)}</p>
          <div className="st8">
            <div>
              <span>{t('replay.stat.netProfit', { pct: (st.returnPct * 100).toFixed(1) })}</span>
              <b className={cn('num', st.netProfit >= 0 ? 'up' : 'dn')}>{st.netProfit >= 0 ? '+' : ''}{fmtNum(st.netProfit)}</b>
            </div>
            <div>
              <span>{t('replay.stat.winRate', { wins: st.wins, losses: st.losses })}</span>
              <b className="num">{st.totalTrades > 0 ? `${(st.winRate * 100).toFixed(0)}%` : '—'}</b>
            </div>
            <div><span>{t('replay.stat.maxDd')}</span><b className="num dn">-{(st.maxDrawdownPct * 100).toFixed(1)}%</b></div>
            <div><span>{t('replay.stat.finalEquity', { fees: fmtNum(st.totalFees, 0) })}</span><b className="num">{fmtNum(st.finalEquity, 0)}</b></div>
          </div>
          {eqPointsRef.current.length > 1 && <EquityChart points={eqPointsRef.current} />}
          {/* AI 评估：一点就评——AI 对照整局 K 线（此时日期已揭晓）与全部成交，逐笔评操作与行为模式，不需要用户先写什么 */}
          <div className="f">
            <button type="button" className={cn('btn', review?.busy && 'busy')} onClick={askReview} disabled={!!review?.busy}
                    title={t('replay.aiReviewHint', { model: aiLabel })}>
              <Sparkles className="ic" /><span>{t('replay.aiReview')}</span>
            </button>
            <button type="button" className="btn ink" onClick={() => { aiAbortRef.current?.abort(); setSession(null); }}>
              <RotateCcw className="ic" />{t('replay.playAgain')}
            </button>
          </div>
          {review && <AiBox run={review} title={t('replay.aiReview')} extra={aiLabel} onGoConfig={goConfig} raise />}
        </div>
      )}

      <div className="main">
        {/* 左：图表 + 播放 + AI 提示 */}
        <div>
          <div className="cvh">
            <span className={cn('tag sm', blindNow && 'ink')}>{blindNow ? t('replay.blind') : t('replay.review')}</span>
            <span className="num">{session.symbol.replace('USDT', '')} · {ivLabel(ivMin)}</span>
            <Seg value={String(ivMin)} onChange={v => switchIv(Number(v))} label={t('player.interval')}
                 options={IV_OPTIONS.map(o => ({ value: String(o.min), label: o.label }))} />
            {curBar && <span className="num">{fmtReplayTime(curBar[0])}</span>}
            <span className="sp" />
            <span className="num">{t('replay.barsProgress', { played, total: playable })}</span>
          </div>

          <BacktestChart
              bars={aggBars}
              marks={marks}
              cursor={ctxCount + played}
              symbol={session.symbol}
              decimals={decimals}
            blindBaseMs={blindNow ? session.startMs : null}
            bucketSec={ivMin * 60}
          />

          {/* 播放控制：下一根、自动档、AI 提示、结束本局 */}
          {!finished && (
            <div className="player">
              <button type="button" className="btn ink" onClick={doNext}><SkipForward className="ic" />{t('replay.next')}</button>
              <Seg value={String(auto)} onChange={v => setAuto(Number(v))} label={t('player.mode')} options={[
                { value: '0', label: t('replay.manual') },
                ...AUTO_SPEEDS.map(sp => ({ value: String(sp), label: t('replay.speed', { n: sp }) })),
              ]} />
              <span className="mute sm">{t('replay.spaceHint')}</span>
              <span className="sp" />
              {/* AI 提示：只送已揭示的 K 线（盲测下只有相对时间），不泄露未来 */}
              <button type="button" className={cn('btn', hint?.busy && 'busy')} onClick={askHint} disabled={!!hint?.busy}
                      title={t('replay.aiHintTip', { model: aiLabel })}>
                <Sparkles className="ic" /><span>{t('replay.aiHint')}</span>
              </button>
              <button type="button" className="btn plain" disabled={rewound} onClick={handleEnd}>{t('replay.endGame')}</button>
            </div>
          )}

          {/* AI 盘面提示：流式输出，关掉即掐断请求 */}
          {hint && (
            <AiBox run={hint} title={t('replay.aiPanelTitle')} extra={[hint.at, aiLabel].filter(Boolean).join(' · ')} onGoConfig={goConfig}
                   onClose={() => { if (hint.busy) aiAbortRef.current?.abort(); setHint(null); }} />
          )}

        </div>

        {/* 右：权益读数 + 下单台 + 本局交易记录 */}
        <aside className="ctl">
          <div className="acct num">
            <div><span>{t('replay.equity')}</span><b className={curEquity >= session.balance ? 'up' : 'dn'}>{fmtNum(curEquity, 0)}</b></div>
            <div><span>{t('replay.cash')}</span><b>{fmtNum(state.cash, 0)}</b></div>
          </div>

          {/* 下单台：开仓两颗常驻（有仓时变加仓）；每个已持方向一块，块里是平 / 减仓 */}
          {!finished && (
            <div className="blk ctl">
              {rewound && <p className="warn">{t('replay.rewound')}</p>}
              {/* 杠杆：只作用于接下来的开/加仓；已有仓位的杠杆不变（加仓换档后按保证金加权成有效杠杆） */}
              <div className="fld" title={t('replay.levTip')}>
                <div className="fl"><span>{t('replay.levLabel')}</span><span>{t('replay.levHint')}</span></div>
                <Seg className="full" value={String(leverage)} onChange={v => setLeverage(Number(v))} label={t('replay.levLabel')}
                     options={LEVERAGE_OPTIONS.map(l => ({ value: String(l), label: `${l}x` }))} />
              </div>
              <div className="fld" title={t('replay.openPctTip')}>
                <div className="fl"><span>{t('replay.openLabel')}</span><span>{t('replay.openHint')}</span></div>
                <Seg className="full" value={String(openPct)} onChange={v => setOpenPct(Number(v))} label={t('replay.openLabel')}
                     options={PCT_OPTIONS.map(p => ({ value: String(p), label: `${p}%` }))} />
              </div>
              <div className="two">
                <button type="button" className="cta" onClick={() => handleOpen('LONG')} disabled={rewound}>
                  {state.positions.LONG ? t('replay.addLong') : t('replay.openLong')} {leverage}x
                </button>
                <button type="button" className="cta short" onClick={() => handleOpen('SHORT')} disabled={rewound}>
                  {state.positions.SHORT ? t('replay.addShort') : t('replay.openShort')} {leverage}x
                </button>
              </div>
              {held.length > 0 && (
                <div className="fld" title={t('replay.closePctTip')}>
                  <div className="fl"><span>{t('replay.closeLabel')}</span><span>{t('replay.closeHint')}</span></div>
                  <Seg className="full" value={String(closePct)} onChange={v => setClosePct(Number(v))} label={t('replay.closeLabel')}
                       options={PCT_OPTIONS.map(p => ({ value: String(p), label: `${p}%` }))} />
                </div>
              )}
              {held.map(p => {
                const upnl = unrealized(p, curPrice);
                return (
                  <div key={p.side} className="rp">
                    <div className="h">
                      <span className={cn('tag sm', p.side === 'LONG' ? 'g' : 'r')}>{t(SIDE_LABEL[p.side])} {fmtLev(p.leverage)}</span>
                      <span className={cn('pn num', upnl >= 0 ? 'up' : 'dn')}>{upnl >= 0 ? '+' : ''}{fmtNum(upnl)}</span>
                    </div>
                    <small className="num">{t('replay.avgEntry', { price: fmtNum(p.entryPrice, decimals), qty: fmtNum(p.qty, 4) })}</small>
                    <div className="f">
                      <button type="button" className="btn sm" onClick={() => handleClose(p.side)} disabled={rewound}>
                        {t(closePct >= 100 ? 'replay.closeAction' : 'replay.reduceAction',
                          { side: t(SIDE_LABEL[p.side]), pct: closePct, price: fmtNum(curPrice, decimals) })}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          <div>
            <div className="sec-h"><h2>{t('replay.tradesTitle')}<small>{t('replay.tradesCount', { count: state.trades.length })}</small></h2></div>
            <div className="tl">
              {state.trades.length === 0 ? (
                <p className="mute">{t('replay.noTrades')}</p>
              ) : (
                /* 循环变量避开 t：与 i18n 的 t 同名会遮蔽 */
                [...state.trades].reverse().map((tr, i) => (
                  <div key={state.trades.length - i} className="te"
                       style={{ '--c': tr.side === 'LONG' ? 'var(--color-gain)' : 'var(--color-loss)' } as CSSProperties}>
                    <b>
                      {t(SIDE_LABEL[tr.side])} {fmtLev(tr.leverage)} · {tr.partial && tr.reason === 'MANUAL' ? t('replay.reason.reduce') : t(REASON_LABEL[tr.reason])}
                    </b>{' '}
                    <span className="num">
                      {fmtNum(tr.entryPrice, decimals)} → {fmtNum(tr.exitPrice, decimals)} · {fmtNum(tr.qty, 4)} · <span className={tr.pnl >= 0 ? 'up' : 'dn'}>{tr.pnl >= 0 ? '+' : ''}{fmtNum(tr.pnl)}</span>
                    </span>
                    <time className="num">{fmtReplayTime(tr.openTime)} ~ {fmtReplayTime(tr.closeTime)}</time>
                  </div>
                ))
              )}
            </div>
          </div>
        </aside>
      </div>
    </>
  );
}
