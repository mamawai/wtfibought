import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ChevronDown, ChevronRight, CircleCheck, CircleX, Clock, GitCompare, RefreshCw, Save, Square, SquareCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  alpha, TRADE_LEGS, type Busy, type CompareJob, type DailyPnl, type DayAttrib, type LegSummary, type SchemeResult,
  type TradeLeg, type TradeRunBrief, type TradeRunDetail, type TradeStatus, type TraderAccount, type TraderCarry,
  type TraderDaily, type TraderFactor, type TraderJob, type TraderLive, type TraderPosition, type TraderSizing,
} from '../api';
import { useBusy } from '../busy';
import { cx, DASH, localClock, localTime, num, pct, signedPct, utcMinute } from '../format';
import { usePoll } from '../poll';
import { useFail } from '../toast';
import { Badge, Button, Card, Empty, Progress, Spinner, Stat, Strip, Tip, Waiting, type Tone } from '../components/ui';

const STATUS_TONE: Record<TradeStatus, Tone> = { done: 'gain', partial: 'warn', failed: 'loss' };
const STATUS_ICON = { done: CircleCheck, partial: TriangleAlert, failed: CircleX };
const METRICS: (keyof SchemeResult)[] = ['netReturn', 'sharpe', 'recentSharpe', 'maxDrawdown', 'turnover', 'carryFundingBps', 'periods'];

/** 金额两位小数 */
const money = (v: number | null | undefined) => num(v, 2);
/** 盈亏：两位小数，正的带加号 */
const signedMoney = (v: number | null | undefined) => (v == null ? DASH : `${v > 0 ? '+' : ''}${v.toFixed(2)}`);
/** 这么久没收到推送（后端重启、网断了、sim 卡住）也算停了；正常 3 秒一帧 */
const STALE_MS = 10_000;

/** 交易员页：账户、每日盈亏、持仓谁都能看，净值和持仓走实时推送；两条腿的归因与止损线、仓位规模、交易因子的勾选与对比、
 *  调仓记录（带着因子名和权重）只给管理员 */
export function TraderPanel({ active, admin }: { active: boolean; admin: boolean }) {
  const { t } = useTranslation();
  const fail = useFail();
  const blocked = useBusy(active && admin);
  const [account, setAccount] = useState<TraderAccount | null>(null);
  const [daily, setDaily] = useState<TraderDaily | null>(null);
  const [live, setLive] = useState<TraderLive | null>(null);
  // 没问到 sim、或者好一阵没收到推送：数字停在上一帧
  const [stale, setStale] = useState(false);
  const [runs, setRuns] = useState<TradeRunBrief[] | null>(null);
  const [factors, setFactors] = useState<TraderFactor[] | null>(null);
  const [carry, setCarry] = useState<TraderCarry | null>(null);
  const [compare, setCompare] = useState<CompareJob | null>(null);
  const [sizing, setSizing] = useState<TraderSizing | null>(null);
  // 实时数里的调仓日：变了是又调过一次仓，每日盈亏、调仓记录和仓位规模重拉
  const liveDay = useRef<string | null>(null);
  // 上一次收到推送的时刻
  const lastFrame = useRef(0);

  const loadTrader = useCallback(() => Promise.all([
    alpha.traderAccount().then(setAccount).catch(fail),
    alpha.traderDaily().then(setDaily).catch(fail),
    ...(admin ? [alpha.traderRuns().then(r => setRuns(r.items)).catch(fail)] : []),
  ]), [admin, fail]);

  const loadFactors = useCallback(() => alpha.traderFactors()
    .then(r => { setFactors(r.items); setCarry(r.carry); setCompare(r.compare); })
    .catch(fail), [fail]);

  // 仓位规模的预览按最近一次调仓的篮子算
  const loadSizing = useCallback(() => alpha.traderSizing().then(setSizing).catch(fail), [fail]);

  useEffect(() => {
    if (!active) return;
    void loadTrader();
    if (!admin) return;
    void loadFactors();
    void loadSizing();
  }, [active, admin, loadTrader, loadFactors, loadSizing]);

  // 实时数：在这一页才连，离开就断
  useEffect(() => {
    if (!active) return;
    lastFrame.current = Date.now();
    const close = alpha.traderLive(f => {
      lastFrame.current = Date.now();
      setStale(!f.ok);
      if (!f.ok || f.userId == null) return;
      setLive(f);
      if (liveDay.current !== null && f.day !== liveDay.current) {
        void loadTrader();
        if (admin) void loadSizing();
      }
      liveDay.current = f.day;
    });
    const watchdog = window.setInterval(() => {
      if (Date.now() - lastFrame.current > STALE_MS) setStale(true);
    }, 1000);
    return () => {
      close();
      clearInterval(watchdog);
    };
  }, [active, admin, loadTrader, loadSizing]);

  usePoll(active && (account?.job.running ?? false), loadTrader, 5000);
  usePoll(active && (compare?.running ?? false), loadFactors, 3000);

  // 持仓跟着实时数走；还没建账户的就是空的
  const positions = useMemo(() => (live ? { items: live.positions, day: live.positionsDay }
    : account?.userId === null ? { items: [], day: null } : null), [account?.userId, live]);

  return (
    <div className="space-y-14">
      <AccountCard account={account} live={live} stale={stale} daily={daily} lastRun={runs?.[0] ?? null}
                   onReload={loadTrader} />
      <Card title={t('trader.daily')} hint={t('trader.dailyHint')} more={admin && t('trader.dailyMore')}>
        {!daily ? <Spinner /> : <DailyView daily={daily} live={live} admin={admin} />}
      </Card>
      {admin ? (
        <div className="grid items-start gap-x-12 gap-y-14 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <PositionsCard positions={positions} admin />
          <div className="space-y-14">
            <SizingCard sizing={sizing} onSaved={setSizing} />
            <FactorsCard factors={factors} carry={carry} compare={compare} blocked={blocked} onChanged={loadFactors}
                         onList={setFactors} onCarry={setCarry} />
          </div>
        </div>
      ) : <PositionsCard positions={positions} admin={false} />}
      {admin && <RunsCard runs={runs} />}
    </div>
  );
}

