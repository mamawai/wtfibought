import { useEffect, useState, useCallback } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Button } from './ui/button';
import { subscribe } from '../hooks/stompClient';
import { adminApi } from '../api';
import type { FeedStreamHealth } from '../types';
import { useToast } from './ui/use-toast';
import { cn } from '../lib/utils';
import { Radio, RefreshCw } from 'lucide-react';

// 状态 → 颜色点/文案：绿=已连；琥珀=建连/重连中；红=断开
const STATUS_META: Record<FeedStreamHealth['status'], { dot: string; text: string; label: string }> = {
  CONNECTED:    { dot: 'bg-gain',     text: 'text-gain',       label: '已连接' },
  CONNECTING:   { dot: 'bg-amber-500', text: 'text-amber-500', label: '连接中' },
  RECONNECTING: { dot: 'bg-amber-500', text: 'text-amber-500', label: '重连中' },
  DISCONNECTED: { dot: 'bg-loss',     text: 'text-loss',       label: '已断开' },
};

// 距上次数据 age：纯本地计算，不请求服务端
function fmtAge(ms: number, now: number): string {
  if (!ms) return '—';
  const sec = Math.max(0, Math.floor((now - ms) / 1000));
  if (sec < 60) return `${sec}s`;
  const m = Math.floor(sec / 60);
  if (m < 60) return `${m}m${sec % 60}s`;
  return `${Math.floor(m / 60)}h${m % 60}m`;
}

/**
 * feed 数据流健康面板（Admin，仅管理员）。
 * 进面板拉一次快照（GET /monitor/streams），之后订 STOMP /topic/feed/streams 实时整表替换；
 * 每行一条 Binance 行情 WS，断开点「重试」强制重连。
 */
export function FeedStreamHealthCard() {
  const { toast } = useToast();
  const [streams, setStreams] = useState<FeedStreamHealth[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const fetchSnapshot = useCallback(async () => {
    try {
      setStreams(await adminApi.feedStreams());
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
      toast((e as Error).message || 'feed 流状态获取失败', 'error');
    }
  }, [toast]);

  useEffect(() => { void fetchSnapshot(); }, [fetchSnapshot]);

  // 实时事件：某条流状态一变，feed 推全量快照，整表替换（事件驱动，无轮询）
  useEffect(() => subscribe('/topic/feed/streams', msg => {
    try { setStreams(JSON.parse(msg.body)); } catch { /* ignore */ }
  }), []);

  // 本地 1s tick：仅驱动"距上次数据 Xs"走字
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const handleRetry = async (name: string) => {
    setRetrying(name);
    try {
      const r = await adminApi.retryFeedStream(name);
      toast(r.ok ? `已触发重连 ${name}` : `未找到流 ${name}`, r.ok ? 'success' : 'error');
    } catch (e) {
      toast((e as Error).message || '重试失败', 'error');
    } finally {
      setRetrying(null);
    }
  };

  // 异常 = 断开或重连中；CONNECTING 是启动/重连途中的正常瞬态，不算异常
  const anyDown = streams?.some(s => s.status === 'DISCONNECTED' || s.status === 'RECONNECTING');

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-lg flex items-center gap-2">
            <Radio className="w-5 h-5" /> feed 数据流健康
          </CardTitle>
          <div className="flex items-center gap-2">
            {streams && (
              <span className={cn('text-xs font-medium', anyDown ? 'text-loss' : 'text-muted-foreground')}>
                {anyDown ? '有流异常' : '全部正常'}
              </span>
            )}
            <Button variant="outline" size="sm" onClick={() => void fetchSnapshot()}>
              <RefreshCw className="w-3.5 h-3.5" />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-1.5">
        <div className="text-xs text-muted-foreground">Binance 行情 WS（应当常连；断开=故障，点重试强制立即重连）。状态实时推送。</div>
        {!streams && !loadError && <div className="text-sm text-muted-foreground text-center py-4">加载中…</div>}
        {!streams && loadError && <div className="text-sm text-loss text-center py-4">无法连接 feed（是否在线？）—点右上角刷新重试</div>}
        {streams?.length === 0 && <div className="text-sm text-muted-foreground text-center py-4">无登记的流</div>}
        {streams?.map(s => {
          const meta = STATUS_META[s.status];
          return (
            <div key={s.name} className="flex items-center gap-3 p-2.5 rounded-xl neu-inset">
              <span className={cn('w-2 h-2 rounded-full shrink-0', meta.dot, s.status === 'CONNECTED' && 'animate-pulse')} />
              <span className="text-sm font-bold min-w-[7rem]">{s.name}</span>
              <span className={cn('text-xs font-medium', meta.text)}>{meta.label}</span>
              {/* age 只对非连接态显示：连着的流数据在流但事件不刷 lastMessageAt，显示会假性增长；断了才关心"多久没数据" */}
              <span className="text-[11px] text-muted-foreground ml-auto tabular-nums">
                {s.status !== 'CONNECTED' && <>断开 {fmtAge(s.lastMessageAt, now)}</>}
                {s.reconnectAttempt > 0 && <span className="text-loss ml-2">重连×{s.reconnectAttempt}</span>}
              </span>
              <Button
                variant="ghost"
                size="sm"
                className="h-7 px-2 text-xs shrink-0"
                onClick={() => void handleRetry(s.name)}
                disabled={retrying === s.name}
              >
                <RefreshCw className={cn('w-3.5 h-3.5', retrying === s.name && 'animate-spin')} />
                <span className="ml-1">重试</span>
              </Button>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}
