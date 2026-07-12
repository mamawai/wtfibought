import { useState, useEffect, useCallback, useMemo, type ElementType, type ReactNode } from 'react';
import { testnetApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
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
const SYM_LABEL: Record<string, string> = { ALL: '全部', BTCUSDT: 'BTC', ETHUSDT: 'ETH' };

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
    <div className="neu-raised-sm rounded-xl p-4 flex flex-col gap-1.5">
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
  const isLong = p.side === 'LONG';
  const pnlUp = p.unrealizedProfit >= 0;
  const px = (v?: number | null) => formatCoinPrice(p.symbol, v);
  return (
    <div className={cn('neu-raised-sm rounded-xl p-4', isLong ? 'border-l-3 border-l-gain' : 'border-l-3 border-l-loss')}>
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
          { label: '标记价', value: px(p.markPrice) },
          { label: '强平价', value: px(p.liquidationPrice), warn: true },
          { label: '开仓价', value: px(p.entryPrice) },
        ].map((it) => (
          <div key={it.label} className="neu-inset rounded-lg px-2 py-1.5 text-center">
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
  const isEntry = o.type === 'LIMIT';
  const buy = o.side === 'BUY';
  return (
    <div className="neu-flat rounded-lg px-3 py-2 flex items-center gap-2 text-xs">
      <span className="font-bold">{o.symbol.replace('USDT', '')}</span>
      <span className={cn('text-[10px] font-black px-1.5 py-0.5 rounded',
        isEntry ? 'bg-primary/10 text-primary' : 'bg-warning/10 text-warning')}>
        {isEntry ? '进场' : o.type === 'STOP_MARKET' ? '止损' : '止盈'}
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
    <div className="neu-flat rounded-lg px-3 py-2 flex items-center gap-2 text-xs">
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
      .catch((e) => { const msg = (e as Error).message || '操作失败'; setResult({ ok: false, text: msg }); toast(msg, 'error'); })
      .finally(() => setBusy(null));
  };

  const submitOrder = () => {
    const qty = Number(quantity);
    if (!qty || qty <= 0) { toast('数量必须 > 0', 'error'); return; }
    if (type === 'LIMIT' && (!Number(price) || Number(price) <= 0)) { toast('LIMIT 需填价格', 'error'); return; }
    run('order', async () => {
      const r = await testnetApi.manualOrder({
        symbol, side, type, quantity: qty,
        price: type === 'LIMIT' ? Number(price) : undefined,
        leverage: leverage ? Number(leverage) : undefined,
      });
      return `#${r.orderId} ${r.status}${r.avgPrice ? ` @ ${r.avgPrice}` : ''}（${r.side} ${r.type} ${r.origQty}）`;
    });
  };

  const submitClose = () => run('close', async () => {
    const r = await testnetApi.manualClose(symbol);
    return `平仓 #${r.orderId} ${r.status}${r.avgPrice ? ` @ ${r.avgPrice}` : ''}`;
  });

  const submitCancelAll = () => run('cancel', async () => {
    await testnetApi.manualCancelAll(symbol);
    return `已撤 ${symbol} 全部挂单`;
  });

  const inputCls = 'neu-inset rounded-lg px-2.5 py-1.5 text-xs tabular-nums w-24 bg-transparent outline-none';

  return (
    <div className="neu-raised-sm rounded-xl overflow-hidden">
      <button onClick={() => setOpen((o) => !o)} className="w-full flex items-center gap-2 px-4 py-3 text-sm font-bold">
        <Wrench className="w-4 h-4 text-warning" />
        接口自检 · 手动交易
        <span className="text-[11px] font-normal text-muted-foreground">仅管理员 · 直连 testnet</span>
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
                    symbol === s ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground')}>
                  {s.replace('USDT', '')}
                </button>
              ))}
            </div>
            <div className="flex gap-1">
              {(['BUY', 'SELL'] as const).map((s) => (
                <button key={s} onClick={() => setSide(s)}
                  className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                    side === s ? (s === 'BUY' ? 'neu-inset text-gain' : 'neu-inset text-loss') : 'neu-flat text-muted-foreground')}>
                  {s === 'BUY' ? '买/多' : '卖/空'}
                </button>
              ))}
            </div>
            <div className="flex gap-1">
              {(['MARKET', 'LIMIT'] as const).map((t) => (
                <button key={t} onClick={() => setType(t)}
                  className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                    type === t ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground')}>
                  {t === 'MARKET' ? '市价' : '限价'}
                </button>
              ))}
            </div>
          </div>
          {/* 行2：数量 / 限价(仅 LIMIT) / 杠杆 */}
          <div className="flex flex-wrap items-end gap-3">
            <Field label="数量(张)">
              <input value={quantity} onChange={(e) => setQuantity(e.target.value)} inputMode="decimal" className={inputCls} placeholder="0.002" />
            </Field>
            {type === 'LIMIT' && (
              <Field label="限价">
                <input value={price} onChange={(e) => setPrice(e.target.value)} inputMode="decimal" className={inputCls} placeholder="挂单价" />
              </Field>
            )}
            <Field label="杠杆">
              <input value={leverage} onChange={(e) => setLeverage(e.target.value)} inputMode="numeric" className={cn(inputCls, 'w-16')} placeholder="20" />
            </Field>
          </div>
          {/* 行3：操作按钮 */}
          <div className="flex flex-wrap gap-2">
            <button onClick={submitOrder} disabled={busy !== null}
              className="neu-btn-sm px-3 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50">
              {busy === 'order' ? '提交中…' : '下单'}
            </button>
            <button onClick={submitClose} disabled={busy !== null}
              className="neu-btn-sm px-3 py-1.5 rounded-lg text-xs font-bold text-loss disabled:opacity-50">
              {busy === 'close' ? '平仓中…' : '市价平仓'}
            </button>
            <button onClick={submitCancelAll} disabled={busy !== null}
              className="neu-btn-sm px-3 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50">
              {busy === 'cancel' ? '撤单中…' : '撤挂单'}
            </button>
          </div>
          {/* 结果框 */}
          {result && (
            <div className={cn('neu-inset rounded-lg px-3 py-2 text-[11px] font-mono break-all',
              result.ok ? 'text-gain' : 'text-loss')}>
              {result.ok ? '✓ ' : '✗ '}{result.text}
            </div>
          )}
          <div className="text-[10px] text-muted-foreground/70 leading-relaxed">
            数量为币本位张数(如 BTC 0.002)。需先在 application.yml 配置 binance-testnet 的 api-key/secret-key；
            手动测试建议关闭自动执行(strategy.execution.enabled=false)，避免两套状态机冲突。
          </div>
        </div>
      )}
    </div>
  );
}

