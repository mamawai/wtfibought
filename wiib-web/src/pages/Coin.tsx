import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { TrendingUp, TrendingDown, ChevronRight } from 'lucide-react';
import { cryptoApi, cryptoOrderApi, futuresApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Skeleton } from '../components/ui/skeleton';
import { CandleChart } from '../components/CandleChart';
import TradingViewWidget from '../components/TradingViewWidget';
import { SpotTradePanel } from '../components/coin/SpotTradePanel';
import { FuturesOpenPanel } from '../components/coin/FuturesOpenPanel';
import { FuturesPositionsCard } from '../components/coin/FuturesPositionsCard';
import { CoinOrdersCard } from '../components/coin/CoinOrdersCard';
import { fmtNum } from '../lib/utils';
import { COIN_MAP, getCoin, DEFAULT_SYMBOL, formatCoinPrice } from '../lib/coinConfig';
import type { CryptoPosition, FuturesBracket } from '../types';

/** 图表周期：现货/合约统一 K 线（各拉 500 根：5m≈41h / 15m≈5天 / 1h≈20天）；TABS 之后一位 = TradingView 高级图 */
const TABS = [
  { label: '5m', interval: '5m' as const, limit: 500 },
  { label: '15m', interval: '15m' as const, limit: 500 },
  { label: '1h', interval: '1h' as const, limit: 500 },
];
const TV_TAB = TABS.length;

export function CoinRoute() {
  const { symbol } = useParams<{ symbol: string }>();
  const s = symbol && COIN_MAP[symbol] ? symbol : DEFAULT_SYMBOL;
  return <Coin key={s} symbol={s} />;
}

