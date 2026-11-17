import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router-dom';
import { Pencil, Plus, RefreshCw } from 'lucide-react';
import { useUserStore } from '../stores/userStore';
import { adminApi } from '../api';
import type { AiKeyConfig, AiKeyView, AiModelAssignment, InviteCode } from '../types';
import { useToast } from '../components/ui/use-toast';
import { Select } from '../components/ui/select';
import { useSubmitFx } from '../hooks/useSubmitFx';
import { FeedStreams } from '../components/FeedStreams';
import { JvmMonitors } from '../components/JvmMonitors';
import { SureButton } from '../components/SureButton';
import { DEFAULT_CONTEXT_WINDOW, LlmEndpointForm, type LlmEndpointValue } from '../components/LlmEndpointForm';
import { cn } from '../lib/utils';
import i18n from '../i18n';
import './Admin.css';

/** 功能位名称的词表 key，顺序即页面上的行序。常量在组件外，存翻译结果会在模块加载那一刻定死，切语言不跟着变 */
const FUNCTION_LABEL_KEYS: Record<string, string> = {
  'news-translation': 'admin.fn.newsTranslation',
  'news-translation-fallback': 'admin.fn.newsTranslationFallback',
};
/** 可空位：下拉多一项"不用"，与后端 AiAgentRuntimeManager.OPTIONAL_FUNCTIONS 同步 */
const OPTIONAL_FUNCTIONS = new Set(['news-translation-fallback']);

/** 手动补拉最多补最近几条，与后端 MAX_BACKFILL_COUNT 同步 */
const MAX_BACKFILL_COUNT = 500;

/** 配置上显示的协议名 */
const PROTO_NAME: Record<string, string> = { openai: 'ChatCompletions', responses: 'Responses', anthropic: 'Anthropic', gemini: 'Gemini' };

/** 新增时那份空表单 */
const BLANK_FORM: LlmEndpointValue = {
  name: '', apiProtocol: 'openai', baseUrl: '', model: '', reasoningEffort: '', apiKey: '', webSearch: false,
  antigravity: false, contextWindow: DEFAULT_CONTEXT_WINDOW,
};

/** 结算与风控那组手动任务：钮的 key、调哪个接口、钮上的字 */
const SETTLE_TASKS = [
  { key: 'bankruptcy', run: adminApi.bankruptcyCheck, labelKey: 'admin.manual.bankruptcy' },
  { key: 'interest', run: adminApi.accrueInterest, labelKey: 'admin.manual.interest' },
  { key: 'snapshot', run: adminApi.assetSnapshot, labelKey: 'admin.manual.snapshot' },
];

/** 表单值（与 BYOK 同一份表单）→ 平台配置；平台位没有联网搜索 */
function toKeyConfig(id: number | undefined, f: LlmEndpointValue): AiKeyConfig {
  return {
    id, configName: f.name, apiKey: f.apiKey, baseUrl: f.baseUrl, model: f.model,
    reasoningEffort: f.reasoningEffort, apiProtocol: f.apiProtocol,
  };
}

/**
 * 管理页，只有 1 号用户进得来（样式见 Admin.css）。
 * 最上面一排三个进程的状况；下面左边是行情数据流和平台用的 LLM（配置、哪个功能用哪条），右边是杠杆日利率、邀请码和几样手动任务。
 */
