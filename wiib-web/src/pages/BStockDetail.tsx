import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { bstockApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Skeleton } from '../components/ui/skeleton';
import { CandleChart } from '../components/CandleChart';
import { FuturesActionButton } from '../components/FuturesActionButton';
import { useQuantityAnimation } from '../components/coin/useQuantityAnimation';
import { cn, fmtNum } from '../lib/utils';
import { ChevronLeft, Wallet, Globe, Landmark } from 'lucide-react';
import type { BStock, CryptoPosition } from '../types';

const COMMISSION_RATE = 0.001;
const CHART_TABS = [{ label: '5m', interval: '5m' as const }, { label: '15m', interval: '15m' as const }, { label: '1h', interval: '1h' as const }];
const PCTS = [0.25, 0.5, 0.75, 1];
const LEVERAGES = [1, 2, 3, 5, 10];
const QTY_STEP = 0.0001;   // 数量精度：与 toFixed(4) 同口径，缓动动画的步长

const fmtCap = (v?: number) => {
  if (v == null) return '—';
  if (v >= 1e12) return `${(v / 1e12).toFixed(2)}T`;
  if (v >= 1e9) return `${(v / 1e9).toFixed(1)}B`;
  if (v >= 1e6) return `${(v / 1e6).toFixed(1)}M`;
  return fmtNum(v);
};

export function BStockRoute() {
  const { symbol } = useParams<{ symbol: string }>();
  return <BStockDetail key={symbol} symbol={symbol ?? ''} />;
}

