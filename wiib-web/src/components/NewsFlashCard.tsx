import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { Skeleton } from './ui/skeleton';
import { useStagger } from '../hooks/useStagger';
import { quantApi, type NewsEventItem } from '../api';
import { currentLang } from '../i18n';
import { dayBounds, fmtDate, fmtTime, DAY_MS } from '../lib/utils';

/** 首页快讯与最新成交并排，两卡列表共用这个高度上限，卡底才齐平（LatestTradesCard 引用同一个） */
export const FEED_MAX_H = 'max-h-[560px] overflow-y-auto';

/**
 * 实时快讯（首页，与最新成交并列）：读 news_event 存档（采集轨定时打标+翻译后落库）。
 * <p>默认最新 100 条 + 60s 轻轮询；也可按天翻看，选了日期就只拉那一天并停掉轮询。
 * <p>中英两套一起到，切语言不重拉。英文界面只展示标题正文都译好的那些，没译完的不展示，不拿中文凑。
 */
export function NewsFlashCard() {
  const { t } = useTranslation('home');
  const en = currentLang() === 'en';
  const [items, setItems] = useState<NewsEventItem[] | null>(null);
  // null=最新（轮询）；yyyy-MM-dd=只看那一天（拉一次）
  const [day, setDay] = useState<string | null>(null);
  const listRef = useStagger<HTMLDivElement>();
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
      <div className="sec-h flex-wrap">
        <h2>{t('news.title')}</h2>
        {/* 按天翻看：前后一天 + 日期框；清掉回到最新 */}
        <div className="ml-auto self-center flex items-center gap-1.5">
          <button type="button" className="btn xs" onClick={() => shiftDay(-1)}>{t('news.prevDay')}</button>
          <input type="date" value={day ?? ''} max={today}
                 onChange={e => changeDay(e.target.value || null)}
                 className="input num h-7 px-2 text-[13px]" />
          <button type="button" className="btn xs disabled:opacity-40" disabled={!day || day >= today}
                  onClick={() => shiftDay(1)}>{t('news.nextDay')}</button>
          {day && (
            <button type="button" className="btn xs" aria-label={t('news.allDays')} onClick={() => changeDay(null)}>
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>
      {shown == null ? (
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-11" />)}
        </div>
      ) : shown.length === 0 ? (
        <div className="py-10 text-center text-sm text-muted-foreground">{day ? t('news.emptyDay') : t('news.empty')}</div>
      ) : (
        <div ref={listRef} className={FEED_MAX_H}>
          {shown.map(n => {
            const title = en ? n.titleEn : n.title;
            const content = en ? n.contentEn : n.content;
            return (
              <div key={n.id} className="grid grid-cols-[56px_1fr] gap-4 py-4 border-b border-border">
                <span className="num text-[13px] text-muted-foreground pt-[3px]">{fmtTime(n.publishedAt)}</span>
                <div className="min-w-0 pr-6">
                  {n.url
                    ? <a href={n.url} target="_blank" rel="noopener noreferrer" className="block text-[18px] font-bold tracking-[-0.01em] leading-[1.35] break-words">{title}</a>
                    : <div className="text-[18px] font-bold tracking-[-0.01em] leading-[1.35] break-words">{title}</div>}
                  {/* 全文不截断：2/3 宽度是给全文腾的，截两行就白拿这个宽度了 */}
                  {content && (
                    <div className="mt-1 text-sm text-muted-foreground leading-[1.45] break-words">{content}</div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
