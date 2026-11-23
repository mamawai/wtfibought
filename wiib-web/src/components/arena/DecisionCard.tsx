import { Fragment, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, NotebookPen } from 'lucide-react';
import { traderApi } from '../../api';
import { cn, fmtDateTime, fmtNum, fmtSignedUsd, fmtTokens } from '../../lib/utils';
import type { AiTraderDecisionView, TradeRecordView, WakeTrace } from '../../types';
import { Markdown } from '../Markdown';
import { ReasoningFold } from './ReasoningFold';
import { WakeTraceView } from './WakeTraceView';
import {
  actionNote, entryStatus, openEntries, parseActions, px, rungsText, sideText, TRADE_TOOL_SET, toolName, tradeArgsSummary,
  type ActionRow,
} from './traderTools';
import { KIND_TONE, useKindLabel } from './useKindLabel';

/** "看了什么"一行最多点名几个工具，其余折成 +N */
const LOOKED_MAX = 4;

/**
 * 时间线上的一条决策（样式见 ArenaDetail.css .dc）：左边一根细线串起来，圆点颜色跟着是什么叫醒它的。
 * 从上到下：标签/时间/权益/遥测 → "看了什么"一句 → 交易动作行（参数/拒因）→ 推理折叠 + 过程。
 * 复盘不交易，圆点是空心的，正文铺在一块浅灰里；单笔复盘在标签后面写明是哪一笔，它带工具，所以也有"看了什么"和过程。
 * highlight=从做完的交易跳过来的那一条，底色亮一下让人找得到。
 * mine=看的是自己的 trader，只有这时才出"过程"入口——轨迹接口对别人一律回 null。
 * trade=单笔复盘对应的那笔做完的交易，页面上有就传；没有就只写仓位编号
 */
