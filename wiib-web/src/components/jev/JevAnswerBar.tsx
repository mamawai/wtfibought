import { cn } from '../../lib/utils';
import { pct } from './format';
import type { JevAnswer } from '../../types';

interface BarOption {
  key: string;
  label: string;
  bar: string;
}

/**
 * 一道题：标题是我们自己的话，下面每个选项一行，右边一根条按概率填满对应比例（0.8 就填 80%）。
 * 概率最高的那行加粗。三列共用一个网格，各行的条左右对齐。
 */
export function JevAnswerBar({ title, a, options }: { title: string; a: JevAnswer; options: BarOption[] }) {
  const ps = options.map(o => a.probabilities[o.key] ?? 0);
  const top = ps.indexOf(Math.max(...ps));
  return (
    <div>
      <div className="text-[13px] font-semibold mb-2">{title}</div>
      <div className="grid grid-cols-[max-content_1fr_3rem] items-center gap-x-3 gap-y-2 text-[13px]">
        {options.map((o, i) => (
          <div key={o.key} className="contents">
            <span className={i === top ? 'font-semibold' : 'mute'}>{o.label}</span>
            <div className="h-1.5 bg-border overflow-hidden">
              <div className={cn('h-full', o.bar)} style={{ width: `${ps[i] * 100}%` }} />
            </div>
            <span className={cn('num text-right', i === top ? 'font-semibold' : 'mute')}>{pct(ps[i])}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
