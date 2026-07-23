/**
 * 同步 <meta name="theme-color"> —— 切主题时跟手更新，冷启动那次由 index.html 内联脚本先设好。
 *
 * 装成 App 后没有地址栏夹在中间，状态栏紧贴顶栏：固定成品牌橙的话，屏幕最上方就是
 * "一条橙杠顶着白/黑顶栏"的硬切，所以取顶栏 bg-card 同色（值同 index.css 的 --color-card）。
 * 浏览器里相反——这个色是拿去染地址栏的，维持品牌橙。
 */
const BRAND = '#F97316';
const CARD_LIGHT = '#ffffff';
const CARD_DARK = '#13151a';

export function syncThemeColor(isDark: boolean) {
  const meta = document.querySelector('meta[name="theme-color"]');
  if (!meta) return;
  // .pwa 由 index.html 内联脚本在首帧前打好，这里只读
  const pwa = document.documentElement.classList.contains('pwa');
  meta.setAttribute('content', pwa ? (isDark ? CARD_DARK : CARD_LIGHT) : BRAND);
}
