import { useTranslation } from 'react-i18next';
import { Seg } from './ui/seg';
import { useLangToggle } from '../hooks/useLangToggle';
import type { Lang } from '../i18n';

/**
 * 语言名一律用本族语写死，不跟着界面语言翻译——中文在英文界面里也该是"中文"，
 * 不然切错语言的人反而找不着回来的那一格。同理不进词表。
 */
const LANG_LABEL: Record<Lang, string> = { zh: '中文', en: 'English' };

/** 「我的」页设置块里的一行：左边名字，右边中文 / English 分段 */
export function LanguageSettingRow() {
  const { t } = useTranslation();
  const { current, toggle } = useLangToggle();

  return (
    <div className="sw-row">
      <div><b>{t('language.label')}</b></div>
      <Seg value={current} label={t('language.label')}
           onChange={v => { if (v !== current) toggle(); }}
           options={(['zh', 'en'] as const).map(l => ({ value: l, label: LANG_LABEL[l] }))} />
    </div>
  );
}
