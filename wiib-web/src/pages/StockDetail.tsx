import { useState, useMemo, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { stockApi, orderApi, newsApi, userApi, buffApi } from '../api';
import { useQuote } from '../hooks/useQuote';
import { TickChart } from '../components/TickChart';
import { useUserStore } from '../stores/userStore';
import { useToast } from '../components/ui/use-toast';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Select } from '../components/ui/select';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { cn } from '../lib/utils';
import {
  ArrowLeft,
  TrendingUp,
  TrendingDown,
  ShoppingCart,
  HandCoins,
  Building2,
  Loader2,
  Newspaper,
  LogIn,
  ChevronLeft,
  ChevronRight,
  Calendar,
  Sparkles,
  ChartCandlestick,
} from 'lucide-react';
import type { Stock, DayTick, News, Position, UserBuff } from '../types';

export function StockDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const user = useUserStore((s) => s.user);
  const token = useUserStore((s) => s.token);
  const userReady = !token || !!user; // 无token或user已加载
  const { toast } = useToast();

  const [stock, setStock] = useState<Stock | null>(null);
  const [initialTicks, setInitialTicks] = useState<DayTick[]>([]);
  const [news, setNews] = useState<News[]>([]);
  const [newsIndex, setNewsIndex] = useState(-1);
  const [newsDate, setNewsDate] = useState<string>(''); // yyyy-MM-dd
  const [newsLoading, setNewsLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [quantity, setQuantity] = useState(100);
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [limitPrice, setLimitPrice] = useState('');
  const [useLeverage, setUseLeverage] = useState(false);
  const [leverageMultiple, setLeverageMultiple] = useState(10);
  const [submitting, setSubmitting] = useState(false);
  const [position, setPosition] = useState<Position | null>(null);
  const [discountBuff, setDiscountBuff] = useState<UserBuff | null>(null);
  const [useBuff, setUseBuff] = useState(false);

  // 生成最近5个交易日（排除周末）
  const tradingDays = useMemo(() => {
    const days: { date: string; label: string }[] = [];
    const today = new Date();
    let d = new Date(today);
    while (days.length < 5) {
      const dayOfWeek = d.getDay();
      if (dayOfWeek !== 0 && dayOfWeek !== 6) {
        const yyyy = d.getFullYear();
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');
        days.push({
          date: `${yyyy}-${mm}-${dd}`,
          label: `${d.getMonth() + 1}月${d.getDate()}日`,
        });
      }
      d.setDate(d.getDate() - 1);
    }
    return days;
  }, []);

  // 加载新闻
  const loadNews = async (stockCode: string, date: string) => {
    setNewsLoading(true);
    try {
      const items = await newsApi.byStock(stockCode, date);
      setNews(items);
      setNewsIndex(-1);
    } catch {
      setNews([]);
    } finally {
      setNewsLoading(false);
    }
  };

  // 行情订阅：传入历史数据，返回实时更新的ticks
  const { quote, realtimeTicks } = useQuote(stock?.code, initialTicks);
  const currentPrice = quote?.price ?? stock?.price ?? 0;
  const quoteTime = quote?.timestamp
    ? new Date(quote.timestamp).toLocaleTimeString('zh-CN', { hour12: false })
    : null;

  // 加载折扣Buff
  useEffect(() => {
    if (user) {
      buffApi.status().then((s) => {
        const buff = s.todayBuff;
        if (buff && buff.buffType.startsWith('DISCOUNT_') && !buff.isUsed) {
          setDiscountBuff(buff);
        } else {
          setDiscountBuff(null);
        }
      }).catch(() => {});
    } else {
      setDiscountBuff(null);
    }
  }, [user]);

  const requestKey = id && userReady ? `stock-detail:id=${id}:refresh=${refreshNonce}:user=${!!user}` : null;
  useEffect(() => {
      if (requestKey == null) return;
      if (!id) return;
      const stockId = Number(id);
      if (!Number.isFinite(stockId)) return;

      let cancelled = false;

      setLoading(true);
      setLoadError(null);
      setStock(null);
      setInitialTicks([]);
      setNews([]);
      setNewsIndex(-1);
      setNewsDate('');
      setPosition(null);

      Promise.all([
        stockApi.detail(stockId),
        stockApi.ticks(stockId),
        user ? userApi.positions() : Promise.resolve([])
      ])
        .then(([s, t, positions]) => {
          if (cancelled) return;

          setStock(s);
          setInitialTicks(t);
          setLimitPrice((s.price ?? 0).toFixed(2));

          // 查找当前股票的持仓
          if (user && Array.isArray(positions)) {
            const currentPosition = positions.find(p => p.stockId === s.id);
            setPosition(currentPosition || null);
          }

          // 默认加载当天新闻
          if (s.code) {
            const today = new Date();
            const dayOfWeek = today.getDay();
            // 如果是周末，取上一个交易日
            if (dayOfWeek === 0) today.setDate(today.getDate() - 2);
            else if (dayOfWeek === 6) today.setDate(today.getDate() - 1);
            const yyyy = today.getFullYear();
            const mm = String(today.getMonth() + 1).padStart(2, '0');
            const dd = String(today.getDate()).padStart(2, '0');
            const todayStr = `${yyyy}-${mm}-${dd}`;
            setNewsDate(todayStr);
            newsApi.byStock(s.code, todayStr).then(setNews).catch(() => setNews([]));
          }
        })
        .catch((e: unknown) => {
          if (cancelled) return;
          const msg = e instanceof Error ? e.message : '加载失败';
          setLoadError(msg);
          toast('加载股票详情失败', 'error', { description: msg });
        })
        .finally(() => {
          if (cancelled) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
    }, [requestKey]);

  // 提取交易计算逻辑
  const tradeCalc = useMemo(() => {
    if (!user) return null;
    const price = orderType === 'LIMIT' ? (Number(limitPrice) || currentPrice) : currentPrice;
    let tradeAmount = quantity * price;

    // 应用折扣
    let discountRate = 1;
    if (useBuff && discountBuff && orderType === 'MARKET') {
      const match = discountBuff.buffType.match(/DISCOUNT_(\d+)/);
      if (match) {
        discountRate = Number(match[1]) / 100;
        tradeAmount = tradeAmount * discountRate;
      }
    }

    const estimatedCommission = Math.max(tradeAmount * 0.0005, 5);
    const leverageEnabled = useLeverage && orderType === 'MARKET';
    const marginRequired = leverageEnabled ? Math.ceil((tradeAmount / leverageMultiple) * 100) / 100 : tradeAmount;
    const borrowed = leverageEnabled ? tradeAmount - marginRequired : 0;
    const cashNeed = marginRequired + estimatedCommission;
    const insufficient = cashNeed > user.balance;
    const limitPriceInvalid = orderType === 'LIMIT' && limitPrice !== '' && Number(limitPrice) > 0 &&
                              (Number(limitPrice) < currentPrice * 0.5 || Number(limitPrice) > currentPrice * 1.5);

    return {
      price,
      tradeAmount,
      originalAmount: quantity * price,
      discountRate,
      estimatedCommission,
      leverageEnabled,
      marginRequired,
      borrowed,
      cashNeed,
      insufficient,
      limitPriceInvalid,
    };
  }, [user, orderType, limitPrice, currentPrice, quantity, useLeverage, leverageMultiple, useBuff, discountBuff]);

  const handleOrder = async (side: 'buy' | 'sell') => {
    if (!stock || !user) return;
    setSubmitting(true);
    try {
      const request = {
        stockId: stock.id,
        quantity,
        orderType,
        limitPrice: orderType === 'LIMIT' ? parseFloat(limitPrice) : undefined,
        leverageMultiple: side === 'buy' && orderType === 'MARKET' && useLeverage ? leverageMultiple : 1,
        useBuffId: side === 'buy' && orderType === 'MARKET' && useBuff && discountBuff ? discountBuff.id : undefined,
      };
      if (side === 'buy') {
        await orderApi.buy(request);
        // 使用折扣后刷新状态
        if (useBuff && discountBuff) {
          setDiscountBuff(null);
          setUseBuff(false);
        }
      } else {
        await orderApi.sell(request);
      }
      toast('下单成功', 'success');
      setRefreshNonce((n) => n + 1);
      userApi.portfolio().then((u) => useUserStore.setState({ user: u })).catch(() => {});
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '下单失败';
      toast(msg, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto p-4 space-y-4">
        <Skeleton className="h-8 w-20" />
        <Card>
          <CardContent className="p-6">
            <div className="flex justify-between">
              <div className="space-y-2">
                <Skeleton className="h-8 w-32" />
                <Skeleton className="h-4 w-24" />
              </div>
              <div className="space-y-2 text-right">
                <Skeleton className="h-10 w-28" />
                <Skeleton className="h-4 w-20" />
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-6">
            <Skeleton className="h-48 w-full" />
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!stock) {
    return (
      <div className="max-w-4xl mx-auto p-4">
        <Card>
          <CardContent className="p-8 text-center">
            <div className="text-muted-foreground">
              {loadError ? '加载失败' : '股票不存在'}
            </div>
            {loadError && (
              <div className="mt-4 flex justify-center gap-2">
                <Button variant="outline" size="sm" onClick={() => setRefreshNonce((n) => n + 1)}>
                  重试
                </Button>
                <Button size="sm" onClick={() => navigate('/stocks')}>
                  返回列表
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    );
  }

  // 计算动态涨跌（基于实时价格）
  const prevClose = stock.prevClose ?? stock.openPrice ?? currentPrice;
  const change = currentPrice - prevClose;
  const changePct = prevClose > 0 ? (change / prevClose) * 100 : 0;
  
  const isUp = change > 0;
  const isDown = change < 0;

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      {/* Back Button */}
      <Button variant="ghost" size="sm" onClick={() => navigate(-1)} className="gap-1">
        <ArrowLeft className="w-4 h-4" />
        返回
      </Button>

      {/* Header Card */}
      <Card>
        <CardContent className="p-6">
          <div className="flex justify-between items-start">
            <div>
              <h1 className="text-2xl font-bold">{stock.name}</h1>
              <span className="text-sm text-muted-foreground">{stock.code}</span>
              {stock.industry && (
                <Badge variant="secondary" className="text-xs mt-1 block w-fit">
                  {stock.industry}
                </Badge>
              )}
            </div>
            <div className="text-right">
              <div className="flex items-center justify-end gap-2">
                {isUp ? (
                  <TrendingUp className="w-5 h-5 text-green-500" />
                ) : isDown ? (
                  <TrendingDown className="w-5 h-5 text-red-500" />
                ) : null}
                <span className={cn(
                  "text-3xl font-bold tabular-nums",
                  isUp ? "text-green-500" : isDown ? "text-red-500" : "text-muted-foreground"
                )}>
                  {currentPrice.toFixed(2)}
                </span>
              </div>
              {quoteTime && (
                <div className="mt-2 flex items-center justify-end">
                  <Badge variant="secondary" className="text-xs gap-1">
                    <span className="inline-block w-1.5 h-1.5 rounded-full bg-success" />
                    实时 {quoteTime}
                  </Badge>
                </div>
              )}
              <span className={cn(
                "text-sm tabular-nums",
                isUp ? "text-green-500" : isDown ? "text-red-500" : "text-muted-foreground"
              )}>
                {isUp ? '+' : ''}{change.toFixed(2)} ({isUp ? '+' : ''}{changePct.toFixed(2)}%)
              </span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Chart Card */}
      <Card>
        <CardContent className="p-4">
          <TickChart ticks={realtimeTicks} prevClose={stock.prevClose} />
          <div className="mt-3 flex justify-end">
            <Button
              variant="outline"
              size="sm"
              className="gap-1.5 text-xs"
              onClick={() => navigate(`/stock/${id}/kline`, { state: { name: stock.name, code: stock.code } })}
            >
              <ChartCandlestick className="w-3.5 h-3.5" />
              历史K线
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Recent Trend Card */}
      {stock.trendList && stock.trendList.length > 0 && (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <div className="p-1.5 rounded-lg bg-primary/10">
                <TrendingUp className="w-4 h-4 text-primary" />
              </div>
              近十日走势
            </CardTitle>
          </CardHeader>
          <CardContent>
            <TrendChart trendList={stock.trendList} />
          </CardContent>
        </Card>
      )}

      {/* Info Grid */}
      <Card>
        <CardContent className="p-4">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <InfoItem label="开盘" value={stock.openPrice?.toFixed(2)} />
            <InfoItem label="最高" value={stock.highPrice?.toFixed(2)} isHigh />
            <InfoItem label="最低" value={stock.lowPrice?.toFixed(2)} isLow />
            <InfoItem label="昨收" value={stock.prevClose?.toFixed(2)} />
          </div>
        </CardContent>
      </Card>

      {/* Trade Card */}
      <Card className="overflow-hidden">
        <CardHeader className="pb-4 bg-linear-to-br from-primary/5 via-primary/3 to-transparent border-b">
          <CardTitle className="flex items-center justify-between text-base">
            <div className="flex items-center gap-2">
              <div className="p-2 rounded-lg bg-primary/10 ring-1 ring-primary/20">
                <ShoppingCart className="w-4 h-4 text-primary" />
              </div>
              <span>交易面板</span>
            </div>
            {user && (
              <div className="text-xs font-normal text-muted-foreground">
                余额 <span className="font-semibold text-foreground tabular-nums">{user.balance.toFixed(2)}</span>
              </div>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="p-6">
          {!user ? (
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 p-6 rounded-xl bg-linear-to-br from-muted/50 to-muted/30 border border-border/50">
              <div>
                <div className="text-sm font-semibold mb-1">登录后可买卖该股票</div>
                <div className="text-xs text-muted-foreground">下单、撤单与持仓管理需要登录</div>
              </div>
              <Button onClick={() => navigate('/login')} size="lg" className="shrink-0">
                <LogIn className="w-4 h-4" />
                去登录
              </Button>
            </div>
          ) : (
            <div className="space-y-5">
              {/* Current Position */}
              {position && (
                <div className="relative overflow-hidden rounded-xl bg-linear-to-br from-primary/8 via-primary/5 to-transparent border border-primary/20 p-4 shadow-sm">
                  <div className="absolute top-0 right-0 w-24 h-24 bg-primary/10 rounded-full blur-2xl -translate-y-1/2 translate-x-1/2" />
                  <div className="relative flex justify-between items-start">
                    <div>
                      <div className="text-xs font-medium text-muted-foreground mb-1">当前持仓</div>
                      <div className="text-2xl font-bold tabular-nums">{position.quantity}</div>
                      <div className="text-xs text-muted-foreground mt-0.5">股</div>
                    </div>
                    <div className="text-right">
                      <div className="text-xs text-muted-foreground mb-1">成本价</div>
                      <div className="text-lg font-semibold tabular-nums">{position.avgCost.toFixed(2)}</div>
                      <div className={cn(
                        "text-sm font-bold tabular-nums mt-1",
                        (() => {
                          const marketValue = position.quantity * currentPrice;
                          const profit = marketValue - (position.quantity * position.avgCost);
                          return profit >= 0 ? "text-green-500" : "text-red-500";
                        })()
                      )}>
                        {(() => {
                          const marketValue = position.quantity * currentPrice;
                          const profit = marketValue - (position.quantity * position.avgCost);
                          const profitPct = position.avgCost > 0 ? (profit / (position.quantity * position.avgCost)) * 100 : 0;
                          return `${profit >= 0 ? '+' : ''}${profitPct.toFixed(2)}%`;
                        })()}
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Trade Inputs */}
              <div className="space-y-5">
                {/* 订单类型切换 */}
                <div className="flex gap-2 p-1 bg-muted/50 rounded-lg border">
                  <button
                    type="button"
                    onClick={() => {
                      setOrderType('MARKET');
                    }}
                    className={cn(
                      "flex-1 py-2.5 px-4 rounded-md text-sm font-medium transition-all",
                      orderType === 'MARKET'
                        ? "bg-background shadow-sm border border-border/50"
                        : "text-muted-foreground hover:text-foreground"
                    )}
                  >
                    市价单
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setOrderType('LIMIT');
                      setUseLeverage(false);
                    }}
                    className={cn(
                      "flex-1 py-2.5 px-4 rounded-md text-sm font-medium transition-all",
                      orderType === 'LIMIT'
                        ? "bg-background shadow-sm border border-border/50"
                        : "text-muted-foreground hover:text-foreground"
                    )}
                  >
                    限价单
                  </button>
                </div>

                {/* 数量输入 */}
                <div className="space-y-3">
                  <label className="text-sm font-medium">交易数量</label>
                  <Input
                    type="number"
                    value={quantity}
                    onChange={(e) => {
                      const next = Number(e.target.value);
                      setQuantity(Number.isFinite(next) ? Math.max(1, Math.floor(next)) : 1);
                    }}
                    min={1}
                    className="h-12 text-base font-semibold"
                    placeholder="输入股数"
                  />
                  <div className="flex gap-2">
                    {[100, 500, 1000].map((n) => (
                      <Button
                        key={n}
                        type="button"
                        variant="outline"
                        size="sm"
                        className="flex-1 h-9"
                        onClick={() => setQuantity(n)}
                      >
                        {n}股
                      </Button>
                    ))}
                  </div>
                </div>

                {/* 杠杆设置 */}
                {orderType === 'MARKET' && (
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <label className="text-sm font-medium">杠杆交易</label>
                      <button
                        type="button"
                        onClick={() => {
                          if (user.bankrupt || useBuff) return;
                          setUseLeverage((v) => !v);
                        }}
                        disabled={user.bankrupt || useBuff}
                        className={cn(
                          "relative inline-flex h-6 w-11 items-center rounded-full transition-colors",
                          useLeverage ? "bg-primary" : "bg-muted-foreground/30",
                          (user.bankrupt || useBuff) && "opacity-50 cursor-not-allowed"
                        )}
                      >
                        <span
                          className={cn(
                            "inline-block h-4 w-4 transform rounded-full bg-background transition-transform shadow-sm",
                            useLeverage ? "translate-x-6" : "translate-x-1"
                          )}
                        />
                      </button>
                    </div>
                    {useBuff && (
                      <div className="text-xs text-muted-foreground">使用折扣时不支持杠杆</div>
                    )}
                    {useLeverage && (
                      <div className="animate-in slide-in-from-top-2 fade-in duration-200">
                        <Select
                          value={String(leverageMultiple)}
                          onChange={(e) => setLeverageMultiple(Number(e.target.value))}
                          disabled={user.bankrupt}
                          className="h-11"
                        >
                          {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50].map((v) => (
                            <option key={v} value={String(v)}>{v}x 杠杆</option>
                          ))}
                        </Select>
                      </div>
                    )}
                  </div>
                )}

                {/* 折扣Buff */}
                {orderType === 'MARKET' && discountBuff && (
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <label className="text-sm font-medium flex items-center gap-2">
                        <Sparkles className="w-4 h-4 text-yellow-500" />
                        使用折扣
                      </label>
                      <button
                        type="button"
                        onClick={() => {
                          if (useLeverage) return;
                          setUseBuff((v) => !v);
                        }}
                        disabled={useLeverage}
                        className={cn(
                          "relative inline-flex h-6 w-11 items-center rounded-full transition-colors",
                          useBuff ? "bg-yellow-500" : "bg-muted-foreground/30",
                          useLeverage && "opacity-50 cursor-not-allowed"
                        )}
                      >
                        <span
                          className={cn(
                            "inline-block h-4 w-4 transform rounded-full bg-background transition-transform shadow-sm",
                            useBuff ? "translate-x-6" : "translate-x-1"
                          )}
                        />
                      </button>
                    </div>
                    {useLeverage && (
                      <div className="text-xs text-muted-foreground">使用杠杆时不支持折扣</div>
                    )}
                    {useBuff && (
                      <div className="flex items-center gap-2 text-sm bg-yellow-500/10 border border-yellow-500/20 rounded-lg px-3 py-2 animate-in slide-in-from-top-2 fade-in duration-200">
                        <Badge className="bg-yellow-100 text-yellow-700">{discountBuff.buffName}</Badge>
                        <span className="text-muted-foreground text-xs">本次买入将享受整单折扣</span>
                      </div>
                    )}
                  </div>
                )}

                {/* 限价输入 */}
                {orderType === 'LIMIT' && (
                  <div className="space-y-3 animate-in slide-in-from-top-2 fade-in duration-200">
                    <label className="text-sm font-medium">限价价格</label>
                    <Input
                      type="number"
                      value={limitPrice}
                      onChange={(e) => setLimitPrice(e.target.value)}
                      step="0.01"
                      className={cn(
                        "h-12 text-base font-semibold",
                        limitPrice && Number(limitPrice) > 0 &&
                        (Number(limitPrice) < currentPrice * 0.5 || Number(limitPrice) > currentPrice * 1.5)
                        ? "border-destructive focus-visible:ring-destructive"
                        : ""
                      )}
                      placeholder="输入限价"
                    />
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-muted-foreground">允许范围</span>
                      <span className="font-medium tabular-nums">
                        {(currentPrice * 0.5).toFixed(2)} ~ {(currentPrice * 1.5).toFixed(2)}
                      </span>
                    </div>
                    {limitPrice && Number(limitPrice) > 0 &&
                     (Number(limitPrice) < currentPrice * 0.5 || Number(limitPrice) > currentPrice * 1.5) && (
                      <div className="flex items-center gap-2 text-xs text-destructive bg-destructive/10 px-3 py-2 rounded-lg animate-in fade-in slide-in-from-top-1">
                        <span className="w-1 h-1 rounded-full bg-destructive" />
                        限价超出允许范围
                      </div>
                    )}
                  </div>
                )}

                {/* Calculation & Validation */}
                {tradeCalc && (
                  <div className="rounded-xl border border-border/50 overflow-hidden bg-linear-to-br from-muted/30 to-muted/10">
                    {/* 交易明细 */}
                    <div className="p-4 space-y-3">
                      <div className="flex justify-between items-center">
                        <span className="text-xs text-muted-foreground">参考单价</span>
                        <span className="text-sm font-semibold tabular-nums">{tradeCalc.price.toFixed(2)}</span>
                      </div>
                      {tradeCalc.discountRate < 1 && (
                        <div className="flex justify-between items-center">
                          <span className="text-xs text-muted-foreground flex items-center gap-1">
                            <Sparkles className="w-3 h-3 text-yellow-500" />
                            折扣
                          </span>
                          <span className="text-sm font-semibold tabular-nums text-yellow-600">
                            {(tradeCalc.discountRate * 100).toFixed(0)}% (-{(tradeCalc.originalAmount - tradeCalc.tradeAmount).toFixed(2)})
                          </span>
                        </div>
                      )}
                      <div className="flex justify-between items-center">
                        <span className="text-xs text-muted-foreground">手续费</span>
                        <span className="text-sm font-medium tabular-nums text-muted-foreground">
                          {tradeCalc.estimatedCommission.toFixed(2)}
                        </span>
                      </div>
                      {tradeCalc.leverageEnabled && (
                        <>
                          <div className="h-px bg-linear-to-r from-transparent via-border to-transparent" />
                          <div className="flex justify-between items-center">
                            <span className="text-xs text-muted-foreground">保证金</span>
                            <span className="text-sm font-medium tabular-nums">
                              {tradeCalc.marginRequired.toFixed(2)}
                            </span>
                          </div>
                          <div className="flex justify-between items-center">
                            <span className="text-xs text-muted-foreground">借款金额</span>
                            <span className="text-sm font-semibold tabular-nums text-amber-500">
                              {tradeCalc.borrowed.toFixed(2)}
                            </span>
                          </div>
                        </>
                      )}
                    </div>

                    {/* 总计 */}
                    <div className="px-4 py-3 bg-muted/40 border-t border-border/50">
                      <div className="flex justify-between items-center mb-2">
                        <span className="text-sm font-medium">交易总额</span>
                        <div className="flex items-center gap-2">
                          {tradeCalc.discountRate < 1 && (
                            <span className="text-sm tabular-nums text-muted-foreground line-through">
                              {tradeCalc.originalAmount.toFixed(2)}
                            </span>
                          )}
                          <span className="text-lg font-bold tabular-nums text-primary">
                            {tradeCalc.tradeAmount.toFixed(2)}
                          </span>
                        </div>
                      </div>
                      <div className="flex justify-between items-center">
                        <span className="text-sm font-medium">需要现金</span>
                        <span className={cn(
                          "text-lg font-bold tabular-nums",
                          tradeCalc.insufficient ? "text-destructive" : "text-primary"
                        )}>
                          {tradeCalc.cashNeed.toFixed(2)}
                        </span>
                      </div>
                    </div>

                    {/* 快捷买入仓位 */}
                    <div className="px-4 py-3 border-t border-border/50 bg-muted/20">
                      <div className="text-xs font-medium text-muted-foreground mb-2">快捷买入</div>
                      <div className="grid grid-cols-3 gap-2">
                      {[0.25, 0.5, 1].map((pct) => {
                        if (tradeCalc.price <= 0) return null;

                        const availableCash = user.balance * pct;
                        let maxCanBuy = 0;

                        if (tradeCalc.leverageEnabled) {
                          const estimatedShares = (availableCash * leverageMultiple) / tradeCalc.price;
                          const estimatedAmount = estimatedShares * tradeCalc.price;
                          const estimatedFee = Math.max(estimatedAmount * 0.0005, 5);
                          const adjustedCash = availableCash - estimatedFee;
                          maxCanBuy = Math.floor((adjustedCash * leverageMultiple) / tradeCalc.price);
                        } else {
                          const minFee = 5;
                          const estimatedShares1 = (availableCash - minFee) / tradeCalc.price;
                          const estimatedAmount1 = estimatedShares1 * tradeCalc.price;
                          const actualFee1 = Math.max(estimatedAmount1 * 0.0005, minFee);

                          if (actualFee1 <= minFee) {
                            maxCanBuy = Math.floor((availableCash - minFee) / tradeCalc.price);
                          } else {
                            maxCanBuy = Math.floor(availableCash / (tradeCalc.price * 1.0005));
                          }
                        }

                        maxCanBuy = Math.max(0, maxCanBuy);

                        return (
                          <Button
                            key={pct}
                            type="button"
                            variant="outline"
                            size="sm"
                            className="h-10 font-medium"
                            onClick={() => setQuantity(Math.max(1, maxCanBuy))}
                            disabled={maxCanBuy < 1}
                          >
                            {pct === 1 ? '全仓' : `${pct * 100}%`}
                          </Button>
                        );
                      })}
                      </div>
                    </div>
                  </div>
                )}

                {/* Insufficient Funds Warning */}
                {tradeCalc?.insufficient && (
                  <div className="flex items-center gap-2 text-sm font-medium text-destructive bg-destructive/10 px-4 py-3 rounded-xl border border-destructive/20 animate-in fade-in slide-in-from-top-2">
                    <span className="w-2 h-2 rounded-full bg-destructive animate-pulse" />
                    余额不足，请调整数量
                  </div>
                )}

                {/* Action Buttons */}
                <div className="grid grid-cols-2 gap-3 pt-2">
                  <Button
                    size="lg"
                    className="h-14 text-base font-semibold bg-linear-to-br from-red-500 to-red-600 hover:from-red-600 hover:to-red-700 text-white shadow-lg shadow-red-500/25 transition-all hover:shadow-xl hover:shadow-red-500/30"
                    onClick={() => handleOrder('buy')}
                    disabled={
                      submitting ||
                      user.bankrupt ||
                      tradeCalc?.insufficient ||
                      tradeCalc?.limitPriceInvalid
                    }
                  >
                    {submitting ? (
                      <Loader2 className="w-5 h-5 animate-spin" />
                    ) : (
                      <>
                        <ShoppingCart className="w-5 h-5" />
                        买入
                      </>
                    )}
                  </Button>
                  <Button
                    size="lg"
                    variant="outline"
                    className="h-14 text-base font-semibold border-2 hover:bg-muted/50 transition-all"
                    onClick={() => handleOrder('sell')}
                    disabled={
                      submitting ||
                      user.bankrupt ||
                      tradeCalc?.limitPriceInvalid
                    }
                  >
                    {submitting ? (
                      <Loader2 className="w-5 h-5 animate-spin" />
                    ) : (
                      <>
                        <HandCoins className="w-5 h-5" />
                        卖出
                      </>
                    )}
                  </Button>
                </div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Company Description */}
      {stock.companyDesc && (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <div className="p-1.5 rounded-lg bg-primary/10">
                <Building2 className="w-4 h-4 text-primary" />
              </div>
              公司简介
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground leading-relaxed">
              {stock.companyDesc}
            </p>
          </CardContent>
        </Card>
      )}

      {/* News */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <div className="p-1.5 rounded-lg bg-primary/10">
              <Newspaper className="w-4 h-4 text-primary" />
            </div>
            相关新闻
          </CardTitle>
        </CardHeader>
        <CardContent>
          {/* 日期按钮 */}
          <div className="flex items-center gap-2 mb-4 overflow-x-auto pb-2">
            <Calendar className="w-4 h-4 text-muted-foreground shrink-0" />
            {tradingDays.map((day) => (
              <Button
                key={day.date}
                variant={newsDate === day.date ? 'default' : 'outline'}
                size="sm"
                className="shrink-0 text-xs"
                onClick={() => {
                  if (stock?.code && day.date !== newsDate) {
                    setNewsDate(day.date);
                    loadNews(stock.code, day.date);
                  }
                }}
              >
                {day.label}
              </Button>
            ))}
          </div>

          {/* 新闻列表 */}
          {!newsDate ? (
            <div className="py-8 text-center text-muted-foreground text-sm">
              请选择日期查看新闻
            </div>
          ) : newsLoading ? (
            <div className="py-8 flex justify-center">
              <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
            </div>
          ) : news.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground text-sm">
              该日期暂无新闻
            </div>
          ) : (
            <div className="border rounded-lg divide-y">
              {news.map((item, idx) => (
                <button
                  type="button"
                  key={item.id}
                  onClick={() => setNewsIndex(idx)}
                  className="w-full text-left p-4 cursor-pointer hover:bg-accent/50 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <h4 className="text-sm font-medium leading-tight">{item.title}</h4>
                    <Badge variant="secondary" className="text-xs shrink-0">
                      {item.newsType === 'stock' ? '股票' : '公司'}
                    </Badge>
                  </div>
                  <p className="text-xs text-muted-foreground line-clamp-2 mb-2">
                    {item.content}
                  </p>
                  <span className="text-xs text-muted-foreground/60">
                    {item.publishTime?.replace('T', ' ').substring(11, 16)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* News Dialog */}
      <Dialog open={newsIndex >= 0} onClose={() => setNewsIndex(-1)}>
        {newsIndex >= 0 && news[newsIndex] && (
          <>
            <DialogHeader>
              <Badge variant="secondary" className="text-xs w-fit mb-2">
                {news[newsIndex].newsType === 'stock' ? '股票新闻' : '公司新闻'}
              </Badge>
              <h3 className="text-lg font-semibold leading-tight pr-6">
                {news[newsIndex].title}
              </h3>
              <span className="text-xs text-muted-foreground">
                {news[newsIndex].publishTime?.replace('T', ' ').substring(0, 16)}
              </span>
            </DialogHeader>
            <DialogContent>
              <p className="text-sm text-muted-foreground leading-relaxed whitespace-pre-wrap">
                {news[newsIndex].content}
              </p>
            </DialogContent>
            <DialogFooter>
              <Button
                variant="outline"
                size="sm"
                disabled={newsIndex <= 0}
                onClick={() => setNewsIndex(newsIndex - 1)}
              >
                <ChevronLeft className="w-4 h-4" />
                上一条
              </Button>
              <span className="text-sm text-muted-foreground">
                {newsIndex + 1} / {news.length}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={newsIndex >= news.length - 1}
                onClick={() => setNewsIndex(newsIndex + 1)}
              >
                下一条
                <ChevronRight className="w-4 h-4" />
              </Button>
            </DialogFooter>
          </>
        )}
      </Dialog>
    </div>
  );
}

function InfoItem({ label, value, isHigh, isLow }: { label: string; value?: string; isHigh?: boolean; isLow?: boolean }) {
  return (
    <div className="text-center p-3 rounded-lg bg-muted/50">
      <span className="text-xs text-muted-foreground block mb-1">{label}</span>
      <span className={cn(
        "text-sm font-medium tabular-nums",
        isHigh && "text-green-500",
        isLow && "text-red-500"
      )}>
        {value ?? '--'}
      </span>
    </div>
  );
}

function TrendChart({ trendList }: { trendList: number[] }) {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2 py-1">
        {trendList.map((trend, i) => (
          <div key={i} className="flex flex-col items-center gap-1 flex-1">
            <div
              className={cn(
                "w-2.5 h-2.5 rounded-full transition-all",
                trend === 1 && "bg-green-500 shadow-[0_0_8px_2px_rgba(34,197,94,0.5)]",
                trend === -1 && "bg-red-500 shadow-[0_0_8px_2px_rgba(239,68,68,0.5)]",
                trend === 0 && "bg-zinc-400 dark:bg-zinc-500",
                i === trendList.length - 1 && "w-3 h-3 animate-pulse"
              )}
            />
            <span className={cn(
              "text-[10px]",
              trend === 1 && "text-green-500",
              trend === -1 && "text-red-500",
              trend === 0 && "text-muted-foreground"
            )}>
              {i === trendList.length - 1 ? '今' : 9 - i}
            </span>
          </div>
        ))}
      </div>

      <div className="flex justify-center gap-4 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-green-500 shadow-[0_0_6px_1px_rgba(34,197,94,0.5)]" />
          涨
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-red-500 shadow-[0_0_6px_1px_rgba(239,68,68,0.5)]" />
          跌
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-zinc-400" />
          平
        </span>
      </div>
    </div>
  );
}
