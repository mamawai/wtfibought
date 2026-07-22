import { useState, type CSSProperties } from 'react';

/**
 * 合约杠杆滑杆（精密终端版）：机加工细槽 + 风险色净色填充 + 小旋钮，拖拽跟随数值气泡。
 * 视觉样式集中在 index.css 的 .wiib-slider（与 PctSlider 共用）。
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
const MID = '#f97316';
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
    <div
      className={`wiib-slider ${dragging ? 'dragging' : ''} ${disabled ? 'disabled' : ''}`}
      style={{ '--slider-color': color } as CSSProperties}
    >
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
    </div>
  );
}
