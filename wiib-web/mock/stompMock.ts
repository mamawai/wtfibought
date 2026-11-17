/**
 * 假行情流：前端 sockjs-client 连 /ws/quotes，本地没有 ws server，
 * 这儿只实现 SockJS 的 xhr-streaming 那条降级路径（/info 里 websocket:false，客户端自己会挑它），
 * 上面再套一层最小 STOMP：CONNECT / SUBSCRIBE / 每秒往订上的 destination 推 MESSAGE。
 *
 * 三条链路：
 *   GET  /ws/quotes/info                             → 传输能力协商
 *   POST /ws/quotes/{server}/{session}/xhr_streaming → 帧从这条下行
 *   POST /ws/quotes/{server}/{session}/xhr_send      → 客户端发的 STOMP 帧从这条上行
 *
 * 下行那条只开 2 秒就收掉，客户端自己会用同一个 session 再拉一条（SockJS 本来就这么设计的，
 * 真服务端是按响应体积切）。会话（订阅表）跨响应留着，断开那几毫秒的帧先攒进队列。
 *
 * 无头 Chrome 另开一条路：整个会话只活 3.5 秒就断，让它去等 5 秒重连。
 * 一直挂着不断的话 --virtual-time-budget 等不到网络空闲，截图命令会一直吊在那儿不返回。
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { livePrice, liveBar, roundPrice } from './market';
import { jvm } from './admin';

/** SockJS 开场白：2048 个 h，逼浏览器把 XHR 的响应缓冲吐出来，之后的帧才是流式到达的 */
const PRELUDE = 'h'.repeat(2048) + '\n';

/** 一条下行响应开多久 */
const RESPONSE_MS = 2000;
/** 客户端多久不回来续，就当它走了 */
const REAP_MS = 15_000;
/** 无头浏览器的会话寿命：断开的空档才是截图那条命令的喘息机会 */
const HEADLESS_SESSION_MS = 3500;

/** 后端只为这三档开了 kline 广播，照抄 */
const KLINE_IVS = ['5m', '15m', '1h'];

interface Session {
  /** 当前挂着的下行响应，两条响应之间是 null */
  res: ServerResponse | null;
  /** destination → STOMP subscription id */
  subs: Map<string, string>;
  /** 没挂响应时先攒着的帧 */
  queue: string[];
  tick: ReturnType<typeof setInterval> | null;
  reap: ReturnType<typeof setTimeout> | null;
  dead: ReturnType<typeof setTimeout> | null;
}

const sessions = new Map<string, Session>();
let msgSeq = 0;

/** SockJS 消息帧：a["…"]，STOMP 帧里的 \0 和换行交给 JSON.stringify 转义 */
const emit = (s: Session, frame: string) => {
  const line = `a[${JSON.stringify(frame)}]\n`;
  if (s.res && !s.res.writableEnded) { s.res.write(line); return; }
  s.queue.push(line);
  if (s.queue.length > 60) s.queue.shift();
};

/** 拼一条 STOMP 帧：命令行 + 头 + 空行 + body + \0 */
const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}\n`).join('')}\n${body}\0`;

/** 拆一条 STOMP 帧 */
const parseFrame = (raw: string) => {
  const split = raw.indexOf('\n\n');
  const lines = (split < 0 ? raw : raw.slice(0, split)).split('\n');
  const command = lines.shift() ?? '';
  const headers: Record<string, string> = {};
  for (const l of lines) {
    const i = l.indexOf(':');
    if (i > 0) headers[l.slice(0, i)] = l.slice(i + 1);
  }
  return { command, headers };
};

/** 假盘口每边出几行、几个价格单位各是最小价位的几倍，同行情服务的 OrderBook */
const BOOK_ROWS = 10;
const BOOK_MULTS = [1, 10, 100, 1000];

