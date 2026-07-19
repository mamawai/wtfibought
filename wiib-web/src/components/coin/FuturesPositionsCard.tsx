import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Loader2, ChevronDown } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useCryptoStream } from '../../hooks/useCryptoStream';
import { useToast } from '../ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Badge } from '../ui/badge';
import { HelpTip } from '../HelpTip';
import { cn, fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import type { FuturesPosition, FuturesBracket } from '../../types';
import { SLTPEditor } from './SLTPEditor';
import {
  POSITION_PCTS, FUTURES_COMMISSION_RATE, FUTURES_LEVERAGE_OPTIONS, roundHalfUp2, roundCeil2,
  findFuturesBracket, formatRate, calcMaxIncreaseQty,
  type SLTPRow,
} from './futuresMath';

type PosActionType = 'close' | 'increase' | 'margin' | 'reduceMargin' | 'stoploss' | 'leverage';

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
 * 合约持仓卡组：symbol 传入=只显示该币种（Coin页），不传=全部持仓（Portfolio汇总）。
 * 每张仓位卡自订该币 futures WS 流（共享 STOMP 连接）实时算盈亏，不再依赖页面传 markPrice；
 * showCloseAll 开启头部"一键全平"（两段确认，市价全平所有仓位）。多仓宽屏双列小卡。
 */
export function FuturesPositionsCard({ symbol, refreshKey, onOrdersChanged, showCloseAll }: {
  symbol?: string;
  refreshKey: number;
  onOrdersChanged?: () => void;
  showCloseAll?: boolean;
}) {
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);

  const [positions, setPositions] = useState<FuturesPosition[]>([]);
  // 全仓可用余额（含浮盈亏），全仓仓位加仓的预算基数；无全仓仓位时为 null
  const [crossAvailable, setCrossAvailable] = useState<number | null>(null);
  const [bracketsMap, setBracketsMap] = useState<Record<string, FuturesBracket[]>>({});
  const [loading, setLoading] = useState(false);
  const [closingAll, setClosingAll] = useState(false);
  const [confirmCloseAll, setConfirmCloseAll] = useState(false);

  useEffect(() => { loadBrackets().then(setBracketsMap).catch(() => { /* MMR/杠杆上限降级显示 */ }); }, []);

  const fetchPositions = useCallback(async () => {
    setLoading(true);
    try {
      const list = await futuresApi.positions(symbol);
      setPositions(list);
      // 跟仓位一起刷：全仓预算按账户统一算
      if (list.some(p => p.marginMode === 'CROSS')) {
        futuresApi.crossAccount().then(a => setCrossAvailable(a.available)).catch(() => setCrossAvailable(null));
      } else {
        setCrossAvailable(null);
      }
    } catch (e) {
      console.error('查询合约仓位失败', e);
      setPositions([]);
    } finally {
      setLoading(false);
    }
  }, [symbol]);

  useEffect(() => { fetchPositions(); }, [fetchPositions, refreshKey]);

  // 一键全平两段确认，3s 未二次点击自动还原
  useEffect(() => {
    if (!confirmCloseAll) return;
    const t = window.setTimeout(() => setConfirmCloseAll(false), 3000);
    return () => window.clearTimeout(t);
  }, [confirmCloseAll]);

  // 子卡操作成功后的统一收口：刷仓位+用户；成交类操作再通知外部订单表
  const handleMutated = useCallback((ordersChanged: boolean) => {
    fetchPositions();
    fetchUser();
    if (ordersChanged) onOrdersChanged?.();
  }, [fetchPositions, fetchUser, onOrdersChanged]);

  const handleCloseAll = async () => {
    setConfirmCloseAll(false);
    setClosingAll(true);
    try {
      const res = await futuresApi.closeAll();
      if (res.failures.length > 0) {
        toast(`已平 ${res.closedCount} 个仓位，${res.failures.length} 个失败`, 'error', { description: res.failures.join('；') });
      } else {
        toast(`已全部平仓（${res.closedCount} 个）`, 'success');
      }
      handleMutated(true);
    } catch (e: unknown) {
      toast((e as Error).message || '一键全平失败', 'error');
    } finally {
      setClosingAll(false);
    }
  };

  if (positions.length === 0) return null;

  return (
    <Card>
      <CardHeader className="pb-4 pt-5 px-5">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base font-black flex items-center gap-2">
            {symbol ? '当前持仓' : '合约持仓'}
            <span className="text-xs text-muted-foreground font-normal">{positions.length}个</span>
            <HelpTip text={symbol ? '仅显示当前币种活跃仓位，盈亏基于标记价实时计算' : '全部币种活跃仓位，盈亏基于各币标记价实时计算'} />
          </CardTitle>
          <div className="flex items-center gap-2">
            {showCloseAll && (
              <Button
                size="sm"
                variant={confirmCloseAll ? 'destructive' : 'outline'}
                className="h-7 text-[11px]"
                disabled={closingAll}
                onClick={() => confirmCloseAll ? handleCloseAll() : setConfirmCloseAll(true)}
              >
                {closingAll ? <Loader2 className="w-3 h-3 animate-spin" />
                  : confirmCloseAll ? `确认全平 ${positions.length} 个仓位？` : '一键全平'}
              </Button>
            )}
            <button onClick={fetchPositions} disabled={loading} className="p-1 rounded-md hover:bg-muted transition-colors disabled:opacity-50">
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
            </button>
          </div>
        </div>
      </CardHeader>
      {/* 多仓宽屏双列小卡；items-start 防止展开一张把同行另一张撑高 */}
      <CardContent className={cn('grid grid-cols-1 gap-4 px-5 pb-5 pt-0 items-start', positions.length > 1 && 'xl:grid-cols-2')}>
        {positions.map(pos => (
          <PositionItem
            key={pos.id}
            pos={pos}
            brackets={bracketsMap[pos.symbol]}
            crossAvailable={crossAvailable}
            onMutated={handleMutated}
          />
        ))}
      </CardContent>
    </Card>
  );
}

