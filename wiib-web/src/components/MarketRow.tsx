import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { cn, fmtNum } from '../lib/utils';
import { formatCoinPrice, type CoinCfg } from '../lib/coinConfig';
import { getStockBrand } from '../lib/stockConfig';
import { bstockApi, futuresApi } from '../api';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { landCoinReveal, playCoinReveal } from '../lib/coinReveal';
import { Sparkline } from './fx/Sparkline';
import type { BStock } from '../types';

/**
 * 行情行：图标 名称(股票后面跟灰字公司名) | 最新价(币种行下面再跟一行灰字现货价) | 涨跌幅 | 走势线，横线分行，没有表头，名称下面不加第二行。
 * 鼠标指上去这一行浮起、别的行退后（样式在 index.css 的 .mkt-* 段，只给有鼠标的设备）。
 * 按容器宽度自适应（@container，父级带 @container 类）：窄容器走势线缩窄、字号降一号。
 * 行比文字列左右各宽出一截（负外边距 + 内边距），浮起来时底面盖过文字边缘才像一块。
 */
export function MarketRow({ icon, name, sub, price, priceSub, pct, spark, sparkColor, onClick, rowRef }: {
  icon: ReactNode;
  name: string;
  /** 名字后面的灰字（股票行放公司名），窄容器放不下就不显示 */
  sub?: string;
  price: string | null;
  /** 价格下面的灰字（币种行放现货价），不传就只有一行 */
  priceSub?: string;
  pct: number | null;
  spark?: number[];
  sparkColor?: string;
  onClick: () => void;
  rowRef?: RefObject<HTMLButtonElement | null>;
}) {
  const up = (pct ?? 0) >= 0;
  return (
    <button
      ref={rowRef}
      type="button"
      onClick={onClick}
      className={cn(
        'mkt-row grid items-center text-left cursor-pointer border-b border-border',
        'grid-cols-[minmax(0,1fr)_auto_62px_84px] gap-3 h-[60px] w-[calc(100%+1.5rem)] -mx-3 px-3',
        '@md:grid-cols-[minmax(0,1fr)_auto_76px_160px] @md:gap-[22px] @md:h-[66px] @md:w-[calc(100%+2rem)] @md:-mx-4 @md:px-4',
      )}
    >
      <span className="flex items-center gap-3 @md:gap-[13px] min-w-0">
        <span data-cr-icon className="shrink-0 w-[26px] h-[26px] @md:w-[30px] @md:h-[30px]">{icon}</span>
        <b className="text-[15px] @md:text-base font-bold tracking-[-.01em] truncate">{name}</b>
        {sub && <span className="hidden @md:block flex-1 min-w-0 truncate text-[13px] text-muted-foreground">{sub}</span>}
      </span>

      <span className="text-right">
        <span className="block num text-base @md:text-lg font-semibold font-stretch-[88%] tracking-[-.01em]">{price ?? '—'}</span>
        {priceSub && <span className="block num text-[11px] @md:text-xs text-muted-foreground">{priceSub}</span>}
      </span>

      <span
        className={cn(
          'num text-xs @md:text-[13.5px] font-bold text-right',
          pct == null ? 'text-muted-foreground' : up ? 'text-gain' : 'text-loss',
        )}
      >
        {pct == null ? '—' : `${up ? '+' : ''}${pct.toFixed(2)}%`}
      </span>

      <span data-cr-spark className="block h-[26px] @md:h-[30px]">
        {spark && spark.length > 1 && <Sparkline data={spark} stroke={sparkColor} className="w-full h-full" />}
      </span>
    </button>
  );
}

/** 币种/商品行：合约实时价 + 1h×25 根合约K线（首根收盘=24h涨跌基准，整条作走势线）；有现货的币，价格下面再带一行现货价 */
export function CoinMarketRow({ cfg }: { cfg: CoinCfg }) {
  const { t } = useTranslation('market');
  const navigate = useNavigate();
  const rowRef = useRef<HTMLButtonElement>(null);
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
  const priceText = price == null ? null : `$${formatCoinPrice(cfg.symbol, price)}`;
  // 现货价没到先占个 —
  const spotPrice = spotTick == null ? '—' : `$${formatCoinPrice(cfg.symbol, spotTick.price)}`;
  const spotLine = cfg.futuresOnly ? undefined : `${t('coin.spot')} ${spotPrice}`;

  // 点行：先放过渡（约 0.7s 图标到正中），再切页，交易页挂好后图标落到页头
  const open = () => {
    const to = `/coin/${cfg.symbol}`;
    const flight = rowRef.current && playCoinReveal(rowRef.current, { color: cfg.chartColor, price: priceText, pct });
    if (!flight) return;   // 连点 / 图标没渲染：忽略，等这段跑完
    flight.then(() => { navigate(to); landCoinReveal(cfg.symbol); });
  };

  return (
    <MarketRow
      rowRef={rowRef}
      icon={<cfg.icon className={cn('w-full h-full', cfg.colorClass)} />}
      name={cfg.name}
      price={priceText}
      priceSub={spotLine}
      pct={pct}
      spark={spark}
      sparkColor={cfg.chartColor}
      onClick={open}
    />
  );
}

/**
 * 代币化美股行：口径同上（价在现货流里，1h×25 根K线首根收盘当 24h 基准），代号当主名、公司名作灰字。
 * 流和K线没到之前先用列表接口带的价和涨跌
 */
export function StockMarketRow({ stock }: { stock: BStock }) {
  const navigate = useNavigate();
  const rowRef = useRef<HTMLButtonElement>(null);
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
  const priceText = price == null ? null : `$${fmtNum(price)}`;

  const open = () => {
    const to = `/bstock/${stock.symbol}`;
    const flight = rowRef.current && playCoinReveal(rowRef.current, { color: brand.color, price: priceText, pct });
    if (!flight) return;
    flight.then(() => { navigate(to); landCoinReveal(stock.symbol); });
  };

  return (
    <MarketRow
      rowRef={rowRef}
      icon={<brand.icon className="w-full h-full" />}
      name={stock.ticker}
      sub={stock.name}
      price={priceText}
      pct={pct}
      spark={spark}
      sparkColor={brand.color}
      onClick={open}
    />
  );
}
