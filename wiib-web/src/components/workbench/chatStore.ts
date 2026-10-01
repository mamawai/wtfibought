import { ApiError, workbenchApi } from '../../api';
import type { BehaviorAnalysisReport, ChatIntent, SearchSource, TraderFormKind, TurnMeta, WorkbenchChatMessage, WorkbenchEvent } from '../../types';

/** 与后端 ErrorCode 对齐：2200 段是研判工作台（1600 段是 Crypto，别复用） */
export const CHAT_ERROR = {
  CONFIG_MISSING: 2201,
  CONFIG_INVALID: 2202,
  ALREADY_RUNNING: 2203,
  CAPACITY_FULL: 2204,
  /** 这条回答回不去（末尾不是答案／补答行／本轮触发过历史压缩），重新生成被拒 */
  REGENERATE_UNAVAILABLE: 2206,
  /** 发起补答轮时会话已不欠账（别的标签页接走了，或后端重启丢了队列） */
  NOTHING_DEFERRED: 2208,
} as const;

/**
 * 工作台对话 store（模块级单例）：状态与 SSE 消费脱离组件生命周期——
 * 切页只是 ChatPanel 卸载，流在这里继续收、状态继续涨，切回来订阅即续显；
 * 整页刷新后 store 归零，靠 status 轮询发现"AI 还在后台跑"，结束拉历史补答案。
 * <p>
 * 让位后欠下的补答由<b>前端发起</b>（{@link runDeferred}）：后端只排队不偷跑。
 * 时机只有一条规则——本地没有轮在跑、排队消息也发完了（用户消息永远优先），见 {@link settle}。
 */

export type ChatItem =
  // queued=后端不让位（正在出答案）时先上屏排队，本轮结束自动真发；
  // 专家取数期间的新消息会触发后端让位直接插话，不走排队
  // at=这条消息的时刻（历史回放取库里的 createdAt，实时取本地时钟），面板上要显示。
  // queuedId=这条消息的身份，气泡与队列条目共用——靠下标对应的话，
  // 排队期间流式往 items 里插条目、回放整体重建、重生成砍尾巴，任一处都会让两边错位
  | { kind: 'user'; content: string; at: number; queued?: boolean; queuedId?: number }
  // meta=这一轮的读数（端点/耗时/token），流式结束时随 done 事件到；历史回放从库里带。
  // deferred=这条是补答（对应提问不在会话末尾，回退会误伤中间轮次），不给重新生成；
  // 实时流里的答案永远在末尾，只有历史回放才可能是补答行
  // sources=这一轮联网搜索的来源，随 done 到（历史回放从库里带），答案底部展示
  | { kind: 'assistant'; content: string; streaming: boolean; at: number; meta?: TurnMeta | null; deferred?: boolean; sources?: SearchSource[] | null }
  // 专家过程流（不落历史）：视图层收进"工作过程"轨，折叠状态归视图管。
  // rid=轨内条目的自增号，视图拿轨首那条的 rid 当折叠状态的键——用下标做键的话，
  // 让位说明行往中间一插、重新生成把尾巴一砍，键就整体错位，收着的轨会自己弹开
  | { kind: 'expert'; rid: number; agent: string; content: string; streaming: boolean }
  | { kind: 'agent'; rid: number; node: string; agent: string }
  // 长工具阶段进度（深研判等）：最新一条亮着转圈，后续事件到达即熄灭。
  // keyed=true 时 text 存的是 ai 词表的 key（前端自己立的说明行），由视图层渲染时现翻——
  // store 是纯 ts 模块、条目一存就是一整场会话，存翻好的字面量切了语言会僵在旧语言里。
  // 后端下发的阶段文案 keyed 为假，原样显示（那是后端数据，不进词表）
  | { kind: 'progress'; rid: number; text: string; keyed?: boolean; active: boolean }
  // 汇总者的一次联网搜索：active=还在搜（亮着转圈），搜完回填 sources 熄灭；query 开搜时可能还没有
  | { kind: 'search'; rid: number; query: string | null; sources: SearchSource[]; active: boolean }
  // requestId 存在 item 上：hitlDecide 按它取回本条再原样回传，服务端据此确认"点的是哪张卡"
  | { kind: 'hitl'; symbol: string; reason: string; requestId: string; resumeMessage: string; status: 'pending' | 'approved' | 'rejected' }
  // trader 动作表单卡：模型只有弹卡的权，执行权归用户点击。纯前端态不落历史，id 本地发
  | { kind: 'form'; id: string; form: TraderFormKind; prefill?: Record<string, unknown>; status: 'pending' | 'done'; result?: string }
  // 行为分析报告卡：模型调 analyze_my_behavior 后随 SSE 到。同样不落历史——
  // 报告在服务端缓存 30 分钟，刷新后想再看一眼再问一句就是了，不值得为它开一张表
  | { kind: 'behavior'; report: BehaviorAnalysisReport }
  // keyed 同 progress：前端自己的兜底报错存 key，后端/异常带回来的 message 原样显示
  | { kind: 'error'; message: string; keyed?: boolean };

export interface ChatState {
  items: ChatItem[];
  loading: boolean;
  /** true=刷新后发现会话还在后台跑（无 token 流，轮询等结果） */
  background: boolean;
  /** true=后端说没配 LLM 或配置不可用，面板显示"去配置"引导条而不是干显示一行红字 */
  needsConfig: boolean;
  sessionId: string | null;
  /** 本地每开一轮流 +1：排队续发、补答是紧接着上一轮开跑的，loading 中间不落地，面板靠它认出"换了一轮" */
  runId: number;
}

