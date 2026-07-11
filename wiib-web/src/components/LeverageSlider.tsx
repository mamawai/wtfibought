import { useEffect, useRef, useState } from 'react';
import styled from 'styled-components';

/**
 * 合约杠杆动效滑条：
 * - 轨道凹槽（neu-inset 质感）+ 随杠杆升高从绿→橙→红的渐变填充
 * - 档位刻度点/标签可点击直接跳档
 * - 拖拽时 thumb 弹性放大并跟随数值气泡
 * - 拉满瞬间：火花粒子喷发 + thumb 弹跳 + MAX 徽章常驻脉冲 + 移动端震动
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

/** 当前杠杆占比对应的风险色（thumb 描边/气泡用） */
function riskColor(ratio: number): string {
  return ratio < 0.35 ? GAIN : ratio < 0.7 ? MID : LOSS;
}

const SPARK_COLORS = [LOSS, MID, '#fbbf24'];
const SPARK_COUNT = 12;

export function LeverageSlider({ value, max, ticks, onChange }: LeverageSliderProps) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  const [burstKey, setBurstKey] = useState(0);

  const span = Math.max(max - 1, 1);
  const ratio = Math.min(1, Math.max(0, (value - 1) / span));
  const pct = ratio * 100;
  const atMax = max > 1 && value >= max;
  const color = riskColor(ratio);

  // 拉满边沿检测：从非满档进入满档才触发爆发（点档位/拖拽/键盘都走这里）
  const emit = (next: number) => {
    if (max > 1 && next >= max && value < max) {
      setBurstKey(k => k + 1);
      navigator.vibrate?.(40);
    }
    onChange(next);
  };
  // 粒子放完自动卸载，避免残留 DOM
  useEffect(() => {
    if (!burstKey) return;
    const t = setTimeout(() => setBurstKey(0), 900);
    return () => clearTimeout(t);
  }, [burstKey]);

  const valueFromClientX = (clientX: number) => {
    const rect = trackRef.current!.getBoundingClientRect();
    const r = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    return Math.max(1, Math.round(1 + r * span));
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (max <= 1) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragging(true);
    emit(valueFromClientX(e.clientX));
  };
  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging) return;
    emit(valueFromClientX(e.clientX));
  };
  const stopDrag = () => setDragging(false);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (max <= 1) return;
    let next: number | null = null;
    switch (e.key) {
      case 'ArrowRight': case 'ArrowUp': next = value + 1; break;
      case 'ArrowLeft': case 'ArrowDown': next = value - 1; break;
      case 'PageUp': next = value + 10; break;
      case 'PageDown': next = value - 10; break;
      case 'Home': next = 1; break;
      case 'End': next = max; break;
      default: return;
    }
    e.preventDefault();
    emit(Math.min(max, Math.max(1, next)));
  };

  return (
    <Wrapper className={dragging ? 'dragging' : ''} $color={color}>
      <div
        className="hit-area"
        ref={trackRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={stopDrag}
        onPointerCancel={stopDrag}
      >
        <div className="track">
          <div
            className="fill"
            style={{
              width: `${pct}%`,
              // 渐变始终铺满整条轨道宽度，填充只是"揭开"它
              backgroundSize: pct > 0 ? `${10000 / pct}% 100%` : '100% 100%',
            }}
          >
            {atMax && <div className="shimmer" />}
          </div>
          {ticks.map(t => {
            const tp = ((t - 1) / span) * 100;
            return (
              <button
                key={t}
                type="button"
                className={`tick-dot ${value >= t ? 'passed' : ''}`}
                style={{ left: `${tp}%` }}
                onClick={() => emit(t)}
                tabIndex={-1}
                aria-label={`${t}x`}
              />
            );
          })}
          <div
            className={`thumb ${atMax ? 'at-max' : ''} ${burstKey ? 'bounce' : ''}`}
            style={{ left: `${pct}%` }}
            role="slider"
            tabIndex={0}
            aria-valuemin={1}
            aria-valuemax={max}
            aria-valuenow={value}
            aria-label="杠杆"
            onKeyDown={handleKeyDown}
          >
            {dragging && !atMax && <span className="bubble">{value}x</span>}
            {atMax && <span className="max-chip">MAX</span>}
          </div>
          {burstKey > 0 && (
            <span className="burst" key={burstKey} style={{ left: `${pct}%` }}>
              {Array.from({ length: SPARK_COUNT }, (_, i) => (
                <span
                  key={i}
                  className="spark"
                  style={{
                    // 伪随机方向/距离：137° 黄金角错开，视觉均匀又不呆板
                    ['--a' as string]: `${(i * 137) % 360}deg`,
                    ['--d' as string]: `${26 + (i % 3) * 9}px`,
                    background: SPARK_COLORS[i % SPARK_COLORS.length],
                    animationDelay: `${i * 14}ms`,
                  }}
                />
              ))}
            </span>
          )}
        </div>
      </div>
      <div className="tick-labels">
        {ticks.map(t => {
          const tp = ((t - 1) / span) * 100;
          return (
            <button
              key={t}
              type="button"
              className={`tick-label ${value === t ? 'active' : ''}`}
              style={{ left: `${tp}%` }}
              onClick={() => emit(t)}
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
  padding: 0 11px;

  .hit-area {
    padding: 10px 0;
    cursor: pointer;
    touch-action: none; /* 拖拽时禁掉页面滚动 */
  }

  .track {
    position: relative;
    height: 10px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--color-muted, #e5e7eb) 80%, transparent);
    box-shadow: inset 2px 2px 4px var(--shadow-dark), inset -2px -2px 4px var(--shadow-light);
  }

  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: 999px;
    background-image: linear-gradient(90deg, ${GAIN} 0%, ${MID} 55%, ${LOSS} 100%);
    background-repeat: no-repeat;
    overflow: hidden;
    transition: width 0.22s cubic-bezier(0.34, 1.3, 0.64, 1);
  }

  .shimmer {
    position: absolute;
    inset: 0;
    background: linear-gradient(110deg, transparent 30%, rgba(255, 255, 255, 0.45) 50%, transparent 70%);
    background-size: 200% 100%;
    animation: lever-shimmer 1.4s linear infinite;
  }

  .tick-dot {
    position: absolute;
    top: 50%;
    width: 14px;
    height: 14px;
    padding: 0;
    border: 0;
    transform: translate(-50%, -50%);
    background: transparent;
    cursor: pointer;
  }
  .tick-dot::after {
    content: '';
    position: absolute;
    inset: 4px;
    border-radius: 50%;
    background: var(--color-muted-foreground, #9ca3af);
    opacity: 0.55;
    transition: background 0.2s, opacity 0.2s;
  }
  .tick-dot.passed::after {
    background: rgba(255, 255, 255, 0.9);
    opacity: 0.95;
  }

  .thumb {
    position: absolute;
    top: 50%;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    transform: translate(-50%, -50%);
    background: var(--color-card, #fff);
    border: 2.5px solid var(--risk);
    box-shadow: 2px 2px 5px var(--shadow-dark), -2px -2px 5px var(--shadow-light);
    cursor: grab;
    outline: none;
    z-index: 2;
    transition:
      left 0.22s cubic-bezier(0.34, 1.3, 0.64, 1),
      border-color 0.2s ease,
      scale 0.18s cubic-bezier(0.34, 1.56, 0.64, 1),
      box-shadow 0.25s ease;
  }
  .thumb:hover { scale: 1.12; }
  .thumb:focus-visible { box-shadow: 0 0 0 3px color-mix(in srgb, var(--risk) 35%, transparent); }

  &.dragging .thumb {
    cursor: grabbing;
    scale: 1.28;
    transition-property: border-color, scale, box-shadow; /* 拖拽中 left 不过渡，跟手 */
  }
  &.dragging .fill { transition: none; }

  .thumb.at-max {
    border-color: ${LOSS};
    animation: lever-glow 1.1s ease-in-out infinite;
  }
  .thumb.bounce { animation: lever-bounce 0.5s cubic-bezier(0.34, 1.56, 0.64, 1), lever-glow 1.1s ease-in-out 0.5s infinite; }

  .bubble,
  .max-chip {
    position: absolute;
    bottom: calc(100% + 8px);
    left: 50%;
    transform: translateX(-50%);
    padding: 2px 8px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 800;
    line-height: 1.4;
    white-space: nowrap;
    color: #fff;
    pointer-events: none;
  }
  .bubble {
    background: var(--risk);
    font-variant-numeric: tabular-nums;
  }
  .max-chip {
    background: linear-gradient(135deg, ${LOSS}, ${MID});
    letter-spacing: 0.05em;
    animation: lever-pop 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  .burst {
    position: absolute;
    top: 50%;
    width: 0;
    height: 0;
    z-index: 3;
    pointer-events: none;
  }
  .spark {
    position: absolute;
    top: -2px;
    left: -2px;
    width: 5px;
    height: 5px;
    border-radius: 2px;
    transform: rotate(var(--a)) translateX(0);
    animation: lever-spark 0.65s cubic-bezier(0.2, 0.7, 0.3, 1) forwards;
  }

  .tick-labels {
    position: relative;
    height: 16px;
    margin-top: 2px;
  }
  .tick-label {
    position: absolute;
    top: 0;
    padding: 0 4px;
    border: 0;
    background: transparent;
    transform: translateX(-50%);
    font-size: 10px;
    font-weight: 600;
    color: var(--color-muted-foreground, #9ca3af);
    cursor: pointer;
    transition: color 0.2s;
    font-variant-numeric: tabular-nums;
  }
  .tick-label:hover { color: var(--color-foreground, #111827); }
  .tick-label.active { color: var(--risk); font-weight: 800; }
  /* 首末标签不越界 */
  .tick-label:first-child { transform: translateX(-6px); }
  .tick-label:last-child { transform: translateX(calc(-100% + 6px)); }

  @keyframes lever-shimmer {
    0% { background-position: 200% 0; }
    100% { background-position: -100% 0; }
  }
  @keyframes lever-glow {
    0%, 100% { box-shadow: 0 0 4px 1px color-mix(in srgb, ${LOSS} 45%, transparent), 2px 2px 5px var(--shadow-dark); }
    50% { box-shadow: 0 0 12px 4px color-mix(in srgb, ${LOSS} 60%, transparent), 2px 2px 5px var(--shadow-dark); }
  }
  @keyframes lever-bounce {
    0% { scale: 1; }
    35% { scale: 1.55; }
    65% { scale: 0.92; }
    100% { scale: 1; }
  }
  @keyframes lever-pop {
    0% { transform: translateX(-50%) scale(0.4); opacity: 0; }
    70% { transform: translateX(-50%) scale(1.18); opacity: 1; }
    100% { transform: translateX(-50%) scale(1); opacity: 1; }
  }
  @keyframes lever-spark {
    0% { transform: rotate(var(--a)) translateX(2px) scale(1); opacity: 1; }
    100% { transform: rotate(var(--a)) translateX(var(--d)) scale(0.25); opacity: 0; }
  }
`;
