import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useToast } from '../ui/use-toast';
import { FuturesActionButton } from '../FuturesActionButton';
import { LeverageSlider } from '../LeverageSlider';
import { HelpTip } from '../HelpTip';
import { cn, fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import { useTradeFilter } from '../../lib/tradeFilters';
import type { FuturesBracket, FuturesCrossAccount, FuturesMarginMode, FuturesPosition, FuturesSLItem, FuturesTPItem } from '../../types';
import { TradeModeSwitch } from './TradeModeSwitch';
import { SLTPEditor } from './SLTPEditor';
import { NumInput, PctRow } from './TradeFields';
import { useQuantityAnimation } from './useQuantityAnimation';
import {
  POSITION_PCTS, FUTURES_LEVERAGE_OPTIONS, formatRate, getStepPrecision, floorToStep, qtyByPct,
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
  const { t } = useTranslation('trade');
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
  const [slRows, setSlRows] = useState<SLTPRow[]>([{ price: '', quantity: '' }]);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([{ price: '', quantity: '' }]);
  // 手机上止盈止损默认收起；收起行摘要：填了价格的，一档显示价格，多档显示档数
  const [sltpOpen, setSltpOpen] = useState(false);
  const sltpSummary = ([['sl', slRows], ['tp', tpRows]] as const)
    .map(([k, rows]) => {
      const set = rows.filter(r => parseFloat(r.price) > 0);
      if (!set.length) return null;
      return `${t(`sltp.${k}`)} ${set.length === 1 ? fmtPrice(parseFloat(set[0].price)) : t('sltp.levels', { count: set.length })}`;
    })
    .filter(Boolean)
    .join(' · ') || t('sltp.notSet');
  const [crossAcct, setCrossAcct] = useState<FuturesCrossAccount | null>(null);
  // 开仓/调杠杆成功后 +1 触发全仓账户与持仓快照重拉（可用/净值/杠杆都可能变了）
  const [acctTick, setAcctTick] = useState(0);
  // 保证金输入单位：币=未乘杠杆数量；USDT=保证金金额（内部再 /price 还原成币数量）。点输入框右侧单位切换
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
    const timer = window.setTimeout(() => setActionSuccess(false), 800);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

  // 估算价：限价用限价，否则现价（百分比换算、提交、单位切换共用）
  const priceForCalc = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  // 输入框展示值；统一换算成「币保证金数量」喂后续估算/下单
  const inputNum = parseFloat(quantity) || 0;
  const marginQty = marginUnit === 'USDT'
    ? (priceForCalc > 0 ? inputNum / priceForCalc : 0)
    : inputNum;
  // 实际下单币量 = 保证金数量×杠杆，按步长向下对齐。SL/TP 的 100% 必须拿它算：
  // 用未对齐的 marginQty×杠杆 会多出一截尾数，提交时正好被"止损总量超过开仓数量"挡下
  const orderQty = floorToStep(marginQty * effLeverage, MIN_QTY);

  /** 默认档位行：数量预填满仓(100%)。开仓量还没输入时留空，免得显示成 "0" */
  const fullSltpRow = (): SLTPRow => ({ price: '', quantity: orderQty > 0 ? String(orderQty) : '' });

  // 开仓量随数量/单位/杠杆变，已设档位按各自百分比跟着重算——用户表达的是"平多少比例"，
  // 改开仓量不该把比例冲掉。数量还空着的行补满 100%
  const prevOrderQty = useRef(orderQty);
  useEffect(() => {
    const prev = prevOrderQty.current;
    prevOrderQty.current = orderQty;
    if (prev === orderQty) return;
    const rescale = (rows: SLTPRow[]) => rows.map(r => {
      if (orderQty <= 0) return { ...r, quantity: '' };
      const q = parseFloat(r.quantity) || 0;
      const pct = q > 0 && prev > 0 ? Math.round((q / prev) * 100) : 100;
      return { ...r, quantity: qtyByPct(orderQty, pct, MIN_QTY) };
    });
    setSlRows(rescale);
    setTpRows(rescale);
  }, [orderQty, MIN_QTY]);

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
    if (pendingLev) { toast(t('toast.levNotConfirmed'), 'error'); return; }
    if (orderType === 'LIMIT') {
      const lp = parseFloat(limitPrice);
      if (!lp || lp <= 0) { toast(t('toast.invalidLimit'), 'error'); return; }
    }
    // orderQty 已在上方按步长对齐（USDT模式÷价、×杠杆都会产生任意小数，后端按Binance规则硬校验）
    if (orderQty < filter.minQty) { toast(t('toast.minQty', { qty: filter.minQty, unit: cfg.name }), 'error'); return; }
    if (priceForCalc > 0 && orderQty * priceForCalc < filter.minNotional) {
      toast(t('toast.minNotional', { amount: filter.minNotional }), 'error'); return;
    }
    // 价格留空的档位=没设，直接滤掉；一档都不填就是不带止损/止盈开仓
    const slItems: FuturesSLItem[] = slRows
      .filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    const tpItems: FuturesTPItem[] = tpRows
      .filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    const slTotal = slItems.reduce((s, r) => s + r.quantity, 0);
    const tpTotal = tpItems.reduce((s, r) => s + r.quantity, 0);
    if (slTotal > orderQty + 1e-9) { toast(t('toast.slOverQty'), 'error'); return; }
    if (tpTotal > orderQty + 1e-9) { toast(t('toast.tpOverQty'), 'error'); return; }
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
      toast(t(side === 'LONG' ? 'toast.openedLong' : 'toast.openedShort'), 'success');
      // USDT 模式别把最小币数当金额填回去
      setQuantity(marginUnit === 'USDT' ? '' : String(MIN_QTY));
      setLimitPrice('');
      setSlRows([fullSltpRow()]);
      setTpRows([fullSltpRow()]);
      setAcctTick(t => t + 1);
      onTraded();
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.openFailed'), 'error');
    } finally { setSubmitting(false); }
  };

  // 确认调杠杆（Binance语义：点确认那一刻即完成调整，独立于下单）：多空共用，同时作用于该币全部仓位；
  // 失败（全仓可用不够/逐仓禁调低）toast 后端信息并把滑杆弹回持仓杠杆
  const handleAdjustLeverage = async (target: number) => {
    setAdjustingLev(true);
    try {
      await futuresApi.adjustLeverage({ symbol, leverage: target });
      toast(t('toast.levAdjusted', { lev: target }), 'success');
      // 乐观更新本地快照：避免重拉落地前确认按钮仍挂着被二次点击
      setPositions(ps => ps.map(p => ({ ...p, leverage: target })));
      setAcctTick(t => t + 1);
      onTraded();
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.levFailed'), 'error');
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
  const liqText = openLiqPrice ? fmtPrice(openLiqPrice) : '—';
  // 下注预算基数：全仓=账户可用 available（余额扣掉已占用+挂单预留）；
  // 逐仓要真划钱，卡两道取小——available 管"钱是不是被全仓占着"，balance 管"钱包里有没有现金"
  // （全仓浮盈进得了 available 进不了 balance）。快照没回来就退回余额，别把可用显示成 0
  const budgetBalance = isCross
    ? (crossAcct?.available ?? 0)
    : Math.min(crossAcct?.available ?? Infinity, user?.balance ?? 0);

  /** 保证金按钮：当前输入正好等于某档预算时高亮那一颗 */
  const pctTarget = (pct: number) => marginUnit === 'USDT'
    ? Math.floor(budgetBalance * pct * 100) / 100
    : calcMaxAffordableMarginQty(budgetBalance, pct, priceForCalc, effLeverage, MIN_QTY);
  const activePct = inputNum > 0 && priceForCalc > 0
    ? (POSITION_PCTS.find(p => Math.abs(pctTarget(p) - inputNum) < 1e-9) ?? null)
    : null;
  const handlePct = (pct: number) => {
    const target = pctTarget(pct);
    if (!(target > 0)) { setQuantity(''); return; }
    // USDT 模式直接填金额（两位小数），币模式走缓动
    if (marginUnit === 'USDT') setQuantity(target.toFixed(2));
    else animateQuantity(target, MIN_QTY);
  };

  // 逐仓只能调高：滑杆下限=持仓杠杆，档位标签补持仓值作起点
  const isolatedHeld = heldPos != null && !isCross;
  const sliderMin = isolatedHeld ? posLeverage! : 1;
  const levTicks = isolatedHeld
    ? [...new Set([posLeverage!, ...FUTURES_LEVERAGE_OPTIONS.filter(lv => lv >= posLeverage! && lv <= maxLeverage)])].sort((a, b) => a - b)
    : leverageOptions;

  const limitWarn = parseFloat(limitPrice) > 0 && currentPrice > 0 && (
    (side === 'LONG' && parseFloat(limitPrice) >= currentPrice) ||
    (side === 'SHORT' && parseFloat(limitPrice) <= currentPrice)
  );

  return (
    <>
      {/* 合约/现货 + 可用 */}
      <div className="flex justify-between items-center">
        <TradeModeSwitch mode="futures" futuresOnly={cfg.futuresOnly} onModeChange={onModeChange} />
        {(isCross ? crossAcct : user) != null && (
          <span className="text-[12.5px] text-muted-foreground">
            {t('open.availLabel')} <b className="num text-foreground font-semibold">{fmtNum(budgetBalance)}</b> USDT
          </span>
        )}
      </div>

      {/* 做多 / 做空 */}
      <div className="grid grid-cols-2 border-[1.5px] border-foreground">
        <button
          onClick={() => setSide('LONG')}
          className={cn('h-12 text-[17px] font-extrabold cursor-pointer transition-colors', side === 'LONG' ? 'bg-gain text-white' : 'text-muted-foreground hover:text-foreground')}
        >{t('side.long')}</button>
        <button
          onClick={() => setSide('SHORT')}
          className={cn('h-12 text-[17px] font-extrabold cursor-pointer transition-colors', side === 'SHORT' ? 'bg-loss text-white' : 'text-muted-foreground hover:text-foreground')}
        >{t('side.short')}</button>
      </div>

      {/* 保证金模式（币种级设置，有持仓锁定）+ 委托类型 */}
      <div className="grid grid-cols-2 gap-3.5">
        <div className="field">
          <label>{t('marginMode.label')}</label>
          <div className="seg flex">
            {(['CROSS', 'ISOLATED'] as const).map(m => (
              <button
                key={m}
                className={cn('flex-1', effMarginMode === m && 'on')}
                onClick={() => { if (heldPos) { toast(t('toast.marginModeLocked'), 'error'); return; } setMarginMode(m); }}
              >{t(m === 'CROSS' ? 'marginMode.cross' : 'marginMode.isolated')}</button>
            ))}
          </div>
        </div>
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
      </div>

      {orderType === 'LIMIT' && (
        <div className="field">
          <label>{t('open.limitLabel')}</label>
          <NumInput value={limitPrice} onChange={setLimitPrice} placeholder={t('open.limitPlaceholder')} step={PRICE_STEP_TEXT} min="0" unit="USDT" />
          {limitWarn && <div className="text-[12px] text-warning">{t(side === 'LONG' ? 'open.limitFillsGte' : 'open.limitFillsLte')}</div>}
        </div>
      )}

      {/* 杠杆：无持仓=本地状态；有持仓=调杠杆入口（拖动出确认，确认即改仓位，多空一起变） */}
      <div className="flex flex-col gap-2">
        <div className="flex justify-between items-baseline text-[12.5px] font-semibold text-muted-foreground">
          <span>{t('lev.label')}</span>
          <b className={cn('num text-[20px] font-bold', pendingLev ? 'text-warning' : 'text-foreground')}>{leverage}x</b>
        </div>
        <LeverageSlider value={leverage} min={sliderMin} max={maxLeverage} ticks={levTicks} onChange={setLeverage} />
        {pendingLev && (
          <div className="flex gap-1.5">
            <button className="btn xs fill" disabled={adjustingLev} onClick={() => handleAdjustLeverage(leverage)}>
              {adjustingLev ? t('lev.applying') : t('lev.apply', { lev: leverage })}
            </button>
            <button className="btn xs" disabled={adjustingLev} onClick={() => setLeverage(posLeverage!)}>{t('lev.revert')}</button>
          </div>
        )}
      </div>

      {/* 保证金：右侧单位可点，币 ↔ USDT 换算 */}
      <div className="field">
        <label>
          <span>{t('open.marginLabel')}</span>
          <span>
            {t('open.minOrder', { step: filter.stepSize, unit: cfg.name })}
            {effLeverage > 0 && ` · ${t('open.minMargin', { amount: (filter.minNotional / effLeverage).toFixed(2) })}`}
          </span>
        </label>
        <NumInput
          value={quantity}
          onChange={setQuantity}
          placeholder={marginUnit === 'USDT' ? '0.00' : String(MIN_QTY)}
          step={marginUnit === 'USDT' ? '0.01' : String(MIN_QTY)}
          min={marginUnit === 'USDT' ? 0 : MIN_QTY}
          unit={marginUnit === 'USDT' ? 'USDT' : cfg.name}
          unitTitle={t('open.switchUnit')}
          onUnitClick={() => switchMarginUnit(marginUnit === 'USDT' ? 'COIN' : 'USDT')}
        />
        {priceForCalc > 0 && <PctRow active={activePct} onPick={handlePct} />}
      </div>

      {/* 预估 */}
      {openEstimate && (
        <div className="num border-t border-foreground pt-1">
          <div className="kv py-[7px]">
            <span className="k">{t('open.positionValue')}</span>
            <span className="v">{fmtNum(openEstimate.positionValue)}</span>
          </div>
          <div className="kv py-[7px]">
            <span className="k">{t('open.fee')}</span>
            <span className="v">{fmtNum(openEstimate.commission)}</span>
          </div>
          <div className="kv py-[7px]">
            <span className="k">{t('open.mmr')}{openLiq ? ` ${t('open.tierShort', { tier: openLiq.bracket.tier })}` : ''}</span>
            <span className="v">{openLiq ? formatRate(openLiq.bracket.mmr) : '—'}</span>
          </div>
          <div className="kv py-[7px]">
            <span className="k">{t('open.estLiq')}</span>
            <span className="v text-warning">{liqText}</span>
          </div>
          <div className="kv py-[7px] border-b-0 text-[16px]">
            <span className="k">{t('open.totalRequired')}</span>
            <span className="v text-[20px] font-bold [font-stretch:85%]">{fmtNum(openEstimate.totalCost)} USDT</span>
          </div>
        </div>
      )}

      {/* 手机上止盈止损收成一行，点开才显示编辑器；电脑端常显 */}
      <button type="button" onClick={() => setSltpOpen(o => !o)}
              className="md:hidden flex items-center gap-1.5 h-10 border-y border-border text-[13px] font-semibold cursor-pointer">
        <ChevronRight className={cn('w-3.5 h-3.5 shrink-0 transition-transform', sltpOpen && 'rotate-90')} />
        {t('sltp.toggle')}
        <span className="ml-auto min-w-0 truncate num font-medium text-muted-foreground">{sltpSummary}</span>
      </button>

      {/* 开仓止损/止盈：价格留空就是不设。手机单列（双列时价格/数量输入被挤到不可用），≥sm 恢复双列 */}
      <div className={cn('grid grid-cols-1 sm:grid-cols-2 gap-3.5', !sltpOpen && 'max-md:hidden')}>
        <div className="field">
          <label>
            <span className="flex items-center gap-1">{t('sltp.sl')} <HelpTip text={t('sltp.slHelpOpen')} /></span>
          </label>
          <SLTPEditor rows={slRows} onChange={setSlRows} kind="SL" posQty={orderQty} minQty={MIN_QTY}
            entryPrice={priceForCalc || currentPrice} margin={openEstimate?.margin ?? 0} side={side} unit={cfg.name}
            minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
        </div>
        <div className="field">
          <label>
            <span className="flex items-center gap-1">{t('sltp.tp')} <HelpTip text={t('sltp.tpHelpOpen')} /></span>
          </label>
          <SLTPEditor rows={tpRows} onChange={setTpRows} kind="TP" posQty={orderQty} minQty={MIN_QTY}
            entryPrice={priceForCalc || currentPrice} margin={openEstimate?.margin ?? 0} side={side} unit={cfg.name}
            minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
        </div>
      </div>

      {/* 杠杆调整待确认时置灰，防止"滑杆显示的杠杆"与"实际下单杠杆"错位 */}
      <FuturesActionButton
        onClick={handleSubmit}
        disabled={submitting || currentPrice <= 0 || pendingLev}
        loading={submitting}
        success={actionSuccess}
        side={side}
        label={symbol}
        leverage={effLeverage}
      />

      <p className="text-[12px] text-muted-foreground leading-[1.6]">
        {t(isCross ? 'open.hintCross' : 'open.hintIsolated')}
        {openLiqPrice ? t('open.hintLiq', { liq: liqText }) : ''}
      </p>
    </>
  );
}
