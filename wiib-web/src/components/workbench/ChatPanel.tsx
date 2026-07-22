import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Bot, ChevronRight, History, Loader2, MessageSquareText, RotateCcw, Send, ShieldQuestion, Trash2, User } from 'lucide-react';
import { workbenchApi } from '../../api';
import { cn, fmtDateTime } from '../../lib/utils';
import { chatStore, type ChatItem } from './chatStore';
import { BeamCard } from '../fx/BeamCard';
import type { WorkbenchSessionSummary } from '../../types';

const AGENT_CN: Record<string, string> = {
  market_agent: '市场专家',
  quant_agent: '量化专家',
  news_agent: '新闻专家',
  supervisor: '调度中枢',
};

/** agent 调度切换 chip：Supervisor 把问题派给了谁，一眼可见（多 agent 编排的过程可视化）。 */
function AgentChip({ agent, node }: { agent: string; node: string }) {
  const label = AGENT_CN[agent] || AGENT_CN[node] || agent || node;
  return (
    <div className="flex items-center gap-1.5 py-0.5">
      <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
      <span className="text-[10px] font-bold text-primary">{label}</span>
      <span className="text-[10px] text-muted-foreground">接管分析</span>
    </div>
  );
}

/** HITL 确认卡：深研判 3 次深模型调用是贵操作，人工把关后 agent 才继续。 */
function HitlCard({ item, onDecide, busy }: {
  item: Extract<ChatItem, { kind: 'hitl' }>;
  onDecide: (approved: boolean) => void;
  busy: boolean;
}) {
  return (
    <div className={cn(
      'rounded-xl p-3.5 space-y-2.5 border-l-4',
      item.status === 'pending' ? 'bg-card border border-border border-l-primary' : 'border border-border border-l-muted-foreground/30 opacity-70',
    )}>
      <div className="flex items-center gap-2">
        <ShieldQuestion className="w-4 h-4 text-primary shrink-0" />
        <span className="text-xs font-black">深度研判确认</span>
        <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-primary/10 text-primary">{item.symbol}</span>
      </div>
      <p className="text-xs text-muted-foreground leading-relaxed">{item.reason}</p>
      {item.status === 'pending' ? (
        <div className="flex gap-2">
          <button
            disabled={busy}
            onClick={() => onDecide(true)}
            className="border border-border hover:bg-surface-hover flex-1 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50"
          >
            批准执行
          </button>
          <button
            disabled={busy}
            onClick={() => onDecide(false)}
            className="border border-border hover:bg-surface-hover flex-1 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50"
          >
            拒绝
          </button>
        </div>
      ) : (
        <p className="text-[10px] font-bold text-muted-foreground">
          {item.status === 'approved' ? '✓ 已批准 · 深研判继续执行' : '✗ 已拒绝 · 本次跳过深研判'}
        </p>
      )}
    </div>
  );
}

/** markdown 渲染（assistant 回答）：LLM 输出带格式，元素样式贴拟物风字号体系。 */
function Markdown({ content }: { content: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        p: (props) => <p className="leading-relaxed mb-1.5 last:mb-0" {...props} />,
        ul: (props) => <ul className="list-disc pl-4 space-y-0.5 mb-1.5" {...props} />,
        ol: (props) => <ol className="list-decimal pl-4 space-y-0.5 mb-1.5" {...props} />,
        code: (props) => <code className="px-1 py-0.5 rounded bg-muted text-[11px] font-mono" {...props} />,
        pre: (props) => <pre className="p-2.5 rounded-lg bg-muted text-[11px] font-mono overflow-x-auto mb-1.5" {...props} />,
        table: (props) => <div className="overflow-x-auto mb-1.5"><table className="text-[11px] border-collapse [&_th]:border [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:px-2 [&_td]:py-1" {...props} /></div>,
        a: (props) => <a className="text-primary underline" target="_blank" rel="noopener noreferrer" {...props} />,
      }}
    >
      {content}
    </ReactMarkdown>
  );
}

/**
 * 工作台对话面板：chatStore 的视图层（SSE 消费在 store，切页不中断）。
 * 渲染调度过程 + 专家过程(可折叠) + 进度 + token 流 + HITL 确认。
 */
