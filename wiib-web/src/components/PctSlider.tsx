import { useState } from 'react';
import styled from 'styled-components';

/**
 * 百分比滑杆（拟物·LeverageSlider 同款简化）：细凹槽轨道 + 净色填充 + 0/25/50/75/100 刻痕与可点标签，
 * 拖拽跟随数值气泡。交互层为透明原生 range（28px thumb 热区 + touch-action:none，
 * 手机拖动不带滚页面），视觉层纯跟随 value。
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
    <Wrapper className={dragging ? 'dragging' : ''} $color={color}>
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
    </Wrapper>
  );
}

const Wrapper = styled.div<{ $color: string }>`
  --pct-color: ${({ $color }) => $color};

  position: relative;
  /* 左右留出 thumb 半径，避免两端溢出裁切 */
  padding: 0 10px;

  .slider-area {
    position: relative;
    padding: 8px 0;
  }

  /* 原生 range 铺满轨道区，透明但可交互：拖拽/点击跳值/方向键全部原生 */
  .native {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    opacity: 0;
    cursor: pointer;
    -webkit-appearance: none;
    appearance: none;
    touch-action: none; /* 拖拽时禁掉页面滚动 */
  }
  .native::-webkit-slider-thumb { -webkit-appearance: none; width: 28px; height: 28px; }
  .native::-moz-range-thumb { width: 28px; height: 28px; border: 0; }

  .track {
    position: relative;
    height: 6px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--color-muted, #e5e7eb) 85%, transparent);
    box-shadow:
      inset 1.5px 1.5px 4px var(--shadow-dark),
      inset -1.5px -1.5px 3px var(--shadow-light);
  }

  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: 999px;
    background: var(--pct-color);
    box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.3);
    transition: width 0.18s ease-out;
  }
  &.dragging .fill { transition: none; }

  .tick-notch {
    position: absolute;
    left: 0;
    top: 50%;
    width: 1.5px;
    height: 4px;
    border-radius: 1px;
    transform: translate(-50%, -50%);
    background: color-mix(in srgb, var(--color-muted-foreground, #9ca3af) 45%, transparent);
    transition: background 0.2s;
    pointer-events: none;
  }
  .tick-notch.passed { background: rgba(255, 255, 255, 0.8); }

  .thumb {
    position: absolute;
    top: 50%;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    transform: translate(-50%, -50%);
    background: var(--color-card, #fff);
    border: 2px solid var(--pct-color);
    box-shadow:
      2px 2px 5px var(--shadow-dark),
      -2px -2px 4px var(--shadow-light);
    pointer-events: none;
    z-index: 1;
    transition: left 0.18s ease-out, transform 0.15s ease;
  }
  .slider-area:hover .thumb { transform: translate(-50%, -50%) scale(1.1); }
  .slider-area:has(.native:focus-visible) .thumb {
    box-shadow:
      0 0 0 3px color-mix(in srgb, var(--pct-color) 30%, transparent),
      2px 2px 5px var(--shadow-dark);
  }
  &.dragging .thumb {
    transform: translate(-50%, -50%) scale(1.15);
    transition-property: transform; /* 拖拽中 left 不过渡，跟手 */
  }

  .bubble {
    position: absolute;
    bottom: calc(100% + 8px);
    left: 50%;
    transform: translateX(-50%);
    padding: 2px 8px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 800;
    line-height: 1.4;
    white-space: nowrap;
    color: #fff;
    background: var(--pct-color);
    box-shadow: 0 2px 6px color-mix(in srgb, var(--pct-color) 40%, transparent);
    font-variant-numeric: tabular-nums;
    pointer-events: none;
    animation: pct-pop 0.16s ease-out;
  }
  .bubble::after {
    content: '';
    position: absolute;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    border: 4px solid transparent;
    border-top-color: var(--pct-color);
  }

  .tick-labels {
    position: relative;
    height: 16px;
    margin-top: 2px;
  }
  .tick-label {
    position: absolute;
    top: 0;
    padding: 1px 4px;
    border: 0;
    border-radius: 999px;
    background: transparent;
    transform: translateX(-50%);
    font-size: 10px;
    font-weight: 600;
    color: var(--color-muted-foreground, #9ca3af);
    cursor: pointer;
    transition: color 0.2s, background 0.2s, box-shadow 0.2s;
    font-variant-numeric: tabular-nums;
    line-height: 1.5;
  }
  .tick-label:hover { color: var(--color-foreground, #111827); }
  .tick-label.active {
    color: var(--pct-color);
    font-weight: 800;
    background: color-mix(in srgb, var(--color-muted, #e5e7eb) 70%, transparent);
    box-shadow: inset 1px 1px 2px var(--shadow-dark), inset -1px -1px 2px var(--shadow-light);
  }

  @keyframes pct-pop {
    0% { transform: translateX(-50%) scale(0.5); opacity: 0; }
    100% { transform: translateX(-50%) scale(1); opacity: 1; }
  }
`;
