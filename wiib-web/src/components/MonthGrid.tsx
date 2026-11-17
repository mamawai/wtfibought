import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn, fmtDate, fmtSignedUsd } from '../lib/utils';
import './MonthGrid.css';

/** 月历的一格：哪天、那天净盈亏多少 */
export interface MonthCell {
  date: string; // yyyy-MM-dd
  pnl: number;
}

interface Props {
  cells: MonthCell[];
  /** 当前月份 yyyy-MM，受控——翻月要去后端拉那个月的数据，月份状态只能在外面 */
  month: string;
  onMonthChange: (month: string) => void;
  selectedDate?: string;
  onSelectDate: (date: string) => void;
  /** 左上角的名字，不给就是「月度盈亏」 */
  title?: string;
}

// 周一起头，存词表 key 不存文案（数组在组件外，拿不到 t）
const WEEKDAY_KEYS = ['grid.mon', 'grid.tue', 'grid.wed', 'grid.thu', 'grid.fri', 'grid.sat', 'grid.sun'];

const pad2 = (n: number) => String(n).padStart(2, '0');

/**
 * 一个月的盈亏月历：一格一天，绿赚红亏，越深盈亏越大（样式见 MonthGrid.css）。首页和 testnet 页在用。
 * 点有数的格子看当天明细；今天及以前没数的日子画一格空框，未来的日子只占位。
 */
export function MonthGrid({ cells, month, onMonthChange, selectedDate, onSelectDate, title }: Props) {
  const { t, i18n } = useTranslation('home');

  const [year, mon] = month.split('-').map(Number);
  const today = fmtDate();

  // 只算当前这个月的：给进来的可能跨了好几个月
  const monthCells = useMemo(() => cells.filter(c => c.date.startsWith(month)), [cells, month]);
  const byDate = useMemo(() => new Map(monthCells.map(c => [c.date, c])), [monthCells]);
  // 当月最大 |盈亏|，格子深浅按它归一；整月都是 0 时给 1 兜住除法
  const maxAbs = useMemo(() => Math.max(...monthCells.map(c => Math.abs(c.pnl)), 0) || 1, [monthCells]);
  const total = useMemo(() => monthCells.reduce((s, c) => s + c.pnl, 0), [monthCells]);

  const days = new Date(year, mon, 0).getDate();
  const lead = (new Date(year, mon - 1, 1).getDay() + 6) % 7;   // 周一首列偏移

  // 纯算术翻月，不绕 Date：构造出来的"某月1号本地时间"再转东八区可能跨月，翻着翻着就串了
  const shift = (delta: number) => {
    const n = year * 12 + (mon - 1) + delta;
    onMonthChange(`${Math.floor(n / 12)}-${pad2((n % 12) + 1)}`);
  };

  const label = new Date(year, mon - 1, 1)
    .toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, { year: 'numeric', month: 'long' });

  return (
    <div className="month">
      <div className="mh">
        <b>{title ?? t('grid.title')}</b>
        <span>
          <button type="button" className="xb" aria-label={t('grid.prevMonth')} onClick={() => shift(-1)}><ChevronLeft className="ic" /></button>
          {label}
          <button type="button" className="xb" aria-label={t('grid.nextMonth')} onClick={() => shift(1)}><ChevronRight className="ic" /></button>
        </span>
      </div>

      <div className="cal">
        {WEEKDAY_KEYS.map(k => <u key={k}>{t(k)}</u>)}
        {Array.from({ length: lead }, (_, i) => <span key={`b${i}`} />)}
        {Array.from({ length: days }, (_, i) => {
          const day = i + 1;
          const date = `${year}-${pad2(mon)}-${pad2(day)}`;
          const cell = byDate.get(date);
          // 未来的日子只占位；今天和以前没数的画一格空框
          if (!cell) return date <= today ? <span key={date} className="c none">{day}</span> : <span key={date} />;
          const k = Math.abs(cell.pnl) / maxAbs;
          const cellTitle = t('grid.cellTitle', { day, pnl: fmtSignedUsd(cell.pnl, 0) });
          return (
            <button
              key={date}
              type="button"
              style={{ '--k': `${Math.round(9 + k * 91)}%` } as React.CSSProperties}
              title={cellTitle}
              aria-label={cellTitle}
              onClick={() => onSelectDate(date)}
              className={cn('c', cell.pnl >= 0 ? 'g' : 'l', k > .6 && 'deep', date === today && 'td', date === selectedDate && 'sel')}
            >
              {day}
            </button>
          );
        })}
      </div>

      <div className="msum">
        <span>{t('grid.monthTotal')}</span>
        {/* 整月一格都没有时给「—」不给 +₮0.00 */}
        <b className={cn(monthCells.length > 0 && (total >= 0 ? 'up' : 'dn'))}>{monthCells.length ? fmtSignedUsd(total) : '—'}</b>
      </div>
    </div>
  );
}
