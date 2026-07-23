import { useEffect, useState } from 'react';
import { futuresApi } from '../api';
import type { TradeFilter, TradeFilterMap } from '../types';

/**
 * 交易过滤器（数量步长/最小数量/最小名义额）：后端注册表启动时按 Binance exchangeInfo 刷新，
 * 这里模块级缓存全站只拉一次；接口不可达时用下方实拉快照兜底（2026-07-23）。
 */
const DEFAULTS: TradeFilterMap = {
  futures: {
    BTCUSDT:  { stepSize: 0.001,  minQty: 0.001,  minNotional: 50 },
    ETHUSDT:  { stepSize: 0.001,  minQty: 0.001,  minNotional: 20 },
    DOGEUSDT: { stepSize: 1,      minQty: 1,      minNotional: 5 },
    SOLUSDT:  { stepSize: 0.01,   minQty: 0.01,   minNotional: 5 },
    XRPUSDT:  { stepSize: 0.1,    minQty: 0.1,    minNotional: 5 },
    BNBUSDT:  { stepSize: 0.01,   minQty: 0.01,   minNotional: 5 },
    XAUUSDT:  { stepSize: 0.001,  minQty: 0.001,  minNotional: 5 },
    CLUSDT:   { stepSize: 0.01,   minQty: 0.01,   minNotional: 5 },
  },
  spot: {
    BTCUSDT:  { stepSize: 0.00001, minQty: 0.00001, minNotional: 5 },
    ETHUSDT:  { stepSize: 0.0001,  minQty: 0.0001,  minNotional: 5 },
    DOGEUSDT: { stepSize: 1,       minQty: 1,       minNotional: 1 },
    SOLUSDT:  { stepSize: 0.001,   minQty: 0.001,   minNotional: 5 },
    XRPUSDT:  { stepSize: 0.1,     minQty: 0.1,     minNotional: 5 },
    BNBUSDT:  { stepSize: 0.001,   minQty: 0.001,   minNotional: 5 },
  },
};

// 未配置 symbol 宽松放行（后端注册表同口径）
const OPEN_FILTER: TradeFilter = { stepSize: 0.00000001, minQty: 0.00000001, minNotional: 0 };

let current: TradeFilterMap = DEFAULTS;
let inflight: Promise<void> | null = null;
const listeners = new Set<() => void>();

function ensureLoaded(): void {
  inflight ??= futuresApi.tradeFilters()
    .then(m => {
      current = { futures: { ...DEFAULTS.futures, ...m.futures }, spot: { ...DEFAULTS.spot, ...m.spot } };
      listeners.forEach(l => l());
    })
    .catch(() => { inflight = null; /* 失败保留兜底，下个订阅者重试 */ });
}

/** 取该 symbol 的过滤器；接口数据到位后自动触发重渲染 */
export function useTradeFilter(market: 'futures' | 'spot', symbol: string): TradeFilter {
  const [, force] = useState(0);
  useEffect(() => {
    const l = () => force(n => n + 1);
    listeners.add(l);
    ensureLoaded();
    return () => { listeners.delete(l); };
  }, []);
  return current[market][symbol] ?? OPEN_FILTER;
}
