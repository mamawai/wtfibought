import { useState, useEffect, useCallback } from 'react';
import { Navigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { adminApi, type TaskStatus } from '../api';
import type { AiKeyConfig, AiModelAssignment } from '../types';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { useToast } from '../components/ui/use-toast';
import { Play, Square, RefreshCw, Database, Calendar, Clock, Plus, Trash2, Pencil, Save } from 'lucide-react';

const FUNCTION_LABELS: Record<string, string> = {
  behavior: '行为分析',
  quant: '量化分析',
  chat: '追问对话',
  reflection: '反思验证',
};

export function Admin() {
  const { user } = useUserStore();
  const { toast } = useToast();
  const [status, setStatus] = useState<TaskStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [generateOffset, setGenerateOffset] = useState(0);
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

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const data = await adminApi.taskStatus();
      setStatus(data);
    } catch { /* ignore */ }
    finally { setLoading(false); }
  }, []);

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
      setAssignmentsDraft(list.map(a => ({ ...a })));
    } catch { /* ignore */ }
    finally { setAssignmentsLoading(false); }
  }, []);

  useEffect(() => {
    if (user?.id === 1) {
      fetchStatus();
      fetchInterestRate();
      fetchAiKeys();
      fetchAssignments();
    }
  }, [user, fetchStatus, fetchInterestRate, fetchAiKeys, fetchAssignments]);

  if (!user || user.id !== 1) {
    return <Navigate to="/" replace />;
  }

  const handleAction = async (action: () => Promise<unknown>, name: string) => {
    setActionLoading(name);
    try {
      await action();
      await fetchStatus();
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
      await fetchAiKeys();
      toast('API Key 已保存', 'success');
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

  // ========== 模型分配操作 ==========

  const updateDraft = (functionName: string, field: 'configId' | 'model', value: string | number) => {
    setAssignmentsDraft(prev =>
      prev.map(a => a.functionName === functionName ? { ...a, [field]: value } : a)
    );
  };

  const handleSaveAssignments = async () => {
    for (const a of assignmentsDraft) {
      if (!a.configId || !a.model?.trim()) {
        toast(`${FUNCTION_LABELS[a.functionName] || a.functionName} 配置不完整`, 'error');
        return;
      }
    }
    setActionLoading('saveAssignments');
    try {
      await adminApi.saveAssignments(assignmentsDraft);
      await fetchAssignments();
      toast('模型分配已保存并生效', 'success');
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const maskKey = (key: string) => {
    if (key.length <= 8) return '****';
    return key.slice(0, 4) + '****' + key.slice(-4);
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">任务管理</h1>
        <Button variant="outline" size="sm" onClick={fetchStatus} disabled={loading}>
          <RefreshCw className={`w-4 h-4 mr-1 ${loading ? 'animate-spin' : ''}`} />
          刷新
        </Button>
      </div>

      {status && (
        <>
          {/* 状态概览 */}
          <Card>
            <CardHeader><CardTitle className="text-lg">运行状态</CardTitle></CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <StatusItem label="行情推送" running={status.marketDataTask} />
                <StatusItem label="订单执行" running={status.orderExecutionTask} />
                <StatusItem label="T+1结算" running={status.settlementTask} />
                <StatusItem label="排行榜刷新" running={status.rankingTask} />
              </div>
              <div className="mt-4 flex gap-4 text-sm text-muted-foreground">
                <span>交易时段: {status.isTradingTime ? '是' : '否'}</span>
                <span>当前Tick: {status.currentTickIndex}/1440</span>
              </div>
            </CardContent>
          </Card>

          {/* 杠杆日利率 */}
          <Card>
            <CardHeader><CardTitle className="text-lg">杠杆日利率</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm text-muted-foreground">
                当前：{interestRateDecimal == null ? ' -' : ` ${(interestRateDecimal * 100).toFixed(4)}%/天（${interestRateDecimal}）`}
              </div>
              <div className="flex gap-3 items-center">
                <div className="flex-1">
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

          {/* ========== AI Key 管理 ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">API Key 管理</CardTitle>
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
              {aiKeys.length === 0 && !aiKeysLoading && (
                <div className="text-sm text-muted-foreground text-center py-4">暂无 API Key 配置</div>
              )}
              {aiKeys.map(key => (
                <div key={key.id} className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-bold">{key.configName}</span>
                      <Badge variant="outline" className="text-[10px]">{maskKey(key.apiKey)}</Badge>
                      {key.model && <Badge variant="secondary" className="text-[10px]">{key.model}</Badge>}
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
                  <div className="text-sm font-bold">{editingKey.id ? '编辑 API Key' : '新增 API Key'}</div>
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

          {/* ========== 模型分配 ========== */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-lg">模型分配</CardTitle>
                <Button variant="outline" size="sm" onClick={fetchAssignments} disabled={assignmentsLoading}>
                  <RefreshCw className={`w-3.5 h-3.5 mr-1 ${assignmentsLoading ? 'animate-spin' : ''}`} /> 刷新
                </Button>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-xs text-muted-foreground">每个功能独立选择 API Key 和模型，保存后立即生效。</div>
              {assignmentsDraft.map(a => (
                <div key={a.functionName} className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                  <span className="text-sm font-bold min-w-[5rem]">{FUNCTION_LABELS[a.functionName] || a.functionName}</span>
                  <select
                    className="flex-1 h-9 rounded-md border bg-background px-3 text-sm"
                    value={a.configId || ''}
                    onChange={e => updateDraft(a.functionName, 'configId', Number(e.target.value))}
                  >
                    <option value="">选择 API Key</option>
                    {aiKeys.map(k => (
                      <option key={k.id} value={k.id}>{k.configName} ({maskKey(k.apiKey)})</option>
                    ))}
                  </select>
                  <Input
                    className="flex-1"
                    value={a.model || ''}
                    onChange={e => updateDraft(a.functionName, 'model', e.target.value)}
                    placeholder="模型名，如 gpt-4o"
                  />
                </div>
              ))}
              <Button onClick={() => void handleSaveAssignments()} disabled={actionLoading === 'saveAssignments' || assignmentsDraft.length === 0}>
                <Save className="w-4 h-4 mr-1" /> 保存并生效
              </Button>
            </CardContent>
          </Card>

          {/* 行情推送控制 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Clock className="w-5 h-5" /> 行情推送 (含订单执行、排行榜)
              </CardTitle>
            </CardHeader>
            <CardContent className="flex gap-3">
              <Button onClick={() => handleAction(adminApi.startMarketPush, 'startMarket')} disabled={actionLoading !== null || status.marketDataTask}>
                <Play className="w-4 h-4 mr-1" /> 启动
              </Button>
              <Button variant="destructive" onClick={() => handleAction(adminApi.stopMarketPush, 'stopMarket')} disabled={actionLoading !== null || !status.marketDataTask}>
                <Square className="w-4 h-4 mr-1" /> 停止
              </Button>
            </CardContent>
          </Card>

          {/* 结算任务控制 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Database className="w-5 h-5" /> T+1结算任务
              </CardTitle>
            </CardHeader>
            <CardContent className="flex gap-3">
              <Button onClick={() => handleAction(adminApi.startSettlement, 'startSettle')} disabled={actionLoading !== null || status.settlementTask}>
                <Play className="w-4 h-4 mr-1" /> 启动
              </Button>
              <Button variant="destructive" onClick={() => handleAction(adminApi.stopSettlement, 'stopSettle')} disabled={actionLoading !== null || !status.settlementTask}>
                <Square className="w-4 h-4 mr-1" /> 停止
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
            <CardContent className="flex flex-wrap gap-3">
              <Button variant="outline" onClick={() => handleAction(adminApi.expireOrders, 'expire')} disabled={actionLoading !== null}>处理过期订单</Button>
              <Button variant="outline" onClick={() => handleAction(adminApi.bankruptcyCheck, 'bankruptcyCheck')} disabled={actionLoading !== null}>执行爆仓检查</Button>
              <Button variant="outline" onClick={() => handleAction(adminApi.accrueInterest, 'accrueInterest')} disabled={actionLoading !== null}>手动计息</Button>
              <div className="flex items-center gap-2">
                <Input type="number" value={generateOffset} onChange={e => setGenerateOffset(Number(e.target.value) || 0)} className="w-20 h-9" />
                <Button variant="outline" onClick={() => handleAction(() => adminApi.generateData(generateOffset), 'generate')} disabled={actionLoading !== null}>
                  生成行情(偏移{generateOffset >= 0 ? '+' : ''}{generateOffset}天)
                </Button>
              </div>
              <Button variant="outline" onClick={() => handleAction(adminApi.loadRedis, 'loadRedis')} disabled={actionLoading !== null}>加载今日行情到Redis</Button>
              <Button variant="outline" onClick={() => handleAction(async () => adminApi.refreshStockCache(), 'refreshStockCache')} disabled={actionLoading !== null}>按ticks重建今日汇总缓存</Button>
              <Button variant="outline" onClick={() => handleAction(adminApi.assetSnapshot, 'assetSnapshot')} disabled={actionLoading !== null}>资产快照</Button>
              <div className="flex items-center gap-2">
                <Input value={quantSymbol} onChange={e => setQuantSymbol(e.target.value.toUpperCase())} placeholder="币种，如 BTCUSDT" className="w-40 h-9" />
                <Button
                  variant="outline"
                  onClick={() => {
                    const sym = quantSymbol.trim();
                    if (!sym) return;
                    handleAction(() => adminApi.triggerQuant(sym), 'triggerQuant');
                    toast('量化分析已触发: ' + sym, 'success');
                  }}
                  disabled={actionLoading !== null || !quantSymbol.trim()}
                >
                  触发量化分析
                </Button>
                <Button
                  variant="outline"
                  onClick={() => {
                    const sym = quantSymbol.trim();
                    if (!sym) return;
                    handleAction(() => adminApi.triggerQuantVerification(sym), 'triggerQuantVerification');
                    toast('预测验证已触发: ' + sym, 'success');
                  }}
                  disabled={actionLoading !== null || !quantSymbol.trim()}
                >
                  触发预测验证
                </Button>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

function StatusItem({ label, running }: { label: string; running: boolean }) {
  return (
    <div className="flex items-center justify-between p-3 rounded-lg bg-muted/50">
      <span className="text-sm">{label}</span>
      <Badge variant={running ? 'default' : 'secondary'}>{running ? '运行中' : '已停止'}</Badge>
    </div>
  );
}
