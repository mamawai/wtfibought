import { Fragment, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, ArrowDown, ArrowUp, ChevronRight, Equal } from 'lucide-react';
import { CountryFlag } from '../CountryFlag';
import type { EconCalendarEvent } from '../../api';
import { currentLang } from '../../i18n';
import { findEntry, type EconEntry } from '../../lib/econCatalog';
import { expectation } from '../../lib/econValue';
import { cn, fmtDateTime } from '../../lib/utils';

/** 预期标签：橙=高于、蓝=低于、灰=符合，带箭头；字墨色，颜色在边框、淡底和箭头上 */
const EXP_TAG = {
  above: { Icon: ArrowUp, cls: 'border-primary bg-primary/10 [&>svg]:text-primary' },
  below: { Icon: ArrowDown, cls: 'border-info bg-info/10 [&>svg]:text-info' },
  inline: { Icon: Equal, cls: 'border-border text-muted-foreground' },
} as const;

/**
 * 财经日历一行：时间 · 国旗 · 指标名 · 实际/预测/前值 + 预期标签，点开看解读。首页日历卡和日历页共用。
 * 指标名：中文界面用核对清单里的中文名，英文界面照原标题；清单里查不到的打三角警告。
 * 美国事件左侧主色竖条 + 淡底；下一条要公布的美国事件名字前闪一个小方点。
 */
export function EconEventRow({ event: r, bold, next, showDate = true, children }: {
  event: EconCalendarEvent;
  bold?: boolean;
  /** 下一条将要公布的美国事件 */
  next?: boolean;
  /** 日历页按天分组，行里只要时分 */
  showDate?: boolean;
  /** 展开区解读下面的内容（日历页放走势图），展开了才渲染 */
  children?: ReactNode;
}) {
  const { t } = useTranslation('calendar');
  const [open, setOpen] = useState(false);
  const entry = findEntry(r.country, r.title);
  const name = currentLang() === 'zh' && entry ? entry.zh : r.title;
  const exp = expectation(r.actual, r.forecast);
  // 手机（<640px）时间列两行、数值换到标题下一行；sm 起三列单行
  const [date, time] = fmtDateTime(r.eventTime).split(' ');

  return (
    <div className="relative isolate">
      {/* 美国行：两边各铺 12px 的淡底垫在最下层，左侧主色竖条压在悬停底色上面 */}
      {r.country === 'US' && (
        <>
          <span aria-hidden className="pointer-events-none absolute inset-y-0 -inset-x-3 -z-10 bg-primary/[.05]" />
          <span aria-hidden className="pointer-events-none absolute inset-y-0 -left-3 z-10 w-0.5 bg-primary" />
        </>
      )}
      <details className="group border-b border-border" onToggle={e => setOpen(e.currentTarget.open)}>
        <summary className={cn('hov grid grid-cols-[44px_1fr] sm:grid-cols-[92px_1fr_auto] gap-x-3 gap-y-1 items-baseline py-2.5 text-[14px] cursor-pointer list-none select-none [&::-webkit-details-marker]:hidden',
          bold && 'font-semibold')}>
          <span className="num text-[13px] text-muted-foreground">
            {showDate && <><span className="block sm:inline">{date}</span>{' '}</>}
            <span className="block sm:inline">{time}</span>
          </span>
          <span className="min-w-0 flex flex-wrap sm:flex-nowrap items-baseline gap-x-2">
            {next && (
              <span title={t('nextUs')}
                    className="self-center shrink-0 w-1.5 h-1.5 bg-primary animate-[pt-pulse_1.2s_ease-in-out_infinite] motion-reduce:animate-none" />
            )}
            <CountryFlag code={r.country} className="self-center" />
            <b className="shrink-0 text-[12px] font-bold">{r.country} / {r.currency}</b>
            {/* 手机上整段换行，sm 起单行截断 */}
            <span className="min-w-0 flex-auto sm:truncate">{name}</span>
            {!entry && (
              <span className="wn inline-flex self-center shrink-0" title={t('unknownTip')}>
                <AlertTriangle className="w-3 h-3" />
              </span>
            )}
            <ChevronRight className="w-3 h-3 self-center shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
          </span>
          <span className="col-start-2 sm:col-start-auto flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <Values row={r} />
            {exp && <ExpectationTag exp={exp} />}
          </span>
        </summary>
        <div className="pb-3 space-y-3">
          <div className="pl-[56px] sm:pl-[104px]"><Explain event={r} entry={entry} /></div>
          {open && children}
        </div>
      </details>
    </div>
  );
}

function ExpectationTag({ exp }: { exp: keyof typeof EXP_TAG }) {
  const { t } = useTranslation('calendar');
  const { Icon, cls } = EXP_TAG[exp];
  return (
    <span className={cn('self-center inline-flex items-center gap-0.5 shrink-0 px-1 border text-[11px] font-bold whitespace-nowrap', cls)}>
      <Icon className="w-3 h-3" />{t(exp)}
    </span>
  );
}

/** 解读：是什么 + 数字怎么看，美国事件多一句影响面；清单里没有的只说暂无解读 */
function Explain({ event: r, entry }: { event: EconCalendarEvent; entry: EconEntry | null }) {
  const { t, i18n } = useTranslation('calendar');
  if (!entry) return <p className="text-sm text-muted-foreground">{t('unknown')}</p>;
  const vars = { bank: t(`bank.${r.country}`, { defaultValue: t('bank.default') }), currency: r.currency };
  // 讲话、会议、预算、政治事件只有"是什么"，没有 read
  const readKey = `kind.${entry.kind}.read`;
  return (
    <div className="space-y-1 text-sm text-muted-foreground leading-[1.6]">
      <p>{t(`kind.${entry.kind}.what`, vars)}</p>
      {i18n.exists(readKey, { ns: 'calendar' }) && <p>{t(readKey, vars)}</p>}
      {r.country === 'US' && <p>{t('usImpact')}</p>}
    </div>
  );
}

/** 实际 · 预测 · 前值，有哪个显示哪个，实际值加深；讲话类三个都没有就不占位 */
function Values({ row }: { row: EconCalendarEvent }) {
  const { t } = useTranslation('home');
  const parts = ([['actual', row.actual], ['forecast', row.forecast], ['previous', row.previous]] as const)
    .filter(([, v]) => v);
  if (!parts.length) return null;
  return (
    // 手机上只在 · 处折行，"实际 3.1%" 这种标签和数值不拆开
    <span className="num sm:whitespace-nowrap text-[12px] text-muted-foreground">
      {parts.map(([k, v], i) => (
        <Fragment key={k}>
          {i > 0 && ' · '}
          <span className={cn('whitespace-nowrap', k === 'actual' && 'text-foreground font-bold')}>
            {t(`calendar.${k}`)} {v}
          </span>
        </Fragment>
      ))}
    </span>
  );
}
