import { fmtDateTime } from '../../lib/utils';
import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { useIsDark } from '../../hooks/useIsDark';
import { chartUi, rgba } from '../../lib/chartTheme';
import type { QuantSnapshotSeriesPoint, QuantDeepAnalysisView } from '../../types';

interface Props {
  points: QuantSnapshotSeriesPoint[];
  analyses: QuantDeepAnalysisView[];
  onSelectAnalysis?: (a: QuantDeepAnalysisView) => void;
}

const TRIGGER_CN: Record<string, string> = { schedule: '定频', sentinel: '哨兵插队', chat: '对话触发', manual: '手动' };

// 系列色经 CVD 校验（validate_palette）：粉色研判点与蓝色散点在红绿色盲下也可分
const C_FORECAST = '#F97316';
const C_REALIZED = '#3b82f6';
const C_ANALYSIS = '#ec4899';
const C_FRAGILITY = '#f59e0b';

/**
 * 快照时间线：H6 预测 vol 曲线 vs 已验证 realized 散点 + 脆弱度副图 + 深研判点标记。
 * 研判点画成可点击 scatter（贴在预测线高度），点击联动下方研判卡。
 * 轴/网格/tooltip 走 chartTheme，与拟物底色同源。
 */
export function VolTimeline({ points, analyses, onSelectAnalysis }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();

  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);
    const ui = chartUi(isDark);

    const forecast = points.map(p => [p.closeTime, p.h6SigmaBps] as [number, number | null]);
    const realized = points.filter(p => p.realizedAbsBps != null)
      .map(p => [p.closeTime, p.realizedAbsBps] as [number, number]);
    const fragility = points.map(p => [p.closeTime, p.fragilityScore] as [number, number | null]);

    // 研判点落在时间轴上；y 取该时刻预测值让点贴线（找不到就取 0）
    const sigmaAt = (t: number) => {
      let best: QuantSnapshotSeriesPoint | undefined;
      for (const p of points) {
        if (p.closeTime <= t) best = p; else break;
      }
      return best?.h6SigmaBps ?? 0;
    };
    const analysisDots = analyses.map(a => ({
      value: [a.closeTime, sigmaAt(a.closeTime)] as [number, number],
      analysis: a,
    }));

    chart.setOption({
      // 上下双 grid：vol 主图 + 脆弱度副图，时间轴联动
      grid: [
        { top: 30, right: 12, left: 48, height: '48%' },
        { top: '74%', right: 12, left: 48, height: '18%' },
      ],
      legend: {
        top: 2,
        left: 44,
        itemWidth: 10,
        itemHeight: 8,
        itemGap: 12,
        icon: 'roundRect',
        textStyle: { fontSize: 10, color: ui.axisLabel },
        data: ['H6 预测', '实际波幅', '深研判', '脆弱度'],
      },
      axisPointer: { link: [{ xAxisIndex: 'all' }], lineStyle: { color: ui.gridLine } },
      tooltip: {
        trigger: 'axis',
        confine: true,
        ...ui.tooltip,
        formatter: (params: unknown) => {
          const list = params as { seriesName: string; value: [number, number | null]; marker: string }[];
          if (!list.length) return '';
          const time = fmtDateTime(list[0].value[0]);
          const rows = list
            .filter(p => p.value[1] != null)
            .map(p => `${p.marker}${p.seriesName}: ${typeof p.value[1] === 'number' ? p.value[1]!.toFixed(0) : p.value[1]}${p.seriesName === '脆弱度' ? '' : ' bps'}`);
          return [time, ...rows].join('<br/>');
        },
      },
      xAxis: [
        { type: 'time', gridIndex: 0, axisLabel: { show: false }, axisLine: { lineStyle: { color: ui.gridLine } }, axisTick: { show: false } },
        { type: 'time', gridIndex: 1, axisLabel: { fontSize: 10, color: ui.axisLabel }, axisLine: { lineStyle: { color: ui.gridLine } }, axisTick: { show: false } },
      ],
      yAxis: [
        {
          type: 'value', gridIndex: 0, name: 'H6 vol (bps)', nameTextStyle: { fontSize: 9, color: ui.axisLabel },
          axisLabel: { fontSize: 10, color: ui.axisLabel }, splitLine: { lineStyle: { color: ui.gridLine, opacity: 0.6 } },
        },
        {
          type: 'value', gridIndex: 1, max: 100, min: 0,
          axisLabel: { fontSize: 9, color: ui.axisLabel }, splitLine: { show: false },
        },
      ],
      series: [
        {
          name: 'H6 预测', type: 'line', xAxisIndex: 0, yAxisIndex: 0,
          data: forecast, symbol: 'none', smooth: true, connectNulls: true,
          lineStyle: { width: 2, color: C_FORECAST, shadowColor: rgba(C_FORECAST, 0.3), shadowBlur: 5, shadowOffsetY: 3 },
        },
        {
          name: '实际波幅', type: 'scatter', xAxisIndex: 0, yAxisIndex: 0,
          data: realized, symbolSize: 5,
          // 面色描边（surface ring）：散点落在预测线上也不糊
          itemStyle: { color: C_REALIZED, opacity: 0.85, borderColor: ui.card, borderWidth: 1 },
        },
        {
          name: '深研判', type: 'scatter', xAxisIndex: 0, yAxisIndex: 0,
          data: analysisDots, symbol: 'pin', symbolSize: 26,
          itemStyle: { color: C_ANALYSIS, shadowColor: rgba(C_ANALYSIS, 0.4), shadowBlur: 6 },
          tooltip: {
            formatter: (p: unknown) => {
              const a = (p as { data: { analysis: QuantDeepAnalysisView } }).data.analysis;
              return `${new Date(a.closeTime).toLocaleString('zh-CN')}<br/>深研判 · ${TRIGGER_CN[a.triggerSource] || a.triggerSource}<br/><span style="opacity:.7">点击查看详情</span>`;
            },
          },
          z: 10,
        },
        {
          name: '脆弱度', type: 'line', xAxisIndex: 1, yAxisIndex: 1,
          data: fragility, symbol: 'none', smooth: true, connectNulls: true,
          lineStyle: { width: 1.5, color: C_FRAGILITY },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: rgba(C_FRAGILITY, 0.22) },
              { offset: 1, color: rgba(C_FRAGILITY, 0.02) },
            ]),
          },
        },
      ],
    });

    chart.on('click', (params) => {
      const data = params.data as { analysis?: QuantDeepAnalysisView } | undefined;
      if (params.seriesName === '深研判' && data?.analysis && onSelectAnalysis) {
        onSelectAnalysis(data.analysis);
      }
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => { chart.dispose(); window.removeEventListener('resize', onResize); };
  }, [points, analyses, isDark, onSelectAnalysis]);

  return <div ref={ref} style={{ width: '100%', height: 300 }} />;
}
