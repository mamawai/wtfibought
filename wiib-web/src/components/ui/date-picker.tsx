import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { currentLang } from '../../i18n';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn, fmtDate } from '../../lib/utils';

/** 弹层宽：7 格 × 32 + 内边距 + 边框 */
const POP_W = 242;

/** 拼 yyyy-MM-dd，m 从 0 起 */
const ymd = (y: number, m: number, d: number) =>
  `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;

interface DatePickerProps {
  /** yyyy-MM-dd；'' = 没选 */
  value: string;
  onChange: (v: string) => void;
  min?: string;
  max?: string;
  disabled?: boolean;
  /** 触发钮样式 */
  className?: string;
}

/**
 * 日期选择：点开一张月历，月份和星期名跟站内语言走。
 * 值一律 yyyy-MM-dd 字符串，范围直接按字典序比。
 */
export function DatePicker({ value, onChange, min, max, disabled, className }: DatePickerProps) {
  const { t } = useTranslation('common');
  // 月历翻到哪个月 [年, 月(0 起)]；null = 收起
  const [view, setView] = useState<[number, number] | null>(null);
  const [alignRight, setAlignRight] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const close = useCallback(() => setView(null), []);
  useClickOutside(wrapRef, close, view != null);

  // 月份越界自动进退年
  const goMonth = (y: number, m: number) => {
    const d = new Date(Date.UTC(y, m, 1));
    setView([d.getUTCFullYear(), d.getUTCMonth()]);
  };

  const toggle = () => {
    if (view) { close(); return; }
    // 没选就从今天那个月翻起，今天出了范围就从范围边上那个月翻起
    const today = fmtDate();
    const base = value || (max && today > max ? max : min && today < min ? min : today);
    // 右边放不下就贴右展开
    setAlignRight(wrapRef.current!.getBoundingClientRect().left + POP_W > window.innerWidth - 8);
    goMonth(Number(base.slice(0, 4)), Number(base.slice(5, 7)) - 1);
  };

  const pick = (d: string) => {
    onChange(d);
    close();
    btnRef.current?.focus();
  };

  return (
    <div ref={wrapRef} className="relative"
         onKeyDown={e => { if (e.key === 'Escape' && view) { close(); btnRef.current?.focus(); } }}>
      <button ref={btnRef} type="button" disabled={disabled} onClick={toggle}
              aria-haspopup="dialog" aria-expanded={view != null}
              className={cn('inline-flex items-center gap-1.5 cursor-pointer disabled:cursor-not-allowed', className)}>
        <span className={value ? 'text-foreground' : 'text-muted-foreground'}>{value || t('datePicker.placeholder')}</span>
        <CalendarDays className="w-3 h-3 text-muted-foreground" />
      </button>
      {view && (
        <div role="dialog" style={{ width: POP_W }}
             className={cn('absolute top-[calc(100%+6px)] z-50 p-2 border border-foreground bg-background',
               alignRight ? 'right-0' : 'left-0')}>
          <MonthGrid y={view[0]} m={view[1]} value={value} min={min} max={max} onMonth={goMonth} onPick={pick} />
        </div>
      )}
    </div>
  );
}

/** 一个月的月历：翻月表头 + 星期行 + 日期格，周一打头，按 UTC 推 */
function MonthGrid({ y, m, value, min, max, onMonth, onPick }: {
  y: number;
  m: number;
  value: string;
  min?: string;
  max?: string;
  onMonth: (y: number, m: number) => void;
  onPick: (d: string) => void;
}) {
  const { t } = useTranslation('common');
  const today = fmtDate();
  const ym = ymd(y, m, 1).slice(0, 7);
  const days = new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  // 月初前空几格：getUTCDay 0=周日，挪成 0=周一
  const lead = (new Date(Date.UTC(y, m, 1)).getUTCDay() + 6) % 7;
  const locale = currentLang() === 'en' ? 'en-US' : 'zh-CN';
  const title = new Date(Date.UTC(y, m, 1)).toLocaleDateString(locale, { year: 'numeric', month: 'long', timeZone: 'UTC' });
  // 2024-01-01 是周一，连取 7 天当表头
  const weekdays = Array.from({ length: 7 }, (_, i) =>
    new Date(Date.UTC(2024, 0, 1 + i)).toLocaleDateString(locale, { weekday: 'narrow', timeZone: 'UTC' }));

  return (
    <>
      <div className="flex items-center justify-between mb-1">
        <button type="button" className="ibtn w-7 h-7" aria-label={t('datePicker.prevMonth')}
                disabled={!!min && ym <= min.slice(0, 7)} onClick={() => onMonth(y, m - 1)}>
          <ChevronLeft className="w-3.5 h-3.5" />
        </button>
        <span className="text-[13px] font-bold text-foreground">{title}</span>
        <button type="button" className="ibtn w-7 h-7" aria-label={t('datePicker.nextMonth')}
                disabled={!!max && ym >= max.slice(0, 7)} onClick={() => onMonth(y, m + 1)}>
          <ChevronRight className="w-3.5 h-3.5" />
        </button>
      </div>
      <div className="grid grid-cols-7">
        {weekdays.map((w, i) => (
          <span key={i} className="h-7 flex items-center justify-center text-[11px] text-muted-foreground">{w}</span>
        ))}
        {Array.from({ length: lead }, (_, i) => <span key={`pad${i}`} />)}
        {Array.from({ length: days }, (_, i) => {
          const d = ymd(y, m, i + 1);
          const off = (!!min && d < min) || (!!max && d > max);
          return (
            <button key={d} type="button" disabled={off} aria-pressed={d === value} onClick={() => onPick(d)}
                    className={cn('num h-8 text-[12px] transition-colors',
                      d === value ? 'bg-foreground text-background'
                        : off ? 'text-muted-foreground/40 cursor-not-allowed'
                          : cn('hover:bg-card-2', d === today && 'text-primary font-bold'))}>
              {i + 1}
            </button>
          );
        })}
      </div>
    </>
  );
}
