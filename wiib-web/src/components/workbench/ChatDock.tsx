import { useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bot, KeyRound, Loader2, X } from 'lucide-react';
import { llmEndpointApi } from '../../api';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { ChatPanel } from './ChatPanel';
import { HUB_NAME } from './chatView';

const SIZE_KEY = 'wiib-chatdock-size';
const BALL_KEY = 'wiib-chatdock-ball';
const MIN_W = 320, MAX_W = 760, MIN_H = 420;
type Size = { w: number; h: number };
type Axis = 'x' | 'y' | 'xy';

/** 球停在哪：横向只有贴左/贴右两种，竖向存比例——存像素的话换个窗口高度就跑到视口外了 */
type BallPos = { side: 'left' | 'right'; yRatio: number };

/**
 * 位移超过它才算拖动；一旦算过就不回退，手抖着挪回原点也不该当成点击。
 * 8 是照触屏的 tap slop 定的：给 4 的话手指落下时的自然滑动就会被判成拖，
 * 而那点位移吸完边球根本没动、click 又被吞，用户看到的是"点了没反应"。
 */
const DRAG_THRESHOLD = 8;

/** 拖完补的那个 click 在这段时间内到达就吞掉。用时间戳不用布尔：补不出 click 的路径
 *  （拖出元素、触摸被取消）会把布尔标记一直留着，下一次键盘 Enter 就被冤枉吞掉 */
const CLICK_SWALLOW_MS = 300;

/** 开像从 Dock 图标里放大出来，关像被吸回去。收回要看得清是「一路缩过去」，
 *  所以比打开还长一点——短了就成了原地消失，看不出面板去了哪 */
const OPEN_MS = 400, CLOSE_MS = 440;

const canAnimateDock = () => typeof Element.prototype.animate === 'function'
  && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** 反向关闭时接住正在显示的那一帧，不能先跳到完整尺寸再缩回去。 */
function motionFrame(element: HTMLElement) {
  const style = getComputedStyle(element);
  return { transform: style.transform, opacity: style.opacity, borderRadius: style.borderRadius };
}

/** 面板尺寸的 CSS 上限用的是 rem（max-w-[calc(100vw-2rem)] / max-h-[calc(100vh-6.5rem)]），
 *  JS 侧的钳制得用同一把尺子，写死 px 在 PC(17px 根字号) 下会差出十几像素 */
const DOCK_MAX_W_GAP_REM = 2, DOCK_MAX_H_GAP_REM = 6.5;

function rootFontSize() {
  return parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
}

function loadSize(): Size {
  try {
    const s = JSON.parse(localStorage.getItem(SIZE_KEY) || '') as Size;
    if (typeof s.w === 'number' && typeof s.h === 'number') return s;
  } catch { /* 没存过/存坏了都用默认 */ }
  return { w: 400, h: 672 };
}

function loadBall(): BallPos {
  try {
    const b = JSON.parse(localStorage.getItem(BALL_KEY) || '') as BallPos;
    if ((b.side === 'left' || b.side === 'right') && typeof b.yRatio === 'number') {
      return { side: b.side, yRatio: Math.min(Math.max(b.yRatio, 0), 1) };
    }
  } catch { /* 没存过/存坏了都用默认 */ }
  return { side: 'right', yRatio: 1 };   // 右下角：悬浮入口的常规落点，压不着主内容
}

/**
 * 球的直径与活动范围，随视口和根字号实时算。
 * <p>
 * 尺寸不写死 px：球是 w-12(3rem)、边距对齐 right-4(1rem)，而 PC 根字号是 17px——
 * 按 48/16 算的话球会比实际小 3px，贴边永远差一截。
 */
