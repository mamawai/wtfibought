import i18n from '../../i18n';
import type { AiTraderDecisionView } from '../../types';

/**
 * trader agent 每次唤醒拿到的全部工具（与后端 TraderWakeupRunner.wakeTools 同一份清单）：
 * 交易动作会动账本，行情数据只读。顺序即展示顺序——时间线的工具名与配置页的 skills 卡共用。
 */
export const TRADE_TOOLS = [
  'open_position', 'close_position', 'set_stop_loss', 'set_take_profit', 'write_plan', 'cancel_order', 'set_watch', 'get_account',
] as const;
export const DATA_TOOLS = [
  'klines', 'kline_structure', 'indicators', 'market_snapshot', 'funding_history', 'orderbook_depth', 'option_iv',
  'derivatives_history', 'whale_positions', 'news_search', 'cross_asset_snapshot', 'web_search',
] as const;

/** 工具 id → 词表 key（存 key 不存文案：模块级常量只算一次，切语言不会失效） */
const TOOL_KEY: Record<string, string> = {
  open_position: 'tool.openPosition', close_position: 'tool.closePosition', set_stop_loss: 'tool.setStopLoss',
  set_take_profit: 'tool.setTakeProfit', cancel_order: 'tool.cancelOrder', write_plan: 'tool.writePlan',
  set_watch: 'tool.setWatch', get_account: 'tool.getAccount', klines: 'tool.klines', kline_structure: 'tool.klineStructure',
  indicators: 'tool.indicators', market_snapshot: 'tool.marketSnapshot', funding_history: 'tool.fundingHistory',
  orderbook_depth: 'tool.orderbookDepth', option_iv: 'tool.optionIv', news_search: 'tool.newsSearch',
  derivatives_history: 'tool.derivativesHistory', whale_positions: 'tool.whalePositions',
  cross_asset_snapshot: 'tool.crossAssetSnapshot', web_search: 'tool.webSearch',
};

/** 工具的展示名；表里没有的（后端加了新工具）原样显示 id */
export function toolName(tool: string): string {
  const key = TOOL_KEY[tool];
  return key ? i18n.t(`ai:${key}`) : tool;
}

/** 会动账本的那几个：时间线上独立成行展示参数与拒因，其余数据查询弱化成一句"看了什么" */
export const TRADE_TOOL_SET: ReadonlySet<string> = new Set(TRADE_TOOLS);

/** actions_json 里的一条工具动作 */
export interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
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

/** 这轮做成了的交易动作（布盯盘、查账户、被拒、出错的不算） */
export function doneTrades(d: AiTraderDecisionView): ActionRow[] {
  return parseActions(d.actionsJson)
    .filter(a => TRADE_TOOL_SET.has(a.tool) && !NOT_ACTING.has(a.tool) && !a.rejected && a.status !== 'error');
}

/** 交易动作的关键参数一行话（按工具挑重点，不倒整个 JSON）。词表在函数体里现查，切语言即变 */
export function tradeArgsSummary(a: ActionRow): string {
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  const tr = (key: string, vars?: Record<string, string>) => i18n.t(`ai:${key}`, vars ?? {});
  switch (a.tool) {
    case 'open_position': {
      const parts = [g('symbol'), g('side') === 'LONG' ? tr('args.long') : g('side') === 'SHORT' ? tr('args.short') : g('side'),
        g('quantity') && tr('args.qty', { n: g('quantity') }), g('leverage') && `${g('leverage')}x`,
        g('stopLossPrice') && tr('args.sl', { p: g('stopLossPrice') }),
        g('takeProfitPrice') && tr('args.tp', { p: g('takeProfitPrice') }),
        g('playType')];
      return parts.filter(Boolean).join(' · ');
    }
    case 'close_position':
      return [tr('args.position', { id: g('positionId') }), g('quantity') && tr('args.qty', { n: g('quantity') }), g('reason')]
        .filter(Boolean).join(' · ');
    case 'set_stop_loss':
      return [`→${g('stopLossPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'set_take_profit':
      return [`→${g('takeProfitPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'write_plan':
      return [g('playType'), g('invalidationCondition') && tr('args.invalidation', { c: g('invalidationCondition') })]
        .filter(Boolean).join(' · ');
    case 'cancel_order':
      return tr('args.order', { id: g('orderId') });
    case 'set_watch': {
      const n = Array.isArray(a.args?.watches) ? (a.args.watches as unknown[]).length : 0;
      return [g('symbol'), tr('args.watches', { n: String(n) })].filter(Boolean).join(' · ');
    }
    default:
      return '';
  }
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
