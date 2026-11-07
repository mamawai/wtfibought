import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowRight, BookOpen, Loader2, Pin, RefreshCw } from 'lucide-react';
import { traderApi } from '../../api';
import { useToast } from '../ui/use-toast';
import { cn } from '../../lib/utils';
import type { TraderExperience } from '../../types';

type Tab = 'active' | 'findings' | 'retired';

/** 说明面板收起后记住，下次进来不再自动展开 */
const HELP_KEY = 'wiib-ledger-help-closed';

function inTab(e: TraderExperience, tab: Tab): boolean {
  if (tab === 'retired') return e.status === 'RETIRED';
  return e.status === 'ACTIVE' && (tab === 'findings') === (e.kind === 'FINDING');
}

/**
 * 它现在会不会看到这条、还差几次转正。转正条件同后端 ExperienceLedger：对满 3 次，且错的不超过对的三分之一
 */
function stateOf(e: TraderExperience): { key: string; cls: string; need?: number } {
  if (e.status === 'RETIRED') return { key: 'ledger.state.off', cls: '' };
  if (e.kind === 'FINDING') return { key: 'ledger.state.me', cls: 'me' };
  if (e.kind === 'HYPOTHESIS') {
    const need = Math.max(3, 3 * e.opposes) - e.supports;
    return need > 0 ? { key: 'ledger.state.hyp', cls: '', need } : { key: 'ledger.state.hypReady', cls: '' };
  }
  return { key: 'ledger.state.inj', cls: 'inj' };
}

/** 方向徽章配色：提示机会绿、提示别开仓黄、执行纪律灰 */
const DIR_CHIP: Record<TraderExperience['direction'], string> = { OPPORTUNITY: 'up', RESTRICTION: 'wn', EXECUTION: 'mute' };

/**
 * 配置台的「经验账本」一节：说明面板 + 三个页签（经验 / 给你的提醒 / 已停用）。
 * 每日复盘后代码记账，这里只管不写——条目正文来自模型的复盘
 */
