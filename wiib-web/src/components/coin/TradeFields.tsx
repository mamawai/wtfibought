import { cn } from '../../lib/utils';
import { ArrowLeftRight } from 'lucide-react';
import { POSITION_PCTS } from './futuresMath';

/** 塞进 .input 里的裸输入：撑满、去掉自带边框和上下箭头，字体跟着 .input 走 */
const RAW = 'flex-1 min-w-0 bg-transparent border-0 outline-none [font:inherit] [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none';

/**
 * 海报 .input 数字框：左输入右灰单位。
 * 给了 onUnitClick 时单位是个按钮，点一下换单位（币 ↔ USDT）。
 */
export function NumInput({ value, onChange, placeholder, step, min, max, unit, onUnitClick, unitTitle, className }: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  step?: string | number;
  min?: string | number;
  max?: string | number;
  unit?: string;
  onUnitClick?: () => void;
  unitTitle?: string;
  className?: string;
}) {
  return (
    <div className={cn('input num', className)}>
      <input
        type="number"
        className={RAW}
        value={value}
        placeholder={placeholder}
        step={step}
        min={min}
        max={max}
        onChange={e => onChange(e.target.value)}
      />
      {unit && (onUnitClick
        ? (
          <button type="button" className="unit inline-flex items-center gap-1.5 self-stretch pl-3 border-l border-border cursor-pointer" title={unitTitle} aria-label={unitTitle} onClick={onUnitClick}>
            {unit}
            <ArrowLeftRight aria-hidden="true" className="size-3 shrink-0" />
          </button>
        )
        : <span className="unit">{unit}</span>
      )}
    </div>
  );
}

/** 25/50/75/100% 一排：相邻共边，选中填墨 */
export function PctRow({ active, onPick }: {
  /** 当前正好落在哪一档（0.25/0.5/0.75/1），没落在任何一档传 null */
  active: number | null;
  onPick: (pct: number) => void;
}) {
  return (
    <div className="grid grid-cols-4">
      {POSITION_PCTS.map(pct => (
        <button
          key={pct}
          type="button"
          onClick={() => onPick(pct)}
          className={cn(
            'cursor-pointer border border-border border-l-0 first:border-l py-1.5 text-[12.5px] font-semibold transition-colors',
            active === pct ? 'bg-foreground text-background border-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {pct * 100}%
        </button>
      ))}
    </div>
  );
}
