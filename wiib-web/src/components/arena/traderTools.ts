import i18n from '../../i18n';
import type { AiTraderDecisionView, PlanRevision, TraderPlanEntry } from '../../types';

/**
 * trader agent 每次唤醒拿到的全部工具（与后端 TraderWakeupRunner.wakeTools 同一份清单）：
 * 交易动作会动账本，行情数据只读。顺序即展示顺序——时间线的工具名与配置页的 skills 卡共用。
 */
export const TRADE_TOOLS = [
  'open_position', 'close_position', 'reverse_position', 'set_stop_loss', 'set_take_profit', 'write_plan', 'cancel_order', 'set_watch', 'get_account',
] as const;
export const DATA_TOOLS = [
  'klines', 'kline_structure', 'indicators', 'market_snapshot', 'funding_history', 'orderbook_depth', 'option_iv',
  'derivatives_history', 'whale_positions', 'news_search', 'cross_asset_snapshot', 'web_search',
] as const;

/** 工具 id → 词表 key（存 key 不存文案：模块级常量只算一次，切语言不会失效） */
const TOOL_KEY: Record<string, string> = {
  open_position: 'tool.openPosition', close_position: 'tool.closePosition', set_stop_loss: 'tool.setStopLoss',
  reverse_position: 'tool.reversePosition',
  set_take_profit: 'tool.setTakeProfit', cancel_order: 'tool.cancelOrder', write_plan: 'tool.writePlan',
  set_watch: 'tool.setWatch', get_account: 'tool.getAccount', klines: 'tool.klines', kline_structure: 'tool.klineStructure',
  indicators: 'tool.indicators', market_snapshot: 'tool.marketSnapshot', funding_history: 'tool.fundingHistory',
  orderbook_depth: 'tool.orderbookDepth', option_iv: 'tool.optionIv', news_search: 'tool.newsSearch',
  derivatives_history: 'tool.derivativesHistory', whale_positions: 'tool.whalePositions',
  cross_asset_snapshot: 'tool.crossAssetSnapshot', web_search: 'tool.webSearch',
  // 不是模型调的工具：预案命中后平台执行的那一下，时间线上按一条动作显示
  preset: 'tool.preset',
  // 单笔复盘查历史用的三个
  round_detail: 'tool.roundDetail', trade_history: 'tool.tradeHistory', news_archive: 'tool.newsArchive',
};

/** 工具的展示名；表里没有的（后端加了新工具）原样显示 id */
export function toolName(tool: string): string {
  const key = TOOL_KEY[tool];
  return key ? i18n.t(`ai:${key}`) : tool;
}

/** 会动账本的那几个，加上预案执行的那一下：时间线上独立成行展示参数与拒因，其余数据查询弱化成一句"看了什么" */
export const TRADE_TOOL_SET: ReadonlySet<string> = new Set([...TRADE_TOOLS, 'preset']);

/** 止盈的一档：价位，和这一档平掉仓位的百分之几 */
export interface TargetRung {
  price: number;
  percent: number;
}

/** 计划里存的止盈分档；没设止盈或解析不了给空表 */
export function planTargets(json: string | null | undefined): TargetRung[] {
  try {
    return json ? JSON.parse(json) as TargetRung[] : [];
  } catch {
    return [];
  }
}

export function planEntries(json: string | null | undefined): TraderPlanEntry[] {
  try {
    return json ? JSON.parse(json) as TraderPlanEntry[] : [];
  } catch {
    return [];
  }
}

/** 计划的修订记录；没改过或解析不了给空表 */
export function planRevisions(json: string | null | undefined): PlanRevision[] {
  try {
    return json ? JSON.parse(json) as PlanRevision[] : [];
  } catch {
    return [];
  }
}

export function entryStatus(status: string): string {
  return i18n.t(`ai:plan.entryStatus.${status.toLowerCase()}`);
}

/** 价位：千分位，小数照原样（小币的价位小数位多） */
export function px(v: unknown): string {
  return Number(v).toLocaleString('en-US', { maximumFractionDigits: 8 });
}

