/** 收益率大数：带正负号，小数位另起一截放进 <i>（字号、颜色、小数压多淡都由外面定） */
export function PnlPct({ value }: { value: number }) {
  const [int, dec] = Math.abs(value).toFixed(2).split('.');
  return <>{value >= 0 ? '+' : '-'}{int}<i>.{dec}%</i></>;
}
