import { useState, useEffect, useCallback, useMemo, useRef, type ElementType, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { testnetApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDate, fmtDateTime, fmtNum, fmtSignedUsd } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { EquityChart } from '../components/EquityChart';
import { DailyGrid } from '../components/DailyGrid';
import {
  Activity, RefreshCcw, Wallet, TrendingUp, BarChart3, Target,
  Clock, CheckCircle2, ListChecks, Layers, Gauge, Wrench, ChevronDown,
} from 'lucide-react';
import type {
  TnOverview, TnTrade, TnDailyCell, TnEquityPoint, TnFillStats, TnPosition, TnOpenOrder,
} from '../types/testnet';

const SYMBOLS = ['ALL', 'BTCUSDT', 'ETHUSDT'] as const;

/* ========== 格式化（与 AiTrader 同口径） ========== */
function fmt$(n?: number | null, compact = false) {
  if (compact && n != null && Number.isFinite(n) && Math.abs(n) >= 1e4) return (n / 1e3).toFixed(1) + 'K';
  return fmtNum(n);
}
/** 毫秒时刻 → 东八区 yyyy-MM-dd（与后端 dailyGrid 切日口径一致）。 */
function cnDate(ms: number) {
  return new Date(ms).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
}
function fmtDuration(sec: number) {
  if (!sec || sec <= 0) return '-';
  if (sec < 60) return sec.toFixed(0) + 's';
  if (sec < 3600) return (sec / 60).toFixed(1) + 'm';
  return (sec / 3600).toFixed(1) + 'h';
}

/* ========== Stat Card ========== */
function StatCard({ label, value, sub, icon: Icon, trend }: {
  label: string; value: string; sub?: string; icon: ElementType; trend?: 'up' | 'down' | 'neutral';
}) {
  return (
    <div className="pt-card rounded-lg p-4 flex flex-col gap-1.5">
      <div className="flex items-center gap-2 text-xs text-muted-foreground font-medium">
        <div className={cn('w-7 h-7 rounded-lg flex items-center justify-center',
          trend === 'up' && 'bg-gain/10 text-gain',
          trend === 'down' && 'bg-loss/10 text-loss',
          (!trend || trend === 'neutral') && 'bg-primary/10 text-primary')}>
          <Icon className="w-3.5 h-3.5" />
        </div>
        {label}
      </div>
      <div className={cn('text-xl font-black tabular-nums tracking-tight',
        trend === 'up' && 'text-gain', trend === 'down' && 'text-loss')}>{value}</div>
      {sub && <div className="text-[11px] text-muted-foreground leading-tight">{sub}</div>}
    </div>
  );
}

/* ========== Position Card ========== */
function PositionCard({ p }: { p: TnPosition }) {
  const { t } = useTranslation('strategy');
  const isLong = p.side === 'LONG';
  const pnlUp = p.unrealizedProfit >= 0;
  const px = (v?: number | null) => formatCoinPrice(p.symbol, v);
  return (
    <div className={cn('pt-card rounded-lg p-4', isLong ? 'border-l-3 border-l-gain' : 'border-l-3 border-l-loss')}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className={cn('w-9 h-9 rounded-lg flex items-center justify-center text-xs font-black',
            isLong ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss')}>
            {p.symbol.replace('USDT', '')}
          </div>
          <div>
            <span className={cn('text-[10px] font-black px-1.5 py-0.5 rounded',
              isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
              {p.side}
            </span>
            <div className="text-[11px] text-muted-foreground mt-0.5">{p.positionAmt} @ {px(p.entryPrice)}</div>
          </div>
        </div>
        <div className={cn('text-lg font-black tabular-nums', pnlUp ? 'text-gain' : 'text-loss')}>
          {pnlUp ? '+' : ''}{fmt$(p.unrealizedProfit)}
        </div>
      </div>
      <div className="grid grid-cols-3 gap-2">
        {[
          { label: t('testnet.pos.mark'), value: px(p.markPrice) },
          { label: t('testnet.pos.liq'), value: px(p.liquidationPrice), warn: true },
          { label: t('testnet.pos.entry'), value: px(p.entryPrice) },
        ].map((it) => (
          <div key={it.label} className="border border-border bg-card-2 rounded-md px-2 py-1.5 text-center">
            <div className="text-[10px] text-muted-foreground">{it.label}</div>
            <div className={cn('text-xs font-bold tabular-nums mt-0.5', it.warn && 'text-loss')}>{it.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ========== Open Order Row ========== */
function OpenOrderRow({ o }: { o: TnOpenOrder }) {
  const { t } = useTranslation('strategy');
  const isEntry = o.type === 'LIMIT';
  const buy = o.side === 'BUY';
  return (
    <div className="border border-border bg-card rounded-md px-3 py-2 flex items-center gap-2 text-xs">
      <span className="font-bold">{o.symbol.replace('USDT', '')}</span>
      <span className={cn('text-[10px] font-black px-1.5 py-0.5 rounded',
        isEntry ? 'bg-primary/10 text-primary' : 'bg-warning/10 text-warning')}>
        {isEntry ? t('testnet.order.entry') : o.type === 'STOP_MARKET' ? t('testnet.order.stopLoss') : t('testnet.order.takeProfit')}
      </span>
      <span className={cn('font-bold', buy ? 'text-gain' : 'text-loss')}>{o.side}</span>
      <span className="ml-auto tabular-nums text-muted-foreground">
        @ {formatCoinPrice(o.symbol, isEntry ? o.price : o.stopPrice)}
      </span>
    </div>
  );
}

/* ========== Trade Row ========== */
function TradeRow({ t }: { t: TnTrade }) {
  const buy = t.side === 'BUY';
  const hasPnl = t.realizedPnl !== 0;
  const pnlUp = t.realizedPnl >= 0;
  return (
    // flex-wrap：手机上时间+价格×数量+盈亏一行放不下时折行
    <div className="border border-border bg-card rounded-md px-3 py-2 flex flex-wrap items-center gap-2 text-xs">
      <span className="text-[10px] text-muted-foreground tabular-nums w-20">{fmtDateTime(t.time)}</span>
      <span className="font-bold">{t.symbol.replace('USDT', '')}</span>
      <span className={cn('font-bold', buy ? 'text-gain' : 'text-loss')}>{t.side}</span>
      {t.maker
        ? <span className="text-[9px] font-black px-1 py-0.5 rounded bg-primary/10 text-primary">MAKER</span>
        : <span className="text-[9px] font-black px-1 py-0.5 rounded bg-warning/10 text-warning">TAKER</span>}
      <span className="tabular-nums text-muted-foreground">{formatCoinPrice(t.symbol, t.price)} × {t.qty}</span>
      <span className="ml-auto flex items-center gap-2">
        {hasPnl && (
          <span className={cn('font-black tabular-nums', pnlUp ? 'text-gain' : 'text-loss')}>
            {pnlUp ? '+' : ''}{fmt$(t.realizedPnl)}
          </span>
        )}
        <span className="text-[10px] text-loss/70 tabular-nums">{fmt$(t.commission)}</span>
      </span>
    </div>
  );
}

/* ========== Section Title ========== */
function SectionTitle({ icon: Icon, title, hint }: { icon: ElementType; title: string; hint?: string }) {
  return (
    <div className="flex items-center gap-2 px-1">
      <Icon className="w-4 h-4 text-primary" />
      <span className="text-sm font-bold">{title}</span>
      {hint && <span className="text-[11px] text-muted-foreground">{hint}</span>}
    </div>
  );
}

/* ========== 手动交易面板（接口自检，仅 admin 可见，后端二次门控） ========== */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[10px] text-muted-foreground font-medium">{label}</span>
      {children}
    </label>
  );
}

function ManualTradePanel({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation('strategy');
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [symbol, setSymbol] = useState('BTCUSDT');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [type, setType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [quantity, setQuantity] = useState('0.002');
  const [price, setPrice] = useState('');
  const [leverage, setLeverage] = useState('20');
  const [busy, setBusy] = useState<string | null>(null);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  // 统一跑请求：成功展示返回摘要并刷新看板，失败展示错误原文（含 Binance/权限/校验）。
  const run = (label: string, fn: () => Promise<string>) => {
    setBusy(label);
    setResult(null);
    fn()
      .then((text) => { setResult({ ok: true, text }); onDone(); })
      .catch((e) => { const msg = (e as Error).message || t('testnet.manual.opFailed'); setResult({ ok: false, text: msg }); toast(msg, 'error'); })
      .finally(() => setBusy(null));
  };

  const submitOrder = () => {
    const qty = Number(quantity);
    if (!qty || qty <= 0) { toast(t('testnet.manual.qtyPositive'), 'error'); return; }
    if (type === 'LIMIT' && (!Number(price) || Number(price) <= 0)) { toast(t('testnet.manual.priceRequired'), 'error'); return; }
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

  const inputCls = 'border border-border bg-card-2 rounded-md px-2.5 py-1.5 text-xs tabular-nums w-24 bg-transparent outline-none';

  return (
    <div className="pt-card rounded-lg overflow-hidden">
      <button onClick={() => setOpen((o) => !o)} className="w-full flex items-center gap-2 px-4 py-3 text-sm font-bold">
        <Wrench className="w-4 h-4 text-warning" />
        {t('testnet.manual.title')}
        <span className="text-[11px] font-normal text-muted-foreground">{t('testnet.manual.subtitle')}</span>
        <ChevronDown className={cn('w-4 h-4 ml-auto transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="px-4 pb-4 space-y-3 border-t border-border/40 pt-3">
          {/* 行1：symbol / 方向 / 类型 */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <div className="flex gap-1">
              {(['BTCUSDT', 'ETHUSDT'] as const).map((s) => (
                <button key={s} onClick={() => setSymbol(s)}
                  className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                    symbol === s ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground')}>
                  {s.replace('USDT', '')}
                </button>
              ))}
            </div>
            <div className="flex gap-1">
              {(['BUY', 'SELL'] as const).map((s) => (
                <button key={s} onClick={() => setSide(s)}
                  className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                    side === s ? (s === 'BUY' ? 'border border-border bg-card-2 text-gain' : 'border border-border bg-card-2 text-loss') : 'border border-border text-muted-foreground')}>
                  {s === 'BUY' ? t('testnet.manual.buy') : t('testnet.manual.sell')}
                </button>
              ))}
            </div>
            <div className="flex gap-1">
              {/* 循环变量避开 t：与 i18n 的 t 同名会遮蔽 */}
              {(['MARKET', 'LIMIT'] as const).map((ty) => (
                <button key={ty} onClick={() => setType(ty)}
                  className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                    type === ty ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground')}>
                  {ty === 'MARKET' ? t('testnet.manual.market') : t('testnet.manual.limit')}
                </button>
              ))}
            </div>
          </div>
          {/* 行2：数量 / 限价(仅 LIMIT) / 杠杆 */}
          <div className="flex flex-wrap items-end gap-3">
            <Field label={t('testnet.manual.qty')}>
              <input value={quantity} onChange={(e) => setQuantity(e.target.value)} inputMode="decimal" className={inputCls} placeholder="0.002" />
            </Field>
            {type === 'LIMIT' && (
              <Field label={t('testnet.manual.price')}>
                <input value={price} onChange={(e) => setPrice(e.target.value)} inputMode="decimal" className={inputCls} placeholder={t('testnet.manual.pricePh')} />
              </Field>
            )}
            <Field label={t('testnet.manual.leverage')}>
              <input value={leverage} onChange={(e) => setLeverage(e.target.value)} inputMode="numeric" className={cn(inputCls, 'w-16')} placeholder="20" />
            </Field>
          </div>
          {/* 行3：操作按钮 */}
          <div className="flex flex-wrap gap-2">
            <button onClick={submitOrder} disabled={busy !== null}
              className="border border-border hover:bg-surface-hover px-3 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50">
              {busy === 'order' ? t('testnet.manual.submitting') : t('testnet.manual.submit')}
            </button>
            <button onClick={submitClose} disabled={busy !== null}
              className="border border-border hover:bg-surface-hover px-3 py-1.5 rounded-lg text-xs font-bold text-loss disabled:opacity-50">
              {busy === 'close' ? t('testnet.manual.closing') : t('testnet.manual.closeMarket')}
            </button>
            <button onClick={submitCancelAll} disabled={busy !== null}
              className="border border-border hover:bg-surface-hover px-3 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50">
              {busy === 'cancel' ? t('testnet.manual.cancelling') : t('testnet.manual.cancelAll')}
            </button>
          </div>
          {/* 结果框 */}
          {result && (
            <div className={cn('border border-border bg-card-2 rounded-md px-3 py-2 text-[11px] font-mono break-all',
              result.ok ? 'text-gain' : 'text-loss')}>
              {result.ok ? '✓ ' : '✗ '}{result.text}
            </div>
          )}
          <div className="text-[10px] text-muted-foreground/70 leading-relaxed">
            {t('testnet.manual.note')}
          </div>
        </div>
      )}
    </div>
  );
}

/* ========== Main ========== */
export function TestnetMonitor() {
  const { t } = useTranslation(['strategy', 'common']);
  const { toast } = useToast();
  const user = useUserStore((s) => s.user);
  const [overview, setOverview] = useState<TnOverview | null>(null);
  const [trades, setTrades] = useState<TnTrade[]>([]);          // 近 30 天：成交列表、胜率
  const [gridTrades, setGridTrades] = useState<TnTrade[]>([]);  // 近 90 天：跟网格同跨度，点格子下钻用
  const [daily, setDaily] = useState<TnDailyCell[]>([]);
  const [equity, setEquity] = useState<TnEquityPoint[]>([]);
  const [fill, setFill] = useState<TnFillStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [symbol, setSymbol] = useState<string>('ALL');
  const [selectedDate, setSelectedDate] = useState<string | undefined>();
  // 网格月份受控。用户没翻过就跟着数据走（落在最新有成交的那个月），翻过就听用户的
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
  useEffect(() => { const t = setInterval(load, 300_000); return () => clearInterval(t); }, [load]);

  // 派生指标（全部来自 testnet 原始数据）
  const cumPnl = equity.length ? equity[equity.length - 1].cumPnl : 0;
  const closed = useMemo(() => trades.filter((t) => t.realizedPnl !== 0), [trades]);
  const wins = useMemo(() => closed.filter((t) => t.realizedPnl > 0).length, [closed]);
  const winRate = closed.length ? (wins / closed.length) * 100 : 0;
  const fillRatePct = fill ? fill.fillRate * 100 : 0;
  const equityNow = overview?.account.marginBalance;

  const gridMonth = useMemo(() => {
    if (pickedMonth) return pickedMonth;
    const months = daily.map((c) => c.date.slice(0, 7)).sort();
    return months.length ? months[months.length - 1] : fmtDate().slice(0, 7);
  }, [pickedMonth, daily]);

  // 选中某天的成交（东八区切日，与网格一致）
  const dayTrades = useMemo(
    () => (selectedDate ? gridTrades.filter((t) => cnDate(t.time) === selectedDate) : []),
    [selectedDate, gridTrades],
  );

  const isEmpty = !loading && trades.length === 0 && equity.length === 0 && (overview?.positions.length ?? 0) === 0
    && equityNow == null;

  return (
    <div className="page-shell px-4 md:px-6 py-5 space-y-5">
      {/* Header：手机上标题+币种切换放不下一行，允许换行 */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-lg pt-card flex items-center justify-center bg-primary/10">
            <Activity className="w-5.5 h-5.5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight">{t('testnet.title')}</h1>
            <p className="text-[11px] text-muted-foreground">{t('testnet.subtitle')}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex gap-1">
            {SYMBOLS.map((s) => (
              <button key={s} onClick={() => setSymbol(s)}
                className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                  symbol === s ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground')}>
                {s === 'ALL' ? t('common:all') : s.replace('USDT', '')}
              </button>
            ))}
          </div>
          <button onClick={() => { load(); toast(t('testnet.refreshed'), 'info'); }}
            className="border border-border hover:bg-surface-hover w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary">
            <RefreshCcw className={cn('w-4 h-4', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      {/* 历史轨横幅：执行目标已切 sim 时明示——本页旧交易与"策略账户"页 sim 记录不一致属预期 */}
      {overview?.executionTarget === 'sim' && (
        <div className="rounded-lg border border-border bg-card px-4 py-3 text-xs leading-relaxed border-l-4 border-l-warning">
          <span className="font-black text-warning">{t('testnet.banner.title')}</span>
          <span className="text-muted-foreground">{t('testnet.banner.body')}</span>
          <a href="/strategies" className="font-bold text-primary hover:underline ml-1">{t('testnet.banner.link')} →</a>
        </div>
      )}

      {/* 接口自检面板（仅 admin=1 可见；后端再做一次 admin 门控） */}
      {user?.id === 1 && <ManualTradePanel onDone={load} />}

      {/* 空态提示 */}
      {isEmpty && (
        <div className="border border-border bg-card-2 rounded-lg py-10 text-center">
          <p className="text-sm text-muted-foreground">{t('testnet.empty.title')}</p>
          <p className="text-[11px] text-muted-foreground/70 mt-1">{t('testnet.empty.hint')}</p>
        </div>
      )}

      {/* Stat Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatCard label={t('testnet.stat.equity')} value={`$${fmt$(equityNow, true)}`}
          sub={t('testnet.stat.available', { value: fmt$(overview?.account.availableBalance, true) })} icon={Wallet} />
        <StatCard label={t('testnet.stat.cumPnl')} value={fmtSignedUsd(cumPnl)}
          sub={t('testnet.stat.cumPnlSub')} icon={BarChart3} trend={cumPnl >= 0 ? 'up' : 'down'} />
        <StatCard label={t('testnet.stat.fillRate')} value={fill ? `${fillRatePct.toFixed(1)}%` : '-'}
          sub={fill ? t('testnet.stat.fillRateSub', { filled: fill.filled, placed: fill.placed, expired: fill.expired })
            : t('testnet.stat.fillRateHint')} icon={Target}
          trend={fillRatePct >= 50 ? 'up' : fill ? 'down' : 'neutral'} />
        <StatCard label={t('testnet.stat.winRate')} value={closed.length ? `${winRate.toFixed(1)}%` : '-'}
          sub={t('testnet.stat.winRateSub', { wins, total: closed.length })} icon={TrendingUp}
          trend={winRate >= 50 ? 'up' : closed.length ? 'down' : 'neutral'} />
      </div>

      {/* 权益曲线 */}
      {equity.length > 0 && (
        <div className="space-y-2">
          <SectionTitle icon={BarChart3} title={t('testnet.equity.title')} hint={t('testnet.equity.hint')} />
          <div className="pt-card rounded-lg p-3"><EquityChart points={equity} /></div>
        </div>
      )}

      {/* 日交易网格 + 下钻 */}
      <div className="space-y-2">
        <SectionTitle icon={Layers} title={t('testnet.grid.title')} hint={t('testnet.grid.hint')} />
        <DailyGrid cells={daily} month={gridMonth} onMonthChange={setPickedMonth}
          selectedDate={selectedDate} onSelectDate={setSelectedDate} className="pt-card rounded-lg p-4" />
        {selectedDate && (
          <div className="border border-border bg-card-2 rounded-lg p-3 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold">{t('testnet.grid.dayTrades', { date: selectedDate, n: dayTrades.length })}</span>
              <button onClick={() => setSelectedDate(undefined)} className="text-[11px] text-muted-foreground hover:text-primary">{t('common:collapse')}</button>
            </div>
            {dayTrades.length ? dayTrades.map((tr) => <TradeRow key={tr.id} t={tr} />)
              : <div className="text-[11px] text-muted-foreground text-center py-4">{t('testnet.grid.empty')}</div>}
          </div>
        )}
      </div>

      {/* 当前持仓 */}
      {overview && overview.positions.length > 0 && (
        <div className="space-y-3">
          <SectionTitle icon={Activity} title={t('testnet.positions.title')} hint={`${overview.positions.length}`} />
          <div className="grid gap-3 md:grid-cols-2">
            {overview.positions.map((p) => <PositionCard key={p.symbol} p={p} />)}
          </div>
        </div>
      )}

      {/* 当前挂单 */}
      {overview && overview.openOrders.length > 0 && (
        <div className="space-y-2">
          <SectionTitle icon={ListChecks} title={t('testnet.orders.title')} hint={`${overview.openOrders.length}`} />
          <div className="space-y-1.5">
            {overview.openOrders.map((o) => <OpenOrderRow key={o.orderId} o={o} />)}
          </div>
        </div>
      )}

      {/* fill 对账 */}
      {fill && fill.placed > 0 && (
        <div className="space-y-2">
          <SectionTitle icon={Gauge} title={t('testnet.fill.title')} hint={t('testnet.fill.hint')} />
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard label={t('testnet.fill.rate')} value={`${fillRatePct.toFixed(1)}%`} sub={`${fill.filled}/${fill.placed}`}
              icon={Target} trend={fillRatePct >= 50 ? 'up' : 'down'} />
            <StatCard label={t('testnet.fill.expired')} value={`${fill.expired}`} sub={t('testnet.fill.expiredSub')} icon={Clock} />
            <StatCard label={t('testnet.fill.avgTime')} value={fmtDuration(fill.avgFillSeconds)} sub={t('testnet.fill.avgTimeSub')} icon={Clock} />
            <StatCard label={t('testnet.fill.maker')} value={`${fill.makerConfirmed}/${fill.filled}`} sub={t('testnet.fill.makerSub')} icon={CheckCircle2}
              trend={fill.makerConfirmed === fill.filled && fill.filled > 0 ? 'up' : 'neutral'} />
          </div>
          <div className="border border-border bg-card-2 rounded-md px-3 py-2 text-[11px] text-muted-foreground leading-relaxed">
            {t('testnet.fill.note')}
          </div>
        </div>
      )}

      {/* 交易记录 */}
      <div className="space-y-2">
        <SectionTitle icon={ListChecks} title={t('testnet.trades.title')} hint={t('testnet.trades.hint')} />
        <div className="space-y-1.5">
          {loading && trades.length === 0 ? (
            Array.from({ length: 3 }).map((_, i) => <div key={i} className="border border-border bg-card rounded-md h-9 animate-pulse bg-muted/20" />)
          ) : trades.length === 0 ? (
            <div className="border border-border bg-card-2 rounded-lg py-8 text-center text-sm text-muted-foreground">{t('testnet.trades.empty')}</div>
          ) : (
            trades.slice(0, 50).map((tr) => <TradeRow key={tr.id} t={tr} />)
          )}
        </div>
      </div>
    </div>
  );
}
