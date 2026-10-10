import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Archive, ChevronDown, Flag, Loader2, Pin, PinOff, RefreshCw, RotateCcw, Scroll, Trash2 } from 'lucide-react';
import { traderApi } from '../../api';
import { useToast } from '../ui/use-toast';
import { cn } from '../../lib/utils';
import type { TraderExperience } from '../../types';

/**
 * 主人的经验账本：现役条目（可钉住/退役）、发现（给主人看的）、退役名单（可恢复/删除）。
 * 每日复盘后代码记账；这里只管，不写——条目正文来自模型的复盘。
 */
export function ExperienceLedgerCard() {
  const { t } = useTranslation(['ai', 'common']);
  const { toast } = useToast();
  const [rows, setRows] = useState<TraderExperience[] | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  // 拉账本失败：显示一行失败与重试，不把空列表当成"账本是空的"
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(() => {
    setLoadError(false);
    traderApi.experiences().then(setRows).catch(() => setLoadError(true));
  }, []);
  useEffect(() => { load(); }, [load]);

  const act = async (id: number, action: () => Promise<unknown>) => {
    setBusy(id);
    try {
      await action();
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setBusy(null);
    }
  };

  const retire = (e: TraderExperience) => {
    const reason = window.prompt(t('ledger.retireReasonPrompt'), t('ledger.retireDefaultReason'));
    if (reason == null || !reason.trim()) return;
    void act(e.id, () => traderApi.retireExperience(e.id, reason.trim()));
  };
  const remove = (e: TraderExperience) => {
    if (!window.confirm(t('ledger.deleteConfirm'))) return;
    void act(e.id, () => traderApi.deleteExperience(e.id));
  };

  const all = rows ?? [];
  const active = all.filter(r => r.status === 'ACTIVE' && r.kind !== 'FINDING');
  const findings = all.filter(r => r.status === 'ACTIVE' && r.kind === 'FINDING');
  const retired = all.filter(r => r.status === 'RETIRED');

  return (
    <details className="rounded-lg pt-card group" open>
      <summary className="list-none cursor-pointer px-4 py-3 flex items-center gap-2.5 flex-wrap">
        <Scroll className="w-3 h-3 text-primary" />
        <span className="microlabel">{t('ledger.title')}</span>
        {rows != null
          ? <span className="text-[11px] text-muted-foreground">{t('ledger.summary', { active: active.length, findings: findings.length, retired: retired.length })}</span>
          : !loadError && <Loader2 className="w-3 h-3 animate-spin text-muted-foreground" />}
        <ChevronDown className="ml-auto w-3.5 h-3.5 text-muted-foreground transition-transform group-open:rotate-180" />
      </summary>
      <div className="px-4 pb-4 space-y-3">
        <p className="text-[10px] leading-relaxed text-muted-foreground border-l-2 border-border pl-2.5">{t('ledger.intro')}</p>

        {loadError && (
          <p className="text-xs text-loss inline-flex items-center gap-2 flex-wrap">
            {t('ledger.loadFailed')}
            <button type="button" onClick={load}
                    className="inline-flex items-center gap-1 rounded border border-border px-1.5 py-0.5 text-[10px] font-bold text-muted-foreground hover:text-primary hover:bg-surface-hover">
              <RefreshCw className="w-3 h-3" />{t('ledger.retry')}
            </button>
          </p>
        )}

        {rows != null && all.length === 0 && (
          <p className="text-xs text-muted-foreground">{t('ledger.empty')}</p>
        )}

        {active.length > 0 && (
          <Section title={t('ledger.active')} count={active.length}>
            {active.map(e => (
              <Row key={e.id} e={e} busy={busy === e.id}>
                <ActionBtn onClick={() => void act(e.id, () => traderApi.pinExperience(e.id, !e.pinned))}
                           label={e.pinned ? t('ledger.unpin') : t('ledger.pin')}>
                  {e.pinned ? <PinOff className="w-3 h-3" /> : <Pin className="w-3 h-3" />}
                </ActionBtn>
                <ActionBtn onClick={() => retire(e)} label={t('ledger.retire')}>
                  <Archive className="w-3 h-3" />
                </ActionBtn>
              </Row>
            ))}
          </Section>
        )}

        {findings.length > 0 && (
          <Section title={t('ledger.findings')} count={findings.length}>
            {findings.map(e => (
              <Row key={e.id} e={e} busy={busy === e.id}>
                <ActionBtn onClick={() => remove(e)} label={t('ledger.delete')} danger>
                  <Trash2 className="w-3 h-3" />
                </ActionBtn>
              </Row>
            ))}
          </Section>
        )}

        {retired.length > 0 && (
          <Section title={t('ledger.retired')} count={retired.length}>
            {retired.map(e => (
              <Row key={e.id} e={e} busy={busy === e.id}>
                <ActionBtn onClick={() => void act(e.id, () => traderApi.reviveExperience(e.id))} label={t('ledger.revive')}>
                  <RotateCcw className="w-3 h-3" />
                </ActionBtn>
                <ActionBtn onClick={() => remove(e)} label={t('ledger.delete')} danger>
                  <Trash2 className="w-3 h-3" />
                </ActionBtn>
              </Row>
            ))}
          </Section>
        )}
      </div>
    </details>
  );
}