/** 止盈档写成「105,000(50%) / 110,000(50%)」 */
export function rungsText(rungs: unknown): string {
  return Array.isArray(rungs)
    ? (rungs as TargetRung[]).map(r => `${px(r.price)}(${r.percent}%)`).join(' / ')
    : '';
}

/** actions_json 里的一条工具动作 */
export interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
  result?: {
    close?: ActionRow; open?: ActionRow; entries?: EntryResult[]; orders?: { status: string }[];
    /** 改止损：原来的止损价（老记录没有） */
    from?: number | null;
    /** 平仓回执：哪个币、平了多少、成交价、这次平掉的已实现盈亏 */
    symbol?: string; quantity?: number; filledPrice?: number; realizedPnl?: number;
  };
}

export interface EntryResult {
  quantity: number;
  limitPrice?: number;
  filledPrice?: number;
  status?: string;
}

/** 开仓的各档：有回执用回执（带成交没有），被拒的用参数；更早的老格式只有一档 quantity/limitPrice */
export function openEntries(a: ActionRow): EntryResult[] {
  if (a.result?.entries) return a.result.entries;
  if (Array.isArray(a.args?.entries)) return a.args.entries as EntryResult[];
  return a.args?.quantity != null
    ? [{ quantity: Number(a.args.quantity), limitPrice: a.args.limitPrice != null ? Number(a.args.limitPrice) : undefined }]
    : [];
}

/** 动作的理由：模型调工具时写的那句话，开仓、反手、补计划是依据；指到那一行时给看 */
export function actionNote(a: ActionRow): string | undefined {
  const note = a.args?.reason ?? a.args?.signalsUsed;
  return note != null ? String(note) : undefined;
}

export function parseActions(json: string | null): ActionRow[] {
  try {
    return json ? JSON.parse(json) as ActionRow[] : [];
  } catch {
    return [];
  }
}

/** 不算「动过仓位」的交易工具：布盯盘、查账户 */
const NOT_ACTING = new Set(['set_watch', 'get_account']);

function acceptedEntry(entry: EntryResult): boolean {
  return entry.status === 'filled' || entry.status === 'pending';
}

function doneAction(action: ActionRow): boolean {
  if (action.rejected || action.status === 'error' || action.status === 'rejected') return false;
  if (action.status === 'partial' || action.status === 'unknown') {
    if (action.tool === 'cancel_order') return action.result?.orders?.some(o => o.status === 'cancelled') ?? false;
    return action.result?.entries?.some(acceptedEntry) ?? false;
  }
  return action.status == null || action.status === 'ok';
}

/** 已做成的动作；分档和反手保留实际成功的部分。 */
export function doneTrades(d: AiTraderDecisionView): ActionRow[] {
  return parseActions(d.actionsJson)
    .flatMap(a => a.tool === 'reverse_position' && a.status !== 'ok'
      ? [a.result?.close, a.result?.open].filter((leg): leg is ActionRow => leg != null)
      : [a])
    .filter(a => TRADE_TOOL_SET.has(a.tool) && !NOT_ACTING.has(a.tool) && doneAction(a));
}

/**
 * 交易动作的关键参数一行话：按工具挑重点，只写做了什么，理由不写（理由见 {@link actionNote}）。
 * 改止损写成「原来 → 现在」。词表在函数体里现查，切语言即变
 */
