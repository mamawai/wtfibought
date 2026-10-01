import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Bot, ChevronDown, ChevronLeft, ChevronRight, History } from 'lucide-react';
import { cn, fmtDateTime, fmtDuration, fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { orderSideView, tradeSymbolName } from '../lib/orderSide';
import { Skeleton } from './ui/skeleton';
import { Button } from './ui/button';
import { EmptyState } from './EmptyState';
import type { PositionFill, PositionHistoryItem } from '../types';

/**
 * 列宽模板。表头和数据行必须共用同一份，各写各的迟早错位。
 * 窄屏退化成两列带标签的格子（表头整条隐藏），宽屏才是九列表格。
 */
const GRID = 'grid grid-cols-2 gap-x-3 gap-y-2 md:gap-y-0 md:items-center md:grid-cols-[minmax(9rem,1.3fr)_minmax(0,.85fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,.7fr)_minmax(0,.95fr)_minmax(0,.7fr)_1rem]';

/**
 * 这笔成交是怎么来的。FILLED 是手动下单，不用额外标注；其余三种都是被动触发。
 * 表里存词表 key 不存文案：常量在组件外，存翻译结果会在模块加载那一刻定死，切语言不跟着变。
 */
const FILL_TRIGGER: Record<PositionFill['status'], string | null> = {
  FILLED: null,
  STOP_LOSS: 'history.stopLoss',
  TAKE_PROFIT: 'history.takeProfit',
  LIQUIDATED: 'history.liquidated',
};

/** 窄屏每个格子自带微标签（表头看不见了），宽屏交给表头 */
function Cell({ label, className, children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <div className={cn('min-w-0', className)}>
      <div className="microlabel md:hidden mb-0.5">{label}</div>
      {children}
    </div>
  );
}

function Pnl({ value, className }: { value: number; className?: string }) {
  const up = value >= 0;
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss', className)}>
      {up ? '+' : ''}{fmtNum(value)}
    </span>
  );
}

/** 资金费净额：后端 fundingFeeTotal 正=付出、负=收到，显示成对钱包的影响（付出 -、收到 +） */
function FundingFee({ value }: { value: number }) {
  return (
    <span className={cn('num', value > 0 ? 'text-loss' : value < 0 ? 'text-gain' : 'text-foreground')}>
      {value > 0 ? '-' : value < 0 ? '+' : ''}{fmtNum(Math.abs(value))}
    </span>
  );
}

/** 一笔成交明细。分批平仓就是靠这几行还原出「0.4@110 / 0.6@120」的完整过程 */
function FillRow({ fill, symbol }: { fill: PositionFill; symbol: string }) {
  // 这行的方向标签走 orderSideView（词表在 labels ns），组件自己订一份 t 才会跟着切语言重渲染
  const { t } = useTranslation('portfolio');
  const { label, tone } = orderSideView(fill.orderSide);
  const triggerKey = FILL_TRIGGER[fill.status];
  return (
    <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 px-3 py-2 border-b border-border/20 last:border-b-0">
      <span className="num text-[10px] text-muted-foreground w-[5.5rem] shrink-0">
        {fmtDateTime(fill.filledAt)}
      </span>
      <span className={cn(
        'text-[10px] font-bold px-1.5 py-0.5 rounded shrink-0',
        tone === 'buy' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
      )}>
        {label}
      </span>
      {triggerKey && (
        <span className="text-[10px] px-1.5 py-0.5 rounded bg-warning/10 text-warning font-medium shrink-0">
          {t(triggerKey)}
        </span>
      )}
      <span className="num text-[11px]">
        {fmtNum(fill.quantity, 4)}
        <span className="text-muted-foreground mx-1">@</span>
        {formatCoinPrice(symbol, fill.price)}
      </span>
      <span className="num text-[10px] text-muted-foreground ml-auto shrink-0">
        {t('history.fee', { value: fmtNum(fill.commission) })}
      </span>
      {/* 开/加仓单没有已实现盈亏，留空位对齐，不填 0 冒充"这笔不赚不亏" */}
      <span className="num text-[11px] font-semibold w-20 text-right shrink-0">
        {fill.realizedPnl == null
          ? <span className="text-muted-foreground/50">—</span>
          : <Pnl value={fill.realizedPnl} />}
      </span>
    </div>
  );
}

