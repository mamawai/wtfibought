import { useCallback, useEffect, useRef, useState } from 'react';

/** 对勾停多久再放手 */
const OK_MS = 700;
/** 晃两下的时长，同 index.css 的 .nope */
const NOPE_MS = 360;

export type FxPhase = 'busy' | 'ok' | 'nope';

/**
 * 提交钮的三段反馈（样子是 index.css 的 .busy / .ok / .nope）：转圈 → 成了打勾停一下 / 没成晃两下。
 * key 是哪一颗钮，一个面板里几颗钮共用一份；同一时间只跑一颗。
 */
export function useSubmitFx<K extends string = string>() {
  const [fx, setFx] = useState<{ key: K; phase: FxPhase } | null>(null);
  const timerRef = useRef(0);
  // 有一次提交还在转圈或打勾
  const runningRef = useRef(false);
  useEffect(() => () => window.clearTimeout(timerRef.current), []);

  /** 摆出某一段，ms 后自己收掉 */
  const hold = useCallback((key: K, phase: FxPhase, ms: number) => new Promise<void>(resolve => {
    window.clearTimeout(timerRef.current);
    setFx({ key, phase });
    timerRef.current = window.setTimeout(() => { setFx(null); resolve(); }, ms);
  }), []);

  /** 没发请求就被挡下（数量没填这类）：晃两下。有一次提交还在转圈或打勾时不晃，不打断它 */
  const nope = useCallback((key: K) => {
    if (!runningRef.current) void hold(key, 'nope', NOPE_MS);
  }, [hold]);

  /** 跑一次提交：fn 抛错算没成，错误交给 onError。返回成没成，成的话等对勾停完才返回；上一次还在转圈或打勾就直接返回 false */
  const run = useCallback(async (key: K, fn: () => Promise<unknown>, onError: (e: unknown) => void) => {
    if (runningRef.current) return false;
    runningRef.current = true;
    window.clearTimeout(timerRef.current);
    setFx({ key, phase: 'busy' });
    try {
      await fn();
    } catch (e) {
      runningRef.current = false;
      onError(e);
      void hold(key, 'nope', NOPE_MS);
      return false;
    }
    await hold(key, 'ok', OK_MS);
    runningRef.current = false;
    return true;
  }, [hold]);

  /** 这颗钮现在该挂的类名 */
  const cls = (key: K) => (fx?.key === key ? fx.phase : undefined);
  /** 有一颗在转圈或打勾：别的钮先别点 */
  const locked = fx != null && fx.phase !== 'nope';
  return { run, nope, cls, locked };
}
