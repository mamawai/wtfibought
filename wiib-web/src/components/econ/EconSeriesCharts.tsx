import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import * as echarts from 'echarts';
import { useTranslation } from 'react-i18next';
import { Skeleton } from '../ui/skeleton';
import { quantApi, type EconCalendarEvent } from '../../api';
import { useIsDark } from '../../hooks/useIsDark';
import { chartUi, cssVar, rgba } from '../../lib/chartTheme';
import { parseValue, valueSuffix } from '../../lib/econValue';
import { fmtDate } from '../../lib/utils';

interface Props {
  country: string;
  /** 同一个指标的全部标题（换过名的新旧标题一起）；清单查不到时调用方传 [当前标题] */
  titles: string[];
  /** 展开的这一条：默认选中它 */
  sourceId: string;
  /** 展开的这一条的公布时刻，加载中的小标题用 */
  eventTime: number;
}

/** 两块画布与骨架屏同高（含坐标轴文字） */
const CHART_H = 'h-[180px]';

/** 柱子顺序：前值 · 预测 · 实际 */
const BAR_KEYS = ['previous', 'forecast', 'actual'] as const;

/**
 * 财经日历展开区：左边这个指标历次实际值的折线，右边选中那一次的 前值/预测/实际 三根柱。
 * 点折线切换选中，柱子跟着变。
 */
export function EconSeriesCharts({ country, titles, sourceId, eventTime }: Props) {
  const { t } = useTranslation('calendar');
  // undefined=加载中，null=失败（空列表也算失败）
  const [rows, setRows] = useState<EconCalendarEvent[] | null>();
  const [selId, setSelId] = useState(sourceId);
  // titles 转字符串当 effect 依赖
  const titlesKey = JSON.stringify(titles);

  useEffect(() => {
    let alive = true;
    quantApi.econCalendarSeries(country, JSON.parse(titlesKey))
      .then(v => { if (alive) setRows(v.length ? v : null); })
      .catch(() => { if (alive) setRows(null); });
    return () => { alive = false; };
  }, [country, titlesKey]);

  // 折线只画有实际值的期，useMemo 固定引用
  const pts = useMemo(() => rows?.filter(r => parseValue(r.actual) != null) ?? [], [rows]);

  if (rows === null) return null;

  // sel 为空 = 还在加载
  const sel = rows && (rows.find(r => r.sourceId === selId) ?? rows[rows.length - 1]);
  // 单位取全部值里第一个非空后缀
  const unit = rows?.flatMap(r => [r.actual, r.forecast, r.previous]).map(valueSuffix).find(Boolean) ?? '';

  return (
    <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
      <Block title={t('chart.history')}>
        {!sel ? <Skeleton className={CHART_H} />
          : pts.length ? <HistoryLine pts={pts} sel={sel} unit={unit} onPick={setSelId} />
          : <p className="text-sm text-muted-foreground">{t('chart.noData')}</p>}
      </Block>
      <Block title={t('chart.release', { date: fmtDate(sel?.eventTime ?? eventTime) })}>
        {sel ? <ReleaseBars row={sel} unit={unit} /> : <Skeleton className={CHART_H} />}
      </Block>
    </div>
  );
}

/** 小标题 + 图，min-w-0 允许列宽收缩 */
function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="mb-2 text-[12px] text-muted-foreground">{title}</div>
      {children}
    </div>
  );
}