function ballBounds() {
  const rem = rootFontSize();
  const size = rem * 3, edge = rem;
  // 移动端底部压着导航栏（Layout 里 fixed bottom-0 那条，高度随内容+安全区走），
  // 给一段宽裕的留白让开；PC 没有导航，只留视觉边距
  const bottom = window.matchMedia('(min-width: 1024px)').matches ? edge * 1.5 : rem * 5;
  return {
    size,
    minX: edge,
    maxX: window.innerWidth - size - edge,
    minY: edge,
    maxY: window.innerHeight - bottom - size,
  };
}

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));

/**
 * 全站悬浮对话入口（Layout 挂载，登录后可见）：可拖的悬浮球 + PC 浮窗 / 移动端全屏层。
 * <p>
 * <b>球</b>：按住拖走，松手吸最近的左右边（竖向随手停），位置记 localStorage；
 * 未读脉冲环、研判中雷达扫描都是纯 CSS。开着面板时球收起来——它能停在任意高度，
 * 而面板锚点固定，两者重叠时球会盖住面板的按钮。
 * <p>
 * <b>面板</b>：跟着球换边（球在左就从左下角长出来），拖拽把手在外侧边缘、增量方向随之镜像，
 * 尺寸记 localStorage。PC 可全屏——铺满<b>浏览器视口</b>（地址栏、标签栏都还在），全屏时左边多出常驻的历史会话栏。
 * <p>
 * chatStore 是页面无关的单例，SSE 不随面板关闭中断——关掉球研判照跑，
 * 一轮跑完球亮橙点提醒；输入草稿也存在 store 里，关面板不会把正在敲的字弄丢。
 */
