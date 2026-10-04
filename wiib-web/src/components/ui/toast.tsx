import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
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
  // 只翻关闭按钮的可读名；message / description / action.label 由调用方翻好了传进来
  const { t } = useTranslation('layout');

  const remove = useCallback((id: number) => {
    const timer = timersRef.current.get(id);
    if (timer) window.clearTimeout(timer);
    timersRef.current.delete(id);
    setToasts((prev) => prev.filter((item) => item.id !== id));
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
      const deduped = prev.filter((item) => !(item.message === message && item.type === type));
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
        // z 必须高过 Dialog(200)：不然弹窗里点「检测/测试连通性」的反馈会被遮罩压着，
        // 而且点 toast 会打到遮罩上把弹窗关掉，用户刚填的 key 就没了。容器 pointer-events-none，抬高不挡弹窗交互
        className="fixed bottom-[calc(var(--tabbar-space)+0.75rem)] lg:bottom-4 right-[max(1rem,env(safe-area-inset-right))] left-auto w-[min(24rem,calc(100vw-2rem))] z-[300] flex flex-col gap-2 pointer-events-none"
        aria-live="polite"
        aria-relevant="additions removals"
      >
        {/* 循环变量原名 t，与译函数 t 同名会把 aria-label 那句翻译打成"把 toast 当函数调"，改名堵死 */}
        {toasts.map((item) => (
          <div
            key={item.id}
            className={cn(
              "relative overflow-hidden pointer-events-auto",
              "flex items-start gap-3 p-4 rounded-lg bg-card text-card-foreground border shadow-lg",
              "animate-in slide-in-from-bottom-2 fade-in",
              item.type === 'success' && "border-success/30",
              item.type === 'error' && "border-destructive/30",
              item.type === 'info' && "border-border"
            )}
            role="status"
          >
            <div className={cn(
              "mt-0.5",
              item.type === 'success' && "text-success",
              item.type === 'error' && "text-destructive",
              item.type === 'info' && "text-primary"
            )}>
              {icon[item.type]}
            </div>

            <div className="flex-1 min-w-0">
              <div className="text-sm font-medium leading-snug break-words">{item.message}</div>
              {item.description && (
                <div className="text-xs text-muted-foreground mt-1 leading-snug break-words">
                  {item.description}
                </div>
              )}
              {item.action && (
                <button
                  type="button"
                  onClick={() => {
                    try {
                      item.action?.onClick();
                    } finally {
                      remove(item.id);
                    }
                  }}
                  className="mt-2 text-xs font-medium underline underline-offset-4 hover:opacity-80"
                >
                  {item.action.label}
                </button>
              )}
            </div>

            <button
              type="button"
              onClick={() => remove(item.id)}
              className="shrink-0 p-1 rounded-md hover:bg-surface-hover transition-colors"
              aria-label={t('toast.close')}
            >
              <X className="w-4 h-4 text-muted-foreground" />
            </button>

            <div
              className={cn(
                "absolute bottom-0 left-0 h-0.5 w-full origin-left",
                item.type === 'success' && "bg-success/60",
                item.type === 'error' && "bg-destructive/60",
                item.type === 'info' && "bg-primary/60",
                "wiib-toast-progress"
              )}
              style={{ animationDuration: `${item.duration}ms` }}
            />
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
