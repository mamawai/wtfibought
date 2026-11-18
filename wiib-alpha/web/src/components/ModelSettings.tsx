import { useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, Plug, Save } from 'lucide-react';
import {
  alpha, MODEL_ROLES, PROTOCOLS, type ModelRole, type Models, type ModelSave, type ModelView, type Protocol,
} from '../api';
import { cx, localTime } from '../format';
import { useFail, useToast } from '../toast';
import { Button, Card, Spinner } from './ui';

/** 两个角色的模型端点：协议、地址、模型、key、思考档位。收着时一行一个角色，点开才是表单 */
export function ModelSettings({ onReady }: { onReady: (ready: boolean) => void }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [models, setModels] = useState<Models | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    alpha.models()
      .then(m => { setModels(m); onReady(!!m.gen && !!m.review); })
      .catch(fail);
  }, [fail, onReady]);

  const saved = (role: ModelRole, view: ModelView) => {
    const next = { ...(models as Models), [role]: view };
    setModels(next);
    onReady(!!next.gen && !!next.review);
  };

  return (
    <Card title={t('models.title')} blk
          actions={<Button variant="icon" icon={open ? ChevronUp : ChevronDown} aria-expanded={open}
                           aria-label={t(open ? 'common.collapse' : 'common.expand')} onClick={() => setOpen(o => !o)} />}>
      {!models ? <Spinner /> : (
        <div className={cx(open ? 'space-y-6' : '-my-2 divide-y divide-line')}>
          {MODEL_ROLES.map(role => (
            open
              ? <RoleForm key={role} role={role} view={models[role]} onSaved={v => saved(role, v)} />
              : <Summary key={role} role={role} view={models[role]} />
          ))}
          {!open && (!models.gen || !models.review) && <p className="py-2 text-[12px] text-warn">{t('models.needBoth')}</p>}
        </div>
      )}
    </Card>
  );
}

function Summary({ role, view }: { role: ModelRole; view: ModelView | null }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-baseline gap-2 py-2 text-[13px]">
      <span className="w-14 shrink-0 text-muted">{t(`models.role.${role}`)}</span>
      {view
        ? <span className="truncate">{view.protocol} · {view.model}{view.effort && ` · ${view.effort}`} · ****{view.apiKeyTail}</span>
        : <span className="text-warn">{t('models.unset')}</span>}
    </div>
  );
}

function RoleForm({ role, view, onSaved }: { role: ModelRole; view: ModelView | null; onSaved: (v: ModelView) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [protocol, setProtocol] = useState<Protocol>(view?.protocol ?? 'openai');
  const [baseUrl, setBaseUrl] = useState(view?.baseUrl ?? '');
  const [model, setModel] = useState(view?.model ?? '');
  const [effort, setEffort] = useState(view?.effort ?? '');
  const [apiKey, setApiKey] = useState('');
  const [busy, setBusy] = useState<'save' | 'test' | null>(null);
  // 测试结果：回答与耗时，或上游的报错
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  const body = (): ModelSave => ({ protocol, baseUrl: baseUrl.trim(), model: model.trim(), effort: effort.trim(), apiKey });

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy('save');
    try {
      onSaved(await alpha.saveModel(role, body()));
      setApiKey('');
      toast(t('models.saved', { role: t(`models.role.${role}`) }), 'success');
    } catch (err) {
      setResult({ ok: false, text: (err as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const test = async () => {
    setBusy('test');
    setResult(null);
    try {
      const { reply, ms } = await alpha.testModel(role, body());
      setResult({ ok: true, text: t('models.testOk', { reply, ms }) });
    } catch (err) {
      setResult({ ok: false, text: (err as Error).message });
    } finally {
      setBusy(null);
    }
  };

  return (
    <form onSubmit={e => void save(e)} className="space-y-2.5">
      <div className="flex items-baseline justify-between">
        <span className="text-[14px] font-semibold">{t(`models.role.${role}`)}</span>
        {view && <span className="text-[12px] text-muted">{t('models.updatedAt', { time: localTime(view.updatedAt) })}</span>}
      </div>
      <div className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-2">
        <select value={protocol} onChange={e => setProtocol(e.target.value as Protocol)} className="input">
          {PROTOCOLS.map(p => <option key={p} value={p}>{p}</option>)}
        </select>
        <input value={model} onChange={e => setModel(e.target.value)} placeholder={t('models.modelPh')} className="input" required />
      </div>
      <input value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder={t('models.baseUrlPh')} className="input w-full" />
      <div className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-2">
        <input value={effort} onChange={e => setEffort(e.target.value)} placeholder={t('models.effortPh')} className="input" />
        <input type="password" value={apiKey} onChange={e => setApiKey(e.target.value)} autoComplete="off"
               placeholder={view ? t('models.keyKept', { tail: view.apiKeyTail }) : t('models.keyPh')}
               className="input" required={!view} />
      </div>
      <div className="flex items-center gap-2">
        <Button type="button" icon={Plug} loading={busy === 'test'} disabled={busy !== null || !model.trim()} onClick={() => void test()}>
          {t('models.test')}
        </Button>
        <Button type="submit" variant="primary" icon={Save} loading={busy === 'save'} disabled={busy !== null}>
          {t('models.save')}
        </Button>
      </div>
      {result && <p className={cx('text-[12.5px] break-all', result.ok ? 'text-gain' : 'text-loss')}>{result.text}</p>}
    </form>
  );
}
