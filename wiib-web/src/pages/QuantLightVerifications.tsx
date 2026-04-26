import { useState, useCallback, useEffect, useMemo } from 'react';
import { useSearchParams, useNavigate, useLocation } from 'react-router-dom';
import { aiAgentApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Loader2, ArrowLeft } from 'lucide-react';
import { parseRiskTags, translateRiskTag } from '../lib/utils';
import type { GroupedHeavyCycle, GroupedVerificationSummary, QuantForecastAdjustment, QuantForecastVerificationItem, QuantVerificationCycleResult } from '../types';

const HORIZON_LABELS: Record<string, string> = { '0_10': '0-10min', '10_20': '10-20min', '20_30': '20-30min' };

function formatTime(iso: string | null) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

// 轻周期修正徽章样式：颜色对应「对重周期的影响强度」
const ADJUST_BADGE: Record<QuantForecastAdjustment['adjustType'], { text: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  FLIP: { text: '⇄ 翻盘', variant: 'destructive' },
  LIGHT_VETO: { text: '⊘ 否决', variant: 'destructive' },
  SAME_DIR_BOOST: { text: '↑ 加成', variant: 'default' },
  OPPO_STRONG_PENALTY: { text: '↓ 强削', variant: 'secondary' },
  OPPO_WEAK_PENALTY: { text: '↓ 轻削', variant: 'outline' },
};

function buildAdjustTooltip(adjs: QuantForecastAdjustment[]): string {
  // 按 createdAt 倒序，逐条展开。每行：[时间] prev(conf) → new(conf) · 类型 · vote N/2
  return adjs
    .slice()
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .map(a => {
      const pConf = (a.prevHeavyConfidence * 100).toFixed(0);
      const nConf = (a.newHeavyConfidence * 100).toFixed(0);
      const lConf = (a.lightConfidence * 100).toFixed(0);
      return `[${formatTime(a.createdAt)}] ${a.lightHorizon}→${a.heavyHorizon} ${a.adjustType}\n  轻 ${a.lightDirection}(${lConf}%) · 重 ${a.prevHeavyDirection}(${pConf}%) → ${a.newHeavyDirection}(${nConf}%) · 票 ${a.voteCountAfter}/2`;
    })
    .join('\n\n');
}

function HorizonCard({ item, adjustments }: { item: QuantForecastVerificationItem; adjustments?: QuantForecastAdjustment[] }) {
  // 同一 (lightCycleId, lightHorizon) 理论上只会有 1 条 adjustment（dMin 下 lightH→heavyH 一一映射），但容器支持多条
  const latest = adjustments && adjustments.length > 0 ? adjustments[adjustments.length - 1] : null;
  const cfg = latest ? ADJUST_BADGE[latest.adjustType] : null;
  const tooltip = adjustments && adjustments.length > 0 ? buildAdjustTooltip(adjustments) : '';
  return (
    <div className="rounded-lg border bg-muted/30 p-3 space-y-2 relative">
      <div className="flex items-center justify-between">
        <span className="text-xs font-bold">{HORIZON_LABELS[item.horizon] || item.horizon}</span>
        <Badge
          variant={item.tradeQuality === 'GOOD' ? 'default' : item.tradeQuality === 'BAD' ? 'destructive' : 'outline'}
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
      {cfg && (
        <div className="absolute bottom-1 right-1" title={tooltip}>
          <Badge variant={cfg.variant} className="text-[9px] h-4 px-1 cursor-help">
            {cfg.text}
          </Badge>
        </div>
      )}
    </div>
  );
}

