import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMarkPrices } from '../../hooks/useMarkPrices';
import { formatCoinPrice, getCoin } from '../../lib/coinConfig';
import { cn, fmtDateTime, fmtNum, fmtSignedPct, fmtTime } from '../../lib/utils';
import type { AiTraderPlanView, FuturesOrder, FuturesPosition, PlanRevision, TraderWatch } from '../../types';

/** 盯盘价离现价不到这么多（%）就标「快到了」 */
const NEAR_PCT = 0.35;

/** 块头：和分节头同一套排版，只是间距紧一档 */
const BLK_H = 'sec-h mb-4';

const signed = (v: number) => `${v >= 0 ? '+' : '-'}${fmtNum(Math.abs(v))}`;

/** 多单最高的止损、空单最低的止损先被打到；止盈反过来取离现价最近的那档 */
function nearestSl(p: FuturesPosition): number | null {
  const xs = (p.stopLosses ?? []).map(s => s.price);
  if (!xs.length) return null;
  return p.side === 'LONG' ? Math.max(...xs) : Math.min(...xs);
}

function nearestTp(p: FuturesPosition): number | null {
  const xs = (p.takeProfits ?? []).map(s => s.price);
  if (!xs.length) return null;
  return p.side === 'LONG' ? Math.min(...xs) : Math.max(...xs);
}

function planFor(plans: AiTraderPlanView[], symbol: string, side: 'LONG' | 'SHORT') {
  return plans.find(p => p.symbol === symbol && p.side === side);
}

/** 一个仓挂的全部止损或止盈价，多档用 / 连起来 */
function levelsText(symbol: string, xs: { price: number }[] | undefined): string {
  return (xs ?? []).map(s => formatCoinPrice(symbol, s.price)).join(' / ');
}

/** 从现价走到 v 还要涨 / 跌多少 */
function useMoveText() {
  const { t } = useTranslation('ai');
  return (v: number, mark: number) => {
    const d = (v / mark - 1) * 100;
    return t(d >= 0 ? 'wb.move.up' : 'wb.move.down', { pct: Math.abs(d).toFixed(2) });
  };
}

/** 计划并进仓位卡：为什么开、看错的标志、改过几次 */
function PlanLines({ plan }: { plan?: AiTraderPlanView }) {
  const { t } = useTranslation('ai');
  const revisions = useMemo<PlanRevision[]>(() => {
    try {
      return plan?.revisionsJson ? JSON.parse(plan.revisionsJson) as PlanRevision[] : [];
    } catch {
      return [];
    }
  }, [plan?.revisionsJson]);
  if (!plan) return null;
  const why = [plan.playType, plan.signalsUsed].filter(Boolean).join(' · ');
  return (
    <div className="tr-pc-why">
      {why && <div><span>{t('wb.hold.why')}</span>{why}</div>}
      <div><span>{t('wb.hold.inval')}</span>{plan.invalidationCondition}</div>
      {revisions.length > 0 && (
        <div className="num">
          <span>{t('wb.hold.revised', { count: revisions.length })}</span>
          {revisions.map(r => [fmtTime(r.time), r.type, r.change, r.reason].filter(Boolean).join(' ')).join(' / ')}
        </div>
      )}
    </div>
  );
}

