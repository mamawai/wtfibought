import { useEffect, useState } from 'react';
import { notificationApi } from '../api';
import { subscribe } from './stompClient';
import { useNotificationStore } from '../stores/notificationStore';
import { mergeNotifications } from '../lib/notifications';
import type { NotificationItem } from '../types';

/**
 * 通知面板的行为层。PC 顶栏信封和「我的」页通知卡共用——两处的订阅、开合、
 * 标已读逻辑本来是各写一遍的，除了重复，还让未读数变成两份互不知情的状态。
 * 未读数放共享 store，两处永远一致。
 */
export function useNotificationPanel(userId: number | null) {
  const unread = useNotificationStore(s => s.unread);
  const setUnread = useNotificationStore(s => s.setUnread);

  const [open, setOpen] = useState(false);
  const [list, setList] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (userId == null) return;
    void notificationApi.unread().then(setUnread).catch(() => { /* 角标失败不打扰 */ });
    // 点对点推送只带未读数，面板内容等用户点开再拉，省一次无人看的查询
    return subscribe('/user/queue/notification', msg => {
      const body = JSON.parse(msg.body) as { unread: number };
      setUnread(body.unread);
    });
  }, [userId, setUnread]);

  const toggle = async () => {
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
      // 拉不到就是空面板，这两个位置都不适合弹 toast
    } finally {
      setLoading(false);
    }
  };

  return { unread, open, setOpen, loading, items: mergeNotifications(list), toggle };
}
