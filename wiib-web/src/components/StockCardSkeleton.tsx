import { Skeleton } from './ui/skeleton';

/**
 * 股票行骨架屏：variant='pill' 右侧是操作按钮占位（首页涨跌榜），
 * 'lines' 右侧是价格+涨跌两行占位（股票列表）。
 */
export function StockCardSkeleton({ variant = 'lines' }: { variant?: 'pill' | 'lines' }) {
  if (variant === 'pill') {
    return (
      <div className="flex justify-between items-center px-4 py-3 border-b border-border/20 last:border-b-0">
        <div className="flex flex-col gap-2"><Skeleton className="h-4 w-20" /><Skeleton className="h-3 w-14" /></div>
        <Skeleton className="h-7 w-20 rounded-full" />
      </div>
    );
  }
  return (
    <div className="flex justify-between items-center p-4 border-b border-border last:border-b-0">
      <div className="flex flex-col gap-2">
        <Skeleton className="h-4 w-20" />
        <Skeleton className="h-3 w-16" />
      </div>
      <div className="flex flex-col items-end gap-2">
        <Skeleton className="h-5 w-16" />
        <Skeleton className="h-3 w-24" />
      </div>
    </div>
  );
}
