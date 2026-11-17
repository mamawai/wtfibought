import { useId, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { userApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from './ui/use-toast';
import { Dialog, DialogFooter, DialogHeader } from './ui/dialog';
import { Input } from './ui/input';
import { cn } from '../lib/utils';

/** 重置账户的二次确认：用户名一字不差才点得动（持仓页、我的页共用）。onDone 是重置成功后各页自己的刷新 */
export function ResetAccountDialog({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation(['portfolio', 'common']);
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const nameId = useId();
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  if (!user) return null;
  const ok = name === user.username;

  const close = () => {
    setName('');
    onClose();
  };

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await userApi.resetAccount(name);
      close();
      onDone();
      toast(t('toast.resetDone'), 'success');
    } catch (e) {
      toast((e as Error).message || t('toast.resetFailed'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={close}>
      <DialogHeader><h3>{t('reset.dialogTitle')}</h3></DialogHeader>
      <div className="txt">
        <p>{t('reset.warnClears')}</p>
        <p className="wn mt-2">{t('reset.warnCost')}</p>
      </div>
      <div className="fld">
        <label className="fl" htmlFor={nameId}>
          <span>
            {/* 用户名夹在句子中间，中英语序不同，整句交给 Trans 摆位 */}
            <Trans t={t} i18nKey="reset.confirmName" values={{ name: user.username }}
                   components={[<strong key="name" className="font-semibold text-foreground" />]} />
          </span>
        </label>
        <Input id={nameId} value={name} onChange={e => setName(e.target.value)} placeholder={t('reset.namePlaceholder')} autoComplete="off"
               onKeyDown={e => { if (e.key === 'Enter' && ok) void submit(); }} />
      </div>
      <DialogFooter>
        <button type="button" className="btn" onClick={close}>{t('common:cancel')}</button>
        <button type="button" className={cn('btn danger', busy && 'busy')} disabled={!ok} onClick={() => void submit()}>
          {t('reset.submit')}
        </button>
      </DialogFooter>
    </Dialog>
  );
}
