/**
 * ECharts 精密终端主题工具：轴/网格/提示框与全站 CSS 变量同源，
 * 亮暗双模式各取各的层次色，杜绝图表里硬编码白底 slate 灰。
 */

/** 读 tailwind v4 @theme 变量（运行时随亮暗模式变化） */
export function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

export interface ChartUi {
  /** 轴刻度文字 */
  axisLabel: string;
  /** 网格线/轴线（用全站边框色，保持 recessive） */
  gridLine: string;
  /** 卡片面色（tooltip 底） */
  card: string;
  /** 正文色 */
  fg: string;
  /** 平面 tooltip 外观（直接展开进 ECharts tooltip 配置） */
  tooltip: {
    backgroundColor: string;
    borderColor: string;
    borderWidth: number;
    padding: number[];
    textStyle: { color: string; fontSize: number; fontFamily: string };
    extraCssText: string;
  };
}

export function chartUi(isDark: boolean): ChartUi {
  const axisLabel = cssVar('--color-muted-foreground', isDark ? '#878b96' : '#71737b');
  const gridLine = cssVar('--color-border', isDark ? '#23262e' : '#e4e4df');
  const card = cssVar('--color-card', isDark ? '#13151a' : '#ffffff');
  const fg = cssVar('--color-foreground', isDark ? '#eceef0' : '#17181a');
  return {
    axisLabel,
    gridLine,
    card,
    fg,
    tooltip: {
      backgroundColor: card,
      borderColor: gridLine,
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: fg, fontSize: 11, fontFamily: cssVar('--font-sans', 'ui-sans-serif, sans-serif') },
      extraCssText: isDark
        ? 'box-shadow: 0 6px 16px rgba(0,0,0,.45); border-radius: 8px;'
        : 'box-shadow: 0 4px 12px rgba(0,0,0,.10); border-radius: 8px;',
    },
  };
}

/** hex → rgba，渐变透明度用 */
export function rgba(hex: string, a: number): string {
  const m = hex.replace('#', '');
  const n = m.length === 3 ? m.split('').map((c) => c + c).join('') : m;
  const r = parseInt(n.slice(0, 2), 16);
  const g = parseInt(n.slice(2, 4), 16);
  const b = parseInt(n.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}
