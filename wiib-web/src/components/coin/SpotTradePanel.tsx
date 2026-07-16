import { useCallback, useEffect, useState } from 'react';
import { Wallet, Warehouse, Scale, Sparkles } from 'lucide-react';
import { cryptoOrderApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useDiscountBuff } from '../../hooks/useDiscountBuff';
import { useToast } from '../ui/use-toast';
import { CardContent } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Badge } from '../ui/badge';
import { FuturesActionButton } from '../FuturesActionButton';
import { fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep } from '../../lib/coinConfig';
import type { CryptoPosition } from '../../types';
import { TradeModeSwitch } from './TradeModeSwitch';
import { useQuantityAnimation } from './useQuantityAnimation';
import { COMMISSION_RATE, POSITION_PCTS, SPOT_LEVERAGE_OPTIONS } from './futuresMath';

/**
 * 现货交易面板：买卖方向、市价/限价、数量/仓位、现货杠杆、折扣券、预估与提交。
 * 状态全部内聚；成交后调 onTraded 让父级刷新持仓/用户/订单表。
 */
export function SpotTradePanel({ symbol, currentPrice, position, onModeChange, onTraded }: {
  symbol: string;
  currentPrice: number;
  position: CryptoPosition | null;
  onModeChange: (m: 'spot' | 'futures') => void;
  onTraded: () => void;
}) {
  const cfg = getCoin(symbol);
  const MIN_QTY = cfg.minQty;
  const PRICE_STEP_TEXT = getCoinPriceStep(symbol).toFixed(getCoinPriceDecimals(symbol));
  const { toast } = useToast();
  const user = useUserStore(s => s.user);

  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  // 数量按 方向_执行方式 分桶记忆，切换时互不覆盖
  const [qtyMap, setQtyMap] = useState<Record<string, string>>({});
  const qtyKey = `${side}_${orderType}`;
  const quantity = qtyMap[qtyKey] ?? '';
  const setQuantity = useCallback((v: string) => setQtyMap(m => ({ ...m, [qtyKey]: v })), [qtyKey]);
  const animateQuantity = useQuantityAnimation(quantity, setQuantity);
  const [limitPrice, setLimitPrice] = useState('');
  const [leverage, setLeverage] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(false);

  // 折扣券（仅市价买入可用）
  const [discountBuff, setDiscountBuff] = useDiscountBuff(true, `${symbol}:${orderType}`);
  const [useBuff, setUseBuff] = useState(false);

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => setActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  const handleSubmit = async () => {
    const qty = parseFloat(quantity);
    if (!qty || qty < MIN_QTY) { toast(`最小数量 ${MIN_QTY}`, 'error'); return; }
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
      setActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      if (useBuff && discountBuff) { setDiscountBuff(null); setUseBuff(false); }
      setQuantity(String(MIN_QTY));
      setLimitPrice('');
      setLeverage(1);
      onTraded();
    } catch (e: unknown) {
      toast((e as Error).message || '下单失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 预估金额
  const qtyNum = parseFloat(quantity) || 0;
  const priceForCalc = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  const discountRate = useBuff && discountBuff && orderType === 'MARKET' ? Number(discountBuff.buffType.match(/DISCOUNT_(\d+)/)?.[1] ?? 100) / 100 : 1;
  const leveragedQty = side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? qtyNum * leverage : qtyNum;
  const estimatedAmount = leveragedQty * priceForCalc;
  const marginAmount = qtyNum * priceForCalc; // 保证金部分
  const estimatedCommission = estimatedAmount * COMMISSION_RATE;

  return (
    <>
      {/* 买卖切换 + 现货/合约 + 爆仓 */}
      <div className="px-5 pt-5 flex flex-wrap items-center gap-3">
        <div className="flex flex-1 min-w-[140px] rounded-xl bg-card overflow-hidden neu-raised">
          <button onClick={() => setSide('BUY')} className={`flex-1 py-2.5 text-sm font-black border-r border-border transition-colors ${side === 'BUY' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>买入</button>
          <button onClick={() => setSide('SELL')} className={`flex-1 py-2.5 text-sm font-black transition-colors ${side === 'SELL' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>卖出</button>
        </div>
        <TradeModeSwitch mode="spot" futuresOnly={cfg.futuresOnly} onModeChange={onModeChange} />
      </div>

      {/* flex-1 + 预估提交区 mt-auto：面板随左侧图表卡等高，条件块（限价/杠杆/折扣券）出现时消耗预留空档 */}
      <CardContent className="p-5 mt-2 flex-1 flex flex-col gap-6">
        {/* 执行方式：市价/限价 */}
        <div className="flex items-center gap-2">
          <div className="flex rounded-lg overflow-hidden neu-raised-sm">
            <button onClick={() => setOrderType('MARKET')} className={`px-4 py-1.5 text-xs font-bold transition-colors ${orderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>市价</button>
            <button onClick={() => setOrderType('LIMIT')} className={`px-4 py-1.5 text-xs font-bold border-l border-border transition-colors ${orderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground hover:bg-surface-hover'}`}>限价</button>
          </div>
        </div>
        {/* 限价输入 */}
        {orderType === 'LIMIT' && (
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-muted-foreground">限价 (USDT)</label>
            <Input type="number" placeholder="输入限价" value={limitPrice} onChange={e => setLimitPrice(e.target.value)} step={PRICE_STEP_TEXT} min="0" />
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
                  ? <>{fmtNum(user.balance)} USDT</>
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
                {SPOT_LEVERAGE_OPTIONS.map(lv => (
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

        {/* 预估 + 提交（mt-auto 压底） */}
        <div className="mt-auto pt-4 border-t border-border border-dashed space-y-4">
          {qtyNum > 0 && priceForCalc > 0 && (
            <div className="space-y-2">
              {side === 'BUY' && leverage > 1 && (
                <div className="flex justify-between text-xs font-bold text-muted-foreground">
                  <span>总仓位 ({leverage}x)</span>
                  <span className="font-mono text-foreground">${fmtNum(estimatedAmount)} USDT</span>
                </div>
              )}
              <div className="flex justify-between text-xs font-bold text-muted-foreground">
                <span>{side === 'BUY' && leverage > 1 ? '保证金' : `预估${side === 'BUY' ? '花费' : '收入'}`}</span>
                <span className="text-foreground">
                  {useBuff && discountRate < 1 && side === 'BUY' && (
                    <span className="line-through text-muted-foreground mr-1.5">${fmtNum(marginAmount)}</span>
                  )}
                  ${fmtNum(marginAmount * discountRate)} USDT
                </span>
              </div>
              <div className="flex justify-between text-xs font-bold text-muted-foreground">
                <span>手续费 (0.1%)</span>
                <span className="font-mono">${fmtNum(estimatedCommission * discountRate)} USDT</span>
              </div>
              {side === 'BUY' && (
                <div className="flex justify-between text-sm font-black pt-1">
                  <span className="text-muted-foreground">合计</span>
                  <span className="text-foreground">
                    ${fmtNum((marginAmount + estimatedCommission) * discountRate)} USDT
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
            success={actionSuccess}
            side={side}
            label={cfg.name}
          />
        </div>
      </CardContent>
    </>
  );
}
