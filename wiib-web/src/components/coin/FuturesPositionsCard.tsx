import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Loader2 } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useToast } from '../ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Badge } from '../ui/badge';
import { HelpTip } from '../HelpTip';
import { fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import type { FuturesPosition, FuturesBracket } from '../../types';
import { SLTPEditor } from './SLTPEditor';
import {
  POSITION_PCTS, FUTURES_COMMISSION_RATE, roundHalfUp2, roundCeil2,
  findFuturesBracket, formatRate, calcMaxIncreaseQty,
  type SLTPRow,
} from './futuresMath';

type PosActionType = 'close' | 'increase' | 'margin' | 'reduceMargin' | 'stoploss';

/**
 * 合约持仓卡：当前币种活跃仓位 + 价格轴 + 平仓/加仓/保证金/止盈损内联操作。
 * 仓位数据自管（symbol/refreshKey 变化时拉取，markPrice 推送实时刷盈亏）；
 * 平仓/加仓成交后调 onOrdersChanged 让订单表刷新。
 */
export function FuturesPositionsCard({ symbol, currentPrice, markPrice, bracketsMap, refreshKey, onOrdersChanged }: {
  symbol: string;
  currentPrice: number;
  markPrice?: number;
  bracketsMap: Record<string, FuturesBracket[]>;
  refreshKey: number;
  onOrdersChanged: () => void;
}) {
  const cfg = getCoin(symbol);
  const MIN_QTY = cfg.minQty;
  const PRICE_STEP = getCoinPriceStep(symbol);
  const PRICE_STEP_TEXT = PRICE_STEP.toFixed(getCoinPriceDecimals(symbol));
  const fmtPrice = useCallback((n?: number | null) => formatCoinPrice(symbol, n), [symbol]);

  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);

  const [positions, setPositions] = useState<FuturesPosition[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [posAction, setPosAction] = useState<{ id: number; type: PosActionType } | null>(null);
  const [closeOrderType, setCloseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [closeLimitPrice, setCloseLimitPrice] = useState('');
  const [closeQty, setCloseQty] = useState('');
  const [increaseOrderType, setIncreaseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [increaseLimitPrice, setIncreaseLimitPrice] = useState('');
  const [increaseQty, setIncreaseQty] = useState('');
  const [marginAmt, setMarginAmt] = useState('');
  const [slRows, setSlRows] = useState<SLTPRow[]>([]);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([]);

  const fetchPositions = useCallback(async () => {
    setLoading(true);
    try {
      const list = await futuresApi.positions(symbol);
      setPositions(list);
    } catch (e) {
      console.error('查询合约仓位失败', e);
      setPositions([]);
    } finally {
      setLoading(false);
    }
  }, [symbol]);

  useEffect(() => { fetchPositions(); }, [fetchPositions, refreshKey]);

  // 用 WS 推送的 markPrice 实时更新仓位盈亏
  useEffect(() => {
    if (!markPrice || positions.length === 0) return;
    const mp = markPrice;
    setPositions(prev => prev.map(pos => {
      const posValue = mp * pos.quantity;
      const unrealizedPnl = pos.side === 'LONG'
        ? (mp - pos.entryPrice) * pos.quantity
        : (pos.entryPrice - mp) * pos.quantity;
      const effectiveMargin = pos.margin + unrealizedPnl;
      const unrealizedPnlPct = pos.margin > 0 ? (unrealizedPnl / pos.margin) * 100 : 0;
      return { ...pos, markPrice: mp, currentPrice: mp, positionValue: posValue, unrealizedPnl, unrealizedPnlPct, effectiveMargin };
    }));
  }, [markPrice, positions.length]);

  // 仓位操作切换：展开时重置各操作输入，止盈损带入已有档位
  const togglePosAction = (posId: number, type: PosActionType, pos?: FuturesPosition) => {
    if (posAction?.id === posId && posAction.type === type) {
      setPosAction(null);
    } else {
      setPosAction({ id: posId, type });
      setCloseQty(''); setCloseLimitPrice(''); setCloseOrderType('MARKET');
      setIncreaseQty(''); setIncreaseLimitPrice(''); setIncreaseOrderType('MARKET');
      setMarginAmt('');
      setSlRows(pos?.stopLosses?.map(s => ({ price: String(s.price), quantity: String(s.quantity) })) ?? [{ price: '', quantity: '' }]);
      setTpRows(pos?.takeProfits?.map(t => ({ price: String(t.price), quantity: String(t.quantity) })) ?? [{ price: '', quantity: '' }]);
    }
  };

  const handleClose = async (positionId: number) => {
    const qty = parseFloat(closeQty);
    if (!qty || qty <= 0) { toast('请输入平仓数量', 'error'); return; }
    if (closeOrderType === 'LIMIT') {
      const lp = parseFloat(closeLimitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    setSubmitting(true);
    try {
      await futuresApi.close({
        positionId, quantity: qty, orderType: closeOrderType,
        ...(closeOrderType === 'LIMIT' ? { limitPrice: parseFloat(closeLimitPrice) } : {}),
      });
      toast('平仓成功', 'success');
      setPosAction(null);
      fetchPositions();
      onOrdersChanged();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '平仓失败', 'error');
    } finally { setSubmitting(false); }
  };

  const handleIncrease = async (positionId: number) => {
    const qty = parseFloat(increaseQty);
    if (!qty || qty <= 0) { toast('请输入加仓数量', 'error'); return; }
    if (increaseOrderType === 'LIMIT') {
      const lp = parseFloat(increaseLimitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    setSubmitting(true);
    try {
      await futuresApi.increase({
        positionId, quantity: qty, orderType: increaseOrderType,
        ...(increaseOrderType === 'LIMIT' ? { limitPrice: parseFloat(increaseLimitPrice) } : {}),
      });
      toast('加仓成功', 'success');
      setPosAction(null);
      fetchPositions();
      onOrdersChanged();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '加仓失败', 'error');
    } finally { setSubmitting(false); }
  };

  const handleAddMargin = async (positionId: number) => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.addMargin({ positionId, amount: amt });
      toast('追加保证金成功', 'success');
      setPosAction(null);
      fetchPositions();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '追加保证金失败', 'error');
    } finally { setSubmitting(false); }
  };

  const handleReduceMargin = async (positionId: number) => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.reduceMargin({ positionId, amount: amt });
      toast('减少保证金成功', 'success');
      setPosAction(null);
      fetchPositions();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '减少保证金失败', 'error');
    } finally { setSubmitting(false); }
  };

  const handleSetStopLoss = async (positionId: number) => {
    const items = slRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    setSubmitting(true);
    try {
      await futuresApi.setStopLoss({ positionId, stopLosses: items });
      toast('设置止损成功', 'success');
      setPosAction(null);
      fetchPositions();
    } catch (e: unknown) {
      toast((e as Error).message || '设置止损失败', 'error');
    } finally { setSubmitting(false); }
  };

  const handleSetTakeProfit = async (positionId: number) => {
    const items = tpRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    setSubmitting(true);
    try {
      await futuresApi.setTakeProfit({ positionId, takeProfits: items });
      toast('设置止盈成功', 'success');
      setPosAction(null);
      fetchPositions();
    } catch (e: unknown) {
      toast((e as Error).message || '设置止盈失败', 'error');
    } finally { setSubmitting(false); }
  };

  if (positions.length === 0) return null;

  return (
    <Card className="mb-6">
      <CardHeader className="pb-4 pt-5 px-5">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base font-black flex items-center gap-2">
            当前持仓
            <HelpTip text="仅显示当前币种活跃仓位，盈亏基于标记价实时计算" />
          </CardTitle>
          <button onClick={fetchPositions} disabled={loading} className="p-1 rounded-md hover:bg-muted transition-colors disabled:opacity-50">
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </CardHeader>
      <CardContent className="space-y-5 px-5 pb-5 pt-0">
        {positions.map(pos => {
          const isPnlUp = pos.unrealizedPnl >= 0;
          const isLong = pos.side === 'LONG';
          const isActive = posAction?.id === pos.id;
          const currentBracket = findFuturesBracket(bracketsMap[pos.symbol], pos.positionValue);
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
                    {isPnlUp ? '+' : ''}${fmtNum(pos.unrealizedPnl)}
                  </div>
                </div>
              </div>
              {/* 价格轴 */}
              <PositionPriceBar pos={pos} currentPrice={currentPrice} priceFormatter={fmtPrice} />
              <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
                <div>开仓 <span className="text-foreground font-mono">${fmtPrice(pos.entryPrice)}</span></div>
                <div>强平 <span className="text-yellow-500 font-mono">${fmtPrice(pos.liquidationPrice)}</span></div>
                <div>保证金 <span className="text-foreground font-mono">${fmtNum(pos.margin)}</span></div>
                <div>资金费 <span className="font-mono">${fmtNum(pos.fundingFeeTotal)}</span></div>
                <div>MMR <span className="text-foreground font-mono">{currentBracket ? `档位 ${currentBracket.tier} / ${formatRate(currentBracket.mmr)}` : '—'}</span></div>
              </div>
              {/* 操作按钮 */}
              <div className="flex flex-wrap gap-1.5 pt-1">
                <Button size="sm" variant={isActive && posAction.type === 'close' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'close', pos)}>平仓</Button>
                <Button size="sm" variant={isActive && posAction.type === 'increase' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'increase', pos)}>加仓</Button>
                <Button size="sm" variant={isActive && posAction.type === 'margin' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'margin', pos)}>+保证金</Button>
                <Button size="sm" variant={isActive && posAction.type === 'reduceMargin' ? 'default' : 'outline'} className="h-7 text-[11px] flex-1 min-w-15" onClick={() => togglePosAction(pos.id, 'reduceMargin', pos)}>-保证金</Button>
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
                          <button onClick={() => setCloseOrderType('MARKET')} className={`px-3 py-1 text-[11px] font-medium transition-all ${closeOrderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>市价</button>
                          <button onClick={() => setCloseOrderType('LIMIT')} className={`px-3 py-1 text-[11px] font-medium transition-all border-l border-border ${closeOrderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>限价</button>
                        </div>
                      </div>
                      {closeOrderType === 'LIMIT' && (
                        <>
                          <Input type="number" placeholder="限价 (USDT)" value={closeLimitPrice} onChange={e => setCloseLimitPrice(e.target.value)} step={PRICE_STEP_TEXT} className="h-8 text-xs" />
                          {parseFloat(closeLimitPrice) > 0 && currentPrice > 0 && (
                            (pos.side === 'LONG' && parseFloat(closeLimitPrice) <= currentPrice) ||
                            (pos.side === 'SHORT' && parseFloat(closeLimitPrice) >= currentPrice)
                          ) && (
                            <div className="text-[10px] text-yellow-500">限价{pos.side === 'LONG' ? '≤' : '≥'}当前价，将立即以市价成交</div>
                          )}
                        </>
                      )}
                      <div className="flex items-center gap-2">
                        <Input type="number" placeholder="平仓数量" value={closeQty} onChange={e => setCloseQty(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={pos.quantity} className="flex-1 h-8 text-xs" />
                        <span className="text-[11px] text-muted-foreground shrink-0">/ {pos.quantity}</span>
                      </div>
                      <div className="flex gap-1">
                        {POSITION_PCTS.map(pct => (
                          <button key={pct} onClick={() => {
                            const raw = pos.quantity * pct;
                            setCloseQty(pct === 1 ? String(pos.quantity) : (Math.round(raw / MIN_QTY) * MIN_QTY).toFixed(8).replace(/0+$/, '').replace(/\.$/, ''));
                          }} className="flex-1 py-1 rounded text-[11px] font-medium border border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent transition-all">
                            {pct * 100}%
                          </button>
                        ))}
                      </div>
                      <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleClose(pos.id)} disabled={submitting}>
                        {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认平仓'}
                      </Button>
                    </>
                  )}
                  {/* 加仓 */}
                  {posAction.type === 'increase' && (() => {
                    const incPrice = increaseOrderType === 'LIMIT' && parseFloat(increaseLimitPrice) > 0 ? parseFloat(increaseLimitPrice) : currentPrice;
                    const maxIncQty = user ? calcMaxIncreaseQty(user.balance, incPrice, pos.leverage, MIN_QTY) : 0;
                    return (
                    <>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-muted-foreground shrink-0">执行</span>
                        <div className="flex rounded-md border border-border overflow-hidden">
                          <button onClick={() => setIncreaseOrderType('MARKET')} className={`px-3 py-1 text-[11px] font-medium transition-all ${increaseOrderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>市价</button>
                          <button onClick={() => setIncreaseOrderType('LIMIT')} className={`px-3 py-1 text-[11px] font-medium transition-all border-l border-border ${increaseOrderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>限价</button>
                        </div>
                      </div>
                      {increaseOrderType === 'LIMIT' && (
                        <>
                          <Input type="number" placeholder="限价 (USDT)" value={increaseLimitPrice} onChange={e => setIncreaseLimitPrice(e.target.value)} step={PRICE_STEP_TEXT} className="h-8 text-xs" />
                          {parseFloat(increaseLimitPrice) > 0 && currentPrice > 0 && (
                            (pos.side === 'LONG' && parseFloat(increaseLimitPrice) >= currentPrice) ||
                            (pos.side === 'SHORT' && parseFloat(increaseLimitPrice) <= currentPrice)
                          ) && (
                            <div className="text-[10px] text-yellow-500">限价{pos.side === 'LONG' ? '≥' : '≤'}当前价，将立即以市价成交</div>
                          )}
                        </>
                      )}
                      <div className="flex items-center gap-2">
                        <Input type="number" placeholder={`${MIN_QTY} - ${maxIncQty > 0 ? maxIncQty : '0'}`} value={increaseQty} onChange={e => setIncreaseQty(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={maxIncQty > 0 ? maxIncQty : undefined} className="flex-1 h-8 text-xs" />
                        {user && <span className="text-[11px] text-muted-foreground shrink-0">余额 {fmtNum(user.balance)}</span>}
                      </div>
                      {parseFloat(increaseQty) > 0 && incPrice > 0 && (() => {
                        const iq = parseFloat(increaseQty);
                        const val = roundHalfUp2(incPrice * iq);
                        const mg = roundCeil2(val / pos.leverage);
                        const cm = roundHalfUp2(val * FUTURES_COMMISSION_RATE);
                        return (
                          <div className="grid grid-cols-2 gap-x-4 gap-y-0.5 text-[11px] text-muted-foreground">
                            <div>杠杆前数量 <span className="text-foreground font-mono">{(iq / pos.leverage).toFixed(8).replace(/0+$/, '').replace(/\.$/, '')}</span></div>
                            <div>仓位价值 <span className="text-foreground font-mono">${fmtNum(val)}</span></div>
                            <div>保证金 <span className="text-foreground font-mono">${fmtNum(mg)}</span></div>
                            <div>手续费 <span className="text-foreground font-mono">${fmtNum(cm)}</span></div>
                            <div className="col-span-2">需支付 <span className="text-foreground font-mono font-semibold">${fmtNum(mg + cm)}</span></div>
                          </div>
                        );
                      })()}
                      <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleIncrease(pos.id)} disabled={submitting}>
                        {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认加仓'}
                      </Button>
                    </>
                    );
                  })()}
                  {/* +保证金 */}
                  {posAction.type === 'margin' && (
                    <>
                      <Input type="number" placeholder="追加金额 (USDT)" value={marginAmt} onChange={e => setMarginAmt(e.target.value)} step="0.01" min="0" className="h-8 text-xs" />
                      {user && <div className="text-[11px] text-muted-foreground">可用余额 {fmtNum(user.balance)} USDT</div>}
                      <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleAddMargin(pos.id)} disabled={submitting}>
                        {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认追加'}
                      </Button>
                    </>
                  )}
                  {/* -保证金 */}
                  {posAction.type === 'reduceMargin' && (
                    <>
                      <Input type="number" placeholder="减少金额 (USDT)" value={marginAmt} onChange={e => setMarginAmt(e.target.value)} step="0.01" min="0" max={pos.margin} className="h-8 text-xs" />
                      <div className="text-[11px] text-muted-foreground">当前保证金 {fmtNum(pos.margin)} USDT</div>
                      <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleReduceMargin(pos.id)} disabled={submitting}>
                        {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认减少'}
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
                        <SLTPEditor rows={slRows} onChange={setSlRows} label="止损" posQty={pos.quantity} minQty={MIN_QTY}
                          currentPrice={currentPrice} side={pos.side} liquidationPrice={pos.liquidationPrice}
                          minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                        <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleSetStopLoss(pos.id)} disabled={submitting}>
                          {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止损'}
                        </Button>
                      </div>
                      <div className="space-y-2">
                        <div className="text-xs text-muted-foreground flex items-center gap-1">
                          止盈 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                        </div>
                        <SLTPEditor rows={tpRows} onChange={setTpRows} label="止盈" posQty={pos.quantity} minQty={MIN_QTY}
                          currentPrice={currentPrice} side={pos.side} liquidationPrice={pos.liquidationPrice}
                          minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
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
  );
}

/** 价格轴：强平/止损/开仓/标记/现价/止盈 各价位相对区间的水平条 */
function PositionPriceBar({ pos, currentPrice, priceFormatter = fmtNum }: {
  pos: FuturesPosition;
  currentPrice: number;
  priceFormatter?: (value?: number | null) => string;
}) {
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
            {priceFormatter(r.price)}
          </span>
        </div>
      ))}
    </div>
  );
}
