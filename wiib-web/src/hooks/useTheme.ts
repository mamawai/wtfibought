import { useCallback, useState } from 'react';

export function useTheme() {
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  const toggleTheme = useCallback(() => {
    const newIsDark = !isDark;
    const apply = () => document.documentElement.classList.toggle('dark', newIsDark);
    // View Transition 浏览器自带交叉淡入(~250ms)，不支持的直接切
    if (document.startViewTransition) {
      document.startViewTransition(apply);
    } else {
      apply();
    }
    localStorage.setItem('theme', newIsDark ? 'dark' : 'light');
    setIsDark(newIsDark);
  }, [isDark]);

  return { toggleTheme, isDark };
}
