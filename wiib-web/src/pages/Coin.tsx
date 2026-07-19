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
      {/* 顶部价格 */}
      <Card className="relative overflow-hidden">
        <div className={`absolute top-0 right-0 p-32 rounded-full blur-3xl -z-10 transform translate-x-1/3 -translate-y-1/2 opacity-20 bg-linear-to-br ${cfg.gradientClass}`} />
        <CardContent className="p-5 md:p-6">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <div className="p-2.5 sm:p-3 rounded-2xl bg-card neu-raised-sm shrink-0">
                <Icon className={`w-6 h-6 sm:w-7 sm:h-7 ${cfg.colorClass}`} />
              </div>
              <div className="space-y-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-lg sm:text-xl font-black tracking-tight">{cfg.pair}</span>
                  <div className="flex items-center gap-2 px-2 py-0.5 rounded-full bg-card neu-flat text-[10px]">
                    <span className="flex items-center gap-1.5" title="现货行情">
                      <span className={`inline-block w-2 h-2 rounded-full ${isFuturesMode ? 'bg-muted-foreground/35' : (tick?.ws ? 'bg-success' : 'bg-destructive animate-pulse')}`} />
                      <span className={`font-bold ${isFuturesMode ? 'text-muted-foreground' : 'text-foreground'}`}>现货</span>
                    </span>
                    <span className="w-0.5 h-2.5 bg-border" />
                    <span className="flex items-center gap-1.5" title="合约行情">
                      <span className={`inline-block w-2 h-2 rounded-full ${isFuturesMode ? (tick?.fws ? 'bg-success' : 'bg-destructive animate-pulse') : 'bg-muted-foreground/35'}`} />
                      <span className={`font-bold ${isFuturesMode ? 'text-foreground' : 'text-muted-foreground'}`}>合约</span>
                    </span>
                  </div>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-xs font-bold text-muted-foreground">Binance</span>
                  {cfg.unitLabel && (
                    <>
                      <span className="w-1.5 h-1.5 rounded-full bg-border" />
                      <span className="text-[11px] text-warning font-bold">1枚 = 1盎司黄金（{cfg.unitFactor}{cfg.unitLabel}）</span>
                    </>
                  )}
                </div>
              </div>
            </div>
            <div className="text-left sm:text-right">
              {currentPrice > 0 ? (
                <div className="flex flex-col items-start sm:items-end">
                  <div className="flex items-baseline gap-2">
                    <span className="text-2xl sm:text-3xl font-black tracking-tight font-mono" style={{ color: 'var(--color-foreground)' }}>
                      ${fmtPrice(currentPrice)}
                    </span>
                  </div>
                  {cfg.unitLabel && usdCny > 0 && currentPrice > 0 && (
                    <div className="text-sm text-warning font-mono font-bold mt-1">
                      ¥{(currentPrice * usdCny / cfg.unitFactor!).toFixed(2)}/{cfg.unitLabel}
                    </div>
                  )}
                  <div className={`flex items-center gap-1.5 text-sm font-bold mt-2 px-2.5 py-1 rounded-lg neu-flat ${isUp ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'}`}>
                    {isUp ? <TrendingUp className="w-4 h-4 stroke-[3px]" /> : <TrendingDown className="w-4 h-4 stroke-[3px]" />}
                    <span>{isUp ? '+' : ''}{fmtPrice(change)}</span>
                    <span>({isUp ? '+' : ''}{changePct.toFixed(2)}% · 24h)</span>
                  </div>
                </div>
              ) : (
                <div className="space-y-2 flex flex-col items-start sm:items-end">
                  <Skeleton className="h-10 w-40" />
                  <Skeleton className="h-6 w-24" />
                </div>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* PC 左图右面板（items-stretch 等高：图表卡 flex 填满左列，右面板内容变化时两列始终同高），移动端自然上下堆叠 */}
      <div className="grid lg:grid-cols-5 gap-5 items-stretch">
        {/* 左：图表 + BTC预测入口 */}
        <div className="lg:col-span-3 flex flex-col gap-5">
          <Card className="flex-1 flex flex-col">
            <CardHeader className="pb-2 pt-5 px-5">
              <div className="flex items-center justify-between">
                <CardTitle className="text-base font-black">走势</CardTitle>
                <div className="flex rounded-xl bg-card neu-raised-sm overflow-hidden">
                  {TABS.map((tab, i) => (
                    <button key={tab.label} onClick={() => setActiveTab(i)} className={`px-3 sm:px-4 py-2 sm:py-1.5 text-xs font-bold border-r border-border transition-colors ${activeTab === i ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                      {tab.label}
                    </button>
                  ))}
                  <button onClick={() => setActiveTab(TV_TAB)} className={`px-3 sm:px-4 py-2 sm:py-1.5 text-xs font-bold transition-colors ${activeTab === TV_TAB ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                    高级
                  </button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="p-2 flex-1 flex flex-col">
              {/* 现货/合约统一 K 线：合约走后端K线广播，现货由价格流驱动最后一根；高度跟随右侧面板拉伸，PC 最矮 560、手机 400 免得占满两屏 */}
              {activeTab < TABS.length && (
                <div className="flex-1 min-h-[400px] md:min-h-[560px]">
                  <CandleChart
                    key={mode}
                    symbol={symbol}
                    interval={TABS[activeTab].interval}
                    limit={TABS[activeTab].limit}
                    klinesFn={isFuturesMode ? futuresApi.klines : cryptoApi.klines}
                    streamLive={klineLive}
                    tick={klineLive ? null : chartTick}
                  />
                </div>
              )}
              {/* 高级：TradingView（合约/现货各自 symbol） */}
              {activeTab === TV_TAB && <div className="flex-1 min-h-[400px] md:min-h-[560px]"><TradingViewWidget symbol={isFuturesMode ? cfg.futuresTvSymbol : cfg.tvSymbol} label={cfg.name} /></div>}
            </CardContent>
          </Card>

          {/* BTC涨跌预测入口 */}
          {symbol === 'BTCUSDT' && (
            <button onClick={() => navigate('/prediction')}
                    className="w-full flex items-center justify-between px-5 py-3.5 rounded-2xl bg-gradient-to-r from-amber-500/10 to-orange-500/10 border border-amber-500/30 hover:border-amber-500/50 transition-all group">
              <div className="flex items-center gap-3">
                <span className="text-lg">🔮</span>
                <div className="text-left">
                  <div className="text-sm font-bold">BTC 5分钟涨跌预测 <span className="text-[10px] font-bold text-amber-500 ml-1">NEW</span></div>
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
              <div className="px-5 pt-5">
                <div className={`rounded-2xl bg-card p-4 space-y-3 neu-raised`}>
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className={`p-2 rounded-xl bg-card neu-flat`}>
                        <Icon className={`w-5 h-5 ${cfg.colorClass}`} />
                      </div>
                      <div>
                        <span className="text-base font-black">{cfg.name}</span>
                        <span className="text-sm font-bold text-muted-foreground ml-2">{position.quantity} 个</span>
                        {cfg.unitLabel && (
                          <span className="text-xs font-bold text-warning ml-1.5">约合 {(position.quantity * cfg.unitFactor!).toFixed(1)} {cfg.unitLabel}</span>
                        )}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className={`text-base font-black tracking-tight ${isPnlUp ? 'text-gain' : 'text-loss'}`}>
                        {isPnlUp ? '+' : ''}{pnlPct.toFixed(2)}%
                      </div>
                      <div className={`text-xs font-bold mt-0.5 ${isPnlUp ? 'text-gain' : 'text-loss'}`}>
                        {isPnlUp ? '+' : ''}${fmtNum(pnlAmount)}
                      </div>
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs font-bold text-muted-foreground pt-1">
                    <span>均价 <span className="text-foreground font-mono">${fmtPrice(position.avgCost)}</span></span>
                    <span>现价 <span className="text-foreground font-mono">${fmtPrice(currentPrice)}</span></span>
                    <span>市值 <span className="text-foreground font-mono">${fmtNum(currentPrice * position.quantity)}</span></span>
                    {position.frozenQuantity > 0 && <span>冻结 <span className="text-warning font-mono">{position.frozenQuantity}</span></span>}
                    {position.totalDiscount > 0 && <span>已省 <span className="text-warning font-mono">${fmtNum(position.totalDiscount)}</span></span>}
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
