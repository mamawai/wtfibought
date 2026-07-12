import { useEffect, useState } from 'react';
import { subscribe } from './stompClient';

/** 后端 /topic/kline/{symbol} 推送的实时 K 线（含未收盘的当前根）。字段与 KlineStreamHandler.broadcastKline 契约一致。 */
export interface KlineTick {
  i: string;       // interval：5m / 15m
  t: number;       // 开盘时刻（ms）
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;       // 成交量（基础币，如 ETH）
  q: number;       // 成交额（USDT）
  x: boolean;      // 是否已收盘
}

/**
 * 订阅某 symbol 的实时 K 线流；同一 topic 同时有 5m/15m，按 interval 过滤只取当前视图所需。
 * 仅合约（后端只广播合约 kline）。
 */
export function useKlineStream(symbol: string | undefined, interval: string): KlineTick | null {
  // tick 挂 key：symbol/interval 切换时旧数据按 null 返回，免去 effect 里同步 setState 清空
  const [state, setState] = useState<{ key: string; tick: KlineTick | null }>({ key: '', tick: null });
  const key = `${symbol}:${interval}`;

  useEffect(() => {
    if (!symbol) return;
    const k = `${symbol}:${interval}`;
    const unsub = subscribe(`/topic/kline/${symbol}`, (msg) => {
      try {
        const d = JSON.parse(msg.body);
        if (d.i !== interval) return;   // 过滤：只要当前 interval（5m / 15m）
        setState({ key: k, tick: { i: d.i, t: d.t, o: +d.o, h: +d.h, l: +d.l, c: +d.c, v: +d.v, q: +d.q, x: !!d.x } });
      } catch { /* ignore */ }
    });
    return unsub;
  }, [symbol, interval]);

  return state.key === key ? state.tick : null;
}
