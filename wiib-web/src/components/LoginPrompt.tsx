import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LogIn } from 'lucide-react';
import { cn } from '../lib/utils';

/** 游客占位：一句话 + 去登录。顶替那些没登录就没意义的面板（下单、发言） */
export function LoginPrompt({ text, className }: { text: string; className?: string }) {
  const { t } = useTranslation('layout');
  return (
    <div className={cn('flex flex-col items-start gap-3', className)}>
      <p className="text-[13px] text-muted-foreground">{text}</p>
      <Link to="/login" className="btn sm fill"><LogIn className="ic" />{t('header.login')}</Link>
    </div>
  );
}
