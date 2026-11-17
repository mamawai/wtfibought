import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { cn, fmtNum } from '../lib/utils';
import { formatCoinPrice, type CoinCfg } from '../lib/coinConfig';
import { getStockBrand } from '../lib/stockConfig';
import { bstockApi, futuresApi } from '../api';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { Sparkline } from './fx/Sparkline';
import type { BStock } from '../types';
import './MarketRow.css';

/**
 * 行情行：图标 名称(股票后面跟灰字公司名) | 最新价(有现货的币下面再跟一行灰字现货价) | 涨跌幅 | 走势线，横线分行，没有表头。
 * 点行直接进交易页（样式见 MarketRow.css）。
 */
export function MarketRow({ icon, name, sub, price, priceSub, pct, spark, sparkColor, onClick }: {
  icon: ReactNode;
  name: string;
  /** 名字后面的灰字（股票行放公司名），手机上不显示 */
  sub?: string;
  price: string | null;
  /** 价格下面的灰字（币种行放现货价），不传就只有一行 */
  priceSub?: string;
  pct: number | null;
  spark?: number[];
  sparkColor?: string;
  onClick: () => void;
}) {
  const up = (pct ?? 0) >= 0;
  return (
    <button type="button" className="mrow" onClick={onClick}>
      <span className="nm">
        <span className="ci">{icon}</span>
        <b>{name}</b>
        {sub && <span>{sub}</span>}
      </span>
      <span className="px">
        <b>{price ?? '—'}</b>
        {priceSub && <span>{priceSub}</span>}
      </span>
      <span className={cn('pc', pct == null ? 'mute' : up ? 'up' : 'dn')}>
        {pct == null ? '—' : `${up ? '+' : ''}${pct.toFixed(2)}%`}
      </span>
      <span className="sp">
        {spark && spark.length > 1 && <Sparkline data={spark} stroke={sparkColor} dot={false} className="w-full h-full" />}
      </span>
    </button>
  );
}

/** 币种/商品行：合约实时价 + 1h×25 根合约K线（首根收盘=24h涨跌基准，整条作走势线）；有现货的币，价格下面再带一行现货价 */
export function CoinMarketRow({ cfg }: { cfg: CoinCfg }) {
  const { t } = useTranslation('market');
  const navigate = useNavigate();
  const tick = useCryptoStream(cfg.symbol, 'futures');
  // 纯合约的没有现货，不订
  const spotTick = useCryptoStream(cfg.futuresOnly ? undefined : cfg.symbol, 'spot');
  const [closes, setCloses] = useState<number[]>([]);

  useEffect(() => {
    let cancelled = false;
    futuresApi.klines(cfg.symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows.length) setCloses(rows.map(r => Number(r[4]))); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [cfg.symbol]);

  const livePrice = tick?.price;
  const spark = livePrice != null && closes.length ? [...closes.slice(0, -1), livePrice] : closes;
  const price = livePrice ?? (closes.length ? closes[closes.length - 1] : null);
  const base = closes.length ? closes[0] : null;
  const pct = price != null && base ? ((price - base) / base) * 100 : null;
  // 现货价没到先占个 —
  const spotPrice = spotTick == null ? '—' : `₮${formatCoinPrice(cfg.symbol, spotTick.price)}`;

  return (
    <MarketRow
      icon={<cfg.icon className={cn('w-full h-full', cfg.colorClass)} />}
      name={cfg.name}
      price={price == null ? null : `₮${formatCoinPrice(cfg.symbol, price)}`}
      priceSub={cfg.futuresOnly ? undefined : `${t('coin.spot')} ${spotPrice}`}
      pct={pct}
      spark={spark}
      sparkColor={cfg.chartColor}
      onClick={() => navigate(`/coin/${cfg.symbol}`)}
    />
  );
}

/**
 * 代币化美股行：口径同上（价在现货流里，1h×25 根K线首根收盘当 24h 基准），代号当主名、公司名作灰字。
 * 流和K线没到之前先用列表接口带的价和涨跌
 */
export function StockMarketRow({ stock }: { stock: BStock }) {
  const navigate = useNavigate();
  const brand = getStockBrand(stock.ticker);
  const tick = useCryptoStream(stock.symbol, 'spot');
  const [closes, setCloses] = useState<number[]>([]);

  useEffect(() => {
    let cancelled = false;
    bstockApi.klines(stock.symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows.length) setCloses(rows.map(r => Number(r[4]))); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [stock.symbol]);

  const livePrice = tick?.price;
  const spark = livePrice != null && closes.length ? [...closes.slice(0, -1), livePrice] : closes;
  const price = livePrice ?? (closes.length ? closes[closes.length - 1] : (stock.price ?? null));
  const base = closes.length ? closes[0] : null;
  const pct = price != null && base ? ((price - base) / base) * 100 : (stock.changePct ?? null);

  return (
    <MarketRow
      icon={<brand.icon className="w-full h-full" />}
      name={stock.ticker}
      sub={stock.name}
      price={price == null ? null : `₮${fmtNum(price)}`}
      pct={pct}
      spark={spark}
      sparkColor={brand.color}
      onClick={() => navigate(`/bstock/${stock.symbol}`)}
    />
  );
}
