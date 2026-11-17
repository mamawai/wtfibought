import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft } from 'lucide-react';
import { cryptoApi, cryptoOrderApi, futuresApi, quantApi, type WhaleCoinDetail } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useToast } from '../components/ui/use-toast';
import { Skeleton } from '../components/ui/skeleton';
import { BigNum } from '../components/ui/big-num';
import { CandleChart, type PositionOverlay, type TradeMark } from '../components/CandleChart';
import TradingViewWidget from '../components/TradingViewWidget';
import { SpotTradePanel } from '../components/coin/SpotTradePanel';
import { FuturesOpenPanel } from '../components/coin/FuturesOpenPanel';
import { FuturesPositionsCard } from '../components/coin/FuturesPositionsCard';
import { CoinOrdersCard } from '../components/coin/CoinOrdersCard';
import { OrderBook } from '../components/coin/OrderBook';
import { MarketSessionBadge } from '../components/coin/MarketSessionBadge';
import { WhaleBlock } from '../components/coin/WhaleBlock';
import { LoginPrompt } from '../components/LoginPrompt';
import { cn, fmtNum, fmtSignedPct, fmtSignedUsd, parseServerTime } from '../lib/utils';
import { COIN_MAP, getCoin, DEFAULT_SYMBOL, formatCoinPrice, getCoinPriceDecimals } from '../lib/coinConfig';
import type { CryptoPosition, FuturesBracket, FuturesPosition } from '../types';
import './Coin.css';

