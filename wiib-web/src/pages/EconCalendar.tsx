import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Skeleton } from '../components/ui/skeleton';
import { DatePicker } from '../components/ui/date-picker';
import { CountryFlag } from '../components/CountryFlag';
import { EconEventRow } from '../components/econ/EconEventRow';
import { EconSeriesCharts } from '../components/econ/EconSeriesCharts';
import { quantApi, type EconCalendarEvent } from '../api';
import { currentLang } from '../i18n';
import { findEntry } from '../lib/econCatalog';
import { DAY_MS, cn, dayBounds, fmtDate } from '../lib/utils';

/** 可翻到的最早日期 */
const MIN_DAY = '2022-01-01';
const WEEK_MS = 7 * DAY_MS;
/** 国家按钮，按 2022 年起 High 事件条数从多到少 */
const COUNTRIES = ['US', 'GB', 'CN', 'DE', 'AU', 'CA', 'JP', 'EU', 'IT', 'FR', 'IN', 'ES', 'CH', 'KR'];
/** 选中的国家记在浏览器里，空数组 = 全部 */
const COUNTRY_KEY = 'wiib-econ-countries';

/** 某天（站点时区 yyyy-MM-dd）所在那周的周一 */
function mondayOf(day: string): string {
  const from = dayBounds(day).from;
  // from 是 +08:00 的零点，加 8 小时按 UTC 取星期就是站点时区的星期；0=周日
  const dow = new Date(from + 8 * 3_600_000).getUTCDay();
  return fmtDate(from - ((dow + 6) % 7) * DAY_MS);
}

const FIRST_WEEK = mondayOf(MIN_DAY);

/** 分组标题：09/14 周一 */
function fmtDayHead(ts: number): string {
  return new Date(ts).toLocaleDateString(currentLang() === 'en' ? 'en-US' : 'zh-CN',
    { timeZone: 'Asia/Singapore', month: '2-digit', day: '2-digit', weekday: 'short' });
}

/**
 * 财经日历页：按周翻 2022 年起的 High 级事件，按天分组，可按国家多选筛。
 * 点开一条看解读；带数值的再挂这个指标的历次走势，展开才请求。
 */
export function EconCalendar() {
  const { t } = useTranslation('calendar');
  // 本周、可翻范围、"下一条美国数据"的参照时刻，进页面时定一次
  const [range] = useState(() => {
    const now = Date.now();
    return {
      now,
      thisWeek: mondayOf(fmtDate(now)),
      // 最远翻到下周
      lastWeek: mondayOf(fmtDate(now + WEEK_MS)),
      maxDay: fmtDate(now + WEEK_MS),
    };
  });
  const [week, setWeek] = useState(range.thisWeek);
  const [rows, setRows] = useState<EconCalendarEvent[] | null>(null);
  const [countries, setCountries] = useState<string[]>(() => JSON.parse(localStorage.getItem(COUNTRY_KEY) ?? '[]'));

  useEffect(() => {
    let alive = true;
    const from = dayBounds(week).from;
    // 查 [周一 0 点, 下周一 0 点)，右端退 1ms
    quantApi.econCalendarEvents(from, from + WEEK_MS - 1)
      .then(v => { if (alive) setRows(v); })
      .catch(() => { if (alive) setRows([]); });
    return () => { alive = false; };
  }, [week]);

  // 换周先清成骨架；同一周不处理
  const changeWeek = (w: string) => { if (w === week) return; setRows(null); setWeek(w); };
  const shiftWeek = (n: number) => changeWeek(fmtDate(dayBounds(week).from + n * WEEK_MS));

  // null = 点"全部国家"清空
  const toggleCountry = (c: string | null) => {
    const picked = c == null ? [] : countries.includes(c) ? countries.filter(x => x !== c) : [...countries, c];
    setCountries(picked);
    localStorage.setItem(COUNTRY_KEY, JSON.stringify(picked));
  };

  const days = useMemo(() => {
    const byDay = new Map<string, EconCalendarEvent[]>();
    for (const r of rows ?? []) {
      if (countries.length && !countries.includes(r.country)) continue;
      const d = fmtDate(r.eventTime);
      byDay.set(d, [...(byDay.get(d) ?? []), r]);
    }
    return [...byDay.values()];
  }, [rows, countries]);
  // 闪点只在本周页找
  const nextUs = week === range.thisWeek ? rows?.find(r => r.country === 'US' && r.eventTime > range.now) : undefined;

  return (
    <div className="max-w-[980px] mx-auto px-5 pt-11 pb-14">
      <h1 className="text-[22px] font-extrabold tracking-[-.01em] mb-1.5">{t('title')}</h1>
      <p className="text-[13px] text-muted-foreground mb-6">{t('note')}</p>

      {/* 周导航：前后一周 + 日期框跳到那天所在的周 + 回本周；日期框只能点可翻范围内的日期 */}
      <div className="flex flex-wrap items-center gap-1.5">
        <button type="button" className="btn xs disabled:opacity-40" disabled={week <= FIRST_WEEK}
                onClick={() => shiftWeek(-1)}>{t('prevWeek')}</button>
        <DatePicker value={week} min={MIN_DAY} max={range.maxDay} onChange={v => changeWeek(mondayOf(v))}
                    className="input num h-7 px-2 text-[13px]" />
        <button type="button" className="btn xs disabled:opacity-40" disabled={week >= range.lastWeek}
                onClick={() => shiftWeek(1)}>{t('nextWeek')}</button>
        {week !== range.thisWeek && (
          <button type="button" className="btn xs" onClick={() => changeWeek(range.thisWeek)}>{t('thisWeek')}</button>
        )}
        <span className="num ml-auto text-[13px] text-muted-foreground">
          {week} ~ {fmtDate(dayBounds(week).from + 6 * DAY_MS)}
        </span>
      </div>

      {/* 国家筛选：可多选，一个都不选 = 全部 */}
      <div className="flex flex-wrap gap-1.5 mt-3">
        <button type="button" className={cn('chip', countries.length ? 'mute text-muted-foreground' : 'fill')}
                onClick={() => toggleCountry(null)}>{t('allCountries')}</button>
        {COUNTRIES.map(c => (
          <button key={c} type="button" className={cn('chip', countries.includes(c) ? 'fill' : 'mute text-muted-foreground')}
                  onClick={() => toggleCountry(c)}>
            <CountryFlag code={c} />{t(`country.${c}`)}
          </button>
        ))}
      </div>

      {rows == null ? (
        <div className="space-y-3 pt-6">
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-9" />)}
        </div>
      ) : days.length === 0 ? (
        <div className="py-10 text-center text-sm text-muted-foreground">{t('emptyWeek')}</div>
      ) : days.map(list => (
        <section key={list[0].eventTime} className="mt-6">
          <div className="microlabel uppercase pb-2 border-b border-foreground">{fmtDayHead(list[0].eventTime)}</div>
          {list.map(r => (
            <EconEventRow key={r.sourceId} event={r} showDate={false} next={r === nextUs}>
              {/* 讲话、会议这类没有数值的不挂图 */}
              {(r.actual != null || r.forecast != null || r.previous != null) && (
                <EconSeriesCharts country={r.country} sourceId={r.sourceId} eventTime={r.eventTime}
                                  titles={findEntry(r.country, r.title)?.titles ?? [r.title]} />
              )}
            </EconEventRow>
          ))}
        </section>
      ))}
    </div>
  );
}