function AccountCard({ account, live, stale, daily, lastRun, onReload }: {
  account: TraderAccount | null;
  live: TraderLive | null;
  stale: boolean;
  daily: TraderDaily | null;
  lastRun: TradeRunBrief | null;
  onReload: () => Promise<unknown>;
}) {
  const { t } = useTranslation();
  return (
    <Card title={t('trader.title')} hint={t('trader.hint')} more={t('trader.hintMore')}
          actions={<Button variant="icon" icon={RefreshCw} aria-label={t('common.refresh')} onClick={() => void onReload()} />}>
      {!account ? <Spinner /> : (
        <div className="space-y-4">
          {!live ? (account.userId == null ? <Empty>{t('trader.noAccount')}</Empty> : !stale && <Spinner />) : (
            <div>
              {/* 记分牌：净值、累计收益两个大数，底下一排细项；数字跟着实时推送跳 */}
              <Strip cols={2}>
                <Stat label={t('trader.equity')} size="lg">{money(live.equity)}</Stat>
                <Stat label={t('trader.totalReturn')} hint={t('trader.totalReturnHint', { initial: account.initialBalance })}
                      size="lg" tone={tone(live.equity - account.initialBalance)}>
                  {signedPct(live.equity / account.initialBalance - 1, 2)}
                </Stat>
              </Strip>
              <Strip cols={5}>
                <Stat label={t('trader.today')} tone={tone(live.dayPnl)}
                      hint={live.dayPnl == null || live.dayStart == null ? undefined
                        : t('trader.todayHint', { pct: signedPct(live.dayPnl / live.dayStart, 2), day: live.day })}>
                  {signedMoney(live.dayPnl)}
                </Stat>
                <Stat label={t('trader.drawdown')} hint={t('trader.drawdownHint')}>{pct(daily?.maxDrawdown, 2)}</Stat>
                <Stat label={t('trader.balance')}>{money(live.balance)}</Stat>
                <Stat label={t('trader.available')}>{money(live.available)}</Stat>
                <Stat label={t('trader.userId')}>{live.userId}</Stat>
              </Strip>
            </div>
          )}
          {stale ? (
            <div>
              <Badge tone="warn" icon={TriangleAlert}>
                {live ? t('trader.stale', { time: localClock(live.ts) }) : t('trader.down')}
              </Badge>
            </div>
          ) : live && (
            <div><Badge tone="gain" pulse>{t('trader.live', { time: localClock(live.ts) })}</Badge></div>
          )}
          <JobLine job={account.job} lastRun={lastRun} />
        </div>
      )}
    </Card>
  );
}

function JobLine({ job, lastRun }: { job: TraderJob; lastRun: TradeRunBrief | null }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2 text-[12.5px]">
      <div className="flex flex-wrap items-center gap-2 text-muted">
        <Clock className="w-3.5 h-3.5 shrink-0" />
        <span>{t('trader.schedule')}</span>
        {lastRun && <StatusBadge status={lastRun.status} />}
        {lastRun && <span className="num">{lastRun.day}</span>}
      </div>
      {job.running && (
        <>
          <div className="flex items-center gap-3">
            <Badge tone="info" pulse>{t('trader.running', { stage: t(`trader.stage.${job.stage ?? 'prepare'}`) })}</Badge>
            {job.stage === 'live' && <span className="num text-muted">{job.done ?? 0} / {job.total ?? 0} {job.symbol ?? ''}</span>}
          </div>
          {job.stage === 'live' && <Progress done={job.done ?? 0} total={job.total ?? 0} />}
        </>
      )}
      {!job.running && job.error && (
        <p className="flex items-start gap-1.5 text-loss break-words">
          <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('trader.lastError', { msg: job.error })}
        </p>
      )}
    </div>
  );
}

// ========== 每日盈亏 ==========

/** 一天一行；live 是还没到下次调仓的今天，跟着实时数走 */
type DayRow = DailyPnl & { live: boolean };

