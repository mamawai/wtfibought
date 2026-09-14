import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { buffApi, cryptoOrderApi, futuresApi, userApi } from '../api';
import { HomeMarketSection } from '../components/HomeMarketSection';
import { HomeTraderBlock } from '../components/HomeTraderBlock';
import { HomeMonthGrid } from '../components/HomeMonthGrid';
import { DailyBuffModal } from '../components/DailyBuffCard';
import { LatestTradesCard } from '../components/LatestTradesCard';
import type { TradeItem } from '../components/LatestTradesCard';
import { ForceOrdersCard } from '../components/ForceOrdersCard';
import { NewsFlashCard } from '../components/NewsFlashCard';
import { EconCalendarCard } from '../components/EconCalendarCard';
import { WhaleSummaryCard } from '../components/WhaleSummaryCard';
import { HomeFaq } from '../components/HomeFaq';
import { LoginPrompt } from '../components/LoginPrompt';
import { Sparkline } from '../components/fx/Sparkline';
import { DayDetailModal } from '../components/DayDetailModal';
import { useCountUp } from '../hooks/useCountUp';
import { useStagger } from '../hooks/useStagger';
import { Gamepad2, List, DollarSign, Target, Settings2, Gift, Swords } from 'lucide-react';
import type { BuffStatus, AssetSnapshot, User } from '../types';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDate, fmtNum, fmtSignedPct, fmtSignedUsd } from '../lib/utils';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
const NOTICE_SEEN_KEY = 'wiib-notice-seen';
// seen = 点过「我知道了」，永久不再自动跳；hide-date 只压当天，明天还提醒一次
function shouldShowNotice() {
  if (localStorage.getItem(NOTICE_SEEN_KEY)) return false;
  const d = localStorage.getItem(HIDE_NOTICE_KEY);
  return !d || d !== new Date().toDateString();
}

/** 入口一排的前六格；第七格是福利，点开弹窗不跳路由，单独渲染 */
const ENTRIES = [
  { icon: List, k: 'stocks', to: '/bstock' },
  { icon: DollarSign, k: 'crypto', to: '/coin' },
  { icon: Target, k: 'prediction', to: '/prediction' },
  { icon: Settings2, k: 'ai', to: '/ai' },
  { icon: Swords, k: 'arena', to: '/arena' },
  { icon: Gamepad2, k: 'games', to: '/games' },
];

/** 格间细线：手机两列、md 四列、xl 七列，各自把每行头一格的左线和左内边距去掉 */
const entryCls = (i: number) => cn(
  'group flex items-baseline gap-2 min-w-0 px-4 py-2 border-l border-border cursor-pointer text-left',
  i % 2 === 0 && 'pl-0 border-l-0',
  i % 4 === 0 ? 'md:pl-0 md:border-l-0' : 'md:pl-4 md:border-l',
  i === 0 ? 'xl:pl-0 xl:border-l-0' : 'xl:pl-4 xl:border-l',
);

const ENTRY_NAME = 'inline-flex items-center gap-[7px] text-[16px] font-bold [font-stretch:90%] tracking-[-0.01em] whitespace-nowrap';
const ENTRY_IC = 'w-[15px] h-[15px] text-muted-foreground transition-colors group-hover:text-primary';
const ENTRY_DESC = 'min-w-0 text-[12px] text-muted-foreground whitespace-nowrap overflow-hidden text-ellipsis';

/** 按时段挑问候语，返回的是词表 key：这里直接查词表会把文案定死在模块加载那一刻 */
function greetingKey(): string {
  const h = new Date().getHours();
  if (h < 5) return 'greeting.lateNight';
  if (h < 11) return 'greeting.morning';
  if (h < 13) return 'greeting.noon';
  if (h < 18) return 'greeting.afternoon';
  return 'greeting.evening';
}

/**
 * 开屏左半：总资产大数 + 四个小数 + 净值线。
 * 拆成组件是为了 useCountUp——五个数得等 user 到了一起挂载才滚得起来。
 */
