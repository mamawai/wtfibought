/** 系统设了「减少动效」：页面加载时读一次，中途改了设置要刷新才认 */
export const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
