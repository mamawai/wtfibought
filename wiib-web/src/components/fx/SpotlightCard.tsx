import { useRef } from 'react';
import { cn } from '../../lib/utils';

/** 光标跟随聚光卡：pt-card 的强调版，鼠标进卡内亮起橙色柔光。视觉细节见 index.css .spotlight-card */
export function SpotlightCard({ className, children, ...props }: React.ComponentProps<'div'>) {
  const ref = useRef<HTMLDivElement>(null);
  return (
    <div
      ref={ref}
      onMouseMove={e => {
        const el = ref.current;
        if (!el) return;
        const r = el.getBoundingClientRect();
        el.style.setProperty('--spot-x', `${e.clientX - r.left}px`);
        el.style.setProperty('--spot-y', `${e.clientY - r.top}px`);
      }}
      className={cn('spotlight-card pt-card rounded-lg', className)}
      {...props}
    >
      {children}
    </div>
  );
}
