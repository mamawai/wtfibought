import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight, X } from 'lucide-react';
import { DatePicker } from './ui/date-picker';
import { quantApi, type NewsEventItem } from '../api';
import { currentLang } from '../i18n';
import { dayBounds, fmtDate, fmtTime, DAY_MS } from '../lib/utils';

/**
 * 实时快讯（首页，与最新成交并列；样式见 pages/Home.css .nw）：读 news_event 存档（采集轨定时存中文，译文随后补上）。
 * <p>默认最新 100 条 + 60s 轻轮询；也可按天翻看，选了日期就只拉那一天并停掉轮询。
 * <p>中英两套一起到，切语言不重拉。英文界面只展示标题正文都译好的那些，没译完的不展示，不拿中文凑。
 */
export function NewsFlashCard() {
  const { t } = useTranslation('home');
  const en = currentLang() === 'en';
  const [items, setItems] = useState<NewsEventItem[] | null>(null);
  // null=最新（轮询）；yyyy-MM-dd=只看那一天（拉一次）
  const [day, setDay] = useState<string | null>(null);
  const today = fmtDate();

  useEffect(() => {
    let alive = true;
    const bounds = day ? dayBounds(day) : null;
    const load = () => quantApi.news(bounds?.from, bounds?.to)
      .then(list => { if (alive) setItems(list); })
      .catch(() => { if (alive) setItems(prev => prev ?? []); });
    load();
    if (bounds) return () => { alive = false; };
    const timer = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(timer); };
  }, [day]);

  // 换天先清成骨架，别让上一天的列表挂着
  const changeDay = (d: string | null) => { setItems(null); setDay(d); };
  // 没选日期时从今天起步
  const shiftDay = (delta: number) => changeDay(fmtDate(dayBounds(day ?? today).from + delta * DAY_MS));

  const shown = items == null ? null : en ? items.filter(n => n.titleEn && n.contentEn) : items;

  return (
    <>
      <div className="sec-h">
        <h2>{t('news.title')}</h2>
        {/* 按天翻看：前一天 / 日期 / 后一天；清掉回到最新 */}
        <div className="dstep">
          <button type="button" className="ib sm" aria-label={t('news.prevDay')} onClick={() => shiftDay(-1)}><ChevronLeft className="ic" /></button>
          <DatePicker value={day ?? ''} max={today} onChange={changeDay} />
          <button type="button" className="ib sm" aria-label={t('news.nextDay')} disabled={!day || day >= today}
                  onClick={() => shiftDay(1)}><ChevronRight className="ic" /></button>
          {day && (
            <button type="button" className="xb" aria-label={t('news.allDays')} onClick={() => changeDay(null)}><X className="ic" /></button>
          )}
        </div>
      </div>
      {shown == null ? (
        <div className="feed">
          {Array.from({ length: 3 }).map((_, i) => <i key={i} className="sk" style={{ height: 44 }} />)}
        </div>
      ) : shown.length === 0 ? (
        <div className="empty"><b>{day ? t('news.emptyDay') : t('news.empty')}</b></div>
      ) : (
        <div className="feed">
          {shown.map(n => {
            const title = en ? n.titleEn : n.title;
            const content = en ? n.contentEn : n.content;
            return (
              <article key={n.id} className="nw">
                <time>{fmtTime(n.publishedAt)}</time>
                <div className="min-w-0">
                  {n.url
                    ? <a href={n.url} target="_blank" rel="noopener noreferrer"><h3>{title}</h3></a>
                    : <h3>{title}</h3>}
                  {/* 全文不截断：这一栏给的宽度就是用来放全文的 */}
                  {content && <p>{content}</p>}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </>
  );
}
