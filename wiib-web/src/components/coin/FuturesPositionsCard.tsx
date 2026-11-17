import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Layers, Loader2, RefreshCw } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useCryptoStream } from '../../hooks/useCryptoStream';
import { useCrossAccount } from '../../hooks/useCrossAccount';
import { useSubmitFx } from '../../hooks/useSubmitFx';
import { useToast } from '../ui/use-toast';
import { Seg } from '../ui/seg';
import { HelpTip } from '../HelpTip';
import { EmptyState } from '../EmptyState';
import { LeverageSlider } from '../LeverageSlider';
import { SureButton } from '../SureButton';
import { cn, fmtNum, fmtSignedPct, fmtSignedUsd, spinIcon } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import { useTradeFilter } from '../../lib/tradeFilters';
import type { FuturesPosition, FuturesBracket } from '../../types';
import { SLTPEditor } from './SLTPEditor';
import { NumInput, PctRow } from './TradeFields';
import { FUTURES_LEVERAGE_OPTIONS, POSITION_PCTS, findFuturesBracket, formatRate, qtyByPct, type SLTPRow } from './futuresMath';
import './FuturesPositionsCard.css';

type PosActionType = 'close' | 'margin' | 'reduceMargin' | 'stoploss' | 'leverage';
/** 面板里的提交钮：转圈、打勾、晃一下都只落在点的那一颗上 */
type SubmitKey = 'close' | 'margin' | 'reduceMargin' | 'leverage' | 'saveSltp' | 'clearSltp';
type Level = { price: number; quantity: number };

/** 编辑行 → 要提交的档位：价格或数量没填的行不算 */
const toLevels = (rows: SLTPRow[]): Level[] => rows
  .filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
  .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));

/** 表单里的档位和仓位上已存的是不是同一组（没设过后端给的可能是空数组，也可能不带这个字段） */
const sameLevels = (a: Level[], saved?: Level[] | null) => {
  const b = saved ?? [];
  return a.length === b.length && a.every((x, i) => x.price === b[i].price && x.quantity === b[i].quantity);
};

// 档位表运行期不变，模块级缓存：Coin/Portfolio 多处挂载全站只打一次接口
let bracketsCache: Record<string, FuturesBracket[]> | null = null;
let bracketsInflight: Promise<Record<string, FuturesBracket[]>> | null = null;
function loadBrackets(): Promise<Record<string, FuturesBracket[]>> {
  if (bracketsCache) return Promise.resolve(bracketsCache);
  bracketsInflight ??= futuresApi.brackets()
    .then(b => { bracketsCache = b; return b; })
    .catch(e => { bracketsInflight = null; throw e; });
  return bracketsInflight;
}

/**
 * 合约持仓整节（样式见 FuturesPositionsCard.css）：symbol 传入=只显示该币种（Coin页，双向持仓最多 多+空 两张），不传=全部持仓（Portfolio汇总）。
 * 每张仓位卡自订该币 futures WS 流（共享 STOMP 连接）实时算盈亏；
 * showCloseAll 开启头部"一键全平"（两段确认，市价全平所有仓位）。
 * 加仓无独立入口（对齐Binance）：同方向再下单即自动并入，走开仓面板。
 */
