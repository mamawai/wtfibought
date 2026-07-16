import { useCallback, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Bot, History, Loader2, MessageSquareText, RotateCcw, Send, ShieldQuestion, Trash2, User } from 'lucide-react';
import { workbenchApi } from '../../api';
import { cn, fmtDateTime } from '../../lib/utils';
import type { WorkbenchChatMessage, WorkbenchEvent, WorkbenchSessionSummary } from '../../types';

const SESSION_KEY = 'wiib-workbench-session';

const AGENT_CN: Record<string, string> = {
  market_agent: '市场专家',
  quant_agent: '量化专家',
  news_agent: '新闻专家',
  supervisor: '调度中枢',
};

type ChatItem =
  | { kind: 'user'; content: string }
  | { kind: 'assistant'; content: string; streaming: boolean }
  | { kind: 'agent'; node: string; agent: string }
  | { kind: 'hitl'; symbol: string; reason: string; resumeMessage: string; status: 'pending' | 'approved' | 'rejected' }
  | { kind: 'error'; message: string };

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
      item.status === 'pending' ? 'neu-raised-sm border-l-primary' : 'neu-flat border-l-muted-foreground/30 opacity-70',
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
            className="neu-btn-sm flex-1 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50"
          >
            批准执行
          </button>
          <button
            disabled={busy}
            onClick={() => onDecide(false)}
            className="neu-btn-sm flex-1 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50"
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
 * 工作台对话面板：SSE 渲染 Supervisor 多 agent 调度过程 + token 流 + HITL 确认。
 * sessionId 持久在 sessionStorage——断连/刷新后带同一 id 续聊（后端 PostgresSaver）。
 */
/** 后端历史消息 → 对话项（过程事件不落库，只回放 user/assistant） */
function toItems(messages: WorkbenchChatMessage[]): ChatItem[] {
  return messages.map(m => m.role === 'user'
    ? { kind: 'user' as const, content: m.content }
    : { kind: 'assistant' as const, content: m.content, streaming: false });
}

