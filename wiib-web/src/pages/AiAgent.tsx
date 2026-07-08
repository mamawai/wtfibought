import { useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { aiAgentApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Workbench } from '../components/workbench/Workbench';
import { Loader2, Brain, User, CheckCircle2, BarChart3, Zap, Trophy, BrainCircuit } from 'lucide-react';
import type { BehaviorAnalysisReport } from '../types';

type Tab = 'behavior' | 'workbench';

export function AiAgent() {
  const { toast } = useToast();
  const [tab, setTab] = useState<Tab>('workbench');
  const [behaviorLoading, setBehaviorLoading] = useState(false);
  const [steps] = useState<string[]>([]);
  const [behaviorReport, setBehaviorReport] = useState<BehaviorAnalysisReport | null>(null);

  const handleAnalyzeBehavior = useCallback(async () => {
    setBehaviorLoading(true);
    try {
      const report = await aiAgentApi.analyzeBehavior() as unknown as BehaviorAnalysisReport;
      setBehaviorReport(report);
      toast('分析完成', 'success');
    } catch (e: unknown) {
      toast((e as Error).message || '分析失败', 'error');
    } finally {
      setBehaviorLoading(false);
    }
  }, [toast]);

  return (
    <div className="max-w-7xl mx-auto p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-3 px-3 py-2 rounded-lg bg-primary/10 border border-primary/20 text-primary text-xs font-bold">
        <Zap className="w-4 h-4 shrink-0" />
        投资有风险，当前分析结果仅供参考不构成任何建议
        <Link to="/scorecard" className="ml-auto hidden sm:flex items-center gap-1 hover:underline shrink-0">
          <Trophy className="w-3.5 h-3.5" /> 预测战绩
        </Link>
      </div>

      {/* Tab Switcher */}
      <div className="flex bg-muted rounded-lg p-0.5 max-w-md">
        <button
          onClick={() => setTab('workbench')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-md text-sm font-bold transition-all ${tab === 'workbench' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
        >
          <BrainCircuit className="w-4 h-4" />
          研判工作台
        </button>
        <button
          onClick={() => setTab('behavior')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-md text-sm font-bold transition-all ${tab === 'behavior' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
        >
          <User className="w-4 h-4" />
          行为分析
        </button>
      </div>

      {/* 研判工作台（P7）：左对话右时间线，PC 双栏吃满 7xl */}
      {tab === 'workbench' && <Workbench />}

      {/* 行为分析（behavior agent，保持原样）：窄容器保读感 */}
      {tab === 'behavior' && (
        <div className="space-y-4 max-w-5xl">
          {!behaviorReport || !behaviorReport.overview ? (
            <Card>
              <CardContent className="p-5 sm:p-8 text-center">
                {!behaviorLoading ? (
                  <>
                    <Brain className="w-10 h-10 sm:w-12 sm:h-12 mx-auto text-muted-foreground mb-4" />
                    <h2 className="text-lg font-bold mb-2">用户行为分析</h2>
                    <p className="text-sm text-muted-foreground mb-6 max-w-md mx-auto">
                      基于你的全部历史交易数据、游戏记录、风险偏好等维度，全面分析你的投资行为特征
                    </p>
                    <Button onClick={handleAnalyzeBehavior} size="lg">开始分析</Button>
                  </>
                ) : (
                  <>
                    <Brain className="w-10 h-10 sm:w-12 sm:h-12 mx-auto text-primary mb-4 animate-pulse" />
                    <h2 className="text-lg font-bold mb-4">正在分析中...</h2>
                    {steps.length > 0 && (
                      <div className="text-left max-w-xs mx-auto space-y-2">
                        {steps.map((step, i) => (
                          <div key={i} className="flex items-center gap-2 text-sm">
                            {i === steps.length - 1 ? (
                              <Loader2 className="w-3.5 h-3.5 animate-spin text-primary shrink-0" />
                            ) : (
                              <CheckCircle2 className="w-3.5 h-3.5 text-green-500 shrink-0" />
                            )}
                            <span className={i === steps.length - 1 ? 'text-foreground' : 'text-muted-foreground'}>
                              {step}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </CardContent>
            </Card>
          ) : (
            <>
              {/* 概览 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <BarChart3 className="w-4 h-4" /> 资产概览
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="grid grid-cols-2 gap-4">
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xl sm:text-2xl font-black tabular-nums">${behaviorReport.overview.totalAssets.toLocaleString()}</div>
                      <div className="text-xs text-muted-foreground">总资产</div>
                    </div>
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className={`text-xl sm:text-2xl font-black tabular-nums ${behaviorReport.overview.totalProfitPct >= 0 ? 'text-green-500' : 'text-red-500'}`}>
                        {behaviorReport.overview.totalProfitPct >= 0 ? '+' : ''}{behaviorReport.overview.totalProfitPct.toFixed(2)}%
                      </div>
                      <div className="text-xs text-muted-foreground">总收益率</div>
                    </div>
                  </div>

                  {behaviorReport.overview.distribution.length > 0 && (
                    <div>
                      <div className="text-xs text-muted-foreground mb-2">资产分布</div>
                      <div className="flex flex-wrap gap-2">
                        {behaviorReport.overview.distribution.map((d, i) => (
                          <Badge key={i} variant="outline">{d.category}: ${d.value.toLocaleString()}</Badge>
                        ))}
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* 交易行为 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">交易行为分析</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  {behaviorReport.tradeBehavior.stock.positionCount > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">股票</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>持仓: <span className="font-bold">{behaviorReport.tradeBehavior.stock.positionCount}</span></span>
                        <span>订单: <span className="font-bold">{behaviorReport.tradeBehavior.stock.orderCount}</span></span>
                        <span>买入额: <span className="font-bold">${behaviorReport.tradeBehavior.stock.totalBuyAmount.toLocaleString()}</span></span>
                        <span>偏好: <span className="font-bold">{behaviorReport.tradeBehavior.stock.preference}</span></span>
                      </div>
                    </div>
                  )}
                  {behaviorReport.tradeBehavior.crypto.positionCount > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">加密货币</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>持仓: <span className="font-bold">{behaviorReport.tradeBehavior.crypto.positionCount}</span></span>
                        <span>杠杆: <span className="font-bold">{behaviorReport.tradeBehavior.crypto.leverageUsage}</span></span>
                        <span>买入: <span className="font-bold">${behaviorReport.tradeBehavior.crypto.totalBuyAmount.toLocaleString()}</span></span>
                        <span>卖出: <span className="font-bold">${behaviorReport.tradeBehavior.crypto.totalSellAmount.toLocaleString()}</span></span>
                      </div>
                    </div>
                  )}
                  {behaviorReport.tradeBehavior.futures.orderCount > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">永续合约</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>订单: <span className="font-bold">{behaviorReport.tradeBehavior.futures.orderCount}</span></span>
                        <span>方向: <span className="font-bold">{behaviorReport.tradeBehavior.futures.direction}</span></span>
                        <span>平仓盈亏: <span className="font-bold">{behaviorReport.tradeBehavior.futures.realizedPnl >= 0 ? '+' : ''}{behaviorReport.tradeBehavior.futures.realizedPnl.toFixed(2)}</span></span>
                        <span>平均杠杆: <span className="font-bold">{behaviorReport.tradeBehavior.futures.avgLeverage}x</span></span>
                      </div>
                    </div>
                  )}
                  {behaviorReport.tradeBehavior.prediction.frequency > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">预测交易</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>频率: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.frequency}次</span></span>
                        <span>胜率: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.winRate}%</span></span>
                        <span>净盈亏: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.netProfit >= 0 ? '+' : ''}{behaviorReport.tradeBehavior.prediction.netProfit.toFixed(2)}</span></span>
                        <span>偏好: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.directionPreference}</span></span>
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* 游戏行为 */}
              {behaviorReport.gameBehavior && (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base">游戏行为分析</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {behaviorReport.gameBehavior.blackjack.totalHands > 0 && (
                      <div className="p-3 rounded-lg bg-muted/50">
                        <div className="text-xs text-muted-foreground mb-1">Blackjack</div>
                        <div className="grid grid-cols-2 gap-2 text-xs">
                          <span>局数: <span className="font-bold">{behaviorReport.gameBehavior.blackjack.totalHands}</span></span>
                          <span>胜: <span className="font-bold text-green-500">{behaviorReport.gameBehavior.blackjack.totalWon}</span></span>
                          <span>负: <span className="font-bold text-red-500">{behaviorReport.gameBehavior.blackjack.totalLost}</span></span>
                          <span>最大赢: <span className="font-bold">${behaviorReport.gameBehavior.blackjack.biggestWin}</span></span>
                        </div>
                      </div>
                    )}
                    {behaviorReport.gameBehavior.mines.frequency > 0 && (
                      <div className="p-3 rounded-lg bg-muted/50">
                        <div className="text-xs text-muted-foreground mb-1">矿工游戏</div>
                        <div className="grid grid-cols-2 gap-2 text-xs">
                          <span>频率: <span className="font-bold">{behaviorReport.gameBehavior.mines.frequency}次</span></span>
                          <span>净盈亏: <span className="font-bold">{behaviorReport.gameBehavior.mines.netProfit >= 0 ? '+' : ''}{behaviorReport.gameBehavior.mines.netProfit.toFixed(2)}</span></span>
                        </div>
                      </div>
                    )}
                    {behaviorReport.gameBehavior.videoPoker.frequency > 0 && (
                      <div className="p-3 rounded-lg bg-muted/50">
                        <div className="text-xs text-muted-foreground mb-1">视频扑克</div>
                        <div className="grid grid-cols-2 gap-2 text-xs">
                          <span>频率: <span className="font-bold">{behaviorReport.gameBehavior.videoPoker.frequency}次</span></span>
                          <span>净盈亏: <span className="font-bold">{behaviorReport.gameBehavior.videoPoker.netProfit >= 0 ? '+' : ''}{behaviorReport.gameBehavior.videoPoker.netProfit.toFixed(2)}</span></span>
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>
              )}

              {/* 风险画像 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">风险画像</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="flex items-center gap-3 flex-wrap">
                    <Badge variant={behaviorReport.riskProfile.riskLevel === 'HIGH' ? 'destructive' : behaviorReport.riskProfile.riskLevel === 'MEDIUM' ? 'default' : 'outline'}>
                      {behaviorReport.riskProfile.riskLevel}
                    </Badge>
                    <span className="text-xs">爆仓次数: <span className="font-bold">{behaviorReport.riskProfile.bankruptCount}</span></span>
                    <span className="text-xs">最大回撤: <span className="font-bold">{behaviorReport.riskProfile.maxDrawdown}</span></span>
                  </div>
                </CardContent>
              </Card>

              {/* 建议 */}
              {behaviorReport.suggestions.length > 0 && (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base">个性化建议</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <ul className="space-y-2">
                      {behaviorReport.suggestions.map((s, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm">
                          <CheckCircle2 className="w-4 h-4 text-green-500 shrink-0 mt-0.5" />
                          {s}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              )}

              <Button variant="outline" onClick={() => setBehaviorReport(null)} className="mb-4">重新分析</Button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
