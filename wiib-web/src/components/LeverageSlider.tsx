import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import './LeverageSlider.css';

/**
 * 杠杆滑杆：一根细轨 + 圆旋钮 + 下面一排可点档位（样式见 LeverageSlider.css）。
 *
 * 档位在轨道上等距排（1/10/25/50… 挨个占一格），段内线性取整——不然 5x 的旋钮会贴在最左边，
 * 跟它自己的标签对不上。交互层是一条透明的原生 <input type="range">（盖住轨道区）跑位置值，
 * 拖拽/点击/aria 全部由浏览器原生实现。
 * 旋钮跟手不加缓动，点档位才滑过去；方向键一次挪 1x。
 */
interface LeverageSliderProps {
  value: number;
  max: number;        // 当前币种最高杠杆（档位未加载时可能为 0）
  ticks: number[];    // 可点击档位（已按 min/max 过滤）
  onChange: (v: number) => void;
  min?: number;       // 下限，默认 1；持仓调杠杆时逐仓只能调高（下限=当前杠杆）
}

/** 原生 range 的位置分辨率：0..1000 映射轨道全长 */
const RES = 1000;

/** 方向键一次挪几 x */
const KEY_STEP: Record<string, number> = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1 };

export function LeverageSlider({ value, max, ticks, onChange, min = 1 }: LeverageSliderProps) {
  const { t } = useTranslation('trade');
  // 点档位时旋钮滑过去，拖的时候跟手
  const [glide, setGlide] = useState(false);
  const hi = Math.max(max, min);
  const disabled = hi <= min;
  // 轨道上的节点：档位 + 两端（有的币最高杠杆不在常规档位里，补成最后一格）
  const nodes = [...new Set([min, ...ticks.filter(v => v >= min && v <= hi), hi])].sort((a, b) => a - b);
  const seg = nodes.length - 1;

  /** 杠杆值 → 位置 0..1 */
  const posOf = (v: number) => {
    if (seg <= 0) return 0;
    if (v <= nodes[0]) return 0;
    if (v >= nodes[seg]) return 1;
    let i = 0;
    while (i < seg - 1 && v >= nodes[i + 1]) i++;
    return (i + (v - nodes[i]) / (nodes[i + 1] - nodes[i])) / seg;
  };
  /** 位置 0..1 → 杠杆值（整数） */
  const valueAt = (p: number) => {
    if (seg <= 0) return nodes[0];
    const x = Math.max(0, Math.min(1, p)) * seg;
    const i = Math.min(seg - 1, Math.floor(x));
    return Math.round(nodes[i] + (x - i) * (nodes[i + 1] - nodes[i]));
  };

  const set = (v: number, slide: boolean) => {
    setGlide(slide);
    onChange(Math.min(hi, Math.max(min, v)));
  };

  const pct = posOf(value) * 100;

  return (
    <div className={cn('sl', glide && 'glide', disabled && 'off')}>
      <div className="rail">
        <input
          type="range"
          className="hit"
          min={0}
          max={RES}
          step={1}
          value={Math.round(posOf(value) * RES)}
          disabled={disabled}
          aria-label={t('lev.label')}
          aria-valuetext={`${value}x`}
          onChange={e => set(valueAt(Number(e.target.value) / RES), false)}
          onKeyDown={e => {
            const d = KEY_STEP[e.key];
            if (d) { e.preventDefault(); set(value + d, false); }
            else if (e.key === 'Home' || e.key === 'End') { e.preventDefault(); set(e.key === 'Home' ? min : hi, true); }
          }}
        />
        <i className="fi" style={{ width: `${pct}%` }} />
        <i className="kn" style={{ left: `${pct}%` }} />
      </div>
      {/* 档位跟旋钮共用 posOf 定位，各自居中钉在自己那格上 */}
      <div className="ticks">
        {nodes.map(node => (
          <button
            key={node}
            type="button"
            disabled={disabled}
            aria-pressed={value === node}
            style={{ left: `${posOf(node) * 100}%` }}
            onClick={() => set(node, true)}
          >
            {node}
          </button>
        ))}
      </div>
    </div>
  );
}
