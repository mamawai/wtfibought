import { Loader2 } from 'lucide-react';
import { cn, fmtDateTime } from '../lib/utils';
import { describeNotification, type MergedNotification } from '../lib/notifications';

/**
 * 通知列表的呈现层。PC 顶栏信封的下拉面板和「我的」页的通知卡共用同一份，
 * 免得两处各写一遍、日后各自跑偏。容器高度由调用方给（下拉面板要限高，卡片可放开）。
 */
export function NotificationList({ items, loading, onSelect, className }: {
  items: MergedNotification[];
  loading: boolean;
  onSelect: (commentId: number) => void;
  className?: string;
}) {
  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-xs text-muted-foreground">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> 加载中…
      </div>
    );
  }

  if (items.length === 0) {
    return <div className="py-10 text-center text-xs text-muted-foreground">还没有通知</div>;
  }

  return (
    <div className={cn('overflow-y-auto', className)}>
      {items.map(g => (
        <button
          key={g.key}
          type="button"
          onClick={() => onSelect(g.commentId)}
          className="w-full text-left px-4 py-2.5 border-b border-border/40 last:border-0 hover:bg-surface-hover transition-colors cursor-pointer"
        >
          <div className="flex items-start gap-2">
            <span className={cn(
              'w-1.5 h-1.5 rounded-full mt-1.5 shrink-0',
              g.unread ? 'bg-primary' : 'bg-transparent',
            )} />
            <div className="min-w-0 flex-1">
              <div className="text-xs font-medium leading-snug break-words">{describeNotification(g)}</div>
              <div className="text-[10px] text-muted-foreground mt-0.5">{fmtDateTime(g.latestAt)}</div>
            </div>
          </div>
        </button>
      ))}
    </div>
  );
}
