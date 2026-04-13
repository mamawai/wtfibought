import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import type { AiTradingDecision } from '../types';

const INITIAL_BALANCE = 100000;

interface Props {
  decisions: AiTradingDecision[];
}

export function AiPnlChart({ decisions }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!chartRef.current || decisions.length === 0) return;

    // 按cycleNo去重，同一周期取最晚的那条（多symbol时余额可能变化多次）
    const cycleMap = new Map<number, AiTradingDecision>();
    for (const d of decisions) {
      const existing = cycleMap.get(d.cycleNo);
      if (!existing || new Date(d.createdAt) > new Date(existing.createdAt)) {
        cycleMap.set(d.cycleNo, d);
      }
    }
    const sorted = [...cycleMap.values()].sort(
      (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
    );

    const times = sorted.map(d =>
      new Date(d.createdAt).toLocaleString('zh-CN', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      }),
    );
    const pnlData = sorted.map(d => +(d.balanceAfter - INITIAL_BALANCE).toFixed(2));

    const isDark = document.documentElement.classList.contains('dark');
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');
    const textColor = isDark ? '#94A3B8' : '#78716C';

    const lastPnl = pnlData[pnlData.length - 1] ?? 0;
    const mainColor = lastPnl >= 0 ? '#22c55e' : '#ef4444';

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: isDark ? '#1E293B' : '#FFFFFF',
        borderColor: isDark ? '#334155' : '#E7E0D8',
        textStyle: { color: isDark ? '#F8FAFC' : '#1C1917', fontSize: 12 },
        formatter: (params: any) => {
          const p = params[0];
          if (!p) return '';
          const v = (p.value as number).toFixed(2);
          const sign = p.value >= 0 ? '+' : '';
          const color = p.value >= 0 ? '#22c55e' : '#ef4444';
          return `<div style="font-weight:600;margin-bottom:4px">${p.axisValue}</div>
            <div style="font-weight:700;font-size:14px;color:${color}">${sign}${v} USDT</div>`;
        },
      },
      grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
      xAxis: {
        type: 'category',
        data: times,
        axisLabel: { color: textColor, fontSize: 9, rotate: times.length > 15 ? 30 : 0 },
        axisLine: { lineStyle: { color: isDark ? '#334155' : '#E7E0D8' } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: isDark ? '#1E293B' : '#F5F0EB', type: 'dashed' } },
        axisLabel: { color: textColor, fontSize: 9 },
      },
      series: [{
        type: 'line',
        data: pnlData,
        smooth: true,
        symbol: pnlData.length <= 20 ? 'circle' : 'none',
        symbolSize: 5,
        lineStyle: { width: 2.5, color: mainColor },
        itemStyle: { color: mainColor },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: lastPnl >= 0 ? 'rgba(34,197,94,0.2)' : 'rgba(239,68,68,0.2)' },
            { offset: 1, color: 'rgba(0,0,0,0)' },
          ]),
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: isDark ? '#475569' : '#D6D3D1', type: 'dashed', width: 1 },
          data: [{ yAxis: 0 }],
          label: { show: false },
        },
      }],
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [decisions]);

  if (decisions.length === 0) return null;

  return <div ref={chartRef} className="w-full h-48 sm:h-56" />;
}
