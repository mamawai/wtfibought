import { fmtDateTime } from '../../lib/utils';
import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { useIsDark } from '../../hooks/useIsDark';
import type { QuantSnapshotSeriesPoint, QuantDeepAnalysisView } from '../../types';

interface Props {
  points: QuantSnapshotSeriesPoint[];
  analyses: QuantDeepAnalysisView[];
  onSelectAnalysis?: (a: QuantDeepAnalysisView) => void;
}

const TRIGGER_CN: Record<string, string> = { schedule: '定频', sentinel: '哨兵插队', chat: '对话触发', manual: '手动' };

/**
 * 快照时间线：H6 预测 vol 曲线 vs 已验证 realized 散点 + 脆弱度副图 + 深研判点标记。
 * 研判点画成可点击 scatter（挂在预测线高度上方），点击联动下方研判卡。
 */
export function VolTimeline({ points, analyses, onSelectAnalysis }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();

  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);

    const axisColor = isDark ? '#6b7280' : '#94a3b8';
    const splitColor = isDark ? '#3a3e47' : '#f1f5f9';
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
        { top: 26, right: 12, left: 48, height: '52%' },
        { top: '74%', right: 12, left: 48, height: '18%' },
      ],
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      tooltip: {
        trigger: 'axis',
        confine: true,
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
        { type: 'time', gridIndex: 0, axisLabel: { show: false }, axisLine: { lineStyle: { color: splitColor } } },
        { type: 'time', gridIndex: 1, axisLabel: { fontSize: 10, color: axisColor }, axisLine: { lineStyle: { color: splitColor } } },
      ],
      yAxis: [
        {
          type: 'value', gridIndex: 0, name: 'H6 vol (bps)', nameTextStyle: { fontSize: 9, color: axisColor },
          axisLabel: { fontSize: 10, color: axisColor }, splitLine: { lineStyle: { color: splitColor } },
        },
        {
          type: 'value', gridIndex: 1, max: 100, min: 0,
          axisLabel: { fontSize: 9, color: axisColor }, splitLine: { show: false },
        },
      ],
      series: [
        {
          name: 'H6 预测', type: 'line', xAxisIndex: 0, yAxisIndex: 0,
          data: forecast, symbol: 'none', smooth: true, connectNulls: true,
          lineStyle: { width: 2, color: '#F97316' },
        },
        {
          name: '实际波幅', type: 'scatter', xAxisIndex: 0, yAxisIndex: 0,
          data: realized, symbolSize: 4,
          itemStyle: { color: isDark ? '#60a5fa' : '#3b82f6', opacity: 0.75 },
        },
        {
          name: '深研判', type: 'scatter', xAxisIndex: 0, yAxisIndex: 0,
          data: analysisDots, symbol: 'pin', symbolSize: 26,
          itemStyle: { color: '#a855f7' },
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
          lineStyle: { width: 1.5, color: '#f59e0b' },
          areaStyle: { color: 'rgba(245,158,11,0.12)' },
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
