import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { adminApi, jevPredictionApi } from '../api';
import type { AiKeyConfig, AiModelAssignment, InviteCode, JevSwitchState } from '../types';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { useToast } from '../components/ui/use-toast';
import { FeedStreamHealthCard } from '../components/FeedStreamHealthCard';
import { MonitorCarousel } from '../components/MonitorCarousel';
import { LlmEndpointForm, type LlmEndpointValue } from '../components/LlmEndpointForm';
import { RefreshCw, Calendar, Plus, Trash2, Pencil, Save, Ban } from 'lucide-react';

/** 功能位名称的词表 key，顺序即页面上的行序。常量在组件外，存翻译结果会在模块加载那一刻定死，切语言不跟着变 */
const FUNCTION_LABEL_KEYS: Record<string, string> = {
  'news-translation': 'admin.fn.newsTranslation',
  'news-translation-fallback': 'admin.fn.newsTranslationFallback',
};
/** 可空位：下拉多一项"不用"，与后端 AiAgentRuntimeManager.OPTIONAL_FUNCTIONS 同步 */
const OPTIONAL_FUNCTIONS = new Set(['news-translation-fallback']);

/** 手动补拉最多补最近几条，与后端 MAX_BACKFILL_COUNT 同步 */
const MAX_BACKFILL_COUNT = 500;

/** 表单值（与 BYOK 同一份表单）→ 平台配置；平台位没有联网搜索 */
function toKeyConfig(id: number | undefined, f: LlmEndpointValue): AiKeyConfig {
  return {
    id, configName: f.name, apiKey: f.apiKey, baseUrl: f.baseUrl, model: f.model,
    reasoningEffort: f.reasoningEffort, apiProtocol: f.apiProtocol,
  };
}

