import { fmtNum, toCents } from '../../lib/utils';
import type { JevArm, JevRun, JevThresholds } from '../../types';

/** 概率显示成百分比整数；空显示 -- */
export function pct(v?: number): string {
  return v == null ? '--' : `${Math.round(v * 100)}%`;
}

/** 三组的代号，局切换、对照表上标在局号后面 */
export const ARM_CODE: Record<JevArm, string> = { JUMP_CODE: 'v5-1', JUMP_JEV: 'v5-2', TIMER_JEV: 'v5-3' };

/** 局的叫法：R6 · v5-1，旧局只有局号 */
export function runName(r: Pick<JevRun, 'runNo' | 'arm'>): string {
  return r.arm ? `R${r.runNo} · ${ARM_CODE[r.arm]}` : `R${r.runNo}`;
}

/** 说明文字里要写的数，都取现在的配置：时长写成秒，价写成美分，比例和概率写成百分比 */
export function thresholdVars(th: JevThresholds) {
  return {
    stake: fmtNum(th.baseStake, 0), delay: th.fillDelayMs / 1000, tol: toCents(th.fillTolerance),
    jump: toCents(th.jumpThreshold), from: toCents(th.jumpFromMin), to: toCents(th.jumpFromMax),
    watch: th.jumpWatchMs / 1000, extend: toCents(th.jumpExtend), ratio: pct(th.jumpRejectRatio),
    extendMin: pct(th.jumpExtendMin), rejectMin: pct(th.jumpRejectMin),
    askMin: toCents(th.timerAskMin), askMax: toCents(th.timerAskMax),
    against: pct(th.timerAgainstMin), sellWin: pct(th.timerSellWinMax),
  };
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

/** 入场题的选项，按发给 Jev 的顺序；R4 空仓持仓都问它。离场题只有 R3 有 */
export const ENTRY_OPTIONS = ['BUY_UP', 'BUY_DOWN', 'PASS'];
export const EXIT_OPTIONS = ['HOLD', 'SELL'];

/** R1 旧版后劲三档：在回吐 / 没方向 / 还在推 */
export const MOMENTUM_TEXT = ['wn', 'mute', ''];
export const MOMENTUM_BAR = ['bg-warning', 'bg-muted-foreground/40', 'bg-foreground'];

/** v5 形态题的选项，按发给 Jev 的顺序：单边 / 震荡 / 没形态；文字色 + 概率条色 */
export const PATTERN_OPTIONS = ['cascade', 'chop', 'neither'];
export const PATTERN_STYLE: Record<string, { text: string; bar: string }> = {
  cascade: { text: '', bar: 'bg-foreground' },
  chop: { text: 'wn', bar: 'bg-warning' },
  neither: { text: 'mute', bar: 'bg-muted-foreground/40' },
};

/** 这一行怎么来的：checkpoint 首字母 T 整点、J 突变、W v5-1 盯完；旧局的行都是 T，只在分组的局里标 */
export function wakePath(d: { checkpoint: string }): 'T' | 'J' | 'W' {
  const c = d.checkpoint[0];
  return c === 'J' || c === 'W' ? c : 'T';
}

/** reason 的首个词：后端的代码，见 PredictionRules */
export function reasonCode(d: { reason?: string }): string {
  return d.reason?.split(' ')[0] ?? '';
}

/**
 * reason 的第二个词：BUY / UNSURE / NO_QUOTE / MISSED（v4 的 ADD / MAX_STAKE、R2 的 WAIT / ASK_LOW、v5 条件没过的各代码）是哪边，
 * v5 的 EXTEND / REJECT 是买的那一边，WATCH / NO_TRIGGER / NO_CALL 是突变那一边，HOLD / SELL / NO_BID 是手里那一边；R1 旧版的行没有
 */
export function reasonSide(d: { reason?: string }): 'UP' | 'DOWN' | undefined {
  const s = d.reason?.split(' ')[1];
  return s === 'UP' || s === 'DOWN' ? s : undefined;
}

/**
 * reason 第三个词起的数，按顺序：v5-3 条件没过时第一个是没过的那个数（PRICE_BAND 是卖价，其余是概率），SELL 是会赢；
 * v5-1 盯完是价差，v5-2 突变是概率，NO_CALL 两个依次是会延续、会被打回
 */
export function reasonNums(d: { reason?: string }): number[] {
  return d.reason?.split(' ').slice(2).map(Number).filter(Number.isFinite) ?? [];
}

/**
 * 每种代码的分类：已执行 / 照常不动或拿着 / 把握不够、加满了、条件没过（折起、灰字提示）/ 被代码拦下 / 这次没问或没成交。
 * SELL、NO_BID 旧局和 v5-3 有，ADD、MAX_STAKE 只有 v4 有，WAIT 只有 R1、R2 有，ASK_LOW 只有 R2 有，NOT_CHEAP、EXPENSIVE、ASK_RANGE 只有 R1 有；
 * EXTEND、REJECT 是 v5-1 盯完和 v5-2 突变时买的，WATCH、NO_TRIGGER 只有 v5-1 有，NO_CALL 只有 v5-2 有，FADING 只有旧的 v5-2 局有，
 * PRICE_BAND、NO_PULLBACK 只有 v5-3 有
 */
const REASON_KIND: Record<string, 'done' | 'idle' | 'unsure' | 'blocked' | 'skipped'> = {
  BUY: 'done', ADD: 'done', SELL: 'done', EXTEND: 'done', REJECT: 'done',
  PASS: 'idle', WAIT: 'idle', HOLD: 'idle', WATCH: 'idle', UNSURE: 'unsure', MAX_STAKE: 'unsure',
  NO_TRIGGER: 'unsure', NO_CALL: 'unsure', FADING: 'unsure', PRICE_BAND: 'unsure', NO_PULLBACK: 'unsure',
  NO_QUOTE: 'blocked', ASK_LOW: 'blocked', ASK_RANGE: 'blocked', NOT_CHEAP: 'blocked', EXPENSIVE: 'blocked',
  NO_BALANCE: 'blocked', NO_BID: 'blocked',
  STALE_BOOK: 'skipped', STALE_CHAINLINK: 'skipped', STALE_WHILE_ASKING: 'skipped', MISSED: 'skipped',
};

/**
 * 提示行只在有新信息时出；照常执行和照常不动的，动作芯片和第二行已经说清楚了。
 * v5 分组的局里持有、卖出、开始盯、延续、被打回也说一句：为什么拿着、为什么卖、盯着什么、为什么买这一边
 */
export function noticeKind(d: { error?: string; reason?: string }, r5: boolean): 'unsure' | 'blocked' | 'skipped' | 'error' | null {
  if (d.error) return 'error';
  const code = reasonCode(d);
  if (r5 && ['HOLD', 'SELL', 'WATCH', 'EXTEND', 'REJECT'].includes(code)) return 'unsure';
  const kind = REASON_KIND[code];
  return kind === 'unsure' || kind === 'blocked' || kind === 'skipped' ? kind : null;
}

/** 正常的"先等 / 拿着 / 已经买过 / 开始盯"、把握不够、条件没过的折起，其余（下单、卖出、被拦、没问、出错）默认露出来 */
export function noteworthy(d: { error?: string; reason?: string }): boolean {
  const kind = REASON_KIND[reasonCode(d)];
  return !!d.error || (kind !== 'idle' && kind !== 'unsure');
}
