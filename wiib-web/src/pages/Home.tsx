import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { cryptoOrderApi, futuresApi, userApi } from '../api';
import { HomeMarketSection } from '../components/HomeMarketSection';
import { HomeTraderBlock } from '../components/HomeTraderBlock';
import { MonthGrid } from '../components/MonthGrid';
import { LatestTradesCard } from '../components/LatestTradesCard';
import type { TradeItem } from '../components/LatestTradesCard';
import { ForceOrdersCard } from '../components/ForceOrdersCard';
import { NewsFlashCard } from '../components/NewsFlashCard';
import { EconCalendarCard } from '../components/EconCalendarCard';
import { WhaleSummaryCard } from '../components/WhaleSummaryCard';
import { HomeFaq } from '../components/HomeFaq';
import { Sparkline } from '../components/fx/Sparkline';
import { DayDetailModal } from '../components/DayDetailModal';
import { BigNum } from '../components/ui/big-num';
import { ChevronRight, DollarSign, List, LogIn, Settings2, Swords, Target } from 'lucide-react';
import type { AssetSnapshot, User } from '../types';
import { useUserStore } from '../stores/userStore';
import { fmtDate, fmtNum, fmtSignedPct, fmtSignedUsd, parseServerTime } from '../lib/utils';
import './Home.css';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
const NOTICE_SEEN_KEY = 'wiib-notice-seen';
// seen = 点过「我知道了」，永久不再自动跳；hide-date 只压当天，明天还提醒一次
function shouldShowNotice() {
  if (localStorage.getItem(NOTICE_SEEN_KEY)) return false;
  const d = localStorage.getItem(HIDE_NOTICE_KEY);
  return !d || d !== new Date().toDateString();
}

/** 入口一排五格 */
const ENTRIES = [
  { icon: List, k: 'stocks', to: '/bstock' },
  { icon: DollarSign, k: 'crypto', to: '/coin' },
  { icon: Swords, k: 'arena', to: '/arena' },
  { icon: Target, k: 'prediction', to: '/prediction' },
  { icon: Settings2, k: 'ai', to: '/ai' },
];

/** 按时段挑问候语，返回的是词表 key：这里直接查词表会把文案定死在模块加载那一刻 */
function greetingKey(): string {
  const h = new Date().getHours();
  if (h < 5) return 'greeting.lateNight';
  if (h < 11) return 'greeting.morning';
  if (h < 13) return 'greeting.noon';
  if (h < 18) return 'greeting.afternoon';
  return 'greeting.evening';
}

/** 首屏左半：总资产大数 + 四个小数 + 净值线 */
function HeroMain({ user, history, realtime }: { user: User; history: AssetSnapshot[]; realtime: AssetSnapshot | null }) {
  const { t, i18n } = useTranslation('home');

  // 曲线尾端接上实时值，让"最新一格"跟着盘面动
  const curve = history.length
    ? [...history.map(h => h.totalAssets), ...(realtime ? [realtime.totalAssets] : [])]
    : [];
  const start = user.totalAssets - user.profit;   // 起始资金：曲线按它分绿红两段
  const lang = i18n.resolvedLanguage ?? i18n.language;
  const axDay = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString(lang, { month: 'long', day: 'numeric' });

  return (
    <div className="acct">
      <div className="cap">{t('hero.totalAssets')}</div>
      <h1 className="big total nf"><BigNum value={user.totalAssets} /></h1>

      <div className="stats">
        <div><b className={user.profitPct >= 0 ? 'up' : 'dn'}>{fmtSignedPct(user.profitPct)}</b><span>{t('hero.profitPct')}</span></div>
        <div><b>₮{fmtNum(user.balance)}</b><span>{t('hero.balance')}</span></div>
        <div><b className={user.profit >= 0 ? 'up' : 'dn'}>{fmtSignedUsd(user.profit)}</b><span>{t('hero.totalPnl')}</span></div>
        <div><b>₮{fmtNum(user.positionMarketValue)}</b><span>{t('hero.positionValue')}</span></div>
      </div>

      {/* 净值线：起始资金画虚线，线在它之上绿、之下红；底下一行首末日期 */}
      <div className="spark">
        {curve.length > 1 && (
          <Sparkline
            /* key 随数据变 → path 重建，描线动画重跑一遍 */
            key={`${curve.length}:${curve[curve.length - 1]}`}
            data={curve}
            baseline={start}
            baselineLabel={t('hero.startCapital', { amount: `₮${fmtNum(start)}` })}
            dot={false}
            className="w-full h-full"
          />
        )}
      </div>
      <div className="axis">
        {history.length > 0 && (
          <>
            <span>{axDay(history[0].date)}</span>
            <span>{axDay(history[history.length - 1].date)}</span>
          </>
        )}
      </div>
    </div>
  );
}

