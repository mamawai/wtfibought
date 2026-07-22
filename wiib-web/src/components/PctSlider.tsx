import { useState, type CSSProperties } from 'react';

/**
 * 百分比滑杆（LeverageSlider 同款简化）：机加工细槽 + 净色填充 + 0/25/50/75/100 刻痕与可点标签，
 * 拖拽跟随数值气泡。视觉样式集中在 index.css 的 .wiib-slider（与 LeverageSlider 共用）。
 * 交互层为透明原生 range（28px thumb 热区 + touch-action:none，手机拖动不带滚页面），视觉层纯跟随 value。
 */
const TICKS = [0, 25, 50, 75, 100];

export function PctSlider({ value, onChange, color = 'var(--color-primary)' }: {
  value: number;                 // 0-100
  onChange: (pct: number) => void;
  color?: string;                // 填充/气泡色（CSS 颜色值或变量）
}) {
  const [dragging, setDragging] = useState(false);
  const pct = Math.min(100, Math.max(0, Math.round(value)));

  return (
    <div
      className={`wiib-slider sm ${dragging ? 'dragging' : ''}`}
      style={{ '--slider-color': color } as CSSProperties}
    >
      <div className="slider-area">
        <div className="track">
          <div className="fill" style={{ width: `${pct}%` }} />
          {TICKS.map(t => (
            <span key={t} className={`tick-notch ${pct >= t ? 'passed' : ''}`} style={{ left: `${t}%` }} />
          ))}
          <div className="thumb" style={{ left: `${pct}%` }}>
            {dragging && <span className="bubble">{pct}%</span>}
          </div>
        </div>
        <input
          type="range"
          className="native"
          min={0}
          max={100}
          step={1}
          value={pct}
          aria-label="百分比"
          onChange={e => onChange(Number(e.target.value))}
          onPointerDown={() => setDragging(true)}
          onPointerUp={() => setDragging(false)}
          onPointerCancel={() => setDragging(false)}
          onBlur={() => setDragging(false)}
        />
      </div>
      <div className="tick-labels">
        {TICKS.map(t => (
          <button key={t} type="button" className={`tick-label ${pct === t ? 'active' : ''}`} style={{ left: `${t}%` }} onClick={() => onChange(t)}>
            {t}%
          </button>
        ))}
      </div>
    </div>
  );
}
