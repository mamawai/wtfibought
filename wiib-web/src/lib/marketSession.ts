/**
 * TradFi 标的的「标的市场交易时段」计算。
 *
 * 这些永续合约本身 7×24 交易，但流动性跟着底层股票市场走，所以要把美股/韩股的
 * 盘前/开盘/盘后/夜盘映射到用户本地时区展示。
 *
 * 时段用「相对交易日 D 的墙钟锚点」声明（见 MARKETS），代码把锚点放回**市场时区**
 * 解析成绝对时刻 —— 夏令时切换自动跟着走，不需要写死 UTC 偏移。
 */

export type SessionKind = 'pre' | 'open' | 'post' | 'overnight' | 'closed';
export type MarketId = 'US' | 'KRX';

/** 一个时段锚点：分钟数相对交易日 D 的 00:00（可为负 = 前一天，可 >1440 = 后一天） */
interface Anchor {
  kind: Exclude<SessionKind, 'closed'>;
  from: number;
  to: number;
}

interface MarketDef {
  tz: string;
  /** 市场时区里的交易日（0=周日）*/
  tradingDays: number[];
  anchors: Anchor[];
}

const H = (h: number, m = 0) => h * 60 + m;

const MARKETS: Record<MarketId, MarketDef> = {
  // 美股：周日 20:00 ET 起夜盘开跑，到周五 20:00 ET 收，中间一天不断
  US: {
    tz: 'America/New_York',
    tradingDays: [1, 2, 3, 4, 5],
    anchors: [
      { kind: 'overnight', from: H(20) - 1440, to: H(4) },   // 前一日 20:00 → 当日 04:00
      { kind: 'pre', from: H(4), to: H(9, 30) },
      { kind: 'open', from: H(9, 30), to: H(16) },
      { kind: 'post', from: H(16), to: H(20) },
    ],
  },
  // 韩股 KRX：只有连续竞价一段（15:20 后是收盘集合竞价，不算开盘），韩国无夏令时
  KRX: {
    tz: 'Asia/Seoul',
    tradingDays: [1, 2, 3, 4, 5],
    anchors: [{ kind: 'open', from: H(9), to: H(15, 20) }],
  },
};

export const SESSION_META: Record<SessionKind, { label: string; liquidity: string }> = {
  pre: { label: '盘前', liquidity: '高流动性' },
  open: { label: '开盘', liquidity: '高流动性' },
  post: { label: '盘后', liquidity: '中等流动性' },
  overnight: { label: '夜盘', liquidity: '中等流动性' },
  closed: { label: '非交易时段', liquidity: '低流动性' },
};

/** 图例展示顺序：按一天里的自然推进排，跟 anchors 的声明顺序无关 */
const LEGEND_ORDER: SessionKind[] = ['pre', 'open', 'post', 'overnight', 'closed'];

/** 该市场会出现的时段种类（弹窗图例只画用得上的几项） */
export function sessionKindsOf(market: MarketId): SessionKind[] {
  const has = new Set<SessionKind>([...MARKETS[market].anchors.map(a => a.kind), 'closed']);
  return LEGEND_ORDER.filter(k => has.has(k));
}

// ---------- 时区换算：Intl 取偏移量，反推 epoch ----------

const fmtCache = new Map<string, Intl.DateTimeFormat>();
function tzFormatter(tz: string): Intl.DateTimeFormat {
  let f = fmtCache.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: tz, hour12: false,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
    fmtCache.set(tz, f);
  }
  return f;
}

/** ts 时刻在 tz 的墙钟，拆成数字 */
function partsIn(ts: number, tz: string) {
  const p = tzFormatter(tz).formatToParts(new Date(ts));
  const get = (t: string) => Number(p.find(x => x.type === t)!.value);
  // en-US + hour12:false 在午夜会给出 24，归一成 0
  const hour = get('hour') % 24;
  return { y: get('year'), mo: get('month'), d: get('day'), h: hour, mi: get('minute'), s: get('second') };
}

/** tz 在 ts 时刻相对 UTC 的偏移（毫秒） */
function tzOffset(ts: number, tz: string): number {
  const p = partsIn(ts, tz);
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - ts;
}

/** 把「tz 时区的墙钟时间」解析成 epoch；二次迭代修正夏令时切换日 */
function zonedToUtc(y: number, mo: number, d: number, h: number, mi: number, tz: string): number {
  const wall = Date.UTC(y, mo - 1, d, h, mi);
  let ts = wall - tzOffset(wall, tz);
  ts = wall - tzOffset(ts, tz);
  return ts;
}

// ---------- 时间线：把锚点展开成绝对时刻的连续覆盖 ----------

export interface Segment {
  kind: SessionKind;
  start: number;
  end: number;
}

/**
 * 生成 [from, to] 范围内的完整时段序列（首尾各多铺几天保证边界完整），
 * 相邻交易时段之间的空隙补成 closed，结果连续无缝。
 */
