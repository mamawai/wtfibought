import { createContext, useContext, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { cn } from '../../lib/utils';

/** 头里那颗关闭钮要用的 onClose，Dialog 往下递 */
const CloseContext = createContext<() => void>(() => {});

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
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [open]);

  if (!open) return null;

  return (
    <div className="dlg" role="dialog" aria-modal="true"
         onKeyDown={e => { if (e.key === 'Escape') onClose(); }}>
      <div className="scrim" onClick={onClose} />
      <div className={cn('panel', className)}>
        <CloseContext.Provider value={onClose}>{children}</CloseContext.Provider>
      </div>
    </div>
  );
}

/** 头：左边放标题（h3）和说明（p），右边是关闭钮；actions 是排在关闭钮前面的小钮 */
export function DialogHeader({ children, actions, className }: {
  children: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  const onClose = useContext(CloseContext);
  const { t } = useTranslation('common');
  return (
    <div className={cn('dh', className)}>
      <div>{children}</div>
      <div className="acts">
        {actions}
        <button type="button" className="xb" onClick={onClose} aria-label={t('close')}><X className="ic" /></button>
      </div>
    </div>
  );
}

export function DialogContent({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn('min-w-0', className)}>{children}</div>;
}

export function DialogFooter({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn('df', className)}>{children}</div>;
}
