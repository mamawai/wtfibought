import { useState, useEffect, useCallback } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { adminApi, type TaskStatus } from '../api';
import type { AiKeyConfig, AiModelAssignment, TradingRuntimeConfig, QuantRuntimeConfig, TradingCycleSubmitResult } from '../types';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { useToast } from '../components/ui/use-toast';
import { Play, Square, RefreshCw, Database, Calendar, Clock, Plus, Trash2, Pencil, Save, ShieldAlert, Activity } from 'lucide-react';

const FUNCTION_LABELS: Record<string, string> = {
  behavior: '行为分析',
  quant: '量化分析',
  chat: '追问对话',
  reflection: '反思验证',
};
const MODEL_ASSIGNMENT_FUNCTIONS = new Set(Object.keys(FUNCTION_LABELS));

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

  // 交易运行时开关
  const [tradingConfig, setTradingConfig] = useState<TradingRuntimeConfig>({ lowVolTradingEnabled: false });
  const [drawdownDraft, setDrawdownDraft] = useState<TradingRuntimeConfig>({});

  // 量化运行时开关
  const [quantConfig, setQuantConfig] = useState<QuantRuntimeConfig>({
    debateJudgeEnabled: false,
    debateJudgeShadowEnabled: false,
  });

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
      setAssignmentsDraft(list.filter(a => MODEL_ASSIGNMENT_FUNCTIONS.has(a.functionName)).map(a => ({ ...a })));
    } catch { /* ignore */ }
    finally { setAssignmentsLoading(false); }
  }, []);

  const fetchTradingConfig = useCallback(async () => {
    try {
      const c = await adminApi.getTradingConfig();
      setTradingConfig(c);
      setDrawdownDraft({
        drawdownWindowMinutes: c.drawdownWindowMinutes,
        drawdownPnlPctDropThresholdPpt: c.drawdownPnlPctDropThresholdPpt,
        drawdownProfitDrawdownThresholdPct: c.drawdownProfitDrawdownThresholdPct,
        drawdownProfitDrawdownMinBase: c.drawdownProfitDrawdownMinBase,
        drawdownCooldownMinutes: c.drawdownCooldownMinutes,
      });
    } catch { /* ignore */ }
  }, []);

  const fetchQuantConfig = useCallback(async () => {
    try {
      const c = await adminApi.getQuantConfig();
      setQuantConfig(c);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    if (user?.id === 1) {
      fetchStatus();
      fetchInterestRate();
      fetchAiKeys();
      fetchAssignments();
      fetchTradingConfig();
      fetchQuantConfig();
    }
  }, [user, fetchStatus, fetchInterestRate, fetchAiKeys, fetchAssignments, fetchTradingConfig, fetchQuantConfig]);

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

  const toggleLowVolTrading = async () => {
    const next = !tradingConfig.lowVolTradingEnabled;
    setActionLoading('toggleLowVol');
    try {
      const c = await adminApi.setTradingConfig({ lowVolTradingEnabled: next });
      setTradingConfig(c);
      toast(next ? '低波动小仓位模式：已开启' : '低波动小仓位模式：已关闭', 'success');
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const toggleLegacyThreshold5of7 = async () => {
    const next = !tradingConfig.legacyThreshold5of7Enabled;
    setActionLoading('toggleLegacyThreshold5of7');
    try {
      const c = await adminApi.setTradingConfig({ legacyThreshold5of7Enabled: next });
      setTradingConfig(c);
      toast(next ? 'LEGACY 5/7 实盘阈值：已开启' : 'LEGACY 5/7 实盘阈值：已关闭', 'success');
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const toggleLegacy5of7Shadow = async () => {
    const next = !tradingConfig.legacy5of7ShadowEnabled;
    setActionLoading('toggleLegacy5of7Shadow');
    try {
      const c = await adminApi.setTradingConfig({ legacy5of7ShadowEnabled: next });
      setTradingConfig(c);
      toast(next ? 'LEGACY 5/7 影子样本：已开启' : 'LEGACY 5/7 影子样本：已关闭', 'success');
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const toggleDrawdownSentinel = async () => {
    const next = !tradingConfig.drawdownSentinelEnabled;
    setActionLoading('toggleDrawdown');
    try {
      const c = await adminApi.setTradingConfig({ drawdownSentinelEnabled: next });
      setTradingConfig(c);
      toast(next ? '持仓回撤哨兵：已开启' : '持仓回撤哨兵：已关闭', 'success');
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const toggleDebateJudge = async () => {
    const next = !quantConfig.debateJudgeEnabled;
    setActionLoading('toggleDebate');
    try {
      const c = await adminApi.setQuantConfig({ debateJudgeEnabled: next });
      setQuantConfig(c);
      toast(next ? 'Bull/Bear辩论裁决：已开启' : 'Bull/Bear辩论裁决：已关闭（省3次LLM调用）', 'success');
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const toggleDebateShadow = async () => {
    const next = !quantConfig.debateJudgeShadowEnabled;
    setActionLoading('toggleDebateShadow');
    try {
      const c = await adminApi.setQuantConfig({ debateJudgeShadowEnabled: next });
      setQuantConfig(c);
      toast(next ? 'Bull/Bear辩论影子模式：已开启' : 'Bull/Bear辩论影子模式：已关闭', 'success');
    } catch { /* ignore */ }
    finally { setActionLoading(null); }
  };

  const saveDrawdownParams = async () => {
    setActionLoading('saveDrawdown');
    try {
      const c = await adminApi.setTradingConfig(drawdownDraft);
      setTradingConfig(c);
      setDrawdownDraft({
        drawdownWindowMinutes: c.drawdownWindowMinutes,
        drawdownPnlPctDropThresholdPpt: c.drawdownPnlPctDropThresholdPpt,
        drawdownProfitDrawdownThresholdPct: c.drawdownProfitDrawdownThresholdPct,
        drawdownProfitDrawdownMinBase: c.drawdownProfitDrawdownMinBase,
        drawdownCooldownMinutes: c.drawdownCooldownMinutes,
      });
      toast('回撤哨兵参数已保存', 'success');
    } catch (e) { toast((e as Error).message || '保存失败', 'error'); }
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

  const handleMessageAction = async (action: () => Promise<string>, name: string) => {
    setActionLoading(name);
    try {
      const message = await action();
      toast(message, 'success');
      await fetchStatus();
    } catch (e) {
      toast((e as Error).message || '操作失败', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const formatTradingSubmitResult = (result: TradingCycleSubmitResult) => {
    const submitted = result.items
      .filter(item => item.status === 'SUBMITTED')
      .map(item => item.symbol);
    const skipped = result.items
      .filter(item => item.status === 'SKIPPED')
      .map(item => `${item.symbol}:${item.reason || 'SKIPPED'}`);
    const parts = [`cycleNo=${result.cycleNo}`];
    if (submitted.length > 0) parts.push(`已提交 ${submitted.join(', ')}`);
    if (skipped.length > 0) parts.push(`已跳过 ${skipped.join(', ')}`);
    return `AI Trader提交完成：${parts.join('；')}`;
  };

  const maskKey = (key: string) => {
    if (key.length <= 8) return '****';
    return key.slice(0, 4) + '****' + key.slice(-4);
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-bold">任务管理</h1>
        <div className="flex items-center gap-1.5">
          <Link
            to="/admin/sprint-c"
            className="inline-flex h-8 items-center gap-1 rounded-md border bg-background px-2 text-xs font-medium hover:bg-muted"
          >
            <ShieldAlert className="w-3.5 h-3.5" />
            Sprint C
          </Link>
          <Link
            to="/admin/graph-obs"
            className="inline-flex h-8 items-center gap-1 rounded-md border bg-background px-2 text-xs font-medium hover:bg-muted"
          >
            <Activity className="w-3.5 h-3.5" />
            Graph 观测
          </Link>
          <Button variant="outline" size="sm" onClick={fetchStatus} disabled={loading}>
            <RefreshCw className={`w-4 h-4 mr-1 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </Button>
        </div>
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
                <div key={a.functionName} className="flex flex-col md:flex-row md:items-center gap-2 md:gap-3 p-3 rounded-lg border bg-muted/30">
                  <span className="text-sm font-bold min-w-[5rem]">{FUNCTION_LABELS[a.functionName] || a.functionName}</span>
                  <select
                    className="w-full md:flex-1 h-9 rounded-md border bg-background px-3 text-sm"
                    value={a.configId || ''}
                    onChange={e => updateDraft(a.functionName, 'configId', Number(e.target.value))}
                  >
                    <option value="">选择 API Key</option>
                    {aiKeys.map(k => (
                      <option key={k.id} value={k.id}>{k.configName} ({maskKey(k.apiKey)})</option>
                    ))}
                  </select>
                  <Input
                    className="w-full md:flex-1"
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
            <CardContent className="space-y-4">
              {/* 股票行情 */}
              <div>
                <div className="text-xs text-muted-foreground mb-2">股票行情</div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="col-span-2 flex gap-2">
                    <Input type="number" value={generateOffset} onChange={e => setGenerateOffset(Number(e.target.value) || 0)} className="w-20 h-9 shrink-0" />
                    <Button variant="outline" className="flex-1 h-9 text-xs" onClick={() => handleAction(() => adminApi.generateData(generateOffset), 'generate')} disabled={actionLoading !== null}>
                      生成行情(偏移{generateOffset >= 0 ? '+' : ''}{generateOffset}天)
                    </Button>
                  </div>
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.loadRedis, 'loadRedis')} disabled={actionLoading !== null}>加载行情到Redis</Button>
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(async () => adminApi.refreshStockCache(), 'refreshStockCache')} disabled={actionLoading !== null}>重建汇总缓存</Button>
                </div>
              </div>
              {/* 结算与风控 */}
              <div>
                <div className="text-xs text-muted-foreground mb-2">结算与风控</div>
                <div className="grid grid-cols-2 gap-2">
                  <Button variant="outline" className="h-9 text-xs" onClick={() => handleAction(adminApi.expireOrders, 'expire')} disabled={actionLoading !== null}>处理过期订单</Button>
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
                  <Button variant="outline" className="w-full h-9 text-xs" onClick={() => { void handleMessageAction(async () => formatTradingSubmitResult(await adminApi.triggerAiTraderDetails()), 'triggerAiTrader'); }} disabled={actionLoading !== null}>唤醒AI Trader决策(全部币种)</Button>
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>交易运行时开关</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="flex items-center justify-between gap-4">
                <div className="flex-1">
                  <div className="font-medium">低波动小仓位模式</div>
                  <div className="text-xs text-muted-foreground mt-1">
                    开启：盘整期(ATR×SL倍数&lt;noise floor)时，扩SL到噪音下限（≤3.0x）、TP同比例扩、仓位×0.6试探入场<br/>
                    关闭：低波动期直接HOLD（保守，默认）。重启后保持当前设置。
                  </div>
                  <div className="text-xs mt-2">
                    当前状态：<Badge variant={tradingConfig.lowVolTradingEnabled ? 'default' : 'secondary'}>
                      {tradingConfig.lowVolTradingEnabled ? '已开启（激进）' : '已关闭（保守）'}
                    </Badge>
                  </div>
                </div>
                <Button
                  variant={tradingConfig.lowVolTradingEnabled ? 'default' : 'outline'}
                  onClick={() => void toggleLowVolTrading()}
                  disabled={actionLoading !== null}
                >
                  {tradingConfig.lowVolTradingEnabled ? '关闭' : '开启'}
                </Button>
              </div>

              <div className="flex items-center justify-between gap-4 border-t pt-4">
                <div className="flex-1">
                  <div className="font-medium">LEGACY 5/7 实盘阈值</div>
                  <div className="text-xs text-muted-foreground mt-1">
                    开启：LEGACY_TREND 在 5/7 共振时也允许进入实盘开仓链路。<br/>
                    关闭：只允许 6/7 共振实盘开仓（默认）。重启后保持当前设置。
                  </div>
                  <div className="text-xs mt-2">
                    当前状态：<Badge variant={tradingConfig.legacyThreshold5of7Enabled ? 'default' : 'secondary'}>
                      {tradingConfig.legacyThreshold5of7Enabled ? '已开启（激进）' : '已关闭（保守）'}
                    </Badge>
                  </div>
                </div>
                <Button
                  variant={tradingConfig.legacyThreshold5of7Enabled ? 'default' : 'outline'}
                  onClick={() => void toggleLegacyThreshold5of7()}
                  disabled={actionLoading !== null}
                >
                  {tradingConfig.legacyThreshold5of7Enabled ? '关闭' : '开启'}
                </Button>
              </div>

              <div className="flex items-center justify-between gap-4 border-t pt-4">
                <div className="flex-1">
                  <div className="font-medium">LEGACY 5/7 影子样本</div>
                  <div className="text-xs text-muted-foreground mt-1">
                    开启：5/7 但实盘阈值关闭时，只记录 SHADOW_5OF7 决策，不下单。<br/>
                    关闭：5/7 直接按共振不足 HOLD。实盘阈值开启时，此项不额外生效。
                  </div>
                  <div className="text-xs mt-2">
                    当前状态：<Badge variant={tradingConfig.legacy5of7ShadowEnabled ? 'default' : 'secondary'}>
                      {tradingConfig.legacy5of7ShadowEnabled ? '已开启（只观测）' : '已关闭'}
                    </Badge>
                  </div>
                </div>
                <Button
                  variant={tradingConfig.legacy5of7ShadowEnabled ? 'default' : 'outline'}
                  onClick={() => void toggleLegacy5of7Shadow()}
                  disabled={actionLoading !== null}
                >
                  {tradingConfig.legacy5of7ShadowEnabled ? '关闭' : '开启'}
                </Button>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>量化运行时开关</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="flex items-center justify-between gap-4">
                <div className="flex-1">
                  <div className="font-medium">Bull/Bear 辩论裁决 (Q4.5)</div>
                  <div className="text-xs text-muted-foreground mt-1">
                    开启：每轮量化分析跑 Bull辩手 ∥ Bear辩手 + Judge裁判共3次LLM调用，对HorizonJudge裁决做二次审核与概率修正。<br/>
                    关闭：跳过正式辩论，保留原始forecasts。重启后保持当前设置。
                  </div>
                  <div className="text-xs mt-2">
                    当前状态：<Badge variant={quantConfig.debateJudgeEnabled ? 'default' : 'secondary'}>
                      {quantConfig.debateJudgeEnabled ? '已开启' : '已关闭（默认）'}
                    </Badge>
                  </div>
                </div>
                <Button
                  variant={quantConfig.debateJudgeEnabled ? 'default' : 'outline'}
                  onClick={() => void toggleDebateJudge()}
                  disabled={actionLoading !== null}
                >
                  {quantConfig.debateJudgeEnabled ? '关闭' : '开启'}
                </Button>
              </div>

              <div className="flex items-center justify-between gap-4 border-t pt-4">
                <div className="flex-1">
                  <div className="font-medium">Bull/Bear 辩论影子模式</div>
                  <div className="text-xs text-muted-foreground mt-1">
                    开启：正式辩论关闭时仍跑3次LLM，只写 shadow 结果到 debate_json，不影响forecast、报告概率和交易。<br/>
                    正式辩论已开启时，shadow 不额外生效，避免重复调用。
                  </div>
                  <div className="text-xs mt-2">
                    当前状态：<Badge variant={quantConfig.debateJudgeShadowEnabled ? 'default' : 'secondary'}>
                      {quantConfig.debateJudgeShadowEnabled ? '已开启（只观测）' : '已关闭（默认）'}
                    </Badge>
                  </div>
                </div>
                <Button
                  variant={quantConfig.debateJudgeShadowEnabled ? 'default' : 'outline'}
                  onClick={() => void toggleDebateShadow()}
                  disabled={actionLoading !== null || quantConfig.debateJudgeEnabled}
                >
                  {quantConfig.debateJudgeShadowEnabled ? '关闭' : '开启'}
                </Button>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>持仓回撤哨兵 (主动平仓)</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <div className="flex items-center justify-between gap-4">
                  <div className="flex-1">
                    <div className="font-medium">按持仓回撤主动平仓</div>
                    <div className="text-xs text-muted-foreground mt-1">
                      每分钟扫描AI账户所有OPEN持仓，窗口内PnL急速恶化时直接市价平仓。<br/>
                      <b>条件A</b>：PnL%下降 ≥ 阈值ppt（保证金权益角度）<br/>
                      <b>条件B</b>：盈利金额回撤 ≥ 阈值%（仅在窗口最高盈利 &gt; 基线USDT时启用）<br/>
                      <b>条件C</b>：生命周期峰值浮盈达到ATR阈值后回撤到指定比例以下<br/>
                      任一条件满足即平仓，日志会注明触发原因。同持仓平仓后进入冷却期。
                    </div>
                    <div className="text-xs mt-2">
                      当前状态：<Badge variant={tradingConfig.drawdownSentinelEnabled ? 'default' : 'secondary'}>
                        {tradingConfig.drawdownSentinelEnabled ? '已开启（默认）' : '已关闭'}
                      </Badge>
                    </div>
                  </div>
                  <Button
                    variant={tradingConfig.drawdownSentinelEnabled ? 'default' : 'outline'}
                    onClick={() => void toggleDrawdownSentinel()}
                    disabled={actionLoading !== null}
                  >
                    {tradingConfig.drawdownSentinelEnabled ? '关闭' : '开启'}
                  </Button>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="text-xs text-muted-foreground">窗口时长(分钟)</label>
                    <Input
                      type="number"
                      min={1}
                      value={drawdownDraft.drawdownWindowMinutes ?? ''}
                      onChange={(e) => setDrawdownDraft({ ...drawdownDraft, drawdownWindowMinutes: Number(e.target.value) })}
                    />
                  </div>
                  <div>
                    <label className="text-xs text-muted-foreground">冷却时长(分钟)</label>
                    <Input
                      type="number"
                      min={1}
                      value={drawdownDraft.drawdownCooldownMinutes ?? ''}
                      onChange={(e) => setDrawdownDraft({ ...drawdownDraft, drawdownCooldownMinutes: Number(e.target.value) })}
                    />
                  </div>
                  <div>
                    <label className="text-xs text-muted-foreground">A - PnL%下降阈值(ppt)</label>
                    <Input
                      type="number"
                      step="0.1"
                      min={0}
                      value={drawdownDraft.drawdownPnlPctDropThresholdPpt ?? ''}
                      onChange={(e) => setDrawdownDraft({ ...drawdownDraft, drawdownPnlPctDropThresholdPpt: Number(e.target.value) })}
                    />
                  </div>
                  <div>
                    <label className="text-xs text-muted-foreground">B - 盈利回撤阈值(%)</label>
                    <Input
                      type="number"
                      step="1"
                      min={0}
                      value={drawdownDraft.drawdownProfitDrawdownThresholdPct ?? ''}
                      onChange={(e) => setDrawdownDraft({ ...drawdownDraft, drawdownProfitDrawdownThresholdPct: Number(e.target.value) })}
                    />
                  </div>
                  <div>
                    <label className="text-xs text-muted-foreground">B - 盈利基线(USDT)</label>
                    <Input
                      type="number"
                      step="1"
                      min={0}
                      value={drawdownDraft.drawdownProfitDrawdownMinBase ?? ''}
                      onChange={(e) => setDrawdownDraft({ ...drawdownDraft, drawdownProfitDrawdownMinBase: Number(e.target.value) })}
                    />
                  </div>
                </div>

                <Button
                  onClick={() => void saveDrawdownParams()}
                  disabled={actionLoading !== null}
                >
                  <Save className="w-4 h-4 mr-2" />保存参数
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
