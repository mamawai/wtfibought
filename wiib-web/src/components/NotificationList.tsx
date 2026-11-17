import { useTranslation } from 'react-i18next';
import { AlertTriangle, Loader2, MessageCircle, ShieldAlert, Target, ThumbsUp, Zap, type LucideIcon } from 'lucide-react';
import { cn, fmtNum, fmtRelative } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import {
  describeComment, describeTrade,
  type CommentNotification, type MergedNotification, type TradeNotification,
} from '../lib/notifications';
import type { TradeNotifType } from '../types';

const TRADE_META: Record<TradeNotifType, { icon: LucideIcon; color: string }> = {
  3: { icon: Zap, color: 'text-loss' },                 // 逐仓强平
  4: { icon: ShieldAlert, color: 'text-warning' },      // 止损：警示但不是灾难
  5: { icon: Target, color: 'text-gain' },              // 止盈
  6: { icon: AlertTriangle, color: 'text-loss' },       // 全仓爆仓：最重
};

const COMMENT_META: Record<1 | 2, { icon: LucideIcon; color: string }> = {
  1: { icon: ThumbsUp, color: 'text-primary' },
  2: { icon: MessageCircle, color: 'text-muted-foreground' },
};

/** 一条的前两格：未读点 + 圆底的类型图标（样式见 index.css .nrow） */
function RowHead({ icon: Icon, color }: { icon: LucideIcon; color: string }) {
  return (
    <>
      <i />
      <span className={cn('ib0', color)}><Icon className="ic" /></span>
    </>
  );
}

/** 交易类：不可点击（仓位已平，跳哪儿都看不到它），信息全摊在条目里 */
function TradeRow({ g }: { g: TradeNotification }) {
  const hasPnl = g.pnl != null && Number.isFinite(g.pnl);
  const up = (g.pnl ?? 0) >= 0;
  return (
    <div className={cn('nrow', !g.unread && 'read')}>
      <RowHead {...TRADE_META[g.type]} />
      <div>
        <b>{describeTrade(g)}</b>
        {/* 全仓爆仓跨多币种，没有单一的数量@价格可报 */}
        {g.type !== 6 && g.quantity != null && (
          <span>{g.quantity} @ {formatCoinPrice(g.symbol ?? undefined, g.price)}</span>
        )}
        <div className="ln">
          <strong className={up ? 'up' : 'dn'}>{hasPnl ? `${up ? '+' : ''}${fmtNum(g.pnl, 2)}` : '-'}</strong>
          <span>{fmtRelative(g.latestAt)}</span>
        </div>
      </div>
    </div>
  );
}

/** 评论类：可点击，跳到留言板并聚焦那条评论 */
function CommentRow({ g, onSelect }: { g: CommentNotification; onSelect: (commentId: number) => void }) {
  return (
    <button type="button" onClick={() => onSelect(g.commentId)} className={cn('nrow', !g.unread && 'read')}>
      <RowHead {...COMMENT_META[g.type]} />
      <div>
        <b>{describeComment(g)}</b>
        <span>{fmtRelative(g.latestAt)}</span>
      </div>
    </button>
  );
}

/**
 * 通知列表的呈现层。PC 顶栏信封的下拉面板和「我的」页的通知卡共用同一份，
 * 免得两处各写一遍、日后各自跑偏。容器高度由调用方给（下拉面板要限高，卡片可放开）。
 * <p>这里订的这份 t 也是整棵子树切语言的开关：下面两种行的文案（describeTrade /
 * describeComment / fmtRelative）都走 i18n 实例现查，靠本组件重渲染带着它们一起更新。
 */
export function NotificationList({ items, loading, onSelect, className }: {
  items: MergedNotification[];
  loading: boolean;
  onSelect: (commentId: number) => void;
  className?: string;
}) {
  const { t } = useTranslation('account');

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-[13px] text-muted-foreground">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> {t('notif.loading')}
      </div>
    );
  }

  if (items.length === 0) {
    return <div className="py-10 text-center text-[13px] text-muted-foreground">{t('notif.empty')}</div>;
  }

  return (
    <div className={cn('overflow-y-auto', className)}>
      {items.map(g => g.kind === 'trade'
        ? <TradeRow key={g.key} g={g} />
        : <CommentRow key={g.key} g={g} onSelect={onSelect} />)}
    </div>
  );
}
