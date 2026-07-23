/**
 * 同步 <meta name="theme-color"> 到当前主题的顶栏底色。
 *
 * 装成 PWA 后没有地址栏夹在中间，状态栏紧贴顶栏：theme-color 若固定成品牌橙，
 * 屏幕最上方就是"一条橙杠顶着一条白/黑顶栏"的硬切。取值必须与顶栏 bg-card 一致，
 * 状态栏才会和顶栏融成一块。值同 index.css 的 --color-card。
 *
 * 冷启动那次由 index.html 的内联脚本先设好，避免闪一下默认色；此处负责切主题时跟手更新。
 */
const CARD_LIGHT = '#ffffff';
const CARD_DARK = '#13151a';

export function syncThemeColor(isDark: boolean) {
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', isDark ? CARD_DARK : CARD_LIGHT);
}
