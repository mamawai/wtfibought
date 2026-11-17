/**
 * 图表主题工具：轴/网格/提示框与全站 CSS 变量同源，
 * 亮暗双模式各取各的层次色，杜绝图表里硬编码白底 slate 灰。
 * <p>{@link chartUi} 给 ECharts，{@link lwcTheme} 给 lightweight-charts。
 */
import { CrosshairMode, LineStyle, type ChartOptions, type DeepPartial } from 'lightweight-charts';

/** 读 tailwind v4 @theme 变量（运行时随亮暗模式变化） */
export function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

export interface ChartUi {
  /** 轴刻度文字 */
  axisLabel: string;
  /** 网格线/轴线（用图表网格色，保持 recessive） */
  gridLine: string;
  /** 页面底色：数据点外圈描一圈它，跟线分开 */
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
  const axisLabel = cssVar('--color-muted-foreground', isDark ? '#9a9aa1' : '#6e6e73');
  const gridLine = cssVar('--color-grid', isDark ? '#1a1a1d' : '#f0f0f2');
  const card = cssVar('--color-background', isDark ? '#0c0c0d' : '#ffffff');
  const fg = cssVar('--color-foreground', isDark ? '#f4f4f6' : '#0b0b0c');
  return {
    axisLabel,
    gridLine,
    card,
    fg,
    tooltip: {
      backgroundColor: cssVar('--color-pop', isDark ? '#1b1b1e' : '#ffffff'),
      borderColor: 'transparent',
      borderWidth: 0,
      padding: [8, 12],
      textStyle: { color: fg, fontSize: 11, fontFamily: cssVar('--font-sans', 'ui-sans-serif, sans-serif') },
      // 同全站浮层：圆角 10、一圈发丝线 + 投影
      extraCssText: `border-radius:10px;box-shadow:${cssVar('--sh-pop', '0 12px 32px -8px rgba(0,0,0,.18)')};`,
    },
  };
}

/** lightweight-charts 那份主题：颜色现读 token，切主题时重新取一份 applyOptions 就够 */
export interface LwcTheme {
  fg: string;
  bg: string;
  mute: string;
  border: string;
  gain: string;
  loss: string;
  warning: string;
  primary: string;
  /** 三条线的固定配色：MA / EMA / RSI 三档按序取，DIF/DEA 取前两个 */
  col3: [string, string, string];
  /** 直接喂 createChart / applyOptions */
  options: DeepPartial<ChartOptions>;
  /** 蜡烛四色：涨绿跌红、影线与描边同色、实心 */
  candle: {
    upColor: string; downColor: string;
    borderUpColor: string; borderDownColor: string;
    wickUpColor: string; wickDownColor: string;
  };
}

export function lwcTheme(): LwcTheme {
  const fg = cssVar('--color-foreground', '#0b0b0c');
  const bg = cssVar('--color-background', '#ffffff');
  const mute = cssVar('--color-muted-foreground', '#6e6e73');
  const border = cssVar('--color-border', '#ebebed');
  const grid = cssVar('--color-grid', '#f0f0f2');
  const line2 = cssVar('--color-line2', '#d9d9de');
  const gain = cssVar('--color-gain', '#0b8a5c');
  const loss = cssVar('--color-loss', '#d63b2f');
  const warning = cssVar('--color-warning', '#a8700a');
  const primary = cssVar('--color-primary', '#f25f0a');
  const info = cssVar('--color-info', '#2563eb');
  const font = cssVar('--font-sans', 'ui-sans-serif, system-ui, sans-serif');
  // 十字线：两条浅灰线，轴上的标签是墨底；落在副图上时标签自动显示那个 pane 的值
  const cross = { color: line2, width: 1 as const, style: LineStyle.Solid, labelBackgroundColor: fg };
  return {
    fg, bg, mute, border, gain, loss, warning, primary,
    col3: [primary, info, '#7c5cff'],
    options: {
      layout: {
        background: { color: 'transparent' }, textColor: mute, fontSize: 11, fontFamily: font,
        // attributionLogo: v5 默认 true 会在右下角画 TradingView logo，关掉保持纸面干净
        attributionLogo: false,
        panes: { separatorColor: border, separatorHoverColor: mute, enableResize: true },
      },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      crosshair: { mode: CrosshairMode.Normal, vertLine: cross, horzLine: cross },
      rightPriceScale: { borderColor: border },
      timeScale: { borderColor: border },
    },
    candle: {
      upColor: gain, downColor: loss,
      borderUpColor: gain, borderDownColor: loss,
      wickUpColor: gain, wickDownColor: loss,
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
