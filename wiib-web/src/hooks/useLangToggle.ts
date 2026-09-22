import i18n, { currentLang, LANG_STORAGE_KEY, type Lang } from '../i18n';

/**
 * 当前语言与"点一下切到哪"。只有两门语言，点按直切，不做下拉。
 * 只切界面，不碰服务端：agent 提示词语言是另一个开关（配置页 AgentLangSetting）。
 */
export function useLangToggle() {
  const current = currentLang();
  const next: Lang = current === 'zh' ? 'en' : 'zh';
  const toggle = () => {
    void i18n.changeLanguage(next);
    // 存下手动选的，之后刷新以它为准，不再按浏览器判
    localStorage.setItem(LANG_STORAGE_KEY, next);
  };
  return { current, next, toggle };
}
