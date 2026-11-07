import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTraderLive } from '../../hooks/useTraderLive';
import { cn, fmtTime } from '../../lib/utils';
import { MAX_MODEL_CALLS } from './traderConfig';
import { useKindLabel } from './useKindLabel';
import { WakeTraceView } from './WakeTraceView';

/**
 * 工作台的实时思考卡：醒着时才出现，橙色顶线 + 脉冲徽章，12 格步数条、已用秒/预算每秒走字，主体是逐帧长出来的过程。
 * 实时流的 hook 挂在这张卡里：token 帧只重渲染这张卡，不带着整页的曲线和时间线一起刷。
 * 一轮结束把 endedAt 交给 onEnded，页面据此重拉；醒着没醒着报给 onRunning，心跳条跟着亮
 */
export function LiveRunCard({ traderId, intervalCode, onEnded, onRunning, className }: {
  traderId: number; intervalCode: string; onEnded: (endedAt: number) => void;
  onRunning: (running: boolean) => void; className?: string;
}) {
  const { t } = useTranslation('ai');
  const kindLabel = useKindLabel();
  const { live, running, endedAt } = useTraderLive(traderId);
  useEffect(() => {
    if (endedAt) onEnded(endedAt);
  }, [endedAt, onEnded]);
  useEffect(() => { onRunning(running); }, [running, onRunning]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [running]);
  if (!live || !running) return null;
  const used = Math.max(0, Math.round((now - live.startedAt) / 1000));
  // 第一次模型调用开帧之前 calls 还是空的，按正在想第 1 步算
  const cur = Math.max(1, live.calls.length);
  return (
    <div className={cn('border-t-[3px] border-primary pt-3.5', className)}>
      <div className="flex items-center gap-3 flex-wrap text-[14px] font-bold">
        <span className="chip border-primary text-primary"><i className="dot pulse" />{t('wb.live.title')}</span>
        <span>{t('live.callN', { n: cur })}</span>
        <span className="mute font-medium">{kindLabel(live.kind, true, intervalCode)} · {fmtTime(live.wakeTime)}</span>
        <span className="ml-auto flex items-center gap-2.5 text-[13px] font-normal mute">
          {/* 超预算标红：后端到点会催模型收尾 */}
          <span className={cn('num', used > live.budgetSeconds && 'text-loss')}>
            {t('live.budget', { used, budget: live.budgetSeconds })}
          </span>
          <i className="block w-[120px] h-1 bg-border">
            <b className="block h-full bg-primary" style={{ width: `${Math.min(100, used / live.budgetSeconds * 100)}%` }} />
          </i>
        </span>
      </div>
      <div className="tr-cells mt-3.5 mb-3.5" aria-hidden="true">
        {Array.from({ length: MAX_MODEL_CALLS }, (_, i) => (
          <i key={i} className={cn(i < cur - 1 && 'done', i === cur - 1 && 'cur')} />
        ))}
      </div>
      <WakeTraceView trace={live} running />
    </div>
  );
}
