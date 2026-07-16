import { useEffect, useRef, useState } from 'react';
import styled from 'styled-components';

/**
 * 合约杠杆滑杆（拟物·收敛版）：细凹槽轨道 + 风险色净色填充 + 小旋钮，拖拽跟随数值气泡。
 * 档位刻痕/标签可点击跳档；键盘全支持。
 *
 * 交互正确性：拖拽去重用事件侧同步 ref（liveRef），不依赖渲染时序——
 * 旧版在渲染期写 ref 再拿它去重，事件与渲染一错位就吞掉移动事件（表现为满档回拖卡死）。
 * 性能：拖拽期组件内 liveValue 渲染，对父组件 onChange 按 rAF 节流、松手必提交终值。
 */
interface LeverageSliderProps {
  value: number;
  max: number;        // 当前币种最高杠杆（档位未加载时可能为 0）
  ticks: number[];    // 可点击档位（已按 max 过滤）
  onChange: (v: number) => void;
}

const GAIN = '#089981';
const MID = '#F97316';
const LOSS = '#f23645';

/** 当前杠杆占比对应的风险色（填充/thumb 内环/气泡用） */
function riskColor(ratio: number): string {
  return ratio < 0.35 ? GAIN : ratio < 0.7 ? MID : LOSS;
}

export function LeverageSlider({ value, max, ticks, onChange }: LeverageSliderProps) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  // 拖拽期本地值：非 null 时渲染以它为准；liveRef 是它的事件侧同步镜像（去重专用）
  const [liveValue, setLiveValue] = useState<number | null>(null);
  const liveRef = useRef<number | null>(null);
  const rafRef = useRef(0);
  const pendingRef = useRef(value);
  const lastSentRef = useRef(value);

  const shown = liveValue ?? value;
  const disabled = max <= 1;

  const span = Math.max(max - 1, 1);
  const ratio = Math.min(1, Math.max(0, (shown - 1) / span));
  const pct = ratio * 100;
  const atMax = max > 1 && shown >= max;
  const color = riskColor(ratio);

  // 卸载时丢弃未 flush 的 rAF 提交
  useEffect(() => () => cancelAnimationFrame(rafRef.current), []);

  const setLive = (v: number) => {
    liveRef.current = v;
    setLiveValue(v);
  };

  /** 立即提交给父组件（去重：同值不重复触发父页渲染） */
  const commit = (v: number) => {
    if (v !== lastSentRef.current) {
      lastSentRef.current = v;
      onChange(v);
    }
  };
  /** 拖拽期节流提交：每动画帧至多一次，父页渲染频率被封顶 */
  const scheduleCommit = (v: number) => {
    pendingRef.current = v;
    if (!rafRef.current) {
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = 0;
        commit(pendingRef.current);
      });
    }
  };

  const valueFromClientX = (clientX: number) => {
    const rect = trackRef.current!.getBoundingClientRect();
    const r = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    return Math.max(1, Math.round(1 + r * span));
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (disabled) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragging(true);
    const next = valueFromClientX(e.clientX);
    setLive(next);
    scheduleCommit(next);
  };
  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging) return;
    const next = valueFromClientX(e.clientX);
    if (next === liveRef.current) return;
    setLive(next);
    scheduleCommit(next);
  };
  const stopDrag = () => {
    if (!dragging) return;
    setDragging(false);
    // 终值必达父组件，然后交还受控权
    cancelAnimationFrame(rafRef.current);
    rafRef.current = 0;
    commit(pendingRef.current);
    liveRef.current = null;
    setLiveValue(null);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (disabled) return;
    let next: number | null = null;
    switch (e.key) {
      case 'ArrowRight': case 'ArrowUp': next = shown + 1; break;
      case 'ArrowLeft': case 'ArrowDown': next = shown - 1; break;
      case 'PageUp': next = shown + 10; break;
      case 'PageDown': next = shown - 10; break;
      case 'Home': next = 1; break;
      case 'End': next = max; break;
      default: return;
    }
    e.preventDefault();
    commit(Math.min(max, Math.max(1, next)));
  };

  return (
    <Wrapper className={`${dragging ? 'dragging' : ''} ${disabled ? 'disabled' : ''}`} $color={color}>
      <div
        className="hit-area"
        ref={trackRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={stopDrag}
        onPointerCancel={stopDrag}
      >
        <div className="track">
          <div className="fill" style={{ width: `${pct}%` }} />
          {ticks.map(t => {
            const tp = ((t - 1) / span) * 100;
            return (
              <button
                key={t}
                type="button"
                className={`tick-notch ${shown >= t ? 'passed' : ''}`}
                style={{ left: `${tp}%` }}
                onClick={() => commit(t)}
                tabIndex={-1}
                aria-label={`${t}x`}
              />
            );
          })}
          <div
            className={`thumb ${atMax ? 'at-max' : ''}`}
            style={{ left: `${pct}%` }}
            role="slider"
            tabIndex={disabled ? -1 : 0}
            aria-valuemin={1}
            aria-valuemax={max}
            aria-valuenow={shown}
            aria-label="杠杆"
            onKeyDown={handleKeyDown}
          >
            {(dragging || atMax) && (
              <span className={`bubble ${atMax ? 'max' : ''}`}>{atMax ? 'MAX' : `${shown}x`}</span>
            )}
          </div>
        </div>
      </div>
      <div className="tick-labels">
        {ticks.map(t => {
          const tp = ((t - 1) / span) * 100;
          return (
            <button
              key={t}
              type="button"
              className={`tick-label ${shown === t ? 'active' : ''}`}
              style={{ left: `${tp}%` }}
              onClick={() => commit(t)}
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
  &.disabled .hit-area { cursor: default; }

  .hit-area {
    padding: 10px 0;
    cursor: pointer;
    touch-action: none; /* 拖拽时禁掉页面滚动 */
  }

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

  /* 档位刻痕：槽内细竖痕，越过后在填充上显白 */
  .tick-notch {
    position: absolute;
    top: 50%;
    width: 14px;
    height: 18px;
    padding: 0;
    border: 0;
    transform: translate(-50%, -50%);
    background: transparent;
    cursor: pointer;
    z-index: 1;
  }
  .tick-notch::after {
    content: '';
    position: absolute;
    left: 50%;
    top: 50%;
    width: 1.5px;
    height: 4px;
    border-radius: 1px;
    transform: translate(-50%, -50%);
    background: color-mix(in srgb, var(--color-muted-foreground, #9ca3af) 45%, transparent);
    transition: background 0.2s;
  }
  .tick-notch.passed::after { background: rgba(255, 255, 255, 0.8); }

  /* 旋钮：小而实的白色圆钮 + 风险色细环 */
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
    cursor: grab;
    outline: none;
    z-index: 2;
    transition: left 0.18s ease-out, border-color 0.2s ease, transform 0.15s ease;
  }
  .thumb:hover { transform: translate(-50%, -50%) scale(1.12); }
  .thumb:focus-visible {
    box-shadow:
      0 0 0 3px color-mix(in srgb, var(--risk) 30%, transparent),
      2px 2px 5px var(--shadow-dark);
  }
  &.dragging .thumb {
    cursor: grabbing;
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
