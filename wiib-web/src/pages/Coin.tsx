import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronRight, ChevronLeft, Target } from 'lucide-react';
import { cryptoApi, cryptoOrderApi, futuresApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useCountUp } from '../hooks/useCountUp';
import { useToast } from '../components/ui/use-toast';
import { Skeleton } from '../components/ui/skeleton';
import { CandleChart, type PositionOverlay, type TradeMark } from '../components/CandleChart';
import TradingViewWidget from '../components/TradingViewWidget';
import { SpotTradePanel } from '../components/coin/SpotTradePanel';
import { FuturesOpenPanel } from '../components/coin/FuturesOpenPanel';
import { FuturesPositionsCard } from '../components/coin/FuturesPositionsCard';
import { CoinOrdersCard } from '../components/coin/CoinOrdersCard';
import { MarketSessionBadge } from '../components/coin/MarketSessionBadge';
import { LoginPrompt } from '../components/LoginPrompt';
import { fmtNum } from '../lib/utils';
import { COIN_MAP, getCoin, DEFAULT_SYMBOL, formatCoinPrice } from '../lib/coinConfig';
import type { CryptoPosition, FuturesBracket, FuturesPosition } from '../types';

/** 图表周期：现货/合约统一 K 线，各拉 500 根（5m≈41h / 15m≈5天 / 1h≈20天 / 4h≈83天 / 1d≈1.4年） */
const INTERVALS = ['5m', '15m', '1h', '4h', '1d'] as const;
type ChartInterval = (typeof INTERVALS)[number];
const IV_KEY = 'wiib-chart-interval';

/** feed 只为这三档开了 WS 广播（Kline5m/15m/1h StreamHandler）；不在表里的走价格 tick 驱动最后一根 */
const KLINE_BROADCAST: readonly string[] = ['5m', '15m', '1h'];

export function CoinRoute() {
  const { symbol } = useParams<{ symbol: string }>();
  const s = symbol && COIN_MAP[symbol] ? symbol : DEFAULT_SYMBOL;
  return <Coin key={s} symbol={s} />;
}

