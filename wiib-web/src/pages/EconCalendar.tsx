import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { Skeleton } from '../components/ui/skeleton';
import { DatePicker } from '../components/ui/date-picker';
import { CountryFlag } from '../components/CountryFlag';
import { EmptyState } from '../components/EmptyState';
import { EconEventRow } from '../components/econ/EconEventRow';
import { EconSeriesCharts } from '../components/econ/EconSeriesCharts';
import { quantApi, type EconCalendarEvent } from '../api';
import { currentLang } from '../i18n';
import { findEntry } from '../lib/econCatalog';
import { DAY_MS, dayBounds, fmtDate } from '../lib/utils';
import './EconCalendar.css';

/** 可翻到的最早日期 */
const MIN_DAY = '2022-01-01';
const WEEK_MS = 7 * DAY_MS;
/** 国家筛选的先后，按 2022 年起 High 事件条数从多到少 */
const COUNTRIES = ['US', 'GB', 'CN', 'DE', 'AU', 'CA', 'JP', 'EU', 'IT', 'FR', 'IN', 'ES', 'CH', 'KR'];
/** 选中的国家记在浏览器里，空数组 = 全部 */
const COUNTRY_KEY = 'wiib-econ-countries';
/** 周导航上的日期：11月16日 / Nov 16 */
const MONTH_DAY = { month: 'short', day: 'numeric' } as const;

/** 某天（站点时区 yyyy-MM-dd）所在那周的周一 */
function mondayOf(day: string): string {
  const from = dayBounds(day).from;
  // from 是 +08:00 的零点，加 8 小时按 UTC 取星期就是站点时区的星期；0=周日
  const dow = new Date(from + 8 * 3_600_000).getUTCDay();
  return fmtDate(from - ((dow + 6) % 7) * DAY_MS);
}

const FIRST_WEEK = mondayOf(MIN_DAY);

/** 站点时区的日期文字，跟界面语言走 */
function fmtDay(ts: number, opts: Intl.DateTimeFormatOptions): string {
  return new Date(ts).toLocaleDateString(currentLang() === 'en' ? 'en-US' : 'zh-CN', { timeZone: 'Asia/Singapore', ...opts });
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
  // 日期框里点的那天；翻到别的周就不再显示它
  const [jumpDay, setJumpDay] = useState('');

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

  // 本周每个国家几条事件
  const counts = useMemo(() => {
    const m = new Map<string, number>();
    for (const r of rows ?? []) m.set(r.country, (m.get(r.country) ?? 0) + 1);
    return m;
  }, [rows]);
  // 筛选里列本周有事件的国家，再加上选中了但本周没事件的（显示 0，免得选着的筛选被藏起来）
  const chipCodes = [...new Set([...COUNTRIES, ...counts.keys(), ...countries])]
    .filter(c => counts.has(c) || countries.includes(c));

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
  const weekStart = dayBounds(week).from;
  const today = fmtDate(range.now);

  return (
    <div className="page-shell">
      <div className="p-ec">
        <div className="pg-h">
          <div>
            <h1>{t('title')}</h1>
            <p className="sub">{t('note')}</p>
          </div>
        </div>

        {/* 周导航：前后一周 + 回本周 + 日期框跳到那天所在的周；日期框只能点可翻范围内的日期 */}
        <div className="wk">
          <button type="button" className="ib" aria-label={t('prevWeek')} title={t('prevWeek')}
                  disabled={week <= FIRST_WEEK} onClick={() => shiftWeek(-1)}>
            <ChevronLeft className="ic" />
          </button>
          <b className="nf">{fmtDay(weekStart, MONTH_DAY)} – {fmtDay(weekStart + 6 * DAY_MS, MONTH_DAY)}</b>
          <button type="button" className="ib" aria-label={t('nextWeek')} title={t('nextWeek')}
                  disabled={week >= range.lastWeek} onClick={() => shiftWeek(1)}>
            <ChevronRight className="ic" />
          </button>
          {week !== range.thisWeek && (
            <button type="button" className="btn" onClick={() => changeWeek(range.thisWeek)}>{t('thisWeek')}</button>
          )}
          <span className="sp" />
          <DatePicker value={jumpDay && mondayOf(jumpDay) === week ? jumpDay : ''} min={MIN_DAY} max={range.maxDay}
                      placeholder={t('jumpTo')} onChange={v => { setJumpDay(v); changeWeek(mondayOf(v)); }} />
        </div>

        {/* 国家筛选：可多选，一个都不选 = 全部 */}
        <div className="chips">
          <button type="button" className="chip" aria-pressed={!countries.length} onClick={() => toggleCountry(null)}>
            {t('allCountries')}
          </button>
          {rows && chipCodes.map(c => (
            <button key={c} type="button" className="chip" aria-pressed={countries.includes(c)} onClick={() => toggleCountry(c)}>
              <CountryFlag code={c} />{t(`country.${c}`, { defaultValue: c })}<em className="nf">{counts.get(c) ?? 0}</em>
            </button>
          ))}
        </div>

        {rows == null ? (
          <div className="mt-[30px] flex flex-col gap-2">
            {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-11" />)}
          </div>
        ) : days.length === 0 ? (
          <EmptyState icon={<CalendarDays />} text={t('emptyWeek')} />
        ) : days.map(list => {
          const ts = list[0].eventTime;
          return (
            <section key={ts}>
              <div className="day">
                {fmtDay(ts, { weekday: 'short' })}
                <small className="nf">{fmtDay(ts, { month: '2-digit', day: '2-digit' })}</small>
                {fmtDate(ts) === today && <span className="tag sm ink">{t('today')}</span>}
              </div>
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
          );
        })}
      </div>
    </div>
  );
}
