import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { getCoin } from '../lib/coinConfig';
import { useIsDark } from '../hooks/useIsDark';

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

// bStock 无 coinConfig 配色，用独立蓝青系列循环取色，与币种暖色区分
const BSTOCK_COLORS = ['#635bff', '#0ea5e9', '#14b8a6', '#6366f1', '#06b6d4', '#3b82f6'];

interface Props {
  cryptoPositions?: CryptoRow[];
  bstockRows?: BStockRow[];
  futuresRows?: FuturesRow[];
  balance: number;
  gameBalance?: number;
  pendingSettlement?: number;
}

export function PortfolioChart({ cryptoPositions = [], bstockRows = [], futuresRows = [], balance, gameBalance = 0, pendingSettlement = 0 }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');

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
          itemStyle: { color: BSTOCK_COLORS[i % BSTOCK_COLORS.length] },
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
      { name: '余额钱包', value: balance, itemStyle: { color: '#22c55e' } },
      ...(gameBalance > 0 ? [{ name: '游戏钱包', value: gameBalance, itemStyle: { color: '#d946ef' } }] : []),
      ...(pendingSettlement > 0 ? [{ name: '待结算', value: pendingSettlement, itemStyle: { color: '#a855f7' } }] : [])
    ];

    const textColor = isDark ? '#94A3B8' : '#78716C'; // Tailwind slate-400 / stone-500
    const borderColor = isDark ? '#1E293B' : '#FFFFFF'; // Card background

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        backgroundColor: isDark ? '#1E293B' : '#FFFFFF',
        borderColor: isDark ? '#334155' : '#E7E0D8',
        textStyle: { color: isDark ? '#F8FAFC' : '#1C1917' },
        formatter: (params: { marker: string; name: string; value: number; percent: number }) => {
           // 游戏钱包计入总资产但不能直接下单交易，tooltip 里说清楚免得误解
           const note = params.name === '游戏钱包'
             ? '<br/><span style="font-size:0.8em;opacity:0.7">不可直接交易，需划转至余额钱包</span>' : '';
           return `${params.marker}${params.name}<br/>
                   <span style="font-weight:bold; font-size:1.1em">${params.value.toFixed(2)}</span> (${params.percent}%)${note}`;
        }
      },
      legend: {
        bottom: '0%',
        left: 'center',
        textStyle: { color: textColor, fontSize: 11, fontFamily: 'Plus Jakarta Sans, sans-serif' },
        itemWidth: 10,
        itemHeight: 10,
        itemGap: 12,
        icon: 'circle'
      },
      series: [
        {
          name: '资产分布',
          type: 'pie',
          radius: ['45%', '70%'],
          center: ['50%', '42%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderRadius: 6,
            borderColor: borderColor,
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
              color: isDark ? '#F8FAFC' : '#1C1917',
              fontFamily: 'Plus Jakarta Sans, sans-serif'
            },
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
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
  }, [cryptoPositions, bstockRows, futuresRows, balance, gameBalance, pendingSettlement, isDark]);

  return <div ref={chartRef} className="w-full h-56 sm:h-64 transition-colors duration-300" />;
}
