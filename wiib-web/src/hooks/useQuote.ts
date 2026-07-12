import { useEffect, useState } from 'react';
import { subscribe } from './stompClient';
import type { Quote, DayTick } from '../types';

interface UseQuoteResult {
  quote: Quote | null;
  realtimeTicks: DayTick[];
}

const MAX_TICKS = 1440;

export function useQuote(stockCode: string | undefined, initialTicks?: DayTick[]): UseQuoteResult {
  const [quote, setQuote] = useState<Quote | null>(null);
  // initialTicks 变化时在 render 期重置为基底（React 文档 prev 比较模式），流式增量在订阅回调追加
  const [ticksState, setTicksState] = useState<{ src: DayTick[] | undefined; list: DayTick[] }>(
    () => ({ src: initialTicks, list: initialTicks?.length ? [...initialTicks].slice(0, MAX_TICKS) : [] }));
  if (ticksState.src !== initialTicks) {
    setTicksState({ src: initialTicks, list: initialTicks?.length ? [...initialTicks].slice(0, MAX_TICKS) : [] });
  }

  useEffect(() => {
    if (!stockCode) return;

    const unsub = subscribe(`/topic/quote/${stockCode}`, (msg) => {
      try {
        const data: Quote = JSON.parse(msg.body);
        setQuote(data);
        setTicksState((s) => {
          if (s.list.length >= MAX_TICKS) return s;
          return { ...s, list: [...s.list, { time: '', price: data.price }] };
        });
      } catch { /* ignore */ }
    });

    return unsub;
  }, [stockCode]);

  return { quote, realtimeTicks: ticksState.list };
}