function BStockDetail({ symbol }: { symbol: string }) {
  const navigate = useNavigate();
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);

  const [info, setInfo] = useState<BStock | null>(null);
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  const [chartTab, setChartTab] = useState(0);
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [qty, setQty] = useState('');
  const [leverage, setLeverage] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(false);
  const animateQty = useQuantityAnimation(qty, setQty);

  const tick = useCryptoStream(symbol, 'spot');
  const livePrice = tick?.price ?? info?.price ?? 0;
  const balance = user?.balance ?? 0;

  const load = useCallback(() => {
    bstockApi.detail(symbol).then(setInfo).catch(() => { /* keep */ });
    bstockApi.positions()
      .then(ps => setPosition(ps.find(p => p.symbol === symbol) ?? null))
      .catch(() => setPosition(null));
  }, [symbol]);
  useEffect(load, [load]);

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => setActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  const qtyNum = parseFloat(qty) || 0;
  const amount = qtyNum * livePrice;
  const commission = amount * COMMISSION_RATE;
  const isLevBuy = side === 'BUY' && leverage > 1;
  const marginCost = (isLevBuy ? amount / leverage : amount) + commission;   // 买入现金占用
  const proceeds = amount - commission;                                       // 卖出到账
  const held = position?.quantity ?? 0;
  const chg = info?.changePct ?? 0;
  const up = chg >= 0;

  const setPct = (pct: number) => {
    if (livePrice <= 0) return;
    const target = side === 'BUY'
      ? (balance * pct * leverage) / (livePrice * (1 + COMMISSION_RATE))
      : held * pct;
    animateQty(Math.max(0, target), QTY_STEP);
  };

  const submit = async () => {
    if (qtyNum <= 0) { toast('请输入数量', 'error'); return; }
    if (side === 'SELL' && qtyNum > held) { toast('持仓不足', 'error'); return; }
    setSubmitting(true);
    try {
      if (side === 'BUY') {
        await bstockApi.buy({ symbol, quantity: qtyNum, orderType: 'MARKET', ...(leverage > 1 ? { leverageMultiple: leverage } : {}) });
        toast('买入成功', 'success');
      } else {
        await bstockApi.sell({ symbol, quantity: qtyNum, orderType: 'MARKET' });
        toast('卖出成功 · 瞬时到账', 'success');
      }
      setActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      setQty('');
      fetchUser();
      load();
    } catch (e) {
      toast((e as Error).message || '下单失败', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="page-shell px-4 md:px-6 py-5 space-y-4">
      {/* 头部 */}
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" className="h-9 w-9 shrink-0" onClick={() => navigate('/bstock')}>
          <ChevronLeft className="w-5 h-5" />
        </Button>
        <div className="w-10 h-10 rounded-xl bg-primary/10 ring-1 ring-primary/20 flex items-center justify-center shrink-0 text-[11px] font-bold text-primary">
          {info?.ticker?.slice(0, 4) ?? <Landmark className="w-5 h-5" />}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="text-lg font-extrabold tracking-tight truncate">{info?.name ?? symbol}</span>
            <span className="text-xs text-muted-foreground shrink-0">{info?.ticker}</span>
          </div>
          <div className="text-xs text-muted-foreground">{info?.industry ?? '代币化美股'} · 24/7 · 瞬时结算</div>
        </div>
        <div className="text-right shrink-0">
          <div className={cn("text-xl font-extrabold tabular-nums tracking-tight transition-colors", livePrice ? (up ? "text-green-400" : "text-red-400") : "")}>
            {livePrice ? fmtNum(livePrice) : <Skeleton className="h-6 w-20" />}
          </div>
          <div className={cn("text-xs tabular-nums font-medium", up ? "text-green-400" : "text-red-400")}>
            {up ? '+' : ''}{chg.toFixed(2)}% · 24h
          </div>
        </div>
      </div>

      {/* items-stretch + 右栏 flex：交易面板拉伸到与左栏(K线+公司信息)等高，不再"缺一块" */}
      <div className="grid lg:grid-cols-3 gap-4 items-stretch">
        {/* 左：图 + 公司信息 */}
        <div className="lg:col-span-2 space-y-4">
          <Card className="overflow-hidden">
            <div className="px-4 pt-3 flex items-center gap-1.5">
              {CHART_TABS.map((t, i) => (
                <Button key={t.label} variant={chartTab === i ? 'secondary' : 'ghost'} size="sm" className="h-7 text-xs px-3" onClick={() => setChartTab(i)}>
                  {t.label}
                </Button>
              ))}
              <span className="ml-auto text-xs text-muted-foreground tabular-nums">
                {info?.high != null && info?.low != null && <>高 {fmtNum(info.high)} · 低 {fmtNum(info.low)}</>}
              </span>
            </div>
            <div className="h-[360px] sm:h-[430px] p-2">
              {/* bstock 无后端K线广播：现货价格流驱动最后一根实时跳动 */}
              <CandleChart
                symbol={symbol}
                interval={CHART_TABS[chartTab].interval}
                klinesFn={bstockApi.klines}
                streamLive={false}
                tick={tick?.price != null && tick?.ts != null ? { price: tick.price, ts: tick.ts } : null}
              />
            </div>
          </Card>

          {/* 公司信息 */}
          <Card>
            <CardContent className="p-4 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm font-semibold">
                  <Landmark className="w-4 h-4 text-primary" /> 公司信息
                </div>
                {info?.homepage && (
                  <a href={info.homepage} target="_blank" rel="noopener noreferrer" className="text-xs text-primary hover:underline inline-flex items-center gap-1">
                    <Globe className="w-3 h-3" /> 官网
                  </a>
                )}
              </div>
              {info ? (
                <>
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                    {[
                      ['市值', fmtCap(info.marketCap)],
                      ['市盈率', info.peRatio != null ? String(info.peRatio) : '—'],
                      ['股息率', info.dividendYield != null ? `${info.dividendYield}%` : '—'],
                      ['行业', info.industry ?? '—'],
                      ['52周最高', info.week52High != null ? fmtNum(info.week52High) : '—'],
                      ['52周最低', info.week52Low != null ? fmtNum(info.week52Low) : '—'],
                      ['CEO', info.ceo ?? '—'],
                      ['英文名', info.nameEn ?? info.ticker ?? '—'],
                    ].map(([k, v]) => (
                      <div key={k} className="rounded-xl neu-inset px-3 py-2.5">
                        <div className="text-[10px] text-muted-foreground">{k}</div>
                        <div className="text-sm font-bold tabular-nums tracking-tight truncate mt-0.5" title={v}>{v}</div>
                      </div>
                    ))}
                  </div>
                  {info.description && <p className="text-xs leading-relaxed text-muted-foreground">{info.description}</p>}
                  {info.multiplier != null && info.multiplier !== 1 && (
                    <p className="text-[11px] text-muted-foreground/70">乘数 {info.multiplier.toFixed(4)}（含分红再投，价已反映）</p>
                  )}
                </>
              ) : (
                <div className="space-y-2"><Skeleton className="h-16 w-full" /><Skeleton className="h-12 w-full" /></div>
              )}
            </CardContent>
          </Card>
        </div>

        {/* 右：交易面板（flex 拉伸补齐左栏高度）+ 持仓 */}
        <div className="flex flex-col gap-4">
          <Card className="overflow-hidden flex-1 flex flex-col">
            <CardContent className="p-5 flex-1 flex flex-col gap-5">
              {/* 买/卖切换：拟物段控件 */}
              <div className="flex rounded-xl bg-card overflow-hidden neu-raised">
                {(['BUY', 'SELL'] as const).map(sd => (
                  <button
                    key={sd}
                    type="button"
                    onClick={() => { setSide(sd); setQty(''); }}
                    className={cn(
                      "flex-1 py-2.5 text-sm font-black transition-colors first:border-r first:border-border",
                      side === sd
                        ? (sd === 'BUY' ? "bg-gain text-white" : "bg-loss text-white")
                        : "bg-card text-foreground hover:bg-surface-hover"
                    )}
                  >
                    {sd === 'BUY' ? '买入' : '卖出'}
                  </button>
                ))}
              </div>

              {/* 余额 / 持仓 */}
              <div className="flex items-center justify-between text-xs font-bold text-muted-foreground">
                <span className="inline-flex items-center gap-1"><Wallet className="w-3.5 h-3.5" /> 可用 <span className="text-foreground tabular-nums">{fmtNum(balance)}</span></span>
                <span>持有 <span className="text-foreground tabular-nums">{fmtNum(held)}</span></span>
              </div>

              {/* 数量 */}
              <div className="space-y-2">
                <label className="text-xs font-bold text-muted-foreground">数量（股）</label>
                <Input
                  value={qty}
                  onChange={e => setQty(e.target.value.replace(/[^0-9.]/g, ''))}
                  placeholder="数量"
                  inputMode="decimal"
                  className="h-11 text-base tabular-nums"
                />
                <div className="grid grid-cols-4 gap-1.5">
                  {PCTS.map(p => (
                    <Button key={p} variant="outline" size="sm" className="h-9 text-[11px] font-black" onClick={() => setPct(p)}>
                      {p === 1 ? '全部' : `${p * 100}%`}
                    </Button>
                  ))}
                </div>
              </div>

              {/* 杠杆（仅买入） */}
              {side === 'BUY' && (
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs font-bold text-muted-foreground">
                    <span>杠杆（借款 · 日息 0.05%）</span>
                    <span className="text-foreground">{leverage}x</span>
                  </div>
                  <div className="grid grid-cols-5 gap-1.5">
                    {LEVERAGES.map(lv => (
                      <Button key={lv} variant={leverage === lv ? 'secondary' : 'outline'} size="sm" className="h-8 text-[11px] font-black" onClick={() => setLeverage(lv)}>
                        {lv}x
                      </Button>
                    ))}
                  </div>
                </div>
              )}

              {/* 预览：内凹面板，数字随数量实时跳动（百分比按钮触发缓动） */}
              <div className="rounded-xl neu-inset px-3.5 py-3 space-y-1.5 text-xs">
                <div className="flex justify-between"><span className="text-muted-foreground">成交额</span><span className="tabular-nums font-bold">{fmtNum(amount)}</span></div>
                <div className="flex justify-between"><span className="text-muted-foreground">手续费 (0.1%)</span><span className="tabular-nums font-bold">{fmtNum(commission)}</span></div>
                {isLevBuy && <div className="flex justify-between"><span className="text-muted-foreground">借款</span><span className="tabular-nums font-bold text-amber-400">{fmtNum(amount - amount / leverage)}</span></div>}
                <div className="flex justify-between font-black text-sm pt-1.5 border-t border-border/40">
                  <span>{side === 'BUY' ? '需现金' : '到账'}</span>
                  <span className="tabular-nums">{fmtNum(side === 'BUY' ? marginCost : proceeds)}</span>
                </div>
              </div>

              {/* 下单：与 crypto 现货同款动画按钮（悬停刷卡动效 + 成交对勾） */}
              <div className="mt-auto space-y-2 pt-1">
                <FuturesActionButton
                  onClick={submit}
                  disabled={submitting || qtyNum <= 0 || livePrice <= 0}
                  loading={submitting}
                  success={actionSuccess}
                  side={side}
                  label={info?.ticker ?? ''}
                />
                <p className="text-[10px] text-center text-muted-foreground/60">现货 · 瞬时结算 · 无 T+1</p>
              </div>
            </CardContent>
          </Card>

          {/* 当前持仓 */}
          {held > 0 && (
            <Card>
              <CardContent className="p-4">
                <div className="text-xs text-muted-foreground mb-2">当前持仓</div>
                <div className="flex items-center justify-between">
                  <div>
                    <div className="text-base font-bold tabular-nums">{fmtNum(held)} <span className="text-xs text-muted-foreground font-normal">股</span></div>
                    <div className="text-xs text-muted-foreground">均价 {fmtNum(position?.avgCost ?? 0)}</div>
                  </div>
                  <div className="text-right">
                    <div className="text-xs text-muted-foreground">现值</div>
                    <div className="text-base font-bold tabular-nums">{fmtNum(held * livePrice)}</div>
                  </div>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
