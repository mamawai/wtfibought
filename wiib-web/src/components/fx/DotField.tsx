import { useEffect, useMemo, useRef, type RefObject } from 'react';
import { useIsDark } from '../../hooks/useIsDark';
import { SCATTER_MS } from '../../hooks/useWhatIfCycle';
import { REDUCED_MOTION } from '../../lib/motion';

/** 圆点网格的间距（CSS px）：宽屏 / 窄屏 */
const CELL = 14, CELL_NARROW = 11;
/** 这么宽以上才是左右两栏，才画山 */
const WIDE = 1024;
/** 走势重采样成多少个点 */
const POINTS = 60;
/** 灰点三档的透明度：浅灰、深灰、墨（暗色下是暗灰、亮灰、白） */
const ALPHA = [0.13, 0.32, 0.85];
/** 整屏每个格点至少这么浓：没烟没山的地方也有一颗最小的浅灰点 */
const BASE = 0.12;
/** 山聚拢用几秒；手动换标的时先把旧的散掉用几秒 */
const GATHER_S = 1.3, SWAP_S = 0.45;
const TAU = Math.PI * 2;

type El = RefObject<HTMLElement | null>;

/** 山的形状：走势压成 0~1（对数刻度，十年的线不会只剩最后一截）；base 是买入价落在 0~1 的哪儿 */
interface Shape { land: number[]; base: number }

function toShape(values: number[]): Shape {
  const logs = values.map(v => Math.log(v / values[0]));
  const lo = Math.min(...logs), range = Math.max(...logs) - lo || 1;
  const land = Array.from({ length: POINTS }, (_, k) => {
    const f = k / (POINTS - 1) * (logs.length - 1), i = Math.floor(f);
    return (logs[i] + (logs[Math.min(logs.length - 1, i + 1)] - logs[i]) * (f - i) - lo) / range;
  });
  return { land, base: -lo / range };
}

/** (整数格点) → [0,1) */
function hash(x: number, y: number, z: number) {
  let n = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(z, 1274126177)) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
}

/** 三维平滑噪声，0~1 */
function noise(x: number, y: number, z: number) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  let u = x - xi, v = y - yi, w = z - zi;
  u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v); w = w * w * (3 - 2 * w);
  const a = hash(xi, yi, zi), b = hash(xi + 1, yi, zi), c = hash(xi, yi + 1, zi), d = hash(xi + 1, yi + 1, zi);
  const e = hash(xi, yi, zi + 1), f = hash(xi + 1, yi, zi + 1), g = hash(xi, yi + 1, zi + 1), h = hash(xi + 1, yi + 1, zi + 1);
  const p = a + (b - a) * u, q = c + (d - c) * u, r = e + (f - e) * u, s = g + (h - g) * u;
  const m = p + (q - p) * v, k = r + (s - r) * v;
  return m + (k - m) * w;
}

/** (x, y) 这儿的烟有多浓：三层噪声叠起来，被另一层噪声推着扭，整体慢慢往上飘 */
function smokeAt(x: number, y: number, t: number) {
  const X = x / 300, Y = y / 300;
  const wx = noise(X * 1.4 + 11, Y * 1.4, t * 0.1) - 0.5, wy = noise(X * 1.4, Y * 1.4 + 7, t * 0.1 + 5) - 0.5;
  return 0.58 * noise(X + wx * 1.6 + t * 0.035, Y + wy * 1.6 - t * 0.05, t * 0.06)
    + 0.28 * noise(X * 2.2 + wx * 2, Y * 2.2 + wy * 2 - t * 0.09, t * 0.1 + 3)
    + 0.14 * noise(X * 4.6, Y * 4.6 - t * 0.14, t * 0.16 + 9);
}

interface Props {
  /** 这笔钱一路值多少，第一个是本金 */
  values: number[];
  /** 亮够了：山散成烟 */
  scatter: boolean;
  /** 登录卡：山画到它左边为止 */
  cardRef: El;
  /** 左边那几行字：山不往上越过它 */
  heroRef: El;
  /** 左下角那句话：山不往下碰它 */
  mottoRef: El;
}

/**
 * 登录页的背景：铺满整屏的一张错开排的圆点网，每个格点都有一颗点，大小和深浅跟着它那儿"有多浓"走，最淡也有一颗小浅灰点。
 * 浓度两个来源：一团慢慢翻滚、往上飘的烟（黑白灰三档，偏右偏下；同一页里带 data-clear 的块是有字的地方，烟到跟前压到最淡）；
 * 左下一道山，山脊就是这笔钱的走势——上沿两三排比买入价高的是绿点、低的是红点，往下是灰点，越来越小越来越淡。
 * 山会聚会散：进页面和换标的时从烟里聚出来、显出绿红；scatter 一来又散回成黑点。指针周围推开一个洞。
 * 窄屏单列不画山，烟只留最淡的一档
 */