export function Coin({ symbol = DEFAULT_SYMBOL }: { symbol?: string }) {
  const cfg = getCoin(symbol);
  const Icon = cfg.icon;
  const fmtPrice = useCallback((n?: number | null) => formatCoinPrice(symbol, n), [symbol]);
  const navigate = useNavigate();
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);

  // 现货/合约模式（纯合约标的只有合约）
  const [mode, setMode] = useState<'spot' | 'futures'>('futures');
  const isFuturesMode = mode === 'futures';
  const tick = useCryptoStream(symbol, mode);

  // 合约档位（开仓面板算强平价 + 持仓卡显示 MMR）
  const [futuresBracketsMap, setFuturesBracketsMap] = useState<Record<string, FuturesBracket[]>>({});
  useEffect(() => {
    futuresApi.brackets()
      .then(setFuturesBracketsMap)
      .catch(() => toast('合约档位数据加载失败，请刷新页面重试', 'error'));
  }, [toast]);

  // 实物换算币种: USD/CNY 汇率
  const [usdCny, setUsdCny] = useState(0);
  useEffect(() => {
    if (!cfg.unitLabel) return;
    fetch('https://open.er-api.com/v6/latest/USD')
      .then(r => r.json())
      .then(d => { if (d.result === 'success') setUsdCny(d.rates.CNY); })
      .catch(() => {});
  }, [cfg.unitLabel]);

  // 现货持仓
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  const fetchPosition = useCallback(() => {
    cryptoOrderApi.position(symbol).then(setPosition).catch(() => setPosition(null));
  }, [symbol]);
  useEffect(() => { fetchPosition(); }, [fetchPosition, mode]);

  // 跨卡刷新：成交后 bump 对应 key，订单表/仓位卡据此重拉
  const [spotOrdersKey, setSpotOrdersKey] = useState(0);
  const [futuresOrdersKey, setFuturesOrdersKey] = useState(0);
  const [futuresPositionsKey, setFuturesPositionsKey] = useState(0);
  // 仓位卡有变动（平仓/调杠杆/保证金）时递增，驱动开仓面板的持仓快照重拉（模式锁定/杠杆跟随要保持新鲜）
  const [futuresPanelKey, setFuturesPanelKey] = useState(0);

  const handleSpotTraded = () => {
    fetchPosition();
    fetchUser();
    setSpotOrdersKey(k => k + 1);
  };
  const handleFuturesOpened = () => {
    fetchUser();
    setFuturesPositionsKey(k => k + 1);
    setFuturesOrdersKey(k => k + 1);
  };

  const [activeTab, setActiveTab] = useState(0);

  // 实时价：合约用标记/成交价，现货用现货价；流未到前用 REST 最新收盘兜底（面板可用不至于全 0）
  const livePrice = isFuturesMode ? (tick?.fp ?? tick?.price) : tick?.price;
  const [restPrice, setRestPrice] = useState(0);
  useEffect(() => {
    let cancelled = false;
    const fn = isFuturesMode ? futuresApi.klines : cryptoApi.klines;
    fn(symbol, '5m', 1)
      .then(rows => { if (!cancelled && rows?.length) setRestPrice(Number(rows[rows.length - 1][4])); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol, isFuturesMode]);
  const currentPrice = livePrice ?? restPrice;

  // 24h 涨跌基准：1h×25 首根收盘（与首页行情卡同口径）
  const [base24h, setBase24h] = useState(0);
  useEffect(() => {
    let cancelled = false;
    const fn = isFuturesMode ? futuresApi.klines : cryptoApi.klines;
    fn(symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows?.length) setBase24h(Number(rows[0][4])); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol, isFuturesMode]);
  const change = base24h > 0 && currentPrice > 0 ? currentPrice - base24h : 0;
  const changePct = base24h > 0 ? (change / base24h) * 100 : 0;
  const isUp = change >= 0;

  // K线实时驱动分派：crypto 合约 5m/15m/1h 后端都有K线广播（含量/额）；
  // 大宗商品合约只有 5m 广播，15m/1h 退化为价格 tick 驱动；现货全部由价格 tick 驱动最后一根
  const chartInterval = activeTab < TABS.length ? TABS[activeTab].interval : null;
  const klineLive = isFuturesMode && (!cfg.futuresOnly || chartInterval === '5m');
  const chartTick = useMemo(() => {
    if (tick?.ts == null) return null;
    const p = isFuturesMode ? (tick.fp ?? tick.price) : tick.price;
    return p != null ? { price: p, ts: tick.ts } : null;
  }, [tick, isFuturesMode]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-5">
      {/* 页头：去卡片化的终端报价行 */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <Icon className={`w-8 h-8 sm:w-9 sm:h-9 shrink-0 ${cfg.colorClass}`} />
          <div className="space-y-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-lg sm:text-xl font-extrabold tracking-tight">{cfg.pair}</span>
              <div className="flex items-center gap-2 px-2.5 py-0.5 rounded-full border border-border bg-card text-[10px]">
                <span className="flex items-center gap-1.5" title="现货行情">
                  <span className={`inline-block w-1.5 h-1.5 rounded-full ${isFuturesMode ? 'bg-muted-foreground/35' : (tick?.ws ? 'led' : 'bg-destructive animate-pulse')}`} />
                  <span className={`font-semibold ${isFuturesMode ? 'text-muted-foreground' : 'text-foreground'}`}>现货</span>
                </span>
                <span className="w-px h-2.5 bg-border" />
                <span className="flex items-center gap-1.5" title="合约行情">
                  <span className={`inline-block w-1.5 h-1.5 rounded-full ${isFuturesMode ? (tick?.fws ? 'led' : 'bg-destructive animate-pulse') : 'bg-muted-foreground/35'}`} />
                  <span className={`font-semibold ${isFuturesMode ? 'text-foreground' : 'text-muted-foreground'}`}>合约</span>
                </span>
              </div>
            </div>
            <div className="flex items-center gap-2 flex-wrap">
              <span className="microlabel font-semibold">BINANCE</span>
              {cfg.unitLabel && (
                <span className="text-[11px] text-warning font-semibold">1枚 = 1盎司黄金（{cfg.unitFactor}{cfg.unitLabel}）</span>
              )}
            </div>
          </div>
        </div>
        <div className="text-left sm:text-right">
          {currentPrice > 0 ? (
            <div className="flex flex-col items-start sm:items-end gap-1">
              <span className="num text-2xl sm:text-3xl font-bold tracking-tight">
                ${fmtPrice(currentPrice)}
              </span>
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`num inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded-md ${isUp ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'}`}>
                  {isUp ? <TrendingUp className="w-3.5 h-3.5" /> : <TrendingDown className="w-3.5 h-3.5" />}
                  {isUp ? '+' : ''}{fmtPrice(change)}（{isUp ? '+' : ''}{changePct.toFixed(2)}% · 24h）
                </span>
                {cfg.unitLabel && usdCny > 0 && (
                  <span className="num text-xs text-warning font-semibold">
                    ¥{(currentPrice * usdCny / cfg.unitFactor!).toFixed(2)}/{cfg.unitLabel}
                  </span>
                )}
              </div>
            </div>
          ) : (
            <div className="space-y-2 flex flex-col items-start sm:items-end">
              <Skeleton className="h-9 w-40" />
              <Skeleton className="h-5 w-24" />
            </div>
          )}
        </div>
      </div>

      {/* PC 左图右面板（items-stretch 等高：图表卡 flex 填满左列，右面板内容变化时两列始终同高），移动端自然上下堆叠 */}
      <div className="grid lg:grid-cols-5 gap-5 items-stretch">
        {/* 左：图表 + BTC预测入口 */}
        <div className="lg:col-span-3 flex flex-col gap-5">
          {/* 手机上图表卡吃掉大部分页边距（留 8px 呼吸），保留边框和小圆角；PC 端(md+)完全保持原样 */}
          <Card className="flex-1 flex flex-col -mx-2 md:mx-0 rounded-md md:rounded-lg">
            <CardHeader className="pb-2 pt-4 px-4">
              <div className="flex items-center justify-between">
                <CardTitle>走势</CardTitle>
                <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
                  {TABS.map((tab, i) => (
                    <button key={tab.label} onClick={() => setActiveTab(i)} className={`num px-3 sm:px-3.5 py-2 sm:py-1.5 text-xs font-semibold transition-colors cursor-pointer ${activeTab === i ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                      {tab.label}
                    </button>
                  ))}
                  <button onClick={() => setActiveTab(TV_TAB)} className={`px-3 sm:px-3.5 py-2 sm:py-1.5 text-xs font-semibold transition-colors cursor-pointer ${activeTab === TV_TAB ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                    高级
                  </button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="p-0 md:p-2 flex-1 flex flex-col">
              {/* 现货/合约统一 K 线：合约走后端K线广播，现货由价格流驱动最后一根；
                  高度跟随右侧面板拉伸。主图:MACD:RSI=3:1:1，比纯 K 线时多要了两格，
                  所以底线抬到 PC 720 / 手机 520，否则主图蜡烛被压得看不清。
                  矮视口(手机横屏)按视口高度收，否则 520 的底线会把图顶出屏幕外要滚动才看得全 */}
              {activeTab < TABS.length && (
                <div className="flex-1 min-h-[520px] md:min-h-[720px] [@media(max-height:600px)]:min-h-[300px]">
                  <CandleChart
                    key={mode}
                    symbol={symbol}
                    interval={TABS[activeTab].interval}
                    limit={TABS[activeTab].limit}
                    klinesFn={isFuturesMode ? futuresApi.klines : cryptoApi.klines}
                    streamLive={klineLive}
                    tick={klineLive ? null : chartTick}
                    indicators
                  />
                </div>
              )}
              {/* 高级：TradingView（合约/现货各自 symbol） */}
              {activeTab === TV_TAB && <div className="flex-1 min-h-[400px] md:min-h-[560px] [@media(max-height:600px)]:min-h-[300px]"><TradingViewWidget symbol={isFuturesMode ? cfg.futuresTvSymbol : cfg.tvSymbol} label={cfg.name} /></div>}
            </CardContent>
          </Card>

          {/* BTC涨跌预测入口 */}
          {symbol === 'BTCUSDT' && (
            <button onClick={() => navigate('/prediction')}
                    className="w-full flex items-center justify-between px-4 py-3 rounded-lg pt-card hover:bg-surface-hover hover:border-primary/40 transition-colors group cursor-pointer">
              <div className="flex items-center gap-3">
                <span className="text-lg">🔮</span>
                <div className="text-left">
                  <div className="text-sm font-semibold">BTC 5分钟涨跌预测 <span className="text-[10px] font-bold text-primary ml-1">NEW</span></div>
                  <div className="text-[11px] text-muted-foreground">基于 Polymarket 实时概率，预测BTC短期走势</div>
                </div>
              </div>
              <ChevronRight className="w-4 h-4 text-muted-foreground group-hover:text-foreground transition-colors" />
            </button>
          )}
        </div>

        {/* 右：交易面板（含现货持仓信息块），2/5 宽 */}
        <Card className="lg:col-span-2 self-start lg:self-auto flex flex-col">
          {/* 现货持仓信息（两种模式都显示） */}
          {position && (position.quantity > 0 || position.frozenQuantity > 0) && (() => {
            const pnlPct = position.avgCost > 0 && currentPrice > 0
              ? ((currentPrice - position.avgCost) / position.avgCost) * 100 : 0;
            const pnlAmount = currentPrice > 0
              ? (currentPrice - position.avgCost) * position.quantity : 0;
            const isPnlUp = pnlPct >= 0;
            return (
              <div className="px-4 pt-4">
                <div className="rounded-lg border border-border bg-card-2 p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <Icon className={`w-6 h-6 ${cfg.colorClass}`} />
                      <div>
                        <span className="text-base font-bold">{cfg.name}</span>
                        <span className="num text-sm font-semibold text-muted-foreground ml-2">{position.quantity} 个</span>
                        {cfg.unitLabel && (
                          <span className="text-xs font-semibold text-warning ml-1.5">约合 {(position.quantity * cfg.unitFactor!).toFixed(1)} {cfg.unitLabel}</span>
                        )}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className={`num text-base font-bold tracking-tight ${isPnlUp ? 'text-gain' : 'text-loss'}`}>
                        {isPnlUp ? '+' : ''}{pnlPct.toFixed(2)}%
                      </div>
                      <div className={`num text-xs font-semibold mt-0.5 ${isPnlUp ? 'text-gain' : 'text-loss'}`}>
                        {isPnlUp ? '+' : ''}${fmtNum(pnlAmount)}
                      </div>
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs font-semibold text-muted-foreground pt-1">
                    <span>均价 <span className="num text-foreground">${fmtPrice(position.avgCost)}</span></span>
                    <span>现价 <span className="num text-foreground">${fmtPrice(currentPrice)}</span></span>
                    <span>市值 <span className="num text-foreground">${fmtNum(currentPrice * position.quantity)}</span></span>
                    {position.frozenQuantity > 0 && <span>冻结 <span className="num text-warning">{position.frozenQuantity}</span></span>}
                    {position.totalDiscount > 0 && <span>已省 <span className="num text-warning">${fmtNum(position.totalDiscount)}</span></span>}
                  </div>
                </div>
              </div>
            );
          })()}

          {isFuturesMode ? (
            <FuturesOpenPanel
              symbol={symbol}
              currentPrice={currentPrice}
              brackets={futuresBracketsMap[symbol]}
              positionsKey={futuresPanelKey}
              onModeChange={setMode}
              onTraded={handleFuturesOpened}
            />
          ) : (
            <SpotTradePanel
              symbol={symbol}
              currentPrice={currentPrice}
              position={position}
              onModeChange={setMode}
              onTraded={handleSpotTraded}
            />
          )}
        </Card>
      </div>

      {/* 合约持仓（独立Card，空仓时自隐藏；卡内自订 WS 流与档位表） */}
      {isFuturesMode && (
        <FuturesPositionsCard
          symbol={symbol}
          refreshKey={futuresPositionsKey}
          onOrdersChanged={() => setFuturesOrdersKey(k => k + 1)}
          onPositionsChanged={() => setFuturesPanelKey(k => k + 1)}
        />
      )}

      {/* 订单列表 */}
      <CoinOrdersCard
        symbol={symbol}
        mode={mode}
        spotRefreshKey={spotOrdersKey}
        futuresRefreshKey={futuresOrdersKey}
      />
    </div>
  );
}
