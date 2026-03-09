import type { Stock } from '../types';
import { cn } from '../lib/utils';

interface Props {
  stock: Stock;
  onClick?: () => void;
}

export function StockCard({ stock, onClick }: Props) {
  const change = stock.change ?? 0;
  const changePct = stock.changePct ?? 0;
  const price = stock.price ?? 0;
  const isUp = change > 0;
  const isDown = change < 0;

  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "w-full text-left",
        "flex justify-between items-center px-4 py-3 cursor-pointer",
        "transition-colors duration-150",
        "hover:bg-surface-hover border-b border-edge/10 last:border-b-0",
        "group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      )}
    >
      <div className="flex flex-col gap-0.5 min-w-0">
        <span className="font-bold text-sm text-foreground group-hover:text-primary transition-colors truncate">
          {stock.name}
        </span>
        <span className="text-[11px] text-muted-foreground font-medium">{stock.code}</span>
      </div>

      <div className="flex items-center gap-3 shrink-0">
        <span className={cn(
          "text-sm font-extrabold tabular-nums",
          isUp ? "text-gain" : isDown ? "text-loss" : "text-muted-foreground"
        )}>
          {price.toFixed(2)}
        </span>
        <span className={cn(
          "text-xs tabular-nums font-bold px-3 py-1 rounded-full min-w-[5rem] text-center border-2",
          isUp ? "bg-gain/8 text-gain border-gain/20" : isDown ? "bg-loss/8 text-loss border-loss/20" : "bg-muted text-muted-foreground border-edge/10"
        )}>
          {isUp ? '+' : ''}{changePct.toFixed(2)}%
        </span>
      </div>
    </button>
  );
}
