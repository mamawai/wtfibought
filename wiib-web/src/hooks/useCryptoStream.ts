import { useEffect, useRef, useState } from 'react';
import { subscribe } from './stompClient';

export type CryptoStreamMode = 'spot' | 'futures';

export interface CryptoTick {
  price: number;
  ts: number;
  ws: boolean;
  fws: boolean;
  mp?: number;
  fp?: number;
}

const THROTTLE_MS = 3000;

export function useCryptoStream(symbol: string | undefined, mode: CryptoStreamMode): CryptoTick | null {
  // tick 挂 key：symbol/mode 切换时旧数据按 null 返回，免去 effect 里同步 setState 清空
  const [state, setState] = useState<{ key: string; tick: CryptoTick | null }>({ key: '', tick: null });
  const lastUpdateRef = useRef(0);
  const pendingRef = useRef<CryptoTick | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const key = `${symbol}:${mode}`;

  useEffect(() => {
    if (!symbol) return;
    const k = `${symbol}:${mode}`;
    lastUpdateRef.current = 0;
    pendingRef.current = null;
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;

    const flush = (t: CryptoTick) => {
      lastUpdateRef.current = Date.now();
      pendingRef.current = null;
      setState({ key: k, tick: t });
    };

    const topic = mode === 'futures' ? `/topic/futures/${symbol}` : `/topic/crypto/${symbol}`;

    const unsub = subscribe(topic, (msg) => {
      try {
        const data = JSON.parse(msg.body);

        if (mode === 'futures') {
          const nextFp = data.fp != null ? parseFloat(data.fp) : undefined;
          const nextMp = data.mp != null ? parseFloat(data.mp) : undefined;
          setState(s => {
            const prev = s.key === k ? s.tick : null;
            const price = nextFp ?? prev?.fp ?? prev?.price ?? nextMp;
            if (price == null) return s;
            return { key: k, tick: {
              price, ts: Date.now(), ws: false, fws: !!data.fws,
              mp: nextMp ?? prev?.mp, fp: nextFp ?? prev?.fp,
            } };
          });
          return;
        }

        // spot 模式: 3秒节流
        const t: CryptoTick = {
          price: parseFloat(data.price), ts: data.ts, ws: !!data.ws, fws: false,
        };
        const elapsed = Date.now() - lastUpdateRef.current;
        if (elapsed >= THROTTLE_MS) {
          flush(t);
        } else {
          pendingRef.current = t;
          if (!timerRef.current) {
            timerRef.current = setTimeout(() => {
              timerRef.current = null;
              if (pendingRef.current) flush(pendingRef.current);
            }, THROTTLE_MS - elapsed);
          }
        }
      } catch { /* ignore */ }
    });

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = null;
      unsub();
    };
  }, [symbol, mode]);

  return state.key === key ? state.tick : null;
}