function Row({ item }: { item: PositionHistoryItem }) {
  const { t } = useTranslation('portfolio');
  const [open, setOpen] = useState(false);
  const long = item.side === 'LONG';
  const liquidated = item.status === 'LIQUIDATED';

  return (
    <div className="border-b border-border/25 last:border-b-0">
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        className={cn(GRID, 'w-full text-left px-3 sm:px-4 py-3 hover:bg-accent/25 transition-colors')}
      >
        {/* 合约：窄屏独占一行 */}
        <div className="col-span-2 md:col-span-1 min-w-0">
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-[13px] font-bold truncate">{tradeSymbolName(item.symbol)}</span>
            <span className={cn(
              'text-[10px] font-bold px-1.5 py-0.5 rounded',
              long ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
            )}>
              {long ? t('history.long') : t('history.short')}
            </span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-secondary text-muted-foreground">
              {item.marginMode === 'CROSS' ? t('history.cross') : t('history.isolated')} {item.leverage}x
            </span>
            {liquidated && (
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-loss/15 text-loss">{t('history.liquidated')}</span>
            )}
            {item.memo && (
              <span className="inline-flex items-center gap-0.5 text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary">
                <Bot className="w-3 h-3" />{item.memo}
              </span>
            )}
          </div>
        </div>

        <Cell label={t('field.closedQty')} className="md:text-right">
          <span className="num text-[12px]">{fmtNum(item.closedQty, 4)}</span>
        </Cell>

        <Cell label={t('field.entryPrice')} className="md:text-right">
          <span className="num text-[12px]">{formatCoinPrice(item.symbol, item.entryPrice)}</span>
        </Cell>

        <Cell label={t('field.exitPrice')} className="md:text-right">
          {/* 破产清零那批仓位一单没平过，给"—"不给 0——0 会被读成"平在 0 块钱" */}
          <span className="num text-[12px]">
            {item.closeAvgPrice == null
              ? <span className="text-muted-foreground/50">—</span>
              : formatCoinPrice(item.symbol, item.closeAvgPrice)}
          </span>
        </Cell>

        <Cell label={t('field.realizedPnl')} className="md:text-right">
          <Pnl value={item.realizedPnl} className="text-[13px] font-bold" />
        </Cell>

        <Cell label={t('field.roi')} className="md:text-right">
          {item.roiPct == null ? (
            <span className="num text-[12px] text-muted-foreground/50">—</span>
          ) : (
            <span className={cn('num text-[12px] font-semibold', item.roiPct >= 0 ? 'text-gain' : 'text-loss')}>
              {item.roiPct >= 0 ? '+' : ''}{item.roiPct.toFixed(2)}%
            </span>
          )}
        </Cell>

        <Cell label={t('field.openedAt')} className="md:text-right">
          <span className="num text-[11px] text-muted-foreground">{fmtDateTime(item.openedAt)}</span>
        </Cell>

        <Cell label={t('field.duration')} className="md:text-right">
          <span className="num text-[11px]">{fmtDuration(item.openedAt, item.closedAt)}</span>
        </Cell>

        <div className="hidden md:flex justify-end">
          <ChevronDown className={cn('w-3.5 h-3.5 text-muted-foreground/50 transition-transform', open && 'rotate-180')} />
        </div>
        {/* 窄屏箭头跟着"持仓时间"那格走，不占独立一格 */}
        <div className="col-span-2 md:hidden flex justify-center pt-1">
          <ChevronDown className={cn('w-3.5 h-3.5 text-muted-foreground/40 transition-transform', open && 'rotate-180')} />
        </div>
      </button>

      {open && (
        <div className="bg-card-2 border-t border-border/25">
          {item.fills.length === 0 ? (
            <div className="px-4 py-3 text-[11px] text-muted-foreground">
              {t('history.noFills')}
            </div>
          ) : (
            item.fills.map(f => <FillRow key={f.orderId} fill={f} symbol={item.symbol} />)
          )}

          {/* 盈亏构成：已实现盈亏已经把手续费和资金费减完了，这里摊开是为了看清钱花在哪 */}
          <div className="flex flex-wrap gap-x-5 gap-y-1 px-3 py-2.5 border-t border-border/30">
            <span className="text-[10px] text-muted-foreground">
              {t('history.investedMargin')} <span className="num text-foreground">{fmtNum(item.investedMargin)}</span>
            </span>
            <span className="text-[10px] text-muted-foreground">
              {t('history.commission')} <span className="num text-loss">-{fmtNum(item.commission)}</span>
            </span>
            <span className="text-[10px] text-muted-foreground">
              {t('history.fundingFee')} <FundingFee value={item.fundingFeeTotal} />
            </span>
            <span className="text-[10px] text-muted-foreground">
              {t('history.closedAt')} <span className="num text-foreground">{fmtDateTime(item.closedAt)}</span>
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 仓位历史列表。自己的历史页与排行榜用户详情页共用这一份——
 * 两处只差数据从哪个接口来，行怎么画是同一件事。
 */
export function PositionHistoryList({ records, page, pages, loading, onPage, emptyText }: {
  records: PositionHistoryItem[];
  page: number;
  pages: number;
  loading: boolean;
  onPage: (p: number) => void;
  /** 调用方自带的空态文案（已翻译好的字符串）；不传就用本域的默认说法 */
  emptyText?: string;
}) {
  const { t } = useTranslation('portfolio');

  if (loading) {
    return (
      <div className="p-4 space-y-2.5">
        {[...Array(6)].map((_, i) => <Skeleton key={i} className="h-12 w-full rounded-lg" />)}
      </div>
    );
  }

  if (records.length === 0) {
    return <EmptyState icon={<History />} text={emptyText ?? t('history.empty')} />;
  }

  return (
    <>
      {/* 表头只在宽屏出现，窄屏每格自带微标签 */}
      <div className={cn(GRID, 'hidden md:grid px-4 py-2 bg-card-2 border-b border-border/30')}>
        <span className="microlabel font-bold">{t('field.contract')}</span>
        <span className="microlabel font-bold text-right">{t('field.closedQty')}</span>
        <span className="microlabel font-bold text-right">{t('field.entryPrice')}</span>
        <span className="microlabel font-bold text-right">{t('field.exitPrice')}</span>
        <span className="microlabel font-bold text-right">{t('field.realizedPnl')}</span>
        <span className="microlabel font-bold text-right">{t('field.roi')}</span>
        <span className="microlabel font-bold text-right">{t('field.openedAt')}</span>
        <span className="microlabel font-bold text-right">{t('field.duration')}</span>
        <span />
      </div>

      {records.map(item => <Row key={item.id} item={item} />)}

      {pages > 1 && (
        <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
          <span className="text-xs text-muted-foreground">{t('pager.page', { page, pages })}</span>
          <div className="flex items-center gap-1">
            <Button variant="ghost" size="sm" className="h-8 w-8 p-0" disabled={page <= 1} onClick={() => onPage(page - 1)}>
              <ChevronLeft className="w-4 h-4" />
            </Button>
            <Button variant="ghost" size="sm" className="h-8 w-8 p-0" disabled={page >= pages} onClick={() => onPage(page + 1)}>
              <ChevronRight className="w-4 h-4" />
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
