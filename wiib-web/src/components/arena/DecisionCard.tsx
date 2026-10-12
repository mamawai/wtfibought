import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, NotebookPen } from 'lucide-react';
import { traderApi } from '../../api';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../../lib/utils';
import type { AiTraderDecisionView, WakeTrace } from '../../types';
import { Markdown } from '../Markdown';
import { ReasoningFold } from './ReasoningFold';
import { WakeTraceView } from './WakeTraceView';
import { TRADE_TOOL_SET, toolName, tradeArgsSummary, type ActionRow } from './traderTools';

/** 状态徽章存的是词表 key：模块级常量只算一次，存翻好的字面量切了语言也不会变 */
const STATUS_CHIP: Record<string, { labelKey: string; cls: string }> = {
  OK: { labelKey: 'decision.ok', cls: '' },
  ERROR: { labelKey: 'decision.error', cls: 'dn' },
  SKIPPED: { labelKey: 'decision.skipped', cls: 'mute' },
};

/** 复盘紫：复盘行混在时间线上要能一眼分清 */
const REVIEW_INK = '#7c5cff';

/** "看了什么"一行最多点名几个工具，其余折成 +N */
const LOOKED_MAX = 4;

/**
 * 单条决策卡：徽章/时间/权益/遥测 → "看了什么"一句 → 交易动作行（参数/拒因）→ 推理折叠 + 过程。
 * 复盘不交易，左边一道色线，正文直接铺 markdown。
 * highlight=从已了结交易跳过来的那一条，描个边让人找得到。
 * mine=看的是自己的 trader，只有这时才出"过程"入口——轨迹接口对别人一律回 null。
 */
