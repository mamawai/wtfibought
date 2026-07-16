import { useEffect, useState } from 'react';
import { subscribe } from '../hooks/stompClient';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';
import { Activity, Layers, Cpu, Recycle, Clock } from 'lucide-react';
import { cn } from '../lib/utils';

interface MemoryUsage { used: number; committed: number; max: number }
interface GcInfo { name: string; count: number; timeMs: number }
interface ThreadInfo { current: number; peak: number; daemon: number }
interface MonitorData {
  heap: MemoryUsage;
  nonHeap: MemoryUsage;
  thread: ThreadInfo;
  gc: GcInfo[];
  cpuPct?: number;
  uptimeSec?: number;
}

function fmtMem(mb: number) {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)}G`;
  return `${mb}M`;
}

function fmtUptime(sec: number) {
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function toneColor(pct: number): string {
  if (pct >= 85) return 'var(--color-loss)';
  if (pct >= 60) return '#f59e0b';
  return 'var(--color-gain)';
}

// SVG半圆仪表盘
function Gauge({ pct, label, sub }: { pct: number; label: string; sub?: string }) {
  const clamped = Math.max(0, Math.min(100, pct));
  const r = 38;
  const cx = 50;
  const cy = 48;
  const circumHalf = Math.PI * r;
  const strokeW = 7;

  // 刻度线
  const ticks = Array.from({ length: 11 }, (_, i) => {
    const angle = Math.PI + (Math.PI * i) / 10;
    const inner = r - 3;
    const outer = r + 3;
    return {
      x1: cx + Math.cos(angle) * inner,
      y1: cy + Math.sin(angle) * inner,
      x2: cx + Math.cos(angle) * outer,
      y2: cy + Math.sin(angle) * outer,
      major: i % 5 === 0,
    };
  });

  // 指针角度: 0% → -90°(左), 100% → 90°(右)
  const needleAngle = -90 + clamped * 1.8;
  const needleLen = r - 10;

  const color = toneColor(clamped);

  return (
    <div className="flex flex-col items-center">
      <svg viewBox="0 0 100 58" className="w-full max-w-[130px]">
        <defs>
          <linearGradient id={`gauge-grad-${label}`} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="var(--color-gain)" />
            <stop offset="60%" stopColor="#f59e0b" />
            <stop offset="85%" stopColor="var(--color-loss)" />
          </linearGradient>
        </defs>

        {/* 底弧(背景) */}
        <path
          d={`M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}`}
          fill="none"
          stroke="var(--color-muted)"
          strokeWidth={strokeW}
          strokeLinecap="round"
        />

        {/* 填充弧 */}
        <path
          d={`M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}`}
          fill="none"
          stroke={`url(#gauge-grad-${label})`}
          strokeWidth={strokeW}
          strokeLinecap="round"
          strokeDasharray={circumHalf}
          strokeDashoffset={circumHalf * (1 - clamped / 100)}
          style={{ transition: 'stroke-dashoffset 0.8s cubic-bezier(0.34, 1.56, 0.64, 1)' }}
        />

        {/* 刻度 */}
        {ticks.map((t, i) => (
          <line
            key={i}
            x1={t.x1} y1={t.y1} x2={t.x2} y2={t.y2}
            stroke="var(--color-muted-foreground)"
            strokeWidth={t.major ? 1.2 : 0.5}
            opacity={t.major ? 0.6 : 0.3}
          />
        ))}

        {/* 指针 — 用 transform rotate 实现 CSS 动画 */}
        <g
          style={{
            transform: `rotate(${needleAngle}deg)`,
            transformOrigin: `${cx}px ${cy}px`,
            transition: 'transform 0.8s cubic-bezier(0.34, 1.56, 0.64, 1)',
          }}
        >
          <line
            x1={cx} y1={cy} x2={cx} y2={cy - needleLen}
            stroke={color}
            strokeWidth={1.8}
            strokeLinecap="round"
            style={{ filter: `drop-shadow(0 0 2px ${color})` }}
          />
        </g>

        {/* 中心圆 */}
        <circle cx={cx} cy={cy} r={2.5} fill={color} style={{ transition: 'fill 0.8s ease' }} />

        {/* 百分比 */}
        <text
          x={cx} y={cy - 6}
          textAnchor="middle"
          className="fill-foreground"
          style={{ fontSize: '11px', fontWeight: 800, fontFamily: 'ui-monospace, monospace' }}
        >
          {pct >= 0 ? `${clamped}%` : 'N/A'}
        </text>
      </svg>

      <div className="text-center -mt-1">
        <div className="text-[10px] font-bold text-muted-foreground leading-none">{label}</div>
        {sub && <div className="text-[9px] text-muted-foreground/70 leading-tight mt-0.5">{sub}</div>}
      </div>
    </div>
  );
}

