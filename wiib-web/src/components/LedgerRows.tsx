import { Fragment, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from '../i18n';
import { fmtNum, fmtTime, parseServerTime } from '../lib/utils';
import type { LedgerEntry, LedgerWallet } from '../types';
import './LedgerRows.css';

/**
 * 钱包名的词表 key。这份映射刻意留在前端——后端只下发枚举名，
 * 不像 bizType 那样平铺 label（见 LedgerControllerTest：wallet 没有 label 也不需要一份）。
 */
const WALLET_LABEL: Record<LedgerWallet, string> = {
  BALANCE: 'wallet.balance',
  FROZEN: 'wallet.frozen',
  GAME: 'wallet.game',
  LOAN_PRINCIPAL: 'wallet.loanPrincipal',
  LOAN_INTEREST: 'wallet.loanInterest',
  POSITION_MARGIN: 'wallet.positionMargin',
};

/**
 * 借款本金/应计利息这两个钱包，数字变大 = 欠得更多。
 * 一律按"正数标绿"会把"又借了 5000"画成好事，所以这两个钱包反过来：减少才标绿。
 */
const DEBT_WALLETS: LedgerWallet[] = ['LOAN_PRINCIPAL', 'LOAN_INTEREST'];

/** 变动额颜色：进账标绿，花出去的保持正文色不标红 */
function deltaTone(entry: LedgerEntry): string | undefined {
  if (entry.delta === 0) return 'mute';
  const good = DEBT_WALLETS.includes(entry.wallet) ? entry.delta < 0 : entry.delta > 0;
  return good ? 'up' : undefined;
}

/** 分组日期键。必须跟 fmtTime 同一时区（新加坡），否则跨零点的行会被分进错误的日期组 */
function dayKey(ts: string): string {
  return parseServerTime(ts).toLocaleDateString('zh-CN', {
    timeZone: 'Asia/Singapore', year: 'numeric', month: '2-digit', day: '2-digit',
  });
}

function dayLabel(key: string): string {
  const today = dayKey(new Date().toISOString());
  const yesterday = dayKey(new Date(Date.now() - 86400_000).toISOString());
  // 词表必须在函数体里现查：存成模块级常量的话切语言后不会变
  if (key === today) return i18n.t('portfolio:ledger.today');
  if (key === yesterday) return i18n.t('portfolio:ledger.yesterday');
  return key;
}

/** 连续同一天的行归一组，保持后端给的 id 倒序，不重排 */
function groupByDay(entries: LedgerEntry[]): { key: string; rows: LedgerEntry[] }[] {
  const groups: { key: string; rows: LedgerEntry[] }[] = [];
  for (const e of entries) {
    const key = dayKey(e.createdAt);
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.rows.push(e);
    else groups.push({ key, rows: [e] });
  }
  return groups;
}

/** 一条流水：时刻 | 类型 + 钱包 + 币 + 手续费/备注 | 变动额和变动后余额 */
function EntryRow({ entry }: { entry: LedgerEntry }) {
  const { t } = useTranslation('portfolio');
  return (
    <div className="le2">
      <span className="tm nf">{fmtTime(entry.createdAt)}</span>
      <span className="nm">
        {/* 类型文案走前端词表，认不出的新枚举兜底显示后端下发的 label */}
        {t('biz.' + entry.bizType, { defaultValue: entry.bizTypeLabel })}
        <span className="tag sm">{t(WALLET_LABEL[entry.wallet])}</span>
        {entry.symbol && <span className="tag sm">{entry.symbol.replace('USDT', '')}</span>}
        {entry.fee != null && <small className="nf">{t('ledger.fee', { value: fmtNum(entry.fee) })}</small>}
        {entry.remark && <small>{entry.remark}</small>}
      </span>
      <span className="am nf">
        <b className={deltaTone(entry)}>{entry.delta > 0 ? '+' : ''}{fmtNum(entry.delta)}</b>
        <span>{t('ledger.balanceAfter', { value: fmtNum(entry.balanceAfter) })}</span>
      </span>
    </div>
  );
}

/** 资金流水列表：按天分组，一天一个日期头，下面一行一条（账单页和 AI Trader 的资金账单页签共用） */
export function LedgerRows({ entries }: { entries: LedgerEntry[] }) {
  const { t } = useTranslation('portfolio');
  const dayGroups = useMemo(() => groupByDay(entries), [entries]);
  return (
    <div className="lgr">
      {dayGroups.map(g => (
        <Fragment key={g.key}>
          <div className="day">{dayLabel(g.key)}<small className="nf">{t('ledger.dayCount', { count: g.rows.length })}</small></div>
          {g.rows.map(e => <EntryRow key={e.id} entry={e} />)}
        </Fragment>
      ))}
    </div>
  );
}
