import { useState } from 'react';
import styled from 'styled-components';

/**
 * 合约杠杆滑杆（拟物·收敛版）：细凹槽轨道 + 风险色净色填充 + 小旋钮，拖拽跟随数值气泡。
 *
 * 交互层是一个透明的原生 <input type="range">（绝对定位盖住轨道区），拖拽/点击/键盘/aria
 * 全部由浏览器原生实现——此前两版手写 pointer 事件跟踪都出过"回拖卡住"类 bug，
 * 原生控件从根上消灭这一类问题。轨道/填充/刻痕/旋钮/气泡只是跟随 value 的纯视觉层。
 */
interface LeverageSliderProps {
  value: number;
  max: number;        // 当前币种最高杠杆（档位未加载时可能为 0）
  ticks: number[];    // 可点击档位（已按 min/max 过滤）
  onChange: (v: number) => void;
  min?: number;       // 下限，默认 1；持仓调杠杆时逐仓只能调高（下限=当前杠杆）
}

const GAIN = '#089981';
const MID = '#F97316';
const LOSS = '#f23645';

/** 当前杠杆占比对应的风险色（填充/thumb 内环/气泡用） */
function riskColor(ratio: number): string {
  return ratio < 0.35 ? GAIN : ratio < 0.7 ? MID : LOSS;
}

export function LeverageSlider({ value, max, ticks, onChange, min = 1 }: LeverageSliderProps) {
  // 仅控制气泡显隐与旋钮跟手（拖拽期关掉 left 过渡），不参与取值
  const [dragging, setDragging] = useState(false);

  const disabled = max <= min;
  const span = Math.max(max - min, 1);
  const ratio = Math.min(1, Math.max(0, (value - min) / span));
  const pct = ratio * 100;
  const atMax = max > min && value >= max;
  const color = riskColor(ratio);

  return (
    <Wrapper className={`${dragging ? 'dragging' : ''} ${disabled ? 'disabled' : ''}`} $color={color}>
      <div className="slider-area">
        <div className="track">
          <div className="fill" style={{ width: `${pct}%` }} />
          {ticks.map(t => {
            const tp = ((t - min) / span) * 100;
            return <span key={t} className={`tick-notch ${value >= t ? 'passed' : ''}`} style={{ left: `${tp}%` }} />;
          })}
          <div className={`thumb ${atMax ? 'at-max' : ''}`} style={{ left: `${pct}%` }}>
            {(dragging || atMax) && (
              <span className={`bubble ${atMax ? 'max' : ''}`}>{atMax ? 'MAX' : `${value}x`}</span>
            )}
          </div>
        </div>
        <input
          type="range"
          className="native"
          min={min}
          max={Math.max(max, min)}
          step={1}
          value={value}
          disabled={disabled}
          aria-label="杠杆"
          onChange={e => onChange(Number(e.target.value))}
          onPointerDown={() => setDragging(true)}
          onPointerUp={() => setDragging(false)}
          onPointerCancel={() => setDragging(false)}
          onBlur={() => setDragging(false)}
        />
      </div>
      <div className="tick-labels">
        {ticks.map(t => {
          const tp = ((t - min) / span) * 100;
          return (
            <button
              key={t}
              type="button"
              className={`tick-label ${value === t ? 'active' : ''}`}
              style={{ left: `${tp}%` }}
              onClick={() => onChange(t)}
            >
              {t}x
            </button>
          );
        })}
      </div>
    </Wrapper>
  );
}

const Wrapper = styled.div<{ $color: string }>`
  --risk: ${({ $color }) => $color};

  position: relative;
  /* 左右留出 thumb 半径，避免两端溢出裁切 */
  padding: 0 10px;

  &.disabled { opacity: 0.45; }

  .slider-area {
    position: relative;
    padding: 10px 0;
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
  .native:disabled { cursor: default; }
  /* 原生 thumb 放大到旋钮大小，保证按到旋钮附近就能拖 */
  .native::-webkit-slider-thumb { -webkit-appearance: none; width: 28px; height: 28px; }
  .native::-moz-range-thumb { width: 28px; height: 28px; border: 0; }

  /* 细凹槽：内阴影读作"车出来的槽" */
  .track {
    position: relative;
    height: 8px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--color-muted, #e5e7eb) 85%, transparent);
    box-shadow:
      inset 1.5px 1.5px 4px var(--shadow-dark),
      inset -1.5px -1.5px 3px var(--shadow-light);
  }

  /* 填充：风险色净色 + 顶部细高光，随占比 GAIN→MID→LOSS 整段换色 */
  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: 999px;
    background: var(--risk);
    box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.3);
    transition: width 0.18s ease-out, background-color 0.2s ease;
  }
  &.dragging .fill { transition: background-color 0.2s ease; }

  /* 档位刻痕：槽内细竖痕（纯视觉），越过后在填充上显白；点击跳档由原生 range 与下方标签负责 */
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

  /* 旋钮：小而实的白色圆钮 + 风险色细环（纯视觉，跟随 value） */
  .thumb {
    position: absolute;
    top: 50%;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    transform: translate(-50%, -50%);
    background: var(--color-card, #fff);
    border: 2px solid var(--risk);
    box-shadow:
      2px 2px 5px var(--shadow-dark),
      -2px -2px 4px var(--shadow-light);
    pointer-events: none;
    z-index: 1;
    transition: left 0.18s ease-out, border-color 0.2s ease, transform 0.15s ease;
  }
  .slider-area:hover .thumb { transform: translate(-50%, -50%) scale(1.12); }
  .slider-area:has(.native:focus-visible) .thumb {
    box-shadow:
      0 0 0 3px color-mix(in srgb, var(--risk) 30%, transparent),
      2px 2px 5px var(--shadow-dark);
  }
  &.dragging .thumb {
    transform: translate(-50%, -50%) scale(1.18);
    transition-property: border-color, transform; /* 拖拽中 left 不过渡，跟手 */
  }
  .thumb.at-max { border-color: ${LOSS}; }

  /* 数值气泡：风险色底，带指向箭头；满档常驻显示 MAX */
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
    background: var(--risk);
    box-shadow: 0 2px 6px color-mix(in srgb, var(--risk) 40%, transparent);
    font-variant-numeric: tabular-nums;
    pointer-events: none;
    animation: lever-pop 0.16s ease-out;
  }
  .bubble::after {
    content: '';
    position: absolute;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    border: 4px solid transparent;
    border-top-color: var(--risk);
  }
  .bubble.max {
    background: ${LOSS};
    letter-spacing: 0.05em;
  }
  .bubble.max::after { border-top-color: ${LOSS}; }

  .tick-labels {
    position: relative;
    height: 18px;
    margin-top: 3px;
  }
  .tick-label {
    position: absolute;
    top: 0;
    padding: 1px 5px;
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
    color: var(--risk);
    font-weight: 800;
    background: color-mix(in srgb, var(--color-muted, #e5e7eb) 70%, transparent);
    box-shadow: inset 1px 1px 2px var(--shadow-dark), inset -1px -1px 2px var(--shadow-light);
  }

  @keyframes lever-pop {
    0% { transform: translateX(-50%) scale(0.5); opacity: 0; }
    100% { transform: translateX(-50%) scale(1); opacity: 1; }
  }
`;
