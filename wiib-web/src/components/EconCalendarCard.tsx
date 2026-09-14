import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Skeleton } from './ui/skeleton';
import { EconEventRow } from './econ/EconEventRow';
import { quantApi, type EconCalendarEvent, type EconCalendarView } from '../api';

/** 采集 4 小时一次、实际值由公布时刻的等待闸补齐，轮询只是让分界与实际值跟着时间走 */
const POLL_MS = 300_000;

/**
 * 首页财经日历：TradingView 全球 High 级事件，左栏过去 3 天已公布、右栏未来一周即将公布，各最多 6 条。
 * 行可以点开看解读；翻历史去日历页。
 */
export function EconCalendarCard() {
  const { t } = useTranslation('home');
  const [data, setData] = useState<EconCalendarView | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => quantApi.econCalendar()
      .then(v => { if (alive) setData(v); })
      .catch(() => { if (alive) setData(prev => prev ?? { past: [], upcoming: [] }); });
    load();
    const timer = setInterval(load, POLL_MS);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  return (
    <>
      <div className="sec-h">
        <h2>{t('calendar.title')}<small>{t('calendar.note')}</small></h2>
        <Link to="/calendar">{t('calendar:viewAll')}</Link>
      </div>
      <div className="g12">
        <Column title={t('calendar.published')} rows={data?.past} empty={t('calendar.emptyPast')} />
        <Column title={t('calendar.upcoming')} rows={data?.upcoming} empty={t('calendar.emptyUpcoming')} upcoming />
      </div>
    </>
  );
}

function Column({ title, rows, empty, upcoming }: {
  title: string;
  /** undefined=还没拉回来 */
  rows?: EconCalendarEvent[];
  empty: string;
  /** 即将公布栏：头一条是最近要撞上的，加粗；第一条美国的就是下一条美国数据，闪点 */
  upcoming?: boolean;
}) {
  const nextUs = upcoming ? rows?.find(r => r.country === 'US') : undefined;
  return (
    <div className="col-span-12 xl:col-span-6">
      <div className="microlabel uppercase pb-2 border-b border-foreground">{title}</div>
      {rows == null ? (
        <div className="space-y-3 pt-3">
          {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-9" />)}
        </div>
      ) : rows.length === 0 ? (
        <div className="py-8 text-center text-sm text-muted-foreground">{empty}</div>
      ) : rows.map((r, i) => (
        <EconEventRow key={r.sourceId} event={r} bold={upcoming && i === 0} next={r === nextUs} />
      ))}
    </div>
  );
}