/** 历次实际值折线，选中那次叠一个主色点 */
function HistoryLine({ pts, sel, unit, onPick }: {
  /** 有实际值的那些期，按时间正序 */
  pts: EconCalendarEvent[];
  sel: EconCalendarEvent;
  unit: string;
  onPick: (sourceId: string) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const isDark = useIsDark();
  const { t } = useTranslation('home');

  // 实例只随 pts 重建；选中、主题、语言走 setOption 合并更新
  useEffect(() => {
    const el = ref.current!;
    const chart = echarts.init(el);
    chartRef.current = chart;
    // 点绘图区任意位置，选时间最近的一期
    chart.getZr().on('click', e => {
      if (!chart.containPixel('grid', [e.offsetX, e.offsetY])) return;
      const x = chart.convertFromPixel({ xAxisIndex: 0 }, e.offsetX);
      let best = pts[0];
      for (const r of pts) if (Math.abs(r.eventTime - x) < Math.abs(best.eventTime - x)) best = r;
      onPick(best.sourceId);
    });
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(el);
    return () => { ro.disconnect(); chart.dispose(); };
  }, [pts, onPick]);

  useEffect(() => {
    const ui = chartUi(isDark);
    const selV = parseValue(sel.actual);
    const enc = echarts.format.encodeHTML;
    chartRef.current!.setOption({
      // grid 只留线头和选中点的边距
      grid: { top: 10, right: 12, bottom: 0, left: 0 },
      tooltip: {
        trigger: 'axis',
        ...ui.tooltip,
        axisPointer: { lineStyle: { color: ui.axisLabel, width: 1, type: 'solid' } },
        // 数值转义后拼进 tooltip HTML
        formatter: (ps: { dataIndex: number }[]) => {
          const r = pts[ps[0].dataIndex];
          const lines = [fmtDate(r.eventTime), `${t('calendar.actual')} <b>${enc(r.actual!)}</b>`];
          if (r.forecast) lines.push(`${t('calendar.forecast')} ${enc(r.forecast)}`);
          return lines.join('<br/>');
        },
      },
      xAxis: {
        type: 'time',
        axisLabel: { fontSize: 10, color: ui.axisLabel },
        axisLine: { lineStyle: { color: ui.gridLine } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { fontSize: 10, color: ui.axisLabel, formatter: (v: number) => `${v}${unit}` },
        splitLine: { lineStyle: { color: ui.gridLine, width: 1, type: 'solid' } },
      },
      series: [
        {
          id: 'history',
          type: 'line',
          data: pts.map(r => [r.eventTime, parseValue(r.actual)]),
          smooth: false,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2, color: ui.fg },
          itemStyle: { color: ui.fg },
        },
        // 选中点：主色填充 + 面色描边环压在线上；还没公布的那次不标
        {
          id: 'selected',
          type: 'scatter',
          data: selV == null ? [] : [[sel.eventTime, selV]],
          symbolSize: 10,
          itemStyle: { color: cssVar('--color-primary', '#f25f0a'), borderColor: ui.card, borderWidth: 2 },
          silent: true,
          tooltip: { show: false },
          z: 3,
        },
      ],
    });
  }, [pts, sel, unit, isDark, t]);

  return <div ref={ref} className={CHART_H} />;
}

/** 选中那一次的 前值/预测/实际 三根柱，数值直接标在柱端 */
function ReleaseBars({ row, unit }: { row: EconCalendarEvent; unit: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const isDark = useIsDark();
  const { t } = useTranslation('home');

  useEffect(() => {
    const el = ref.current!;
    const chart = echarts.init(el);
    chartRef.current = chart;
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(el);
    return () => { ro.disconnect(); chart.dispose(); };
  }, []);

  // 切选中只合并更新，柱子从旧高度过渡到新高度
  useEffect(() => {
    const ui = chartUi(isDark);
    // 实际用墨色，前值、预测用淡灰
    const gray = rgba(cssVar('--color-muted-foreground', '#7a7e88'), 0.5);
    const vals = BAR_KEYS.map(k => parseValue(row[k]));
    const hasNeg = vals.some(v => v != null && v < 0);
    chartRef.current!.setOption({
      // top 给柱顶数值留位置
      grid: { top: 20, right: 8, bottom: 0, left: 0 },
      xAxis: {
        type: 'category',
        data: BAR_KEYS.map(k => t(`calendar.${k}`)),
        // 有负数时类目字下移
        axisLabel: { fontSize: 10, color: ui.axisLabel, margin: hasNeg ? 22 : 8 },
        // 轴线固定在底部
        axisLine: { onZero: false, lineStyle: { color: ui.gridLine } },
        axisTick: { show: false },
      },
      // 没开 scale，值轴自动含 0
      yAxis: {
        type: 'value',
        axisLabel: { fontSize: 10, color: ui.axisLabel, formatter: (v: number) => `${v}${unit}` },
        splitLine: { lineStyle: { color: ui.gridLine, width: 1, type: 'solid' } },
      },
      series: [{
        type: 'bar',
        barMaxWidth: 24,
        silent: true,
        data: BAR_KEYS.map((k, i) => ({
          value: vals[i],
          itemStyle: { color: k === 'actual' ? ui.fg : gray },
          // outside：正数标柱顶、负数标柱底；null 那根不画柱也不标
          label: {
            show: vals[i] != null,
            position: 'outside',
            formatter: () => row[k]!,
            fontSize: 11,
            color: k === 'actual' ? ui.fg : ui.axisLabel,
            fontWeight: k === 'actual' ? 'bold' : 'normal',
          },
        })),
        // 有负数时单独画出 0 轴
        markLine: {
          silent: true,
          symbol: 'none',
          label: { show: false },
          lineStyle: { color: ui.axisLabel, width: 1, type: 'solid' },
          data: hasNeg ? [{ yAxis: 0 }] : [],
        },
      }],
    });
  }, [row, unit, isDark, t]);

  return <div ref={ref} className={CHART_H} />;
}