const SESSION_KEY = 'wiib-workbench-session';
const POLL_MS = 3000;
/**
 * 让位后立在原问题过程轨里的说明行（也当"这个问题已有交代"的标记，防重复插）。
 * 存的是词表 key 不是文案：既躲开切语言僵住的坑，标记比对也不会随语言变。
 */
const DEFERRED_NOTE = 'rail.deferredNote';
/** 续跑指令在过程轨里的措辞（词表 key）：与 HITL 卡自己的状态行错开，别同一句话连着显示两遍 */
const HITL_RESUME_NOTE = 'rail.hitlResume';

let state: ChatState = {
  items: [],
  loading: false,
  background: false,
  needsConfig: false,
  sessionId: sessionStorage.getItem(SESSION_KEY),
  runId: 0,
};
const listeners = new Set<() => void>();
let abortCtrl: AbortController | null = null;
/** 本地还在收的流，包括主导权已经交给插话、自己还没收尾的那条 */
const liveStreams = new Set<AbortController>();
/**
 * 已上屏、请求还没开流的提问气泡（按 queuedId 记）：后端让位接下还是占线拒掉，还没定。
 * 它和排队气泡一样算"还没开跑"，本轮的新条目插在它们前面
 */
const unopenedBubbles = new Set<number>();
let pollTimer: number | null = null;
let initialized = false;
/**
 * 后端不让位（正在出答案）时排队的待发消息。
 * bubbled=屏幕上有没有对应的排队气泡——HITL 续跑那种自动补发的指令没有气泡，
 * 续发时不能跟着去解别人气泡的排队标记。
 * noteRid=HITL 续跑指令在过程轨里那行说明的 rid（见 hitlDecide）
 */
// intent 跟着排队条目走：功能按钮那一发被后端占线拒掉后，续发时意图不能丢
type QueuedMessage = { id: number; text: string; bubbled: boolean; intent?: ChatIntent; noteRid?: number };
let sendQueue: QueuedMessage[] = [];
let queueSeq = 0;
/**
 * 会话还欠着补答（让位交出去的专家批次等着被接回）。跟着后端口径走：每个 done 事件与 status 都带 pending，
 * 前端只转抄不推断。为真且本地空闲时由 settle() 发起补答轮
 */
let deferredPending = false;
/** 表单卡只活在本地 items 里（不落历史），自增序号足够把几张卡区分开 */
let formSeq = 0;
function nextFormId() {
  return `form-${++formSeq}`;
}
/**
 * 输入框草稿。关面板会把 ChatPanel 整棵卸载，草稿放在这儿才不会跟着没。
 * <b>刻意不进 state、不发通知</b>：它只在面板重新挂载时被读一次，
 * 跟着 items 一起触发重渲染纯属浪费——流式期间那是每帧一次。
 */
let draft = '';

/** 过程条目的自增号：视图用轨首那条的 rid 记折叠状态，条目挪位置也认得回来 */
let railSeq = 0;
function nextRid() {
  return ++railSeq;
}

function set(patch: Partial<ChatState>) {
  state = { ...state, ...patch };
  listeners.forEach(l => l());
}

function updateItems(updater: (prev: ChatItem[]) => ChatItem[]) {
  set({ items: updater(state.items) });
}

function setSession(id: string | null) {
  if (id) sessionStorage.setItem(SESSION_KEY, id);
  else sessionStorage.removeItem(SESSION_KEY);
  set({ sessionId: id });
}

/**
 * 后端历史 → 对话项（专家过程/进度不落库，只回放 user/assistant）。
 * 特殊行按后端给的码分流：续跑指令不是用户打的字，还原成过程轨行，
 * 否则历史里会多出一句用户从没说过的话。
 */
function toItems(messages: WorkbenchChatMessage[]): ChatItem[] {
  return messages.map(m => {
    if (m.role !== 'user') {
      return {
        kind: 'assistant' as const, content: m.content, streaming: false,
        at: m.createdAt, meta: m.meta, deferred: m.kind === 'deferred', sources: m.sources,
      };
    }
    return m.kind === 'hitlResume'
      ? { kind: 'progress' as const, rid: nextRid(), text: HITL_RESUME_NOTE, keyed: true, active: false }
      : { kind: 'user' as const, content: m.content, at: m.createdAt };
  });
}

/** 没填完的表单卡：历史回放整体重建 items 时得接回尾部，否则用户填一半的内容无声消失 */
function pendingForms(): ChatItem[] {
  return state.items.filter(it => it.kind === 'form' && it.status === 'pending');
}

/** 还没开跑的提问：排队中，或刚发出去、请求还没开流 */
function isWaitingQuestion(it: ChatItem): boolean {
  return it.kind === 'user' && (it.queued === true || (it.queuedId != null && unopenedBubbles.has(it.queuedId)));
}

/**
 * 新条目追加到尾部、但排在尾部那串还没开跑的提问之前。
 * 直接 push 到末尾的话，本轮后面的进度、答案、报错会挂到那些新提问下面，看着像在回答它们
 */
function appendTail(items: ChatItem[], ...added: ChatItem[]): ChatItem[] {
  let at = items.length;
  while (at > 0 && isWaitingQuestion(items[at - 1])) at--;
  return [...items.slice(0, at), ...added, ...items.slice(at)];
}

/**
 * 算"一问"的条目：用户提问，以及 HITL 续跑指令那行（落库就是一条 user 行，前端还原成了过程轨里的说明行）。
 * 重新生成的切点、能不能重新生成都按它认，跟后端回退找"最后一条 user 行"一个口径
 */
