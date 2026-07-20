import type { NotificationItem } from '../types';

export type MergedNotification = {
  key: string;
  type: 1 | 2;
  commentId: number;
  actors: string[];
  latestAt: string;
  unread: boolean;
};

/**
 * 按 type + commentId 分组。这一个分组键让两种类型各自表现正确：
 * 赞的 commentId 都指向"我那条被赞的评论"，多人赞自动合并成一组；
 * 回复的 commentId 是"对方那条回复"各不相同，天然落成独立组（回复本来就不该合并——
 * 每条内容不同、点击要跳到不同位置）。所以两种类型只需要这一套逻辑。
 */
export function mergeNotifications(list: NotificationItem[]): MergedNotification[] {
  const groups = new Map<string, MergedNotification>();
  for (const n of list) {
    const key = `${n.type}:${n.commentId}`;
    const g = groups.get(key);
    if (g) {
      if (!g.actors.includes(n.actorName)) g.actors.push(n.actorName);
      if (!n.isRead) g.unread = true;
    } else {
      groups.set(key, {
        key,
        type: n.type,
        commentId: n.commentId,
        actors: [n.actorName],
        latestAt: n.createdAt,
        unread: !n.isRead,
      });
    }
  }
  return [...groups.values()].sort((a, b) => b.latestAt.localeCompare(a.latestAt));
}

export function describeNotification(g: MergedNotification): string {
  const verb = g.type === 1 ? '赞了你的评论' : '评论了你';
  if (g.actors.length === 1) return `${g.actors[0]} ${verb}`;
  if (g.actors.length === 2) return `${g.actors[0]}、${g.actors[1]} ${verb}`;
  return `${g.actors[0]}、${g.actors[1]} 等 ${g.actors.length} 人${verb}`;
}