/** 上面一天一根柱，底下明细表（新的在前）；两条腿对止损线、每天按腿归因的几列只给管理员 */
function DailyView({ daily, live, admin }: { daily: TraderDaily; live: TraderLive | null; admin: boolean }) {
  const { t } = useTranslation();
  const rows = useMemo<DayRow[]>(() => {
    const done = daily.days.map(d => ({ ...d, live: false }));
    // 刚调过仓、每日盈亏还没重拉回来的那几秒，实时数的今天可能已经在 done 里
    if (!live || live.day == null || live.dayStart == null || live.dayPnl == null || done.some(d => d.day === live.day)) {
      return done;
    }
    return [...done, { day: live.day, start: live.dayStart, end: live.equity, pnl: live.dayPnl,
                       pct: live.dayPnl / live.dayStart, attrib: null, live: true }];
  }, [daily, live]);
  if (!rows.length) return <Empty>{t('trader.dailyEmpty')}</Empty>;
  return (
    <div className="space-y-6">
      {admin && <LegsStrip legs={daily.legs} />}
      <PnlBars rows={rows} />
      <div className="tw max-h-[24rem] overflow-y-auto">
        <table className="tbl tbl-sticky">
          <thead>
            <tr>
              <th>{t('trader.dailyCol.day')}</th>
              <th className="r">{t('trader.dailyCol.start')}</th>
              <th className="r">{t('trader.dailyCol.end')}</th>
              <th className="r">{t('trader.dailyCol.pnl')}</th>
              <th className="r">{t('trader.dailyCol.pct')}</th>
              <th className="r">{t('trader.dailyCol.total')}</th>
              {admin && (
                <>
                  {TRADE_LEGS.map(leg => <th key={leg} className="r">{t(`trader.leg.${leg}`)}</th>)}
                  <th className="r">{t('trader.dailyCol.model')}</th>
                  <th className="r">{t('trader.dailyCol.residual')}</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {[...rows].reverse().map(r => (
              <tr key={r.day}>
                <td className="num whitespace-nowrap">
                  {r.day}{r.live && <Badge tone="info" pulse className="ml-2">{t('trader.ongoing')}</Badge>}
                </td>
                <td className="r num">{money(r.start)}</td>
                <td className="r num">{money(r.end)}</td>
                <td className={cx('r num font-semibold', tone(r.pnl))}>{signedMoney(r.pnl)}</td>
                <td className={cx('r num', tone(r.pnl))}>{signedPct(r.pct, 2)}</td>
                <td className={cx('r num', tone(r.end - daily.initialBalance))}>
                  {signedPct(r.end / daily.initialBalance - 1, 2)}
                </td>
                {admin && (
                  <>
                    {TRADE_LEGS.map(leg => (
                      <td key={leg} className={cx('r num', tone(r.attrib?.legs[leg].net))}>{signedMoney(r.attrib?.legs[leg].net)}</td>
                    ))}
                    <td className={cx('r num', tone(r.attrib?.model))}>{signedMoney(r.attrib?.model)}</td>
                    <td className={cx('r num', tone(r.attrib?.residual))}>{signedMoney(r.attrib?.residual)}</td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** 两条腿前向到现在：累计收益、回撤对止损线；碰过线的整块标红 */
function LegsStrip({ legs }: { legs: Record<TradeLeg, LegSummary> }) {
  const { t } = useTranslation();
  return (
    <div className="grid gap-x-10 gap-y-5 md:grid-cols-2">
      {TRADE_LEGS.map(leg => {
        const s = legs[leg];
        return (
          <div key={leg} className={cx('space-y-2 border-t pt-3', s.hit ? 'border-loss' : 'border-fg')}>
            <div className="flex items-center gap-2 text-[13px] font-extrabold">
              <span>{t(`trader.leg.${leg}`)}<Tip text={t(`trader.legTip.${leg}`)} /></span>
              <span className="text-[12px] font-normal text-muted num">{t('trader.legDays', { n: s.days })}</span>
              {s.hit && <Badge tone="loss" icon={TriangleAlert} className="ml-auto">{t('trader.legHit')}</Badge>}
            </div>
            <Strip cols={3}>
              <Stat label={t('trader.legCum')} tone={tone(s.cumReturn)}>{signedPct(s.cumReturn, 2)}</Stat>
              <Stat label={t('trader.legDrawdown')} hint={t('trader.legStop', { stop: pct(s.stop, 1) })}
                    tone={s.hit ? 'text-loss' : undefined}>{pct(s.drawdown, 2)}</Stat>
              <Stat label={t('trader.legMaxDrawdown')} tone={s.hit ? 'text-loss' : undefined}>{pct(s.maxDrawdown, 2)}</Stat>
            </Strip>
          </div>
        );
      })}
    </div>
  );
}

/** 一天一根，对着中线赚的朝上、亏的朝下；还没完的今天淡一点，鼠标指上去变橙、右上角读数 */
function PnlBars({ rows }: { rows: DayRow[] }) {
  const { t } = useTranslation();
  const [hover, setHover] = useState<string | null>(null);
  const h = rows.find(r => r.day === hover);
  const max = Math.max(...rows.map(r => Math.abs(r.pnl))) || 1;
  return (
    <div>
      <div className="flex items-baseline justify-between text-[12.5px] text-muted">
        <span>{t('trader.dailyBars')}</span>
        <span className={cx('num font-bold', h ? tone(h.pnl) : undefined)}>
          {h ? t('trader.dayTip', { day: h.day, pnl: signedMoney(h.pnl), pct: signedPct(h.pct, 2) }) : ''}
        </span>
      </div>
      <div className="pnl-bars mt-2" onMouseLeave={() => setHover(null)}>
        {rows.map((r, i) => (
          <div key={r.day} className="b" onMouseEnter={() => setHover(r.day)}>
            <i className={cx(r.pnl >= 0 ? 'pos' : 'neg', r.live && 'live', hover === r.day && 'on')}
               style={{ height: `${(Math.abs(r.pnl) / max) * 50}%`, transitionDelay: `${i * 8}ms, 0ms` }} />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex justify-between text-[11px] text-muted num">
        <span>{rows[0].day}</span>
        <span>{rows[rows.length - 1].day}</span>
      </div>
    </div>
  );
}

// ========== 持仓 ==========

/** 两条腿各占多少的那两列只给管理员 */
function PositionsCard({ positions, admin }: { positions: { items: TraderPosition[]; day: string | null } | null; admin: boolean }) {
  const { t } = useTranslation();
  const items = useMemo(() => [...(positions?.items ?? [])].sort((a, b) => b.notional - a.notional), [positions]);
  return (
    <Card title={t('trader.positions')}
          hint={admin && positions?.day ? t('trader.positionsHint', { day: positions.day }) : undefined}>
      {!positions ? <Spinner /> : items.length === 0 ? <Empty>{t('trader.positionsEmpty')}</Empty> : (
        <div className="tw max-h-[32rem] overflow-y-auto">
          <table className="tbl tbl-sticky">
            <thead>
              <tr>
                <th>{t('trader.col.symbol')}</th>
                <th>{t('trader.col.side')}</th>
                <th className="r">{t('trader.col.quantity')}</th>
                <th className="r">{t('trader.col.notional')}</th>
                {admin && TRADE_LEGS.map(leg => <th key={leg} className="r">{t(`trader.leg.${leg}`)}</th>)}
                <th className="r">{t('trader.col.mark')}</th>
                <th className="r">{t('trader.col.pnl')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map(p => (
                <tr key={`${p.symbol}-${p.side}`}>
                  <td className="num font-mono text-[12.5px] font-bold">{p.symbol}</td>
                  <td><SideBadge side={p.side} /></td>
                  <td className="r num">{p.quantity}</td>
                  <td className="r num">{money(p.notional)}</td>
                  {admin && TRADE_LEGS.map(leg => <td key={leg} className="r num">{money(p.legs?.[leg])}</td>)}
                  <td className="r num" title={`${t('trader.col.entry')} ${p.entryPrice}`}>{p.markPrice}</td>
                  <td className={cx('r num font-semibold', tone(p.unrealizedPnl))}>{money(p.unrealizedPnl)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

// ========== 仓位规模 ==========

/** 杠杆和保证金比例：上面改设定，底下是按最近一次调仓的篮子实际按几倍下 */
function SizingCard({ sizing, onSaved }: { sizing: TraderSizing | null; onSaved: (s: TraderSizing) => void }) {
  const { t } = useTranslation();
  const p = sizing?.preview;
  return (
    <Card title={t('trader.sizing.title')} hint={t('trader.sizing.hint')} more={t('trader.sizing.more')}>
      {!sizing ? <Spinner /> : (
        <div className="space-y-3">
          {/* 设定变了换一个表单，输入框从新的值起 */}
          <SizingForm key={`${sizing.leverage}/${sizing.margin}`} sizing={sizing} onSaved={onSaved} />
          {sizing.previewError ? (
            <p className="flex items-start gap-1.5 text-[12px] text-warn break-words">
              <TriangleAlert className="w-3 h-3 shrink-0 mt-0.5" />{t('trader.sizing.previewError', { msg: sizing.previewError })}
            </p>
          ) : !p ? <p className="text-[12px] text-muted">{t('trader.sizing.noPreview')}</p> : (
            <div className="space-y-1 text-[12px]">
              <p>{t('trader.sizing.preview', { day: p.day, leverage: p.leverage, side: +p.multiple.toFixed(2) })}</p>
              {p.caps.bracket != null && (
                <p className="text-muted">{t('trader.sizing.caps', { bracket: p.caps.bracket, stress: p.caps.stress })}</p>
              )}
              {p.leverage < sizing.leverage && (
                <p className="flex items-center gap-1 text-warn">
                  <TriangleAlert className="w-3 h-3" />{t('trader.sizing.capped', { set: sizing.leverage, leverage: p.leverage })}
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </Card>
  );
}

function SizingForm({ sizing, onSaved }: { sizing: TraderSizing; onSaved: (s: TraderSizing) => void }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [leverage, setLeverage] = useState(String(sizing.leverage));
  // 保证金比例的输入框里是百分数
  const [margin, setMargin] = useState(String(Math.round(sizing.margin * 1000) / 10));
  const [busy, setBusy] = useState(false);
  const lev = Number(leverage);
  const m = Number(margin) / 100;
  const tooMuch = m > sizing.maxMargin;
  const valid = Number.isInteger(lev) && lev >= 1 && m > 0 && !tooMuch;

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      onSaved(await alpha.setTraderSizing({ leverage: lev, margin: m }));
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={e => void save(e)} className="space-y-2 border-t border-fg pt-3">
      <div className="flex flex-wrap items-center gap-2 text-[12.5px]">
        <span className="text-muted">{t('trader.sizing.leverage')}</span>
        <input type="number" min={1} step={1} value={leverage} onChange={e => setLeverage(e.target.value)} disabled={busy}
               className="input w-20 text-right font-mono" />
        <span className="mr-3 text-muted">{t('trader.sizing.times')}</span>
        <span className="text-muted">{t('trader.sizing.margin')}</span>
        <input type="number" min={1} step={1} value={margin} onChange={e => setMargin(e.target.value)} disabled={busy}
               className="input w-20 text-right font-mono" />
        <span className="text-muted">%</span>
        <Button type="submit" icon={Save} loading={busy}
                disabled={!valid || (lev === sizing.leverage && m === sizing.margin)}>
          {t('trader.sizing.save')}
        </Button>
      </div>
      {tooMuch ? (
        <p className="flex items-center gap-1 text-[12px] text-warn">
          <TriangleAlert className="w-3 h-3" />{t('trader.sizing.maxMargin', { max: +(sizing.maxMargin * 100).toFixed(1) })}
        </p>
      ) : valid && (
        <p className="text-[12px] text-muted">
          {t('trader.sizing.effect', { margin: +(m * 100).toFixed(1), leverage: lev, side: +(m * lev / 2).toFixed(2) })}
        </p>
      )}
    </form>
  );
}

// ========== 交易因子：勾选与对比 ==========

/** blocked 里看改份额、对比现在做不做得了 */
function FactorsCard({ factors, carry, compare, blocked, onChanged, onList, onCarry }: {
  factors: TraderFactor[] | null;
  carry: TraderCarry | null;
  compare: CompareJob | null;
  blocked: Busy | null;
  onChanged: () => Promise<unknown>;
  onList: (list: TraderFactor[]) => void;
  onCarry: (c: TraderCarry) => void;
}) {
  const { t } = useTranslation();
  const fail = useFail();
  const [busy, setBusy] = useState<string | null>(null);
  const running = compare?.running ?? false;

  const toggle = async (f: TraderFactor) => {
    setBusy(f.id);
    try {
      const r = await alpha.selectTraderFactor(f.id, !f.selected);
      onList((factors ?? []).map(x => (x.id === f.id ? { ...x, selected: r.selected } : x)));
    } catch (err) {
      fail(err);
    } finally {
      setBusy(null);
    }
  };

  const toggleCarry = async () => {
    if (!carry) return;
    setBusy('carry');
    try {
      onCarry(await alpha.setTraderCarry({ enabled: !carry.enabled }));
    } catch (err) {
      fail(err);
    } finally {
      setBusy(null);
    }
  };

  const saveShare = async (share: number) => {
    setBusy('share');
    try {
      onCarry(await alpha.setTraderCarry({ share }));
    } catch (err) {
      fail(err);
    } finally {
      setBusy(null);
    }
  };

  const start = async (f: TraderFactor) => {
    setBusy(f.id);
    try {
      await alpha.compareTraderFactor(f.id);
      await onChanged();
    } catch (err) {
      fail(err);
    } finally {
      setBusy(null);
    }
  };

  return (
    <Card title={t('trader.factors')} hint={t('trader.factorsHint')} more={t('trader.factorsMore')}>
      {!factors || !carry ? <Spinner /> : (
        <div className="space-y-5">
          <ul className="border-t border-fg divide-y divide-line border-b border-b-line">
            <li className="flex items-start gap-3 py-3">
              <button onClick={() => void toggleCarry()} disabled={busy !== null}
                      title={carry.enabled ? t('trader.selected') : t('trader.unselected')}
                      className="mt-0.5 inline-flex cursor-pointer text-primary disabled:cursor-not-allowed disabled:opacity-40">
                {carry.enabled ? <SquareCheck className="w-4 h-4" /> : <Square className="w-4 h-4" />}
              </button>
              <div className="min-w-0 flex-1 space-y-0.5">
                <div className="font-mono text-[13px] font-bold">{t('trader.leg.carry')}</div>
                <p className="text-[12px] text-muted">{t('trader.carryExpr')}</p>
                {/* 份额变了换一个输入框，从新的值起 */}
                <CarryShare key={carry.share} share={carry.share} busy={busy} blocked={blocked?.carryShare}
                            onSave={v => void saveShare(v)} />
                {!carry.enabled && (
                  <p className="flex items-center gap-1 text-[12px] text-warn">
                    <TriangleAlert className="w-3 h-3" />{t('trader.carryOff')}
                  </p>
                )}
              </div>
            </li>
            {factors.length === 0 && <li className="py-3"><Empty>{t('trader.factorsEmpty')}</Empty></li>}
            {factors.map(f => (
              <li key={f.id} className="flex items-start gap-3 py-3">
                <button onClick={() => void toggle(f)} disabled={busy !== null || (!f.selected && f.missing.length > 0)}
                        title={f.selected ? t('trader.selected') : t('trader.unselected')}
                        className="mt-0.5 inline-flex cursor-pointer text-primary disabled:cursor-not-allowed disabled:opacity-40">
                  {f.selected ? <SquareCheck className="w-4 h-4" /> : <Square className="w-4 h-4" />}
                </button>
                <div className="min-w-0 flex-1 space-y-0.5">
                  <div className="font-mono text-[13px] font-bold">{f.name}</div>
                  <code className="block truncate text-[12px] text-muted" title={f.expr}>{f.expr}</code>
                  {f.missing.length > 0 && (
                    <p className="flex items-center gap-1 text-[12px] text-warn">
                      <TriangleAlert className="w-3 h-3" />
                      {t('trader.missing', { cols: f.missing.map(c => `$${c}`).join(', ') })}
                    </p>
                  )}
                </div>
                <Button icon={GitCompare} loading={busy === f.id || (running && compare?.factorId === f.id)}
                        disabled={busy !== null || running || !!blocked?.compare} onClick={() => void start(f)}>
                  {t('trader.compare')}
                </Button>
              </li>
            ))}
          </ul>
          {!running && <Waiting why={blocked?.compare} />}
          {compare && <CompareView job={compare} />}
        </div>
      )}
    </Card>
  );
}

/** carry 份额的输入和保存；busy 是卡片里正在做的动作，blocked 是现在改不了的原因 */
function CarryShare({ share, busy, blocked, onSave }: {
  share: number;
  busy: string | null;
  blocked: string | null | undefined;
  onSave: (share: number) => void;
}) {
  const { t } = useTranslation();
  // 输入框里是百分数
  const [value, setValue] = useState(String(Math.round(share * 1000) / 10));
  return (
    <>
      <div className="flex flex-wrap items-center gap-2 text-[12px]">
        <span className="text-muted">{t('trader.carryShare')}</span>
        <input type="number" min={0} max={100} step={0.5} value={value} onChange={e => setValue(e.target.value)}
               disabled={busy !== null} className="input w-20 text-right font-mono" />
        <span className="text-muted">%</span>
        <Button onClick={() => onSave(Number(value) / 100)} loading={busy === 'share'}
                disabled={busy !== null || !!blocked || value === '' || Number(value) / 100 === share}>
          {t('trader.carryShareSave')}
        </Button>
        <span className="text-muted">{t('trader.carryShareHint')}</span>
      </div>
      <Waiting why={blocked} />
    </>
  );
}

function CompareView({ job }: { job: CompareJob }) {
  const { t } = useTranslation();
  if (!job.startedAt) return null;
  if (job.running) {
    return (
      <p className="flex items-center gap-2 text-[12.5px] text-muted">
        <Spinner />{t('trader.comparing', { name: job.name, stage: t(`trader.compareStage.${job.stage ?? 'library'}`) })}
      </p>
    );
  }
  if (job.error) {
    return (
      <p className="flex items-start gap-1.5 text-[13px] text-loss break-words">
        <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('trader.compareError', { msg: job.error })}
      </p>
    );
  }
  const r = job.result;
  if (!r) return null;
  const action = t(`trader.compareAction.${r.action}`);
  // "Sharpe（xxxx 起）"那一行从哪一年起
  const from = String(new Date(r.base.search.recentFrom).getUTCFullYear());
  const columns = [
    { label: `${t('trader.compareBase')} · ${t('trader.search')}`, v: r.base.search },
    { label: `${t('trader.compareAlt', { action })} · ${t('trader.search')}`, v: r.alt.search },
    ...(r.base.holdout && r.alt.holdout ? [
      { label: `${t('trader.compareBase')} · ${t('trader.holdout')}`, v: r.base.holdout },
      { label: `${t('trader.compareAlt', { action })} · ${t('trader.holdout')}`, v: r.alt.holdout },
    ] : []),
  ];
  return (
    <section className="space-y-3 border-t border-fg pt-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[14px] font-extrabold">{t('trader.compareTitle', { action, name: job.name })}</span>
        <span className="ml-auto text-[12px] text-muted num">{t('trader.compareAt', { time: localTime(job.finishedAt) })}</span>
      </div>
      <p className="text-[12.5px] text-muted">
        {t('trader.compareSets', {
          base: r.base.factors.join(', ') || t('trader.noneFactors'), action,
          alt: r.alt.factors.join(', ') || t('trader.noneFactors'),
        })}
      </p>
      <div className="overflow-x-auto">
        <table className="tbl">
          <thead>
            <tr>
              <th>{t('brief.metric')}</th>
              {columns.map(c => <th key={c.label} className="r">{c.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {METRICS.map(k => (
              <tr key={k}>
                <td className="text-muted">{t(`trader.metric.${k}`, { from })}</td>
                {columns.map(c => <td key={c.label} className="r num">{schemeValue(k, c.v[k])}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className={cx('text-[12.5px]', r.better ? 'text-gain' : 'text-muted')}>{t(r.better ? 'trader.betterYes' : 'trader.betterNo')}</p>
    </section>
  );
}

function schemeValue(k: keyof SchemeResult, v: number | null): string {
  if (k === 'netReturn') return signedPct(v, 1);
  if (k === 'maxDrawdown') return pct(v, 1);
  if (k === 'carryFundingBps') return v == null ? DASH : `${num(v, 2)} bps`;
  if (k === 'periods') return v == null ? DASH : String(v);
  return num(v, k === 'sharpe' || k === 'recentSharpe' ? 2 : 3);
}

// ========== 调仓记录 ==========

function RunsCard({ runs }: { runs: TradeRunBrief[] | null }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [open, setOpen] = useState<string | null>(null);
  const [details, setDetails] = useState<Record<string, TradeRunDetail>>({});

  const toggle = (day: string) => {
    setOpen(o => (o === day ? null : day));
    if (!details[day]) {
      alpha.traderRun(day).then(d => setDetails(all => ({ ...all, [day]: d }))).catch(fail);
    }
  };

  return (
    <Card title={t('trader.runs')} hint={t('trader.runsHint')}>
      {!runs ? <Spinner /> : runs.length === 0 ? <Empty>{t('trader.runsEmpty')}</Empty> : (
        <div className="tw">
          <table className="tbl">
            <thead>
              <tr>
                <th className="w-6" />
                <th>{t('trader.runCol.day')}</th>
                <th>{t('trader.runCol.status')}</th>
                <th>{t('trader.runCol.factors')}</th>
                <th className="r">{t('trader.runCol.equity')}</th>
                <th className="r">{t('trader.runCol.orders')}</th>
              </tr>
            </thead>
            <tbody>
              {runs.map(r => (
                <Fragment key={r.day}>
                  <tr onClick={() => toggle(r.day)} className="cursor-pointer">
                    <td>{open === r.day ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}</td>
                    <td className="num whitespace-nowrap">{r.day}</td>
                    <td><StatusBadge status={r.status} /></td>
                    <td className="text-muted">{r.factorNames.join(', ') || DASH}</td>
                    <td className="r num whitespace-nowrap">{money(r.equityBefore)} → {money(r.equityAfter)}</td>
                    <td className="r num whitespace-nowrap">
                      {t('trader.orderCount', { n: r.orderCount })}
                      {r.failedCount > 0 && <span className="ml-1.5 text-loss">{t('trader.orderFailed', { n: r.failedCount })}</span>}
                    </td>
                  </tr>
                  {open === r.day && (
                    <tr>
                      <td colSpan={6} className="expand">
                        {details[r.day] ? <RunDetail run={details[r.day]} /> : <Spinner />}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function RunDetail({ run }: { run: TradeRunDetail }) {
  const { t } = useTranslation();
  const coins = run.targets?.coins ?? [];
  const sized = run.targets?.sizing;
  return (
    <div className="space-y-5">
      <p className="text-[12px] text-muted num">
        {t('trader.signalAt', { time: utcMinute(run.signalMs + 1) })} · {t('trader.ranAt', {
          start: localTime(run.startedAt), end: localTime(run.finishedAt) })}
      </p>
      {run.error && (
        <p className="flex items-start gap-1.5 text-[13px] text-loss break-words">
          <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{run.error}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-1.5 text-[12px]">
        <span className="mr-1 text-muted">{t('trader.usedFactors')}</span>
        {run.factors.length === 0 ? <span className="text-muted">{t('trader.noFactors')}</span>
          : run.factors.map(f => (
            <span key={f.id} title={f.expr}><Badge>{f.name} {num(f.weight, 4)}</Badge></span>
          ))}
        {run.targets?.carry === false && <Badge tone="warn" icon={TriangleAlert}>{t('trader.carryOffBadge')}</Badge>}
        {run.targets?.carryShare != null && <Badge>{t('trader.carryShareBadge', { pct: pct(run.targets.carryShare, 0) })}</Badge>}
        {sized && (
          <Badge>{t('trader.sizingBadge', { leverage: sized.leverage, margin: pct(sized.margin, 0), side: +sized.multiple.toFixed(2) })}</Badge>
        )}
        {sized && sized.leverage < sized.setting.leverage && (
          <Badge tone="warn" icon={TriangleAlert}>
            {t('trader.sizing.capped', { set: sized.setting.leverage, leverage: sized.leverage })}
          </Badge>
        )}
      </div>
      {run.attrib && <AttribView attrib={run.attrib} />}
      {run.legs && TRADE_LEGS.map(leg => (
        <div key={leg} className="flex flex-wrap items-center gap-1.5 text-[12px]">
          <span className="mr-1 w-16 text-muted">{t(`trader.leg.${leg}`)}</span>
          {run.legs![leg].long.map(s => <Badge key={`l-${s}`} tone="gain">{s}</Badge>)}
          {run.legs![leg].short.map(s => <Badge key={`s-${s}`} tone="loss">{s}</Badge>)}
        </div>
      ))}
      {run.targets && run.targets.notTrading.length > 0 && (
        <p className="text-[12px] text-muted">{t('trader.notTrading')}{run.targets.notTrading.join(', ')}</p>
      )}
      {coins.length > 0 && (
        <section className="space-y-2">
          <div className="microlabel">{t('trader.targets')}</div>
          <div className="max-h-[28rem] overflow-auto">
            <table className="tbl tbl-sticky">
              <thead>
                <tr>
                  <th>{t('trader.col.symbol')}</th>
                  <th className="r">{t('trader.coinCol.weight')}</th>
                  {TRADE_LEGS.map(leg => <th key={leg} className="r">{t(`trader.leg.${leg}`)}</th>)}
                  <th className="r">{t('trader.coinCol.notional')}</th>
                  <th className="r">{t('trader.coinCol.current')}</th>
                  <th className="r">{t('trader.coinCol.target')}</th>
                  <th className="r">{t('trader.coinCol.price')}</th>
                  <th className="r">{t('trader.coinCol.ref')}</th>
                  <th>{t('trader.coinCol.note')}</th>
                </tr>
              </thead>
              <tbody>
                {coins.map(c => (
                  <tr key={c.symbol}>
                    <td className="num font-mono text-[12.5px] font-bold">{c.symbol}</td>
                    <td className="r num">{signedPct(c.weight, 2)}</td>
                    {TRADE_LEGS.map(leg => <td key={leg} className="r num">{signedPct(c[leg], 2)}</td>)}
                    <td className="r num">{money(c.notional)}</td>
                    <td className="r num">{c.current}</td>
                    <td className="r num">{c.target ?? DASH}</td>
                    <td className="r num">{c.price ?? DASH}</td>
                    <td className="r num">{c.refPrice ?? DASH}</td>
                    <td className={cx('text-[12px]', c.failed ? 'text-loss' : 'text-muted')}>{c.note ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      <section className="space-y-2">
        <div className="microlabel">{t('trader.orders')}</div>
        {run.orders.length === 0 ? <Empty>{t('trader.ordersEmpty')}</Empty> : (
          <div className="max-h-[28rem] overflow-auto">
            <table className="tbl tbl-sticky">
              <thead>
                <tr>
                  <th>{t('trader.col.symbol')}</th>
                  <th>{t('trader.orderCol.action')}</th>
                  <th className="r">{t('trader.orderCol.quantity')}</th>
                  <th className="r">{t('trader.orderCol.fill')}</th>
                  <th>{t('trader.orderCol.result')}</th>
                </tr>
              </thead>
              <tbody>
                {run.orders.map(o => (
                  <tr key={`${o.symbol}-${o.action}-${o.side}`}>
                    <td className="num font-mono text-[12.5px] font-bold">{o.symbol}</td>
                    <td className="whitespace-nowrap">
                      <span className="mr-1.5">{t(`trader.action.${o.action}`)}</span>{o.side && <SideBadge side={o.side} />}
                    </td>
                    <td className="r num">
                      {o.action === 'leverage' ? t('trader.toLeverage', { n: o.leverage }) : o.quantity ?? t('trader.closeAll')}
                    </td>
                    <td className="r num">{o.price ?? DASH}</td>
                    <td className="text-[12px]">
                      {o.ok ? (
                        <Badge tone="gain" icon={CircleCheck}>
                          {o.action === 'leverage' ? t('trader.adjusted') : `${t('trader.filled')} ${o.filled}`}
                        </Badge>
                      ) : <span className="text-loss break-words">{o.error}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {run.targets && (
        <details className="group border-t border-line">
          <summary className="flex items-center gap-1.5 py-2.5 text-[13px] text-muted cursor-pointer select-none transition-colors hover:text-fg">
            {t('trader.signals')}
            <ChevronDown className="w-3.5 h-3.5 ml-auto transition-transform group-open:rotate-180" />
          </summary>
          <div className="grid gap-x-8 gap-y-5 pt-2 lg:grid-cols-2">
            {TRADE_LEGS.map(leg => (
              <div key={leg} className="space-y-2">
                <div className="microlabel">{t(`trader.leg.${leg}`)}</div>
                <div className="max-h-[24rem] overflow-auto">
                  <table className="tbl tbl-sticky">
                    <thead>
                      <tr>
                        <th>{t('trader.col.symbol')}</th>
                        <th className="r">{t('trader.signalCol.rank')}</th>
                        <th className="r">{t('trader.signalCol.signal')}</th>
                        <th className="r">{t('trader.signalCol.vol')}</th>
                        <th className="r">{t('trader.signalCol.weight')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {[...run.targets!.signals[leg]].sort((a, b) => a.rank - b.rank).map(c => (
                        <tr key={c.symbol}>
                          <td className="num font-mono text-[12.5px]">{c.symbol}</td>
                          <td className="r num">{c.rank}</td>
                          <td className="r num">{num(c.signal, 5)}</td>
                          <td className="r num">{num(c.vol, 5)}</td>
                          <td className={cx('r num', tone(c.weight))}>{c.weight ? signedPct(c.weight, 2) : ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

/** 一天的按腿归因：实际 vs 回测应得，两腿各多少，每币明细按盈亏绝对值排（单币事件一眼看到） */
function AttribView({ attrib }: { attrib: DayAttrib }) {
  const { t } = useTranslation();
  const coins = useMemo(() => [...(attrib.coins ?? [])]
    .sort((a, b) => Math.abs(b.legs.combo + b.legs.carry) - Math.abs(a.legs.combo + a.legs.carry)), [attrib]);
  return (
    <section className="space-y-2">
      <div className="microlabel">{t('trader.attrib', { until: attrib.until })}</div>
      <div className="flex flex-wrap items-center gap-1.5 text-[12.5px] num">
        <span className={cx('font-semibold', tone(attrib.actual))}>{t('trader.attribActual')} {signedMoney(attrib.actual)}</span>
        <span className="text-muted">·</span>
        <span className={tone(attrib.model)}>{t('trader.attribModel')} {signedMoney(attrib.model)}</span>
        <span className="text-muted">·</span>
        <span className={tone(attrib.residual)}>{t('trader.attribResidual')} {signedMoney(attrib.residual)}</span>
        {TRADE_LEGS.map(leg => (
          <Badge key={leg} tone={attrib.legs[leg].net >= 0 ? 'gain' : 'loss'} className="ml-1">
            {t(`trader.leg.${leg}`)} {signedMoney(attrib.legs[leg].net)}
          </Badge>
        ))}
      </div>
      {attrib.missing.length > 0 && (
        <p className="flex items-center gap-1 text-[12px] text-warn">
          <TriangleAlert className="w-3 h-3" />{t('trader.attribMissing', { coins: attrib.missing.join(', ') })}
        </p>
      )}
      {coins.length > 0 && (
        <div className="max-h-[28rem] overflow-auto">
          <table className="tbl tbl-sticky">
            <thead>
              <tr>
                <th>{t('trader.col.symbol')}</th>
                {TRADE_LEGS.map(leg => <th key={leg} className="r">{t(`trader.leg.${leg}`)}</th>)}
                <th className="r">{t('trader.attribCol.entry')}</th>
                <th className="r">{t('trader.attribCol.exit')}</th>
                <th className="r">{t('trader.attribCol.ret')}</th>
                <th className="r">{t('trader.attribCol.fund')}</th>
                {TRADE_LEGS.map(leg => <th key={leg} className="r">{t('trader.attribCol.pnl', { leg: t(`trader.leg.${leg}`) })}</th>)}
              </tr>
            </thead>
            <tbody>
              {coins.map(c => (
                <tr key={c.symbol}>
                  <td className="num font-mono text-[12.5px] font-bold">{c.symbol}</td>
                  {TRADE_LEGS.map(leg => <td key={leg} className="r num">{c[leg] ? signedPct(c[leg], 2) : ''}</td>)}
                  <td className="r num">{c.entry}</td>
                  <td className="r num">{c.exit}</td>
                  <td className={cx('r num', tone(c.ret))}>{signedPct(c.ret, 2)}</td>
                  <td className="r num">{signedPct(c.fund, 3)}</td>
                  {TRADE_LEGS.map(leg => (
                    <td key={leg} className={cx('r num', tone(c.legs[leg]))}>{c[leg] ? signedMoney(c.legs[leg]) : ''}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ========== 小件 ==========

function StatusBadge({ status }: { status: TradeStatus }) {
  const { t } = useTranslation();
  return <Badge tone={STATUS_TONE[status]} icon={STATUS_ICON[status]}>{t(`trader.status.${status}`)}</Badge>;
}

function SideBadge({ side }: { side: 'LONG' | 'SHORT' }) {
  const { t } = useTranslation();
  return <Badge tone={side === 'LONG' ? 'gain' : 'loss'}>{t(`trader.side.${side}`)}</Badge>;
}

/** 正负着色：赚是 gain、亏是 loss */
function tone(v: number | null | undefined): string | undefined {
  if (v == null || v === 0) return undefined;
  return v > 0 ? 'text-gain' : 'text-loss';
}
