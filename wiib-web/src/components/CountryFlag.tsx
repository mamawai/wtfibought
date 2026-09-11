import {
  AU, BR, CA, CH, CN, DE, ES, EU, FR, GB, HK, IN, IT, JP, KR, MX, NO, NZ, RU, SE, SG, TR, US, ZA,
} from 'country-flag-icons/string/3x2';
import { cn } from '../lib/utils';

/** 国旗 SVG 字符串（3:2）：只收财经日历 High 级事件会出现的国家，没映射的显示两字母码 */
const FLAGS: Record<string, string> = {
  AU, BR, CA, CH, CN, DE, ES, EU, FR, GB, HK, IN, IT, JP, KR, MX, NO, NZ, RU, SE, SG, TR, US, ZA,
};

/** 18×12 小旗，SVG 撑满；字符串常量是为了画布弹窗的 innerHTML 也能用同一套 class */
const FLAG_CLS = 'inline-block w-[18px] h-[12px] shrink-0 align-[-1px] [&>svg]:block [&>svg]:size-full';
const CODE_CLS = 'inline-block shrink-0 px-0.5 border border-border text-[9px] font-bold leading-[10px] align-[-1px]';

/** 画布弹窗的 innerHTML 用 */
export function flagHtml(code: string): string {
  const svg = FLAGS[code];
  return svg ? `<span class="${FLAG_CLS}">${svg}</span>` : `<span class="${CODE_CLS}">${code}</span>`;
}

/** React 侧同一份 SVG */
export function CountryFlag({ code, className }: { code: string; className?: string }) {
  const svg = FLAGS[code];
  return svg
    ? <span className={cn(FLAG_CLS, className)} dangerouslySetInnerHTML={{ __html: svg }} />
    : <span className={cn(CODE_CLS, className)}>{code}</span>;
}
