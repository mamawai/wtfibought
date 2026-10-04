/**
 * 画线交互层：工具选择 → 落点 → 选中 → 拖拽 → 删除 → 存盘。
 *
 * 三个必须说清楚的设计：
 *
 * 1. **为什么用 pointerdown 而不是 mousedown**
 *    LWC 内部绑的是 mousedown/touchstart，挂在它自己的 canvas 上。想"抢"手势，
 *    靠 stopPropagation 是抢不到的（事件类型都不同）。真正管用的是：规范保证
 *    pointerdown 先于 mousedown/touchstart 触发，所以在 pointerdown 里把
 *    handleScroll/handleScale 关掉，等 LWC 收到它那份事件时平移已经被禁了。
 *    手机上再补一手 touchAction:'none'，否则拖线会带着整页一起滚。
 *
 * 2. **为什么 attach 是手动调用而不是自己开 effect**
 *    图表本体在 CandleChart 的建图 effect 里创建/销毁。若这里另开一个同依赖的
 *    effect，React 会先跑建图 effect 的 cleanup(chart.remove())、再跑这里的，
 *    那时 series 已经死了，detachPrimitive 要炸。所以把 attach/detach 交给
 *    CandleChart 在同一个 effect 内按正确顺序调。
 *
 * 3. **触屏怎么落点（触控板式十字线）**
 *    手指点哪儿哪儿就被手指挡住，没法精确落点。所以触屏上选了工具后图上出现一横一竖
 *    贯穿整图的虚线十字（DrawingLayer.cursor），手指在图上**任意位置**拖动，十字交叉点
 *    按相对位移跟着走；原地轻点 = 固定当前点。多点工具固定一点后十字留在原地接着拖下一点。
 *    期间图表自身平移/缩放锁死、LWC 自带十字线藏起来（两套十字会打架）。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  CrosshairMode, type DeepPartial, type HandleScrollOptions, type IChartApi, type ISeriesApi, type Logical,
} from 'lightweight-charts';
import i18n from '../../i18n';
import { DrawingLayer } from './DrawingLayer';
import {
  anchorToPoint, coordToTime, DRAW_COLOR, finalizePoints, loadDrawings, magnetPrice, newId, PLACE_POINTS, saveDrawings,
  type Anchor, type ChartCtx, type Drawing, type DrawingKind, type LineDash,
} from '../../lib/chartDrawings';

/** null = 选择模式（可选中/拖拽已有图形，图表照常平移缩放） */
export type Tool = DrawingKind | null;

/** 属性条看的选中图形快照：图层里的对象是就地改的，直接交给 React 它感知不到变化 */
export interface DrawSelection { id: string; kind: DrawingKind; color: string; width: number; dash: LineDash }

/** 属性条能改的几样 */
export type DrawStylePatch = Partial<Pick<Drawing, 'color' | 'width' | 'dash'>>;

/** 触屏设备：画线改走"十字线拖动+轻点固定"模式，手指不再直接点图落点 */
export const IS_COARSE = window.matchMedia('(pointer: coarse)').matches;
/** 触屏轻点判定：按下到抬起位移不超过这些像素算"点"，超过算"拖" */
const TAP_SLOP = 6;
/** 撤销最多记几步 */
const UNDO_MAX = 50;
/** 复制出来的副本往右下错开多少像素，跟原图叠在一起就看不出复制了 */
const CLONE_OFFSET = 24;

export interface AttachArgs {
  chart: IChartApi;
  series: ISeriesApi<'Candlestick'>;
  /** 图表宿主 div(absolute inset-0)：指针事件挂它，它的 rect 也是文字浮层的定位基准 */
  host: HTMLDivElement;
  symbol: string;
  ctx: ChartCtx;
  decimals: number;
  fmtTime: (t: number) => string;
  /** 建图时那份滚动配置，拖拽结束后原样恢复 */
  scrollOpts: DeepPartial<HandleScrollOptions>;
}

interface Live extends AttachArgs {
  layer: DrawingLayer;
  /** pane0 的画布，clientX/Y → pane 局部坐标靠它；顺带把副图/价格轴天然排除在外 */
  paneBox: HTMLElement | null;
  /** 建图时的十字线模式，触屏绘制模式藏掉后按它恢复 */
  crosshairMode: CrosshairMode;
}

/**
 * 拖拽会话。t0/p0 是按下那一刻的自由坐标，整体平移按它算增量；cx0/cy0 是按下时的屏幕坐标，判起拖阈值用；
 * moved=真拖动过（只是点选不改图形、不进撤销栈）
 */
