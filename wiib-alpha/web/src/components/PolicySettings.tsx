import { useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Lock, Pencil, Plus, Save, X } from 'lucide-react';
import { alpha, type Policy, type PolicyFamily } from '../api';
import { localTime } from '../format';
import { useFail, useToast } from '../toast';
import { Button, Card, Drawer, Spinner, Waiting } from './ui';

/** 研究方针：出题、写公式、评审时给模型看的观点。卡片上是当前第几版，点编辑在抽屉里改；存一次多一版，下一个 campaign 起用。
 *  blocked 是现在存不了的原因（campaign 在跑） */
export function PolicySettings({ blocked }: { blocked: string | null | undefined }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    alpha.policy().then(setPolicy).catch(fail);
  }, [fail]);

  return (
    <>
      <Card title={t('policy.title')} hint={t('policy.hint')}
            actions={<Button variant="icon" icon={Pencil} aria-label={t('policy.edit')} disabled={!policy}
                             onClick={() => setOpen(true)} />}>
        {!policy ? <Spinner /> : (
          <p className="border-t border-fg pt-2 text-[13px]">
            {t('policy.summary', { id: policy.id, time: localTime(policy.createdAt), n: policy.families.length })}
          </p>
        )}
      </Card>
      {policy && (
        <Drawer open={open} onClose={() => setOpen(false)}
                title={<span className="text-[16px] font-extrabold">{t('policy.editTitle', { id: policy.id })}</span>}>
          <PolicyForm key={policy.id} policy={policy} blocked={blocked} onSaved={p => { setPolicy(p); setOpen(false); }} />
        </Drawer>
      )}
    </>
  );
}

function PolicyForm({ policy, blocked, onSaved }: {
  policy: Policy;
  blocked: string | null | undefined;
  onSaved: (p: Policy) => void;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const [families, setFamilies] = useState<PolicyFamily[]>(policy.families);
  const [genRules, setGenRules] = useState(policy.genRules);
  const [implementChecks, setImplementChecks] = useState(policy.implementChecks);
  const [reviewRules, setReviewRules] = useState(policy.reviewRules);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 前这么多条是存过的：id 不能改名、不能删
  const kept = policy.families.length;

  const edit = (i: number, patch: Partial<PolicyFamily>) =>
    setFamilies(fs => fs.map((f, j) => (j === i ? { ...f, ...patch } : f)));

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await alpha.savePolicy({ families, genRules, implementChecks, reviewRules }));
      toast(t('policy.saved'), 'success');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={e => void save(e)} className="space-y-6">
      <TextBlock label={t('policy.genRules')} value={genRules} onChange={setGenRules} />
      <TextBlock label={t('policy.implementChecks')} value={implementChecks} onChange={setImplementChecks} />
      <TextBlock label={t('policy.reviewRules')} value={reviewRules} onChange={setReviewRules} />
      <div className="space-y-2.5">
        <div>
          <div className="text-[14px] font-extrabold">{t('policy.families')}</div>
          <p className="text-[12px] text-muted">{t('policy.familiesHint')}</p>
        </div>
        {families.map((f, i) => (
          <div key={i} className="grid grid-cols-[8rem_minmax(0,1fr)_2rem] items-center gap-2 border-t border-line pt-2.5">
            {i < kept
              ? <span className="inline-flex items-center gap-1 font-mono text-[12.5px]"><Lock className="w-3 h-3 text-muted" />{f.id}</span>
              : <input value={f.id} onChange={e => edit(i, { id: e.target.value })} placeholder={t('policy.idPh')}
                       pattern="[a-z][a-z0-9_]*" maxLength={32} required className="input font-mono" />}
            <input value={f.what} onChange={e => edit(i, { what: e.target.value })} placeholder={t('policy.whatPh')}
                   maxLength={300} required className="input" />
            {i < kept ? <span /> : (
              <Button type="button" variant="icon" icon={X} aria-label={t('policy.remove')}
                      onClick={() => setFamilies(fs => fs.filter((_, j) => j !== i))} />
            )}
            <span />
            <div className="grid grid-cols-[minmax(0,1fr)_8rem] gap-2">
              <input value={f.cols} onChange={e => edit(i, { cols: e.target.value })} placeholder={t('policy.colsPh')}
                     maxLength={200} className="input" />
              <input value={f.hold} onChange={e => edit(i, { hold: e.target.value })} placeholder={t('policy.holdPh')}
                     maxLength={50} className="input" />
            </div>
          </div>
        ))}
        <Button type="button" icon={Plus} onClick={() => setFamilies(fs => [...fs, { id: '', what: '', cols: '', hold: '' }])}>
          {t('policy.addFamily')}
        </Button>
      </div>
      <div className="space-y-2 border-t border-fg pt-3">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <Button type="submit" variant="primary" icon={Save} loading={busy} disabled={!!blocked}>{t('policy.save')}</Button>
          <span className="text-[12px] text-muted">
            {t('policy.history', { ids: policy.history.map(h => `#${h.id}`).join(' ') })}
          </span>
        </div>
        <Waiting why={blocked} />
        {error && <p className="text-[12.5px] text-loss break-all">{error}</p>}
      </div>
    </form>
  );
}

function TextBlock({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <label className="block space-y-1.5">
      <span className="text-[14px] font-extrabold">{label}</span>
      <textarea value={value} onChange={e => onChange(e.target.value)} rows={Math.max(3, value.split('\n').length + 1)}
                maxLength={4000} className="input h-auto w-full py-2 leading-relaxed" />
    </label>
  );
}
