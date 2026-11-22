import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { traderApi } from '../api';
import { cn, fmtDate, fmtNum, fmtSignedPct, fmtSignedUsd, fmtTime } from '../lib/utils';
import type { TraderPublicView } from '../types';

/** 首页要显示的战报数字，都是拉完几个接口算好的现成值 */
interface Stats {
  todayPnl: number;
  todayPct: number;
  /** 本局第几天 */
  dayNo: number;
  positions: number;
  /** HH:mm，没有下次唤醒就是 — */
  nextWake: string;
  /** 最近一轮推理的第一行，没有决策就是 null */
  last: string | null;
}

/** 一张战报：哪个 trader + 它的数 */
interface Card {
  pub: TraderPublicView;
  stats: Stats;
}

/** 好几个 trader 时几秒切一张 */
const ROTATE_MS = 6000;

/** 推理只取第一行，超 40 字截断——这里是一句话战报，不是全文 */
function firstLine(reasoning: string | null | undefined): string | null {
  const s = reasoning?.split('\n').find(l => l.trim())?.trim();
  if (!s) return null;
  return s.length > 40 ? `${s.slice(0, 40)}…` : s;
}

/** 一个 trader 的战报数：详情、净值曲线、最近一次唤醒三个接口拉齐算好 */
async function statsOf(pub: TraderPublicView): Promise<Stats> {
  const [detail, curve, decisions] = await Promise.all([
    traderApi.detail(pub.id).catch(() => null),
    traderApi.equityCurve(pub.id).catch(() => []),
    traderApi.recentWakes(pub.id, 1).catch(() => []),
  ]);
  // 今日盈亏 = 现在的权益 − 今天 0 点（东八区）之前最后一个点；本局刚开就拿第一个点当基准
  const todayStart = new Date(`${fmtDate()}T00:00:00+08:00`).getTime();
  const base = [...curve].reverse().find(p => p.wakeTime < todayStart) ?? curve[0];
  const todayPnl = base ? pub.equity - base.equity : 0;
  return {
    todayPnl,
    todayPct: base?.equity ? (todayPnl / base.equity) * 100 : 0,
    dayNo: curve.length ? Math.floor((Date.now() - curve[0].wakeTime) / 86400000) + 1 : 1,
    positions: detail?.positions.length ?? 0,
    nextWake: detail?.nextWakeAt ? fmtTime(detail.nextWakeAt) : '—',
    last: firstLine(decisions[0]?.reasoning),
  };
}

/**
 * 首页首屏里的 Trader 黑块：有 trader 是战报，好几个就轮着播；没 trader 是入口（样式见 pages/Home.css .trader）。
 */
export function HomeTraderBlock() {
  const { t } = useTranslation(['home', 'common']);
  // undefined=还在拉，null=没有 trader
  const [cards, setCards] = useState<Card[] | null | undefined>(undefined);
  // 问 mine 失败了：不知道有没有 trader，不能当成没有去出"创建"入口
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let dead = false;
    traderApi.mine().then(async mine => {
      if (dead) return;
      if (mine.length === 0) {
        setCards(null);
        return;
      }
      // 几张战报一次拉齐再一起出
      const all = await Promise.all(mine.map(async m => ({ pub: m.pub, stats: await statsOf(m.pub) })));
      if (!dead) setCards(all);
    }).catch(() => { if (!dead) setFailed(true); });
    return () => { dead = true; };
  }, []);

  if (cards === null) return <TraderEmpty />;
  // 数还没齐就只出块名，不先摆 0
  if (!cards) {
    return (
      <div className="trader">
        <div className="row1">{t('trader.title')}</div>
        {failed && <div className="tlast">{t('common:loadFailed')}</div>}
      </div>
    );
  }
  return <TraderCarousel cards={cards} />;
}

