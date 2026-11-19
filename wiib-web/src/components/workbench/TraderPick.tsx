import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Loader2, Timer, Users, X } from 'lucide-react';
import { cn, fmtSignedPct } from '../../lib/utils';
import { STATUS_META } from '../arena/traderStatus';
import type { ChatItem } from './chatStore';

/** 选择里的一行：detail 是名字下面那行说明；blocked 非空就点不了，显示拦因 */
export interface ChooserRow {
  id: number;
  name: string;
  status: string;
  detail?: ReactNode;
  blocked?: string | null;
  /** 这行的状态还在取 */
  loading?: boolean;
}

/** 名字比对：去掉首尾空格、不分大小写，跟后端点名同一口径 */
const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

const secondsUntil = (deadline: number) => Math.max(0, Math.ceil((deadline - Date.now()) / 1000));

/** 离截止还剩几秒，每秒刷一次 */
function useSecondsLeft(deadline: number): number {
  const [left, setLeft] = useState(() => secondsUntil(deadline));
  useEffect(() => {
    const id = window.setInterval(() => setLeft(secondsUntil(deadline)), 1000);
    return () => window.clearInterval(id);
  }, [deadline]);
  return left;
}

/**
 * 选 trader（样式见 ChatMessages.css .cpick）：一行一个，右上角倒计时，到点全部点不了。
 * AI 点了名的那行描一圈，仍要用户点；被拦的那行显示拦因、点不了。
 */
export function TraderChooser({ title, rows, deadline, highlight, busyId, onPick, onClose }: {
  /** 不给就只有倒计时 */
  title?: string;
  rows: ChooserRow[];
  deadline: number;
  /** AI 点了名的那个 */
  highlight?: string;
  /** 点了这行、还在等回音 */
  busyId?: number | null;
  onPick: (id: number) => void;
  /** 不给就没有「收起」 */
  onClose?: () => void;
}) {
  const { t } = useTranslation('ai');
  const left = useSecondsLeft(deadline);
  return (
    <div className="cpick">
      <div className="hd">
        {title && <b>{title}</b>}
        <span className="ck"><Timer className="ic" />{left > 0 ? t('pick.left', { count: left }) : t('pick.timeUp')}</span>
        {onClose && <button type="button" className="linkb" onClick={onClose}>{t('pick.collapse')}</button>}
      </div>
      <div className="rows">
        {rows.map(r => {
          const named = highlight != null && sameName(r.name, highlight);
          const s = STATUS_META[r.status] ?? STATUS_META.PAUSED;
          return (
            <button key={r.id} type="button" className={cn('row', named && 'hl')}
                    disabled={left === 0 || busyId != null || r.loading || !!r.blocked} onClick={() => onPick(r.id)}>
              <span className="nm">
                <b>{r.name}</b>
                <span className={cn('tag sm', s.tone)}>{t(s.labelKey)}</span>
                {named && <span className="tag sm o">{t('pick.aiNamed')}</span>}
                {busyId === r.id && <Loader2 className="ic animate-spin" />}
              </span>
              <span className={cn('dt', r.blocked && 'bad')}>{r.loading ? t('card.loading') : (r.blocked || r.detail)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * 对话里选 trader 的卡：查询工具定不到 trader，后端停在这一轮里等用户点。
 * 选了这一轮接着往下跑；到时间没选、或点了停止，这一轮就结束了。
 */
export function TraderPickCard({ item, onPick }: {
  item: Extract<ChatItem, { kind: 'pick' }>;
  onPick: (id: number) => Promise<void>;
}) {
  const { t } = useTranslation('ai');
  const [busyId, setBusyId] = useState<number | null>(null);
  const pick = (id: number) => {
    setBusyId(id);
    void onPick(id).finally(() => setBusyId(null));
  };
  const picked = item.traders.find(x => x.id === item.pickedId);
  return (
    <div className="ccard">
      <h4><Users className="ic" />{t('pick.title')}</h4>
      <p>{t('pick.hint')}</p>
      {item.status === 'pending' ? (
        <TraderChooser
          rows={item.traders.map(x => ({
            id: x.id, name: x.name, status: x.status,
            detail: <span className={x.pnlPct >= 0 ? 'up' : 'dn'}>{t('pick.roundPnl', { pnl: fmtSignedPct(x.pnlPct) })}</span>,
          }))}
          deadline={item.deadline}
          busyId={busyId}
          onPick={pick}
        />
      ) : (
        <div className="done">
          {item.status === 'picked' ? <Check className="ic" /> : <X className="ic" />}
          {item.status === 'picked' ? t('pick.picked', { name: picked?.name }) : t(`pick.${item.status}`)}
        </div>
      )}
      {item.error && <p className="err">{item.error}</p>}
    </div>
  );
}
