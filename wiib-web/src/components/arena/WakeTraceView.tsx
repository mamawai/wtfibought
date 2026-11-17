import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight, Timer } from 'lucide-react';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../../lib/utils';
import type { WakeStage, WakeToolCall, WakeToolResult, WakeTrace } from '../../types';
import { StreamText } from '../StreamText';
import { TRADE_TOOL_SET, dataArgsSummary, toolName, tradeArgsSummary } from './traderTools';

/** 数据工具的参数 k=v 一行；对象值压成 JSON */
function kvArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' ');
}

/**
 * 一次工具调用一行：工具名（结果不好就跟着标被拒 / 出错…）、参数、还没回执的标「处理中」；
 * 有回执预览的能点开看全文。
 */
function ToolRow({ tc, result, running }: { tc: WakeToolCall; result?: WakeToolResult; running: boolean }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  const args = typeof tc.args === 'string' ? tc.args
    : TRADE_TOOL_SET.has(tc.name) ? tradeArgsSummary({ tool: tc.name, args: tc.args })
    : dataArgsSummary(tc.name, tc.args) ?? kvArgs(tc.args);
  // not_attempted：调用次数用完被拦下，没执行，不算出错
  const skipped = result?.status === 'not_attempted';
  const failed = result != null && result.status !== 'ok' && !skipped;
  const pending = !result && running;
  return (
    <>
      <button type="button" className={cn('tr-tool', failed && 'bad', pending && 'wait')} aria-expanded={open}
              disabled={!result?.preview} onClick={() => setOpen(o => !o)}>
        <b>
          {toolName(tc.name)}
          {result?.status === 'rejected' ? t('trade.rejected')
            : result?.status === 'error' ? t('trade.errored')
            : result?.status === 'unknown' ? t('trade.unknown')
            : result?.status === 'partial' ? t('trade.partial')
            : result?.status === 'unavailable' ? t('trade.unavailable')
            : skipped ? t('trade.notAttempted') : ''}
        </b>
        {/* 参数一行放不下就截断，指上去看全的 */}
        <span title={args}>{args}</span>
        {pending && <em>{t('live.processing')}</em>}
        <ChevronRight className="ic" />
      </button>
      {open && <pre className="tr-pre">{result?.preview}</pre>}
    </>
  );
}

/** 一段的分界：第 n 段 + 它的开场白折叠（提示词一样只有主人拿得到） */
function StageDivider({ stage }: { stage: WakeStage }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  return (
    <>
      <div className="tr-stage">
        <span>{t('live.stageN', { n: stage.n })}</span>
        <button type="button" className="btn sm" aria-expanded={open} onClick={() => setOpen(o => !o)}>{t('live.instruction')}</button>
      </div>
      {open && <pre className="tr-pre">{stage.instruction}</pre>}
    </>
  );
}

/**
 * 一次唤醒的过程（样式见 ArenaDetail.css .tr-*）：信息行（带提示词开关，有才显示，只有主人拿得到）→ 一次思考一节，
 * 分过段的在那次调用前插一道分界 → 收尾行。
 * 只吃 WakeTrace，不管数据来自实时流还是 trace 接口；running=true 时末节的字是浮出来的（卡住了文末有呼吸点）、没回执的工具标处理中
 */
export function WakeTraceView({ trace, running = false }: { trace: WakeTrace; running?: boolean }) {
  const { t } = useTranslation('ai');
  const [promptOpen, setPromptOpen] = useState(false);
  const last = trace.calls.length;
  const stages = trace.stages ?? [];
  return (
    <>
      <div className="tr-h">
        <span>{fmtDateTime(trace.startedAt, true)}</span>
        <span className="inline-flex items-center gap-1"><Timer className="ic" />{trace.budgetSeconds}s</span>
        <span>{t('term.equity')} <b>{fmtNum(trace.equity)}</b></span>
        <span>{t('wb.hold.positionsN', { count: trace.positions })} · {t('wb.hold.ordersN', { count: trace.pendingOrders })}</span>
        {trace.prompt && (
          <button type="button" className="btn sm" aria-expanded={promptOpen} onClick={() => setPromptOpen(o => !o)}>{t('live.prompt')}</button>
        )}
      </div>
      {promptOpen && trace.prompt && (
        <>
          <pre className="tr-pre"><small>{t('live.systemPrompt')}</small>{trace.prompt.system}</pre>
          <pre className="tr-pre"><small>{t('live.instruction')}</small>{trace.prompt.instruction}</pre>
        </>
      )}

      {trace.calls.map(c => {
        // 进行中＝还在跑且是最后一节：模型在出字，或工具在跑等回执
        const hot = running && c.n === last;
        return (
          <div key={c.n} className="tr-call">
            {stages.filter(s => s.call === c.n).map(s => <StageDivider key={s.n} stage={s} />)}
            <small>{t('live.callN', { n: c.n })}</small>
            {c.text && <StreamText className="tx" content={c.text} streaming={hot} />}
            {c.toolCalls.map(tc => <ToolRow key={tc.id} tc={tc} result={c.results.find(r => r.id === tc.id)} running={hot} />)}
          </div>
        );
      })}

      {trace.end && (
        <div className="tr-end">
          <b className={cn(trace.end.status !== 'OK' && 'dn')}>{trace.end.status === 'OK' ? t('live.endedOk') : t('live.endedError')}</b>
          <span>{t('detail.modelCalls', { count: trace.end.modelCalls })}</span>
          {/* token null＝上游没回 usage，显示「—」不显示 0 */}
          <span>{trace.end.totalTokens == null ? '—' : fmtTokens(trace.end.totalTokens)} tok</span>
          <span>{(trace.end.latencyMs / 1000).toFixed(1)}s</span>
          <span>{t('term.equity')} <b>{fmtNum(trace.end.equity)}</b></span>
          {trace.end.error && <p className="dn">{trace.end.error}</p>}
        </div>
      )}
    </>
  );
}
