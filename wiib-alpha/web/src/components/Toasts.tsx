import { useCallback, useState, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { ToastContext, type ToastKind } from '../toast';
import { cx } from '../format';

interface Item {
  id: number;
  message: string;
  kind: ToastKind;
}

let seq = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Item[]>([]);

  const dismiss = useCallback((id: number) => setItems(list => list.filter(i => i.id !== id)), []);

  const toast = useCallback((message: string, kind: ToastKind = 'error') => {
    const id = ++seq;
    const ms = kind === 'error' ? 6000 : 3000;
    setItems(list => [...list.slice(-3), { id, message, kind }]);
    setTimeout(() => dismiss(id), ms);
  }, [dismiss]);

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <div className="toasts" aria-live="polite">
        {items.map(i => (
          <div key={i.id} className={cx('toast', i.kind === 'error' && 'err')}>
            <i />
            <span>{i.message}</span>
            <button onClick={() => dismiss(i.id)} aria-label="close"><X className="w-3.5 h-3.5" /></button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
