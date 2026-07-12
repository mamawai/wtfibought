import type { ReactNode } from 'react';
import { cn } from '../lib/utils';

/** 页内分组切换按钮（拟物风选中态），Portfolio/Options 共用。 */
export function TabButton({ active, onClick, icon, children }: {
  active: boolean;
  onClick: () => void;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-lg text-sm font-medium transition-all whitespace-nowrap',
        active
          ? 'bg-primary text-primary-foreground neu-raised-sm'
          : 'text-muted-foreground hover:text-foreground'
      )}
    >
      {icon}
      {children}
    </button>
  );
}
