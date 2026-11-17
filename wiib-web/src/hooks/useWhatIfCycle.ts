import { useEffect, useState } from 'react';
import { REDUCED_MOTION } from '../lib/motion';
import { WHAT_IF } from '../lib/whatIfData';

/** 选定一个标的之后，第几毫秒山开始散、第几毫秒换下一个标的 */
const SCATTER_AT_MS = 10_000, NEXT_AT_MS = 18_000;
/** 山散成烟用多久（背景那边按这个速度散） */
export const SCATTER_MS = 1800;
/** 一进来先演哪档时间：一年前 */
const START_SPAN = 2;

/**
 * 登录页现在演的是哪个标的、哪档时间，和背景里的山该不该散。
 * 选定一个之后山亮 10 秒，然后 scatter 变真（山散成烟），到第 18 秒自动换下一个标的；手动换了就重新计时。
 * 系统设了减少动效就不散也不自动换
 */
export function useWhatIfCycle() {
  // n 每次选都加一：点的还是同一个标的，也要重新计时
  const [pick, setPick] = useState({ coin: 0, span: START_SPAN, n: 0 });
  const [scatter, setScatter] = useState(false);

  useEffect(() => {
    if (REDUCED_MOTION) return;
    const fade = window.setTimeout(() => setScatter(true), SCATTER_AT_MS);
    const next = window.setTimeout(() => {
      setScatter(false);
      setPick(p => ({ ...p, coin: (p.coin + 1) % WHAT_IF.length, n: p.n + 1 }));
    }, NEXT_AT_MS);
    return () => {
      window.clearTimeout(fade);
      window.clearTimeout(next);
    };
  }, [pick]);

  const choose = (to: { coin?: number; span?: number }) => {
    setScatter(false);
    setPick(p => ({ ...p, ...to, n: p.n + 1 }));
  };

  return { coin: pick.coin, span: pick.span, scatter, choose };
}