export function isTurnQuestion(it: ChatItem): boolean {
  return it.kind === 'user' || (it.kind === 'progress' && it.text === HITL_RESUME_NOTE);
}

/** 进度行熄灭：token/新进度到达说明上个阶段已过去 */
function deactivateProgress(items: ChatItem[]): ChatItem[] {
  return items.some(it => it.kind === 'progress' && it.active)
    ? items.map(it => (it.kind === 'progress' && it.active ? { ...it, active: false } : it))
    : items;
}

/** 来源按 url 去重合并（同一个网站可能被几次搜索重复命中） */
function mergeSources(a: SearchSource[], b: SearchSource[]): SearchSource[] {
  const seen = new Set(a.map(s => s.url));
  return [...a, ...b.filter(s => !seen.has(s.url) && seen.add(s.url))];
}

/** 还亮着的搜索条目熄灭：done 到了说明搜索早已过去（searched 没配上对的那种） */
function settleSearches(items: ChatItem[]): ChatItem[] {
  return items.some(it => it.kind === 'search' && it.active)
    ? items.map(it => (it.kind === 'search' && it.active ? { ...it, active: false } : it))
    : items;
}

/** 这一轮没走到 done 就结束了：还在流式的答案/专家块停住，进度和搜索熄灭 */
function endStreaming(items: ChatItem[]): ChatItem[] {
  return settleSearches(deactivateProgress(items)).map(it =>
    (it.kind === 'assistant' || it.kind === 'expert') && it.streaming ? { ...it, streaming: false } : it);
}

/**
 * 包一层事件回调，记下这条流有没有正常收尾（done/error 都是最后一帧）。
 * opened=收到过事件（流建起来了）；broken=流建起来了，却没等到 done/error 就断了（网络断开、后端超时收口）
 */
function watchEnd(onEvent: (e: WorkbenchEvent) => void) {
  let opened = false;
  let ended = false;
  return {
    onEvent: (e: WorkbenchEvent) => {
      opened = true;
      if (e.type === 'done' || e.type === 'error') ended = true;
      onEvent(e);
    },
    opened: () => opened,
    broken: () => opened && !ended,
  };
}

/**
 * 搜完事件回填到哪条：先找亮着且 query 相同（或开搜时没给 query）的，
 * 再找同 query 已熄灭的（Gemini 分块补来源），都没有就新建一条
 */
function fillSearched(items: ChatItem[], query: string | null, sources: SearchSource[]): ChatItem[] {
  const next = [...items];
  for (let j = next.length - 1; j >= 0; j--) {
    const it = next[j];
    if (it.kind !== 'search') continue;
    const hit = it.active ? (it.query === query || !it.query) : (it.query !== null && it.query === query);
    if (hit) {
      next[j] = { ...it, query: it.query ?? query, sources: mergeSources(it.sources, sources), active: false };
      return next;
    }
  }
  return appendTail(next, { kind: 'search', rid: nextRid(), query, sources, active: false });
}

/**
 * 让位说明行插到被让位的那个问题名下：按后端给的原问题找到那条 user 项（同文多问取第一条还没交代的），
 * 在下一个 user 项之前插入——被让位的问题在新消息上屏之后、done(deferred) 到达之前，
 * 直接 push 到末尾会挂错到新消息名下。
 * 它已经有回答或说明行（补答轮再被让位时原问题早就立过牌子）就不再插，找不到（刷新后历史里没有这条）也不插。
 */
function insertDeferredNote(items: ChatItem[], question: string | undefined): ChatItem[] {
  if (!question) return items;
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.kind !== 'user' || item.queued || item.content !== question) continue;
    let end = items.length;
    for (let j = i + 1; j < items.length; j++) {
      if (items[j].kind === 'user') { end = j; break; }
    }
    const settled = items.slice(i + 1, end).some(it =>
      it.kind === 'assistant' || (it.kind === 'progress' && it.text === DEFERRED_NOTE));
    if (settled) continue;
    return [
      ...items.slice(0, end),
      { kind: 'progress', rid: nextRid(), text: DEFERRED_NOTE, keyed: true, active: false },
      ...items.slice(end),
    ];
  }
  return items;
}

/** 补答轮的实时答案气泡：对应提问不在会话末尾，与历史回放的补答行一样不给重新生成 */
function markLastAnswerDeferred(items: ChatItem[]): ChatItem[] {
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i];
    if (it.kind === 'assistant') {
      return [...items.slice(0, i), { ...it, deferred: true }, ...items.slice(i + 1)];
    }
  }
  return items;
}

