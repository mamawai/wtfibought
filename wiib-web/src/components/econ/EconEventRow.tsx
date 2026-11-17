import { Fragment, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, ArrowDown, ArrowUp, ChevronRight, Equal } from 'lucide-react';
import type { EconCalendarEvent } from '../../api';
import { currentLang } from '../../i18n';
import { findEntry, type EconEntry } from '../../lib/econCatalog';
import { expectation } from '../../lib/econValue';
import { cn, fmtDateTime } from '../../lib/utils';
import './EconEventRow.css';

/** 预期标签：橙=高于、蓝=低于、灰=符合，带箭头 */
const EXP_TAG = {
  above: { Icon: ArrowUp, tone: 'o' },
  below: { Icon: ArrowDown, tone: 'b' },
  inline: { Icon: Equal, tone: '' },
} as const;

/**
 * 财经日历一行：时间 · 国家货币 · 指标名 · 实际/预测/前值 + 预期标签，点开看解读。首页日历块和日历页共用。
 * 指标名：中文界面用核对清单里的中文名，英文界面照原标题；清单里查不到的打三角警告。
 * 美国事件的国家代码加深；下一条要公布的美国事件名字前闪一颗橙点。
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
  const { t } = useTranslation(['calendar', 'home']);
  const [open, setOpen] = useState(false);
  const entry = findEntry(r.country, r.title);
  const name = currentLang() === 'zh' && entry ? entry.zh : r.title;
  const exp = expectation(r.actual, r.forecast);
  const [date, time] = fmtDateTime(r.eventTime).split(' ');
  // 实际 · 预测 · 前值，有哪个显示哪个；讲话类三个都没有就不占位
  const vals = ([['actual', r.actual], ['forecast', r.forecast], ['previous', r.previous]] as const).filter(([, v]) => v);

  return (
    <div className={cn('ev', r.country === 'US' && 'us', bold && 'first', !showDate && 'nodate')}>
      <button type="button" aria-expanded={open} onClick={() => setOpen(v => !v)}>
        <time>{showDate && <><span>{date}</span>{' '}</>}<span>{time}</span></time>
        <span className="nm">
          {next && <i className="next" title={t('nextUs')} />}
          <span className="cc">{r.country} / {r.currency}</span>
          <span className="tt">{name}</span>
          {!entry && <span className="wn inline-flex self-center" title={t('unknownTip')}><AlertTriangle className="ic" /></span>}
          <ChevronRight className="ic chv" />
        </span>
        <span className="vals">
          {vals.map(([k, v], i) => {
            const text = `${t(`home:calendar.${k}`)} ${v}`;
            return (
              <Fragment key={k}>
                {i > 0 && <span>·</span>}
                {k === 'actual' ? <b>{text}</b> : <span>{text}</span>}
              </Fragment>
            );
          })}
          {exp && <ExpectationTag exp={exp} />}
        </span>
      </button>
      <div className={cn('col', open && 'open')}>
        <div>
          <div className="ex"><Explain event={r} entry={entry} /></div>
          {open && children}
        </div>
      </div>
    </div>
  );
}

function ExpectationTag({ exp }: { exp: keyof typeof EXP_TAG }) {
  const { t } = useTranslation('calendar');
  const { Icon, tone } = EXP_TAG[exp];
  return <span className={cn('tag sm', tone)}><Icon className="ic" />{t(exp)}</span>;
}

/** 解读：是什么 + 数字怎么看，美国事件多一句影响面；清单里没有的只说暂无解读 */
function Explain({ event: r, entry }: { event: EconCalendarEvent; entry: EconEntry | null }) {
  const { t, i18n } = useTranslation('calendar');
  if (!entry) return <p>{t('unknown')}</p>;
  const vars = { bank: t(`bank.${r.country}`, { defaultValue: t('bank.default') }), currency: r.currency };
  // 讲话、会议、预算、政治事件只有"是什么"，没有 read
  const readKey = `kind.${entry.kind}.read`;
  return (
    <>
      <p>{t(`kind.${entry.kind}.what`, vars)}</p>
      {i18n.exists(readKey, { ns: 'calendar' }) && <p>{t(readKey, vars)}</p>}
      {r.country === 'US' && <p>{t('usImpact')}</p>}
    </>
  );
}