export function Coin({ symbol = DEFAULT_SYMBOL }: { symbol?: string }) {
  const { t } = useTranslation('market');
  const cfg = getCoin(symbol);
  // 返回目标写死对应列表页而不是 navigate(-1)：后者在直接打开深链时会一路退出 App
  const backTo = cfg.category === 'commodity' ? '/commodity' : cfg.category === 'tradfi' ? '/tradfi' : '/coin';
  const fmtPrice = useCallback((n?: number | null) => formatCoinPrice(symbol, n), [symbol]);
  const navigate = useNavigate();
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);
  // 游客只看图和行情：持仓/委托/下单面板这些要登录的都不发请求、不渲染
  const loggedIn = useUserStore(s => !!s.token);

  // 现货/合约模式（纯合约标的只有合约）
  const [mode, setMode] = useState<'spot' | 'futures'>('futures');
  const isFuturesMode = mode === 'futures';
  const tick = useCryptoStream(symbol, mode);

  // 合约档位（开仓面板算强平价 + 持仓卡显示 MMR）
  const [futuresBracketsMap, setFuturesBracketsMap] = useState<Record<string, FuturesBracket[]>>({});
  useEffect(() => {
    futuresApi.brackets()
      .then(setFuturesBracketsMap)
      .catch(() => toast(t('coin.bracketsFailed'), 'error'));
  }, [toast, t]);

  // 资金费率：后端只在 0/8/16 点拉一次写缓存，这里读的就是那份缓存。
  // 一天才变三次，10 分钟重拉一次足够；无合约的标的后端返 null，整段不渲染
  const [fundingRate, setFundingRate] = useState<number | null>(null);
  useEffect(() => {
    if (!isFuturesMode) return;
    let cancelled = false;
    const pull = () => futuresApi.fundingRate(symbol)
      .then(r => { if (!cancelled) setFundingRate(r?.rate ?? null); })
      .catch(() => { if (!cancelled) setFundingRate(null); });
    pull();
    const timer = setInterval(pull, 10 * 60 * 1000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [symbol, isFuturesMode]);

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
  useEffect(() => { if (loggedIn) fetchPosition(); }, [fetchPosition, mode, loggedIn]);

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

  // 周期：图表顶栏切，记本地，下次进页沿用
  const [chartIv, setChartIv] = useState<ChartInterval>(() => {
    const saved = localStorage.getItem(IV_KEY) ?? '';
    return (INTERVALS as readonly string[]).includes(saved) ? saved as ChartInterval : '4h';
  });
  useEffect(() => { localStorage.setItem(IV_KEY, chartIv); }, [chartIv]);

  // 历史成交 B/S 标记：本 symbol 最近 200 笔终态委托的成交价（图上可见范围足够覆盖）。
  // B=买入方向（开多/平空），S=卖出方向（开空/平多）。时间取 createdAt：市价单即成交时刻，
  // 限价单是挂单时刻（成交时刻接口没给），偏差最多一根 K 线，接受
  const [tradeMarks, setTradeMarks] = useState<TradeMark[]>([]);
  useEffect(() => {
    if (!isFuturesMode || !loggedIn) return;
    let cancelled = false;
    const TERMINAL = new Set(['FILLED', 'STOP_LOSS', 'TAKE_PROFIT', 'LIQUIDATED']);
    futuresApi.orders(undefined, 1, 200, symbol).then(page => {
      if (cancelled) return;
      setTradeMarks(page.records
        .filter(o => TERMINAL.has(o.status) && o.filledPrice != null)
        .map(o => ({
          timeMs: new Date(o.createdAt).getTime(),
          side: (o.orderSide === 'OPEN_LONG' || o.orderSide === 'CLOSE_SHORT') ? 'B' as const : 'S' as const,
          price: o.filledPrice as number,
          quantity: o.quantity,
        })));
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [symbol, isFuturesMode, futuresOrdersKey, loggedIn]);

  // 本 symbol 的合约仓位（仓位卡每次拉到都上抛一份），映射成 K 线的仓位参考线。
  // 双向持仓同 symbol 至多一多一空，标签"多 10x / 空 25x"天然不重名
  const [futPositions, setFutPositions] = useState<FuturesPosition[]>([]);
  const positionOverlays = useMemo<PositionOverlay[] | undefined>(() => {
    if (!isFuturesMode) return undefined;
    return futPositions.map(p => ({
      id: p.id,
      side: p.side,
      label: `${p.side === 'LONG' ? t('coin.long') : t('coin.short')} ${p.leverage}x`,
      entry: p.entryPrice,
      tps: (p.takeProfits ?? []).map(t => t.price),
      sls: (p.stopLosses ?? []).map(s => s.price),
      liq: p.liquidationPrice > 0 ? p.liquidationPrice : null,
    }));
  }, [futPositions, isFuturesMode, t]);

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

  // 24h 基准与高低：1h×25 根，首根收盘当基准（与首页行情卡同口径），25 根里取最高/最低
  const [day, setDay] = useState({ base: 0, high: 0, low: 0 });
  useEffect(() => {
    let cancelled = false;
    const fn = isFuturesMode ? futuresApi.klines : cryptoApi.klines;
    fn(symbol, '1h', 25)
      .then(rows => {
        if (cancelled || !rows?.length) return;
        setDay({
          base: Number(rows[0][4]),
          high: Math.max(...rows.map(r => Number(r[2]))),
          low: Math.min(...rows.map(r => Number(r[3]))),
        });
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol, isFuturesMode]);
  const change = day.base > 0 && currentPrice > 0 ? currentPrice - day.base : 0;
  const changePct = day.base > 0 ? (change / day.base) * 100 : 0;
  const isUp = change >= 0;

  // 大数滚动：挂载 0→现价，之后每次报价变化补间过去
  const priceRef = useCountUp<HTMLElement>(currentPrice, v => `$${fmtPrice(v)}`);

  // K线实时驱动分派：crypto 合约 5m/15m/1h 有后端广播（含量/额），4h/1d 没有；
  // 大宗商品/美股永续只有 5m 广播；现货全部由价格 tick 驱动最后一根。
  // 无广播的档位量/额停在进页时的 REST 快照，只有 OHLC 随价格流跳
  const klineLive = isFuturesMode && (cfg.futuresOnly ? chartIv === '5m' : KLINE_BROADCAST.includes(chartIv));
  const chartTick = useMemo(() => {
    if (tick?.ts == null) return null;
    const p = isFuturesMode ? (tick.fp ?? tick.price) : tick.price;
    return p != null ? { price: p, ts: tick.ts } : null;
  }, [tick, isFuturesMode]);

  /** 行情灯：本模式的那颗按连线状态亮绿/闪红，另一模式那颗压成灰方块 */
  const feed = (active: boolean, connected: boolean) => ({
    dot: `inline-block w-[7px] h-[7px] mr-1.5 align-[1px] ${!active ? 'bg-border' : connected ? 'bg-gain' : 'bg-loss animate-pulse'}`,
    text: active ? '' : 'mute',
  });
  const spotFeed = feed(!isFuturesMode, !!tick?.ws);
  const futFeed = feed(isFuturesMode, !!tick?.fws);

  return (
    <div className="wrap">
      {/* ====== 页头：币对 / 行情灯 / 报价大数 ====== */}
      <div className="grid grid-cols-1 xl:grid-cols-[1fr_auto] gap-8 items-end pt-8">
        <div>
          {/* 返回列表：装成 PWA 后 iOS 没有浏览器返回键，底部 Tab 的"市场"只通向股票列表，
              不给入口就退不回币种/大宗/TradFi 列表 */}
          <button
            type="button"
            onClick={() => navigate(backTo)}
            className="inline-flex items-center gap-1 mb-2.5 text-[13px] mute hover:text-foreground transition-colors cursor-pointer"
          >
            <ChevronLeft className="w-3.5 h-3.5" />{t('coin.backToList')}
          </button>

          <div className="flex items-baseline gap-4 flex-wrap">
            <b className="cond text-[44px] font-bold leading-none">{symbol}</b>
            <span className="text-[15px] mute">{cfg.pair} · {isFuturesMode ? t('coin.perp') : t('coin.spot')}</span>
            <span className="inline-flex gap-3.5 self-center text-[12.5px] font-semibold">
              <span className={spotFeed.text} title={t('coin.spotFeed')}><i className={spotFeed.dot} />{t('coin.spot')}</span>
              <span className={futFeed.text} title={t('coin.futuresFeed')}><i className={futFeed.dot} />{t('coin.futures')}</span>
            </span>
          </div>

          <div className="flex items-center gap-4 flex-wrap mt-2 text-[12px] mute">
            <span>BINANCE</span>
            {isFuturesMode && tick?.mp != null && (
              <span>{t('coin.markPrice')} <b className="num font-semibold text-foreground">{fmtPrice(tick.mp)}</b></span>
            )}
            {isFuturesMode && fundingRate != null && (
              <span>
                {t('coin.fundingRate')}{' '}
                <b className="num font-semibold text-foreground">
                  {fundingRate >= 0 ? '+' : ''}{(fundingRate * 100).toFixed(4)}%
                </b> 8h
              </span>
            )}
            {/* TradFi 标的：合约 7×24，但流动性跟着标的股票市场走，给个当前时段入口 */}
            {cfg.market && <MarketSessionBadge market={cfg.market} />}
            {cfg.unitLabel && (
              <span className="wn font-semibold">{t('coin.goldUnit', { factor: cfg.unitFactor, unit: cfg.unitLabel })}</span>
            )}
          </div>
        </div>

        <div className="num text-left xl:text-right">
          {currentPrice > 0 ? (
            <>
              <b ref={priceRef} className="cond block text-[64px] font-bold leading-none" />
              <div className="flex items-baseline flex-wrap gap-3 mt-2 text-[15px] font-semibold justify-start xl:justify-end">
                <span className={isUp ? 'up' : 'dn'}>
                  {t('coin.change', {
                    change: `${isUp ? '+' : ''}${fmtPrice(change)}`,
                    pct: `${isUp ? '+' : ''}${changePct.toFixed(2)}`,
                  })}
                </span>
                <span className="mute font-medium text-[13px]">{t('coin.h24')}</span>
                <span className="mute font-medium text-[13px]">{t('coin.high')} <b className="text-foreground">{day.high > 0 ? fmtPrice(day.high) : '-'}</b></span>
                <span className="mute font-medium text-[13px]">{t('coin.low')} <b className="text-foreground">{day.low > 0 ? fmtPrice(day.low) : '-'}</b></span>
                {cfg.unitLabel && usdCny > 0 && (
                  <span className="wn font-semibold text-[13px]">
                    ¥{(currentPrice * usdCny / cfg.unitFactor!).toFixed(2)}/{cfg.unitLabel}
                  </span>
                )}
              </div>
            </>
          ) : (
            <div className="flex flex-col items-start xl:items-end gap-2">
              <Skeleton className="h-16 w-56" />
              <Skeleton className="h-5 w-40" />
            </div>
          )}
        </div>
      </div>

      {/* ====== 图 + 下单面板 ====== */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-8 mt-7 border-t-2 border-foreground pt-[18px]">
        <div className="xl:col-span-8 flex flex-col gap-5">
          {/* 周期/图型/指标/画线全在图表组件里；「高级」档把 plot 区换成 TradingView。
              矮视口(手机横屏)收到 360，否则整张图顶出屏幕外 */}
          <div className="h-[600px] xl:h-[822px] [@media(max-height:600px)]:h-[360px]">
            <CandleChart
              key={mode}
              symbol={symbol}
              interval={chartIv}
              limit={500}
              onIntervalChange={setChartIv}
              marketLabel={`BINANCE ${isFuturesMode ? t('coin.perpShort') : t('coin.spot')}`}
              advanced={<TradingViewWidget symbol={isFuturesMode ? cfg.futuresTvSymbol : cfg.tvSymbol} label={cfg.name} />}
              positionOverlays={positionOverlays}
              tradeMarks={isFuturesMode ? tradeMarks : undefined}
              econMarks={symbol === 'BTCUSDT'}
              klinesFn={isFuturesMode ? futuresApi.klines : cryptoApi.klines}
              loadHistory={loggedIn}
              streamLive={klineLive}
              tick={klineLive ? null : chartTick}
              indicators
            />
          </div>

          {/* BTC涨跌预测入口 */}
          {symbol === 'BTCUSDT' && (
            <Link to="/prediction" className="hov group flex items-center gap-3 py-3 border-t border-border">
              <Target className="ic mute group-hover:text-primary transition-colors" />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 text-[15px] font-bold">
                  {t('coin.predictTitle')}<span className="chip fill orange">NEW</span>
                </div>
                <div className="text-[12px] mute">{t('coin.predictDesc')}</div>
              </div>
              <ChevronRight className="ic mute" />
            </Link>
          )}
        </div>

        <aside className="xl:col-span-4 flex flex-col gap-[18px]">
          {/* 现货持仓信息（两种模式都显示） */}
          {position && (position.quantity > 0 || position.frozenQuantity > 0) && (() => {
            const pnlPct = position.avgCost > 0 && currentPrice > 0
              ? ((currentPrice - position.avgCost) / position.avgCost) * 100 : 0;
            const pnlAmount = currentPrice > 0
              ? (currentPrice - position.avgCost) * position.quantity : 0;
            const isPnlUp = pnlPct >= 0;
            return (
              <div className="border-t-2 border-foreground pt-3.5">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <b className="text-[16px] font-bold">{cfg.name}</b>
                    <span className="num ml-2 text-[13px] font-semibold mute">{t('coin.units', { qty: position.quantity })}</span>
                    {cfg.unitLabel && (
                      <span className="ml-1.5 text-[12px] font-semibold wn">{t('coin.approxUnit', { value: (position.quantity * cfg.unitFactor!).toFixed(1), unit: cfg.unitLabel })}</span>
                    )}
                  </div>
                  <div className={`num shrink-0 text-right ${isPnlUp ? 'up' : 'dn'}`}>
                    <div className="text-[16px] font-bold">{isPnlUp ? '+' : ''}{pnlPct.toFixed(2)}%</div>
                    <div className="text-[12px] font-semibold">{isPnlUp ? '+' : ''}${fmtNum(pnlAmount)}</div>
                  </div>
                </div>
                <div className="num mt-3">
                  <div className="kv"><span className="k">{t('coin.avgCost')}</span><span className="v">${fmtPrice(position.avgCost)}</span></div>
                  <div className="kv"><span className="k">{t('coin.lastPrice')}</span><span className="v">${fmtPrice(currentPrice)}</span></div>
                  <div className="kv"><span className="k">{t('coin.marketValue')}</span><span className="v">${fmtNum(currentPrice * position.quantity)}</span></div>
                  {position.frozenQuantity > 0 && (
                    <div className="kv"><span className="k">{t('coin.frozen')}</span><span className="v wn">{position.frozenQuantity}</span></div>
                  )}
                  {position.totalDiscount > 0 && (
                    <div className="kv"><span className="k">{t('coin.saved')}</span><span className="v wn">${fmtNum(position.totalDiscount)}</span></div>
                  )}
                </div>
              </div>
            );
          })()}

          {!loggedIn ? (
            <LoginPrompt text={t('coin.loginToTrade')} className="border-t-2 border-foreground pt-3.5" />
          ) : isFuturesMode ? (
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
        </aside>
      </div>

      {/* 合约持仓（空仓时自隐藏；卡内自订 WS 流与档位表） */}
      {loggedIn && isFuturesMode && (
        <FuturesPositionsCard
          symbol={symbol}
          showCloseAll
          refreshKey={futuresPositionsKey}
          onOrdersChanged={() => setFuturesOrdersKey(k => k + 1)}
          onPositionsChanged={() => setFuturesPanelKey(k => k + 1)}
          onPositions={setFutPositions}
        />
      )}

      {loggedIn && (
        <CoinOrdersCard
          symbol={symbol}
          mode={mode}
          spotRefreshKey={spotOrdersKey}
          futuresRefreshKey={futuresOrdersKey}
        />
      )}
    </div>
  );
}
