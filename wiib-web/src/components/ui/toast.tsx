import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { cn } from '../../lib/utils';
import { X, CheckCircle, AlertCircle, Info } from 'lucide-react';
import { ToastContext, type ToastType, type ToastOptions, type ToastAction } from './use-toast';

interface Toast {
  id: number;
  message: string;
  description?: string;
  type: ToastType;
  duration: number;
  action?: ToastAction;
}

let toastId = 0;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timersRef = useRef<Map<number, number>>(new Map());

  const remove = useCallback((id: number) => {
    const timer = timersRef.current.get(id);
    if (timer) window.clearTimeout(timer);
    timersRef.current.delete(id);
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      for (const timer of timers.values()) window.clearTimeout(timer);
      timers.clear();
    };
  }, []);

  const toast = useCallback((message: string, type: ToastType = 'info', options?: ToastOptions) => {
    const duration = Math.max(1200, options?.duration ?? 3000);
    const id = ++toastId;
    const nextToast: Toast = {
      id,
      message,
      description: options?.description,
      type,
      duration,
      action: options?.action,
    };

    setToasts((prev) => {
      const deduped = prev.filter((t) => !(t.message === message && t.type === type));
      const next = [...deduped, nextToast];
      return next.length > 3 ? next.slice(next.length - 3) : next;
    });

    const timer = window.setTimeout(() => remove(id), duration);
    timersRef.current.set(id, timer);
  }, [remove]);

  const icon = useMemo(() => {
    return {
      success: <CheckCircle className="w-5 h-5 shrink-0" />,
      error: <AlertCircle className="w-5 h-5 shrink-0" />,
      info: <Info className="w-5 h-5 shrink-0" />,
    } satisfies Record<ToastType, React.ReactNode>;
  }, []);

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div
        className="fixed bottom-20 md:bottom-4 right-4 left-auto w-[min(24rem,calc(100vw-2rem))] z-[100] flex flex-col gap-2 pointer-events-none"
        aria-live="polite"
        aria-relevant="additions removals"
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className={cn(
              "relative overflow-hidden pointer-events-auto",
              "flex items-start gap-3 p-4 rounded-lg bg-card text-card-foreground border shadow-lg",
              "animate-in slide-in-from-bottom-2 fade-in",
              t.type === 'success' && "border-success/30",
              t.type === 'error' && "border-destructive/30",
              t.type === 'info' && "border-border"
            )}
            role="status"
          >
            <div className={cn(
              "mt-0.5",
              t.type === 'success' && "text-success",
              t.type === 'error' && "text-destructive",
              t.type === 'info' && "text-primary"
            )}>
              {icon[t.type]}
            </div>

            <div className="flex-1 min-w-0">
              <div className="text-sm font-medium leading-snug break-words">{t.message}</div>
              {t.description && (
                <div className="text-xs text-muted-foreground mt-1 leading-snug break-words">
                  {t.description}
                </div>
              )}
              {t.action && (
                <button
                  type="button"
                  onClick={() => {
                    try {
                      t.action?.onClick();
                    } finally {
                      remove(t.id);
                    }
                  }}
                  className="mt-2 text-xs font-medium underline underline-offset-4 hover:opacity-80"
                >
                  {t.action.label}
                </button>
              )}
            </div>

            <button
              type="button"
              onClick={() => remove(t.id)}
              className="shrink-0 p-1 rounded-md hover:bg-surface-hover transition-colors"
              aria-label="关闭通知"
            >
              <X className="w-4 h-4 text-muted-foreground" />
            </button>

            <div
              className={cn(
                "absolute bottom-0 left-0 h-0.5 w-full origin-left",
                t.type === 'success' && "bg-success/60",
                t.type === 'error' && "bg-destructive/60",
                t.type === 'info' && "bg-primary/60",
                "wiib-toast-progress"
              )}
              style={{ animationDuration: `${t.duration}ms` }}
            />
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
