import * as echarts from 'echarts';
import { useEffect, useRef, useState } from 'react';
import { cn } from '../lib/utils';
import type { AssetSnapshot } from '../types';

interface Props {
  data: AssetSnapshot[];
}

const CUMULATIVE_CONFIG = [
  { key: 'profit', name: '总收益', color: '#635bff' },
  { key: 'stockProfit', name: '股票', color: '#3b82f6' },
  { key: 'cryptoProfit', name: '加密货币', color: '#f97316' },
  { key: 'futuresProfit', name: '合约', color: '#a855f7' },
  { key: 'optionProfit', name: '期权', color: '#14b8a6' },
  { key: 'predictionProfit', name: '预测', color: '#eab308' },
  { key: 'gameProfit', name: '游戏', color: '#ef4444' },
] as const;

const DAILY_CONFIG = [
  { key: 'dailyProfit', name: '日收益', color: '#635bff' },
  { key: 'dailyStockProfit', name: '股票', color: '#3b82f6' },
  { key: 'dailyCryptoProfit', name: '加密货币', color: '#f97316' },
  { key: 'dailyFuturesProfit', name: '合约', color: '#a855f7' },
  { key: 'dailyOptionProfit', name: '期权', color: '#14b8a6' },
  { key: 'dailyPredictionProfit', name: '预测', color: '#eab308' },
  { key: 'dailyGameProfit', name: '游戏', color: '#ef4444' },
] as const;

export function ProfitChart({ data }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<'cumulative' | 'daily'>('cumulative');
  const [dailyRange, setDailyRange] = useState<7 | 14 | 30>(7);

  const filteredData = mode === 'daily' ? data.slice(-dailyRange) : data;

  useEffect(() => {
    if (!chartRef.current || filteredData.length === 0) return;
    const isDark = document.documentElement.classList.contains('dark');
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');

    const config = mode === 'daily' ? DAILY_CONFIG : CUMULATIVE_CONFIG;
    const dates = filteredData.map(d => d.date);
    const textColor = isDark ? '#94A3B8' : '#78716C';

    const series: echarts.SeriesOption[] = config.map(cfg => ({
      name: cfg.name,
      type: 'line',
      data: filteredData.map(d => d[cfg.key as keyof AssetSnapshot] as number ?? 0),
      smooth: true,
      symbol: 'circle',
      symbolSize: filteredData.length <= 7 ? 6 : 0,
      lineStyle: {
        width: cfg.key === 'profit' || cfg.key === 'dailyProfit' ? 2.5 : 1.5,
      },
      itemStyle: { color: cfg.color },
      ...(cfg.key === 'profit' || cfg.key === 'dailyProfit' ? {
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: isDark ? 'rgba(99,91,255,0.25)' : 'rgba(99,91,255,0.15)' },
            { offset: 1, color: 'rgba(99,91,255,0)' },
          ]),
        },
      } : {}),
      emphasis: { focus: 'series' as const },
    }));

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: isDark ? '#1E293B' : '#FFFFFF',
        borderColor: isDark ? '#334155' : '#E7E0D8',
        textStyle: { color: isDark ? '#F8FAFC' : '#1C1917', fontSize: 12 },
        formatter: (params: any) => {
          const date = params[0]?.axisValue ?? '';
          let html = `<div style="font-weight:600;margin-bottom:4px">${date}</div>`;
          for (const p of params) {
            const v = (p.value as number).toFixed(2);
            const sign = p.value >= 0 ? '+' : '';
            html += `<div style="display:flex;align-items:center;gap:6px;margin:2px 0">
              ${p.marker}<span>${p.seriesName}</span>
              <span style="margin-left:auto;font-weight:600;color:${p.value >= 0 ? '#22c55e' : '#ef4444'}">${sign}${v}</span>
            </div>`;
          }
          return html;
        },
      },
      legend: {
        bottom: 0,
        textStyle: { color: textColor, fontSize: 10 },
        itemWidth: 10,
        itemHeight: 2,
        itemGap: 6,
        icon: 'roundRect',
        type: 'scroll',
      },
      grid: { left: 8, right: 8, top: 16, bottom: 40, containLabel: true },
      xAxis: {
        type: 'category',
        data: dates,
        axisLabel: {
          color: textColor,
          fontSize: 9,
          formatter: (v: string) => v.substring(5),
        },
        axisLine: { lineStyle: { color: isDark ? '#334155' : '#E7E0D8' } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: isDark ? '#1E293B' : '#F5F0EB', type: 'dashed' } },
        axisLabel: { color: textColor, fontSize: 9 },
      },
      series,
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [filteredData, mode]);

  if (data.length === 0) {
    return (
      <div className="w-full h-48 sm:h-56 flex items-center justify-center text-sm text-muted-foreground">
        暂无历史数据，每日零点自动快照
      </div>
    );
  }

  return (
    <div className="w-full">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2 mb-2">
        <div className="flex gap-1">
          {mode === 'daily' && ([7, 14, 30] as const).map(d => (
            <button
              key={d}
              onClick={() => setDailyRange(d)}
              className={cn(
                "px-3 py-1.5 rounded-lg text-xs sm:text-[10px] font-medium transition-colors min-w-[44px]",
                dailyRange === d ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground bg-muted/50"
              )}
            >
              {d}天
            </button>
          ))}
        </div>
        <div className="flex gap-1">
          <button
            onClick={() => setMode('cumulative')}
            className={cn(
              "px-3 py-1.5 rounded-lg text-xs sm:text-[10px] font-medium transition-colors min-w-[44px]",
              mode === 'cumulative' ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground bg-muted/50"
            )}
          >
            累计
          </button>
          <button
            onClick={() => setMode('daily')}
            className={cn(
              "px-3 py-1.5 rounded-lg text-xs sm:text-[10px] font-medium transition-colors min-w-[44px]",
              mode === 'daily' ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground bg-muted/50"
            )}
          >
            日收益
          </button>
        </div>
      </div>
      <div ref={chartRef} className="w-full h-56 sm:h-72" />
    </div>
  );
}
