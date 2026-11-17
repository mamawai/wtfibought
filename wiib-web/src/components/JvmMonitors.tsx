import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { subscribe } from '../hooks/stompClient';
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

// 三个进程，都订 /topic/monitor/{进程}（sim 自推，feed/agent 经 sim 中继）。label 是进程代号不翻；说明存词表 key，渲染时现查
const PROCESSES = [
  { topic: '/topic/monitor/sim', label: 'sim', descKey: 'monitor.proc.sim' },
  { topic: '/topic/monitor/feed', label: 'feed', descKey: 'monitor.proc.feed' },
  { topic: '/topic/monitor/agent', label: 'agent', descKey: 'monitor.proc.agent' },
];

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

/** 一个占用率：名字、大数、一根量条。60 以上量条变黄，85 以上变红；pct 小于 0 是这项拿不到 */
function Meter({ label, pct, sub }: { label: string; pct: number; sub?: string }) {
  const shown = Math.max(0, Math.min(100, pct));
  return (
    <div className="mt">
      <span>{label}{sub && <em>{sub}</em>}</span>
      <b className="nf">{pct >= 0 ? `${shown}%` : 'N/A'}</b>
      <i className={cn(shown >= 85 ? 'r' : shown >= 60 && 'w')}><u style={{ width: `${shown}%` }} /></i>
    </div>
  );
}

/** 一个进程一块：CPU 和堆内存两个占用率，下面是堆外、线程、运行了多久和各代 GC */
function JvmBlock({ topic, label, desc }: { topic: string; label: string; desc: string }) {
  const { t } = useTranslation('strategy');
  const [data, setData] = useState<MonitorData | null>(null);

  // 共享 STOMP 连接订这个进程的 topic；进程挂了不再来帧，就停在最后一帧（"断了看得出来"，不做离线判定）
  useEffect(() => subscribe(topic, msg => {
    try { setData(JSON.parse(msg.body)); } catch { /* ignore */ }
  }), [topic]);

  return (
    <div className="blk jvm">
      <div className="jvm-h">
        <b>{label}</b>
        <span>{desc}</span>
        {data && <span className="tag sm g"><i className="live" />{t('monitor.live')}</span>}
      </div>
      {data ? (
        <>
          <div className="mts">
            <Meter label="CPU" pct={data.cpuPct ?? -1} />
            <Meter label={t('monitor.heap')} pct={data.heap.max > 0 ? Math.round(data.heap.used / data.heap.max * 100) : -1}
                   sub={`${fmtMem(data.heap.used)} / ${fmtMem(data.heap.max)}`} />
          </div>
          <div className="kv">
            <div>
              <span>{t('monitor.nonHeap')}</span>
              <b>{fmtMem(data.nonHeap.used)}<small>{t('monitor.committed', { value: fmtMem(data.nonHeap.committed) })}</small></b>
            </div>
            <div>
              <span>{t('monitor.threads')}</span>
              <b>{data.thread.current}<small>{t('monitor.threadSub', { peak: data.thread.peak, daemon: data.thread.daemon })}</small></b>
            </div>
            <div><span>{t('monitor.uptime')}</span><b>{fmtUptime(data.uptimeSec ?? 0)}</b></div>
            {data.gc.map(g => (
              <div key={g.name}>
                <span>{g.name}</span>
                <b>
                  {g.count}{t('monitor.gcTimes')}
                  <small>{t('monitor.gcSub', { total: g.timeMs, avg: g.count > 0 ? (g.timeMs / g.count).toFixed(1) : '0' })}</small>
                </b>
              </div>
            ))}
          </div>
        </>
      ) : (
        // 第一帧还没到：占着差不多的地方
        <>
          <div className="mts"><span className="sk" /><span className="sk" /></div>
          <div className="skr"><span className="sk t" /><span className="sk t" /><span className="sk t" /></div>
        </>
      )}
    </div>
  );
}

/** 三个进程的 JVM 状况，并排三块（管理页的一节，样式见 pages/Admin.css） */
export function JvmMonitors() {
  const { t } = useTranslation('strategy');
  return (
    <section className="sec">
      <div className="sec-h"><h2>{t('monitor.title')}</h2></div>
      <div className="jvms">
        {PROCESSES.map(p => <JvmBlock key={p.label} topic={p.topic} label={p.label} desc={t(p.descKey)} />)}
      </div>
    </section>
  );
}
