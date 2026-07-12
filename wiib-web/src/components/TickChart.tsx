import * as echarts from 'echarts';
import { useEffect, useRef, useState, useCallback } from 'react';
import type { DayTick } from '../types';
import { useIsDark } from '../hooks/useIsDark';

interface Props {
  ticks: DayTick[];
  prevClose?: number;
}

// 生成完整交易时间轴（与后端 TOTAL_TICKS=1440 对齐）
// 口径：每个交易日共 240 分钟 × 6 点/分钟 = 1440 点
// - 上午：09:30:00 ~ 11:29:50
// - 下午：13:00:00 ~ 14:59:50
function generateFullTimeAxis(): string[] {
  const times: string[] = [];
  // 上午 9:30 - 11:30
  for (let h = 9; h <= 11; h++) {
    const startM = h === 9 ? 30 : 0;
    const endM = h === 11 ? 29 : 59;
    for (let m = startM; m <= endM; m++) {
      for (let s = 0; s < 60; s += 10) {
        times.push(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`);
      }
    }
  }
  // 下午 13:00 - 14:59
  for (let h = 13; h <= 14; h++) {
    const endM = 59;
    for (let m = 0; m <= endM; m++) {
      for (let s = 0; s < 60; s += 10) {
        times.push(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`);
      }
    }
  }
  return times;
}

const fullTimeAxis = generateFullTimeAxis();

export function TickChart({ ticks, prevClose }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstanceRef = useRef<echarts.ECharts | null>(null);
  const pricesRef = useRef<(number | null)[]>(new Array(fullTimeAxis.length).fill(null));
  const [isMobile, setIsMobile] = useState(window.innerWidth < 640);
  const isDark = useIsDark();

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 640);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // 初始化图表（只执行一次）
  useEffect(() => {
    if (!chartRef.current) return;

    const chart = echarts.init(chartRef.current, isDark ? 'dark' : undefined);
    chartInstanceRef.current = chart;

    const keyTimes = isMobile
      ? ['09:30', '10:30', '11:30', '13:00', '14:00', '15:00']
      : ['09:30', '10:00', '10:30', '11:00', '11:30', '13:00', '13:30', '14:00', '14:30', '15:00'];

    chart.setOption({
      backgroundColor: 'transparent',
      grid: {
        left: isMobile ? 4 : 8,
        right: isMobile ? 4 : 8,
        top: 16,
        bottom: 32,
        containLabel: !isMobile,
      },
      xAxis: {
        type: 'category',
        data: fullTimeAxis,
        axisLine: { lineStyle: { color: '#333' } },
        axisTick: { show: false },
        axisLabel: {
          fontSize: isMobile ? 10 : 12,
          color: '#888',
          hideOverlap: true,
          interval: (index: number) => {
            const time = fullTimeAxis[index];
            if (!time) return false;
            const shortTime = time.substring(0, 5);
            return keyTimes.includes(shortTime) && time.endsWith(':00');
          },
          formatter: (value: string) => value.substring(0, 5),
        },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { show: !isMobile, fontSize: 10, color: '#888', formatter: (v: number) => v.toFixed(2) },
        splitLine: { lineStyle: { color: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.12)', type: 'dashed' } },
      },
      series: [
        {
          name: '价格',
          type: 'line',
          data: pricesRef.current,
          smooth: 0.3,
          showSymbol: false,
          connectNulls: true,
          lineStyle: { width: 1.5, color: '#089981' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(8, 153, 129, 0.15)' },
              { offset: 1, color: 'rgba(8, 153, 129, 0.02)' },
            ]),
          },
        },
      ],
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(20, 20, 20, 0.9)',
        borderColor: '#333',
        textStyle: { color: '#fff', fontSize: 12 },
        formatter: (params: unknown) => {
          const arr = params as { name: string; value: number | null | undefined; seriesName: string }[];
          const p = arr.find(item => item.seriesName === '价格');
          if (!p || p.value == null) return '';
          const val = p.value;
          const base = prevClose ?? val;
          const change = base ? ((val - base) / base * 100).toFixed(2) : '--';
          const changeColor = val >= base ? '#089981' : '#f23645';
          return `
            <div style="padding: 4px 0">
              <div style="color: #888; margin-bottom: 4px">${p.name.substring(0, 5)}</div>
              <div style="font-size: 16px; font-weight: bold">${val.toFixed(2)}</div>
              <div style="color: ${changeColor}; font-size: 12px">${change}%</div>
            </div>
          `;
        },
      },
      dataZoom: [{ type: 'inside', start: 0, end: 100, minValueSpan: 60 }],
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartInstanceRef.current = null;
      pricesRef.current = new Array(fullTimeAxis.length).fill(null);
    };
  }, [isMobile, isDark, prevClose]);

  // 更新数据（增量更新，不重建图表）
  const updateChart = useCallback(() => {
    const chart = chartInstanceRef.current;
    if (!chart) return;

    // 按顺序填充：ticks[0]->槽位0, ticks[1]->槽位1, ...
    ticks.forEach((t, index) => {
      if (index < fullTimeAxis.length) {
        pricesRef.current[index] = parseFloat(String(t.price));
      }
    });

    const validPrices = pricesRef.current.filter((p): p is number => p !== null);
    if (!validPrices.length) return;

    const minPrice = Math.min(...validPrices);
    const maxPrice = Math.max(...validPrices);
    const padding = (maxPrice - minPrice) * 0.1 || 1;

    const basePrice = prevClose ?? validPrices[0];
    const lastPrice = validPrices[validPrices.length - 1];
    const isUp = lastPrice >= basePrice;
    const lineColor = isUp ? '#089981' : '#f23645';
    const areaColorStart = isUp ? 'rgba(8, 153, 129, 0.15)' : 'rgba(242, 54, 69, 0.15)';
    const areaColorEnd = isUp ? 'rgba(8, 153, 129, 0.02)' : 'rgba(242, 54, 69, 0.02)';

    // 增量更新
    chart.setOption({
      yAxis: {
        min: minPrice - padding,
        max: maxPrice + padding,
      },
      series: [
        {
          name: '价格',
          data: [...pricesRef.current],
          lineStyle: { color: lineColor },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: areaColorStart },
              { offset: 1, color: areaColorEnd },
            ]),
          },
        },
        ...(prevClose
          ? [{
              name: '昨收',
              type: 'line',
              data: fullTimeAxis.map(() => prevClose),
              lineStyle: { type: 'dashed', color: isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.18)', width: 1 },
              showSymbol: false,
              silent: true,
            }]
          : []),
      ],
    });
  }, [ticks, prevClose, isDark]);

  useEffect(() => {
    updateChart();
  }, [updateChart]);

  useEffect(() => {
    const c = chartInstanceRef.current;
    if (!c) return;
    const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.12)';
    c.setOption({ yAxis: { splitLine: { lineStyle: { color: gridColor } } } });
  }, [isDark]);

  return <div ref={chartRef} className="w-full" style={{ height: 280 }} />;
}
