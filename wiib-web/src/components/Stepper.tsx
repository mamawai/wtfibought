import { Minus, Plus } from 'lucide-react';
import './Stepper.css';

/** 步进：两头减号加号，中间的数能直接改，超出 min~max 的收回到边上（样式见 Stepper.css） */
export function Stepper({ value, onChange, min, max, label, lessLabel, moreLabel, disabled }: {
  value: number;
  onChange: (v: number) => void;
  min: number;
  max: number;
  /** 读屏名：中间那个数是什么 */
  label: string;
  lessLabel: string;
  moreLabel: string;
  disabled?: boolean;
}) {
  return (
    <div className="step">
      <button type="button" aria-label={lessLabel} disabled={disabled || value <= min} onClick={() => onChange(value - 1)}>
        <Minus className="ic" />
      </button>
      <input inputMode="numeric" value={value} aria-label={label} disabled={disabled}
             onChange={e => onChange(Math.max(min, Math.min(max, parseInt(e.target.value, 10) || min)))} />
      <button type="button" aria-label={moreLabel} disabled={disabled || value >= max} onClick={() => onChange(value + 1)}>
        <Plus className="ic" />
      </button>
    </div>
  );
}
