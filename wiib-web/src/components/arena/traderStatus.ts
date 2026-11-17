/**
 * trader 运行状态徽章（竞技场排行与详情页共用一份，别再各抄各的）。
 * 存的是词表 key 不是文案：模块级常量只算一次，存翻好的字面量切了语言也不会变。
 * tone 是 .tag 的配色（g 绿 / w 黄 / r 红），shortKey 是排行行里"R3 · 在跑"那半句。
 */
export const STATUS_META: Record<string, { labelKey: string; shortKey: string; tone: 'g' | 'w' | 'r' }> = {
  RUNNING: { labelKey: 'status.running', shortKey: 'arena.runningShort', tone: 'g' },
  PAUSED: { labelKey: 'status.paused', shortKey: 'arena.pausedShort', tone: 'w' },
  LIQUIDATED: { labelKey: 'status.liquidated', shortKey: 'arena.liqShort', tone: 'r' },
};
