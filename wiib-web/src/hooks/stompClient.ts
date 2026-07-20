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

/** 从持久化的 store 里取 token。带上它后端才认得出身份，才能收 /user/queue 点对点消息 */
function currentToken(): string {
  try {
    const stored = localStorage.getItem('wiib-user');
    if (!stored) return '';
    const { state } = JSON.parse(stored);
    return state?.token ?? '';
  } catch {
    return '';
  }
}

function getClient(): Client {
  if (client) return client;
  client = new Client({
    // 每次建连（含自动重连）都会重新执行，所以总是拿到当下最新的 token；
    // 游客没 token 就匿名连，后端不会拒，照样收行情广播
    webSocketFactory: () => {
      const token = currentToken();
      return new SockJS(`/ws/quotes${token ? `?token=${encodeURIComponent(token)}` : ''}`);
    },
    reconnectDelay: 5000,
    onConnect: () => {
      connected = true;
      // 重连后重新订阅所有 topic
      for (const topic of subs.keys()) {
        bindTopic(topic);
      }
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

/**
 * 登录态变化后重连，让新 token 生效。
 * 漏调的表现很隐蔽：行情照跑、评论能发，只有通知角标永远是 0 且不报任何错——
 * 因为连接还挂着登录前的匿名身份，点对点消息投不到它。
 * subs 不动，onConnect 会按 subs.keys() 全量补订。
 */
export function reconnectWithIdentity() {
  if (!client?.active) return;
  void client.deactivate().then(() => {
    // 断开期间订阅者可能全走了（登出会卸掉信封组件），这时不该再连回来。
    // deactivate() 是同步把 active 置 false 的，所以 unsubscribe 里那句
    // "没人订阅了就关连接" 判断会失效，得在这儿补上这一刀，否则登出后
    // 会留一条谁也不用、又没人能关掉的匿名连接靠 5s 重连一直活着
    if (subs.size > 0) client?.activate();
  });
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

  // 未连接时无需暂存：onConnect 会按 subs.keys() 全量补订
  if (connected) {
    bindTopic(topic);
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
