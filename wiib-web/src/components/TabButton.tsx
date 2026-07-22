import type { ReactNode } from 'react';
import { cn } from '../lib/utils';

/** 页内分组切换按钮：激活格 = 次级面板底 + 顶部橙色指示线（仪器面板选中态）。 */
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
        'flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-md text-sm font-medium transition-colors whitespace-nowrap',
        active
          ? 'bg-card-2 text-foreground border border-border shadow-[inset_0_2px_0_var(--color-primary)]'
          : 'text-muted-foreground hover:text-foreground hover:bg-surface-hover'
      )}
    >
      {icon}
      {children}
    </button>
  );
}
