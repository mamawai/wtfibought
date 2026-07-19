import { useEffect, useState } from 'react';
import { MonitorCard } from './MonitorCard';
import { cn } from '../lib/utils';

// 三进程 JVM，统一订 /topic/monitor/{进程}（sim 自推，feed/quant 经 sim 中继）
const PROCESSES = [
  { topic: '/topic/monitor/sim', label: 'sim', desc: '模拟盘' },
  { topic: '/topic/monitor/feed', label: 'feed', desc: '行情上游' },
  { topic: '/topic/monitor/quant', label: 'quant', desc: '量化引擎' },
];

/**
 * 三进程 JVM 监控轮播。三张卡全程挂载（都订阅、都有实时数据），仅靠 translateX 滑动切换可见的那张；
 * 6s 自动轮播，hover 暂停，进程标签在卡片标题行内可点选（三张卡渲染同一组标签，高度恒定）。无第三方轮播库。
 */
export function MonitorCarousel() {
  const [idx, setIdx] = useState(0);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (paused) return;
    const t = setInterval(() => setIdx(i => (i + 1) % PROCESSES.length), 6000);
    return () => clearInterval(t);
  }, [paused]);

  const switcher = (
    <span className="flex items-center gap-1">
      {PROCESSES.map((p, i) => (
        <button
          key={p.label}
          onClick={() => setIdx(i)}
          className={cn(
            'px-2 py-0.5 rounded-full text-[10px] font-bold transition-all cursor-pointer',
            i === idx
              ? 'bg-primary/10 text-primary neu-inset'
              : 'text-muted-foreground hover:text-foreground neu-flat',
          )}
        >
          {p.label}
        </button>
      ))}
    </span>
  );

  return (
    <div onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      {/* 裁剪框外扩 16px（-m-4 + 内 p-4）：拟物高光阴影在裁剪边内自然衰减，不再被直角截出四角白边 */}
      <div className="overflow-hidden -m-4">
        <div
          className="flex transition-transform duration-500 ease-out"
          style={{ transform: `translateX(-${idx * 100}%)` }}
        >
          {PROCESSES.map(p => (
            <div key={p.label} className="w-full shrink-0 p-4">
              <MonitorCard topic={p.topic} label={p.label} desc={p.desc} actions={switcher} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
