import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Timer } from 'lucide-react';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../../lib/utils';
import type { WakeCall, WakeStage, WakeToolCall, WakeToolResult, WakeTrace } from '../../types';
import { Markdown } from '../Markdown';
import { TRADE_TOOL_SET, dataArgsSummary, toolName, tradeArgsSummary } from './traderTools';

/** 提示词 / 回执预览的等宽块 */
const PRE = 'font-mono text-[13px] leading-[1.5] whitespace-pre-wrap break-words';
const PRE_BOX = 'border border-border bg-card-2 px-3 py-2 max-h-72 overflow-y-auto';

/** 打字光标：贴在最后一段末尾跟着字走，所以挂在末段的 ::after 上而不是另起一个元素 */
const CURSOR = '[&>*:last-child]:after:content-[""] [&>*:last-child]:after:inline-block '
  + '[&>*:last-child]:after:w-2 [&>*:last-child]:after:h-4 [&>*:last-child]:after:align-[-2px] '
  + '[&>*:last-child]:after:ml-[3px] [&>*:last-child]:after:bg-primary '
  + '[&>*:last-child]:after:animate-[pt-pulse_1s_steps(2)_infinite]';

/** 数据工具的参数 k=v 一行；对象值压成 JSON */
function kvArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' ');
}

/**
 * 一节思考的工具调用：每个调用一枚 chip（成功素灰、被拒/出错红框、还没回执的标"处理中"；带理由的参数很长，在 chip 里折行），
 * 点 chip 展开回执预览（先 3 行，再点展全，再点收起）。预览是块，只能落在 chip 行下面。
 */
function CallTools({ call, running }: { call: WakeCall; running: boolean }) {
  const { t } = useTranslation('ai');
  // 工具调用 id → 展开档：0 收起 1 三行 2 全文
  const [level, setLevel] = useState<Record<string, number>>({});
  const previewOf = (tc: WakeToolCall): WakeToolResult | undefined => call.results.find(r => r.id === tc.id);
  return (
    <>
      <div className="flex flex-wrap gap-1.5 mt-3.5">
        {call.toolCalls.map(tc => {
          const result = previewOf(tc);
          const args = typeof tc.args === 'string' ? tc.args
            : TRADE_TOOL_SET.has(tc.name) ? tradeArgsSummary({ tool: tc.name, args: tc.args })
            : dataArgsSummary(tc.name, tc.args) ?? kvArgs(tc.args);
          const failed = result != null && result.status !== 'ok';
          return (
            <button key={tc.id} type="button"
                    onClick={() => result?.preview && setLevel(m => ({ ...m, [tc.id]: ((m[tc.id] ?? 0) + 1) % 3 }))}
                    className={cn('chip whitespace-normal text-left wrap-anywhere',
                      failed ? 'dn' : result ? 'border-border text-muted-foreground' : '')}>
              {toolName(tc.name)} {args}
              {result?.status === 'rejected' ? t('trade.rejected')
                : result?.status === 'error' ? t('trade.errored')
                : !result && running ? ` · ${t('live.processing')}` : ''}
            </button>
          );
        })}
      </div>
      {call.toolCalls.filter(tc => level[tc.id]).map(tc => (
        <pre key={tc.id} className={cn(PRE, 'mute mt-1.5', level[tc.id] === 1 && 'line-clamp-3')}>
          {previewOf(tc)?.preview}
        </pre>
      ))}
    </>
  );
}

/** 一段的分界：第 n 段 + 它的开场白折叠（提示词一样只有主人拿得到） */
function StageDivider({ stage }: { stage: WakeStage }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-4 pt-3 border-t border-dashed border-border">
      <div className="flex items-center gap-2.5 text-[13px] font-semibold">
        <span>{t('live.stageN', { n: stage.n })}</span>
        <button type="button" className="btn xs" onClick={() => setOpen(o => !o)}>{t('live.instruction')}</button>
      </div>
      {open && <pre className={cn(PRE, PRE_BOX, 'mt-2')}>{stage.instruction}</pre>}
    </div>
  );
}

/**
 * 一次唤醒的过程视图：信息行 → 提示词折叠（有才显示，只有主人拿得到）→ 按 call 分节的过程轨（分过段的在那次调用前插一道分界）→ 收尾行。
 * 只吃 WakeTrace，不管数据来自实时流还是 trace 接口；running=true 时末节文末点着光标、没回执的工具标处理中
 */
export function WakeTraceView({ trace, running = false }: { trace: WakeTrace; running?: boolean }) {
  const { t } = useTranslation('ai');
  const [promptOpen, setPromptOpen] = useState(false);
  const last = trace.calls.length;
  const stages = trace.stages ?? [];
  return (
    <div>
      <div className="flex items-center gap-3 flex-wrap text-[13px] mute">
        <span className="num">{fmtDateTime(trace.startedAt, true)}</span>
        <span className="num inline-flex items-center gap-1"><Timer className="w-3.5 h-3.5" />{trace.budgetSeconds}s</span>
        <span>{t('term.equity')} <b className="num font-semibold text-foreground">{fmtNum(trace.equity)}</b></span>
      </div>

      {trace.prompt && (
        <div className="mt-2.5">
          <button type="button" className="btn xs" onClick={() => setPromptOpen(o => !o)}>{t('live.prompt')}</button>
          {promptOpen && (
            <div className="mt-2 space-y-2">
              <div>
                <div className="microlabel mb-1">{t('live.systemPrompt')}</div>
                <pre className={cn(PRE, PRE_BOX)}>{trace.prompt.system}</pre>
              </div>
              <div>
                <div className="microlabel mb-1">{t('live.instruction')}</div>
                <pre className={cn(PRE, PRE_BOX)}>{trace.prompt.instruction}</pre>
              </div>
            </div>
          )}
        </div>
      )}

      {trace.calls.map(c => {
        // 进行中＝还在跑且是最后一节：模型在出字，或工具在跑等回执
        const hot = running && c.n === last;
        return (
          <div key={c.n} className="mt-3.5">
            {stages.filter(s => s.call === c.n).map(s => <StageDivider key={s.n} stage={s} />)}
            <div className="text-[13px] font-semibold mute">{t('live.callN', { n: c.n })}</div>
            {c.text && (
              <div className={cn('text-[15px] leading-[1.7] max-w-[80ch]', hot && CURSOR)}>
                <Markdown content={c.text} />
              </div>
            )}
            {c.toolCalls.length > 0 && <CallTools call={c} running={hot} />}
          </div>
        );
      })}

      {trace.end && (
        <div className="flex gap-3 flex-wrap text-[13px] mute border-t border-border pt-2 mt-3">
          <span className={cn('font-bold', trace.end.status === 'OK' ? 'text-foreground' : 'text-loss')}>
            {trace.end.status === 'OK' ? t('live.endedOk') : t('live.endedError')}
          </span>
          <span className="num">{(trace.end.latencyMs / 1000).toFixed(1)}s</span>
          <span>{t('detail.modelCalls', { count: trace.end.modelCalls })}</span>
          {/* token null＝上游没回 usage，显示「—」不显示 0 */}
          <span className="num">{trace.end.totalTokens == null ? '—' : fmtTokens(trace.end.totalTokens)} tok</span>
          <span>{t('term.equity')} <b className="num font-semibold text-foreground">{fmtNum(trace.end.equity)}</b></span>
          {trace.end.error && <p className="w-full text-loss">{trace.end.error}</p>}
        </div>
      )}
    </div>
  );
}
