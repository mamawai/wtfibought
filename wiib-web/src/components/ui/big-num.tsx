import { fmtNum } from '../../lib/utils';

/**
 * 大数的里子：小数位压成浅灰。外面套一个带 big nf 的元素定字号（样式见 index.css .big）。
 * 金额前面是 Tether 符号 ₮（全站都以 USDT 计价）。不带号的（余额）₮ 缩成右上角一个小字；signed = 带正负号（盈亏），₮ 跟着号走、不缩小。
 */
export function BigNum({ value, decimals = 2, signed }: { value: number; decimals?: number; signed?: boolean }) {
  const [int, dec] = fmtNum(Math.abs(value), decimals).split('.');
  return (
    <>
      {signed ? `${value < 0 ? '-' : '+'}₮` : <>{value < 0 && '-'}<small>₮</small></>}
      {int}
      {dec && <i>.{dec}</i>}
    </>
  );
}
