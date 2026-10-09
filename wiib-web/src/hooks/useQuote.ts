import { useEffect, useState } from 'react';
import { COIN_MAP } from '../lib/coinConfig';
import { cryptoApi, futuresApi, bstockApi } from '../api';
import { useCryptoStream } from './useCryptoStream';
import type { BStock } from '../types';

export interface Quote { key: string; name: string; price: number | null; pct: number | null; to: string; live: boolean }

/** 单币报价：实时流价 + 1h×25 根K线首根收盘作 24h 基准（与行情卡同口径） */
export function useCoinQuote(symbol: string): Quote {
  const cfg = COIN_MAP[symbol];
  const tick = useCryptoStream(symbol, cfg.futuresOnly ? 'futures' : 'spot');
  const [base, setBase] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = cfg.futuresOnly ? futuresApi.klines : cryptoApi.klines;
    load(symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows.length) setBase(Number(rows[0][4])); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol, cfg.futuresOnly]);

  const price = (cfg.futuresOnly ? (tick?.fp ?? tick?.price) : tick?.price) ?? null;
  const pct = price != null && base ? ((price - base) / base) * 100 : null;
  return { key: symbol, name: cfg.name, price, pct, to: `/coin/${symbol}`, live: true };
}

/**
 * 美股报价：bStock 的价并在 Spot 流里（getAllSpotSymbols = crypto ∪ stock），
 * 订阅方式与币种一致；24h 基准同样取 1h×25 首根收盘，与上面的币种口径对齐。
 * stock 为 undefined（列表还没回来）时 useCryptoStream 收 undefined 自动空转。
 */
export function useStockQuote(stock: BStock | undefined): Quote {
  const symbol = stock?.symbol;
  const tick = useCryptoStream(symbol, 'spot');
  const [base, setBase] = useState<number | null>(null);

  useEffect(() => {
    if (!symbol) return;
    let cancelled = false;
    bstockApi.klines(symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows.length) setBase(Number(rows[0][4])); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol]);

  const price = tick?.price ?? stock?.price ?? null;
  const pct = price != null && base ? ((price - base) / base) * 100 : (stock?.changePct ?? null);
  return {
    key: symbol ?? '',
    name: stock?.ticker ?? stock?.name ?? '',
    price, pct,
    to: `/bstock/${symbol}`,
    live: true,
  };
}
