import { useCallback, useEffect, useRef, useState } from 'react';
import { cn } from '../../lib/utils';
import { ToastContext, type ToastType, type ToastOptions } from './use-toast';
import './toast.css';

interface Toast {
  id: number;
  message: string;
  description?: string;
  type: ToastType;
  /** 正在淡出 */
  out?: boolean;
}

let toastId = 0;
/** 淡出那段：同 toast.css 里 .out 的过渡时长 */
const OUT_MS = 160;

/** 提示条：右下角墨色一条（手机在顶上），自己会走；点一下提前收起（样式见 toast.css） */
export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timersRef = useRef<Map<number, number>>(new Map());

  // 先淡出，再摘掉
  const dismiss = useCallback((id: number) => {
    const timers = timersRef.current;
    window.clearTimeout(timers.get(id));
    setToasts(prev => prev.map(it => it.id === id ? { ...it, out: true } : it));
    timers.set(id, window.setTimeout(() => {
      timers.delete(id);
      setToasts(prev => prev.filter(it => it.id !== id));
    }, OUT_MS));
  }, []);

  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      for (const timer of timers.values()) window.clearTimeout(timer);
      timers.clear();
    };
  }, []);

  const toast = useCallback((message: string, type: ToastType = 'info', options?: ToastOptions) => {
    const id = ++toastId;
    // 一句话的停 2.4 秒，多带一行说明的停 4.2 秒
    const duration = options?.duration ?? (options?.description ? 4200 : 2400);
    // 同一句话不叠两条；最多留三条
    setToasts(prev => {
      const next = [...prev.filter(it => !(it.message === message && it.type === type)), { id, message, description: options?.description, type }];
      return next.length > 3 ? next.slice(next.length - 3) : next;
    });
    timersRef.current.set(id, window.setTimeout(() => dismiss(id), duration));
  }, [dismiss]);

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div className="toasts" aria-live="polite" aria-relevant="additions removals">
        {toasts.map(item => (
          <div key={item.id} role="status" onClick={() => dismiss(item.id)}
               className={cn('toast', item.type === 'error' && 'err', item.type === 'info' && 'info', item.out && 'out')}>
            <i />
            <div>
              <b>{item.message}</b>
              {item.description && <span>{item.description}</span>}
            </div>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