function handleEvent(e: WorkbenchEvent) {
  switch (e.type) {
    case 'session':
      setSession(e.sessionId);
      // 流能建起来就说明端点是通的（配置类错误在准入期就拒了，根本到不了这里），
      // 引导条自己撤掉——挂着不动会让刚配好的用户以为还没生效
      if (state.needsConfig) set({ needsConfig: false });
      break;
    case 'agent_start':
      updateItems(prev => appendTail(prev, { kind: 'agent', rid: nextRid(), node: e.node, agent: e.agent }));
      break;
    case 'progress':
      updateItems(prev => appendTail(deactivateProgress(prev), { kind: 'progress', rid: nextRid(), text: e.text, active: true }));
      break;
    case 'token':
      updateItems(prev => {
        const base = deactivateProgress(prev);
        if (e.role === 'process') {
          // 并行派发时多专家 chunk 交错到达：从尾部找本专家的流式块追加，不能只看最后一项
          const next = [...base];
          for (let j = next.length - 1; j >= 0; j--) {
            const it = next[j];
            if (it.kind === 'expert' && it.agent === e.agent && it.streaming) {
              next[j] = { ...it, content: it.content + e.text };
              return next;
            }
          }
          return appendTail(next, { kind: 'expert', rid: nextRid(), agent: e.agent, content: e.text, streaming: true });
        }
        // 答案流开始：专家过程停止流式（视图层据此把工作过程轨默认收起）
        const next = base.map(it =>
          it.kind === 'expert' && it.streaming ? { ...it, streaming: false } : it,
        );
        // 同样从尾部回扫本轮答案块：表单卡这类条目会插到尾部，只认最后一项会把一轮答案劈成两截
        for (let j = next.length - 1; j >= 0; j--) {
          const it = next[j];
          if (it.kind === 'assistant' && it.streaming) {
            next[j] = { ...it, content: it.content + e.text };
            return next;
          }
        }
        return appendTail(next, { kind: 'assistant', content: e.text, streaming: true, at: Date.now() });
      });
      break;
    case 'hitl_request':
      updateItems(prev => appendTail(prev, {
        kind: 'hitl', symbol: e.symbol, reason: e.reason, requestId: e.requestId,
        resumeMessage: e.resumeMessage, status: 'pending',
      }));
      break;
    case 'form_request':
      // 模型只把卡推上屏就到头了，动作等用户在卡上点，后端不会自己往下走
      updateItems(prev => appendTail(prev, {
        kind: 'form', id: nextFormId(), form: e.form, prefill: e.prefill, status: 'pending',
      }));
      break;
    case 'behavior_report':
      // 报告卡先上屏，模型紧接着会就着它讲两句——卡是数据、答案是解读，两者互补不重复
      updateItems(prev => appendTail(deactivateProgress(prev), { kind: 'behavior', report: e.report }));
      break;
    case 'search':
      // 引用只并入后端攒的来源（随 done 回来），过程轨不画
      if (e.phase === 'cited') break;
      updateItems(prev => e.phase === 'searching'
        ? appendTail(deactivateProgress(prev), { kind: 'search', rid: nextRid(), query: e.query ?? null, sources: [], active: true })
        : fillSearched(deactivateProgress(prev), e.query ?? null, e.sources ?? []));
      break;
    case 'done':
      // 欠不欠补答以后端此刻的口径为准：补答轮跑完队列里可能还排着下一单，让位收尾则必然欠着
      deferredPending = e.pending === true;
      if (e.deferred) {
        // 让位收尾：答案欠着，这里只在被让位的问题后面立块牌子；补答轮由本地流收尾时的 settle() 发起。
        // 本轮流式中的条目一并收尾，不然插话那轮同名专家的结论会接到这边的专家块上。
        // 它总先于插话那轮的事件到（后端让位收尾、还了名额，插话那轮才开流），收不到插话那轮的东西
        updateItems(prev => insertDeferredNote(endStreaming(prev), e.question));
        break;
      }
      updateItems(prev => {
        // 本轮出过答案流＝还挂着一个流式中的答案气泡（排队气泡也是 user 项，不能按"最后一条提问之后"找）
        const streamed = prev.some(it => it.kind === 'assistant' && it.streaming);
        const next = settleSearches(deactivateProgress(prev)).map(it => {
          // 读数与来源随 done 一起到：本轮不用等刷新就能显示端点/耗时/token 和答案底部的来源。
          // 中断的那条要整段用服务端定稿覆盖——"（已中断）"这个尾标只在服务端拼一次，
          // 前端复刻一份的话两处措辞迟早对不上，刷新前后看到的就不是同一段文字
          if (it.kind === 'assistant' && it.streaming) {
            return {
              ...it,
              content: e.cancelled ? e.answer : (it.content || e.answer),
              streaming: false,
              meta: e.meta,
              sources: e.sources,
            };
          }
          if (it.kind === 'expert' && it.streaming) return { ...it, streaming: false };
          return it;
        });
        // 答案流没出现过（如调用上限截停）：done 里的兜底答案补成气泡，不然这轮白问
        return !streamed && e.answer
          ? appendTail(next, { kind: 'assistant', content: e.answer, streaming: false, at: Date.now(), meta: e.meta, sources: e.sources })
          : next;
      });
      break;
    case 'error':
      // 后端发完 error 就收口，不会再来 done：流式中的条目在这里收尾
      updateItems(prev => appendTail(endStreaming(prev), { kind: 'error', message: e.message }));
      break;
    default:
      // 后端日后加新事件类型时不至于静默吞掉；不上屏，排查看控制台
      console.warn('[chat] 未识别的事件', e);
  }
}

/**
 * 掐掉本地所有在收的流（换会话 / 新建对话），状态由调用方接管。
 * 不光掐 abortCtrl 那条：插话还没回音时，被占线拒掉后要接回主导权的那条也还活着，留着它会往新会话里写
 */
