import { useEffect, useState } from 'react';
import { subscribe } from './stompClient';

/** 攒一秒合一次再更新，几个币同时跳也只重渲染一次 */
const FLUSH_MS = 1000;

/**
 * 几个币的合约标记价实时推送（/topic/futures/{symbol} 里的 mp）。还没推到的币不在返回里，调用方退回接口快照值。
 * 依赖用排好序的币串：调用方每次渲染新建数组也不会反复重订
 */
export function useMarkPrices(symbols: string[]): Record<string, number> {
  const key = [...new Set(symbols)].sort().join(',');
  const [marks, setMarks] = useState<Record<string, number>>({});
  useEffect(() => {
    if (!key) return;
    const pending: Record<string, number> = {};
    let dirty = false;
    const unsubs = key.split(',').map(s => subscribe(`/topic/futures/${s}`, msg => {
      try {
        const d = JSON.parse(msg.body);
        if (d.mp != null) {
          pending[s] = parseFloat(d.mp);
          dirty = true;
        }
      } catch {
        // 坏消息丢掉，等下一条
      }
    }));
    const timer = setInterval(() => {
      if (!dirty) return;
      dirty = false;
      setMarks(m => ({ ...m, ...pending }));
    }, FLUSH_MS);
    return () => {
      clearInterval(timer);
      unsubs.forEach(u => u());
    };
  }, [key]);
  return marks;
}
