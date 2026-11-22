import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTraderLive } from '../../hooks/useTraderLive';
import { cn, fmtTime } from '../../lib/utils';
import type { WakeTrace } from '../../types';
import { MAX_MODEL_CALLS } from './traderConfig';
import { useKindLabel } from './useKindLabel';
import { WakeTraceView } from './WakeTraceView';

/**
 * 工作台的实时思考卡（样式见 ArenaDetail.css .livebox）：醒着时才出现，同时在跑几轮就出几张，一轮一张。
 * 实时流的 hook 挂在这里：token 帧只重渲染这几张卡，不带着整页的曲线和时间线一起刷。
 * 一轮结束把 endedAt 交给 onEnded，页面据此重拉；有没有轮在跑报给 onRunning，心跳条跟着变
 */
export function LiveRunCard({ traderId, intervalCode, onEnded, onRunning, className }: {
  traderId: number; intervalCode: string; onEnded: (endedAt: number) => void;
  onRunning: (running: boolean) => void; className?: string;
}) {
  const { runs, endedAt } = useTraderLive(traderId);
  const running = runs.length > 0;
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
  return (
    <>
      {runs.map(({ id, trace }) => (
        <LiveRun key={id} live={trace} now={now} intervalCode={intervalCode} className={className} />
      ))}
    </>
  );
}

/**
 * 一轮的卡：头一行是闪着的「正在思考」、第几次思考、什么叫醒的、只处理哪个币、已用秒 / 预算；
 * 底下一排格子是这一轮最多能想几次、想到第几次了，主体是逐帧长出来的过程
 */
function LiveRun({ live, now, intervalCode, className }: {
  live: WakeTrace; now: number; intervalCode: string; className?: string;
}) {
  const { t } = useTranslation('ai');
  const kindLabel = useKindLabel();
  const used = Math.max(0, Math.round((now - live.startedAt) / 1000));
  // 第一次模型调用开帧之前 calls 还是空的，按正在想第 1 步算
  const cur = Math.max(1, live.calls.length);
  return (
    <section className={cn('blk livebox', className)}>
      <div className="live-h">
        <span className="tag o"><i className="live" />{t('wb.live.title')}</span>
        <span>{t('live.callN', { n: cur })}</span>
        <span>{kindLabel(live.kind, true, intervalCode)} · {fmtTime(live.wakeTime)}</span>
        {live.symbol && <span>{t('live.onlySymbol', { symbol: live.symbol })}</span>}
        <span className="bud">
          {/* 超预算标红：后端到点会催模型收尾 */}
          <span className={cn(used > live.budgetSeconds && 'dn')}>{t('live.budget', { used, budget: live.budgetSeconds })}</span>
          <i><b style={{ width: `${Math.min(100, used / live.budgetSeconds * 100)}%` }} /></i>
        </span>
      </div>
      <div className="cells" aria-hidden="true">
        {Array.from({ length: MAX_MODEL_CALLS }, (_, i) => (
          <i key={i} className={cn(i < cur - 1 && 'done', i === cur - 1 && 'cur')} />
        ))}
      </div>
      <WakeTraceView trace={live} running />
    </section>
  );
}
