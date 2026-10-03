import * as echarts from 'echarts';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { AssetSnapshot } from '../types';
import { useIsDark } from '../hooks/useIsDark';
import { chartUi, cssVar, rgba } from '../lib/chartTheme';

interface Props {
  data: AssetSnapshot[];
}

// 四分类收益曲线：crypto 含币合约，大宗商品含金/油合约
// 常量在组件外拿不到 t 和 token，存词表 key 和变量名，画图时再查——
// 存翻译结果/色值会在模块加载那一刻定死，切语言、切主题都不跟着变
const CUMULATIVE_CONFIG = [
  { key: 'profit', nameKey: 'cat.totalProfit', token: '--color-foreground' },
  { key: 'cryptoProfit', nameKey: 'cat.crypto', token: '--color-primary' },
  { key: 'commodityProfit', nameKey: 'cat.commodity', token: '--color-warning' },
  { key: 'bstockProfit', nameKey: 'cat.bstock', token: '--color-gain' },
  { key: 'predictionProfit', nameKey: 'cat.prediction', token: '--color-loss' },
] as const;

const DAILY_CONFIG = [
  { key: 'dailyProfit', nameKey: 'cat.dailyProfit', token: '--color-foreground' },
  { key: 'dailyCryptoProfit', nameKey: 'cat.crypto', token: '--color-primary' },
  { key: 'dailyCommodityProfit', nameKey: 'cat.commodity', token: '--color-warning' },
  { key: 'dailyBstockProfit', nameKey: 'cat.bstock', token: '--color-gain' },
  { key: 'dailyPredictionProfit', nameKey: 'cat.prediction', token: '--color-loss' },
] as const;

export function ProfitChart({ data }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<'cumulative' | 'daily'>('cumulative');
  const [dailyRange, setDailyRange] = useState<7 | 14 | 30>(7);
  const isDark = useIsDark();
  const { t, i18n } = useTranslation('portfolio');

  const filteredData = mode === 'daily' ? data.slice(-dailyRange) : data;

  useEffect(() => {
    if (!chartRef.current || filteredData.length === 0) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');

    const ui = chartUi(isDark);
    const config = mode === 'daily' ? DAILY_CONFIG : CUMULATIVE_CONFIG;
    const dates = filteredData.map(d => d.date);
    const gainColor = cssVar('--color-gain', isDark ? '#3ecf8e' : '#0b8a5c');
    const lossColor = cssVar('--color-loss', isDark ? '#ff6b6b' : '#d63b2f');

    const series: echarts.SeriesOption[] = config.map(cfg => {
      const color = cssVar(cfg.token, ui.fg);
      const isTotal = cfg.key === 'profit' || cfg.key === 'dailyProfit';
      return {
        name: t(cfg.nameKey),
        type: 'line',
        data: filteredData.map(d => d[cfg.key as keyof AssetSnapshot] as number ?? 0),
        smooth: true,
        symbol: 'circle',
        symbolSize: filteredData.length <= 7 ? 6 : 0,
        lineStyle: { width: isTotal ? 2.5 : 1.5 },
        itemStyle: { color },
        // 总收益那条带面积，深浅由墨色透明度给，不引第二个色系
        ...(isTotal ? {
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: rgba(color, isDark ? 0.22 : 0.14) },
              { offset: 1, color: rgba(color, 0) },
            ]),
          },
        } : {}),
        emphasis: { focus: 'series' as const },
      };
    });

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        ...ui.tooltip,
        formatter: (params: { axisValue: string; value: number; marker: string; seriesName: string }[]) => {
          const date = params[0]?.axisValue ?? '';
          let html = `<div style="font-weight:600;margin-bottom:4px">${date}</div>`;
          for (const p of params) {
            const v = (p.value as number).toFixed(2);
            const sign = p.value >= 0 ? '+' : '';
            html += `<div style="display:flex;align-items:center;gap:6px;margin:2px 0">
              ${p.marker}<span>${p.seriesName}</span>
              <span style="margin-left:auto;font-weight:600;color:${p.value >= 0 ? gainColor : lossColor}">${sign}${v}</span>
            </div>`;
          }
          return html;
        },
      },
      legend: {
        bottom: 0,
        textStyle: { color: ui.axisLabel, fontSize: 10 },
        itemWidth: 10,
        itemHeight: 2,
        itemGap: 6,
        icon: 'rect',
        type: 'scroll',
      },
      grid: { left: 8, right: 8, top: 16, bottom: 40, containLabel: true },
      xAxis: {
        type: 'category',
        data: dates,
        axisLabel: {
          color: ui.axisLabel,
          fontSize: 9,
          formatter: (v: string) => v.substring(5),
        },
        axisLine: { lineStyle: { color: ui.gridLine } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: ui.gridLine, type: 'dashed' } },
        axisLabel: { color: ui.axisLabel, fontSize: 9 },
      },
      series,
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
    // 依赖里必须带 i18n.language：少了它切语言后 option 不重算，图例还是旧文案
  }, [filteredData, mode, isDark, t, i18n.language]);

  if (data.length === 0) {
    return (
      <div className="w-full h-[320px] flex items-center justify-center text-[14px] mute">
        {t('chart.noHistory')}
      </div>
    );
  }

  return (
    <div className="w-full">
      <div className="flex flex-wrap justify-between items-center gap-2 mb-2">
        {mode === 'daily' && (
          <span className="seg">
            {([7, 14, 30] as const).map(d => (
              <button key={d} type="button" className={dailyRange === d ? 'on' : ''} onClick={() => setDailyRange(d)}>
                {t('chart.range', { days: d })}
              </button>
            ))}
          </span>
        )}
        <span className="seg ml-auto">
          <button type="button" className={mode === 'cumulative' ? 'on' : ''} onClick={() => setMode('cumulative')}>
            {t('chart.cumulative')}
          </button>
          <button type="button" className={mode === 'daily' ? 'on' : ''} onClick={() => setMode('daily')}>
            {t('cat.dailyProfit')}
          </button>
        </span>
      </div>
      <div ref={chartRef} className="w-full h-[320px]" />
    </div>
  );
}