export function ExperienceLedgerSection({ id, className }: { id: string; className?: string }) {
  const { t } = useTranslation(['ai', 'common']);
  const { toast } = useToast();
  const [rows, setRows] = useState<TraderExperience[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>('active');
  const [helpOpen, setHelpOpen] = useState(() => localStorage.getItem(HELP_KEY) !== '1');
  // 正在填停用理由的那一条
  const [retiring, setRetiring] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  const load = useCallback(() => {
    setLoadError(false);
    traderApi.experiences().then(setRows).catch(() => setLoadError(true));
  }, []);
  useEffect(() => { load(); }, [load]);

  const toggleHelp = () => {
    const open = !helpOpen;
    setHelpOpen(open);
    localStorage.setItem(HELP_KEY, open ? '0' : '1');
  };

  const act = async (rowId: number, action: () => Promise<unknown>) => {
    setBusy(rowId);
    try {
      await action();
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setBusy(null);
    }
  };
  const startRetire = (e: TraderExperience) => {
    setRetiring(e.id);
    setReason(t('ledger.retireDefault'));
  };
  const confirmRetire = (e: TraderExperience) => {
    if (!reason.trim()) return;
    setRetiring(null);
    void act(e.id, () => traderApi.retireExperience(e.id, reason.trim()));
  };
  const remove = (e: TraderExperience) => {
    if (!window.confirm(t('ledger.deleteConfirm'))) return;
    void act(e.id, () => traderApi.deleteExperience(e.id));
  };

  const all = rows ?? [];
  const list = all.filter(e => inTab(e, tab));
  const tabs: Tab[] = ['active', 'findings', 'retired'];

  return (
    <section id={id} className={cn('tr-sec', className)}>
      <div className="tr-sec-h">
        <h2>{t('ledger.title')}</h2>
        <span className="d">{t('ledger.desc')}</span>
        <button type="button" className="btn xs" onClick={toggleHelp} aria-expanded={helpOpen}>
          <BookOpen className="w-3.5 h-3.5" />{helpOpen ? t('ledger.helpClose') : t('ledger.helpOpen')}
        </button>
      </div>

      {helpOpen && <LedgerHelp />}

      <div className="seg mb-3.5">
        {tabs.map(k => (
          <button key={k} type="button" className={cn(tab === k && 'on')} onClick={() => setTab(k)}>
            {t(`ledger.tab.${k}`)}
            {rows != null && <em className="not-italic num text-[11.5px] opacity-75">{all.filter(e => inTab(e, k)).length}</em>}
          </button>
        ))}
      </div>

      {loadError && (
        <p className="text-[13px] dn inline-flex items-center gap-2 flex-wrap">
          {t('ledger.loadFailed')}
          <button type="button" className="btn xs" onClick={load}><RefreshCw className="w-3 h-3" />{t('ledger.retry')}</button>
        </p>
      )}
      {rows == null && !loadError && <Loader2 className="w-4 h-4 animate-spin mute" />}
      {rows != null && (
        list.length === 0
          ? <p className="tr-hint py-4">{all.length === 0 ? t('ledger.empty') : t(`ledger.emptyTab.${tab}`)}</p>
          : (
            <div className="tr-lg-list">
              {list.map(e => (
                <LedgerRow key={e.id} e={e} busy={busy === e.id}
                           retiring={retiring === e.id} reason={reason} onReason={setReason}
                           onPin={() => void act(e.id, () => traderApi.pinExperience(e.id, !e.pinned))}
                           onRetire={() => startRetire(e)} onRetireOk={() => confirmRetire(e)}
                           onRetireCancel={() => setRetiring(null)}
                           onRevive={() => void act(e.id, () => traderApi.reviveExperience(e.id))}
                           onDelete={() => remove(e)} />
              ))}
            </div>
          )
      )}
    </section>
  );
}

/** 一条经验：徽章一排 → 正文 → 对错计数条 / 引用次数 / 证据；右边是它现在的状态和能点的按钮 */
function LedgerRow({ e, busy, retiring, reason, onReason, onPin, onRetire, onRetireOk, onRetireCancel, onRevive, onDelete }: {
  e: TraderExperience; busy: boolean; retiring: boolean; reason: string; onReason: (v: string) => void;
  onPin: () => void; onRetire: () => void; onRetireOk: () => void; onRetireCancel: () => void;
  onRevive: () => void; onDelete: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const retired = e.status === 'RETIRED';
  const finding = e.kind === 'FINDING';
  const st = stateOf(e);
  const total = Math.max(1, e.supports + e.opposes + e.untested);
  const cited = e.citedOpen + e.citedHold;
  return (
    <div className={cn('tr-lg-row', retired && 'ret')}>
      <div className="min-w-0">
        <div className="tr-tags">
          <span className="chip mute num">#{e.id}</span>
          <span className="chip" title={t(`ledger.kindTip.${e.kind}`)}>{t(`ledger.kind.${e.kind}`)}</span>
          {!finding && <span className="chip mute" title={t('ledger.slotTip')}>{t(`ledger.slot.${e.slot}`)}</span>}
          {!finding && (
            <span className={cn('chip', DIR_CHIP[e.direction])} title={t(`ledger.dirTip.${e.direction}`)}>
              {t(`ledger.dir.${e.direction}`)}
            </span>
          )}
          {!finding && <span className="pl">{e.playType ?? t('ledger.general')}</span>}
          {e.pinned && !retired && <span className="flag text-primary"><Pin className="w-3 h-3" />{t('ledger.pinned')}</span>}
          {e.flagged && !retired && <span className="flag wn">{t('ledger.flagged')}</span>}
        </div>
        <div className="tr-lg-t">{e.text}</div>
        <div className="tr-lg-s">
          {!finding && (
            <>
              <span className="tr-tally" aria-hidden="true">
                <i className="bg-foreground" style={{ width: `${e.supports / total * 100}%` }} />
                <i className="bg-loss" style={{ width: `${e.opposes / total * 100}%` }} />
              </span>
              <span className="num">
                {t(e.kind === 'GAP' ? 'ledger.countsGap' : 'ledger.counts', { s: e.supports, o: e.opposes, u: e.untested })}
              </span>
            </>
          )}
          {!finding && cited > 0 && <span className="num">{t('ledger.cited', { open: e.citedOpen, hold: e.citedHold })}</span>}
          {e.evidence && <span>{t('ledger.evidenceOf', { v: e.evidence })}</span>}
          {retired && e.retireReason && <span>{t('ledger.retiredReasonOf', { v: e.retireReason })}</span>}
        </div>
      </div>
      <div className="tr-lg-a">
        <span className={cn('tr-lg-st', st.cls)}><i />{t(st.key, { n: st.need })}</span>
        <span className="tr-lg-bt">
          {busy ? <Loader2 className="w-4 h-4 animate-spin mute" /> : retired ? (
            <>
              <button type="button" className="btn xs" onClick={onRevive}>{t('ledger.revive')}</button>
              <button type="button" className="btn xs border-loss text-loss" onClick={onDelete}>{t('ledger.delete')}</button>
            </>
          ) : finding ? (
            <button type="button" className="btn xs border-loss text-loss" onClick={onDelete}>{t('ledger.delete')}</button>
          ) : (
            <>
              <button type="button" className="btn xs" onClick={onPin}>{e.pinned ? t('ledger.unpin') : t('ledger.pin')}</button>
              {!retiring && <button type="button" className="btn xs" onClick={onRetire}>{t('ledger.retire')}</button>}
            </>
          )}
        </span>
      </div>
      {retiring && (
        <div className="tr-retire">
          <input value={reason} onChange={ev => onReason(ev.target.value)} maxLength={200} autoFocus
                 aria-label={t('ledger.retireReasonAria')}
                 onKeyDown={ev => { if (ev.key === 'Enter') onRetireOk(); if (ev.key === 'Escape') onRetireCancel(); }} />
          <button type="button" className="btn xs border-loss text-loss" onClick={onRetireOk} disabled={!reason.trim()}>
            {t('ledger.retireOk')}
          </button>
          <button type="button" className="btn xs" onClick={onRetireCancel}>{t('common:cancel')}</button>
        </div>
      )}
    </div>
  );
}

/** 说明面板：有什么用 → 一条经验怎么来的 → 你要做的 */
function LedgerHelp() {
  const { t } = useTranslation('ai');
  const arrow = <span className="tr-fa"><ArrowRight className="w-[18px] h-[18px]" /></span>;
  const steps = [1, 2, 3, 4];
  return (
    <div className="tr-lgh">
      <div className="tr-lgh-h">{t('ledger.help.useTitle')}</div>
      <div className="tr-use2">
        <div><b>{t('ledger.help.use1Title')}</b><p>{t('ledger.help.use1')}</p></div>
        <div><b>{t('ledger.help.use2Title')}</b><p>{t('ledger.help.use2')}</p></div>
      </div>
      <div className="tr-lgh-h">{t('ledger.help.flowTitle')}</div>
      <div className="tr-flow">
        {steps.map(n => (
          <span key={n} className="contents">
            {n > 1 && arrow}
            <span className={cn('tr-fc', n === 4 && 'inj')}>
              <i className="n">{n}</i>{t(`ledger.help.step${n}`)}<em>{t(`ledger.help.step${n}Sub`)}</em>
            </span>
          </span>
        ))}
      </div>
      <div className="tr-flow">
        <span className="tr-flow-l">{t('ledger.help.recheckLead')}</span>
        <span className="tr-fc">{t('ledger.help.recheck1')}</span>
        {arrow}
        <span className="tr-fc wn">{t('ledger.help.recheck2')}</span>
        {arrow}
        <span className="tr-fc">{t('ledger.help.recheck3')}<em>{t('ledger.help.recheck3Sub')}</em></span>
      </div>
      <div className="tr-kinds">
        <div><span className="chip">{t('ledger.kind.GAP')}</span>{t('ledger.help.gap')}</div>
        <div><span className="chip border-primary text-primary">{t('ledger.kind.FINDING')}</span>{t('ledger.help.finding')}</div>
      </div>
      <div className="tr-lgh-h">{t('ledger.help.todoTitle')}</div>
      <ul className="tr-when">
        {['finding', 'promoted', 'pin', 'full', 'delete'].map(k => (
          <li key={k}><b>{t(`ledger.help.todo.${k}Title`)}</b>{t(`ledger.help.todo.${k}`)}</li>
        ))}
      </ul>
      <p className="tr-lgh-foot">{t('ledger.help.foot')}</p>
    </div>
  );
}
