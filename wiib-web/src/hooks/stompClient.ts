import { Client } from '@stomp/stompjs';
import type { IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

type Callback = (msg: IMessage) => void;

let client: Client | null = null;
// 同一 topic 可能有多个订阅者
const subs = new Map<string, Set<Callback>>();
// STOMP subscription id，每个 topic 只建一个
const stompSubs = new Map<string, { unsubscribe: () => void }>();
let connected = false;
const pendingTopics = new Set<string>();

function getClient(): Client {
  if (client) return client;
  client = new Client({
    webSocketFactory: () => new SockJS('/ws/quotes'),
    reconnectDelay: 5000,
    onConnect: () => {
      connected = true;
      // 重连后重新订阅所有 topic
      for (const topic of subs.keys()) {
        bindTopic(topic);
      }
      pendingTopics.clear();
    },
    onDisconnect: () => {
      connected = false;
      stompSubs.clear();
    },
    onWebSocketClose: () => {
      connected = false;
      stompSubs.clear();
    },
  });
  return client;
}

function bindTopic(topic: string) {
  if (stompSubs.has(topic) || !client) return;
  const sub = client.subscribe(topic, (msg) => {
    const cbs = subs.get(topic);
    if (cbs) cbs.forEach(cb => cb(msg));
  });
  stompSubs.set(topic, sub);
}

/** 订阅 topic，返回取消函数 */
export function subscribe(topic: string, cb: Callback): () => void {
  let set = subs.get(topic);
  if (!set) {
    set = new Set();
    subs.set(topic, set);
  }
  set.add(cb);

  const c = getClient();
  if (!c.active) c.activate();

  if (connected) {
    bindTopic(topic);
  } else {
    pendingTopics.add(topic);
  }

  // 返回 unsubscribe
  return () => {
    set!.delete(cb);
    if (set!.size === 0) {
      subs.delete(topic);
      const s = stompSubs.get(topic);
      if (s) { s.unsubscribe(); stompSubs.delete(topic); }
    }
    // 所有订阅者都走了，关闭连接
    if (subs.size === 0 && client?.active) {
      client.deactivate();
      client = null;
      connected = false;
    }
  };
}
