import './switch.css';

/** 开关（样式见 switch.css）。label 给读屏用，旁边写字的说明由调用方自己摆 */
export function Switch({ checked, onChange, disabled, label }: {
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <button type="button" role="switch" className="sw" aria-checked={checked} aria-label={label}
            disabled={disabled} onClick={() => onChange(!checked)} />
  );
}
