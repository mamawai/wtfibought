import { create } from 'zustand';

/**
 * 未读通知数。必须是全局单份——PC 顶栏信封和「我的」页通知卡是两个组件，
 * 各自存一份的话，在「我的」页标了已读，顶栏那个红点还挂着旧数字，
 * 而顶栏是常驻组件不会重新挂载，这个假红点会一直留到下次刷新。
 */
interface NotificationState {
  unread: number;
  setUnread: (n: number) => void;
}

export const useNotificationStore = create<NotificationState>()((set) => ({
  unread: 0,
  setUnread: (unread: number) => set({ unread }),
}));
