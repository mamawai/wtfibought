import { useEffect, useRef, useState, useCallback } from 'react';
import { subscribe } from './stompClient';
import type { PredictionRound, PredictionBetLive } from '../types';

export interface PredictionStream {
  btcPrice: number | null;
  round: Partial<PredictionRound> | null;
  upBid: number | null;
  upAsk: number | null;
  downBid: number | null;
  downAsk: number | null;
  activities: PredictionBetLive[];
}

const MAX_ACTIVITIES = 30;
const DRIP_INTERVAL = 180;

export function usePredictionStream(): PredictionStream {
  const [btcPrice, setBtcPrice] = useState<number | null>(null);
  const [round, setRound] = useState<Partial<PredictionRound> | null>(null);
  const [upBid, setUpBid] = useState<number | null>(null);
  const [upAsk, setUpAsk] = useState<number | null>(null);
  const [downBid, setDownBid] = useState<number | null>(null);
  const [downAsk, setDownAsk] = useState<number | null>(null);
  const [activities, setActivities] = useState<PredictionBetLive[]>([]);
  const queueRef = useRef<PredictionBetLive[]>([]);

  const enqueue = useCallback((a: PredictionBetLive) => {
    queueRef.current.push(a);
  }, []);

  // 定时从队列取一条，逐条滴入
  useEffect(() => {
    const iv = setInterval(() => {
      if (queueRef.current.length > 0) {
        const item = queueRef.current.shift()!;
        setActivities(prev => [item, ...prev].slice(0, MAX_ACTIVITIES));
      }
    }, DRIP_INTERVAL);
    return () => clearInterval(iv);
  }, []);

  useEffect(() => {
    const unsubs = [
      subscribe('/topic/prediction/price', (msg) => {
        try {
          const d = JSON.parse(msg.body);
          if (d.price) setBtcPrice(parseFloat(d.price));
        } catch { /* ignore */ }
      }),
      subscribe('/topic/prediction/round', (msg) => {
        try { setRound(JSON.parse(msg.body)); } catch { /* ignore */ }
      }),
      subscribe('/topic/prediction/activity', (msg) => {
        try { enqueue(JSON.parse(msg.body)); } catch { /* ignore */ }
      }),
      subscribe('/topic/prediction/market', (msg) => {
        try {
          const d = JSON.parse(msg.body);
          setUpBid(d.upBid != null ? parseFloat(d.upBid) : null);
          setUpAsk(d.upAsk != null ? parseFloat(d.upAsk) : null);
          setDownBid(d.downBid != null ? parseFloat(d.downBid) : null);
          setDownAsk(d.downAsk != null ? parseFloat(d.downAsk) : null);
        } catch { /* ignore */ }
      }),
    ];

    return () => unsubs.forEach(fn => fn());
  }, [enqueue]);

  return { btcPrice, round, upBid, upAsk, downBid, downAsk, activities };
}
