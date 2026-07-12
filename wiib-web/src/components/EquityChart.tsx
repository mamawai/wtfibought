import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { useIsDark } from '../hooks/useIsDark';
import { chartUi, cssVar, rgba } from '../lib/chartTheme';
import type { TnEquityPoint } from '../types/testnet';

interface Props {
  points: TnEquityPoint[];
}

/**
 * 累计已实现盈亏曲线。带 0 轴参考线；终值为正用 gain 色、为负用 loss 色，
 * 末点实心标记收口。轴/网格/tooltip 走 chartTheme，亮暗模式自动匹配拟物底色。
 */
export function EquityChart({ points }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();

  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);
    const ui = chartUi(isDark);

    const gain = cssVar('--color-gain', '#089981');
    const loss = cssVar('--color-loss', '#f23645');
    const data = points.map((p) => [p.time, p.cumPnl] as [number, number]);
    const lastPoint = points.length ? points[points.length - 1] : null;
    const color = (lastPoint?.cumPnl ?? 0) >= 0 ? gain : loss;

    chart.setOption({
      grid: { top: 16, right: 14, bottom: 24, left: 52 },
      tooltip: {
        trigger: 'axis',
        ...ui.tooltip,
        axisPointer: { lineStyle: { color: ui.gridLine, type: 'dashed' } },
        formatter: (params: { value: [number, number] }[]) => {
          const p = params[0];
          const v = p.value[1] as number;
          const sign = v >= 0 ? '+' : '';
          return `${new Date(p.value[0]).toLocaleString('zh-CN')}<br/><b>累计盈亏 ${sign}$${v.toFixed(2)}</b>`;
        },
      },
      xAxis: {
        type: 'time',
        axisLabel: { fontSize: 10, color: ui.axisLabel },
        axisLine: { lineStyle: { color: ui.gridLine } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        axisLabel: { fontSize: 10, color: ui.axisLabel, formatter: (v: number) => `$${v.toFixed(0)}` },
        splitLine: { lineStyle: { color: ui.gridLine, opacity: 0.6 } },
      },
      series: [
        {
          type: 'line',
          smooth: true,
          symbol: 'none',
          data,
          lineStyle: {
            width: 2,
            color,
            shadowColor: rgba(color, 0.35),
            shadowBlur: 6,
            shadowOffsetY: 4,
          },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: rgba(color, 0.2) },
              { offset: 1, color: rgba(color, 0.02) },
            ]),
          },
          markLine: {
            silent: true,
            symbol: 'none',
            label: { show: false },
            lineStyle: { color: ui.gridLine, type: 'dashed', width: 1 },
            data: [{ yAxis: 0 }],
          },
        },
        // 末点实心标记：面色描边（surface ring），一眼定位"现在到哪了"
        lastPoint && {
          type: 'scatter',
          data: [[lastPoint.time, lastPoint.cumPnl]],
          symbolSize: 8,
          itemStyle: { color, borderColor: ui.card, borderWidth: 2 },
          tooltip: { show: false },
          silent: true,
          z: 5,
        },
      ].filter(Boolean),
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => { chart.dispose(); window.removeEventListener('resize', onResize); };
  }, [points, isDark]);

  return <div ref={ref} style={{ width: '100%', height: 220 }} />;
}