function abortAll() {
  liveStreams.forEach(c => c.abort());
  abortCtrl = null;
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

/**
 * 本地没有流、可这个用户有轮在跑（刷新前的那轮 / 另一个标签页 / 别的会话 / 新对话撞上占线）：
 * 轮询用户占不占线，等那轮跑完再拉一次历史补出完整答案，按 settle() 的规则续发排队消息或发起补答轮。
 * 查用户不查本会话：占线的那轮可能不在本会话，只看本会话会以为早就结束了，一遍遍重发撞 2203
 */
function startPolling() {
  stopPolling();
  // 上一跳还没问完（网慢）就跳过这一跳，免得两次一起回放
  let checking = false;
  const timer = window.setInterval(() => {
    if (checking) return;
    checking = true;
    void (async () => {
      try {
        if (await workbenchApi.busy()) return;
        const sid = state.sessionId;
        const [msgs, status] = sid
          ? await Promise.all([workbenchApi.sessionMessages(sid), workbenchApi.sessionStatus(sid)])
          : [null, null];
        // 等的这段时间开了新一轮流式对话、切了会话或新建了对话（都会先停掉轮询）：这次结果作废
        if (pollTimer !== timer) return;
        // 用户刚空下来、本会话又跑起来了（别的标签页紧接着续发/补答）：历史是半截的，等下一跳
        if (status?.running) return;
        stopPolling();
        if (status) deferredPending = status.pending;
        // 历史回放会整体重建 items：排队气泡和没填完的表单卡都不在后端历史里，得补回尾部（排队气泡压最后）
        const queued = sendQueue.filter(q => q.bubbled)
          .map(q => ({ kind: 'user' as const, content: q.text, at: Date.now(), queued: true, queuedId: q.id }));
        set({
          ...(msgs ? { items: [...toItems(msgs), ...pendingForms(), ...queued] } : {}),
          loading: false, background: false,
        });
        settle();
      } catch { /* 网络抖动下轮再试 */ } finally {
        checking = false;
      }
    })();
  }, POLL_MS);
  pollTimer = timer;
}

/**
 * 发一条消息。
 *
 * @param opts.noBubble   不上气泡：HITL 批准后自动补发的续跑指令（不是用户打的字），
 *                        以及气泡已在屏上的排队续发
 * @param opts.requeueAs  被拒时塞回队头用的原样条目（排队续发专用），不传就按新消息入队尾
 * @param opts.intent     功能按钮直发的意图：后端据此跳过专家派发，直奔对应工具
 * @param opts.noteRid    HITL 续跑指令在过程轨里那行说明的 rid：一帧没收到就被拒（不是占线）时按它撤掉
 */
async function send(message: string, opts?: { noBubble?: boolean; requeueAs?: QueuedMessage; intent?: ChatIntent; noteRid?: number }) {
  const msg = message.trim();
  if (!msg) return;
  // 有轮在跑也直接真发：后端专家等待期会让位（用户消息优先，专家结果转入补答队列）；
  // 不可让位（正在出答案）会拒 2203，届时再回落本地排队——排不排队由后端仲裁，前端不预判
  const prevAbort = abortCtrl;
  // 这条消息的身份：上屏时就发好，被拒时按它找回自己那只气泡。
  // 用下标的话，被拒之前流式往 items 里插过条目就会认错人
  const queuedId = ++queueSeq;
  // 屏上对应的气泡：新消息是这次上屏的那只，排队续发是排队时那只，无气泡的自动指令没有
  const bubbleId = opts?.requeueAs
    ? (opts.requeueAs.bubbled ? opts.requeueAs.id : null)
    : (opts?.noBubble ? null : queuedId);
  stopPolling();
  if (bubbleId != null) unopenedBubbles.add(bubbleId);
  if (!opts?.noBubble) {
    updateItems(prev => [...prev, { kind: 'user', content: msg, at: Date.now(), queuedId }]);
  }
  set({ loading: true, background: false, runId: state.runId + 1 });
  const abort = new AbortController();
  abortCtrl = abort;
  liveStreams.add(abort);
  let fellBack = false;
  const watch = watchEnd(e => {
    // 开流了：这条提问正式开跑，本轮条目接在它后面
    if (bubbleId != null) unopenedBubbles.delete(bubbleId);
    handleEvent(e);
  });
  try {
    await workbenchApi.chat(state.sessionId, msg, watch.onEvent, abort.signal, opts?.intent);
  } catch (err) {
    // 没开成流：气泡不再算在途（排队的下面会标 queued），报错行要排在它后面
    if (bubbleId != null) unopenedBubbles.delete(bubbleId);
    if (!abort.signal.aborted) {
      const code = err instanceof ApiError ? err.code : 0;
      if (code === CHAT_ERROR.ALREADY_RUNNING) {
        // 后端不让位：本条转入排队。排队续发与无气泡的自动指令都塞回队头保序，新消息入队尾；
        // 气泡标成排队中（排队续发那只在 drainQueue 里摘过标记，这里挂回去）
        if (opts?.requeueAs) {
          sendQueue.unshift(opts.requeueAs);
        } else if (opts?.noBubble) {
          sendQueue.unshift({ id: queuedId, text: msg, bubbled: false, intent: opts?.intent, noteRid: opts?.noteRid });
        } else {
          sendQueue.push({ id: queuedId, text: msg, bubbled: true, intent: opts?.intent });
        }
        if (bubbleId != null) {
          updateItems(prev => prev.map(it =>
            it.kind === 'user' && it.queuedId === bubbleId ? { ...it, queued: true } : it));
        }
        // 发出时本地就没有流（刷新后后台轮在跑 / 别的标签页在跑）：收尾时转后台轮询等那轮结束。
        // 本地那条流还活着的话主导权交回给它，见 finally
        fellBack = !prevAbort;
        return;
      }
      // 续跑指令没开成流就被拒：后端没落续跑那行，过程轨里的说明行跟着撤掉
      const noteRid = opts?.requeueAs?.noteRid ?? opts?.noteRid;
      if (noteRid != null && !watch.opened()) {
        updateItems(prev => prev.filter(it => !(it.kind === 'progress' && it.rid === noteRid)));
      }
      // e.message 来自后端/网络异常，原样显示；只有兜底那半句是自家文案，存 key 交给视图现翻
      const errMsg = (err as Error).message;
      updateItems(prev => appendTail(prev, errMsg
        ? { kind: 'error', message: errMsg }
        : { kind: 'error', message: 'err.disconnected', keyed: true }));
      // 配置类错误光显一行红字没用，用户得知道去哪儿改——置标记让面板亮"去配置"引导条
      if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
        set({ needsConfig: true });
      }
    }
  } finally {
    liveStreams.delete(abort);
    if (bubbleId != null) unopenedBubbles.delete(bubbleId);
    // 流建起来过却没等到收尾（网络断、后端 10 分钟超时收口）：后端那轮其实还在跑或已经落库
    const broken = watch.broken() && !abort.signal.aborted;
    // 亮着的流式条目收尾，不然下一轮的答案 token 会接到这条半截气泡后面
    if (broken) updateItems(endStreaming);
    // 被 openSession/newSession 主动掐掉时它们各自接管状态，这里不抢；
    // 让位成功的插话把 abortCtrl 换成了自己，被让位那条流的收尾也走不进来
    const other = [...liveStreams].pop();
    if (abortCtrl === abort && other) {
      // 本地还有别的流活着（典型是被占线拒掉时、前面那轮还在收）：主导权交给最近那条，它收尾时接着往下走。
      // 按"现在还活着"认，不按"发出时有流"认：那条可能在这期间已经收尾了，交给它就没人往下走了
      abortCtrl = other;
    } else if (abortCtrl === abort) {
      abortCtrl = null;
      if (fellBack || broken) {
        // 后端有轮在跑、本地却没有流可收：回到"后台在跑"的等待姿态，轮询等那轮结束、回放历史补齐再续发。
        // 新对话（还没有会话号）撞上占线也走这里，不能直接 settle——那会立刻重发、再撞 2203，原地打转
        set({ loading: true, background: true });
        startPolling();
      } else {
        set({ loading: false });
        settle();
      }
    }
  }
}

