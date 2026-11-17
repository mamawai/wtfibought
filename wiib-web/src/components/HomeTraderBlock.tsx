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

/** 推理只取第一行，超 40 字截断——这里是一句话战报，不是全文 */
function firstLine(reasoning: string | null | undefined): string | null {
  const s = reasoning?.split('\n').find(l => l.trim())?.trim();
  if (!s) return null;
  return s.length > 40 ? `${s.slice(0, 40)}…` : s;
}

/**
 * 首页首屏里的 Trader 黑块：有 trader 是战报，没 trader 是入口（样式见 pages/Home.css .trader）。
 */
export function HomeTraderBlock() {
  const { t } = useTranslation(['home', 'common']);
  // undefined=还在问，null=没有 trader
  const [pub, setPub] = useState<TraderPublicView | null | undefined>(undefined);
  const [stats, setStats] = useState<Stats | null>(null);
  // 问 mine 失败了：不知道有没有 trader，不能当成没有去出"创建"入口
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let dead = false;
    traderApi.mine().then(async mine => {
      if (dead) return;
      setPub(mine?.pub ?? null);
      if (!mine) return;
      const id = mine.pub.id;
      const [panel, detail, curve, decisions] = await Promise.all([
        traderApi.actionPanel().catch(() => null),
        traderApi.detail(id).catch(() => null),
        traderApi.equityCurve(id).catch(() => []),
        traderApi.decisions(id, 1).catch(() => []),
      ]);
      if (dead) return;
      // 今日盈亏 = 现在的权益 − 今天 0 点（东八区）之前最后一个点；本局刚开就拿第一个点当基准
      const todayStart = new Date(`${fmtDate()}T00:00:00+08:00`).getTime();
      const base = [...curve].reverse().find(p => p.wakeTime < todayStart) ?? curve[0];
      const todayPnl = base ? mine.pub.equity - base.equity : 0;
      setStats({
        todayPnl,
        todayPct: base?.equity ? (todayPnl / base.equity) * 100 : 0,
        dayNo: curve.length ? Math.floor((Date.now() - curve[0].wakeTime) / 86400000) + 1 : 1,
        positions: detail?.positions.length ?? 0,
        nextWake: panel?.nextWakeAt ? fmtTime(panel.nextWakeAt) : '—',
        last: firstLine(decisions[0]?.reasoning),
      });
    }).catch(() => { if (!dead) setFailed(true); });
    return () => { dead = true; };
  }, []);

  if (pub === null) return <TraderEmpty />;
  // 数还没齐就只出块名，不先摆 0
  if (!pub || !stats) {
    return (
      <div className="trader">
        <div className="row1">{t('trader.title')}</div>
        {failed && <div className="tlast">{t('common:loadFailed')}</div>}
      </div>
    );
  }
  return <TraderStats pub={pub} stats={stats} />;
}

function TraderStats({ pub, stats }: { pub: TraderPublicView; stats: Stats }) {
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
    <div className="trader">
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
    </div>
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
        <Link to="/my-trader" className="btn">{t('trader.create')}</Link>
        <Link to="/arena" className="btn dim">{t('trader.seeOthers')}</Link>
      </div>
    </div>
  );
}
