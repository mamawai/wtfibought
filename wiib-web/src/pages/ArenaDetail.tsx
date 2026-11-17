import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type MouseEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight, Info, RefreshCw, SlidersHorizontal, TriangleAlert, X } from 'lucide-react';
import { traderApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { DatePicker } from '../components/ui/date-picker';
import { Seg } from '../components/ui/seg';
import { Tabs } from '../components/ui/tabs';
import { Markdown } from '../components/Markdown';
import { DecisionCard } from '../components/arena/DecisionCard';
import { EquityCurve, type CurveEvent } from '../components/arena/EquityCurve';
import { HeartbeatStrip } from '../components/arena/HeartbeatStrip';
import { Holdings, WatchList } from '../components/arena/HoldingsPanel';
import { LiveRunCard } from '../components/arena/LiveRunCard';
import { PnlPct } from '../components/arena/PnlPct';
import { ScoreStrip } from '../components/arena/ScoreStrip';
import { STATUS_META } from '../components/arena/traderStatus';
import { TradeCard } from '../components/arena/TradeCard';
import { doneTrades } from '../components/arena/traderTools';
import { COIN_MAP, getCoin } from '../lib/coinConfig';
import { cn, dayBounds, fmtDate, fmtDateTime, fmtNum, fmtTokens, parseServerTime, spinIcon, DAY_MS } from '../lib/utils';
import type { AiTraderDecisionView, TradeDecisionRef, TradeRecordView, TraderDetailView, TraderEquityPoint } from '../types';
import '../components/arena/traderHead.css';
import './ArenaDetail.css';

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

/** 笔记（经验账本）：一块浅灰，正文先露一截；比露出来的长才底下渐隐、出「展开全文」，点了铺开 */
function NotesCard({ time, content }: { time: number | null | undefined; content: string | null | undefined }) {
  const { t } = useTranslation(['ai', 'common']);
  const [open, setOpen] = useState(false);
  // 收着时正文有没有被截掉；栏宽变了会重新折行，跟着再量
  const [long, setLong] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);
  const text = content?.trim() || '';
  useLayoutEffect(() => {
    const el = bodyRef.current;
    if (!el || open) return;
    const measure = () => setLong(el.scrollHeight > el.clientHeight);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [text, open]);
  return (
    <div className="blk">
      <div className="nt-h">
        <b>{t('detail.memoryShort')}</b>
        {time != null && text && <span>{t('detail.lastAt', { time: fmtDateTime(time) })}</span>}
      </div>
      {text ? (
        <>
          <div ref={bodyRef} className={cn('nt-b', open && 'open', long && 'long')}><Markdown content={text} /></div>
          {long && (
            <button type="button" className="linkb nt-more" onClick={() => setOpen(o => !o)}>
              {open ? t('common:collapse') : t('detail.expandFull')}
            </button>
          )}
        </>
      ) : <p className="nt-e">{t('detail.noMemory')}</p>}
    </div>
  );
}

/**
 * trader 工作台（样式见 ArenaDetail.css）：页头（名字 / 收益率大数）→ 心跳条 → 记分条 →
 * 左主栏（净值 + 实时思考 + 时间线）‖ 右栏（仓位 + 盯盘 + 笔记）。
 * 时间线是这页的正餐，整页滚不封顶；窄屏两栏堆成一列，块序另排（见 css）。
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
  // 从做完的交易跳过来要找的那一条决策：底色亮一下 + 滚到它
  const [focusId, setFocusId] = useState<number | null>(null);
  const listRef = useRef<HTMLElement>(null);
  // 实时思考卡报上来的"刚跑完一轮"时刻：新决策行、净值点、持仓都在那一刻落库，据此立刻重拉
  const [endedAt, setEndedAt] = useState(0);
  // 它正醒着想事（只有主人看得到）：心跳条第一格换成「正在想事」
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
    // 上一页还没回来就不再拉：转圈的钮键盘还按得动
    if (!oldest || loadingMore) return;
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
  }, [traderId, decisions, loadingMore, round, day, timelineKey]);

  const refresh = (e: MouseEvent<HTMLButtonElement>) => {
    spinIcon(e);
    load();
    setRefreshNonce(n => n + 1);
  };

  // 局与局的日期不重叠，切局时日期筛选一并清掉
  const pickRound = (r: number | null) => { setRound(r); setDay(null); setFocusId(null); };
  const changeDay = (d: string | null) => { setDay(d); setFocusId(null); };
  const today = fmtDate();
  // 没选日期时从今天起步
  const shiftDay = (delta: number) => changeDay(fmtDate(dayBounds(day ?? today).from + delta * DAY_MS));

  // 做完的交易 → 它的开/平仓那一轮：切回时间线、筛到那一天、当前局（做完的交易只有当前局的）。
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
  // 区间内点不够画线（末点之前是长停工）就退到整局，段选跟着退
  const effectiveRange: Range = windowed.length > 1 ? range : 0;
  const visible = effectiveRange === 0 ? curve : windowed;
  // 区间内变化：窗口首尾权益之差，百分比按窗口起点权益
  const windowDelta = visible.length > 1 ? visible[visible.length - 1].equity - visible[0].equity : null;
  const windowPct = windowDelta != null && visible[0].equity ? windowDelta / visible[0].equity * 100 : null;

  // 净值图上的开平仓点：开仓空心圈，平仓按盈亏绿 / 红；指到时写进读数行
  const curveEvents = useMemo<CurveEvent[]>(() => {
    const side = (s: string) => t(s === 'LONG' ? 'term.long' : 'term.short');
    const events: CurveEvent[] = [];
    for (const r of trades) {
      events.push({ at: r.openedAt, type: 'open', text: t('wb.ev.open', { coin: coinName(r.symbol), side: side(r.side) }) });
      if (r.closedPnl != null) {
        events.push({
          at: r.closedAt,
          type: r.closedPnl >= 0 ? 'win' : 'loss',
          text: t('wb.ev.close', {
            coin: coinName(r.symbol), how: t(`trade.closeManner.${r.closeMannerKey}`),
            pnl: `${r.closedPnl >= 0 ? '+' : '-'}${fmtNum(Math.abs(r.closedPnl))}`,
          }),
        });
      }
    }
    for (const p of detail?.positions ?? []) {
      events.push({ at: parseServerTime(p.createdAt).getTime(), type: 'open', text: t('wb.ev.open', { coin: coinName(p.symbol), side: side(p.side) }) });
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
  // round=null 表示跟随当前局，落到显示时统一成具体数字
  const viewingRound = round ?? tr?.roundNo ?? 1;
  const viewingHistory = tr != null && viewingRound !== tr.roundNo;
  // 只列保留窗口内的局——后端只留最近 10 局（TraderService.MAX_ROUNDS_KEPT），更早的已整局清除
  const rounds = tr
    ? Array.from({ length: Math.min(tr.roundNo, 10) }, (_, i) => Math.max(1, tr.roundNo - 9) + i)
    : [];
  const roundLabel = viewingHistory ? `R${viewingRound}` : t('detail.thisRound');

  // 记分条：第几天＝曲线首点到末点（末点就是最近一次唤醒）；笔数/胜率从做完的交易现算，只有当前局的
  const dayNo = curve.length > 0
    ? Math.floor((curve[curve.length - 1].wakeTime - curve[0].wakeTime) / DAY_MS) + 1
    : null;
  const closed = trades.filter(r => r.closedPnl != null);
  const winRate = closed.length > 0 ? Math.round(closed.filter(r => (r.closedPnl as number) > 0).length / closed.length * 100) : null;
  const maxDd = maxDrawdownPct(curve);
  // token 那格的小字跟着时间线筛选走：翻到某天就是那天，没翻就是当前看的这一局
  const tokenScope = day ? day.slice(5) : roundLabel;
  // 心跳条第四格：在盯几条，其中看价格的几条
  const watchN = detail?.watches.length ?? 0;
  const priceWatchN = detail?.watches.filter(w => w.kind !== 'AT_TIME').length ?? 0;

  return (
    <div className="page-shell p-tw">
      {/* 页头：左名字 + 一行说明，右收益率大数 + 权益 + 局次 / 刷新 / 配置 */}
      <div className="trhd">
        <div>
          <Link to="/arena" className="back"><ChevronLeft className="ic" />{t('term.backToArena')}</Link>
          <div className="nm">
            <b>{tr?.name ?? '…'}</b>
            {tr && st && <span className={cn('tag', st.tone)}><i className={cn(tr.status === 'RUNNING' && 'live')} />{t(st.labelKey)}</span>}
            {tr?.mine && <span className="tag new">{t('arena.mine')}</span>}
          </div>
          {tr && (
            <div className="meta">
              {/* 它交易的几个币：图标叠成一小串 */}
              <span className="coins">
                {tr.symbols.split(',').map(s => {
                  const Icon = getCoin(s).icon;
                  return <Icon key={s} className="ci" />;
                })}
              </span>
              <span>
                {[
                  tr.model ?? t('term.noModel'),
                  t('wb.meta.beat', { iv: tr.intervalCode }),
                  tr.wakeWindow ? t('wb.meta.window', { win: tr.wakeWindow.replace('-', '–') }) : t('detail.allDayWake'),
                  t('wb.meta.round', { n: tr.roundNo }),
                ].join(' · ')}
              </span>
            </div>
          )}
        </div>
        {tr && (
          <div className="pnl">
            <b className={cn('big nf', tr.pnlPct >= 0 ? 'up' : 'dn')}><PnlPct value={tr.pnlPct} /></b>
            <div className="sub">
              <span>{t('term.equity')} <b>{fmtNum(tr.equity)}</b></span>
              <span>{t('arena.initial')}</span>
            </div>
            <div className="acts">
              {/* 局次：每局是独立子账户各自注资 10000，曲线与时间线同进同出；只有一局时不出现 */}
              {rounds.length > 1 && (
                <>
                  <span>{t('detail.round')}</span>
                  <Seg value={String(viewingRound)} label={t('detail.round')}
                       onChange={v => pickRound(Number(v) === tr.roundNo ? null : Number(v))}
                       options={rounds.map(r => ({ value: String(r), label: `R${r}` }))} />
                </>
              )}
              <button type="button" className="ib" aria-label={t('common:refresh')} title={t('common:refresh')} onClick={refresh}>
                <RefreshCw className="ic" />
              </button>
              {tr.mine && <Link className="btn" to="/my-trader"><SlidersHorizontal className="ic" />{t('detail.configure')}</Link>}
            </div>
          </div>
        )}
      </div>

      {tr && st && detail && (
        <HeartbeatStrip key={traderId} traderId={traderId} intervalCode={tr.intervalCode} wakeWindow={tr.wakeWindow}
                        running={tr.status === 'RUNNING'} idleText={t(st.labelKey)} nextWakeAt={detail.nextWakeAt}
                        mine={tr.mine} live={liveRunning} refreshKey={detail}
                        extra={{
                          k: t('wb.hb.watch'), v: t('wb.hb.watchN', { n: watchN }),
                          s: watchN ? t('wb.hb.watchMix', { p: priceWatchN, t: watchN - priceWatchN }) : t('wb.hb.watchNone'),
                        }} />
      )}

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
            ? `${windowDelta >= 0 ? '+' : '-'}${fmtNum(Math.abs(windowDelta))} · ${windowDelta >= 0 ? '+' : ''}${windowPct.toFixed(2)}%`
            : '—' },
        { label: t('detail.tokensOf', { scope: tokenScope }),
          value: tokens != null ? fmtTokens(tokens) : '—' },
      ]} />

      {(tr?.pausedReason || viewingHistory) && (
        <div className="bans">
          {tr?.pausedReason && <div className="note w"><TriangleAlert className="ic" /><div>{tr.pausedReason}</div></div>}
          {/* 持仓/盯盘/做完的交易是实时现查当前账户的，看历史局时跟曲线不是同一局，一条横幅说清楚 */}
          {viewingHistory && (
            <div className="note"><Info className="ic" /><div>{t('detail.historyRoundBanner', { viewing: viewingRound, cur: tr.roundNo })}</div></div>
          )}
        </div>
      )}

      <div className="grid2">
        <div className="left">
          <section className="w-curve">
            <div className="sec-h">
              <h2>{t('detail.equityCurve', { round: roundLabel })}</h2>
              <Seg value={String(effectiveRange)} onChange={v => setRange(Number(v) as Range)}
                   options={RANGES.map(r => ({ value: String(r), label: r === 0 ? t('detail.rangeAll') : t('detail.rangeDays', { n: r }) }))} />
            </div>
            {visible.length > 1
              ? <EquityCurve points={visible} events={curveEvents} />
              : <div className="cv"><div className="empty"><b>{t('detail.notEnoughPoints')}</b></div></div>}
          </section>

          {/* 实时思考只给主人：门控必须在这层，LiveRunCard 一挂载就建流，卡片内部 return null 拦不住。
              主人这边常挂着（里面的流要一直连着），醒着才渲染出来 */}
          {tr?.mine && (
            <LiveRunCard traderId={traderId} intervalCode={tr.intervalCode} onEnded={setEndedAt} onRunning={setLiveRunning}
                         className="w-live" />
          )}

          <section ref={listRef} className="w-tl">
            <div className="tl-h">
              <Tabs value={tab} onChange={setTab} items={[
                { value: 'timeline', label: `${t('detail.timeline')}${viewingHistory ? ` · R${viewingRound}` : ''}` },
                { value: 'trades', label: t('detail.tradesTitle'), count: trades.length },
              ]} />
              {/* 按天翻看：前后一天 + 日期框；清掉回到不限日期。只属于时间线，切到交易时隐身占位，页签那行不跳 */}
              <div className={cn('dstep', tab !== 'timeline' && 'invisible')}>
                <button type="button" className="ib sm" aria-label={t('detail.prevDay')} title={t('detail.prevDay')} onClick={() => shiftDay(-1)}>
                  <ChevronLeft className="ic" />
                </button>
                <DatePicker value={day ?? ''} max={today} onChange={changeDay} />
                <button type="button" className="ib sm" aria-label={t('detail.nextDay')} title={t('detail.nextDay')}
                        disabled={!day || day >= today} onClick={() => shiftDay(1)}>
                  <ChevronRight className="ic" />
                </button>
                {day && (
                  <button type="button" className="xb" aria-label={t('detail.allDays')} title={t('detail.allDays')} onClick={() => changeDay(null)}>
                    <X className="ic" />
                  </button>
                )}
              </div>
            </div>

            {tab === 'timeline' ? (
              <>
                {/* 筛选只在已经拉下来的这些里挑，计数也是 */}
                <div className="chips">
                  {FILTERS.map(f => (
                    <button key={f} type="button" className="chip" aria-pressed={filter === f} onClick={() => setFilter(f)}>
                      {t(`wb.filter.${f}`)}<em>{decisions.filter(d => passes(d, f)).length}</em>
                    </button>
                  ))}
                </div>
                {shown.length === 0 ? (
                  <div className="empty">
                    <b>{decisions.length > 0 ? t('wb.filter.none') : day ? t('detail.noDecisionsDay') : t('detail.noDecisions')}</b>
                  </div>
                ) : (
                  <div>
                    {shown.map(d => <DecisionCard key={d.id} d={d} mine={tr?.mine} highlight={d.id === focusId} />)}
                  </div>
                )}
                {hasMore && decisions.length > 0 && (
                  <button type="button" className={cn('btn wide more', loadingMore && 'busy')} onClick={loadMore}>
                    <span>{t('detail.loadMore')}</span>
                  </button>
                )}
              </>
            ) : (
              trades.length === 0
                ? <div className="empty"><b>{t('detail.noTrades')}</b></div>
                : trades.map(r => <TradeCard key={r.positionId} r={r} onJump={jumpToDecision}
                                             onToggleStale={tr?.mine ? toggleStale : undefined} />)
            )}
          </section>
        </div>

        <aside className="side">
          {detail && (
            <>
              <section className="w-hold"><Holdings positions={detail.positions} orders={detail.pendingOrders} plans={detail.plans} /></section>
              <section className="w-watch"><WatchList watches={detail.watches} /></section>
            </>
          )}
          {/* 经验账本跨局累积，不随局次切换 */}
          <section className="w-notes">
            <div className="sec-h"><h2>{t('detail.notesTitle')}</h2></div>
            <NotesCard time={detail?.lastReviewAt} content={detail?.memory} />
          </section>
        </aside>
      </div>
    </div>
  );
}