export function DecisionCard({ d, mine, highlight, trade }: {
  d: AiTraderDecisionView; mine?: boolean; highlight?: boolean; trade?: TradeRecordView;
}) {
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
  const kindLabel = useKindLabel();
  const isDebrief = d.kind === 'DEBRIEF';
  // 两种复盘：空心圆点，正文直接铺开
  const isReview = d.kind === 'REVIEW' || isDebrief;
  const actions = useMemo(() => parseActions(d.actionsJson), [d.actionsJson]);
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
    d.cachedTokens != null && t('detail.cachedTokens', { n: fmtTokens(d.cachedTokens) }),
    d.latencyMs != null && `${(d.latencyMs / 1000).toFixed(1)}s`,
    // 统一复盘无工具（单次调用）不显示"0次工具"占位；预案那一行不是模型调的工具，也不显示
    d.kind !== 'REVIEW' && d.kind !== 'PRESET' && t('detail.toolCalls', { count: d.toolCalls }),
    d.modelCalls != null && t('detail.modelCalls', { count: d.modelCalls }),
  ].filter(Boolean).join(' · ');

  // 圆点：失败红，其余跟叫醒原因的标签同色；复盘是空心圈
  const dot = d.status === 'ERROR' ? 'dot-r' : KIND_TONE[d.kind] ? `dot-${KIND_TONE[d.kind]}` : '';

  return (
    <div id={`decision-${d.id}`} className={cn('dc', isReview ? 'rv' : dot, highlight && 'hl')}>
      <div className="dc-h">
        {isDebrief ? (
          <>
            {d.status === 'OK' ? <span className="tag ink"><NotebookPen className="ic" />{t('term.debrief')}</span>
              : <span className="tag r">{t('decision.debriefFailed')}</span>}
            {/* 是哪一笔：页面上有这笔交易就写币和方向，没有就写仓位编号 */}
            <span>
              {trade ? `${trade.symbol} ${t(trade.side === 'LONG' ? 'term.long' : 'term.short')}` : t('decision.position', { id: d.positionId })}
            </span>
          </>
        ) : isReview ? (
          d.status === 'OK' ? <span className="tag ink"><NotebookPen className="ic" />{t('term.dailyReview')}</span>
            // 观望门控跳过的复盘行（SKIPPED）不是失败：灰标签，error 字段带着跳过缘由
            : d.status === 'SKIPPED' ? <span className="tag">{t('decision.skipped')}</span>
            : <span className="tag r">{t('decision.reviewFailed')}</span>
        ) : d.status === 'SKIPPED' ? <span className="tag">{t('decision.skipped')}</span> : (
          <>
            {/* 是什么叫醒它的：例行心跳 / 盯盘触发 / 持仓有变化 / 剧烈波动 / 手动 */}
            <span className={cn('tag', KIND_TONE[d.kind])}>{kindLabel(d.kind, !!mine, d.intervalCode)}</span>
            {/* 只处理一个币的那种轮，标出是哪个币 */}
            {d.symbol && <span className="tag">{t('live.onlySymbol', { symbol: d.symbol })}</span>}
            {d.status === 'ERROR' && <span className="tag r">{t('decision.error')}</span>}
          </>
        )}
        <span>{fmtDateTime(d.wakeTime)}</span>
        {d.equity != null && <span>{t('term.equity')} <b>{fmtNum(d.equity)}</b></span>}
        {meta && <span className="m">{meta}</span>}
      </div>

      {/* 数据查询收成一句：交代"它看了什么"再决策；出错的工具标红 */}
      {looked.length > 0 && (
        <div className="look">
          {t('detail.lookedAt')}{' '}
          {looked.slice(0, LOOKED_MAX).map(([tool, failed], i) => (
            <span key={tool}>
              {i > 0 && ' · '}
              <b className={cn(failed && 'dn')} title={failed ? dataCalls.find(a => a.tool === tool && a.status === 'error')?.error : undefined}>
                {toolName(tool)}
              </b>
            </span>
          ))}
          {extra > 0 && ` +${extra}`}
        </div>
      )}

      {/* 交易动作：独立成行带参数与拒因——"它做了什么"是时间线的主角 */}
      {trades.length > 0 && (
        <div className="al">
          {trades.map((a, i) => {
            const failed = a.rejected || a.status === 'error';
            // unknown：成交结果未确认；partial：反手未完整执行
            const unsure = a.status === 'unknown' || a.status === 'partial';
            // not_attempted：模型要调但没执行（调用次数用完，或参数没对上）
            const skipped = a.status === 'not_attempted';
            return (
              <div key={i} className={cn('act', failed ? 'bad' : unsure ? 'unsure' : skipped && 'skip')}>
                <b>
                  {toolName(a.tool)}
                  {a.rejected ? t('trade.rejected') : a.status === 'error' ? t('trade.errored') : a.status === 'unknown' ? t('trade.unknown')
                    : a.status === 'partial' ? t('trade.partial') : skipped ? t('trade.notAttempted') : ''}
                </b>
                {/* 理由不铺在这一行，指上去看 */}
                <div className="ad" title={actionNote(a)}>
                  <ActionDetail a={a} />
                  {a.rejected && <RejectReason text={a.rejected} />}
                  {a.error && <small>{a.error}</small>}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {d.status === 'ERROR' && d.error && <p className="err">{d.error}</p>}
      {/* SKIPPED 的缘由（调度跳过/观望门控跳过复盘）：灰字注明，不是错误 */}
      {d.status === 'SKIPPED' && d.error && <p className="why">{d.error}</p>}

      {isReview
        ? (
          <>
            {d.reasoning && <div className="rvb"><Markdown content={d.reasoning} /></div>}
            {/* 单笔复盘带工具，落了轨迹的主人可以看它查了什么 */}
            {isDebrief && mine && d.hasTrace && (
              <button type="button" className="linkb" aria-expanded={traceOpen} onClick={toggleTrace}>{t('live.process')}</button>
            )}
          </>
        )
        : (
          <ReasoningFold reasoning={d.reasoning}>
            {/* 这轮落了轨迹（hasTrace）才有过程可看 */}
            {mine && d.hasTrace && (
              <button type="button" className="linkb" aria-expanded={traceOpen} onClick={toggleTrace}>{t('live.process')}</button>
            )}
          </ReasoningFold>
        )}
      {traceOpen && (
        <div className="trace">
          {trace ? <WakeTraceView trace={trace} /> : <Loader2 className="ic animate-spin mute" />}
        </div>
      )}
    </div>
  );
}

/**
 * 一条交易动作右边写什么：开仓是一块小面板；平仓写平了多少、成交价和这次的盈亏；
 * 反手是平掉旧仓那一句加新仓的面板；其余一句话（{@link tradeArgsSummary}）
 */
function ActionDetail({ a }: { a: ActionRow }) {
  const { t } = useTranslation('ai');
  if (a.tool === 'open_position') return <OpenPanel a={a} />;
  if (a.tool === 'close_position') return <ClosedLine a={a} />;
  if (a.tool === 'reverse_position' && a.result?.close) {
    return (
      <>
        {a.result.close.status === 'liquidated' ? <span>{t('args.reverseLiquidated')}</span> : <ClosedLine a={a.result.close} />}
        {a.result.open && <OpenPanel a={a.result.open} />}
      </>
    );
  }
  return <span>{tradeArgsSummary(a)}</span>;
}

/** 平仓：哪个币平了多少、成交价、这次平掉的盈亏；没成交的（被拒、出错、老记录）写仓位编号和数量 */
function ClosedLine({ a }: { a: ActionRow }) {
  const { t } = useTranslation('ai');
  const r = a.result;
  if (r?.filledPrice == null) return <span>{tradeArgsSummary(a)}</span>;
  return (
    <span className="cl">
      {[r.symbol, r.quantity != null && t('args.qty', { n: r.quantity }), `@ ${px(r.filledPrice)}`].filter(Boolean).join(' ')}
      {r.realizedPnl != null && <span className={cn('num', r.realizedPnl >= 0 ? 'up' : 'dn')}>{fmtSignedUsd(r.realizedPnl)}</span>}
    </span>
  );
}

/** 一档的状态配色：成交了实色，没准黄，被拒红，撤掉和没下更淡；挂着的照常灰 */
const ENTRY_TONE: Record<string, string> = {
  filled: 'ok', unknown: 'wn', rejected: 'dn', cancelled: 'off', not_attempted: 'off',
};

/** 开仓的小面板：币、方向、杠杆和玩法一行；每一档一行，限价还是市价、数量、成交没有；止损止盈一行 */
function OpenPanel({ a }: { a: ActionRow }) {
  const { t } = useTranslation('ai');
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  const side = g('side');
  const entries = openEntries(a);
  const tp = rungsText(a.args?.takeProfits);
  return (
    <div className="op">
      <div className="op-h">
        <b>{g('symbol')}</b>
        {side && <span className={cn('tag sm', side === 'LONG' ? 'g' : side === 'SHORT' && 'r')}>{sideText(side)}</span>}
        {g('leverage') && <span className="num">{g('leverage')}x</span>}
        {g('playType') && <span className="op-p">{g('playType')}</span>}
      </div>
      {entries.length > 0 && (
        <div className="op-e num">
          {entries.map((e, i) => (
            <Fragment key={i}>
              <span>{e.limitPrice != null ? t('args.limit', { p: px(e.limitPrice) }) : t('plan.marketEntry')}</span>
              <span>× {e.quantity}</span>
              <span className={e.status ? ENTRY_TONE[e.status.toLowerCase()] : undefined}>
                {e.status && entryStatus(e.status)}{e.filledPrice != null && ` @ ${px(e.filledPrice)}`}
              </span>
            </Fragment>
          ))}
        </div>
      )}
      {(g('stopLossPrice') || tp) && (
        <div className="op-f num">
          {g('stopLossPrice') && <span>{t('args.sl', { p: px(g('stopLossPrice')) })}</span>}
          {tp && <span>{t('args.tp', { p: tp })}</span>}
        </div>
      )}
    </div>
  );
}

/** 拒因：夹着账户快照（JSON）的只露前面那句原因，点开看全文；没夹数据的原样给 */
function RejectReason({ text }: { text: string }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  const cut = text.search(/[{[]/);
  if (cut < 0) return <small>{text}</small>;
  let head = text.slice(0, cut).trimEnd();
  const end = Math.max(head.lastIndexOf('。'), head.lastIndexOf('. '));
  // 引出数据的那半句（"你上次看到的："）不要，退回上一句末；截在句子中间的补个省略号
  if (/[:：]$/.test(head) && end >= 0) head = head.slice(0, end + 1);
  else head += '…';
  return (
    <small>
      {open ? text : head}{' '}
      <button type="button" className="linkb" aria-expanded={open} onClick={() => setOpen(o => !o)}>
        {open ? t('common:collapse') : t('detail.expandFull')}
      </button>
    </small>
  );
}
