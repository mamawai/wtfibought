import type { ReactNode } from 'react';

/** 列表空态占位（圆形图标 + 文案）。 */
export function EmptyState({ icon, text }: { icon: ReactNode; text: string }) {
  return (
    <div className="p-12 text-center text-muted-foreground">
      <div className="w-12 h-12 mx-auto mb-3 rounded-lg border border-border bg-card-2 flex items-center justify-center">
        {icon}
      </div>
      {text}
    </div>
  );
}