/**
 * 一轮在本地收尾后的下一步：排队消息先发（用户消息永远优先），一条都没有了再把欠的补答接回来。
 * 补答轮自己收尾也走这里——队列里可能还排着下一单。
 */
function settle() {
  if (sendQueue.length > 0) {
    drainQueue();
    return;
  }
  if (deferredPending && state.sessionId) void runDeferred(state.sessionId);
}

/**
 * 补答轮：让位时交出去的专家批次由这一轮接回，事件与普通轮同一套处理（标头是第一帧答案 token，
 * 专家进度照常上屏，结束后 done 照常定稿）。
 * 只由 settle() 在本地空闲时发起。后端占线（另一个标签页正跑着用户的轮）不让位、直接拒，
 * 这边转后台轮询等那轮结束再来；没欠账（被别的标签页接走 / 后端重启丢了队列）就清标记作罢。
 */
async function runDeferred(sid: string) {
  if (abortCtrl || state.sessionId !== sid) return;
  stopPolling();
  set({ loading: true, background: false, runId: state.runId + 1 });
  const abort = new AbortController();
  abortCtrl = abort;
  liveStreams.add(abort);
  let busy = false;
  const watch = watchEnd(e => {
    handleEvent(e);
    // 这一轮真出了答案（含中断的半截）：打上补答标，实时气泡与刷新后回放的补答行一个待遇
    if (e.type === 'done' && !e.deferred) updateItems(markLastAnswerDeferred);
  });
  try {
    await workbenchApi.deferred(sid, watch.onEvent, abort.signal);
  } catch (err) {
    if (!abort.signal.aborted) {
      const code = err instanceof ApiError ? err.code : 0;
      if (code === CHAT_ERROR.ALREADY_RUNNING) {
        busy = true;
      } else if (code === CHAT_ERROR.NOTHING_DEFERRED) {
        deferredPending = false;
      } else {
        // 失败的那一单已经出队（后端名额到手才出队），不会再来一遍；如实报错，用户重问即可
        deferredPending = false;
        const msg = (err as Error).message;
        updateItems(prev => appendTail(prev, msg
          ? { kind: 'error', message: msg }
          : { kind: 'error', message: 'err.disconnected', keyed: true }));
        if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
          set({ needsConfig: true });
        }
      }
    }
  } finally {
    liveStreams.delete(abort);
    // 断流同 send：后端那轮还在跑或已落库，转后台轮询等它、回放历史补齐
    const broken = watch.broken() && !abort.signal.aborted;
    if (broken) updateItems(endStreaming);
    if (abortCtrl === abort) {
      abortCtrl = null;
      if (busy || broken) {
        set({ loading: true, background: true });
        startPolling();
      } else {
        set({ loading: false });
        settle();
      }
    }
  }
}

/**
 * 重新生成最后一条回答。
 * <p>
 * 后端把模型侧上下文回退到那条提问之前、用原提问重跑，提问行留在库里不动——所以这里也只抹掉
 * 切点（见 regenCutIndex）<b>之后</b>的内容（旧答案与那一轮的工作过程），提问气泡原样留着。
 * <p>
 * 抹除放在<b>第一个事件到达之后</b>：准入被拒（2206/占线/没配模型）时一个事件都不会来，
 * 屏幕上的旧答案就该原样留着——先抹再发的话，被拒的用户会平白丢掉一条好答案。
 * <p>
 * 后端是新答案落库之后才删旧答案的。这一轮没顶掉旧答案就收尾的（报错收口；一个字没出就被停下；空产出），
 * 库里那条还在，屏上也把它换回来，跟刷新后看到的一致。
 */
