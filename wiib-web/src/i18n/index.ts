import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import type { Resource, ResourceKey } from 'i18next';

/** 支持的语言。加一门语言 = 这里加一项 + 铺一份 locales/<lng>/ 目录，别处不用动 */
export const SUPPORTED_LANGS = ['zh', 'en'] as const;
export type Lang = (typeof SUPPORTED_LANGS)[number];

/** 手动选的语言，localStorage 键。检测器读它、切换器写它，全站只此一个键；没值就按浏览器判 */
export const LANG_STORAGE_KEY = 'wiib-lang';

/**
 * 词表自动装配：locales/{语言}/{命名空间}.json 全量打进包，目录名当语言、文件名当命名空间。
 * 关键就在"自动"——按业务域分批迁移时各人只新增自己那份 JSON，这个文件一行都不用碰，
 * 否则十来处改动全撞在这一个注册表上。
 */
const files = import.meta.glob<{ default: ResourceKey }>('./locales/*/*.json', { eager: true });

const resources: Resource = {};
for (const [path, mod] of Object.entries(files)) {
  const m = /\/locales\/([^/]+)\/([^/]+)\.json$/.exec(path);
  if (!m) continue;
  const [, lng, ns] = m;
  if (!resources[lng]) resources[lng] = {};
  resources[lng][ns] = mod.default;
}

/** 命名空间清单取各语言并集：某语言缺哪份就按 fallbackLng 回落，不会整块查不到 */
const namespaces = [...new Set(Object.values(resources).flatMap(bundle => Object.keys(bundle)))];

const detector = new LanguageDetector();

/**
 * 浏览器语言归一：zh-* 一律算中文，其余一律算英文。
 * 不能用内置的 navigator 检测器——它把 fr-FR 归成 fr，不在支持列表里就掉进 fallbackLng(zh)，
 * 法国用户一进门看见中文。
 */
detector.addDetector({
  name: 'navigatorZhOrEn',
  lookup: () => {
    // 只看首选那门：ja 在前 zh 在后也算英文
    const first = navigator.languages?.[0] ?? navigator.language ?? '';
    return first.toLowerCase().startsWith('zh') ? 'zh' : 'en';
  },
});

void i18n
  .use(detector)
  .use(initReactI18next)
  .init({
    resources,
    ns: namespaces,
    defaultNS: 'common',
    fallbackLng: 'zh',
    supportedLngs: [...SUPPORTED_LANGS],
    // zh-CN / en-US 这类带地区码的降到语言级，词表只按 zh / en 铺一份
    load: 'languageOnly',
    detection: {
      order: ['localStorage', 'navigatorZhOrEn'],
      lookupLocalStorage: LANG_STORAGE_KEY,
      // 不自动回写：识别结果不落盘，只有 useLangToggle 手动切才存
      caches: [],
    },
    // React 自带转义，i18next 再转一次会把 & < > 变成实体码显示出来
    interpolation: { escapeValue: false },
    // 词表随包同步就位，没有异步加载，不需要谁去套 Suspense 边界
    react: { useSuspense: false },
  });

/** 当前语言。i18next 的 resolvedLanguage 可能带地区码，这里归一到支持列表里的两门 */
export const currentLang = (): Lang => (i18n.resolvedLanguage === 'en' ? 'en' : 'zh');

/** html lang 跟着切：影响浏览器断词与字体回退，也让读屏软件知道当前在读哪门语言 */
const applyHtmlLang = (lng: string) => {
  document.documentElement.lang = lng.startsWith('zh') ? 'zh-CN' : 'en';
};
i18n.on('languageChanged', applyHtmlLang);
applyHtmlLang(i18n.resolvedLanguage ?? 'zh');

export default i18n;
