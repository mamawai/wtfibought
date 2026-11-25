import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Timer } from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../../lib/utils';
import type { WakeStage, WakeToolCall, WakeToolResult, WakeTrace } from '../../types';
import { StreamText } from '../StreamText';
import { TRADE_TOOL_SET, actionNote, dataArgsSummary, toolName, tradeArgsSummary } from './traderTools';

/** 数据工具的参数 k=v 一行；对象值压成 JSON */
function kvArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' ');
}

/**
 * 一次工具调用一小块：工具名（结果不好就跟着标被拒 / 出错…）、参数、还没回执的标「处理中」。
 * 有回执的能点：回执弹在这块上方，点外面或按 Esc 收起。这块自己点开前后一个样，弹窗靠一根细脖子连在它顶上偏左的地方。
 * 脖子、弹窗的底、这块的底画在同一层（.tr-goo），整层过一遍「先糊开、再把边缘收紧」的滤镜，脖子两头就像水滴那样是弧线；
 * 弹出时脖子先顶上去、冒出一个小球，再撑开成弹窗；收起时倒着来（动画在 ArenaDetail.css）。
 */
function ToolChip({ tc, result, running }: { tc: WakeToolCall; result?: WakeToolResult; running: boolean }) {
  const { t } = useTranslation('ai');
  // 收起分两步：先标 closing 放收回去的动画，动画放完才真的卸掉弹窗
  const [phase, setPhase] = useState<'closed' | 'open' | 'closing'>('closed');
  const ref = useRef<HTMLDivElement>(null);
  const popRef = useRef<HTMLPreElement>(null);
  const gooId = useId();
  const close = useCallback(() => setPhase('closing'), []);
  useClickOutside(ref, close, phase === 'open');

  // 摆弹窗：宽度跟着内容走（最宽到这一排的宽度），左边跟这块对齐，右边放不下就往左挪。
  // 量出来的 --x / --r 是弹窗比这块往左、往右各多伸出多少，--h 是弹窗多高，样式拿去定位和画底下那两层。
  // 每次渲染都重量：实时流里前面的块会变宽变窄，这块跟着挪
  useLayoutEffect(() => {
    if (phase === 'closed') return;
    const place = () => {
      const el = ref.current!;
      const pop = popRef.current!;
      const rowWidth = (el.offsetParent as HTMLElement).clientWidth;
      el.style.setProperty('--w', `${rowWidth}px`);
      const left = Math.max(0, Math.min(el.offsetLeft, rowWidth - pop.offsetWidth));
      el.style.setProperty('--x', `${el.offsetLeft - left}px`);
      el.style.setProperty('--r', `${left + pop.offsetWidth - el.offsetLeft - el.offsetWidth}px`);
      el.style.setProperty('--h', `${pop.offsetHeight}px`);
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  });
  // 开着的时候：弹窗顶到屏幕外面就把页面往回滚一点；Esc 挂在整页上听，焦点不在这块里也收得起来
  useEffect(() => {
    if (phase !== 'open') return;
    popRef.current!.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [phase, close]);

  const args = typeof tc.args === 'string' ? tc.args
    : TRADE_TOOL_SET.has(tc.name) ? tradeArgsSummary({ tool: tc.name, args: tc.args })
    : dataArgsSummary(tc.name, tc.args) ?? kvArgs(tc.args);
  // 交易动作的理由不在一行里，指上去连理由一起看
  const note = typeof tc.args === 'string' ? undefined : actionNote({ tool: tc.name, args: tc.args });
  // not_attempted：调用次数用完被拦下，没执行，不算出错
  const skipped = result?.status === 'not_attempted';
  const failed = result != null && result.status !== 'ok' && !skipped;
  const pending = !result && running;
  return (
    <div ref={ref}
         className={cn('tr-tool', failed && 'bad', pending && 'wait', phase !== 'closed' && 'open', phase === 'closing' && 'closing')}>
      <button type="button" aria-expanded={phase === 'open'} disabled={!result?.preview}
              onClick={() => setPhase(p => p === 'open' ? 'closing' : 'open')}>
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
        {args && <span title={note ? `${args}\n${note}` : args}>{args}</span>}
        {pending && <em>{t('live.processing')}</em>}
      </button>
      {phase !== 'closed' && (
        <>
          <svg className="tr-goo-def" aria-hidden="true">
            <filter id={gooId} colorInterpolationFilters="sRGB">
              <feGaussianBlur stdDeviation="4" />
              {/* 只动透明度：半透明的边缘要么拉成实的、要么砍掉 */}
              <feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 24 -12" />
            </filter>
          </svg>
          <span className="tr-sh" aria-hidden="true"><i className="pane" /></span>
          <span className="tr-goo" aria-hidden="true" style={{ filter: `url(#${gooId})` }}>
            <i className="pane" onAnimationEnd={() => { if (phase === 'closing') setPhase('closed'); }} />
            <i className="neck" />
            <i className="chip" />
          </span>
          <pre ref={popRef} className="tr-pop">{result?.preview}</pre>
        </>
      )}
    </div>
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
 * 一次唤醒的过程（样式见 ArenaDetail.css .tr-*）：信息行（带提示词开关，有才显示，只有主人拿得到）→ 一次模型调用一节，
 * 分过段的在那次调用前插一道分界 → 收尾行。
 * 只吃 WakeTrace，不管数据来自实时流还是 trace 接口；running=true 时末节的字是浮出来的（卡住了文末有呼吸点）、没回执的工具标处理中
 */
export function WakeTraceView({ trace, running = false }: { trace: WakeTrace; running?: boolean }) {
  const { t } = useTranslation('ai');
  const [promptOpen, setPromptOpen] = useState(false);
  const last = trace.calls.length;
  const stages = trace.stages ?? [];
  // 两种复盘不是唤醒，没有权益和持仓这两格
  const wake = trace.kind !== 'DEBRIEF' && trace.kind !== 'REVIEW';
  return (
    <>
      <div className="tr-h">
        <span>{fmtDateTime(trace.startedAt, true)}</span>
        <span className="inline-flex items-center gap-1"><Timer className="ic" />{trace.budgetSeconds}s</span>
        {wake && (
          <>
            <span>{t('term.equity')} <b>{fmtNum(trace.equity)}</b></span>
            <span>{t('wb.hold.positionsN', { count: trace.positions })} · {t('wb.hold.ordersN', { count: trace.pendingOrders })}</span>
          </>
        )}
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
            {c.toolCalls.length > 0 && (
              <div className="tr-tools">
                {c.toolCalls.map(tc => <ToolChip key={tc.id} tc={tc} result={c.results.find(r => r.id === tc.id)} running={hot} />)}
              </div>
            )}
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
          {wake && <span>{t('term.equity')} <b>{fmtNum(trace.end.equity)}</b></span>}
          {trace.end.error && <p className="dn">{trace.end.error}</p>}
        </div>
      )}
    </>
  );
}
