import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { futuresApi } from '../api';
import { parseServerTime } from '../lib/utils';

const POLL_MS = 30_000;
const WINDOW_MS = 3_600_000;

/** 大额压成一眼能读的宽度：1,243,800 → 1.24M。两个数共用一个量级（按大的那个定），并排不会一个 M 一个 K */
function compact(v: number, ref: number): string {
  if (ref >= 1e6) return `${(v / 1e6).toFixed(2)}M`;
  if (ref >= 1e3) return `${(v / 1e3).toFixed(2)}K`;
  return String(Math.round(v));
}

/** 首页爆仓动态：过去 1 小时的强平额分多空两边，右边一条多空比条 */
export function ForceOrdersCard() {
  const { t } = useTranslation('home');
  const [stat, setStat] = useState({ long: 0, short: 0, count: 0 });

  useEffect(() => {
    let cancelled = false;
    const load = () => futuresApi.forceOrders(undefined, 1, 200)
      .then(page => {
        if (cancelled) return;
        const since = Date.now() - WINDOW_MS;
        const rows = (page?.records ?? []).filter(r => parseServerTime(r.tradeTime).getTime() >= since);
        // SELL=多头被强平，BUY=空头被强平
        setStat({
          long: rows.reduce((s, r) => s + (r.side === 'SELL' ? r.amount : 0), 0),
          short: rows.reduce((s, r) => s + (r.side === 'BUY' ? r.amount : 0), 0),
          count: rows.length,
        });
      })
      .catch(() => {});
    load();
    const timer = window.setInterval(load, POLL_MS);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, []);

  const total = stat.long + stat.short;
  const longPct = total ? (stat.long / total) * 100 : 50;

  return (
    <>
      <div className="sec-h">
        <h2>{t('force.title')}<small>{t('force.window', { count: stat.count })}</small></h2>
        <Link to="/force-orders">{t('force.viewAll')}</Link>
      </div>
      <div className="num flex flex-wrap items-baseline gap-11">
        <div>
          <div className="dn cond text-[44px] font-bold leading-none">${compact(stat.long, Math.max(stat.long, stat.short))}</div>
          <div className="text-[13px] text-muted-foreground mt-1.5">{t('force.longLiq')}</div>
        </div>
        <div>
          <div className="up cond text-[44px] font-bold leading-none">${compact(stat.short, Math.max(stat.long, stat.short))}</div>
          <div className="text-[13px] text-muted-foreground mt-1.5">{t('force.shortLiq')}</div>
        </div>
        <div className="flex flex-1 self-center ml-auto h-1.5 max-w-[360px] bg-border">
          <i className="block h-full bg-loss" style={{ width: `${longPct}%` }} />
          <i className="block h-full bg-gain" style={{ width: `${100 - longPct}%` }} />
        </div>
      </div>
    </>
  );
}
