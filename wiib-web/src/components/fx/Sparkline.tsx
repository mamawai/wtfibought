import { useId } from 'react';
import { smoothPath } from '../../lib/smoothPath';
import { cn } from '../../lib/utils';

/**
 * 描线动画迷你走势：平滑曲线 + 面积渐变 + 入场画线 + 端点光点。
 * 不传 stroke 时按首尾涨跌自动取 gain/loss。
 * <p>
 * 传了 baseline 就换一套画法：那条线（首页是起始资金）画成虚线，曲线和面积在它上下分成绿红两段。
 * <p>
 * 想让数据刷新后重新描一遍线，调用方给个随数据变化的 key 即可（首页净值曲线就这么干）；
 * 行情表那种一屏几十条的小图别给，不然每次行情跳动整屏重画。
 */
export function Sparkline({ data, stroke, area = true, dot = true, baseline, baselineLabel, className }: {
  data: number[];
  /** CSS 颜色值（可用 var(--color-*)） */
  stroke?: string;
  area?: boolean;
  /** 端点光点。大尺寸拉伸容器（如首页净值曲线）圆点会变形，建议关掉 */
  dot?: boolean;
  /** 分色基准线的值，会一起参与 min/max，保证这条线一定落在图内 */
  baseline?: number;
  /** 挂在基线左端上方的小字，只在传了 baseline 时有意义；给了它线尾还会多一颗空心点 */
  baselineLabel?: string;
  className?: string;
}) {
  const id = useId();
  if (data.length < 2) return null;

  const W = 100, H = 28, P = 2;
  const split = baseline != null;
  const mn = Math.min(...data, ...(split ? [baseline] : []));
  const mx = Math.max(...data, ...(split ? [baseline] : []));
  const step = W / (data.length - 1);
  const y = (v: number) => H - P - ((v - mn) / (mx - mn || 1)) * (H - P * 2);
  const ys = data.map(y);

  const line = smoothPath(ys, 0, step);

  const up = data[data.length - 1] >= data[0];
  const c = stroke ?? (up ? 'var(--color-gain)' : 'var(--color-loss)');
  const [lx, ly] = [W, ys[ys.length - 1]];

  // 基线那条 y；面积不再落到图底，改成落到基线上，上下两份各自裁一半
  const yB = split ? y(baseline) : H;
  const areaD = `${line} L${W} ${yB.toFixed(2)} L0 ${yB.toFixed(2)} Z`;
  const lineProps = {
    fill: 'none', strokeWidth: split ? '1.75' : '1.5', strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const, vectorEffect: 'non-scaling-stroke' as const,
  };

  const svg = (
    /* 入场动画走 clip-path 从左往右揭示，见 index.css。
       不用 stroke-dasharray 那套：本组件为了线宽不被 preserveAspectRatio=none 拉变形，
       必须挂 vectorEffect=non-scaling-stroke，而它会让 dash 改按屏幕坐标算——
       user-space 的 pathLength 归一化当场失效，线要么全隐要么只画一半 */
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className={cn('reveal', className)}>
      {split ? (
        <>
          <defs>
            <clipPath id={`${id}a`}><rect x="0" y="0" width={W} height={yB} /></clipPath>
            <clipPath id={`${id}b`}><rect x="0" y={yB} width={W} height={H - yB} /></clipPath>
            <linearGradient id={`${id}u`} gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2={yB}>
              <stop offset="0" stopColor="var(--color-gain)" stopOpacity=".22" />
              <stop offset="1" stopColor="var(--color-gain)" stopOpacity=".02" />
            </linearGradient>
            <linearGradient id={`${id}d`} gradientUnits="userSpaceOnUse" x1="0" y1={yB} x2="0" y2={H}>
              <stop offset="0" stopColor="var(--color-loss)" stopOpacity=".02" />
              <stop offset="1" stopColor="var(--color-loss)" stopOpacity=".22" />
            </linearGradient>
          </defs>
          {area && (
            <>
              <path d={areaD} clipPath={`url(#${id}a)`} fill={`url(#${id}u)`} />
              <path d={areaD} clipPath={`url(#${id}b)`} fill={`url(#${id}d)`} />
            </>
          )}
          <line
            x1="0" x2={W} y1={yB} y2={yB} stroke="var(--color-faint)"
            strokeDasharray="2 4" vectorEffect="non-scaling-stroke"
          />
          <path d={line} clipPath={`url(#${id}a)`} stroke="var(--color-gain)" {...lineProps} />
          <path d={line} clipPath={`url(#${id}b)`} stroke="var(--color-loss)" {...lineProps} />
        </>
      ) : (
        <>
          {area && (
            <>
              <defs>
                <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor={c} stopOpacity=".16" />
                  <stop offset="1" stopColor={c} stopOpacity="0" />
                </linearGradient>
              </defs>
              <path d={areaD} fill={`url(#${id})`} />
            </>
          )}
          <path d={line} stroke={c} {...lineProps} />
        </>
      )}
      {dot && <circle cx={lx} cy={ly} r="2" fill={c} className="reveal-late" />}
    </svg>
  );

  if (!split || !baselineLabel) return svg;
  // 标签挂基线左端上方，线尾一颗空心点：svg 被 preserveAspectRatio=none 拉满容器，所以 y 按百分比换算就对得上
  const tail = data[data.length - 1] >= baseline ? 'var(--color-gain)' : 'var(--color-loss)';
  return (
    <div className="relative h-full">
      {svg}
      <i
        className="reveal-late absolute -right-1 w-[9px] h-[9px] -mt-[4.5px] rounded-full bg-background"
        style={{ top: `${(ly / H) * 100}%`, boxShadow: `inset 0 0 0 2px ${tail}` }}
      />
      <span
        className="reveal-late absolute left-0 text-[11.5px] text-muted-foreground whitespace-nowrap"
        style={{ top: `${(yB / H) * 100}%`, transform: 'translateY(calc(-100% - 5px))' }}
      >
        {baselineLabel}
      </span>
    </div>
  );
}
