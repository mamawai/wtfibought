import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router-dom';
import { Check, ChevronDown, CircleAlert, RefreshCcw, TriangleAlert } from 'lucide-react';
import { testnetApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Seg } from '../components/ui/seg';
import { useSubmitFx } from '../hooks/useSubmitFx';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDate, fmtDateTime, fmtNum, fmtSignedUsd } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { EquityChart } from '../components/EquityChart';
import { MonthGrid } from '../components/MonthGrid';
import type {
  TnOverview, TnTrade, TnDailyCell, TnEquityPoint, TnFillStats, TnPosition, TnOpenOrder,
} from '../types/testnet';
import './TestnetMonitor.css';

type SymbolFilter = 'ALL' | 'BTCUSDT' | 'ETHUSDT';
type ManualSymbol = 'BTCUSDT' | 'ETHUSDT';

/** 金额：上万的压成 12.3K */
function fmt$(n?: number | null, compact = false) {
  if (compact && n != null && Number.isFinite(n) && Math.abs(n) >= 1e4) return (n / 1e3).toFixed(1) + 'K';
  return fmtNum(n);
}
function fmtDuration(sec: number) {
  if (!sec || sec <= 0) return '-';
  if (sec < 60) return sec.toFixed(0) + 's';
  if (sec < 3600) return (sec / 60).toFixed(1) + 'm';
  return (sec / 3600).toFixed(1) + 'h';
}
const coin = (symbol: string) => symbol.replace('USDT', '');

/** 一个指标：名字、一个大数、一句小字。tone 给大数上涨跌色 */
function Stat({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'up' | 'dn' }) {
  return (
    <div>
      <span>{label}</span>
      <b className={cn('nf', tone)}>{value}</b>
      {sub && <small>{sub}</small>}
    </div>
  );
}

/** 一个持仓一块：币、方向、数量 @ 开仓价，右边浮动盈亏；下面标记价和强平价 */
function PositionBlock({ p }: { p: TnPosition }) {
  const { t } = useTranslation('strategy');
  return (
    <div className="blk">
      <div className="pos-h">
        <b>{coin(p.symbol)}</b>
        <span className={cn('tag sm', p.side === 'LONG' ? 'g' : 'r')}>{p.side}</span>
        <span>{p.positionAmt} @ {formatCoinPrice(p.symbol, p.entryPrice)}</span>
        <b className={cn('nf', p.unrealizedProfit >= 0 ? 'up' : 'dn')}>{fmtSignedUsd(p.unrealizedProfit)}</b>
      </div>
      <div className="kv">
        <div><span>{t('testnet.pos.mark')}</span><b>{formatCoinPrice(p.symbol, p.markPrice)}</b></div>
        <div><span>{t('testnet.pos.liq')}</span><b className="dn">{formatCoinPrice(p.symbol, p.liquidationPrice)}</b></div>
      </div>
    </div>
  );
}

/** 一条挂单：币、是进场还是止损止盈、买卖方向、挂在什么价 */
function OpenOrderRow({ o }: { o: TnOpenOrder }) {
  const { t } = useTranslation('strategy');
  const isEntry = o.type === 'LIMIT';
  return (
    <div>
      <b>{coin(o.symbol)}</b>
      <span className={cn('tag sm', isEntry ? 'o' : 'w')}>
        {isEntry ? t('testnet.order.entry') : o.type === 'STOP_MARKET' ? t('testnet.order.stopLoss') : t('testnet.order.takeProfit')}
      </span>
      <span className={o.side === 'BUY' ? 'up' : 'dn'}>{o.side}</span>
      <span className="end">@ {formatCoinPrice(o.symbol, isEntry ? o.price : o.stopPrice)}</span>
    </div>
  );
}

/** 一笔成交：时间、币、方向、是挂单成交还是吃单、价格 × 数量，行尾是这笔平仓的盈亏和手续费 */
function TradeRow({ trade }: { trade: TnTrade }) {
  return (
    <div>
      <span className="tm">{fmtDateTime(trade.time)}</span>
      <b>{coin(trade.symbol)}</b>
      <span className={trade.side === 'BUY' ? 'up' : 'dn'}>{trade.side}</span>
      <span className={cn('tag sm', !trade.maker && 'w')}>{trade.maker ? 'MAKER' : 'TAKER'}</span>
      <span className="px">{formatCoinPrice(trade.symbol, trade.price)} × {trade.qty}</span>
      <span className="end">
        {trade.realizedPnl !== 0 && <b className={trade.realizedPnl > 0 ? 'up' : 'dn'}>{fmtSignedUsd(trade.realizedPnl)}</b>}
        <em>{fmt$(trade.commission)}</em>
      </span>
    </div>
  );
}

