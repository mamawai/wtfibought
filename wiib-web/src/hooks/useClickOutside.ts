import { useEffect, type RefObject } from 'react';

/** 点击 ref 外部时回调；when=false 时不挂监听（如弹层未打开）。 */
export function useClickOutside(ref: RefObject<HTMLElement | null>, onOutside: () => void, when = true) {
  useEffect(() => {
    if (!when) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onOutside();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
    // onOutside 每次渲染都是新引用，重挂监听成本可忽略
  }, [ref, onOutside, when]);
}
