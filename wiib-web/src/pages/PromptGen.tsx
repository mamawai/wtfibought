import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowUp, BadgeCheck, ChevronLeft, CircleHelp, Copy, FileText, Loader2, MessagesSquare, Play, RotateCcw, SkipForward, Square, Undo2 } from 'lucide-react';
import { llmEndpointApi, promptGenApi, userApi } from '../api';
import { LlmEndpointSelect } from '../components/LlmEndpointSelect';
import { Markdown } from '../components/Markdown';
import { FluidOrb } from '../components/ui/fluid-orb';
import { useToast } from '../components/ui/use-toast';
import { MAX_MESSAGE_CHARS, SEND_BTN } from '../components/workbench/ChatComposer';
import { ProcessRail, ThinkingRow } from '../components/workbench/ChatMessages';
import { PULSE_DOT, type RailStep } from '../components/workbench/chatView';
import { useIsDark } from '../hooks/useIsDark';
import { cn } from '../lib/utils';
import { SUPPORTED_LANGS, type Lang } from '../i18n';
import type { LlmEndpointView, PromptGenEvent, PromptGenItem, PromptGenQuestion, PromptGenView } from '../types';

/** 指令正文上限，与后端 TraderService.MAX_CUSTOM_PROMPT 同数 */
const MAX_CHARS = 4000;
/** 上一轮在后台跑时多久看一眼，与工作台同值 */
const POLL_MS = 3000;
/** 输入框自动长高的上限 */
const INPUT_MAX_H = 160;
/** 球的两套颜色，取 index.css 的 --color-primary 与 --color-card-2 */
const ORB_LIGHT = { color: '#f25f0a', base: '#f0f0ec' };
const ORB_DARK = { color: '#f97316', base: '#1a1b1f' };
/** 两栏同高、各自滚动：对话再长输入框也在手边 */
const PANEL = 'pt-card flex flex-col h-[calc(100dvh-12rem)] min-h-[30rem]';
/** 流式输出尾巴上的光标 */
const CURSOR = 'inline-block w-1.5 h-3.5 bg-primary/80 ml-0.5 align-middle animate-pulse motion-reduce:animate-none';
/** 单独一行的段名：【目标】或 [Goal] */
const SECTION_LINE = /^\s*(【[^】]+】|\[[A-Za-z][^\]]*\])\s*$/;

type Stage = 'thinking' | 'reading' | 'writing';
type ToolCall = { name: string; args?: Record<string, unknown>; active?: boolean };
/** 这一轮还没落库的现场：agent 一段段说的话、读的数据（中间没说话的几批并成一条，和历史里一样）、写出的版本 */
type LiveEntry = { kind: 'text'; text: string } | { kind: 'tools'; calls: ToolCall[] } | { kind: 'version'; no: number };
/** 对话里的一块：相邻的读数据并成一条过程轨 */
type Block = { kind: 'item'; item: PromptGenItem; index: number } | { kind: 'rail'; key: string; calls: ToolCall[] };
/** 对话区的一行；side=agent 的连着几行只挂一次署名，mark（版本分隔线）不打断 */
type Row = { key: string; side: 'agent' | 'user' | 'mark'; node: ReactNode };

function groupItems(items: PromptGenItem[]): Block[] {
  const out: Block[] = [];
  items.forEach((item, index) => {
    if (item.type !== 'tool') {
      out.push({ kind: 'item', item, index });
      return;
    }
    const call = { name: item.text ?? '', args: item.args ?? undefined };
    const last = out[out.length - 1];
    if (last?.kind === 'rail') last.calls.push(call);
    else out.push({ kind: 'rail', key: `rail-${index}`, calls: [call] });
  });
  return out;
}

/** 拼成工作台过程轨认的条目；rid 只当 key 用 */
function railSteps(calls: ToolCall[]): RailStep[] {
  return calls.map((c, i) => ({
    item: { kind: 'tool' as const, rid: i, id: String(i), name: c.name, args: c.args, active: !!c.active },
    index: i,
  }));
}

/** 对话末尾用户说的那句是不是 said */
function landed(view: PromptGenView, said: string): boolean {
  for (let i = view.items.length - 1; i >= 0; i--) {
    const item = view.items[i];
    if (item.type === 'user') return item.text === said;
    if (item.type === 'question' && item.answer != null) return item.answer === said;
  }
  return false;
}

