import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { Loader2, Pencil, Plug, Plus } from 'lucide-react';
import { llmEndpointApi, traderApi, workbenchApi } from '../api';
import { DEFAULT_CONTEXT_WINDOW, LlmEndpointForm, type LlmEndpointValue } from './LlmEndpointForm';
import { LlmEndpointSelect } from './LlmEndpointSelect';
import { Stepper } from './Stepper';
import { SureButton } from './SureButton';
import { Switch } from './ui/switch';
import { useToast } from './ui/use-toast';
import i18n from '../i18n';
import { cn } from '../lib/utils';
import type { LlmBindings, LlmEndpointView, LlmPurpose, TraderOwnerView, WorkbenchPrefs } from '../types';

// webSearch 默认勾上：能声明搜索的协议就声明，保存时探测，上游不支持会拦下请用户取消勾选；openai 协议后端归一 false
const EMPTY: LlmEndpointValue = {
  name: '', apiProtocol: 'openai', baseUrl: '', model: '', reasoningEffort: '', apiKey: '', webSearch: true,
  antigravity: false, contextWindow: DEFAULT_CONTEXT_WINDOW,
};

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

/** Base URL 只露主机部分 */
const host = (url: string) => url.replace(/^https?:\/\//, '');

/**
 * 模型配置（BYOK 总配置，样式见 AiAgent.css）：端点库（一条一块，新增和编辑是同一份表单，就地展开）+ 谁用哪条 + 后台子 agent。
 * 全站要选模型的地方（对话 / 交易员 / 复盘教练）都从这里配的端点里选；只配一条时它就是默认，谁都用它。
 */
export function ModelConfig() {
  const { toast } = useToast();
  const { t } = useTranslation(['ai', 'common']);
  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  const [bindings, setBindings] = useState<LlmBindings>({});
  const [trader, setTrader] = useState<TraderOwnerView | null>(null);
  const [prefs, setPrefs] = useState<WorkbenchPrefs | null>(null);
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
      workbenchApi.prefs().catch(() => null),
    ]).then(([eps, b, t, p]) => {
      setEndpoints(eps);
      setBindings(b);
      setTrader(t);
      setPrefs(p);
    }).finally(() => setLoaded(true));
  }, []);

  /** 后台子 agent 的开关/上限：先落界面，失败再滚回 */
  const savePrefs = async (patch: Partial<Pick<WorkbenchPrefs, 'subAgentsEnabled' | 'subAgentLimit'>>) => {
    if (!prefs) return;
    const next = { ...prefs, ...patch };
    setPrefs(next);
    try {
      setPrefs(await workbenchApi.savePrefs({ subAgentsEnabled: next.subAgentsEnabled, subAgentLimit: next.subAgentLimit }));
      toast(t('toast.prefsSaved'), 'success');
    } catch (e) {
      setPrefs(prefs);
      toast((e as Error).message || t('toast.saveFailed'), 'error');
    }
  };

  useEffect(() => { load(); }, [load]);

  const startCreate = () => { setForm(EMPTY); setEditing({ id: null }); };
  const startEdit = (e: LlmEndpointView) => {
    setForm({
      name: e.name, apiProtocol: e.apiProtocol, baseUrl: e.baseUrl, model: e.model,
      reasoningEffort: e.reasoningEffort ?? '', apiKey: '',   // 明文 key 不出服务端，改 key 才填
      webSearch: e.webSearch, antigravity: e.antigravity, contextWindow: e.contextWindow,
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

  /** 删除点第一下：说清删的是哪条；正被用着的再说一句会回落到默认端点 */
  const warnRemove = (e: LlmEndpointView) => {
    const used = usagesOf(e, bindings, trader != null).map(u => u.label);
    toast(t('model.removeConfirm', { name: e.name, model: e.model, warn: '' }), 'info',
      used.length ? { description: t('model.removeWarn', { used: used.join(' / ') }).trim() } : undefined);
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
    return <div className="empty"><Loader2 className="ic animate-spin" /><span>{t('loadingConfig')}</span></div>;
  }

  const formActions = (
    <>
      <button type="button" className="btn" onClick={() => setEditing(null)}>{t('common:cancel')}</button>
      <button type="button" className={cn('btn ink', saving && 'busy')} onClick={() => void save()}><span>{t('common:save')}</span></button>
    </>
  );

  return (
    <>
      {/* ===== 端点库 ===== */}
      <section className="sec ep-sec">
        <div className="sec-h">
          <h2>{t('model.title')}{endpoints.length > 0 && <small>{t('model.count', { count: endpoints.length })}</small>}</h2>
          {!(editing && editing.id == null) && (
            <button type="button" className="btn ink" onClick={startCreate}><Plus className="ic" />{t('model.add')}</button>
          )}
        </div>
        <p className="intro">{t('model.intro')}</p>
        <div className="eps">
          {/* 新增：一块里就是那份表单，第一条会自动成为默认 */}
          {editing != null && editing.id == null && (
            <div className="blk edit">
              {endpoints.length === 0 && <p className="intro">{t('model.firstIsDefault')}</p>}
              <LlmEndpointForm value={form} onChange={patch => setForm(f => ({ ...f, ...patch }))}
                               onDetect={() => llmEndpointApi.listModels(form)} onTest={() => llmEndpointApi.test(form)}
                               actions={formActions} />
            </div>
          )}

          {endpoints.length === 0 && editing == null && (
            <div className="empty">
              <span className="ib0"><Plug className="ic" /></span>
              <span>{t('model.empty')}</span>
            </div>
          )}

          {endpoints.map(e => {
            const usages = usagesOf(e, bindings, trader != null);
            const isEditing = editing?.id === e.id;
            return (
              <div key={e.id} className={cn('blk', isEditing && 'edit')}>
                <div className="ep-h">
                  <b>{e.name}</b>
                  {e.isDefault && <span className="tag ink">{t('model.default')}</span>}
                  <span className="acts">
                    {!e.isDefault && (
                      <button type="button" className="btn sm" disabled={busyId === e.id} title={t('model.setDefaultTitle')}
                              onClick={() => void run(e.id, () => llmEndpointApi.setDefault(e.id), t('toast.setDefault', { name: e.name }))}>
                        {t('model.setDefault')}
                      </button>
                    )}
                    <button type="button" className="btn sm" aria-pressed={isEditing}
                            onClick={() => (isEditing ? setEditing(null) : startEdit(e))}>
                      <Pencil className="ic" />{t('common:edit')}
                    </button>
                    <SureButton className={cn('btn sm', busyId === e.id && 'busy')} idleClassName="plain" sureLabel={t('model.removeSure')}
                                onArm={() => warnRemove(e)}
                                onConfirm={() => void run(e.id, () => llmEndpointApi.remove(e.id), t('toast.endpointRemoved'))}>
                      <span>{t('common:delete')}</span>
                    </SureButton>
                  </span>
                </div>
                <div className="ep-l">
                  <span className="tag sm">{e.apiProtocol}</span>
                  <code>{e.model}</code>
                  <span>{host(e.baseUrl)}</span>
                </div>
                <div className="ep-l">
                  <span>{t('model.effortTail', { effort: e.reasoningEffort || t('effort.default'), tail: e.apiKeyTail })}</span>
                  <span>{t('model.contextOf', { k: e.contextWindow / 1000 })}</span>
                  {e.webSearch && <span>{t('endpoint.webSearch')}</span>}
                </div>
                {/* 用途：谁在用这条端点。经默认落上来的标"（默认）"，提醒改默认会连带影响 */}
                <div className="ep-u">
                  {usages.length === 0
                    ? <span className="none">{t('model.unused')}</span>
                    : usages.map(u => <span key={u.label} className="tag">{u.label}{u.viaDefault ? t('model.viaDefault') : ''}</span>)}
                </div>
                {isEditing && (
                  <LlmEndpointForm value={form} onChange={patch => setForm(f => ({ ...f, ...patch }))} exists keyTail={editing?.keyTail}
                                   onDetect={() => llmEndpointApi.listModels(form, e.id)} onTest={() => llmEndpointApi.test(form, e.id)}
                                   actions={formActions} />
                )}
              </div>
            );
          })}
        </div>
      </section>

      {endpoints.length > 0 && (
        <>
          {/* ===== 用途绑定 ===== */}
          <section className="sec bind-sec">
            <div className="sec-h"><h2>{t('model.bindTitle')}</h2></div>
            <div className="bind">
              <div>
                <div className="k"><b>{t('model.chatMain')}</b><span>{t('model.chatMainHint')}</span></div>
                <LlmEndpointSelect endpoints={endpoints} value={bindings.CHAT_MAIN ?? null} onChange={id => void bind('CHAT_MAIN', id)} />
              </div>
              <div>
                <div className="k"><b>{t('model.chatLight')}</b><span>{t('model.chatLightHint')}</span></div>
                <LlmEndpointSelect endpoints={endpoints} value={bindings.CHAT_LIGHT ?? null} onChange={id => void bind('CHAT_LIGHT', id)}
                                   followLabel={chatMain
                                     ? t('model.sameAsMain', { name: chatMain.name, model: chatMain.model })
                                     : t('model.sameAsMainPlain')} />
              </div>
              <div>
                <div className="k">
                  <b>{t('term.trader')}</b>
                  <span>{t('model.traderHint', { name: trader?.pub.name ?? t('term.trader') })}</span>
                </div>
                {trader ? (
                  <div className="v">
                    {traderEndpoint ? `${traderEndpoint.name} · ${traderEndpoint.model}` : t('model.noEndpoint')}
                    {bindings.TRADER == null && traderEndpoint ? t('model.viaDefault') : ''}
                    {' '}<Link to="/my-trader" className="linkb whitespace-nowrap">{t('model.editInMyTrader')}</Link>
                  </div>
                ) : (
                  <div className="v"><Trans ns="ai" i18nKey="model.noTrader" components={[<Link to="/my-trader" className="linkb" />]} /></div>
                )}
              </div>
              <div>
                <div className="k"><b>{t('model.coach')}</b><span>{t('model.coachHint')}</span></div>
                <div className="v">{t('model.coachValue')}</div>
              </div>
            </div>
          </section>

          {/* ===== 后台子 agent：主 agent 能不能派、同时最多几个 ===== */}
          {prefs && (
            <section className="sec sub-sec">
              <div className="sec-h"><h2>{t('model.subAgentTitle')}</h2></div>
              <p className="intro">{t('model.subAgentIntro')}</p>
              <div className="swg">
                <div className="sw-row">
                  <div><b>{t('model.subAgentOn')}</b><span>{t('model.subAgentOnHint')}</span></div>
                  <Switch checked={prefs.subAgentsEnabled} onChange={v => void savePrefs({ subAgentsEnabled: v })} label={t('model.subAgentOn')} />
                </div>
                <div className={cn('sw-row', !prefs.subAgentsEnabled && 'off')}>
                  <div><b>{t('model.subAgentLimit')}</b><span>{t('model.subAgentLimitHint', { max: prefs.maxLimit })}</span></div>
                  <Stepper value={prefs.subAgentLimit} min={1} max={prefs.maxLimit} disabled={!prefs.subAgentsEnabled}
                           onChange={n => void savePrefs({ subAgentLimit: n })} label={t('model.subAgentLimit')}
                           lessLabel={t('model.less')} moreLabel={t('model.more')} />
                </div>
              </div>
            </section>
          )}
        </>
      )}
    </>
  );
}
