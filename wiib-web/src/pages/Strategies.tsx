import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight, CloudOff, RefreshCw } from 'lucide-react';
import { strategyAccountApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from '../components/ui/use-toast';
import { Seg } from '../components/ui/seg';
import { SureButton } from '../components/SureButton';
import { cn, fmtDateTime, fmtNum, spinIcon } from '../lib/utils';
import { COIN_MAP } from '../lib/coinConfig';
import { tradeSymbolName } from '../lib/orderSide';
import type { FuturesPosition, StrategyAccountView, StrategyClosedPosition, StrategySignalState } from '../types';
import { strategyDisplay } from '../lib/strategyCatalog';
import './Strategies.css';

const ADMIN_USER_ID = 1;
const REFRESH_MS = 60_000;
const TRADE_PAGE_SIZE = 8;

/** 带号盈亏，涨绿跌红；没有值给横杠 */
function Pnl({ value }: { value: number | null | undefined }) {
  if (value == null) return <>—</>;
  return <span className={value >= 0 ? 'up' : 'dn'}>{value >= 0 ? '+' : ''}{fmtNum(value)}</span>;
}

/** 认不出的策略 id 直接把 id 当名字显示（后端新增策略时不至于空白） */
function useStrategyName() {
  const { t } = useTranslation('strategy');
  return (id: string) => {
    const meta = strategyDisplay(id);
    return { name: meta ? t(meta.nameKey) : id, desc: meta ? t(meta.descKey) : '' };
  };
}

/** 收益曲线：累计已实现盈亏从 0 起，一根线 + 0 轴虚线；收着盈利绿、亏损红 */
function Curve({ points }: { points: number[] }) {
  const mn = Math.min(0, ...points);
  const mx = Math.max(0, ...points);
  const y = (v: number) => (92 - (v - mn) / ((mx - mn) || 1) * 88).toFixed(1);
  const x = (i: number) => (i / Math.max(1, points.length - 1) * 300).toFixed(1);
  const up = points[points.length - 1] >= 0;
  return (
    <div className="cv">
      <svg viewBox="0 0 300 96" preserveAspectRatio="none" aria-hidden>
        <line className="bl" x1="0" x2="300" y1={y(0)} y2={y(0)} />
        <polyline className="ln" style={{ stroke: up ? 'var(--color-gain)' : 'var(--color-loss)' }}
                  points={points.map((v, i) => `${x(i)},${y(v)}`).join(' ')} />
      </svg>
    </div>
  );
}

/** 持仓块：策略当前敞口 + SL/TP + 浮盈；管理员多一颗平仓钮（再点一次才市价平） */
function PositionBlock({ pos, canClose, closing, onClose }: {
  pos: FuturesPosition;
  canClose: boolean;
  closing: boolean;
  onClose: (pos: FuturesPosition) => void;
}) {
  const { t } = useTranslation('strategy');
  const isLong = pos.side === 'LONG';
  const sl = pos.stopLosses?.[0]?.price;
  const tp = pos.takeProfits?.[0]?.price;
  const coin = COIN_MAP[pos.symbol];
  return (
    <div className="blk ps">
      <div className="h">
        {coin && <coin.icon className={cn('ci', coin.colorClass)} />}
        {tradeSymbolName(pos.symbol)}
        <span className={cn('tag sm', isLong ? 'g' : 'r')}>{isLong ? t('side.long') : t('side.short')} {pos.leverage}x</span>
        <span className="pn num">
          <Pnl value={pos.unrealizedPnl} />
          {pos.unrealizedPnlPct != null && (
            <small className={pos.unrealizedPnl >= 0 ? 'up' : 'dn'}>({pos.unrealizedPnl >= 0 ? '+' : ''}{pos.unrealizedPnlPct.toFixed(1)}%)</small>
          )}
        </span>
      </div>
      <div className="gm num">
        <span>{t('strategies.pos.qty')}<b>{pos.quantity}</b></span>
        <span>{t('strategies.pos.entry')}<b>{fmtNum(pos.entryPrice)}</b></span>
        <span>{t('strategies.pos.mark')}<b>{fmtNum(pos.markPrice)}</b></span>
        <span>{t('strategies.pos.margin')}<b>{fmtNum(pos.margin)}</b></span>
        {sl != null && <span>SL<b className="dn">{fmtNum(sl)}</b></span>}
        {tp != null && <span>TP<b className="up">{fmtNum(tp)}</b></span>}
      </div>
      <small className="num">{fmtDateTime(pos.createdAt)}</small>
      {canClose && (
        <SureButton className={cn('btn sm', closing && 'busy')} disabled={closing} sureLabel={t('strategies.pos.closeSure')}
                    onConfirm={() => onClose(pos)}>
          {t('strategies.pos.close')}
        </SureButton>
      )}
    </div>
  );
}

function TradeRow({ t: tr }: { t: StrategyClosedPosition }) {
  const { t } = useTranslation('strategy');
  const isLong = tr.side === 'LONG';
  return (
    <div className="tr num">
      <span className={cn('tag sm', isLong ? 'g' : 'r')}>{isLong ? t('side.long') : t('side.short')}</span>
      <span className="p">{tr.symbol.replace('USDT', '')} · {fmtNum(tr.entryPrice)} → {fmtNum(tr.closedPrice)}</span>
      <span className="pn"><Pnl value={tr.closedPnl} /></span>
      <small>{fmtDateTime(tr.updatedAt)}{tr.status === 'LIQUIDATED' && ` · ${t('strategies.trades.liquidated')}`}</small>
    </div>
  );
}

/** 单策略账户栏：名字 → 四个数 → 实时信号 → 收益曲线 → 持仓 → 交易记录（分页）。on=窄屏下当前选中的那套 */
function StrategyColumn({ view, signals, on, canClose, closingId, onClose }: {
  view: StrategyAccountView;
  signals: StrategySignalState[];
  on: boolean;
  canClose: boolean;
  closingId: number | null;
  onClose: (strategyId: string, pos: FuturesPosition) => void;
}) {
  const { t } = useTranslation('strategy');
  const { name, desc } = useStrategyName()(view.strategyId);
  const [tradePage, setTradePage] = useState(0);

  // 收益曲线：已平仓 closedPnl 按平仓时间升序累加（testnet 页同口径，从 0 起）
  const curve = useMemo(() => [...view.closedPositions].reverse()
    .reduce<number[]>((acc, p) => [...acc, acc[acc.length - 1] + (p.closedPnl ?? 0)], [0]), [view.closedPositions]);

  // 前端分页：overview 一次带全（≤200笔），翻页纯内存；60s 刷新后条数变化时钳住页码
  const pageCount = Math.max(1, Math.ceil(view.closedPositions.length / TRADE_PAGE_SIZE));
  const page = Math.min(tradePage, pageCount - 1);
  const trades = view.closedPositions.slice(page * TRADE_PAGE_SIZE, (page + 1) * TRADE_PAGE_SIZE);

  return (
    <div className={cn('colx', on && 'on')}>
      <div className="nmh">
        <b><i className={cn(!view.available && 'off')} />{name}{!view.available && <span className="tag sm">{t('strategies.offline')}</span>}</b>
        <span>{desc} · quant-{view.strategyId}</span>
      </div>

      {!view.available ? (
        <div className="empty left">
          <span className="ib0"><CloudOff className="ic" /></span>
          <b>{t('strategies.unavailable.title')}</b>
          <span>{t('strategies.unavailable.hint')}</span>
        </div>
      ) : (
        <>
          <div className="st4 num">
            <div className="eq">
              <b>{view.equity == null ? '—' : `₮${fmtNum(view.equity)}`}</b>
              <span>{t('strategies.stats.equity', { available: fmtNum(view.balance, 0) })}</span>
            </div>
            <div><b><Pnl value={view.cumPnl} /></b><span>{t('strategies.stats.realized', { count: view.tradeCount })}</span></div>
            <div>
              <b>{view.tradeCount > 0 ? `${(view.winRate * 100).toFixed(1)}%` : '—'}</b>
              <span>{t('strategies.stats.winRate', { wins: view.winCount, total: view.tradeCount })}</span>
            </div>
            <div>
              <b>{view.positions.length > 0 ? <Pnl value={view.unrealizedPnl} /> : '—'}</b>
              <span>{t('strategies.stats.unrealized', { count: view.positions.length })}</span>
            </div>
          </div>

          {/* 实时信号状态：每根5m收盘随策略评估更新，证明策略在跑 */}
          <div>
            <h3>{t('strategies.signals.title')}</h3>
            {signals.length === 0 ? (
              <p className="none">{t('strategies.signals.empty')} · {t('strategies.signals.emptyHint')}</p>
            ) : signals.map(sig => (
              <div key={sig.symbol} className="blk sig">
                {/* 同一套策略盯着好几个币时，前面标上是哪个 */}
                <b>{signals.length > 1 && `${sig.symbol.replace('USDT', '')} · `}{t(`strategies.signals.state.${sig.state.key}`, sig.state.vars)}</b>
                <div className="kv num">
                  {sig.metrics.map(m => (
                    <div key={m.key}>
                      <span>{t(`strategies.signals.metric.${m.key}.label`, m.vars)}</span>
                      <b>{t(`strategies.signals.metric.${m.key}.value`, m.vars)}</b>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>

          <div>
            <h3>{t('strategies.equity.title')}<small>{t('strategies.equity.sub')}</small></h3>
            {view.closedPositions.length > 0
              ? <Curve points={curve} />
              : <p className="none">{t('strategies.equity.empty')} · {t('strategies.equity.emptyHint')}</p>}
          </div>

          <div>
            <h3>{t('strategies.positions.title')}</h3>
            {view.positions.length === 0 ? (
              <p className="none">{t('strategies.positions.empty')}</p>
            ) : view.positions.map(p => (
              <PositionBlock key={p.id} pos={p} canClose={canClose} closing={closingId === p.id}
                             onClose={pos => onClose(view.strategyId, pos)} />
            ))}
          </div>

          <div>
            <h3>{t('strategies.trades.title')}</h3>
            {view.closedPositions.length === 0 ? (
              <p className="none">{t('strategies.trades.empty')}</p>
            ) : (
              <>
                {trades.map(tr => <TradeRow key={tr.id} t={tr} />)}
                {pageCount > 1 && (
                  <div className="pgn">
                    <button type="button" className="ib sm" disabled={page === 0} onClick={() => setTradePage(page - 1)} aria-label={t('pager.prev')}>
                      <ChevronLeft className="ic" />
                    </button>
                    <span className="num">{t('strategies.trades.pageInfo', { page: page + 1, pages: pageCount, total: view.closedPositions.length })}</span>
                    <button type="button" className="ib sm" disabled={page >= pageCount - 1} onClick={() => setTradePage(page + 1)} aria-label={t('pager.next')}>
                      <ChevronRight className="ic" />
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}

/**
 * 多策略账户监控：FIBO / TURTLE / SQZMOM 各绑独立模拟盘账户（盈亏归因互不污染）。
 * 宽屏几套并排对比，窄屏段选一次看一套。平仓按钮仅 userId=1 渲染，后端二次校验才是真正的门。
 */
export function Strategies() {
  const { t } = useTranslation(['strategy', 'common']);
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const isAdmin = user?.id === ADMIN_USER_ID;
  const strategyName = useStrategyName();

  const [views, setViews] = useState<StrategyAccountView[]>([]);
  const [signals, setSignals] = useState<StrategySignalState[]>([]);
  const [loading, setLoading] = useState(false);
  const [closingId, setClosingId] = useState<number | null>(null);
  // 窄屏当前看哪套；没选过就是第一套
  const [picked, setPicked] = useState<string | null>(null);
  const cur = picked ?? views[0]?.strategyId;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // 信号快照失败不拖垮账户全景（quant 运行时可能未启用）
      const [overview, sigs] = await Promise.all([
        strategyAccountApi.overview(),
        strategyAccountApi.signals().catch(() => [] as StrategySignalState[]),
      ]);
      setViews(overview);
      setSignals(sigs);
    } catch (e) {
      toast((e as Error).message || t('strategies.toast.loadFailed'), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast, t]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  // 确认后市价整仓平（服务端实时取仓位数量+userId==1 校验）
  const handleClose = useCallback(async (strategyId: string, pos: FuturesPosition) => {
    if (closingId != null) return;
    setClosingId(pos.id);
    try {
      await strategyAccountApi.close(strategyId, pos.id);
      toast(t('strategies.toast.closed'), 'success');
      void load();
    } catch (e) {
      toast((e as Error).message || t('strategies.toast.closeFailed'), 'error');
    } finally {
      setClosingId(null);
    }
  }, [closingId, load, toast, t]);

  return (
    <div className="page-shell p-sg">
      <div className="pg-h">
        <div>
          <h1>{t('strategies.title')}</h1>
          <p className="sub">{t('strategies.subtitle')}</p>
        </div>
        <div className="r">
          <button type="button" className="ib" title={t('common:refresh')} aria-label={t('common:refresh')}
                  onClick={e => { spinIcon(e); void load(); }}>
            <RefreshCw className="ic" />
          </button>
        </div>
      </div>

      {loading && views.length === 0 ? (
        <div className="empty">{t('strategies.loading')}</div>
      ) : (
        <>
          {cur && (
            <Seg className="pick" value={cur} onChange={setPicked} label={t('strategies.title')}
                 options={views.map(v => ({ value: v.strategyId, label: strategyName(v.strategyId).name }))} />
          )}
          <div className="cols">
            {views.map(v => (
              <StrategyColumn
                key={v.strategyId}
                view={v}
                signals={signals.filter(s => s.strategyId === v.strategyId)}
                on={v.strategyId === cur}
                canClose={isAdmin}
                closingId={closingId}
                onClose={handleClose}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
