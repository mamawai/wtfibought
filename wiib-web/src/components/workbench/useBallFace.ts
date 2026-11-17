import { useEffect, type RefObject } from 'react';

/** 眼睛最多偏这么多 px */
const MAX_EYE = 3.2;
/** 鼠标离球心这么近（还没碰到）就睁大一点 */
const NEAR_PX = 110;
/** 答案到了笑这么久 */
const HAPPY_MS = 2200;
const REDUCED = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * 悬浮球的脸：眼睛跟着鼠标看，隔几秒眨一下，鼠标停久了自己回正、偶尔朝别处瞥一眼；answered 一挂上笑两秒。
 * 研判中（data-busy）这些都让开，由 CSS 接管成"往上看着想"。
 * 全部直接改 DOM（--ex/--ey 两个变量和 blink / near / happy 三个类），不走 React 渲染。
 */
export function useBallFace(ref: RefObject<HTMLButtonElement | null>, answered: boolean) {
  useEffect(() => {
    const ball = ref.current;
    const face = ball?.querySelector<HTMLElement>('.face');
    if (!ball || !face || REDUCED) return;
    const busy = () => ball.dataset.busy === 'true';

    // ex/ey 眼睛现在偏到哪，tx/ty 要去哪
    let ex = 0, ey = 0, tx = 0, ty = 0, raf = 0, lastMove = 0;
    const tick = () => {
      ex += (tx - ex) * .18;
      ey += (ty - ey) * .18;
      face.style.setProperty('--ex', `${ex.toFixed(2)}px`);
      face.style.setProperty('--ey', `${ey.toFixed(2)}px`);
      raf = Math.abs(tx - ex) + Math.abs(ty - ey) > .05 ? requestAnimationFrame(tick) : 0;
    };
    const kick = () => { if (!raf) raf = requestAnimationFrame(tick); };

    const onMove = (e: PointerEvent) => {
      lastMove = performance.now();
      const r = ball.getBoundingClientRect();
      const dx = e.clientX - (r.left + r.width / 2), dy = e.clientY - (r.top + r.height / 2);
      // 越远偏得越多，200px 外到顶
      const d = Math.hypot(dx, dy) || 1, k = Math.min(1, d / 200);
      tx = dx / d * MAX_EYE * k;
      ty = dy / d * MAX_EYE * k;
      ball.classList.toggle('near', d < NEAR_PX);
      kick();
    };
    const onLeave = () => { tx = ty = 0; ball.classList.remove('near'); kick(); };

    // 鼠标停了几秒：眼睛回正，偶尔自己朝别处瞥一眼
    const idle = window.setInterval(() => {
      if (performance.now() - lastMove < 3500 || busy()) return;
      if (Math.random() < .3) {
        const a = Math.random() * Math.PI * 2;
        tx = Math.cos(a) * 2.4;
        ty = Math.sin(a) * 1.6;
      } else tx = ty = 0;
      ball.classList.remove('near');
      kick();
    }, 1400);

    // 眨眼：隔 2.4 到 6 秒一次，偶尔连眨两下
    let blinkTimer = 0;
    const wait = () => {
      blinkTimer = window.setTimeout(() => {
        if (busy() || ball.classList.contains('happy')) wait();
        else blink(Math.random() < .18 ? 2 : 1);
      }, 2400 + Math.random() * 3600);
    };
    const blink = (n: number) => {
      ball.classList.add('blink');
      blinkTimer = window.setTimeout(() => {
        ball.classList.remove('blink');
        if (n > 1) blinkTimer = window.setTimeout(() => blink(n - 1), 110);
        else wait();
      }, 110);
    };
    wait();

    window.addEventListener('pointermove', onMove, { passive: true });
    document.documentElement.addEventListener('pointerleave', onLeave);
    return () => {
      window.removeEventListener('pointermove', onMove);
      document.documentElement.removeEventListener('pointerleave', onLeave);
      window.clearInterval(idle);
      window.clearTimeout(blinkTimer);
      cancelAnimationFrame(raf);
      ball.classList.remove('blink', 'near');
    };
  }, [ref]);

  useEffect(() => {
    const ball = ref.current;
    if (!ball || !answered || REDUCED) return;
    ball.classList.add('happy');
    const timer = window.setTimeout(() => ball.classList.remove('happy'), HAPPY_MS);
    return () => { window.clearTimeout(timer); ball.classList.remove('happy'); };
  }, [ref, answered]);
}
