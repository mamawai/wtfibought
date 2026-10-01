import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Loader2, RefreshCw } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useCryptoStream } from '../../hooks/useCryptoStream';
import { useCrossAccount } from '../../hooks/useCrossAccount';
import { useStagger } from '../../hooks/useStagger';
import { useToast } from '../ui/use-toast';
import { HelpTip } from '../HelpTip';
import { LeverageSlider } from '../LeverageSlider';
import { cn, fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import { useTradeFilter } from '../../lib/tradeFilters';
import type { FuturesPosition, FuturesBracket } from '../../types';
import { SLTPEditor } from './SLTPEditor';
import { NumInput, PctRow } from './TradeFields';
import { FUTURES_LEVERAGE_OPTIONS, POSITION_PCTS, findFuturesBracket, formatRate, qtyByPct, type SLTPRow } from './futuresMath';

type PosActionType = 'close' | 'margin' | 'reduceMargin' | 'stoploss' | 'leverage';

/** .m 格里的次级小标签（资金费的"每期/累计"），比值小一号、灰 */
const SUBLABEL = 'not-italic text-[11.5px] font-medium text-muted-foreground mr-1 [font-stretch:100%]';

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
 * 合约持仓整节：symbol 传入=只显示该币种（Coin页，双向持仓最多 多+空 两张），不传=全部持仓（Portfolio汇总）。
 * 每张仓位块自订该币 futures WS 流（共享 STOMP 连接）实时算盈亏，不再依赖页面传 markPrice；
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
  const gridRef = useStagger<HTMLDivElement>();

  const [positions, setPositions] = useState<FuturesPosition[]>([]);
  const [bracketsMap, setBracketsMap] = useState<Record<string, FuturesBracket[]>>({});
  const [loading, setLoading] = useState(false);
  const [closingAll, setClosingAll] = useState(false);
  const [confirmCloseAll, setConfirmCloseAll] = useState(false);

  useEffect(() => { loadBrackets().then(setBracketsMap).catch(() => { /* 杠杆上限降级显示 */ }); }, []);

  const fetchPositions = useCallback(async () => {
    setLoading(true);
    try {
      const list = await futuresApi.positions(symbol);
      setPositions(list);
      onPositions?.(list);
    } catch (e) {
      console.error('查询合约仓位失败', e);
      setPositions([]);
      onPositions?.([]);
    } finally {
      setLoading(false);
    }
  }, [symbol, onPositions]);

  useEffect(() => { fetchPositions(); }, [fetchPositions, refreshKey]);

  // 一键全平两段确认，3s 未二次点击自动还原
  useEffect(() => {
    if (!confirmCloseAll) return;
    const t = window.setTimeout(() => setConfirmCloseAll(false), 3000);
    return () => window.clearTimeout(t);
  }, [confirmCloseAll]);

  // 子块操作成功后的统一收口：刷仓位+用户+通知外部（开仓面板要跟随仓位快照）；成交类操作再通知订单表
  const handleMutated = useCallback((ordersChanged: boolean) => {
    fetchPositions();
    fetchUser();
    onPositionsChanged?.();
    if (ordersChanged) onOrdersChanged?.();
  }, [fetchPositions, fetchUser, onPositionsChanged, onOrdersChanged]);

  const handleCloseAll = async () => {
    setConfirmCloseAll(false);
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

  if (positions.length === 0) return null;

  // 副标题：Coin 页带币种前缀，Portfolio 页只有"几个 · 保证金模式"（跨币可能两种模式都有，一起列）
  const modeText = [...new Set(positions.map(p => p.marginMode))]
    .map(m => t(m === 'CROSS' ? 'marginMode.cross' : 'marginMode.isolated'))
    .join(' / ');

  return (
    <section className="sec">
      <div className="sec-h">
        <h2>
          {t('pos.titleAll')}
          <small>{symbol ? `${symbol} · ` : ''}{t('pos.count', { n: positions.length })} · {modeText}</small>
          <HelpTip text={`${t(symbol ? 'pos.helpSymbol' : 'pos.helpAll')}\n${t('pos.helpRealized')}`} />
        </h2>
        <span className="inline-flex items-center gap-3">
          <button type="button" className="ibtn" disabled={loading} onClick={fetchPositions} aria-label={t('pos.refresh')} title={t('pos.refresh')}>
            <RefreshCw className={cn('w-[15px] h-[15px]', loading && 'animate-spin')} />
          </button>
          {showCloseAll && (
            <button
              type="button"
              disabled={closingAll}
              onClick={() => confirmCloseAll ? handleCloseAll() : setConfirmCloseAll(true)}
              className={cn('text-[13px] underline underline-offset-[3px] cursor-pointer', confirmCloseAll && 'dn font-bold')}
            >
              {closingAll ? <Loader2 className="w-3 h-3 animate-spin" /> : confirmCloseAll ? t('pos.closeAllConfirm') : t('pos.closeAll')}
            </button>
          )}
        </span>
      </div>
      <div ref={gridRef} className="pos-grid">
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
    </section>
  );
}

/**
 * 单个仓位块：自订该币 futures 流，标记价实时衍生盈亏（纯 render 期计算，不回写 state）；
 * 操作面板点按钮展开在按钮排下面，状态全部内聚于本块。
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

  const [action, setAction] = useState<PosActionType | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [closeOrderType, setCloseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [closeLimitPrice, setCloseLimitPrice] = useState('');
  const [closeQty, setCloseQty] = useState('');
  const [marginAmt, setMarginAmt] = useState('');
  const [newLeverage, setNewLeverage] = useState<number | null>(null);
  const [slRows, setSlRows] = useState<SLTPRow[]>([]);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([]);
  const [reversing, setReversing] = useState(false);
  const [confirmReverse, setConfirmReverse] = useState(false);

  // 反手两段确认，3s 未二次点击自动还原。确认态在每个块里各自一份，不是全局共享
  useEffect(() => {
    if (!confirmReverse) return;
    const t = window.setTimeout(() => setConfirmReverse(false), 3000);
    return () => window.clearTimeout(t);
  }, [confirmReverse]);

  const isPnlUp = unrealizedPnl >= 0;
  const isLong = pos.side === 'LONG';
  const isCrossPos = pos.marginMode === 'CROSS';
  // 追加保证金能划的钱 = min(全仓可用, 余额)：后端先过全仓可用这道闸再扣余额。只在展开加保证金时拉快照
  const { spendable } = useCrossAccount(action === 'margin');

  // 操作切换：展开时重置各输入，止盈损带入已有档位；平仓默认市价全平（100%）
  const toggleAction = (type: PosActionType) => {
    if (action === type) {
      setAction(null);
      return;
    }
    setAction(type);
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

  // 通用提交包装：成功关面板并向父上报。成败两条文案分别传——原先靠裁"成功"两字拼失败句，换语言就拼不出来了
  const submit = async (fn: () => Promise<unknown>, okKey: string, failKey: string, ordersChanged: boolean) => {
    setSubmitting(true);
    try {
      await fn();
      toast(t(okKey), 'success');
      setAction(null);
      onMutated(ordersChanged);
    } catch (e: unknown) {
      toast((e as Error).message || t(failKey), 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleClose = () => {
    const qty = parseFloat(closeQty);
    if (!qty || qty <= 0) { toast(t('toast.enterCloseQty'), 'error'); return; }
    if (closeOrderType === 'LIMIT') {
      const lp = parseFloat(closeLimitPrice);
      if (!lp || lp <= 0) { toast(t('toast.invalidLimit'), 'error'); return; }
    }
    void submit(() => futuresApi.close({
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
    if (!amt || amt <= 0) { toast(t('toast.enterAmount'), 'error'); return; }
    void submit(() => futuresApi.addMargin({ positionId: pos.id, amount: amt }), 'toast.addMarginOk', 'toast.addMarginFailed', false);
  };

  const handleReduceMargin = () => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) { toast(t('toast.enterAmount'), 'error'); return; }
    void submit(() => futuresApi.reduceMargin({ positionId: pos.id, amount: amt }), 'toast.reduceMarginOk', 'toast.reduceMarginFailed', false);
  };

  // 币种级调杠杆：多空共用，同时作用于该币全部仓位；全仓双向可调（调低要可用够），逐仓只能调高；错误信息由后端 toast 透出
  const handleAdjustLeverage = () => {
    if (!newLeverage) { toast(t('toast.selectLev'), 'error'); return; }
    void submit(() => futuresApi.adjustLeverage({ symbol: pos.symbol, leverage: newLeverage }), 'toast.levOk', 'toast.levFailed', false);
  };

  const handleSetStopLoss = () => {
    const items = slRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    void submit(() => futuresApi.setStopLoss({ positionId: pos.id, stopLosses: items }), 'toast.slSaved', 'toast.slSaveFailed', false);
  };

  const handleSetTakeProfit = () => {
    const items = tpRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    void submit(() => futuresApi.setTakeProfit({ positionId: pos.id, takeProfits: items }), 'toast.tpSaved', 'toast.tpSaveFailed', false);
  };

  // 取消=提交空档位列表，后端清库并撤触发索引
  const handleCancelStopLoss = () => {
    void submit(() => futuresApi.setStopLoss({ positionId: pos.id, stopLosses: [] }), 'toast.slCleared', 'toast.slClearFailed', false);
  };

  const handleCancelTakeProfit = () => {
    void submit(() => futuresApi.setTakeProfit({ positionId: pos.id, takeProfits: [] }), 'toast.tpCleared', 'toast.tpClearFailed', false);
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
  const actBtn = (type: PosActionType) => cn('btn xs', (type === 'close' || action === type) && 'fill');

  return (
    <div className={cn('pos', isLong ? 'long' : 'short')}>
      <div className="h">
        <b>{pos.symbol}</b>
        <span className="chips">
          <span className={cn('chip fill', isLong ? 'up' : 'dn')}>{t(isLong ? 'sideShort.long' : 'sideShort.short')} {pos.leverage}x</span>
          <span className="chip mute">{t(isCrossPos ? 'marginMode.cross' : 'marginMode.isolated')}</span>
        </span>
      </div>
      <div className="pnl num">
        <b className={isPnlUp ? 'up' : 'dn'}>{isPnlUp ? '+' : '-'}${fmtNum(Math.abs(unrealizedPnl))}</b>
        <span className={isPnlUp ? 'up' : 'dn'}>{isPnlUp ? '+' : ''}{unrealizedPnlPct.toFixed(2)}%</span>
        <em>{t('pos.marginShort', { amount: fmtNum(pos.margin) })}</em>
      </div>
      <div className="m num">
        <div><i>{t('metric.qty')}</i>{pos.quantity} {cfg.name}</div>
        <div><i>{t('metric.entry')}</i>{fmtPrice(pos.entryPrice)}</div>
        <div><i>{t('metric.mark')}</i>{fmtPrice(mp)}</div>
        <div><i>{t('metric.liq')}</i><span className="wn">{pos.liquidationPrice > 0 ? fmtPrice(pos.liquidationPrice) : '—'}</span></div>
        <div><i>{t('sltp.sl')}</i><span className="dn">{levelsText(pos.stopLosses)}</span></div>
        <div><i>{t('sltp.tp')}</i><span className="up">{levelsText(pos.takeProfits)}</span></div>
        <div>
          <i>{t('metric.funding')}</i>
          <span className="whitespace-nowrap"><em className={SUBLABEL}>{t('metric.fundingPer')}</em>{fmtNum(pos.fundingFeePerCycle)}</span>{' '}
          <span className="whitespace-nowrap"><em className={SUBLABEL}>{t('metric.fundingSum')}</em>{fmtNum(pos.fundingFeeTotal)}</span>
        </div>
        <div><i>{t('metric.mmr')}</i>{currentBracket ? t('metric.tier', { tier: currentBracket.tier, rate: formatRate(currentBracket.mmr) }) : '—'}</div>
        <div><i>{t('metric.realizedPnl')}</i><span className={realizedPnl >= 0 ? 'up' : 'dn'}>{realizedPnl >= 0 ? '+' : ''}{fmtNum(realizedPnl)}</span></div>
      </div>
      {/* 加仓无独立入口（对齐Binance）：同方向再下一单即自动并入仓位，走开仓面板 */}
      <div className="acts flex-wrap">
        <button className={actBtn('close')} onClick={() => toggleAction('close')}>{t('pos.close')}</button>
        <button className={actBtn('leverage')} onClick={() => toggleAction('leverage')}>{t('pos.leverage')}</button>
        <button className={actBtn('stoploss')} onClick={() => toggleAction('stoploss')}>{t('pos.sltp')}</button>
        {/* 全仓保证金按账户统一算，单仓加减保证金没意义，后端也会拒（1761），直接不给入口 */}
        {!isCrossPos && <button className={actBtn('margin')} onClick={() => toggleAction('margin')}>{t('pos.addMargin')}</button>}
        {!isCrossPos && <button className={actBtn('reduceMargin')} onClick={() => toggleAction('reduceMargin')}>{t('pos.reduceMargin')}</button>}
        {onCoinPage ? (
          <button
            className={cn('btn xs', confirmReverse && 'loss')}
            disabled={reversing}
            title={t('pos.reverseHelp')}
            onClick={() => confirmReverse ? void handleReverse() : setConfirmReverse(true)}
          >
            {reversing ? <Loader2 className="w-3 h-3 animate-spin" /> : confirmReverse ? t('pos.reverseConfirm') : t('pos.reverse')}
          </button>
        ) : (
          <button className="btn xs" onClick={() => navigate(`/coin/${pos.symbol}`)}>{t('pos.goTrade')}</button>
        )}
      </div>

      {action && (
        <div className="border-t border-border pt-3 flex flex-col gap-2.5">
          {/* 平仓 */}
          {action === 'close' && (
            <>
              <div className="field">
                <label>{t('orderType.label')}</label>
                <div className="seg flex">
                  {(['MARKET', 'LIMIT'] as const).map(o => (
                    <button key={o} className={cn('flex-1', closeOrderType === o && 'on')} onClick={() => setCloseOrderType(o)}>
                      {t(o === 'MARKET' ? 'orderType.market' : 'orderType.limit')}
                    </button>
                  ))}
                </div>
              </div>
              {closeOrderType === 'LIMIT' && (
                <div className="field">
                  <label>{t('open.limitLabel')}</label>
                  <NumInput value={closeLimitPrice} onChange={setCloseLimitPrice} step={PRICE_STEP_TEXT} min="0" unit="USDT" />
                  {parseFloat(closeLimitPrice) > 0 && livePrice > 0 && (
                    (isLong && parseFloat(closeLimitPrice) <= livePrice) ||
                    (!isLong && parseFloat(closeLimitPrice) >= livePrice)
                  ) && (
                    <div className="text-[12px] text-warning">{t(isLong ? 'open.limitFillsLte' : 'open.limitFillsGte')}</div>
                  )}
                </div>
              )}
              <div className="field">
                <label>
                  <span>{t('pos.closeQty')}</span>
                  <span className="num">/ {pos.quantity} {cfg.name}</span>
                </label>
                <NumInput value={closeQty} onChange={setCloseQty} step={String(MIN_QTY)} min={MIN_QTY} max={pos.quantity} unit={cfg.name} />
                <PctRow active={closeActivePct} onPick={p => setCloseQty(qtyByPct(pos.quantity, p * 100, MIN_QTY))} />
              </div>
              <button className="btn xs fill" onClick={handleClose} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : t('pos.confirmClose')}
              </button>
            </>
          )}
          {/* +保证金 */}
          {action === 'margin' && (
            <>
              <div className="field">
                <label>
                  <span>{t('pos.addAmount')}</span>
                  {spendable != null && <span className="num">{t('pos.availableBalance', { amount: fmtNum(spendable) })}</span>}
                </label>
                <NumInput value={marginAmt} onChange={setMarginAmt} step="0.01" min="0" unit="USDT" />
              </div>
              <button className="btn xs fill" onClick={handleAddMargin} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : t('pos.confirmAdd')}
              </button>
            </>
          )}
          {/* -保证金 */}
          {action === 'reduceMargin' && (
            <>
              <div className="field">
                <label>
                  <span>{t('pos.reduceAmount')}</span>
                  <span className="num">{t('pos.currentMargin', { amount: fmtNum(pos.margin) })}</span>
                </label>
                <NumInput value={marginAmt} onChange={setMarginAmt} step="0.01" min="0" max={pos.margin} unit="USDT" />
              </div>
              <button className="btn xs fill" onClick={handleReduceMargin} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : t('pos.confirmReduce')}
              </button>
            </>
          )}
          {/* 调杠杆：逐仓调低要退保证金但亏损已吃进仓位，后端不支持，滑杆下限=当前杠杆 */}
          {action === 'leverage' && (() => {
            const maxLev = brackets?.[0]?.maxLeverage ?? FUTURES_LEVERAGE_OPTIONS[FUTURES_LEVERAGE_OPTIONS.length - 1];
            const minLev = isCrossPos ? 1 : pos.leverage;
            const selLev = newLeverage ?? pos.leverage;
            const changed = selLev !== pos.leverage;
            // 档位标签：常规档过滤到 [min,max]，逐仓再补当前杠杆作为起点标签
            const levTicks = [...new Set([minLev, ...FUTURES_LEVERAGE_OPTIONS.filter(lv => lv >= minLev && lv <= maxLev)])].sort((a, b) => a - b);
            return (
              <>
                <div className="flex justify-between items-baseline text-[12.5px] font-semibold text-muted-foreground">
                  <span>{t('lev.label')}</span>
                  <b className={cn('num text-[20px] font-bold', changed ? 'text-warning' : 'text-foreground')}>{selLev}x</b>
                </div>
                <LeverageSlider value={selLev} min={minLev} max={maxLev} ticks={levTicks} onChange={setNewLeverage} />
                <button className="btn xs fill" onClick={handleAdjustLeverage} disabled={submitting || !changed}>
                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : t('lev.apply', { lev: selLev })}
                </button>
              </>
            );
          })()}
          {/* 止损/止盈 */}
          {action === 'stoploss' && (
            <>
              <div className="field">
                <label>
                  <span className="flex items-center gap-1">{t('sltp.sl')} <HelpTip text={t('sltp.posHelp')} /></span>
                </label>
                <SLTPEditor rows={slRows} onChange={setSlRows} kind="SL" posQty={pos.quantity} minQty={MIN_QTY}
                  entryPrice={pos.entryPrice} margin={pos.margin} side={pos.side} unit={cfg.name}
                  minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                <div className="flex gap-1.5">
                  <button className="btn xs fill flex-1" onClick={handleSetStopLoss} disabled={submitting}>
                    {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : t('sltp.saveSl')}
                  </button>
                  {(pos.stopLosses?.length ?? 0) > 0 && (
                    <button className="btn xs" onClick={handleCancelStopLoss} disabled={submitting}>{t('sltp.clearSl')}</button>
                  )}
                </div>
              </div>
              <div className="field">
                <label>
                  <span className="flex items-center gap-1">{t('sltp.tp')} <HelpTip text={t('sltp.posHelp')} /></span>
                </label>
                <SLTPEditor rows={tpRows} onChange={setTpRows} kind="TP" posQty={pos.quantity} minQty={MIN_QTY}
                  entryPrice={pos.entryPrice} margin={pos.margin} side={pos.side} unit={cfg.name}
                  minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                <div className="flex gap-1.5">
                  <button className="btn xs fill flex-1" onClick={handleSetTakeProfit} disabled={submitting}>
                    {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : t('sltp.saveTp')}
                  </button>
                  {(pos.takeProfits?.length ?? 0) > 0 && (
                    <button className="btn xs" onClick={handleCancelTakeProfit} disabled={submitting}>{t('sltp.clearTp')}</button>
                  )}
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