function CycleHeader({ cycle, compact }: { cycle: QuantVerificationCycleResult; compact?: boolean }) {
  return (
    <div className={compact ? 'space-y-0.5' : 'space-y-1'}>
      <div className="flex items-center gap-2 flex-wrap">
        {!compact && <CardTitle className="text-sm">{cycle.symbol}</CardTitle>}
        {cycle.overallDecision && (
          <Badge variant="outline" className="text-xs">{cycle.overallDecision === 'FLAT' ? '观望' : cycle.overallDecision}</Badge>
        )}
        {cycle.riskStatus && cycle.riskStatus !== 'NORMAL' ? (
          <div className="flex flex-wrap gap-1">
            {parseRiskTags(cycle.riskStatus).map(tag => (
              <Badge key={tag} variant="destructive" className="text-[10px]">{translateRiskTag(tag)}</Badge>
            ))}
          </div>
        ) : cycle.riskStatus === 'NORMAL' ? (
          <Badge variant="default" className="text-xs">正常</Badge>
        ) : null}
        <span className="text-[11px] text-muted-foreground ml-auto">
          {compact ? '' : '预测 '}{formatTime(cycle.forecastTime)}
        </span>
      </div>
      <div className="text-[11px] text-muted-foreground">
        {cycle.cycleId}
        {cycle.verifiedAt && ` · 验证于 ${formatTime(cycle.verifiedAt)}`}
      </div>
    </div>
  );
}

export function QuantLightVerifications() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const { toast } = useToast();

  const symbol = searchParams.get('symbol') || 'BTCUSDT';
  const parentCycleId = searchParams.get('parentCycleId') || '';

  // 优先从路由 state 拿数据（和主页面完全一致）
  const stateGroup = (location.state as { group?: GroupedHeavyCycle } | null)?.group ?? null;

  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<GroupedVerificationSummary | null>(null);

  const load = useCallback(async () => {
    if (!parentCycleId) {
      toast('缺少 parentCycleId 参数', 'error');
      return;
    }
    setLoading(true);
    try {
      // 和主页面用相同的 limit=10，确保归属结果一致
      const data = await aiAgentApi.groupedVerifications(symbol, 10);
      setSummary(data);
    } catch (e) {
      toast((e as Error).message || '加载预测验证失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [symbol, parentCycleId, toast]);

  useEffect(() => {
    if (!stateGroup) {
      void load();
    }
  }, [load, stateGroup]);

  const group = useMemo(() => {
    if (stateGroup) return stateGroup;
    if (!summary) return null;
    return summary.groups.find(g => g.heavy.cycleId === parentCycleId) || null;
  }, [stateGroup, summary, parentCycleId]);

  const heavy = group?.heavy;
  const lightCycles = group?.lightCycles || [];

  return (
    <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate(`/verifications?symbol=${symbol}`)}>
          <ArrowLeft className="w-4 h-4 mr-1" /> 返回验证页
        </Button>
        <h1 className="text-lg font-bold">轻周期验证详情</h1>
        <Badge variant="outline" className="text-xs">{symbol}</Badge>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16 gap-2 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" /> 加载验证结果...
        </div>
      ) : !group ? (
        <div className="text-sm text-muted-foreground text-center py-16">
          未找到该重周期的轻周期数据
        </div>
      ) : (
        <div className="space-y-4">
          {/* 重周期参考 */}
          <Card className="border-primary/20">
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CycleHeader cycle={heavy!} />
                <Badge variant="outline" className="text-[10px] ml-2 shrink-0">重周期</Badge>
              </div>
            </CardHeader>
            <CardContent>
              <div className="grid gap-2 md:grid-cols-3">
                {heavy!.items.map(item => <HorizonCard key={item.id} item={item} />)}
              </div>
            </CardContent>
          </Card>

          {/* 轻周期列表 */}
          {lightCycles.length === 0 ? (
            <div className="text-sm text-muted-foreground text-center py-12">该重周期下暂无轻周期</div>
          ) : (
            <div className="space-y-3">
              <div className="text-sm font-medium text-muted-foreground">挂载的轻周期 ({lightCycles.length})</div>
              {lightCycles.map(lc => (
                <Card key={lc.cycleId}>
                  <CardHeader className="pb-2">
                    <CycleHeader cycle={lc} compact />
                  </CardHeader>
                  <CardContent>
                    <div className="grid gap-2 md:grid-cols-3">
                      {lc.items.map(item => (
                        <HorizonCard
                          key={item.id}
                          item={item}
                          adjustments={group?.adjustments?.filter(a => a.lightCycleId === lc.cycleId && a.lightHorizon === item.horizon)}
                        />
                      ))}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