export function FuturesPositionsCard({ symbol, refreshKey, onOrdersChanged, onPositionsChanged, onPositions, showCloseAll }: {
  symbol?: string;
  refreshKey: number;
  onOrdersChanged?: () => void;
  /** 仓位有任何变动（平仓/调杠杆/保证金）时回调；Coin 页用它驱动开仓面板的持仓快照重拉 */
  onPositionsChanged?: () => void;
  /** 每次拉到仓位列表原样上抛；Coin 页拿它喂 K 线的仓位参考线 */
  onPositions?: (list: FuturesPosition[]) => void;
  showCloseAll?: boolean;
}) {
  const { t } = useTranslation('trade');
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);

  const [positions, setPositions] = useState<FuturesPosition[]>([]);
  // 头一次拉回来之前不出空状态，免得先闪一下"没有持仓"
  const [loaded, setLoaded] = useState(false);
  const [bracketsMap, setBracketsMap] = useState<Record<string, FuturesBracket[]>>({});
  const [closingAll, setClosingAll] = useState(false);

  useEffect(() => { loadBrackets().then(setBracketsMap).catch(() => { /* 杠杆上限降级显示 */ }); }, []);

  const fetchPositions = useCallback(async () => {
    try {
      const list = await futuresApi.positions(symbol);
      setPositions(list);
      onPositions?.(list);
    } catch (e) {
      console.error('查询合约仓位失败', e);
      setPositions([]);
      onPositions?.([]);
    } finally {
      setLoaded(true);
    }
  }, [symbol, onPositions]);

  useEffect(() => { fetchPositions(); }, [fetchPositions, refreshKey]);

  // 子块操作成功后的统一收口：刷仓位+用户+通知外部（开仓面板要跟随仓位快照）；成交类操作再通知订单表
  const handleMutated = useCallback((ordersChanged: boolean) => {
    fetchPositions();
    fetchUser();
    onPositionsChanged?.();
    if (ordersChanged) onOrdersChanged?.();
  }, [fetchPositions, fetchUser, onPositionsChanged, onOrdersChanged]);

  const handleCloseAll = async () => {
    setClosingAll(true);
    try {
      const res = await futuresApi.closeAll();
      if (res.failures.length > 0) {
        toast(t('toast.closedAllPartial', { closed: res.closedCount, failed: res.failures.length }), 'error', { description: res.failures.join(t('pos.failureSep')) });
      } else {
        toast(t('toast.closedAll', { n: res.closedCount }), 'success');
      }
      handleMutated(true);
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.closeAllFailed'), 'error');
    } finally {
      setClosingAll(false);
    }
  };

  // 副标题：Coin 页带币种前缀，Portfolio 页只有"几个 · 保证金模式"（跨币可能两种模式都有，一起列）
  const modeText = [...new Set(positions.map(p => p.marginMode))]
    .map(m => t(m === 'CROSS' ? 'marginMode.cross' : 'marginMode.isolated'))
    .join(' / ');
  const subtitle = [symbol, positions.length > 0 && t('pos.count', { n: positions.length }), positions.length > 0 && modeText]
    .filter(Boolean).join(' · ');

  return (
    <section className="sec">
      <div className="sec-h">
        <h2>
          {t('pos.titleAll')}
          {subtitle && <small>{subtitle}</small>}
          <HelpTip text={`${t(symbol ? 'pos.helpSymbol' : 'pos.helpAll')}\n${t('pos.helpRealized')}`} />
        </h2>
        <span className="r">
          <button type="button" className="ib" onClick={e => { spinIcon(e); void fetchPositions(); }} aria-label={t('pos.refresh')} title={t('pos.refresh')}>
            <RefreshCw className="ic" />
          </button>
          {showCloseAll && positions.length > 0 && (
            <SureButton className="linkb" sureClassName="sure" disabled={closingAll}
                        sureLabel={t('pos.closeAllConfirm')} onConfirm={() => void handleCloseAll()}>
              {closingAll ? <Loader2 className="ic animate-spin" /> : t('pos.closeAll')}
            </SureButton>
          )}
        </span>
      </div>
      {loaded && (positions.length > 0 ? (
        <div className="posg">
          {positions.map(pos => (
            <PositionItem
              key={pos.id}
              pos={pos}
              brackets={bracketsMap[pos.symbol]}
              onCoinPage={!!symbol}
              onMutated={handleMutated}
            />
          ))}
        </div>
      ) : (
        <EmptyState icon={<Layers />} text={t('pos.empty')} />
      ))}
    </section>
  );
}

/**
 * 单张仓位卡：自订该币 futures 流，标记价实时衍生盈亏（纯 render 期计算，不回写 state）；
 * 操作面板点按钮展开在按钮排下面，状态全部内聚于本卡。
 */
