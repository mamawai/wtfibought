import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, Loader2, RefreshCw, X } from 'lucide-react';
import { traderApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { DatePicker } from '../components/ui/date-picker';
import { Markdown } from '../components/Markdown';
import { DecisionCard } from '../components/arena/DecisionCard';
import { EquityCurve } from '../components/arena/EquityCurve';
import { LiveRunCard } from '../components/arena/LiveRunCard';
import { PlanBlock } from '../components/arena/PlanBlock';
import { PositionsTable } from '../components/arena/PositionsTable';
import { ScoreStrip } from '../components/arena/ScoreStrip';
import { STATUS_META } from '../components/arena/traderStatus';
import { TradeCard } from '../components/arena/TradeCard';
import { useCountUp } from '../hooks/useCountUp';
import { useStagger } from '../hooks/useStagger';
import { cn, dayBounds, fmtDate, fmtDateTime, fmtNum, fmtSignedPct, fmtTime, fmtTokens, DAY_MS } from '../lib/utils';
import type { AiTraderDecisionView, TradeDecisionRef, TradeRecordView, TraderDetailView, TraderEquityPoint } from '../types';

const REFRESH_MS = 60_000;
const PAGE = 50;
/** 净值曲线可选区间（天）；0=整局 */
const RANGES = [3, 7, 14, 30, 0] as const;
type Range = typeof RANGES[number];
type Tab = 'timeline' | 'trades';

/** 块头：和分节头同一套排版，只是间距紧一档 */
const BLK_H = 'sec-h mb-4';

/** 币种列表 BTCUSDT,ETHUSDT → BTC / ETH */
const symbolList = (symbols: string) => symbols.split(',').map(s => s.replace('USDT', '')).join(' / ');

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

/**
 * 笔记一行（记忆/学习）：标题 + 首句预览 + 最近时间，点开才铺 markdown。
 * 两份笔记是参考资料，不跟实时数据抢版面。
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
 * trader 详情：记分牌 → 六格仪表条 → 左主栏（净值 + 现场 + 时间线）‖ 右侧栏（持仓 + 计划 + 两份笔记）。
 * 决策时间线是这页的正餐，整页滚不封顶；窄屏两栏堆成一列，卡序另排（见布局处注释）。
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
  // 从已了结交易跳过来要找的那一条决策：描边 + 滚到它
  const [focusId, setFocusId] = useState<number | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  // 时间线/已了结共用一个逐项登场：两个列表同一时刻只有一个挂着
  const staggerRef = useStagger<HTMLDivElement>();
  // 现场卡报上来的"刚跑完一轮"时刻：新决策行、净值点、持仓都在那一刻落库，据此立刻重拉
  const [endedAt, setEndedAt] = useState(0);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    void traderApi.detail(traderId).then(setDetail).catch(() => setDetail(null));
    void traderApi.equityCurve(traderId, round ?? undefined).then(setCurve).catch(() => setCurve([]));
    void traderApi.trades(traderId).then(setTrades).catch(() => setTrades([]));
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

  // 时间线单独拉：按天翻看只动它，持仓/曲线/已了结不跟着重拉
  const loadDecisions = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    const bounds = day ? dayBounds(day) : null;
    void traderApi.decisions(traderId, PAGE, undefined, round ?? undefined, bounds?.from, bounds?.to).then(list => {
      setDecisions(list);
      setHasMore(list.length >= PAGE);
    }).catch(() => setDecisions([]));
    // token 合计跟时间线同一套筛选，但列表是分页的、求和不能靠前端，另发一个并行请求让库去 SUM
    void traderApi.tokenUsage(traderId, round ?? undefined, bounds?.from, bounds?.to)
      .then(setTokens).catch(() => setTokens(null));
  }, [traderId, round, day]);

  // 定时刷新之外，一轮唤醒刚结束（endedAt 变）也立刻重拉：新决策行、净值点、持仓都在那一刻落库
  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load, endedAt]);
  useEffect(() => {
    loadDecisions();
    const timer = setInterval(loadDecisions, REFRESH_MS);
    return () => clearInterval(timer);
  }, [loadDecisions, endedAt]);

  const loadMore = useCallback(() => {
    const oldest = decisions[decisions.length - 1];
    if (!oldest) return;
    const bounds = day ? dayBounds(day) : null;
    setLoadingMore(true);
    traderApi.decisions(traderId, PAGE, oldest.wakeTime, round ?? undefined, bounds?.from, bounds?.to)
      .then(list => {
        setDecisions(prev => [...prev, ...list]);
        setHasMore(list.length >= PAGE);
      })
      .finally(() => setLoadingMore(false));
  }, [traderId, decisions, round, day]);

  // 局与局的日期不重叠，切局时日期筛选一并清掉
  const pickRound = (r: number | null) => { setRound(r); setDay(null); setFocusId(null); };
  const changeDay = (d: string | null) => { setDay(d); setFocusId(null); };
  const today = fmtDate();
  // 没选日期时从今天起步
  const shiftDay = (delta: number) => changeDay(fmtDate(dayBounds(day ?? today).from + delta * DAY_MS));

  // 已了结交易 → 它的开/平仓那一轮：切回时间线、筛到那一天、当前局（已了结只有当前局的）
  const jumpToDecision = (d: TradeDecisionRef) => {
    setTab('timeline');
    setRound(null);
    setDay(fmtDate(d.wakeTime));
    setFocusId(d.id);
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

  // 记分牌：第几天＝曲线首点到末点（末点就是最近一次唤醒）；笔数/胜率从已了结交易现算，只有当前局的
  const dayNo = curve.length > 0
    ? Math.floor((curve[curve.length - 1].wakeTime - curve[0].wakeTime) / DAY_MS) + 1
    : null;
  const closed = trades.filter(r => r.closedPnl != null);
  const winRate = closed.length > 0 ? Math.round(closed.filter(r => (r.closedPnl as number) > 0).length / closed.length * 100) : null;
  const maxDd = maxDrawdownPct(curve);
  // token 那格的微标签跟着时间线筛选走：翻到某天就是那天，没翻就是当前看的这一局
  const tokenScope = day ? day.slice(5) : roundLabel;

  const tabBtn = (on: boolean) => cn('pb-2.5 -mb-px inline-flex items-center gap-2 text-[15px] border-b-2 cursor-pointer',
    on ? 'text-foreground font-extrabold border-foreground' : 'mute font-semibold border-transparent');

  return (
    <div className="wrap">
      {/* 记分牌：左身份 + 局次，右收益率 + 权益 */}
      <div className="grid grid-cols-1 xl:grid-cols-[1fr_auto] gap-8 items-end pt-8">
        <div>
          <Link to="/arena" className="inline-flex items-center gap-1 text-[13px] mute mb-2.5">
            <ChevronLeft className="w-3.5 h-3.5" />{t('term.backToArena')}
          </Link>
          <div className="flex items-baseline gap-4 flex-wrap">
            <b className="cond text-[56px] font-bold leading-none">{tr?.name ?? '…'}</b>
            {tr && st && (
              <span className={cn('chip', st.chip)}>
                {tr.status === 'RUNNING' && <i className="dot pulse" />}{t(st.labelKey)}
              </span>
            )}
            {tr?.mine && <span className="chip fill orange">{t('arena.mine')}</span>}
            {tr && (
              <span className="text-[14px] mute">
                {tr.model ?? t('term.noModel')} · {tr.intervalCode} · {symbolList(tr.symbols)} · {tr.wakeWindow ?? t('detail.allDayWake')}
              </span>
            )}
          </div>
          <div className="flex items-center gap-2.5 flex-wrap mt-3 text-[13px] mute">
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
                    onClick={() => { load(); loadDecisions(); }}>
              <RefreshCw className="ic" />
            </button>
          </div>
        </div>
        <div className="num xl:text-right">
          <b ref={pctRef} className={cn('cond block text-[72px] font-bold leading-none', (tr?.pnlPct ?? 0) >= 0 ? 'up' : 'dn')} />
          <div className="flex items-baseline gap-3.5 xl:justify-end mt-2 text-[14px] mute">
            <span>{t('term.equity')} <b ref={eqRef} className="text-foreground text-[18px] font-semibold [font-stretch:85%]" /></span>
            <span>{t('detail.initialLabel')}</span>
          </div>
        </div>
      </div>

      {/* 仪表条：六格平铺，窄屏三格两行 */}
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
      {/* 持仓/计划/已了结是实时现查当前账户的，看历史局时跟曲线不是同一局，一条横幅说清楚 */}
      {viewingHistory && (
        <div className="mt-3.5 border-l-4 border-warning pl-3 py-1 text-[13px] text-warning">
          {t('detail.historyRoundBanner', { viewing: viewingRound, cur: tr.roundNo })}
        </div>
      )}

      {/* 贯通两栏：左主栏 曲线 + 现场（唤醒中才有）+ 时间线，右侧栏 持仓 + 计划 + 两份笔记。
          窄屏两个栏 div 退成 contents，六块直接落进外层单列 grid，再靠 order 排成
          曲线 → 现场 → 持仓 → 计划 → 笔记 → 时间线：时间线能一直往下加载，压在最后才不会把别的挤没 */}
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
              ? <EquityCurve points={visible} lastLabel={t('detail.lastWake', { time: fmtTime(visible[visible.length - 1].wakeTime) })} />
              : <div className="h-[280px] flex items-center justify-center text-[14px] mute">{t('detail.notEnoughPoints')}</div>}
          </div>

          {/* 现场只给主人：门控必须在这层，LiveRunCard 一挂载就建流，卡片内部 return null 拦不住 */}
          {/* 主人这边常挂着（里面的流要一直连着），唤醒中才渲染出来 */}
          {tr?.mine && (
            <LiveRunCard traderId={traderId} intervalCode={tr.intervalCode} onEnded={setEndedAt}
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
              decisions.length === 0 ? (
                <div className="py-10 text-center text-[14px] mute">{day ? t('detail.noDecisionsDay') : t('detail.noDecisions')}</div>
              ) : (
                <>
                  <div ref={staggerRef}>
                    {decisions.map(d => <DecisionCard key={d.id} d={d} mine={tr?.mine} highlight={d.id === focusId} />)}
                  </div>
                  {hasMore && (
                    <div className="py-4">
                      <button type="button" onClick={loadMore} disabled={loadingMore} className="btn sm w-full disabled:opacity-40">
                        {loadingMore && <Loader2 className="ic animate-spin" />}{t('detail.loadMore')}
                      </button>
                    </div>
                  )}
                </>
              )
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

        <div className="contents xl:flex xl:col-span-5 xl:flex-col xl:gap-10">
          <div className="order-3 xl:order-none">
            <div className={BLK_H}>
              <h2>{t('detail.positionsTitle')}</h2>
            </div>
            {detail && (detail.positions.length === 0 && detail.pendingOrders.length === 0
              ? <div className="py-6 text-[14px] mute">{t('detail.flat')}</div>
              : <PositionsTable positions={detail.positions} orders={detail.pendingOrders} />)}
          </div>

          {/* 计划是本局存活的，归档的配在已了结卡里；两份笔记跨局累积不随局次切换 */}
          <div className="order-4 xl:order-none">
            <div className={BLK_H}>
              <h2>{t('detail.plansTitle')}<small>{t('detail.plansActive', { n: detail?.plans.length ?? 0 })}</small></h2>
            </div>
            {detail && (detail.plans.length === 0
              ? <div className="py-6 text-[14px] mute">{t('detail.noPlans')}</div>
              : detail.plans.map(pl => <PlanBlock key={pl.id} plan={pl} />))}
          </div>

          <div className="order-5 xl:order-none">
            <div className={BLK_H}>
              <h2>{t('detail.notesTitle')}</h2>
            </div>
            <NotesCard title={t('detail.memoryShort')} time={detail?.lastReviewAt}
                       content={detail?.memory} empty={t('detail.noMemory')} />
            <NotesCard title={t('detail.learnShort')} time={detail?.lastLearnAt}
                       content={detail?.learningNotes} empty={t('detail.noLearnNotes')} />
          </div>
        </div>
      </div>
    </div>
  );
}
