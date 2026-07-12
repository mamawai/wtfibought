/**
 * ECharts 拟物主题工具：轴/网格/提示框与全站 CSS 变量同源，
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
  /** 网格线/轴线（比背景深/浅半档，保持 recessive） */
  gridLine: string;
  /** 卡片面色（tooltip 底） */
  card: string;
  /** 正文色 */
  fg: string;
  /** 拟物 tooltip 外观（直接展开进 ECharts tooltip 配置） */
  tooltip: {
    backgroundColor: string;
    borderWidth: number;
    padding: number[];
    textStyle: { color: string; fontSize: number; fontFamily: string };
    extraCssText: string;
  };
}

export function chartUi(isDark: boolean): ChartUi {
  const axisLabel = cssVar('--color-muted-foreground', isDark ? '#A3A3A3' : '#78716C');
  const gridLine = isDark ? '#3a3e47' : '#cdd3db';
  const card = cssVar('--color-card', isDark ? '#2d3039' : '#e0e5ec');
  const fg = cssVar('--color-foreground', isDark ? '#e4e6eb' : '#44475a');
  return {
    axisLabel,
    gridLine,
    card,
    fg,
    tooltip: {
      backgroundColor: card,
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: fg, fontSize: 11, fontFamily: "'Plus Jakarta Sans', ui-sans-serif, sans-serif" },
      extraCssText: isDark
        ? 'box-shadow: 4px 4px 10px #22242b, -3px -3px 8px #383c45; border-radius: 10px;'
        : 'box-shadow: 5px 5px 12px #b8bec7, -5px -5px 12px #ffffff; border-radius: 10px;',
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
