import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDown, ChevronsDownUp, ChevronsUpDown, History, KeyRound, Loader2, Maximize2, Minimize2, PanelLeftClose, PanelLeftOpen, SquarePen, X } from 'lucide-react';
import { workbenchApi } from '../../api';
import { cn } from '../../lib/utils';
import { useToast } from '../ui/use-toast';
import { chatStore, isTurnQuestion } from './chatStore';
import { AssistantAnswer, HitlCard, ProcessRail, UserBubble } from './ChatMessages';
import { groupBlocks, HUB_NAME } from './chatView';
import { ChatComposer } from './ChatComposer';
import { SessionHistory } from './SessionHistory';
import { TraderFormCard } from './TraderFormCards';
import { BehaviorReportCard } from './BehaviorReportCard';
import type { ChatIntent, WorkbenchSessionSummary } from '../../types';

/** 会话标题截断长度：与后端 ChatHistoryService.TITLE_MAX 同口径，历史列表与面板头对得上 */
const TITLE_MAX = 40;

/** 贴底判定的容差：小于它就算"用户在看最新内容"，新内容照常跟随滚动 */
const STICK_PX = 80;

/** 顶栏图标键：无边框，悬停才有底色 */
const HEAD_BTN = 'shrink-0 w-8 h-8 flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors';

interface ChatPanelProps {
  /** 停靠壳（ChatDock）传入：点头部 X 关面板 */
  onClose?: () => void;
  /** 后端报配置缺失/不可用时，引导条按钮跳模型配置页 */
  onGoConfig?: () => void;
  /** PC 全屏开关（铺满浏览器视口，不吃地址栏）。不传就不出这个按钮——移动端面板本来就是铺满视口的全屏层 */
  fullscreen?: boolean;
  onToggleFullscreen?: () => void;
}

/**
 * 对话面板：chatStore 的视图层（SSE 消费在 store，关面板/切页不中断）。
 * 自身不带卡片外壳，由 ChatDock 决定浮窗还是全屏。
 * <p>
 * 版式：轻量顶栏（品牌字 + 会话标题 + 图标键）、居中限宽的消息列（用户提问是右侧底色气泡，
 * agent 回答是<b>无框正文</b>：署名行 + 正文 + 一排小动作），过程条目收进一行摘要可展开的工作过程轨。
 * 全屏时左边多一条常驻会话栏，浮窗/手机上它是右滑叠层。
 */
