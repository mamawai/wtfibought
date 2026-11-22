import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Loader2, Pin, RefreshCw } from 'lucide-react';
import { traderApi } from '../../api';
import { useToast } from '../ui/use-toast';
import { Tabs } from '../ui/tabs';
import { SureButton } from '../SureButton';
import { cn, fmtSigned } from '../../lib/utils';
import type { TraderExperience } from '../../types';

type Tab = 'active' | 'findings' | 'retired';
const TABS: Tab[] = ['active', 'findings', 'retired'];

/** 说明面板收起后记住，下次进来不再自动展开 */
const HELP_KEY = 'wiib-ledger-help-closed';

/** 种类标签的颜色：规律绿、待验证灰、没做到黄、给你的提醒橙 */
const KIND_TONE: Record<TraderExperience['kind'], string> = { RULE: 'g', HYPOTHESIS: '', GAP: 'w', FINDING: 'o' };

function inTab(e: TraderExperience, tab: Tab): boolean {
  if (tab === 'retired') return e.status === 'RETIRED';
  return e.status === 'ACTIVE' && (tab === 'findings') === (e.kind === 'FINDING');
}

/** 会转正的假设只在这两格；失败判定、失败处理、盈利处理三格不再新增，存量的留着但不转正 */
const PROMOTABLE: TraderExperience['slot'][] = ['ENTRY', 'RISK'];

/** 它现在看不看得到这条（后端给的 shown）；看不到的说一句什么时候才看得到 */
function stateOf(e: TraderExperience): string {
  if (e.status === 'RETIRED') return 'off';
  if (e.kind === 'FINDING') return 'me';
  if (e.shown) return 'on';
  if (e.kind === 'GAP') return 'gap';
  return PROMOTABLE.includes(e.slot) ? 'hyp' : 'stay';
}

/**
 * 配置台的「经验账本」一节（样式见 MyTrader.css .le / .lhelp）：三个页签（经验 / 给你的提醒 / 已停用）+ 一块能收起的说明。
 * 统一复盘后代码记账，这里只管不写——条目正文来自模型的复盘
 *
 * @param traderId 看的是哪个 trader 的账本（只有主人能看能改）
 */
export function ExperienceLedgerSection({ id, traderId }: { id: string; traderId: number }) {
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
    traderApi.experiences(traderId).then(setRows).catch(() => setLoadError(true));
  }, [traderId]);
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
    void act(e.id, () => traderApi.retireExperience(traderId, e.id, reason.trim()));
  };

  const all = rows ?? [];
  const list = all.filter(e => inTab(e, tab));

  return (
    <section id={id} className="msec">
      <div className="msec-h">
        <h2>{t('ledger.title')}</h2>
        <span className="d">{t('ledger.desc')}</span>
      </div>
      <div className="lg-h">
        <Tabs value={tab} onChange={k => { setTab(k); setRetiring(null); }}
              items={TABS.map(k => ({ value: k, label: t(`ledger.tab.${k}`), count: rows ? all.filter(e => inTab(e, k)).length : undefined }))} />
        <button type="button" className="linkb" aria-expanded={helpOpen} onClick={toggleHelp}>
          {helpOpen ? t('ledger.helpClose') : t('ledger.helpOpen')}
        </button>
      </div>
      <div className={cn('col', helpOpen && 'open')}><div><LedgerHelp /></div></div>

      {loadError ? (
        <div className="empty">
          <b>{t('ledger.loadFailed')}</b>
          <button type="button" className="btn" onClick={load}><RefreshCw className="ic" />{t('ledger.retry')}</button>
        </div>
      ) : rows == null ? (
        <div className="empty"><Loader2 className="ic animate-spin" /></div>
      ) : list.length === 0 ? (
        <div className="empty"><b>{all.length === 0 ? t('ledger.empty') : t(`ledger.emptyTab.${tab}`)}</b></div>
      ) : (
        <div>
          {list.map(e => (
            <LedgerRow key={e.id} e={e} busy={busy === e.id}
                       retiring={retiring === e.id} reason={reason} onReason={setReason}
                       onPin={() => void act(e.id, () => traderApi.pinExperience(traderId, e.id, !e.pinned))}
                       onRetire={() => startRetire(e)} onRetireOk={() => confirmRetire(e)}
                       onRetireCancel={() => setRetiring(null)}
                       onRevive={() => void act(e.id, () => traderApi.reviveExperience(traderId, e.id))}
                       onDelete={() => void act(e.id, () => traderApi.deleteExperience(traderId, e.id))} />
          ))}
        </div>
      )}
    </section>
  );
}