export function DecisionCard({ d, mine, highlight }: { d: AiTraderDecisionView; mine?: boolean; highlight?: boolean }) {
  const { t } = useTranslation('ai');
  // 过程轨迹：首次点开才拉，之后开合不重拉；拉失败复位标记，再点一次重试
  const [traceOpen, setTraceOpen] = useState(false);
  const [trace, setTrace] = useState<WakeTrace | null>(null);
  const traceRequested = useRef(false);
  const toggleTrace = () => {
    setTraceOpen(o => !o);
    if (traceRequested.current) return;
    traceRequested.current = true;
    void traderApi.decisionTrace(d.traderId, d.id).then(setTrace, () => { traceRequested.current = false; });
  };
  const isReview = d.kind === 'REVIEW';
  const status = STATUS_CHIP[d.status] ?? STATUS_CHIP.OK;
  const actions = useMemo<ActionRow[]>(() => {
    try {
      return d.actionsJson ? JSON.parse(d.actionsJson) as ActionRow[] : [];
    } catch {
      return [];
    }
  }, [d.actionsJson]);
  const dataCalls = actions.filter(a => !TRADE_TOOL_SET.has(a.tool));
  const trades = actions.filter(a => TRADE_TOOL_SET.has(a.tool));
  // 同一工具多周期连查几次只点名一次；出错的排前面露出来
  const seen = new Map<string, boolean>();
  for (const a of dataCalls) seen.set(a.tool, (seen.get(a.tool) ?? false) || a.status === 'error');
  const looked = [...seen.entries()].sort((a, b) => Number(b[1]) - Number(a[1]));
  const extra = looked.length - LOOKED_MAX;

  const meta = [
    // token 为 null＝上游端点没报 usage，显示「—」而不是 0：0 会被读成"这轮没花钱"
    d.modelCalls != null && `${d.totalTokens == null ? '—' : fmtTokens(d.totalTokens)} tok`,
    d.latencyMs != null && `${(d.latencyMs / 1000).toFixed(1)}s`,
    // 复盘无工具（单次调用）不显示"0次工具"占位
    !isReview && t('detail.toolCalls', { count: d.toolCalls }),
    d.modelCalls != null && t('detail.modelCalls', { count: d.modelCalls }),
  ].filter(Boolean).join(' · ');

  return (
    <div id={`decision-${d.id}`}
         className={cn('py-5 border-b border-border',
           isReview && 'border-l-[3px] pl-[18px]',
           highlight && 'outline outline-2 outline-primary -outline-offset-2')}
         style={isReview ? { borderLeftColor: REVIEW_INK } : undefined}>
      <div className="flex items-center gap-2.5 flex-wrap">
        {isReview ? (
          d.status === 'OK' ? (
            <span className="chip" style={{ borderColor: REVIEW_INK, color: REVIEW_INK }}>
              <NotebookPen className="w-3 h-3" />{t('term.dailyReview')}
            </span>
          // 观望门控跳过的复盘行（SKIPPED）不是失败：灰徽章，error 字段带着跳过缘由
          ) : d.status === 'SKIPPED' ? <span className="chip mute">{t('decision.skipped')}</span>
            : <span className="chip dn">{t('decision.reviewFailed')}</span>
        ) : (
          <>
            <span className={cn('chip', status.cls)}>{t(status.labelKey)}</span>
            {/* 警报＝哨兵在极端波动时叫醒的，手动＝扳机在主人手里（对话轨 wake_trader），都不是例行K线节奏 */}
            {d.kind === 'ALERT' && <span className="chip wn">{t('decision.alert')}</span>}
            {d.kind === 'MANUAL' && <span className="chip mute">{t('term.manualWake')}</span>}
          </>
        )}
        <span className="num text-[13px] mute">{fmtDateTime(d.wakeTime)}</span>
        {d.equity != null && (
          <span className="text-[13px] mute">
            {t('term.equity')} <b className="num font-semibold text-foreground">{fmtNum(d.equity)}</b>
          </span>
        )}
        {meta && <span className="ml-auto num text-[12px] mute">{meta}</span>}
      </div>

      {/* 数据查询收成一句：交代"它看了什么"再决策；出错的工具标红 */}
      {looked.length > 0 && (
        <div className="text-[13px] mute mt-2.5">
          {t('detail.lookedAt')}{' '}
          {looked.slice(0, LOOKED_MAX).map(([tool, failed], i) => (
            <span key={tool}>
              {i > 0 && ' · '}
              <b className={cn('font-semibold', failed ? 'text-loss' : 'text-foreground')}
                 title={failed ? dataCalls.find(a => a.tool === tool && a.status === 'error')?.error : undefined}>
                {toolName(tool)}
              </b>
            </span>
          ))}
          {extra > 0 && <span> +{extra}</span>}
        </div>
      )}

      {/* 交易动作：独立成行带参数与拒因——"它做了什么"是时间线的主角 */}
      {trades.length > 0 && (
        <div className="mt-1.5">
          {trades.map((a, i) => {
            const failed = a.rejected || a.status === 'error';
            // unknown=重发确认后仍没问到结果，可能成交也可能没有。不单独标出来就跟成交长得一模一样，
            // 而这条时间线是对所有人公开的账面事实
            const unknown = a.status === 'unknown';
            return (
              <div key={i} className="flex gap-2.5 items-baseline py-0.5 text-[13.5px] border-t border-dashed border-border first:border-t-0">
                <b className={cn('font-extrabold whitespace-nowrap', failed ? 'text-loss' : unknown && 'text-warning')}>
                  {toolName(a.tool)}
                  {a.rejected ? t('trade.rejected') : a.status === 'error' ? t('trade.errored') : unknown ? t('trade.unknown') : ''}
                </b>
                <span className="mute">
                  {tradeArgsSummary(a)}
                  {a.rejected && <span className="block text-loss mt-0.5">{a.rejected}</span>}
                  {a.error && <span className="block text-loss mt-0.5">{a.error}</span>}
                </span>
              </div>
            );
          })}
        </div>
      )}

      {d.status === 'ERROR' && d.error && <p className="text-[13.5px] text-loss mt-2.5">{d.error}</p>}
      {/* SKIPPED 的缘由（调度跳过/观望门控跳过复盘）：灰字注明，不是错误 */}
      {d.status === 'SKIPPED' && d.error && <p className="text-[13.5px] mute mt-2.5">{d.error}</p>}

      {isReview
        ? d.reasoning && <div className="text-[15px] leading-[1.7] mt-3 max-w-[80ch]"><Markdown content={d.reasoning} /></div>
        : (
          <ReasoningFold reasoning={d.reasoning}>
            {/* 这轮落了轨迹（hasTrace）才有过程可看 */}
            {mine && d.hasTrace && (
              <button type="button" onClick={toggleTrace}
                      className={cn('underline underline-offset-[3px]', traceOpen && 'font-extrabold')}>
                {t('live.process')}
              </button>
            )}
          </ReasoningFold>
        )}
      {traceOpen && (
        <div className="border-t border-dashed border-border mt-3 pt-3">
          {trace ? <WakeTraceView trace={trace} /> : <Loader2 className="w-3.5 h-3.5 animate-spin mute" />}
        </div>
      )}
    </div>
  );
}
