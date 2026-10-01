import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { traderApi } from '../api';
import { useCountUp } from '../hooks/useCountUp';
import { cn, fmtDate, fmtNum, fmtSignedPct, fmtSignedUsd, fmtTime } from '../lib/utils';
import type { TraderPublicView } from '../types';

/** 墨块外壳：整格反色，撑满 hero 那一格的高 */
const SHELL = 'bg-foreground text-background px-5 py-[18px] flex flex-col';

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
 * 首页 hero 里的 trader 墨块：有 trader 是战报，没 trader 是入口。
 */
export function HomeTraderBlock({ className }: { className?: string }) {
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

  if (pub === null) return <TraderEmpty className={className} />;
  // 数还没齐就只出块名：战报里的数是一起滚上去的，不能先摆 0 再跳
  if (!pub || !stats) {
    return (
      <div className={cn(SHELL, className)}>
        <div className="text-[13px] font-semibold">{t('trader.title')}</div>
        {failed && <div className="mt-2 text-[12.5px] text-background/70">{t('common:loadFailed')}</div>}
      </div>
    );
  }
  return <TraderStats pub={pub} stats={stats} className={className} />;
}

function TraderStats({ pub, stats, className }: { pub: TraderPublicView; stats: Stats; className?: string }) {
  const { t } = useTranslation('home');
  const { t: tAi } = useTranslation('ai');
  const pnlRef = useCountUp<HTMLDivElement>(stats.todayPnl, fmtSignedUsd);

  const up = stats.todayPnl >= 0;
  const chip = pub.status === 'RUNNING'
    ? { cls: 'up text-gain', label: tAi('status.running'), pulse: true }
    : pub.status === 'PAUSED'
      ? { cls: 'wn text-warning', label: tAi('status.paused'), pulse: false }
      : { cls: 'dn text-loss', label: tAi('status.liquidated'), pulse: false };
  const spec = [pub.model, pub.intervalCode, pub.symbols.split(',').map(s => s.replace('USDT', '')).join(' / ')]
    .filter(Boolean).join(' · ');
  const metaK = 'text-[11.5px] text-background/60';
  const metaV = 'block text-[16px] font-semibold [font-stretch:85%]';

  return (
    <div className={cn(SHELL, className)}>
      <div className="flex items-center gap-2.5 text-[13px] font-semibold">
        {t('trader.title')}
        <span className={cn('chip ml-auto', chip.cls)}>
          {chip.pulse && <i className="dot pulse" />}
          {chip.label}
        </span>
      </div>

      <div className="flex items-baseline gap-2.5 mt-2 flex-wrap">
        <b className="text-[26px] font-bold leading-none">{pub.name}</b>
        <span className="text-[12px] text-background/60">{spec}</span>
      </div>

      <div ref={pnlRef} className={cn('num cond text-[40px] font-bold leading-none mt-3', up ? 'up' : 'dn')} />

      <div className="num flex items-baseline justify-between gap-3 mt-[5px] text-[15px] font-semibold">
        <span className={up ? 'up' : 'dn'}>{t('trader.todayPct', { pct: fmtSignedPct(stats.todayPct) })}</span>
        <span className="text-[12px] font-medium text-background/60">
          {t('trader.roundDay', { round: pub.roundNo, day: stats.dayNo })}
        </span>
      </div>

      <div className="num flex gap-[18px] mt-3.5 pt-3 border-t border-background/15">
        <div className={metaK}>{t('trader.equity')}<b className={cn(metaV, 'text-background')}>{fmtNum(pub.equity)}</b></div>
        <div className={metaK}>
          {t('trader.roundPnl')}
          <b className={metaV}>{fmtSignedPct(pub.pnlPct)}</b>
        </div>
        <div className={metaK}>
          {t('trader.positions')}
          <b className={cn(metaV, 'text-background')}>{t('trader.positionsValue', { n: stats.positions })}</b>
        </div>
        <div className={metaK}>{t('trader.nextWake')}<b className={cn(metaV, 'text-background')}>{stats.nextWake}</b></div>
      </div>

      {stats.last && (
        <div className="mt-auto pt-2.5 text-[12.5px] leading-[1.5] text-background/75">
          {t('trader.lastRound', { text: stats.last })}
          <Link to={`/arena/${pub.id}`} className="ml-2 text-background underline underline-offset-[3px]">
            {t('trader.seeThinking')}
          </Link>
        </div>
      )}
    </div>
  );
}

function TraderEmpty({ className }: { className?: string }) {
  const { t } = useTranslation('home');
  return (
    <div className={cn(SHELL, className)}>
      <div className="text-[13px] font-semibold">{t('trader.title')}</div>
      <div className="mt-2">
        <b className="block text-[22px] font-bold leading-[1.3] max-w-[22ch]">{t('trader.emptyTitle')}</b>
      </div>
      <div className="mt-3 text-[12.5px] leading-[1.7] text-background/70">
        {t('trader.step1')}<br />{t('trader.step2')}<br />{t('trader.step3')}
      </div>
      <div className="mt-auto pt-3 flex items-center gap-3">
        <Link to="/my-trader" className="btn orange h-[34px] text-[13px]">{t('trader.create')}</Link>
        <Link to="/arena" className="btn h-[34px] text-[13px] border-background text-background hover:bg-background/10">
          {t('trader.seeOthers')}
        </Link>
      </div>
    </div>
  );
}
