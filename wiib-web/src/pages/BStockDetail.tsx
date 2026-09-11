import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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
import { LoginPrompt } from '../components/LoginPrompt';
import { useQuantityAnimation } from '../components/coin/useQuantityAnimation';
import { floorToStep } from '../components/coin/futuresMath';
import { cn, fmtNum } from '../lib/utils';
import { ChevronLeft, Wallet, Globe, Landmark } from 'lucide-react';
import type { BStock, CryptoPosition } from '../types';

const COMMISSION_RATE = 0.001;
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
  const { t } = useTranslation('market');
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  // 游客只看行情和公司信息，持仓不拉、交易面板换成去登录
  const loggedIn = useUserStore(s => !!s.token);

  const [info, setInfo] = useState<BStock | null>(null);
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  // 周期：图表顶栏自己切，页面只存当前档
  const [chartIv, setChartIv] = useState<'5m' | '15m' | '1h' | '4h' | '1d'>('5m');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [qty, setQty] = useState('');
  // 买入输入单位：股数 / USDT 预算。USDT 指"含手续费的现金占用"（用杠杆时即保证金+手续费）
  const [buyUnit, setBuyUnit] = useState<'SHARE' | 'USDT'>('SHARE');
  const [leverage, setLeverage] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(false);
  const animateQty = useQuantityAnimation(qty, setQty);

  const tick = useCryptoStream(symbol, 'spot');
  const livePrice = tick?.price ?? info?.price ?? 0;
  const balance = user?.balance ?? 0;

  const load = useCallback(() => {
    bstockApi.detail(symbol).then(setInfo).catch(() => { /* keep */ });
    if (!loggedIn) return;
    bstockApi.positions()
      .then(ps => setPosition(ps.find(p => p.symbol === symbol) ?? null))
      .catch(() => setPosition(null));
  }, [symbol, loggedIn]);
  useEffect(load, [load]);

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => setActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  // USDT 预算 ↔ 股数换算：买入现金占用 = 数量 × 价格 × (1/杠杆 + 手续费率)，
  // 与下面 marginCost 的算式同源（bstock 输入的是总股数，保证金=成交额/杠杆、手续费按全额算）。
  // 换算出的股数按 QTY_STEP 向下取整，预估与提交用同一个数，界面不骗人
  const isUsdtInput = side === 'BUY' && buyUnit === 'USDT';
  const unitFactor = livePrice * (1 / (side === 'BUY' ? leverage : 1) + COMMISSION_RATE);
  const inputNum = parseFloat(qty) || 0;
  const qtyNum = isUsdtInput
    ? (unitFactor > 0 ? floorToStep(inputNum / unitFactor, QTY_STEP) : 0)
    : inputNum;
  const amount = qtyNum * livePrice;
  const commission = amount * COMMISSION_RATE;
  const isLevBuy = side === 'BUY' && leverage > 1;
  const marginCost = (isLevBuy ? amount / leverage : amount) + commission;   // 买入现金占用
  const proceeds = amount - commission;                                       // 卖出到账
  const held = position?.quantity ?? 0;
  const chg = info?.changePct ?? 0;
  const up = chg >= 0;

  /** 切换单位时把已输入的值按当前价换算过去，不清空 */
  const switchUnit = (u: 'SHARE' | 'USDT') => {
    if (u === buyUnit) return;
    const v = parseFloat(qty);
    if (v > 0 && unitFactor > 0) {
      setQty(u === 'USDT' ? (v * unitFactor).toFixed(2) : (v / unitFactor).toFixed(4));
    }
    setBuyUnit(u);
  };

  const setPct = (pct: number) => {
    if (livePrice <= 0) return;
    // USDT 模式：% 直接取余额的百分比当预算，换算成股数的事留给预估/提交
    if (isUsdtInput) { animateQty(Math.max(0, balance * pct), 0.01); return; }
    const target = side === 'BUY'
      ? (balance * pct * leverage) / (livePrice * (1 + COMMISSION_RATE))
      : held * pct;
    animateQty(Math.max(0, target), QTY_STEP);
  };

  const submit = async () => {
    if (qtyNum <= 0) { toast(isUsdtInput ? t('bstockDetail.toastEnterAmount') : t('bstockDetail.toastEnterQty'), 'error'); return; }
    if (side === 'SELL' && qtyNum > held) { toast(t('bstockDetail.toastInsufficient'), 'error'); return; }
    setSubmitting(true);
    try {
      if (side === 'BUY') {
        await bstockApi.buy({ symbol, quantity: qtyNum, orderType: 'MARKET', ...(leverage > 1 ? { leverageMultiple: leverage } : {}) });
        toast(t('bstockDetail.toastBought'), 'success');
      } else {
        await bstockApi.sell({ symbol, quantity: qtyNum, orderType: 'MARKET' });
        toast(t('bstockDetail.toastSold'), 'success');
      }
      setActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      setQty('');
      fetchUser();
      load();
    } catch (e) {
      toast((e as Error).message || t('bstockDetail.toastOrderFailed'), 'error');
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
          <div className="text-xs text-muted-foreground">{t('bstockDetail.subtitle', { industry: info?.industry ?? t('bstockDetail.fallbackIndustry') })}</div>
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
            <div className="px-4 pt-3 text-right text-xs text-muted-foreground tabular-nums">
              {info?.high != null && info?.low != null && t('bstockDetail.highLow', { high: fmtNum(info.high), low: fmtNum(info.low) })}
            </div>
            <div className="h-[430px] sm:h-[520px] p-2">
              {/* bstock 无后端K线广播：现货价格流驱动最后一根实时跳动 */}
              <CandleChart
                symbol={symbol}
                interval={chartIv}
                marketLabel={`BINANCE ${t('coin.spot')}`}
                klinesFn={bstockApi.klines}
                loadHistory={loggedIn}
                streamLive={false}
                onIntervalChange={setChartIv}
                tick={tick?.price != null && tick?.ts != null ? { price: tick.price, ts: tick.ts } : null}
              />
            </div>
          </Card>

          {/* 公司信息 */}
          <Card>
            <CardContent className="p-4 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm font-semibold">
                  <Landmark className="w-4 h-4 text-primary" /> {t('bstockDetail.companyInfo')}
                </div>
                {info?.homepage && (
                  <a href={info.homepage} target="_blank" rel="noopener noreferrer" className="text-xs text-primary hover:underline inline-flex items-center gap-1">
                    <Globe className="w-3 h-3" /> {t('bstockDetail.website')}
                  </a>
                )}
              </div>
              {info ? (
                <>
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                    {[
                      [t('bstockDetail.fMarketCap'), fmtCap(info.marketCap)],
                      [t('bstockDetail.fPe'), info.peRatio != null ? String(info.peRatio) : '—'],
                      [t('bstockDetail.fDividend'), info.dividendYield != null ? `${info.dividendYield}%` : '—'],
                      [t('bstockDetail.fIndustry'), info.industry ?? '—'],
                      [t('bstockDetail.fWeek52High'), info.week52High != null ? fmtNum(info.week52High) : '—'],
                      [t('bstockDetail.fWeek52Low'), info.week52Low != null ? fmtNum(info.week52Low) : '—'],
                      [t('bstockDetail.fCeo'), info.ceo ?? '—'],
                      [t('bstockDetail.fNameEn'), info.nameEn ?? info.ticker ?? '—'],
                    ].map(([k, v]) => (
                      <div key={k} className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                        <div className="text-[10px] text-muted-foreground">{k}</div>
                        <div className="num text-sm font-semibold tracking-tight truncate mt-0.5" title={v}>{v}</div>
                      </div>
                    ))}
                  </div>
                  {info.description && <p className="text-xs leading-relaxed text-muted-foreground">{info.description}</p>}
                  {info.multiplier != null && info.multiplier !== 1 && (
                    <p className="text-[11px] text-muted-foreground/70">{t('bstockDetail.multiplier', { value: info.multiplier.toFixed(4) })}</p>
                  )}
                </>
              ) : (
                <div className="space-y-2"><Skeleton className="h-16 w-full" /><Skeleton className="h-12 w-full" /></div>
              )}
            </CardContent>
          </Card>
        </div>

        {/* 右：交易面板（flex 拉伸补齐左栏高度）+ 持仓；游客这格换成去登录 */}
        <div className="flex flex-col gap-4">
          {!loggedIn ? (
            <Card className="flex-1">
              <CardContent className="p-5">
                <LoginPrompt text={t('bstockDetail.loginToTrade')} />
              </CardContent>
            </Card>
          ) : (
          <Card className="overflow-hidden flex-1 flex flex-col">
            <CardContent className="p-5 flex-1 flex flex-col gap-5">
              {/* 买/卖切换：终端段控件 */}
              <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
                {(['BUY', 'SELL'] as const).map(sd => (
                  <button
                    key={sd}
                    type="button"
                    onClick={() => { setSide(sd); setQty(''); }}
                    className={cn(
                      "flex-1 py-2.5 text-sm font-bold transition-colors cursor-pointer",
                      side === sd
                        ? (sd === 'BUY' ? "bg-gain text-white" : "bg-loss text-white")
                        : "text-muted-foreground hover:bg-surface-hover hover:text-foreground"
                    )}
                  >
                    {sd === 'BUY' ? t('bstockDetail.buy') : t('bstockDetail.sell')}
                  </button>
                ))}
              </div>

              {/* 余额 / 持仓 */}
              <div className="flex items-center justify-between text-xs font-bold text-muted-foreground">
                <span className="inline-flex items-center gap-1"><Wallet className="w-3.5 h-3.5" /> {t('bstockDetail.available')} <span className="text-foreground tabular-nums">{fmtNum(balance)}</span></span>
                <span>{t('bstockDetail.held')} <span className="text-foreground tabular-nums">{fmtNum(held)}</span></span>
              </div>

              {/* 数量 / 金额 */}
              <div className="space-y-2">
                {side === 'BUY' ? (
                  <div className="flex items-center gap-2">
                    <label className="text-xs font-bold text-muted-foreground">{buyUnit === 'USDT' ? t('bstockDetail.amount') : t('bstockDetail.qty')}</label>
                    {/* 输入单位切换：按股数买 / 按 USDT 预算买 */}
                    <div className="flex rounded border border-border overflow-hidden divide-x divide-border">
                      {(['SHARE', 'USDT'] as const).map(u => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => switchUnit(u)}
                          className={cn(
                            'px-2 py-0.5 text-[10px] font-bold transition-colors cursor-pointer',
                            buyUnit === u ? 'bg-secondary text-foreground' : 'text-muted-foreground hover:text-foreground',
                          )}
                        >
                          {u === 'SHARE' ? t('bstockDetail.unitShare') : 'USDT'}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <label className="text-xs font-bold text-muted-foreground">{t('bstockDetail.qtyShares')}</label>
                )}
                <Input
                  value={qty}
                  onChange={e => setQty(e.target.value.replace(/[^0-9.]/g, ''))}
                  placeholder={isUsdtInput ? t('bstockDetail.amountPlaceholder') : t('bstockDetail.qty')}
                  inputMode="decimal"
                  className="h-11 text-base tabular-nums"
                />
                <div className="grid grid-cols-4 gap-1.5">
                  {PCTS.map(p => (
                    <Button key={p} variant="outline" size="sm" className="h-9 text-[11px] font-black" onClick={() => setPct(p)}>
                      {p === 1 ? t('bstockDetail.pctAll') : `${p * 100}%`}
                    </Button>
                  ))}
                </div>
              </div>

              {/* 杠杆（仅买入） */}
              {side === 'BUY' && (
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs font-bold text-muted-foreground">
                    <span>{t('bstockDetail.leverage')}</span>
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

              {/* 预览：次级面板，数字随数量实时跳动（百分比按钮触发缓动） */}
              <div className="rounded-md border border-border bg-card-2 px-3.5 py-3 space-y-1.5 text-xs">
                {isUsdtInput && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">{t('bstockDetail.estFill')}{leverage > 1 ? ` (${leverage}x)` : ''}</span>
                    <span className="tabular-nums font-bold">{t('bstockDetail.estShares', { qty: fmtNum(qtyNum) })}</span>
                  </div>
                )}
                <div className="flex justify-between"><span className="text-muted-foreground">{t('bstockDetail.orderValue')}</span><span className="tabular-nums font-bold">{fmtNum(amount)}</span></div>
                <div className="flex justify-between"><span className="text-muted-foreground">{t('bstockDetail.fee')}</span><span className="tabular-nums font-bold">{fmtNum(commission)}</span></div>
                {isLevBuy && <div className="flex justify-between"><span className="text-muted-foreground">{t('bstockDetail.borrowed')}</span><span className="tabular-nums font-bold text-amber-400">{fmtNum(amount - amount / leverage)}</span></div>}
                <div className="flex justify-between font-black text-sm pt-1.5 border-t border-border/40">
                  <span>{side === 'BUY' ? t('bstockDetail.cashNeeded') : t('bstockDetail.proceeds')}</span>
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
                <p className="text-[10px] text-center text-muted-foreground/60">{t('bstockDetail.footer')}</p>
              </div>
            </CardContent>
          </Card>
          )}

          {/* 当前持仓 */}
          {held > 0 && (
            <Card>
              <CardContent className="p-4">
                <div className="text-xs text-muted-foreground mb-2">{t('bstockDetail.currentHoldings')}</div>
                <div className="flex items-center justify-between">
                  <div>
                    <div className="text-base font-bold tabular-nums">{fmtNum(held)} <span className="text-xs text-muted-foreground font-normal">{t('bstockDetail.shares')}</span></div>
                    <div className="text-xs text-muted-foreground">{t('bstockDetail.avgCost')} {fmtNum(position?.avgCost ?? 0)}</div>
                  </div>
                  <div className="text-right">
                    <div className="text-xs text-muted-foreground">{t('bstockDetail.marketValue')}</div>
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
