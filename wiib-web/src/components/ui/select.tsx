import { Fragment, useCallback, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn } from '../../lib/utils';
import './select.css';

export interface SelectOption<T extends string> {
  value: T;
  label: string;
  /** 分组名：跟上一项不同时，前面出一行小灰字 */
  group?: string;
}

/**
 * 选择框：样子同输入框，点开是一张下拉面板（样式见 select.css）。className 传 sm 小号、wide 铺满。
 * 点外面、按 Esc 收起；上下键在选项之间挪。
 */
export function Select<T extends string>({ value, onChange, options, className, placeholder, label }: {
  value: T;
  onChange: (v: T) => void;
  options: SelectOption<T>[];
  className?: string;
  /** 当前值不在选项里时按钮上的字 */
  placeholder?: string;
  /** 给读屏的名字 */
  label?: string;
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(wrapRef, close, open);

  // 面板右边放不下就贴右展开
  useLayoutEffect(() => {
    const m = menuRef.current;
    if (m) m.classList.toggle('rt', m.getBoundingClientRect().right > window.innerWidth - 8);
  }, [open]);

  const cur = options.find(o => o.value === value);

  const pick = (v: T) => {
    setOpen(false);
    btnRef.current?.focus();
    if (v !== value) onChange(v);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (!open) return;
    if (e.key === 'Escape') {
      // 别让外面的弹窗跟着关
      e.stopPropagation();
      setOpen(false);
      btnRef.current?.focus();
      return;
    }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const items = [...menuRef.current!.querySelectorAll<HTMLElement>('[role="option"]')];
    const down = e.key === 'ArrowDown';
    const i = items.indexOf(document.activeElement as HTMLElement);
    const next = i < 0 ? (down ? 0 : items.length - 1) : (i + (down ? 1 : -1) + items.length) % items.length;
    items[next].focus();
  };

  return (
    <div ref={wrapRef} className={cn('sel', open && 'open', className)} role="group" aria-label={label} onKeyDown={onKeyDown}>
      <button ref={btnRef} type="button" aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen(v => !v)}>
        <span className={cn(!cur && 'ph')}>{cur ? cur.label : placeholder}</span>
        <ChevronDown className="ic" />
      </button>
      {/* 开着才挂：收着的时候它也占位置，贴右边的选择框会把页面撑出横向滚动 */}
      {open && (
        <div ref={menuRef} className="menu" role="listbox" aria-label={label}>
          {options.map((o, i) => (
            <Fragment key={o.value}>
              {o.group && o.group !== options[i - 1]?.group && <div className="grp">{o.group}</div>}
              <button type="button" role="option" aria-selected={o.value === value} onClick={() => pick(o.value)}>
                {o.label}<Check className="ic" />
              </button>
            </Fragment>
          ))}
        </div>
      )}
    </div>
  );
}
