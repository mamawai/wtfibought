import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { LATEST_VERSION } from '../lib/releases';

const STORAGE_KEY = 'wiib-release';

/**
 * 更新日志的状态，全局一份：顶栏图标桌面、手机各挂一颗，弹窗只挂一份，都看这里。
 * 看过哪一版按设备记在 localStorage，不分账号
 */
interface ReleaseState {
  /** 这台设备上看过的最新版本号；没看过是 null */
  seen: string | null;
  open: boolean;
  openNotes: () => void;
  /** 关上就算看过最新这一版，顶栏图标上的橙点跟着消失 */
  closeNotes: () => void;
}

export const useReleaseStore = create<ReleaseState>()(
  persist(
    (set) => ({
      seen: null,
      open: false,
      openNotes: () => set({ open: true }),
      closeNotes: () => set({ open: false, seen: LATEST_VERSION }),
    }),
    {
      name: STORAGE_KEY,
      partialize: (state) => ({ seen: state.seen }),
    },
  ),
);

/** 有没有这台设备还没看过的新版本 */
export const useHasUpdate = () => useReleaseStore(s => s.seen !== LATEST_VERSION);

// 别的标签页看过了，这一页跟着记上
window.addEventListener('storage', e => {
  if (e.key === STORAGE_KEY) void useReleaseStore.persist.rehydrate();
});
