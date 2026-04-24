import { useState, useEffect, useRef, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import * as echarts from 'echarts';
import { cryptoApi, cryptoOrderApi, buffApi, futuresApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useIsDark } from '../hooks/useIsDark';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { FuturesActionButton } from '../components/FuturesActionButton';
import { TrendingUp, TrendingDown, ChevronLeft, ChevronRight, Loader2, X, RefreshCw, Sparkles, Wallet, Warehouse, Scale, HelpCircle, Plus, Flame } from 'lucide-react';
import TradingViewWidget from '../components/TradingViewWidget';
import { COIN_MAP, getCoin, DEFAULT_SYMBOL } from '../lib/coinConfig';
import type { CryptoOrder, CryptoPosition, PageResult, UserBuff, FuturesPosition, FuturesOrder, FuturesSLItem, FuturesTPItem } from '../types';

interface SLTPRow { price: string; quantity: string }

const COMMISSION_RATE = 0.001;
const FUTURES_COMMISSION_RATE = 0.0004;
const POSITION_PCTS = [0.25, 0.5, 0.75, 1];
interface TabConfig {
  label: string;
  interval: string;
  limit: number;
}

const TABS: TabConfig[] = [
  { label: '1D', interval: '1m', limit: 720 },
  { label: '7D', interval: '15m', limit: 672 },
];

interface ChartPoint {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
}

function parseKlines(raw: number[][]): ChartPoint[] {
  return raw.map(k => ({
    time: k[0] as number,
    open: parseFloat(String(k[1])),
    high: parseFloat(String(k[2])),
    low: parseFloat(String(k[3])),
    close: parseFloat(String(k[4])),
  }));
}

function formatTime(ts: number, interval: string): string {
  const d = new Date(ts);
  if (interval === '15m') {
    return `${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
  }
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

function formatPrice(n: number): string {
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function roundHalfUp2(n: number): number {
  return Math.round((n + Number.EPSILON) * 100) / 100;
}

function roundCeil2(n: number): number {
  return Math.ceil((n - 1e-9) * 100) / 100;
}

function getStepPrecision(step: number): number {
  const s = String(step);
  const i = s.indexOf('.');
  return i >= 0 ? s.length - i - 1 : 0;
}
function calcMaxIncreaseQty(balance: number, price: number, leverage: number, step: number): number {
  if (balance <= 0 || price <= 0 || leverage <= 0 || step <= 0) return 0;
  const precision = getStepPrecision(step);
  let left = 0;
  let right = Math.floor((balance * leverage / price) / step);
  let best = 0;
  while (left <= right) {
    const mid = Math.floor((left + right) / 2);
    const qty = Number((mid * step).toFixed(precision));
    const value = roundHalfUp2(price * qty);
    const margin = roundCeil2(value / leverage);
    const commission = roundHalfUp2(value * COMMISSION_RATE);
    if (margin + commission <= balance + 1e-9) {
      best = qty;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }
  return best;
}

function estimateLiqPrice(side: 'LONG' | 'SHORT', price: number, leverage: number): number {
  const mmr = 0.005;
  if (side === 'LONG') return price * (1 - 1 / leverage) / (1 - mmr);
  return price * (1 + 1 / leverage) / (1 + mmr);
}

function calcFuturesOpenEstimate(marginQty: number, price: number, leverage: number) {
  const orderQty = marginQty * leverage;
  const positionValue = roundHalfUp2(price * orderQty);
  const margin = roundCeil2(positionValue / leverage);
  const commission = roundHalfUp2(positionValue * FUTURES_COMMISSION_RATE);
  const fundingFee = roundHalfUp2(positionValue * 0.0001);
  const totalCost = roundHalfUp2(margin + commission);
  return { orderQty, positionValue, margin, commission, fundingFee, totalCost };
}

function calcMaxAffordableMarginQty(balance: number, pct: number, price: number, leverage: number, step: number): number {
  const budget = balance * pct;
  if (budget <= 0 || price <= 0 || leverage <= 0 || step <= 0) return 0;

  const precision = getStepPrecision(step);
  let left = 0;
  let right = Math.floor((budget / price) / step);
  let best = 0;

  while (left <= right) {
    const mid = Math.floor((left + right) / 2);
    const qty = Number((mid * step).toFixed(precision));
    const estimate = calcFuturesOpenEstimate(qty, price, leverage);
    if (estimate.totalCost <= budget + 1e-9) {
      best = qty;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }

  return best;
}

function formatDateTime(s: string): string {
  const d = new Date(s);
  return `${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

const LEVERAGE_OPTIONS = Array.from({ length: 10 }, (_, i) => i + 1);
const ORDER_STATUS_FILTERS = [
  { label: '全部', value: '' },
  { label: '待成交', value: 'PENDING' },
  { label: '结算中', value: 'SETTLING' },
  { label: '已成交', value: 'FILLED' },
  { label: '已取消', value: 'CANCELLED' },
];

const FUTURES_ORDER_FILTERS = [
  { label: '全部', value: '' },
  { label: '待成交', value: 'PENDING' },
  { label: '已成交', value: 'FILLED' },
  { label: '已取消', value: 'CANCELLED' },
];

const FUTURES_SIDE_MAP: Record<string, { label: string; color: string }> = {
  OPEN_LONG: { label: '多头开仓', color: 'text-green-500' },
  OPEN_SHORT: { label: '空头开仓', color: 'text-red-500' },
  CLOSE_LONG: { label: '多头平仓', color: 'text-red-500' },
  CLOSE_SHORT: { label: '空头平仓', color: 'text-green-500' },
  INCREASE_LONG: { label: '多头加仓', color: 'text-green-500' },
  INCREASE_SHORT: { label: '空头加仓', color: 'text-red-500' },
};

const STATUS_MAP: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' | 'success' | 'warning' }> = {
  PENDING: { label: '待成交', variant: 'warning' },
  TRIGGERED: { label: '已触发', variant: 'default' },
  SETTLING: { label: '结算中', variant: 'warning' },
  FILLED: { label: '已成交', variant: 'success' },
  CANCELLED: { label: '已取消', variant: 'secondary' },
  EXPIRED: { label: '已过期', variant: 'secondary' },
  LIQUIDATED: { label: '已强平', variant: 'destructive' },
  STOP_LOSS: { label: '已止损', variant: 'warning' },
  TAKE_PROFIT: { label: '已止盈', variant: 'success' },
};

export function CoinRoute() {
  const { symbol } = useParams<{ symbol: string }>();
  const s = symbol && COIN_MAP[symbol] ? symbol : DEFAULT_SYMBOL;
  return <Coin key={s} symbol={s} />;
}

export function Coin({ symbol = DEFAULT_SYMBOL }: { symbol?: string }) {
  const cfg = getCoin(symbol);
  const Icon = cfg.icon;
  const MIN_QTY = cfg.minQty;
  const navigate = useNavigate();
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const [activeTab, setActiveTab] = useState(0);
  const [points1D, setPoints1D] = useState<ChartPoint[]>([]);
  const [points7D, setPoints7D] = useState<ChartPoint[]>([]);
  const [loading, setLoading] = useState(true);
  const chartRef1D = useRef<HTMLDivElement>(null);
  const chartRef7D = useRef<HTMLDivElement>(null);
  const qtyAnimRef = useRef(0);
  const chartInst1D = useRef<echarts.ECharts | null>(null);
  const chartInst7D = useRef<echarts.ECharts | null>(null);

  // 实物换算币种: USD/CNY 汇率
  const [usdCny, setUsdCny] = useState(0);
  useEffect(() => {
    if (!cfg.unitLabel) return;
    fetch('https://open.er-api.com/v6/latest/USD')
      .then(r => r.json())
      .then(d => { if (d.result === 'success') setUsdCny(d.rates.CNY); })
      .catch(() => {});
  }, [cfg.unitLabel]);

  // 交易面板状态
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT' | 'FUTURES'>('FUTURES');
  const [qtyMap, setQtyMap] = useState<Record<string, string>>({});
  const qtyKey = `${side}_${orderType}`;
  const quantity = qtyMap[qtyKey] ?? '';
  const setQuantity = (v: string) => setQtyMap(m => ({ ...m, [qtyKey]: v }));
  const animateQuantity = useCallback((target: number, step: number) => {
    cancelAnimationFrame(qtyAnimRef.current);
    const from = parseFloat(quantity) || 0;
    const duration = 350;
    const start = performance.now();
    const precision = getStepPrecision(step);
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      const ease = 1 - (1 - t) ** 3;
      const v = from + (target - from) * ease;
      const s = v.toFixed(precision).replace(/0+$/, '').replace(/\.$/, '');
      setQtyMap(m => ({ ...m, [qtyKey]: s }));
      if (t < 1) qtyAnimRef.current = requestAnimationFrame(tick);
    };
    qtyAnimRef.current = requestAnimationFrame(tick);
  }, [quantity, qtyKey]);
  useEffect(() => () => cancelAnimationFrame(qtyAnimRef.current), []);
  const [limitPrice, setLimitPrice] = useState('');
  const [leverage, setLeverage] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const isFuturesMode = orderType === 'FUTURES';
  const tick = useCryptoStream(symbol, isFuturesMode ? 'futures' : 'spot');
  const isDark = useIsDark();

  // 合约专用状态
  const [futuresSide, setFuturesSide] = useState<'LONG' | 'SHORT'>('LONG');
  const [futuresLeverage, setFuturesLeverage] = useState(10);
  const [futuresOrderType, setFuturesOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [futuresPositions, setFuturesPositions] = useState<FuturesPosition[]>([]);
  const [positionsLoading, setPositionsLoading] = useState(false);
  const [futuresOrders, setFuturesOrders] = useState<FuturesOrder[]>([]);
  const [futuresOrderPage, setFuturesOrderPage] = useState(1);
  const [futuresOrderTotal, setFuturesOrderTotal] = useState(0);
  const [futuresOrderPages, setFuturesOrderPages] = useState(0);
  const [futuresOrderFilter, setFuturesOrderFilter] = useState('');
  const [futuresOrdersLoading, setFuturesOrdersLoading] = useState(false);
  const [futuresActionSuccess, setFuturesActionSuccess] = useState(false);
  const [spotActionSuccess, setSpotActionSuccess] = useState(false);
  const [openSlEnabled, setOpenSlEnabled] = useState(false);
  const [openSlRows, setOpenSlRows] = useState<SLTPRow[]>([{ price: '', quantity: '' }]);
  const [openTpEnabled, setOpenTpEnabled] = useState(false);
  const [openTpRows, setOpenTpRows] = useState<SLTPRow[]>([{ price: '', quantity: '' }]);
  const [posAction, setPosAction] = useState<{ id: number; type: 'close' | 'increase' | 'margin' | 'stoploss' } | null>(null);
  const [posCloseOrderType, setPosCloseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [posCloseLimitPrice, setPosCloseLimitPrice] = useState('');
  const [posCloseQty, setPosCloseQty] = useState('');
  const [posIncreaseOrderType, setPosIncreaseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [posIncreaseLimitPrice, setPosIncreaseLimitPrice] = useState('');
  const [posIncreaseQty, setPosIncreaseQty] = useState('');
  const [posMarginAmt, setPosMarginAmt] = useState('');
  const [posSlRows, setPosSlRows] = useState<SLTPRow[]>([]);
  const [posTpRows, setPosTpRows] = useState<SLTPRow[]>([]);

  // 订单列表状态
  const [orderFilter, setOrderFilter] = useState('');
  const [orders, setOrders] = useState<CryptoOrder[]>([]);
  const [orderPage, setOrderPage] = useState(1);
  const [orderTotal, setOrderTotal] = useState(0);
  const [orderPages, setOrderPages] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);

  // 折扣券状态
  const [discountBuff, setDiscountBuff] = useState<UserBuff | null>(null);
  const [useBuff, setUseBuff] = useState(false);

  // 持仓
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  const fetchPosition = useCallback(() => {
    cryptoOrderApi.position(symbol).then(setPosition).catch(() => setPosition(null));
  }, []);

  // 拉取K线（现货/合约分用不同数据源）
  const fetchKlines = useCallback(async (tabIdx: number) => {
    setLoading(true);
    try {
      const tab = TABS[tabIdx];
      const raw = orderType === 'FUTURES'
        ? await futuresApi.klines(symbol, tab.interval, tab.limit)
        : await cryptoApi.klines(symbol, tab.interval, tab.limit);
      const data = parseKlines(raw);
      if (tabIdx === 0) setPoints1D(data); else setPoints7D(data);
    } catch (e) {
      console.error('拉取K线失败', e);
      if (tabIdx === 0) setPoints1D([]); else setPoints7D([]);
    } finally {
      setLoading(false);
    }
  }, [symbol, orderType]);

  useEffect(() => { if (activeTab < TABS.length) fetchKlines(activeTab); }, [activeTab, fetchKlines]);

  // 切tab后 resize 当前图表（display:none → block 后尺寸需刷新）
  useEffect(() => {
    if (activeTab >= TABS.length) return;
    const inst = activeTab === 0 ? chartInst1D.current : chartInst7D.current;
    inst?.resize();
  }, [activeTab]);

  const livePrice = isFuturesMode ? (tick?.fp ?? tick?.price) : tick?.price;
  const liveTs = tick?.ts;

  // 实时价格追加到1D图表末端
  useEffect(() => {
    if (livePrice == null || liveTs == null || points1D.length === 0) return;
    setPoints1D(prev => {
      const last = prev[prev.length - 1];
      if (!last) return prev;
      const minuteStart = Math.floor(liveTs / 60000) * 60000;
      if (last.time === minuteStart) {
        return [...prev.slice(0, -1), { ...last, close: livePrice, high: Math.max(last.high, livePrice), low: Math.min(last.low, livePrice) }];
      }
      if (minuteStart > last.time) {
        return [...prev, { time: minuteStart, open: livePrice, high: livePrice, low: livePrice, close: livePrice }];
      }
      return prev;
    });
  }, [livePrice, liveTs, points1D.length]);

  // 图表渲染函数
  const chart1DReady = useRef(false);
  const chart7DReady = useRef(false);

  const renderChart = useCallback((
    container: HTMLDivElement | null,
    instRef: React.MutableRefObject<echarts.ECharts | null>,
    readyRef: React.MutableRefObject<boolean>,
    data: ChartPoint[],
    tab: TabConfig,
    withEffect: boolean,
  ) => {
    if (!container) return;
    if (!instRef.current) instRef.current = echarts.init(container, 'dark');
    const chart = instRef.current;
    const seriesData = data.map(p => [p.time, p.close]);
    const closes = data.map(p => p.close);
    if (closes.length === 0) { chart.clear(); readyRef.current = false; return; }
    const first = closes[0];
    const last = closes[closes.length - 1];
    const isUp = last >= first;
    const lineColor = isUp ? '#089981' : '#f23645';
    const areaStart = isUp ? 'rgba(8,153,129,0.12)' : 'rgba(242,54,69,0.12)';
    const areaEnd = isUp ? 'rgba(8,153,129,0.01)' : 'rgba(242,54,69,0.01)';

    if (!readyRef.current) {
      const now = Date.now();
      const isDay = tab.interval === '1m';
      chart.setOption({
        backgroundColor: 'transparent',
        grid: { left: 8, right: 8, top: 16, bottom: 32, containLabel: true },
        xAxis: { type: 'time', min: isDay ? now - 12 * 3600_000 : undefined, max: isDay ? now + 12 * 3600_000 : undefined, axisLine: { lineStyle: { color: '#333' } }, axisTick: { show: false }, axisLabel: { fontSize: 10, color: '#888', hideOverlap: true, formatter: (val: number) => formatTime(val, tab.interval) }, splitLine: { show: false } },
        yAxis: { type: 'value', scale: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { fontSize: 10, color: '#888', formatter: (v: number) => formatPrice(v) }, splitLine: { lineStyle: { color: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.12)', type: 'dashed' } } },
        series: [
          { type: 'line', data: seriesData, smooth: 0.2, showSymbol: false, lineStyle: { width: 1.5, color: lineColor }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: areaStart }, { offset: 1, color: areaEnd }]) } },
          ...(withEffect && seriesData.length > 0 ? [{ type: 'effectScatter', data: [seriesData[seriesData.length - 1]], symbolSize: 8, rippleEffect: { brushType: 'fill', scale: 5, period: 4, number: 2 }, itemStyle: { color: lineColor, shadowBlur: 6, shadowColor: lineColor }, z: 10 }] : []),
        ],
        tooltip: { trigger: 'axis', backgroundColor: 'rgba(20,20,20,0.9)', borderColor: '#333', textStyle: { color: '#fff', fontSize: 12 }, formatter: (params: unknown) => { const arr = params as { value: [number, number] }[]; const p = arr[0]; if (!p || p.value[1] == null) return ''; return `<div style="padding:4px 0"><div style="color:#888;margin-bottom:4px">${formatTime(p.value[0], tab.interval)}</div><div style="font-size:16px;font-weight:bold">$${formatPrice(p.value[1])}</div></div>`; } },
        dataZoom: [{ type: 'inside', start: 0, end: 100, minValueSpan: 20 }],
      });
      readyRef.current = true;
    } else {
      chart.setOption({
        series: [
          { data: seriesData, lineStyle: { color: lineColor }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: areaStart }, { offset: 1, color: areaEnd }]) } },
          ...(withEffect && seriesData.length > 0 ? [{ data: [seriesData[seriesData.length - 1]], itemStyle: { color: lineColor, shadowBlur: 6, shadowColor: lineColor } }] : []),
        ],
      });
    }
  }, []);

  // 1D 渲染
  useEffect(() => {
    renderChart(chartRef1D.current, chartInst1D, chart1DReady, points1D, TABS[0], true);
  }, [points1D, renderChart]);

  // 7D 渲染
  useEffect(() => {
    renderChart(chartRef7D.current, chartInst7D, chart7DReady, points7D, TABS[1], false);
  }, [points7D, renderChart]);

  // 窗口resize
  useEffect(() => {
    const onResize = () => {
      chartInst1D.current?.resize();
      chartInst7D.current?.resize();
    };
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chartInst1D.current?.dispose(); chartInst1D.current = null; chart1DReady.current = false;
      chartInst7D.current?.dispose(); chartInst7D.current = null; chart7DReady.current = false;
    };
  }, []);

  useEffect(() => {
    const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.12)';
    const opt = { yAxis: { splitLine: { lineStyle: { color: gridColor } } } };
    if (chart1DReady.current) chartInst1D.current?.setOption(opt);
    if (chart7DReady.current) chartInst7D.current?.setOption(opt);
  }, [isDark]);

  // 订单列表
  const fetchOrders = useCallback(async (status: string, page: number) => {
    setOrdersLoading(true);
    try {
      const res = await cryptoOrderApi.list(status || undefined, page, 10, symbol) as unknown as PageResult<CryptoOrder>;
      setOrders(res.records);
      setOrderTotal(res.total);
      setOrderPages(res.pages);
    } catch { setOrders([]); }
    finally { setOrdersLoading(false); }
  }, [symbol]);

  useEffect(() => { fetchOrders(orderFilter, orderPage); }, [orderFilter, orderPage, fetchOrders]);

  // 加载折扣券 + 持仓 + 合约仓位
  useEffect(() => {
    buffApi.status().then(s => {
      const b = s.todayBuff;
      if (b && b.buffType.startsWith('DISCOUNT_') && !b.isUsed) setDiscountBuff(b);
      else setDiscountBuff(null);
    }).catch(() => {});
    fetchPosition();
    if (orderType === 'FUTURES') fetchFuturesPositions();
  }, [fetchPosition, orderType]);

  // 合约仓位查询
  const fetchFuturesPositions = useCallback(async () => {
    setPositionsLoading(true);
    try {
      const positions = await futuresApi.positions(symbol);
      setFuturesPositions(positions);
    } catch (e) {
      console.error('查询合约仓位失败', e);
      setFuturesPositions([]);
    } finally {
      setPositionsLoading(false);
    }
  }, [symbol]);

  // 用 WS 推送的 markPrice 实时更新合约仓位盈亏
  useEffect(() => {
    if (!tick?.mp || futuresPositions.length === 0) return;
    const mp = tick.mp;
    setFuturesPositions(prev => prev.map(pos => {
      const posValue = mp * pos.quantity;
      const unrealizedPnl = pos.side === 'LONG'
        ? (mp - pos.entryPrice) * pos.quantity
        : (pos.entryPrice - mp) * pos.quantity;
      const effectiveMargin = pos.margin + unrealizedPnl;
      const unrealizedPnlPct = pos.margin > 0 ? (unrealizedPnl / pos.margin) * 100 : 0;
      return { ...pos, markPrice: mp, currentPrice: mp, positionValue: posValue, unrealizedPnl, unrealizedPnlPct, effectiveMargin };
    }));
  }, [tick?.mp]);

  // 合约订单查询
  const fetchFuturesOrders = useCallback(async (status: string, page: number) => {
    setFuturesOrdersLoading(true);
    try {
      const res = await futuresApi.orders(status || undefined, page, 10, symbol) as unknown as PageResult<FuturesOrder>;
      setFuturesOrders(res.records);
      setFuturesOrderTotal(res.total);
      setFuturesOrderPages(res.pages);
    } catch (e) {
      console.error('查询合约订单失败', e);
      setFuturesOrders([]);
    } finally {
      setFuturesOrdersLoading(false);
    }
  }, [symbol]);

  // 合约订单：filter/page/tab 变化时拉取
  useEffect(() => {
    if (orderType === 'FUTURES') fetchFuturesOrders(futuresOrderFilter, futuresOrderPage);
  }, [futuresOrderFilter, futuresOrderPage, orderType, fetchFuturesOrders]);

  useEffect(() => {
    if (!futuresActionSuccess) return;
    const timer = window.setTimeout(() => setFuturesActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [futuresActionSuccess]);

  useEffect(() => {
    if (!spotActionSuccess) return;
    const timer = window.setTimeout(() => setSpotActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [spotActionSuccess]);

  // 下单
  const handleSubmit = async () => {
    const qty = parseFloat(quantity);
    if (!qty || qty < MIN_QTY) { toast(`最小数量 ${MIN_QTY}`, 'error'); return; }

    // 合约下单
    if (orderType === 'FUTURES') {
      if (futuresOrderType === 'LIMIT') {
        const lp = parseFloat(limitPrice);
        if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
      }
      const orderQty = qty * futuresLeverage;
      const slItems: FuturesSLItem[] = openSlEnabled
        ? openSlRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0).map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }))
        : [];
      const tpItems: FuturesTPItem[] = openTpEnabled
        ? openTpRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0).map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }))
        : [];
      const slTotal = slItems.reduce((s, r) => s + r.quantity, 0);
      const tpTotal = tpItems.reduce((s, r) => s + r.quantity, 0);
      if (slTotal > orderQty + 1e-9) { toast('止损总量超过开仓数量', 'error'); return; }
      if (tpTotal > orderQty + 1e-9) { toast('止盈总量超过开仓数量', 'error'); return; }
      setSubmitting(true);
      try {
        await futuresApi.open({
          symbol,
          side: futuresSide,
          quantity: orderQty,
          leverage: futuresLeverage,
          orderType: futuresOrderType,
          ...(futuresOrderType === 'LIMIT' ? { limitPrice: parseFloat(limitPrice) } : {}),
          ...(slItems.length > 0 ? { stopLosses: slItems } : {}),
          ...(tpItems.length > 0 ? { takeProfits: tpItems } : {}),
        });
        setFuturesActionSuccess(true);
        if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
        toast(`${futuresSide === 'LONG' ? '做多' : '做空'}开仓成功`, 'success');
        setQuantity(String(MIN_QTY));
        setLimitPrice('');
        setOpenSlEnabled(false);
        setOpenSlRows([{ price: '', quantity: '' }]);
        setOpenTpEnabled(false);
        setOpenTpRows([{ price: '', quantity: '' }]);
        fetchFuturesPositions();
        fetchFuturesOrders('', 1);
        fetchUser();
      } catch (e: unknown) {
        toast((e as Error).message || '开仓失败', 'error');
      } finally { setSubmitting(false); }
      return;
    }

    // 现货下单
    if (orderType === 'LIMIT') {
      const lp = parseFloat(limitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    setSubmitting(true);
    try {
      const actualQty = side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? qty * leverage : qty;
      const req = {
        symbol: symbol,
        quantity: actualQty,
        orderType,
        ...(orderType === 'LIMIT' ? { limitPrice: parseFloat(limitPrice) } : {}),
        ...(side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? { leverageMultiple: leverage } : {}),
        ...(side === 'BUY' && orderType === 'MARKET' && useBuff && discountBuff ? { useBuffId: discountBuff.id } : {}),
      };
      if (side === 'BUY') {
        await cryptoOrderApi.buy(req);
        toast('买入成功', 'success');
      } else {
        await cryptoOrderApi.sell(req);
        toast('卖出成功', 'success');
      }
      setSpotActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      if (useBuff && discountBuff) { setDiscountBuff(null); setUseBuff(false); }
      setQuantity(String(MIN_QTY));
      setLimitPrice('');
      setLeverage(1);
      fetchOrders(orderFilter, 1);
      setOrderPage(1);
      fetchPosition();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '下单失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 取消订单
  const handleCancel = async (orderId: number) => {
    try {
      await cryptoOrderApi.cancel(orderId);
      toast('已取消', 'success');
      fetchOrders(orderFilter, orderPage);
    } catch (e: unknown) {
      toast((e as Error).message || '取消失败', 'error');
    }
  };

  // 合约平仓
  const handleFuturesClose = async (positionId: number) => {
    const qty = parseFloat(posCloseQty);
    if (!qty || qty <= 0) { toast('请输入平仓数量', 'error'); return; }
    if (posCloseOrderType === 'LIMIT') {
      const lp = parseFloat(posCloseLimitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    setSubmitting(true);
    try {
      await futuresApi.close({
        positionId, quantity: qty, orderType: posCloseOrderType,
        ...(posCloseOrderType === 'LIMIT' ? { limitPrice: parseFloat(posCloseLimitPrice) } : {}),
      });
      toast('平仓成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
      fetchFuturesOrders('', futuresOrderPage);
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '平仓失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 合约加仓
  const handleFuturesIncrease = async (positionId: number) => {
    const qty = parseFloat(posIncreaseQty);
    if (!qty || qty <= 0) { toast('请输入加仓数量', 'error'); return; }
    if (posIncreaseOrderType === 'LIMIT') {
      const lp = parseFloat(posIncreaseLimitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    setSubmitting(true);
    try {
      await futuresApi.increase({
        positionId, quantity: qty, orderType: posIncreaseOrderType,
        ...(posIncreaseOrderType === 'LIMIT' ? { limitPrice: parseFloat(posIncreaseLimitPrice) } : {}),
      });
      toast('加仓成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
      fetchFuturesOrders('', futuresOrderPage);
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '加仓失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 合约追加保证金
  const handleAddMargin = async (positionId: number) => {
    const amt = parseFloat(posMarginAmt);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.addMargin({ positionId, amount: amt });
      toast('追加保证金成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '追加保证金失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 合约设置止损
  const handleSetStopLoss = async (positionId: number) => {
    const items = posSlRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    setSubmitting(true);
    try {
      await futuresApi.setStopLoss({ positionId, stopLosses: items });
      toast('设置止损成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
    } catch (e: unknown) {
      toast((e as Error).message || '设置止损失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 合约设置止盈
  const handleSetTakeProfit = async (positionId: number) => {
    const items = posTpRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    setSubmitting(true);
    try {
      await futuresApi.setTakeProfit({ positionId, takeProfits: items });
      toast('设置止盈成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
    } catch (e: unknown) {
      toast((e as Error).message || '设置止盈失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 仓位操作切换
  const togglePosAction = (posId: number, type: typeof posAction extends null ? never : NonNullable<typeof posAction>['type'], pos?: FuturesPosition) => {
    if (posAction?.id === posId && posAction.type === type) {
      setPosAction(null);
    } else {
      setPosAction({ id: posId, type });
      setPosCloseQty(''); setPosCloseLimitPrice(''); setPosCloseOrderType('MARKET');
      setPosIncreaseQty(''); setPosIncreaseLimitPrice(''); setPosIncreaseOrderType('MARKET');
      setPosMarginAmt('');
      setPosSlRows(pos?.stopLosses?.map(s => ({ price: String(s.price), quantity: String(s.quantity) })) ?? [{ price: '', quantity: '' }]);
      setPosTpRows(pos?.takeProfits?.map(t => ({ price: String(t.price), quantity: String(t.quantity) })) ?? [{ price: '', quantity: '' }]);
    }
  };

  // 合约取消订单
  const handleFuturesCancel = async (orderId: number) => {
    try {
      await futuresApi.cancel(orderId);
      toast('已取消', 'success');
      fetchFuturesOrders('', futuresOrderPage);
    } catch (e: unknown) {
      toast((e as Error).message || '取消失败', 'error');
    }
  };

  // 计算涨跌
  const points = activeTab === 0 ? points1D : points7D;
  const currentPrice = livePrice ?? (points.length > 0 ? points[points.length - 1].close : 0);
  const firstPrice = points.length > 0 ? points[0].close : 0;
  const change = firstPrice ? currentPrice - firstPrice : 0;
  const changePct = firstPrice ? (change / firstPrice) * 100 : 0;
  const isUp = change >= 0;

  // 预估金额
  const qtyNum = parseFloat(quantity) || 0;
  const priceForCalc = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  const futuresPriceForCalc = futuresOrderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  const discountRate = useBuff && discountBuff && orderType === 'MARKET' ? Number(discountBuff.buffType.match(/DISCOUNT_(\d+)/)?.[1] ?? 100) / 100 : 1;
  const leveragedQty = side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? qtyNum * leverage : qtyNum;
  const estimatedAmount = leveragedQty * priceForCalc;
  const marginAmount = qtyNum * priceForCalc; // 保证金部分
  const estimatedCommission = estimatedAmount * COMMISSION_RATE;

  return (
    <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-8">
      {/* 顶部价格 */}
      <Card className="relative overflow-hidden mb-6">
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
                      ${formatPrice(currentPrice)}
                    </span>
                  </div>
                  {cfg.unitLabel && usdCny > 0 && currentPrice > 0 && (
                    <div className="text-sm text-warning font-mono font-bold mt-1">
                      ¥{(currentPrice * usdCny / cfg.unitFactor!).toFixed(2)}/{cfg.unitLabel}
                    </div>
                  )}
                  <div className={`flex items-center gap-1.5 text-sm font-bold mt-2 px-2.5 py-1 rounded-lg neu-flat ${isUp ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'}`}>
                    {isUp ? <TrendingUp className="w-4 h-4 stroke-[3px]" /> : <TrendingDown className="w-4 h-4 stroke-[3px]" />}
                    <span>{isUp ? '+' : ''}{formatPrice(change)}</span>
                    <span>({isUp ? '+' : ''}{changePct.toFixed(2)}%)</span>
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

      {/* 图表 */}
      <Card className="mb-6">
        <CardHeader className="pb-2 pt-5 px-5">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base font-black">走势</CardTitle>
            <div className="flex rounded-xl bg-card neu-raised-sm overflow-hidden">
              {TABS.map((tab, i) => (
                <button key={tab.label} onClick={() => setActiveTab(i)} className={`px-4 py-1.5 text-xs font-bold border-r border-border transition-colors ${activeTab === i ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                  {tab.label}
                </button>
              ))}
              <button onClick={() => setActiveTab(2)} className={`px-4 py-1.5 text-xs font-bold transition-colors ${activeTab === 2 ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                高级
              </button>
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-2 relative">
          {loading && activeTab < TABS.length && points.length === 0 && <Skeleton className="absolute inset-0 z-10 m-2" style={{ height: 300 }} />}
          <div ref={chartRef1D} className="w-full" style={{ height: 300, display: activeTab === 0 ? 'block' : 'none' }} />
          <div ref={chartRef7D} className="w-full" style={{ height: 300, display: activeTab === 1 ? 'block' : 'none' }} />
          {activeTab === 2 && <div style={{ height: 500 }}><TradingViewWidget symbol={orderType === 'FUTURES' ? cfg.futuresTvSymbol : cfg.tvSymbol} label={cfg.name} /></div>}
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

      {/* 交易面板 */}
      <Card className="mb-6">
        {/* 持仓信息 */}
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
                      {isPnlUp ? '+' : ''}${formatPrice(pnlAmount)}
                    </div>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs font-bold text-muted-foreground pt-1">
                  <span>均价 <span className="text-foreground font-mono">${formatPrice(position.avgCost)}</span></span>
                  <span>现价 <span className="text-foreground font-mono">${formatPrice(currentPrice)}</span></span>
                  <span>市值 <span className="text-foreground font-mono">${formatPrice(currentPrice * position.quantity)}</span></span>
                  {position.frozenQuantity > 0 && <span>冻结 <span className="text-warning font-mono">{position.frozenQuantity}</span></span>}
                  {position.totalDiscount > 0 && <span>已省 <span className="text-warning font-mono">${formatPrice(position.totalDiscount)}</span></span>}
                </div>
              </div>
            </div>
          );
        })()}

        {/* 买卖/做多做空 + 市价限价合约 */}
        <div className="px-5 pt-5 flex flex-wrap items-center gap-3">
          <div className="flex flex-1 min-w-[140px] rounded-xl bg-card overflow-hidden neu-raised">
            {orderType === 'FUTURES' ? (
              <>
                <button onClick={() => setFuturesSide('LONG')} className={`flex-1 py-2.5 text-sm font-black border-r border-border transition-colors ${futuresSide === 'LONG' ? 'bg-gain text-white' : 'bg-card text-foreground hover:bg-surface-hover'}`}>做多</button>
                <button onClick={() => setFuturesSide('SHORT')} className={`flex-1 py-2.5 text-sm font-black transition-colors ${futuresSide === 'SHORT' ? 'bg-loss text-white' : 'bg-card text-foreground hover:bg-surface-hover'}`}>做空</button>
              </>
            ) : (
              <>
                <button onClick={() => setSide('BUY')} className={`flex-1 py-2.5 text-sm font-black border-r border-border transition-colors ${side === 'BUY' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>买入</button>
                <button onClick={() => setSide('SELL')} className={`flex-1 py-2.5 text-sm font-black transition-colors ${side === 'SELL' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>卖出</button>
              </>
            )}
          </div>
          <div className="flex rounded-xl bg-card overflow-hidden neu-raised">
            {(['FUTURES', 'MARKET', 'LIMIT'] as const).map((t, idx) => (
              <button key={t} onClick={() => setOrderType(t)} className={`px-4 py-2.5 text-xs font-bold transition-colors ${idx !== 2 ? 'border-r border-border' : ''} ${orderType === t ? 'bg-foreground text-background' : 'bg-card text-foreground hover:bg-surface-hover'}`}>
                {t === 'MARKET' ? '市价' : t === 'LIMIT' ? '限价' : '合约'}
              </button>
            ))}
          </div>
          <Link
            to="/force-orders"
            className="px-3 sm:px-4 py-2 sm:py-2.5 rounded-xl bg-red-500/8 neu-raised text-xs font-black text-red-500 border border-red-500/15 hover:bg-red-500/12 transition-colors flex items-center gap-1.5"
          >
            <Flame className="w-3.5 h-3.5" />
            爆仓
          </Link>
        </div>

        <CardContent className="p-5 space-y-6 mt-2">
          {/* 合约面板 */}
          {orderType === 'FUTURES' && (
            <>
              {/* 执行方式 */}
              <div className="flex items-center gap-2">
                <div className="flex rounded-lg overflow-hidden neu-raised-sm">
                  <button onClick={() => setFuturesOrderType('MARKET')} className={`px-4 py-1.5 text-xs font-bold transition-colors ${futuresOrderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>市价</button>
                  <button onClick={() => setFuturesOrderType('LIMIT')} className={`px-4 py-1.5 text-xs font-bold border-l border-border transition-colors ${futuresOrderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>限价</button>
                </div>
              </div>

              {/* 限价输入 */}
              {futuresOrderType === 'LIMIT' && (
                <div className="space-y-1.5">
                  <label className="text-xs text-muted-foreground">限价 (USDT)</label>
                  <Input type="number" placeholder="输入限价" value={limitPrice} onChange={e => setLimitPrice(e.target.value)} step="0.01" min="0" />
                  {parseFloat(limitPrice) > 0 && currentPrice > 0 && (
                    (futuresSide === 'LONG' && parseFloat(limitPrice) >= currentPrice) ||
                    (futuresSide === 'SHORT' && parseFloat(limitPrice) <= currentPrice)
                  ) && (
                    <div className="text-[10px] text-yellow-500">限价{futuresSide === 'LONG' ? '≥' : '≤'}当前价，将立即以市价成交</div>
                  )}
                </div>
              )}

              {/* 杠杆选择 */}
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <label className="text-xs text-muted-foreground flex items-center gap-1.5 font-medium">
                    <Scale className="w-3.5 h-3.5" /> 杠杆
                  </label>
                  <div className="px-2 py-0.5 rounded bg-primary/10 text-primary text-xs font-bold tabular-nums">{futuresLeverage}x</div>
                </div>
                <div className="flex gap-1.5">
                  {[1, 10, 25, 50, 100, 250].map(lv => (
                    <button key={lv} onClick={() => setFuturesLeverage(lv)} className={`flex-1 py-1.5 text-xs font-medium rounded-md transition-all border ${futuresLeverage === lv ? 'bg-primary border-primary text-primary-foreground shadow-sm' : 'bg-transparent border-border/60 text-muted-foreground hover:text-foreground hover:border-border'}`}>
                      {lv}x
                    </button>
                  ))}
                </div>
                <div className="pt-1">
                  <input
                    type="range" min={1} max={250}
                    value={futuresLeverage}
                    onChange={e => setFuturesLeverage(Number(e.target.value))}
                    className="w-full h-1.5 bg-muted rounded-full appearance-none cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-4 [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:bg-primary [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:shadow-md transition-all"
                  />
                </div>
              </div>

              {/* 数量输入 */}
              <div className="flex flex-col sm:flex-row gap-3 sm:items-end">
                {/* 左半边：保证金输入框 */}
                <div className="flex-1">
                  <div className="relative">
                    <Input type="number" placeholder={String(MIN_QTY)} value={quantity} onChange={e => setQuantity(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} className="pr-24" />
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground pointer-events-none">保证金 {cfg.name}</span>
                  </div>
                </div>

                {/* 右半边：余额和百分比按钮 */}
                <div className="flex-1 space-y-1.5">
                  {user && (
                    <div className="flex items-center justify-end gap-1 text-xs text-muted-foreground">
                      <Wallet className="w-3 h-3" />
                      {formatPrice(user.balance)} USDT
                    </div>
                  )}
                  {futuresPriceForCalc > 0 && (
                    <div className="flex gap-1.5">
                      {POSITION_PCTS.map(pct => {
                        const handlePct = () => {
                          const balance = user?.balance ?? 0;
                          const qty = calcMaxAffordableMarginQty(balance, pct, futuresPriceForCalc, futuresLeverage, MIN_QTY);
                          if (qty > 0) animateQuantity(qty, MIN_QTY); else setQuantity('');
                        };
                        return (
                          <Button key={pct} size="sm" variant="outline" className="h-7 text-[11px] flex-1 min-w-15" onClick={handlePct}>
                            {pct * 100}%
                          </Button>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>

              {/* 预估信息 */}
              {parseFloat(quantity) > 0 && futuresPriceForCalc > 0 && (() => {
                const qty = parseFloat(quantity);
                const { positionValue, margin, commission, totalCost } = calcFuturesOpenEstimate(qty, futuresPriceForCalc, futuresLeverage);
                return (
                  <div className="space-y-1.5 p-3 rounded-lg bg-accent/30 border border-border/50">
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">仓位价值</span>
                      <span className="font-mono">${formatPrice(positionValue)}</span>
                    </div>
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">保证金</span>
                      <span className="font-mono">${formatPrice(margin)}</span>
                    </div>
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">手续费 (0.04%)</span>
                      <span className="font-mono">${formatPrice(commission)}</span>
                    </div>
                    <div className="flex justify-between text-xs font-medium pt-1.5 border-t border-border/50">
                      <span className="text-muted-foreground">合计需要</span>
                      <span>${formatPrice(totalCost)}</span>
                    </div>
                  </div>
                );
              })()}

              {/* 开仓止损/止盈 */}
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-muted-foreground flex items-center gap-1">止损 <HelpTip text="标记价格触及止损价时自动平仓对应数量，可设多档分批止损" /></label>
                    <button type="button" onClick={() => { setOpenSlEnabled(!openSlEnabled); setOpenSlRows([{ price: '', quantity: '' }]); }}
                      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${openSlEnabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}>
                      <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform shadow-sm ${openSlEnabled ? 'translate-x-4.5' : 'translate-x-0.75'}`} />
                    </button>
                  </div>
                  {openSlEnabled && (
                    <SLTPEditor rows={openSlRows} onChange={setOpenSlRows} label="止损" posQty={qtyNum * futuresLeverage} minQty={MIN_QTY}
                      currentPrice={futuresPriceForCalc || currentPrice} side={futuresSide}
                      liquidationPrice={estimateLiqPrice(futuresSide, futuresPriceForCalc || currentPrice, futuresLeverage)} />
                  )}
                </div>
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-muted-foreground flex items-center gap-1">止盈 <HelpTip text="现价触及止盈价时自动平仓对应数量，可设多档分批止盈" /></label>
                    <button type="button" onClick={() => { setOpenTpEnabled(!openTpEnabled); setOpenTpRows([{ price: '', quantity: '' }]); }}
                      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${openTpEnabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}>
                      <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform shadow-sm ${openTpEnabled ? 'translate-x-4.5' : 'translate-x-0.75'}`} />
                    </button>
                  </div>
                  {openTpEnabled && (
                    <SLTPEditor rows={openTpRows} onChange={setOpenTpRows} label="止盈" posQty={qtyNum * futuresLeverage} minQty={MIN_QTY}
                      currentPrice={futuresPriceForCalc || currentPrice} side={futuresSide} />
                  )}
                </div>
              </div>

              {/* 开仓按钮 */}
              <FuturesActionButton
                className="mt-2"
                onClick={handleSubmit}
                disabled={submitting || currentPrice <= 0}
                loading={submitting}
                success={futuresActionSuccess}
                side={futuresSide}
                leverage={futuresLeverage}
              />
            </>
          )}

          {/* 现货面板（原有逻辑） */}
          {orderType !== 'FUTURES' && (
            <>
          {/* 限价输入 */}
          {orderType === 'LIMIT' && (
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-muted-foreground">限价 (USDT)</label>
              <Input type="number" placeholder="输入限价" value={limitPrice} onChange={e => setLimitPrice(e.target.value)} step="0.01" min="0" />
            </div>
          )}

          {/* 数量 + 余额 */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-muted-foreground">数量 ({cfg.name})</label>
              {user && (
                <span className="text-xs font-bold text-muted-foreground flex items-center gap-1">
                  <Wallet className="w-3.5 h-3.5" />
                  {side === 'BUY'
                    ? <>{formatPrice(user.balance)} USDT</>
                    : <>{position?.quantity ?? 0} {cfg.name}</>
                  }
                </span>
              )}
            </div>
            <Input type="number" placeholder={String(MIN_QTY)} value={quantity} onChange={e => setQuantity(e.target.value)} step="0.001" min={MIN_QTY} />
            {currentPrice > 0 && (
              <div className="space-y-1.5 pt-1">
                <label className="text-xs font-bold text-muted-foreground flex items-center gap-1.5">
                  <Warehouse className="w-3.5 h-3.5" /> 仓位
                </label>
                <div className="flex gap-1.5">
                  {POSITION_PCTS.map(pct => {
                  const handlePct = () => {
                    let raw: number;
                    if (side === 'BUY') {
                      const balance = user?.balance ?? 0;
                      const lv = orderType === 'MARKET' ? leverage : 1;
                      raw = (balance * pct) / (currentPrice * (1 + COMMISSION_RATE * lv));
                    } else {
                      raw = (position?.quantity ?? 0) * pct;
                    }
                    const qty = Math.max(MIN_QTY, Math.floor(raw / MIN_QTY) * MIN_QTY);
                    const target = qty <= MIN_QTY && raw < MIN_QTY ? MIN_QTY : qty;
                    animateQuantity(target, MIN_QTY);
                  };
                  return (
                    <Button key={pct} onClick={handlePct} variant="outline" size="sm" className="h-11 text-[11px] font-black flex-1 min-w-15">
                      {pct * 100}%
                    </Button>
                  );
                })}
                </div>
              </div>
            )}
          </div>

          {/* 杠杆 - 仅市价买入 */}
          {side === 'BUY' && orderType === 'MARKET' && (
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-muted-foreground flex items-center gap-1.5">
                <Scale className="w-3.5 h-3.5" /> 杠杆{useBuff ? ' (使用折扣时不支持)' : ''}
              </label>
              <div className={useBuff ? 'opacity-40 pointer-events-none' : ''}>
                <select
                  value={leverage}
                  onChange={e => setLeverage(Number(e.target.value))}
                  className="w-full h-11 rounded-xl bg-background neu-inset px-4 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-ring appearance-none cursor-pointer"
                  style={{ backgroundImage: `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23888' stroke-width='2.5'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E")`, backgroundRepeat: 'no-repeat', backgroundPosition: 'right 12px center' }}
                >
                  {LEVERAGE_OPTIONS.map(lv => (
                    <option key={lv} value={lv}>{lv}x{lv === 1 ? ' (无杠杆)' : ''}</option>
                  ))}
                </select>
              </div>
            </div>
          )}

          {/* 折扣券 - 仅市价买入且有可用券 */}
          {side === 'BUY' && orderType === 'MARKET' && discountBuff && (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label className="text-xs font-bold text-muted-foreground flex items-center gap-1">
                  <Sparkles className="w-3.5 h-3.5 text-warning" />
                  折扣券{leverage > 1 ? ' (使用杠杆时不支持)' : ''}
                </label>
                <button
                  type="button"
                  onClick={() => { if (leverage > 1) return; setUseBuff(v => !v); }}
                  disabled={leverage > 1}
                  className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${useBuff ? 'bg-warning' : 'bg-muted-foreground/30'} ${leverage > 1 ? 'opacity-40 cursor-not-allowed' : ''}`}
                >
                  <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform ${useBuff ? 'translate-x-5' : 'translate-x-1'}`} />
                </button>
              </div>
              {useBuff && (
                <div className="flex items-center gap-2 text-xs bg-warning/10 border-2 border-warning/50 rounded-xl px-3 py-2">
                  <Badge variant="warning" className="text-[10px] px-2">{discountBuff.buffName}</Badge>
                  <span className="text-muted-foreground font-bold">本次买入整单折扣</span>
                </div>
              )}
            </div>
          )}

          {/* 预估 + 提交 */}
          <div className="pt-4 border-t border-border border-dashed space-y-4">
            {qtyNum > 0 && priceForCalc > 0 && (
              <div className="space-y-2">
                {side === 'BUY' && leverage > 1 && (
                  <div className="flex justify-between text-xs font-bold text-muted-foreground">
                    <span>总仓位 ({leverage}x)</span>
                    <span className="font-mono text-foreground">${formatPrice(estimatedAmount)} USDT</span>
                  </div>
                )}
                <div className="flex justify-between text-xs font-bold text-muted-foreground">
                  <span>{side === 'BUY' && leverage > 1 ? '保证金' : `预估${side === 'BUY' ? '花费' : '收入'}`}</span>
                  <span className="text-foreground">
                    {useBuff && discountRate < 1 && side === 'BUY' && (
                      <span className="line-through text-muted-foreground mr-1.5">${formatPrice(marginAmount)}</span>
                    )}
                    ${formatPrice(marginAmount * discountRate)} USDT
                  </span>
                </div>
                <div className="flex justify-between text-xs font-bold text-muted-foreground">
                  <span>手续费 (0.1%)</span>
                  <span className="font-mono">${formatPrice(estimatedCommission * discountRate)} USDT</span>
                </div>
                {side === 'BUY' && (
                  <div className="flex justify-between text-sm font-black pt-1">
                    <span className="text-muted-foreground">合计</span>
                    <span className="text-foreground">
                      ${formatPrice((marginAmount + estimatedCommission) * discountRate)} USDT
                    </span>
                  </div>
                )}
              </div>
            )}
            <FuturesActionButton
              className="mt-2"
              onClick={handleSubmit}
              disabled={submitting || currentPrice <= 0}
              loading={submitting}
              success={spotActionSuccess}
              side={side}
              label={cfg.name}
            />
          </div>
            </>
          )}
        </CardContent>
      </Card>

      {/* 合约持仓（独立Card） */}
      {orderType === 'FUTURES' && futuresPositions.length > 0 && (
        <Card className="mb-6">
          <CardHeader className="pb-4 pt-5 px-5">
            <div className="flex items-center justify-between">
              <CardTitle className="text-base font-black flex items-center gap-2">
                当前持仓
                <HelpTip text="仅显示当前币种活跃仓位，盈亏基于标记价实时计算" />
              </CardTitle>
              <button onClick={fetchFuturesPositions} disabled={positionsLoading} className="p-1 rounded-md hover:bg-muted transition-colors disabled:opacity-50">
                  <RefreshCw className={`w-3.5 h-3.5 ${positionsLoading ? 'animate-spin' : ''}`} />
                </button>
            </div>
          </CardHeader>
          <CardContent className="space-y-5 px-5 pb-5 pt-0">
            {futuresPositions.map(pos => {
              const isPnlUp = pos.unrealizedPnl >= 0;
              const isLong = pos.side === 'LONG';
              const isActive = posAction?.id === pos.id;
              return (
                <div key={pos.id} className={`p-4 rounded-2xl bg-card neu-raised space-y-3 transition-all relative overflow-hidden`}>
                  <div className={`absolute top-0 bottom-0 left-0 w-2.5 border-r border-border ${isLong ? 'bg-gain' : 'bg-loss'}`} />
                  <div className="flex items-center justify-between pl-3">
                    <div className="flex items-center gap-2.5">
                      <Badge variant={isLong ? 'success' : 'destructive'} className="text-[10px] px-2 py-0.5">{isLong ? '做多' : '做空'}</Badge>
                      <span className="text-base font-black">{pos.leverage}x</span>
                      <span className="text-sm font-bold text-muted-foreground">{pos.quantity} {cfg.name}</span>
                    </div>
                    <div className="text-right">
                      <div className={`text-sm font-bold ${isPnlUp ? 'text-green-500' : 'text-red-500'}`}>
                        {isPnlUp ? '+' : ''}{pos.unrealizedPnlPct.toFixed(2)}%
                      </div>
                      <div className={`text-xs ${isPnlUp ? 'text-green-500/70' : 'text-red-500/70'}`}>
                        {isPnlUp ? '+' : ''}${formatPrice(pos.unrealizedPnl)}
                      </div>
                    </div>
                  </div>
                  {/* 价格轴 */}
                  <PositionPriceBar pos={pos} currentPrice={currentPrice} />
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
                    <div>开仓 <span className="text-foreground font-mono">${formatPrice(pos.entryPrice)}</span></div>
                    <div>强平 <span className="text-yellow-500 font-mono">${formatPrice(pos.liquidationPrice)}</span></div>
                    <div>保证金 <span className="text-foreground font-mono">${formatPrice(pos.margin)}</span></div>
                    <div>资金费 <span className="font-mono">${formatPrice(pos.fundingFeeTotal)}</span></div>
                  </div>
                  {/* 操作按钮 */}
                  <div className="flex flex-wrap gap-1.5 pt-1">
                    <Button size="sm" variant={isActive && posAction.type === 'close' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'close', pos)}>平仓</Button>
                    <Button size="sm" variant={isActive && posAction.type === 'increase' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'increase', pos)}>加仓</Button>
                    <Button size="sm" variant={isActive && posAction.type === 'margin' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'margin', pos)}>+保证金</Button>
                    <Button size="sm" variant={isActive && posAction.type === 'stoploss' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'stoploss', pos)}>止损/盈</Button>
                  </div>
                  {/* 内联操作面板 */}
                  {isActive && (
                    <div className="pt-2 mt-1 border-t border-border/30 space-y-2">
                      {/* 平仓 */}
                      {posAction.type === 'close' && (
                        <>
                          <div className="flex items-center gap-2">
                            <span className="text-xs text-muted-foreground shrink-0">执行</span>
                            <div className="flex rounded-md border border-border overflow-hidden">
                              <button onClick={() => setPosCloseOrderType('MARKET')} className={`px-3 py-1 text-[11px] font-medium transition-all ${posCloseOrderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>市价</button>
                              <button onClick={() => setPosCloseOrderType('LIMIT')} className={`px-3 py-1 text-[11px] font-medium transition-all border-l border-border ${posCloseOrderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>限价</button>
                            </div>
                          </div>
                          {posCloseOrderType === 'LIMIT' && (
                            <>
                              <Input type="number" placeholder="限价 (USDT)" value={posCloseLimitPrice} onChange={e => setPosCloseLimitPrice(e.target.value)} step="0.01" className="h-8 text-xs" />
                              {parseFloat(posCloseLimitPrice) > 0 && currentPrice > 0 && (
                                (pos.side === 'LONG' && parseFloat(posCloseLimitPrice) <= currentPrice) ||
                                (pos.side === 'SHORT' && parseFloat(posCloseLimitPrice) >= currentPrice)
                              ) && (
                                <div className="text-[10px] text-yellow-500">限价{pos.side === 'LONG' ? '≤' : '≥'}当前价，将立即以市价成交</div>
                              )}
                            </>
                          )}
                          <div className="flex items-center gap-2">
                            <Input type="number" placeholder="平仓数量" value={posCloseQty} onChange={e => setPosCloseQty(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={pos.quantity} className="flex-1 h-8 text-xs" />
                            <span className="text-[11px] text-muted-foreground shrink-0">/ {pos.quantity}</span>
                          </div>
                          <div className="flex gap-1">
                            {POSITION_PCTS.map(pct => (
                              <button key={pct} onClick={() => {
                                const raw = pos.quantity * pct;
                                setPosCloseQty(pct === 1 ? String(pos.quantity) : (Math.round(raw / MIN_QTY) * MIN_QTY).toFixed(8).replace(/0+$/, '').replace(/\.$/, ''));
                              }} className="flex-1 py-1 rounded text-[11px] font-medium border border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent transition-all">
                                {pct * 100}%
                              </button>
                            ))}
                          </div>
                          <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleFuturesClose(pos.id)} disabled={submitting}>
                            {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认平仓'}
                          </Button>
                        </>
                      )}
                      {/* 加仓 */}
                      {posAction.type === 'increase' && (() => {
                        const incPrice = posIncreaseOrderType === 'LIMIT' && parseFloat(posIncreaseLimitPrice) > 0 ? parseFloat(posIncreaseLimitPrice) : currentPrice;
                        const maxIncQty = user ? calcMaxIncreaseQty(user.balance, incPrice, pos.leverage, MIN_QTY) : 0;
                        return (
                        <>
                          <div className="flex items-center gap-2">
                            <span className="text-xs text-muted-foreground shrink-0">执行</span>
                            <div className="flex rounded-md border border-border overflow-hidden">
                              <button onClick={() => setPosIncreaseOrderType('MARKET')} className={`px-3 py-1 text-[11px] font-medium transition-all ${posIncreaseOrderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>市价</button>
                              <button onClick={() => setPosIncreaseOrderType('LIMIT')} className={`px-3 py-1 text-[11px] font-medium transition-all border-l border-border ${posIncreaseOrderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>限价</button>
                            </div>
                          </div>
                          {posIncreaseOrderType === 'LIMIT' && (
                            <>
                              <Input type="number" placeholder="限价 (USDT)" value={posIncreaseLimitPrice} onChange={e => setPosIncreaseLimitPrice(e.target.value)} step="0.01" className="h-8 text-xs" />
                              {parseFloat(posIncreaseLimitPrice) > 0 && currentPrice > 0 && (
                                (pos.side === 'LONG' && parseFloat(posIncreaseLimitPrice) >= currentPrice) ||
                                (pos.side === 'SHORT' && parseFloat(posIncreaseLimitPrice) <= currentPrice)
                              ) && (
                                <div className="text-[10px] text-yellow-500">限价{pos.side === 'LONG' ? '≥' : '≤'}当前价，将立即以市价成交</div>
                              )}
                            </>
                          )}
                          <div className="flex items-center gap-2">
                            <Input type="number" placeholder={`${MIN_QTY} - ${maxIncQty > 0 ? maxIncQty : '0'}`} value={posIncreaseQty} onChange={e => setPosIncreaseQty(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={maxIncQty > 0 ? maxIncQty : undefined} className="flex-1 h-8 text-xs" />
                            {user && <span className="text-[11px] text-muted-foreground shrink-0">余额 {formatPrice(user.balance)}</span>}
                          </div>
                          {parseFloat(posIncreaseQty) > 0 && incPrice > 0 && (() => {
                            const iq = parseFloat(posIncreaseQty);
                            const val = roundHalfUp2(incPrice * iq);
                            const mg = roundCeil2(val / pos.leverage);
                            const cm = roundHalfUp2(val * FUTURES_COMMISSION_RATE);
                            return (
                              <div className="grid grid-cols-2 gap-x-4 gap-y-0.5 text-[11px] text-muted-foreground">
                                <div>杠杆前数量 <span className="text-foreground font-mono">{(iq / pos.leverage).toFixed(8).replace(/0+$/, '').replace(/\.$/, '')}</span></div>
                                <div>仓位价值 <span className="text-foreground font-mono">${formatPrice(val)}</span></div>
                                <div>保证金 <span className="text-foreground font-mono">${formatPrice(mg)}</span></div>
                                <div>手续费 <span className="text-foreground font-mono">${formatPrice(cm)}</span></div>
                                <div className="col-span-2">需支付 <span className="text-foreground font-mono font-semibold">${formatPrice(mg + cm)}</span></div>
                              </div>
                            );
                          })()}
                          <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleFuturesIncrease(pos.id)} disabled={submitting}>
                            {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认加仓'}
                          </Button>
                        </>
                        );
                      })()}
                      {/* +保证金 */}
                      {posAction.type === 'margin' && (
                        <>
                          <Input type="number" placeholder="追加金额 (USDT)" value={posMarginAmt} onChange={e => setPosMarginAmt(e.target.value)} step="0.01" min="0" className="h-8 text-xs" />
                          {user && <div className="text-[11px] text-muted-foreground">可用余额 {formatPrice(user.balance)} USDT</div>}
                          <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleAddMargin(pos.id)} disabled={submitting}>
                            {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认追加'}
                          </Button>
                        </>
                      )}
                      {/* 止损/止盈 */}
                      {posAction.type === 'stoploss' && (
                        <div className="grid grid-cols-2 gap-3">
                          <div className="space-y-2">
                            <div className="text-xs text-muted-foreground flex items-center gap-1">
                              止损 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                            </div>
                            <SLTPEditor rows={posSlRows} onChange={setPosSlRows} label="止损" posQty={pos.quantity} minQty={MIN_QTY}
                              currentPrice={currentPrice} side={pos.side} liquidationPrice={pos.liquidationPrice} />
                            <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleSetStopLoss(pos.id)} disabled={submitting}>
                              {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止损'}
                            </Button>
                          </div>
                          <div className="space-y-2">
                            <div className="text-xs text-muted-foreground flex items-center gap-1">
                              止盈 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                            </div>
                            <SLTPEditor rows={posTpRows} onChange={setPosTpRows} label="止盈" posQty={pos.quantity} minQty={MIN_QTY}
                              currentPrice={currentPrice} side={pos.side} liquidationPrice={pos.liquidationPrice} />
                            <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleSetTakeProfit(pos.id)} disabled={submitting}>
                              {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止盈'}
                            </Button>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </CardContent>
        </Card>
      )}

      {/* 订单列表 */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base flex items-center gap-2">
              {orderType === 'FUTURES' ? '合约订单' : '订单'}
              <button onClick={() => orderType === 'FUTURES' ? fetchFuturesOrders(futuresOrderFilter, futuresOrderPage) : fetchOrders(orderFilter, orderPage)} className="text-muted-foreground hover:text-foreground transition-colors" title="刷新">
                <RefreshCw className={`w-3.5 h-3.5 ${(orderType === 'FUTURES' ? futuresOrdersLoading : ordersLoading) ? 'animate-spin' : ''}`} />
              </button>
            </CardTitle>
            <div className="flex gap-1">
              {orderType === 'FUTURES' ? (
                FUTURES_ORDER_FILTERS.map(f => (
                  <Button key={f.value} variant={futuresOrderFilter === f.value ? 'default' : 'ghost'} size="sm" className="h-7 px-2.5 text-xs" onClick={() => { setFuturesOrderFilter(f.value); setFuturesOrderPage(1); }}>
                    {f.label}
                  </Button>
                ))
              ) : (
                ORDER_STATUS_FILTERS.map(f => (
                  <Button key={f.value} variant={orderFilter === f.value ? 'default' : 'ghost'} size="sm" className="h-7 px-2.5 text-xs" onClick={() => { setOrderFilter(f.value); setOrderPage(1); }}>
                    {f.label}
                  </Button>
                ))
              )}
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {orderType === 'FUTURES' ? (
            /* 合约订单表 */
            futuresOrdersLoading && futuresOrders.length === 0 ? (
              <div className="p-4"><Skeleton className="w-full h-32" /></div>
            ) : futuresOrders.length === 0 ? (
              <div className="p-8 text-center text-sm text-muted-foreground">暂无合约订单</div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-border/50 text-muted-foreground">
                        <th className="text-left px-4 py-2.5 font-medium">时间</th>
                        <th className="text-left px-2 py-2.5 font-medium">方向</th>
                        <th className="text-left px-2 py-2.5 font-medium">类型</th>
                        <th className="text-right px-2 py-2.5 font-medium">数量</th>
                        <th className="text-right px-2 py-2.5 font-medium">杠杆</th>
                        <th className="text-right px-2 py-2.5 font-medium">限价</th>
                        <th className="text-right px-2 py-2.5 font-medium">成交价</th>
                        <th className="text-right px-2 py-2.5 font-medium">盈亏</th>
                        <th className="text-center px-2 py-2.5 font-medium">状态</th>
                        <th className="text-center px-4 py-2.5 font-medium">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {futuresOrders.map(o => {
                        const sm = FUTURES_SIDE_MAP[o.orderSide] || { label: o.orderSide, color: 'text-foreground' };
                        const st = STATUS_MAP[o.status] || { label: o.status, variant: 'outline' as const };
                        const hasPnl = o.realizedPnl != null && o.realizedPnl !== 0;
                        return (
                          <tr key={o.orderId} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                            <td className="px-4 py-2.5 text-muted-foreground whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                            <td className={`px-2 py-2.5 font-medium ${sm.color}`}>{sm.label}</td>
                            <td className="px-2 py-2.5">{o.orderType === 'MARKET' ? '市价' : '限价'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.quantity}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.leverage}x</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.limitPrice != null ? formatPrice(o.limitPrice) : '-'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.filledPrice != null ? formatPrice(o.filledPrice) : '-'}</td>
                            <td className={`px-2 py-2.5 text-right font-mono ${hasPnl ? (o.realizedPnl! > 0 ? 'text-green-500' : 'text-red-500') : ''}`}>
                              {hasPnl ? `${o.realizedPnl! > 0 ? '+' : ''}${formatPrice(o.realizedPnl!)}` : '-'}
                            </td>
                            <td className="px-2 py-2.5 text-center"><Badge variant={st.variant}>{st.label}</Badge></td>
                            <td className="px-4 py-2.5 text-center">
                              {o.status === 'PENDING' ? (
                                <button onClick={() => handleFuturesCancel(o.orderId)} className="text-muted-foreground hover:text-red-500 transition-colors" title="取消"><X className="w-3.5 h-3.5 inline" /></button>
                              ) : <span className="text-muted-foreground/30">-</span>}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                {futuresOrderPages > 1 && (
                  <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                    <span className="text-xs text-muted-foreground">共 {futuresOrderTotal} 条</span>
                    <div className="flex items-center gap-1">
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={futuresOrderPage <= 1} onClick={() => setFuturesOrderPage(p => p - 1)}>
                        <ChevronLeft className="w-3.5 h-3.5" />
                      </Button>
                      <span className="text-xs px-2">{futuresOrderPage} / {futuresOrderPages}</span>
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={futuresOrderPage >= futuresOrderPages} onClick={() => setFuturesOrderPage(p => p + 1)}>
                        <ChevronRight className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )
          ) : (
            /* 现货订单表 */
            ordersLoading && orders.length === 0 ? (
              <div className="p-4"><Skeleton className="w-full h-32" /></div>
            ) : orders.length === 0 ? (
              <div className="p-8 text-center text-sm text-muted-foreground">暂无订单</div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-border/50 text-muted-foreground">
                        <th className="text-left px-4 py-2.5 font-medium">时间</th>
                        <th className="text-left px-2 py-2.5 font-medium">方向</th>
                        <th className="text-left px-2 py-2.5 font-medium">类型</th>
                        <th className="text-right px-2 py-2.5 font-medium">数量</th>
                        <th className="text-right px-2 py-2.5 font-medium">挂单价</th>
                        <th className="text-right px-2 py-2.5 font-medium">触发价</th>
                        <th className="text-right px-2 py-2.5 font-medium">金额</th>
                        <th className="text-center px-2 py-2.5 font-medium">状态</th>
                        <th className="text-center px-4 py-2.5 font-medium">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {orders.map(o => {
                        const isBuy = o.orderSide === 'BUY';
                        const st = STATUS_MAP[o.status] || { label: o.status, variant: 'outline' as const };
                        return (
                          <tr key={o.orderId} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                            <td className="px-4 py-2.5 text-muted-foreground whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                            <td className={`px-2 py-2.5 font-medium ${isBuy ? 'text-green-500' : 'text-red-500'}`}>{isBuy ? '买入' : '卖出'}</td>
                            <td className="px-2 py-2.5">{o.orderType === 'MARKET' ? '市价' : '限价'}{o.leverage > 1 ? ` ${o.leverage}x` : ''}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.quantity}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.limitPrice != null ? formatPrice(o.limitPrice) : '-'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.triggerPrice != null ? formatPrice(o.triggerPrice) : '-'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.filledAmount != null ? formatPrice(o.filledAmount) : '-'}</td>
                            <td className="px-2 py-2.5 text-center"><Badge variant={st.variant}>{st.label}</Badge></td>
                            <td className="px-4 py-2.5 text-center">
                              {o.status === 'PENDING' ? (
                                <button onClick={() => handleCancel(o.orderId)} className="text-muted-foreground hover:text-red-500 transition-colors" title="取消"><X className="w-3.5 h-3.5 inline" /></button>
                              ) : <span className="text-muted-foreground/30">-</span>}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                {orderPages > 1 && (
                  <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                    <span className="text-xs text-muted-foreground">共 {orderTotal} 条</span>
                    <div className="flex items-center gap-1">
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={orderPage <= 1} onClick={() => setOrderPage(p => p - 1)}>
                        <ChevronLeft className="w-3.5 h-3.5" />
                      </Button>
                      <span className="text-xs px-2">{orderPage} / {orderPages}</span>
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={orderPage >= orderPages} onClick={() => setOrderPage(p => p + 1)}>
                        <ChevronRight className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function HelpTip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [open]);
  return (
    <div ref={ref} className="relative inline-flex">
      <button type="button" onClick={() => setOpen(!open)} className="text-muted-foreground hover:text-foreground transition-colors">
        <HelpCircle className="w-3.5 h-3.5" />
      </button>
      {open && (
        <div className="absolute left-1/2 -translate-x-1/2 top-full mt-2 z-50 w-52 p-2.5 rounded-lg border bg-card text-xs text-muted-foreground shadow-lg leading-relaxed">
          {text}
        </div>
      )}
    </div>
  );
}

function SLTPEditor({ rows, onChange, label, posQty, minQty, currentPrice, side, liquidationPrice }: {
  rows: SLTPRow[];
  onChange: (rows: SLTPRow[]) => void;
  label: string;
  posQty: number;
  minQty: number;
  currentPrice: number;
  side: 'LONG' | 'SHORT';
  liquidationPrice?: number;
}) {
  const isSL = label === '止损';
  const dragging = useRef(false);
  // TP LONG / SL SHORT: ascending; TP SHORT / SL LONG: descending
  const ascending = (!isSL && side === 'LONG') || (isSL && side === 'SHORT');

  let rangeMin: number, rangeMax: number;
  if (isSL) {
    if (side === 'LONG') {
      rangeMin = Math.max(0.01, liquidationPrice ?? currentPrice * 0.5);
      rangeMax = currentPrice * 0.999;
    } else {
      rangeMin = currentPrice * 1.001;
      rangeMax = liquidationPrice ?? currentPrice * 1.5;
    }
  } else {
    if (side === 'LONG') {
      rangeMin = currentPrice * 1.001;
      rangeMax = currentPrice * 1.2;
    } else {
      rangeMin = Math.max(0.01, currentPrice * 0.8);
      rangeMax = currentPrice * 0.999;
    }
  }

  const priceStep = currentPrice >= 10000 ? 10 : currentPrice >= 1000 ? 1 : currentPrice >= 100 ? 0.1 : 0.01;
  rangeMin = Math.round(rangeMin / priceStep) * priceStep;
  rangeMax = Math.round(rangeMax / priceStep) * priceStep;

  const getRowRange = (i: number): [number, number] => {
    let rMin = rangeMin, rMax = rangeMax;
    if (i > 0) {
      const prev = parseFloat(rows[i - 1].price) || 0;
      if (prev > 0) {
        if (ascending) rMin = Math.max(rMin, prev + priceStep);
        else rMax = Math.min(rMax, prev - priceStep);
      }
    }
    return [rMin, rMax];
  };

  const total = rows.reduce((s, r) => s + (parseFloat(r.quantity) || 0), 0);
  const over = total > posQty + 1e-9;
  const showSlider = currentPrice > 0 && rangeMin < rangeMax;

  return (
    <div className="space-y-2 p-2.5 rounded-lg bg-accent/30 border border-border/50">
      {rows.map((row, i) => {
        const [rowMin, rowMax] = getRowRange(i);
        const priceVal = parseFloat(row.price) || 0;
        const sliderVal = priceVal >= rowMin && priceVal <= rowMax ? priceVal : ascending ? rowMin : rowMax;
        return (
          <div key={i} className="space-y-1">
            <div className="flex items-center gap-1.5">
              <span className="text-[10px] text-muted-foreground w-3 shrink-0">{i + 1}</span>
              <Input type="number" placeholder={`${label}价`} value={row.price}
                onChange={e => { const n = [...rows]; n[i] = { ...n[i], price: e.target.value }; onChange(n); }}
                step="0.01" className="flex-1 h-8 text-xs" />
              <Input type="number" placeholder="数量" value={row.quantity}
                onChange={e => { const n = [...rows]; n[i] = { ...n[i], quantity: e.target.value }; onChange(n); }}
                step={String(minQty)} className="w-24 h-8 text-xs" />
              {rows.length > 1 && (
                <button type="button" onClick={() => onChange(rows.filter((_, j) => j !== i))} className="text-muted-foreground hover:text-red-500 transition-colors shrink-0">
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            {showSlider && rowMin < rowMax && (
              <div className="flex items-center gap-1">
                <span className="text-[9px] text-muted-foreground shrink-0 font-mono">
                  {isSL && ((side === 'LONG' && i === 0) || (side === 'SHORT' && i === rows.length - 1)) ? '强平' : formatPrice(rowMin)}
                </span>
                <input
                  type="range" min={rowMin} max={rowMax} step={priceStep} value={sliderVal}
                  onPointerDown={() => { dragging.current = true; }}
                  onPointerUp={() => { dragging.current = false; }}
                  onChange={e => {
                    if (!dragging.current) return;
                    const val = Number(e.target.value);
                    const n = [...rows];
                    n[i] = { ...n[i], price: val % 1 === 0 ? String(val) : val.toFixed(2) };
                    onChange(n);
                  }}
                  className="flex-1 h-1.5 cursor-pointer"
                  style={{ accentColor: isSL ? '#eab308' : '#3b82f6' }}
                />
                <span className="text-[9px] text-muted-foreground shrink-0 font-mono">{formatPrice(rowMax)}</span>
              </div>
            )}
          </div>
        );
      })}
      <div className="flex items-center justify-between">
        {rows.length < 4 ? (
          <button type="button" onClick={() => onChange([...rows, { price: '', quantity: '' }])} className="text-xs text-primary hover:underline flex items-center gap-0.5">
            <Plus className="w-3 h-3" /> 添加
          </button>
        ) : <span />}
        <span className={`text-[11px] ${over ? 'text-red-500' : 'text-muted-foreground'}`}>
          已分配 {total > 0 ? total.toFixed(8).replace(/0+$/, '').replace(/\.$/, '') : '0'} / {posQty}
        </span>
      </div>
    </div>
  );
}

function PositionPriceBar({ pos, currentPrice }: { pos: FuturesPosition; currentPrice: number }) {
  const sls = pos.stopLosses?.map(s => s.price) ?? [];
  const tps = pos.takeProfits?.map(t => t.price) ?? [];
  const all = [pos.entryPrice, pos.markPrice, currentPrice, pos.liquidationPrice, ...sls, ...tps].filter(p => p > 0);
  if (all.length < 2) return null;

  const mn = Math.min(...all), mx = Math.max(...all);
  const pad = (mx - mn) * 0.08 || 1;
  const lo = mn - pad, hi = mx + pad;
  const pct = (p: number) => Math.max(0.5, Math.min(99.5, ((p - lo) / (hi - lo)) * 100));

  type Row = { label: string; price: number; color: string; anim?: boolean };
  const rows: Row[] = [
    { label: '强平', price: pos.liquidationPrice, color: '#ef4444' },
    ...sls.map((p, i) => ({ label: `止损${sls.length > 1 ? i + 1 : ''}`, price: p, color: '#eab308' })),
    { label: '开仓', price: pos.entryPrice, color: '#94a3b8' },
    { label: '标记', price: pos.markPrice, color: '#a78bfa', anim: true },
    { label: '现价', price: currentPrice, color: '#60a5fa', anim: true },
    ...tps.map((p, i) => ({ label: `止盈${tps.length > 1 ? i + 1 : ''}`, price: p, color: '#34d399' })),
  ].filter(r => r.price > 0);

  return (
    <div className="mt-1.5 space-y-0.75">
      {rows.map((r, i) => (
        <div key={i} className="flex items-center gap-1.5 h-4.5">
          <span className="text-[10px] w-7 shrink-0 text-right font-medium" style={{ color: r.color }}>{r.label}</span>
          <div className="flex-1 relative h-1.5 rounded-full bg-muted-foreground/10 overflow-hidden">
            <div
              className="absolute left-0 top-0 h-full rounded-full"
              style={{
                width: `${pct(r.price)}%`,
                backgroundColor: r.color,
                opacity: r.anim ? 0.7 : 0.45,
                transition: r.anim ? 'width 0.3s ease' : undefined,
                boxShadow: r.anim ? `0 0 6px ${r.color}40` : undefined,
              }}
            />
          </div>
          <span className={`text-[10px] font-mono shrink-0 tabular-nums ${r.anim ? 'font-semibold' : ''}`} style={{ color: r.color, minWidth: 58, textAlign: 'right' }}>
            {r.price >= 1000 ? formatPrice(r.price) : r.price.toFixed(2)}
          </span>
        </div>
      ))}
    </div>
  );
}
