import { useRef, useState } from 'react';
import { HelpCircle } from 'lucide-react';
import { cn } from '../lib/utils';
import { useClickOutside } from '../hooks/useClickOutside';

/** 小问号点击弹说明气泡；side 控制气泡在图标下方（默认）或上方，点击外部关闭。 */
export function HelpTip({ text, side = 'bottom', iconClassName }: {
  text: string;
  side?: 'top' | 'bottom';
  iconClassName?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  useClickOutside(ref, () => setOpen(false), open);

  return (
    <span ref={ref} className="relative inline-flex">
      <button
        type="button"
        onClick={e => { e.stopPropagation(); setOpen(v => !v); }}
        className="text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
      >
        <HelpCircle className={cn('w-3.5 h-3.5', iconClassName)} />
      </button>
      {open && (
        <span
          className={cn(
            'absolute left-1/2 -translate-x-1/2 z-50 w-52 p-2.5 rounded-lg border bg-card text-xs text-muted-foreground shadow-lg leading-relaxed whitespace-pre-line',
            side === 'bottom' ? 'top-full mt-2' : 'bottom-full mb-1.5',
          )}
        >
          {text}
        </span>
      )}
    </span>
  );
}
