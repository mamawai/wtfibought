import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { useUserStore } from '../stores/userStore';
import { useClickOutside } from '../hooks/useClickOutside';
import { useNotificationPanel } from '../hooks/useNotificationPanel';
import { NotificationList } from './NotificationList';

/**
 * 顶栏信封：未读角标 + 下拉面板。点开即全部标已读，面板仍列最近 50 条历史。
 * 按钮壳跟着 .tools button / .tools .ic 走（无底色无边框，图标 15px 灰）。
 * 只在 PC 显示（顶栏工具区整体 hidden lg:flex），手机端的通知入口在「我的」页。
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const { t } = useTranslation('account');
  // 只取 id：fetchUser 每次都换一个新 user 对象，盯整个对象会反复退订重订，
  // 而退订到 0 时 stompClient 会直接断开连接，白白抖一次 WS
  const userId = useUserStore(s => s.user?.id ?? null);

  const { unread, open, setOpen, loading, items, toggle } = useNotificationPanel(userId);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false), open);

  if (userId == null) return null;

  return (
    // flex 不能省：块级 div 会按行高撑出行盒，按钮贴着基线，图标比邻居高一截
    <div ref={ref} className="relative flex">
      <button
        type="button"
        onClick={() => void toggle()}
        className="relative inline-flex cursor-pointer"
        aria-label={t('notif.title')}
      >
        <Bell className="ic" />
        {unread > 0 && (
          <span className="absolute -top-2 -right-2 min-w-4 h-4 px-1 bg-primary text-white text-[9px] font-black tabular-nums flex items-center justify-center">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-1.5 w-80 border border-foreground bg-background overflow-hidden z-50 animate-in fade-in slide-in-from-top-2">
          <div className="px-4 py-2.5 text-[13px] font-semibold text-muted-foreground border-b border-border">{t('notif.title')}</div>
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
