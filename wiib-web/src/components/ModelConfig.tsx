import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { Bot, KeyRound, Loader2, MessagesSquare, Pencil, Plus, Rocket, Sparkles, Star, Trash2, X } from 'lucide-react';
import { llmEndpointApi, traderApi } from '../api';
import { LlmEndpointForm, type LlmEndpointValue } from './LlmEndpointForm';
import { LlmEndpointSelect } from './LlmEndpointSelect';
import { JevConfig } from './JevConfig';
import { Button } from './ui/button';
import { useToast } from './ui/use-toast';
import i18n from '../i18n';
import { cn } from '../lib/utils';
import type { LlmBindings, LlmEndpointView, LlmPurpose, TraderOwnerView } from '../types';

// webSearch 默认勾上：能声明搜索的协议就声明，上游不支持会自动退回不搜；openai 协议后端归一 false
const EMPTY: LlmEndpointValue = { name: '', apiProtocol: 'openai', baseUrl: '', model: '', reasoningEffort: '', apiKey: '', webSearch: true };

/** 一条端点被谁用着：主标签 + 是否经"默认"落到它头上 */
interface Usage { label: string; viaDefault: boolean; }

/**
 * 计算每条端点的用途标签。绑定优先，没绑的用途落到默认端点；
 * 复盘教练不落库（复盘配置台每局现选），默认端点上标一条"复盘教练"提示它是缺省项。
 * 标签在函数体里现查词表：存模块级常量的话切语言后它不会变。
 */
function usagesOf(e: LlmEndpointView, bindings: LlmBindings, hasTrader: boolean): Usage[] {
  const out: Usage[] = [];
  const hit = (purpose: LlmPurpose, label: string, fallbackToDefault: boolean) => {
    const bound = bindings[purpose];
    if (bound === e.id) out.push({ label, viaDefault: false });
    else if (bound == null && fallbackToDefault && e.isDefault) out.push({ label, viaDefault: true });
  };
  hit('CHAT_MAIN', i18n.t('ai:model.usageChat'), true);
  hit('CHAT_LIGHT', i18n.t('ai:model.usageChatLight'), false);   // 轻模型不绑=复用主模型，不算落到默认
  if (hasTrader) hit('TRADER', i18n.t('ai:term.trader'), true);
  if (e.isDefault) out.push({ label: i18n.t('ai:model.coach'), viaDefault: true });
  return out;
}

/**
 * 模型配置（BYOK 总配置）：端点库 + 用途绑定。
 * 全站要选模型的地方（对话 / 交易员 / 复盘教练）都从这里配的端点里选；只配一条时它就是默认，谁都用它。
 * 行为分析不在这里——它走平台管理员配的模型，不烧用户的 key。
 */
