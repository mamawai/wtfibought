import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { userApi } from '../api';
import { LangSeg } from './LanguageSwitcher';
import { useToast } from './ui/use-toast';
import type { Lang } from '../i18n';

/**
 * 配置页的「Agent 提示词语言」一节（样式见 AiAgent.css .lang）：落 user.lang，只在这里改，顶栏的界面语言切换碰不到它。
 * 进页拉一次，点了先切过去，失败弹回
 */
export function AgentLangSetting() {
  const { t } = useTranslation('ai');
  const { toast } = useToast();
  // null = 还没拉回来，先不让点，免得先显示成一门再跳到另一门
  const [lang, setLang] = useState<Lang | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    userApi.getLang().then(setLang).catch(() => setLang(null));
  }, []);

  const pick = (next: Lang) => {
    if (lang == null || saving) return false;
    setSaving(true);
    setLang(next);
    userApi.setLang(next)
      .catch((e: Error) => {
        setLang(lang);
        toast(e.message || t('agentLang.failed'), 'error');
      })
      .finally(() => setSaving(false));
  };

  return (
    <section className="sec lang-sec">
      <div className="sec-h"><h2>{t('agentLang.title')}</h2></div>
      <div className="lang">
        <p>{t('agentLang.desc')}</p>
        <LangSeg value={lang} onChange={pick} label={t('agentLang.title')} />
      </div>
    </section>
  );
}
