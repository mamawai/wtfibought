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

/** Jev 拍板的各选项：文字色 + 概率条色；WAIT 只有 R1 旧版有，HOLD / SELL 只有 R3 有 */
export const CHOICE_STYLE: Record<string, { text: string; bar: string }> = {
  BUY_UP: { text: 'up', bar: 'bg-gain' },
  BUY_DOWN: { text: 'dn', bar: 'bg-loss' },
  PASS: { text: 'mute', bar: 'bg-muted-foreground/40' },
  WAIT: { text: 'mute', bar: 'bg-muted-foreground/40' },
  HOLD: { text: '', bar: 'bg-foreground' },
  SELL: { text: 'wn', bar: 'bg-warning' },
};

/** 入场题的选项，按发给 Jev 的顺序；R4 起空仓持仓都问它。离场题只有 R3 有 */
export const ENTRY_OPTIONS = ['BUY_UP', 'BUY_DOWN', 'PASS'];
export const EXIT_OPTIONS = ['HOLD', 'SELL'];

/** R1 旧版后劲三档：在回吐 / 没方向 / 还在推 */
export const MOMENTUM_TEXT = ['wn', 'mute', ''];
export const MOMENTUM_BAR = ['bg-warning', 'bg-muted-foreground/40', 'bg-foreground'];

/** reason 的首个词：后端的代码，见 PredictionRules */
export function reasonCode(d: { reason?: string }): string {
  return d.reason?.split(' ')[0] ?? '';
}

/** reason 的第二个词：BUY / ADD / UNSURE / NO_QUOTE / MISSED / MAX_STAKE（R2 的 WAIT / ASK_LOW）是哪边；R1 旧版的行没有 */
export function reasonSide(d: { reason?: string }): 'UP' | 'DOWN' | undefined {
  const s = d.reason?.split(' ')[1];
  return s === 'UP' || s === 'DOWN' ? s : undefined;
}

/**
 * 每种代码的分类：已执行 / 照常不动或拿着 / 把握不够或加满了照常拿着（折起、灰字提示）/ 被代码拦下 / 这次没问或没成交。
 * WAIT 只有 R1、R2 有，ASK_LOW 只有 R2 有，NOT_CHEAP、EXPENSIVE、ASK_RANGE 只有 R1 有
 */
const REASON_KIND: Record<string, 'done' | 'idle' | 'unsure' | 'blocked' | 'skipped'> = {
  BUY: 'done', ADD: 'done', SELL: 'done', PASS: 'idle', WAIT: 'idle', HOLD: 'idle', UNSURE: 'unsure', MAX_STAKE: 'unsure',
  NO_QUOTE: 'blocked', ASK_LOW: 'blocked', ASK_RANGE: 'blocked', NOT_CHEAP: 'blocked', EXPENSIVE: 'blocked',
  NO_BALANCE: 'blocked', NO_BID: 'blocked',
  STALE_BOOK: 'skipped', STALE_CHAINLINK: 'skipped', STALE_WHILE_ASKING: 'skipped', MISSED: 'skipped',
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
