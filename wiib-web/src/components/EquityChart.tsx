import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import type { TnEquityPoint } from '../types/testnet';

interface Props {
  points: TnEquityPoint[];
}

/** 读主题色（tailwind v4 @theme 变量），保证与全站盈亏配色一致。 */
function themeColor(name: string, fallback: string): string {
  const css = getComputedStyle(document.documentElement);
  return (css.getPropertyValue(`--color-${name}`) || css.getPropertyValue(`--${name}`) || fallback).trim();
}

/**
 * 累计已实现盈亏曲线。带 0 轴参考线；终值为正用 gain 色、为负用 loss 色，
 * 一眼看出策略到目前赚还是亏。数据来自 testnet income 流水累计。
 */
export function EquityChart({ points }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);

    const gain = themeColor('gain', '#10b981');
    const loss = themeColor('loss', '#ef4444');
    const data = points.map((p) => [p.time, p.cumPnl] as [number, number]);
    const last = points.length ? points[points.length - 1].cumPnl : 0;
    const color = last >= 0 ? gain : loss;
    const rgba = (hex: string, a: number) => {
      const m = hex.replace('#', '');
      const n = m.length === 3 ? m.split('').map((c) => c + c).join('') : m;
      const r = parseInt(n.slice(0, 2), 16), g = parseInt(n.slice(2, 4), 16), b = parseInt(n.slice(4, 6), 16);
      return `rgba(${r},${g},${b},${a})`;
    };

    chart.setOption({
      grid: { top: 20, right: 16, bottom: 28, left: 60 },
      tooltip: {
        trigger: 'axis',
        formatter: (params: { value: [number, number] }[]) => {
          const p = params[0];
          const v = p.value[1] as number;
          const sign = v >= 0 ? '+' : '';
          return `${new Date(p.value[0]).toLocaleString('zh-CN')}<br/>累计盈亏 ${sign}$${v.toFixed(2)}`;
        },
      },
      xAxis: {
        type: 'time',
        axisLabel: { fontSize: 10, color: '#94a3b8' },
        axisLine: { lineStyle: { color: '#e2e8f0' } },
      },
      yAxis: {
        type: 'value',
        axisLabel: { fontSize: 10, color: '#94a3b8', formatter: (v: number) => `$${v.toFixed(0)}` },
        splitLine: { lineStyle: { color: '#f1f5f9' } },
      },
      series: [{
        type: 'line',
        smooth: true,
        symbol: 'none',
        data,
        lineStyle: { width: 2, color },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: rgba(color, 0.22) },
            { offset: 1, color: rgba(color, 0.02) },
          ]),
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#cbd5e1', type: 'dashed', width: 1 },
          data: [{ yAxis: 0 }],
        },
      }],
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => { chart.dispose(); window.removeEventListener('resize', onResize); };
  }, [points]);

  return <div ref={ref} style={{ width: '100%', height: 220 }} />;
}