export function ChatPanel() {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [hitlBusy, setHitlBusy] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [sessions, setSessions] = useState<WorkbenchSessionSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const sessionRef = useRef<string | null>(sessionStorage.getItem(SESSION_KEY));
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [items]);

  useEffect(() => () => abortRef.current?.abort(), []);

  // 刷新页面后当前会话不再一片空白：有 sessionId 就把消息回放出来
  useEffect(() => {
    const sid = sessionRef.current;
    if (!sid) return;
    workbenchApi.sessionMessages(sid)
      .then(msgs => { if (msgs.length) setItems(prev => (prev.length ? prev : toItems(msgs))); })
      .catch(() => {});
  }, []);

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
    setHistoryLoading(true);
    try {
      const msgs = await workbenchApi.sessionMessages(s.sessionId);
      abortRef.current?.abort();
      sessionRef.current = s.sessionId;
      sessionStorage.setItem(SESSION_KEY, s.sessionId);
      setItems(toItems(msgs));
      setShowHistory(false);
      setLoading(false);
    } catch {
      setSessions(prev => prev); // 拉取失败保持列表
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  const send = useCallback(async (message: string, opts?: { silent?: boolean }) => {
    if (!message.trim() || loading) return;
    if (!opts?.silent) {
      setItems(prev => [...prev, { kind: 'user', content: message }]);
    }
    setLoading(true);
    const abort = new AbortController();
    abortRef.current = abort;
    try {
      await workbenchApi.chat(sessionRef.current, message, (e: WorkbenchEvent) => {
        switch (e.type) {
          case 'session':
            sessionRef.current = e.sessionId;
            sessionStorage.setItem(SESSION_KEY, e.sessionId);
            break;
          case 'agent_start':
            setItems(prev => [...prev, { kind: 'agent', node: e.node, agent: e.agent }]);
            break;
          case 'token':
            setItems(prev => {
              const next = [...prev];
              const last = next[next.length - 1];
              if (last?.kind === 'assistant' && last.streaming) {
                next[next.length - 1] = { ...last, content: last.content + e.text };
              } else {
                next.push({ kind: 'assistant', content: e.text, streaming: true });
              }
              return next;
            });
            break;
          case 'hitl_request':
            setItems(prev => [...prev, {
              kind: 'hitl', symbol: e.symbol, reason: e.reason,
              resumeMessage: e.resumeMessage, status: 'pending',
            }]);
            break;
          case 'done':
            setItems(prev => prev.map(it =>
              it.kind === 'assistant' && it.streaming
                ? { ...it, content: it.content || e.answer, streaming: false }
                : it,
            ));
            break;
          case 'error':
            setItems(prev => [...prev, { kind: 'error', message: e.message }]);
            break;
        }
      }, abort.signal);
    } catch (err) {
      if (!abort.signal.aborted) {
        setItems(prev => [...prev, { kind: 'error', message: (err as Error).message || '连接中断，可直接重问续聊' }]);
      }
    } finally {
      if (abortRef.current === abort) abortRef.current = null;
      setLoading(false);
    }
  }, [loading]);

  const handleSend = useCallback(() => {
    const msg = input.trim();
    if (!msg) return;
    setInput('');
    void send(msg);
  }, [input, send]);

  /** HITL 决策：批准→登记授权→自动补发 resumeMessage 让 agent 恢复执行；拒绝→仅登记。 */
  const handleHitl = useCallback(async (idx: number, approved: boolean) => {
    const item = items[idx];
    if (item?.kind !== 'hitl' || !sessionRef.current) return;
    setHitlBusy(true);
    try {
      await workbenchApi.approve(sessionRef.current, approved);
      setItems(prev => prev.map((it, i) =>
        i === idx && it.kind === 'hitl' ? { ...it, status: approved ? 'approved' : 'rejected' } : it,
      ));
      if (approved) {
        await send(item.resumeMessage);
      }
    } catch (err) {
      setItems(prev => [...prev, { kind: 'error', message: (err as Error).message || '确认失败，请重试' }]);
    } finally {
      setHitlBusy(false);
    }
  }, [items, send]);

  /** 删除会话：列表移除；删的是当前会话时本地一并清空，回到全新会话状态。 */
  const removeSession = useCallback(async (s: WorkbenchSessionSummary) => {
    try {
      await workbenchApi.deleteSession(s.sessionId);
      setSessions(prev => prev.filter(x => x.sessionId !== s.sessionId));
      if (sessionRef.current === s.sessionId) {
        abortRef.current?.abort();
        sessionRef.current = null;
        sessionStorage.removeItem(SESSION_KEY);
        setItems([]);
        setLoading(false);
      }
    } catch { /* 删除失败保持原样 */ }
  }, []);

  const handleNewSession = useCallback(() => {
    abortRef.current?.abort();
    sessionRef.current = null;
    sessionStorage.removeItem(SESSION_KEY);
    setItems([]);
    setLoading(false);
  }, []);

  return (
    <div className="rounded-xl neu-raised-sm flex flex-col h-[70vh] lg:h-[calc(100vh-11rem)] lg:sticky lg:top-24">
      {/* 面板头 */}
      <div className="flex items-center gap-2 px-4 py-3">
        <Bot className="w-4.5 h-4.5 text-primary" />
        <span className="text-sm font-black">研判对话</span>
        <span className="text-[10px] text-muted-foreground hidden sm:inline">Supervisor 调度 · 断连自动续聊</span>
        <button
          onClick={() => showHistory ? setShowHistory(false) : openHistory()}
          className={cn(
            'ml-auto neu-btn-sm w-7 h-7 rounded-lg flex items-center justify-center hover:text-primary',
            showHistory ? 'text-primary' : 'text-muted-foreground',
          )}
          title="历史对话"
        >
          <History className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={handleNewSession}
          className="neu-btn-sm w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
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
                  'w-full text-left rounded-xl neu-flat px-3.5 py-2.5 space-y-1 hover:bg-accent/40 transition-colors cursor-pointer',
                  s.sessionId === sessionRef.current && 'ring-1 ring-primary/40',
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
                  {s.sessionId === sessionRef.current && <span className="text-primary font-bold">当前会话</span>}
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
                  onClick={() => { setInput(''); void send(q); }}
                  className="neu-btn-sm w-full py-2 px-3 rounded-lg text-xs text-left text-muted-foreground hover:text-foreground"
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
                  <div className="max-w-[92%] rounded-2xl rounded-bl-md neu-inset px-3.5 py-2.5 text-sm">
                    <Markdown content={item.content} />
                    {item.streaming && <Loader2 className="w-3 h-3 animate-spin text-muted-foreground inline-block ml-1" />}
                  </div>
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
        {loading && items[items.length - 1]?.kind === 'user' && (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="w-3.5 h-3.5 animate-spin" /> Supervisor 分析问题中...
          </div>
        )}
      </div>

      {/* 输入区 */}
      <div className="p-3">
        <div className="flex items-center gap-2 rounded-xl neu-inset px-3 py-1.5">
          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && !e.nativeEvent.isComposing && handleSend()}
            placeholder="问市场、要研判、查战绩..."
            disabled={loading}
            className="flex-1 bg-transparent text-sm py-1.5 focus:outline-none placeholder:text-muted-foreground/60 disabled:opacity-50"
          />
          <button
            onClick={handleSend}
            disabled={loading || !input.trim()}
            className="neu-btn-sm w-8 h-8 rounded-lg flex items-center justify-center text-primary disabled:opacity-40"
            aria-label="发送"
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
          </button>
        </div>
      </div>
    </div>
  );
}
