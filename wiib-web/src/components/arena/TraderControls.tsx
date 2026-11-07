import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/utils';
import { bjMinute, INTERVAL_MIN, inWindow, minToHm, REVIEW_MIN, type WakeWin } from './traderConfig';

/** 开关行：方块开关 + 标题 + 一句后果 */
export function SwitchRow({ on, onChange, title, desc, disabled, bare }: {
  on: boolean; onChange: (on: boolean) => void; title: string; desc: string; disabled?: boolean;
  /** 不带上边线和上下留白，单独摆着用 */
  bare?: boolean;
}) {
  return (
    <button type="button" role="switch" aria-checked={on} disabled={disabled} onClick={() => onChange(!on)}
            className={cn('tr-swr', on && 'on', bare && 'bare')}>
      <span className="tr-sw" />
      <span className="t"><b>{title}</b><span>{desc}</span></span>
    </button>
  );
}

/** 值 → 滑杆上的位置（0~1）：刻度等距摆，刻度之间线性 */
function toPos(ticks: number[], v: number): number {
  for (let i = 0; i < ticks.length - 1; i++) {
    if (v <= ticks[i + 1]) return (i + Math.max(0, (v - ticks[i]) / (ticks[i + 1] - ticks[i]))) / (ticks.length - 1);
  }
  return 1;
}

function fromPos(ticks: number[], p: number): number {
  const x = Math.max(0, Math.min(1, p)) * (ticks.length - 1);
  const i = Math.min(ticks.length - 2, Math.floor(x));
  return ticks[i] + (x - i) * (ticks[i + 1] - ticks[i]);
}

const KEY_STEP: Record<string, number> = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1, PageDown: -10, PageUp: 10 };

/**
 * 刻度不等距的滑杆（1、2、3、5、10、20…这种跨度大的值也好拖）。
 * values 一个值是单手柄，两个值是区间；按下时就近抓一个手柄，区间两头不会交叉
 */
export function ScaleSlider({ ticks, values, onChange, snap, step, tickLabel, ariaLabels, disabled }: {
  ticks: number[];
  values: number[];
  onChange: (values: number[]) => void;
  /** 拖到的值吸到合法档位 */
  snap: (v: number) => number;
  /** 键盘按一下往 dir（±1）走一档 */
  step: (v: number, dir: number) => number;
  tickLabel: (v: number) => string;
  ariaLabels: string[];
  disabled?: boolean;
}) {
  const rail = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<number | null>(null);

  const valueAt = (e: PointerEvent) => {
    const r = rail.current!.getBoundingClientRect();
    return snap(fromPos(ticks, (e.clientX - r.left) / r.width));
  };
  const put = (idx: number, v: number) => {
    const next = values.slice();
    next[idx] = values.length === 2 ? (idx === 0 ? Math.min(v, values[1]) : Math.max(v, values[0])) : v;
    if (next[idx] !== values[idx]) onChange(next);
  };
  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (disabled) return;
    const v = valueAt(e);
    let idx = 0;
    if (values.length === 2) {
      const d0 = Math.abs(toPos(ticks, v) - toPos(ticks, values[0]));
      const d1 = Math.abs(toPos(ticks, v) - toPos(ticks, values[1]));
      // 两个手柄叠在一起时，往右点抓右边那个
      idx = d1 < d0 || (d1 === d0 && v > values[1]) ? 1 : 0;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag(idx);
    put(idx, v);
  };
  const key = (idx: number) => (e: KeyboardEvent) => {
    const k = KEY_STEP[e.key];
    if (!k) return;
    e.preventDefault();
    let v = values[idx];
    for (let i = 0; i < Math.abs(k); i++) v = step(v, Math.sign(k));
    put(idx, v);
  };

  const ps = values.map(v => toPos(ticks, v) * 100);
  const fillLeft = values.length === 2 ? ps[0] : 0;
  const fillWidth = values.length === 2 ? ps[1] - ps[0] : ps[0];
  return (
    <>
      <div ref={rail} className={cn('tr-rs', drag != null && 'drag')} aria-disabled={disabled}
           onPointerDown={down} onPointerMove={e => { if (drag != null) put(drag, valueAt(e)); }}
           onPointerUp={() => setDrag(null)} onPointerCancel={() => setDrag(null)}>
        <i className="tr-rs-t" />
        <i className="tr-rs-f" style={{ left: `${fillLeft}%`, width: `${fillWidth}%` }} />
        {values.map((v, i) => (
          <button key={i} type="button" role="slider" aria-label={ariaLabels[i]} aria-valuenow={v}
                  aria-valuemin={ticks[0]} aria-valuemax={ticks[ticks.length - 1]} disabled={disabled}
                  className={cn('tr-rs-h', drag === i && 'act')} style={{ left: `${ps[i]}%` }} onKeyDown={key(i)} />
        ))}
      </div>
      <div className="tr-ticks num">
        {ticks.map((v, i) => <span key={v} style={{ left: `${i / (ticks.length - 1) * 100}%` }}>{tickLabel(v)}</span>)}
      </div>
    </>
  );
}

