import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cryptoOrderApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useCrossAccount } from '../../hooks/useCrossAccount';
import { useDiscountBuff } from '../../hooks/useDiscountBuff';
import { useToast } from '../ui/use-toast';
import { FuturesActionButton } from '../FuturesActionButton';
import { LeverageSlider } from '../LeverageSlider';
import { cn, fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep } from '../../lib/coinConfig';
import { useTradeFilter } from '../../lib/tradeFilters';
import type { CryptoPosition } from '../../types';
import { TradeModeSwitch } from './TradeModeSwitch';
import { NumInput, PctRow } from './TradeFields';
import { useQuantityAnimation } from './useQuantityAnimation';
import { COMMISSION_RATE, POSITION_PCTS, SPOT_LEVERAGE_OPTIONS, calcMaxSpotBuyQty, calcSpotOrderEstimate, floorToStep, getStepPrecision } from './futuresMath';

const SPOT_MAX_LEVERAGE = SPOT_LEVERAGE_OPTIONS[SPOT_LEVERAGE_OPTIONS.length - 1];

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
  const { t } = useTranslation('trade');
  const cfg = getCoin(symbol);
  // 现货交易过滤器（对齐Binance）：步长 + 最小名义额（DOGE=1U 其余5U）
  const filter = useTradeFilter('spot', symbol);
  const MIN_QTY = filter.stepSize;
  const PRICE_STEP_TEXT = getCoinPriceStep(symbol).toFixed(getCoinPriceDecimals(symbol));
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  // 买入能花的钱 = min(全仓可用, 余额)：后端买入先过全仓可用这道闸再扣余额
  const { spendable } = useCrossAccount();

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
  // 买入输入单位：币数量 / USDT 预算。USDT 指"含手续费的总现金占用"，与仓位 % 按钮同一口径
  const [buyUnit, setBuyUnit] = useState<'COIN' | 'USDT'>('COIN');
  const [submitting, setSubmitting] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(false);

  // 折扣券（仅市价买入可用）
  const [discountBuff, setDiscountBuff] = useDiscountBuff(true, `${symbol}:${orderType}`);
  const [useBuff, setUseBuff] = useState(false);

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => setActionSuccess(false), 800);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  const isUsdtInput = side === 'BUY' && buyUnit === 'USDT';
  const isBuyMarket = side === 'BUY' && orderType === 'MARKET';
  // 生效杠杆：只有市价买入吃杠杆，限价买、卖出都按 1x
  const effLeverage = isBuyMarket ? leverage : 1;
  // USDT 预算 ↔ 币数量的换算系数（切单位时用）：现金占用 = 数量 × 价格 × (1 + 手续费率 × 杠杆)
  const unitPrice = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  const unitFactor = unitPrice * (1 + COMMISSION_RATE * effLeverage);
  /** USDT 预算 → 付得起的最大币数（未乘杠杆的那份），现金占用按后端取整口径算 */
  const usdtToQty = (usdt: number) => unitPrice > 0 ? calcMaxSpotBuyQty(usdt, unitPrice, effLeverage, MIN_QTY) / effLeverage : 0;
  /** 实际提交的下单量：×生效杠杆后按步长向下对齐；全量卖出保留精确持仓量（后端豁免步长，尘埃能清干净） */
  const toOrderQty = (qty: number) => side === 'SELL' && qty === (position?.quantity ?? -1)
    ? qty
    : floorToStep(qty * effLeverage, filter.stepSize);

  /** 切换单位时把已输入的值按当前价换算过去，不清空 */
  const switchUnit = (u: 'COIN' | 'USDT') => {
    if (u === buyUnit) return;
    const v = parseFloat(quantity);
    if (v > 0 && unitFactor > 0) {
      setQuantity(u === 'USDT' ? (v * unitFactor).toFixed(2) : String(floorToStep(v / unitFactor, MIN_QTY)));
    }
    setBuyUnit(u);
  };

  const handleSubmit = async () => {
    if (orderType === 'LIMIT') {
      const lp = parseFloat(limitPrice);
      if (!lp || lp <= 0) { toast(t('toast.invalidLimit'), 'error'); return; }
    }
    // USDT 模式：输入是总花费预算（含手续费），先按当前价换算成数量，再走原有校验与提交。
    // 市价单实际按服务端提交时刻的价成交，与页面价的抖动差和仓位 % 按钮同级，模拟盘可接受
    const input = parseFloat(quantity);
    const qty = isUsdtInput ? usdtToQty(input || 0) : input;
    if (!qty || qty < MIN_QTY) {
      toast(isUsdtInput
        ? t('toast.amountTooSmall', { qty: MIN_QTY, unit: cfg.name })
        : t('toast.minQtySpot', { qty: MIN_QTY }), 'error');
      return;
    }
    const price = orderType === 'LIMIT' ? parseFloat(limitPrice) : currentPrice;
    const actualQty = toOrderQty(qty);
    // 买入需过最小名义额（对齐Binance；卖出为减持豁免）
    if (side === 'BUY' && price > 0 && actualQty * price < filter.minNotional) {
      toast(t('toast.minNotional', { amount: filter.minNotional }), 'error'); return;
    }
    setSubmitting(true);
    try {
      const req = {
        symbol: symbol,
        quantity: actualQty,
        orderType,
        ...(orderType === 'LIMIT' ? { limitPrice: parseFloat(limitPrice) } : {}),
        ...(effLeverage > 1 ? { leverageMultiple: effLeverage } : {}),
        ...(isBuyMarket && useBuff && discountBuff ? { useBuffId: discountBuff.id } : {}),
      };
      if (side === 'BUY') {
        await cryptoOrderApi.buy(req);
        toast(t('toast.buyOk'), 'success');
      } else {
        await cryptoOrderApi.sell(req);
        toast(t('toast.sellOk'), 'success');
      }
      setActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      // 券只有市价买入才真用掉了（限价买/卖出请求里不带券）
      if (isBuyMarket && useBuff && discountBuff) { setDiscountBuff(null); setUseBuff(false); }
      setQuantity(isUsdtInput ? '' : String(MIN_QTY));
      setLimitPrice('');
      setLeverage(1);
      onTraded();
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.orderFailed'), 'error');
    } finally { setSubmitting(false); }
  };

  // 预估按实际提交的下单量算（已乘杠杆、按步长取整），取整同后端。USDT 模式先把预算换算回数量（合计不超过输入的预算）
  const inputNum = parseFloat(quantity) || 0;
  const qtyNum = isUsdtInput ? usdtToQty(inputNum) : inputNum;
  const orderQty = toOrderQty(qtyNum);
  const priceForCalc = unitPrice;
  // 折扣券只吃市价买入，卖出不打折
  const discountRate = useBuff && discountBuff && isBuyMarket ? Number(discountBuff.buffType.match(/DISCOUNT_(\d+)/)?.[1] ?? 100) / 100 : 1;
  const est = calcSpotOrderEstimate(orderQty, priceForCalc, effLeverage, discountRate);

  /** 仓位 % 按钮的目标值：买入吃可用（spendable）、卖出吃持仓；口径与提交一致 */
  const pctTarget = (pct: number) => {
    if (side === 'SELL') {
      // 卖出100%：精确全量（尘埃也能清干净，后端对全量卖豁免步长），不做步长取整
      const full = position?.quantity ?? 0;
      return pct >= 1 ? full : Math.max(MIN_QTY, floorToStep(full * pct, MIN_QTY));
    }
    const budget = (spendable ?? 0) * pct;
    // USDT 模式填预算本身（向下取到分），换算成数量交给 usdtToQty
    if (isUsdtInput) return Math.floor(budget * 100) / 100;
    // 限价还没填价时退回现价，别让百分比按钮点了没反应。总量按后端口径付得起，再拆回未乘杠杆的那份
    const total = calcMaxSpotBuyQty(budget, unitPrice || currentPrice, effLeverage, MIN_QTY);
    return Math.max(MIN_QTY, floorToStep(total / effLeverage, MIN_QTY));
  };
  const activePct = inputNum > 0 && currentPrice > 0
    ? (POSITION_PCTS.find(p => Math.abs(pctTarget(p) - inputNum) < 1e-9) ?? null)
    : null;
  const handlePct = (pct: number) => {
    const target = pctTarget(pct);
    if (!(target > 0)) return;
    // 卖出全量直接落精确值，别让缓动把尾数抹了
    if (side === 'SELL' && pct >= 1) { setQuantity(String(target)); return; }
    animateQuantity(target, isUsdtInput ? 0.01 : MIN_QTY);
  };

  return (
    <>
      {/* 合约/现货 + 可用 */}
      <div className="flex justify-between items-center">
        <TradeModeSwitch mode="spot" futuresOnly={cfg.futuresOnly} onModeChange={onModeChange} />
        {user && (
          <span className="text-[12.5px] text-muted-foreground">
            {t('open.availLabel')}{' '}
            <b className="num text-foreground font-semibold">{side === 'BUY' ? fmtNum(spendable) : (position?.quantity ?? 0)}</b>{' '}
            {side === 'BUY' ? 'USDT' : cfg.name}
          </span>
        )}
      </div>

      {/* 买入 / 卖出 */}
      <div className="grid grid-cols-2 border-[1.5px] border-foreground">
        <button
          onClick={() => setSide('BUY')}
          className={cn('h-12 text-[17px] font-extrabold cursor-pointer transition-colors', side === 'BUY' ? 'bg-gain text-white' : 'text-muted-foreground hover:text-foreground')}
        >{t('side.buy')}</button>
        <button
          onClick={() => setSide('SELL')}
          className={cn('h-12 text-[17px] font-extrabold cursor-pointer transition-colors', side === 'SELL' ? 'bg-loss text-white' : 'text-muted-foreground hover:text-foreground')}
        >{t('side.sell')}</button>
      </div>

      {/* 委托类型 + 限价 */}
      <div className="grid grid-cols-2 gap-3.5">
        <div className="field">
          <label>{t('orderType.label')}</label>
          <div className="seg flex">
            {(['MARKET', 'LIMIT'] as const).map(o => (
              <button key={o} className={cn('flex-1', orderType === o && 'on')} onClick={() => setOrderType(o)}>
                {t(o === 'MARKET' ? 'orderType.market' : 'orderType.limit')}
              </button>
            ))}
          </div>
        </div>
        {orderType === 'LIMIT' && (
          <div className="field">
            <label>{t('open.limitLabel')}</label>
            <NumInput value={limitPrice} onChange={setLimitPrice} placeholder={t('open.limitPlaceholder')} step={PRICE_STEP_TEXT} min="0" unit="USDT" />
          </div>
        )}
      </div>

      {/* 数量：买入时右侧单位可点，币 ↔ USDT 换算 */}
      <div className="field">
        <label>
          <span>{isUsdtInput ? t('spot.amount') : t('spot.quantity')}</span>
          <span>{t('open.minOrder', { step: MIN_QTY, unit: cfg.name })}</span>
        </label>
        <NumInput
          value={quantity}
          onChange={setQuantity}
          placeholder={isUsdtInput ? t('spot.spendPlaceholder') : String(MIN_QTY)}
          step={isUsdtInput ? '0.01' : String(MIN_QTY)}
          min={isUsdtInput ? 0 : MIN_QTY}
          unit={isUsdtInput ? 'USDT' : cfg.name}
          unitTitle={side === 'BUY' ? t('open.switchUnit') : undefined}
          onUnitClick={side === 'BUY' ? () => switchUnit(buyUnit === 'USDT' ? 'COIN' : 'USDT') : undefined}
        />
        {currentPrice > 0 && <PctRow active={activePct} onPick={handlePct} />}
      </div>

      {/* 现货杠杆借款：仅市价买入；用了折扣券就只能 1x */}
      {isBuyMarket && (
        <div className="flex flex-col gap-2">
          <div className="flex justify-between items-baseline text-[12.5px] font-semibold text-muted-foreground">
            <span>{t('lev.label')}</span>
            <b className="num text-[20px] font-bold text-foreground">{leverage}x</b>
          </div>
          <div className={useBuff ? 'opacity-40 pointer-events-none' : ''}>
            <LeverageSlider value={leverage} max={SPOT_MAX_LEVERAGE} ticks={SPOT_LEVERAGE_OPTIONS} onChange={setLeverage} />
          </div>
          {useBuff && <div className="text-[12px] text-warning">{t('spot.levDisabledByBuff')}</div>}
        </div>
      )}

      {/* 折扣券：仅市价买入且有可用券；用了杠杆就不能叠 */}
      {isBuyMarket && discountBuff && (
        <div className="field">
          <label>
            <span>{t('spot.discount')}</span>
            <button
              type="button"
              disabled={leverage > 1}
              onClick={() => { if (leverage > 1) return; setUseBuff(v => !v); }}
              className={cn('chip', useBuff && 'fill orange', leverage > 1 && 'opacity-40 cursor-not-allowed')}
            >
              {discountBuff.buffName}
            </button>
          </label>
          <div className="text-[12px] text-muted-foreground">
            {leverage > 1 ? t('spot.discountDisabledByLev') : t('spot.discountLine')}
          </div>
        </div>
      )}

      {/* 预估 */}
      {orderQty > 0 && priceForCalc > 0 && (
        <div className="num border-t border-foreground pt-1">
          {isUsdtInput && (
            <div className="kv py-[7px]">
              <span className="k">{t('spot.estGet')}{effLeverage > 1 ? ` ${t('spot.totalPosLev', { lev: effLeverage })}` : ''}</span>
              <span className="v">≈ {fmtNum(orderQty, getStepPrecision(filter.stepSize))} {cfg.name}</span>
            </div>
          )}
          {effLeverage > 1 && (
            <div className="kv py-[7px]">
              <span className="k">{t('spot.totalPos', { lev: effLeverage })}</span>
              <span className="v">{fmtNum(est.amount)}</span>
            </div>
          )}
          {side === 'BUY' && (
            <div className="kv py-[7px]">
              <span className="k">{effLeverage > 1 ? t('spot.margin') : t('spot.estCost')}</span>
              <span className="v">
                {discountRate < 1 && <span className="line-through text-muted-foreground mr-1.5">{fmtNum(est.fullAmount)}</span>}
                {fmtNum(est.margin)}
              </span>
            </div>
          )}
          <div className="kv py-[7px]">
            <span className="k">{t('spot.fee')}</span>
            <span className="v">{fmtNum(est.commission)}</span>
          </div>
          <div className="kv py-[7px] border-b-0 text-[16px]">
            <span className="k">{side === 'BUY' ? t('spot.total') : t('spot.estProceeds')}</span>
            <span className="v text-[20px] font-bold [font-stretch:85%]">
              {fmtNum(side === 'BUY' ? est.margin + est.commission : est.amount - est.commission)} USDT
            </span>
          </div>
        </div>
      )}

      <FuturesActionButton
        onClick={handleSubmit}
        disabled={submitting || currentPrice <= 0}
        loading={submitting}
        success={actionSuccess}
        side={side}
        label={cfg.name}
      />
    </>
  );
}
