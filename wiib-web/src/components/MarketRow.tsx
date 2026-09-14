import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../lib/utils';
import { formatCoinPrice, type CoinCfg } from '../lib/coinConfig';
import { cryptoApi, futuresApi } from '../api';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { landCoinReveal, playCoinReveal } from '../lib/coinReveal';
import { Sparkline } from './fx/Sparkline';

/**
 * 行情行：图标 币名 | 最新价 | 涨跌幅 | 走势线，横线分行，没有表头和副标题。
 * 鼠标指上去这一行浮起、别的行退后（样式在 index.css 的 .mkt-* 段，只给有鼠标的设备）。
 * 按容器宽度自适应（@container，父级带 @container 类）：窄容器走势线缩窄、字号降一号。
 * 行比文字列左右各宽出一截（负外边距 + 内边距），浮起来时底面盖过文字边缘才像一块。
 */
export function MarketRow({ icon, name, price, pct, spark, sparkColor, onClick, rowRef }: {
  icon: ReactNode;
  name: string;
  price: string | null;
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
      </span>

      <span className="num text-base @md:text-lg font-semibold font-stretch-[88%] tracking-[-.01em] text-right">{price ?? '—'}</span>

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

/** 币种/商品行：实时流价 + 1h×25 根K线（首根收盘=24h涨跌基准，整条作走势线），与原行情卡同口径 */
export function CoinMarketRow({ cfg }: { cfg: CoinCfg }) {
  const navigate = useNavigate();
  const rowRef = useRef<HTMLButtonElement>(null);
  const tick = useCryptoStream(cfg.symbol, cfg.futuresOnly ? 'futures' : 'spot');
  const [closes, setCloses] = useState<number[]>([]);

  useEffect(() => {
    let cancelled = false;
    const loadKlines = cfg.futuresOnly ? futuresApi.klines : cryptoApi.klines;
    loadKlines(cfg.symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows?.length) setCloses(rows.map(r => Number(r[4]))); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [cfg.symbol, cfg.futuresOnly]);

  const livePrice = cfg.futuresOnly ? (tick?.fp ?? tick?.price) : tick?.price;
  const spark = livePrice != null && closes.length ? [...closes.slice(0, -1), livePrice] : closes;
  const price = livePrice ?? (closes.length ? closes[closes.length - 1] : null);
  const base = closes.length ? closes[0] : null;
  const pct = price != null && base ? ((price - base) / base) * 100 : null;
  const priceText = price == null ? null : `$${formatCoinPrice(cfg.symbol, price)}`;

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
      pct={pct}
      spark={spark}
      sparkColor={cfg.chartColor}
      onClick={open}
    />
  );
}
