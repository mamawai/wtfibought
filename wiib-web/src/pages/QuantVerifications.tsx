import { useState, useCallback, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { aiAgentApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import { Loader2, CheckCircle2, ArrowLeft, RefreshCw } from 'lucide-react';
import { parseRiskTags, translateRiskTag } from '../lib/utils';
import type { QuantVerificationSummary } from '../types';

const HORIZON_LABELS: Record<string, string> = { '0_10': '0-10min', '10_20': '10-20min', '20_30': '20-30min' };

export function QuantVerifications() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { toast } = useToast();

  const paramSymbol = searchParams.get('symbol') || 'BTCUSDT';
  const [symbol, setSymbol] = useState(paramSymbol);
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<QuantVerificationSummary | null>(null);

  const load = useCallback(async (sym: string) => {
    setLoading(true);
    try {
      const data = await aiAgentApi.verifications(sym, 10);
      setSummary(data);
    } catch (e) {
      toast((e as Error).message || '加载预测验证失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    void load(paramSymbol);
  }, [paramSymbol, load]);

  const handleSearch = () => {
    const normalized = symbol.trim().toUpperCase() || 'BTCUSDT';
    setSearchParams({ symbol: normalized });
  };

  return (
    <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate('/ai')}>
          <ArrowLeft className="w-4 h-4 mr-1" /> 返回
        </Button>
        <h1 className="text-lg font-bold flex items-center gap-2">
          <CheckCircle2 className="w-5 h-5 text-primary" />
          预测验证
        </h1>
      </div>

      <div className="flex items-center gap-3">
        <Input
          value={symbol}
          onChange={e => setSymbol(e.target.value.toUpperCase())}
          placeholder="币种，如 BTCUSDT"
          className="w-44"
          onKeyDown={e => e.key === 'Enter' && handleSearch()}
        />
        <Button variant="outline" size="sm" onClick={handleSearch}>查询</Button>
        <Button variant="outline" size="sm" onClick={() => void load(paramSymbol)} disabled={loading}>
          <RefreshCw className={`w-4 h-4 mr-1 ${loading ? 'animate-spin' : ''}`} /> 刷新
        </Button>
        <span className="text-xs text-muted-foreground ml-auto">{paramSymbol} 最近已验证周期</span>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16 gap-2 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" /> 加载验证结果...
        </div>
      ) : !summary || summary.cycles.length === 0 ? (
        <div className="text-sm text-muted-foreground text-center py-16">
          暂无已验证的预测结果
        </div>
      ) : (
        <div className="space-y-4">
          <div className="flex items-center gap-3 text-sm">
            <Badge variant="outline">总计 {summary.total} 条</Badge>
            <Badge variant="default">命中 {summary.correct} 条</Badge>
            <Badge variant={summary.correct / Math.max(summary.total, 1) >= 0.5 ? 'default' : 'destructive'}>
              准确率 {summary.accuracyRate}
            </Badge>
          </div>
          {summary.cycles.map((cycle) => (
            <Card key={cycle.cycleId} className="border-primary/20">
              <CardHeader className="pb-2">
                <div className="flex items-center gap-2 flex-wrap">
                  <CardTitle className="text-sm">{cycle.symbol}</CardTitle>
                  {cycle.overallDecision && (
                    <Badge variant="outline" className="text-xs">{cycle.overallDecision === 'FLAT' ? '观望' : cycle.overallDecision}</Badge>
                  )}
                  {cycle.riskStatus && cycle.riskStatus !== 'NORMAL' && (
                    <div className="flex flex-wrap gap-1">
                      {parseRiskTags(cycle.riskStatus).map(tag => (
                        <Badge key={tag} variant="destructive" className="text-[10px]">{translateRiskTag(tag)}</Badge>
                      ))}
                    </div>
                  )}
                  {cycle.riskStatus === 'NORMAL' && (
                    <Badge variant="default" className="text-xs">正常</Badge>
                  )}
                  <span className="text-[11px] text-muted-foreground ml-auto">
                    预测 {cycle.forecastTime ? new Date(cycle.forecastTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-'}
                  </span>
                </div>
                <div className="text-[11px] text-muted-foreground">
                  {cycle.cycleId}
                  {cycle.verifiedAt && ` · 验证于 ${new Date(cycle.verifiedAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}`}
                </div>
              </CardHeader>
              <CardContent>
                <div className="grid gap-2 md:grid-cols-3">
                  {cycle.items.map((item) => (
                    <div key={item.id} className="rounded-lg border bg-muted/30 p-3 space-y-2">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold">{HORIZON_LABELS[item.horizon] || item.horizon}</span>
                        <Badge
                          variant={
                            item.tradeQuality === 'GOOD'
                              ? 'default'
                              : item.tradeQuality === 'BAD'
                              ? 'destructive'
                              : 'outline'
                          }
                          className="text-[10px]"
                        >
                          {item.tradeQuality}
                        </Badge>
                      </div>
                      <div className="text-xs text-muted-foreground space-y-1">
                        {item.resultSummary ? (
                          <div className="text-foreground">{item.resultSummary}</div>
                        ) : (
                          <>
                            <div>预测: <span className="font-semibold text-foreground">{item.predictedDirection}</span> · 置信 {(item.predictedConfidence * 100).toFixed(0)}%</div>
                            <div>实际: <span className={item.actualChangeBps >= 0 ? 'text-green-600 font-semibold' : 'text-red-600 font-semibold'}>{item.actualChangeBps >= 0 ? '+' : ''}{item.actualChangeBps}bps</span></div>
                          </>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
