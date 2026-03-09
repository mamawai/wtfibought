import { useCallback, useState, useRef } from 'react';

export function useTheme() {
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));
  const ref = useRef<HTMLButtonElement>(null);

  const toggleTheme = useCallback(async () => {
    const newIsDark = !isDark;

    // 获取按钮位置作为动画起点
    const button = ref.current;
    const x = button ? button.getBoundingClientRect().left + button.offsetWidth / 2 : window.innerWidth / 2;
    const y = button ? button.getBoundingClientRect().top + button.offsetHeight / 2 : 0;

    // 计算圆形动画需要的最大半径
    const endRadius = Math.hypot(
      Math.max(x, window.innerWidth - x),
      Math.max(y, window.innerHeight - y)
    );

    // 检查浏览器是否支持 View Transition API
    if (!document.startViewTransition) {
      // 不支持则直接切换
      if (newIsDark) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }
      localStorage.setItem('theme', newIsDark ? 'dark' : 'light');
      setIsDark(newIsDark);
      return;
    }

    // 使用 View Transition API 实现圆形发散动画
    const transition = document.startViewTransition(() => {
      if (newIsDark) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }
    });

    await transition.ready;

    // 圆形裁剪动画
    const clipPath = newIsDark
      ? [`circle(${endRadius}px at ${x}px ${y}px)`, `circle(0px at ${x}px ${y}px)`]
      : [`circle(0px at ${x}px ${y}px)`, `circle(${endRadius}px at ${x}px ${y}px)`];

    document.documentElement.animate(
      { clipPath },
      {
        duration: 400,
        easing: 'ease-in-out',
        pseudoElement: newIsDark ? '::view-transition-old(root)' : '::view-transition-new(root)',
      }
    );

    localStorage.setItem('theme', newIsDark ? 'dark' : 'light');
    setIsDark(newIsDark);
  }, [isDark]);

  return {
    ref,
    toggleTheme,
    isDark,
  };
}