async function regenerate() {
  const sid = state.sessionId;
  // background=有轮在别处跑、正靠轮询等它结束。这时候重生成会把轮询掐掉，那轮的答案再没人回放
  if (!sid || state.loading || state.background || abortCtrl) return;
  const cutAt = regenCutIndex(state.items);
  if (cutAt < 0) return;
  stopPolling();
  set({ loading: true, runId: state.runId + 1 });
  const abort = new AbortController();
  abortCtrl = abort;
  liveStreams.add(abort);
  // 抹掉的旧答案（连同那一轮的工作过程），换回来时用
  let oldOutput: ChatItem[] | null = null;
  const watch = watchEnd(e => {
    if (!oldOutput) {
      oldOutput = state.items.slice(cutAt + 1).filter(isTurnOutput);
      updateItems(prev => replaceTurnOutput(prev, cutAt));
    }
    const old = oldOutput;
    // 报错收口：后端没落新答案，旧的没删。错误行由 handleEvent 接在后面
    const failed = e.type === 'error';
    // 一个字没出就收尾，后端旧答案不删：被停下的只在旧答案后面追加一行中断说明（见 finishCancelled），
    // 中断行由 handleEvent 按"没出过答案流"补成气泡接在后面；空产出（answer 为空）什么都不落（见 finishAnswered）
    const noOutput = e.type === 'done' && !e.deferred
      && !state.items.some(it => it.kind === 'assistant' && it.streaming)
      && (e.cancelled === true || !e.answer);
    if (failed || noOutput) updateItems(prev => replaceTurnOutput(prev, cutAt, old));
    handleEvent(e);
  });
  try {
    await workbenchApi.regenerate(sid, watch.onEvent, abort.signal);
  } catch (err) {
    if (!abort.signal.aborted) {
      const code = err instanceof ApiError ? err.code : 0;
      const msg = (err as Error).message;
      updateItems(prev => appendTail(prev,
        code === CHAT_ERROR.REGENERATE_UNAVAILABLE
          ? { kind: 'error', message: 'err.regenUnavailable', keyed: true }
          : msg
            ? { kind: 'error', message: msg }
            : { kind: 'error', message: 'err.regenFailed', keyed: true },
      ));
      if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
        set({ needsConfig: true });
      }
    }
  } finally {
    liveStreams.delete(abort);
    // 断流同 send：后端那轮还在跑或已落库（新答案顶没顶掉旧的说不准），转后台轮询、回放历史为准
    const broken = watch.broken() && !abort.signal.aborted;
    if (broken) updateItems(endStreaming);
    if (abortCtrl === abort) {
      abortCtrl = null;
      if (broken) {
        set({ loading: true, background: true });
        startPolling();
      } else {
        set({ loading: false });
        settle();
      }
    }
  }
}

/**
 * 请后端在下一个检查点收尾这一轮。
 * <p>
 * <b>不 abort 本地的流</b>：收尾的 done 事件还要靠它把半截答案定稿、把读数带回来。
 * 返回 false=后端说没有轮在跑（按钮点晚了，这一轮其实已经结束）。
 */
async function cancelRun(): Promise<boolean> {
  const sid = state.sessionId;
  if (!sid || !state.loading) return false;
  try {
    return await workbenchApi.cancel(sid);
  } catch {
    return false;   // 网络抖动或那轮刚好结束，都按"点晚了"处理
  }
}

/**
 * 重新生成的切点：最后那条答案往前，碰到的第一个"一问"（见 isTurnQuestion）或上一条答案。
 * 后端只顶替库里最后那条 assistant 行。一般一问一答，切点就是提问；
 * 重新生成一个字没出就被停下时，同一问下会留两条（旧答案 + 中断行），再重新生成只换掉中断行。
 * 没有返回 -1
 */
function regenCutIndex(items: ChatItem[]): number {
  let seenAnswer = false;
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i];
    if (isTurnQuestion(it)) return i;
    if (it.kind === 'assistant') {
      if (seenAnswer) return i;
      seenAnswer = true;
    }
  }
  return -1;
}

/**
 * 一轮的产物：提问之后的答案、工作过程、确认卡、报错。
 * 提问气泡和表单卡不算——表单卡是用户自己在卡上点出来的动作，已落地的那些（已唤醒／已复盘／已留言）
 * 钱都花掉了，而卡是纯前端态、抹了刷新也回不来
 */
function isTurnOutput(it: ChatItem): boolean {
  return it.kind !== 'user' && it.kind !== 'form';
}

/**
 * 把切点（见 regenCutIndex）之后的本轮产物换成 output（不传就是抹掉），提问气泡和表单卡原地留着。
 * 切点在发起时就记死：等首个事件到达才抹，期间用户可能又发了一条（后端占线会让它排队），那条气泡留着
 */
function replaceTurnOutput(prev: ChatItem[], cutAt: number, output: ChatItem[] = []): ChatItem[] {
  return [
    ...prev.slice(0, cutAt + 1),
    ...output,
    ...prev.slice(cutAt + 1).filter(it => !isTurnOutput(it)),
  ];
}

/** 续发排队消息：气泡已上屏，去掉排队标记后不再重复上屏（失败各自报错，不阻塞后面的） */
function drainQueue() {
  const next = sendQueue.shift();
  if (next == null) return;
  // 按 id 解自己那只气泡，并挪到剩下那串排队气泡之前，它这一轮的条目才接得在它后面
  //（排队期间别的消息插话跑完了的话，它还停在插话那轮上面）。
  // 无气泡的自动指令没有 id 对应的条目，天然什么都不动
  if (next.bubbled) {
    updateItems(prev => {
      const at = prev.findIndex(it => it.kind === 'user' && it.queuedId === next.id);
      const bubble = prev[at];
      if (bubble?.kind !== 'user') return prev;
      return appendTail([...prev.slice(0, at), ...prev.slice(at + 1)], { ...bubble, queued: false });
    });
  }
  void send(next.text, { noBubble: true, requeueAs: next, intent: next.intent });
}