/** 一个持仓：浮盈跟着实时标记价走；止损止盈都挂了才画止损（左）到止盈（右）的条，竖线是现价 */
function PositionCard({ p, plan, mark }: { p: FuturesPosition; plan?: AiTraderPlanView; mark: number }) {
  const { t } = useTranslation('ai');
  const moveText = useMoveText();
  const cfg = getCoin(p.symbol);
  const Icon = cfg.icon;
  const long = p.side === 'LONG';
  const pnl = (long ? mark - p.entryPrice : p.entryPrice - mark) * p.quantity;
  const px = (v: number) => formatCoinPrice(p.symbol, v);
  const sl = nearestSl(p);
  const tp = nearestTp(p);
  // 止损挪过了开仓价（锁了利润）：开仓价在条外，不画那根线
  const locked = sl != null && (long ? sl > p.entryPrice : sl < p.entryPrice);
  // 条上的位置：左头止损、右头止盈，多空都是往右离止盈更近
  const at = (v: number) => sl == null || tp == null ? 0
    : Math.max(0, Math.min(100, (long ? (v - sl) / (tp - sl) : (sl - v) / (sl - tp)) * 100));
  const pe = at(p.entryPrice);
  const pn = at(mark);
  return (
    <div className="tr-pc">
      <div className="tr-pc-h">
        <Icon className="coin" />
        <span className="sym">{cfg.name}</span>
        <span className={cn('chip fill', long ? 'up' : 'dn')}>{t(long ? 'wb.hold.long' : 'wb.hold.short', { lev: p.leverage })}</span>
        <span className={cn('tr-pc-pnl num', pnl >= 0 ? 'up' : 'dn')}>
          {signed(pnl)}
          <em>{t('wb.hold.ofMargin', { pct: fmtSignedPct(p.margin > 0 ? pnl / p.margin * 100 : 0) })}</em>
        </span>
      </div>
      <div className="tr-pc-q num">{t('wb.hold.qtyAt', { qty: p.quantity, coin: cfg.name, price: px(p.entryPrice) })}</div>
      {sl != null && tp != null && (
        <div className="tr-pc-bar" aria-hidden="true">
          <i className={cn('tr-pc-fill', pn >= pe ? 'up' : 'dn')} style={{ left: `${Math.min(pe, pn)}%`, width: `${Math.abs(pn - pe)}%` }} />
          {!locked && <i className="tr-pc-entry" style={{ left: `${pe}%` }} />}
          <i className="tr-pc-now" style={{ left: `${pn}%` }}>
            {/* 现价贴着两头时，标签往里收，不出条 */}
            <span className="num" style={{ transform: `translateX(${pn > 85 ? 'calc(-100% + 1px)' : pn < 15 ? '-1px' : '-50%'})` }}>
              {t('wb.hold.now', { price: px(mark) })}
            </span>
          </i>
        </div>
      )}
      {/* 多档全列出来，离触发多远按最近那档算 */}
      <div className="tr-pc-ends num">
        {sl != null
          ? <span className="dn">{t('wb.hold.sl', { price: levelsText(p.symbol, p.stopLosses) })}{locked && ` · ${t('wb.hold.locked')}`}<em>{t('wb.hold.trigger', { move: moveText(sl, mark) })}</em></span>
          : <span className="mute">{t('wb.hold.noSl')}</span>}
        {tp != null
          ? <span className="r up">{t('wb.hold.tp', { price: levelsText(p.symbol, p.takeProfits) })}<em>{t('wb.hold.trigger', { move: moveText(tp, mark) })}</em></span>
          : <span className="r mute">{t('wb.hold.noTp')}</span>}
      </div>
      <PlanLines plan={plan} />
    </div>
  );
}

/** 一个没成交的挂单：挂在哪、离现价多远就成交、成交后的止损止盈（取自计划） */
function OrderCard({ o, plan, mark }: { o: FuturesOrder; plan?: AiTraderPlanView; mark?: number }) {
  const { t } = useTranslation('ai');
  const moveText = useMoveText();
  const cfg = getCoin(o.symbol);
  const Icon = cfg.icon;
  const long = o.orderSide.includes('LONG');
  const px = (v: number) => formatCoinPrice(o.symbol, v);
  const sideKey = o.orderSide.startsWith('OPEN')
    ? (long ? 'wb.hold.limitLong' : 'wb.hold.limitShort')
    : (long ? 'wb.hold.limitCloseLong' : 'wb.hold.limitCloseShort');
  return (
    <div className="tr-pc">
      <div className="tr-pc-h">
        <Icon className="coin" />
        <span className="sym">{cfg.name}</span>
        <span className="chip mute">{t(sideKey, { lev: o.leverage })}</span>
        <span className="tr-pc-pnl wait">{t('wb.hold.notFilled')}</span>
      </div>
      <div className="tr-pc-q num">
        {o.limitPrice != null ? t('wb.hold.qtyLimit', { qty: o.quantity, coin: cfg.name, price: px(o.limitPrice) }) : o.quantity}
      </div>
      {o.limitPrice != null && mark != null && (
        <p className="tr-pc-ord num">
          {t('wb.hold.fillIn', { now: px(mark), move: moveText(o.limitPrice, mark) })}
          {plan?.stopLossPrice != null && plan.takeProfitPrice != null
            && ` ${t('wb.hold.afterFill', { sl: px(plan.stopLossPrice), tp: px(plan.takeProfitPrice) })}`}
        </p>
      )}
      <PlanLines plan={plan} />
    </div>
  );
}

