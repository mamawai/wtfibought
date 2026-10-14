import type { ChatItem } from './chatStore';

/** 主 agent 的对外名字：后端事件里仍叫 supervisor，只在展示层换 */
export const HUB_NAME = 'Polaris';

/** 带 rid 的那几种条目就是过程条目 */
type RailItem = Extract<ChatItem, { rid: number }>;

/** 过程类条目（进度/搜索/后台子运行）归进工作过程轨；其余各自成块 */
const isRailKind = (it: ChatItem): it is RailItem =>
  it.kind === 'progress' || it.kind === 'search' || it.kind === 'child';

export type RailStep = { item: ChatItem; index: number };
export type Block =
  // key 带 rail- 前缀：单条块用的是 items 下标，轨用的是自增 rid，两者都是小整数，
  // 同处一层 children 就会撞（第一轮的答案下标 2 撞第二轮的轨 rid 2）。前缀让两套编号各占各的空间
  | { kind: 'single'; item: ChatItem; index: number }
  | { kind: 'rail'; steps: RailStep[]; key: string };

/** 相邻过程条目并轨：user/assistant/hitl/form/error 断轨 */
export function groupBlocks(items: ChatItem[]): Block[] {
  const out: Block[] = [];
  items.forEach((item, index) => {
    if (isRailKind(item)) {
      const last = out[out.length - 1];
      if (last?.kind === 'rail') last.steps.push({ item, index });
      // 键取轨首条目的 rid 而不是下标：条目在 items 里挪位置（重新生成砍尾巴）时下标会整体错位，
      // 收着的轨会自己弹开
      else out.push({ kind: 'rail', steps: [{ item, index }], key: `rail-${item.rid}` });
    } else {
      out.push({ kind: 'single', item, index });
    }
  });
  return out;
}