function Section({ title, count, children }: { title: string; count: number; children: React.ReactNode }) {
  return (
    <div className="rounded-md border border-border overflow-hidden">
      <div className="flex items-center gap-2 px-3 py-1.5 bg-card-2">
        <b className="text-[11px] font-extrabold">{title}</b>
        <span className="ml-auto num text-[10px] text-muted-foreground">{count}</span>
      </div>
      {children}
    </div>
  );
}

/** 一条：编号 + 种类/方向徽章 + 正文；下一行证据与计数；右侧操作按钮 */
function Row({ e, busy, children }: { e: TraderExperience; busy: boolean; children: React.ReactNode }) {
  const { t } = useTranslation('ai');
  const retired = e.status === 'RETIRED';
  return (
    <div className={cn('px-3 py-2 border-t border-border/60 text-[11px] leading-relaxed space-y-1', retired && 'opacity-70')}>
      <div className="flex items-start gap-2">
        <div className="flex-1 min-w-0 space-y-0.5">
          <div className="flex items-center gap-1.5 flex-wrap">
            <code className="num text-[10px] text-muted-foreground">#{e.id}</code>
            <Badge>{t(`ledger.kind.${e.kind}`)}</Badge>
            {e.kind !== 'FINDING' && <Badge>{t(`ledger.slot.${e.slot}`)}</Badge>}
            {e.kind !== 'FINDING' && (
              <Badge className={e.direction === 'RESTRICTION' ? 'text-amber-600 border-amber-500/40' : e.direction === 'OPPORTUNITY' ? 'text-gain border-gain/40' : undefined}>
                {t(`ledger.dir.${e.direction}`)}
              </Badge>
            )}
            <span className="text-[10px] font-bold text-muted-foreground">{e.playType ?? t('ledger.general')}</span>
            {e.flagged && (
              <span className="inline-flex items-center gap-0.5 text-[10px] font-bold text-amber-600"><Flag className="w-3 h-3" />{t('ledger.flagged')}</span>
            )}
            {e.pinned && !retired && (
              <span className="inline-flex items-center gap-0.5 text-[10px] font-bold text-primary"><Pin className="w-3 h-3" />{t('ledger.pinned')}</span>
            )}
          </div>
          <p className={cn('text-xs font-bold text-foreground', retired && 'line-through')}>{e.text}</p>
          <p className="text-[10px] text-muted-foreground">
            {e.evidence && <>{t('ledger.evidenceOf', { v: e.evidence })} · </>}
            {e.kind !== 'FINDING' && <>{t('ledger.counts', { s: e.supports, o: e.opposes, u: e.untested })} · {t('ledger.cited', { open: e.citedOpen, hold: e.citedHold })}</>}
            {retired && e.retireReason && <> · {t('ledger.retiredReasonOf', { v: e.retireReason })}</>}
          </p>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          {busy ? <Loader2 className="w-3 h-3 animate-spin text-muted-foreground" /> : children}
        </div>
      </div>
    </div>
  );
}

function Badge({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <span className={cn('inline-flex items-center rounded border border-border px-1 text-[10px] font-bold text-muted-foreground', className)}>
      {children}
    </span>
  );
}

function ActionBtn({ onClick, label, danger, children }: { onClick: () => void; label: string; danger?: boolean; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick} title={label} aria-label={label}
            className={cn('inline-flex items-center gap-1 rounded border border-border px-1.5 py-0.5 text-[10px] font-bold hover:bg-surface-hover',
              danger ? 'text-loss border-loss/40 hover:bg-loss/10' : 'text-muted-foreground hover:text-primary')}>
      {children}<span className="hidden sm:inline">{label}</span>
    </button>
  );
}
