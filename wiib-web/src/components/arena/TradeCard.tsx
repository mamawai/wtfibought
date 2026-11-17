import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Eye, EyeOff } from 'lucide-react';
import { formatCoinPrice, getCoin } from '../../lib/coinConfig';
import { cn, fmtDateTime, fmtDuration, fmtNum } from '../../lib/utils';
import type { TradeDecisionRef, TradeRecordView } from '../../types';
import { PlanEntries } from './PlanEntries';
import { planRevisions, planTargets } from './traderTools';

/**
 * 了结方式标签的颜色：止盈绿、止损/强平红、主动平仓灰（模型的手）。
 * 键是后端下发的语言无关码（ReviewMaterialAssembler.closeMannerKey），文案另查词表。
 */
const CLOSE_MANNER_TONE: Record<string, string> = {
  takeProfit: 'g', stopLoss: 'r', manual: '', liquidated: 'r',
};

/**
 * 一笔做完的交易（样式见 ArenaDetail.css .tc）：头行（币种·多空·了结方式·入场→出场·盈亏）→ 开/平时刻
 * → 计划（打法和依据、失效条件、入场/下单时止损/目标、逐档安排、修订史）→ 开仓/平仓决策的跳转钮。
 * 决策全文不在这里重复——时间线才是它的家，onJump 把人带过去。
 * onToggleStale 只有主人视角才传：忽略开关只治理自己 AI 的教材，公开记录不动。
 */
export function TradeCard({ r, onJump, onToggleStale }: {
  r: TradeRecordView;
  onJump: (d: TradeDecisionRef) => void;
  onToggleStale?: (r: TradeRecordView) => void;
}) {
  const { t } = useTranslation('ai');
  const isLong = r.side === 'LONG';
  const pnl = r.closedPnl;
  const plan = r.plan;
  const Icon = getCoin(r.symbol).icon;
  const px = (v: number) => formatCoinPrice(r.symbol, v);
  // stale 视觉与开关同门槛（仅主人）：忽略是主人对自家教材的私人治理，公开视角的列表与常人无异
  const stale = plan?.stale === true && onToggleStale != null;
  const targets = useMemo(() => planTargets(plan?.takeProfitsJson), [plan?.takeProfitsJson]);
  const revisions = useMemo(() => planRevisions(plan?.revisionsJson), [plan?.revisionsJson]);
  return (
    <div className={cn('tc', stale && 'stale')}>
      <div className="tc-h">
        <Icon className="ci" />
        <b>{r.symbol}</b>
        <span className={cn('tag', isLong ? 'g' : 'r')}>
          {t(isLong ? 'term.long' : 'term.short')}{r.leverage != null && ` ${r.leverage}x`}
        </span>
        <span className={cn('tag', CLOSE_MANNER_TONE[r.closeMannerKey])}>{t(`trade.closeManner.${r.closeMannerKey}`)}</span>
        {stale && <span className="tag">{t('trade.staleBadge')}</span>}
        <span className="pr">{px(r.entryPrice)} → {r.closedPrice != null ? px(r.closedPrice) : '—'}</span>
        <b className={cn('pn', pnl == null ? 'mute' : pnl >= 0 ? 'up' : 'dn')}>
          {pnl == null ? '—' : `${pnl >= 0 ? '+' : '-'}${fmtNum(Math.abs(pnl))}`}
        </b>
        {onToggleStale && plan && (
          <button type="button" className="btn sm" title={t('trade.staleHint')} onClick={() => onToggleStale(r)}>
            {stale ? <Eye className="ic" /> : <EyeOff className="ic" />}
            {stale ? t('trade.staleUnmark') : t('trade.staleMark')}
          </button>
        )}
      </div>
      <div className="tm">
        <span>{t('trade.openedAt', { time: fmtDateTime(r.openedAt) })}</span>
        <span>{t('trade.closedAt', { time: fmtDateTime(r.closedAt) })}</span>
        <span>{t('trade.held', { d: fmtDuration(r.openedAt, r.closedAt) })}</span>
      </div>
      {plan ? (
        <>
          <div className="pl">
            {(plan.playType || plan.signalsUsed) && (
              <div>{plan.playType && <b>{plan.playType}</b>}{plan.signalsUsed && t('plan.basis', { s: plan.signalsUsed })}</div>
            )}
            <div className="mute"><b>{t('plan.invalidation')}</b>{plan.invalidationCondition}</div>
          </div>
          <div className="lvs">
            {plan.entryPrice != null && <span>{t('plan.entry')}<b>{px(plan.entryPrice)}</b></span>}
            {plan.stopLossPrice != null && <span>{t('plan.origSl')}<b>{px(plan.stopLossPrice)}</b></span>}
            {/* 止盈分档：每档价位后面跟平掉仓位的百分之几；没设止盈就没有这一格 */}
            {targets.length > 0 && (
              <span>{t('plan.target')}{targets.map((g, i) => <b key={i}>{px(g.price)}<small>({g.percent}%)</small></b>)}</span>
            )}
          </div>
          <PlanEntries json={plan.entriesJson} />
          {revisions.length > 0 && (
            <div className="revs">
              {revisions.map((v, i) => (
                // 改了几次是这块的抬头，接在第一条前面，后面每条各占一行
                <p key={i}>
                  {i === 0 && `${t('plan.revisions', { count: revisions.length })} · `}
                  {fmtDateTime(v.time)} {v.type}{v.change && ` ${v.change}`}{v.reason && `，${v.reason}`}
                </p>
              ))}
            </div>
          )}
        </>
      ) : <div className="tm">{t('trade.noPlan')}</div>}
      {(r.openDecision || r.closeDecision) && (
        <div className="jp">
          {r.openDecision && <DecisionLink label={t('trade.openDecision')} d={r.openDecision} onJump={onJump} />}
          {r.closeDecision && <DecisionLink label={t('trade.closeDecision')} d={r.closeDecision} onJump={onJump} />}
          {/* 平仓那一轮的一句话理由：止损/止盈带走没有这一轮，头行的了结方式标签已经说明 */}
          {r.closeDecision?.reason && <span>—— {r.closeDecision.reason}</span>}
        </div>
      )}
    </div>
  );
}

function DecisionLink({ label, d, onJump }: { label: string; d: TradeDecisionRef; onJump: (d: TradeDecisionRef) => void }) {
  return (
    <button type="button" className="btn sm" onClick={() => onJump(d)}>
      <span>{label} · {fmtDateTime(d.wakeTime)}</span><ArrowRight className="ic" />
    </button>
  );
}