function HeroMain({ user, history, realtime }: { user: User; history: AssetSnapshot[]; realtime: AssetSnapshot | null }) {
  const { t, i18n } = useTranslation('home');
  const totalRef = useCountUp<HTMLSpanElement>(user.totalAssets, v => fmtNum(v));
  const pctRef = useCountUp<HTMLDivElement>(user.profitPct, fmtSignedPct);
  const balanceRef = useCountUp<HTMLDivElement>(user.balance, v => `$${fmtNum(v)}`);
  const pnlRef = useCountUp<HTMLDivElement>(user.profit, fmtSignedUsd);
  const mvRef = useCountUp<HTMLDivElement>(user.positionMarketValue, v => `$${fmtNum(v)}`);

  // 曲线尾端接上实时值，让"最新一格"跟着盘面动
  const curve = history.length
    ? [...history.map(h => h.totalAssets), ...(realtime ? [realtime.totalAssets] : [])]
    : [];
  const start = user.totalAssets - user.profit;   // 起始资金：曲线按它分绿红两段
  const lang = i18n.resolvedLanguage ?? i18n.language;
  const axDay = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString(lang, { month: 'long', day: 'numeric' });

  const cell = (i: number) => cn('stat', i === 0 ? 'md:pr-[22px]' : 'md:px-[22px] md:border-l md:border-border');

  return (
    <div className="xl:col-span-6 flex flex-col">
      <p className="m-0 text-[13px] text-muted-foreground">{t('hero.totalAssets')}</p>
      <h1 className="num cond mt-0.5 text-[clamp(56px,5.4vw,96px)] font-bold leading-[0.96]">
        <span className="text-[0.36em] font-medium text-muted-foreground align-[1em] mr-[0.06em] [font-stretch:90%] tracking-normal">$</span>
        <span ref={totalRef} />
      </h1>

      <div className="num flex flex-wrap gap-4 mt-3.5 md:flex-nowrap md:gap-0">
        <div className={cell(0)}>
          <div ref={pctRef} className={cn('v text-[22px]', user.profitPct >= 0 ? 'up' : 'dn')} />
          <div className="k text-[12px] mt-[3px]">{t('hero.profitPct')}</div>
        </div>
        <div className={cell(1)}>
          <div ref={balanceRef} className="v text-[22px]" />
          <div className="k text-[12px] mt-[3px]">{t('hero.balance')}</div>
        </div>
        <div className={cell(2)}>
          <div ref={pnlRef} className={cn('v text-[22px]', user.profit >= 0 ? 'up' : 'dn')} />
          <div className="k text-[12px] mt-[3px]">{t('hero.totalPnl')}</div>
        </div>
        <div className={cell(3)}>
          <div ref={mvRef} className="v text-[22px]" />
          <div className="k text-[12px] mt-[3px]">{t('hero.positionValue')}</div>
        </div>
      </div>

      {/* 净值线：起始资金画虚线，线在它之上绿、之下红；两端挂首末日期 */}
      <div className="relative h-[122px] mt-auto pt-3">
        {curve.length > 1 && (
          <Sparkline
            /* key 随数据变 → path 重建，描线动画重跑一遍 */
            key={`${curve.length}:${curve[curve.length - 1]}`}
            data={curve}
            baseline={start}
            baselineLabel={t('hero.startCapital', { amount: `$${fmtNum(start)}` })}
            dot={false}
            className="w-full h-full"
          />
        )}
        {history.length > 0 && (
          <>
            <span className="absolute left-0 -bottom-[18px] text-[11.5px] text-muted-foreground">{axDay(history[0].date)}</span>
            <span className="absolute right-0 -bottom-[18px] text-[11.5px] text-muted-foreground">{axDay(history[history.length - 1].date)}</span>
          </>
        )}
      </div>
    </div>
  );
}

