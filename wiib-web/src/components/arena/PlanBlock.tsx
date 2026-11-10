import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { cn, fmtDateTime, fmtNum } from '../../lib/utils';
import type { AiTraderPlanView, PlanRevision } from '../../types';
import { planTargets } from './traderTools';

/** 价位一格：标签 + 窄体大数，入场/原始止损并排 */
function Px({ label, value }: { label: string; value: number }) {
  return (
    <span>{label}<b className="text-foreground font-semibold text-[15px] [font-stretch:85%] ml-[5px]">{fmtNum(value)}</b></span>
  );
}

/**
 * 交易计划块：币对/方向/立的时刻 → 论点 + 依据 → 失效条件 → 原始价位 → 修订史。退出纪律的公开凭证。
 * embedded=挂在已了结交易卡里，改用虚线接上去，不自带底线。
 */
export function PlanBlock({ plan, embedded }: { plan: AiTraderPlanView; embedded?: boolean }) {
  const { t } = useTranslation('ai');
  const isLong = plan.side === 'LONG';
  const revisions = useMemo<PlanRevision[]>(() => {
    try {
      return plan.revisionsJson ? JSON.parse(plan.revisionsJson) as PlanRevision[] : [];
    } catch {
      return [];
    }
  }, [plan.revisionsJson]);
  const targets = useMemo(() => planTargets(plan.takeProfitsJson), [plan.takeProfitsJson]);

  return (
    <div className={cn(embedded ? 'mt-3 pt-3 border-t border-dashed border-border' : 'py-4 border-b border-border')}>
      <div className="flex items-center gap-2.5 flex-wrap">
        <b className="text-[17px] font-bold">{plan.symbol}</b>
        <span className={cn('chip fill', isLong ? 'up' : 'dn')}>{t(isLong ? 'term.long' : 'term.short')}</span>
        <span className="text-[12.5px] mute">{t('plan.setAt', { time: fmtDateTime(plan.openedWakeTime) })}</span>
      </div>
      {(plan.playType || plan.signalsUsed) && (
        <div className="text-[14px] mt-2">
          {plan.playType && <b className="font-bold mr-2.5">{plan.playType}</b>}
          {plan.signalsUsed && t('plan.basis', { s: plan.signalsUsed })}
        </div>
      )}
      <div className="text-[13.5px] mute mt-1 leading-[1.6]">
        <b className="text-foreground font-semibold mr-2.5">{t('plan.invalidation')}</b>{plan.invalidationCondition}
      </div>
      {/* 间距走 gap 不用空格字符：末项缺席时不会拖着个尾巴，窄屏也能换行 */}
      <div className="num flex flex-wrap gap-x-[22px] gap-y-1 mt-2.5 text-[13px] mute">
        {plan.entryPrice != null && <Px label={t('plan.entry')} value={plan.entryPrice} />}
        {plan.stopLossPrice != null && <Px label={t('plan.origSl')} value={plan.stopLossPrice} />}
        {/* 止盈分档：每档价位后面跟平掉仓位的百分之几；没设止盈就没有这一格 */}
        {targets.length > 0 && (
          <span>{t('plan.target')}
            {targets.map((r, i) => (
              <b key={i} className="text-foreground font-semibold text-[15px] [font-stretch:85%] ml-[5px]">
                {fmtNum(r.price)}<span className="mute font-normal text-[12.5px]">({r.percent}%)</span>
              </b>
            ))}
          </span>
        )}
      </div>
      {revisions.length > 0 && (
        <div className="text-[12.5px] mute mt-2 pt-2 border-t border-dashed border-border">
          {revisions.map((r, i) => (
            // 改了几次是这块的抬头，接在第一条前面，后面每条各占一行
            <p key={i}>
              {i === 0 && `${t('plan.revisions', { count: revisions.length })} · `}
              <span className="num">{fmtDateTime(r.time)}</span> {r.type}
              {r.change && <span className="num"> {r.change}</span>}
              {r.reason && `，${r.reason}`}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
