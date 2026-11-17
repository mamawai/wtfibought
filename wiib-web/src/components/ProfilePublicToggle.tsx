import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { userApi } from '../api';
import { useToast } from './ui/use-toast';
import { Switch } from './ui/switch';

/**
 * 详情页公开开关。/me 与 /portfolio 各摆一处，
 * 两处摆同一个组件而不是各写一份——两份状态逻辑迟早只改一边。
 * 持仓页是一节（标题 + 一段说明 + 开关），我的页传 row 是设置块里的一行。
 */
export function ProfilePublicToggle({ row }: { row?: boolean }) {
  const { t } = useTranslation('account');
  const { toast } = useToast();
  // null = 还没拉回来。此时禁用开关，免得先渲染成"关"再跳回"开"闪一下
  const [profilePublic, setProfilePublic] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    userApi.getProfilePublic().then(setProfilePublic).catch(() => setProfilePublic(null));
  }, []);

  const toggle = async () => {
    if (profilePublic == null || saving) return;
    const next = !profilePublic;
    setSaving(true);
    // 乐观更新：开关是即时反馈的控件，等一个来回再动会让人觉得点了没反应；失败再弹回去
    setProfilePublic(next);
    try {
      await userApi.setProfilePublic(next);
      toast(next ? t('toggle.on') : t('toggle.off'), 'success');
    } catch (e) {
      setProfilePublic(!next);
      toast((e as Error).message || t('toggle.failed'), 'error');
    } finally {
      setSaving(false);
    }
  };

  const sw = (
    <Switch checked={profilePublic !== false} disabled={profilePublic == null}
            onChange={() => void toggle()} label={t('toggle.title')} />
  );

  if (row) {
    return (
      <div className="sw-row">
        <div><b>{t('toggle.title')}</b><span>{t('toggle.desc')}</span></div>
        {sw}
      </div>
    );
  }
  return (
    <section className="sec">
      <div className="sec-h"><h2>{t('toggle.title')}</h2></div>
      <div className="line"><p>{t('toggle.desc')}</p>{sw}</div>
    </section>
  );
}