/**
 * 单仓位卡：自订该币 futures 流，标记价实时衍生盈亏（纯 render 期计算，不回写 state）；
 * 默认两行摘要，展开才见细节+操作面板。操作状态全部内聚于本卡。
 */
function PositionItem({ pos, brackets, crossAvailable, onMutated }: {
  pos: FuturesPosition;
  brackets?: FuturesBracket[];
  crossAvailable: number | null;
  onMutated: (ordersChanged: boolean) => void;
}) {
  const cfg = getCoin(pos.symbol);
  const MIN_QTY = cfg.minQty;
  const PRICE_STEP = getCoinPriceStep(pos.symbol);
  const PRICE_STEP_TEXT = PRICE_STEP.toFixed(getCoinPriceDecimals(pos.symbol));
  const fmtPrice = useCallback((n?: number | null) => formatCoinPrice(pos.symbol, n), [pos.symbol]);

  const { toast } = useToast();
  const user = useUserStore(s => s.user);

  // WS 实时价：mp 驱动盈亏，fp（最新价）用于限价单提示与 SLTP 编辑；断流时退回后端快照值
  const tick = useCryptoStream(pos.symbol, 'futures');
  const mp = tick?.mp ?? pos.markPrice;
  const livePrice = tick?.price ?? pos.currentPrice;
  const positionValue = mp * pos.quantity;
  const unrealizedPnl = pos.side === 'LONG'
    ? (mp - pos.entryPrice) * pos.quantity
    : (pos.entryPrice - mp) * pos.quantity;
  const unrealizedPnlPct = pos.margin > 0 ? (unrealizedPnl / pos.margin) * 100 : 0;

  const [expanded, setExpanded] = useState(false);
  const [action, setAction] = useState<PosActionType | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [closeOrderType, setCloseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [closeLimitPrice, setCloseLimitPrice] = useState('');
  const [closeQty, setCloseQty] = useState('');
  const [increaseOrderType, setIncreaseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [increaseLimitPrice, setIncreaseLimitPrice] = useState('');
  const [increaseQty, setIncreaseQty] = useState('');
  const [marginAmt, setMarginAmt] = useState('');
  const [newLeverage, setNewLeverage] = useState<number | null>(null);
  const [slRows, setSlRows] = useState<SLTPRow[]>([]);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([]);

  const isPnlUp = unrealizedPnl >= 0;
  const isLong = pos.side === 'LONG';
  const isCrossPos = pos.marginMode === 'CROSS';
  const currentBracket = findFuturesBracket(brackets, positionValue);

  const toggleExpand = () => {
    // 收起时顺手关掉打开的操作面板
    if (expanded) setAction(null);
    setExpanded(v => !v);
  };

  // 操作切换：展开时重置各输入，止盈损带入已有档位
  const toggleAction = (type: PosActionType) => {
    if (action === type) {
      setAction(null);
      return;
    }
    setAction(type);
    setCloseQty(''); setCloseLimitPrice(''); setCloseOrderType('MARKET');
    setIncreaseQty(''); setIncreaseLimitPrice(''); setIncreaseOrderType('MARKET');
    setMarginAmt('');
    setNewLeverage(null);
    setSlRows(pos.stopLosses?.map(s => ({ price: String(s.price), quantity: String(s.quantity) })) ?? [{ price: '', quantity: '' }]);
    setTpRows(pos.takeProfits?.map(t => ({ price: String(t.price), quantity: String(t.quantity) })) ?? [{ price: '', quantity: '' }]);
  };

  // 通用提交包装：成功关面板并向父上报
  const submit = async (fn: () => Promise<unknown>, okMsg: string, ordersChanged: boolean) => {
    setSubmitting(true);
    try {
      await fn();
      toast(okMsg, 'success');
      setAction(null);
      onMutated(ordersChanged);
    } catch (e: unknown) {
      toast((e as Error).message || `${okMsg.replace('成功', '')}失败`, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleClose = () => {
    const qty = parseFloat(closeQty);
    if (!qty || qty <= 0) { toast('请输入平仓数量', 'error'); return; }
    if (closeOrderType === 'LIMIT') {
      const lp = parseFloat(closeLimitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    void submit(() => futuresApi.close({
      positionId: pos.id, quantity: qty, orderType: closeOrderType,
      ...(closeOrderType === 'LIMIT' ? { limitPrice: parseFloat(closeLimitPrice) } : {}),
    }), '平仓成功', true);
  };

  const handleIncrease = () => {
    const qty = parseFloat(increaseQty);
    if (!qty || qty <= 0) { toast('请输入加仓数量', 'error'); return; }
    if (increaseOrderType === 'LIMIT') {
      const lp = parseFloat(increaseLimitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    void submit(() => futuresApi.increase({
      positionId: pos.id, quantity: qty, orderType: increaseOrderType,
      ...(increaseOrderType === 'LIMIT' ? { limitPrice: parseFloat(increaseLimitPrice) } : {}),
    }), '加仓成功', true);
  };

  const handleAddMargin = () => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    void submit(() => futuresApi.addMargin({ positionId: pos.id, amount: amt }), '追加保证金成功', false);
  };

  const handleReduceMargin = () => {
    const amt = parseFloat(marginAmt);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    void submit(() => futuresApi.reduceMargin({ positionId: pos.id, amount: amt }), '减少保证金成功', false);
  };

  // 调杠杆：全仓双向可调（调低要可用够），逐仓只能调高；错误信息由后端 toast 透出
  const handleAdjustLeverage = () => {
    if (!newLeverage) { toast('请选择目标杠杆', 'error'); return; }
    void submit(() => futuresApi.adjustLeverage({ positionId: pos.id, leverage: newLeverage }), '杠杆调整成功', false);
  };

  const handleSetStopLoss = () => {
    const items = slRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    void submit(() => futuresApi.setStopLoss({ positionId: pos.id, stopLosses: items }), '设置止损成功', false);
  };

  const handleSetTakeProfit = () => {
    const items = tpRows.filter(r => parseFloat(r.price) > 0 && parseFloat(r.quantity) > 0)
      .map(r => ({ price: parseFloat(r.price), quantity: parseFloat(r.quantity) }));
    void submit(() => futuresApi.setTakeProfit({ positionId: pos.id, takeProfits: items }), '设置止盈成功', false);
  };

  return (
    <div className="p-4 rounded-2xl bg-card neu-raised space-y-3 transition-all relative overflow-hidden">
      <div className={`absolute top-0 bottom-0 left-0 w-1.5 ${isLong ? 'bg-gain' : 'bg-loss'}`} />
      {/* 默认态两行摘要：行1 币种+徽标+数量 ↔ 盈亏主角，行2 三个关键价+SL/TP 胶囊；整块可点展开细节/操作 */}
      <div className="pl-3 flex items-center gap-2 cursor-pointer select-none" onClick={toggleExpand}>
        <div className="flex-1 min-w-0 space-y-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[13px] font-black">{cfg.name}</span>
            <Badge variant={isLong ? 'success' : 'destructive'} className="text-[10px] px-2 py-0.5">{isLong ? '做多' : '做空'}</Badge>
            <Badge variant="outline" className="text-[10px] px-2 py-0.5">{isCrossPos ? '全仓' : '逐仓'} {pos.leverage}x</Badge>
            <span className="text-xs font-semibold text-muted-foreground tabular-nums">{pos.quantity} {cfg.name}</span>
          </div>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
            {[
              { label: '开仓', value: `$${fmtPrice(pos.entryPrice)}` },
              { label: '标记', value: `$${fmtPrice(mp)}` },
              { label: '强平', value: pos.liquidationPrice > 0 ? `$${fmtPrice(pos.liquidationPrice)}` : '—', cls: 'text-yellow-500' },
            ].map(it => (
              <span key={it.label} className="whitespace-nowrap">
                <span className="text-muted-foreground">{it.label} </span>
                <span className={`font-mono font-semibold tabular-nums ${it.cls ?? 'text-foreground'}`}>{it.value}</span>
              </span>
            ))}
            {pos.stopLosses?.map((s, i) => (
              <span key={`sl-${i}`} className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-yellow-500/10 text-yellow-500">SL ${fmtPrice(s.price)}</span>
            ))}
            {pos.takeProfits?.map((t, i) => (
              <span key={`tp-${i}`} className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-green-500/10 text-green-500">TP ${fmtPrice(t.price)}</span>
            ))}
          </div>
        </div>
        <div className="text-right space-y-1 shrink-0">
          <div className={`text-xl font-black tabular-nums leading-none ${isPnlUp ? 'text-green-500' : 'text-red-500'}`}>
            {isPnlUp ? '+' : ''}{fmtNum(unrealizedPnl)}
            <span className="text-[11px] font-bold ml-1 opacity-60">USDT</span>
          </div>
          <span className={`inline-block text-[11px] font-bold tabular-nums px-1.5 py-0.5 rounded-md ${isPnlUp ? 'text-green-500 bg-green-500/10' : 'text-red-500 bg-red-500/10'}`}>
            {isPnlUp ? '+' : ''}{unrealizedPnlPct.toFixed(2)}%
          </span>
        </div>
        <ChevronDown className={`w-4 h-4 text-muted-foreground shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`} />
      </div>
      {expanded && (
      <>
      {/* 细节：保证金/资金费/MMR，展开才见 */}
      <div className="grid grid-cols-3 gap-x-3 gap-y-2 pl-3">
        {[
          { label: isCrossPos ? '占用保证金' : '保证金', value: `$${fmtNum(pos.margin)}` },
          { label: '资金费', value: `$${fmtNum(pos.fundingFeeTotal)}` },
          { label: 'MMR', value: currentBracket ? `档${currentBracket.tier} · ${formatRate(currentBracket.mmr)}` : '—' },
        ].map(it => (
          <div key={it.label}>
            <div className="text-[10px] text-muted-foreground">{it.label}</div>
            <div className="text-[11px] font-mono font-semibold tabular-nums text-foreground">{it.value}</div>
          </div>
        ))}
      </div>
      {/* 操作按钮：展开卡片才可见 */}
      <div className="flex flex-wrap gap-1.5 pt-1">
        <Button size="sm" variant={action === 'close' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('close')}>平仓</Button>
        <Button size="sm" variant={action === 'increase' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('increase')}>加仓</Button>
        {/* 全仓保证金按账户统一算，单仓加减保证金没意义，后端也会拒（1761），直接不给入口 */}
        {!isCrossPos && <Button size="sm" variant={action === 'margin' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('margin')}>+保证金</Button>}
        {!isCrossPos && <Button size="sm" variant={action === 'reduceMargin' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('reduceMargin')}>-保证金</Button>}
        <Button size="sm" variant={action === 'leverage' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('leverage')}>调杠杆</Button>
        <Button size="sm" variant={action === 'stoploss' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('stoploss')}>止损/盈</Button>
      </div>
      {/* 内联操作面板 */}
      {action && (
        <div className="pt-2 mt-1 border-t border-border/30 space-y-2">
          {/* 平仓 */}
          {action === 'close' && (
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
                  {parseFloat(closeLimitPrice) > 0 && livePrice > 0 && (
                    (isLong && parseFloat(closeLimitPrice) <= livePrice) ||
                    (!isLong && parseFloat(closeLimitPrice) >= livePrice)
                  ) && (
                    <div className="text-[10px] text-yellow-500">限价{isLong ? '≤' : '≥'}当前价，将立即以市价成交</div>
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
                  }} className="flex-1 py-2 sm:py-1 rounded text-[11px] font-medium border border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent transition-all">
                    {pct * 100}%
                  </button>
                ))}
              </div>
              <Button size="sm" className="w-full h-8 text-xs" onClick={handleClose} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认平仓'}
              </Button>
            </>
          )}
          {/* 加仓 */}
          {action === 'increase' && (() => {
            const incPrice = increaseOrderType === 'LIMIT' && parseFloat(increaseLimitPrice) > 0 ? parseFloat(increaseLimitPrice) : livePrice;
            // 预算：全仓=账户可用余额（占用制口径），逐仓=余额钱包
            const incBudget = isCrossPos ? (crossAvailable ?? 0) : (user?.balance ?? 0);
            const maxIncQty = calcMaxIncreaseQty(incBudget, incPrice, pos.leverage, MIN_QTY);
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
                  {parseFloat(increaseLimitPrice) > 0 && livePrice > 0 && (
                    (isLong && parseFloat(increaseLimitPrice) >= livePrice) ||
                    (!isLong && parseFloat(increaseLimitPrice) <= livePrice)
                  ) && (
                    <div className="text-[10px] text-yellow-500">限价{isLong ? '≥' : '≤'}当前价，将立即以市价成交</div>
                  )}
                </>
              )}
              <div className="flex items-center gap-2">
                <Input type="number" placeholder={`${MIN_QTY} - ${maxIncQty > 0 ? maxIncQty : '0'}`} value={increaseQty} onChange={e => setIncreaseQty(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={maxIncQty > 0 ? maxIncQty : undefined} className="flex-1 h-8 text-xs" />
                {user && <span className="text-[11px] text-muted-foreground shrink-0">{isCrossPos ? `可用 ${fmtNum(crossAvailable ?? 0)}` : `余额 ${fmtNum(user.balance)}`}</span>}
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
                    <div>{isCrossPos ? '占用保证金' : '保证金'} <span className="text-foreground font-mono">${fmtNum(mg)}</span></div>
                    <div>手续费 <span className="text-foreground font-mono">${fmtNum(cm)}</span></div>
                    <div className="col-span-2">需支付 <span className="text-foreground font-mono font-semibold">${fmtNum(mg + cm)}</span></div>
                  </div>
                );
              })()}
              <Button size="sm" className="w-full h-8 text-xs" onClick={handleIncrease} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认加仓'}
              </Button>
            </>
            );
          })()}
          {/* +保证金 */}
          {action === 'margin' && (
            <>
              <Input type="number" placeholder="追加金额 (USDT)" value={marginAmt} onChange={e => setMarginAmt(e.target.value)} step="0.01" min="0" className="h-8 text-xs" />
              {user && <div className="text-[11px] text-muted-foreground">可用余额 {fmtNum(user.balance)} USDT</div>}
              <Button size="sm" className="w-full h-8 text-xs" onClick={handleAddMargin} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认追加'}
              </Button>
            </>
          )}
          {/* -保证金 */}
          {action === 'reduceMargin' && (
            <>
              <Input type="number" placeholder="减少金额 (USDT)" value={marginAmt} onChange={e => setMarginAmt(e.target.value)} step="0.01" min="0" max={pos.margin} className="h-8 text-xs" />
              <div className="text-[11px] text-muted-foreground">当前保证金 {fmtNum(pos.margin)} USDT</div>
              <Button size="sm" className="w-full h-8 text-xs" onClick={handleReduceMargin} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认减少'}
              </Button>
            </>
          )}
          {/* 调杠杆：档位按钮复用开仓的杠杆选项，按币种最高杠杆过滤 */}
          {action === 'leverage' && (() => {
            const maxLev = brackets?.[0]?.maxLeverage ?? 0;
            const options = maxLev > 0 ? FUTURES_LEVERAGE_OPTIONS.filter(lv => lv <= maxLev) : FUTURES_LEVERAGE_OPTIONS;
            return (
              <>
                <div className="text-[11px] text-muted-foreground">
                  当前 {pos.leverage}x · {isCrossPos ? '全仓可调高调低，调低需可用余额足够' : '逐仓只能调高'}
                </div>
                <div className="flex flex-wrap gap-1">
                  {options.map(lv => {
                    // 逐仓调低要退保证金但亏损已吃进仓位，后端不支持，只放行调高
                    const optDisabled = isCrossPos ? lv === pos.leverage : lv <= pos.leverage;
                    return (
                      <button key={lv} type="button" disabled={optDisabled}
                        onClick={() => setNewLeverage(lv)}
                        className={`flex-1 py-2 sm:py-1 rounded text-[11px] font-medium border transition-all ${newLeverage === lv
                          ? 'border-primary bg-primary text-primary-foreground'
                          : 'border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent'} disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-card disabled:hover:text-muted-foreground`}>
                        {lv}x
                      </button>
                    );
                  })}
                </div>
                <Button size="sm" className="w-full h-8 text-xs" onClick={handleAdjustLeverage} disabled={submitting || !newLeverage}>
                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : newLeverage ? `调整为 ${newLeverage}x` : '确认调整'}
                </Button>
              </>
            );
          })()}
          {/* 止损/止盈 */}
          {action === 'stoploss' && (
            // 手机单列（双列时 SLTP 编辑器输入框被挤到不可用），≥sm 恢复双列
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground flex items-center gap-1">
                  止损 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                </div>
                <SLTPEditor rows={slRows} onChange={setSlRows} label="止损" posQty={pos.quantity} minQty={MIN_QTY}
                  currentPrice={livePrice} side={pos.side} liquidationPrice={pos.liquidationPrice}
                  minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                <Button size="sm" className="w-full h-8 text-xs" onClick={handleSetStopLoss} disabled={submitting}>
                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止损'}
                </Button>
              </div>
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground flex items-center gap-1">
                  止盈 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                </div>
                <SLTPEditor rows={tpRows} onChange={setTpRows} label="止盈" posQty={pos.quantity} minQty={MIN_QTY}
                  currentPrice={livePrice} side={pos.side} liquidationPrice={pos.liquidationPrice}
                  minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                <Button size="sm" className="w-full h-8 text-xs" onClick={handleSetTakeProfit} disabled={submitting}>
                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止盈'}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
      </>
      )}
    </div>
  );
}
