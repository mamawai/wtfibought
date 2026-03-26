import { useState, useEffect, useCallback } from 'react';
import { Navigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { adminApi, type TaskStatus } from '../api';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Play, Square, RefreshCw, Database, Calendar, Clock } from 'lucide-react';

export function Admin() {
  const { user } = useUserStore();
  const [status, setStatus] = useState<TaskStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [generateOffset, setGenerateOffset] = useState(0);
  const [interestRateDecimal, setInterestRateDecimal] = useState<number | null>(null);
  const [interestRatePct, setInterestRatePct] = useState('');
  const [rateLoading, setRateLoading] = useState(false);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const data = await adminApi.taskStatus();
      setStatus(data);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchInterestRate = useCallback(async () => {
    setRateLoading(true);
    try {
      const rate = await adminApi.getDailyInterestRate();
      setInterestRateDecimal(rate);
      setInterestRatePct(rate > 0 ? String(rate * 100) : '0');
    } catch {
      // ignore
    } finally {
      setRateLoading(false);
    }
  }, []);

  useEffect(() => {
    if (user?.id === 1) {
      fetchStatus();
      fetchInterestRate();
    }
  }, [user, fetchStatus, fetchInterestRate]);

  if (!user || user.id !== 1) {
    return <Navigate to="/" replace />;
  }

  const handleAction = async (action: () => Promise<unknown>, name: string) => {
    setActionLoading(name);
    try {
      await action();
      await fetchStatus();
    } catch {
      // ignore
    } finally {
      setActionLoading(null);
    }
  };

  const handleSaveRate = async () => {
    const pct = Number(interestRatePct);
    if (!Number.isFinite(pct) || pct < 0 || pct > 100) {
      return;
    }
    setActionLoading('setRate');
    try {
      const decimal = pct / 100;
      const updated = await adminApi.setDailyInterestRate(decimal);
      setInterestRateDecimal(updated);
      setInterestRatePct(String(updated > 0 ? updated * 100 : 0));
    } catch {
      // ignore
    } finally {
      setActionLoading(null);
    }
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
            <CardHeader>
              <CardTitle className="text-lg">运行状态</CardTitle>
            </CardHeader>
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
            <CardHeader>
              <CardTitle className="text-lg">杠杆日利率</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="text-sm text-muted-foreground">
                当前：
                {interestRateDecimal == null
                  ? ' -'
                  : ` ${(interestRateDecimal * 100).toFixed(4)}%/天（${interestRateDecimal}）`}
              </div>
              <div className="flex gap-3 items-center">
                <div className="flex-1">
                  <Input
                    value={interestRatePct}
                    onChange={(e) => setInterestRatePct(e.target.value)}
                    placeholder="输入百分比，例如 0.05 表示 0.05%/天"
                    disabled={rateLoading || actionLoading !== null}
                  />
                </div>
                <Button
                  onClick={() => void fetchInterestRate()}
                  variant="outline"
                  disabled={rateLoading || actionLoading !== null}
                >
                  刷新
                </Button>
                <Button
                  onClick={() => void handleSaveRate()}
                  disabled={rateLoading || actionLoading !== null || interestRatePct.trim() === ''}
                >
                  保存
                </Button>
              </div>
              <div className="text-xs text-muted-foreground">
                输入为百分比；例如 0.05%/天 对应 decimal=0.0005。
              </div>
            </CardContent>
          </Card>

          {/* 行情推送控制 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Clock className="w-5 h-5" />
                行情推送 (含订单执行、排行榜)
              </CardTitle>
            </CardHeader>
            <CardContent className="flex gap-3">
              <Button
                onClick={() => handleAction(adminApi.startMarketPush, 'startMarket')}
                disabled={actionLoading !== null || status.marketDataTask}
              >
                <Play className="w-4 h-4 mr-1" />
                启动
              </Button>
              <Button
                variant="destructive"
                onClick={() => handleAction(adminApi.stopMarketPush, 'stopMarket')}
                disabled={actionLoading !== null || !status.marketDataTask}
              >
                <Square className="w-4 h-4 mr-1" />
                停止
              </Button>
            </CardContent>
          </Card>

          {/* 结算任务控制 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Database className="w-5 h-5" />
                T+1结算任务
              </CardTitle>
            </CardHeader>
            <CardContent className="flex gap-3">
              <Button
                onClick={() => handleAction(adminApi.startSettlement, 'startSettle')}
                disabled={actionLoading !== null || status.settlementTask}
              >
                <Play className="w-4 h-4 mr-1" />
                启动
              </Button>
              <Button
                variant="destructive"
                onClick={() => handleAction(adminApi.stopSettlement, 'stopSettle')}
                disabled={actionLoading !== null || !status.settlementTask}
              >
                <Square className="w-4 h-4 mr-1" />
                停止
              </Button>
            </CardContent>
          </Card>

          {/* 手动触发任务 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Calendar className="w-5 h-5" />
                手动触发
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-wrap gap-3">
              <Button
                variant="outline"
                onClick={() => handleAction(adminApi.expireOrders, 'expire')}
                disabled={actionLoading !== null}
              >
                处理过期订单
              </Button>
              <Button
                variant="outline"
                onClick={() => handleAction(adminApi.bankruptcyCheck, 'bankruptcyCheck')}
                disabled={actionLoading !== null}
              >
                执行爆仓检查
              </Button>
              <Button
                variant="outline"
                onClick={() => handleAction(adminApi.accrueInterest, 'accrueInterest')}
                disabled={actionLoading !== null}
              >
                手动计息
              </Button>
              <div className="flex items-center gap-2">
                <Input
                  type="number"
                  value={generateOffset}
                  onChange={(e) => setGenerateOffset(Number(e.target.value) || 0)}
                  className="w-20 h-9"
                />
                <Button
                  variant="outline"
                  onClick={() => handleAction(() => adminApi.generateData(generateOffset), 'generate')}
                  disabled={actionLoading !== null}
                >
                  生成行情(偏移{generateOffset >= 0 ? '+' : ''}{generateOffset}天)
                </Button>
              </div>
              <Button
                variant="outline"
                onClick={() => handleAction(adminApi.loadRedis, 'loadRedis')}
                disabled={actionLoading !== null}
              >
                加载今日行情到Redis
              </Button>
              <Button
                variant="outline"
                onClick={() => handleAction(async () => adminApi.refreshStockCache(), 'refreshStockCache')}
                disabled={actionLoading !== null}
              >
                按ticks重建今日汇总缓存
              </Button>
              <Button
                variant="outline"
                onClick={() => handleAction(adminApi.assetSnapshot, 'assetSnapshot')}
                disabled={actionLoading !== null}
              >
                资产快照
              </Button>
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
      <Badge variant={running ? 'default' : 'secondary'}>
        {running ? '运行中' : '已停止'}
      </Badge>
    </div>
  );
}