export function ModelConfig() {
  const { toast } = useToast();
  const { t } = useTranslation(['ai', 'common']);
  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  const [bindings, setBindings] = useState<LlmBindings>({});
  const [trader, setTrader] = useState<TraderOwnerView | null>(null);
  const [loaded, setLoaded] = useState(false);

  // 表单：null=收起；{ id: null }=新增；{ id: n }=编辑
  const [editing, setEditing] = useState<{ id: number | null; keyTail?: string } | null>(null);
  const [form, setForm] = useState<LlmEndpointValue>(EMPTY);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(() => {
    Promise.all([
      llmEndpointApi.list().catch(() => [] as LlmEndpointView[]),
      llmEndpointApi.bindings().catch(() => ({} as LlmBindings)),
      traderApi.mine().catch(() => null),
    ]).then(([eps, b, t]) => {
      setEndpoints(eps);
      setBindings(b);
      setTrader(t);
    }).finally(() => setLoaded(true));
  }, []);

  useEffect(() => { load(); }, [load]);

  const startCreate = () => { setForm(EMPTY); setEditing({ id: null }); };
  const startEdit = (e: LlmEndpointView) => {
    setForm({
      name: e.name, apiProtocol: e.apiProtocol, baseUrl: e.baseUrl, model: e.model,
      reasoningEffort: e.reasoningEffort ?? '', apiKey: '',   // 明文 key 不出服务端，改 key 才填
      webSearch: e.webSearch,
    });
    setEditing({ id: e.id, keyTail: e.apiKeyTail });
  };

  const save = async () => {
    setSaving(true);
    try {
      if (editing?.id != null) await llmEndpointApi.update(editing.id, form);
      else await llmEndpointApi.create(form);
      toast(editing?.id != null ? t('toast.endpointUpdated') : t('toast.endpointAdded'), 'success');
      setEditing(null);
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.saveFailed'), 'error');
    } finally {
      setSaving(false);
    }
  };

  const run = async (id: number, action: () => Promise<unknown>, okMsg: string) => {
    setBusyId(id);
    try {
      await action();
      toast(okMsg, 'success');
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setBusyId(null);
    }
  };

  const remove = (e: LlmEndpointView) => {
    const used = usagesOf(e, bindings, trader != null).map(u => u.label);
    const warn = used.length ? t('model.removeWarn', { used: used.join(' / ') }) : '';
    if (!window.confirm(t('model.removeConfirm', { name: e.name, model: e.model, warn }))) return;
    void run(e.id, () => llmEndpointApi.remove(e.id), t('toast.endpointRemoved'));
  };

  const bind = async (purpose: LlmPurpose, id: number | null) => {
    try {
      await llmEndpointApi.bind(purpose, id);
      setBindings(b => {
        const next = { ...b };
        if (id == null) delete next[purpose]; else next[purpose] = id;
        return next;
      });
    } catch (e) {
      toast((e as Error).message || t('toast.bindFailed'), 'error');
    }
  };

  const dft = endpoints.find(e => e.isDefault);
  const traderEndpoint = trader
    ? (bindings.TRADER != null ? endpoints.find(e => e.id === bindings.TRADER) : dft)
    : null;
  /** 对话主模型实际用的那条（显式绑定或默认），轻模型下拉的"同主模型"要说清同的是谁 */
  const chatMain = bindings.CHAT_MAIN != null ? endpoints.find(e => e.id === bindings.CHAT_MAIN) : dft;

  if (!loaded) {
    return (
      <div className="flex items-center gap-2 py-8 justify-center text-xs text-muted-foreground">
        <Loader2 className="w-4 h-4 animate-spin" /> {t('loadingConfig')}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* ===== 端点库 ===== */}
      <div className="rounded-lg pt-card p-4 sm:p-5 space-y-3">
        <div className="flex items-center gap-2 flex-wrap">
          <KeyRound className="w-4 h-4 text-primary" />
          <span className="text-sm font-black">{t('model.title')}</span>
          <span className="text-[10px] text-muted-foreground">{t('model.count', { count: endpoints.length })}</span>
          <Button size="sm" className="ml-auto" onClick={startCreate} disabled={editing != null && editing.id == null}>
            <Plus className="w-3.5 h-3.5 mr-1" /> {t('model.add')}
          </Button>
        </div>
        <p className="text-[11px] text-muted-foreground">
          {t('model.intro')}
        </p>

        {endpoints.length === 0 && editing == null && (
          <div className="rounded-lg border border-dashed border-border bg-card-2/50 py-8 px-4 flex flex-col items-center gap-2.5 text-center">
            <Bot className="w-8 h-8 text-muted-foreground/50" />
            <p className="text-xs text-muted-foreground">{t('model.empty')}</p>
            <Button size="sm" onClick={startCreate}><Plus className="w-3.5 h-3.5 mr-1" /> {t('model.add')}</Button>
          </div>
        )}

        <div className="space-y-2">
          {endpoints.map(e => {
            const usages = usagesOf(e, bindings, trader != null);
            const isEditing = editing?.id === e.id;
            return (
              <div key={e.id} className={cn('rounded-lg border bg-card-2/50 p-3 space-y-2',
                isEditing ? 'border-primary/60' : 'border-border')}>
                <div className="flex items-start gap-2 flex-wrap">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-xs font-black truncate">{e.name}</span>
                      <span className="text-[11px] num text-primary font-bold truncate">{e.model}</span>
                      {e.isDefault && (
                        <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-primary/10 text-primary flex items-center gap-1">
                          <Star className="w-3 h-3" /> {t('model.default')}
                        </span>
                      )}
                    </div>
                    <div className="text-[10px] text-muted-foreground num truncate mt-0.5">
                      {e.apiProtocol}{e.webSearch ? '+search' : ''} · {e.baseUrl} · {t('model.effortTail', {
                        effort: e.reasoningEffort || t('effort.default'), tail: e.apiKeyTail,
                      })}
                    </div>
                    {/* 用途：谁在用这条端点。经默认落上来的标"默认"，提醒改默认会连带影响 */}
                    <div className="flex items-center gap-1.5 flex-wrap mt-1.5">
                      {usages.length === 0
                        ? <span className="text-[10px] text-muted-foreground/70">{t('model.unused')}</span>
                        : usages.map(u => (
                          <span key={u.label} className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded',
                            u.viaDefault ? 'bg-muted text-muted-foreground' : 'bg-primary/10 text-primary')}>
                            {u.label}{u.viaDefault ? t('model.viaDefault') : ''}
                          </span>
                        ))}
                    </div>
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    {!e.isDefault && (
                      <button type="button" disabled={busyId === e.id}
                        onClick={() => void run(e.id, () => llmEndpointApi.setDefault(e.id), t('toast.setDefault', { name: e.name }))}
                        title={t('model.setDefaultTitle')}
                        className="h-8 px-2 rounded-md border border-border text-[11px] font-bold text-muted-foreground hover:text-foreground hover:bg-surface-hover flex items-center gap-1 disabled:opacity-50">
                        <Star className="w-3.5 h-3.5" /> {t('model.setDefault')}
                      </button>
                    )}
                    <button type="button" onClick={() => (isEditing ? setEditing(null) : startEdit(e))} title={t('common:edit')}
                      className="w-8 h-8 rounded-md border border-border text-muted-foreground hover:text-foreground hover:bg-surface-hover flex items-center justify-center">
                      {isEditing ? <X className="w-3.5 h-3.5" /> : <Pencil className="w-3.5 h-3.5" />}
                    </button>
                    <button type="button" onClick={() => remove(e)} disabled={busyId === e.id} title={t('common:delete')}
                      className="w-8 h-8 rounded-md border border-border text-muted-foreground hover:text-loss hover:bg-surface-hover flex items-center justify-center disabled:opacity-50">
                      {busyId === e.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                    </button>
                  </div>
                </div>

                {isEditing && (
                  <div className="pt-2 border-t border-border/60 space-y-3">
                    <LlmEndpointForm
                      value={form}
                      onChange={patch => setForm(f => ({ ...f, ...patch }))}
                      exists
                      keyTail={editing?.keyTail}
                      onDetect={() => llmEndpointApi.listModels(form, e.id)}
                      onTest={() => llmEndpointApi.test(form, e.id)}
                    />
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="outline" onClick={() => setEditing(null)}>{t('common:cancel')}</Button>
                      <Button size="sm" disabled={saving} onClick={() => void save()}>
                        {saving && <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" />} {t('common:save')}
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* 新增表单 */}
        {editing != null && editing.id == null && (
          <div className="rounded-lg border border-primary/60 bg-card-2/50 p-3 space-y-3">
            <div className="flex items-center gap-2">
              <Plus className="w-3.5 h-3.5 text-primary" />
              <span className="text-xs font-black">{t('model.add')}</span>
              {endpoints.length === 0 && <span className="text-[10px] text-muted-foreground">{t('model.firstIsDefault')}</span>}
            </div>
            <LlmEndpointForm
              value={form}
              onChange={patch => setForm(f => ({ ...f, ...patch }))}
              onDetect={() => llmEndpointApi.listModels(form)}
              onTest={() => llmEndpointApi.test(form)}
            />
            <div className="flex justify-end gap-2">
              <Button size="sm" variant="outline" onClick={() => setEditing(null)}>{t('common:cancel')}</Button>
              <Button size="sm" disabled={saving} onClick={() => void save()}>
                {saving && <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" />} {t('common:save')}
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* ===== 用途绑定 ===== */}
      <div className="rounded-lg pt-card p-4 sm:p-5 space-y-3">
        <div className="flex items-center gap-2">
          <MessagesSquare className="w-4 h-4 text-primary" />
          <span className="text-sm font-black">{t('model.bindTitle')}</span>
        </div>
        <div className="grid sm:grid-cols-2 gap-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold flex items-center gap-1"><MessagesSquare className="w-3 h-3" /> {t('model.chatMain')}</span>
            <LlmEndpointSelect endpoints={endpoints} value={bindings.CHAT_MAIN ?? null}
              onChange={id => void bind('CHAT_MAIN', id)} className="w-full" />
            <span className="text-[10px] text-muted-foreground/70 block">{t('model.chatMainHint')}</span>
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold flex items-center gap-1"><MessagesSquare className="w-3 h-3" /> {t('model.chatLight')}</span>
            <LlmEndpointSelect endpoints={endpoints} value={bindings.CHAT_LIGHT ?? null}
              onChange={id => void bind('CHAT_LIGHT', id)} className="w-full"
              followLabel={chatMain
                ? t('model.sameAsMain', { name: chatMain.name, model: chatMain.model })
                : t('model.sameAsMainPlain')} />
            <span className="text-[10px] text-muted-foreground/70 block">{t('model.chatLightHint')}</span>
          </label>
          <div className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold flex items-center gap-1"><Rocket className="w-3 h-3" /> {t('term.trader')}</span>
            {trader ? (
              <div className="h-9 rounded-lg border border-border bg-card-2 px-2.5 flex items-center gap-2 text-xs">
                <span className="font-bold truncate">
                  {traderEndpoint ? `${traderEndpoint.name} · ${traderEndpoint.model}` : t('model.noEndpoint')}
                  {bindings.TRADER == null && traderEndpoint ? t('model.viaDefault') : ''}
                </span>
                <Link to="/my-trader" className="ml-auto text-primary font-bold hover:underline shrink-0">{t('model.editInMyTrader')}</Link>
              </div>
            ) : (
              <div className="h-9 rounded-lg border border-border bg-card-2 px-2.5 flex items-center text-xs text-muted-foreground">
                <Trans ns="ai" i18nKey="model.noTrader"
                       components={[<Link to="/my-trader" className="text-primary font-bold hover:underline mx-1" />]} />
              </div>
            )}
            <span className="text-[10px] text-muted-foreground/70 block">
              {t('model.traderHint', { name: trader?.pub.name ?? t('term.trader') })}
            </span>
          </div>
          <div className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold flex items-center gap-1"><Sparkles className="w-3 h-3" /> {t('model.coach')}</span>
            <div className="h-9 rounded-lg border border-border bg-card-2 px-2.5 flex items-center text-xs text-muted-foreground">
              {t('model.coachValue')}
            </div>
            <span className="text-[10px] text-muted-foreground/70 block">{t('model.coachHint')}</span>
          </div>
        </div>
      </div>

      {/* ===== Jev 决策模型（可选）：与端点库分开的一份配置 ===== */}
      <JevConfig />

      <p className="text-[10px] text-muted-foreground/70 px-1">
        {t('model.behaviorNote')}
      </p>
    </div>
  );
}