/** 亮着的读数据全标成读完 */
function settleCalls(live: LiveEntry[]): LiveEntry[] {
  return live.map(x => (x.kind === 'tools' && x.calls.some(c => c.active)
    ? { ...x, calls: x.calls.map(c => ({ ...c, active: false })) }
    : x));
}

/**
 * 提示词生成：agent 先看 trader 数据，再一题一题问，信息够了写一版；用户提修改它接着问，直到它认为写完了。
 * 后端在 agent（/api/ai/prompt-gen），每轮走 SSE，轮结束重新拉一次 session；最近 3 版可以回到当时接着聊。
 * 左栏是对话（过程轨、题卡、输入框），右栏是正文；手机上两栏切着看。
 */
export function PromptGen() {
  const { t } = useTranslation(['promptGen', 'ai']);
  const { toast } = useToast();
  const orb = useIsDark() ? ORB_DARK : ORB_LIGHT;

  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  const [endpointId, setEndpointId] = useState<number | null>(null);
  const [outputLang, setOutputLang] = useState<Lang>('zh');

  const [view, setView] = useState<PromptGenView | null>(null);
  /** 点了「重新开始」：先回到开场设置，点开始才真覆盖旧对话 */
  const [restarting, setRestarting] = useState(false);
  /** 非 null = 本页正在收这一轮的流 */
  const [stage, setStage] = useState<Stage | null>(null);
  /** 上一轮在后台跑（刷新进来、或流断了）：没有流可收，轮询等它落库 */
  const [background, setBackground] = useState(false);
  /** 点了停止、这一轮还没收尾 */
  const [stopping, setStopping] = useState(false);
  const [live, setLive] = useState<LiveEntry[]>([]);
  /** agent 正在一个字一个字地说：这时候不挂「在想」那行 */
  const [speaking, setSpeaking] = useState(false);
  /** 用户刚发的话；轮结束拉回 session 后由 items 接手 */
  const [sent, setSent] = useState<string | null>(null);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  /** 状态切过 = 后面的字是下一次模型调用说的，另起一段 */
  const newSegmentRef = useRef(false);
  /** 非 null = 这一版正在流式写；'' = 写稿人还没出字 */
  const [draft, setDraft] = useState<string | null>(null);
  /** 右栏在看哪一版；null 跟着当前版 */
  const [viewing, setViewing] = useState<number | null>(null);
  /** 题卡上选中的那项 */
  const [picked, setPicked] = useState('');
  /** 过程轨的开合；没点过的都展开，和工作台一样 */
  const [railOpen, setRailOpen] = useState<Record<string, boolean>>({});
  /** 手机上一次只看一栏 */
  const [tab, setTab] = useState<'chat' | 'doc'>('chat');

  const scrollRef = useRef<HTMLDivElement>(null);
  /** 用户往回翻了就别把他拽回底部 */
  const stuckRef = useRef(true);
  const docRef = useRef<HTMLDivElement>(null);
  const docStuckRef = useRef(true);
  /** 本页在收的那条流；离开页面只是不收了，后端这一轮照样跑完落库 */
  const abortRef = useRef<AbortController | null>(null);

  const busy = stage !== null || background;
  const active = !!view?.active && !restarting;

  const apply = useCallback((v: PromptGenView) => {
    setView(v);
    setViewing(null);
    setPicked('');
  }, []);
  const refresh = useCallback(() => promptGenApi.session().then(v => {
    apply(v);
    return v;
  }), [apply]);

  useEffect(() => { llmEndpointApi.list().then(setEndpoints).catch(() => setEndpoints([])); }, []);
  useEffect(() => () => abortRef.current?.abort(), []);
  // 有对话就接着上次的；没有的话指令语言默认跟 agent 语言
  useEffect(() => {
    refresh()
      .then(v => {
        if (v.running) setBackground(true);
        if (v.active) {
          setEndpointId(v.endpointId);
          setOutputLang(v.outputLang as Lang);
        } else {
          userApi.getLang().then(setOutputLang).catch(() => { /* 拿不到就留 zh */ });
        }
      })
      .catch(err => toast((err as Error).message, 'error'));
  }, [refresh, toast]);

  // 上一轮在后台跑：隔一会儿看一眼，跑完了换上落库后的对话
  useEffect(() => {
    if (!background) return;
    const timer = window.setInterval(() => {
      promptGenApi.session()
        .then(v => {
          if (v.running) return;
          setBackground(false);
          setStopping(false);
          apply(v);
        })
        .catch(() => { /* 网络抖动下回再看 */ });
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [background, apply]);

  // 对话往下长时跟到底
  useEffect(() => {
    const el = scrollRef.current;
    if (el && stuckRef.current) el.scrollTop = el.scrollHeight;
  }, [view, live, sent, busy]);
  // 正文流式写时跟到底
  useEffect(() => {
    const el = docRef.current;
    if (el && draft && docStuckRef.current) el.scrollTop = el.scrollHeight;
  }, [draft]);

  const onEvent = useCallback((e: PromptGenEvent) => {
    switch (e.type) {
      case 'status':
        setStage(e.stage);
        // writing：超长重写时会再来一次，首稿清掉从头接；切到别的阶段 = 写稿收场了（没写成也一样），右栏回到当前版
        setDraft(e.stage === 'writing' ? '' : null);
        if (e.stage === 'writing') docStuckRef.current = true;
        setSpeaking(false);
        newSegmentRef.current = true;
        // 阶段切了 = 上一批数据读完了
        setLive(settleCalls);
        break;
      case 'text': {
        const fresh = newSegmentRef.current;
        newSegmentRef.current = false;
        setSpeaking(true);
        setLive(s => {
          const last = s[s.length - 1];
          return !fresh && last?.kind === 'text'
            ? [...s.slice(0, -1), { kind: 'text', text: last.text + e.text }]
            : [...s, { kind: 'text', text: e.text }];
        });
        break;
      }
      case 'tool_calls':
        setSpeaking(false);
        newSegmentRef.current = true;
        setLive(s => {
          const settled = settleCalls(s);
          const fresh = e.calls.map(c => ({ ...c, active: true }));
          const last = settled[settled.length - 1];
          return last?.kind === 'tools'
            ? [...settled.slice(0, -1), { kind: 'tools', calls: [...last.calls, ...fresh] }]
            : [...settled, { kind: 'tools', calls: fresh }];
        });
        break;
      case 'draft':
        setDraft(d => (d ?? '') + e.text);
        break;
      case 'version':
        // 刚写好的这版先放进列表，轮结束拉 session 再对齐
        setView(v => v && {
          ...v,
          currentNo: e.no,
          versions: [{ no: e.no, body: e.body, notes: e.notes, finalVersion: e.finalVersion }, ...v.versions].slice(0, 3),
        });
        setViewing(null);
        setDraft(null);
        // 对话里当场插一条「写出了 vN」
        setLive(s => [...settleCalls(s), { kind: 'version', no: e.no }]);
        break;
      default:
        // done / error：轮结束统一拉 session（失败原因 session 里带着）
        break;
    }
  }, []);

  /**
   * @param restore 后端没接下这一轮（一帧都没收到，比如名额被占）时把用户的输入放回去：这句没落库，
   *                跟着 refresh 清掉就丢了。接下了的话成败后端都会把它落库，轮后在对话里看得到
   */
  const run = async (call: (on: (e: PromptGenEvent) => void, signal: AbortSignal) => Promise<void>,
                     said: string | null, restore?: () => void) => {
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    stuckRef.current = true;
    setStage('thinking');
    setLive([]);
    setSpeaking(false);
    setDraft(null);
    setSent(said);
    setStartedAt(Date.now());
    let accepted = false;
    let failure: Error | null = null;
    let streamError: string | null = null;
    try {
      await call(e => {
        accepted = true;
        if (e.type === 'error') streamError = e.message;
        onEvent(e);
      }, ctrl.signal);
    } catch (err) {
      failure = err as Error;
    }
    // 页面已经离开，不用再收尾
    if (ctrl.signal.aborted) return;
    const v = await refresh().catch(() => null);
    // 流断了但这一轮还在后台跑：不算出错，接着等它
    if (v?.running) setBackground(true);
    else if (failure) toast(failure.message, 'error');
    // 失败原因对话末尾会显示；session 里没有的才弹出来
    else if (streamError && !v?.failure) toast(streamError, 'error');
    // 这句没落进对话（后端开跑先落库这句）就放回输入框
    if (!accepted && said != null && (!v || !landed(v, said))) restore?.();
    if (!v?.running) setStopping(false);
    setStage(null);
    setLive([]);
    setSpeaking(false);
    setDraft(null);
    setSent(null);
  };

  const start = () => {
    // 「跟随默认」时把默认那条的 id 显式发出去，后端只认具体端点
    const id = endpointId ?? endpoints.find(e => e.isDefault)?.id ?? endpoints[0]?.id;
    if (id == null) return;
    setRestarting(false);
    setTab('chat');
    setView(v => v && { ...v, active: true, items: [], question: null, currentNo: null, done: false });
    void run((on, signal) => promptGenApi.start({ endpointId: id, outputLang }, on, signal), null);
  };

  const reply = (body: { text?: string; skip?: boolean }, said: string, restore?: () => void) =>
    void run((on, signal) => promptGenApi.reply(body, on, signal), said, restore);

  const answer = () => {
    const text = picked;
    if (!text) return;
    reply({ text }, text, () => setPicked(text));
  };

  const say = (text: string, restore: () => void) => reply({ text }, text, restore);

  const continueFrom = (no: number) => {
    if (busy || !window.confirm(t('rollbackConfirm', { no }))) return;
    promptGenApi.rollback(no)
      .then(v => {
        apply(v);
        setRestarting(false);
        // 回到另一个对话写的版本时，指令语言跟着那一版
        setOutputLang(v.outputLang as Lang);
      })
      .catch(err => toast((err as Error).message, 'error'));
  };

  /** 叫停在跑的这一轮；后端跑到下一个检查点收尾，收尾照常走 done 或轮询 */
  const stop = () => {
    setStopping(true);
    promptGenApi.cancel()
      .then(ok => { if (!ok) setStopping(false); })
      .catch(err => {
        setStopping(false);
        toast((err as Error).message, 'error');
      });
  };

  const toggleRail = (key: string) => setRailOpen(prev => ({ ...prev, [key]: !(prev[key] ?? true) }));
  const railLabel = (count: number) => ({ live: t('railLive', { count }), done: t('railDone', { count }) });

  /** 旧的在左 */
  const versions = [...(view?.versions ?? [])].reverse();
  const shownNo = viewing ?? view?.currentNo ?? view?.versions[0]?.no ?? null;
  const shown = view?.versions.find(v => v.no === shownNo) ?? null;
  const body = draft ?? shown?.body ?? '';
  const question = active && !busy ? view?.question ?? null : null;
  const stageKey = background ? 'background'
    : stage ?? (!active ? 'idle' : view?.done ? 'done' : question ? 'asking' : 'yourTurn');
  const endpoint = endpoints.find(e => e.id === view?.endpointId);

  const copy = () => {
    navigator.clipboard.writeText(body)
      .then(() => toast(t('copied'), 'success'))
      .catch(() => toast(t('copyFailed'), 'error'));
  };

  const openVersion = (no: number) => {
    setViewing(no);
    setTab('doc');
  };

  // ===== 对话区：历史 + 这一轮的现场 + 等待行 + 题卡，排成一行行 =====
  const rows: Row[] = [];
  for (const block of groupItems(view?.items ?? [])) {
    if (block.kind === 'rail') {
      const { key, calls } = block;
      rows.push({
        key, side: 'agent', node: (
          <ProcessRail steps={railSteps(calls)} live={false} since={null}
                       open={railOpen[key] ?? true} onToggle={() => toggleRail(key)} label={railLabel(calls.length)} />
        ),
      });
      continue;
    }
    const { item, index } = block;
    switch (item.type) {
      case 'agent':
        rows.push({ key: `a${index}`, side: 'agent', node: <AgentText text={item.text ?? ''} /> });
        break;
      case 'user':
        rows.push({ key: `u${index}`, side: 'user', node: <UserBubble text={item.text ?? ''} /> });
        break;
      case 'question':
        // 还没答的那道在最下面的题卡里
        if (item.answer == null || !item.question) break;
        rows.push({ key: `q${index}`, side: 'agent', node: <AskedLine text={item.question.question} /> });
        rows.push({
          key: `qa${index}`, side: 'user', node: (
            <UserBubble text={item.answer}
                        note={item.answer === item.question.recommended ? t('pickedRecommended') : undefined} />
          ),
        });
        break;
      case 'version': {
        const no = item.no;
        // 只留最近 3 版：被挤掉的那版打不开了，照实写出来
        const kept = !!view?.versions.some(v => v.no === no);
        rows.push({
          key: `v${index}`, side: 'mark', node: (
            <VersionMark label={kept ? t('wrote', { no }) : t('wrotePruned', { no })}
                         onOpen={no != null && kept ? () => openVersion(no) : undefined} />
          ),
        });
        break;
      }
    }
  }
  if (sent) rows.push({ key: 'sent', side: 'user', node: <UserBubble text={sent} /> });
  live.forEach((entry, i) => {
    if (entry.kind === 'text') {
      rows.push({
        key: `lt${i}`, side: 'agent',
        node: <AgentText text={entry.text} streaming={speaking && i === live.length - 1} />,
      });
      return;
    }
    if (entry.kind === 'version') {
      rows.push({
        key: `lv${i}`, side: 'mark',
        node: <VersionMark label={t('wrote', { no: entry.no })} onOpen={() => openVersion(entry.no)} />,
      });
      return;
    }
    const key = `live-${startedAt}-${i}`;
    rows.push({
      key, side: 'agent', node: (
        <ProcessRail steps={railSteps(entry.calls)} live={entry.calls.some(c => c.active)} since={startedAt}
                     open={railOpen[key] ?? true} onToggle={() => toggleRail(key)} label={railLabel(entry.calls.length)} />
      ),
    });
  });
  const lastLive = live[live.length - 1];
  // 过程轨在亮、或者字正在出，都不必再说「在想」
  if (busy && !speaking && !(lastLive?.kind === 'tools' && lastLive.calls.some(c => c.active))) {
    rows.push({
      key: 'thinking', side: 'agent', node: (
        <ThinkingRow text={background ? t('background') : t(`stage.${stage ?? 'thinking'}`)}
                     since={background ? null : startedAt} />
      ),
    });
  }
  if (active && !busy && view?.failure) {
    rows.push({
      key: 'failure', side: 'agent',
      node: <p className="text-[12.5px] leading-relaxed text-loss">{t('lastFailed', { message: view.failure })}</p>,
    });
  }
  if (question) {
    rows.push({
      key: 'card', side: 'agent', node: (
        <QuestionCard question={question} picked={picked} onPick={setPicked} onSubmit={answer}
                      onSkip={() => reply({ skip: true }, t('skipped'))} />
      ),
    });
  }
  let prevSide: Row['side'] | null = null;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4 max-w-6xl">
      <div className="flex items-center gap-3">
        <Link to="/my-trader" aria-label={t('back')} className="ibtn shrink-0">
          <ChevronLeft className="w-4 h-4" />
        </Link>
        <div className="min-w-0">
          <h1 className="text-lg font-black leading-tight">{t('title')}</h1>
          {active && endpoint && (
            <p className="text-[11px] text-muted-foreground truncate">
              {endpoint.name} · {t(`lang.${view?.outputLang ?? outputLang}`)}
            </p>
          )}
        </div>
        <div className="ml-auto flex items-center gap-3 shrink-0">
          <span className="inline-flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground">
            {busy && <span className={PULSE_DOT} />}
            {t(`stage.${stageKey}`)}
          </span>
          {active && (
            <button type="button" onClick={() => setRestarting(true)} disabled={busy} className="btn xs">
              <RotateCcw className="w-3 h-3" />{t('restart')}
            </button>
          )}
        </div>
      </div>

      {/* 开场设置：没对话、或点了重新开始才出来；对话开着时端点和语言在标题下面那行 */}
      {!active && (
        <div className="pt-card p-4 space-y-3">
          <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
            <div className="space-y-1.5">
              <span className="microlabel block">{t('endpoint')}</span>
              <LlmEndpointSelect endpoints={endpoints} value={endpointId} onChange={setEndpointId}
                                 disabled={busy} className="w-full sm:min-w-[280px]" />
            </div>
            <div className="space-y-1.5">
              <span className="microlabel block">{t('outputLang')}</span>
              <div className="seg">
                {SUPPORTED_LANGS.map(l => (
                  <button key={l} type="button" disabled={busy} onClick={() => setOutputLang(l)}
                          className={cn(outputLang === l && 'on')} aria-pressed={outputLang === l}>
                    {t(`lang.${l}`)}
                  </button>
                ))}
              </div>
            </div>
            <div className="flex items-center gap-2">
              <button type="button" onClick={start} disabled={busy || endpoints.length === 0} className="btn sm orange">
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
                {t('start')}
              </button>
              {restarting && (
                <button type="button" onClick={() => setRestarting(false)} className="btn sm">
                  {t('cancelRestart')}
                </button>
              )}
            </div>
          </div>
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            {endpoints.length === 0 ? t('noEndpoint') : restarting ? t('restartHint') : t('startHint')}
          </p>
        </div>
      )}

      <div className="seg lg:hidden">
        <button type="button" onClick={() => setTab('chat')} className={cn(tab === 'chat' && 'on')} aria-pressed={tab === 'chat'}>
          {t('dialog')}
        </button>
        <button type="button" onClick={() => setTab('doc')} className={cn(tab === 'doc' && 'on')} aria-pressed={tab === 'doc'}>
          {t('body')}
          {draft != null && <span className={PULSE_DOT} />}
        </button>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* 左栏：对话 */}
        <section className={cn(PANEL, tab !== 'chat' && 'hidden lg:flex')}>
          <div ref={scrollRef}
               onScroll={e => {
                 const el = e.currentTarget;
                 stuckRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
               }}
               className="flex-1 min-h-0 overflow-y-auto px-4 py-5">
            {!active && !busy ? (
              <div className="h-full flex flex-col items-center justify-center gap-3 text-center px-6">
                <MessagesSquare className="w-7 h-7 text-primary" />
                <p className="text-[13px] leading-relaxed text-muted-foreground max-w-sm">{t('desc')}</p>
              </div>
            ) : (
              <div className="space-y-5">
                {rows.map(row => {
                  const signed = row.side === 'agent' && prevSide !== 'agent';
                  if (row.side !== 'mark') prevSide = row.side;
                  return (
                    <div key={row.key} className="space-y-2">
                      {signed && <Signature name={t('agentName')} />}
                      {row.node}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
          {active && (
            <Composer busy={busy} stopping={stopping} onSend={say} onStop={stop}
                      placeholder={question ? t('freePh') : view?.done ? t('doneHint') : t('composerPh')} />
          )}
        </section>

        {/* 右栏：版本与正文 */}
        <section className={cn(PANEL, tab !== 'doc' && 'hidden lg:flex')}>
          <div className="flex flex-wrap items-center gap-2 px-4 py-2.5 border-b border-border">
            <span className="text-xs font-black">{t('body')}</span>
            {versions.length > 0 && (
              <div className="flex items-center gap-1" title={t('keepHint')} aria-label={t('versions')}>
                {versions.map(v => (
                  <button key={v.no} type="button" onClick={() => setViewing(v.no)}
                          aria-pressed={v.no === shownNo && draft == null}
                          className={cn('btn xs num', v.no === shownNo && draft == null && 'fill')}>
                    {t('version', { no: v.no })}
                    {/* 当前对话停在这一版 */}
                    {v.no === view?.currentNo && <span className="w-1.5 h-1.5 bg-primary" />}
                  </button>
                ))}
              </div>
            )}
            <div className="ml-auto flex items-center gap-2">
              {view?.active && shown && shown.no !== view.currentNo && !busy && draft == null && (
                <button type="button" onClick={() => continueFrom(shown.no)} className="btn xs">
                  <Undo2 className="w-3 h-3" />{t('continueFrom')}
                </button>
              )}
              <button type="button" onClick={copy} disabled={!body || draft != null} className="btn xs">
                <Copy className="w-3 h-3" />{t('copy')}
              </button>
            </div>
          </div>

          <div ref={docRef}
               onScroll={e => {
                 const el = e.currentTarget;
                 docStuckRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
               }}
               className="flex-1 min-h-0 overflow-y-auto px-5 py-4">
            {draft === '' ? (
              <WritingOrb color={orb.color} base={orb.base} hint={t('orbHint')} />
            ) : body ? (
              <InstructionText text={body} streaming={draft != null} />
            ) : (
              <div className="h-full flex flex-col items-center justify-center gap-2 text-center px-6">
                <FileText className="w-6 h-6 text-muted-foreground/50" />
                <p className="text-[12px] leading-relaxed text-muted-foreground max-w-xs">{t('empty')}</p>
              </div>
            )}
          </div>

          <div className="border-t border-border px-4 py-2.5 space-y-2">
            <div className="flex items-center gap-3">
              {draft != null ? (
                <span className="inline-flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground">
                  <span className={PULSE_DOT} />{t('writingNow')}
                </span>
              ) : shown?.finalVersion && (
                <span className="chip up text-gain">
                  <BadgeCheck className="w-3 h-3" />{t('final')}
                </span>
              )}
              <span className={cn('ml-auto text-[11px] num', body.length > MAX_CHARS ? 'text-loss' : 'text-muted-foreground')}>
                {t('chars', { n: body.length, max: MAX_CHARS })}
              </span>
            </div>
            {shown?.notes && draft == null && (
              <details className="border border-border bg-card-2/50">
                <summary className="px-3 py-2 text-[11px] font-bold text-muted-foreground cursor-pointer">{t('notes')}</summary>
                <p className="max-h-40 overflow-y-auto px-3 pb-3 text-[11.5px] leading-relaxed text-muted-foreground whitespace-pre-wrap">
                  {shown.notes}
                </p>
              </details>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

/** agent 署名：橙方块 + 名字 */
function Signature({ name }: { name: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-[7px] h-[7px] bg-primary shrink-0" />
      <span className="microlabel text-foreground font-bold">{name}</span>
    </div>
  );
}

/** agent 说的话：无框正文，流式时尾巴挂光标 */
function AgentText({ text, streaming }: { text: string; streaming?: boolean }) {
  return (
    <div className="min-w-0 text-[14px] leading-relaxed">
      <Markdown content={text} />
      {streaming && <span className={CURSOR} />}
    </div>
  );
}

/** 答过的题：agent 那侧的题面 */
function AskedLine({ text }: { text: string }) {
  return (
    <p className="flex items-start gap-2 text-[14px] font-semibold leading-relaxed">
      <CircleHelp className="w-4 h-4 shrink-0 mt-[3px] text-primary" />
      <span className="min-w-0">{text}</span>
    </p>
  );
}

/** 用户的话：右侧色块气泡；note 是气泡下面一行小字 */
function UserBubble({ text, note }: { text: string; note?: string }) {
  return (
    <div className="flex flex-col items-end gap-1">
      <div className="max-w-[85%] bg-card-2 px-3.5 py-2 text-[14px] leading-relaxed whitespace-pre-wrap break-words">
        {text}
      </div>
      {note && <span className="microlabel">{note}</span>}
    </div>
  );
}

/** 写出一版：一道带字的分隔线，点了右栏看这版 */
function VersionMark({ label, onOpen }: { label: string; onOpen?: () => void }) {
  return (
    <div className="flex items-center gap-3 text-[11px] font-bold text-muted-foreground">
      <span className="h-px flex-1 bg-border" />
      {onOpen ? (
        <button type="button" onClick={onOpen} className="inline-flex items-center gap-1.5 hover:text-primary">
          <FileText className="w-3.5 h-3.5" />{label}
        </button>
      ) : (
        <span className="inline-flex items-center gap-1.5 opacity-60">
          <FileText className="w-3.5 h-3.5" />{label}
        </span>
      )}
      <span className="h-px flex-1 bg-border" />
    </div>
  );
}

/** 等回答的题卡：推荐项带理由，其余选项一行一个；自己写的从下面输入框发 */
function QuestionCard({ question, picked, onPick, onSubmit, onSkip }: {
  question: PromptGenQuestion;
  picked: string;
  onPick: (option: string) => void;
  onSubmit: () => void;
  onSkip: () => void;
}) {
  const { t } = useTranslation('promptGen');
  return (
    <div className="border border-border border-l-[3px] border-l-primary bg-card p-3.5 space-y-3 animate-in fade-in slide-in-from-bottom-2">
      <div className="flex items-start gap-2">
        <CircleHelp className="w-4 h-4 shrink-0 mt-[3px] text-primary" />
        <p className="flex-1 min-w-0 text-[14px] font-semibold leading-relaxed">{question.question}</p>
        {question.required && <span className="chip shrink-0">{t('required')}</span>}
      </div>
      <div role="radiogroup" aria-label={question.question} className="space-y-1.5">
        <OptionRow text={question.recommended} on={picked === question.recommended} onPick={onPick}
                   badge={t('recommended')} reason={question.reason} />
        {question.options.map(op => (
          <OptionRow key={op} text={op} on={picked === op} onPick={onPick} />
        ))}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[11px] text-muted-foreground">{t('typeHint')}</span>
        <div className="ml-auto flex items-center gap-2">
          {!question.required && (
            <button type="button" onClick={onSkip} className="btn xs">
              <SkipForward className="w-3 h-3" />{t('skip')}
            </button>
          )}
          <button type="button" onClick={onSubmit} disabled={!picked} className="btn xs fill">{t('submit')}</button>
        </div>
      </div>
    </div>
  );
}

/** 一个选项：方形单选点 + 文字；推荐项多一个标和一行理由 */
function OptionRow({ text, on, onPick, badge, reason }: {
  text: string;
  on: boolean;
  onPick: (option: string) => void;
  badge?: string;
  reason?: string;
}) {
  return (
    <button type="button" role="radio" aria-checked={on} onClick={() => onPick(text)}
            className={cn(
              'w-full flex items-start gap-2.5 border px-3 py-2 text-left transition-colors',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              on ? 'border-primary bg-primary/8' : 'border-border hover:bg-surface-hover',
            )}>
      <span className={cn('mt-[6px] w-2.5 h-2.5 shrink-0 border', on ? 'border-primary bg-primary' : 'border-muted-foreground/60')} />
      <span className="flex-1 min-w-0 space-y-1">
        <span className="flex items-start gap-2">
          <span className="flex-1 min-w-0 text-[13px] leading-relaxed">{text}</span>
          {badge && <span className="chip fill orange shrink-0">{badge}</span>}
        </span>
        {reason && <span className="block text-[11.5px] leading-relaxed text-muted-foreground">{reason}</span>}
      </span>
    </button>
  );
}

/** 底部输入框：多行自适应，Enter 发送、Shift+Enter 换行；跑着时可以先打字，发送键换成停止键 */
function Composer({ busy, stopping, placeholder, onSend, onStop }: {
  busy: boolean;
  stopping: boolean;
  placeholder: string;
  /** restore：后端没接下时把这句放回输入框 */
  onSend: (text: string, restore: () => void) => void;
  onStop: () => void;
}) {
  const { t } = useTranslation(['promptGen', 'ai']);
  const [text, setText] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);

  // 内容撑到哪就多高，有上限；清空、放回都跟着变
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, INPUT_MAX_H)}px`;
  }, [text]);

  const send = () => {
    const msg = text.trim();
    if (!msg || busy) return;
    setText('');
    onSend(msg, () => setText(msg));
  };

  return (
    <div className="border-t border-border p-3 shrink-0">
      <div className="border border-border bg-card-2 transition-colors focus-within:border-foreground">
        <textarea
          ref={ref}
          rows={1}
          value={text}
          maxLength={MAX_MESSAGE_CHARS}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              send();
            }
          }}
          placeholder={placeholder}
          className="block w-full bg-transparent px-3 pt-2.5 text-[14px] leading-relaxed resize-none focus:outline-none placeholder:text-muted-foreground/60"
        />
        <div className="flex items-center gap-2 px-1.5 pb-1.5 pt-1">
          <span className="pl-1.5 text-[10px] text-muted-foreground/60 truncate">{t('ai:composer.hint')}</span>
          {busy ? (
            <button type="button" onClick={onStop} disabled={stopping}
                    title={stopping ? t('ai:chat.stopping') : t('ai:chat.stopTitle')}
                    aria-label={stopping ? t('ai:chat.stopping') : t('ai:chat.stopTitle')}
                    className={SEND_BTN}>
              {stopping ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Square className="w-3 h-3 fill-current" />}
            </button>
          ) : (
            <button type="button" onClick={send} disabled={!text.trim()}
                    title={t('send')} aria-label={t('send')} className={SEND_BTN}>
              <ArrowUp className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/** 写稿人还没出字：球隔一会儿跳一下，底下影子跟着缩 */
function WritingOrb({ color, base, hint }: { color: string; base: string; hint: string }) {
  return (
    <div className="h-full min-h-[14rem] flex flex-col items-center justify-center gap-4">
      <div className="flex flex-col items-center">
        <div className="wiib-orb-hop">
          <FluidOrb size={88} color={color} base={base} />
        </div>
        <div className="wiib-orb-shade mt-1.5 h-2 w-14 rounded-[50%] bg-foreground/40 blur-[3px]" />
      </div>
      <p className="wiib-shimmer text-[12px] font-semibold">{hint}</p>
    </div>
  );
}

/** 正文按行排：单独一行的段名加粗当小标题，其余原样；复制出去的还是原文 */
function InstructionText({ text, streaming }: { text: string; streaming: boolean }) {
  const lines = text.split('\n');
  return (
    <div className="text-[13.5px] leading-[1.75] break-words">
      {lines.map((line, i) => {
        const heading = SECTION_LINE.test(line);
        return (
          <p key={i} className={cn('whitespace-pre-wrap', heading && 'font-bold', heading && i > 0 && 'mt-4')}>
            {line || ' '}
            {streaming && i === lines.length - 1 && <span className={CURSOR} />}
          </p>
        );
      })}
    </div>
  );
}
