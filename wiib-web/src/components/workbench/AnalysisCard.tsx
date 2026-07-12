import { useMemo, useState } from 'react';
import { ChevronDown, ChevronUp, Scale, ShieldOff, Clock } from 'lucide-react';
import { cn, fmtDateTime } from '../../lib/utils';
import type { QuantDeepAnalysisView } from '../../types';

const TRIGGER_CN: Record<string, string> = { schedule: '定频', sentinel: '哨兵插队', chat: '对话触发', manual: '手动' };

/** 三情景概率条：bull 绿 / range 灰 / bear 红，宽度即概率。 */
function ScenarioBar({ scenariosJson }: { scenariosJson: string }) {
  const s = useMemo(() => {
    try {
      const p = JSON.parse(scenariosJson) as { bullPct?: number; rangePct?: number; bearPct?: number };
      return { bull: p.bullPct ?? 33, range: p.rangePct ?? 34, bear: p.bearPct ?? 33 };
    } catch {
      return null;
    }
  }, [scenariosJson]);
  if (!s) return null;
  return (
    <div className="space-y-1.5">
      <div className="flex h-2.5 rounded-full overflow-hidden neu-inset">
        <div className="bg-gain/80" style={{ width: `${s.bull}%` }} />
        <div className="bg-muted-foreground/30" style={{ width: `${s.range}%` }} />
        <div className="bg-loss/80" style={{ width: `${s.bear}%` }} />
      </div>
      <div className="flex justify-between text-[10px] font-bold text-muted-foreground">
        <span className="text-gain">看涨 {s.bull}%</span>
        <span>震荡 {s.range}%</span>
        <span className="text-loss">看跌 {s.bear}%</span>
      </div>
    </div>
  );
}

function ArgumentFold({ title, content, tone }: { title: string; content: string; tone: 'bull' | 'bear' | 'judge' }) {
  const [open, setOpen] = useState(false);
  if (!content) return null;
  const color = tone === 'bull' ? 'text-gain' : tone === 'bear' ? 'text-loss' : 'text-primary';
  return (
    <div className="rounded-lg neu-flat">
      <button
        onClick={() => setOpen(v => !v)}
        className={cn('w-full flex items-center justify-between px-3 py-2 text-xs font-bold', color)}
      >
        {title}
        {open ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
      </button>
      {open && <p className="px-3 pb-3 text-xs text-muted-foreground leading-relaxed whitespace-pre-wrap">{content}</p>}
    </div>
  );
}

/**
 * 深研判详情卡：叙事 + 情景分布 + 失效条件 + 无方向态 + Bull/Bear/Judge 论点折叠。
 * 刻意没有方向数字——方向以叙事和情景分布存在（research 证伪了方向预测）。
 */
export function AnalysisCard({ analysis }: { analysis: QuantDeepAnalysisView | null }) {
  if (!analysis) {
    return (
      <div className="rounded-xl neu-inset p-6 text-center text-sm text-muted-foreground">
        暂无深研判 · 1 小时定频或波动哨兵触发后生成
      </div>
    );
  }
  return (
    <div className="rounded-xl neu-raised-sm p-4 space-y-3">
      <div className="flex items-center gap-2 flex-wrap">
        <Scale className="w-4 h-4 text-primary" />
        <span className="text-sm font-black">深研判</span>
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary/10 text-primary">
          {TRIGGER_CN[analysis.triggerSource] || analysis.triggerSource}
        </span>
        {analysis.noDirection && (
          <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-muted text-muted-foreground flex items-center gap-1">
            <ShieldOff className="w-3 h-3" /> 无方向态
          </span>
        )}
        <span className="ml-auto text-[10px] text-muted-foreground flex items-center gap-1">
          <Clock className="w-3 h-3" />
          {fmtDateTime(analysis.closeTime)}
        </span>
      </div>

      <p className="text-sm leading-relaxed whitespace-pre-wrap">{analysis.narrative}</p>

      <ScenarioBar scenariosJson={analysis.scenariosJson} />

      {analysis.invalidation && (
        <div className="rounded-lg bg-warning/10 px-3 py-2">
          <span className="text-[10px] font-black text-warning">失效条件</span>
          <p className="text-xs text-muted-foreground leading-relaxed mt-0.5 whitespace-pre-wrap">{analysis.invalidation}</p>
        </div>
      )}

      <div className="space-y-1.5">
        <ArgumentFold title="Bull 论点" content={analysis.bullArgument} tone="bull" />
        <ArgumentFold title="Bear 论点" content={analysis.bearArgument} tone="bear" />
        <ArgumentFold title="Judge 裁决" content={analysis.judgeReasoning} tone="judge" />
      </div>
    </div>
  );
}
