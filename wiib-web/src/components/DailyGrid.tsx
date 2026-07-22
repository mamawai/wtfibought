import { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '../lib/utils';
import type { TnDailyCell } from '../types/testnet';

interface Props {
  cells: TnDailyCell[];
  selectedDate?: string;
  onSelectDate: (date: string) => void;
}

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];
// 热力分档（绿/红各4级，越深盈亏越大）；封顶 /70 保证格内数字可读
const GAIN_BG = ['bg-gain/15', 'bg-gain/30', 'bg-gain/50', 'bg-gain/70'];
const LOSS_BG = ['bg-loss/15', 'bg-loss/30', 'bg-loss/50', 'bg-loss/70'];

function ym(date: string) {
  return date.slice(0, 7); // yyyy-MM
}

/**
 * 日交易网格（月历热力图）。每格=一天：上日期、中当天净盈亏、下笔数；
 * 绿赚红亏、颜色深浅表盈亏大小。点有交易的格子下钻当天明细。
 */
export function DailyGrid({ cells, selectedDate, onSelectDate }: Props) {
  const byDate = useMemo(() => {
    const m = new Map<string, TnDailyCell>();
    cells.forEach((c) => m.set(c.date, c));
    return m;
  }, [cells]);

  // 默认月份 = 数据里最新的月份，否则当前月
  const months = useMemo(() => Array.from(new Set(cells.map((c) => ym(c.date)))).sort(), [cells]);
  const latest = months.length ? months[months.length - 1] : new Date().toISOString().slice(0, 7);
  const [month, setMonth] = useState(latest);

  const [year, mon] = month.split('-').map(Number);
  // 当月最大|盈亏|，用于热力分档
  const maxAbs = useMemo(() => {
    let mx = 0;
    cells.forEach((c) => { if (ym(c.date) === month) mx = Math.max(mx, Math.abs(c.pnl)); });
    return mx || 1;
  }, [cells, month]);

  // 周一为首列的当月格子（前置空位对齐）
  const grid = useMemo(() => {
    const first = new Date(year, mon - 1, 1);
    const lead = (first.getDay() + 6) % 7; // 周一首列偏移
    const days = new Date(year, mon, 0).getDate();
    const arr: (string | null)[] = [];
    for (let i = 0; i < lead; i++) arr.push(null);
    for (let d = 1; d <= days; d++) {
      arr.push(`${year}-${String(mon).padStart(2, '0')}-${String(d).padStart(2, '0')}`);
    }
    return arr;
  }, [year, mon]);

  const shiftMonth = (delta: number) => {
    const d = new Date(year, mon - 1 + delta, 1);
    setMonth(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`);
  };

  const level = (absPnl: number) => Math.min(3, Math.floor((absPnl / maxAbs) * 4));

  return (
    <div className="pt-card rounded-lg p-4">
      {/* 月份切换 */}
      <div className="flex items-center justify-between mb-3">
        <button onClick={() => shiftMonth(-1)} className="w-7 h-7 rounded-md border border-border hover:bg-surface-hover flex items-center justify-center text-muted-foreground hover:text-primary transition-colors cursor-pointer">
          <ChevronLeft className="w-4 h-4" />
        </button>
        <span className="num text-sm font-bold">{month}</span>
        <button onClick={() => shiftMonth(1)} className="w-7 h-7 rounded-md border border-border hover:bg-surface-hover flex items-center justify-center text-muted-foreground hover:text-primary transition-colors cursor-pointer">
          <ChevronRight className="w-4 h-4" />
        </button>
      </div>

      {/* 星期表头 */}
      <div className="grid grid-cols-7 gap-1 mb-1">
        {WEEKDAYS.map((w) => (
          <div key={w} className="text-center text-[10px] text-muted-foreground font-bold">{w}</div>
        ))}
      </div>

      {/* 日格子 */}
      <div className="grid grid-cols-7 gap-1">
        {grid.map((date, i) => {
          if (!date) return <div key={`b${i}`} />;
          const cell = byDate.get(date);
          const day = Number(date.slice(8));
          const has = !!cell && (cell.tradeCount > 0 || cell.pnl !== 0);
          const up = cell ? cell.pnl >= 0 : true;
          const heat = has ? (up ? GAIN_BG : LOSS_BG)[level(Math.abs(cell!.pnl))] : '';
          const selected = date === selectedDate;
          return (
            <button
              key={date}
              disabled={!has}
              onClick={() => has && onSelectDate(date)}
              className={cn(
                'aspect-square rounded-lg p-1 flex flex-col items-center justify-center transition-all text-center',
                has ? 'cursor-pointer hover:ring-2 hover:ring-primary/40' : 'cursor-default',
                has ? heat : 'bg-muted/20',
                selected && 'ring-2 ring-primary',
              )}
            >
              <span className="text-[9px] text-muted-foreground leading-none self-start pl-0.5">{day}</span>
              {has ? (
                <>
                  <span className={cn('text-[11px] font-black tabular-nums leading-tight', up ? 'text-gain' : 'text-loss')}>
                    {up ? '+' : ''}{cell!.pnl.toFixed(1)}
                  </span>
                  <span className="text-[8px] text-muted-foreground leading-none">{cell!.tradeCount}笔</span>
                </>
              ) : (
                <span className="text-muted-foreground/30 text-xs">·</span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
