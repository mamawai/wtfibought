import { cn } from '../lib/utils';

/**
 * 二选一仪器开关：凹槽轨道 + 橙色滑块（机加工高光）。
 * 两个档位始终都写在面上——下单界面只显示当前档会让人分不清"这是当前值还是点了会变成的值"。
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

/** pad=槽内边距(滑块四周留的沟)，btn=按钮字号/内边距，对齐被替换的原按钮 */
const SIZES = {
  md: { pad: 3, btn: 'px-3.5 text-xs font-bold' },
  sm: { pad: 2, btn: 'px-2.5 text-[0.6875rem] font-medium' },
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
  const { pad, btn } = SIZES[size];

  return (
    <div
      className={cn(
        'relative inline-grid grid-cols-2 rounded-full bg-secondary border border-border',
        'shadow-[inset_0_1px_2px_rgba(0,0,0,0.08)] dark:shadow-[inset_0_1px_2px_rgba(0,0,0,0.4)]',
        className,
      )}
      style={{ padding: pad }}
      role="group"
      aria-label={label}
    >
      {options.map((opt, i) => (
        <button
          key={opt.value}
          type="button"
          aria-pressed={i === activeIndex}
          onClick={() => onChange(opt.value)}
          className={cn(
            'relative z-[1] rounded-full border-0 bg-transparent py-1 leading-4 whitespace-nowrap cursor-pointer',
            'transition-colors duration-150 motion-reduce:transition-none',
            'focus-visible:outline-2 focus-visible:outline-ring focus-visible:outline-offset-2',
            btn,
            i === activeIndex ? 'text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {opt.label}
        </button>
      ))}
      {/* 滑块：橙色实体键帽，machined 顶部高光给物理感 */}
      <span
        aria-hidden="true"
        className="absolute z-0 rounded-full bg-primary machined transition-transform duration-150 ease-out motion-reduce:transition-none"
        style={{
          top: pad,
          bottom: pad,
          left: pad,
          width: `calc(50% - ${pad}px)`,
          transform: `translateX(${activeIndex * 100}%)`,
        }}
      />
    </div>
  );
}
