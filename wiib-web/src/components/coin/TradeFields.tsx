import type { ReactNode, Ref } from 'react';
import { cn } from '../../lib/utils';
import { ArrowLeftRight } from 'lucide-react';
import { POSITION_PCTS } from './futuresMath';

/** 裸数字框去掉上下箭头 */
const RAW = '[appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none';

/**
 * 数字输入框（index.css 的 .inp）：左边输入，右边灰单位；className 传 sm 是小号。
 * 给了 onUnitClick 时单位是个按钮，点一下换单位（币 ↔ USDT），unitFlip 让上面的换向箭头转半圈。
 * children 排在单位后面（划转的「全部」这类框里的小钮）。
 */
export function NumInput({ value, onChange, placeholder, step, min, max, unit, onUnitClick, unitTitle, unitFlip, className, id, ref, children }: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  step?: string | number;
  min?: string | number;
  max?: string | number;
  unit?: string;
  onUnitClick?: () => void;
  unitTitle?: string;
  unitFlip?: boolean;
  className?: string;
  id?: string;
  /** React 19：ref 是普通 prop，挂到里面的 input 上 */
  ref?: Ref<HTMLInputElement>;
  children?: ReactNode;
}) {
  return (
    <label className={cn('inp', className)}>
      <input
        ref={ref}
        id={id}
        type="number"
        inputMode="decimal"
        autoComplete="off"
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
          <button type="button" className={cn('u', unitFlip && 'flip')} title={unitTitle} aria-label={unitTitle} onClick={onUnitClick}>
            <span>{unit}</span>
            <ArrowLeftRight aria-hidden="true" className="ic" />
          </button>
        )
        : <span className="u">{unit}</span>
      )}
      {children}
    </label>
  );
}

/** 25/50/75/100% 一排（index.css 的 .pct），选中填墨 */
export function PctRow({ active, onPick }: {
  /** 当前正好落在哪一档（0.25/0.5/0.75/1），没落在任何一档传 null */
  active: number | null;
  onPick: (pct: number) => void;
}) {
  return (
    <div className="pct">
      {POSITION_PCTS.map(pct => (
        <button key={pct} type="button" aria-pressed={active === pct} onClick={() => onPick(pct)}>
          {pct * 100}%
        </button>
      ))}
    </div>
  );
}
