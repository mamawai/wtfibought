import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { useUserStore } from '../stores/userStore';
import { useClickOutside } from '../hooks/useClickOutside';
import { useNotificationPanel } from '../hooks/useNotificationPanel';
import { NotificationList } from './NotificationList';
import { cn } from '../lib/utils';

/**
 * 顶栏铃铛：未读角标 + 下拉面板。点开即全部标已读，面板仍列最近 50 条历史。
 * 样式是顶栏那套 .dd / .bell / .menu（见 index.css）。
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
    <div ref={ref} className={cn('dd', open && 'open')}
         onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
      <button type="button" className="bell" onClick={() => void toggle()}
              aria-label={t('notif.title')} aria-haspopup="dialog" aria-expanded={open}>
        <Bell className="ic" />
        {/* 一直在，有未读才显出来：数字是长出来、缩回去的 */}
        <em className={cn(unread > 0 && 'on')}>{unread > 99 ? '99+' : unread}</em>
      </button>

      {/* 面板一直挂着，开合靠 .dd.open 的过渡 */}
      <div className="menu rt notif" role="dialog" aria-label={t('notif.title')}>
        <h3>{t('notif.title')}</h3>
        <NotificationList
          items={items}
          loading={loading}
          onSelect={commentId => { setOpen(false); navigate(`/comments?focus=${commentId}`); }}
          className="max-h-[392px]"
        />
      </div>
    </div>
  );
}
