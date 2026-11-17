import { createContext, useContext } from 'react'

export type ToastType = 'success' | 'error' | 'info'

export interface ToastOptions {
  /** 多带的一行小字 */
  description?: string
  /** 停多久（毫秒）；不给就按有没有说明取 2.4 / 4.2 秒 */
  duration?: number
}

export interface ToastContextType {
  toast: (message: string, type?: ToastType, options?: ToastOptions) => void
}

export const ToastContext = createContext<ToastContextType | null>(null)

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