/** 一条经验：标签一排（右边能点的按钮）→ 正文 → 证据 / 停用原因 → 名下单子的账、它用过几次、它现在看不看得到 */
function LedgerRow({ e, busy, retiring, reason, onReason, onPin, onRetire, onRetireOk, onRetireCancel, onRevive, onDelete }: {
  e: TraderExperience; busy: boolean; retiring: boolean; reason: string; onReason: (v: string) => void;
  onPin: () => void; onRetire: () => void; onRetireOk: () => void; onRetireCancel: () => void;
  onRevive: () => void; onDelete: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const retired = e.status === 'RETIRED';
  const finding = e.kind === 'FINDING';
  // 删除只给已停用的和给你的提醒；在用的经验先停用
  const del = (
    <SureButton className="btn sm" idleClassName="plain" sureLabel={t('ledger.deleteSure')} title={t('ledger.deleteConfirm')} onConfirm={onDelete}>
      {t('ledger.delete')}
    </SureButton>
  );
  return (
    <div className="le">
      <div className="le-h">
        <span className={cn('tag', KIND_TONE[e.kind])} title={t(`ledger.kindTip.${e.kind}`)}>{t(`ledger.kind.${e.kind}`)}</span>
        {!finding && (
          <>
            <span className="tag" title={t('ledger.slotTip')}>{t(`ledger.slot.${e.slot}`)}</span>
            <span className="tag" title={t(`ledger.dirTip.${e.direction}`)}>{t(`ledger.dir.${e.direction}`)}</span>
            <span className="tag">{e.playType ?? t('ledger.general')}</span>
          </>
        )}
        {e.pinned && !retired && <span className="tag ink"><Pin className="ic" />{t('ledger.pinned')}</span>}
        <span className="acts">
          {busy ? <Loader2 className="ic animate-spin mute" /> : retired ? (
            <>
              <button type="button" className="btn sm" onClick={onRevive}>{t('ledger.revive')}</button>
              {del}
            </>
          ) : finding ? del : (
            <>
              <button type="button" className="btn sm" onClick={onPin}>{e.pinned ? t('ledger.unpin') : t('ledger.pin')}</button>
              {!retiring && <button type="button" className="btn sm" onClick={onRetire}>{t('ledger.retire')}</button>}
            </>
          )}
        </span>
      </div>
      <p className="tx">{e.text}</p>
      {e.evidence && <p className="evd">{t('ledger.evidenceOf', { v: e.evidence })}</p>}
      {retired && e.retireReason && <p className="evd">{t('ledger.retiredReasonOf', { v: e.retireReason })}</p>}
      <div className="ft">
        {!finding && <Tally e={e} />}
        {!finding && e.citedOpen + e.citedHold > 0 && (
          <span>{t('ledger.cited', { open: t('ledger.times', { count: e.citedOpen }), hold: t('ledger.times', { count: e.citedHold }) })}</span>
        )}
        <span>{t(`ledger.state.${stateOf(e)}`)}</span>
      </div>
      {retiring && (
        <div className="rt">
          <label className="inp sm">
            <input value={reason} onChange={ev => onReason(ev.target.value)} maxLength={200} autoFocus
                   aria-label={t('ledger.retireReasonAria')}
                   onKeyDown={ev => { if (ev.key === 'Enter') onRetireOk(); if (ev.key === 'Escape') onRetireCancel(); }} />
          </label>
          <button type="button" className="btn danger" onClick={onRetireOk} disabled={!reason.trim()}>{t('ledger.retireOk')}</button>
          <button type="button" className="btn" onClick={onRetireCancel}>{t('common:cancel')}</button>
        </div>
      )}
    </div>
  );
}

/**
 * 名下单子的账：规律和假设记钱（限制型记的是违反它的单子），转正或降级过的再列出当前这一段；
 * 执行缺口只记碰到几次、做到几次
 */
function Tally({ e }: { e: TraderExperience }) {
  const { t } = useTranslation('ai');
  const tl = e.tally;
  if (e.kind === 'GAP') {
    return (
      <span>
        <Trans ns="ai" i18nKey="ledger.gapTally" count={tl.kept + tl.broken} values={{ kept: tl.kept }} components={{ b: <b /> }} />
      </span>
    );
  }
  return (
    <>
      <span>
        <Trans ns="ai" i18nKey={e.direction === 'RESTRICTION' ? 'ledger.tallyAgainst' : 'ledger.tally'} count={tl.under}
               values={{ w: tl.wins, l: tl.losses, net: fmtSigned(tl.net), days: t('ledger.days', { count: tl.days }) }}
               components={{ b: <b /> }} />
      </span>
      {tl.miscited > 0 && <span>{t('ledger.miscited', { count: tl.miscited })}</span>}
      {/* 段号从 0 起，大于 0 说明转正或降级过，当前段才和累计不一样 */}
      {e.segmentNo > 0 && (
        <span>
          <Trans ns="ai" i18nKey={e.kind === 'RULE' ? 'ledger.segmentRule' : 'ledger.segmentHyp'} count={tl.segmentUnder}
                 values={{ net: fmtSigned(tl.segmentNet), days: t('ledger.days', { count: tl.segmentDays }) }} components={{ b: <b /> }} />
        </span>
      )}
    </>
  );
}

/** 说明：有什么用 → 一条经验怎么来的（名下记哪些单子、代码怎么转正降级停用）→ 你要做的 */
function LedgerHelp() {
  const { t } = useTranslation('ai');
  return (
    <div className="lhelp blk">
      <h4>{t('ledger.help.useTitle')}</h4>
      <div className="two">
        <div><b>{t('ledger.help.use1Title')}</b><span>{t('ledger.help.use1')}</span></div>
        <div><b>{t('ledger.help.use2Title')}</b><span>{t('ledger.help.use2')}</span></div>
      </div>
      <h4>{t('ledger.help.flowTitle')}</h4>
      <div className="flow">
        {[1, 2, 3, 4].map(n => (
          <div key={n}><b>{t(`ledger.help.step${n}`)}</b><span>{t(`ledger.help.step${n}Sub`)}</span></div>
        ))}
      </div>
      <p className="mt-3">{t('ledger.help.under')}</p>
      <p className="mt-1.5">{t('ledger.help.auto')}</p>
      <p className="mt-1.5">{t('ledger.help.manage')}</p>
      <p className="mt-1.5"><span className="tag w sm">{t('ledger.kind.GAP')}</span> {t('ledger.help.gap')}</p>
      <p className="mt-1.5"><span className="tag o sm">{t('ledger.kind.FINDING')}</span> {t('ledger.help.finding')}</p>
      <h4>{t('ledger.help.todoTitle')}</h4>
      <div className="todo">
        {['finding', 'promoted', 'pin', 'full', 'delete'].map(k => (
          <div key={k}><b>{t(`ledger.help.todo.${k}Title`)}</b><span>{t(`ledger.help.todo.${k}`)}</span></div>
        ))}
      </div>
      <p className="mt-3.5">{t('ledger.help.foot')}</p>
    </div>
  );
}
