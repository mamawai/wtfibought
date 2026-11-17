import { useEffect, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';
import { Switch } from '../ui/switch';
import { bjMinute, minToHm, posOf, REVIEW_MIN, valueAt, type WakeWin } from './traderConfig';

/** 开关行：左边标题 + 一句后果，右边开关；点不动时整行淡下去（样式见 MyTrader.css .sw-row） */
export function SwitchRow({ checked, onChange, title, desc, disabled }: {
  checked: boolean; onChange: (on: boolean) => void; title: string; desc: string; disabled?: boolean;
}) {
  return (
    <div className={cn('sw-row', disabled && 'off')}>
      <div><b>{title}</b><span>{desc}</span></div>
      <Switch checked={checked} onChange={onChange} disabled={disabled} label={title} />
    </div>
  );
}

const KEY_STEP: Record<string, number> = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1, PageDown: -10, PageUp: 10 };

/** 方向键一档一档挪：按 KEY_STEP 走几步，每步交给 step */
function keyStep(e: KeyboardEvent, v: number, step: (v: number, dir: number) => number): number | null {
  const k = KEY_STEP[e.key];
  if (!k) return null;
  e.preventDefault();
  for (let i = 0; i < Math.abs(k); i++) v = step(v, Math.sign(k));
  return v;
}

/** 尺上的标注：一根短竖线 + 一行小字；below=字写在尺下面 */
export interface RailMark {
  v: number;
  label: string;
  /** o=橙色 */
  tone?: 'o';
  below?: boolean;
}

/**
 * 两头都能拖的尺（杠杆区间、唤醒时段；样式见 MyTrader.css .rg）：按下就近抓一头跟着手走，方向键一档一档挪。
 * wrap=起点可以比终点靠右（时段跨午夜），这时填两段；否则两头不交叉
 */
export function RangeRail({ values, onChange, pos, val, step, ticks, marks, wrap, labels, text, className }: {
  values: [number, number];
  onChange: (v: [number, number]) => void;
  /** 值 → 尺上的位置 0~1 */
  pos: (v: number) => number;
  /** 位置 0~1 → 吸到档位上的值 */
  val: (p: number) => number;
  /** 键盘按一下往 dir（±1）走一档 */
  step: (v: number, dir: number) => number;
  ticks: [number, string][];
  marks?: RailMark[];
  wrap?: boolean;
  /** 两头的读屏名 */
  labels: [string, string];
  /** 读屏念的值 */
  text: (v: number) => string;
  className?: string;
}) {
  const [drag, setDrag] = useState<number | null>(null);

  const put = (i: number, v: number) => {
    const next: [number, number] = [values[0], values[1]];
    next[i] = wrap ? v : i ? Math.max(v, values[0]) : Math.min(v, values[1]);
    if (next[i] !== values[i]) onChange(next);
  };
  const at = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return val(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)));
  };
  const down = (e: PointerEvent<HTMLDivElement>) => {
    const v = at(e);
    const knob = (e.target as HTMLElement).closest<HTMLElement>('.kn');
    let i: number;
    if (knob) {
      i = Number(knob.dataset.i);
    } else {
      // 点在尺上抓离得近的那头；时段的尺首尾相接，距离按绕一圈算；两头叠在一起时往右点抓右边那个
      const dist = (a: number) => {
        const d = Math.abs(pos(v) - pos(a));
        return wrap ? Math.min(d, 1 - d) : d;
      };
      i = dist(values[1]) < dist(values[0]) || (dist(values[1]) === dist(values[0]) && v > values[1]) ? 1 : 0;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag(i);
    if (!knob) put(i, v);
  };

  const p0 = pos(values[0]) * 100;
  const p1 = pos(values[1]) * 100;
  // 起点比终点靠右 = 跨过了尽头（时段跨午夜），填两段
  const fills = p0 <= p1 ? [[p0, p1]] : [[0, p1], [p0, 100]];
  return (
    <div className={cn('rg', className)}>
      <div className="rail" onPointerDown={down} onPointerMove={e => { if (drag != null) put(drag, at(e)); }}
           onPointerUp={() => setDrag(null)} onPointerCancel={() => setDrag(null)}>
        {fills.map(([a, b]) => <i key={a} className="fi" style={{ left: `${a}%`, width: `${b - a}%` }} />)}
        {marks?.map(m => (
          <i key={m.label} className={cn('mk', m.tone, m.below && 'below')} style={{ left: `${pos(m.v) * 100}%` }}><span>{m.label}</span></i>
        ))}
        {values.map((v, i) => (
          <i key={i} className={cn('kn', drag === i && 'drag')} data-i={i} tabIndex={0} role="slider"
             aria-label={labels[i]} aria-valuenow={v} aria-valuemin={val(0)} aria-valuemax={val(1)} aria-valuetext={text(v)}
             style={{ left: `${pos(v) * 100}%` }}
             onKeyDown={e => { const nv = keyStep(e, v, step); if (nv != null) put(i, nv); }} />
        ))}
      </div>
      <div className="ticks">{ticks.map(([v, l]) => <span key={v} style={{ left: `${pos(v) * 100}%` }}>{l}</span>)}</div>
    </div>
  );
}

