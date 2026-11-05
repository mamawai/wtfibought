import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTraderLive } from '../../hooks/useTraderLive';
import { cn, fmtTime } from '../../lib/utils';
import type { WakeKind } from '../../types';
import { WakeTraceView } from './WakeTraceView';

/** 是什么把它叫醒的：例行K线 / 哨兵警报 / 主人手动 / 持仓事件。存词表 key 不存文案 */
const KIND_KEY: Record<WakeKind, string> = {
  TRADE: 'decision.ok', ALERT: 'decision.alert', MANUAL: 'term.manualWake', EVENT: 'decision.event',
};

/**
 * 详情页的现场卡：唤醒进行中才出现，橙色顶线 + 脉冲徽章，已用秒/预算每秒走字，主体是逐帧长出来的过程视图。
 * 现场流的 hook 挂在这张卡里：token 帧只重渲染这张卡，不带着整页的曲线和时间线一起刷。
 * 一轮结束只把 endedAt 交给 onEnded，页面据此重拉；running=false 整卡不渲染（过程归时间线卡的"过程"按钮）
 */
export function LiveRunCard({ traderId, intervalCode, onEnded, className }: {
  traderId: number; intervalCode?: string; onEnded: (endedAt: number) => void; className?: string;
}) {
  const { t } = useTranslation('ai');
  const { live, running, endedAt } = useTraderLive(traderId);
  useEffect(() => {
    if (endedAt) onEnded(endedAt);
  }, [endedAt, onEnded]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [running]);
  if (!live || !running) return null;
  const used = Math.max(0, Math.round((now - live.startedAt) / 1000));
  return (
    <div className={cn('border-t-[3px] border-primary pt-3.5', className)}>
      <div className="flex items-center gap-3 text-[14px] font-bold mb-3.5">
        <span className="chip border-primary text-primary"><i className="dot pulse" />{t('live.scene')}</span>
        {/* 第一次模型调用开帧之前 calls 还是空的，别显示"第 0 次" */}
        <span>{t('live.callN', { n: live.calls.length || 1 })}</span>
        {/* 决策 · 4h 例行 · 12:00；警报和手动唤醒不是例行的，中间那段不出 */}
        <span className="mute font-medium">
          {[
            t(KIND_KEY[live.kind]),
            live.kind === 'TRADE' && intervalCode ? t('live.routine', { iv: intervalCode }) : '',
            fmtTime(live.wakeTime),
          ].filter(Boolean).join(' · ')}
        </span>
        <span className="ml-auto flex items-center gap-2.5 text-[13px] font-normal mute">
          {/* 超预算标红：后端到点会催模型收尾 */}
          <span className={cn('num', used > live.budgetSeconds && 'text-loss')}>
            {t('live.budget', { used, budget: live.budgetSeconds })}
          </span>
          <i className="block w-[120px] h-1 bg-border">
            <b className="block h-full bg-primary"
               style={{ width: `${Math.min(100, used / live.budgetSeconds * 100)}%` }} />
          </i>
        </span>
      </div>
      <WakeTraceView trace={live} running />
    </div>
  );
}
