import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Loader2, Plus } from 'lucide-react';
import { futuresApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { useCryptoStream } from '../../hooks/useCryptoStream';
import { useToast } from '../ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Badge } from '../ui/badge';
import { HelpTip } from '../HelpTip';
import { LeverageSlider } from '../LeverageSlider';
import { NeuToggle } from '../NeuToggle';
import { PctSlider } from '../PctSlider';
import { fmtNum } from '../../lib/utils';
import { getCoin, getCoinPriceDecimals, getCoinPriceStep, formatCoinPrice } from '../../lib/coinConfig';
import type { FuturesPosition, FuturesBracket } from '../../types';
import { SLTPEditor } from './SLTPEditor';
import {
  FUTURES_LEVERAGE_OPTIONS, findFuturesBracket, formatRate, qtyByPct,
  type SLTPRow,
} from './futuresMath';

type PosActionType = 'close' | 'margin' | 'reduceMargin' | 'stoploss' | 'leverage';

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
 * 合约持仓卡组：symbol 传入=只显示该币种（Coin页，双向持仓最多 多+空 两张，一行两格），
 * 不传=全部持仓（Portfolio汇总，一行四张）。行尾空位用 + 槽占位。
 * 每张仓位卡自订该币 futures WS 流（共享 STOMP 连接）实时算盈亏，不再依赖页面传 markPrice；
 * showCloseAll 开启头部"一键全平"（两段确认，市价全平所有仓位）。
 * 加仓无独立入口（对齐Binance）：同方向再下单即自动并入，走开仓面板。
 */
export function FuturesPositionsCard({ symbol, refreshKey, onOrdersChanged, onPositionsChanged, showCloseAll }: {
  symbol?: string;
  refreshKey: number;
  onOrdersChanged?: () => void;
  /** 仓位有任何变动（平仓/调杠杆/保证金）时回调；Coin 页用它驱动开仓面板的持仓快照重拉 */
  onPositionsChanged?: () => void;
  showCloseAll?: boolean;
}) {
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);

  const [positions, setPositions] = useState<FuturesPosition[]>([]);
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

  // 子卡操作成功后的统一收口：刷仓位+用户+通知外部（开仓面板要跟随仓位快照）；成交类操作再通知订单表
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
            <HelpTip text={`${symbol ? '仅显示当前币种活跃仓位，盈亏基于标记价实时计算' : '全部币种活跃仓位，盈亏基于各币标记价实时计算'}\n已实现盈亏 = 开/加仓手续费 + 已平部分净盈亏（不含资金费）`} />
          </CardTitle>
          <div className="flex items-center gap-2">
            {showCloseAll && (
              <Button
                size="sm"
                variant={confirmCloseAll ? 'destructive' : 'outline'}
                className="h-9 sm:h-7 text-[11px]"
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
      {/* Coin页(传symbol)对齐Binance双向持仓：每币最多 多+空 两张，一行两格；Portfolio跨币汇总维持一行四张。
          items-start 防止一张打开操作面板把同行其他卡撑高 */}
      <CardContent className={`grid grid-cols-1 sm:grid-cols-2 ${symbol ? '' : 'xl:grid-cols-4'} gap-4 px-5 pb-5 pt-0 items-start`}>
        {positions.map(pos => (
          <PositionItem
            key={pos.id}
            pos={pos}
            brackets={bracketsMap[pos.symbol]}
            wide={!!symbol}
            onMutated={handleMutated}
          />
        ))}
        {/* 行尾 + 槽占位：Coin页补到两格（空缺的多/空方向），Portfolio补齐四格；self-stretch 跟随同行最高卡，窄屏隐藏 */}
        {Array.from({ length: symbol ? Math.max(0, 2 - positions.length) : (4 - positions.length % 4) % 4 }).map((_, i) => (
          <div key={`slot-${i}`} className={`${symbol ? 'hidden sm:flex' : 'hidden xl:flex'} self-stretch min-h-44 rounded-lg border border-dashed border-border bg-card-2 items-center justify-center`}>
            <Plus className="w-7 h-7 text-muted-foreground opacity-30" />
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/**
 * 单仓位卡：自订该币 futures 流，标记价实时衍生盈亏（纯 render 期计算，不回写 state）；
 * 全信息常驻（不折叠），竖排布局适配四列窄卡；操作面板点按钮展开，状态全部内聚于本卡。
 */
function PositionItem({ pos, brackets, wide, onMutated }: {
  pos: FuturesPosition;
  brackets?: FuturesBracket[];
  /** Coin页两格布局卡更宽，指标恒3列；Portfolio四格窄卡 ≥sm 退2列 */
  wide?: boolean;
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

  const [action, setAction] = useState<PosActionType | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [closeOrderType, setCloseOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [closeLimitPrice, setCloseLimitPrice] = useState('');
  const [closeQty, setCloseQty] = useState('');
  const [marginAmt, setMarginAmt] = useState('');
  const [newLeverage, setNewLeverage] = useState<number | null>(null);
  const [slRows, setSlRows] = useState<SLTPRow[]>([]);
  const [tpRows, setTpRows] = useState<SLTPRow[]>([]);

  const isPnlUp = unrealizedPnl >= 0;
  const isLong = pos.side === 'LONG';
  const isCrossPos = pos.marginMode === 'CROSS';
  const currentBracket = findFuturesBracket(brackets, positionValue);

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

  // 币种级调杠杆：多空共用，同时作用于该币全部仓位；全仓双向可调（调低要可用够），逐仓只能调高；错误信息由后端 toast 透出
  const handleAdjustLeverage = () => {
    if (!newLeverage) { toast('请选择目标杠杆', 'error'); return; }
    void submit(() => futuresApi.adjustLeverage({ symbol: pos.symbol, leverage: newLeverage }), '杠杆调整成功', false);
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

  // 取消=提交空档位列表，后端清库并撤触发索引
  const handleCancelStopLoss = () => {
    void submit(() => futuresApi.setStopLoss({ positionId: pos.id, stopLosses: [] }), '已取消止损', false);
  };

  const handleCancelTakeProfit = () => {
    void submit(() => futuresApi.setTakeProfit({ positionId: pos.id, takeProfits: [] }), '已取消止盈', false);
  };

  return (
    // 不能 overflow-hidden：卡内 HelpTip/预估气泡要溢出卡边显示（窄卡下会被裁掉），色条自己收圆角
    <div className="p-4 rounded-lg border border-border bg-card-2 relative">
      <div className={`absolute top-0 bottom-0 left-0 w-1.5 rounded-l-2xl ${isLong ? 'bg-gain' : 'bg-loss'}`} />
      {/* 全信息竖排常驻：头部徽标 → 盈亏主角 → 全量指标 → SL/TP 胶囊 → 操作按钮 */}
      <div className="pl-3 space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[13px] font-black">{cfg.name}</span>
        <Badge variant={isLong ? 'success' : 'destructive'} className="text-[10px] px-2 py-0.5">{isLong ? '做多' : '做空'}</Badge>
        <Badge variant="outline" className="text-[10px] px-2 py-0.5">{isCrossPos ? '全仓' : '逐仓'} {pos.leverage}x</Badge>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className={`text-xl font-black tabular-nums leading-none ${isPnlUp ? 'text-green-500' : 'text-red-500'}`}>
          {isPnlUp ? '+' : ''}{fmtNum(unrealizedPnl)}
          <span className="text-[11px] font-bold ml-1 opacity-60">USDT</span>
        </div>
        <span className={`inline-block text-[11px] font-bold tabular-nums px-1.5 py-0.5 rounded-md ${isPnlUp ? 'text-green-500 bg-green-500/10' : 'text-red-500 bg-red-500/10'}`}>
          {isPnlUp ? '+' : ''}{unrealizedPnlPct.toFixed(2)}%
        </span>
      </div>
      {/* 手机全宽单列卡塞得下3列指标（压卡高）；四格窄卡 ≥sm 退2列，Coin页两格宽卡恒3列 */}
      <div className={`grid grid-cols-3 ${wide ? '' : 'sm:grid-cols-2'} gap-x-3 gap-y-1.5`}>
        {[
          { label: '数量', value: `${pos.quantity} ${cfg.name}` },
          { label: '开仓价', value: `$${fmtPrice(pos.entryPrice)}` },
          { label: '标记价', value: `$${fmtPrice(mp)}` },
          { label: '强平价', value: pos.liquidationPrice > 0 ? `$${fmtPrice(pos.liquidationPrice)}` : '—', cls: 'text-yellow-500' },
          { label: isCrossPos ? '占用保证金' : '保证金', value: `$${fmtNum(pos.margin)}` },
          { label: '资金费', value: `$${fmtNum(pos.fundingFeeTotal)}` },
          { label: 'MMR', value: currentBracket ? `档${currentBracket.tier} · ${formatRate(currentBracket.mmr)}` : '—' },
          {
            label: '已实现盈亏',
            value: `${(pos.realizedPnl ?? 0) >= 0 ? '+' : ''}${fmtNum(pos.realizedPnl ?? 0)}`,
            cls: (pos.realizedPnl ?? 0) >= 0 ? 'text-green-500' : 'text-red-500',
          },
        ].map(it => (
          <div key={it.label}>
            <div className="text-[10px] text-muted-foreground">{it.label}</div>
            <div className={`text-[11px] font-mono font-semibold tabular-nums ${it.cls ?? 'text-foreground'}`}>{it.value}</div>
          </div>
        ))}
      </div>
      {((pos.stopLosses?.length ?? 0) > 0 || (pos.takeProfits?.length ?? 0) > 0) && (
        <div className="flex flex-wrap gap-1.5">
          {pos.stopLosses?.map((s, i) => (
            <span key={`sl-${i}`} className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-yellow-500/10 text-yellow-500">SL ${fmtPrice(s.price)}</span>
          ))}
          {pos.takeProfits?.map((t, i) => (
            <span key={`tp-${i}`} className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-green-500/10 text-green-500">TP ${fmtPrice(t.price)}</span>
          ))}
        </div>
      )}
      <div className="flex flex-wrap gap-1.5 pt-1">
        {/* 加仓无独立入口（对齐Binance）：同方向再下一单即自动并入仓位，走开仓面板 */}
        <Button size="sm" variant={action === 'close' ? 'default' : 'outline'} className="h-9 sm:h-7 text-[11px] flex-1 min-w-15" onClick={() => toggleAction('close')}>平仓</Button>
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
                <NeuToggle
                  size="sm"
                  label="平仓执行方式"
                  value={closeOrderType}
                  onChange={setCloseOrderType}
                  options={[{ value: 'MARKET', label: '市价' }, { value: 'LIMIT', label: '限价' }]}
                />
              </div>
              {closeOrderType === 'LIMIT' && (
                <>
                  <Input type="number" placeholder="限价 (USDT)" value={closeLimitPrice} onChange={e => setCloseLimitPrice(e.target.value)} step={PRICE_STEP_TEXT} className="h-9 sm:h-8 text-xs" />
                  {parseFloat(closeLimitPrice) > 0 && livePrice > 0 && (
                    (isLong && parseFloat(closeLimitPrice) <= livePrice) ||
                    (!isLong && parseFloat(closeLimitPrice) >= livePrice)
                  ) && (
                    <div className="text-[10px] text-yellow-500">限价{isLong ? '≤' : '≥'}当前价，将立即以市价成交</div>
                  )}
                </>
              )}
              {(() => {
                const closePct = pos.quantity > 0 ? Math.round(((parseFloat(closeQty) || 0) / pos.quantity) * 100) : 0;
                return (
                  <>
                    <div className="flex items-center gap-2">
                      <Input type="number" placeholder="平仓数量" value={closeQty} onChange={e => setCloseQty(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={pos.quantity} className="flex-1 h-9 sm:h-8 text-xs" />
                      <span className="text-[11px] text-muted-foreground tabular-nums shrink-0">/ {pos.quantity} · {closePct}%</span>
                    </div>
                    <PctSlider
                      value={Math.min(closePct, 100)}
                      onChange={p => setCloseQty(qtyByPct(pos.quantity, p, MIN_QTY))}
                    />
                  </>
                );
              })()}
              <Button size="sm" className="w-full h-9 sm:h-8 text-xs" onClick={handleClose} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认平仓'}
              </Button>
            </>
          )}
          {/* +保证金 */}
          {action === 'margin' && (
            <>
              <Input type="number" placeholder="追加金额 (USDT)" value={marginAmt} onChange={e => setMarginAmt(e.target.value)} step="0.01" min="0" className="h-9 sm:h-8 text-xs" />
              {user && <div className="text-[11px] text-muted-foreground">可用余额 {fmtNum(user.balance)} USDT</div>}
              <Button size="sm" className="w-full h-9 sm:h-8 text-xs" onClick={handleAddMargin} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认追加'}
              </Button>
            </>
          )}
          {/* -保证金 */}
          {action === 'reduceMargin' && (
            <>
              <Input type="number" placeholder="减少金额 (USDT)" value={marginAmt} onChange={e => setMarginAmt(e.target.value)} step="0.01" min="0" max={pos.margin} className="h-9 sm:h-8 text-xs" />
              <div className="text-[11px] text-muted-foreground">当前保证金 {fmtNum(pos.margin)} USDT</div>
              <Button size="sm" className="w-full h-9 sm:h-8 text-xs" onClick={handleReduceMargin} disabled={submitting}>
                {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认减少'}
              </Button>
            </>
          )}
          {/* 调杠杆：复用开仓的拟物杠杆滑杆；逐仓调低要退保证金但亏损已吃进仓位，后端不支持，滑杆下限=当前杠杆 */}
          {action === 'leverage' && (() => {
            const maxLev = brackets?.[0]?.maxLeverage ?? FUTURES_LEVERAGE_OPTIONS[FUTURES_LEVERAGE_OPTIONS.length - 1];
            const minLev = isCrossPos ? 1 : pos.leverage;
            const selLev = newLeverage ?? pos.leverage;
            const changed = selLev !== pos.leverage;
            // 档位标签：常规档过滤到 [min,max]，逐仓再补当前杠杆作为起点标签
            const levTicks = [...new Set([minLev, ...FUTURES_LEVERAGE_OPTIONS.filter(lv => lv >= minLev && lv <= maxLev)])].sort((a, b) => a - b);
            return (
              <>
                <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-0.5">
                  <span className="text-[11px] text-muted-foreground">
                    当前 {pos.leverage}x · 多空共用同时调整 · {isCrossPos ? '全仓可调高调低，调低需可用余额足够' : '逐仓只能调高'}
                  </span>
                  <span className={`text-sm font-black tabular-nums ${changed ? 'text-primary' : 'text-muted-foreground'}`}>{selLev}x</span>
                </div>
                <LeverageSlider value={selLev} min={minLev} max={maxLev} ticks={levTicks} onChange={setNewLeverage} />
                <Button size="sm" className="w-full h-9 sm:h-8 text-xs" onClick={handleAdjustLeverage} disabled={submitting || !changed}>
                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : changed ? `调整为 ${selLev}x` : '拖动滑杆选择杠杆'}
                </Button>
              </>
            );
          })()}
          {/* 止损/止盈 */}
          {action === 'stoploss' && (
            // 四列窄卡下双列会把 SLTP 编辑器输入框挤到不可用，恒单列
            <div className="grid grid-cols-1 gap-3">
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground flex items-center gap-1">
                  止损 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                </div>
                <SLTPEditor rows={slRows} onChange={setSlRows} label="止损" posQty={pos.quantity} minQty={MIN_QTY}
                  entryPrice={pos.entryPrice} margin={pos.margin} side={pos.side}
                  minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                <div className="flex gap-1.5">
                  <Button size="sm" className="flex-1 h-9 sm:h-8 text-xs" onClick={handleSetStopLoss} disabled={submitting}>
                    {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止损'}
                  </Button>
                  {(pos.stopLosses?.length ?? 0) > 0 && (
                    <Button size="sm" variant="destructive" className="h-9 sm:h-8 text-xs" onClick={handleCancelStopLoss} disabled={submitting}>
                      取消止损
                    </Button>
                  )}
                </div>
              </div>
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground flex items-center gap-1">
                  止盈 <HelpTip text="触发价达到时自动市价平仓，可分多档，总量不超过持仓" />
                </div>
                <SLTPEditor rows={tpRows} onChange={setTpRows} label="止盈" posQty={pos.quantity} minQty={MIN_QTY}
                  entryPrice={pos.entryPrice} margin={pos.margin} side={pos.side}
                  minPriceStep={PRICE_STEP} priceFormatter={fmtPrice} />
                <div className="flex gap-1.5">
                  <Button size="sm" className="flex-1 h-9 sm:h-8 text-xs" onClick={handleSetTakeProfit} disabled={submitting}>
                    {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '保存止盈'}
                  </Button>
                  {(pos.takeProfits?.length ?? 0) > 0 && (
                    <Button size="sm" variant="destructive" className="h-9 sm:h-8 text-xs" onClick={handleCancelTakeProfit} disabled={submitting}>
                      取消止盈
                    </Button>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      )}
      </div>
    </div>
  );
}