export function Home() {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation(['home', 'layout']);
  const { user, token } = useUserStore();
  // 游客没 token；有 token 但 user 为 null 是 fetchUser 还没回来。
  // 行情/成交那几块不依赖 user，先渲染出来，首屏等 user 到了再补
  const guest = !token;
  const ready = !!user;

  const [latestTrades, setLatestTrades] = useState<TradeItem[]>([]);
  const [tradesLoading, setTradesLoading] = useState(true);

  // 首屏数据：资产曲线(30d) + 实时快照 + 月度网格(逐日快照)
  const [history, setHistory] = useState<AssetSnapshot[]>([]);
  const [realtime, setRealtime] = useState<AssetSnapshot | null>(null);
  const [monthCells, setMonthCells] = useState<AssetSnapshot[]>([]);
  const [gridMonth, setGridMonth] = useState(() => fmtDate().slice(0, 7));
  const [selectedDate, setSelectedDate] = useState<string | null>(null);

  // 首访自动跳玩法说明只给登录用户；游客打开根路径就是首页，说明页从顶部那条链接自己点
  useEffect(() => { if (!guest && shouldShowNotice()) navigate('/intro', { replace: true }); }, [guest, navigate]);

  useEffect(() => {
    if (!ready) return;
    userApi.assetHistory(30).then(setHistory).catch(() => {});
    userApi.assetRealtime().then(setRealtime).catch(() => {});
  }, [ready]);

  // 网格按月拉：翻月就再问一次。旧月数据先留着不清，避免切月时整片格子闪白
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    userApi.assetDaily(gridMonth)
      .then(rows => { if (!cancelled) setMonthCells(rows); })
      .catch(() => { if (!cancelled) setMonthCells([]); });
    return () => { cancelled = true; };
  }, [ready, gridMonth]);

  // 这里只装后端原始值（方向枚举、去 USDT 的符号），方向标签和展示名交给 LatestTradesCard 渲染期算：
  // 在拉数这一刻就把字烤进 state 的话，切语言后这批卡片还是老语言
  useEffect(() => {
    Promise.all([cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([co, fo]) => {
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, base: o.symbol.replace('USDT', ''), quantity: o.quantity, filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => ({ id: `f-${o.orderId}`, orderSide: o.orderSide, base: o.symbol.replace('USDT', ''), isFutures: true, quantity: o.quantity, filledAmount: o.filledAmount, createdAt: o.createdAt, isAi: o.isAiTrader === true }));
        setLatestTrades([...ci, ...fi].sort((a, b) => parseServerTime(b.createdAt).getTime() - parseServerTime(a.createdAt).getTime()).slice(0, 20));
      }).finally(() => setTradesLoading(false));
  }, []);

  // 切月时 monthCells 还是上个月的，先按当前月份筛一道，合计和格子才不会串月
  const monthRows = monthCells.filter(s => s.date.slice(0, 7) === gridMonth);
  const gridCells = monthRows.map(s => ({ date: s.date, pnl: s.dailyProfit }));
  // 弹窗里前后翻：只在这个月有快照的日子之间走
  const selectedIdx = monthRows.findIndex(s => s.date === selectedDate);
  const stepDay = (delta: number) => {
    const next = monthRows[selectedIdx + delta];
    return next ? () => setSelectedDate(next.date) : undefined;
  };
  // 月份名/星期名跟着界面语言走，不能钉死 zh-CN——英文界面下会漏出"8月20日星期三"。
  // 取 resolvedLanguage：language 可能是没落在支持列表里的原始值（与 lib/utils.ts 同口径）
  const dateStr = new Date().toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, { month: 'long', day: 'numeric', weekday: 'long' });

  return (
    <div className="page-shell p-home">

      {/* ====== 首屏：问候 / 总资产 / 我的 Trader / 月度盈亏 ====== */}
      {ready && (
        <>
          <div className="hello">
            <div>
              <b>{t('greeting.withName', { greeting: t(greetingKey()), name: user!.username })}</b>
              <span>{dateStr}</span>
            </div>
            <Link to="/intro">{t('hero.howToPlay')}</Link>
          </div>
          <section className="hero">
            <HeroMain user={user!} history={history} realtime={realtime} />
            <HomeTraderBlock />
            <MonthGrid
              cells={gridCells}
              month={gridMonth}
              onMonthChange={setGridMonth}
              selectedDate={selectedDate ?? undefined}
              onSelectDate={setSelectedDate}
            />
          </section>
        </>
      )}

      {/* 游客：首屏那块整个不出，换一句说明 + 去登录 */}
      {guest && (
        <section className="guest">
          <div>
            <h1>{t('hero.guestTitle')}</h1>
            <p>{t('hero.guestDesc')}</p>
            <Link to="/login" className="btn ink"><LogIn className="ic" />{t('layout:header.login')}</Link>
          </div>
          <Link to="/intro" className="go">{t('hero.howToPlay')}<ChevronRight className="ic" /></Link>
        </section>
      )}

      {/* ====== 入口一排五格 ====== */}
      <section className="sec tight sm">
        <div className="sec-h"><h2>{t('entries.title')}</h2></div>
        <div className="ent">
          {ENTRIES.map(({ icon: Icon, k, to }) => (
            <Link key={to} to={to}>
              <span className="ib0"><Icon className="ic" /></span>
              <span><b>{t(`quick.${k}`)}</b><span>{t(`quick.${k}Desc`)}</span></span>
            </Link>
          ))}
        </div>
      </section>

      {/* ====== 市场：每类两只代表，点行直达交易页 ====== */}
      <section className="sec tight sm"><HomeMarketSection /></section>

      {/* ====== 快讯 + 最新成交 ====== */}
      <section className="sec">
        <div className="g12">
          <div className="col-span-8 min-w-0"><NewsFlashCard /></div>
          <div className="col-span-4 min-w-0"><LatestTradesCard trades={latestTrades} loading={tradesLoading} /></div>
        </div>
      </section>

      {/* ====== 财经日历：已公布 / 即将公布 ====== */}
      <section className="sec"><EconCalendarCard /></section>

      {/* ====== 大户持仓：没快照时组件自己整节不渲染 ====== */}
      <WhaleSummaryCard />

      {/* ====== 爆仓动态 ====== */}
      <section className="sec"><ForceOrdersCard /></section>

      {/* ====== 新手教学 ====== */}
      <section className="sec"><HomeFaq /></section>

      {/* 月历点某一天：当日四类拆解，数据就是手上这个月的，弹窗自己不发请求，能直接前后翻 */}
      <DayDetailModal
        date={selectedDate}
        snapshot={monthRows[selectedIdx] ?? null}
        onPrev={stepDay(-1)}
        onNext={stepDay(1)}
        onClose={() => setSelectedDate(null)}
      />

    </div>
  );
}