function PositionItem({ pos, brackets, onCoinPage, onMutated }: {
  pos: FuturesPosition;
  brackets?: FuturesBracket[];
  /** Coin 页最后一颗按钮是反手，Portfolio 页是去交易 */
  onCoinPage: boolean;
  onMutated: (ordersChanged: boolean) => void;
}) {
  const { t } = useTranslation(['trade', 'common']);
  const cfg = getCoin(pos.symbol);
  // 平仓/SLTP 数量步长用合约过滤器（reduce-only 免最小名义额；全量平仓后端豁免步长，存量尘埃仓能平干净）
  const MIN_QTY = useTradeFilter('futures', pos.symbol).stepSize;
  const PRICE_STEP = getCoinPriceStep(pos.symbol);
  const PRICE_STEP_TEXT = PRICE_STEP.toFixed(getCoinPriceDecimals(pos.symbol));
  const fmtPrice = useCallback((n?: number | null) => formatCoinPrice(pos.symbol, n), [pos.symbol]);

  const { toast } = useToast();
  const navigate = useNavigate();

  // WS 实时价：mp 驱动盈亏，fp（最新价）用于限价单提示与 SLTP 编辑；断流时退回后端快照值
  const tick = useCryptoStream(pos.symbol, 'futures');
  const mp = tick?.mp ?? pos.markPrice;
  const livePrice = tick?.price ?? pos.currentPrice;
  const unrealizedPnl = pos.side === 'LONG'
    ? (mp - pos.entryPrice) * pos.quantity
    : (pos.entryPrice - mp) * pos.quantity;
  const unrealizedPnlPct = pos.margin > 0 ? (unrealizedPnl / pos.margin) * 100 : 0;

  // panel 是面板里摆的哪一种，open 是开没开：收起时内容留着，折叠动画才有东西可收
  const [panel, setPanel] = useState<PosActionType>('close');
  const [open, setOpen] = useState(false);
  const fx = useSubmitFx<SubmitKey>();
  const [closeOrderType, setCloseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [closeLimitPrice, setCloseLimitPrice] = useState('');
  const [closeQty, setCloseQty] = useState('');
  const [marginAmt, setMarginAmt] = useState('');
  const [newLeverage, setNewLeverage] = useState<number | null>(null);
  const [slRows, setSlRows] = useState<SLTPRow[]>([]);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([]);
  const [reversing, setReversing] = useState(false);
  const [confirmReverse, setConfirmReverse] = useState(false);

  // 反手两段确认，3s 未二次点击自动还原。确认态在每张卡里各自一份，不是全局共享
  useEffect(() => {
    if (!confirmReverse) return;
    const t = window.setTimeout(() => setConfirmReverse(false), 3000);
    return () => window.clearTimeout(t);
  }, [confirmReverse]);

  const isPnlUp = unrealizedPnl >= 0;
  const isLong = pos.side === 'LONG';
  const isCrossPos = pos.marginMode === 'CROSS';
  // 追加保证金能划的钱 = min(全仓可用, 余额)：后端先过全仓可用这道闸再扣余额。只在展开加保证金时拉快照
  const { spendable } = useCrossAccount(open && panel === 'margin');

  // 操作切换：展开时重置各输入，止盈损带入已有档位；平仓默认市价全平（100%）
  const toggleAction = (type: PosActionType) => {
    if (open && panel === type) {
      setOpen(false);
      return;
    }
    setPanel(type);
    setOpen(true);
    setCloseQty(type === 'close' ? String(pos.quantity) : ''); setCloseLimitPrice(''); setCloseOrderType('MARKET');
    setMarginAmt('');
    setNewLeverage(null);
    // 没设过档位时预填满仓(100%)：设止损/盈最常见的就是整仓保护，让想分批的人往下调，
    // 而不是每个人都从 0% 拖起。判 length 而不是 ?? —— 后端返的是空数组不是 null，
    // 用 ?? 兜不住，编辑器会一行都不渲染只剩个"添加"
    const fullRow = { price: '', quantity: String(pos.quantity) };
    setSlRows(pos.stopLosses?.length ? pos.stopLosses.map(s => ({ price: String(s.price), quantity: String(s.quantity) })) : [fullRow]);
    setTpRows(pos.takeProfits?.length ? pos.takeProfits.map(t => ({ price: String(t.price), quantity: String(t.quantity) })) : [fullRow]);
  };

  // 通用提交包装：成功向父上报，按钮打完勾再收起面板。成败两条文案分别传
  const submit = async (key: SubmitKey, fn: () => Promise<unknown>, okKey: string, failKey: string, ordersChanged: boolean) => {
    const done = await fx.run(key, async () => {
      await fn();
      toast(t(okKey), 'success');
      onMutated(ordersChanged);
    }, e => toast((e as Error).message || t(failKey), 'error'));
    if (done) setOpen(false);
  };
  /** 没过本地校验：说原因，那颗钮晃一下 */
  const reject = (key: SubmitKey, msg: string) => { toast(msg, 'error'); fx.nope(key); };

  const handleClose = () => {
    const qty = parseFloat(closeQty);
    if (!qty || qty <= 0) return reject('close', t('toast.enterCloseQty'));
    if (closeOrderType === 'LIMIT') {
      const lp = parseFloat(closeLimitPrice);
      if (!lp || lp <= 0) return reject('close', t('toast.invalidLimit'));
    }
    void submit('close', () => futuresApi.close({
      positionId: pos.id, quantity: qty, orderType: closeOrderType,
      ...(closeOrderType === 'LIMIT' ? { limitPrice: parseFloat(closeLimitPrice) } : {}),
    }), 'toast.closeOk', 'toast.closeFailed', true);
  };

  /**
   * 反手：市价全平 + 立刻反向开等量新仓。不走 submit——两步的结果要分别报，
   * 尤其是"平了但没开成"这种半成功：那会儿原仓已经没了，不明说用户会以为什么都没发生。
   */
  const handleReverse = async () => {
    setConfirmReverse(false);
    setReversing(true);
    try {
      const res = await futuresApi.reverse(pos.id);
      const pnl = res.closed.realizedPnl ?? 0;
      const pnlText = `${pnl >= 0 ? '+' : ''}${fmtNum(pnl)} USDT`;
      const closedText = t('toast.reverseClosed', {
        dir: t(isLong ? 'sideShort.long' : 'sideShort.short'),
        qty: res.closed.quantity, unit: cfg.name, pnl: pnlText,
      });
      if (res.opened) {
        toast(t(isLong ? 'toast.reversedToShort' : 'toast.reversedToLong'), 'success', {
          description: t('toast.reverseOkDesc', {
            closed: closedText, qty: res.opened.quantity, unit: cfg.name, lev: pos.leverage,
          }),
        });
      } else {
        toast(t('toast.reverseHalf'), 'error', {
          duration: 8000,
          description: t('toast.reverseFailDesc', {
            closed: closedText,
            dir: t(isLong ? 'sideShort.short' : 'sideShort.long'),
            reason: res.openError ?? t('toast.unknownReason'),
          }),
        });
      }
      onMutated(true);
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.reverseFailed'), 'error');
    } finally {
      setReversing(false);
    }
  };

  const handleAddMargin = () => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) return reject('margin', t('toast.enterAmount'));
    void submit('margin', () => futuresApi.addMargin({ positionId: pos.id, amount: amt }), 'toast.addMarginOk', 'toast.addMarginFailed', false);
  };

  const handleReduceMargin = () => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) return reject('reduceMargin', t('toast.enterAmount'));
    void submit('reduceMargin', () => futuresApi.reduceMargin({ positionId: pos.id, amount: amt }), 'toast.reduceMarginOk', 'toast.reduceMarginFailed', false);
  };

  // 币种级调杠杆：多空共用，同时作用于该币全部仓位；全仓双向可调（调低要可用够），逐仓只能调高；错误信息由后端 toast 透出
  const handleAdjustLeverage = () => {
    if (!newLeverage) return reject('leverage', t('toast.selectLev'));
    void submit('leverage', () => futuresApi.adjustLeverage({ symbol: pos.symbol, leverage: newLeverage }), 'toast.levOk', 'toast.levFailed', false);
  };

  // 止损、止盈表单里现在是什么，和仓位上已存的比一比：哪边动过才提交哪边
  const slLevels = toLevels(slRows);
  const tpLevels = toLevels(tpRows);
  const slChanged = !sameLevels(slLevels, pos.stopLosses);
  const tpChanged = !sameLevels(tpLevels, pos.takeProfits);
  const hasSltp = (pos.stopLosses?.length ?? 0) + (pos.takeProfits?.length ?? 0) > 0;

  /**
   * 止损、止盈一起提交：sl / tp 传 null 是那一边不动，传空列表是清掉（后端清库并撤触发索引）。
   * 后端是两个接口，先止损后止盈：止盈没存上时止损那边已经生效了，要单独说清楚
   */
  const submitSltp = async (key: 'saveSltp' | 'clearSltp', sl: Level[] | null, tp: Level[] | null, okKey: string, failKey: string) => {
    let slDone = false;
    const done = await fx.run(key, async () => {
      if (sl) {
        await futuresApi.setStopLoss({ positionId: pos.id, stopLosses: sl });
        slDone = true;
      }
      if (tp) await futuresApi.setTakeProfit({ positionId: pos.id, takeProfits: tp });
      toast(t(okKey), 'success');
      onMutated(false);
    }, e => {
      const reason = (e as Error).message;
      if (!slDone) { toast(reason || t(failKey), 'error'); return; }
      toast(t('toast.sltpHalf'), 'error', { description: reason });
      onMutated(false);
    });
    if (done) setOpen(false);
  };

  const handleSaveSltp = () => {
    void submitSltp('saveSltp', slChanged ? slLevels : null, tpChanged ? tpLevels : null, 'toast.sltpSaved', 'toast.sltpSaveFailed');
  };

  const handleClearSltp = () => {
    void submitSltp('clearSltp', pos.stopLosses?.length ? [] : null, pos.takeProfits?.length ? [] : null, 'toast.sltpCleared', 'toast.sltpClearFailed');
  };

  /** 止损/止盈全档一行列完，多档用 " / " 连；某档不是满仓平就在价格后括个数量 */
  const levelsText = (items?: { price: number; quantity: number }[]) => {
    if (!items?.length) return '—';
    return items
      .map(it => fmtPrice(it.price) + (Math.abs(it.quantity - pos.quantity) < 1e-9 ? '' : ` (${it.quantity})`))
      .join(' / ');
  };
  // 维持保证金率的档位按当前仓位名义价值查
  const currentBracket = findFuturesBracket(brackets, mp * pos.quantity);
  const realizedPnl = pos.realizedPnl ?? 0;

  const closePct = pos.quantity > 0 ? (parseFloat(closeQty) || 0) / pos.quantity : 0;
  const closeActivePct = POSITION_PCTS.find(p => Math.abs(p - closePct) < 1e-9) ?? null;
  const closeLimitNum = parseFloat(closeLimitPrice);
  const closeLimitWarn = closeLimitNum > 0 && livePrice > 0 && (isLong ? closeLimitNum <= livePrice : closeLimitNum >= livePrice);
  /** 操作一排的按钮：开着的那一项填墨；平仓本来就是墨色 */
  const actBtn = (type: PosActionType) => cn('btn', (type === 'close' || (open && panel === type)) && 'ink');
  /**
   * 面板里的提交钮：转圈、打勾、晃一下只落在点的那一颗上，它忙的时候其余的禁掉。
   * off 是这颗钮平时按不了的条件（没改动之类）；它自己在转圈、打勾时不压灰
   */
  const doBtn = (key: SubmitKey, base = 'btn ink wide', off = false) => ({
    className: cn(base, fx.cls(key)),
    disabled: fx.cls(key) == null && (fx.locked || off),
  });

  // 调杠杆：逐仓调低要退保证金但亏损已吃进仓位，后端不支持，滑杆下限=当前杠杆
  const maxLev = brackets?.[0]?.maxLeverage ?? FUTURES_LEVERAGE_OPTIONS[FUTURES_LEVERAGE_OPTIONS.length - 1];
  const minLev = isCrossPos ? 1 : pos.leverage;
  const selLev = newLeverage ?? pos.leverage;
  const levChanged = selLev !== pos.leverage;
  // 档位标签：常规档过滤到 [min,max]，逐仓再补当前杠杆作为起点标签
  const levTicks = [...new Set([minLev, ...FUTURES_LEVERAGE_OPTIONS.filter(lv => lv >= minLev && lv <= maxLev)])].sort((a, b) => a - b);

  return (
    <article className="pos blk">
      <div className="h">
        <b>{pos.symbol}</b>
        <span className="cs">
          <span className={cn('tag', isLong ? 'g' : 'r')}>{t(isLong ? 'sideShort.long' : 'sideShort.short')} {pos.leverage}x</span>
          <span className="tag">{t(isCrossPos ? 'marginMode.cross' : 'marginMode.isolated')}</span>
        </span>
      </div>
      <div className="pnl">
        <b className={cn('nf', isPnlUp ? 'up' : 'dn')}>{fmtSignedUsd(unrealizedPnl)}</b>
        <span className={isPnlUp ? 'up' : 'dn'}>{fmtSignedPct(unrealizedPnlPct)}</span>
        <em>{t('pos.marginShort', { amount: fmtNum(pos.margin) })}</em>
      </div>
      <div className="m">
        <div><i>{t('metric.qty')}</i>{pos.quantity} {cfg.name}</div>
        <div><i>{t('metric.entry')}</i>{fmtPrice(pos.entryPrice)}</div>
        <div><i>{t('metric.mark')}</i>{fmtPrice(mp)}</div>
        <div><i>{t('metric.liq')}</i><span className="wn">{pos.liquidationPrice > 0 ? fmtPrice(pos.liquidationPrice) : '—'}</span></div>
        <div><i>{t('sltp.sl')}</i><span className={pos.stopLosses?.length ? 'dn' : 'mute'}>{levelsText(pos.stopLosses)}</span></div>
        <div><i>{t('sltp.tp')}</i><span className={pos.takeProfits?.length ? 'up' : 'mute'}>{levelsText(pos.takeProfits)}</span></div>
        <div>
          <i>{t('metric.funding')}</i>
          <span className="fund"><em>{t('metric.fundingPer')}</em>{fmtNum(pos.fundingFeePerCycle)}</span>
          <span className="fund"><em>{t('metric.fundingSum')}</em>{fmtNum(pos.fundingFeeTotal)}</span>
        </div>
        <div><i>{t('metric.mmr')}</i>{currentBracket ? t('metric.tier', { tier: currentBracket.tier, rate: formatRate(currentBracket.mmr) }) : '—'}</div>
        <div><i>{t('metric.realizedPnl')}</i><span className={realizedPnl >= 0 ? 'up' : 'dn'}>{realizedPnl >= 0 ? '+' : ''}{fmtNum(realizedPnl)}</span></div>
      </div>
      {/* 加仓无独立入口（对齐Binance）：同方向再下一单即自动并入仓位，走开仓面板 */}
      <div className="acts">
        <button type="button" className={actBtn('close')} onClick={() => toggleAction('close')}>{t('pos.close')}</button>
        <button type="button" className={actBtn('leverage')} onClick={() => toggleAction('leverage')}>{t('pos.leverage')}</button>
        <button type="button" className={actBtn('stoploss')} onClick={() => toggleAction('stoploss')}>{t('pos.sltp')}</button>
        {/* 全仓保证金按账户统一算，单仓加减保证金没意义，后端也会拒（1761），直接不给入口 */}
        {!isCrossPos && <button type="button" className={actBtn('margin')} onClick={() => toggleAction('margin')}>{t('pos.addMargin')}</button>}
        {!isCrossPos && <button type="button" className={actBtn('reduceMargin')} onClick={() => toggleAction('reduceMargin')}>{t('pos.reduceMargin')}</button>}
        {onCoinPage ? (
          <button
            type="button"
            className={cn('btn', confirmReverse && 'danger', reversing && 'busy')}
            title={t('pos.reverseHelp')}
            onClick={() => {
              if (reversing) return;
              if (confirmReverse) void handleReverse();
              else setConfirmReverse(true);
            }}
          >
            {confirmReverse ? t('pos.reverseConfirm') : t('pos.reverse')}
          </button>
        ) : (
          <button type="button" className="btn" onClick={() => navigate(`/coin/${pos.symbol}`)}>{t('pos.goTrade')}</button>
        )}
      </div>

      <div className={cn('col', open && 'open')}>
        <div>
          {/* 换一种操作就重挂，里面的内容从下面浮上来 */}
          <div className="pin" key={panel}>
            {/* 平仓 */}
            {panel === 'close' && (
              <>
                <div className="fld">
                  <div className="fl">{t('orderType.label')}</div>
                  <Seg className="full" value={closeOrderType} onChange={setCloseOrderType} label={t('orderType.label')} options={[
                    { value: 'MARKET', label: t('orderType.market') },
                    { value: 'LIMIT', label: t('orderType.limit') },
                  ]} />
                </div>
                <div className={cn('col', closeOrderType === 'LIMIT' && 'open')}>
                  <div>
                    <div className="fld">
                      <div className="fl">{t('open.limitLabel')}</div>
                      <NumInput className="sm" value={closeLimitPrice} onChange={setCloseLimitPrice} placeholder={t('open.limitPlaceholder')} step={PRICE_STEP_TEXT} min="0" unit="USDT" />
                      {closeLimitWarn && <div className="fhint text-warning">{t(isLong ? 'open.limitFillsLte' : 'open.limitFillsGte')}</div>}
                    </div>
                  </div>
                </div>
                <div className="fld">
                  <div className="fl">
                    <span>{t('pos.closeQty')}</span>
                    <span>/ {pos.quantity} {cfg.name}</span>
                  </div>
                  <NumInput className="sm" value={closeQty} onChange={setCloseQty} step={String(MIN_QTY)} min={MIN_QTY} max={pos.quantity} unit={cfg.name} />
                  <PctRow active={closeActivePct} onPick={p => setCloseQty(qtyByPct(pos.quantity, p * 100, MIN_QTY))} />
                </div>
                <button type="button" {...doBtn('close')} onClick={handleClose}>{t('pos.confirmClose')}</button>
              </>
            )}
            {/* 加保证金 */}
            {panel === 'margin' && (
              <>
                <div className="fld">
                  <div className="fl">
                    <span>{t('pos.addAmount')}</span>
                    {spendable != null && <span>{t('pos.availableBalance', { amount: fmtNum(spendable) })}</span>}
                  </div>
                  <NumInput className="sm" value={marginAmt} onChange={setMarginAmt} step="0.01" min="0" unit="USDT" />
                </div>
                <button type="button" {...doBtn('margin')} onClick={handleAddMargin}>{t('pos.confirmAdd')}</button>
              </>
            )}
            {/* 减保证金 */}
            {panel === 'reduceMargin' && (
              <>
                <div className="fld">
                  <div className="fl">
                    <span>{t('pos.reduceAmount')}</span>
                    <span>{t('pos.currentMargin', { amount: fmtNum(pos.margin) })}</span>
                  </div>
                  <NumInput className="sm" value={marginAmt} onChange={setMarginAmt} step="0.01" min="0" max={pos.margin} unit="USDT" />
                </div>
                <button type="button" {...doBtn('reduceMargin')} onClick={handleReduceMargin}>{t('pos.confirmReduce')}</button>
              </>
            )}
            {/* 调杠杆 */}
            {panel === 'leverage' && (
              <>
                <div className={cn('lev', levChanged && 'pending')}>
                  <div className="fl">{t('lev.label')}<b className="v">{selLev}x</b></div>
                  <LeverageSlider value={selLev} min={minLev} max={maxLev} ticks={levTicks} onChange={setNewLeverage} />
                </div>
                <button type="button" {...doBtn('leverage', 'btn ink wide', !levChanged)} onClick={handleAdjustLeverage}>
                  {t('lev.apply', { lev: selLev })}
                </button>
              </>
            )}
            {/* 止损 / 止盈 */}
            {panel === 'stoploss' && (
              <>
                <div className="fld">
                  <div className="fl"><span>{t('sltp.sl')}<HelpTip text={t('sltp.posHelp')} /></span></div>
                  <SLTPEditor rows={slRows} onChange={setSlRows} kind="SL" posQty={pos.quantity} minQty={MIN_QTY}
                    entryPrice={pos.entryPrice} margin={pos.margin} side={pos.side} unit={cfg.name}
                    minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                </div>
                <div className="fld">
                  <div className="fl"><span>{t('sltp.tp')}<HelpTip text={t('sltp.posHelp')} /></span></div>
                  <SLTPEditor rows={tpRows} onChange={setTpRows} kind="TP" posQty={pos.quantity} minQty={MIN_QTY}
                    entryPrice={pos.entryPrice} margin={pos.margin} side={pos.side} unit={cfg.name}
                    minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                </div>
                {/* 止损止盈一颗钮一起存，没改动时按不了；已经设过的可以一下全清掉 */}
                <div className="sltp-act">
                  <button type="button" {...doBtn('saveSltp', 'btn ink', !slChanged && !tpChanged)} onClick={handleSaveSltp}>{t('sltp.save')}</button>
                  {hasSltp && (
                    <button type="button" {...doBtn('clearSltp', 'btn')} onClick={handleClearSltp}>{t('sltp.clearAll')}</button>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </article>
  );
}