export function DotField({ values, scatter, cardRef, heroRef, mottoRef }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const want = useMemo(() => toShape(values), [values]);
  // 画的那边每帧来这儿看：该画成什么形状、该不该散
  const live = useRef({ want, scatter });
  // 不放动效时没有每帧重画，形状变了、切了明暗靠它补一张
  const repaint = useRef<(() => void) | null>(null);
  const isDark = useIsDark();
  // 点的三种颜色：墨、绿、红，切明暗时取一次
  const colors = useRef({ ink: '', green: '', red: '' });

  useEffect(() => {
    live.current = { want, scatter };
    repaint.current?.();
  }, [want, scatter]);

  useEffect(() => {
    const css = getComputedStyle(document.documentElement);
    colors.current = {
      ink: css.getPropertyValue('--color-foreground'),
      green: css.getPropertyValue('--color-gain'),
      red: css.getPropertyValue('--color-loss'),
    };
    repaint.current?.();
  }, [isDark]);

  useEffect(() => {
    const cv = canvasRef.current!, cx = cv.getContext('2d')!;
    // 这一页里有字、烟要让开的那几块
    const clear = cv.parentElement!.querySelectorAll('[data-clear]');
    let W = 0, H = 0, dpr = 1, cell = CELL, quiet: DOMRect[] = [];
    // 山占的地方：右边到 landRight（是 0 就不画山）；最高到 landTop，最低到 landLow，再往下到 landFloor 就淡没了
    let landRight = 0, landTop = 0, landLow = 0, landFloor = 0;
    let mx = -9999, my = -9999;
    // shown 是正画着的形状；gather 是聚拢了多少：0 全散成烟，1 聚齐成山
    let shown = live.current.want, gather = 0, lastT = 0;

    // 走势在 x 处的值（0~1）
    const valueAt = (x: number) => {
      const land = shown.land, f = Math.min(1, Math.max(0, x / landRight)) * (POINTS - 1), i = Math.floor(f);
      return land[i] + (land[Math.min(POINTS - 1, i + 1)] - land[i]) * (f - i);
    };
    // 值落在画布的哪个 y（越小越高）
    const yOf = (v: number) => landLow - (landLow - landTop) * v;
    // 一堆点一口气画完，三个数一组：x、y、半径
    const fill = (dots: number[]) => {
      cx.beginPath();
      for (let i = 0; i < dots.length; i += 3) {
        cx.moveTo(dots[i] + dots[i + 2], dots[i + 1]);
        cx.arc(dots[i], dots[i + 1], dots[i + 2], 0, TAU);
      }
      cx.fill();
    };

    const paint = (t: number) => {
      // 山聚拢还是散开：换了标的先把旧的散掉再聚新的；亮够了就慢慢散
      const { want: to, scatter: out } = live.current;
      const dt = Math.min(0.1, Math.max(0, t - lastT));
      lastT = t;
      if (REDUCED_MOTION) { shown = to; gather = 1; }
      else if (shown !== to) { gather -= dt / SWAP_S; if (gather <= 0) { gather = 0; shown = to; } }
      else if (out) gather = Math.max(0, gather - dt * 1000 / SCATTER_MS);
      else gather = Math.min(1, gather + dt / GATHER_S);
      // e 是缓过的聚拢程度，loose 是散了多少
      const e = gather * gather * (3 - 2 * gather), loose = 1 - e;

      const { ink, green, red } = colors.current;
      cx.setTransform(dpr, 0, 0, dpr, 0, 0);
      cx.clearRect(0, 0, W, H);
      // 灰点按深浅分三堆，山脊上沿的点按绿红分两堆
      const gray: number[][] = [[], [], []], ridge: number[][] = [[], []];
      const wide = W >= WIDE;
      const rows = Math.ceil(H / cell) + 1, cols = Math.ceil(W / cell) + 1;
      // 买入价落在第几行：山聚得差不多了，那一行在山外头画成一排小点
      const bj = landRight > 0 && e > 0.9 ? Math.round(yOf(shown.base) / cell) : -1;
      for (let j = 0; j < rows; j++) {
        for (let i = 0; i < cols; i++) {
          const x = i * cell + (j & 1 ? cell / 2 : 0), y = j * cell;
          // 烟：k 是这个点有多浓（0 没有，1 最大颗）。宽屏往右偏浓，窄屏左右一样
          let k = (smokeAt(x, y, t) + (wide ? 0.16 * (x / W) : 0.08) + 0.1 * (y / H) - 0.58) / 0.32;
          for (const r of quiet) {
            const dx = Math.max(r.left - x, 0, x - r.right), dy = Math.max(r.top - y, 0, y - r.bottom);
            k -= 1.25 * Math.max(0, 1 - Math.hypot(dx, dy) / 130);
          }
          // 山那一带的烟：山聚着的时候只留最淡的一档，不跟山脊抢；山散了就放开，还比别处浓一点（散开的点都成了烟），靠近登录卡那头慢慢收回去。
          // 窄屏卡片两边只露一条缝，只留最淡的
          if (!wide) k = Math.min(k, 0.44);
          else if (x < landRight) {
            const above = Math.min(1, Math.max(0, (landTop - y) / 90));
            k = Math.min(k + 0.3 * loose * (1 - above) * Math.min(1, (landRight - x) / 120), 0.44 + 0.56 * Math.max(above, loose));
          }
          // 这个点归哪一堆：0~2 是灰的三档，3 绿 4 红
          let pile = k > 0.8 ? 2 : k > 0.44 ? 1 : 0;
          if (e > 0 && x < landRight && y < landFloor) {
            // 散的时候山被推得走样、往上飘：越散偏得越多
            const push = 240 * loose;
            const sx = x + (noise(x / 150, y / 150, t * 0.35) - 0.5) * push;
            const sy = y + (noise(x / 150 + 7, y / 150, t * 0.35 + 3) - 0.5) * push + 60 * loose;
            // depth 是这个点在山脊下面多深。山脊上沿那一圈按盖住多少缩小，边才不是锯齿
            const depth = sy - yOf(valueAt(sx));
            if (depth > -cell) {
              // 往上不越过 landTop 多少（散的时候会往上飘，别盖到那几行字），往下到 landFloor 淡没
              const m = (depth < 0 ? 1 + depth / cell : Math.exp(-depth / 120)) * Math.min(1, e * 1.8)
                * Math.min(1, Math.max(0, (y - landTop + 30) / 30)) * Math.min(1, (landFloor - y) / 60)
                * (0.88 + 0.24 * noise(x / 120, y / 120, t * 0.25));
              if (m > k) {
                k = m;
                // 上沿聚到一半以上才显出绿红，一块一块地显；没显色的时候是墨点
                const lit = e > 0.5 + 0.35 * (noise(x / 70, y / 70, 3.3) - 0.5);
                pile = depth < 30 ? (lit ? (valueAt(sx) >= shown.base ? 3 : 4) : 2) : depth < 92 ? 1 : 0;
              }
            } else if (j === bj && k < 0.2) {
              k = 0.2; pile = 1;
            }
          }
          const md = Math.hypot(x - mx, y - my);
          if (md < 170) k -= 1.56 * (1 - md / 170) ** 2;
          // 烟和山被压没了的地方（有字的、指针推开的、本来就空的）留一颗最小的浅灰点，整屏都是点
          if (k < BASE) { k = BASE; pile = 0; }
          (pile > 2 ? ridge[pile - 3] : gray[pile]).push(x, y, cell * 0.46 * Math.min(1, k) ** 0.8);
        }
      }
      cx.fillStyle = ink;
      gray.forEach((dots, i) => { cx.globalAlpha = ALPHA[i]; fill(dots); });
      cx.globalAlpha = 1;
      cx.fillStyle = green; fill(ridge[0]);
      cx.fillStyle = red; fill(ridge[1]);
    };
    // 不放动效：只画定住的一张
    const still = () => paint(20);

    // 量一遍：画布多大、哪几块有字、山能占哪儿
    const measure = () => {
      // 登录成功跳走时那几块先从页面上摘掉，尺寸回调还会来一次
      if (!cv.isConnected) return;
      dpr = Math.min(2, window.devicePixelRatio || 1);
      W = window.innerWidth; H = window.innerHeight;
      const pw = Math.round(W * dpr), ph = Math.round(H * dpr);
      if (cv.width !== pw || cv.height !== ph) { cv.width = pw; cv.height = ph; }
      cell = W < 700 ? CELL_NARROW : CELL;
      quiet = Array.from(clear, el => el.getBoundingClientRect()).filter(r => r.width > 0);
      const hero = heroRef.current!.getBoundingClientRect(), motto = mottoRef.current!.getBoundingClientRect();
      landTop = hero.bottom + 44; landLow = motto.top - 70; landFloor = motto.top - 16;
      // 窄屏上下排，或者屏幕太矮腾不出地方，就不画山
      landRight = W >= WIDE && landLow - landTop > 40 ? cardRef.current!.getBoundingClientRect().left - 28 : 0;
      if (REDUCED_MOTION) still();
    };

    const onMove = (e: PointerEvent) => { mx = e.clientX; my = e.clientY; };
    const onLeave = () => { mx = my = -9999; };
    window.addEventListener('resize', measure);
    // 画布钉在屏幕上，页面一滚那几块字的位置就变了
    window.addEventListener('scroll', measure, { passive: true });
    window.addEventListener('pointermove', onMove);
    document.documentElement.addEventListener('pointerleave', onLeave);
    // 字换了（换标的、换语言、字体到了）那几块的大小会变
    const ro = new ResizeObserver(measure);
    ro.observe(cardRef.current!);
    clear.forEach(el => ro.observe(el));
    measure();

    let raf = 0;
    if (REDUCED_MOTION) {
      repaint.current = still;
    } else {
      // 烟走得慢，隔一帧画一次就够
      let odd = false;
      const loop = (ts: number) => {
        odd = !odd;
        if (odd) paint(ts / 1000);
        raf = requestAnimationFrame(loop);
      };
      raf = requestAnimationFrame(loop);
    }
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      repaint.current = null;
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure);
      window.removeEventListener('pointermove', onMove);
      document.documentElement.removeEventListener('pointerleave', onLeave);
    };
  }, [cardRef, heroRef, mottoRef]);

  return <canvas ref={canvasRef} className="lbg" aria-hidden="true" />;
}
