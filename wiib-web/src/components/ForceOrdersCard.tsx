import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
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
  const ref = Math.max(stat.long, stat.short);

  return (
    <>
      <div className="sec-h">
        <h2>{t('force.title')}<small>{t('force.window', { count: stat.count })}</small></h2>
        <Link to="/force-orders" className="go">{t('force.viewAll')}<ChevronRight className="ic" /></Link>
      </div>
      <div className="liq">
        <div><Amount value={compact(stat.long, ref)} tone="dn" /><span>{t('force.longLiq')}</span></div>
        <div><Amount value={compact(stat.short, ref)} tone="up" /><span>{t('force.shortLiq')}</span></div>
        {/* 多空比条：左边红是多单被爆的占比，剩下的是空单 */}
        <div className="ratio">
          <div className="rb"><i style={{ width: `${longPct}%` }} /><i /></div>
          <div className="rl">
            <span>{t('force.longShare', { pct: longPct.toFixed(1) })}</span>
            <span>{t('force.shortShare', { pct: (100 - longPct).toFixed(1) })}</span>
          </div>
        </div>
      </div>
    </>
  );
}

/** 一颗细体大数：₮ 压小，小数和单位压成浅灰 */
function Amount({ value, tone }: { value: string; tone: 'up' | 'dn' }) {
  const dot = value.indexOf('.');
  return (
    <b className={`big nf ${tone}`}>
      <small>₮</small>
      {dot < 0 ? value : <>{value.slice(0, dot)}<i>{value.slice(dot)}</i></>}
    </b>
  );
}
