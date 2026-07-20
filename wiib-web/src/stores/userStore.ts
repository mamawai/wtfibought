import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '../types';
import { authApi } from '../api';
import { reconnectWithIdentity } from '../hooks/stompClient';

interface UserState {
  user: User | null;
  token: string | null;
  loading: boolean;
  setUser: (user: User | null) => void;
  setToken: (token: string | null) => void;
  fetchUser: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useUserStore = create<UserState>()(
  persist(
    (set) => ({
      user: null,
      token: null,
      loading: false,

      setUser: (user: User | null) => set({ user }),
      // token 变了必须重连 WS：连接的身份是握手时用 token 定的，不重连就还挂着旧身份，
      // 表现为登录后通知角标永远 0 且不报错。persist 是同步写，重连读到的已是新值
      setToken: (token: string | null) => {
        set({ token });
        reconnectWithIdentity();
      },

      fetchUser: async () => {
        try {
          set({ loading: true });
          const user = await authApi.current();
          set({ user });
        } catch {
          set({ user: null, token: null });
        } finally {
          set({ loading: false });
        }
      },

      logout: async () => {
        try {
          await authApi.logout();
        } catch {
          // ignore
        }
        set({ user: null, token: null });
        reconnectWithIdentity();   // 退回匿名身份，否则登出后仍能收到上一个账号的通知
      },
    }),
    {
      name: 'wiib-user',
      partialize: (state) => ({ token: state.token }),
    }
  )
);
