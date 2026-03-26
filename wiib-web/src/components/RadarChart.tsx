import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import type { CategoryAverages } from '../types';

interface Props {
  userData: CategoryAverages;
}

const INDICATORS = [
  { name: '股票', key: 'stockProfit' },
  { name: '加密货币', key: 'cryptoProfit' },
  { name: '合约', key: 'futuresProfit' },
  { name: '期权', key: 'optionProfit' },
  { name: '预测', key: 'predictionProfit' },
  { name: '游戏', key: 'gameProfit' },
];

export function RadarChart({ userData }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstanceRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!chartRef.current) return;
    const isDark = document.documentElement.classList.contains('dark');
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');
    chartInstanceRef.current = chart;

    const buildOption = (d: boolean) => {
      const userValues = INDICATORS.map(ind => userData[ind.key as keyof CategoryAverages] || 0);
      const avgVal = userValues.length ? userValues.reduce((a, b) => a + b, 0) / userValues.length : 0;
      return {
        backgroundColor: 'transparent',
        legend: {
          data: [`超过${Math.round(avgVal)}%的其他用户`],
          bottom: 0,
          textStyle: { color: d ? '#94A3B8' : '#78716C', fontSize: 11 },
        },
        tooltip: {
          trigger: 'item',
          backgroundColor: d ? '#1E293B' : '#fff',
          borderColor: d ? '#334155' : '#E7E0D8',
          textStyle: { color: d ? '#E2E8F0' : '#1C1917', fontSize: 12 },
          formatter: (params: any) => {
            const vals = params.value;
            return INDICATORS.map((ind, i) => `${ind.name}: <b>超过${Number(vals[i]).toFixed(2)}%的其他用户</b>`).join('<br/>');
          },
        },
        radar: {
          indicator: INDICATORS.map(ind => ({
            name: ind.name,
            max: 100,
          })),
          shape: 'polygon',
          splitNumber: 5,
          center: ['50%', '45%'],
          radius: '65%',
          axisName: {
            color: d ? '#94A3B8' : '#78716C',
            fontSize: 11,
          },
          splitLine: {
            lineStyle: { color: d ? '#334155' : '#E7E0D8' },
          },
          splitArea: {
            areaStyle: { color: d ? ['#1E293B', '#263244', '#1E293B', '#263244', '#1E293B'] : ['#F5F0EB', '#EBE5DD', '#F5F0EB', '#EBE5DD', '#F5F0EB'] },
          },
          axisLine: {
            lineStyle: { color: d ? '#334155' : '#E7E0D8' },
          },
        },
        series: [
          {
            type: 'radar',
            data: [
              {
                value: userValues,
                name: `超过${Math.round(avgVal)}%的其他用户`,
                lineStyle: { color: '#635bff', width: 2 },
                areaStyle: { color: 'rgba(99, 91, 255, 0.3)' },
                itemStyle: { color: '#635bff' },
                symbol: 'circle',
                symbolSize: 6,
              },
            ],
          },
        ],
      };
    };

    chart.setOption(buildOption(isDark));

    const onResize = () => chartInstanceRef.current?.resize();
    const observer = new MutationObserver((records) => {
      if (records.some(r => r.attributeName === 'class') && chartInstanceRef.current) {
        chartInstanceRef.current.dispose();
        const newDark = document.documentElement.classList.contains('dark');
        const newChart = echarts.init(chartRef.current!, newDark ? 'dark' : 'light');
        chartInstanceRef.current = newChart;
        newChart.setOption(buildOption(newDark));
      }
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });

    window.addEventListener('resize', onResize);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', onResize);
      chartInstanceRef.current?.dispose();
    };
  }, [userData]);

  return (
    <div className="w-full">
      <div ref={chartRef} className="w-full h-80 sm:h-96" />
    </div>
  );
}
