import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Bot, ChevronDown, History } from 'lucide-react';
import { cn, fmtDateTime, fmtDuration, fmtNum, fmtSigned, fmtSignedPct } from '../lib/utils';
import { COIN_MAP, formatCoinPrice } from '../lib/coinConfig';
import { orderSideView, tradeSymbolName } from '../lib/orderSide';
import { Skeleton } from './ui/skeleton';
import { Pager } from './ui/pager';
import { EmptyState } from './EmptyState';
import type { PositionFill, PositionHistoryItem } from '../types';
import './PositionHistoryList.css';

/**
 * 这笔成交是怎么来的。FILLED 是手动下单，不用额外标注；其余三种都是被动触发。
 * 表里存词表 key 不存文案：常量在组件外，存翻译结果会在模块加载那一刻定死，切语言不跟着变。
 */
const FILL_TRIGGER: Record<PositionFill['status'], { key: string; tone: string } | null> = {
  FILLED: null,
  STOP_LOSS: { key: 'history.stopLoss', tone: 'r' },
  TAKE_PROFIT: { key: 'history.takeProfit', tone: 'g' },
  LIQUIDATED: { key: 'history.liquidated', tone: 'r' },
};

/** 资金费净额：后端 fundingFeeTotal 正=付出、负=收到，显示成对钱包的影响（付出 -、收到 +） */
const fundingText = (v: number) => `${v > 0 ? '-' : v < 0 ? '+' : ''}${fmtNum(Math.abs(v))}`;

/** 一笔成交明细。分批平仓就是靠这几行还原出「0.4@110 / 0.6@120」的完整过程 */
function FillRow({ fill, symbol }: { fill: PositionFill; symbol: string }) {
  // 这行的方向标签走 orderSideView（词表在 labels ns），组件自己订一份 t 才会跟着切语言重渲染
  const { t } = useTranslation('portfolio');
  const { label } = orderSideView(fill.orderSide);
  const trigger = FILL_TRIGGER[fill.status];
  return (
    <div className="nf">
      <span className="mute">{fmtDateTime(fill.filledAt)}</span>
      <b>{label}</b>
      {trigger && <span className={cn('tag sm', trigger.tone)}>{t(trigger.key)}</span>}
      <span>{fmtNum(fill.quantity, 4)} @ {formatCoinPrice(symbol, fill.price)}</span>
      <span className="mute">
        {t('history.fee', { value: fmtNum(fill.commission) })}
        {fill.liquidationFee != null && fill.liquidationFee > 0 && ` · ${t('history.liqFee', { value: fmtNum(fill.liquidationFee) })}`}
      </span>
      {/* 开/加仓单没有已实现盈亏，不填 0 冒充"这笔不赚不亏" */}
      {fill.realizedPnl != null && <span className={cn('pn', fill.realizedPnl >= 0 ? 'up' : 'dn')}>{fmtSigned(fill.realizedPnl)}</span>}
    </div>
  );
}

