import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import type { CategoryAverages } from '../types';
import { useIsDark } from '../hooks/useIsDark';

interface Props {
  userData: CategoryAverages;
}

// 五分类能力轴：与后端 CategoryAveragesDTO 一一对应
const INDICATORS = [
  { name: '加密货币', key: 'cryptoProfit' },
  { name: '大宗商品', key: 'commodityProfit' },
  { name: 'bStock', key: 'bstockProfit' },
  { name: '预测', key: 'predictionProfit' },
  { name: '游戏', key: 'gameProfit' },
];

export function RadarChart({ userData }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstanceRef = useRef<echarts.ECharts | null>(null);
  const isDark = useIsDark();

  useEffect(() => {
    if (!chartRef.current) return;
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
          textStyle: { color: d ? '#878b96' : '#71737b', fontSize: 11 },
        },
        tooltip: {
          trigger: 'item',
          backgroundColor: d ? '#13151a' : '#fff',
          borderColor: d ? '#23262e' : '#e4e4df',
          textStyle: { color: d ? '#eceef0' : '#17181a', fontSize: 12 },
          formatter: (params: { value: number[] }) => {
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
            color: d ? '#878b96' : '#71737b',
            fontSize: 11,
          },
          splitLine: {
            lineStyle: { color: d ? '#23262e' : '#e4e4df' },
          },
          splitArea: {
            areaStyle: { color: d ? ['#13151a', '#181b21', '#13151a', '#181b21', '#13151a'] : ['#fafaf8', '#f1f1ee', '#fafaf8', '#f1f1ee', '#fafaf8'] },
          },
          axisLine: {
            lineStyle: { color: d ? '#23262e' : '#e4e4df' },
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
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chartInstanceRef.current?.dispose();
    };
  }, [userData, isDark]);

  return (
    <div className="w-full">
      <div ref={chartRef} className="w-full h-72 sm:h-96" />
      {/* 五分类百分位速览：免 hover 直读，值=胜过多少其他用户 */}
      <div className="grid grid-cols-5 gap-1 mt-2 px-1">
        {INDICATORS.map(ind => {
          const v = Number(userData[ind.key as keyof CategoryAverages] || 0);
          return (
            <div key={ind.key} className="text-center">
              <div className="text-[10px] text-muted-foreground leading-tight">{ind.name}</div>
              <div className={`text-xs font-bold tabular-nums ${v >= 50 ? 'text-green-400' : 'text-muted-foreground'}`}>
                {v.toFixed(0)}%
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
