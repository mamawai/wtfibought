/** 财经日历数值文本的小工具：库里存显示文本（0.2% / 206K / 1.443M），比大小、画图只取数字部分 */

/** "206K" → 206、"-0.6%" → -0.6；null 或解析不了 → null */
export function parseValue(text: string | null | undefined): number | null {
  if (text == null) return null;
  const v = parseFloat(text);
  return Number.isFinite(v) ? v : null;
}

/** 数字后面的单位："206K" → "K"、"3.1%" → "%"、"52.1" → "" */
export function valueSuffix(text: string | null | undefined): string {
  return text == null ? '' : text.replace(/^[-+]?[\d.]+/, '');
}

/** 实际对预测：两个都有数才比，相等算符合 */
export function expectation(actual: string | null, forecast: string | null): 'above' | 'below' | 'inline' | null {
  const a = parseValue(actual), f = parseValue(forecast);
  if (a == null || f == null) return null;
  return a > f ? 'above' : a < f ? 'below' : 'inline';
}