interface Drag { id: string; pt: number; t0: number; p0: number; orig: Anchor[]; moved: boolean; cx0: number; cy0: number; }

/** 触屏十字线的一次手指会话：baseX/Y=按下时十字位置，startCX/CY=按下时手指位置 */
interface Touch { pointerId: number; startCX: number; startCY: number; baseX: number; baseY: number; moved: boolean; }

export function useDrawings() {
  const [tool, setTool] = useState<Tool>(null);
  const [magnet, setMagnet] = useState(true);
  const [selection, setSelection] = useState<DrawSelection | null>(null);
  const [count, setCount] = useState(0);
  /** 隐藏全部画线（只切可见性不删数据；不持久化，进页面默认显示） */
  const [hiddenAll, setHiddenAll] = useState(false);
  /** 文字标注输入浮层的位置(相对 host)，value=改已有标注时的原文；null=没在输入 */
  const [textEdit, setTextEdit] = useState<{ x: number; y: number; value?: string } | null>(null);
  /** 撤销栈：每次改动前存一份整套画线的快照（换币种时清空，见 attach） */
  const historyRef = useRef<Drawing[][]>([]);
  const historySymbolRef = useRef<string | null>(null);
  const [undoCount, setUndoCount] = useState(0);
  /** 当前这一笔已落定几个点（给绘制提示条显示进度）；跟 placedRef 同步 */
  const [placed, setPlaced] = useState(0);

  const liveRef = useRef<Live | null>(null);
  const toolRef = useRef<Tool>(null);
  const magnetRef = useRef(true);
  const hiddenRef = useRef(false);
  const dragRef = useRef<Drag | null>(null);
  /** 拖拽期挂在 window 上的那对监听，存下来才摘得掉（add/remove 必须同一个函数对象） */
  const dragHandlersRef = useRef<{ move: (e: PointerEvent) => void; up: () => void } | null>(null);
  /** 多点工具已落定的点（不含正跟着指针的预览点） */
  const placedRef = useRef<Anchor[]>([]);
  const textAnchorRef = useRef<Anchor | null>(null);
  /** 触屏十字线正在被拖的手指会话 */
  const touchRef = useRef<Touch | null>(null);

  useEffect(() => { magnetRef.current = magnet; }, [magnet]);

  // 切语言后画布上的中文标签（止盈/止损/多空/根数）要重画：canvas 不跟 React 重渲染走，
  // 而下面那些回调必须保持引用稳定（add/removeEventListener 要配对），词表不能进它们的依赖
  useEffect(() => {
    const redraw = () => liveRef.current?.layer.update();
    i18n.on('languageChanged', redraw);
    return () => { i18n.off('languageChanged', redraw); };
  }, []);

  // 眼睛开关：同步图层 + 清选中都在事件回调里做（不进 effect，避免级联渲染）
  const setHiddenAllSync = useCallback((v: boolean) => {
    setHiddenAll(v);
    hiddenRef.current = v;
    // 画到一半点了隐藏：这一笔作罢，不然落定的新线看不见却被选中着
    if (v) { setTool(null); setPlaced(0); }
    const live = liveRef.current;
    if (!live) return;
    live.layer.hidden = v;
    if (v && live.layer.selectedId) {
      live.layer.selectedId = null;   // 看不见的线不该保持选中态
      setSelection(null);
    }
    live.layer.update();
  }, []);

  // ---------- 基础换算 ----------
  // 下面这些 helper 一律 useCallback([])：它们只读 ref 和稳定 setter，本就没有响应式依赖。
  // 稳定是硬要求 —— 指针handler 要靠同一个函数对象 add/removeEventListener 配对，
  // 一旦每次渲染换新引用，卸载时就摘不掉旧监听。

  /** pane0 画布元素（懒取：pane 的 DOM 是渲染流程里才建的，同 CandleChart makeLegend 的注释） */
  const paneEl = useCallback((live: Live) => {
    if (!live.paneBox) {
      const el = live.chart.panes()[0]?.getHTMLElement() ?? null;
      live.paneBox = el?.querySelector('canvas') ?? el;
    }
    return live.paneBox;
  }, []);

  /** clientX/Y → pane 局部坐标。inside=false 表示落在副图/价格轴上，不归画线管 */
  const localPt = useCallback((live: Live, clientX: number, clientY: number) => {
    const r = paneEl(live)?.getBoundingClientRect();
    if (!r) return null;
    const x = clientX - r.left, y = clientY - r.top;
    return { x, y, inside: x >= 0 && x <= r.width && y >= 0 && y <= r.height };
  }, [paneEl]);

  /** pane 局部坐标 → host 坐标（文字输入浮层按 host 定位） */
  const paneToHost = useCallback((live: Live, x: number, y: number) => {
    const pr = paneEl(live)?.getBoundingClientRect(), hr = live.host.getBoundingClientRect();
    if (!pr) return { x, y };
    return { x: x + pr.left - hr.left, y: y + pr.top - hr.top };
  }, [paneEl]);

  /** 像素 → 锚点，磁吸开着就顺手吸到最近的 OHLC */
  const anchorAt = useCallback((live: Live, x: number, y: number): { a: Anchor; snapped: boolean } | null => {
    const t = coordToTime(x, live.ctx);
    if (t === null) return null;
    if (!magnetRef.current) {
      const p = live.ctx.series.coordinateToPrice(y);
      return p === null ? null : { a: { t, p }, snapped: false };
    }
    const m = magnetPrice(t, y, live.ctx);
    return m === null ? null : { a: { t, p: m.p }, snapped: m.snapped };
  }, []);

  /**
   * 锁住图表自身的平移缩放，把手势让给画线；解锁时恢复建图时那份配置。
   * 触屏绘制模式顺手把 LWC 自带十字线藏掉：图上已经有我们的落点十字，两套会打架。
   */
  const lock = useCallback((live: Live, on: boolean) => {
    live.chart.applyOptions({
      handleScroll: on ? false : live.scrollOpts,
      handleScale: !on,
      crosshair: { mode: on && IS_COARSE && toolRef.current ? CrosshairMode.Hidden : live.crosshairMode },
    });
    live.host.style.touchAction = on ? 'none' : '';
  }, []);

  const persist = useCallback((live: Live) => {
    saveDrawings(live.symbol, live.layer.drawings);
    setCount(live.layer.drawings.length);
  }, []);

  /** 选中图形变了（换了一个/改了样式/删了）就同步给属性条 */
  const syncSel = useCallback((live: Live) => {
    const d = live.layer.drawings.find(x => x.id === live.layer.selectedId);
    setSelection(d ? { id: d.id, kind: d.kind, color: d.color, width: d.width ?? 1, dash: d.dash ?? 'solid' } : null);
  }, []);

  /** 改动前调：把改之前的整套画线压进撤销栈 */
  const remember = useCallback((live: Live) => {
    const h = historyRef.current;
    h.push(structuredClone(live.layer.drawings));
    if (h.length > UNDO_MAX) h.shift();
    setUndoCount(h.length);
  }, []);

  /** 撤销上一步。正在落点/拖拽时不撤：那一步还没落定，撤的会是再前一步 */
  const undo = useCallback(() => {
    const live = liveRef.current;
    if (!live || placedRef.current.length || dragRef.current) return;
    const prev = historyRef.current.pop();
    if (!prev) return;
    setUndoCount(historyRef.current.length);
    const L = live.layer;
    L.drawings = prev;
    if (!prev.some(d => d.id === L.selectedId)) L.selectedId = null;
    syncSel(live);
    persist(live);
    L.update();
  }, [syncSel, persist]);

  /** 一次绘制结束（无论落定还是取消）：清预览、退回选择模式 */
  const endDraw = useCallback((live: Live) => {
    placedRef.current = [];
    setPlaced(0);
    live.layer.pending = null;
    live.layer.snap = null;
    setTool(null);
    live.layer.update();
  }, []);

  const commit = useCallback((live: Live, kind: DrawingKind, pts: Anchor[], text?: string) => {
    remember(live);
    const d: Drawing = { id: newId(), kind, pts, color: DRAW_COLOR, ...(text ? { text } : {}) };
    live.layer.drawings.push(d);
    live.layer.selectedId = d.id;
    syncSel(live);
    persist(live);
    live.layer.update();
  }, [remember, syncSel, persist]);

  const dropSelected = useCallback((live: Live) => {
    const L = live.layer;
    remember(live);
    L.drawings = L.drawings.filter(d => d.id !== L.selectedId);
    L.selectedId = null;
    setSelection(null);
    persist(live);
    L.update();
  }, [remember, persist]);

  /** 中央"已完成"提示（触屏用）：淡入停留后自删，纯装饰不进 React 树 */
  const flashDone = useCallback((host: HTMLElement) => {
    const tip = document.createElement('div');
    tip.textContent = i18n.t('market:draw.done');
    Object.assign(tip.style, {
      position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%,-50%)',
      zIndex: '8', padding: '8px 18px', borderRadius: '10px',
      background: 'rgba(23,24,26,.82)', color: '#fff',
      font: '700 14px/1 system-ui, sans-serif', letterSpacing: '.05em',
      opacity: '0', transition: 'opacity .18s ease', pointerEvents: 'none',
    } as Partial<CSSStyleDeclaration>);
    host.appendChild(tip);
    requestAnimationFrame(() => { tip.style.opacity = '1'; });
    setTimeout(() => {
      tip.style.opacity = '0';
      setTimeout(() => tip.remove(), 220);
    }, 900);
  }, []);

  // ---------- 落点（鼠标点击 / 触屏轻点共用） ----------

  /** 预览点跟随：多点工具已落 k 个点时，pending 的第 k+1 个点跟着指针/十字走 */
  const preview = useCallback((live: Live, r: { a: Anchor; snapped: boolean }) => {
    const L = live.layer, t = toolRef.current;
    L.snap = r.snapped ? r.a : null;
    if (t && placedRef.current.length > 0) {
      L.pending = { id: '_pending', kind: t, pts: finalizePoints(t, [...placedRef.current, r.a]), color: DRAW_COLOR };
    }
    L.update();
  }, []);

  /**
   * 落一个点。单点工具一击即成；多点工具攒够 PLACE_POINTS 才 commit（仓位工具的止盈由
   * finalizePoints 派生），没攒够就起/更新预览。返回 true=这一笔落定了。
   */
  const place = useCallback((live: Live, r: { a: Anchor; snapped: boolean }) => {
    const t = toolRef.current;
    if (!t) return false;
    const L = live.layer;
    if (t === 'text') {
      // 输入框直接摆在落定后文字出现的地方（锚点取整到 bar、磁吸过，文字在它右侧 15px 起），提交那一下不跳
      const q = anchorToPoint(r.a, live.ctx);
      if (!q) return false;
      const h = paneToHost(live, q.x + 15, q.y);
      textAnchorRef.current = r.a;
      setTextEdit({ x: h.x, y: h.y });
      endDraw(live);
      return true;
    }
    const need = PLACE_POINTS[t];
    if (need === 1) {
      commit(live, t, [r.a]);
      endDraw(live);
      return true;
    }
    placedRef.current.push(r.a);
    if (placedRef.current.length >= need) {
      commit(live, t, finalizePoints(t, placedRef.current));
      endDraw(live);
      return true;
    }
    setPlaced(placedRef.current.length);
    L.pending = { id: '_pending', kind: t, pts: finalizePoints(t, [...placedRef.current, r.a]), color: DRAW_COLOR };
    L.snap = r.snapped ? r.a : null;
    L.update();
    return false;
  }, [commit, endDraw, paneToHost]);

  // ---------- 触屏十字线 ----------

  /** 十字线定位 + 联动预览/磁吸提示 */
  const moveCursor = useCallback((live: Live, x: number, y: number) => {
    const L = live.layer;
    L.cursor = { x, y };
    const r = anchorAt(live, x, y);
    if (r) preview(live, r);
    else L.update();
  }, [anchorAt, preview]);

  /** 进入触屏绘制模式：十字线摆到主图中央 */
  const showCursor = useCallback((live: Live) => {
    const r = paneEl(live)?.getBoundingClientRect();
    moveCursor(live, (r?.width ?? live.host.clientWidth) / 2, (r?.height ?? live.host.clientHeight) / 2);
  }, [paneEl, moveCursor]);

  const hideCursor = useCallback((live: Live) => {
    touchRef.current = null;
    if (!live.layer.cursor) return;
    live.layer.cursor = null;
    live.layer.update();
  }, []);

  /** 触屏轻点：把十字交叉点固定为当前工具的下一个点 */
  const tapFix = useCallback((live: Live) => {
    const cur = live.layer.cursor;
    if (!cur) return;
    const r = anchorAt(live, cur.x, cur.y);
    if (!r) return;                              // 十字停在价格轴/副图上，点了不算
    // 文字工具这一点只是开始打字，不算画完
    const t = toolRef.current;
    if (place(live, r) && t !== 'text') flashDone(live.host);
  }, [anchorAt, place, flashDone]);

  // ---------- 指针事件 ----------

  /** 摘掉拖拽期挂在 window 上的三个监听。单独抽出来，省得 onWinUp 自引用 */
  const stopDrag = useCallback(() => {
    const h = dragHandlersRef.current;
    if (!h) return;
    window.removeEventListener('pointermove', h.move, true);
    window.removeEventListener('pointerup', h.up, true);
    window.removeEventListener('pointercancel', h.up, true);
    dragHandlersRef.current = null;
  }, []);

  const onWinMove = useCallback((e: PointerEvent) => {
    const live = liveRef.current, drag = dragRef.current;
    if (!live || !drag) return;
    const pt = localPt(live, e.clientX, e.clientY);
    if (!pt) return;
    const L = live.layer;
    const d = L.drawings.find(x => x.id === drag.id);
    if (!d) return;
    if (!drag.moved) {
      // 没挪出 TAP_SLOP 算点选：手指/鼠标的微抖不该挪动图形，也不该记一步撤销
      if (Math.hypot(e.clientX - drag.cx0, e.clientY - drag.cy0) <= TAP_SLOP) return;
      drag.moved = true;
      remember(live);
    }

    if (drag.pt >= 0) {
      const r = anchorAt(live, pt.x, pt.y);
      if (!r) return;
      if (d.kind === 'long' || d.kind === 'short') {
        // 仓位工具约定：止盈点 t 恒等于止损点 t（区间右缘）。拖止盈只改价、拖止损把止盈的 t 一起带走
        if (drag.pt === 2) d.pts[2] = { t: d.pts[1].t, p: r.a.p };
        else if (drag.pt === 1) { d.pts[1] = r.a; d.pts[2] = { t: r.a.t, p: d.pts[2].p }; }
        else d.pts[0] = r.a;
      } else {
        d.pts[drag.pt] = r.a;
      }
      L.snap = r.snapped ? r.a : null;
    } else {
      // 整体平移不吸附：每根都吸会拖得一跳一跳。横向仍按整根 bar 走(coordToTime 已取整)
      const t = coordToTime(pt.x, live.ctx), p = live.ctx.series.coordinateToPrice(pt.y);
      if (t === null || p === null) return;
      const dt = t - drag.t0, dp = p - drag.p0;
      d.pts = drag.orig.map(a => ({ t: a.t + dt, p: a.p + dp }));
      L.snap = null;
    }
    L.update();
  }, [localPt, anchorAt, remember]);

  const onWinUp = useCallback(() => {
    stopDrag();
    const live = liveRef.current;
    const moved = dragRef.current?.moved === true;
    dragRef.current = null;
    if (!live) return;
    if (moved) persist(live);
    live.layer.snap = null;
    lock(live, toolRef.current !== null);   // 还在绘制模式就继续锁着
    live.layer.update();
  }, [stopDrag, persist, lock]);

  /**
   * host 上的指针移动：触屏绘制模式 = 拖十字线（相对位移）；
   * 鼠标 = 悬停预览，只在"选了工具"或"画到一半"时接管，其余交给 LWC 走十字线
   */
  const onMove = useCallback((e: PointerEvent) => {
    const live = liveRef.current;
    if (!live) return;
    const touch = touchRef.current;
    if (touch) {
      if (e.pointerId !== touch.pointerId) return;
      const dx = e.clientX - touch.startCX, dy = e.clientY - touch.startCY;
      if (Math.hypot(dx, dy) > TAP_SLOP) touch.moved = true;
      if (!touch.moved) return;     // 还在轻点的抖动范围里：十字不动，轻点就固定在原处
      const r = paneEl(live)?.getBoundingClientRect();
      const w = r?.width ?? live.host.clientWidth, h = r?.height ?? live.host.clientHeight;
      moveCursor(live, Math.min(Math.max(touch.baseX + dx, 0), w), Math.min(Math.max(touch.baseY + dy, 0), h));
      return;
    }
    if (live.layer.cursor) return;   // 触屏十字线模式下 host 上的悬停不算数
    if (!toolRef.current && placedRef.current.length === 0) return;
    const pt = localPt(live, e.clientX, e.clientY);
    if (!pt) return;
    const L = live.layer;
    if (!pt.inside) { if (L.snap) { L.snap = null; L.update(); } return; }
    const r = anchorAt(live, pt.x, pt.y);
    if (!r) return;
    preview(live, r);
  }, [paneEl, moveCursor, localPt, anchorAt, preview]);

  const onDown = useCallback((e: PointerEvent) => {
    const live = liveRef.current;
    if (!live) return;
    const L = live.layer, t = toolRef.current;
    // 点到图上就让输入框失焦（文字标注也靠这次失焦提交）：下面多半会 preventDefault 压掉兼容 mousedown，
    // 手机上 LWC 的轻点也会压，焦点不会自己走，Delete/Ctrl+Z 就一直被输入框吃掉
    const ae = document.activeElement;
    if (ae instanceof HTMLElement && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) ae.blur();

    // 触屏绘制模式：整块图是十字线的触控板，按下只是记起点，抬起时按"动没动"分拖/点
    if (L.cursor) {
      if (touchRef.current) return;   // 第二根手指不理
      e.preventDefault();
      live.host.setPointerCapture(e.pointerId);
      touchRef.current = { pointerId: e.pointerId, startCX: e.clientX, startCY: e.clientY, baseX: L.cursor.x, baseY: L.cursor.y, moved: false };
      return;
    }

    const pt = localPt(live, e.clientX, e.clientY);
    if (!pt || !pt.inside) return;

    if (t) {
      const r = anchorAt(live, pt.x, pt.y);
      if (!r) return;
      e.preventDefault();                      // 压掉兼容鼠标事件，LWC 的 mousedown 不会触发
      place(live, r);
      return;
    }

    // 选择模式：主动 hitTest 而不是等 LWC 的 hover —— 触摸端没有 hover 前置态
    const hit = L.pick(pt.x, pt.y);
    if (!hit) {
      if (L.selectedId) { L.selectedId = null; setSelection(null); L.update(); }
      return;                                  // 没点中就放行，图表照常平移缩放
    }
    // 手机：按中还没选中的图形只选中、不起拖。斐波/水平线/大矩形铺满大片主图，手指一落上去就拖线的话页面就翻不动了；
    // 选中之后再按住才拖。事件放行，滑动照常翻页/平移
    if (e.pointerType === 'touch' && L.selectedId !== hit.id) {
      L.selectedId = hit.id;
      syncSel(live);
      L.update();
      return;
    }
    e.preventDefault();
    lock(live, true);
    L.selectedId = hit.id;
    syncSel(live);
    const d = L.drawings.find(x => x.id === hit.id);
    const t0 = coordToTime(pt.x, live.ctx), p0 = live.ctx.series.coordinateToPrice(pt.y);
    dragRef.current = (d && t0 !== null && p0 !== null)
      ? { id: hit.id, pt: hit.pt, t0, p0, orig: d.pts.map(a => ({ ...a })), moved: false, cx0: e.clientX, cy0: e.clientY }
      : null;
    // 挂 window 而不是 host：手指/鼠标拖出图表范围也要跟得住
    const h = { move: onWinMove, up: onWinUp };
    dragHandlersRef.current = h;
    window.addEventListener('pointermove', h.move, true);
    window.addEventListener('pointerup', h.up, true);
    window.addEventListener('pointercancel', h.up, true);
    L.update();
  }, [localPt, anchorAt, lock, place, syncSel, onWinMove, onWinUp]);

  /** 触屏手指抬起：没动过 = 轻点固定；动过 = 只是拖十字线，松手不固定 */
  const onUp = useCallback((e: PointerEvent) => {
    const live = liveRef.current, touch = touchRef.current;
    if (!live || !touch || e.pointerId !== touch.pointerId) return;
    touchRef.current = null;
    if (e.type === 'pointerup' && !touch.moved) tapFix(live);
  }, [tapFix]);

  const onKey = useCallback((e: KeyboardEvent) => {
    const live = liveRef.current;
    if (!live) return;
    const el = document.activeElement;
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) return;   // 正在打字，别抢键
    if (e.key === 'Escape') {
      if (placedRef.current.length || toolRef.current) endDraw(live);
      else if (live.layer.selectedId) { live.layer.selectedId = null; setSelection(null); live.layer.update(); }
      return;
    }
    if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'z') {
      e.preventDefault();
      undo();
      return;
    }
    if ((e.key === 'Delete' || e.key === 'Backspace') && live.layer.selectedId) {
      e.preventDefault();
      dropSelected(live);
    }
  }, [endDraw, dropSelected, undo]);

  /** 改选中的文字标注：输入框带着原文盖到它原来的位置（文字在锚点右侧 15px 起） */
  const editText = useCallback(() => {
    const live = liveRef.current;
    if (!live) return;
    const L = live.layer, d = L.drawings.find(x => x.id === L.selectedId);
    if (d?.kind !== 'text') return;
    const q = anchorToPoint(d.pts[0], live.ctx);
    if (!q) return;
    const h = paneToHost(live, q.x + 15, q.y);
    L.editingId = d.id;
    L.update();
    setTextEdit({ x: h.x, y: h.y, value: d.text ?? '' });
  }, [paneToHost]);

  /** 桌面双击文字标注 = 改字（手机走属性条上的编辑钮） */
  const onDbl = useCallback((e: MouseEvent) => {
    const live = liveRef.current;
    if (!live || toolRef.current) return;
    const pt = localPt(live, e.clientX, e.clientY);
    if (!pt?.inside) return;
    const hit = live.layer.pick(pt.x, pt.y);
    if (!hit || live.layer.drawings.find(x => x.id === hit.id)?.kind !== 'text') return;
    live.layer.selectedId = hit.id;
    syncSel(live);
    editText();
  }, [localPt, syncSel, editText]);

  // ---------- 挂载 / 卸载 ----------

  /** 由 CandleChart 在建图 effect 内调用，返回 detach（必须在 chart.remove() 之前调） */
  const attach = useCallback((a: AttachArgs) => {
    const layer = new DrawingLayer(a.ctx, { decimals: a.decimals, fmtTime: a.fmtTime });
    layer.drawings = loadDrawings(a.symbol);
    layer.interactive = true;
    layer.hidden = hiddenRef.current;   // 换 symbol/周期重挂时保持当前眼睛状态
    a.series.attachPrimitive(layer);
    const live: Live = {
      ...a, layer, paneBox: null,
      crosshairMode: a.chart.options().crosshair.mode ?? CrosshairMode.Normal,
    };
    liveRef.current = live;

    setTool(null);                     // 换 symbol/周期时不该还端着上一手的笔
    setSelection(null);
    setCount(layer.drawings.length);
    setTextEdit(null);
    textAnchorRef.current = null;
    placedRef.current = [];
    setPlaced(0);
    touchRef.current = null;
    // 撤销栈跟着币种走：开关副图、切主题、切周期也会重挂图层，同一个币的画线没变，撤销记录留着
    if (historySymbolRef.current !== a.symbol) {
      historySymbolRef.current = a.symbol;
      historyRef.current = [];
      setUndoCount(0);
    }

    // 手机拖已有图形：pointerdown 里才设 touch-action 已经晚了，浏览器照样滚页面、再 pointercancel 掉拖拽。
    // 拖拽或拖十字线期间直接拦掉 touchmove 的默认滚动（必须非 passive 才拦得住）
    const onTouchMove = (e: TouchEvent) => { if (dragRef.current || touchRef.current) e.preventDefault(); };

    a.host.addEventListener('pointerdown', onDown, true);
    a.host.addEventListener('pointermove', onMove, true);
    a.host.addEventListener('pointerup', onUp, true);
    a.host.addEventListener('pointercancel', onUp, true);
    a.host.addEventListener('dblclick', onDbl, true);
    a.host.addEventListener('touchmove', onTouchMove, { passive: false });
    window.addEventListener('keydown', onKey);

    return () => {
      a.host.removeEventListener('pointerdown', onDown, true);
      a.host.removeEventListener('pointermove', onMove, true);
      a.host.removeEventListener('pointerup', onUp, true);
      a.host.removeEventListener('pointercancel', onUp, true);
      a.host.removeEventListener('dblclick', onDbl, true);
      a.host.removeEventListener('touchmove', onTouchMove);
      window.removeEventListener('keydown', onKey);
      // 卸载时可能正拖着：只摘监听，别走 onWinUp（那会去碰马上要被 remove 的 chart）
      stopDrag();
      dragRef.current = null;
      touchRef.current = null;
      a.host.style.touchAction = '';
      a.host.style.cursor = '';
      a.series.detachPrimitive(layer);
      liveRef.current = null;
    };
  }, [onDown, onMove, onUp, onDbl, onKey, stopDrag]);

  // 工具切换：锁图表、改光标、关掉命中判定（画新线时不该被旧线抢走光标）
  useEffect(() => {
    toolRef.current = tool;
    const live = liveRef.current;
    if (!live) return;
    live.layer.interactive = tool === null;
    live.host.style.cursor = tool ? 'crosshair' : '';
    // 换工具（含退出）就清掉画了一半的点：不然上一个工具落的点会被带进新工具，存出点数不对的图形
    placedRef.current = [];
    live.layer.pending = null;
    live.layer.snap = null;
    // 触屏：进入绘制模式即出十字线；退出（含 Esc/完成）即收。先收再 lock：lock 按 cursor 决定藏不藏 LWC 十字
    if (IS_COARSE && tool) showCursor(live);
    if (!tool) hideCursor(live);
    lock(live, tool !== null);
    live.layer.update();
  }, [tool, lock, showCursor, hideCursor]);

  // ---------- 工具条动作 ----------

  /** 有选中删选中；没选中则清空全部（问一句，一键抹掉几十条太狠） */
  const trash = useCallback(() => {
    const live = liveRef.current;
    if (!live) return;
    const L = live.layer;
    if (L.selectedId) { dropSelected(live); return; }
    if (!L.drawings.length) return;
    if (!window.confirm(i18n.t('market:draw.clearConfirm', { symbol: live.symbol, count: L.drawings.length }))) return;
    remember(live);
    L.drawings = [];
    persist(live);
    L.update();
  }, [dropSelected, remember, persist]);

  /**
   * 文字输入收尾（回车/失焦）。改已有标注：清空算不改、原样放回（删标注走删除键）；
   * 回车之后输入框卸载还会再触发一次失焦，那时 editingId 和锚点都已清掉，空转
   */
  const commitText = useCallback((v: string) => {
    const live = liveRef.current, a = textAnchorRef.current;
    setTextEdit(null);
    textAnchorRef.current = null;
    if (!live) return;
    const L = live.layer, text = v.trim();
    const editing = L.drawings.find(x => x.id === L.editingId);
    if (editing) {
      L.editingId = null;
      if (text && text !== editing.text) {
        remember(live);
        editing.text = text;
        persist(live);
      }
      L.update();
      return;
    }
    if (a && text) commit(live, 'text', [a], text);
  }, [commit, remember, persist]);

  const cancelText = useCallback(() => {
    setTextEdit(null);
    textAnchorRef.current = null;
    const live = liveRef.current;
    if (live?.layer.editingId) {
      live.layer.editingId = null;
      live.layer.update();
    }
  }, []);

  /** 属性条改颜色/线宽/线型，作用于选中的那个 */
  const setStyle = useCallback((patch: DrawStylePatch) => {
    const live = liveRef.current;
    if (!live) return;
    const d = live.layer.drawings.find(x => x.id === live.layer.selectedId);
    if (!d) return;
    // 点的就是当前值：什么都不变，别平白多一步撤销
    if ((patch.color === undefined || patch.color === d.color)
      && (patch.width === undefined || patch.width === (d.width ?? 1))
      && (patch.dash === undefined || patch.dash === (d.dash ?? 'solid'))) return;
    remember(live);
    Object.assign(d, patch);
    persist(live);
    syncSel(live);
    live.layer.update();
  }, [remember, persist, syncSel]);

  /** 复制选中的：副本往右下错开 CLONE_OFFSET 像素（横向按整根 bar 错），选中副本方便接着拖 */
  const cloneSelected = useCallback(() => {
    const live = liveRef.current;
    if (!live) return;
    const L = live.layer, src = L.drawings.find(x => x.id === L.selectedId);
    if (!src) return;
    remember(live);
    const ts = live.ctx.timeScale;
    const a0 = ts.logicalToCoordinate(0 as Logical), a1 = ts.logicalToCoordinate(1 as Logical);
    const spacing = a0 !== null && a1 !== null ? a1 - a0 : 0;
    const dt = Math.max(1, spacing > 0 ? Math.round(CLONE_OFFSET / spacing) : 1) * live.ctx.bucketSec;
    const pts = src.pts.map(a => {
      const y = live.ctx.series.priceToCoordinate(a.p);
      const p = y === null ? null : live.ctx.series.coordinateToPrice(y + CLONE_OFFSET);
      return { t: a.t + dt, p: p ?? a.p };
    });
    const copy: Drawing = { ...structuredClone(src), id: newId(), pts };
    L.drawings.push(copy);
    L.selectedId = copy.id;
    syncSel(live);
    persist(live);
    L.update();
  }, [remember, syncSel, persist]);

  const deleteSelected = useCallback(() => {
    const live = liveRef.current;
    if (live?.layer.selectedId) dropSelected(live);
  }, [dropSelected]);

  /** 工具条入口：选画线工具时若线被藏着，自动把眼睛打开（画完看不见太诡异） */
  const selectTool = useCallback((t: Tool) => {
    if (t !== null && hiddenRef.current) setHiddenAllSync(false);
    // 换了工具，画了一半的点会被下面的工具切换 effect 清掉，进度跟着归零；点的还是同一个就接着画
    if (t !== toolRef.current) setPlaced(0);
    setTool(t);
  }, [setHiddenAllSync]);

  return {
    attach,
    tool, setTool: selectTool,
    /** 当前这一笔已落定的点数 */
    placed,
    magnet, setMagnet,
    hiddenAll, setHiddenAll: setHiddenAllSync,
    /** 当前有选中的图形 → 删除按钮是"删选中"，否则是"清空全部" */
    selected: selection !== null,
    /** 选中图形的样式快照，属性条按它显示；null=没选中 */
    selection,
    setStyle, cloneSelected, deleteSelected, editText,
    undo, canUndo: undoCount > 0,
    count,
    trash,
    textEdit, commitText, cancelText,
  };
}

export type DrawingsApi = ReturnType<typeof useDrawings>;
