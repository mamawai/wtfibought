import type { ChatItem } from './chatStore';

/** 主 agent 的对外名字：后端事件里仍叫 supervisor，只在展示层换 */
export const HUB_NAME = 'Polaris';

/** "做着"的那颗脉冲橙点：过程轨、答案头、输入区状态行共用 */
export const PULSE_DOT = 'w-1.5 h-1.5 shrink-0 bg-primary animate-[pt-pulse_1.2s_ease-in-out_infinite] motion-reduce:animate-none';

/** 带 rid 的那几种条目就是过程条目 */
type RailItem = Extract<ChatItem, { rid: number }>;

/** 过程类条目（进度/工具调用/搜索/后台子运行）归进工作过程轨；其余各自成块 */
const isRailKind = (it: ChatItem): it is RailItem =>
  it.kind === 'progress' || it.kind === 'tool' || it.kind === 'search' || it.kind === 'child';

/** 这几个工具调了之后屏上另有东西（子运行条目、确认卡、表单卡），工具条目本身不画 */
const HIDDEN_TOOLS = new Set(['delegate', 'run_deep_analysis', 'leave_note_to_trader', 'wake_trader', 'review_trader_now']);

export type RailStep = { item: RailItem; index: number };
export type Block =
  // key 带 rail- 前缀：单条块用的是 items 下标，轨用的是自增 rid，两者都是小整数，
  // 同处一层 children 就会撞（第一轮的答案下标 2 撞第二轮的轨 rid 2）。前缀让两套编号各占各的空间
  // replayed=历史回放重建的轨（默认收着），实时长出来的轨默认开着
  | { kind: 'single'; item: ChatItem; index: number }
  | { kind: 'rail'; steps: RailStep[]; key: string; replayed: boolean };

/** 相邻过程条目并轨：user/assistant/hitl/form/error 断轨 */
export function groupBlocks(items: ChatItem[]): Block[] {
  const out: Block[] = [];
  items.forEach((item, index) => {
    if (item.kind === 'tool' && HIDDEN_TOOLS.has(item.name)) return;
    if (isRailKind(item)) {
      const last = out[out.length - 1];
      if (last?.kind === 'rail') last.steps.push({ item, index });
      // 键取轨首条目的 rid 而不是下标：条目在 items 里挪位置（重新生成砍尾巴）时下标会整体错位，
      // 收着的轨会自己弹开
      else out.push({ kind: 'rail', steps: [{ item, index }], key: `rail-${item.rid}`, replayed: item.replayed === true });
    } else {
      out.push({ kind: 'single', item, index });
    }
  });
  return out;
}