/**
 * 假盘口，形状同行情服务发的那份：最小价位按价格的量级给（6 万的币 0.1，3 千的 0.01）；
 * 每个价位的挂单量是拿价位序号散出来的伪随机数，同一个价位每帧都一样，价动了盘口才跟着挪，不会整屏乱跳。
 * 三成的价位空着，整十、整百的价位挂得多一些。
 */
const depthBook = (symbol: string) => {
  const mid = livePrice(symbol);
  const exp = Math.floor(Math.log10(mid)) - 5;
  const tick = 10 ** exp;
  const dec = Math.max(0, -exp);
  // 买一落在第几个价位
  const best = Math.floor(mid / tick);
  // 一个价位大约挂 3 万 U
  const unit = 30_000 / mid;
  const qtyAt = (k: number) => {
    const r = Math.abs(Math.sin(k * 12.9898) * 43758.5453) % 1;
    return r < 0.3 ? 0 : unit * r * r * (k % 100 === 0 ? 9 : k % 10 === 0 ? 3 : 1);
  };
  const side = (bid: boolean, mult: number) => {
    const rows: [number, number][] = [];
    let bucket = NaN, sum = 0;
    for (let k = bid ? best : best + 1; rows.length < BOOK_ROWS; k += bid ? -1 : 1) {
      const qty = qtyAt(k);
      if (qty === 0) continue;
      // 买盘往下取整、卖盘往上取整
      const b = (bid ? Math.floor(k / mult) : Math.ceil(k / mult)) * mult;
      if (b !== bucket && sum > 0) {
        rows.push([+(bucket * tick).toFixed(dec), +sum.toFixed(4)]);
        sum = 0;
      }
      bucket = b;
      sum += qty;
    }
    return rows;
  };
  return {
    ladders: BOOK_MULTS.map(mult => ({ step: String(+(tick * mult).toFixed(dec)), b: side(true, mult), a: side(false, mult) })),
  };
};

/** 该 destination 这一秒要推的 body（kline 一次三档，前端自己按 i 过滤） */
const payloadsFor = (dest: string): unknown[] => {
  let m = /^\/topic\/crypto\/([A-Z0-9]+)$/.exec(dest);
  if (m) return [{ price: String(livePrice(m[1])), ts: Date.now(), ws: true }];

  m = /^\/topic\/futures\/([A-Z0-9]+)$/.exec(dest);
  if (m) {
    const fp = livePrice(m[1]);
    return [{ fp: String(fp), mp: String(roundPrice(m[1], fp * 0.99992)), fws: true }];
  }

  m = /^\/topic\/kline\/([A-Z0-9]+)$/.exec(dest);
  if (m) return KLINE_IVS.map(iv => liveBar(m![1], iv));

  m = /^\/topic\/depth\/([A-Z0-9]+)$/.exec(dest);
  if (m) return [depthBook(m[1])];

  // 管理页那三个进程的 JVM 状况
  m = /^\/topic\/monitor\/([a-z]+)$/.exec(dest);
  if (m) return [jvm(m[1])];

  return [];   // 其它 destination 静默
};

const pushTick = (s: Session) => {
  for (const [dest, id] of s.subs) {
    for (const body of payloadsFor(dest)) {
      emit(s, stompFrame('MESSAGE', {
        destination: dest, subscription: id,
        'message-id': `m-${++msgSeq}`, 'content-type': 'application/json',
      }, JSON.stringify(body)));
    }
  }
};

const dropSession = (key: string) => {
  const s = sessions.get(key);
  if (!s) return;
  if (s.tick) clearInterval(s.tick);
  if (s.reap) clearTimeout(s.reap);
  if (s.dead) clearTimeout(s.dead);
  sessions.delete(key);
};

