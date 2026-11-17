import type { ReactNode } from 'react';

export interface StripCell {
  label: ReactNode;
  value: ReactNode;
  /** 数值配色，涨跌用 up / dn；不传就是常规前景色 */
  tone?: string;
}

/** 工作台的记分条：一格一个指标，小字在上、数在下；六格一行，窄屏三格两行（样式见 ArenaDetail.css .sc） */
export function ScoreStrip({ cells }: { cells: StripCell[] }) {
  return (
    <div className="sc">
      {cells.map((c, i) => (
        <div key={i}>
          <span>{c.label}</span>
          <b className={c.tone}>{c.value}</b>
        </div>
      ))}
    </div>
  );
}
