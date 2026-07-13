import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../lib/utils';
import { COIN_LIST, formatCoinPrice, type CoinCfg } from '../lib/coinConfig';
import { cryptoApi, futuresApi } from '../api';
import { useCryptoStream } from '../hooks/useCryptoStream';

/** 折线归一化到 100x28 视口；range=0（横盘）时画中线 */
function sparkPoints(data: number[]): string {
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const W = 100, H = 28, PAD = 2;
  return data
    .map((v, i) => {
      const x = (i / (data.length - 1)) * W;
      const y = PAD + (1 - (v - min) / range) * (H - PAD * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

function CoinMarketCard({ cfg }: { cfg: CoinCfg }) {
  const navigate = useNavigate();
  // 商品是纯合约（无现货）→ 走 futures 流 + 合约K线；crypto 走现货
  const tick = useCryptoStream(cfg.symbol, cfg.futuresOnly ? 'futures' : 'spot');
  // 进页面拉一次 1h×25 根：closes[0]≈24h前收盘价作涨跌基准，整条作走势线
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
  // 实时价到了就顶掉最后一根（当前未收盘K线）的收盘价，走势线尾端跟着动
  const spark = useMemo(
    () => (livePrice != null && closes.length ? [...closes.slice(0, -1), livePrice] : closes),
    [closes, livePrice],
  );

  const price = livePrice ?? (closes.length ? closes[closes.length - 1] : null);
  const base = closes.length ? closes[0] : null;
  const pct = price != null && base ? ((price - base) / base) * 100 : null;
  const up = (pct ?? 0) >= 0;

  return (
    <button
      onClick={() => navigate(`/coin/${cfg.symbol}`)}
      className={cn(
        'flex flex-col rounded-2xl p-4 text-left transition-all cursor-pointer neu-btn-sm',
        cfg.bgClass, cfg.hoverBgClass,
      )}
    >
      <div className="flex items-center gap-2.5 w-full">
        <cfg.icon className={cn('w-7 h-7 shrink-0', cfg.colorClass)} />
        <div className="min-w-0">
          <div className="font-bold text-sm leading-tight">{cfg.name}</div>
          <div className="text-[10px] text-muted-foreground whitespace-nowrap">{cfg.pair}</div>
        </div>
        <span
          className={cn(
            'ml-auto shrink-0 text-[11px] font-bold tabular-nums px-1.5 py-0.5 rounded-full',
            pct == null ? 'text-muted-foreground' : up ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
          )}
        >
          {pct == null ? '—' : `${up ? '+' : ''}${pct.toFixed(2)}%`}
        </span>
      </div>

      <div className="mt-2 text-lg font-extrabold tabular-nums tracking-tight">
        {price == null ? '—' : `$${formatCoinPrice(cfg.symbol, price)}`}
      </div>

      <div className="mt-2 h-7 w-full">
        {spark.length > 1 && (
          <svg viewBox="0 0 100 28" preserveAspectRatio="none" className="w-full h-full">
            <polyline
              points={sparkPoints(spark)}
              fill="none" stroke={cfg.chartColor} strokeWidth="1.5"
              strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"
            />
          </svg>
        )}
      </div>
    </button>
  );
}

/** 币种行情卡片网格：实时价（STOMP）+24h涨跌+迷你走势线，点卡片直达交易页。首页与 /coin 选择页共用。 */
export function CoinMarketGrid({ list = COIN_LIST }: { list?: CoinCfg[] }) {
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-3 md:gap-4">
      {list.map(c => <CoinMarketCard key={c.symbol} cfg={c} />)}
    </div>
  );
}