/** 载入会话：消息回放 + 运行状态感知（还在跑→轮询等结果；欠着补答→立刻接回；新消息照常可排队） */
async function openSession(sid: string) {
  abortAll();
  stopPolling();
  const [msgs, status] = await Promise.all([
    workbenchApi.sessionMessages(sid),
    workbenchApi.sessionStatus(sid).catch(() => ({ running: false, pending: false })),
  ]).catch((err: unknown) => {
    // 拉不到就留在原会话。它被掐掉的流、停掉的轮询没人接手了（await 期间用户又发了新的除外）：
    // 转后台轮询，等那轮跑完回放历史补齐
    if (!abortCtrl && state.loading) {
      updateItems(endStreaming);
      set({ background: true });
      startPolling();
    }
    throw err;
  });
  // await 期间用户已发起新对话流：别用旧快照覆盖在途状态
  if (abortCtrl) return;
  sendQueue = [];   // 排队的消息属于上一个会话语境，跟着带过去只会答非所问
  deferredPending = status.pending;   // 欠账归会话：以后端口径为准
  // 表单卡是 trader 动作、不属于哪个会话（跟排队消息不同），换会话也带过去，别抹掉填一半的
  const forms = pendingForms();
  setSession(sid);
  set({ items: [...toItems(msgs), ...forms], loading: status.running, background: status.running });
  if (status.running) startPolling();
  else settle();
}

/** HITL 决策：批准→登记授权→自动补发 resumeMessage 恢复执行；拒绝→仅登记。 */
async function hitlDecide(requestId: string, approved: boolean) {
  // 按 requestId 认卡而不是下标：让位说明行会 splice 到 items 中间，其后所有条目下标整体错位
  const item = state.items.find(it => it.kind === 'hitl' && it.requestId === requestId);
  if (item?.kind !== 'hitl' || !state.sessionId) return;
  await workbenchApi.approve(state.sessionId, approved, requestId);
  updateItems(prev => prev.map(it =>
    it.kind === 'hitl' && it.requestId === requestId ? { ...it, status: approved ? 'approved' : 'rejected' } : it,
  ));
  if (!approved) return;
  // 续跑指令是批准这个动作的一部分，不是用户打的字：立在工作过程轨里，
  // 用 noBubble 发出去不上气泡——否则历史里会多出一句用户从没说过的话
  const noteRid = nextRid();
  updateItems(prev => appendTail(prev, { kind: 'progress', rid: noteRid, text: HITL_RESUME_NOTE, keyed: true, active: false }));
  await send(item.resumeMessage, { noBubble: true, noteRid });
}

function newSession() {
  abortAll();
  stopPolling();
  sendQueue = [];
  deferredPending = false;
  setSession(null);
  set({ items: [], loading: false, background: false });
}

export const chatStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  },
  getSnapshot(): ChatState {
    return state;
  },
  /** ChatPanel 首挂时调：store 空且有会话号→回放历史并感知运行状态（只跑一次） */
  init() {
    if (initialized) return;
    initialized = true;
    const sid = state.sessionId;
    // 本地弹的表单卡不算"聊过了"：只数非表单项，否则先点了动作按钮就再也回放不到历史
    if (!sid || state.items.some(it => it.kind !== 'form') || state.loading) return;
    void openSession(sid).catch(() => {});
  },
  getDraft() {
    return draft;
  },
  setDraft(text: string) {
    draft = text;
  },
  send,
  regenerate,
  cancelRun,
  openSession,
  hitlDecide,
  newSession,
  /** 入口按钮直接弹卡：跟模型弹的卡走同一条 items 通路，不经后端 */
  openForm(form: TraderFormKind, prefill?: Record<string, unknown>) {
    updateItems(prev => appendTail(prev, { kind: 'form', id: nextFormId(), form, prefill, status: 'pending' }));
  },
  /** 撤掉一条还没发出去的排队消息：气泡与队列条目共用同一个 id，两边一起走 */
  cancelQueued(queuedId: number) {
    const at = sendQueue.findIndex(q => q.id === queuedId);
    if (at >= 0) sendQueue.splice(at, 1);
    updateItems(prev => prev.filter(it => !(it.kind === 'user' && it.queuedId === queuedId)));
  },
  /** 用户关掉卡：卡留在对话里当痕迹（无 result），只是不再可填 */
  closeForm(id: string) {
    updateItems(prev => prev.map(it =>
      it.kind === 'form' && it.id === id ? { ...it, status: 'done' as const } : it));
  },
  /** 卡执行完：result 是给用户看的一行回执 */
  settleForm(id: string, result: string) {
    updateItems(prev => prev.map(it =>
      it.kind === 'form' && it.id === id ? { ...it, status: 'done' as const, result } : it));
  },
  /** 删除的是当前会话时清空回到全新状态 */
  clearIfCurrent(sid: string) {
    if (state.sessionId === sid) newSession();
  },
  pushError(message: string) {
    updateItems(prev => appendTail(prev, { kind: 'error', message }));
  },
  /** 引导条点掉/点了去配置后清标记，否则一直挂着 */
  clearNeedsConfig() {
    set({ needsConfig: false });
  },
};
