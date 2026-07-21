import styled from 'styled-components';

/**
 * 二选一拟物开关：槽凹进去、滑块凸起来，滑块滑到哪边哪边高亮。
 * 两个档位始终都写在面上——下单界面只显示当前档会让人分不清"这是当前值还是点了会变成的值"。
 *
 * 阴影不自己写，直接挂 index.css 的 .neu-inset / .neu-raised-sm，
 * 那边的暗色收窄高光、手机端缩小 blur 已经调好，跟着全站走。
 */

interface NeuToggleOption<T extends string> {
  value: T;
  label: string;
}

interface NeuToggleProps<T extends string> {
  value: T;
  /** 恰好两档，数组顺序就是左右顺序 */
  options: readonly [NeuToggleOption<T>, NeuToggleOption<T>];
  onChange: (value: T) => void;
  /** sm 给持仓卡那种紧凑内联行用 */
  size?: 'sm' | 'md';
  /** 屏幕阅读器读的组名，如"保证金模式" */
  label: string;
  className?: string;
}

/** pad=槽内边距(滑块四周留的沟)，px=按钮左右内边距，font/weight 对齐被替换的原按钮 */
const SIZES = {
  md: { pad: 3, px: '0.875rem', font: '0.75rem', weight: 700 },
  sm: { pad: 2, px: '0.625rem', font: '0.6875rem', weight: 500 },
} as const;

export function NeuToggle<T extends string>({
  value,
  options,
  onChange,
  size = 'md',
  label,
  className,
}: NeuToggleProps<T>) {
  // 传进来的 value 不在两档里时兜到左档，否则 -1 会把滑块甩出槽外
  const idx = options.findIndex(o => o.value === value);
  const activeIndex = idx < 0 ? 0 : idx;

  return (
    <Wrapper
      className={`neu-inset${className ? ` ${className}` : ''}`}
      $size={size}
      $index={activeIndex}
      role="group"
      aria-label={label}
    >
      {options.map((opt, i) => (
        <button
          key={opt.value}
          type="button"
          aria-pressed={i === activeIndex}
          onClick={() => onChange(opt.value)}
        >
          {opt.label}
        </button>
      ))}
      {/* 滑块必须排在按钮后面：按下拉伸靠 button:active ~ .slider，兄弟选择器只能往后找 */}
      <span className="slider neu-raised-sm" aria-hidden="true" />
    </Wrapper>
  );
}

const Wrapper = styled.div<{ $size: 'sm' | 'md'; $index: number }>`
  position: relative;
  display: inline-grid;
  grid-template-columns: 1fr 1fr;
  padding: ${({ $size }) => SIZES[$size].pad}px;
  border-radius: 999px;
  background: var(--color-card);

  button {
    position: relative;
    z-index: 1;
    padding: 0.25rem ${({ $size }) => SIZES[$size].px};
    border: 0;
    border-radius: 999px;
    background: transparent;
    font-size: ${({ $size }) => SIZES[$size].font};
    font-weight: ${({ $size }) => SIZES[$size].weight};
    line-height: 1rem;
    white-space: nowrap;
    cursor: pointer;
    color: var(--color-muted-foreground);
    transition: color 0.25s ease;
  }

  button[aria-pressed='true'] {
    color: var(--color-primary-foreground);
  }

  button[aria-pressed='false']:hover {
    color: var(--color-foreground);
  }

  button:focus-visible {
    outline: 2px solid var(--color-ring);
    outline-offset: 2px;
  }

  .slider {
    position: absolute;
    z-index: 0;
    top: ${({ $size }) => SIZES[$size].pad}px;
    bottom: ${({ $size }) => SIZES[$size].pad}px;
    left: ${({ $size }) => SIZES[$size].pad}px;
    /* 宽度扣掉一份 pad，translateX(100%) 正好把它推到右格 */
    width: calc(50% - ${({ $size }) => SIZES[$size].pad}px);
    border-radius: 999px;
    background: var(--color-primary);
    transform: translateX(${({ $index }) => $index * 100}%) scaleX(var(--stretch, 1));
    transition: transform 0.3s cubic-bezier(0.18, 0.89, 0.35, 1.15);
  }

  /* 按下时滑块横向撑一点，复刻原组件 :active 拉伸的手感 */
  button:active ~ .slider {
    --stretch: 1.06;
  }

  @media (prefers-reduced-motion: reduce) {
    button,
    .slider {
      transition: none;
    }
  }
`;
