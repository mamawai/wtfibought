import { useEffect, useState, type ReactNode, type RefObject } from 'react';
import type { PopChunk } from '../components/Markdown';
import '../components/StreamText.css';

/** 句中停顿（逗号、顿号、冒号）处要攒够这么多字才放 */
const MIN_CLAUSE = 14;
/** 一直没等到标点，攒到这么多字也放 */
const MAX_HOLD = 72;
/** 这么久没来新字，手里攒着的先放出去，别捏着不显示 */
const FLUSH_MS = 650;
/** 还在跑但这么久没来新字：算卡住了，文末亮起呼吸点 */
const STALL_MS = 1200;
/** 最近放出来的几段一直包着：弹出动画 0.28 秒，出字再快也不会有段还没弹完就被拆包 */
const KEEP_CHUNKS = 3;
/** 弹出动画多长（StreamText.css 的 wiib-pop-rise） */
const POP_MS = 280;

const HARD_STOPS = '。！？!?；;\n';
const SOFT_STOPS = '，、：';

/**
 * 流式时放到哪儿为止：从已经放出来的位置往后，找最后一个能断开的地方。
 * 句末标点和换行随时断；句中停顿要攒够 MIN_CLAUSE 个字；英文的 . , : 后面跟着空白才算（3.5、10:30 不断）。
 * 找不到就先不放，除非已经攒了 MAX_HOLD 个字
 */
function releasePoint(content: string, shown: number): number {
  const from = Math.min(shown, content.length);
  for (let i = content.length - 1; i >= from; i--) {
    const c = content[i];
    const spaced = i + 1 < content.length && /\s/.test(content[i + 1]);
    const hard = HARD_STOPS.includes(c) || (c === '.' && spaced);
    const soft = SOFT_STOPS.includes(c) || ((c === ',' || c === ':') && spaced);
    if (hard || (soft && i + 1 - from >= MIN_CLAUSE)) return i + 1;
  }
  return content.length - from >= MAX_HOLD ? content.length : from;
}

/** 放到了哪儿、是不是正在流式，和最近放出来的几段（它们在弹出来） */
interface Released { shown: number; seq: number; live: boolean; chunks: PopChunk[] }

/** 再放一段，放到 to 为止。to 比已经放出来的还靠前＝正文从头重写了，之前那几段作废 */
function release(r: Released, to: number): Released {
  const seq = r.seq + 1;
  const kept = to < r.shown ? [] : r.chunks.slice(1 - KEEP_CHUNKS);
  return { shown: to, seq, live: r.live, chunks: [...kept, { from: Math.min(r.shown, to), to, seq }] };
}

/**
 * 流式正文的出字方式（样式在 StreamText.css，el 要带 .sx）：收到的字先攒着，攒够一段（到句末，或者句中停顿处够长了）
 * 才整段放出来，这一段一起弹出来；流结束时剩下的那截也是弹出来的。
 * 还在跑但一阵没来新字，手里的先放掉，再久一点给 el 挂 .stall，文末亮一颗小橙点慢慢呼吸，字一来就收。
 * 没在流式时正文整个换了（翻出来的历史消息、切到别的版本）直接显示，不弹。
 *
 * @returns text 现在该显示到哪儿；chunks 最近放出来的几段，渲染时把它们包成 .pop（markdown 交给 Markdown 的 pop，纯文字用 popText）
 */
export function useStreamReveal(ref: RefObject<HTMLElement | null>, content: string, streaming: boolean): { text: string; chunks: PopChunk[] } {
  const [rel, setRel] = useState<Released>(() => ({ shown: streaming ? 0 : content.length, seq: 0, live: streaming, chunks: [] }));

  const target = streaming ? releasePoint(content, rel.shown) : content.length;
  if (streaming) {
    if (target !== rel.shown) setRel(release({ ...rel, live: true }, target));
    else if (!rel.live) setRel({ ...rel, live: true });
  } else if (target !== rel.shown) {
    setRel(rel.live ? release({ ...rel, live: false }, target) : { shown: target, seq: rel.seq, live: false, chunks: [] });
  } else if (rel.live) {
    setRel({ ...rel, live: false });
  }

  // 新字到了重新计时：一阵不来就把攒着的放掉，再久就亮呼吸点
  useEffect(() => {
    const el = ref.current;
    if (!el || !streaming) return;
    el.classList.remove('stall');
    const flush = window.setTimeout(() => setRel(r => (r.shown < content.length ? release(r, content.length) : r)), FLUSH_MS);
    const stall = window.setTimeout(() => el.classList.add('stall'), STALL_MS);
    return () => {
      window.clearTimeout(flush);
      window.clearTimeout(stall);
      el.classList.remove('stall');
    };
  }, [ref, content, streaming]);

  // 流停了、最后一段弹完就拆包：一直包着的话，祖先藏起来再显示时会再弹一次
  useEffect(() => {
    if (streaming || rel.chunks.length === 0) return;
    const timer = window.setTimeout(() => setRel(r => ({ ...r, chunks: [] })), POP_MS);
    return () => window.clearTimeout(timer);
  }, [streaming, rel.chunks]);

  return { text: content.slice(0, rel.shown), chunks: rel.chunks };
}

/**
 * 不走 markdown 的纯文字：把落在 chunks 里的部分包成弹出来的行内元素。
 * start 是这段文字在整篇正文里的起点（chunks 记的是整篇里的位置）
 */
export function popText(value: string, start: number, chunks: PopChunk[]): ReactNode {
  const end = start + value.length;
  const hits = chunks.filter(c => c.from < end && c.to > start);
  if (hits.length === 0) return value;
  const out: ReactNode[] = [];
  let cursor = start;
  for (const c of hits) {
    const a = Math.max(c.from, cursor), b = Math.min(c.to, end);
    if (a > cursor) out.push(value.slice(cursor - start, a - start));
    out.push(<span key={c.seq} className="pop">{value.slice(a - start, b - start)}</span>);
    cursor = b;
  }
  if (cursor < end) out.push(value.slice(cursor - start));
  return out;
}