export function ChatPanel() {
  const { items, loading, background, sessionId } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  const [input, setInput] = useState('');
  const [hitlBusy, setHitlBusy] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [sessions, setSessions] = useState<WorkbenchSessionSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [items]);

  // 首挂：回放历史 + 感知后台运行状态（store 级幂等；切页回来时 store 状态还在，直接续显）
  useEffect(() => { chatStore.init(); }, []);

  const openHistory = useCallback(() => {
    setShowHistory(true);
    setHistoryLoading(true);
    workbenchApi.sessions()
      .then(setSessions)
      .catch(() => setSessions([]))
      .finally(() => setHistoryLoading(false));
  }, []);

  /** 载入历史会话：消息回放 + sessionId 复用（checkpoint 在后端，继续聊自动带全上下文） */
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

  const handleSend = useCallback(() => {
    const msg = input.trim();
    if (!msg) return;
    setInput('');
    void chatStore.send(msg);
  }, [input]);

  /** HITL 决策交给 store；busy 只是本地防连点。 */
  const handleHitl = useCallback(async (idx: number, approved: boolean) => {
    setHitlBusy(true);
    try {
      await chatStore.hitlDecide(idx, approved);
    } catch (err) {
      chatStore.pushError((err as Error).message || '确认失败，请重试');
    } finally {
      setHitlBusy(false);
    }
  }, []);

  /** 删除会话：列表移除；删的是当前会话时 store 一并清空。 */
  const removeSession = useCallback(async (s: WorkbenchSessionSummary) => {
    try {
      await workbenchApi.deleteSession(s.sessionId);
      setSessions(prev => prev.filter(x => x.sessionId !== s.sessionId));
      chatStore.clearIfCurrent(s.sessionId);
    } catch { /* 删除失败保持原样 */ }
  }, []);

  const handleNewSession = useCallback(() => {
    chatStore.newSession();
  }, []);

  // 有 token 流或亮着的进度行时，气泡/进度自带 spinner，底部指示条只在"纯静默"时出现
  const streamingNow = items.some(it =>
    ((it.kind === 'assistant' || it.kind === 'expert') && it.streaming) || (it.kind === 'progress' && it.active));

  return (
    <div className="rounded-lg pt-card flex flex-col h-[70vh] lg:h-[calc(100vh-11rem)] lg:sticky lg:top-24">
      {/* 面板头 */}
      <div className="flex items-center gap-2 px-4 py-3">
        <Bot className="w-4.5 h-4.5 text-primary" />
        <span className="text-sm font-black">研判对话</span>
        <span className="text-[10px] text-muted-foreground hidden sm:inline">Supervisor 调度 · 切页/断连后台继续</span>
        <button
          onClick={() => showHistory ? setShowHistory(false) : openHistory()}
          className={cn(
            'ml-auto border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center hover:text-primary',
            showHistory ? 'text-primary' : 'text-muted-foreground',
          )}
          title="历史对话"
        >
          <History className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={handleNewSession}
          className="border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
          title="新会话"
        >
          <RotateCcw className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* 历史会话列表：点开回放消息并复用 sessionId 续聊（上下文由后端 checkpoint 自动带全） */}
      {showHistory && (
        <div className="flex-1 overflow-y-auto px-4 pb-3 space-y-2">
          {historyLoading ? (
            <div className="flex items-center justify-center gap-2 py-10 text-xs text-muted-foreground">
              <Loader2 className="w-4 h-4 animate-spin" /> 加载历史会话...
            </div>
          ) : sessions.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
              <MessageSquareText className="w-8 h-8 text-muted-foreground/40" />
              <p className="text-xs text-muted-foreground">还没有历史对话</p>
            </div>
          ) : (
            sessions.map(s => (
              <div
                key={s.sessionId}
                role="button"
                tabIndex={0}
                onClick={() => void openSession(s)}
                onKeyDown={e => e.key === 'Enter' && void openSession(s)}
                className={cn(
                  'w-full text-left rounded-lg border border-border bg-card px-3.5 py-2.5 space-y-1 hover:bg-surface-hover transition-colors cursor-pointer',
                  s.sessionId === sessionId && 'ring-1 ring-primary/40',
                )}
              >
                <div className="flex items-center gap-2">
                  <div className="text-xs font-bold truncate flex-1">{s.title}</div>
                  <button
                    onClick={e => { e.stopPropagation(); void removeSession(s); }}
                    className="shrink-0 w-6 h-6 rounded-md flex items-center justify-center text-muted-foreground/50 hover:text-loss hover:bg-loss/10 transition-colors"
                    title="删除会话"
                    aria-label="删除会话"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
                <div className="text-[10px] text-muted-foreground flex items-center gap-2">
                  <span>{fmtDateTime(s.lastAt)}</span>
                  <span>· {s.messageCount} 条</span>
                  {s.sessionId === sessionId && <span className="text-primary font-bold">当前会话</span>}
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {/* 消息流 */}
      <div ref={scrollRef} className={cn('flex-1 overflow-y-auto px-4 space-y-3 pb-3', showHistory && 'hidden')}>
        {items.length === 0 && (
          <div className="h-full flex flex-col items-center justify-center gap-3 text-center px-6">
            <Bot className="w-10 h-10 text-muted-foreground/50" />
            <p className="text-sm text-muted-foreground">问点什么——比如：</p>
            <div className="space-y-1.5 w-full max-w-xs">
              {['BTC 现在市场结构怎么样？', '你的 vol 预测战绩靠谱吗？', '对 ETH 做一次深度研判'].map(q => (
                <button
                  key={q}
                  onClick={() => { setInput(''); void chatStore.send(q); }}
                  className="border border-border hover:bg-surface-hover w-full py-2 px-3 rounded-lg text-xs text-left text-muted-foreground hover:text-foreground"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        )}
        {items.map((item, i) => {
          switch (item.kind) {
            case 'user':
              return (
                <div key={i} className="flex justify-end">
                  <div className="max-w-[85%] rounded-2xl rounded-br-md bg-primary/10 px-3.5 py-2.5 text-sm flex gap-2">
                    <span className="whitespace-pre-wrap">{item.content}</span>
                    <User className="w-3.5 h-3.5 text-primary shrink-0 mt-0.5" />
                  </div>
                </div>
              );
            case 'assistant':
              return (
                <div key={i} className="flex">
                  <div className="max-w-[92%] rounded-2xl rounded-bl-md border border-border bg-card-2 px-3.5 py-2.5 text-sm">
                    <Markdown content={item.content} />
                    {item.streaming && <Loader2 className="w-3 h-3 animate-spin text-muted-foreground inline-block ml-1" />}
                  </div>
                </div>
              );
            case 'expert':
              return (
                <div key={i} className="max-w-[92%]">
                  <button
                    onClick={() => chatStore.toggleExpert(i)}
                    className="flex items-center gap-1 text-[10px] font-bold text-muted-foreground hover:text-foreground py-0.5"
                  >
                    <ChevronRight className={cn('w-3 h-3 transition-transform', !item.collapsed && 'rotate-90')} />
                    {AGENT_CN[item.agent] || item.agent} · 工作过程
                    {item.streaming && <Loader2 className="w-3 h-3 animate-spin ml-1" />}
                  </button>
                  {!item.collapsed && (
                    <div className="rounded-lg border border-border bg-card px-3.5 py-2.5 text-xs text-muted-foreground">
                      <Markdown content={item.content} />
                    </div>
                  )}
                </div>
              );
            case 'progress':
              // 活跃条目上边框巡游光：一眼看出"深研判正在跑"
              return item.active ? (
                <BeamCard key={i} className="my-1">
                  <div className="flex items-center gap-1.5 py-1.5 px-2.5">
                    <Loader2 className="w-3 h-3 animate-spin text-primary shrink-0" />
                    <span className="text-[10px] font-bold text-primary">{item.text}</span>
                  </div>
                </BeamCard>
              ) : (
                <div key={i} className="flex items-center gap-1.5 py-0.5 pl-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/40 shrink-0" />
                  <span className="text-[10px] font-bold text-muted-foreground">{item.text}</span>
                </div>
              );
            case 'agent':
              return <AgentChip key={i} agent={item.agent} node={item.node} />;
            case 'hitl':
              return <HitlCard key={i} item={item} busy={hitlBusy} onDecide={a => void handleHitl(i, a)} />;
            case 'error':
              return (
                <p key={i} className="text-[11px] text-destructive/80 text-center py-1">{item.message}</p>
              );
          }
        })}
        {loading && !streamingNow && (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
            {background ? 'AI 在后台继续研判中，完成后自动展示答案' : 'Supervisor 分析问题中...'}
          </div>
        )}
      </div>

      {/* 输入区 */}
      <div className="p-3">
        <div className="flex items-center gap-2 rounded-lg border border-border bg-card-2 px-3 py-1.5">
          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && !e.nativeEvent.isComposing && handleSend()}
            placeholder={loading ? 'AI 工作中，稍候…' : '问市场、要研判、查战绩...'}
            disabled={loading}
            className="flex-1 bg-transparent text-sm py-1.5 focus:outline-none placeholder:text-muted-foreground/60 disabled:opacity-50"
          />
          <button
            onClick={handleSend}
            disabled={loading || !input.trim()}
            className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-primary disabled:opacity-40"
            aria-label="发送"
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
          </button>
        </div>
      </div>
    </div>
  );
}
