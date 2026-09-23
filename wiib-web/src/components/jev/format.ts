/** 概率显示成百分比整数；空显示 -- */
export function pct(v?: number): string {
  return v == null ? '--' : `${Math.round(v * 100)}%`;
}

/** 实际动作的芯片配色：真买卖了的填色，其余描边 */
export const ACTION_CHIP: Record<string, string> = {
  BUY_UP: 'fill up',
  BUY_DOWN: 'fill dn',
  SELL: 'fill',
  HOLD: '',
  STAY_OUT: 'mute',
  ERROR: 'dn',
};

/** R1 旧版决定题各选项：文字/芯片色 + 概率条色 */
export const CHOICE_STYLE: Record<string, { text: string; bar: string }> = {
  BUY_UP: { text: 'up', bar: 'bg-gain' },
  BUY_DOWN: { text: 'dn', bar: 'bg-loss' },
  WAIT: { text: 'mute', bar: 'bg-muted-foreground/40' },
};

/** R1 旧版后劲三档：在回吐 / 没方向 / 还在推 */
export const MOMENTUM_TEXT = ['wn', 'mute', ''];
export const MOMENTUM_BAR = ['bg-warning', 'bg-muted-foreground/40', 'bg-foreground'];

/** reason 的首个词：后端的代码，见 PredictionRules */
export function reasonCode(d: { reason?: string }): string {
  return d.reason?.split(' ')[0] ?? '';
}

/** reason 的第二个词：BUY / WAIT / ASK_LOW 挑中的那边；R1 旧版的行没有 */
export function reasonSide(d: { reason?: string }): 'UP' | 'DOWN' | undefined {
  const s = d.reason?.split(' ')[1];
  return s === 'UP' || s === 'DOWN' ? s : undefined;
}

/** 每种代码的分类：已执行 / 照常不动或拿着 / 把握不够 / 被代码拦下 / 这次没问；UNSURE、NOT_CHEAP、EXPENSIVE、ASK_RANGE 只有 R1 旧版有 */
const REASON_KIND: Record<string, 'done' | 'idle' | 'unsure' | 'blocked' | 'skipped'> = {
  BUY: 'done', SELL: 'done', WAIT: 'idle', HOLD: 'idle', UNSURE: 'unsure',
  NO_QUOTE: 'blocked', ASK_LOW: 'blocked', ASK_RANGE: 'blocked', NOT_CHEAP: 'blocked', EXPENSIVE: 'blocked',
  NO_BALANCE: 'blocked', NO_BID: 'blocked',
  STALE_BOOK: 'skipped', STALE_WHILE_ASKING: 'skipped',
};

/** 提示行只在有新信息时出；照常执行和照常不动的，动作芯片和优势那行已经说清楚了 */
export function noticeKind(d: { error?: string; reason?: string }): 'unsure' | 'blocked' | 'skipped' | 'error' | null {
  if (d.error) return 'error';
  const kind = REASON_KIND[reasonCode(d)];
  return kind === 'unsure' || kind === 'blocked' || kind === 'skipped' ? kind : null;
}

/** 正常的"先等 / 拿着"和把握不够的折起，其余（下单、卖出、被拦、没问、出错）默认露出来 */
export function noteworthy(d: { error?: string; reason?: string }): boolean {
  const kind = REASON_KIND[reasonCode(d)];
  return !!d.error || (kind !== 'idle' && kind !== 'unsure');
}
