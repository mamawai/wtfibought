import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { cn } from '../../lib/utils';

export interface TabItem<T extends string> {
  value: T;
  label: ReactNode;
  /** 名字后面的小计数 */
  count?: number;
}

/** 把墨线摆到当前项底下，再把当前项滚进这一排的视野；animate=false 直接到位不滑 */
function placeBar(box: HTMLElement, animate: boolean) {
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
  // 有哪几项：换了就是换了一批钮，要重新挂观察
  const keys = items.map(it => it.value).join('|');

  // 换了当前项：滑过去
  useLayoutEffect(() => {
    placeBar(ref.current!, placedRef.current);
    placedRef.current = true;
  }, [value]);

  // 这一排或哪一项的大小变了（文字换了、字体到位、窗口变宽）墨线就重摆，不带动画
  useLayoutEffect(() => {
    const box = ref.current!;
    const ro = new ResizeObserver(() => placeBar(box, false));
    ro.observe(box);
    box.querySelectorAll('button').forEach(b => ro.observe(b));
    return () => ro.disconnect();
  }, [keys]);

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
