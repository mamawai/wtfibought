import { useEffect, useRef, useState } from 'react';
import { onConnectionState, subscribe } from './stompClient';
import { quantApi } from '../api';

export type HealthLevel = 'ok' | 'warn' | 'down' | 'unknown';
export interface SystemHealth { feed: HealthLevel; quant: HealthLevel; sim: HealthLevel }

// 行情静默阈值：BTC 现货流按 3s 节流广播，20s 没消息就是不对劲，60s 判死
const FEED_STALE_MS = 20_000;
const QUANT_POLL_MS = 60_000;

/**
 * 壳层三颗 LED 的数据源（全部借现成链路，零新增后端）：
 * - sim   = 前端到 sim(8080) 的 STOMP 连接态
 * - feed  = 行情心跳：借 BTC 现货 topic 当脉搏（stompClient 同 topic 共享订阅，无额外开销），
 *           行情链路是 feed→Redis→sim→前端，静默即上游断
 * - quant = 60s 轻量探活最新快照接口
 */
export function useSystemHealth(): SystemHealth {
  const [sim, setSim] = useState<HealthLevel>('unknown');
  const [feed, setFeed] = useState<HealthLevel>('unknown');
  const [quant, setQuant] = useState<HealthLevel>('unknown');
  const lastTickRef = useRef(0);

  useEffect(() => onConnectionState(up => setSim(up ? 'ok' : 'down')), []);

  useEffect(() => {
    const unsub = subscribe('/topic/crypto/BTCUSDT', () => {
      lastTickRef.current = Date.now();
      setFeed('ok');
    });
    const timer = setInterval(() => {
      if (!lastTickRef.current) return; // 一条都没收到过 → 保持 unknown
      const silent = Date.now() - lastTickRef.current;
      setFeed(silent > FEED_STALE_MS * 3 ? 'down' : silent > FEED_STALE_MS ? 'warn' : 'ok');
    }, 5_000);
    return () => { clearInterval(timer); unsub(); };
  }, []);

  useEffect(() => {
    let alive = true;
    const probe = () => quantApi.latestSnapshot().then(
      () => { if (alive) setQuant('ok'); },
      () => { if (alive) setQuant('down'); },
    );
    void probe();
    const timer = setInterval(probe, QUANT_POLL_MS);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  return { feed, quant, sim };
}