export function tradeArgsSummary(a: ActionRow): string {
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  const tr = (key: string, vars?: Record<string, string>) => i18n.t(`ai:${key}`, vars ?? {});
  const tp = rungsText(a.args?.takeProfits);
  switch (a.tool) {
    case 'reverse_position':
      return [tr('args.position', { id: g('positionId') }), g('quantity') && tr('args.qty', { n: g('quantity') }),
        g('leverage') && `${g('leverage')}x`, g('stopLossPrice') && tr('args.sl', { p: px(g('stopLossPrice')) }),
        tp && tr('args.tp', { p: tp }), g('playType')].filter(Boolean).join(' · ');
    case 'open_position':
      return [g('symbol'), sideText(g('side')), entriesText(openEntries(a)), g('leverage') && `${g('leverage')}x`,
        g('stopLossPrice') && tr('args.sl', { p: px(g('stopLossPrice')) }), tp && tr('args.tp', { p: tp }),
        g('playType')].filter(Boolean).join(' · ');
    case 'close_position':
      return [tr('args.position', { id: g('positionId') }), g('quantity') && tr('args.qty', { n: g('quantity') })]
        .filter(Boolean).join(' · ');
    case 'set_stop_loss': {
      const from = a.result?.from;
      return `${from != null ? `${px(from)} ` : ''}→ ${px(g('stopLossPrice'))}`;
    }
    case 'set_take_profit':
      return `→ ${tp || tr('args.tpNone')}`;
    case 'preset': {
      const target = [g('symbol'), g('side')].filter(Boolean).join(' ');
      const what = g('type') === 'MOVE_STOP' ? tr('args.presetMoveStop', { p: px(g('stopLossPrice')) })
        : g('type') === 'CLOSE' ? tr('args.presetClose', { pct: g('closePercent') })
          : tr('args.order', { id: g('orderId') });
      return [target, what].filter(Boolean).join(' · ');
    }
    case 'write_plan':
      return [tr('args.position', { id: g('positionId') }), g('playType')].filter(Boolean).join(' · ');
    case 'cancel_order':
      return Array.isArray(a.args?.orderIds)
        ? a.args.orderIds.map(id => tr('args.order', { id: String(id) })).join(' / ')
        : tr('args.order', { id: g('orderId') });
    case 'set_watch': {
      const n = Array.isArray(a.args?.watches) ? (a.args.watches as unknown[]).length : 0;
      return [g('symbol'), tr('args.watches', { n: String(n) })].filter(Boolean).join(' · ');
    }
    default:
      return '';
  }
}

/** 方向的叫法：做多、做空；认不出原样 */
export function sideText(side: string): string {
  return side === 'LONG' ? i18n.t('ai:args.long') : side === 'SHORT' ? i18n.t('ai:args.short') : side;
}

function entriesText(entries: EntryResult[]): string {
  return entries.map(entry => [
    `${entry.limitPrice != null ? px(entry.limitPrice) : i18n.t('ai:plan.marketEntry')} × ${entry.quantity}`,
    entry.status && entryStatus(entry.status),
    entry.filledPrice != null && i18n.t('ai:plan.filledAt', { price: px(entry.filledPrice) }),
  ].filter(Boolean).join(' ')).join(' / ');
}

/**
 * 数据工具的参数说人话：「BTCUSDT 4h · 200 根」而不是 symbol=BTCUSDT interval=4h limit=200。
 * 认不出的工具回 null，调用方退回 k=v 原样显示。
 */
export function dataArgsSummary(tool: string, args: Record<string, unknown>): string | null {
  const g = (k: string) => args[k] != null ? String(args[k]) : '';
  switch (tool) {
    case 'klines':
    case 'indicators':
    case 'kline_structure': {
      // 根数是模型自己加的参数，后端这三个工具都固定取 192 根；给了才拼上
      const n = g('limit') || g('bars');
      return [[g('symbol'), g('interval')].filter(Boolean).join(' '),
        n && i18n.t('ai:live.bars', { count: Number(n) })].filter(Boolean).join(' · ');
    }
    case 'derivatives_history':
      return [g('symbol'), g('period')].filter(Boolean).join(' ');
    case 'market_snapshot':
    case 'option_iv':
    case 'funding_history':
    case 'orderbook_depth':
    case 'whale_positions':
      return g('symbol');
    case 'news_search':
      return [g('keyword'), g('hours') && `${g('hours')}h`].filter(Boolean).join(' · ');
    case 'web_search':
      return g('query');
    case 'cross_asset_snapshot':
      return '';
    default:
      return null;
  }
}
