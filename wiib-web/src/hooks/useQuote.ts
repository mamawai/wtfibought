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
  const [realtimeTicks, setRealtimeTicks] = useState<DayTick[]>([]);

  useEffect(() => {
    if (initialTicks && initialTicks.length > 0) {
      setRealtimeTicks([...initialTicks].slice(0, MAX_TICKS));
    } else {
      setRealtimeTicks([]);
    }
  }, [initialTicks]);

  useEffect(() => {
    if (!stockCode) return;

    const unsub = subscribe(`/topic/quote/${stockCode}`, (msg) => {
      try {
        const data: Quote = JSON.parse(msg.body);
        setQuote(data);
        setRealtimeTicks((prev) => {
          if (prev.length >= MAX_TICKS) return prev;
          return [...prev, { time: '', price: data.price }];
        });
      } catch { /* ignore */ }
    });

    return unsub;
  }, [stockCode]);

  return { quote, realtimeTicks };
}