export function Home() {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation('home');
  const { user, token } = useUserStore();
  // 游客没 token；有 token 但 user 为 null 是 fetchUser 还没回来。
  // 行情/成交那几块不依赖 user，先渲染出来，开屏等 user 到了再补
  const guest = !token;
  const ready = !!user;
  const [refreshNonce, setRefreshNonce] = useState(0);

  const [buffStatus, setBuffStatus] = useState<BuffStatus | null>(null);
  const [buffOpen, setBuffOpen] = useState(false);
  const [latestTrades, setLatestTrades] = useState<TradeItem[]>([]);
  // tradesLoading 由"已加载 nonce 是否追上刷新 nonce"派生
  const [tradesLoadedNonce, setTradesLoadedNonce] = useState(-1);
  const tradesLoading = tradesLoadedNonce !== refreshNonce;

  // 开屏数据：资产曲线(30d) + 实时快照 + 月度网格(逐日快照)
  const [history, setHistory] = useState<AssetSnapshot[]>([]);
  const [realtime, setRealtime] = useState<AssetSnapshot | null>(null);
  const [monthCells, setMonthCells] = useState<AssetSnapshot[]>([]);
  const [gridMonth, setGridMonth] = useState(() => fmtDate().slice(0, 7));
  const [selectedDate, setSelectedDate] = useState<string | null>(null);

  const entriesRef = useStagger<HTMLDivElement>();

  // 首访自动跳玩法说明只给登录用户；游客打开根路径就是首页，说明页从顶部那条链接自己点
  useEffect(() => { if (!guest && shouldShowNotice()) navigate('/intro', { replace: true }); }, [guest, navigate]);

  useEffect(() => {
    if (ready) buffApi.status().then(setBuffStatus).catch(() => {});
  }, [ready, refreshNonce]);

  useEffect(() => {
    if (!ready) return;
    userApi.assetHistory(30).then(setHistory).catch(() => {});
    userApi.assetRealtime().then(setRealtime).catch(() => {});
  }, [ready, refreshNonce]);

  // 网格按月拉：翻月就再问一次。旧月数据先留着不清，避免切月时整片格子闪白
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    userApi.assetDaily(gridMonth)
      .then(rows => { if (!cancelled) setMonthCells(rows); })
      .catch(() => { if (!cancelled) setMonthCells([]); });
    return () => { cancelled = true; };
  }, [ready, gridMonth, refreshNonce]);

  // 这里只装后端原始值（方向枚举、去 USDT 的符号），方向标签和展示名交给 LatestTradesCard 渲染期算：
  // 在拉数这一刻就把字烤进 state 的话，切语言后这批卡片还是老语言
  useEffect(() => {
    Promise.all([cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([co, fo]) => {
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, base: o.symbol.replace('USDT', ''), quantity: o.quantity, filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => ({ id: `f-${o.orderId}`, orderSide: o.orderSide, base: o.symbol.replace('USDT', ''), isFutures: true, quantity: o.quantity, filledAmount: o.filledAmount, createdAt: o.createdAt, isAi: o.isAiTrader === true }));
        setLatestTrades([...ci, ...fi].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 20));
      }).finally(() => setTradesLoadedNonce(refreshNonce));
  }, [refreshNonce]);

  // 切月时 monthCells 还是上个月的，先按当前月份筛一道，合计和格子才不会串月
  const monthRows = monthCells.filter(s => s.date.slice(0, 7) === gridMonth);
  const gridCells = monthRows.map(s => ({ date: s.date, pnl: s.dailyProfit }));
  const selectedSnapshot = monthRows.find(s => s.date === selectedDate) ?? null;
  // 月份名/星期名跟着界面语言走，不能钉死 zh-CN——英文界面下会漏出"8月20日星期三"。
  // 取 resolvedLanguage：language 可能是没落在支持列表里的原始值（与 lib/utils.ts 同口径）
  const dateStr = new Date().toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, { month: 'long', day: 'numeric', weekday: 'long' });

  return (
    <div className="wrap">

      {/* ====== 开屏：问候 / 总资产 / 我的 Trader / 月度盈亏 ====== */}
      {ready && (
        <section className="grid grid-cols-1 xl:grid-cols-12 gap-8 pt-[26px]">
          <div className="xl:col-span-12 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-[14px] text-muted-foreground">
            <div>
              <b className="text-[20px] font-bold text-foreground mr-3.5">
                {t('greeting.withName', { greeting: t(greetingKey()), name: user!.username })}
              </b>
              <span>{dateStr}</span>
            </div>
            {/* 玩法说明页入口 */}
            <Link to="/intro" className="hover:text-foreground transition-colors">{t('hero.howToPlay')}</Link>
          </div>

          <HeroMain user={user!} history={history} realtime={realtime} />
          <HomeTraderBlock className="xl:col-span-3" />
          <HomeMonthGrid
            className="xl:col-span-3"
            cells={gridCells}
            month={gridMonth}
            onMonthChange={setGridMonth}
            selectedDate={selectedDate ?? undefined}
            onSelectDate={setSelectedDate}
          />
        </section>
      )}

      {/* 游客顶栏：开屏那块整个不出，换一句说明 + 去登录 */}
      {guest && (
        <section className="pt-[26px] flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
          <div>
            <b className="text-[20px] font-bold text-foreground">{t('hero.guestTitle')}</b>
            <LoginPrompt text={t('hero.guestDesc')} className="mt-2" />
          </div>
          <Link to="/intro" className="text-[14px] text-muted-foreground hover:text-foreground transition-colors">{t('hero.howToPlay')}</Link>
        </section>
      )}

      {/* ====== 入口一排七格 ====== */}
      <section className="sec tight mt-8 pt-3.5 [&_.sec-h]:mb-3">
        <div className="sec-h">
          <h2>{t('entries.title')}</h2>
        </div>
        <div ref={entriesRef} className="grid grid-cols-2 md:grid-cols-4 xl:grid-cols-7">
          {ENTRIES.map(({ icon: Icon, k, to }, i) => (
            <Link key={to} to={to} className={entryCls(i)}>
              <span className={ENTRY_NAME}><Icon className={ENTRY_IC} />{t(`quick.${k}`)}</span>
              <span className={ENTRY_DESC}>{t(`quick.${k}Desc`)}</span>
            </Link>
          ))}
          {/* 游客点福利直接去登录，抽奖弹窗只在登录后挂 */}
          <button className={entryCls(6)} onClick={() => guest ? navigate('/login') : setBuffOpen(true)}>
            <span className={ENTRY_NAME}>
              <Gift className={ENTRY_IC} />
              {t('quick.buff')}
              {/* 今日未抽 → 亮一颗橙方块 */}
              {buffStatus?.canDraw && <i className="w-[7px] h-[7px] bg-primary ml-1.5" />}
            </span>
            <span className={ENTRY_DESC}>
              {guest ? t('buff.loginFirst') : buffStatus?.canDraw === false ? t('buff.drawnToday') : t('quick.buffDesc')}
            </span>
          </button>
        </div>
      </section>

      {/* ====== 市场行情：每类两只代表，点行直达交易页 ====== */}
      <section className="sec tight mt-8 pt-3.5 [&_.sec-h]:mb-3">
        <HomeMarketSection />
      </section>

      {/* ====== 快讯 + 最新成交 ====== */}
      <section className="sec mt-12">
        <div className="g12">
          <div className="col-span-12 xl:col-span-8"><NewsFlashCard /></div>
          <div className="col-span-12 xl:col-span-4"><LatestTradesCard trades={latestTrades} loading={tradesLoading} /></div>
        </div>
      </section>

      {/* ====== 财经日历：本周已公布 / 即将公布 ====== */}
      <section className="sec mt-12"><EconCalendarCard /></section>

      {/* ====== 大户持仓：没快照时组件自己整节不渲染 ====== */}
      <WhaleSummaryCard />

      {/* ====== 爆仓动态 ====== */}
      <section className="sec mt-12"><ForceOrdersCard /></section>

      {/* ====== 新手教学 ====== */}
      <section className="sec mt-12"><HomeFaq /></section>

      {/* 网格点某一天的下钻：当日五分类盈亏拆解，数据全来自已有的当月快照，弹窗自己不发请求 */}
      <DayDetailModal
        date={selectedDate}
        snapshot={selectedSnapshot}
        onClose={() => setSelectedDate(null)}
      />

      {/* 每日福利弹窗（入口那格触发） */}
      {ready && (
        <DailyBuffModal
          status={buffStatus}
          open={buffOpen}
          onClose={() => setBuffOpen(false)}
          onDrawn={() => setRefreshNonce(n => n + 1)}
        />
      )}

    </div>
  );
}
