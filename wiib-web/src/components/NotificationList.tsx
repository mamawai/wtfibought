import { AlertTriangle, Loader2, MessageCircle, ShieldAlert, Target, ThumbsUp, Zap, type LucideIcon } from 'lucide-react';
import { cn, fmtNum, fmtRelative } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import {
  describeComment, describeTrade,
  type CommentNotification, type MergedNotification, type TradeNotification,
} from '../lib/notifications';
import type { TradeNotifType } from '../types';

const TRADE_META: Record<TradeNotifType, { icon: LucideIcon; color: string }> = {
  3: { icon: Zap, color: 'text-red-400' },              // 逐仓强平
  4: { icon: ShieldAlert, color: 'text-amber-400' },    // 止损：警示但不是灾难
  5: { icon: Target, color: 'text-green-400' },         // 止盈
  6: { icon: AlertTriangle, color: 'text-red-500' },    // 全仓爆仓：最重
};

const COMMENT_META: Record<1 | 2, { icon: LucideIcon; color: string }> = {
  1: { icon: ThumbsUp, color: 'text-primary' },
  2: { icon: MessageCircle, color: 'text-muted-foreground' },
};

/**
 * 条目内层：未读点 + 类型图标 + 内容区。
 * whitespace-normal 不能省：顶栏那层给导航加了 whitespace-nowrap，white-space 会继承进
 * 这个下拉面板，而 nowrap 下 break-words 是失效的，文案会排成一行往右铺出去。
 */
function RowInner({ unread, icon: Icon, iconClass, children }: {
  unread: boolean;
  icon: LucideIcon;
  iconClass: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-2">
      <span className={cn('w-1.5 h-1.5 rounded-full mt-2 shrink-0', unread ? 'bg-primary' : 'bg-transparent')} />
      <Icon className={cn('w-3.5 h-3.5 mt-0.5 shrink-0', iconClass)} />
      <div className="min-w-0 flex-1 whitespace-normal">{children}</div>
    </div>
  );
}

/** 交易类：不可点击（仓位已平，跳哪儿都看不到它），信息全摊在条目里 */
function TradeRow({ g }: { g: TradeNotification }) {
  const meta = TRADE_META[g.type];
  const hasPnl = g.pnl != null && Number.isFinite(g.pnl);
  const up = (g.pnl ?? 0) >= 0;
  return (
    <div className="px-4 py-2.5 border-b border-border/40 last:border-0">
      <RowInner unread={g.unread} icon={meta.icon} iconClass={meta.color}>
        <div className="text-xs font-bold leading-snug break-words">{describeTrade(g)}</div>
        {/* 全仓爆仓跨多币种，没有单一的数量@价格可报 */}
        {g.type !== 6 && g.quantity != null && (
          <div className="text-[11px] text-muted-foreground tabular-nums mt-0.5">
            {g.quantity} @ {formatCoinPrice(g.symbol ?? undefined, g.price)}
          </div>
        )}
        <div className="flex items-baseline justify-between gap-2 mt-0.5">
          <span className={cn('text-[13px] font-black tabular-nums', up ? 'text-green-400' : 'text-red-400')}>
            {hasPnl ? `${up ? '+' : ''}${fmtNum(g.pnl, 2)}` : '-'}
          </span>
          <span className="text-[10px] text-muted-foreground shrink-0">{fmtRelative(g.latestAt)}</span>
        </div>
      </RowInner>
    </div>
  );
}

/** 评论类：可点击，跳到留言板并聚焦那条评论 */
function CommentRow({ g, onSelect }: { g: CommentNotification; onSelect: (commentId: number) => void }) {
  const meta = COMMENT_META[g.type];
  return (
    <button
      type="button"
      onClick={() => onSelect(g.commentId)}
      className="w-full text-left px-4 py-2.5 border-b border-border/40 last:border-0 hover:bg-surface-hover transition-colors cursor-pointer"
    >
      <RowInner unread={g.unread} icon={meta.icon} iconClass={meta.color}>
        <div className="text-xs font-medium leading-snug break-words">{describeComment(g)}</div>
        <div className="text-[10px] text-muted-foreground mt-0.5">{fmtRelative(g.latestAt)}</div>
      </RowInner>
    </button>
  );
}

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
      {items.map(g => g.kind === 'trade'
        ? <TradeRow key={g.key} g={g} />
        : <CommentRow key={g.key} g={g} onSelect={onSelect} />)}
    </div>
  );
}
