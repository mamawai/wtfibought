import { cn } from '../lib/utils';
import { CODE_CLS, FLAGS, FLAG_CLS } from '../lib/countryFlags';

/** 国旗小图：React 侧与画布弹窗 flagHtml 共用 lib/countryFlags 里同一份 SVG 与 class */
export function CountryFlag({ code, className }: { code: string; className?: string }) {
  const svg = FLAGS[code];
  return svg
    ? <span className={cn(FLAG_CLS, className)} dangerouslySetInnerHTML={{ __html: svg }} />
    : <span className={cn(CODE_CLS, className)}>{code}</span>;
}
