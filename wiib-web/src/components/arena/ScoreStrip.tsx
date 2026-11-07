import type { ReactNode } from 'react';
import { cn } from '../../lib/utils';

export interface StripCell {
  label: ReactNode;
  value: ReactNode;
  /** 数值配色，涨跌用 up / dn；不传就是常规前景色 */
  tone?: string;
}

/**
 * 记分牌下面那条仪表条：一格一个指标，微标签在上、数在下。
 * PC 六格一行等宽，窄屏三格两行——格间细线由 .strip 自己画。
 */
export function ScoreStrip({ cells }: { cells: StripCell[] }) {
  return (
    <div className="strip num grid-cols-3 xl:grid-cols-6 mt-6 border-t-2 border-foreground">
      {cells.map((c, i) => (
        <div key={i}>
          <div className="k">{c.label}</div>
          <div className={cn('v', c.tone)}>{c.value}</div>
        </div>
      ))}
    </div>
  );
}
