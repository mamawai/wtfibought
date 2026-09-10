import type { ReactNode } from 'react';
import { cn } from '../lib/utils';

/** 边框始终占位，切换时文字不跳；指示线和底色一起过渡。 */
export function TabButton({ active, onClick, icon, children }: {
  active: boolean;
  onClick: () => void;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'ui-tab relative flex-1 flex items-center justify-center gap-2 py-2 px-3 border border-transparent rounded-md text-sm font-medium whitespace-nowrap cursor-pointer',
        active
          ? 'bg-card-2 text-foreground border-border'
          : 'text-muted-foreground hover:text-foreground hover:bg-surface-hover'
      )}
    >
      {icon}
      {children}
    </button>
  );
}
