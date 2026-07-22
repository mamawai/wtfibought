import { useId } from 'react';

/**
 * 描线动画迷你走势：面积渐变 + 入场画线 + 端点光点。
 * 不传 stroke 时按首尾涨跌自动取 gain/loss。
 */
export function Sparkline({ data, stroke, area = true, dot = true, className }: {
  data: number[];
  /** CSS 颜色值（可用 var(--color-*)） */
  stroke?: string;
  area?: boolean;
  /** 端点光点。大尺寸拉伸容器（如首页净值曲线）圆点会变形，建议关掉 */
  dot?: boolean;
  className?: string;
}) {
  const id = useId();
  if (data.length < 2) return null;

  const W = 100, H = 28, P = 2;
  const mn = Math.min(...data), mx = Math.max(...data);
  const pts = data.map((d, i) => [
    (i / (data.length - 1)) * W,
    H - P - ((d - mn) / (mx - mn || 1)) * (H - P * 2),
  ]);
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(2)} ${p[1].toFixed(2)}`).join(' ');
  const up = data[data.length - 1] >= data[0];
  const c = stroke ?? (up ? 'var(--color-gain)' : 'var(--color-loss)');
  const [lx, ly] = pts[pts.length - 1];

  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className={className}>
      {area && (
        <>
          <defs>
            <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={c} stopOpacity=".16" />
              <stop offset="1" stopColor={c} stopOpacity="0" />
            </linearGradient>
          </defs>
          <path d={`${line} L${W} ${H} L0 ${H} Z`} fill={`url(#${id})`} />
        </>
      )}
      <path
        d={line} fill="none" stroke={c} strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"
        className="spark-draw"
      />
      {dot && <circle cx={lx} cy={ly} r="2" fill={c} className="spark-dot" style={{ filter: `drop-shadow(0 0 2px ${c})` }} />}
    </svg>
  );
}
