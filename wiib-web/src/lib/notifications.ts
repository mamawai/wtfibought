import type { NotificationItem, TradeNotifType } from '../types';

/** 评论类：多人赞同一条会合并成一组 */
export interface CommentNotification {
  kind: 'comment';
  key: string;
  type: 1 | 2;
  commentId: number;
  actors: string[];
  latestAt: string;
  unread: boolean;
}

/** 交易类：每条都是独立事件，不合并 */
export interface TradeNotification {
  kind: 'trade';
  key: string;
  type: TradeNotifType;
  symbol: string | null;
  side: 'LONG' | 'SHORT' | null;
  quantity: number | null;
  price: number | null;
  pnl: number | null;
  latestAt: string;
  unread: boolean;
}

export type MergedNotification = CommentNotification | TradeNotification;

const isTrade = (type: number): type is TradeNotifType => type >= 3;

/**
 * 分组规则按类型分叉：
 * - 评论类用 {@code type:commentId}。赞的 commentId 都指向"我那条被赞的评论"，多人赞自动合并成一组；
 *   回复的 commentId 是"对方那条回复"各不相同，天然落成独立组（回复本来就不该合并——
 *   每条内容不同、点击要跳到不同位置）。
 * - 交易类用 {@code type:id}，即每条自成一组。两次强平就是两件事，合并了会丢掉各自的价格和盈亏；
 *   而且交易通知的 commentId 恒为 null，按老规则会把所有同类型的挤成一组。
 */
export function mergeNotifications(list: NotificationItem[]): MergedNotification[] {
  const groups = new Map<string, MergedNotification>();

  for (const n of list) {
    if (isTrade(n.type)) {
      groups.set(`${n.type}:${n.id}`, {
        kind: 'trade',
        key: `${n.type}:${n.id}`,
        type: n.type,
        symbol: n.symbol,
        side: n.side,
        quantity: n.quantity,
        price: n.price,
        pnl: n.pnl,
        latestAt: n.createdAt,
        unread: !n.isRead,
      });
      continue;
    }

    const key = `${n.type}:${n.commentId}`;
    const g = groups.get(key);
    if (g && g.kind === 'comment') {
      if (n.actorName && !g.actors.includes(n.actorName)) g.actors.push(n.actorName);
      if (!n.isRead) g.unread = true;
    } else {
      groups.set(key, {
        kind: 'comment',
        key,
        type: n.type,
        commentId: n.commentId ?? 0,
        actors: n.actorName ? [n.actorName] : [],
        latestAt: n.createdAt,
        unread: !n.isRead,
      });
    }
  }

  return [...groups.values()].sort((a, b) => b.latestAt.localeCompare(a.latestAt));
}

/** 评论类文案：一行说清谁做了什么 */
export function describeComment(g: CommentNotification): string {
  const verb = g.type === 1 ? '赞了你的评论' : '评论了你';
  if (g.actors.length === 0) return verb;
  if (g.actors.length === 1) return `${g.actors[0]} ${verb}`;
  if (g.actors.length === 2) return `${g.actors[0]}、${g.actors[1]} ${verb}`;
  return `${g.actors[0]}、${g.actors[1]} 等 ${g.actors.length} 人${verb}`;
}

const SIDE_LABEL: Record<string, string> = { LONG: '多单', SHORT: '空单' };
const TRADE_LABEL: Record<TradeNotifType, string> = {
  3: '强平',
  4: '止损',
  5: '止盈',
  6: '全仓爆仓',
};

/** 交易类标题：`BTCUSDT 多单强平` / `全仓爆仓 · 3 个仓位` */
export function describeTrade(g: TradeNotification): string {
  if (g.type === 6) {
    // quantity 在全仓爆仓下是"爆掉的仓位数"，不是币的数量
    const count = g.quantity == null ? null : Math.round(g.quantity);
    return count ? `全仓爆仓 · ${count} 个仓位` : '全仓爆仓';
  }
  const side = g.side ? SIDE_LABEL[g.side] ?? '' : '';
  return `${g.symbol ?? ''} ${side}${TRADE_LABEL[g.type]}`.trim();
}
