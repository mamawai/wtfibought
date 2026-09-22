import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import * as echarts from 'echarts';
import { Clock, Loader2, ArrowUpRight, ArrowDownRight } from 'lucide-react';
import { cn, fmtNum, fmtTime } from '../lib/utils';
import { cssVar } from '../lib/chartTheme';
import { useIsDark } from '../hooks/useIsDark';
import { HelpTip } from './HelpTip';
import { Card, CardContent } from './ui/card';
import { Badge } from './ui/badge';
import { WINDOW_SECONDS, fmtWindow, type PredictionMarket } from '../hooks/usePredictionMarket';

function RollingChar({ char, direction }: { char: string; direction: 'up' | 'down' | 'none' }) {
  const prevRef = useRef(char);
  const [prev, setPrev] = useState(char);
  const [animating, setAnimating] = useState(false);

  useEffect(() => {
    if (char === prevRef.current) return;
    setPrev(prevRef.current);
    prevRef.current = char;
    setAnimating(true);
    const t = setTimeout(() => setAnimating(false), 300);
    return () => clearTimeout(t);
  }, [char]);

  const isDigit = /\d/.test(char);
  const w = isDigit ? '0.62em' : undefined;
  const goUp = direction === 'up';
  const showAnim = isDigit && direction !== 'none' && animating;

  return (
    <span className="inline-block relative overflow-hidden align-bottom" style={{ height: '1.2em', width: w, lineHeight: '1.2em' }}>
      {showAnim ? (
        <>
          <span style={{
            position: 'absolute', top: 0, left: 0, width: '100%', textAlign: 'center',
            animation: `${goUp ? 'roll-up-out' : 'roll-down-out'} 0.3s ease-in-out forwards`
          }}>{prev}</span>
          <span style={{
            position: 'absolute', top: 0, left: 0, width: '100%', textAlign: 'center',
            animation: `${goUp ? 'roll-down-out' : 'roll-up-out'} 0.3s ease-in-out reverse forwards`
          }}>{char}</span>
        </>
      ) : (
        <span style={{ display: 'block', textAlign: 'center' }}>{char}</span>
      )}
    </span>
  );
}

function diffRollingChars(oldValue: string, newValue: string) {
  const oldChars = oldValue.split('');
  const newChars = newValue.split('');
  const maxLen = Math.max(oldChars.length, newChars.length);
  const padOld = oldChars.length < maxLen ? Array(maxLen - oldChars.length).fill('').concat(oldChars) : oldChars;
  const padNew = newChars.length < maxLen ? Array(maxLen - newChars.length).fill('').concat(newChars) : newChars;
  return padNew.map((c, i) => {
    const o = padOld[i];
    if (c === o) return { char: c, dir: 'none' as const };
    const cn = parseInt(c), on = parseInt(o);
    if (isNaN(cn) || isNaN(on)) return { char: c, dir: 'none' as const };
    return { char: c, dir: cn > on ? 'up' as const : 'down' as const };
  });
}

export function RollingNumber({ value, className }: { value: string; className?: string }) {
  // 上一渲染值放 state（render 期读 ref 违反 react-hooks/refs）：过渡方向在变更瞬间算好并保留
  const [state, setState] = useState(() => ({ value, chars: diffRollingChars(value, value) }));
  if (state.value !== value) {
    setState({ value, chars: diffRollingChars(state.value, value) });
  }

  return (
    <span className={className}>
      {state.chars.map((c, i) => <RollingChar key={i} char={c.char} direction={c.dir} />)}
    </span>
  );
}

