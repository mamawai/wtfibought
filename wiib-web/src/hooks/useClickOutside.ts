import { useEffect, type RefObject } from 'react';

/**
 * 点击 ref 外部时回调；when=false 时不挂监听（如弹层未打开）。
 * 盖在 K 线图上的弹层传 event='pointerdown'：手机轻点图表时 LWC 会压掉兼容的 mousedown，监听 mousedown 收不起来。
 */
export function useClickOutside(ref: RefObject<HTMLElement | null>, onOutside: () => void, when = true,
                                event: 'mousedown' | 'pointerdown' = 'mousedown') {
  useEffect(() => {
    if (!when) return;
    const handler = (e: Event) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onOutside();
    };
    document.addEventListener(event, handler);
    return () => document.removeEventListener(event, handler);
    // onOutside 每次渲染都是新引用，重挂监听成本可忽略
  }, [ref, onOutside, when, event]);
}