export function ChatPanel({ onClose, onGoConfig, fullscreen, onToggleFullscreen }: ChatPanelProps) {
  const { t } = useTranslation(['ai', 'common']);
  const { toast } = useToast();
  const { items, loading, background, sessionId, needsConfig, runId } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  // 在途的那张确认卡（按 requestId 认）。面板里可能同时挂着几张，用一个布尔会把别的卡一起禁掉
  const [hitlBusy, setHitlBusy] = useState<{ requestId: string; approved: boolean } | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  // 全屏左栏的开合。跟 showHistory 各记各的：退出全屏时叠层不该跟着弹出来，反过来也一样
  const [sideOpen, setSideOpen] = useState(true);
  const [sessions, setSessions] = useState<WorkbenchSessionSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  // 工作过程轨的手动开合，键是轨首条目的 rid（条目挪位置也认得回来）。默认展开：轨里全是本次
  // 会话实时产生的条目（专家过程不落库），用户正看着专家分析时把它收起来是最恼人的一种"自作主张"
  const [railClosed, setRailClosed] = useState<Record<string, boolean>>({});
  const scrollRef = useRef<HTMLDivElement>(null);
  // 用户往回翻时不再强行拉到底：长回答无框铺开后，往回看是常态
  const [stuckToBottom, setStuckToBottom] = useState(true);
  // 点了停止的是哪一轮（store 的 runId）。那轮收尾 loading 落下、或下一轮开跑 runId 变了，就不再算"收尾中"。
  // 不能靠 loading 落下来复位：排队续发、补答紧接着上一轮开跑，loading 真→假→真在同一个同步链里，React 只渲染到最终值
  const [stoppingRun, setStoppingRun] = useState<number | null>(null);
  const stopping = loading && stoppingRun === runId;

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (el) setStuckToBottom(el.scrollHeight - el.scrollTop - el.clientHeight < STICK_PX);
  }, []);

  const scrollToBottom = useCallback(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setStuckToBottom(true);
  }, []);

  useEffect(() => {
    if (stuckToBottom) scrollToBottom();
  }, [items, stuckToBottom, scrollToBottom]);

  // 首挂：回放历史 + 感知后台运行状态（store 级幂等；关面板再开时 store 状态还在，直接续显）
  useEffect(() => { chatStore.init(); }, []);

  // 换会话后旧轨全不在了，攒着的开合状态没有对应对象；滚动位置也该回到最新一条
  useEffect(() => { setRailClosed({}); setStuckToBottom(true); }, [sessionId]);

  const blocks = useMemo(() => groupBlocks(items), [items]);

  /** 顶栏显示当前会话标题：首条提问截断，与后端历史列表同口径 */
  const title = useMemo(() => {
    const first = items.find(it => it.kind === 'user');
    if (!first || first.kind !== 'user') return null;
    const text = first.content.replace(/\s+/g, ' ').trim();
    return text.length > TITLE_MAX ? `${text.slice(0, TITLE_MAX)}…` : text;
  }, [items]);

  const railKeys = useMemo(
    () => blocks.filter(b => b.kind === 'rail').map(b => b.key),
    [blocks],
  );
  const anyRailOpen = railKeys.some(k => !railClosed[k]);
  const toggleAllRails = useCallback(() => {
    setRailClosed(Object.fromEntries(railKeys.map(k => [k, anyRailOpen])));
  }, [railKeys, anyRailOpen]);

  /** 只有会话最后一条答案能重新生成：后端回退上下文只回得到末尾那一轮 */
  const lastAnswerIndex = useMemo(() => {
    for (let i = items.length - 1; i >= 0; i--) {
      if (items[i].kind === 'assistant') return i;
      if (isTurnQuestion(items[i])) break;   // 提问（含续跑指令）之后还没有答案，这一轮没得重生成
    }
    return -1;
  }, [items]);

  const loadSessions = useCallback(() => {
    setHistoryLoading(true);
    workbenchApi.sessions()
      .then(setSessions)
      .catch(() => setSessions([]))
      .finally(() => setHistoryLoading(false));
  }, []);

  const openHistory = useCallback(() => {
    setShowHistory(true);
    loadSessions();
  }, [loadSessions]);

  // 全屏的左栏是常驻的，进全屏就得先把列表备好；叠层那条路"点开才拉"的时机搬过来的话，
  // 左栏会一直空着，直到用户想起来去点那个开关
  useEffect(() => { if (fullscreen) loadSessions(); }, [fullscreen, loadSessions]);

  /** 载入历史会话：消息回放 + sessionId 复用（续聊上下文在后端，继续聊自动带全上下文） */
  const openSession = useCallback(async (s: WorkbenchSessionSummary) => {
    // 点的就是当前在跑的会话：直接关列表回对话，别把在途流掐了重载
    if (s.sessionId === sessionId && loading) {
      setShowHistory(false);
      return;
    }
    setHistoryLoading(true);
    try {
      await chatStore.openSession(s.sessionId);
      setShowHistory(false);
    } catch { /* 拉取失败保持列表 */ } finally {
      setHistoryLoading(false);
    }
  }, [sessionId, loading]);

  const handleSend = useCallback((msg: string, intent?: ChatIntent) => {
    scrollToBottom();   // 自己刚发的话总要看见
    void chatStore.send(msg, { intent });
  }, [scrollToBottom]);

  const handleRegenerate = useCallback(() => {
    scrollToBottom();
    void chatStore.regenerate();
  }, [scrollToBottom]);

  /** 停止：后端跑到下一个检查点才收尾，所以按钮先进"收尾中"；后端说没轮在跑就恢复原状 */
  const handleStop = useCallback(() => {
    const run = runId;
    setStoppingRun(run);
    void chatStore.cancelRun().then(running => {
      if (!running) setStoppingRun(cur => (cur === run ? null : cur));
    });
  }, [runId]);

  /** HITL 决策交给 store；本地只记"哪张卡在提交"用来防连点+出转圈。按 requestId 认卡，条目挪位置也不会打偏。 */
  const handleHitl = useCallback(async (requestId: string, approved: boolean) => {
    setHitlBusy({ requestId, approved });
    try {
      await chatStore.hitlDecide(requestId, approved);
    } catch (err) {
      chatStore.pushError((err as Error).message || t('chat.hitlFailed'));
    } finally {
      // 只收自己那张：同时挂两张卡时，先点那张收尾会把后点那张的转圈一起清掉
      setHitlBusy(cur => (cur?.requestId === requestId ? null : cur));
    }
  }, [t]);

  /** 删除会话：列表移除；删的是当前会话时 store 一并清空。删了不可恢复，先问一句（站内破坏性操作的既有写法） */
  const removeSession = useCallback(async (s: WorkbenchSessionSummary) => {
    if (!window.confirm(t('history.deleteConfirm', { title: s.title, count: s.messageCount }))) return;
    try {
      await workbenchApi.deleteSession(s.sessionId);
      setSessions(prev => prev.filter(x => x.sessionId !== s.sessionId));
      chatStore.clearIfCurrent(s.sessionId);
    } catch (err) {
      // 后端拒删（会话还在跑/欠补答）带的是成句的拒因，直接给用户看；列表保持原样
      toast((err as Error).message || t('common:loadFailed'), 'error');
    }
  }, [t, toast]);

  /** 清空全部：后端会跳过在跑/欠补答的会话（skipped），所以删完得按剩下的列表重来一遍 */
  const clearAllSessions = useCallback(async () => {
    if (!window.confirm(t('history.clearAllConfirm'))) return;
    setHistoryLoading(true);
    try {
      const { skipped } = await workbenchApi.deleteAllSessions();
      const list = await workbenchApi.sessions();
      setSessions(list);
      // 当前会话被清掉了就回到全新状态：留在一个后端已经没有的会话号上，接着聊会答非所问
      if (sessionId && !list.some(s => s.sessionId === sessionId)) chatStore.newSession();
      if (skipped > 0) toast(t('history.clearedSkipped', { count: skipped }), 'info');
    } catch (err) {
      toast((err as Error).message || t('common:loadFailed'), 'error');
    } finally {
      setHistoryLoading(false);
    }
  }, [sessionId, t, toast]);

  const handleNewSession = useCallback(() => {
    chatStore.newSession();
    setShowHistory(false);
  }, []);

  // 有 token 流或亮着的进度行时，答案卡/过程轨自带动效，底部指示条只在"纯静默"时出现
  const streamingNow = items.some(it =>
    ((it.kind === 'assistant' || it.kind === 'expert') && it.streaming) || (it.kind === 'progress' && it.active));

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* 顶栏：品牌字 + 会话标题 + 一排图标键 */}
      <div className="flex items-center gap-2 px-2 py-1.5 border-b border-border shrink-0">
        <span className="pl-1.5 text-sm font-black shrink-0">{HUB_NAME}</span>
        {title && <span className="flex-1 min-w-0 truncate text-xs text-muted-foreground">{title}</span>}
        {/* 按钮组自己带 ml-auto 把自己顶到右边：没有会话标题时它左边没有能撑开的东西 */}
        <div className="ml-auto flex items-center shrink-0">
          {railKeys.length > 0 && (
            <button
              onClick={toggleAllRails}
              className={HEAD_BTN}
              title={anyRailOpen ? t('chat.collapseAllRails') : t('chat.expandAllRails')}
              aria-label={t('chat.toggleAllRails')}
            >
              {anyRailOpen ? <ChevronsDownUp className="w-4 h-4" /> : <ChevronsUpDown className="w-4 h-4" />}
            </button>
          )}
          {/* 同一个键在两种形态下管两件事：全屏时开合左栏，浮窗/手机时开合右滑叠层。
              对用户都是"看历史对话"，位置不变最省事 */}
          <button
            onClick={() => fullscreen ? setSideOpen(v => !v) : (showHistory ? setShowHistory(false) : openHistory())}
            className={cn(HEAD_BTN, (fullscreen ? sideOpen : showHistory) && 'text-foreground')}
            title={fullscreen ? (sideOpen ? t('chat.hideHistory') : t('chat.showHistory')) : t('history.title')}
          >
            {fullscreen
              ? (sideOpen ? <PanelLeftClose className="w-4 h-4" /> : <PanelLeftOpen className="w-4 h-4" />)
              : <History className="w-4 h-4" />}
          </button>
          <button onClick={handleNewSession} className={HEAD_BTN} title={t('chat.newSession')}>
            <SquarePen className="w-4 h-4" />
          </button>
          {/* 全屏只在 PC 出：移动端面板本来就铺满视口 */}
          {onToggleFullscreen && (
            <button
              onClick={onToggleFullscreen}
              className={cn(HEAD_BTN, 'hidden md:flex')}
              title={fullscreen ? t('chat.exitFullscreen') : t('chat.fullscreen')}
              aria-label={fullscreen ? t('chat.exitFullscreenAria') : t('chat.fullscreenAria')}
            >
              {fullscreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
            </button>
          )}
          {onClose && (
            <button onClick={onClose} className={HEAD_BTN} title={t('common:close')} aria-label={t('chat.closeAria')}>
              <X className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {/* 内容区：全屏时左边是常驻历史栏 + 右边对话；浮窗/手机没有左栏，历史是盖在对话上的右滑叠层 */}
      <div className="relative flex-1 min-h-0 flex">
        {/* 左栏开合做成一层宽度过渡；里面那份 SessionHistory 自己钉死 w-64（原因见它那边的注释） */}
        {fullscreen && (
          <div className={cn(
            'shrink-0 overflow-hidden transition-[width] duration-300 ease-[cubic-bezier(.16,1,.3,1)]',
            sideOpen ? 'w-64' : 'w-0',
          )}>
            <SessionHistory
              sidebar
              open
              loading={historyLoading}
              sessions={sessions}
              currentId={sessionId}
              onBack={() => setSideOpen(false)}
              onOpen={s => void openSession(s)}
              onRemove={s => void removeSession(s)}
              onNew={handleNewSession}
              onClearAll={() => void clearAllSessions()}
            />
          </div>
        )}

        {/* 右边：对话本体（消息流 + 输入区）。全屏时它跟左栏并排，其余形态下它就是整个内容区 */}
        <div className="relative flex-1 min-w-0 flex flex-col">
          {/* 消息流自带一层定位上下文：回到底部要贴消息流的下沿，
              挂在外层的话 bottom 量的是输入区底边，浮标会压在"Enter 发送"那行上 */}
          <div className="relative flex-1 min-h-0 flex flex-col">
            {/* 全屏后每个条目限宽居中：铺满整屏的正文一行能拉到一千多像素，读长回答很累。
                限在子元素上而不是套一层容器——空态那块靠 h-full 撑满，中间多一层它就撑不起来了。
                上下内边距只在有条目时给，空态 h-full 加内边距会多出一截可滚 */}
            <div ref={scrollRef} onScroll={onScroll} className={cn(
              'flex-1 overflow-y-auto px-4',
              items.length > 0 && 'py-6 space-y-6',
              fullscreen && '[&>*]:mx-auto [&>*]:w-full [&>*]:max-w-3xl',
            )}>
              {items.length === 0 && (
                <div className="h-full flex flex-col items-center justify-center gap-2 text-center px-6">
                  <h2 className="text-2xl font-black tracking-tight">{t('chat.emptyTitle')}</h2>
                  <p className="text-xs text-muted-foreground">{t('chat.emptyHint', { hub: HUB_NAME })}</p>
                </div>
              )}
              {blocks.map(block => {
                if (block.kind === 'rail') {
                  const active = block.steps.some(({ item }) =>
                    (item.kind === 'expert' && item.streaming) || (item.kind === 'progress' && item.active)
                    || (item.kind === 'search' && item.active));
                  return (
                    <ProcessRail
                      key={block.key}
                      steps={block.steps}
                      active={active}
                      open={!railClosed[block.key]}
                      onToggle={() => setRailClosed(prev => ({ ...prev, [block.key]: !prev[block.key] }))}
                    />
                  );
                }
                const { item, index } = block;
                switch (item.kind) {
                  case 'user':
                    return (
                      <UserBubble
                        key={index}
                        item={item}
                        onCancelQueued={item.queued && item.queuedId != null
                          ? () => chatStore.cancelQueued(item.queuedId as number) : undefined}
                      />
                    );
                  case 'assistant':
                    return (
                      <AssistantAnswer
                        key={index}
                        item={item}
                        // background=让位后欠着补答、正靠轮询等它落库，这时候重生成会把轮询掐掉
                        canRegenerate={index === lastAnswerIndex && !loading && !background}
                        onRegenerate={handleRegenerate}
                      />
                    );
                  case 'hitl':
                    return (
                      <HitlCard
                        key={item.requestId}
                        item={item}
                        submitting={hitlBusy?.requestId === item.requestId
                          ? (hitlBusy.approved ? 'approve' : 'reject') : null}
                        onDecide={a => void handleHitl(item.requestId, a)}
                      />
                    );
                  case 'form':
                    // key 用卡自身的 id：草稿在卡的组件 state 里，按下标做 key 时列表中间插条目
                    // 会让 React 拿错元素配对、把正在敲的留言卸载掉
                    return (
                      <TraderFormCard
                        key={item.id}
                        form={item.form}
                        prefill={item.prefill}
                        status={item.status}
                        result={item.result}
                        onSettle={r => chatStore.settleForm(item.id, r)}
                        onCancel={() => chatStore.closeForm(item.id)}
                      />
                    );
                  case 'behavior':
                    return <BehaviorReportCard key={index} report={item.report} />;
                  case 'error':
                    // keyed=前端自己的兜底文案（存的是 key），后端/异常带回来的 message 原样显示
                    return (
                      <p key={index} className="text-[11px] text-destructive/80 text-center py-1">
                        {item.keyed ? t(item.message) : item.message}
                      </p>
                    );
                }
              })}
              {/* 停止键在输入区，这里只报一句"还在跑"；有 token 流时答案自带光标，不必再说 */}
              {loading && !streamingNow && (
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  {background ? t('chat.background', { hub: HUB_NAME }) : t('chat.thinking', { hub: HUB_NAME })}
                </div>
              )}
            </div>

            {/* 回到底部：只在用户往回翻之后出现，贴在消息流下沿 */}
            {!stuckToBottom && (
              <button
                onClick={scrollToBottom}
                className="absolute left-1/2 -translate-x-1/2 bottom-2 z-[5] flex items-center gap-1 pt-card shadow-lg px-2.5 py-1 text-[10px] font-bold text-muted-foreground hover:text-primary animate-in fade-in"
              >
                <ArrowDown className="w-3 h-3" /> {t('chat.toBottom')}
              </button>
            )}
          </div>

          {/* 配置引导条：后端报 2201/2202 时出现——光一行红字用户不知道去哪儿改（配置在 AI 页模型配置 Tab） */}
          {needsConfig && (
            <div className={cn(
              'mx-3 mb-2 border border-warning/40 bg-warning/10 px-3 py-2 flex items-center gap-2 shrink-0',
              fullscreen && 'w-full max-w-3xl mx-auto',
            )}>
              <KeyRound className="w-3.5 h-3.5 text-warning shrink-0" />
              <span className="text-[11px] font-bold flex-1 text-left">{t('chat.needsConfig')}</span>
              <button
                onClick={() => { chatStore.clearNeedsConfig(); onGoConfig?.(); }}
                className="text-[11px] font-bold text-primary shrink-0 hover:underline"
              >
                {t('chat.goConfig')}
              </button>
              <button
                onClick={() => chatStore.clearNeedsConfig()}
                aria-label={t('chat.dismiss')}
                className="text-muted-foreground/60 hover:text-foreground shrink-0"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          )}

          <ChatComposer
            loading={loading}
            onSend={handleSend}
            fullscreen={fullscreen}
            empty={items.length === 0}
            stopping={stopping}
            // 后台轮询态没有可中断的本地轮（补答跑在后台），这时候不给停止键，发送键照常
            onStop={background ? undefined : handleStop}
          />

          {/* 浮窗 / 手机：历史还是盖在对话上的右滑叠层。全屏那份在左边常驻，这里不重复挂 */}
          {!fullscreen && (
            <SessionHistory
              open={showHistory}
              loading={historyLoading}
              sessions={sessions}
              currentId={sessionId}
              onBack={() => setShowHistory(false)}
              onOpen={s => void openSession(s)}
              onRemove={s => void removeSession(s)}
              onNew={handleNewSession}
              onClearAll={() => void clearAllSessions()}
            />
          )}
        </div>
      </div>
    </div>
  );
}