export function ChatDock() {
  const navigate = useNavigate();
  const { t } = useTranslation('ai');
  const { loading, items } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  const [open, setOpen] = useState(false);
  // open 管交互，present 管挂载；收回球里的动画结束后才卸载内容。
  const [present, setPresent] = useState(false);
  const motionRef = useRef<Animation | null>(null);
  const motionStartRef = useRef<ReturnType<typeof motionFrame> | null>(null);
  const [unread, setUnread] = useState(false);
  // null=首次还没查回来（面板内转圈）。每次打开都重查：用户去配置页存完回来，不用刷新页面
  const [hasConfig, setHasConfig] = useState<boolean | null>(null);
  // 订阅回调里读不到最新 state，开合状态镜像到 ref（只在事件处理器里写）
  const openRef = useRef(false);

  const [size, setSize] = useState<Size>(loadSize);
  // 拖拽全程的账都记在 ref 里（起点 + 最新值），结束时一次性落 localStorage
  const dragRef = useRef<{ axis: Axis; x: number; y: number; w: number; h: number; cur: Size } | null>(null);

  const [ball, setBall] = useState<BallPos>(loadBall);
  // 拖动中的实时像素位置；null=没在拖，位置由 ball 算出来
  const [ballDrag, setBallDrag] = useState<{ x: number; y: number } | null>(null);
  // id=这一次拖动的指针，cx/cy=拖动中的实时落点。落点同时记在 ref 里是必须的：
  // pointermove 的 setState 走 continuous lane，快速甩一下松手时它还没 flush，
  // 松手那一刻读 state 会把球吸回拖动前的位置
  const ballRef = useRef<{
    id: number; sx: number; sy: number; ox: number; oy: number; cx: number; cy: number; moved: boolean;
  } | null>(null);
  // 拖完松手浏览器还会补一个 click，记下时刻把那一下吞掉。开合仍挂在 onClick 上——
  // 键盘 Enter/Space 只触发 click，改成在 pointerup 里开合的话球就没法用键盘操作了
  const swallowClickRef = useRef(0);
  // 球的落点存的是比例，视口一变就得按新范围重算
  const [bounds, setBounds] = useState(ballBounds);
  const [desktop, setDesktop] = useState(() => window.matchMedia('(min-width: 768px)').matches);
  useEffect(() => {
    const onResize = () => {
      // 收放动画按旧的球位置和面板尺寸算的，视口一变就先让它落地
      motionRef.current?.finish();
      setBounds(ballBounds());
      setDesktop(window.matchMedia('(min-width: 768px)').matches);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const dockRef = useRef<HTMLDivElement>(null);
  const ballBtnRef = useRef<HTMLButtonElement>(null);
  // 全屏 = 铺满浏览器视口的一个 fixed 层，不走原生 Fullscreen API：那个连地址栏、标签栏一起吃掉，
  // 整块屏幕只剩对话，跟"一边看盘一边问"是反着的。K 线那边仍用原生（看图本来就该独占屏幕）
  const [fullscreen, setFullscreen] = useState(false);
  const toggleFullscreen = useCallback(() => {
    motionRef.current?.finish();
    setFullscreen(v => !v);
  }, []);

  useLayoutEffect(() => {
    const panel = dockRef.current, trigger = ballBtnRef.current;
    if (!present || !panel || !trigger || !canAnimateDock()) {
      motionStartRef.current = null;
      return;
    }

    const rect = panel.getBoundingClientRect();
    const ballRect = trigger.getBoundingClientRect();
    // 绕默认的中心缩放，再把中心平移到球心。
    // 别改成把 transform-origin 钉在球心：origin 是缩放的不动点，不是缩放后的中心，
    // 缩完中心停在 origin+(半宽-origin)×scale，400x672 的面板收到 48 的球上会差二十几像素。
    // translate 和 scale 必须写在同一条 transform 里走同一个进度，分轴分进度才会中途甩偏
    const dx = ballRect.left + ballRect.width / 2 - rect.left - rect.width / 2;
    const dy = ballRect.top + ballRect.height / 2 - rect.top - rect.height / 2;
    const scaleX = ballRect.width / Math.max(rect.width, 1);
    const scaleY = ballRect.height / Math.max(rect.height, 1);

    // 球的位置和形状。50% 的圆角在非等比缩放下也跟着压，落到球那一刻正好是个正圆
    const collapsed = {
      transform: `translate(${dx}px, ${dy}px) scale(${scaleX}, ${scaleY})`,
      borderRadius: '50%',
    };
    // 展开态也写成同构的函数列表，让两端逐函数插值，不退化成矩阵插值
    const expanded = { ...motionFrame(panel), transform: 'translate(0px, 0px) scale(1, 1)' };
    const from = motionStartRef.current ?? (open ? { ...collapsed, opacity: '0' } : expanded);
    motionStartRef.current = null;

    // 展开到一半就关掉时按当前大小折算时长，剩一小截还等满程会显得拖沓。
    // 下限给到 220：再短就看不出收的过程了
    const startScale = new DOMMatrixReadOnly(from.transform === 'none' ? undefined : from.transform).a;
    const extent = clamp((startScale - scaleX) / Math.max(1 - scaleX, .001), 0, 1);
    const closeMs = Math.round(220 + (CLOSE_MS - 220) * Math.sqrt(extent));

    const frames: Keyframe[] = open ? [
      { ...from, offset: 0 },
      // 先补齐不透明再撑开，免得背景文字从半透明的面板里透出来
      { opacity: 1, offset: .3 },
      { borderRadius: expanded.borderRadius, offset: .5 },
      { ...expanded, offset: 1 },
    ] : [
      { ...from, offset: 0 },
      // 早早开始化圆，外壳全程实心地缩。末帧就是球的样子，不淡出——
      // 一淡就露出底下的球，成了「球先冒出来、面板还在缩」
      { borderRadius: from.borderRadius, offset: .3 },
      { ...collapsed, opacity: from.opacity, offset: 1 },
    ];
    const animation = panel.animate(frames, {
      duration: open ? OPEN_MS : closeMs,
      // 开：先窜出大半再慢慢贴到位。关：前段留出起势，中段收，末段贴住球停下。
      // 关这条不能用先慢后猛的 ease-in——大半路程挤在最后一瞬走完，看着就是原地变没
      easing: open ? 'cubic-bezier(.25, .8, .25, 1)' : 'cubic-bezier(.55, 0, .35, 1)',
      fill: 'both',
    });
    motionRef.current = animation;

    animation.onfinish = () => {
      if (motionRef.current !== animation || openRef.current !== open) return;
      motionRef.current = null;
      motionStartRef.current = null;
      if (open) {
        animation.cancel();
      } else {
        setPresent(false);
        // 全屏尺寸保留到收回结束，否则关闭第一帧会先跳回小窗。
        setFullscreen(false);
      }
    };

    return () => {
      if (motionRef.current === animation) {
        motionStartRef.current = motionFrame(panel);
        motionRef.current = null;
      }
      animation.cancel();
    };
  }, [open, present]);

  // 球要等收回结束才可聚焦；首挂不抢焦点，重新出现后再接回来。
  const wasPresentRef = useRef(false);
  useEffect(() => {
    if (open) dockRef.current?.focus({ preventScroll: true });
    // 点外部输入框关闭时，保留用户刚移过去的焦点。
    else if (!present && wasPresentRef.current && document.activeElement === document.body) {
      ballBtnRef.current?.focus({ preventScroll: true });
    }
    wasPresentRef.current = present;
  }, [open, present]);

  // 面板关着时一轮研判跑完（loading 真→假）→ 气泡亮橙点
  useEffect(() => {
    let prev = chatStore.getSnapshot().loading;
    return chatStore.subscribe(() => {
      const now = chatStore.getSnapshot().loading;
      if (prev && !now && !openRef.current) setUnread(true);
      prev = now;
    });
  }, []);

  // 无动效偏好下直接开关；其余关闭路径统一等退出动画收尾。
  const setOpenBoth = useCallback((v: boolean) => {
    openRef.current = v;
    setOpen(v);
    if (v) setPresent(true);
    else {
      dragRef.current = null;
      if (!canAnimateDock()) {
        setPresent(false);
        setFullscreen(false);
      }
    }
  }, []);

  const toggle = useCallback(() => {
    const next = !openRef.current;
    if (next) {
      setUnread(false);
      llmEndpointApi.list().then(list => setHasConfig(list.length > 0)).catch(() => setHasConfig(false));
    }
    setOpenBoth(next);
  }, [setOpenBoth]);

  // 面板里有没有没填完的表单卡：它的草稿在卡自己的组件 state 里，关面板就没了，
  // 所以这时候不给 ESC / 点外部这两条"顺手关掉"的路。输入框草稿另有 store 兜着，不受影响
  const hasPendingForm = items.some(it => it.kind === 'form' && it.status === 'pending');

  // ESC 一级一级退：全屏时先回浮窗，浮窗态再按才关面板。不走原生全屏之后浏览器不再帮忙收场，这条得自己接
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      // 中文输入法组合态里的 Esc 是"取消候选词"，不该顺手把面板也关了
      if (e.key !== 'Escape' || e.isComposing) return;
      if (fullscreen) setFullscreen(false);
      else if (!hasPendingForm) setOpenBoth(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, fullscreen, hasPendingForm, setOpenBoth]);

  // 点面板外部关闭。只在 PC 浮窗态：移动端是铺满视口的全屏层，没有"外部"可点；全屏态同理
  const closeOnOutside = useCallback(() => setOpenBoth(false), [setOpenBoth]);
  useClickOutside(dockRef, closeOnOutside, open && desktop && !fullscreen && !hasPendingForm);

  const goConfig = useCallback(() => {
    setOpenBoth(false);
    navigate('/ai');
  }, [setOpenBoth, navigate]);

  /* ===== 悬浮球拖拽：按住挪走 → 松手吸最近的左右边；位移没过阈值才算点击 ===== */
  const ballDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    // 已经有手指在拖就不抢：第二根手指进来会把起点算成它的，正在拖的球会瞬间跳位，
    // 它先松手还会把拖动整个终结掉
    if (present || ballRef.current) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    const b = ballBounds();
    const ox = ball.side === 'left' ? b.minX : b.maxX;
    const oy = clamp(b.minY + ball.yRatio * (b.maxY - b.minY), b.minY, b.maxY);
    ballRef.current = { id: e.pointerId, sx: e.clientX, sy: e.clientY, ox, oy, cx: ox, cy: oy, moved: false };
  };
  const ballMove = (e: React.PointerEvent) => {
    const d = ballRef.current;
    if (!d || d.id !== e.pointerId) return;
    const dx = e.clientX - d.sx, dy = e.clientY - d.sy;
    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) d.moved = true;
    if (!d.moved) return;
    const b = ballBounds();
    d.cx = clamp(d.ox + dx, b.minX, b.maxX);
    d.cy = clamp(d.oy + dy, b.minY, b.maxY);
    setBallDrag({ x: d.cx, y: d.cy });
  };
  /** @param expectClick pointerup 之后浏览器会补一个 click，pointercancel 不会——别留个吞不掉的标记 */
  const ballEnd = (e: React.PointerEvent, expectClick: boolean) => {
    const d = ballRef.current;
    if (!d || d.id !== e.pointerId) return;
    ballRef.current = null;
    if (!d.moved) { setBallDrag(null); return; }   // 没拖动，交给随后那个 click 去开合
    if (expectClick) swallowClickRef.current = performance.now();
    const b = ballBounds();
    // 横向永远吸边（贴哪边看球心落在哪半屏），竖向随手停——微信悬浮窗那个手感
    const next: BallPos = {
      side: d.cx + b.size / 2 < window.innerWidth / 2 ? 'left' : 'right',
      yRatio: b.maxY > b.minY ? (d.cy - b.minY) / (b.maxY - b.minY) : 1,
    };
    setBall(next);
    setBallDrag(null);
    localStorage.setItem(BALL_KEY, JSON.stringify(next));
  };

  /** 面板锚在球那一侧的底角，所以往"外"拖是放大：锚右往左拖，锚左往右拖 */
  const resizeStart = (axis: Axis) => (e: React.PointerEvent) => {
    motionRef.current?.finish();
    e.preventDefault();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    dragRef.current = { axis, x: e.clientX, y: e.clientY, w: size.w, h: size.h, cur: size };
  };
  const resizeMove = (e: React.PointerEvent) => {
    const d = dragRef.current;
    if (!d) return;
    // 锚左时把手在右边缘，往右拖才是放大，增量方向跟着镜像
    const widen = ball.side === 'left' ? e.clientX - d.x : d.x - e.clientX;
    // 上限跟 CSS 的 max-w/max-h 用同一把尺子（都是 rem）：写死 px 的话 PC 根字号 17px
    // 会让 JS 比 CSS 松十几像素，拖到顶时面板会先被 CSS 截住、把手却还在动
    const rem = rootFontSize();
    const next: Size = {
      w: d.axis === 'y' ? d.w
        : Math.min(Math.max(d.w + widen, MIN_W), Math.min(MAX_W, window.innerWidth - rem * DOCK_MAX_W_GAP_REM)),
      h: d.axis === 'x' ? d.h
        : Math.min(Math.max(d.h + (d.y - e.clientY), MIN_H), window.innerHeight - rem * DOCK_MAX_H_GAP_REM),
    };
    d.cur = next;
    setSize(next);
  };
  const resizeEnd = () => {
    if (!dragRef.current) return;
    localStorage.setItem(SIZE_KEY, JSON.stringify(dragRef.current.cur));
    dragRef.current = null;
  };

  // ballDrag 非空时用拖动中的实时坐标，否则按比例落在 bounds 里
  const ballX = ballDrag ? ballDrag.x : (ball.side === 'left' ? bounds.minX : bounds.maxX);
  const ballY = ballDrag
    ? ballDrag.y
    : clamp(bounds.minY + ball.yRatio * (bounds.maxY - bounds.minY), bounds.minY, bounds.maxY);
  const dragging = ballDrag != null;

  return (
    <>
      {/* 悬浮球：可拖，松手吸最近的左右边（位置记 localStorage）。z-90 夹在 GuidedTour 之上、toast 之下。
          移动端面板全屏时球藏起来，否则会压在输入区上 */}
      <button
        ref={ballBtnRef}
        type="button"
        onPointerDown={ballDown}
        onPointerMove={ballMove}
        onPointerUp={e => ballEnd(e, true)}
        onPointerCancel={e => ballEnd(e, false)}
        onClick={() => {
          if (performance.now() - swallowClickRef.current < CLICK_SWALLOW_MS) {
            swallowClickRef.current = 0;
            return;
          }
          toggle();
        }}
        style={{
          left: ballX,
          top: ballY,
          // 吸边回弹带一点过冲；拖动中必须关掉 left/top 的过渡，否则球跟不上手指。
          // background-color 这项不能漏：它整条盖掉 className 里的过渡，漏了 hover 就成硬切
          transition: present ? 'none' : dragging
            ? 'transform .16s ease, box-shadow .16s ease, background-color .16s ease'
            : 'left .34s cubic-bezier(.22,1.4,.36,1), top .34s cubic-bezier(.22,1.4,.36,1),'
              + ' transform .16s ease, box-shadow .16s ease, background-color .16s ease',
        }}
        title={t('chat.title')}
        aria-label={t('chat.openAria')}
        aria-expanded={open}
        aria-controls="polaris-dock"
        aria-hidden={present}
        disabled={present}
        data-dragging={dragging}
        tabIndex={present ? -1 : 0}
        className={cn(
          'polaris-ball fixed z-[90] flex w-12 h-12 rounded-full pt-card touch-none motion-reduce:transition-none!',
          'items-center justify-center hover:bg-surface-hover',
          // 触屏的 :active 会延后释放；隐藏后去掉按压缩放，动画按原始球尺寸对位。
          dragging ? 'cursor-grabbing scale-105' : present ? 'cursor-default' : 'cursor-grab active:scale-95',
          // 面板在场的整段时间都藏住球（含开合动画）：面板收到最后一帧就是球的样子，
          // 卸载那一刻球顶上来，中间不会出现「球和还在缩的面板同时在」
          present && 'invisible pointer-events-none',
        )}
      >
        {/* 球只在面板完全收起时可见 */}
        {/* 研判中：一道扇形绕球扫。-inset-1 让环带落在球外沿，压在图标下面不挡它 */}
        {loading && (
          <span aria-hidden className="absolute -inset-1 rounded-full pointer-events-none wiib-ball-sweep" />
        )}
        {/* 有未读：一圈向外扩散 */}
        {unread && (
          <span aria-hidden className="absolute inset-0 rounded-full border-[1.5px] border-primary pointer-events-none wiib-ball-ping" />
        )}
        {loading ? <Loader2 className="w-5 h-5 text-primary animate-spin" /> : <Bot className="w-5 h-5 text-primary" />}
        {/* 答案到了：亮橙点。脉冲环扩散完的那一瞬靠它留住痕迹。打开面板即清 */}
        {unread && (
          <span className="absolute top-1 right-1 w-2.5 h-2.5 rounded-full bg-primary" />
        )}
      </button>

      {present && (
        <div
          id="polaris-dock"
          ref={dockRef}
          role="dialog"
          aria-label={HUB_NAME}
          inert={!open}
          data-state={open ? 'open' : 'closing'}
          tabIndex={-1}
          // 收放动画绕默认的中心缩放，transform-origin 别在这儿改
          style={{ '--dock-w': `${size.w}px`, '--dock-h': `${size.h}px` } as React.CSSProperties}
          className={cn(
            'fixed z-[85] pt-card flex flex-col overflow-hidden outline-none',
            fullscreen
              // 全屏铺满浏览器视口，浮窗那套尺寸/锚点全让开
              ? 'inset-0 rounded-none w-full h-full max-w-none max-h-none'
              : cn(
                  // 移动端全屏（让开刘海），PC 锚定在球那一侧的浮窗（尺寸可拖拽，CSS 变量只在 md 生效）
                  'inset-0 rounded-none pt-[env(safe-area-inset-top)]',
                  // 面板跟着球换边：球在左就从左下角长出来。竖直方向仍锚底——面板高度可变，
                  // 球拖到顶部时它必然要向下铺，锚底最稳，resize 的方向语义也才立得住
                  'md:inset-auto md:bottom-20 md:shadow-2xl md:pt-0',
                  ball.side === 'left' ? 'md:left-4' : 'md:right-4',
                  'md:w-[var(--dock-w)] md:h-[var(--dock-h)]',
                  'md:max-w-[calc(100vw-2rem)] md:max-h-[calc(100vh-6.5rem)]',
                ),
          )}
        >
          {/* 拖拽把手（仅 PC 浮窗态）：贴外侧的那条边缘调宽、上边缘调高、外侧上角双向。
              锚右时"外侧"是左边，锚左时是右边；全屏没有尺寸可调，整组撤掉 */}
          {!fullscreen && (
            <>
              <div onPointerDown={resizeStart('x')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
                   className={cn('hidden md:block absolute top-4 bottom-0 w-1.5 cursor-ew-resize z-20',
                     ball.side === 'left' ? 'right-0' : 'left-0')} />
              <div onPointerDown={resizeStart('y')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
                   className={cn('hidden md:block absolute top-0 h-1.5 cursor-ns-resize z-20',
                     ball.side === 'left' ? 'left-0 right-4' : 'left-4 right-0')} />
              <div onPointerDown={resizeStart('xy')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
                   className={cn('hidden md:flex absolute top-0 w-4 h-4 z-20 items-start group',
                     ball.side === 'left' ? 'right-0 cursor-nesw-resize justify-end' : 'left-0 cursor-nwse-resize justify-start')}
                   title={t('chat.resize')}>
                <span className={cn('mt-1 w-2 h-2 border-t-2 border-muted-foreground/40 group-hover:border-primary transition-colors',
                  ball.side === 'left' ? 'mr-1 border-r-2 rounded-tr' : 'ml-1 border-l-2 rounded-tl')} />
              </div>
            </>
          )}

          {hasConfig == null ? (
            <PanelShell onClose={() => setOpenBoth(false)}>
              <div className="flex-1 flex items-center justify-center gap-2 text-xs text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" /> {t('loadingConfig')}
              </div>
            </PanelShell>
          ) : hasConfig ? (
            <ChatPanel onClose={() => setOpenBoth(false)} onGoConfig={goConfig}
                       fullscreen={fullscreen} onToggleFullscreen={toggleFullscreen} />
          ) : (
            <PanelShell onClose={() => setOpenBoth(false)}>
              <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center px-6">
                <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
                  <KeyRound className="w-6 h-6" />
                </div>
                <div className="text-sm font-bold text-muted-foreground">{t('chat.byokTitle')}</div>
                <div className="text-[11px] text-muted-foreground/70 max-w-[16rem]">
                  {t('chat.byokHint')}
                </div>
                <button
                  type="button"
                  onClick={goConfig}
                  className="mt-1 border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover"
                >
                  {t('chat.goConfig')}
                </button>
              </div>
            </PanelShell>
          )}
        </div>
      )}
    </>
  );
}

/** 加载中/引导态的简壳：ChatPanel 自带顶栏，这两个状态没有，得补一个能关的头（样式与面板顶栏同款） */
function PanelShell({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  const { t } = useTranslation(['ai', 'common']);
  return (
    <>
      <div className="flex items-center gap-2 px-2 py-1.5 border-b border-border">
        <span className="pl-1.5 text-sm font-black">{HUB_NAME}</span>
        <button
          onClick={onClose}
          className="ml-auto w-8 h-8 flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors"
          title={t('common:close')}
          aria-label={t('chat.closeAria')}
        >
          <X className="w-4 h-4" />
        </button>
      </div>
      {children}
    </>
  );
}
