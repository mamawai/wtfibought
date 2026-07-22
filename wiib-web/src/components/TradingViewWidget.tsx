import { useEffect, useRef, memo } from 'react';
import { useIsDark } from '../hooks/useIsDark';

function TradingViewWidget({ symbol = 'BINANCE:BTCUSD', label = 'Bitcoin' }: { symbol?: string; label?: string }) {
  const container = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();

  useEffect(() => {
    const el = container.current;
    if (!el) return;
    const script = document.createElement('script');
    script.src = 'https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js';
    script.type = 'text/javascript';
    script.async = true;
    script.innerHTML = JSON.stringify({
      allow_symbol_change: true,
      calendar: false,
      details: false,
      hide_side_toolbar: true,
      hide_top_toolbar: false,
      hide_legend: false,
      hide_volume: false,
      hotlist: false,
      interval: 'D',
      locale: 'en',
      save_image: true,
      style: '1',
      symbol,
      theme: isDark ? 'dark' : 'light',
      timezone: 'Etc/UTC',
      backgroundColor: isDark ? '#0b0c0f' : '#FFFFFF',
      gridColor: isDark ? 'rgba(242, 242, 242, 0.06)' : 'rgba(0, 0, 0, 0.06)',
      watchlist: [],
      withdateranges: false,
      compareSymbols: [],
      studies: [],
      autosize: true,
    });
    el.appendChild(script);
    return () => {
      el.innerHTML = '';
    };
  }, [symbol, isDark]);

  return (
    <div className="tradingview-widget-container" ref={container} style={{ height: '100%', width: '100%' }}>
      <div className="tradingview-widget-container__widget" style={{ height: 'calc(100% - 32px)', width: '100%' }} />
      <div className="tradingview-widget-copyright">
        <a href={`https://www.tradingview.com/symbols/${symbol.replace('BINANCE:', '')}/?exchange=BINANCE`} rel="noopener noreferrer" target="_blank">
          <span className="blue-text">{label} price</span>
        </a>
        <span className="trademark"> by TradingView</span>
      </div>
    </div>
  );
}

export default memo(TradingViewWidget);