export function Admin() {
  const { t } = useTranslation(['account', 'common']);
  const { user } = useUserStore();
  const { toast } = useToast();
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [interestRateDecimal, setInterestRateDecimal] = useState<number | null>(null);
  const [interestRatePct, setInterestRatePct] = useState('');
  const [rateLoading, setRateLoading] = useState(false);

  // AI Key管理
  const [aiKeys, setAiKeys] = useState<AiKeyConfig[]>([]);
  const [aiKeysLoading, setAiKeysLoading] = useState(false);
  // null=收起；没 id=新增
  const [editingKey, setEditingKey] = useState<{ id?: number; form: LlmEndpointValue } | null>(null);

  // 模型分配
  const [assignmentsLoading, setAssignmentsLoading] = useState(false);
  const [assignmentsDraft, setAssignmentsDraft] = useState<AiModelAssignment[]>([]);

  // 邀请码管理
  const [inviteCodes, setInviteCodes] = useState<InviteCode[]>([]);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteMaxUses, setInviteMaxUses] = useState('1');
  const [inviteCount, setInviteCount] = useState('1');

  // 快讯补拉：补最近几条
  const [backfillCount, setBackfillCount] = useState('100');

  // Jev 预测员总开关
  const [jevSwitch, setJevSwitch] = useState<JevSwitchState | null>(null);
  // 重新开局的版本说明，null = 确认栏没打开
  const [jevNewRunLabel, setJevNewRunLabel] = useState<string | null>(null);

  const fetchInterestRate = useCallback(async () => {
    setRateLoading(true);
    try {
      const rate = await adminApi.getDailyInterestRate();
      setInterestRateDecimal(rate);
      setInterestRatePct(rate > 0 ? String(rate * 100) : '0');
    } catch { /* ignore */ }
    finally { setRateLoading(false); }
  }, []);

  const fetchAiKeys = useCallback(async () => {
    setAiKeysLoading(true);
    try {
      const list = await adminApi.listAiKeys();
      setAiKeys(list);
    } catch { /* ignore */ }
    finally { setAiKeysLoading(false); }
  }, []);

  const fetchAssignments = useCallback(async () => {
    setAssignmentsLoading(true);
    try {
      const list = await adminApi.listAssignments();
      // 每个功能位固定一行：可空位没配时库里没行，也要给个下拉
      setAssignmentsDraft(Object.keys(FUNCTION_LABEL_KEYS).map(functionName => ({
        functionName,
        configId: list.find(a => a.functionName === functionName)?.configId ?? null,
      })));
    } catch { /* ignore */ }
    finally { setAssignmentsLoading(false); }
  }, []);

  const fetchInviteCodes = useCallback(async () => {
    setInviteLoading(true);
    try {
      const list = await adminApi.listInviteCodes();
      setInviteCodes(list);
    } catch { /* ignore */ }
    finally { setInviteLoading(false); }
  }, []);

  useEffect(() => {
    if (user?.id === 1) {
      fetchInterestRate();
      fetchAiKeys();
      fetchAssignments();
      fetchInviteCodes();
      jevPredictionApi.switchState().then(setJevSwitch).catch(() => { /* ignore */ });
    }
  }, [user, fetchInterestRate, fetchAiKeys, fetchAssignments, fetchInviteCodes]);

  // 外层守卫保证 token 存在，user 为 null 只能是 fetchUser 还没回来 —— 等它。
  // 早先这里 !user 也一起弹，管理员刷新本页会被自己的代码踢回首页（user 没持久化，刷新必为 null）
  if (!user) return null;
  if (user.id !== 1) return <Navigate to="/" replace />;

  const handleAction = async (action: () => Promise<unknown>, name: string) => {
    setActionLoading(name);
    try {
      await action();
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  /** 带结果文案的操作（活动结算等）：成功把返回的消息弹出来，失败弹错误 */
  const handleMessageAction = async (action: () => Promise<string>, name: string) => {
    setActionLoading(name);
    try {
      const message = await action();
      toast(message, 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.actionFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const handleSaveRate = async () => {
    const pct = Number(interestRatePct);
    if (!Number.isFinite(pct) || pct < 0 || pct > 100) return;
    setActionLoading('setRate');
    try {
      const decimal = pct / 100;
      const updated = await adminApi.setDailyInterestRate(decimal);
      setInterestRateDecimal(updated);
      setInterestRatePct(String(updated > 0 ? updated * 100 : 0));
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const handleJevSwitch = async (enabled: boolean) => {
    setActionLoading('jevSwitch');
    try {
      setJevSwitch(await jevPredictionApi.setSwitch(enabled));
    } catch (e) {
      toast((e as Error).message || t('admin.actionFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const handleJevNewRun = async () => {
    setActionLoading('jevNewRun');
    try {
      const s = await jevPredictionApi.newRun(jevNewRunLabel ?? '');
      setJevSwitch(s);
      setJevNewRunLabel(null);
      toast(t('admin.jev.newRunDone', { n: s.run.runNo }), 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.actionFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  // ========== AI Key 操作 ==========

  const handleSaveKey = async () => {
    if (!editingKey) return;
    const { name, apiKey, baseUrl, model } = editingKey.form;
    if (!name.trim() || !apiKey.trim() || !baseUrl.trim() || !model.trim()) {
      toast(t('admin.llm.required'), 'error');
      return;
    }
    setActionLoading('saveKey');
    try {
      await adminApi.saveAiKey(toKeyConfig(editingKey.id, editingKey.form));
      setEditingKey(null);
      // 加第一条配置时后端会自动种出必配功能位分配，两块都要重拉
      await Promise.all([fetchAiKeys(), fetchAssignments()]);
      toast(t('admin.llm.saved'), 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.llm.saveFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const handleDeleteKey = async (id: number) => {
    setActionLoading('deleteKey');
    try {
      await adminApi.deleteAiKey(id);
      await fetchAiKeys();
      toast(t('admin.llm.deleted'), 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.llm.deleteFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  // ========== 更换 LLM 操作 ==========

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
    for (const a of assignmentsDraft) {
      if (!a.configId && !OPTIONAL_FUNCTIONS.has(a.functionName)) {
        toast(t('admin.assign.noneSelected', { name: fnLabel(a.functionName) }), 'error');
        return;
      }
    }
    setActionLoading('saveAssignments');
    try {
      await adminApi.saveAssignments(assignmentsDraft);
      await fetchAssignments();
      toast(t('admin.assign.saved'), 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.llm.saveFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };


  // ========== 快讯补拉 ==========

  const handleBackfillNews = async () => {
    const count = Number(backfillCount);
    if (!Number.isInteger(count) || count < 1 || count > MAX_BACKFILL_COUNT) {
      toast(t('admin.manual.backfillBad', { max: MAX_BACKFILL_COUNT }), 'error');
      return;
    }
    setActionLoading('backfillNews');
    try {
      const r = await adminApi.backfillNews(count);
      toast(t('admin.manual.backfillDone', { fetched: r.fetched, inserted: r.inserted }), 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.actionFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  /** 配置行上的协议徽标 */
  const PROTO_BADGE: Record<string, string> = { openai: 'ChatCompletions', responses: 'Responses', anthropic: 'Anthropic', gemini: 'Gemini' };
  const maskKey = (key: string) => {
    if (key.length <= 8) return '****';
    return key.slice(0, 4) + '****' + key.slice(-4);
  };

  // ========== 邀请码操作 ==========

  const handleGenerateInvites = async () => {
    const maxUses = Number(inviteMaxUses);
    const count = Number(inviteCount);
    if (!Number.isInteger(maxUses) || maxUses < 1 || !Number.isInteger(count) || count < 1) {
      toast(t('admin.invite.badArgs'), 'error');
      return;
    }
    setActionLoading('generateInvites');
    try {
      await adminApi.generateInviteCodes(maxUses, count);
      await fetchInviteCodes();
      toast(t('admin.invite.generated'), 'success');
    } catch (e) {
      toast((e as Error).message || t('admin.invite.generateFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const handleDisableInvite = async (id: number) => {
    setActionLoading('disableInvite');
    try {
      await adminApi.disableInviteCode(id);
      await fetchInviteCodes();
    } catch (e) {
      toast((e as Error).message || t('admin.invite.revokeFailed'), 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const copyInviteCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      toast(t('admin.invite.copied', { code }), 'success');
    } catch {
      toast(t('admin.invite.copyFailed'), 'error');
    }
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 space-y-6">
      <h1 className="text-2xl font-bold">{t('admin.title')}</h1>

      {/* feed 数据流健康：独立于任务状态加载，管理员进页即见 */}
      <FeedStreamHealthCard />

      {/* 上游进程监控（原首页轮播，运维信息归口 admin） */}
      <MonitorCarousel />

      {/* 杠杆日利率 */}
          <Card>
            <CardHeader><CardTitle className="text-lg">{t('admin.rate.title')}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm text-muted-foreground">
                {t('admin.rate.current', {
                  value: interestRateDecimal == null
                    ? '-'
                    : t('admin.rate.value', { pct: (interestRateDecimal * 100).toFixed(4), decimal: interestRateDecimal }),
                })}
              </div>
              <div className="flex flex-wrap gap-3 items-center">
                <div className="flex-1 min-w-[10rem]">
                  <Input
                    value={interestRatePct}
                    onChange={e => setInterestRatePct(e.target.value)}
                    placeholder={t('admin.rate.placeholder')}
                    disabled={rateLoading || actionLoading !== null}
                  />
                </div>
                <Button variant="outline" onClick={() => void fetchInterestRate()} disabled={rateLoading || actionLoading !== null}>{t('common:refresh')}</Button>
                <Button onClick={() => void handleSaveRate()} disabled={rateLoading || actionLoading !== null || interestRatePct.trim() === ''}>{t('common:save')}</Button>
              </div>
              <div className="text-xs text-muted-foreground">{t('admin.rate.hint')}</div>
            </CardContent>
          </Card>

          {/* Jev 预测员总开关 */}
          {jevSwitch && (
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-lg">{t('admin.jev.title')}</CardTitle>
                  <Link to="/jev" className="text-xs font-bold text-primary hover:underline">{t('admin.jev.view')}</Link>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="flex flex-wrap items-center gap-3">
                  <Badge variant={jevSwitch.enabled ? 'default' : 'secondary'}>
                    {jevSwitch.enabled ? t('admin.jev.on') : t('admin.jev.off')}
                  </Badge>
                  <Button size="sm" variant={jevSwitch.enabled ? 'outline' : 'default'}
                          onClick={() => void handleJevSwitch(!jevSwitch.enabled)}
                          disabled={actionLoading !== null || (!jevSwitch.enabled && !jevSwitch.configured)}>
                    {jevSwitch.enabled ? t('admin.jev.turnOff') : t('admin.jev.turnOn')}
                  </Button>
                  {!jevSwitch.configured && <span className="text-xs text-destructive">{t('admin.jev.noKey')}</span>}
                </div>
                {/* 局次：关着才能重新开局 */}
                <div className="flex flex-wrap items-center gap-3 text-sm">
                  <span className="font-semibold">{t('admin.jev.currentRun', { n: jevSwitch.run.runNo })}</span>
                  {jevSwitch.run.label && <span className="text-muted-foreground">{jevSwitch.run.label}</span>}
                  <Button size="sm" variant="outline" onClick={() => setJevNewRunLabel('')}
                          disabled={actionLoading !== null || jevSwitch.enabled || jevNewRunLabel !== null}>
                    {t('admin.jev.newRun')}
                  </Button>
                  {jevSwitch.enabled && <span className="text-xs text-muted-foreground">{t('admin.jev.newRunWhileOn')}</span>}
                </div>
                {jevNewRunLabel !== null && (
                  <div className="flex flex-wrap items-center gap-2">
                    <Input className="max-w-xs" value={jevNewRunLabel} maxLength={60} placeholder={t('admin.jev.labelPh')}
                           onChange={e => setJevNewRunLabel(e.target.value)} disabled={actionLoading !== null} />
                    <Button size="sm" onClick={() => void handleJevNewRun()} disabled={actionLoading !== null}>
                      {t('admin.jev.confirmNewRun', { n: jevSwitch.run.runNo + 1 })}
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setJevNewRunLabel(null)} disabled={actionLoading !== null}>
                      {t('common:cancel')}
                    </Button>
                  </div>
                )}
                <div className="text-xs text-muted-foreground">{t('admin.jev.hint')}</div>
              </CardContent>
            </Card>
          )}

          {/* ========== 邀请码（注册凭证，可配次数/批量生成/作废） ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">{t('admin.invite.title')}</CardTitle>
                <Button variant="outline" size="sm" onClick={fetchInviteCodes} disabled={inviteLoading}>
                  <RefreshCw className={`w-3.5 h-3.5 mr-1 ${inviteLoading ? 'animate-spin' : ''}`} /> {t('common:refresh')}
                </Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap gap-3 items-center">
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground whitespace-nowrap">{t('admin.invite.maxUses')}</span>
                  <Input className="w-20" value={inviteMaxUses} onChange={e => setInviteMaxUses(e.target.value)} disabled={actionLoading !== null} />
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground whitespace-nowrap">{t('admin.invite.count')}</span>
                  <Input className="w-20" value={inviteCount} onChange={e => setInviteCount(e.target.value)} disabled={actionLoading !== null} />
                </div>
                <Button size="sm" onClick={() => void handleGenerateInvites()} disabled={actionLoading !== null}>
                  <Plus className="w-3.5 h-3.5 mr-1" /> {t('admin.invite.generate')}
                </Button>
              </div>
              {inviteCodes.length === 0 && !inviteLoading && (
                <div className="text-sm text-muted-foreground text-center py-4">{t('admin.invite.empty')}</div>
              )}
              {inviteCodes.map(ic => {
                const usedUp = ic.usedCount >= ic.maxUses;
                return (
                  <div key={ic.id} className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                    <button
                      className="font-mono text-sm font-bold tracking-widest hover:text-primary"
                      onClick={() => void copyInviteCode(ic.code)}
                      title={t('admin.invite.copyTitle')}
                    >
                      {ic.code}
                    </button>
                    <Badge variant="outline" className="text-[10px]">{ic.usedCount}/{ic.maxUses}</Badge>
                    {!ic.enabled ? (
                      <Badge variant="secondary" className="text-[10px]">{t('admin.invite.revoked')}</Badge>
                    ) : usedUp ? (
                      <Badge variant="secondary" className="text-[10px]">{t('admin.invite.usedUp')}</Badge>
                    ) : (
                      <Badge className="text-[10px]">{t('admin.invite.active')}</Badge>
                    )}
                    <span className="flex-1" />
                    <span className="text-xs text-muted-foreground">{ic.createdAt?.slice(0, 10)}</span>
                    {ic.enabled && !usedUp && (
                      <Button variant="ghost" size="sm" onClick={() => void handleDisableInvite(ic.id)} disabled={actionLoading !== null} title={t('admin.invite.revoke')}>
                        <Ban className="w-3.5 h-3.5 text-destructive" />
                      </Button>
                    )}
                  </div>
                );
              })}
            </CardContent>
          </Card>

          {/* ========== 配置 LLM（一条=key+baseUrl+model） ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">{t('admin.llm.title')}</CardTitle>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={fetchAiKeys} disabled={aiKeysLoading}>
                    <RefreshCw className={`w-3.5 h-3.5 mr-1 ${aiKeysLoading ? 'animate-spin' : ''}`} /> {t('common:refresh')}
                  </Button>
                  <Button size="sm" onClick={() => setEditingKey({ form: {
                    name: '', apiProtocol: 'openai', baseUrl: '', model: '', reasoningEffort: '', apiKey: '', webSearch: false,
                  } })}>
                    <Plus className="w-3.5 h-3.5 mr-1" /> {t('admin.llm.add')}
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs text-muted-foreground">{t('admin.llm.hint')}</div>
              {aiKeys.length === 0 && !aiKeysLoading && (
                <div className="text-sm text-muted-foreground text-center py-4">{t('admin.llm.empty')}</div>
              )}
              {aiKeys.map(key => (
                <div key={key.id} className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                  <div className="flex-1 min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-bold">{key.configName}</span>
                      <Badge variant="outline" className="text-[10px]">{maskKey(key.apiKey)}</Badge>
                      {key.model && <Badge variant="secondary" className="text-[10px]">{key.model}</Badge>}
                      <Badge variant="outline" className="text-[10px]">{PROTO_BADGE[key.apiProtocol || 'openai'] ?? key.apiProtocol}</Badge>
                      {key.reasoningEffort && <Badge variant="secondary" className="text-[10px]">{t('admin.llm.effortBadge', { value: key.reasoningEffort })}</Badge>}
                    </div>
                    <div className="text-xs text-muted-foreground mt-0.5 truncate">{key.baseUrl}</div>
                  </div>
                  <Button variant="ghost" size="sm" onClick={() => setEditingKey({ id: key.id, form: {
                    name: key.configName, apiProtocol: key.apiProtocol || 'openai', baseUrl: key.baseUrl,
                    model: key.model || '', reasoningEffort: key.reasoningEffort || '', apiKey: key.apiKey, webSearch: false,
                  } })}>
                    <Pencil className="w-3.5 h-3.5" />
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => void handleDeleteKey(key.id!)} disabled={actionLoading !== null}>
                    <Trash2 className="w-3.5 h-3.5 text-destructive" />
                  </Button>
                </div>
              ))}

              {/* 编辑/新增表单 */}
              {editingKey && (
                <div className="p-4 rounded-lg border-2 border-primary/30 bg-primary/5 space-y-3">
                  <div className="text-sm font-bold">{editingKey.id ? t('admin.llm.editTitle') : t('admin.llm.addTitle')}</div>
                  {/* 与 BYOK 同一份表单；拉模型/测连通直接用表单里的 key，不用先存 */}
                  {/* key：换编辑对象就重建，别把上一条检测到的模型清单带过来 */}
                  <LlmEndpointForm
                    key={editingKey.id ?? 'new'}
                    value={editingKey.form}
                    onChange={patch => setEditingKey(prev => prev ? { ...prev, form: { ...prev.form, ...patch } } : prev)}
                    noWebSearch
                    onDetect={() => adminApi.listAiKeyModels(toKeyConfig(editingKey.id, editingKey.form))}
                    onTest={() => adminApi.testAiKey(toKeyConfig(editingKey.id, editingKey.form))}
                  />
                  <div className="flex gap-2">
                    <Button size="sm" onClick={() => void handleSaveKey()} disabled={actionLoading === 'saveKey'}>
                      <Save className="w-3.5 h-3.5 mr-1" /> {t('common:save')}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setEditingKey(null)}>{t('common:cancel')}</Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* ========== 更换 LLM（功能位→配置指针，下拉即换） ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">{t('admin.assign.title')}</CardTitle>
                <Button variant="outline" size="sm" onClick={fetchAssignments} disabled={assignmentsLoading}>
                  <RefreshCw className={`w-3.5 h-3.5 mr-1 ${assignmentsLoading ? 'animate-spin' : ''}`} /> {t('common:refresh')}
                </Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs text-muted-foreground">{t('admin.assign.hint')}</div>
              {assignmentsDraft.map(a => (
                <div key={a.functionName} className="flex flex-col md:flex-row md:items-center gap-2 md:gap-3 p-3 rounded-lg border bg-muted/30">
                  <span className="text-sm font-bold min-w-[6rem]">{fnLabel(a.functionName)}</span>
                  <select
                    className="w-full md:flex-1 h-9 rounded-md border bg-background px-3 text-sm"
                    value={a.configId ?? ''}
                    onChange={e => updateDraft(a.functionName, e.target.value ? Number(e.target.value) : null)}
                  >
                    <option value="">
                      {OPTIONAL_FUNCTIONS.has(a.functionName) ? t('admin.assign.none') : t('admin.assign.select')}
                    </option>
                    {aiKeys.map(k => (
                      <option key={k.id} value={k.id} disabled={!k.model}>
                        {t('admin.assign.option', { name: k.configName, model: k.model || t('admin.assign.noModel') })}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
              <Button onClick={() => void handleSaveAssignments()} disabled={actionLoading === 'saveAssignments' || assignmentsDraft.length === 0}>
                <Save className="w-4 h-4 mr-1" /> {t('admin.assign.save')}
              </Button>
            </CardContent>
          </Card>

          {/* 手动触发任务 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Calendar className="w-5 h-5" /> {t('admin.manual.title')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* 结算与风控 */}
              <div>
                <div className="text-xs text-muted-foreground mb-2">{t('admin.manual.settleGroup')}</div>
                <div className="grid grid-cols-2 gap-2">
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.bankruptcyCheck, 'bankruptcyCheck')} disabled={actionLoading !== null}>{t('admin.manual.bankruptcy')}</Button>
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.accrueInterest, 'accrueInterest')} disabled={actionLoading !== null}>{t('admin.manual.interest')}</Button>
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.assetSnapshot, 'assetSnapshot')} disabled={actionLoading !== null}>{t('admin.manual.snapshot')}</Button>
                  {/* 活动结算：end_at 之后才会成功（服务端校验），幂等可重点 */}
                  <Button variant="outline" className="h-9 text-xs" onClick={() => void handleMessageAction(() => adminApi.settleCampaign().then(n => t('admin.manual.settled', { count: n })), 'settleCampaign')} disabled={actionLoading !== null}>{t('admin.manual.settleCampaign')}</Button>
                </div>
              </div>
              {/* 快讯补拉：断档超出定时拉取窗口时用 */}
              <div>
                <div className="text-xs text-muted-foreground mb-2">{t('admin.manual.newsGroup')}</div>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs text-muted-foreground whitespace-nowrap">{t('admin.manual.backfillCount')}</span>
                  <Input className="w-24" value={backfillCount} onChange={e => setBackfillCount(e.target.value)} disabled={actionLoading !== null} />
                  <Button variant="outline" className="h-9 text-xs" onClick={() => void handleBackfillNews()} disabled={actionLoading !== null}>
                    {t('admin.manual.backfill')}
                  </Button>
                </div>
                <div className="text-xs text-muted-foreground mt-2">{t('admin.manual.backfillHint', { max: MAX_BACKFILL_COUNT })}</div>
              </div>
            </CardContent>
          </Card>
    </div>
  );
}