/* ========== Main ========== */
export function TestnetMonitor() {
  const { toast } = useToast();
  const user = useUserStore((s) => s.user);
  const [overview, setOverview] = useState<TnOverview | null>(null);
  const [trades, setTrades] = useState<TnTrade[]>([]);
  const [daily, setDaily] = useState<TnDailyCell[]>([]);
  const [equity, setEquity] = useState<TnEquityPoint[]>([]);
  const [fill, setFill] = useState<TnFillStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [symbol, setSymbol] = useState<string>('ALL');
  const [selectedDate, setSelectedDate] = useState<string | undefined>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const sym = symbol === 'ALL' ? undefined : symbol;
      const [ov, tr, dg, eq, fs] = await Promise.all([
        testnetApi.overview(),
        testnetApi.trades(sym, 30),
        testnetApi.dailyGrid(sym, 90),
        testnetApi.equity(sym, 90),
        testnetApi.fillStats(sym, 30),
      ]);
      setOverview(ov); setTrades(tr); setDaily(dg); setEquity(eq); setFill(fs);
    } catch (e) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [symbol, toast]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(load, 300_000); return () => clearInterval(t); }, [load]);

  // 派生指标（全部来自 testnet 原始数据）
  const cumPnl = equity.length ? equity[equity.length - 1].cumPnl : 0;
  const closed = useMemo(() => trades.filter((t) => t.realizedPnl !== 0), [trades]);
  const wins = useMemo(() => closed.filter((t) => t.realizedPnl > 0).length, [closed]);
  const winRate = closed.length ? (wins / closed.length) * 100 : 0;
  const fillRatePct = fill ? fill.fillRate * 100 : 0;
  const equityNow = overview?.account.marginBalance;

  // 选中某天的成交（东八区切日，与网格一致）
  const dayTrades = useMemo(
    () => (selectedDate ? trades.filter((t) => cnDate(t.time) === selectedDate) : []),
    [selectedDate, trades],
  );

  const isEmpty = !loading && trades.length === 0 && equity.length === 0 && (overview?.positions.length ?? 0) === 0
    && equityNow == null;

  return (
    <div className="max-w-5xl mx-auto px-4 md:px-6 py-5 space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-xl neu-raised-sm flex items-center justify-center bg-primary/10">
            <Activity className="w-5.5 h-5.5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight">模拟盘监测</h1>
            <p className="text-[11px] text-muted-foreground">Binance Testnet · Fibo 策略实盘验证 · 真实盘口撮合</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex gap-1">
            {SYMBOLS.map((s) => (
              <button key={s} onClick={() => setSymbol(s)}
                className={cn('text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all',
                  symbol === s ? 'neu-inset text-primary' : 'neu-flat text-muted-foreground hover:text-foreground')}>
                {SYM_LABEL[s]}
              </button>
            ))}
          </div>
          <button onClick={() => { load(); toast('已刷新', 'info'); }}
            className="neu-btn-sm w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary">
            <RefreshCcw className={cn('w-4 h-4', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      {/* 接口自检面板（仅 admin=1 可见；后端再做一次 admin 门控） */}
      {user?.id === 1 && <ManualTradePanel onDone={load} />}

      {/* 空态提示 */}
      {isEmpty && (
        <div className="neu-inset rounded-xl py-10 text-center">
          <p className="text-sm text-muted-foreground">暂无 testnet 数据</p>
          <p className="text-[11px] text-muted-foreground/70 mt-1">
            需在 application.yml 配置 binance-testnet 的 api-key/secret-key，并开启 strategy.execution.enabled 跑出成交后才有数据
          </p>
        </div>
      )}

      {/* Stat Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatCard label="当前权益" value={`$${fmt$(equityNow, true)}`}
          sub={`可用 $${fmt$(overview?.account.availableBalance, true)}`} icon={Wallet} />
        <StatCard label="累计盈亏" value={`${cumPnl >= 0 ? '+' : ''}$${fmt$(cumPnl)}`}
          sub="含手续费 · 实时直拉" icon={BarChart3} trend={cumPnl >= 0 ? 'up' : 'down'} />
        <StatCard label="进场成交率" value={fill ? `${fillRatePct.toFixed(1)}%` : '-'}
          sub={fill ? `${fill.filled}/${fill.placed} 成交 · 超时 ${fill.expired}` : 'fill 生死线'} icon={Target}
          trend={fillRatePct >= 50 ? 'up' : fill ? 'down' : 'neutral'} />
        <StatCard label="胜率" value={closed.length ? `${winRate.toFixed(1)}%` : '-'}
          sub={`${wins}/${closed.length} 笔盈利`} icon={TrendingUp}
          trend={winRate >= 50 ? 'up' : closed.length ? 'down' : 'neutral'} />
      </div>

      {/* 权益曲线 */}
      {equity.length > 0 && (
        <div className="space-y-2">
          <SectionTitle icon={BarChart3} title="权益曲线" hint="累计已实现盈亏（从0起）" />
          <div className="neu-raised-sm rounded-xl p-3"><EquityChart points={equity} /></div>
        </div>
      )}

      {/* 日交易网格 + 下钻 */}
      <div className="space-y-2">
        <SectionTitle icon={Layers} title="日交易网格" hint="点格子看当天成交" />
        <DailyGrid cells={daily} selectedDate={selectedDate} onSelectDate={setSelectedDate} />
        {selectedDate && (
          <div className="neu-inset rounded-xl p-3 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold">{selectedDate} 当天成交（{dayTrades.length}）</span>
              <button onClick={() => setSelectedDate(undefined)} className="text-[11px] text-muted-foreground hover:text-primary">收起</button>
            </div>
            {dayTrades.length ? dayTrades.map((t) => <TradeRow key={t.id} t={t} />)
              : <div className="text-[11px] text-muted-foreground text-center py-4">当天无成交明细</div>}
          </div>
        )}
      </div>

      {/* 当前持仓 */}
      {overview && overview.positions.length > 0 && (
        <div className="space-y-3">
          <SectionTitle icon={Activity} title="当前持仓" hint={`${overview.positions.length}`} />
          <div className="grid gap-3 md:grid-cols-2">
            {overview.positions.map((p) => <PositionCard key={p.symbol} p={p} />)}
          </div>
        </div>
      )}

      {/* 当前挂单 */}
      {overview && overview.openOrders.length > 0 && (
        <div className="space-y-2">
          <SectionTitle icon={ListChecks} title="当前挂单" hint={`${overview.openOrders.length}`} />
          <div className="space-y-1.5">
            {overview.openOrders.map((o) => <OpenOrderRow key={o.orderId} o={o} />)}
          </div>
        </div>
      )}

      {/* fill 对账 */}
      {fill && fill.placed > 0 && (
        <div className="space-y-2">
          <SectionTitle icon={Gauge} title="fill 对账" hint="回测 touch=fill 假设的真实检验" />
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard label="成交率" value={`${fillRatePct.toFixed(1)}%`} sub={`${fill.filled}/${fill.placed}`}
              icon={Target} trend={fillRatePct >= 50 ? 'up' : 'down'} />
            <StatCard label="超时撤单" value={`${fill.expired}`} sub="挂了没排到" icon={Clock} />
            <StatCard label="平均成交时长" value={fmtDuration(fill.avgFillSeconds)} sub="挂单→成交" icon={Clock} />
            <StatCard label="零滑点确认" value={`${fill.makerConfirmed}/${fill.filled}`} sub="成交价=挂单价" icon={CheckCircle2}
              trend={fill.makerConfirmed === fill.filled && fill.filled > 0 ? 'up' : 'neutral'} />
          </div>
          <div className="neu-inset rounded-lg px-3 py-2 text-[11px] text-muted-foreground leading-relaxed">
            成交率 = 挂在回撤位的进场单真正被回踩成交的比例。回测假设"价格触及即成交(100%)"，
            实盘 GTX maker 单"触及还要排队"——此处成交率越接近回测假设，回测越可信；差距越大，回测越乐观。
          </div>
        </div>
      )}

      {/* 交易记录 */}
      <div className="space-y-2">
        <SectionTitle icon={ListChecks} title="交易记录" hint="近30天真实成交" />
        <div className="space-y-1.5">
          {loading && trades.length === 0 ? (
            Array.from({ length: 3 }).map((_, i) => <div key={i} className="neu-flat rounded-lg h-9 animate-pulse bg-muted/20" />)
          ) : trades.length === 0 ? (
            <div className="neu-inset rounded-xl py-8 text-center text-sm text-muted-foreground">暂无成交记录</div>
          ) : (
            trades.slice(0, 50).map((t) => <TradeRow key={t.id} t={t} />)
          )}
        </div>
      </div>
    </div>
  );
}
