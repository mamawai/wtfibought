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

interface Props {
  cryptoPositions?: CryptoRow[];
  futuresRows?: FuturesRow[];
  balance: number;
  pendingSettlement?: number;
}

export function PortfolioChart({ cryptoPositions = [], futuresRows = [], balance, pendingSettlement = 0 }: Props) {
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
      { name: '现金余额', value: balance, itemStyle: { color: '#22c55e' } },
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
           return `${params.marker}${params.name}<br/>
                   <span style="font-weight:bold; font-size:1.1em">${params.value.toFixed(2)}</span> (${params.percent}%)`;
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
  }, [cryptoPositions, futuresRows, balance, pendingSettlement, isDark]);

  return <div ref={chartRef} className="w-full h-56 sm:h-64 transition-colors duration-300" />;
}