const DAY_MIN = 1440;
/** 时间尺底下的小时刻度 */
const AXIS_HOURS = [0, 3, 6, 9, 12, 15, 18, 21, 24];
const KEY_MIN: Record<string, number> = { ArrowLeft: -5, ArrowDown: -5, ArrowRight: 5, ArrowUp: 5, PageDown: -60, PageUp: 60 };

const circDist = (a: number, b: number) => {
  const d = Math.abs(a - b);
  return Math.min(d, DAY_MIN - d);
};

/**
 * 唤醒时段的 24 小时尺（北京时间）：拖两端改起止，5 分钟一档；时段里会醒的心跳是深色刻度。
 * 尺子上还标着现在几点、每天 08:00 的复盘（复盘不看时段）
 */
export function WakeWindowRuler({ win, interval, onChange }: {
  win: WakeWin; interval: string; onChange: (w: WakeWin) => void;
}) {
  const { t } = useTranslation('ai');
  const band = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<'from' | 'to' | null>(null);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const minAt = (e: PointerEvent) => {
    const r = band.current!.getBoundingClientRect();
    const p = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    return Math.min(DAY_MIN - 5, Math.round(p * DAY_MIN / 5) * 5);
  };
  const put = (which: 'from' | 'to', m: number) => {
    if (win[which] !== m) onChange({ ...win, [which]: m });
  };
  const down = (e: PointerEvent<HTMLDivElement>) => {
    const m = minAt(e);
    const which = circDist(m, win.from) <= circDist(m, win.to) ? 'from' : 'to';
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag(which);
    put(which, m);
  };
  const key = (which: 'from' | 'to') => (e: KeyboardEvent) => {
    const k = KEY_MIN[e.key];
    if (!k) return;
    e.preventDefault();
    put(which, ((win[which] + k) % DAY_MIN + DAY_MIN) % DAY_MIN);
  };

  const pct = (m: number) => `${m / DAY_MIN * 100}%`;
  const segs = win.from <= win.to ? [[win.from, win.to]] : [[win.from, DAY_MIN], [0, win.to]];
  const step = INTERVAL_MIN[interval];
  const beats = Array.from({ length: DAY_MIN / step }, (_, i) => i * step);
  const nowMin = bjMinute(now);
  return (
    <>
      <div className="tr-band-w">
        <div ref={band} className="tr-band" onPointerDown={down}
             onPointerMove={e => { if (drag) put(drag, minAt(e)); }}
             onPointerUp={() => setDrag(null)} onPointerCancel={() => setDrag(null)}>
          {segs.map(([a, b]) => <i key={a} className="tr-band-seg" style={{ left: pct(a), width: pct(b - a) }} />)}
          {beats.map(m => (
            <i key={m} className={cn('tr-beat', inWindow(win, m) && 'on', step === 15 && 'thin')} style={{ left: pct(m) }} />
          ))}
          <i className="tr-now" style={{ left: pct(nowMin) }}><span className="num">{t('my.window.now', { time: minToHm(nowMin) })}</span></i>
          {(['from', 'to'] as const).map(w => (
            <button key={w} type="button" className="tr-hdl" style={{ left: pct(win[w]) }} onKeyDown={key(w)}
                    aria-label={t(w === 'from' ? 'my.window.fromAria' : 'my.window.toAria', { time: minToHm(win[w]) })}>
              <b>{minToHm(win[w])}</b>
            </button>
          ))}
        </div>
      </div>
      <div className="tr-axis">
        {AXIS_HOURS.map(h => <span key={h} style={{ left: pct(h * 60) }}>{String(h).padStart(2, '0')}</span>)}
        <span className="rev" style={{ left: pct(REVIEW_MIN) }}><i />{t('my.window.review')}</span>
      </div>
    </>
  );
}
