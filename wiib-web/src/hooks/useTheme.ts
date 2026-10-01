import { useCallback } from 'react';
import { syncThemeColor } from '../lib/themeColor';
import { useIsDark } from './useIsDark';

export function useTheme() {
  // 明暗从 <html> 的 dark class 派生：顶栏和「我的」页各调一次也看到的是同一份
  const isDark = useIsDark();

  const toggleTheme = useCallback(() => {
    const newIsDark = !document.documentElement.classList.contains('dark');
    const apply = () => {
      document.documentElement.classList.toggle('dark', newIsDark);
      syncThemeColor(newIsDark); // PWA 状态栏跟着顶栏一起变，否则切主题后状态栏还留着旧色
    };
    // View Transition 浏览器自带交叉淡入(~250ms)，不支持的直接切
    if (document.startViewTransition) {
      document.startViewTransition(apply);
    } else {
      apply();
    }
    localStorage.setItem('theme', newIsDark ? 'dark' : 'light');
  }, []);

  return { toggleTheme, isDark };
}
