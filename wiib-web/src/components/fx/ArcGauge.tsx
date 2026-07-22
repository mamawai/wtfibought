/**
 * 弧形仪表：半环轨道 + 刻度 + 扫动动画，0-100 读数仪器化。
 * 颜色全走 CSS 变量，亮暗主题自适应。
 */
const LEN = Math.PI * 46; // 半圆弧长（r=46）

export function ArcGauge({ value, label, display, className }: {
  /** 0-100 */
  value: number;
  /** 弧下方的小标签，如 EXPOSURE / 脆弱度 */
  label?: string;
  /** 中心显示文本，缺省显示四舍五入的 value */
  display?: string;
  className?: string;
}) {
  const v = Math.max(0, Math.min(100, value));
  const ticks = Array.from({ length: 11 }, (_, i) => {
    const a = Math.PI * (1 + i / 10);
    return {
      x1: 60 + 40 * Math.cos(a), y1: 62 + 40 * Math.sin(a),
      x2: 60 + 46 * Math.cos(a), y2: 62 + 46 * Math.sin(a),
    };
  });

  return (
    <svg viewBox="0 0 120 72" className={className} role="img" aria-label={`${label ?? ''} ${display ?? Math.round(v)}`}>
      <path d="M14 62 A46 46 0 0 1 106 62" fill="none" stroke="var(--color-secondary)" strokeWidth="8" strokeLinecap="round" />
      <path
        d="M14 62 A46 46 0 0 1 106 62" fill="none"
        stroke="var(--color-primary)" strokeWidth="8" strokeLinecap="round"
        strokeDasharray={LEN} strokeDashoffset={LEN * (1 - v / 100)}
        style={{
          filter: 'drop-shadow(0 0 4px color-mix(in srgb, var(--color-primary) 50%, transparent))',
          transition: 'stroke-dashoffset .8s cubic-bezier(.2,.9,.2,1)',
        }}
      />
      {ticks.map((t, i) => (
        <line key={i} x1={t.x1} y1={t.y1} x2={t.x2} y2={t.y2} stroke="var(--color-border)" strokeWidth="1.5" />
      ))}
      <text
        x="60" y="54" textAnchor="middle"
        style={{ fill: 'var(--color-foreground)', fontSize: 21, fontWeight: 700, fontFamily: 'var(--font-mono)' }}
      >
        {display ?? Math.round(v)}
      </text>
      {label && (
        <text x="60" y="68" textAnchor="middle" style={{ fill: 'var(--color-muted-foreground)', fontSize: 7.5, letterSpacing: '.18em' }}>
          {label}
        </text>
      )}
    </svg>
  );
}