/** 图表周期：现货/合约统一 K 线，各拉 500 根（5m≈41h / 15m≈5天 / 1h≈20天 / 4h≈83天 / 1d≈1.4年） */
const INTERVALS = ['5m', '15m', '1h', '4h', '1d'] as const;
type ChartInterval = (typeof INTERVALS)[number];
const IV_KEY = 'wiib-chart-interval';

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

  // 资金费率：feed 从 markPrice 推送里写的当期预估费率（结算前随溢价慢慢变），10 分钟重拉一次足够；
  // 没有合约行情的标的后端返 null，整段不渲染
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

  // 大户持仓：Hyperliquid 只有加密币，大宗/TradFi 与现货模式不拉。
  // 后端 10 分钟一轮快照，60 秒重拉一次；不在盯盘列表/没快照/没仓位都回 null，整块不渲染
  const whaleOn = isFuturesMode && !cfg.category;
  const [whale, setWhale] = useState<WhaleCoinDetail | null>(null);
  useEffect(() => {
    if (!whaleOn) return;
    let cancelled = false;
    const pull = () => quantApi.whaleCoin(symbol.replace(/USDT$/, ''))
      .then(d => { if (!cancelled) setWhale(d); })
      .catch(() => { if (!cancelled) setWhale(null); });
    pull();
    const timer = setInterval(pull, 60 * 1000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [symbol, whaleOn]);

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
  // 撤单：余额（含全仓可用，面板跟着 fetchUser 重拉）要刷；现货撤卖单还会把冻结的币退回持仓
  const handleOrderCancelled = (kind: 'spot' | 'futures') => {
    fetchUser();
    if (kind === 'spot') fetchPosition();
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
          timeMs: parseServerTime(o.createdAt).getTime(),
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
      .then(rows => { if (!cancelled && rows.length) setRestPrice(Number(rows[rows.length - 1][4])); })
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
        if (cancelled || !rows.length) return;
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

  // K线实时驱动分派：合约各周期都有后端 K 线广播（含量/额）；现货由价格 tick 驱动最后一根，
  // 量/额停在进页时的 REST 快照，只有 OHLC 随价格流跳
  const klineLive = isFuturesMode;
  const chartTick = useMemo(() => {
    if (tick?.ts == null) return null;
    const p = isFuturesMode ? (tick.fp ?? tick.price) : tick.price;
    return p != null ? { price: p, ts: tick.ts } : null;
  }, [tick, isFuturesMode]);

  /** 行情灯：在看的那条按连线状态亮绿 / 闪红，另一条灰 */
  const feedCls = (active: boolean, connected: boolean) => !active ? undefined : connected ? 'live' : 'down';

  // 现货持仓：持有总量 = 可用 + 挂限价卖单冻结的，市值/盈亏按它算（同后端估值）；toFixed 抹掉浮点尾差
  const heldQty = position ? Number((position.quantity + position.frozenQuantity).toFixed(8)) : 0;
  const posPnlPct = position && position.avgCost > 0 && currentPrice > 0
    ? ((currentPrice - position.avgCost) / position.avgCost) * 100 : 0;
  const posPnl = position && currentPrice > 0 ? (currentPrice - position.avgCost) * heldQty : 0;

  return (
    <div className="page-shell p-tr">
      {/* ====== 页头：币对 / 行情灯 / 报价大数 ====== */}
      <div className="ph">
        <div>
          {/* 返回列表：装成 PWA 后 iOS 没有浏览器返回键，桌面也没有底部 Tab，得在页里给个回列表的入口 */}
          <Link className="back" to={backTo}><ChevronLeft className="ic" />{t('coin.backToList')}</Link>
          <div className="sym">
            <span className="n"><cfg.icon className={cn('ci', cfg.colorClass)} />{symbol}</span>
            <span className="p">{cfg.pair} · {isFuturesMode ? t('coin.perp') : t('coin.spot')}</span>
            <span className="feeds">
              {!cfg.futuresOnly && (
                <span className={feedCls(!isFuturesMode, !!tick?.ws)} title={t('coin.spotFeed')}><i />{t('coin.spot')}</span>
              )}
              <span className={feedCls(isFuturesMode, !!tick?.fws)} title={t('coin.futuresFeed')}><i />{t('coin.futures')}</span>
            </span>
          </div>
          <div className="meta">
            <span>BINANCE</span>
            {isFuturesMode && tick?.mp != null && (
              <span>{t('coin.markPrice')} <b>{fmtPrice(tick.mp)}</b></span>
            )}
            {isFuturesMode && fundingRate != null && (
              <span>{t('coin.fundingRate')} <b>{fundingRate >= 0 ? '+' : ''}{(fundingRate * 100).toFixed(4)}%</b> 8h</span>
            )}
            {/* TradFi 标的：合约 7×24，但流动性跟着标的股票市场走，给个当前时段入口 */}
            {cfg.market && <MarketSessionBadge market={cfg.market} />}
            {cfg.unitLabel && (
              <span className="wn font-medium">{t('coin.goldUnit', { factor: cfg.unitFactor, unit: cfg.unitLabel })}</span>
            )}
          </div>
        </div>

        <div className="px">
          {currentPrice > 0 ? (
            <>
              <b className="big nf"><BigNum value={currentPrice} decimals={getCoinPriceDecimals(symbol)} /></b>
              <div className="sub">
                <span className={cn('chg', isUp ? 'up' : 'dn')}>
                  {t('coin.change', {
                    change: `${isUp ? '+' : ''}${fmtPrice(change)}`,
                    pct: `${isUp ? '+' : ''}${changePct.toFixed(2)}`,
                  })}
                </span>
                <span>{t('coin.h24')}</span>
                <span>{t('coin.high')} <b>{day.high > 0 ? fmtPrice(day.high) : '-'}</b></span>
                <span>{t('coin.low')} <b>{day.low > 0 ? fmtPrice(day.low) : '-'}</b></span>
                {cfg.unitLabel && usdCny > 0 && (
                  <span className="wn">¥{(currentPrice * usdCny / cfg.unitFactor!).toFixed(2)}/{cfg.unitLabel}</span>
                )}
              </div>
            </>
          ) : (
            <>
              <Skeleton />
              <Skeleton className="t" />
            </>
          )}
        </div>
      </div>

      {/* ====== 图 + 下单面板 ====== */}
      <div className="lay">
        <div className="left">
          {/* 周期/图型/指标/画线全在图表组件里；「高级」档把 plot 区换成 TradingView。
              矮视口(手机横屏)收到 360；手机竖屏高度由图表按副图数自己定 */}
          <div className="chart h-[600px] xl:h-[822px] [@media(max-height:600px)]:h-[360px] phone:h-auto">
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

          {/* 盘口只有合约有，横着放在图下面 */}
          {isFuturesMode && (
            <OrderBook symbol={symbol} lastPrice={tick?.fp ?? tick?.price} markPrice={tick?.mp} />
          )}

          {whaleOn && whale && <WhaleBlock data={whale} symbol={symbol} />}
        </div>

        <aside className="side">
          {/* 这个币的现货持仓（两种模式都显示） */}
          {position && heldQty > 0 && (
            <div className="blk spos">
              <div className="h">
                <div>
                  <b>{cfg.name}</b>
                  <span>{t('coin.units', { qty: heldQty })}</span>
                  {cfg.unitLabel && <em>{t('coin.approxUnit', { value: (heldQty * cfg.unitFactor!).toFixed(1), unit: cfg.unitLabel })}</em>}
                </div>
                <div className={posPnlPct >= 0 ? 'up' : 'dn'}>
                  {fmtSignedPct(posPnlPct)}
                  <small>{fmtSignedUsd(posPnl)}</small>
                </div>
              </div>
              <div className="kv">
                <div><span>{t('coin.avgCost')}</span><b>₮{fmtPrice(position.avgCost)}</b></div>
                <div><span>{t('coin.lastPrice')}</span><b>₮{fmtPrice(currentPrice)}</b></div>
                <div><span>{t('coin.marketValue')}</span><b>₮{fmtNum(currentPrice * heldQty)}</b></div>
                {position.frozenQuantity > 0 && (
                  <div><span>{t('coin.frozen')}</span><b className="wn">{position.frozenQuantity}</b></div>
                )}
              </div>
            </div>
          )}

          {!loggedIn ? (
            <LoginPrompt text={t('coin.loginToTrade')} className="blk" />
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

      {/* 合约持仓（卡内自订 WS 流与档位表） */}
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
          onCancelled={handleOrderCancelled}
        />
      )}
    </div>
  );
}
