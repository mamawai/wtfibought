import { useMemo, useState } from 'react';
import { ChevronDown, Clock, Scale, ScrollText, ShieldOff } from 'lucide-react';
import { cn, fmtDateTime } from '../../lib/utils';
import type { QuantDeepAnalysisView } from '../../types';

const TRIGGER_CN: Record<string, string> = { schedule: '定频', sentinel: '哨兵插队', chat: '对话触发', manual: '手动' };

/** 三情景概率条：凹槽内三段"液柱"（bull 绿/range 灰/bear 红），段间留缝，宽度即概率。 */
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
      <div className="flex h-3.5 rounded-full border border-border bg-card-2 overflow-hidden p-[3px] gap-[2px]">
        <div className="rounded-full bg-gain/85" style={{ width: `${s.bull}%` }} />
        <div className="rounded-full bg-muted-foreground/35" style={{ width: `${s.range}%` }} />
        <div className="rounded-full bg-loss/85" style={{ width: `${s.bear}%` }} />
      </div>
      <div className="flex justify-between text-[10px] font-bold text-muted-foreground tabular-nums">
        <span className="flex items-center gap-1 text-gain"><span className="w-1.5 h-1.5 rounded-full bg-gain" />看涨 {s.bull}%</span>
        <span className="flex items-center gap-1"><span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/50" />震荡 {s.range}%</span>
        <span className="flex items-center gap-1 text-loss"><span className="w-1.5 h-1.5 rounded-full bg-loss" />看跌 {s.bear}%</span>
      </div>
    </div>
  );
}

function ArgumentFold({ title, content, tone }: { title: string; content: string; tone: 'bull' | 'bear' | 'judge' }) {
  const [open, setOpen] = useState(false);
  if (!content) return null;
  const color = tone === 'bull' ? 'text-gain' : tone === 'bear' ? 'text-loss' : 'text-primary';
  const border = tone === 'bull' ? 'border-gain/50' : tone === 'bear' ? 'border-loss/50' : 'border-primary/50';
  return (
    <div className="rounded-md border border-border bg-card overflow-hidden">
      <button
        onClick={() => setOpen(v => !v)}
        className={cn('w-full flex items-center justify-between px-3 py-2 text-xs font-bold', color)}
      >
        {title}
        <ChevronDown className={cn('w-3.5 h-3.5 transition-transform duration-200', open && 'rotate-180')} />
      </button>
      {open && (
        <p className={cn('mx-3 mb-3 pl-2.5 border-l-2 text-xs text-muted-foreground leading-relaxed whitespace-pre-wrap', border)}>
          {content}
        </p>
      )}
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
      <div className="rounded-lg border border-border bg-card-2 flex flex-col items-center justify-center text-center gap-2.5 px-4 py-8">
        <div className="w-11 h-11 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
          <Scale className="w-5 h-5" />
        </div>
        <div className="text-xs font-bold text-muted-foreground">暂无深研判</div>
        <div className="text-[10px] text-muted-foreground/70 -mt-1">1 小时定频或波动哨兵触发后生成</div>
      </div>
    );
  }
  return (
    <div className="rounded-lg pt-card p-4 space-y-3">
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

      {/* 叙事主体：内凹"读稿区"，与操作性内容分层 */}
      <div className="rounded-md border border-border bg-card-2 px-3.5 py-3 flex gap-2.5">
        <ScrollText className="w-4 h-4 text-muted-foreground/60 shrink-0 mt-0.5" />
        <p className="text-sm leading-relaxed whitespace-pre-wrap min-w-0">{analysis.narrative}</p>
      </div>

      <ScenarioBar scenariosJson={analysis.scenariosJson} />

      {analysis.invalidation && (
        <div className="rounded-r-lg rounded-l-sm bg-warning/10 border-l-2 border-warning px-3 py-2">
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
