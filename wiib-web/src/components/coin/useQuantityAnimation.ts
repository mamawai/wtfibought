import { useCallback, useEffect, useRef } from 'react';
import { getStepPrecision } from './futuresMath';

/** 数量输入缓动：从当前值 ease-out 到目标值（仓位百分比按钮用），卸载时取消动画帧 */
export function useQuantityAnimation(current: string, setValue: (v: string) => void) {
  const animRef = useRef(0);

  const animate = useCallback((target: number, step: number) => {
    cancelAnimationFrame(animRef.current);
    const from = parseFloat(current) || 0;
    const duration = 350;
    const start = performance.now();
    const precision = getStepPrecision(step);
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      const ease = 1 - (1 - t) ** 3;
      const v = from + (target - from) * ease;
      setValue(v.toFixed(precision).replace(/0+$/, '').replace(/\.$/, ''));
      if (t < 1) animRef.current = requestAnimationFrame(tick);
    };
    animRef.current = requestAnimationFrame(tick);
  }, [current, setValue]);

  useEffect(() => () => cancelAnimationFrame(animRef.current), []);

  return animate;
}
