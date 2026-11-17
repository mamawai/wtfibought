import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { cn } from '../../lib/utils';

export interface TabItem<T extends string> {
  value: T;
  label: ReactNode;
  /** 名字后面的小计数 */
  count?: number;
}

/**
 * 文字页签：底下一根墨线滑到当前项（样式见 index.css .tabs）。className 传 lg 是当页面标题用的大字。
 * 窄了横向滚，当前项不在可见范围里会自己滚进来。
 */
export function Tabs<T extends string>({ value, onChange, items, className, label }: {
  value: T;
  onChange: (v: T) => void;
  items: TabItem<T>[];
  className?: string;
  /** 给读屏的名字 */
  label?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // 墨线摆过第一次没有：头一次直接到位，之后才滑
  const placedRef = useRef(false);
  // 文字变了（切语言、计数变了）宽度跟着变，墨线要重摆
  const sig = items.map(it => `${it.value}:${typeof it.label === 'string' ? it.label : ''}:${it.count ?? ''}`).join('|');

  useLayoutEffect(() => {
    const box = ref.current;
    if (!box) return;
    const place = (animate: boolean) => {
      const bar = box.querySelector<HTMLElement>('.bar2')!;
      const on = box.querySelector<HTMLElement>('[aria-selected="true"]');
      bar.style.transition = animate ? '' : 'none';
      if (!on) { bar.style.width = '0'; return; }
      bar.style.transform = `translateX(${on.offsetLeft}px)`;
      bar.style.width = `${on.offsetWidth}px`;
      // 只横着滚这一排，不动页面
      const l = on.offsetLeft, r = l + on.offsetWidth;
      if (l < box.scrollLeft) box.scrollLeft = l;
      else if (r > box.scrollLeft + box.clientWidth) box.scrollLeft = r - box.clientWidth;
    };
    place(placedRef.current);
    placedRef.current = true;
    const still = () => place(false);
    window.addEventListener('resize', still);
    void document.fonts.ready.then(still);
    return () => window.removeEventListener('resize', still);
  }, [value, sig]);

  return (
    <div ref={ref} className={cn('tabs', className)} role="tablist" aria-label={label}>
      <i className="bar2" />
      {items.map(it => (
        <button key={it.value} type="button" role="tab" aria-selected={it.value === value} onClick={() => onChange(it.value)}>
          {it.label}
          {it.count != null && <em>{it.count}</em>}
        </button>
      ))}
    </div>
  );
}