/**
 * 手动交易（接口自检用，后端再验一遍管理员）：平时收着，点开是选币 / 方向 / 市价限价、填数量和杠杆，
 * 三颗钮——下单、市价平仓、撤挂单；下面一条是刚才那一下的结果
 */
function ManualTradePanel({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation(['strategy', 'portfolio', 'trade']);
  const { toast } = useToast();
  const fx = useSubmitFx();
  const [open, setOpen] = useState(false);
  const [symbol, setSymbol] = useState<ManualSymbol>('BTCUSDT');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [type, setType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [quantity, setQuantity] = useState('0.002');
  const [price, setPrice] = useState('');
  const [leverage, setLeverage] = useState('20');
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  // 跑一个请求：成了把返回的摘要摆出来并刷新看板，没成摆错误原文（Binance / 权限 / 校验的原话）
  const run = (key: string, fn: () => Promise<string>) => {
    void fx.run(key, async () => {
      setResult(null);
      setResult({ ok: true, text: await fn() });
      onDone();
    }, e => {
      const msg = (e as Error).message || t('testnet.manual.opFailed');
      setResult({ ok: false, text: msg });
      toast(msg, 'error');
    });
  };

  const submitOrder = () => {
    const qty = Number(quantity);
    if (!qty || qty <= 0) { toast(t('testnet.manual.qtyPositive'), 'error'); return fx.nope('order'); }
    if (type === 'LIMIT' && (!Number(price) || Number(price) <= 0)) { toast(t('testnet.manual.priceRequired'), 'error'); return fx.nope('order'); }
    run('order', async () => {
      const r = await testnetApi.manualOrder({
        symbol, side, type, quantity: qty,
        price: type === 'LIMIT' ? Number(price) : undefined,
        leverage: leverage ? Number(leverage) : undefined,
      });
      return t('testnet.manual.orderResult', {
        id: r.orderId, status: r.status, at: r.avgPrice ? ` @ ${r.avgPrice}` : '',
        side: r.side, type: r.type, qty: r.origQty,
      });
    });
  };

  const submitClose = () => run('close', async () => {
    const r = await testnetApi.manualClose(symbol);
    return t('testnet.manual.closeResult', {
      id: r.orderId, status: r.status, at: r.avgPrice ? ` @ ${r.avgPrice}` : '',
    });
  });

  const submitCancelAll = () => run('cancel', async () => {
    await testnetApi.manualCancelAll(symbol);
    return t('testnet.manual.cancelResult', { symbol });
  });

  /** 别的钮在转圈或打勾时，这颗先别点 */
  const off = (key: string) => fx.locked && !fx.cls(key);

  return (
    <section className="sec">
      <div className="sec-h">
        <h2>{t('testnet.manual.title')}<small>{t('testnet.manual.subtitle')}</small></h2>
        <button type="button" className={cn('xb fold', open && 'on')} aria-expanded={open} aria-label={t('testnet.manual.title')}
                onClick={() => setOpen(o => !o)}>
          <ChevronDown className="ic" />
        </button>
      </div>
      <div className={cn('col', open && 'open')}>
        <div>
          <div className="manual">
            <div className="segs">
              <Seg value={symbol} onChange={setSymbol} label={t('portfolio:field.symbol')}
                   options={[{ value: 'BTCUSDT', label: 'BTC' }, { value: 'ETHUSDT', label: 'ETH' }]} />
              <Seg value={side} onChange={setSide} label={t('portfolio:field.side')}
                   options={[{ value: 'BUY', label: t('testnet.manual.buy') }, { value: 'SELL', label: t('testnet.manual.sell') }]} />
              <Seg value={type} onChange={setType} label={t('trade:orderType.label')}
                   options={[{ value: 'MARKET', label: t('testnet.manual.market') }, { value: 'LIMIT', label: t('testnet.manual.limit') }]} />
            </div>
            <div className="gen">
              <div className="fld">
                <label className="fl" htmlFor="tn-qty"><span>{t('testnet.manual.qty')}</span></label>
                <label className="inp sm">
                  <input id="tn-qty" value={quantity} onChange={e => setQuantity(e.target.value)} inputMode="decimal" placeholder="0.002" />
                </label>
              </div>
              {type === 'LIMIT' && (
                <div className="fld">
                  <label className="fl" htmlFor="tn-price"><span>{t('testnet.manual.price')}</span></label>
                  <label className="inp sm">
                    <input id="tn-price" value={price} onChange={e => setPrice(e.target.value)} inputMode="decimal" placeholder={t('testnet.manual.pricePh')} />
                  </label>
                </div>
              )}
              <div className="fld">
                <label className="fl" htmlFor="tn-lev"><span>{t('testnet.manual.leverage')}</span></label>
                <label className="inp sm">
                  <input id="tn-lev" value={leverage} onChange={e => setLeverage(e.target.value)} inputMode="numeric" placeholder="20" />
                </label>
              </div>
            </div>
            <div className="acts">
              <button type="button" className={cn('btn ink', fx.cls('order'))} disabled={off('order')} onClick={submitOrder}>
                <span>{t('testnet.manual.submit')}</span>
              </button>
              <button type="button" className={cn('btn risk', fx.cls('close'))} disabled={off('close')} onClick={submitClose}>
                <span>{t('testnet.manual.closeMarket')}</span>
              </button>
              <button type="button" className={cn('btn', fx.cls('cancel'))} disabled={off('cancel')} onClick={submitCancelAll}>
                <span>{t('testnet.manual.cancelAll')}</span>
              </button>
            </div>
            {result && (
              <div className={cn('note', !result.ok && 'r')}>
                {result.ok ? <Check className="ic" /> : <CircleAlert className="ic" />}
                <div>{result.text}</div>
              </div>
            )}
            <p className="fhint">{t('testnet.manual.note')}</p>
          </div>
        </div>
      </div>
    </section>
  );
}

/** 只给管理员；user 为 null 是 fetchUser 还没回来，先不渲染（同 Admin 页） */
export function TestnetMonitor() {
  const user = useUserStore((s) => s.user);
  if (!user) return null;
  if (user.id !== 1) return <Navigate to="/" replace />;
  return <TestnetMonitorPage />;
}

/**
 * Binance Testnet 那条执行轨的看板（样式见 TestnetMonitor.css）：
 * 四个指标 → 权益曲线和盈亏月历（点某天看当天成交）→ 当前持仓和挂单 → fill 对账 → 近 30 天成交 → 最底下手动交易自检。
 */
function TestnetMonitorPage() {
  const { t } = useTranslation(['strategy', 'common', 'portfolio']);
  const { toast } = useToast();
  const [overview, setOverview] = useState<TnOverview | null>(null);
  const [trades, setTrades] = useState<TnTrade[]>([]);          // 近 30 天：成交列表、胜率
  const [gridTrades, setGridTrades] = useState<TnTrade[]>([]);  // 近 90 天：跟月历同跨度，点格子下钻用
  const [daily, setDaily] = useState<TnDailyCell[]>([]);
  const [equity, setEquity] = useState<TnEquityPoint[]>([]);
  const [fill, setFill] = useState<TnFillStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [symbol, setSymbol] = useState<SymbolFilter>('ALL');
  const [selectedDate, setSelectedDate] = useState<string | undefined>();
  // 月历的月份受控。用户没翻过就跟着数据走（落在最新有成交的那个月），翻过就听用户的
  const [pickedMonth, setPickedMonth] = useState<string | null>(null);

  // 请求序号：切币种/刷新/定时器可能叠着发，只认最后一次
  const loadSeqRef = useRef(0);
  const load = useCallback(async () => {
    const seq = ++loadSeqRef.current;
    setLoading(true);
    try {
      const sym = symbol === 'ALL' ? undefined : symbol;
      const [ov, tr, dg, eq, fs] = await Promise.all([
        testnetApi.overview(),
        testnetApi.trades(sym, 90),
        testnetApi.dailyGrid(sym, 90),
        testnetApi.equity(sym, 90),
        testnetApi.fillStats(sym, 30),
      ]);
      if (seq !== loadSeqRef.current) return;
      const since = Date.now() - 30 * 86_400_000;
      setOverview(ov); setGridTrades(tr); setTrades(tr.filter((x) => x.time >= since));
      setDaily(dg); setEquity(eq); setFill(fs);
    } catch (e) {
      if (seq !== loadSeqRef.current) return;
      toast((e as Error).message || t('common:loadFailed'), 'error');
    } finally {
      if (seq === loadSeqRef.current) setLoading(false);
    }
  }, [symbol, toast, t]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { const id = setInterval(load, 300_000); return () => clearInterval(id); }, [load]);

  // 派生指标（全部来自 testnet 原始数据）
  const cumPnl = equity.length ? equity[equity.length - 1].cumPnl : 0;
  const closed = useMemo(() => trades.filter((x) => x.realizedPnl !== 0), [trades]);
  const wins = useMemo(() => closed.filter((x) => x.realizedPnl > 0).length, [closed]);
  const winRate = closed.length ? (wins / closed.length) * 100 : 0;
  const fillRatePct = fill ? fill.fillRate * 100 : 0;
  const equityNow = overview?.account.marginBalance;

  const gridMonth = useMemo(() => {
    if (pickedMonth) return pickedMonth;
    const months = daily.map((c) => c.date.slice(0, 7)).sort();
    return months.length ? months[months.length - 1] : fmtDate().slice(0, 7);
  }, [pickedMonth, daily]);

  // 选中某天的成交（东八区切日，与月历一致）
  const dayTrades = useMemo(
    () => (selectedDate ? gridTrades.filter((x) => fmtDate(x.time) === selectedDate) : []),
    [selectedDate, gridTrades],
  );

  const isEmpty = !loading && trades.length === 0 && equity.length === 0 && (overview?.positions.length ?? 0) === 0
    && equityNow == null;

  return (
    <div className="page-shell p-tn">
      <div className="pg-h">
        <div>
          <h1>{t('testnet.title')}</h1>
          <p className="d">{t('testnet.subtitle')}</p>
        </div>
        <div className="r">
          <Seg value={symbol} onChange={setSymbol} label={t('portfolio:field.symbol')}
               options={[{ value: 'ALL', label: t('common:all') }, { value: 'BTCUSDT', label: 'BTC' }, { value: 'ETHUSDT', label: 'ETH' }]} />
          <button type="button" className="ib" aria-label={t('common:refresh')} title={t('common:refresh')}
                  onClick={() => { void load(); toast(t('testnet.refreshed'), 'info'); }}>
            <RefreshCcw className={cn('ic', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      {/* 执行目标已经切到 sim：说清这页是停更的历史，跟「策略账户」页对不上是正常的 */}
      {overview?.executionTarget === 'sim' && (
        <div className="note w">
          <TriangleAlert className="ic" />
          <div>
            <b>{t('testnet.banner.title')}</b>{t('testnet.banner.body')}
            {' '}<Link to="/strategies" className="linkb">{t('testnet.banner.link')}</Link>
          </div>
        </div>
      )}

      {isEmpty && (
        <div className="empty">
          <b>{t('testnet.empty.title')}</b>
          <span>{t('testnet.empty.hint')}</span>
        </div>
      )}

      <div className="stats">
        <Stat label={t('testnet.stat.equity')} value={`₮${fmt$(equityNow, true)}`}
              sub={t('testnet.stat.available', { value: fmt$(overview?.account.availableBalance, true) })} />
        <Stat label={t('testnet.stat.cumPnl')} value={fmtSignedUsd(cumPnl)} sub={t('testnet.stat.cumPnlSub')} tone={cumPnl >= 0 ? 'up' : 'dn'} />
        <Stat label={t('testnet.stat.fillRate')} value={fill ? `${fillRatePct.toFixed(1)}%` : '-'}
              sub={fill ? t('testnet.stat.fillRateSub', { filled: fill.filled, placed: fill.placed, expired: fill.expired })
                : t('testnet.stat.fillRateHint')}
              tone={fill ? (fillRatePct >= 50 ? 'up' : 'dn') : undefined} />
        <Stat label={t('testnet.stat.winRate')} value={closed.length ? `${winRate.toFixed(1)}%` : '-'}
              sub={t('testnet.stat.winRateSub', { wins, total: closed.length })}
              tone={closed.length ? (winRate >= 50 ? 'up' : 'dn') : undefined} />
      </div>

      {/* 权益曲线和盈亏月历并排；点月历的某一天，下面摊开当天成交 */}
      <div className="two">
        <section className="sec">
          <div className="sec-h"><h2>{t('testnet.equity.title')}<small>{t('testnet.equity.hint')}</small></h2></div>
          {equity.length > 0 ? <EquityChart points={equity} height={264} /> : <div className="empty"><span>{t('testnet.trades.empty')}</span></div>}
        </section>
        <section className="sec">
          <MonthGrid cells={daily} month={gridMonth} onMonthChange={setPickedMonth}
                     selectedDate={selectedDate} onSelectDate={setSelectedDate} title={t('testnet.grid.title')} />
          <p className="fhint">{t('testnet.grid.hint')}</p>
        </section>
      </div>
      {selectedDate && (
        <div className="blk day">
          <div className="day-h">
            <b>{t('testnet.grid.dayTrades', { date: selectedDate, n: dayTrades.length })}</b>
            <button type="button" className="linkb" onClick={() => setSelectedDate(undefined)}>{t('common:collapse')}</button>
          </div>
          {dayTrades.length > 0
            ? <div className="rows">{dayTrades.map(x => <TradeRow key={x.id} trade={x} />)}</div>
            : <div className="empty"><span>{t('testnet.grid.empty')}</span></div>}
        </div>
      )}

      {overview && overview.positions.length > 0 && (
        <section className="sec">
          <div className="sec-h"><h2>{t('testnet.positions.title')}<small>{overview.positions.length}</small></h2></div>
          <div className="poss">{overview.positions.map(p => <PositionBlock key={p.symbol} p={p} />)}</div>
        </section>
      )}

      {overview && overview.openOrders.length > 0 && (
        <section className="sec">
          <div className="sec-h"><h2>{t('testnet.orders.title')}<small>{overview.openOrders.length}</small></h2></div>
          <div className="rows">{overview.openOrders.map(o => <OpenOrderRow key={o.orderId} o={o} />)}</div>
        </section>
      )}

      {/* fill 对账：挂出去的进场单到底成交了多少、多久成交、成交价是不是挂单价 */}
      {fill && fill.placed > 0 && (
        <section className="sec">
          <div className="sec-h"><h2>{t('testnet.fill.title')}<small>{t('testnet.fill.hint')}</small></h2></div>
          <div className="stats">
            <Stat label={t('testnet.fill.rate')} value={`${fillRatePct.toFixed(1)}%`} sub={`${fill.filled}/${fill.placed}`}
                  tone={fillRatePct >= 50 ? 'up' : 'dn'} />
            <Stat label={t('testnet.fill.expired')} value={`${fill.expired}`} sub={t('testnet.fill.expiredSub')} />
            <Stat label={t('testnet.fill.avgTime')} value={fmtDuration(fill.avgFillSeconds)} sub={t('testnet.fill.avgTimeSub')} />
            <Stat label={t('testnet.fill.maker')} value={`${fill.makerConfirmed}/${fill.filled}`} sub={t('testnet.fill.makerSub')}
                  tone={fill.makerConfirmed === fill.filled && fill.filled > 0 ? 'up' : undefined} />
          </div>
          <div className="note">{t('testnet.fill.note')}</div>
        </section>
      )}

      <section className="sec">
        <div className="sec-h"><h2>{t('testnet.trades.title')}<small>{t('testnet.trades.hint')}</small></h2></div>
        {loading && trades.length === 0 ? (
          <div className="skr">{Array.from({ length: 3 }, (_, i) => <span key={i} className="sk" />)}</div>
        ) : trades.length === 0 ? (
          <div className="empty"><span>{t('testnet.trades.empty')}</span></div>
        ) : (
          <div className="rows">{trades.slice(0, 50).map(x => <TradeRow key={x.id} trade={x} />)}</div>
        )}
      </section>

      <ManualTradePanel onDone={load} />
    </div>
  );
}