/** 处理客户端发上来的一条 STOMP 帧 */
const handleStomp = (key: string, raw: string) => {
  const s = sessions.get(key);
  if (!s) return;
  const { command, headers } = parseFrame(raw);

  if (command === 'CONNECT' || command === 'STOMP') {
    // heart-beat 回 0,0：两边都不发心跳，省掉一整套定时器
    emit(s, stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }));
    return;
  }
  if (command === 'SUBSCRIBE' && headers.destination && headers.id) {
    s.subs.set(headers.destination, headers.id);
    pushTick(s);                       // 订上立刻给一帧，页面不用干等一秒
    return;
  }
  if (command === 'UNSUBSCRIBE' && headers.id) {
    for (const [dest, id] of s.subs) if (id === headers.id) s.subs.delete(dest);
    return;
  }
  if (command === 'DISCONNECT') {
    if (headers.receipt) emit(s, stompFrame('RECEIPT', { 'receipt-id': headers.receipt }));
    if (s.res && !s.res.writableEnded) s.res.end('c[3000,"Go away!"]\n');
    dropSession(key);
  }
};

const readBody = (req: IncomingMessage) => new Promise<string>(resolve => {
  let buf = '';
  req.setEncoding('utf8');
  req.on('data', (c: string) => { buf += c; });
  req.on('end', () => resolve(buf));
});

/** 挂上一条新的下行响应：开场白 + 攒着的帧，2 秒后收掉 */
function attach(key: string, s: Session, res: ServerResponse, fresh: boolean) {
  if (s.reap) { clearTimeout(s.reap); s.reap = null; }
  s.res = res;
  res.writeHead(200, {
    'Content-Type': 'application/javascript; charset=UTF-8',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    'X-Accel-Buffering': 'no',
  });
  res.write(PRELUDE);
  if (fresh) res.write('o\n');         // SockJS 的"连上了"，一个 session 只发一次
  for (const line of s.queue) res.write(line);
  s.queue.length = 0;

  const cut = setTimeout(() => { if (!res.writableEnded) res.end(); }, RESPONSE_MS);
  res.on('close', () => {
    clearTimeout(cut);
    if (s.res !== res) return;
    s.res = null;
    s.reap = setTimeout(() => dropSession(key), REAP_MS);
  });
}

/** 命中 /ws/quotes 就自己接管并返回 true，其余交回上层 */
export function handleQuotes(req: IncomingMessage, res: ServerResponse): boolean {
  const path = (req.url ?? '').split('?')[0];
  if (!path.startsWith('/ws/quotes')) return false;

  if (path === '/ws/quotes/info') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=UTF-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({
      websocket: false, origins: ['*:*'], cookie_needed: false,
      entropy: Math.floor(Math.random() * 2 ** 31),
    }));
    return true;
  }

  const m = /^\/ws\/quotes\/(\w+)\/(\w+)\/(xhr_streaming|xhr_send)$/.exec(path);
  if (!m) { res.statusCode = 404; res.end(); return true; }
  const key = `${m[1]}/${m[2]}`;

  if (m[3] === 'xhr_streaming') {
    let s = sessions.get(key);
    const fresh = !s;
    if (!s) {
      s = { res: null, subs: new Map(), queue: [], tick: null, reap: null, dead: null };
      const self = s;
      s.tick = setInterval(() => pushTick(self), 1000);
      if (/HeadlessChrome/i.test(String(req.headers['user-agent']))) {
        s.dead = setTimeout(() => {
          if (self.res && !self.res.writableEnded) self.res.end('c[3000,"mock: headless session cut"]\n');
          dropSession(key);
        }, HEADLESS_SESSION_MS);
      }
      sessions.set(key, s);
    }
    attach(key, s, res, fresh);
    return true;
  }

  void readBody(req).then(body => {
    try {
      // body 是 ["帧", "帧"]，一条里还可能粘着多个帧，按 \0 再拆一次
      for (const chunk of JSON.parse(body) as string[]) {
        for (const raw of chunk.split('\0')) {
          if (raw.trim()) handleStomp(key, raw);
        }
      }
    } catch { /* 心跳换行之类的非法 body，忽略 */ }
    res.writeHead(204, { 'Content-Type': 'text/plain; charset=UTF-8' });
    res.end();
  });
  return true;
}
