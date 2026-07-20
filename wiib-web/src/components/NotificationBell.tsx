import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Loader2 } from 'lucide-react';
import { notificationApi } from '../api';
import { subscribe } from '../hooks/stompClient';
import { useUserStore } from '../stores/userStore';
import { useClickOutside } from '../hooks/useClickOutside';
import { cn, fmtDateTime } from '../lib/utils';
import type { NotificationItem } from '../types';

type MergedNotification = {
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
 * 回复的 commentId 是"对方那条回复"各不相同，天然落成独立组（回复本来就不该合并）。
 * 所以两种类型只需要这一套逻辑。
 */
function mergeNotifications(list: NotificationItem[]): MergedNotification[] {
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

function describe(g: MergedNotification): string {
  const verb = g.type === 1 ? '赞了你的评论' : '评论了你';
  if (g.actors.length === 1) return `${g.actors[0]} ${verb}`;
  if (g.actors.length === 2) return `${g.actors[0]}、${g.actors[1]} ${verb}`;
  return `${g.actors[0]}、${g.actors[1]} 等 ${g.actors.length} 人${verb}`;
}

/** 顶栏信封：未读角标 + 下拉面板。点开即全部标已读，面板仍列最近 50 条历史。 */
export function NotificationBell() {
  const navigate = useNavigate();
  // 只取 id：fetchUser 每次都换一个新 user 对象，盯整个对象会反复退订重订，
  // 而退订到 0 时 stompClient 会直接断开连接，白白抖一次 WS
  const userId = useUserStore(s => s.user?.id ?? null);

  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const [list, setList] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false), open);

  useEffect(() => {
    if (userId == null) return;
    void notificationApi.unread().then(setUnread).catch(() => { /* 角标失败不打扰 */ });
    // 点对点推送只带未读数，面板内容等用户点开再拉，省一次无人看的查询
    return subscribe('/user/queue/notification', msg => {
      const body = JSON.parse(msg.body) as { unread: number };
      setUnread(body.unread);
    });
  }, [userId]);

  const togglePanel = async () => {
    if (open) { setOpen(false); return; }
    setOpen(true);
    setLoading(true);
    try {
      // 先取列表再标已读：顺序反了会把刚到的那几条也读成已读，面板里就分不出新旧了
      const items = await notificationApi.recent();
      setList(items);
      setUnread(0);
      await notificationApi.readAll();
    } catch {
      // 拉不到就是空面板，顶栏不适合弹 toast
    } finally {
      setLoading(false);
    }
  };

  if (userId == null) return null;

  const merged = mergeNotifications(list);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => void togglePanel()}
        className="relative w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
        aria-label="通知"
      >
        <Bell className="w-4 h-4" />
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-4 h-4 px-1 rounded-full bg-destructive text-white text-[9px] font-black tabular-nums flex items-center justify-center">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-3 w-80 rounded-2xl bg-card border border-border shadow-2xl overflow-hidden z-50">
          <div className="px-4 py-2.5 text-xs font-black border-b border-border/60">通知</div>

          {loading ? (
            <div className="flex items-center justify-center gap-2 py-10 text-xs text-muted-foreground">
              <Loader2 className="w-3.5 h-3.5 animate-spin" /> 加载中…
            </div>
          ) : merged.length === 0 ? (
            <div className="py-10 text-center text-xs text-muted-foreground">还没有通知</div>
          ) : (
            <div className="max-h-96 overflow-y-auto">
              {merged.map(g => (
                <button
                  key={g.key}
                  type="button"
                  onClick={() => { setOpen(false); navigate(`/comments?focus=${g.commentId}`); }}
                  className="w-full text-left px-4 py-2.5 border-b border-border/40 last:border-0 hover:bg-surface-hover transition-colors cursor-pointer"
                >
                  <div className="flex items-start gap-2">
                    <span className={cn(
                      'w-1.5 h-1.5 rounded-full mt-1.5 shrink-0',
                      g.unread ? 'bg-primary' : 'bg-transparent',
                    )} />
                    <div className="min-w-0 flex-1">
                      <div className="text-xs font-medium leading-snug break-words">{describe(g)}</div>
                      <div className="text-[10px] text-muted-foreground mt-0.5">{fmtDateTime(g.latestAt)}</div>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
