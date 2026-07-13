import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '../types';
import { authApi } from '../api';

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
      setToken: (token: string | null) => set({ token }),

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
      },
    }),
    {
      name: 'wiib-user',
      partialize: (state) => ({ token: state.token }),
    }
  )
);
