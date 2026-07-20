import { useState, useEffect, useCallback } from 'react';
import { Navigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { adminApi } from '../api';
import type { AiKeyConfig, AiModelAssignment, InviteCode } from '../types';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { useToast } from '../components/ui/use-toast';
import { FeedStreamHealthCard } from '../components/FeedStreamHealthCard';
import { RefreshCw, Calendar, Plus, Trash2, Pencil, Save, Ban } from 'lucide-react';

const FUNCTION_LABELS: Record<string, string> = {
  behavior: '行为分析',
  quant: '量化研判(深)',
  'quant-light': '对话专家(浅)',
  chat: '对话兜底',
  sim: '模拟行情生成',
};
const MODEL_ASSIGNMENT_FUNCTIONS = new Set(Object.keys(FUNCTION_LABELS));

export function Admin() {
  const { user } = useUserStore();
  const { toast } = useToast();
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [quantSymbol, setQuantSymbol] = useState('BTCUSDT');
  const [interestRateDecimal, setInterestRateDecimal] = useState<number | null>(null);
  const [interestRatePct, setInterestRatePct] = useState('');
  const [rateLoading, setRateLoading] = useState(false);

  // AI Key管理
  const [aiKeys, setAiKeys] = useState<AiKeyConfig[]>([]);
  const [aiKeysLoading, setAiKeysLoading] = useState(false);
  const [editingKey, setEditingKey] = useState<AiKeyConfig | null>(null);

  // 模型分配
  const [assignmentsLoading, setAssignmentsLoading] = useState(false);
  const [assignmentsDraft, setAssignmentsDraft] = useState<AiModelAssignment[]>([]);

  // 邀请码管理
  const [inviteCodes, setInviteCodes] = useState<InviteCode[]>([]);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteMaxUses, setInviteMaxUses] = useState('1');
  const [inviteCount, setInviteCount] = useState('1');

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
      setAssignmentsDraft(list.filter(a => MODEL_ASSIGNMENT_FUNCTIONS.has(a.functionName)).map(a => ({ ...a })));
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

  // ========== AI Key 操作 ==========

  const handleSaveKey = async () => {
    if (!editingKey) return;
    const { configName, apiKey, baseUrl, model } = editingKey;
    if (!configName?.trim() || !apiKey?.trim() || !baseUrl?.trim() || !model?.trim()) {
      toast('名称、API Key、Base URL、Model 不能为空', 'error');
      return;
    }
    setActionLoading('saveKey');
    try {
      await adminApi.saveAiKey(editingKey);
      setEditingKey(null);
      // 加第一条配置时后端会自动种出全部功能位分配，两块都要重拉
      await Promise.all([fetchAiKeys(), fetchAssignments()]);
      toast('LLM 配置已保存', 'success');
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const handleDeleteKey = async (id: number) => {
    setActionLoading('deleteKey');
    try {
      await adminApi.deleteAiKey(id);
      await fetchAiKeys();
      toast('已删除', 'success');
    } catch (e) {
      toast((e as Error).message || '删除失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  // ========== 更换 LLM 操作 ==========

  const updateDraft = (functionName: string, configId: number) => {
    setAssignmentsDraft(prev =>
      prev.map(a => a.functionName === functionName ? { ...a, configId } : a)
    );
  };

  const handleSaveAssignments = async () => {
    for (const a of assignmentsDraft) {
      if (!a.configId) {
        toast(`${FUNCTION_LABELS[a.functionName] || a.functionName} 未选择 LLM`, 'error');
        return;
      }
    }
    setActionLoading('saveAssignments');
    try {
      await adminApi.saveAssignments(assignmentsDraft);
      await fetchAssignments();
      toast('LLM 已切换并生效', 'success');
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const handleMessageAction = async (action: () => Promise<string>, name: string) => {
    setActionLoading(name);
    try {
      const message = await action();
      toast(message, 'success');
    } catch (e) {
      toast((e as Error).message || '操作失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const maskKey = (key: string) => {
    if (key.length <= 8) return '****';
    return key.slice(0, 4) + '****' + key.slice(-4);
  };

  // ========== 邀请码操作 ==========

  const handleGenerateInvites = async () => {
    const maxUses = Number(inviteMaxUses);
    const count = Number(inviteCount);
    if (!Number.isInteger(maxUses) || maxUses < 1 || !Number.isInteger(count) || count < 1) {
      toast('次数和个数需为正整数', 'error');
      return;
    }
    setActionLoading('generateInvites');
    try {
      await adminApi.generateInviteCodes(maxUses, count);
      await fetchInviteCodes();
      toast('邀请码已生成', 'success');
    } catch (e) {
      toast((e as Error).message || '生成失败', 'error');
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
      toast((e as Error).message || '作废失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const copyInviteCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      toast(`已复制 ${code}`, 'success');
    } catch {
      toast('复制失败', 'error');
    }
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 space-y-6">
      <h1 className="text-2xl font-bold">任务管理</h1>

      {/* feed 数据流健康：独立于任务状态加载，管理员进页即见 */}
      <FeedStreamHealthCard />

      {/* 杠杆日利率 */}
          <Card>
            <CardHeader><CardTitle className="text-lg">杠杆日利率</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm text-muted-foreground">
                当前：{interestRateDecimal == null ? ' -' : ` ${(interestRateDecimal * 100).toFixed(4)}%/天（${interestRateDecimal}）`}
              </div>
              <div className="flex flex-wrap gap-3 items-center">
                <div className="flex-1 min-w-[10rem]">
                  <Input
                    value={interestRatePct}
                    onChange={e => setInterestRatePct(e.target.value)}
                    placeholder="输入百分比，例如 0.05 表示 0.05%/天"
                    disabled={rateLoading || actionLoading !== null}
                  />
                </div>
                <Button variant="outline" onClick={() => void fetchInterestRate()} disabled={rateLoading || actionLoading !== null}>刷新</Button>
                <Button onClick={() => void handleSaveRate()} disabled={rateLoading || actionLoading !== null || interestRatePct.trim() === ''}>保存</Button>
              </div>
              <div className="text-xs text-muted-foreground">输入为百分比；例如 0.05%/天 对应 decimal=0.0005。</div>
            </CardContent>
          </Card>

          {/* ========== 邀请码（注册凭证，可配次数/批量生成/作废） ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">邀请码</CardTitle>
                <Button variant="outline" size="sm" onClick={fetchInviteCodes} disabled={inviteLoading}>
                  <RefreshCw className={`w-3.5 h-3.5 mr-1 ${inviteLoading ? 'animate-spin' : ''}`} /> 刷新
                </Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap gap-3 items-center">
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground whitespace-nowrap">每码可用次数</span>
                  <Input className="w-20" value={inviteMaxUses} onChange={e => setInviteMaxUses(e.target.value)} disabled={actionLoading !== null} />
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground whitespace-nowrap">生成个数</span>
                  <Input className="w-20" value={inviteCount} onChange={e => setInviteCount(e.target.value)} disabled={actionLoading !== null} />
                </div>
                <Button size="sm" onClick={() => void handleGenerateInvites()} disabled={actionLoading !== null}>
                  <Plus className="w-3.5 h-3.5 mr-1" /> 生成
                </Button>
              </div>
              {inviteCodes.length === 0 && !inviteLoading && (
                <div className="text-sm text-muted-foreground text-center py-4">暂无邀请码</div>
              )}
              {inviteCodes.map(ic => {
                const usedUp = ic.usedCount >= ic.maxUses;
                return (
                  <div key={ic.id} className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                    <button
                      className="font-mono text-sm font-bold tracking-widest hover:text-primary"
                      onClick={() => void copyInviteCode(ic.code)}
                      title="点击复制"
                    >
                      {ic.code}
                    </button>
                    <Badge variant="outline" className="text-[10px]">{ic.usedCount}/{ic.maxUses}</Badge>
                    {!ic.enabled ? (
                      <Badge variant="secondary" className="text-[10px]">已作废</Badge>
                    ) : usedUp ? (
                      <Badge variant="secondary" className="text-[10px]">已用完</Badge>
                    ) : (
                      <Badge className="text-[10px]">可用</Badge>
                    )}
                    <span className="flex-1" />
                    <span className="text-xs text-muted-foreground">{ic.createdAt?.slice(0, 10)}</span>
                    {ic.enabled && !usedUp && (
                      <Button variant="ghost" size="sm" onClick={() => void handleDisableInvite(ic.id)} disabled={actionLoading !== null} title="作废">
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
                <CardTitle className="text-lg">配置 LLM</CardTitle>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={fetchAiKeys} disabled={aiKeysLoading}>
                    <RefreshCw className={`w-3.5 h-3.5 mr-1 ${aiKeysLoading ? 'animate-spin' : ''}`} /> 刷新
                  </Button>
                  <Button size="sm" onClick={() => setEditingKey({ configName: '', apiKey: '', baseUrl: '', model: '' })}>
                    <Plus className="w-3.5 h-3.5 mr-1" /> 新增
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs text-muted-foreground">一条配置 = 一个具体 LLM（API Key + Base URL + 模型名）；同一 Key 不同模型就建多条。</div>
              {aiKeys.length === 0 && !aiKeysLoading && (
                <div className="text-sm text-muted-foreground text-center py-4">暂无 LLM 配置</div>
              )}
              {aiKeys.map(key => (
                <div key={key.id} className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                  <div className="flex-1 min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-bold">{key.configName}</span>
                      <Badge variant="outline" className="text-[10px]">{maskKey(key.apiKey)}</Badge>
                      {key.model && <Badge variant="secondary" className="text-[10px]">{key.model}</Badge>}
                      <Badge variant="outline" className="text-[10px]">{key.apiProtocol === 'responses' ? 'Responses' : 'ChatCompletions'}</Badge>
                      {key.reasoningEffort && <Badge variant="secondary" className="text-[10px]">思考:{key.reasoningEffort}</Badge>}
                    </div>
                    <div className="text-xs text-muted-foreground mt-0.5 truncate">{key.baseUrl}</div>
                  </div>
                  <Button variant="ghost" size="sm" onClick={() => setEditingKey({ ...key })}>
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
                  <div className="text-sm font-bold">{editingKey.id ? '编辑 LLM 配置' : '新增 LLM 配置'}</div>
                  <Input
                    value={editingKey.configName}
                    onChange={e => setEditingKey(prev => prev ? { ...prev, configName: e.target.value } : prev)}
                    placeholder="名称，如 OpenAI、DeepSeek"
                  />
                  <Input
                    value={editingKey.apiKey}
                    onChange={e => setEditingKey(prev => prev ? { ...prev, apiKey: e.target.value } : prev)}
                    placeholder="API Key"
                  />
                  <Input
                    value={editingKey.baseUrl}
                    onChange={e => setEditingKey(prev => prev ? { ...prev, baseUrl: e.target.value } : prev)}
                    placeholder="Base URL，如 https://api.openai.com"
                  />
                  <Input
                    value={editingKey.model || ''}
                    onChange={e => setEditingKey(prev => prev ? { ...prev, model: e.target.value } : prev)}
                    placeholder="模型名，如 gpt-4o、deepseek-chat"
                  />
                  <select
                    className="w-full h-9 rounded-md border bg-background px-3 text-sm"
                    value={editingKey.apiProtocol || 'openai'}
                    onChange={e => setEditingKey(prev => prev ? { ...prev, apiProtocol: e.target.value } : prev)}
                  >
                    <option value="openai">协议：OpenAI Chat Completions（/v1/chat/completions，DeepSeek 等通用）</option>
                    <option value="responses">协议：OpenAI Responses（/v1/responses，CPA/OpenAI官方/xAI）</option>
                  </select>
                  <select
                    className="w-full h-9 rounded-md border bg-background px-3 text-sm"
                    value={editingKey.reasoningEffort || ''}
                    onChange={e => setEditingKey(prev => prev ? { ...prev, reasoningEffort: e.target.value } : prev)}
                  >
                    <option value="">思考档位：模型默认（不传）</option>
                    <option value="none">none（关思考，最省 token，仅部分模型支持）</option>
                    <option value="low">low</option>
                    <option value="medium">medium</option>
                    <option value="high">high</option>
                  </select>
                  <div className="flex gap-2">
                    <Button size="sm" onClick={() => void handleSaveKey()} disabled={actionLoading === 'saveKey'}>
                      <Save className="w-3.5 h-3.5 mr-1" /> 保存
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setEditingKey(null)}>取消</Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* ========== 更换 LLM（功能位→配置指针，下拉即换） ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">更换 LLM</CardTitle>
                <Button variant="outline" size="sm" onClick={fetchAssignments} disabled={assignmentsLoading}>
                  <RefreshCw className={`w-3.5 h-3.5 mr-1 ${assignmentsLoading ? 'animate-spin' : ''}`} /> 刷新
                </Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs text-muted-foreground">每个功能位下拉选择要用的 LLM，保存后立即生效；模型名在上方「配置 LLM」维护。</div>
              {assignmentsDraft.map(a => (
                <div key={a.functionName} className="flex flex-col md:flex-row md:items-center gap-2 md:gap-3 p-3 rounded-lg border bg-muted/30">
                  <span className="text-sm font-bold min-w-[6rem]">{FUNCTION_LABELS[a.functionName] || a.functionName}</span>
                  <select
                    className="w-full md:flex-1 h-9 rounded-md border bg-background px-3 text-sm"
                    value={a.configId || ''}
                    onChange={e => updateDraft(a.functionName, Number(e.target.value))}
                  >
                    <option value="">选择 LLM</option>
                    {aiKeys.map(k => (
                      <option key={k.id} value={k.id} disabled={!k.model}>{k.configName}（{k.model || '未设模型'}）</option>
                    ))}
                  </select>
                </div>
              ))}
              <Button onClick={() => void handleSaveAssignments()} disabled={actionLoading === 'saveAssignments' || assignmentsDraft.length === 0}>
                <Save className="w-4 h-4 mr-1" /> 保存并生效
              </Button>
            </CardContent>
          </Card>

          {/* 手动触发任务 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Calendar className="w-5 h-5" /> 手动触发
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* 结算与风控 */}
              <div>
                <div className="text-xs text-muted-foreground mb-2">结算与风控</div>
                <div className="grid grid-cols-2 gap-2">
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.bankruptcyCheck, 'bankruptcyCheck')} disabled={actionLoading !== null}>执行爆仓检查</Button>
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.accrueInterest, 'accrueInterest')} disabled={actionLoading !== null}>手动计息</Button>
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.assetSnapshot, 'assetSnapshot')} disabled={actionLoading !== null}>资产快照</Button>
                </div>
              </div>
              {/* AI 量化 */}
              <div>
                <div className="text-xs text-muted-foreground mb-2">AI 量化</div>
                <div className="space-y-2">
                  <Input value={quantSymbol} onChange={e => setQuantSymbol(e.target.value.toUpperCase())} placeholder="币种，如 BTCUSDT" className="h-9" />
                  <div className="grid grid-cols-2 gap-2">
                    <Button variant="outline" className="h-9 text-xs" onClick={() => { const sym = quantSymbol.trim(); if (sym) void handleMessageAction(() => adminApi.triggerQuant(sym), 'triggerQuant'); }} disabled={actionLoading !== null || !quantSymbol.trim()}>触发量化分析</Button>
                    <Button variant="outline" className="h-9 text-xs" onClick={() => { const sym = quantSymbol.trim(); if (sym) void handleMessageAction(() => adminApi.triggerQuantVerification(sym), 'triggerQuantVerification'); }} disabled={actionLoading !== null || !quantSymbol.trim()}>触发预测验证</Button>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
    </div>
  );
}