function Row({ item }: { item: PositionHistoryItem }) {
  const { t } = useTranslation('portfolio');
  const [open, setOpen] = useState(false);
  const long = item.side === 'LONG';
  const cfg = COIN_MAP[item.symbol];

  return (
    <>
      <tr className={cn(open && 'open')} onClick={() => setOpen(v => !v)}>
        <td>
          <span className="sym">
            {cfg && <cfg.icon className={cn('ci', cfg.colorClass)} />}
            {tradeSymbolName(item.symbol)}
          </span>
          <span className="tags">
            <span className={cn('tag sm', long ? 'g' : 'r')}>{long ? t('history.long') : t('history.short')} {item.leverage}x</span>
            <span className="tag sm">{item.marginMode === 'CROSS' ? t('history.cross') : t('history.isolated')}</span>
            {item.status === 'LIQUIDATED' && <span className="tag sm r">{t('history.liquidated')}</span>}
            {item.memo && <span className="tag sm g"><Bot className="ic" />{item.memo}</span>}
          </span>
        </td>
        <td className="r nf">{fmtNum(item.closedQty, 4)}</td>
        <td className="r nf">{formatCoinPrice(item.symbol, item.entryPrice)}</td>
        {/* 破产清零那批仓位一单没平过，给"—"不给 0——0 会被读成"平在 0 块钱" */}
        <td className="r nf">{item.closeAvgPrice == null ? <span className="faint">—</span> : formatCoinPrice(item.symbol, item.closeAvgPrice)}</td>
        <td className={cn('r nf pn', item.realizedPnl >= 0 ? 'up' : 'dn')}>{fmtSigned(item.realizedPnl)}</td>
        <td className={cn('r nf', item.roiPct == null ? 'faint' : item.roiPct >= 0 ? 'up' : 'dn')}>
          {item.roiPct == null ? '—' : fmtSignedPct(item.roiPct)}
        </td>
        <td className="r nf mute">{fmtDateTime(item.openedAt)}</td>
        <td className="r nf mute">{fmtDuration(item.openedAt, item.closedAt)}</td>
        {/* 键盘用这颗开合；鼠标点整行 */}
        <td className="chev">
          <button type="button" className="flex" aria-expanded={open} aria-label={open ? t('common:collapse') : t('common:expand')}
                  onClick={e => { e.stopPropagation(); setOpen(v => !v); }}>
            <ChevronDown className="ic" />
          </button>
        </td>
      </tr>
      {open && (
        <tr className="rec-d">
          <td colSpan={9}>
            {/* 盈亏构成：已实现盈亏已经把手续费、强平清算费和资金费减完了，这里摊开是为了看清钱花在哪 */}
            <div className="rec-kv nf">
              <span>{t('history.investedMargin')}<b>{fmtNum(item.investedMargin)}</b></span>
              <span>{t('history.commission')}<b>-{fmtNum(item.commission)}</b></span>
              {item.liquidationFee > 0 && <span>{t('history.liquidationFee')}<b>-{fmtNum(item.liquidationFee)}</b></span>}
              <span>{t('history.fundingFee')}<b>{fundingText(item.fundingFeeTotal)}</b></span>
              <span>{t('history.closedAt')}<b>{fmtDateTime(item.closedAt)}</b></span>
            </div>
            <div className="rec-f">
              {item.fills.length === 0
                ? <div className="mute">{t('history.noFills')}</div>
                : item.fills.map(f => <FillRow key={f.orderId} fill={f} symbol={item.symbol} />)}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/**
 * 仓位历史表（样式见 PositionHistoryList.css）。自己的历史页与排行榜用户详情页共用这一份——
 * 两处只差数据从哪个接口来，行怎么画是同一件事。手机上整表在自己的框里横着滑。
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
      <div className="flex flex-col gap-2">
        {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-[52px]" />)}
      </div>
    );
  }

  if (records.length === 0) {
    return <EmptyState icon={<History />} text={emptyText ?? t('history.empty')} />;
  }

  return (
    <>
      <div className="tw">
        <table className="tbl hov rec-h min-w-[880px]">
          <thead>
            <tr>
              <th>{t('field.contract')}</th>
              <th className="r">{t('field.closedQty')}</th>
              <th className="r">{t('field.entryPrice')}</th>
              <th className="r">{t('field.exitPrice')}</th>
              <th className="r">{t('field.realizedPnl')}</th>
              <th className="r">{t('field.roi')}</th>
              <th className="r">{t('field.openedAt')}</th>
              <th className="r">{t('field.duration')}</th>
              <th className="chev" />
            </tr>
          </thead>
          <tbody>
            {records.map(item => <Row key={item.id} item={item} />)}
          </tbody>
        </table>
      </div>
      <Pager page={page} pages={pages} onChange={onPage} />
    </>
  );
}