function buildTimeline(market: MarketId, from: number, to: number): Segment[] {
  const def = MARKETS[market];
  const DAY = 86400000;
  const active: Segment[] = [];

  // 按市场时区逐日扫描（多扫 2 天覆盖跨日锚点）
  for (let ts = from - 2 * DAY; ts <= to + 2 * DAY; ts += DAY) {
    const p = partsIn(ts, def.tz);
    // 该市场时区日期是星期几：用当天正午的 UTC 值取 weekday，避开偏移干扰
    const dow = new Date(Date.UTC(p.y, p.mo - 1, p.d, 12)).getUTCDay();
    if (!def.tradingDays.includes(dow)) continue;

    for (const a of def.anchors) {
      active.push({
        kind: a.kind,
        start: anchorToTs(p.y, p.mo, p.d, a.from, def.tz),
        end: anchorToTs(p.y, p.mo, p.d, a.to, def.tz),
      });
    }
  }

  active.sort((x, y) => x.start - y.start);

  // 补空隙成 closed
  const out: Segment[] = [];
  let cursor = from - 2 * DAY;
  for (const seg of active) {
    if (seg.end <= cursor) continue;
    if (seg.start > cursor) out.push({ kind: 'closed', start: cursor, end: seg.start });
    out.push(seg);
    cursor = seg.end;
  }
  if (cursor < to + 2 * DAY) out.push({ kind: 'closed', start: cursor, end: to + 2 * DAY });
  return out;
}

/** 锚点分钟数（可跨日）→ epoch：先把日期加减到位，再按墙钟解析 */
function anchorToTs(y: number, mo: number, d: number, minutes: number, tz: string): number {
  const dayShift = Math.floor(minutes / 1440);
  const inDay = minutes - dayShift * 1440;
  const shifted = new Date(Date.UTC(y, mo - 1, d + dayShift));
  return zonedToUtc(
    shifted.getUTCFullYear(), shifted.getUTCMonth() + 1, shifted.getUTCDate(),
    Math.floor(inDay / 60), inDay % 60, tz,
  );
}

// ---------- 对外 API ----------

export interface SessionState {
  kind: SessionKind;
  /** 当前这一段的起止（弹窗标题的 "08:00 - 16:00" 用） */
  start: number;
  end: number;
  /** 已开盘时 = 本段收盘时刻；未开盘时 = 下一次开盘时刻 */
  targetAt: number;
}

export function getSessionState(market: MarketId, now: number): SessionState {
  const DAY = 86400000;
  // 周五收盘到周一开盘最长隔 ~3.5 天，往后铺 5 天足够找到下一次开盘
  const line = buildTimeline(market, now - DAY, now + 5 * DAY);
  const cur = line.find(s => now >= s.start && now < s.end) ?? line[0];
  const nextOpen = line.find(s => s.kind === 'open' && s.start > now);
  return {
    kind: cur.kind,
    start: cur.start,
    end: cur.end,
    targetAt: cur.kind === 'open' ? cur.end : (nextOpen?.start ?? cur.end),
  };
}

export interface DayRow {
  /** 本地时区当天 00:00 */
  dayStart: number;
  /** 当天内的彩条，单位=当天已过分钟数（0~1440），已裁剪 */
  bars: { kind: SessionKind; from: number; to: number }[];
}

export interface WeekView {
  rows: DayRow[];
  /** 时间轴刻度：本地时区的 minute-of-day */
  ticks: number[];
}

/** 本周（本地时区，周一起）7 行视图 + 时间轴刻度 */
export function getWeekView(market: MarketId, now: number): WeekView {
  const d = new Date(now);
  // 本地周一 00:00（getDay: 0=周日 → 回退 6 天）
  const monday = new Date(d.getFullYear(), d.getMonth(), d.getDate() - ((d.getDay() + 6) % 7));
  const weekStart = monday.getTime();
  const weekEnd = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 7).getTime();

  const line = buildTimeline(market, weekStart, weekEnd);

  const rows: DayRow[] = [];
  for (let i = 0; i < 7; i++) {
    const s = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i);
    const dayStart = s.getTime();
    const dayEnd = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i + 1).getTime();
    const bars: DayRow['bars'] = [];
    for (const seg of line) {
      if (seg.kind === 'closed') continue;              // 灰底槽本身就是非交易时段
      if (seg.end <= dayStart || seg.start >= dayEnd) continue;
      const from = Math.max(0, (Math.max(seg.start, dayStart) - dayStart) / 60000);
      const to = Math.min(1440, (Math.min(seg.end, dayEnd) - dayStart) / 60000);
      if (to > from) bars.push({ kind: seg.kind, from, to });
    }
    rows.push({ dayStart, bars });
  }

  // 刻度 = 本周内所有交易时段边界落到本地时区的 minute-of-day，去重排序
  const tickSet = new Set<number>();
  for (const seg of line) {
    if (seg.kind === 'closed') continue;
    if (seg.end <= weekStart || seg.start >= weekEnd) continue;
    for (const t of [seg.start, seg.end]) {
      const x = new Date(t);
      tickSet.add(x.getHours() * 60 + x.getMinutes());
    }
  }
  tickSet.delete(0);   // 0:00 贴在左边界，画出来会糊到行标上
  return { rows, ticks: [...tickSet].sort((a, b) => a - b) };
}

// ---------- 展示用小工具 ----------

/** 本地时区标签，如 UTC+8 / UTC+5:30 */
export function localTzLabel(): string {
  const min = -new Date().getTimezoneOffset();
  const sign = min < 0 ? '-' : '+';
  const h = Math.floor(Math.abs(min) / 60);
  const m = Math.abs(min) % 60;
  return `UTC${sign}${h}${m ? ':' + String(m).padStart(2, '0') : ''}`;
}

export function fmtHm(ts: number): string {
  const d = new Date(ts);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function fmtMinOfDay(min: number): string {
  return `${String(Math.floor(min / 60) % 24).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
}

/** 剩余时长 → "5小时46分" / "46分" / "1天3小时" */
export function fmtDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 60000));
  const d = Math.floor(total / 1440);
  const h = Math.floor((total % 1440) / 60);
  const m = total % 60;
  if (d > 0) return `${d}天${h}小时`;
  if (h > 0) return `${h}小时${m}分`;
  return `${m}分`;
}