function MetricTile({ icon: Icon, label, value, sub }: {
  icon: React.ElementType; label: string; value: React.ReactNode; sub?: string;
}) {
  return (
    <div className="rounded-xl p-2 neu-inset space-y-0.5">
      <div className="flex items-center gap-1 text-[10px] text-muted-foreground leading-none">
        <Icon className="w-2.5 h-2.5 shrink-0" />
        <span className="truncate">{label}</span>
      </div>
      <div className="font-mono text-xs font-bold leading-tight">{value}</div>
      {sub && <div className="text-[9px] text-muted-foreground leading-tight">{sub}</div>}
    </div>
  );
}

// 结构与数据态逐段对齐（标题含状态点占位 + 双仪表盘 + 3 指标格 + GC 行），
// 保证 skeleton→有数据零高度跳动；三卡轮播因 flex 行取最高值，滑动时高度亦恒定
function MonitorSkeleton({ label, desc }: { label: string; desc?: string }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm">
          <div className="p-1 rounded-md bg-primary/10">
            <Activity className="w-3.5 h-3.5 text-primary" />
          </div>
          {label} JVM
          {desc && <span className="text-xs text-muted-foreground font-normal">{desc}</span>}
          <span className="ml-auto w-1.5 h-1.5 rounded-full shrink-0 bg-muted-foreground/30" />
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex justify-center gap-8">
          <Skeleton className="w-[130px] h-[92px] rounded-xl" />
          <Skeleton className="w-[130px] h-[92px] rounded-xl" />
        </div>
        <div className="grid grid-cols-3 gap-1.5">
          {[0, 1, 2].map(i => <Skeleton key={i} className="h-11 rounded-xl" />)}
        </div>
        <div className="grid grid-cols-2 gap-1.5">
          <Skeleton className="h-11 rounded-xl" />
          <Skeleton className="h-11 rounded-xl" />
        </div>
      </CardContent>
    </Card>
  );
}

export function MonitorCard({ topic, label, desc }: { topic: string; label: string; desc?: string }) {
  const [data, setData] = useState<MonitorData | null>(null);

  // 共享 STOMP 连接订阅该进程的 JVM topic；进程挂了不再来帧，卡片停在最后一帧（用户已认可"断了看得出来"，不做离线判定）
  useEffect(() => subscribe(topic, msg => {
    try { setData(JSON.parse(msg.body)); } catch { /* ignore */ }
  }), [topic]);

  if (!data) return <MonitorSkeleton label={label} desc={desc} />;

  const heapPct = data.heap.max > 0 ? Math.round(data.heap.used / data.heap.max * 100) : -1;
  const cpuPct = data.cpuPct ?? -1;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm">
          <div className="p-1 rounded-md bg-primary/10">
            <Activity className="w-3.5 h-3.5 text-primary" />
          </div>
          {label} JVM
          {desc && <span className="text-xs text-muted-foreground font-normal">{desc}</span>}
          <span className="ml-auto w-1.5 h-1.5 rounded-full shrink-0 bg-gain animate-pulse" title="实时" />
        </CardTitle>
      </CardHeader>

      <CardContent className="space-y-3">
        {/* 仪表盘 */}
        <div className="flex justify-center gap-8">
          <Gauge
            pct={cpuPct}
            label="CPU"
          />
          <Gauge
            pct={heapPct}
            label="堆内存"
            sub={`${fmtMem(data.heap.used)}/${fmtMem(data.heap.max)}`}
          />
        </div>

        {/* 指标网格 */}
        <div className="grid grid-cols-3 gap-1.5">
          <MetricTile
            icon={Layers}
            label="堆外"
            value={fmtMem(data.nonHeap.used)}
            sub={`已提交${fmtMem(data.nonHeap.committed)}`}
          />
          <MetricTile
            icon={Cpu}
            label="线程"
            value={data.thread.current}
            sub={`峰${data.thread.peak} · 守护${data.thread.daemon}`}
          />
          <MetricTile
            icon={Clock}
            label="运行"
            value={fmtUptime(data.uptimeSec ?? 0)}
          />
        </div>

        {/* GC */}
        {data.gc.length > 0 && (
          <div className={cn("grid gap-1.5", data.gc.length > 1 ? "grid-cols-2" : "grid-cols-1")}>
            {data.gc.map(g => {
              const avg = g.count > 0 ? (g.timeMs / g.count).toFixed(1) : '0';
              return (
                <MetricTile
                  key={g.name}
                  icon={Recycle}
                  label={g.name}
                  value={<>{g.count}<span className="text-muted-foreground font-normal text-[9px]">次</span></>}
                  sub={`${g.timeMs}ms · 均${avg}ms`}
                />
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