/** 工作台右栏「手上的仓位」：持仓 + 挂单，交易计划并进对应那张卡 */
export function Holdings({ positions, orders, plans }: {
  positions: FuturesPosition[]; orders: FuturesOrder[]; plans: AiTraderPlanView[];
}) {
  const { t } = useTranslation('ai');
  const marks = useMarkPrices([...positions.map(p => p.symbol), ...orders.map(o => o.symbol)]);
  const markOf = (p: FuturesPosition) => marks[p.symbol] ?? p.markPrice;
  const floating = positions.reduce((s, p) => s + (p.side === 'LONG' ? markOf(p) - p.entryPrice : p.entryPrice - markOf(p)) * p.quantity, 0);
  // 计划挂在哪张卡：同币同向有持仓就挂持仓卡；没持仓才挂到开仓挂单上；平仓挂单不带计划
  const held = new Set(positions.map(p => `${p.symbol}|${p.side}`));
  const orderPlan = (o: FuturesOrder) => {
    if (!o.orderSide.startsWith('OPEN')) return undefined;
    const side = o.orderSide.includes('LONG') ? 'LONG' : 'SHORT';
    return held.has(`${o.symbol}|${side}`) ? undefined : planFor(plans, o.symbol, side);
  };
  return (
    <div>
      <div className={BLK_H}>
        <h2>{t('wb.hold.title')}<small>{t('wb.hold.positionsN', { count: positions.length })} · {t('wb.hold.ordersN', { count: orders.length })}</small></h2>
        {positions.length > 0 && (
          <b className={cn('num text-[14px] font-bold', floating >= 0 ? 'up' : 'dn')}>{t('wb.hold.floating', { v: signed(floating) })}</b>
        )}
      </div>
      {positions.length === 0 && orders.length === 0
        ? <div className="py-6 text-[14px] mute">{t('wb.hold.flat')}</div>
        : (
          <>
            {positions.map(p => <PositionCard key={p.id} p={p} plan={planFor(plans, p.symbol, p.side)} mark={markOf(p)} />)}
            {orders.map(o => <OrderCard key={o.orderId} o={o} mark={marks[o.symbol]} plan={orderPlan(o)} />)}
          </>
        )}
    </div>
  );
}

/** 离 AT_TIME 还有多久：分钟级，半分钟刷一次就够 */
function useLeftText() {
  const { t } = useTranslation('ai');
  return (ms: number) => {
    if (ms <= 0) return t('wb.left.due');
    const min = Math.ceil(ms / 60_000);
    return min >= 60 ? t('wb.left.hm', { h: Math.floor(min / 60), m: min % 60 }) : t('wb.left.m', { m: min });
  };
}

/** 工作台右栏「它的盯盘」：看价格的按离现价远近排，看时间的排后面 */
export function WatchList({ watches }: { watches: TraderWatch[] }) {
  const { t } = useTranslation('ai');
  const moveText = useMoveText();
  const leftText = useLeftText();
  const marks = useMarkPrices(watches.filter(w => w.level != null).map(w => w.symbol));
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);
  const rows = useMemo(() => {
    const away = (w: TraderWatch) => {
      const mark = marks[w.symbol];
      return w.level != null && mark ? Math.abs(w.level / mark - 1) * 100 : Infinity;
    };
    const price = watches.filter(w => w.kind !== 'AT_TIME').map(w => ({ w, away: away(w) })).sort((a, b) => a.away - b.away);
    const time = watches.filter(w => w.kind === 'AT_TIME').sort((a, b) => (a.atTime ?? 0) - (b.atTime ?? 0)).map(w => ({ w, away: Infinity }));
    return [...price, ...time];
  }, [watches, marks]);

  const cond = (w: TraderWatch) => {
    if (w.kind === 'AT_TIME') return t('wb.watch.at', { time: w.atTime != null ? fmtDateTime(w.atTime) : '—' });
    const price = w.level != null ? formatCoinPrice(w.symbol, w.level) : '—';
    if (w.kind === 'TOUCH_ABOVE') return t('wb.watch.touchAbove', { price });
    if (w.kind === 'TOUCH_BELOW') return t('wb.watch.touchBelow', { price });
    const period = t(`wb.period.${w.period}`);
    return t(w.kind === 'CLOSE_ABOVE' ? 'wb.watch.closeAbove' : 'wb.watch.closeBelow', { period, price });
  };

  return (
    <div>
      <div className={BLK_H}>
        <h2>{t('wb.watch.title')}<small>{t('wb.watch.sum', { n: watches.length })}</small></h2>
      </div>
      <p className="tr-hint -mt-2 mb-1">{t('wb.watch.desc')}</p>
      {rows.length === 0
        ? <div className="py-6 text-[14px] mute">{t('wb.watch.none')}</div>
        : rows.map(({ w, away }) => {
          const cfg = getCoin(w.symbol);
          const Icon = cfg.icon;
          const mark = marks[w.symbol];
          return (
            <div key={w.id} className={cn('tr-al', away < NEAR_PCT && 'near')}>
              <Icon className="coin" />
              <div className="tr-al-b">
                <b className="num">{cfg.name} · {cond(w)}<span className="soon">{t('wb.watch.soon')}</span></b>
                {w.note && <span>{t('wb.watch.note', { v: w.note })}</span>}
              </div>
              <div className="tr-al-r num">
                <b>
                  {w.kind === 'AT_TIME'
                    ? (w.atTime != null ? leftText(w.atTime - now) : '—')
                    : (w.level != null && mark ? moveText(w.level, mark) : '—')}
                </b>
                <span>{t('wb.watch.setAt', { time: fmtDateTime(w.createdWakeTime) })}</span>
              </div>
            </div>
          );
        })}
    </div>
  );
}
