import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, Flame } from 'lucide-react';
import { futuresApi } from '../api';
import { Skeleton } from './ui/skeleton';
import { fmtDateTime, fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import type { ForceOrder } from '../types';

const POLL_MS = 30_000;

/** 首页爆仓入口卡：只带最新一条做点缀，整卡可点跳 /force-orders 全量页 */
export function ForceOrdersCard() {
  // undefined=加载中 null=无记录
  const [latest, setLatest] = useState<ForceOrder | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    const load = () => futuresApi.forceOrders(undefined, 1, 1)
      .then(res => { if (!cancelled) setLatest(res.records[0] ?? null); })
      .catch(() => { if (!cancelled) setLatest(l => l ?? null); });
    load();
    const timer = window.setInterval(load, POLL_MS);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, []);

  // SELL=多头被强平（红），BUY=空头被强平（绿）
  const isLongLiq = latest?.side === 'SELL';

  return (
    <Link
      to="/force-orders"
      className="flex items-center gap-3 rounded-2xl bg-card px-4 py-3.5 neu-btn-sm transition-all cursor-pointer"
    >
      <div className="p-2 rounded-xl bg-red-500/10 shrink-0">
        <Flame className="w-4 h-4 text-red-500" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-bold leading-none">爆仓动态</div>
        {latest === undefined ? (
          <Skeleton className="h-3.5 w-44 mt-1.5" />
        ) : latest ? (
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground mt-1.5 min-w-0">
            <span className={`font-medium px-1.5 py-px rounded shrink-0 ${isLongLiq ? 'bg-loss/10 text-loss' : 'bg-gain/10 text-gain'}`}>
              {isLongLiq ? '多爆' : '空爆'}
            </span>
            <span className="font-medium text-foreground shrink-0">{latest.symbol.replace('USDT', '')}</span>
            <span className="font-mono tabular-nums truncate">${formatCoinPrice(latest.symbol, latest.price)}</span>
            <span className={`font-mono font-bold tabular-nums shrink-0 ${isLongLiq ? 'text-loss' : 'text-gain'}`}>
              ${fmtNum(latest.amount, 0)}
            </span>
            <span className="shrink-0 hidden sm:inline">{fmtDateTime(latest.tradeTime)}</span>
          </div>
        ) : (
          <div className="text-xs text-muted-foreground mt-1.5">Binance 全网强平实时记录</div>
        )}
      </div>
      <span className="flex items-center gap-0.5 text-xs font-bold text-muted-foreground shrink-0">
        查看全部 <ChevronRight className="w-3.5 h-3.5" />
      </span>
    </Link>
  );
}
