import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { KeyRound, Loader2, PlugZap, Route, Trash2 } from 'lucide-react';
import { jevApi } from '../api';
import { Button } from './ui/button';
import { useToast } from './ui/use-toast';
import type { JevConfigView, JevSaveRequest } from '../types';

const DEFAULT_BASE_URL = 'https://api.typesafe.ai';
const DEFAULT_MODEL = 'jev-latest';
const EMPTY: JevSaveRequest = { baseUrl: '', model: '', apiKey: '' };

/**
 * Jev 决策模型配置（一人一份，与端点库分开）。
 * 配好后研判对话的专家派发先问 Jev；没配或调不通自动回落到对话·轻模型的路由，所以是可选项。
 * 表单常驻不折叠：就三个框，已配置时从库里预填，key 留空=不换。
 */
export function JevConfig() {
  const { toast } = useToast();
  const { t } = useTranslation(['ai', 'common']);
  const [saved, setSaved] = useState<JevConfigView | null>(null);
  const [form, setForm] = useState<JevSaveRequest>(EMPTY);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [removing, setRemoving] = useState(false);

  const load = useCallback(() => {
    jevApi.get().catch(() => null).then(c => {
      setSaved(c);
      setForm(c ? { baseUrl: c.baseUrl, model: c.model, apiKey: '' } : EMPTY);
    }).finally(() => setLoaded(true));
  }, []);

  useEffect(() => { load(); }, [load]);

  const patch = (p: Partial<JevSaveRequest>) => setForm(f => ({ ...f, ...p }));
  const exists = saved != null;
  // 未配置时 key 必填；已配置留空=沿用
  const keyMissing = !exists && !form.apiKey.trim();

  const save = async () => {
    setSaving(true);
    try {
      await jevApi.save(form);
      toast(t('toast.jevSaved'), 'success');
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.saveFailed'), 'error');
    } finally {
      setSaving(false);
    }
  };

  // 探测不触发重载：重载会用库里旧配置冲掉表单里未保存的 URL/key
  const test = async () => {
    setTesting(true);
    try {
      await jevApi.test(form);
      toast(t('endpoint.testOk'), 'success');
    } catch (e) {
      toast((e as Error).message || t('endpoint.testFailed'), 'error');
    } finally {
      setTesting(false);
    }
  };

  const remove = async () => {
    if (!window.confirm(t('jev.removeConfirm'))) return;
    setRemoving(true);
    try {
      await jevApi.remove();
      toast(t('toast.jevRemoved'), 'success');
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setRemoving(false);
    }
  };

  if (!loaded) return null;

  return (
    <div className="rounded-lg pt-card p-4 sm:p-5 space-y-3">
      <div className="flex items-center gap-2 flex-wrap">
        <Route className="w-4 h-4 text-primary" />
        <span className="text-sm font-black">{t('jev.title')}</span>
        <span className="text-[10px] text-muted-foreground num">
          {saved ? t('jev.configured', { model: saved.model, tail: saved.apiKeyTail }) : t('jev.notConfigured')}
        </span>
      </div>
      <p className="text-[11px] text-muted-foreground">{t('jev.intro')}</p>

      <div className="grid sm:grid-cols-3 gap-3">
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">{t('jev.baseUrl')}</span>
          <input value={form.baseUrl} onChange={e => patch({ baseUrl: e.target.value })}
                 placeholder={DEFAULT_BASE_URL}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">{t('jev.model')}</span>
          <input value={form.model} onChange={e => patch({ model: e.target.value })}
                 placeholder={DEFAULT_MODEL}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold flex items-center gap-1">
            <KeyRound className="w-3 h-3" /> API Key
            {exists && <span className="text-muted-foreground/70 font-normal">{t('endpoint.keyTail', { tail: saved.apiKeyTail })}</span>}
          </span>
          <input value={form.apiKey} onChange={e => patch({ apiKey: e.target.value })}
                 type="password"
                 placeholder={exists ? t('endpoint.keyPhExists') : t('endpoint.keyPh')}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
      </div>
      <span className="text-[10px] text-muted-foreground/70 block">{t('jev.defaultsHint')}</span>

      <div className="flex items-center gap-2 flex-wrap">
        <button type="button" onClick={() => void test()} disabled={testing || keyMissing}
                title={t('endpoint.testTitle')}
                className="border border-border hover:bg-surface-hover rounded-lg px-2.5 h-9 text-xs font-bold text-primary flex items-center gap-1 disabled:opacity-50">
          {testing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <PlugZap className="w-3.5 h-3.5" />}
          {t('endpoint.test')}
        </button>
        <div className="ml-auto flex items-center gap-2">
          {exists && (
            <Button size="sm" variant="outline" disabled={removing} onClick={() => void remove()}>
              {removing ? <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" /> : <Trash2 className="w-3.5 h-3.5 mr-1" />}
              {t('jev.remove')}
            </Button>
          )}
          <Button size="sm" disabled={saving || keyMissing} onClick={() => void save()}>
            {saving && <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" />} {t('common:save')}
          </Button>
        </div>
      </div>
    </div>
  );
}