/**
 * 单头滑杆（保证金上限、警报系数；样式见 MyTrader.css .msl）：旋钮跟手不加缓动；底下的刻度能点，点了滑过去。
 * 刻度在尺上等距排，段内线性取值
 */
export function Slider({ nodes, value, onChange, snap, step, tick, label, text, disabled }: {
  nodes: number[];
  value: number;
  onChange: (v: number) => void;
  /** 拖到的值吸到合法档位 */
  snap: (v: number) => number;
  /** 键盘按一下往 dir（±1）走一档 */
  step: (v: number, dir: number) => number;
  tick: (v: number) => string;
  /** 读屏名 */
  label: string;
  /** 读屏念的值 */
  text: (v: number) => string;
  disabled?: boolean;
}) {
  const [drag, setDrag] = useState(false);
  // 点刻度时滑过去；拖动、方向键不加缓动
  const [glide, setGlide] = useState(false);
  const set = (v: number, smooth: boolean) => {
    setGlide(smooth);
    const nv = Math.max(nodes[0], Math.min(nodes[nodes.length - 1], snap(v)));
    if (nv !== value) onChange(nv);
  };
  const at = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.parentElement!.getBoundingClientRect();
    return valueAt((e.clientX - r.left) / r.width, nodes);
  };
  const p = posOf(value, nodes) * 100;
  return (
    <div className={cn('msl', drag && 'drag', glide && 'glide', disabled && 'off')}>
      <div className="rail">
        <i className="fi" style={{ width: `${p}%` }} />
        <i className="kn" tabIndex={disabled ? -1 : 0} role="slider" aria-label={label} aria-valuenow={value}
           aria-valuemin={nodes[0]} aria-valuemax={nodes[nodes.length - 1]}
           aria-valuetext={text(value)} aria-disabled={disabled} style={{ left: `${p}%` }}
           onKeyDown={e => { const nv = keyStep(e, value, step); if (nv != null) set(nv, false); }} />
        <div className="hit"
             onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); setDrag(true); set(at(e), true); }}
             onPointerMove={e => { if (drag) set(at(e), false); }}
             onPointerUp={() => setDrag(false)} onPointerCancel={() => setDrag(false)} />
      </div>
      <div className="ticks">
        {nodes.map(n => (
          <button key={n} type="button" aria-pressed={n === value} style={{ left: `${posOf(n, nodes) * 100}%` }}
                  disabled={disabled} onClick={() => set(n, true)}>{tick(n)}</button>
        ))}
      </div>
    </div>
  );
}

const DAY_MIN = 1440;

/**
 * 唤醒时段的 24 小时尺（北京时间）：两头拖起止，5 分钟一档，起点比终点靠右就是跨到次日。
 * 尺上标着每天 08:00 的复盘（复盘不看时段）和现在几点
 */
export function WakeRuler({ win, onChange }: { win: WakeWin; onChange: (w: WakeWin) => void }) {
  const { t } = useTranslation('ai');
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);
  const nowMin = bjMinute(now);
  return (
    <RangeRail className="ruler" wrap values={[win.from, win.to]} onChange={([from, to]) => onChange({ from, to })}
               pos={m => m / DAY_MIN} val={p => Math.min(DAY_MIN - 5, Math.round(p * DAY_MIN / 5) * 5)}
               step={(m, d) => ((m + d * 5) % DAY_MIN + DAY_MIN) % DAY_MIN}
               ticks={[0, 6, 12, 18, 24].map(h => [h * 60, `${String(h).padStart(2, '0')}:00`])}
               marks={[
                 { v: REVIEW_MIN, label: t('my.window.review') },
                 { v: nowMin, label: t('my.window.now', { time: minToHm(nowMin) }), tone: 'o', below: true },
               ]}
               labels={[t('my.window.fromAria', { time: minToHm(win.from) }), t('my.window.toAria', { time: minToHm(win.to) })]}
               text={minToHm} />
  );
}
