import { useEffect, useState } from 'react';
import { MonitorCard } from './MonitorCard';
import { cn } from '../lib/utils';

// 三进程 JVM，统一订 /topic/monitor/{进程}（sim 自推，feed/quant 经 sim 中继）
const PROCESSES = [
  { topic: '/topic/monitor/sim', label: 'sim' },
  { topic: '/topic/monitor/feed', label: 'feed' },
  { topic: '/topic/monitor/quant', label: 'quant' },
];

/**
 * 三进程 JVM 监控轮播。三张卡全程挂载（都订阅、都有实时数据），仅靠 translateX 滑动切换可见的那张；
 * 6s 自动轮播，hover 暂停，圆点可点选。无第三方轮播库。
 */
export function MonitorCarousel() {
  const [idx, setIdx] = useState(0);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (paused) return;
    const t = setInterval(() => setIdx(i => (i + 1) % PROCESSES.length), 6000);
    return () => clearInterval(t);
  }, [paused]);

  return (
    <div onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      <div className="overflow-hidden">
        <div
          className="flex transition-transform duration-500 ease-out"
          style={{ transform: `translateX(-${idx * 100}%)` }}
        >
          {PROCESSES.map(p => (
            <div key={p.label} className="w-full shrink-0">
              <MonitorCard topic={p.topic} label={p.label} />
            </div>
          ))}
        </div>
      </div>
      <div className="flex justify-center gap-1.5 mt-2">
        {PROCESSES.map((p, i) => (
          <button
            key={p.label}
            onClick={() => setIdx(i)}
            aria-label={p.label}
            className={cn(
              'h-1.5 rounded-full transition-all',
              i === idx ? 'w-4 bg-primary' : 'w-1.5 bg-muted-foreground/30 hover:bg-muted-foreground/50',
            )}
          />
        ))}
      </div>
    </div>
  );
}
