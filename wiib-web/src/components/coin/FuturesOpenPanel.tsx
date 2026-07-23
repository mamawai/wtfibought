import { useEffect, useState } from 'react';
import { Wallet, Scale, Flame } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useToast } from '../ui/use-toast';
import { CardContent } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { FuturesActionButton } from '../FuturesActionButton';
import { LeverageSlider } from '../LeverageSlider';
import { NeuToggle } from '../NeuToggle';
import { HelpTip } from '../HelpTip';
import { fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import { useTradeFilter } from '../../lib/tradeFilters';
import type { FuturesBracket, FuturesCrossAccount, FuturesMarginMode, FuturesPosition, FuturesSLItem, FuturesTPItem } from '../../types';
import { TradeModeSwitch } from './TradeModeSwitch';
import { SLTPEditor } from './SLTPEditor';
import { useQuantityAnimation } from './useQuantityAnimation';
import {
  POSITION_PCTS, FUTURES_LEVERAGE_OPTIONS, formatRate, getStepPrecision, floorToStep,
  calcFuturesOpenEstimate, calcMaxAffordableMarginQty, estimateFuturesLiqPrice,
  type SLTPRow,
} from './futuresMath';

/**
 * 合约开仓面板：方向、市价/限价、杠杆、保证金数量、开仓止损/止盈、预估强平价。
 * 状态全部内聚；开仓成功后调 onTraded 让父级刷新用户/仓位卡/订单表。
 * 对齐Binance币种级设置：该币有持仓时保证金模式锁定、杠杆滑杆=调杠杆入口（确认即改仓位，多空一起变），
 * 同向下单后端自动并入现有仓位。positionsKey 由父级在仓位卡变动时递增，驱动面板持仓快照重拉。
 */
export function FuturesOpenPanel({ symbol, currentPrice, brackets, positionsKey, onModeChange, onTraded }: {
  symbol: string;
  currentPrice: number;
  brackets: FuturesBracket[] | undefined;
  positionsKey: number;
  onModeChange: (m: 'spot' | 'futures') => void;
  onTraded: () => void;
}) {
  const cfg = getCoin(symbol);
  // 交易过滤器（对齐Binance）：合约步长与现货不同（如BTC合约0.001 vs 现货0.00001），minNotional 是下单硬门槛
  const filter = useTradeFilter('futures', symbol);
  const MIN_QTY = filter.stepSize;
  const PRICE_DECIMALS = getCoinPriceDecimals(symbol);
  const PRICE_STEP = getCoinPriceStep(symbol);
  const PRICE_STEP_TEXT = PRICE_STEP.toFixed(PRICE_DECIMALS);
  const fmtPrice = (n?: number | null) => formatCoinPrice(symbol, n);
  const maxLeverage = brackets?.[0]?.maxLeverage ?? 0;
  const leverageOptions = FUTURES_LEVERAGE_OPTIONS.filter(lv => lv <= maxLeverage);

  const { toast } = useToast();
  const user = useUserStore(s => s.user);

  const [side, setSide] = useState<'LONG' | 'SHORT'>('LONG');
  const [leverage, setLeverage] = useState(10);
  const [marginMode, setMarginMode] = useState<FuturesMarginMode>('CROSS');
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [quantity, setQuantity] = useState('');
  const animateQuantity = useQuantityAnimation(quantity, setQuantity);
  const [limitPrice, setLimitPrice] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(false);
  const [slEnabled, setSlEnabled] = useState(false);
  const [slRows, setSlRows] = useState<SLTPRow[]>([{ price: '', quantity: '' }]);
  const [tpEnabled, setTpEnabled] = useState(false);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([{ price: '', quantity: '' }]);
  const [crossAcct, setCrossAcct] = useState<FuturesCrossAccount | null>(null);
  // 开仓/调杠杆成功后 +1 触发全仓账户与持仓快照重拉（可用/净值/杠杆都可能变了）
  const [acctTick, setAcctTick] = useState(0);
  // 保证金输入单位：币=未乘杠杆数量；USDT=保证金金额（内部再 /price 还原成币数量）
  const [marginUnit, setMarginUnit] = useState<'COIN' | 'USDT'>('USDT');
  // 该币现有仓位快照（≤2张，模式/杠杆币种级一致）
  const [positions, setPositions] = useState<FuturesPosition[]>([]);
  const [adjustingLev, setAdjustingLev] = useState(false);

  useEffect(() => {
    let alive = true;
    futuresApi.positions(symbol)
      .then(list => { if (alive) setPositions(list); })
      .catch(() => { if (alive) setPositions([]); });
    return () => { alive = false; };
  }, [symbol, positionsKey, acctTick]);

  // 币种级设置载体：两张仓位时模式/杠杆必一致，任取其一
  const heldPos = positions[0] ?? null;
  const posLeverage = heldPos?.leverage ?? null;
  // 有持仓时模式/杠杆以仓位为准（下单/预估全用有效值），本地 state 只在无持仓时生效
  const effMarginMode: FuturesMarginMode = heldPos ? heldPos.marginMode : marginMode;
  const isCross = effMarginMode === 'CROSS';
  const effLeverage = posLeverage ?? leverage;
  // 滑杆被拖离持仓杠杆=待确认的调杠杆操作（Binance语义：确认即独立生效，不是下单参数）
  const pendingLev = posLeverage != null && leverage !== posLeverage;

  // 档位数据到位后收敛超限杠杆
  useEffect(() => {
    if (maxLeverage > 0) setLeverage(lv => Math.min(lv, maxLeverage));
  }, [maxLeverage]);

  // 持仓杠杆变化（首拉/别处调整）时滑杆跟随归位
  useEffect(() => {
    if (posLeverage != null) setLeverage(posLeverage);
  }, [posLeverage]);

  // 全仓模式拉账户概览：预算基数和强平价兜底金都取自它
  useEffect(() => {
    if (!isCross) return;
    futuresApi.crossAccount().then(setCrossAcct).catch(() => setCrossAcct(null));
  }, [isCross, acctTick]);

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => setActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  // 估算价：限价用限价，否则现价（百分比换算、提交、单位切换共用）
  const priceForCalc = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  // 输入框展示值；统一换算成「币保证金数量」喂后续估算/下单
  const inputNum = parseFloat(quantity) || 0;
  const marginQty = marginUnit === 'USDT'
    ? (priceForCalc > 0 ? inputNum / priceForCalc : 0)
    : inputNum;

  const switchMarginUnit = (next: 'COIN' | 'USDT') => {
    if (next === marginUnit) return;
    // 有价才换算，避免除零；无输入则只切单位
    if (inputNum > 0 && priceForCalc > 0) {
      if (next === 'USDT') {
        setQuantity((inputNum * priceForCalc).toFixed(2));
      } else {
        const coin = inputNum / priceForCalc;
        const precision = getStepPrecision(MIN_QTY);
        setQuantity(coin.toFixed(precision).replace(/0+$/, '').replace(/\.$/, ''));
      }
    }
    setMarginUnit(next);
  };

  const handleSubmit = async () => {
    // 杠杆拖了没确认：先让用户把调杠杆这步走完，避免"滑杆显示50x实际按150x下单"的错位
    if (pendingLev) { toast('杠杆调整未确认，请先确认或还原', 'error'); return; }
    if (orderType === 'LIMIT') {
      const lp = parseFloat(limitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    // 实际下单币量按步长向下对齐（USDT模式÷价、×杠杆都会产生任意小数，后端按Binance规则硬校验）
    const orderQty = floorToStep(marginQty * effLeverage, filter.stepSize);
    if (orderQty < filter.minQty) { toast(`最小下单数量 ${filter.minQty} ${cfg.name}`, 'error'); return; }
    if (priceForCalc > 0 && orderQty * priceForCalc < filter.minNotional) {
      toast(`最小下单金额 ${filter.minNotional} USDT`, 'error'); return;
    }
    const slItems: FuturesSLItem[] = slEnabled
      ? slRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0).map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }))
      : [];
    const tpItems: FuturesTPItem[] = tpEnabled
      ? tpRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0).map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }))
      : [];
    const slTotal = slItems.reduce((s, r) => s + r.quantity, 0);
    const tpTotal = tpItems.reduce((s, r) => s + r.quantity, 0);
    if (slTotal > orderQty + 1e-9) { toast('止损总量超过开仓数量', 'error'); return; }
    if (tpTotal > orderQty + 1e-9) { toast('止盈总量超过开仓数量', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.open({
        symbol,
        side,
        quantity: orderQty,
        leverage: effLeverage,
        marginMode: effMarginMode,
        orderType,
        ...(orderType === 'LIMIT' ? { limitPrice: parseFloat(limitPrice) } : {}),
        ...(slItems.length > 0 ? { stopLosses: slItems } : {}),
        ...(tpItems.length > 0 ? { takeProfits: tpItems } : {}),
      });
      setActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      toast(`${side === 'LONG' ? '做多' : '做空'}开仓成功`, 'success');
      // USDT 模式别把最小币数当金额填回去
      setQuantity(marginUnit === 'USDT' ? '' : String(MIN_QTY));
      setLimitPrice('');
      setSlEnabled(false);
      setSlRows([{ price: '', quantity: '' }]);
      setTpEnabled(false);
      setTpRows([{ price: '', quantity: '' }]);
      setAcctTick(t => t + 1);
      onTraded();
    } catch (e: unknown) {
      toast((e as Error).message || '开仓失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 确认调杠杆（Binance语义：点确认那一刻即完成调整，独立于下单）：多空共用，同时作用于该币全部仓位；
  // 失败（全仓可用不够/逐仓禁调低）toast 后端信息并把滑杆弹回持仓杠杆
  const handleAdjustLeverage = async (target: number) => {
    setAdjustingLev(true);
    try {
      await futuresApi.adjustLeverage({ symbol, leverage: target });
      toast(`杠杆已调整为 ${target}x`, 'success');
      // 乐观更新本地快照：避免重拉落地前确认按钮仍挂着被二次点击
      setPositions(ps => ps.map(p => ({ ...p, leverage: target })));
      setAcctTick(t => t + 1);
      onTraded();
    } catch (e: unknown) {
      toast((e as Error).message || '杠杆调整失败', 'error');
      if (posLeverage != null) setLeverage(posLeverage);
    } finally { setAdjustingLev(false); }
  };

  // 预估（qtyNum = 币保证金数量，与单位无关；杠杆用有效值=有持仓时恒为仓位杠杆）
  const qtyNum = marginQty;
  const openEstimate = qtyNum > 0 && priceForCalc > 0
    ? calcFuturesOpenEstimate(qtyNum, priceForCalc, effLeverage)
    : null;
  // 全仓强平价：margin 参数换成兜底金 backing=equity−maintenanceMargin，即全仓拿整个账户净值兜底而非本仓保证金（无仓位时 maintenanceMargin=0，backing 就是 equity）；逐仓仍用本仓 margin
  const liqMargin = isCross
    ? (crossAcct ? crossAcct.equity - crossAcct.maintenanceMargin : null)
    : (openEstimate?.margin ?? null);
  const openLiq = openEstimate && liqMargin != null
    ? estimateFuturesLiqPrice(brackets, side, priceForCalc, liqMargin, openEstimate.orderQty)
    : null;
  // 负强平价=永不强平：显示 — 且不传给止损编辑器
  const openLiqPrice = openLiq && openLiq.price > 0 ? openLiq.price : undefined;
  // 下注预算基数：全仓=账户可用 available（余额扣掉已占用+挂单预留），逐仓=余额钱包
  const budgetBalance = isCross ? (crossAcct?.available ?? 0) : (user?.balance ?? 0);

  return (
    <>
      {/* 做多/做空切换 + 现货/合约 */}
      <div className="px-5 pt-5 flex flex-wrap items-center gap-3">
        <div className="flex flex-1 min-w-[140px] rounded-md border border-border overflow-hidden divide-x divide-border">
          <button onClick={() => setSide('LONG')} className={`flex-1 py-2.5 text-sm font-bold transition-colors cursor-pointer ${side === 'LONG' ? 'bg-gain text-white' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>做多</button>
          <button onClick={() => setSide('SHORT')} className={`flex-1 py-2.5 text-sm font-bold transition-colors cursor-pointer ${side === 'SHORT' ? 'bg-loss text-white' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>做空</button>
        </div>
        <TradeModeSwitch mode="futures" futuresOnly={cfg.futuresOnly} onModeChange={onModeChange} />
      </div>

      {/* flex-1 + 按钮 mt-auto：面板随左侧图表卡等高，止损止盈展开时消耗预留空档而不是撑高整卡 */}
      <CardContent className="p-5 mt-2 flex-1 flex flex-col gap-6">
        {/* 保证金模式 + 执行方式：模式是币种级设置（对齐Binance），有持仓时锁定不可切 */}
        <div className="flex items-center gap-2">
          <NeuToggle
            label="保证金模式"
            value={effMarginMode}
            onChange={m => {
              if (heldPos) { toast('有持仓时无法切换保证金模式', 'error'); return; }
              setMarginMode(m);
            }}
            options={[{ value: 'CROSS', label: '全仓' }, { value: 'ISOLATED', label: '逐仓' }]}
          />
          <NeuToggle
            label="执行方式"
            value={orderType}
            onChange={setOrderType}
            options={[{ value: 'MARKET', label: '市价' }, { value: 'LIMIT', label: '限价' }]}
          />
        </div>

        {/* 限价输入 */}
        {orderType === 'LIMIT' && (
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-muted-foreground">限价 (USDT)</label>
            <Input type="number" placeholder="输入限价" value={limitPrice} onChange={e => setLimitPrice(e.target.value)} step={PRICE_STEP_TEXT} min="0" />
            {parseFloat(limitPrice) > 0 && currentPrice > 0 && (
              (side === 'LONG' && parseFloat(limitPrice) >= currentPrice) ||
              (side === 'SHORT' && parseFloat(limitPrice) <= currentPrice)
            ) && (
              <div className="text-[10px] text-yellow-500">限价{side === 'LONG' ? '≥' : '≤'}当前价，将立即以市价成交</div>
            )}
          </div>
        )}

        {/* 杠杆选择：无持仓=本地状态；有持仓=调杠杆入口（拖动出确认，确认即改仓位，多空一起变） */}
        {(() => {
          const isolatedHeld = heldPos != null && !isCross;
          // 逐仓只能调高：滑杆下限=持仓杠杆，档位标签补持仓值作起点
          const sliderMin = isolatedHeld ? posLeverage! : 1;
          const ticks = isolatedHeld
            ? [...new Set([posLeverage!, ...FUTURES_LEVERAGE_OPTIONS.filter(lv => lv >= posLeverage! && lv <= maxLeverage)])].sort((a, b) => a - b)
            : leverageOptions;
          return (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <label className="text-xs font-bold text-muted-foreground flex items-center gap-1.5">
              <Scale className="w-3.5 h-3.5" /> 杠杆
            </label>
            <div className={`px-2 py-0.5 rounded text-xs font-bold tabular-nums ${pendingLev ? 'bg-yellow-500/10 text-yellow-500' : 'bg-primary/10 text-primary'}`}>{leverage}x</div>
          </div>
          <LeverageSlider
            value={leverage}
            min={sliderMin}
            max={maxLeverage}
            ticks={ticks}
            onChange={setLeverage}
          />
          {pendingLev ? (
            <div className="flex items-center gap-1.5">
              <Button size="sm" className="flex-1 h-9 sm:h-7 text-[11px]" disabled={adjustingLev} onClick={() => handleAdjustLeverage(leverage)}>
                {adjustingLev ? '调整中…' : `确认调整为 ${leverage}x（多空共用）`}
              </Button>
              <Button size="sm" variant="outline" className="h-9 sm:h-7 text-[11px]" disabled={adjustingLev} onClick={() => setLeverage(posLeverage!)}>
                还原
              </Button>
            </div>
          ) : (
            <div className="text-[10px] text-muted-foreground leading-relaxed">
              {heldPos
                ? `持仓 ${posLeverage}x · 多空共用，拖动滑杆确认后立即调整仓位杠杆${isCross ? '（调低需可用余额足够）' : '（逐仓只能调高）'}`
                : `当前币种最高 ${maxLeverage}x，实际 MMR 按仓位名义价值档位计算`}
            </div>
          )}
        </div>
          );
        })()}

        {/* 保证金数量：币 | USDT 双单位，内部统一换成币数量再算 */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <label className="text-xs font-bold text-muted-foreground flex items-center gap-1.5">
              <Wallet className="w-3.5 h-3.5" /> 保证金
            </label>
            <div className="flex items-center gap-2">
              {(isCross ? crossAcct : user) != null && (
                <span className="text-xs text-muted-foreground tabular-nums">
                  可用 {fmtNum(budgetBalance)} USDT
                </span>
              )}
              <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
                <button type="button" onClick={() => switchMarginUnit('COIN')}
                  className={`px-2 py-0.5 text-[10px] font-bold transition-colors cursor-pointer ${marginUnit === 'COIN' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                  {cfg.name}
                </button>
                <button type="button" onClick={() => switchMarginUnit('USDT')}
                  className={`px-2 py-0.5 text-[10px] font-bold transition-colors cursor-pointer ${marginUnit === 'USDT' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                  USDT
                </button>
              </div>
            </div>
          </div>
          <div className="relative">
            <Input
              type="number"
              placeholder={marginUnit === 'USDT' ? '0.00' : String(MIN_QTY)}
              value={quantity}
              onChange={e => setQuantity(e.target.value)}
              step={marginUnit === 'USDT' ? '0.01' : String(MIN_QTY)}
              min={marginUnit === 'USDT' ? 0 : MIN_QTY}
              className="pr-16"
            />
            <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground pointer-events-none">
              {marginUnit === 'USDT' ? 'USDT' : cfg.name}
            </span>
          </div>
          {/* 币安同款门槛：最小名义额 + 按当前杠杆折算的最小保证金（100x黄金≈0.05、50x≈0.10 的来源） */}
          <div className="text-[10px] text-muted-foreground">
            最小下单 {filter.minNotional} USDT · 步长 {filter.stepSize} {cfg.name}
            {effLeverage > 0 && ` · 最小保证金 ≈ ${(filter.minNotional / effLeverage).toFixed(2)} USDT`}
          </div>
          {priceForCalc > 0 && (
            <div className="grid grid-cols-4 gap-1.5">
              {POSITION_PCTS.map(pct => {
                const handlePct = () => {
                  if (marginUnit === 'USDT') {
                    // USDT 模式：预算×pct 直接填金额（两位小数）
                    const usdt = Math.floor(budgetBalance * pct * 100) / 100;
                    if (usdt > 0) setQuantity(usdt.toFixed(2)); else setQuantity('');
                  } else {
                    const qty = calcMaxAffordableMarginQty(budgetBalance, pct, priceForCalc, effLeverage, MIN_QTY);
                    if (qty > 0) animateQuantity(qty, MIN_QTY); else setQuantity('');
                  }
                };
                return (
                  <Button key={pct} size="sm" variant="outline" className="h-10 sm:h-7 text-[11px]" onClick={handlePct}>
                    {pct * 100}%
                  </Button>
                );
              })}
            </div>
          )}
        </div>

        {/* 预估信息 */}
        {openEstimate && (() => {
          const { positionValue, margin, commission, totalCost } = openEstimate;
          const liqPriceText = openLiqPrice ? `$${fmtPrice(openLiqPrice)}` : '—';
          const mmrText = openLiq ? `档位 ${openLiq.bracket.tier} / ${formatRate(openLiq.bracket.mmr)}` : '—';
          return (
            <div className="p-3.5 rounded-md border border-border bg-card-2 space-y-2.5">
              {/* 预估四项：手机单列防"标签+数值"挤爆，≥sm 恢复两列 */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">仓位价值</span>
                  <span className="font-mono">${fmtNum(positionValue)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">保证金</span>
                  <span className="font-mono">${fmtNum(margin)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">手续费 (0.04%)</span>
                  <span className="font-mono">${fmtNum(commission)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">维持保证金率</span>
                  <span className="font-mono">{mmrText}</span>
                </div>
              </div>
              <div className="flex justify-between items-center text-xs pt-2 border-t border-border/40">
                <span className="text-muted-foreground flex items-center gap-1">
                  <Flame className="w-3 h-3 text-yellow-500" /> 预估强平价
                </span>
                <span className="font-mono font-bold text-yellow-500">{liqPriceText}</span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-xs text-muted-foreground">合计需要</span>
                <span className="text-sm font-bold tabular-nums">${fmtNum(totalCost)}</span>
              </div>
            </div>
          );
        })()}

        {/* 开仓止损/止盈：手机单列（双列时价格/数量输入被挤到不可用），≥sm 恢复双列 */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-muted-foreground flex items-center gap-1">止损 <HelpTip text="标记价格触及止损价时自动平仓对应数量，可设多档分批止损" /></label>
              <button type="button" onClick={() => { setSlEnabled(!slEnabled); setSlRows([{ price: '', quantity: '' }]); }}
                className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${slEnabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}>
                <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform shadow-sm ${slEnabled ? 'translate-x-4.5' : 'translate-x-0.75'}`} />
              </button>
            </div>
            {slEnabled && (
              <SLTPEditor rows={slRows} onChange={setSlRows} label="止损" posQty={qtyNum * effLeverage} minQty={MIN_QTY}
                entryPrice={priceForCalc || currentPrice} margin={openEstimate?.margin ?? 0} side={side}
                minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
            )}
          </div>
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-muted-foreground flex items-center gap-1">止盈 <HelpTip text="现价触及止盈价时自动平仓对应数量，可设多档分批止盈" /></label>
              <button type="button" onClick={() => { setTpEnabled(!tpEnabled); setTpRows([{ price: '', quantity: '' }]); }}
                className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${tpEnabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}>
                <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform shadow-sm ${tpEnabled ? 'translate-x-4.5' : 'translate-x-0.75'}`} />
              </button>
            </div>
            {tpEnabled && (
              <SLTPEditor rows={tpRows} onChange={setTpRows} label="止盈" posQty={qtyNum * effLeverage} minQty={MIN_QTY}
                entryPrice={priceForCalc || currentPrice} margin={openEstimate?.margin ?? 0} side={side}
                minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
            )}
          </div>
        </div>

        {/* 开仓按钮：杠杆调整待确认时置灰，防止"滑杆显示的杠杆"与"实际下单杠杆"错位 */}
        <FuturesActionButton
          className="mt-auto"
          onClick={handleSubmit}
          disabled={submitting || currentPrice <= 0 || pendingLev}
          loading={submitting}
          success={actionSuccess}
          side={side}
          leverage={effLeverage}
        />
      </CardContent>
    </>
  );
}
