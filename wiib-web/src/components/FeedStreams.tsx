import { useEffect, useState, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { CircleAlert, RefreshCw } from 'lucide-react';
import { subscribe } from '../hooks/stompClient';
import { adminApi } from '../api';
import type { FeedStreamHealth, FeedStreamHealthSnapshot } from '../types';
import { useToast } from './ui/use-toast';
import { cn } from '../lib/utils';

// 状态 → 小标签的颜色和词表 key：绿=已连；黄=建连/重连中；红=断开；灰=闲置（按集合订的流没有要订的币）
const STATUS_META: Record<FeedStreamHealth['status'], { tone: string; labelKey: string }> = {
  CONNECTED:    { tone: 'g', labelKey: 'feed.status.connected' },
  CONNECTING:   { tone: 'w', labelKey: 'feed.status.connecting' },
  RECONNECTING: { tone: 'w', labelKey: 'feed.status.reconnecting' },
  DISCONNECTED: { tone: 'r', labelKey: 'feed.status.disconnected' },
  IDLE:         { tone: '',  labelKey: 'feed.status.idle' },
};

// 距上次数据多久：纯本地算，不请求服务端
function fmtAge(ms: number, now: number): string {
  if (!ms) return '—';
  const sec = Math.max(0, Math.floor((now - ms) / 1000));
  if (sec < 60) return `${sec}s`;
  const m = Math.floor(sec / 60);
  if (m < 60) return `${m}m${sec % 60}s`;
  return `${Math.floor(m / 60)}h${m % 60}m`;
}

/**
 * feed 行情数据流的状况（管理页的一节，样式见 pages/Admin.css）。
 * 进来拉一次快照（GET /monitor/streams），之后订 STOMP /topic/feed/streams 实时整表替换，版本号旧的丢掉；
 * 一行一条 Binance 行情 WS，断了点行尾那颗强制重连。
 */
export function FeedStreams() {
  const { t } = useTranslation(['strategy', 'common']);
  const { toast } = useToast();
  const [streams, setStreams] = useState<FeedStreamHealth[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  // 手上这份快照的版本号：推送经中继会乱序、拉的也可能比推的晚到，版本更旧的直接丢
  const versionRef = useRef(0);

  const apply = useCallback((snap: FeedStreamHealthSnapshot) => {
    if (snap.version < versionRef.current) return;
    versionRef.current = snap.version;
    setStreams(snap.streams);
  }, []);

  const fetchSnapshot = useCallback(async () => {
    try {
      apply(await adminApi.feedStreams());
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
      toast((e as Error).message || t('feed.toast.loadFailed'), 'error');
    }
  }, [apply, toast, t]);

  useEffect(() => { void fetchSnapshot(); }, [fetchSnapshot]);

  // 实时事件：某条流状态一变，feed 推全量快照，整表替换（事件驱动，无轮询）
  useEffect(() => subscribe('/topic/feed/streams', msg => {
    try { apply(JSON.parse(msg.body)); } catch { /* ignore */ }
  }), [apply]);

  // 本地 1s tick：只驱动"断开 Xs"走字
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const handleRetry = async (name: string) => {
    setRetrying(name);
    try {
      const r = await adminApi.retryFeedStream(name);
      toast(r.ok ? t('feed.toast.retryTriggered', { name }) : t('feed.toast.notFound', { name }), r.ok ? 'success' : 'error');
    } catch (e) {
      toast((e as Error).message || t('feed.toast.retryFailed'), 'error');
    } finally {
      setRetrying(null);
    }
  };

  // 异常 = 断开或重连中；CONNECTING 是启动/重连途中的正常瞬态，不算异常
  const anyDown = streams?.some(s => s.status === 'DISCONNECTED' || s.status === 'RECONNECTING');

  return (
    <section className="sec">
      <div className="sec-h">
        <h2>
          {t('feed.title')}
          {streams && <small className={cn(anyDown && 'dn')}>{anyDown ? t('feed.someDown') : t('feed.allOk')}</small>}
        </h2>
        <button type="button" className="ib sm" onClick={() => void fetchSnapshot()}
                aria-label={t('common:refresh')} title={t('common:refresh')}>
          <RefreshCw className="ic" />
        </button>
      </div>
      <p className="intro">{t('feed.desc')}</p>
      {!streams && !loadError && <div className="empty"><span>{t('common:loading')}</span></div>}
      {!streams && loadError && <div className="note r"><CircleAlert className="ic" /><div>{t('feed.loadError')}</div></div>}
      {streams?.length === 0 && <div className="empty"><span>{t('feed.noStreams')}</span></div>}
      {streams && streams.length > 0 && (
        <div className="streams">
          {streams.map(s => {
            const meta = STATUS_META[s.status];
            return (
              <div key={s.name}>
                <b>{s.name}</b>
                <span className={cn('tag', meta.tone)}><i className={cn(s.status === 'CONNECTED' && 'live')} />{t(meta.labelKey)}</span>
                {/* 断了多久只对没连着的显示：连着的流数据在流但事件不刷 lastMessageAt，显示会假性增长；闲置的没断 */}
                <span className="age">
                  {s.status !== 'CONNECTED' && s.status !== 'IDLE' && t('feed.downFor', { age: fmtAge(s.lastMessageAt, now) })}
                  {s.reconnectAttempt > 0 && <em>{t('feed.reconnects', { n: s.reconnectAttempt })}</em>}
                </span>
                <button type="button" className="xb" onClick={() => void handleRetry(s.name)} disabled={retrying === s.name}
                        aria-label={t('common:retry')} title={t('common:retry')}>
                  <RefreshCw className={cn('ic', retrying === s.name && 'animate-spin')} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
