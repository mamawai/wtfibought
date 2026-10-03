import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { CategoryAverages } from '../types';
import { useIsDark } from '../hooks/useIsDark';
import { chartUi, cssVar, rgba } from '../lib/chartTheme';

interface Props {
  userData: CategoryAverages;
}

// 四分类能力轴：与后端 CategoryAveragesDTO 一一对应。表里存词表 key，渲染时现查——
// 存成文案会在模块加载那一刻定死，切语言不跟着变
const INDICATORS = [
  { labelKey: 'radar.crypto', key: 'cryptoProfit' },
  { labelKey: 'radar.commodity', key: 'commodityProfit' },
  { labelKey: 'radar.bstock', key: 'bstockProfit' },
  { labelKey: 'radar.prediction', key: 'predictionProfit' },
];

export function RadarChart({ userData }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstanceRef = useRef<echarts.ECharts | null>(null);
  const isDark = useIsDark();
  const { t, i18n } = useTranslation('strategy');

  // 依赖带 i18n.language：切语言时整张图重建，legend/tooltip/轴名才跟着换
  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');
    chartInstanceRef.current = chart;

    const buildOption = (d: boolean) => {
      const ui = chartUi(d);
      const card2 = cssVar('--color-card-2', d ? '#1a1b1f' : '#f0f0ec');
      const primary = cssVar('--color-primary', d ? '#f97316' : '#f25f0a');
      const userValues = INDICATORS.map(ind => userData[ind.key as keyof CategoryAverages] || 0);
      const avgVal = userValues.length ? userValues.reduce((a, b) => a + b, 0) / userValues.length : 0;
      return {
        backgroundColor: 'transparent',
        legend: {
          data: [t('radar.betterThan', { pct: Math.round(avgVal) })],
          bottom: 0,
          textStyle: { color: ui.axisLabel, fontSize: 11 },
          icon: 'rect',
        },
        tooltip: {
          trigger: 'item',
          ...ui.tooltip,
          formatter: (params: { value: number[] }) => {
            const vals = params.value;
            return INDICATORS.map((ind, i) =>
              `${t(ind.labelKey)}: <b>${t('radar.betterThan', { pct: Number(vals[i]).toFixed(2) })}</b>`).join('<br/>');
          },
        },
        radar: {
          indicator: INDICATORS.map(ind => ({
            name: t(ind.labelKey),
            max: 100,
          })),
          shape: 'polygon',
          splitNumber: 5,
          center: ['50%', '45%'],
          radius: '65%',
          axisName: {
            color: ui.axisLabel,
            fontSize: 11,
          },
          splitLine: {
            lineStyle: { color: ui.gridLine },
          },
          // 一圈深一圈浅，只在纸色和次层面色之间交替
          splitArea: {
            areaStyle: { color: [ui.card, card2, ui.card, card2, ui.card] },
          },
          axisLine: {
            lineStyle: { color: ui.gridLine },
          },
        },
        series: [
          {
            type: 'radar',
            data: [
              {
                value: userValues,
                name: t('radar.betterThan', { pct: Math.round(avgVal) }),
                lineStyle: { color: primary, width: 2 },
                areaStyle: { color: rgba(primary, 0.22) },
                itemStyle: { color: primary },
                symbol: 'rect',
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
  }, [userData, isDark, t, i18n.language]);

  return (
    <div className="w-full">
      <div ref={chartRef} className="w-full h-[320px]" />
      {/* 五分类百分位速览：免 hover 直读，值=胜过多少其他用户 */}
      <div className="grid grid-cols-5 gap-1 mt-2">
        {INDICATORS.map(ind => {
          const v = Number(userData[ind.key as keyof CategoryAverages] || 0);
          return (
            <div key={ind.key} className="text-center">
              <div className="text-[11.5px] mute leading-tight">{t(ind.labelKey)}</div>
              <div className={`num text-[13px] font-bold ${v >= 50 ? 'up' : 'mute'}`}>
                {v.toFixed(0)}%
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
