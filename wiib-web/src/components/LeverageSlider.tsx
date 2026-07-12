import { useEffect, useRef, useState } from 'react';
import styled from 'styled-components';

/**
 * 合约杠杆滑杆（拟物）：
 * - 凹槽轨道（内嵌刻痕档位）+ 随杠杆升高绿→橙→红的渐变"液柱"填充
 * - 旋钮式 thumb（外圈软凸起 + 风险色内环 + 中心点），拖拽跟随数值气泡
 * - 档位刻痕/标签可点击跳档；键盘全支持；拉满瞬间火花爆发 + MAX 徽章 + 移动端震动
 *
 * 性能：拖拽期间用组件内 liveValue 渲染（只重渲染滑杆自身），对父组件的 onChange
 * 按 rAF 节流、松手必提交终值——父页面再重（Coin 页千行+图表）拖动也不掉帧。
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

/** 当前杠杆占比对应的风险色（thumb 内环/气泡用） */
function riskColor(ratio: number): string {
  return ratio < 0.35 ? GAIN : ratio < 0.7 ? MID : LOSS;
}

const SPARK_COLORS = [LOSS, MID, '#fbbf24'];
const SPARK_COUNT = 12;

export function LeverageSlider({ value, max, ticks, onChange }: LeverageSliderProps) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  // 拖拽期本地值：非 null 时渲染以它为准，父页 value 更新与否都不影响跟手
  const [liveValue, setLiveValue] = useState<number | null>(null);
  const [burstKey, setBurstKey] = useState(0);
  const rafRef = useRef(0);
  const pendingRef = useRef(value);
  const lastSentRef = useRef(value);
  const shownRef = useRef(value);

  const shown = liveValue ?? value;
  shownRef.current = shown;
  const disabled = max <= 1;

  const span = Math.max(max - 1, 1);
  const ratio = Math.min(1, Math.max(0, (shown - 1) / span));
  const pct = ratio * 100;
  const atMax = max > 1 && shown >= max;
  const color = riskColor(ratio);

  // 拉满边沿检测：从非满档进入满档才触发爆发（点档位/拖拽/键盘都走这里）
  const detectBurst = (next: number) => {
    if (max > 1 && next >= max && shownRef.current < max) {
      setBurstKey(k => k + 1);
      navigator.vibrate?.(40);
    }
  };
  // 粒子放完自动卸载，避免残留 DOM
  useEffect(() => {
    if (!burstKey) return;
    const t = setTimeout(() => setBurstKey(0), 900);
    return () => clearTimeout(t);
  }, [burstKey]);
  // 卸载时丢弃未 flush 的 rAF 提交
  useEffect(() => () => cancelAnimationFrame(rafRef.current), []);

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
  /** 非拖拽入口（点档位/键盘）：边沿检测 + 直接提交 */
  const emit = (next: number) => {
    detectBurst(next);
    commit(next);
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
    detectBurst(next);
    setLiveValue(next);
    scheduleCommit(next);
  };
  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging) return;
    const next = valueFromClientX(e.clientX);
    if (next === shownRef.current) return;
    detectBurst(next);
    setLiveValue(next);
    scheduleCommit(next);
  };
  const stopDrag = () => {
    if (!dragging) return;
    setDragging(false);
    // 终值必达父组件，然后交还受控权
    cancelAnimationFrame(rafRef.current);
    rafRef.current = 0;
    commit(pendingRef.current);
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
    emit(Math.min(max, Math.max(1, next)));
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
                className={`tick-notch ${shown >= t ? 'passed' : ''}`}
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
            tabIndex={disabled ? -1 : 0}
            aria-valuemin={1}
            aria-valuemax={max}
            aria-valuenow={shown}
            aria-label="杠杆"
            onKeyDown={handleKeyDown}
          >
            {dragging && !atMax && <span className="bubble">{shown}x</span>}
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
              className={`tick-label ${shown === t ? 'active' : ''}`}
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
  padding: 0 12px;

  &.disabled { opacity: 0.45; }
  &.disabled .hit-area { cursor: default; }

  .hit-area {
    padding: 10px 0;
    cursor: pointer;
    touch-action: none; /* 拖拽时禁掉页面滚动 */
  }

  /* 凹槽：双内阴影 + 底缘 1px 高光，读作"车出来的槽"而非扁平色条 */
  .track {
    position: relative;
    height: 12px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--color-muted, #e5e7eb) 85%, transparent);
    box-shadow:
      inset 2px 2px 5px var(--shadow-dark),
      inset -2px -2px 4px var(--shadow-light),
      0 1px 0 var(--shadow-light);
  }

  /* 液柱：渐变揭开式填充 + 顶部高光弧，像槽里注入的有体积的液体 */
  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: 999px;
    background-image: linear-gradient(90deg, ${GAIN} 0%, ${MID} 55%, ${LOSS} 100%);
    background-repeat: no-repeat;
    overflow: hidden;
    box-shadow: inset 0 2px 2px rgba(255, 255, 255, 0.25), inset 0 -2px 3px rgba(0, 0, 0, 0.18);
    transition: width 0.22s cubic-bezier(0.34, 1.3, 0.64, 1);
  }

  .shimmer {
    position: absolute;
    inset: 0;
    background: linear-gradient(110deg, transparent 30%, rgba(255, 255, 255, 0.45) 50%, transparent 70%);
    background-size: 200% 100%;
    animation: lever-shimmer 1.4s linear infinite;
  }

  /* 档位刻痕：槽内竖向凹痕（暗上缘+亮下缘），已越过的在液柱上显白 */
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
    width: 2px;
    height: 6px;
    border-radius: 1px;
    transform: translate(-50%, -50%);
    background: color-mix(in srgb, var(--color-muted-foreground, #9ca3af) 55%, transparent);
    box-shadow: 0 1px 0 var(--shadow-light);
    transition: background 0.2s;
  }
  .tick-notch.passed::after {
    background: rgba(255, 255, 255, 0.85);
    box-shadow: 0 1px 1px rgba(0, 0, 0, 0.25);
  }

  /* 旋钮：软凸起外壳 + 风险色内环 + 中心点，拟物件里的"实体旋钮" */
  .thumb {
    position: absolute;
    top: 50%;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    transform: translate(-50%, -50%);
    background: var(--color-card, #fff);
    box-shadow:
      3px 3px 6px var(--shadow-dark),
      -3px -3px 6px var(--shadow-light),
      inset 1px 1px 1px rgba(255, 255, 255, 0.6);
    cursor: grab;
    outline: none;
    z-index: 2;
    transition:
      left 0.22s cubic-bezier(0.34, 1.3, 0.64, 1),
      scale 0.18s cubic-bezier(0.34, 1.56, 0.64, 1),
      box-shadow 0.25s ease;
  }
  .thumb::before {
    content: '';
    position: absolute;
    inset: 4px;
    border-radius: 50%;
    border: 2px solid var(--risk);
    transition: border-color 0.2s ease;
  }
  .thumb::after {
    content: '';
    position: absolute;
    inset: 9px;
    border-radius: 50%;
    background: var(--risk);
    transition: background 0.2s ease;
  }
  .thumb:hover { scale: 1.1; }
  .thumb:focus-visible {
    box-shadow:
      0 0 0 3px color-mix(in srgb, var(--risk) 35%, transparent),
      3px 3px 6px var(--shadow-dark);
  }

  &.dragging .thumb {
    cursor: grabbing;
    scale: 1.25;
    box-shadow:
      4px 4px 9px var(--shadow-dark),
      -3px -3px 7px var(--shadow-light),
      inset 1px 1px 1px rgba(255, 255, 255, 0.6);
    transition-property: scale, box-shadow; /* 拖拽中 left 不过渡，跟手 */
  }
  &.dragging .fill { transition: none; }

  .thumb.at-max::before { border-color: ${LOSS}; }
  .thumb.at-max::after { background: ${LOSS}; }
  .thumb.at-max { animation: lever-glow 1.1s ease-in-out infinite; }
  .thumb.bounce { animation: lever-bounce 0.5s cubic-bezier(0.34, 1.56, 0.64, 1), lever-glow 1.1s ease-in-out 0.5s infinite; }

  /* 数值气泡：带指向箭头，风险色底 */
  .bubble,
  .max-chip {
    position: absolute;
    bottom: calc(100% + 9px);
    left: 50%;
    transform: translateX(-50%);
    padding: 2px 8px;
    border-radius: 7px;
    font-size: 11px;
    font-weight: 800;
    line-height: 1.4;
    white-space: nowrap;
    color: #fff;
    pointer-events: none;
  }
  .bubble {
    background: var(--risk);
    box-shadow: 0 2px 6px color-mix(in srgb, var(--risk) 45%, transparent);
    font-variant-numeric: tabular-nums;
    animation: lever-pop 0.18s cubic-bezier(0.34, 1.56, 0.64, 1);
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
  .max-chip {
    background: linear-gradient(135deg, ${LOSS}, ${MID});
    letter-spacing: 0.05em;
    box-shadow: 0 2px 8px color-mix(in srgb, ${LOSS} 50%, transparent);
    animation: lever-pop 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  .max-chip::after {
    content: '';
    position: absolute;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    border: 4px solid transparent;
    border-top-color: ${LOSS};
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
    height: 18px;
    margin-top: 3px;
  }
  /* 标签与刻痕同一坐标系居中，不再对首末标签做特殊位移（对不齐正是廉价感来源之一） */
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

  @keyframes lever-shimmer {
    0% { background-position: 200% 0; }
    100% { background-position: -100% 0; }
  }
  @keyframes lever-glow {
    0%, 100% { box-shadow: 0 0 4px 1px color-mix(in srgb, ${LOSS} 45%, transparent), 3px 3px 6px var(--shadow-dark); }
    50% { box-shadow: 0 0 12px 4px color-mix(in srgb, ${LOSS} 60%, transparent), 3px 3px 6px var(--shadow-dark); }
  }
  @keyframes lever-bounce {
    0% { scale: 1; }
    35% { scale: 1.55; }
    65% { scale: 0.92; }
    100% { scale: 1; }
  }
  @keyframes lever-pop {
    0% { transform: translateX(-50%) scale(0.4); opacity: 0; }
    70% { transform: translateX(-50%) scale(1.12); opacity: 1; }
    100% { transform: translateX(-50%) scale(1); opacity: 1; }
  }
  @keyframes lever-spark {
    0% { transform: rotate(var(--a)) translateX(2px) scale(1); opacity: 1; }
    100% { transform: rotate(var(--a)) translateX(var(--d)) scale(0.25); opacity: 0; }
  }
`;
