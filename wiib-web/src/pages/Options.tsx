import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { optionApi, stockApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { Input } from '../components/ui/input';
import { Select } from '../components/ui/select';
import { useToast } from '../components/ui/use-toast';
import { cn, isTradingHours } from '../lib/utils';
import { Briefcase, ClipboardList, RefreshCcw, Clock, TrendingUp, Search } from 'lucide-react';
import type { OptionPosition, OptionOrder, Stock, OptionChainItem, OptionQuote } from '../types';

export function Options() {
  const navigate = useNavigate();
  const { user, token } = useUserStore();
  const { toast } = useToast();
  const [positions, setPositions] = useState<OptionPosition[]>([]);
  const [orders, setOrders] = useState<OptionOrder[]>([]);
  const [tab, setTab] = useState<'positions' | 'orders'>('positions');
  const [loading, setLoading] = useState(true);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [sellQty, setSellQty] = useState<Record<number, number>>({});
  const [submitting, setSubmitting] = useState<number | null>(null);
  const [tutorialOpen, setTutorialOpen] = useState(true);

  // 期权交易状态
  const [stocks, setStocks] = useState<Stock[]>([]);
  const [selectedStockId, setSelectedStockId] = useState<number | null>(null);
  const [optionChain, setOptionChain] = useState<OptionChainItem[]>([]);
  const [selectedContract, setSelectedContract] = useState<OptionChainItem | null>(null);
  const [quote, setQuote] = useState<OptionQuote | null>(null);
  const [buyQuantity, setBuyQuantity] = useState(1);
  const [loadingChain, setLoadingChain] = useState(false);
  const [loadingQuote, setLoadingQuote] = useState(false);
  const [buying, setBuying] = useState(false);

  useEffect(() => {
    if (!token) {
      navigate('/login');
    }
  }, [token, navigate]);

  // 加载股票列表
  useEffect(() => {
    stockApi.list()
      .then(setStocks)
      .catch(() => setStocks([]));
  }, []);

  // 选择股票后加载期权链
  useEffect(() => {
    if (!selectedStockId) {
      setOptionChain([]);
      setSelectedContract(null);
      setQuote(null);
      return;
    }

    setLoadingChain(true);
    optionApi.chain(selectedStockId)
      .then((chain) => {
        setOptionChain(chain);
        setSelectedContract(null);
        setQuote(null);
      })
      .catch(() => {
        setOptionChain([]);
        toast('加载期权链失败', 'error');
      })
      .finally(() => setLoadingChain(false));
  }, [selectedStockId, toast]);

  // 选择合约后加载报价
  useEffect(() => {
    if (!selectedContract) {
      setQuote(null);
      return;
    }

    setLoadingQuote(true);
    optionApi.quote(selectedContract.contractId)
      .then(setQuote)
      .catch(() => {
        setQuote(null);
        toast('加载报价失败', 'error');
      })
      .finally(() => setLoadingQuote(false));
  }, [selectedContract, toast]);

  const requestKey = user ? `options:user=${user.id}:refresh=${refreshNonce}` : null;
  useEffect(() => {
      if (requestKey == null) return;
      if (!user) return;
      let cancelled = false;

      setLoading(true);
      Promise.all([
        optionApi.positions(),
        optionApi.orders(undefined, 1, 20)
      ])
        .then(([p, o]) => {
          if (cancelled) return;
          setPositions(p);
          setOrders(o.records);
        })
        .catch(() => {
          if (cancelled) return;
          setPositions([]);
          setOrders([]);
          toast('获取期权数据失败', 'error', { description: '请稍后重试' });
        })
        .finally(() => {
          if (cancelled) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
    }, [requestKey]);

  const handleClose = async (p: OptionPosition) => {
    const qty = sellQty[p.positionId] || 0;
    if (qty <= 0 || qty > p.quantity) {
      toast('请输入有效数量', 'error');
      return;
    }
    setSubmitting(p.positionId);
    try {
      await optionApi.sell({
        contractId: p.contractId,
        quantity: qty,
      });
      toast('平仓成功', 'success');
      setSellQty((prev) => ({ ...prev, [p.positionId]: 0 }));
      setRefreshNonce((n) => n + 1);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '平仓失败';
      toast(msg, 'error');
    } finally {
      setSubmitting(null);
    }
  };

  const handleBuyOption = async () => {
    if (!selectedContract || !quote) return;

    setBuying(true);
    try {
      await optionApi.buy({
        contractId: selectedContract.contractId,
        quantity: buyQuantity,
      });
      toast('买入成功', 'success');
      setBuyQuantity(1);
      setRefreshNonce((n) => n + 1);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '买入失败';
      toast(msg, 'error');
    } finally {
      setBuying(false);
    }
  };

  if (!user) return null;

  const tradingOpen = isTradingHours();

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      {/* 非交易时间提示 */}
      {!tradingOpen && (
        <Card className="border-yellow-500/50 bg-yellow-500/10">
          <CardContent className="p-4 flex items-center gap-3">
            <Clock className="w-5 h-5 text-yellow-500" />
            <div>
              <div className="font-medium">非交易时段</div>
              <div className="text-sm text-muted-foreground">交易时间: 周一至周五 09:30-11:30, 13:00-15:00</div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">期权交易</h1>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setRefreshNonce((n) => n + 1);
            toast('已刷新', 'info');
          }}
        >
          <RefreshCcw className="w-4 h-4" />
          刷新
        </Button>
      </div>

      {/* 玩法说明 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center justify-between text-base">
            <div className="flex items-center gap-2">
              <div className="p-1.5 rounded-lg bg-primary/10">
                <Search className="w-4 h-4 text-primary" />
              </div>
              玩法说明
            </div>
            <Button variant="ghost" size="sm" onClick={() => setTutorialOpen((v) => !v)}>
              {tutorialOpen ? '收起' : '展开'}
            </Button>
          </CardTitle>
        </CardHeader>
        {tutorialOpen && (
          <CardContent className="space-y-4 text-sm leading-relaxed">
            <div className="space-y-2">
              <div className="font-medium">CALL / PUT</div>
              <ul className="list-disc pl-5 space-y-1 text-muted-foreground">
                <li>
                  <span className="inline-flex items-center gap-2">
                    <Badge variant="destructive" className="mt-0.5">CALL</Badge>
                    看涨，涨得越多赚得越多
                  </span>
                </li>
                <li>
                  <span className="inline-flex items-center gap-2">
                    <Badge variant="success" className="mt-0.5">PUT</Badge>
                    看跌，跌得越多赚得越多
                  </span>
                </li>
                <li>仅支持买入开仓，到期前可平仓或等待到期自动结算</li>
              </ul>
            </div>

            <div className="space-y-2">
              <div className="font-medium">到期结算</div>
              <div className="text-muted-foreground space-y-1">
                <div>现金结算，不涉及实际股票交割</div>
                <div>CALL 返还 = max(现价 − 行权价, 0) × 张数</div>
                <div>PUT 返还 = max(行权价 − 现价, 0) × 张数</div>
                <div>返还为 0 时，损失已付权利金</div>
              </div>
            </div>

            <div className="space-y-2">
              <div className="font-medium">示例</div>
              <div className="text-muted-foreground space-y-2">
                <div className="rounded-md border bg-muted/30 p-3 space-y-1">
                  <div className="font-medium text-foreground">买 CALL 到期盈利</div>
                  <div>买入 CALL，行权价 10，权利金 0.50，10 张 → 成本 5.00</div>
                  <div>到期现价 12 → 返还 (12−10)×10 = 20.00 → 盈利 15.00</div>
                </div>
                <div className="rounded-md border bg-muted/30 p-3 space-y-1">
                  <div className="font-medium text-foreground">买 PUT 到期盈利</div>
                  <div>买入 PUT，行权价 10，权利金 0.40，10 张 → 成本 4.00</div>
                  <div>到期现价 7 → 返还 (10−7)×10 = 30.00 → 盈利 26.00</div>
                </div>
                <div className="rounded-md border bg-muted/30 p-3 space-y-1">
                  <div className="font-medium text-foreground">提前平仓</div>
                  <div>买入权利金 0.50，当前报价 0.80</div>
                  <div>在持仓中平仓 → 每张盈利 0.30</div>
                </div>
              </div>
            </div>

            <div className="space-y-2">
              <div className="font-medium">操作流程</div>
              <ol className="list-decimal pl-5 space-y-1 text-muted-foreground">
                <li>选股票 → 选合约（类型 + 行权价 + 到期日）</li>
                <li>查看期权报价（现价、权利金）</li>
                <li>输入数量 → 买入开仓</li>
                <li>平仓：在持仓中操作（到期前有效）</li>
              </ol>
            </div>

            <div className="space-y-1 text-muted-foreground">
              <div className="font-medium text-foreground">注意</div>
              <ul className="list-disc pl-5 space-y-1">
                <li>交易需在交易时段内且到期前进行</li>
                <li>总价 = 权利金 × 张数，实际金额以订单记录为准（含手续费）</li>
                <li>持仓盈亏为估算值，不含手续费</li>
              </ul>
            </div>
          </CardContent>
        )}
      </Card>

      {/* 期权交易卡片 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <div className="p-1.5 rounded-lg bg-primary/10">
              <TrendingUp className="w-4 h-4 text-primary" />
            </div>
            买入期权
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* 选择股票 */}
          <div className="space-y-2">
            <label className="text-sm font-medium">选择标的股票</label>
            <Select
              value={selectedStockId?.toString() || ''}
              onChange={(e) => setSelectedStockId(e.target.value ? Number(e.target.value) : null)}
              disabled={!tradingOpen}
              className="h-10"
            >
              <option value="">-- 请选择股票 --</option>
              {stocks.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} ({s.code})
                </option>
              ))}
            </Select>
          </div>

          {/* 加载期权链 */}
          {loadingChain && (
            <div className="flex items-center justify-center py-4">
              <div className="text-sm text-muted-foreground">加载期权链...</div>
            </div>
          )}

          {/* 选择期权合约 */}
          {!loadingChain && optionChain.length > 0 && (
            <div className="space-y-2">
              <label className="text-sm font-medium">选择期权合约</label>
              <div className="grid grid-cols-2 gap-3">
                {/* CALL 看涨期权 */}
                <div className="space-y-2">
                  <div className="text-xs font-medium text-center text-green-500 pb-1 border-b">
                    看涨期权 (CALL)
                  </div>
                  <div className="space-y-2 max-h-64 overflow-y-auto">
                    {optionChain
                      .filter((c) => c.optionType === 'CALL')
                      .map((contract) => (
                        <button
                          key={contract.contractId}
                          type="button"
                          onClick={() => setSelectedContract(contract)}
                          className={cn(
                            "w-full p-2.5 rounded-lg border transition-all text-left",
                            selectedContract?.contractId === contract.contractId
                              ? "bg-green-500/10 border-green-500"
                              : "hover:bg-accent/50 border-border"
                          )}
                        >
                          <div className="text-sm font-semibold">K = {contract.strike.toFixed(2)}</div>
                          <div className="text-xs text-muted-foreground mt-0.5">
                            {contract.expireAt.substring(5, 16).replace('T', ' ')}
                          </div>
                        </button>
                      ))}
                  </div>
                </div>

                {/* PUT 看跌期权 */}
                <div className="space-y-2">
                  <div className="text-xs font-medium text-center text-red-500 pb-1 border-b">
                    看跌期权 (PUT)
                  </div>
                  <div className="space-y-2 max-h-64 overflow-y-auto">
                    {optionChain
                      .filter((c) => c.optionType === 'PUT')
                      .map((contract) => (
                        <button
                          key={contract.contractId}
                          type="button"
                          onClick={() => setSelectedContract(contract)}
                          className={cn(
                            "w-full p-2.5 rounded-lg border transition-all text-left",
                            selectedContract?.contractId === contract.contractId
                              ? "bg-red-500/10 border-red-500"
                              : "hover:bg-accent/50 border-border"
                          )}
                        >
                          <div className="text-sm font-semibold">K = {contract.strike.toFixed(2)}</div>
                          <div className="text-xs text-muted-foreground mt-0.5">
                            {contract.expireAt.substring(5, 16).replace('T', ' ')}
                          </div>
                        </button>
                      ))}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* 期权报价 */}
          {loadingQuote && (
            <div className="flex items-center justify-center py-4">
              <div className="text-sm text-muted-foreground">加载报价...</div>
            </div>
          )}

          {!loadingQuote && quote && (
            <div className="space-y-3 rounded-lg border p-4 bg-muted/30">
              <div className="flex items-center justify-between">
                <span className="text-sm text-muted-foreground">标的现价</span>
                <span className="text-sm font-semibold tabular-nums">{quote.spotPrice.toFixed(2)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-muted-foreground">期权权利金</span>
                <span className="text-lg font-bold tabular-nums text-primary">{quote.premium.toFixed(4)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-muted-foreground">内在价值</span>
                <span className="text-sm font-medium tabular-nums">{quote.intrinsicValue.toFixed(4)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-muted-foreground">时间价值</span>
                <span className="text-sm font-medium tabular-nums">{quote.timeValue.toFixed(4)}</span>
              </div>

              {/* 数量输入 */}
              <div className="space-y-2 pt-2 border-t">
                <label className="text-sm font-medium">购买数量</label>
                <Input
                  type="number"
                  min={1}
                  value={buyQuantity}
                  onChange={(e) => setBuyQuantity(Math.max(1, Number(e.target.value)))}
                  className="h-10"
                />
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">总价</span>
                  <span className="font-bold tabular-nums text-primary">
                    {(quote.premium * buyQuantity).toFixed(2)}
                  </span>
                </div>
              </div>

              {/* 买入按钮 */}
              <Button
                className="w-full"
                onClick={handleBuyOption}
                disabled={buying || !tradingOpen}
              >
                {buying ? '买入中...' : '买入开仓'}
              </Button>
            </div>
          )}

          {!loadingChain && selectedStockId && optionChain.length === 0 && (
            <div className="text-center py-4 text-sm text-muted-foreground">
              该股票暂无可用期权合约
            </div>
          )}
        </CardContent>
      </Card>

      {/* Tabs */}
      <div className="flex gap-2 p-1 bg-card rounded-lg border">
        <TabButton active={tab === 'positions'} onClick={() => setTab('positions')} icon={<Briefcase className="w-4 h-4" />}>
          持仓
        </TabButton>
        <TabButton active={tab === 'orders'} onClick={() => setTab('orders')} icon={<ClipboardList className="w-4 h-4" />}>
          订单
        </TabButton>
      </div>

      {/* Tab Content */}
      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-4">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="flex justify-between items-center">
                  <div className="space-y-2">
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-3 w-16" />
                  </div>
                  <Skeleton className="h-8 w-20" />
                </div>
              ))}
            </div>
          ) : (
            <>
              {tab === 'positions' && (
                positions.length === 0 ? (
                  <EmptyState icon={<Briefcase />} text="暂无期权持仓" />
                ) : (
                  positions.map((p) => (
                    <div key={p.positionId} className="p-4 border-b border-border last:border-b-0">
                      <div className="flex justify-between items-start mb-2">
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{p.stockName}</span>
                            <Badge variant={p.optionType === 'CALL' ? 'destructive' : 'success'} className="text-xs">
                              {p.optionType === 'CALL' ? '看涨' : '看跌'}
                            </Badge>
                            {Date.parse(p.expireAt) <= Date.now() && (
                              <Badge variant="secondary" className="text-xs">已到期</Badge>
                            )}
                          </div>
                          <span className="text-xs text-muted-foreground">{p.stockCode}</span>
                        </div>
                        <div className="text-right">
                          <div className={cn("text-lg font-bold tabular-nums", p.pnl >= 0 ? "text-green-500" : "text-red-500")}>
                            {p.pnl >= 0 ? '+' : ''}{p.pnl.toFixed(2)}
                          </div>
                        </div>
                      </div>
                      <div className="grid grid-cols-2 gap-2 text-sm text-muted-foreground mb-3">
                        <div>行权价: {p.strike.toFixed(2)}</div>
                        <div>现价: {p.spotPrice.toFixed(2)}</div>
                        <div>持仓: {p.quantity}张</div>
                        <div>成本: {p.avgCost.toFixed(2)}</div>
                        <div className="col-span-2">到期: {p.expireAt.replace('T', ' ').substring(0, 16)}</div>
                      </div>
                      <div className="flex items-center gap-2">
                        <Input
                          type="number"
                          min={1}
                          max={p.quantity}
                          placeholder={`最多${p.quantity}张`}
                          value={sellQty[p.positionId] || ''}
                          onChange={(e) => setSellQty((prev) => ({ ...prev, [p.positionId]: Number(e.target.value) }))}
                          disabled={Date.parse(p.expireAt) <= Date.now()}
                          className="w-32 h-8"
                        />
                        <Button
                          size="sm"
                          variant="destructive"
                          onClick={() => handleClose(p)}
                          disabled={!tradingOpen || Date.parse(p.expireAt) <= Date.now() || submitting === p.positionId || !sellQty[p.positionId]}
                        >
                          {submitting === p.positionId ? '平仓中...' : '平仓'}
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setSellQty((prev) => ({ ...prev, [p.positionId]: p.quantity }))}
                          disabled={Date.parse(p.expireAt) <= Date.now()}
                        >
                          全部
                        </Button>
                      </div>
                    </div>
                  ))
                )
              )}

              {tab === 'orders' && (
                orders.length === 0 ? (
                  <EmptyState icon={<ClipboardList />} text="暂无期权订单" />
                ) : (
                  orders.map((o) => (
                    <div key={o.orderId} className="p-4 border-b border-border last:border-b-0">
                      <div className="flex justify-between items-start mb-2">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{o.stockName}</span>
                          <Badge variant={o.optionType === 'CALL' ? 'destructive' : 'success'} className="text-xs">
                            {o.optionType === 'CALL' ? '看涨' : '看跌'}
                          </Badge>
                          <Badge variant={o.orderSide === 'BTO' ? 'destructive' : 'success'} className="text-xs">
                            {o.orderSide === 'BTO' ? '买开' : '卖平'}
                          </Badge>
                          <Badge variant={o.status === 'FILLED' ? 'success' : 'secondary'}>
                            {o.status}
                          </Badge>
                        </div>
                      </div>
                      <div className="text-sm text-muted-foreground space-y-1">
                        <div>行权价: {o.strike.toFixed(2)} · 数量: {o.quantity}张</div>
                        {o.filledPrice > 0 && (
                          <div>成交价: {o.filledPrice.toFixed(2)} · 总价: {o.filledAmount.toFixed(2)}</div>
                        )}
                        {o.commission > 0 && <div>手续费: {o.commission.toFixed(2)}</div>}
                        <div>到期: {o.expireAt.replace('T', ' ').substring(0, 16)}</div>
                      </div>
                    </div>
                  ))
                )
              )}
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-md text-sm font-medium transition-all whitespace-nowrap border border-transparent",
        active
          ? "bg-primary text-primary-foreground shadow-md border-primary/20"
          : "text-muted-foreground hover:text-foreground hover:bg-accent/80 border-transparent"
      )}
    >
      {icon}
      {children}
    </button>
  );
}

function EmptyState({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="p-12 text-center text-muted-foreground">
      <div className="w-12 h-12 mx-auto mb-3 rounded-full bg-muted flex items-center justify-center">
        {icon}
      </div>
      {text}
    </div>
  );
}
