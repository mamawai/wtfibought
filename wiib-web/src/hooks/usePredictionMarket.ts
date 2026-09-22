import { useCallback, useEffect, useRef, useState } from 'react';
import { predictionApi } from '../api';
import { fmtTime } from '../lib/utils';
import { subscribe } from './stompClient';
import type { PredictionBetLive, PredictionRound } from '../types';

export const WINDOW_SECONDS = 300;
/** 回合时间段，如 17:05–17:10 */
export const fmtWindow = (ws: number) => `${fmtTime(ws * 1000)}–${fmtTime((ws + WINDOW_SECONDS) * 1000)}`;
const MAX_ACTIVITIES = 30;
const DRIP_INTERVAL = 180;
/** 图表只画最近这么久的跳价 */
const HISTORY_MS = 90_000;

export interface PredictionMarket {
  round: PredictionRound | null;
  /** 当前窗口起点(秒)：回合到了取回合的，没到按校准后的钟取模 */
  windowStart: number | null;
  /** 本回合剩余秒 */
  countdown: number;
  /** 最近 90 秒的价，图表用 */
  priceHistory: { time: number; price: number }[];
  /** Chainlink 60 秒 TWAP：官网显示的当前价，也是结算口径 */
  btcPrice: number | null;
  upBid: number | null;
  upAsk: number | null;
  downBid: number | null;
  downAsk: number | null;
  activities: PredictionBetLive[];
  serverClockOffsetMs: number;
  fetchRound: () => Promise<void>;
}

/**
 * 5 分钟盘的行情：当前回合、服务端时钟校准、倒计时、价格历史、WS 推送的价 / 回合 / 盘口 / 成交流。
 * 预测页和 Jev 页共用；旧回合结算推送到了会调 onSettled，页面自己刷注单/余额。
 * 推送都在订阅回调里落 state（事件，不是 effect 体），回调里要判"是不是旧回合"，读最新 round 走 ref。
 */
export function usePredictionMarket(onSettled?: () => void): PredictionMarket {
  const [round, setRound] = useState<PredictionRound | null>(null);
  const [countdown, setCountdown] = useState(0);
  const [clockWindowStart, setClockWindowStart] = useState<number | null>(null);
  const [serverClockOffsetMs, setServerClockOffsetMs] = useState(0);
  const [priceHistory, setPriceHistory] = useState<{ time: number; price: number }[]>([]);
  const [btcPrice, setBtcPrice] = useState<number | null>(null);
  const [upBid, setUpBid] = useState<number | null>(null);
  const [upAsk, setUpAsk] = useState<number | null>(null);
  const [downBid, setDownBid] = useState<number | null>(null);
  const [downAsk, setDownAsk] = useState<number | null>(null);
  const [activities, setActivities] = useState<PredictionBetLive[]>([]);
  const queueRef = useRef<PredictionBetLive[]>([]);
  const onSettledRef = useRef(onSettled);
  const roundRef = useRef(round);
  useEffect(() => { onSettledRef.current = onSettled; });
  useEffect(() => { roundRef.current = round; });

  const fetchRound = useCallback(() => {
    const sentAt = Date.now();
    return predictionApi.current().then(res => {
      const r = res as unknown as PredictionRound;
      const receivedAt = Date.now();
      const clockSourceMs = r.officialNowTimeMs ?? r.serverTimeMs;
      if (clockSourceMs != null) {
        setServerClockOffsetMs(clockSourceMs - (sentAt + (receivedAt - sentAt) / 2));
      }
      setRound(r);
    }).catch(() => { /* ignore */ });
  }, []);

  useEffect(() => { fetchRound(); }, [fetchRound]);

  useEffect(() => {
    predictionApi.priceHistory().then((data: unknown) => {
      const arr = data as { time: number; price: string }[];
      if (Array.isArray(arr) && arr.length > 0) {
        setPriceHistory(arr.map(p => ({ time: p.time, price: parseFloat(p.price) })));
      }
    }).catch(() => {});
  }, []);

  useEffect(() => {
    const unsubs = [
      subscribe('/topic/prediction/price', (msg) => {
        try {
          const d = JSON.parse(msg.body);
          if (d.price) {
            const price = parseFloat(d.price);
            const now = Date.now();
            setBtcPrice(price);
            setPriceHistory(prev => [...prev.filter(p => p.time >= now - HISTORY_MS), { time: now, price }]);
          }
        } catch { /* ignore */ }
      }),
      subscribe('/topic/prediction/round', (msg) => {
        try {
          const ws = JSON.parse(msg.body) as Partial<PredictionRound>;
          const clockSourceMs = ws.officialNowTimeMs ?? ws.serverTimeMs;
          if (clockSourceMs != null) {
            setServerClockOffsetMs(clockSourceMs - Date.now());
          }
          const calibratedNow = clockSourceMs ?? Date.now();
          const curWs = roundRef.current?.windowStart ?? Math.floor(calibratedNow / 1000 / WINDOW_SECONDS) * WINDOW_SECONDS;
          if (ws.windowStart && ws.windowStart < curWs) {
            // 旧回合结算推送，不覆盖当前回合，只通知页面刷数据
            if (ws.status === 'SETTLED') onSettledRef.current?.();
            return;
          }
          setRound(prev => ({ ...prev, ...ws } as PredictionRound));
        } catch { /* ignore */ }
      }),
      subscribe('/topic/prediction/activity', (msg) => {
        try { queueRef.current.push(JSON.parse(msg.body)); } catch { /* ignore */ }
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
  }, []);

  // 成交流定时从队列取一条，逐条滴入
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
    const tick = () => {
      const calibratedNowMs = Date.now() + serverClockOffsetMs;
      const remaining = round?.officialEndTimeMs
        ? Math.max(0, Math.ceil((round.officialEndTimeMs - calibratedNowMs) / 1000))
        : WINDOW_SECONDS - (Math.floor(calibratedNowMs / 1000) % WINDOW_SECONDS);
      setCountdown(Math.min(WINDOW_SECONDS, remaining));
      setClockWindowStart(Math.floor(calibratedNowMs / 1000 / WINDOW_SECONDS) * WINDOW_SECONDS);
      if (remaining === 0 || remaining === WINDOW_SECONDS) {
        fetchRound();
      }
    };
    tick();
    const iv = setInterval(tick, 1000);
    return () => clearInterval(iv);
  }, [fetchRound, round?.officialEndTimeMs, serverClockOffsetMs]);

  return {
    round, windowStart: round?.windowStart ?? clockWindowStart, countdown, priceHistory, btcPrice,
    upBid, upAsk, downBid, downAsk, activities, serverClockOffsetMs, fetchRound,
  };
}