export function Admin() {
  const { t } = useTranslation(['account', 'common']);
  const { user } = useUserStore();
  const { toast } = useToast();
  // 整页的提交钮共用一份转圈 / 打勾 / 晃两下，同一时间只跑一颗
  const fx = useSubmitFx();
  // 头一遍拉完没有：没拉完不说"暂无"
  const [loaded, setLoaded] = useState(false);
  const [interestRateDecimal, setInterestRateDecimal] = useState<number | null>(null);
  const [interestRatePct, setInterestRatePct] = useState('');
  const [aiKeys, setAiKeys] = useState<AiKeyView[]>([]);
  // null=没在填表；没 id=新增
  const [editingKey, setEditingKey] = useState<{ id?: number; keyTail?: string; form: LlmEndpointValue } | null>(null);
  const [assignmentsDraft, setAssignmentsDraft] = useState<AiModelAssignment[]>([]);
  const [inviteCodes, setInviteCodes] = useState<InviteCode[]>([]);
  const [inviteMaxUses, setInviteMaxUses] = useState('1');
  const [inviteCount, setInviteCount] = useState('1');
  // 快讯补拉：补最近几条
  const [backfillCount, setBackfillCount] = useState('100');

  const fetchInterestRate = useCallback(async () => {
    const rate = await adminApi.getDailyInterestRate();
    setInterestRateDecimal(rate);
    setInterestRatePct(rate > 0 ? String(rate * 100) : '0');
  }, []);

  const fetchAiKeys = useCallback(async () => {
    const list = await adminApi.listAiKeys();
    setAiKeys(list);
  }, []);

  const fetchAssignments = useCallback(async () => {
    const list = await adminApi.listAssignments();
    // 每个功能位固定一行：可空位没配时库里没行，也要给个下拉
    setAssignmentsDraft(Object.keys(FUNCTION_LABEL_KEYS).map(functionName => ({
      functionName,
      configId: list.find(a => a.functionName === functionName)?.configId ?? null,
    })));
  }, []);

  const fetchInviteCodes = useCallback(async () => {
    const list = await adminApi.listInviteCodes();
    setInviteCodes(list);
  }, []);

  // 四样一起拉，哪样没拉到就留着原来的；有没拉到的就抛出去
  const reload = useCallback(async () => {
    const results = await Promise.allSettled([fetchInterestRate(), fetchAiKeys(), fetchAssignments(), fetchInviteCodes()]);
    setLoaded(true);
    const failed = results.find((r): r is PromiseRejectedResult => r.status === 'rejected');
    if (failed) throw failed.reason;
  }, [fetchInterestRate, fetchAiKeys, fetchAssignments, fetchInviteCodes]);

  // 进页拉一遍；放进 then 里调，effect 里不直接写状态
  useEffect(() => {
    if (user?.id === 1) {
      void Promise.resolve().then(reload).catch(() => toast(i18n.t('common:loadFailed'), 'error'));
    }
  }, [user, reload, toast]);

  // 外层守卫保证 token 存在，user 为 null 只能是 fetchUser 还没回来 —— 等它。
  if (!user) return null;
  if (user.id !== 1) return <Navigate to="/" replace />;

  /** 没成时弹后端给的原话，没有就弹 fallback */
  const fail = (fallback: string) => (e: unknown) => toast((e as Error).message || fallback, 'error');
  /** 别的钮在转圈或打勾时，这颗先别点 */
  const off = (key: string) => fx.locked && !fx.cls(key);

  const handleSaveRate = () => {
    const pct = Number(interestRatePct);
    if (interestRatePct.trim() === '' || !Number.isFinite(pct) || pct < 0 || pct > 100) return fx.nope('rate');
    void fx.run('rate', async () => {
      const updated = await adminApi.setDailyInterestRate(pct / 100);
      setInterestRateDecimal(updated);
      setInterestRatePct(String(updated > 0 ? updated * 100 : 0));
    }, fail(t('admin.actionFailed')));
  };

  // ========== LLM 配置 ==========

  const handleSaveKey = async () => {
    if (!editingKey) return;
    const { name, apiKey, baseUrl, model } = editingKey.form;
    // 改已有配置 key 可以留空（不换）
    if (!name.trim() || (!editingKey.id && !apiKey.trim()) || !baseUrl.trim() || !model.trim()) {
      toast(t('admin.llm.required'), 'error');
      return fx.nope('saveKey');
    }
    const saved = await fx.run('saveKey', async () => {
      await adminApi.saveAiKey(toKeyConfig(editingKey.id, editingKey.form));
      // 加第一条配置时后端会自动种出必配功能位分配，两块都要重拉
      await Promise.all([fetchAiKeys(), fetchAssignments()]);
    }, fail(t('admin.llm.saveFailed')));
    if (saved) {
      setEditingKey(null);
      toast(t('admin.llm.saved'), 'success');
    }
  };

  const handleDeleteKey = async (id: number) => {
    const deleted = await fx.run(`key-${id}`, async () => {
      await adminApi.deleteAiKey(id);
      await fetchAiKeys();
    }, fail(t('admin.llm.deleteFailed')));
    if (deleted) toast(t('admin.llm.deleted'), 'success');
  };

  // ========== 哪个功能用哪条 LLM ==========

  /** 功能位显示名：认得的走词表，认不得的原样显示后端给的 functionName */
  const fnLabel = (functionName: string) => {
    const key = FUNCTION_LABEL_KEYS[functionName];
    return key ? t(key) : functionName;
  };

  const updateDraft = (functionName: string, configId: number | null) => {
    setAssignmentsDraft(prev =>
      prev.map(a => a.functionName === functionName ? { ...a, configId } : a)
    );
  };

  const handleSaveAssignments = async () => {
    const missing = assignmentsDraft.find(a => !a.configId && !OPTIONAL_FUNCTIONS.has(a.functionName));
    if (missing) {
      toast(t('admin.assign.noneSelected', { name: fnLabel(missing.functionName) }), 'error');
      return fx.nope('assign');
    }
    const saved = await fx.run('assign', async () => {
      await adminApi.saveAssignments(assignmentsDraft);
      await fetchAssignments();
    }, fail(t('admin.llm.saveFailed')));
    if (saved) toast(t('admin.assign.saved'), 'success');
  };

  // ========== 邀请码 ==========

  const handleGenerateInvites = async () => {
    const maxUses = Number(inviteMaxUses);
    const count = Number(inviteCount);
    if (!Number.isInteger(maxUses) || maxUses < 1 || !Number.isInteger(count) || count < 1) {
      toast(t('admin.invite.badArgs'), 'error');
      return fx.nope('invite');
    }
    const generated = await fx.run('invite', async () => {
      await adminApi.generateInviteCodes(maxUses, count);
      await fetchInviteCodes();
    }, fail(t('admin.invite.generateFailed')));
    if (generated) toast(t('admin.invite.generated'), 'success');
  };

  const handleDisableInvite = (id: number) => fx.run(`inv-${id}`, async () => {
    await adminApi.disableInviteCode(id);
    await fetchInviteCodes();
  }, fail(t('admin.invite.revokeFailed')));

  const copyInviteCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      toast(t('admin.invite.copied', { code }), 'success');
    } catch {
      toast(t('admin.invite.copyFailed'), 'error');
    }
  };

  // ========== 快讯补拉 ==========

  const handleBackfillNews = () => {
    const count = Number(backfillCount);
    if (!Number.isInteger(count) || count < 1 || count > MAX_BACKFILL_COUNT) {
      toast(t('admin.manual.backfillBad', { max: MAX_BACKFILL_COUNT }), 'error');
      return fx.nope('backfill');
    }
    void fx.run('backfill', async () => {
      const r = await adminApi.backfillNews(count);
      toast(t('admin.manual.backfillDone', { fetched: r.fetched, inserted: r.inserted }), 'success');
    }, fail(t('admin.actionFailed')));
  };

  // 没填模型的配置派不了活，下拉里不出
  const keyOptions = aiKeys.filter(k => k.model)
    .map(k => ({ value: String(k.id), label: t('admin.assign.option', { name: k.configName, model: k.model }) }));

  // 表单底下那两颗钮，新增和编辑共用
  const formActions = (
    <>
      <button type="button" className="btn" onClick={() => setEditingKey(null)}>{t('common:cancel')}</button>
      <button type="button" className={cn('btn ink', fx.cls('saveKey'))} disabled={off('saveKey')} onClick={() => void handleSaveKey()}>
        <span>{t('common:save')}</span>
      </button>
    </>
  );
  // 与 BYOK 同一份表单；拉模型/测连通用表单里的 key，不用先存，改已有配置时 key 留空就用已存的。
  // key：换编辑对象就重建，别把上一条检测到的模型清单带过来
  const keyForm = editingKey && (
    <LlmEndpointForm
      key={editingKey.id ?? 'new'}
      value={editingKey.form}
      onChange={patch => setEditingKey(prev => prev ? { ...prev, form: { ...prev.form, ...patch } } : prev)}
      exists={!!editingKey.id}
      keyTail={editingKey.keyTail}
      platform
      onDetect={() => adminApi.listAiKeyModels(toKeyConfig(editingKey.id, editingKey.form))}
      onTest={() => adminApi.testAiKey(toKeyConfig(editingKey.id, editingKey.form))}
      actions={formActions}
    />
  );

  return (
    <div className="page-shell">
      <div className="p-adm">
        <div className="pg-h">
          <h1>{t('admin.title')}</h1>
          <div className="r">
            {/* 因子挖掘只有管理员用，入口就放这儿；页面是 wiib-alpha 自己托管的，整页跳过去 */}
            <a href="/alpha/" className="linkb">{t('admin.alphaLink')}</a>
            <button type="button" className={cn('btn', fx.cls('reload'))} disabled={off('reload')}
                    onClick={() => void fx.run('reload', reload, fail(t('common:loadFailed')))}>
              <RefreshCw className="ic" /><span>{t('common:refresh')}</span>
            </button>
          </div>
        </div>

        {/* 三个进程的状况：订的是实时推送，不跟着上面那颗刷新走 */}
        <JvmMonitors />

        <div className="cols">
          <div>
            {/* feed 的行情数据流：自己拉自己订，管理员进页即见 */}
            <FeedStreams />

            {/* ===== 配置 LLM（一条 = key + baseUrl + model） ===== */}
            <section className="sec">
              <div className="sec-h">
                <h2>{t('admin.llm.title')}{aiKeys.length > 0 && <small>{aiKeys.length}</small>}</h2>
                {!(editingKey && editingKey.id == null) && (
                  <button type="button" className="btn ink" onClick={() => setEditingKey({ form: BLANK_FORM })}>
                    <Plus className="ic" />{t('admin.llm.add')}
                  </button>
                )}
              </div>
              <p className="intro">{t('admin.llm.hint')}</p>
              <div className="eps">
                {editingKey && editingKey.id == null && <div className="blk edit">{keyForm}</div>}
                {loaded && aiKeys.length === 0 && !editingKey && <div className="empty"><span>{t('admin.llm.empty')}</span></div>}
                {aiKeys.map(key => {
                  const isEditing = editingKey?.id === key.id;
                  return (
                    <div key={key.id} className={cn('blk', isEditing && 'edit')}>
                      <div className="ep-h">
                        <b>{key.configName}</b>
                        <span className="acts">
                          <button type="button" className="btn sm" aria-pressed={isEditing}
                                  onClick={() => setEditingKey(isEditing ? null : { id: key.id, keyTail: key.apiKeyTail, form: {
                                    ...BLANK_FORM, name: key.configName, apiProtocol: key.apiProtocol || 'openai', baseUrl: key.baseUrl,
                                    model: key.model || '', reasoningEffort: key.reasoningEffort || '',
                                  } })}>
                            <Pencil className="ic" />{t('common:edit')}
                          </button>
                          <SureButton className={cn('btn sm', fx.cls(`key-${key.id}`))} idleClassName="plain" sureLabel={t('admin.llm.removeSure')}
                                      disabled={off(`key-${key.id}`)} onConfirm={() => void handleDeleteKey(key.id)}>
                            <span>{t('common:delete')}</span>
                          </SureButton>
                        </span>
                      </div>
                      <div className="ep-l">
                        <span className="tag sm">{PROTO_NAME[key.apiProtocol || 'openai'] ?? key.apiProtocol}</span>
                        {key.model && <code>{key.model}</code>}
                        <span>{key.baseUrl}</span>
                      </div>
                      <div className="ep-l">
                        <span>****{key.apiKeyTail}</span>
                        {key.reasoningEffort && <span>{t('admin.llm.effortBadge', { value: key.reasoningEffort })}</span>}
                      </div>
                      {isEditing && keyForm}
                    </div>
                  );
                })}
              </div>
            </section>

            {/* ===== 更换 LLM（功能位 → 配置，保存才生效） ===== */}
            <section className="sec">
              <div className="sec-h"><h2>{t('admin.assign.title')}</h2></div>
              <p className="intro">{t('admin.assign.hint')}</p>
              <div className="bind">
                {assignmentsDraft.map(a => (
                  <div key={a.functionName}>
                    <b>{fnLabel(a.functionName)}</b>
                    <Select className="wide" label={fnLabel(a.functionName)} placeholder={t('admin.assign.select')}
                            value={String(a.configId ?? '')}
                            onChange={v => updateDraft(a.functionName, v ? Number(v) : null)}
                            options={OPTIONAL_FUNCTIONS.has(a.functionName)
                              ? [{ value: '', label: t('admin.assign.none') }, ...keyOptions]
                              : keyOptions} />
                  </div>
                ))}
              </div>
              <button type="button" className={cn('btn ink', fx.cls('assign'))} disabled={off('assign') || assignmentsDraft.length === 0}
                      onClick={() => void handleSaveAssignments()}>
                <span>{t('admin.assign.save')}</span>
              </button>
            </section>
          </div>

          <div>
            {/* ===== 杠杆日利率 ===== */}
            <section className="sec">
              <div className="sec-h">
                <h2>
                  {t('admin.rate.title')}
                  {interestRateDecimal != null && (
                    <small>
                      {t('admin.rate.current', {
                        value: t('admin.rate.value', { pct: (interestRateDecimal * 100).toFixed(4), decimal: interestRateDecimal }),
                      })}
                    </small>
                  )}
                </h2>
              </div>
              <div className="gen">
                <div className="fld grow">
                  <label className="inp sm">
                    <input value={interestRatePct} onChange={e => setInterestRatePct(e.target.value)} inputMode="decimal"
                           placeholder={t('admin.rate.placeholder')} aria-label={t('admin.rate.title')} />
                    <span className="u">%</span>
                  </label>
                </div>
                <button type="button" className={cn('btn ink', fx.cls('rate'))} disabled={off('rate')} onClick={handleSaveRate}>
                  <span>{t('common:save')}</span>
                </button>
              </div>
              <p className="fhint">{t('admin.rate.hint')}</p>
            </section>

            {/* ===== 邀请码（注册凭证：可配次数、批量生成、作废） ===== */}
            <section className="sec">
              <div className="sec-h">
                <h2>{t('admin.invite.title')}{inviteCodes.length > 0 && <small>{inviteCodes.length}</small>}</h2>
              </div>
              <div className="gen">
                <div className="fld">
                  <label className="fl" htmlFor="adm-inv-uses"><span>{t('admin.invite.maxUses')}</span></label>
                  <label className="inp sm">
                    <input id="adm-inv-uses" value={inviteMaxUses} onChange={e => setInviteMaxUses(e.target.value)} inputMode="numeric" />
                  </label>
                </div>
                <div className="fld">
                  <label className="fl" htmlFor="adm-inv-count"><span>{t('admin.invite.count')}</span></label>
                  <label className="inp sm">
                    <input id="adm-inv-count" value={inviteCount} onChange={e => setInviteCount(e.target.value)} inputMode="numeric" />
                  </label>
                </div>
                <button type="button" className={cn('btn ink', fx.cls('invite'))} disabled={off('invite')} onClick={() => void handleGenerateInvites()}>
                  <Plus className="ic" /><span>{t('admin.invite.generate')}</span>
                </button>
              </div>
              {loaded && inviteCodes.length === 0 && <div className="empty"><span>{t('admin.invite.empty')}</span></div>}
              {inviteCodes.length > 0 && (
                <div className="invs">
                  {inviteCodes.map(ic => {
                    const usedUp = ic.usedCount >= ic.maxUses;
                    return (
                      <div key={ic.id}>
                        <button type="button" className="code" title={t('admin.invite.copyTitle')} onClick={() => void copyInviteCode(ic.code)}>
                          {ic.code}
                        </button>
                        <span className="use">{ic.usedCount}/{ic.maxUses}</span>
                        {!ic.enabled ? <span className="tag sm">{t('admin.invite.revoked')}</span>
                          : usedUp ? <span className="tag sm">{t('admin.invite.usedUp')}</span>
                          : <span className="tag sm g">{t('admin.invite.active')}</span>}
                        <span className="day">{ic.createdAt?.slice(0, 10)}</span>
                        {ic.enabled && !usedUp && (
                          <SureButton className={cn('btn sm', fx.cls(`inv-${ic.id}`))} idleClassName="plain" sureLabel={t('admin.invite.revokeSure')}
                                      disabled={off(`inv-${ic.id}`)} onConfirm={() => void handleDisableInvite(ic.id)}>
                            <span>{t('admin.invite.revoke')}</span>
                          </SureButton>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </section>

            {/* ===== 手动触发 ===== */}
            <section className="sec">
              <div className="sec-h"><h2>{t('admin.manual.title')}</h2></div>
              <div className="grp">
                <span>{t('admin.manual.settleGroup')}</span>
                <div className="acts">
                  {SETTLE_TASKS.map(task => (
                    <button key={task.key} type="button" className={cn('btn', fx.cls(task.key))} disabled={off(task.key)}
                            onClick={() => void fx.run(task.key, task.run, fail(t('admin.actionFailed')))}>
                      <span>{t(task.labelKey)}</span>
                    </button>
                  ))}
                </div>
              </div>
              {/* 快讯补拉：断档超出定时拉取窗口时用 */}
              <div className="grp">
                <span>{t('admin.manual.newsGroup')}</span>
                <div className="gen">
                  <div className="fld">
                    <label className="fl" htmlFor="adm-backfill"><span>{t('admin.manual.backfillCount')}</span></label>
                    <label className="inp sm">
                      <input id="adm-backfill" value={backfillCount} onChange={e => setBackfillCount(e.target.value)} inputMode="numeric" />
                    </label>
                  </div>
                  <button type="button" className={cn('btn', fx.cls('backfill'))} disabled={off('backfill')} onClick={handleBackfillNews}>
                    <span>{t('admin.manual.backfill')}</span>
                  </button>
                </div>
                <p className="fhint">{t('admin.manual.backfillHint', { max: MAX_BACKFILL_COUNT })}</p>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  );
}
