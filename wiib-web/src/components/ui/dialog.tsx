import { createContext, useContext, useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { cn } from '../../lib/utils';

/** Dialog 往下递的：头里那颗关闭钮要用的 onClose，头左边那块的 id（面板拿它当读屏名） */
const DialogContext = createContext<{ onClose: () => void; titleId?: string }>({ onClose: () => {} });

interface DialogProps {
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
  /** 加在面板上：sm / lg 换宽度 */
  className?: string;
}

/**
 * 弹窗：遮罩 + 居中的圆角面板，手机上从底边升起（样式见 index.css .dlg）。
 * 面板是一竖排，间距 18px：DialogHeader / DialogContent / DialogFooter 依次往下放。点遮罩关。
 */
export function Dialog({ open, onClose, children, className }: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();

  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [open]);

  // 打开时焦点挪进面板，Tab 走出面板就拉回来，关上还给打开前的那个元素
  useEffect(() => {
    if (!open) return;
    const panel = panelRef.current!;
    const prev = document.activeElement as HTMLElement | null;
    panel.focus();
    const keepIn = (e: FocusEvent) => { if (!panel.contains(e.target as Node)) panel.focus(); };
    document.addEventListener('focusin', keepIn);
    return () => {
      document.removeEventListener('focusin', keepIn);
      prev?.focus();
    };
  }, [open]);

  if (!open) return null;

  return (
    <div className="dlg" onKeyDown={e => { if (e.key === 'Escape') onClose(); }}>
      <div className="scrim" onClick={onClose} />
      <div ref={panelRef} className={cn('panel', className)} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <DialogContext.Provider value={{ onClose, titleId }}>{children}</DialogContext.Provider>
      </div>
    </div>
  );
}

/** 头：左边放标题（h3）和说明（p），右边是关闭钮；actions 是排在关闭钮前面的小钮 */
export function DialogHeader({ children, actions }: {
  children: React.ReactNode;
  actions?: React.ReactNode;
}) {
  const { onClose, titleId } = useContext(DialogContext);
  const { t } = useTranslation('common');
  return (
    <div className="dh">
      <div id={titleId}>{children}</div>
      <div className="acts">
        {actions}
        <button type="button" className="xb" onClick={onClose} aria-label={t('close')}><X className="ic" /></button>
      </div>
    </div>
  );
}

export function DialogContent({ children }: { children: React.ReactNode }) {
  return <div className="min-w-0">{children}</div>;
}

export function DialogFooter({ children }: { children: React.ReactNode }) {
  return <div className="df">{children}</div>;
}
