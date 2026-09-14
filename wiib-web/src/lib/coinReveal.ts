/**
 * 选币页点行 → 交易页的过渡：这一行的底面从行的位置扩到整屏，图标飞到屏幕正中放大，
 * 币色光晕在落点后面晕开，行里的走势线拉大成底纹，价格和涨跌晚半拍浮到图标下面；
 * 交易页在底下挂好后整层淡出，图标再飞到页头 [data-reveal-icon] 的位置落地。
 * 纯 DOM 操作挂在 body 下，样式见 index.css 的 .cr-* 段；同一时刻只跑一段。
 */

const ICON_END = 96;

interface Running {
  nodes: HTMLElement[];
  ov: HTMLElement;
  icon: HTMLElement;
  iconStart: DOMRect;
}

let running: Running | null = null;

/** 起飞。返回 null 表示已有一段在跑（连点）；否则 resolve 于图标到达正中（约 0.7s），调用方此时 navigate */
export function playCoinReveal(row: HTMLElement, opts: { color: string; price: string | null; pct: number | null }): Promise<void> | null {
  if (running) return null;
  const iconSrc = row.querySelector<HTMLElement>('[data-cr-icon] > *');
  if (!iconSrc) return null;
  const r = row.getBoundingClientRect();
  const ir = iconSrc.getBoundingClientRect();
  const cx = window.innerWidth / 2, cy = window.innerHeight / 2;

  // 底面：起点就是这一行
  const ov = el('div', 'cr-ov', { top: `${r.top}px`, left: `${r.left}px`, width: `${r.width}px`, height: `${r.height}px` });
  // 光晕：钉在图标落点正中
  const halo = el('div', 'cr-halo', { left: `${cx - 280}px`, top: `${cy - 280}px` });
  halo.style.setProperty('--cr-color', opts.color);
  // 走势线：从行里那格出发，拉大到屏幕中央一大片（没加载出来就没有这层）
  const sparkSrc = row.querySelector<SVGElement>('[data-cr-spark] svg');
  let spark: HTMLElement | null = null;
  let sparkEnd: Record<string, string> | null = null;
  if (sparkSrc) {
    const sr = sparkSrc.getBoundingClientRect();
    spark = el('div', 'cr-spark', { top: `${sr.top}px`, left: `${sr.left}px`, width: `${sr.width}px`, height: `${sr.height}px` });
    const clone = sparkSrc.cloneNode(true) as SVGElement;
    clone.classList.remove('reveal');   // 别让入场描线动画重放
    clone.querySelectorAll('.reveal-late').forEach(n => n.classList.remove('reveal-late'));
    spark.appendChild(clone);
    const sw = Math.min(window.innerWidth * 0.72, 960), sh = Math.min(window.innerHeight * 0.34, 260);
    sparkEnd = { top: `${cy - sh / 2}px`, left: `${cx - sw / 2}px`, width: `${sw}px`, height: `${sh}px` };
  }
  // 飞行图标：按终态 96px 建，起点用 transform 缩到行里图标的大小；放大只是回到 1 倍，矢量不糊
  const icon = el('div', 'cr-icon', { top: `${ir.top}px`, left: `${ir.left}px`, width: `${ICON_END}px`, height: `${ICON_END}px` });
  icon.appendChild(iconSrc.cloneNode(true));
  icon.style.transform = `scale(${ir.width / ICON_END})`;
  // 价格 + 涨跌
  const meta = el('div', 'cr-meta');
  if (opts.price != null) {
    const b = document.createElement('b');
    b.className = 'num';
    b.textContent = opts.price;
    meta.appendChild(b);
  }
  if (opts.pct != null) {
    const up = opts.pct >= 0;
    const i = document.createElement('i');
    i.className = `num ${up ? 'text-gain' : 'text-loss'}`;
    i.textContent = `${up ? '+' : ''}${opts.pct.toFixed(2)}%`;
    meta.appendChild(i);
  }

  const nodes = [ov, halo, ...(spark ? [spark] : []), icon, meta];
  document.body.append(...nodes);
  row.dataset.leaving = '1';
  running = { nodes, ov, icon, iconStart: ir };

  // 两帧后再改终态，起点先画上去过渡才有起点
  return new Promise(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => {
      ov.classList.add('is-full');
      icon.style.transform = `translate(${cx - ICON_END / 2 - ir.left}px, ${cy - ICON_END / 2 - ir.top}px) scale(1)`;
      if (spark && sparkEnd) Object.assign(spark.style, sparkEnd);
      setTimeout(resolve, 700);
    }));
  });
}

/** 落地。navigate 之后调：等交易页挂上来，图标飞到页头图标处，其余整层淡出后全部移除 */
export function landCoinReveal(symbol: string) {
  const s = running;
  if (!s) return;
  running = null;
  let tries = 0;
  const tick = () => {
    const target = document.querySelector<HTMLElement>(`[data-reveal-icon="${symbol}"]`);
    if (!target && tries++ < 20) { requestAnimationFrame(tick); return; }
    if (target) {
      const tr = target.getBoundingClientRect();
      s.icon.style.transition = 'transform .5s var(--ease-control), opacity .25s .4s';
      s.icon.style.transform = `translate(${tr.left - s.iconStart.left}px, ${tr.top - s.iconStart.top}px) scale(${tr.width / ICON_END})`;
    }
    s.icon.style.opacity = '0';
    s.ov.classList.add('is-out');
    setTimeout(() => s.nodes.forEach(n => n.remove()), 700);
  };
  requestAnimationFrame(tick);
}

function el(tag: string, cls: string, style?: Record<string, string>): HTMLElement {
  const n = document.createElement(tag);
  n.className = cls;
  if (style) Object.assign(n.style, style);
  return n;
}
