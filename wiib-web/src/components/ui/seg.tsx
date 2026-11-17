import { useLayoutEffect, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { cn } from '../../lib/utils';
import './seg.css';

export interface SegOption<T extends string> {
  value: T;
  label: ReactNode;
}

/** 把滑块摆到选中的那颗钮底下；animate=false 直接到位不滑 */
function placeThumb(box: HTMLElement, animate: boolean) {
  const th = box.querySelector<HTMLElement>('.th')!;
  const on = box.querySelector<HTMLElement>('[aria-pressed="true"]');
  th.style.transition = animate ? '' : 'none';
  if (!on) { th.style.width = '0'; return; }
  th.style.transform = `translateX(${on.offsetLeft}px)`;
  th.style.width = `${on.offsetWidth}px`;
}

/**
 * 段选：底下一片滑块滑到选中项（样式见 seg.css）。className 传 full 铺满、k-side 是做多 / 做空那种带方向色的大号。
 * onChange 返回 false = 这一下不让切（比如有持仓时换保证金模式），整排晃一下。左右方向键也能切。
 */
export function Seg<T extends string>({ value, onChange, options, className, label, disabled }: {
  value: T;
  onChange: (v: T) => void | boolean;
  options: SegOption<T>[];
  className?: string;
  /** 给读屏的名字 */
  label?: string;
  /** 整排置灰不可点 */
  disabled?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // 滑块摆过第一次没有：头一次直接到位，之后才滑
  const placedRef = useRef(false);

  // 换了选中项：滑过去
  useLayoutEffect(() => {
    placeThumb(ref.current!, placedRef.current);
    placedRef.current = true;
  }, [value]);

  // 哪颗钮的大小变了（文字换了、字体到位、窗口变宽）滑块就重摆，不带动画
  useLayoutEffect(() => {
    const box = ref.current!;
    const ro = new ResizeObserver(() => placeThumb(box, false));
    box.querySelectorAll('button').forEach(b => ro.observe(b));
    return () => ro.disconnect();
  }, [options.length]);

  const pick = (v: T) => {
    if (v === value || onChange(v) !== false) return;
    // 晃一下：先摘掉再挂，连着点也能重播
    const el = ref.current!;
    el.classList.remove('nope');
    void el.offsetWidth;
    el.classList.add('nope');
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const d = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
    if (!d) return;
    const btns = [...e.currentTarget.querySelectorAll('button')];
    const i = btns.indexOf(document.activeElement as HTMLButtonElement) + d;
    if (i < 0 || i >= btns.length) return;
    e.preventDefault();
    btns[i].focus();
    pick(options[i].value);
  };

  return (
    <div ref={ref} className={cn('seg', className)} role="group" aria-label={label} data-val={value}
         onKeyDown={onKeyDown} onAnimationEnd={e => e.currentTarget.classList.remove('nope')}>
      <i className="th" />
      {options.map(o => (
        <button key={o.value} type="button" disabled={disabled} aria-pressed={o.value === value} onClick={() => pick(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}