/** 轮播：几秒切一张，鼠标放上去停住，底下圆点点着切；只有一张时不轮也不出圆点 */
function TraderCarousel({ cards }: { cards: Card[] }) {
  const { t } = useTranslation('home');
  const [at, setAt] = useState(0);
  const [hover, setHover] = useState(false);
  const many = cards.length > 1;

  // 每换一张重新计时：点圆点切过去的那张也看满一整轮
  useEffect(() => {
    if (!many || hover) return;
    const timer = setTimeout(() => setAt(i => (i + 1) % cards.length), ROTATE_MS);
    return () => clearTimeout(timer);
  }, [many, hover, at, cards.length]);

  return (
    <div className="trader" onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      {/* 几张叠在同一格里只露当前那张：块高取最高那张，切的时候不跳 */}
      <div className="tdeck">
        {cards.map((c, i) => (
          <div key={c.pub.id} className={cn('tcard', i === at && 'on')}><TraderStats {...c} /></div>
        ))}
      </div>
      {many && (
        <div className="tdots" role="group" aria-label={t('trader.switchAria')}>
          {cards.map((c, i) => (
            <button key={c.pub.id} type="button" className={cn(i === at && 'on')} aria-label={c.pub.name}
                    aria-current={i === at ? 'true' : undefined} onClick={() => setAt(i)} />
          ))}
        </div>
      )}
    </div>
  );
}

function TraderStats({ pub, stats }: Card) {
  const { t } = useTranslation('home');
  const { t: tAi } = useTranslation('ai');

  const tone = stats.todayPnl >= 0 ? 'up' : 'dn';
  const status = pub.status === 'RUNNING'
    ? { cls: 'run', label: tAi('status.running') }
    : pub.status === 'PAUSED'
      ? { cls: 'pause', label: tAi('status.paused') }
      : { cls: 'dead', label: tAi('status.liquidated') };
  const spec = [pub.model, pub.intervalCode, pub.symbols.split(',').map(s => s.replace('USDT', '')).join(' / ')]
    .filter(Boolean).join(' · ');

  return (
    <>
      <div className="row1">
        {t('trader.title')}
        <span className={cn('st', status.cls)}><i />{status.label}</span>
      </div>

      <div className="tname"><b>{pub.name}</b><span>{spec}</span></div>

      <div className={cn('tpnl', tone)}>{fmtSignedUsd(stats.todayPnl)}</div>
      <div className={cn('tday', tone)}>
        {t('trader.todayPct', { pct: fmtSignedPct(stats.todayPct) })}
        <span>{t('trader.roundDay', { round: pub.roundNo, day: stats.dayNo })}</span>
      </div>

      <div className="tmeta">
        <div>{t('trader.equity')}<b>{fmtNum(pub.equity)}</b></div>
        <div>{t('trader.roundPnl')}<b className={pub.pnlPct >= 0 ? 'up' : 'dn'}>{fmtSignedPct(pub.pnlPct)}</b></div>
        <div>{t('trader.positions')}<b>{t('trader.positionsValue', { n: stats.positions })}</b></div>
        <div>{t('trader.nextWake')}<b>{stats.nextWake}</b></div>
      </div>

      {stats.last && (
        <div className="tlast">
          {t('trader.lastRound', { text: stats.last })}
          <Link to={`/arena/${pub.id}`}>{t('trader.seeThinking')}</Link>
        </div>
      )}
    </>
  );
}

function TraderEmpty() {
  const { t } = useTranslation('home');
  return (
    <div className="trader tempty">
      <div className="row1">{t('trader.title')}</div>
      <b>{t('trader.emptyTitle')}</b>
      <ol>
        <li>{t('trader.step1')}</li>
        <li>{t('trader.step2')}</li>
        <li>{t('trader.step3')}</li>
      </ol>
      <div className="go2">
        <Link to="/my-trader/new" className="btn">{t('trader.create')}</Link>
        <Link to="/arena" className="btn dim">{t('trader.seeOthers')}</Link>
      </div>
    </div>
  );
}
