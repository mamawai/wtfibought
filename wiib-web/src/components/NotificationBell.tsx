import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { useUserStore } from '../stores/userStore';
import { useClickOutside } from '../hooks/useClickOutside';
import { useNotificationPanel } from '../hooks/useNotificationPanel';
import { NotificationList } from './NotificationList';

/**
 * 顶栏信封：未读角标 + 下拉面板。点开即全部标已读，面板仍列最近 50 条历史。
 * 只在 PC 显示（顶栏整体 hidden md:flex），手机端的通知入口在「我的」页。
 */
export function NotificationBell() {
  const navigate = useNavigate();
  // 只取 id：fetchUser 每次都换一个新 user 对象，盯整个对象会反复退订重订，
  // 而退订到 0 时 stompClient 会直接断开连接，白白抖一次 WS
  const userId = useUserStore(s => s.user?.id ?? null);

  const { unread, open, setOpen, loading, items, toggle } = useNotificationPanel(userId);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false), open);

  if (userId == null) return null;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => void toggle()}
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
          <NotificationList
            items={items}
            loading={loading}
            onSelect={commentId => { setOpen(false); navigate(`/comments?focus=${commentId}`); }}
            className="max-h-96"
          />
        </div>
      )}
    </div>
  );
}
