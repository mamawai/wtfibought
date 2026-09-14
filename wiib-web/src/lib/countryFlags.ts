import {
  AU, BR, CA, CH, CN, DE, ES, EU, FR, GB, HK, IN, IT, JP, KR, MX, NO, NZ, RU, SE, SG, TR, US, ZA,
} from 'country-flag-icons/string/3x2';

// 国旗数据与 class 常量单独成文件：components/CountryFlag.tsx 才能只导出组件，满足 react-refresh 约束

/** 国旗 SVG 字符串（3:2）：只收财经日历 High 级事件会出现的国家，没映射的显示两字母码 */
export const FLAGS: Record<string, string> = {
  AU, BR, CA, CH, CN, DE, ES, EU, FR, GB, HK, IN, IT, JP, KR, MX, NO, NZ, RU, SE, SG, TR, US, ZA,
};

/** 18×12 小旗，SVG 撑满；字符串常量是为了画布弹窗的 innerHTML 也能用同一套 class */
export const FLAG_CLS = 'inline-block w-[18px] h-[12px] shrink-0 align-[-1px] [&>svg]:block [&>svg]:size-full';
export const CODE_CLS = 'inline-block shrink-0 px-0.5 border border-border text-[9px] font-bold leading-[10px] align-[-1px]';

/** 画布弹窗的 innerHTML 用 */
export function flagHtml(code: string): string {
  const svg = FLAGS[code];
  return svg ? `<span class="${FLAG_CLS}">${svg}</span>` : `<span class="${CODE_CLS}">${code}</span>`;
}
