import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useCrossAccount } from '../../hooks/useCrossAccount';
import { useSubmitFx } from '../../hooks/useSubmitFx';
import { useToast } from '../ui/use-toast';
import { Seg } from '../ui/seg';
import { FuturesActionButton } from '../FuturesActionButton';
import { LeverageSlider } from '../LeverageSlider';
import { HelpTip } from '../HelpTip';
import { cn, fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import { useTradeFilter } from '../../lib/tradeFilters';
import type { FuturesBracket, FuturesMarginMode, FuturesPosition, FuturesSLItem, FuturesTPItem } from '../../types';
import { TradeModeSwitch } from './TradeModeSwitch';
import { SLTPEditor } from './SLTPEditor';
import { NumInput, PctRow } from './TradeFields';
import {
  POSITION_PCTS, FUTURES_LEVERAGE_OPTIONS, formatRate, floorToStep, qtyByPct,
  calcFuturesOpenEstimate, calcMaxAffordableOrderQty, orderQtyToMarginUsdt, orderQtyToMarginCoin, estimateFuturesLiqPrice,
  type SLTPRow,
} from './futuresMath';

/**
 * 合约开仓面板（浅灰块，样式见 pages/Coin.css .ord）：方向、保证金模式、市价/限价、杠杆、保证金数量、预估、开仓止损/止盈。
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
  const limitId = useId();
  const amtId = useId();
  const limitRef = useRef<HTMLInputElement>(null);

  const { toast } = useToast();
  const user = useUserStore(s => s.user);

  const [side, setSide] = useState<'LONG' | 'SHORT'>('LONG');
  const [leverage, setLeverage] = useState(10);
  const [marginMode, setMarginMode] = useState<FuturesMarginMode>('CROSS');
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [quantity, setQuantity] = useState('');
  const [limitPrice, setLimitPrice] = useState('');
  // 开仓大按钮的转圈 / 打勾 / 晃一下
  const fx = useSubmitFx<'open'>();
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
  // 全仓账户快照：预算基数和强平价兜底金都取自它，fetchUser 后自动重拉
  const { acct: crossAcct, spendable } = useCrossAccount();
  // 开仓/调杠杆成功后 +1 触发持仓快照重拉
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

  // 估算价：限价用限价，否则现价（百分比换算、提交、单位切换共用）
  const priceForCalc = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  // 输入框展示值；统一换算成实际下单币量喂后续估算/下单
  const inputNum = parseFloat(quantity) || 0;
  /** 输入值 → 实际下单币量：先还原成币保证金数量（USDT ÷价），×杠杆后按步长向下对齐 */
  const toOrderQty = (v: number) => {
    const marginQty = marginUnit === 'USDT' ? (priceForCalc > 0 ? v / priceForCalc : 0) : v;
    return floorToStep(marginQty * effLeverage, MIN_QTY);
  };
  // SL/TP 的 100% 必须拿对齐后的单量算：
  // 用未对齐的 保证金数量×杠杆 会多出一截尾数，提交时正好被"止损总量超过开仓数量"挡下
  const orderQty = toOrderQty(inputNum);

  // 开仓量随数量/单位/杠杆/现价变，已设档位按各自百分比跟着重算——用户表达的是"平多少比例"，
  // 改开仓量不该把比例冲掉。开仓量一时为 0（改金额敲到一半）档位先不动，等有量了按上一次的量折算；
  // 只有一档且数量空着的（默认那一档）补满 100%，后加的空档留给用户自己分
  const lastOrderQty = useRef(orderQty);
  useEffect(() => {
    const prev = lastOrderQty.current;
    if (!(orderQty > 0) || prev === orderQty) return;
    lastOrderQty.current = orderQty;
    const rescale = (rows: SLTPRow[]) => rows.map(r => {
      const q = parseFloat(r.quantity) || 0;
      if (q > 0) return prev > 0 ? { ...r, quantity: qtyByPct(orderQty, Math.round((q / prev) * 100), MIN_QTY) } : r;
      return rows.length === 1 ? { ...r, quantity: String(orderQty) } : r;
    });
    setSlRows(rescale);
    setTpRows(rescale);
  }, [orderQty, MIN_QTY]);

  const switchMarginUnit = (next: 'COIN' | 'USDT') => {
    if (next === marginUnit) return;
    // 有价才换算，避免除零；无输入则只切单位。按当前下单量换过去，切完单量不变；还凑不够一个步长就清空
    if (inputNum > 0 && priceForCalc > 0) {
      if (!(orderQty > 0)) setQuantity('');
      else if (next === 'USDT') setQuantity(orderQtyToMarginUsdt(orderQty, priceForCalc, effLeverage, MIN_QTY).toFixed(2));
      else setQuantity(String(orderQtyToMarginCoin(orderQty, effLeverage, MIN_QTY)));
    }
    setMarginUnit(next);
  };

  /** 没过本地校验：说原因，按钮晃一下 */
  const reject = (msg: string) => { toast(msg, 'error'); fx.nope('open'); };

  const handleSubmit = async () => {
    // 杠杆拖了没确认：先让用户把调杠杆这步走完，避免"滑杆显示50x实际按150x下单"的错位
    if (pendingLev) return reject(t('toast.levNotConfirmed'));
    if (orderType === 'LIMIT') {
      const lp = parseFloat(limitPrice);
      if (!lp || lp <= 0) return reject(t('toast.invalidLimit'));
    }
    // orderQty 已在上方按步长对齐（USDT模式÷价、×杠杆都会产生任意小数，后端按Binance规则硬校验）
    if (orderQty < filter.minQty) return reject(t('toast.minQty', { qty: filter.minQty, unit: cfg.name }));
    if (priceForCalc > 0 && orderQty * priceForCalc < filter.minNotional) {
      return reject(t('toast.minNotional', { amount: filter.minNotional }));
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
    if (slTotal > orderQty + 1e-9) return reject(t('toast.slOverQty'));
    if (tpTotal > orderQty + 1e-9) return reject(t('toast.tpOverQty'));
    await fx.run('open', async () => {
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
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      toast(t(side === 'LONG' ? 'toast.openedLong' : 'toast.openedShort'), 'success');
      // USDT 模式别把最小币数当金额填回去；币模式留一个最小量，对应的开仓量是它乘杠杆
      setQuantity(marginUnit === 'USDT' ? '' : String(MIN_QTY));
      const nextQty = marginUnit === 'USDT' ? 0 : floorToStep(MIN_QTY * effLeverage, MIN_QTY);
      setLimitPrice('');
      // 档位回到默认的一档，数量跟着输入框里留下的量走；没量就空着，下次有量时补满 100%
      lastOrderQty.current = nextQty;
      const row = { price: '', quantity: nextQty > 0 ? String(nextQty) : '' };
      setSlRows([row]);
      setTpRows([row]);
      setAcctTick(t => t + 1);
      onTraded();
    }, e => toast((e as Error).message || t('toast.openFailed'), 'error'));
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

  // 预估按实际下单量 orderQty 算（与单位无关；杠杆用有效值=有持仓时恒为仓位杠杆）
  const openEstimate = orderQty > 0 && priceForCalc > 0
    ? calcFuturesOpenEstimate(orderQty, priceForCalc, effLeverage)
    : null;
  // 全仓强平价：margin 参数换成兜底金 backing=equity−maintenanceMargin，即全仓拿整个账户净值兜底而非本仓保证金（无仓位时 maintenanceMargin=0，backing 就是 equity）；逐仓仍用本仓 margin
  const liqMargin = isCross
    ? (crossAcct ? crossAcct.equity - crossAcct.maintenanceMargin : null)
    : (openEstimate?.margin ?? null);
  const openLiq = openEstimate && liqMargin != null
    ? estimateFuturesLiqPrice(brackets, side, priceForCalc, liqMargin, orderQty)
    : null;
  // 负强平价=永不强平：显示 — 且不传给止损编辑器
  const openLiqPrice = openLiq && openLiq.price > 0 ? openLiq.price : undefined;
  const liqText = openLiqPrice ? fmtPrice(openLiqPrice) : '—';
  // 下注预算基数：全仓=账户可用 available（余额扣掉已占用+挂单预留）；
  // 逐仓要真划钱，卡两道取小——available 管"钱是不是被全仓占着"，balance 管"钱包里有没有现金"
  // （全仓浮盈进得了 available 进不了 balance），即 spendable。快照没回来它先按余额算，别把可用显示成 0
  const budgetBalance = isCross
    ? (crossAcct?.available ?? 0)
    : (spendable ?? 0);

  /** 保证金按钮：按"保证金+手续费 ≤ 预算"在下单量网格上取最大单量，再换回当前单位的输入值 */
  const pctTarget = (pct: number) => {
    const maxQty = calcMaxAffordableOrderQty(budgetBalance * pct, priceForCalc, effLeverage, MIN_QTY);
    return marginUnit === 'USDT'
      ? orderQtyToMarginUsdt(maxQty, priceForCalc, effLeverage, MIN_QTY)
      : orderQtyToMarginCoin(maxQty, effLeverage, MIN_QTY);
  };
  // 高亮：当前输入换出的下单量正好等于某档换出的下单量
  const activePct = orderQty > 0 && priceForCalc > 0
    ? (POSITION_PCTS.find(p => toOrderQty(pctTarget(p)) === orderQty) ?? null)
    : null;
  const handlePct = (pct: number) => {
    const target = pctTarget(pct);
    // USDT 模式填金额（两位小数）；币模式的保证金币数常比步长细，原样填
    setQuantity(!(target > 0) ? '' : marginUnit === 'USDT' ? target.toFixed(2) : String(target));
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
    <div className="ord blk" aria-label={t('mode.futures')}>
      {/* 合约/现货 + 可用 */}
      <div className="rowb">
        <TradeModeSwitch mode="futures" futuresOnly={cfg.futuresOnly} onModeChange={onModeChange} />
        {(isCross ? crossAcct : user) != null && (
          <span className="avail">{t('open.availLabel')} <b>{fmtNum(budgetBalance)}</b> USDT</span>
        )}
      </div>

      <Seg className="k-side" value={side} onChange={setSide} options={[
        { value: 'LONG', label: t('side.long') },
        { value: 'SHORT', label: t('side.short') },
      ]} />

      {/* 保证金模式（币种级设置，有持仓锁定：点了晃一下并说原因）+ 委托类型 */}
      <div className="two">
        <div className="fld">
          <div className="fl">{t('marginMode.label')}</div>
          <Seg className="full" value={effMarginMode} label={t('marginMode.label')}
               onChange={m => {
                 if (heldPos) { toast(t('toast.marginModeLocked'), 'error'); return false; }
                 setMarginMode(m);
               }}
               options={[
                 { value: 'CROSS', label: t('marginMode.cross') },
                 { value: 'ISOLATED', label: t('marginMode.isolated') },
               ]} />
        </div>
        <div className="fld">
          <div className="fl">{t('orderType.label')}</div>
          <Seg className="full" value={orderType} label={t('orderType.label')}
               onChange={o => {
                 setOrderType(o);
                 // 切到限价：等折叠层展开那一帧再把光标放进限价框
                 if (o === 'LIMIT') requestAnimationFrame(() => limitRef.current?.focus({ preventScroll: true }));
               }}
               options={[
                 { value: 'MARKET', label: t('orderType.market') },
                 { value: 'LIMIT', label: t('orderType.limit') },
               ]} />
        </div>
      </div>

      <div className={cn('col', orderType === 'LIMIT' && 'open')}>
        <div>
          <div className="fld gapb">
            <label className="fl" htmlFor={limitId}>{t('open.limitLabel')}</label>
            <NumInput id={limitId} ref={limitRef} value={limitPrice} onChange={setLimitPrice} placeholder={t('open.limitPlaceholder')}
                      step={PRICE_STEP_TEXT} min="0" unit="USDT" />
            {limitWarn && <div className="warn">{t(side === 'LONG' ? 'open.limitFillsGte' : 'open.limitFillsLte')}</div>}
          </div>
        </div>
      </div>

      {/* 杠杆：无持仓=本地状态；有持仓=调杠杆入口（拖动出确认，确认即改仓位，多空一起变） */}
      <div className={cn('lev', pendingLev && 'pending')}>
        <div className="fl">{t('lev.label')}<b className="v">{leverage}x</b></div>
        <LeverageSlider value={leverage} min={sliderMin} max={maxLeverage} ticks={levTicks} onChange={setLeverage} />
        <div className={cn('col', pendingLev && 'open')}>
          <div>
            <div className="levact">
              <button type="button" className={cn('btn sm ink', adjustingLev && 'busy')} onClick={() => handleAdjustLeverage(leverage)}>
                {t('lev.apply', { lev: leverage })}
              </button>
              <button type="button" className="btn sm" disabled={adjustingLev} onClick={() => setLeverage(posLeverage!)}>{t('lev.revert')}</button>
            </div>
          </div>
        </div>
      </div>

      {/* 保证金：右侧单位可点，币 ↔ USDT 换算 */}
      <div className="fld">
        <label className="fl" htmlFor={amtId}>
          <span>{t('open.marginLabel')}</span>
          <span>
            {t('open.minOrder', { step: filter.stepSize, unit: cfg.name })}
            {effLeverage > 0 && ` · ${t('open.minMargin', { amount: (filter.minNotional / effLeverage).toFixed(2) })}`}
          </span>
        </label>
        <NumInput
          id={amtId}
          value={quantity}
          onChange={setQuantity}
          placeholder={marginUnit === 'USDT' ? '0.00' : String(MIN_QTY)}
          step={marginUnit === 'USDT' ? '0.01' : String(MIN_QTY)}
          min={marginUnit === 'USDT' ? 0 : MIN_QTY}
          unit={marginUnit === 'USDT' ? 'USDT' : cfg.name}
          unitTitle={t('open.switchUnit')}
          unitFlip={marginUnit === 'COIN'}
          onUnitClick={() => switchMarginUnit(marginUnit === 'USDT' ? 'COIN' : 'USDT')}
        />
        {priceForCalc > 0 && <PctRow active={activePct} onPick={handlePct} />}
      </div>

      {/* 预估：填了数量才展开 */}
      <div className={cn('col', openEstimate && 'open')}>
        <div>
          {openEstimate && (
            <div className="kv sum">
              <div><span>{t('open.positionValue')}</span><b>{fmtNum(openEstimate.positionValue)}</b></div>
              <div><span>{t('open.fee')}</span><b>{fmtNum(openEstimate.commission)}</b></div>
              <div>
                <span>{t('open.mmr')}{openLiq ? ` ${t('open.tierShort', { tier: openLiq.bracket.tier })}` : ''}</span>
                <b>{openLiq ? formatRate(openLiq.bracket.mmr) : '—'}</b>
              </div>
              <div><span>{t('open.estLiq')}</span><b className="wn">{liqText}</b></div>
              <div><span>{t('open.totalRequired')}</span><b className="tot">{fmtNum(openEstimate.totalCost)}<small>USDT</small></b></div>
            </div>
          )}
        </div>
      </div>

      {/* 手机上止盈止损收成一行，点开才显示编辑器；电脑端常显 */}
      <button type="button" className="sltp-tg md:hidden" aria-expanded={sltpOpen} onClick={() => setSltpOpen(o => !o)}>
        <ChevronRight className="ic" />
        {t('sltp.toggle')}
        <span>{sltpSummary}</span>
      </button>

      {/* 开仓止损/止盈：价格留空就是不设 */}
      <div className={cn('two sltp', !sltpOpen && 'max-md:hidden')}>
        <div className="fld">
          <div className="fl"><span>{t('sltp.sl')}<HelpTip text={t('sltp.slHelpOpen')} /></span></div>
          <SLTPEditor rows={slRows} onChange={setSlRows} kind="SL" posQty={orderQty} minQty={MIN_QTY}
            entryPrice={priceForCalc || currentPrice} margin={openEstimate?.margin ?? 0} side={side} unit={cfg.name}
            minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
        </div>
        <div className="fld">
          <div className="fl"><span>{t('sltp.tp')}<HelpTip text={t('sltp.tpHelpOpen')} /></span></div>
          <SLTPEditor rows={tpRows} onChange={setTpRows} kind="TP" posQty={orderQty} minQty={MIN_QTY}
            entryPrice={priceForCalc || currentPrice} margin={openEstimate?.margin ?? 0} side={side} unit={cfg.name}
            minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
        </div>
      </div>

      {/* 杠杆调整待确认时置灰，防止"滑杆显示的杠杆"与"实际下单杠杆"错位 */}
      <FuturesActionButton
        onClick={handleSubmit}
        disabled={currentPrice <= 0 || pendingLev}
        fx={fx.cls('open')}
        side={side}
        label={symbol}
        leverage={effLeverage}
      />

      <p className="hint">
        {t(isCross ? 'open.hintCross' : 'open.hintIsolated')}
        {openLiqPrice ? t('open.hintLiq', { liq: liqText }) : ''}
      </p>
    </div>
  );
}
