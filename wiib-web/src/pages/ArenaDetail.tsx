import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, Loader2, RefreshCw, X } from 'lucide-react';
import { traderApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { DatePicker } from '../components/ui/date-picker';
import { Markdown } from '../components/Markdown';
import { DecisionCard } from '../components/arena/DecisionCard';
import { EquityCurve, type CurveEvent } from '../components/arena/EquityCurve';
import { HeartbeatStrip } from '../components/arena/HeartbeatStrip';
import { Holdings, WatchList } from '../components/arena/HoldingsPanel';
import { LiveRunCard } from '../components/arena/LiveRunCard';
import { ScoreStrip } from '../components/arena/ScoreStrip';
import { STATUS_META } from '../components/arena/traderStatus';
import { TradeCard } from '../components/arena/TradeCard';
import { doneTrades } from '../components/arena/traderTools';
import { useCountUp } from '../hooks/useCountUp';
import { useStagger } from '../hooks/useStagger';
import { COIN_MAP } from '../lib/coinConfig';
import { cn, dayBounds, fmtDate, fmtDateTime, fmtNum, fmtSignedPct, fmtTokens, parseServerTime, DAY_MS } from '../lib/utils';
import type { AiTraderDecisionView, TradeDecisionRef, TradeRecordView, TraderDetailView, TraderEquityPoint } from '../types';

const REFRESH_MS = 60_000;
const PAGE = 50;
/** 净值曲线可选区间（天）；0=整局 */
const RANGES = [3, 7, 14, 30, 0] as const;
type Range = typeof RANGES[number];
type Tab = 'timeline' | 'trades';
/** 时间线筛选：全部 / 动过仓位的 / 被叫醒的（例行心跳之外） / 复盘 */
const FILTERS = ['all', 'acted', 'woken', 'review'] as const;
type Filter = typeof FILTERS[number];
const WOKEN_KINDS = new Set(['WATCH', 'EVENT', 'ALERT', 'MANUAL']);

/** 块头：和分节头同一套排版，只是间距紧一档 */
const BLK_H = 'sec-h mb-4';

const coinName = (symbol: string) => COIN_MAP[symbol]?.name ?? symbol.replace('USDT', '');

/**
 * 最大回撤%：净值从峰值回落的最大幅度，口径同后端 ReviewMaterialAssembler——
 * 峰值只涨不跌，每个点跟当前峰值比，取最深的那次。整局算，不跟区间按钮走：
 * 跟着区间变会被读成"近3天回撤"，那是另一回事。
 */
function maxDrawdownPct(points: TraderEquityPoint[]): number | null {
  if (points.length < 2) return null;
  let peak = points[0].equity;
  let maxDd = 0;
  for (const p of points) {
    if (p.equity > peak) peak = p.equity;
    else if (peak > 0) maxDd = Math.max(maxDd, (peak - p.equity) / peak * 100);
  }
  return maxDd;
}

/** 定时刷新拉回的第一页并进已有时间线：只补新落库的（决策行只插不改），"加载更多"翻出来的旧页不动 */
function mergeNewer(prev: AiTraderDecisionView[], page: AiTraderDecisionView[]): AiTraderDecisionView[] {
  const known = new Set(prev.map(d => d.id));
  const fresh = page.filter(d => !known.has(d.id));
  return fresh.length ? [...fresh, ...prev].sort((a, b) => b.wakeTime - a.wakeTime) : prev;
}

/**
 * 笔记一行（经验账本）：标题 + 首句预览 + 最近时间，点开才铺 markdown。
 * 笔记是参考资料，不跟实时数据抢版面。
 */
function NotesCard({ title, time, content, empty }: {
  title: string; time: number | null | undefined; content: string | null | undefined; empty: string;
}) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  const text = content?.trim() || '';
  // 预览是纯文本，去掉 markdown 符号免得一行井号
  const preview = text.replace(/[#*`>_-]/g, '').replace(/\s+/g, ' ').slice(0, 120);
  return (
    <>
      <button type="button" onClick={() => text && setOpen(o => !o)} disabled={!text}
              className="w-full text-left py-3.5 border-b border-border flex items-center gap-3 text-[14px] disabled:cursor-default">
        <b className="font-extrabold whitespace-nowrap">{title}</b>
        <span className="mute flex-1 min-w-0 truncate">{text ? preview : empty}</span>
        {time != null && text && (
          <em className="not-italic text-[12px] mute whitespace-nowrap">{t('detail.lastAt', { time: fmtDateTime(time) })}</em>
        )}
      </button>
      {open && <div className="text-[14px] leading-[1.7] py-3"><Markdown content={text} /></div>}
    </>
  );
}

/**
 * trader 工作台：页头（背后是币代号水印）→ 心跳条 → 记分条 → 左主栏（净值 + 实时思考 + 时间线）‖ 右栏（仓位 + 盯盘 + 笔记）。
 * 时间线是这页的正餐，整页滚不封顶；窄屏两栏堆成一列，卡序另排（见布局处注释）。
 */
export function ArenaDetail() {
  const { t } = useTranslation(['ai', 'common']);
  const { toast } = useToast();
  const { id } = useParams();
  const traderId = Number(id);
  const [detail, setDetail] = useState<TraderDetailView | null>(null);
  const [curve, setCurve] = useState<TraderEquityPoint[]>([]);
  const [decisions, setDecisions] = useState<AiTraderDecisionView[]>([]);
  // 时间线当前筛选范围内的 token 合计；null=没数据，或整段上游都没回 usage
  const [tokens, setTokens] = useState<number | null>(null);
  const [trades, setTrades] = useState<TradeRecordView[]>([]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  // null=跟随当前局（会随 detail 刷新自动跟上）；数字=用户选了某一历史局
  const [round, setRound] = useState<number | null>(null);
  const [range, setRange] = useState<Range>(3);
  // 时间线按天：null=不限日期；yyyy-MM-dd 是新加坡时区那一天
  const [day, setDay] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('timeline');
  const [filter, setFilter] = useState<Filter>('all');
  // 从已了结交易跳过来要找的那一条决策：描边 + 滚到它
  const [focusId, setFocusId] = useState<number | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  // 时间线/已了结共用一个逐项登场：两个列表同一时刻只有一个挂着
  const staggerRef = useStagger<HTMLDivElement>();
  // 实时思考卡报上来的"刚跑完一轮"时刻：新决策行、净值点、持仓都在那一刻落库，据此立刻重拉
  const [endedAt, setEndedAt] = useState(0);
  // 它正醒着想事（只有主人看得到）：心跳条亮橙线
  const [liveRunning, setLiveRunning] = useState(false);

  // load 最近一次发请求时的 trader 和局，也就是当前筛选
  const loadAtRef = useRef<{ traderId: number; round: number | null } | null>(null);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    const prev = loadAtRef.current;
    loadAtRef.current = { traderId, round };
    // 回来时用户已经切走（换了 trader / 局）就丢掉；详情和已了结只跟 trader 走，曲线还跟局走
    const sameTrader = () => loadAtRef.current?.traderId === traderId;
    const sameCurve = () => sameTrader() && loadAtRef.current?.round === round;
    // 失败时：刚换过 trader / 局才清掉旧的，同一筛选的定时刷新失败留着旧数据
    const traderSwitched = prev?.traderId !== traderId;
    const curveSwitched = traderSwitched || prev?.round !== round;
    void traderApi.detail(traderId)
      .then(v => { if (sameTrader()) setDetail(v); })
      .catch(() => { if (sameTrader() && traderSwitched) setDetail(null); });
    void traderApi.equityCurve(traderId, round ?? undefined)
      .then(v => { if (sameCurve()) setCurve(v); })
      .catch(() => { if (sameCurve() && curveSwitched) setCurve([]); });
    void traderApi.trades(traderId)
      .then(v => { if (sameTrader()) setTrades(v); })
      .catch(() => { if (sameTrader() && traderSwitched) setTrades([]); });
  }, [traderId, round]);

  // 忽略开关（仅主人可见）：成功后本地改写该行，不整页重拉；失败要出声——静默吞掉用户会以为已忽略
  const toggleStale = useCallback((r: TradeRecordView) => {
    if (!r.plan) return;
    const next = r.plan.stale !== true;
    void traderApi.setPlanStale(r.plan.id, next).then(() =>
      setTrades(prev => prev.map(x => x.positionId === r.positionId && x.plan
        ? { ...x, plan: { ...x.plan, stale: next } } : x))
    ).catch((e: Error) => toast(e.message || t('toast.actionFailed'), 'error'));
  }, [toast, t]);

  // 定时刷新之外，一轮唤醒刚结束（endedAt 变）也立刻重拉：新决策行、净值点、持仓都在那一刻落库
  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load, endedAt]);

  // 时间线的请求键：局 / 天 / 手动刷新任一变了就整页重拉。请求发出时记下键，回来跟当前的对不上
  //（用户已经切走了，连点前一天、加载更多后马上切局都会这样）就整个丢掉。
  // 跟随当前局时拼的是当前局号：主人在别处重置开了新局，也算换了局
  const [refreshNonce, setRefreshNonce] = useState(0);
  const roundPart = round ?? `cur${detail?.trader.roundNo ?? ''}`;
  const timelineKey = `${traderId}|${roundPart}|${day ?? ''}|${refreshNonce}`;
  const timelineKeyRef = useRef<string | null>(null);
  // 跳转要找的那条决策：整页重拉时一路往下翻到它为止（当天决策可能不止一页）
  const jumpTargetRef = useRef<number | null>(null);

  // 时间线单独拉：按天翻看只动它，持仓/曲线/已了结不跟着重拉
  useEffect(() => {
    if (!Number.isFinite(traderId)) return;
    const key = timelineKey;
    // 键没变＝唤醒刚结束触发的重跑，跟定时刷新一样只补新的
    const sameKey = timelineKeyRef.current === key;
    timelineKeyRef.current = key;
    const target = sameKey ? null : jumpTargetRef.current;
    if (!sameKey) jumpTargetRef.current = null;
    const bounds = day ? dayBounds(day) : null;
    const fetchPage = (before?: number) =>
      traderApi.decisions(traderId, PAGE, before, round ?? undefined, bounds?.from, bounds?.to);

    const reload = async () => {
      let page = await fetchPage();
      let list = page;
      // 跳转目标不在第一页：接着往下翻，直到翻到、翻完或用户已经切走
      while (target != null && page.length >= PAGE && !list.some(d => d.id === target)
        && timelineKeyRef.current === key) {
        page = await fetchPage(list[list.length - 1].wakeTime);
        list = [...list, ...page];
      }
      if (timelineKeyRef.current !== key) return;
      setDecisions(list);
      setHasMore(page.length >= PAGE);
    };
    // 定时刷新：只把新落库的并到顶上，用户"加载更多"翻出来的旧页留着
    const refresh = async () => {
      const page = await fetchPage();
      if (timelineKeyRef.current === key) setDecisions(prev => mergeNewer(prev, page));
    };
    // token 合计跟时间线同一套筛选，但列表是分页的、求和不能靠前端，另发一个并行请求让库去 SUM。
    // fresh=换了筛选后的头一拉，失败要清掉（不然挂着上一个筛选的数）；同一筛选的定时刷新失败就留着旧值
    const loadTokens = (fresh: boolean) => {
      traderApi.tokenUsage(traderId, round ?? undefined, bounds?.from, bounds?.to)
        .then(v => { if (timelineKeyRef.current === key) setTokens(v); })
        .catch(() => { if (fresh && timelineKeyRef.current === key) setTokens(null); });
    };

    (sameKey ? refresh() : reload()).catch(() => {
      if (!sameKey && timelineKeyRef.current === key) setDecisions([]);
    });
    loadTokens(!sameKey);
    const timer = setInterval(() => {
      refresh().catch(() => {});
      loadTokens(false);
    }, REFRESH_MS);
    return () => clearInterval(timer);
  }, [traderId, round, day, timelineKey, endedAt]);

  const loadMore = useCallback(() => {
    const oldest = decisions[decisions.length - 1];
    if (!oldest) return;
    const key = timelineKey;
    const bounds = day ? dayBounds(day) : null;
    setLoadingMore(true);
    traderApi.decisions(traderId, PAGE, oldest.wakeTime, round ?? undefined, bounds?.from, bounds?.to)
      .then(list => {
        if (timelineKeyRef.current !== key) return;
        setDecisions(prev => [...prev, ...list]);
        setHasMore(list.length >= PAGE);
      })
      .finally(() => setLoadingMore(false));
  }, [traderId, decisions, round, day, timelineKey]);

  // 局与局的日期不重叠，切局时日期筛选一并清掉
  const pickRound = (r: number | null) => { setRound(r); setDay(null); setFocusId(null); };
  const changeDay = (d: string | null) => { setDay(d); setFocusId(null); };
  const today = fmtDate();
  // 没选日期时从今天起步
  const shiftDay = (delta: number) => changeDay(fmtDate(dayBounds(day ?? today).from + delta * DAY_MS));

  // 已了结交易 → 它的开/平仓那一轮：切回时间线、筛到那一天、当前局（已了结只有当前局的）。
  // 总是整页重拉（同一天也拉）：目标可能在还没翻到的页里，重拉时一路翻到它
  const jumpToDecision = (d: TradeDecisionRef) => {
    jumpTargetRef.current = d.id;
    setTab('timeline');
    setFilter('all');
    setRound(null);
    setDay(fmtDate(d.wakeTime));
    setFocusId(d.id);
    setRefreshNonce(n => n + 1);
    listRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  // 那一天的列表到位后再滚到目标那条
  useEffect(() => {
    if (focusId == null) return;
    document.getElementById(`decision-${focusId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [focusId, decisions]);

  // 区间锚在曲线最后一个点而不是"现在"：看历史局时 3 天＝那局的最后 3 天
  const windowed = useMemo(() => {
    if (range === 0 || curve.length === 0) return curve;
    const from = curve[curve.length - 1].wakeTime - range * DAY_MS;
    return curve.filter(p => p.wakeTime >= from);
  }, [curve, range]);
  // 区间内点不够画线（末点之前是长停工）就退到整局，按钮高亮跟着退
  const effectiveRange: Range = windowed.length > 1 ? range : 0;
  const visible = effectiveRange === 0 ? curve : windowed;
  // 区间内变化：窗口首尾权益之差，百分比按窗口起点权益
  const windowDelta = visible.length > 1 ? visible[visible.length - 1].equity - visible[0].equity : null;
  const windowPct = windowDelta != null && visible[0].equity ? windowDelta / visible[0].equity * 100 : null;

  // 净值图悬停时写出这个点上开平了哪笔单子
  const curveEvents = useMemo<CurveEvent[]>(() => {
    const side = (s: string) => t(s === 'LONG' ? 'term.long' : 'term.short');
    const events: CurveEvent[] = [];
    for (const r of trades) {
      events.push({ at: r.openedAt, text: t('wb.ev.open', { coin: coinName(r.symbol), side: side(r.side) }) });
      if (r.closedPnl != null) {
        events.push({
          at: r.closedAt,
          text: t('wb.ev.close', {
            coin: coinName(r.symbol), how: t(`trade.closeManner.${r.closeMannerKey}`),
            pnl: `${r.closedPnl >= 0 ? '+' : '-'}${fmtNum(Math.abs(r.closedPnl))}`,
          }),
        });
      }
    }
    for (const p of detail?.positions ?? []) {
      events.push({ at: parseServerTime(p.createdAt).getTime(), text: t('wb.ev.open', { coin: coinName(p.symbol), side: side(p.side) }) });
    }
    return events;
  }, [trades, detail, t]);

  // 时间线筛选：每条只解析一次动作
  const actedIds = useMemo(() => new Set(decisions.filter(d => doneTrades(d).length > 0).map(d => d.id)), [decisions]);
  const passes = (d: AiTraderDecisionView, f: Filter) =>
    f === 'all' || (f === 'review' ? d.kind === 'REVIEW' : f === 'woken' ? WOKEN_KINDS.has(d.kind) : actedIds.has(d.id));
  const shown = decisions.filter(d => passes(d, filter));

  // tr=这只 trader（不叫 t，那是词表查询函数）
  const tr = detail?.trader;
  const st = tr ? (STATUS_META[tr.status] ?? STATUS_META.PAUSED) : null;
  // 大数滚动：hook 不能挂在 tr 判空之后，值先兜 0
  const pctRef = useCountUp<HTMLElement>(tr?.pnlPct ?? 0, fmtSignedPct);
  const eqRef = useCountUp<HTMLElement>(tr?.equity ?? 0, v => fmtNum(v));
  // round=null 表示跟随当前局，落到显示时统一成具体数字
  const viewingRound = round ?? tr?.roundNo ?? 1;
  const viewingHistory = tr != null && viewingRound !== tr.roundNo;
  // 只列保留窗口内的局——后端只留最近 10 局（TraderService.MAX_ROUNDS_KEPT），更早的已整局清除
  const rounds = tr
    ? Array.from({ length: Math.min(tr.roundNo, 10) }, (_, i) => Math.max(1, tr.roundNo - 9) + i)
    : [];
  const roundLabel = viewingHistory ? `R${viewingRound}` : t('detail.thisRound');

  // 记分条：第几天＝曲线首点到末点（末点就是最近一次唤醒）；笔数/胜率从已了结交易现算，只有当前局的
  const dayNo = curve.length > 0
    ? Math.floor((curve[curve.length - 1].wakeTime - curve[0].wakeTime) / DAY_MS) + 1
    : null;
  const closed = trades.filter(r => r.closedPnl != null);
  const winRate = closed.length > 0 ? Math.round(closed.filter(r => (r.closedPnl as number) > 0).length / closed.length * 100) : null;
  const maxDd = maxDrawdownPct(curve);
  // token 那格的微标签跟着时间线筛选走：翻到某天就是那天，没翻就是当前看的这一局
  const tokenScope = day ? day.slice(5) : roundLabel;
  // 心跳条第四格：在盯几条，其中看价格的几条
  const watchN = detail?.watches.length ?? 0;
  const priceWatchN = detail?.watches.filter(w => w.kind !== 'AT_TIME').length ?? 0;

  const tabBtn = (on: boolean) => cn('pb-2.5 -mb-px inline-flex items-center gap-2 text-[15px] border-b-2 cursor-pointer',
    on ? 'text-foreground font-extrabold border-foreground' : 'mute font-semibold border-transparent');

  return (
    <div className="wrap">
      {/* 页头：左名字 + 一行说明 + 局次，右收益率 + 权益；背后是它交易的币代号水印 */}
      <section className="relative overflow-hidden pt-8 pb-10">
        {tr && (
          <div className="tr-hwm" aria-hidden="true">
            {tr.symbols.split(',').map(s => (
              <b key={s} style={{ '--c': COIN_MAP[s]?.chartColor ?? 'currentColor' } as CSSProperties}>{coinName(s)}</b>
            ))}
          </div>
        )}
        <div className="relative grid grid-cols-1 xl:grid-cols-[1fr_auto] gap-8 items-end">
          <div>
            <Link to="/arena" className="inline-flex items-center gap-1 text-[13px] mute mb-2.5">
              <ChevronLeft className="w-3.5 h-3.5" />{t('term.backToArena')}
            </Link>
            <div className="flex items-center gap-3.5 flex-wrap">
              <b className="cond text-[56px] font-bold leading-none">{tr?.name ?? '…'}</b>
              {tr && st && (
                <span className={cn('chip', st.chip)}>
                  {tr.status === 'RUNNING' && <i className="dot pulse" />}{t(st.labelKey)}
                </span>
              )}
              {tr?.mine && <span className="chip fill orange">{t('arena.mine')}</span>}
            </div>
            {tr && (
              <div className="text-[14px] mute mt-3">
                {[
                  tr.model ?? t('term.noModel'),
                  t('wb.meta.beat', { iv: tr.intervalCode }),
                  tr.wakeWindow ? t('wb.meta.window', { win: tr.wakeWindow.replace('-', '–') }) : t('detail.allDayWake'),
                  t('wb.meta.round', { n: tr.roundNo }),
                ].join(' · ')}
              </div>
            )}
          </div>
          <div className="num xl:text-right">
            <b ref={pctRef} className={cn('cond block text-[72px] font-bold leading-none', (tr?.pnlPct ?? 0) >= 0 ? 'up' : 'dn')} />
            <div className="flex items-baseline gap-3.5 xl:justify-end mt-2 text-[14px] mute">
              <span>{t('term.equity')} <b ref={eqRef} className="text-foreground text-[18px] font-semibold [font-stretch:85%]" /></span>
              <span>{t('detail.initialLabel')}</span>
            </div>
            <div className="flex items-center gap-2.5 flex-wrap xl:justify-end mt-3 text-[13px] mute">
              {/* 局次：每局是独立子账户各自注资 10000，曲线与时间线同进同出；只有一局时不出现 */}
              {rounds.length > 1 && (
                <>
                  <span>{t('detail.round')}</span>
                  <div className="seg">
                    {rounds.map(r => (
                      <button key={r} type="button" className={cn('num', r === viewingRound && 'on')}
                              onClick={() => pickRound(r === tr?.roundNo ? null : r)}>R{r}</button>
                    ))}
                  </div>
                </>
              )}
              <button type="button" className="ibtn" aria-label={t('common:refresh')}
                      onClick={() => { load(); setRefreshNonce(n => n + 1); }}>
                <RefreshCw className="ic" />
              </button>
            </div>
          </div>
        </div>
      </section>

      {tr && st && detail && (
        <HeartbeatStrip key={traderId} traderId={traderId} intervalCode={tr.intervalCode} wakeWindow={tr.wakeWindow}
                        running={tr.status === 'RUNNING'} idleText={t(st.labelKey)} nextWakeAt={detail.nextWakeAt}
                        mine={tr.mine} live={liveRunning} refreshKey={detail}
                        extra={{
                          k: t('wb.hb.watch'), v: t('wb.hb.watchN', { n: watchN }),
                          s: watchN ? t('wb.hb.watchMix', { p: priceWatchN, t: watchN - priceWatchN }) : t('wb.hb.watchNone'),
                        }} />
      )}

      {/* 记分条：六格平铺，窄屏三格两行 */}
      <ScoreStrip cells={[
        { label: roundLabel, value: dayNo != null ? t('detail.dayN', { n: dayNo }) : '—' },
        { label: t('detail.closedCount'), value: t('detail.tradesN', { count: closed.length }) },
        { label: t('detail.winRate'), value: winRate != null ? `${winRate}%` : '—' },
        { label: t('detail.maxDd'),
          tone: maxDd ? 'dn' : undefined,
          value: maxDd == null ? '—' : maxDd > 0 ? `-${maxDd.toFixed(2)}%` : '0.00%' },
        { label: effectiveRange === 0 ? t('detail.rangeAll') : t('detail.rangeDays', { n: effectiveRange }),
          tone: windowDelta != null ? (windowDelta >= 0 ? 'up' : 'dn') : undefined,
          value: windowDelta != null && windowPct != null
            ? `${windowDelta >= 0 ? '+' : ''}${fmtNum(windowDelta)} · ${windowDelta >= 0 ? '+' : ''}${windowPct.toFixed(2)}%`
            : '—' },
        { label: t('detail.tokensOf', { scope: tokenScope }),
          value: tokens != null ? fmtTokens(tokens) : '—' },
      ]} />

      {tr?.pausedReason && (
        <div className="mt-3.5 border-l-4 border-warning pl-3 py-1 text-[13px] text-warning">{tr.pausedReason}</div>
      )}
      {/* 持仓/盯盘/已了结是实时现查当前账户的，看历史局时跟曲线不是同一局，一条横幅说清楚 */}
      {viewingHistory && (
        <div className="mt-3.5 border-l-4 border-warning pl-3 py-1 text-[13px] text-warning">
          {t('detail.historyRoundBanner', { viewing: viewingRound, cur: tr.roundNo })}
        </div>
      )}

      {/* 贯通两栏：左主栏 曲线 + 实时思考（醒着才有）+ 时间线，右栏 仓位 + 盯盘 + 笔记。
          窄屏两个栏 div 退成 contents，六块直接落进外层单列 grid，再靠 order 排成
          曲线 → 实时思考 → 仓位 → 盯盘 → 笔记 → 时间线：时间线能一直往下加载，压在最后才不会把别的挤没 */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-8 mt-10">
        <div className="contents xl:flex xl:col-span-7 xl:flex-col xl:gap-11">
          <div className="order-1 xl:order-none">
            <div className={BLK_H}>
              <h2>{t('detail.equityCurve', { round: roundLabel })}</h2>
              <div className="seg">
                {RANGES.map(r => (
                  <button key={r} type="button" className={cn(r === effectiveRange && 'on')} onClick={() => setRange(r)}>
                    {r === 0 ? t('detail.rangeAll') : t('detail.rangeDays', { n: r })}
                  </button>
                ))}
              </div>
            </div>
            {visible.length > 1
              ? <EquityCurve points={visible} events={curveEvents} />
              : <div className="h-[300px] flex items-center justify-center text-[14px] mute">{t('detail.notEnoughPoints')}</div>}
          </div>

          {/* 实时思考只给主人：门控必须在这层，LiveRunCard 一挂载就建流，卡片内部 return null 拦不住 */}
          {/* 主人这边常挂着（里面的流要一直连着），醒着才渲染出来 */}
          {tr?.mine && (
            <LiveRunCard traderId={traderId} intervalCode={tr.intervalCode} onEnded={setEndedAt} onRunning={setLiveRunning}
                         className="order-2 xl:order-none" />
          )}

          <div ref={listRef} className="order-6 xl:order-none scroll-mt-16">
            <div className="flex items-end gap-[22px] flex-wrap border-b border-foreground">
              <button type="button" className={tabBtn(tab === 'timeline')} onClick={() => setTab('timeline')}>
                {t('detail.timeline')}{viewingHistory && ` · R${viewingRound}`}
              </button>
              <button type="button" className={tabBtn(tab === 'trades')} onClick={() => setTab('trades')}>
                {t('detail.tradesTitle')}
                <span className="chip mute num text-[10.5px] px-[5px]">{trades.length}</span>
              </button>
              {/* 按天翻看：前后一天 + 日期框；清掉回到不限日期。只属于时间线 */}
              {tab === 'timeline' && (
                <div className="ml-auto flex items-center gap-1.5 pb-2 text-[13px]">
                  <button type="button" className="btn xs" onClick={() => shiftDay(-1)}>{t('detail.prevDay')}</button>
                  <DatePicker value={day ?? ''} max={today} onChange={changeDay}
                              className="input num h-7 px-2 text-[13px]" />
                  <button type="button" className="btn xs disabled:opacity-40" disabled={!day || day >= today}
                          onClick={() => shiftDay(1)}>{t('detail.nextDay')}</button>
                  {day && (
                    <button type="button" className="btn xs" aria-label={t('detail.allDays')} onClick={() => changeDay(null)}>
                      <X className="w-3 h-3" />
                    </button>
                  )}
                </div>
              )}
            </div>

            {tab === 'timeline' ? (
              <>
                {/* 筛选只在已经拉下来的这些里挑，计数也是 */}
                <div className="flex gap-1.5 flex-wrap mt-3.5 mb-1">
                  {FILTERS.map(f => (
                    <button key={f} type="button" className={cn('tr-fchip', filter === f && 'on')} onClick={() => setFilter(f)}>
                      {t(`wb.filter.${f}`)}<em className="num">{decisions.filter(d => passes(d, f)).length}</em>
                    </button>
                  ))}
                </div>
                {shown.length === 0 ? (
                  <div className="py-10 text-center text-[14px] mute">
                    {decisions.length > 0 ? t('wb.filter.none') : day ? t('detail.noDecisionsDay') : t('detail.noDecisions')}
                  </div>
                ) : (
                  <div ref={staggerRef}>
                    {shown.map(d => <DecisionCard key={d.id} d={d} mine={tr?.mine} highlight={d.id === focusId} />)}
                  </div>
                )}
                {hasMore && decisions.length > 0 && (
                  <div className="py-4">
                    <button type="button" onClick={loadMore} disabled={loadingMore} className="btn sm w-full disabled:opacity-40">
                      {loadingMore && <Loader2 className="ic animate-spin" />}{t('detail.loadMore')}
                    </button>
                  </div>
                )}
              </>
            ) : (
              trades.length === 0 ? (
                <div className="py-10 text-center text-[14px] mute">{t('detail.noTrades')}</div>
              ) : (
                <div ref={staggerRef}>
                  {trades.map(r => <TradeCard key={r.positionId} r={r} onJump={jumpToDecision}
                                              onToggleStale={detail?.trader.mine ? toggleStale : undefined} />)}
                </div>
              )
            )}
          </div>
        </div>

        <div className="contents xl:flex xl:col-span-5 xl:flex-col xl:gap-11">
          <div className="order-3 xl:order-none">
            {detail && <Holdings positions={detail.positions} orders={detail.pendingOrders} plans={detail.plans} />}
          </div>
          <div className="order-4 xl:order-none">
            {detail && <WatchList watches={detail.watches} />}
          </div>
          {/* 经验账本跨局累积，不随局次切换 */}
          <div className="order-5 xl:order-none">
            <div className={BLK_H}>
              <h2>{t('detail.notesTitle')}</h2>
            </div>
            <NotesCard title={t('detail.memoryShort')} time={detail?.lastReviewAt}
                       content={detail?.memory} empty={t('detail.noMemory')} />
          </div>
        </div>
      </div>
    </div>
  );
}