function fmtCountdown(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

/**
 * 5 分钟盘的头卡：标题 + 倒计时 + 进度条 + 现价/目标价 + 最近一分钟的走势图，价格都是 Chainlink 60 秒 TWAP。
 * 预测页和 Jev 页共用；extra 放在顶栏右侧倒计时前面（比如跳到另一页的链接）。
 */
export function PredictionHero({ market, extra }: { market: PredictionMarket; extra?: ReactNode }) {
  const { t, i18n } = useTranslation(['community']);
  const isDark = useIsDark();
  const { round, windowStart, countdown, priceHistory, btcPrice } = market;
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInst = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!chartRef.current) return;
    if (!chartInst.current) {
      chartInst.current = echarts.init(chartRef.current, isDark ? 'dark' : undefined);
    }
    const chart = chartInst.current;
    const startPrice = round?.startPrice ? parseFloat(round.startPrice) : null;
    const now = Date.now();
    const windowMs = 60_000;
    const visibleData = priceHistory.filter(p => p.time >= now - windowMs);
    const data = visibleData.map(p => [p.time, p.price]);
    const lastPoint = data.length > 0 ? data[data.length - 1] : null;
    const lastPrice = lastPoint ? (lastPoint[1] as number) : null;
    const isUp = startPrice != null && lastPrice != null && lastPrice >= startPrice;
    // 涨跌色跟全站 token 走，亮暗各一套；后面拼 alpha 要求是 6 位 hex
    const lineColor = isUp ? cssVar('--color-gain', '#0b8a5c') : cssVar('--color-loss', '#d63b2f');

    chart.setOption({
      backgroundColor: 'transparent',
      grid: { left: 12, right: 56, top: 16, bottom: 28 },
      xAxis: {
        type: 'time', min: now - windowMs, max: now,
        // 手机上一分钟的刻度挤不下，重叠的自动藏掉
        axisLabel: { fontSize: 10, color: '#888', hideOverlap: true }, axisLine: { show: false }, axisTick: { show: false },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value', scale: true, position: 'right',
        axisLabel: { fontSize: 10, color: '#888' }, axisLine: { show: false }, axisTick: { show: false },
        splitLine: { lineStyle: { opacity: 0.08 } },
      },
      series: [
        {
          type: 'line', data, smooth: 0.3, symbol: 'none',
          lineStyle: { width: 2.5, color: lineColor },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: lineColor + '30' }, { offset: 1, color: lineColor + '02' }
          ]) },
          markLine: startPrice != null ? {
            silent: true, symbol: 'none',
            data: [{ yAxis: startPrice, lineStyle: { color: '#666', type: 'dashed', width: 1 }, label: { formatter: t('prediction.chartTarget', { price: fmtNum(startPrice) }), fontSize: 10, position: 'insideStartTop', color: '#888' } }]
          } : undefined,
        },
        {
          type: 'effectScatter',
          data: lastPoint ? [lastPoint] : [],
          symbolSize: 7,
          rippleEffect: { brushType: 'fill', scale: 3.5, period: 2.5 },
          itemStyle: { color: lineColor, shadowBlur: 8, shadowColor: lineColor + '80' },
          z: 10,
        },
      ],
      tooltip: { trigger: 'axis', formatter: (p: unknown) => {
        const arr = p as { data: [number, number] }[];
        if (!arr?.[0]) return '';
        return `${fmtTime(arr[0].data[0], true)}<br/><b>$${fmtNum(arr[0].data[1])}</b>`;
      }},
    }, false);
    // 依赖带 i18n.language：切语言后 option 要重算，否则目标价那条线的标签还留着上一门语言
  }, [priceHistory, round?.startPrice, isDark, t, i18n.language]);

  useEffect(() => () => {
    chartInst.current?.dispose();
    chartInst.current = null;
  }, []);

  useEffect(() => {
    const handleResize = () => chartInst.current?.resize();
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const startPrice = round?.startPrice ? parseFloat(round.startPrice) : null;
  // 推送的第一跳还没到时先用价格历史最后一个点顶上，不然开页那几秒是个红色的 $-
  const price = btcPrice ?? priceHistory[priceHistory.length - 1]?.price ?? null;
  const diff = price != null && startPrice != null ? price - startPrice : null;
  const isUp = diff != null && diff >= 0;
  // 方向没定（价或目标价还没到）就不上色
  const tone = diff == null ? '' : isUp ? 'up' : 'dn';

  const pctElapsed = ((WINDOW_SECONDS - countdown) / WINDOW_SECONDS) * 100;
  const urgency = countdown <= 10 ? 'from-red-500 to-red-400' : countdown <= 30 ? 'from-amber-500 to-amber-400' : 'from-emerald-500 to-emerald-400';
  const countdownColor = countdown <= 10 ? 'dn' : countdown <= 30 ? 'wn' : '';

  return (
    <Card className="overflow-hidden">
      <CardContent className="p-0">
        {/* 顶栏 */}
        <div className="flex items-center justify-between px-5 pt-4 pb-3">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-amber-500/10 flex items-center justify-center">
              <span className="text-lg font-black text-amber-500">B</span>
            </div>
            <div>
              <h1 className="text-base font-bold leading-tight">{t('prediction.title')}</h1>
              <span className="text-[11px] text-muted-foreground font-mono">
                5min &middot; {windowStart != null ? fmtWindow(windowStart) : '--'}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-3">
            {extra}
            {round?.status === 'LOCKED' && <Badge variant="outline" className="text-amber-500 border-amber-500/50 text-[10px] px-1.5 py-0">{t('prediction.locked')}</Badge>}
            <div className="flex items-center gap-1.5">
              <Clock className={cn('w-3.5 h-3.5 text-muted-foreground', countdownColor)} />
              <RollingNumber value={fmtCountdown(countdown)} className={cn('font-mono text-xl font-black tabular-nums', countdownColor)} />
            </div>
          </div>
        </div>

        {/* 进度条 */}
        <div className="px-5 pb-3">
          <div className="w-full h-1 rounded-full bg-muted overflow-hidden">
            <div className={`h-full rounded-full transition-all duration-1000 ease-linear bg-gradient-to-r ${urgency}`}
                 style={{ width: `${pctElapsed}%` }} />
          </div>
        </div>

        {/* 价格行：手机上放不下就把目标价挤到下一行，价格本身不断行 */}
        <div className="flex flex-wrap items-end justify-between gap-2 px-5 pb-2">
          <div>
            <RollingNumber value={`$${fmtNum(price)}`} className={cn('text-2xl font-black tabular-nums whitespace-nowrap', tone)} />
            {diff != null && (
              <div className={cn('flex items-center gap-1 mt-0.5 text-xs font-semibold', tone)}>
                {isUp ? <ArrowUpRight className="w-3.5 h-3.5" /> : <ArrowDownRight className="w-3.5 h-3.5" />}
                {isUp ? '+' : ''}{diff.toFixed(2)}
              </div>
            )}
          </div>
          <span className="px-2.5 py-1 rounded bg-muted text-xs font-bold font-mono tabular-nums text-muted-foreground flex items-center gap-1 whitespace-nowrap">
            {startPrice != null
              ? <><HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.targetTip')} />{t('prediction.targetPrice', { price: fmtNum(startPrice) })}</>
              : <><Loader2 className="w-3 h-3 animate-spin" />{t('prediction.targetLoading')}</>}
          </span>
        </div>

        {/* 图表 */}
        <div className="px-3 pb-3">
          <div ref={chartRef} style={{ height: 200, width: '100%' }} />
        </div>
      </CardContent>
    </Card>
  );
}
