import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { getCoin } from '../lib/coinConfig';
import { useIsDark } from '../hooks/useIsDark';
import { chartUi, cssVar, rgba } from '../lib/chartTheme';
import { fmtNum } from '../lib/utils';

interface CryptoRow {
  symbol: string;
  marketValue: number;
}

interface FuturesRow {
  symbol: string;
  marketValue: number;
}

interface BStockRow {
  ticker: string;
  marketValue: number;
}

// bStock 无 coinConfig 配色，用墨色深浅循环取色，与币种的暖色分开
const BSTOCK_ALPHA = [0.9, 0.72, 0.56, 0.42, 0.3, 0.2];

interface Props {
  cryptoPositions?: CryptoRow[];
  bstockRows?: BStockRow[];
  futuresRows?: FuturesRow[];
  balance: number;
  gameBalance?: number;
}

export function PortfolioChart({ cryptoPositions = [], bstockRows = [], futuresRows = [], balance, gameBalance = 0 }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();
  const { t, i18n } = useTranslation('portfolio');

  // 饼图画不了负数：负的（全仓浮亏）不进扇区，红字列在图下方
  const losses = futuresRows.filter(f => f.marketValue < 0);
  const lossTotal = losses.reduce((s, f) => s + f.marketValue, 0);

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');
    const ui = chartUi(isDark);
    const ink = cssVar('--color-foreground', isDark ? '#f1f1ec' : '#121316');
    const gain = cssVar('--color-gain', isDark ? '#3ecf8e' : '#0b8a5c');
    const primary = cssVar('--color-primary', isDark ? '#f97316' : '#f25f0a');

    // 扇区名同时是 tooltip 里认钱包的判据，先取出来，别在 formatter 里再查一次
    const balanceWalletName = t('ov.balanceWallet');
    const gameWalletName = t('ov.gameWallet');

    const data = [
      ...cryptoPositions
        .filter(c => c.marketValue > 0)
        .map(c => {
          const coin = getCoin(c.symbol);
          return {
            name: coin.name,
            value: c.marketValue,
            itemStyle: { color: coin.chartColor },
          };
        }),
      ...bstockRows
        .filter(b => b.marketValue > 0)
        .map((b, i) => ({
          name: b.ticker,
          value: b.marketValue,
          itemStyle: { color: rgba(ink, BSTOCK_ALPHA[i % BSTOCK_ALPHA.length]) },
        })),
      ...futuresRows
        .filter(f => f.marketValue > 0)
        .map(f => {
          const coin = getCoin(f.symbol);
          return {
            name: `${coin.name.toLowerCase()} future`,
            value: f.marketValue,
            itemStyle: { color: coin.chartColor },
          };
        }),
      { name: balanceWalletName, value: balance, itemStyle: { color: gain } },
      ...(gameBalance > 0 ? [{ name: gameWalletName, value: gameBalance, itemStyle: { color: primary } }] : [])
    ];

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        ...ui.tooltip,
        formatter: (params: { marker: string; name: string; value: number; percent: number }) => {
           // 游戏钱包不能直接下单；余额钱包这块还没扣合约浮亏。tooltip 里说清楚免得误解
           const note = params.name === gameWalletName ? t('chart.gameWalletNote')
             : params.name === balanceWalletName && lossTotal < 0 ? t('chart.futuresLossNote', { value: fmtNum(lossTotal) })
             : '';
           return `${params.marker}${params.name}<br/>
                   <span style="font-weight:bold; font-size:1.1em">${params.value.toFixed(2)}</span> (${params.percent}%)${note && `<br/><span style="font-size:0.8em;opacity:0.7">${note}</span>`}`;
        }
      },
      legend: {
        bottom: '0%',
        left: 'center',
        textStyle: { color: ui.axisLabel, fontSize: 11, fontFamily: cssVar('--font-sans', 'sans-serif') },
        itemWidth: 10,
        itemHeight: 10,
        itemGap: 12,
        icon: 'rect'
      },
      series: [
        {
          name: t('chart.assetAllocation'),
          type: 'pie',
          radius: ['45%', '70%'],
          center: ['50%', '42%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderColor: ui.card,
            borderWidth: 2
          },
          label: {
            show: false,
            position: 'center'
          },
          emphasis: {
            label: {
              show: true,
              fontSize: 14,
              fontWeight: 'bold',
              color: ui.fg,
              fontFamily: cssVar('--font-sans', 'sans-serif')
            }
          },
          labelLine: {
            show: false
          },
          data: data
        }
      ]
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
    // 依赖里必须带 i18n.language：少了它切语言后 option 不重算，图上还是旧文案
  }, [cryptoPositions, bstockRows, futuresRows, balance, gameBalance, lossTotal, isDark, t, i18n.language]);

  return (
    <div className="w-full h-full flex flex-col">
      <div ref={chartRef} className="flex-1 min-h-0" />
      {losses.length > 0 && (
        <div className="num dn mt-1 text-center text-[12.5px]">
          {t('chart.futuresLoss')} {losses.map(f => `${getCoin(f.symbol).name} ${fmtNum(f.marketValue)}`).join(' · ')}
        </div>
      )}
    </div>
  );
}
