import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowUp, Check, ChevronLeft, Copy, RotateCcw, Square } from 'lucide-react';
import { llmEndpointApi, promptGenApi, traderApi, userApi } from '../api';
import { LlmEndpointSelect } from '../components/LlmEndpointSelect';
import type { PopChunk } from '../components/Markdown';
import { StreamText } from '../components/StreamText';
import { SureButton } from '../components/SureButton';
import { Seg } from '../components/ui/seg';
import { useToast } from '../components/ui/use-toast';
import { MAX_MESSAGE_CHARS } from '../components/workbench/ChatComposer';
import { ProcessRail, ThinkingRow } from '../components/workbench/ChatMessages';
import type { RailStep } from '../components/workbench/chatView';
import { popText, useStreamReveal } from '../hooks/useStreamReveal';
import { cn } from '../lib/utils';
import { SUPPORTED_LANGS, type Lang } from '../i18n';
import type { LlmEndpointView, PromptGenEvent, PromptGenItem, PromptGenQuestion, PromptGenView, TraderOwnerView } from '../types';
import './PromptGen.css';

/** 指令正文上限，与后端 TraderService.MAX_CUSTOM_PROMPT 同数 */
const MAX_CHARS = 4000;
/** 上一轮在后台跑时多久看一眼，与工作台同值 */
const POLL_MS = 3000;
/** 输入框自动长高的上限 */
const INPUT_MAX_H = 160;
/** 单独一行的段名：【目标】或 [Goal] */
const SECTION_LINE = /^\s*(【[^】]+】|\[[A-Za-z][^\]]*\])\s*$/;

type Stage = 'thinking' | 'reading' | 'writing';
type ToolCall = { name: string; args?: Record<string, unknown>; active?: boolean };
/** 这一轮还没落库的现场：agent 一段段说的话、读的数据（中间没说话的几批并成一条，和历史里一样）、写出的版本 */
type LiveEntry = { kind: 'text'; text: string } | { kind: 'tools'; calls: ToolCall[] } | { kind: 'version'; no: number };
/** 对话里的一块：相邻的读数据并成一条过程轨 */
type Block = { kind: 'item'; item: PromptGenItem; index: number } | { kind: 'rail'; key: string; calls: ToolCall[] };
/** 对话区的一行；side=agent 的连着几行只挂一次署名，mark（写出一版）不打断 */
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
 * 提示词生成（样式见 PromptGen.css）：agent 先看 trader 数据，再一题一题问，信息够了写一版；用户提修改它接着问，直到它认为写完了。
 * 后端在 agent（/api/ai/prompt-gen），每轮走 SSE，轮结束重新拉一次 session；最近 3 版可以回到当时接着聊。
 * 左栏是对话（过程轨、题卡、输入框），右栏是正文；窄屏两栏切着看。
 * <p>
 * 为哪个 trader 写看地址里的 ?trader（我的 Trader 页带过来，新建页不带）：只在开新对话时用。
 * 现有对话是为别的 trader 写的照常接着聊，页头说明是为谁写的，点「重新开始」才按地址里这个开。
 */
export function PromptGen() {
  const { t } = useTranslation(['promptGen', 'ai']);
  const { toast } = useToast();
  const [searchParams] = useSearchParams();
  /** 地址里带来的 trader id；不带、乱填都是 null */
  const wanted = Number(searchParams.get('trader')) || null;
  /** 名下的 trader，用来认出地址里那个叫什么；null=还在取 */
  const [mine, setMine] = useState<TraderOwnerView[] | null>(null);

  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  const [endpointId, setEndpointId] = useState<number | null>(null);
  const [outputLang, setOutputLang] = useState<Lang>('zh');

  const [view, setView] = useState<PromptGenView | null>(null);
  /** 点了「重新开始」：对话底下出开场设置，点开始才真覆盖旧对话 */
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
  /** 窄屏上一次只看一栏 */
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
  /** 地址里那个 trader，在名下才算；开新对话就为它写 */
  const target = mine?.find(x => x.pub.id === wanted) ?? null;
  // 现有对话不是为地址里那个写的：照常显示，页头说一声点「重新开始」会换
  const switching = active && mine != null && (target?.pub.id ?? null) !== view?.traderId;

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
  useEffect(() => { traderApi.mine().then(setMine).catch(() => setMine([])); }, []);
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

  // 对话往下长时跟到底：盯着每一行的高度，agent 说的话是 StreamText 自己一段段放出来的，不经过这里重渲染
  useEffect(() => {
    const el = scrollRef.current!;
    const ro = new ResizeObserver(() => { if (stuckRef.current) el.scrollTop = el.scrollHeight; });
    for (const child of el.children) ro.observe(child);
    return () => ro.disconnect();
  }, [view, live, sent, busy, restarting]);
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
    setView(v => v && {
      ...v, active: true, traderId: target?.pub.id ?? null, traderName: target?.pub.name ?? null,
      items: [], question: null, currentNo: null, done: false,
    });
    void run((on, signal) => promptGenApi.start({ endpointId: id, traderId: wanted, outputLang }, on, signal), null);
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
  // 正文流式写时字是攒够一段整段弹出来的；每放出一段跟到底
  const doc = useStreamReveal(docRef, body, draft != null);
  useEffect(() => {
    const el = docRef.current;
    if (el && draft && docStuckRef.current) el.scrollTop = el.scrollHeight;
  }, [draft, doc.text]);
  const question = active && !busy ? view?.question ?? null : null;
  const stageKey = background ? 'background'
    : stage ?? (!active ? 'idle' : view?.done ? 'done' : question ? 'asking' : 'yourTurn');
  const endpoint = endpoints.find(e => e.id === view?.endpointId);
  // 返回：有对话回它为之写的那个，还没开对话回地址里那个
  const backId = view?.active ? view.traderId : target?.pub.id ?? null;
  // 看的不是当前对话停着的那版：可以回到那版接着聊
  const canContinue = !!view?.active && !!shown && shown.no !== view.currentNo && !busy && draft == null;

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
        rows.push({ key: `q${index}`, side: 'agent', node: <div className="md"><p>{item.question.question}</p></div> });
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
    rows.push({ key: 'failure', side: 'agent', node: <p className="err">{t('lastFailed', { message: view.failure })}</p> });
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

  // 开场设置：没对话时占满左栏；点了重新开始挂在对话底下
  const setup = (
    <div className="blk setup">
      <div className="row">
        <div className="fld">
          <div className="fl">{t('endpoint')}</div>
          <LlmEndpointSelect endpoints={endpoints} value={endpointId} onChange={setEndpointId} disabled={busy} />
        </div>
        <div className="fld">
          <div className="fl">{t('outputLang')}</div>
          <Seg value={outputLang} onChange={setOutputLang} label={t('outputLang')}
               options={SUPPORTED_LANGS.map(l => ({ value: l, label: t(`lang.${l}`) }))} />
        </div>
      </div>
      <div className="ft">
        <span>{endpoints.length === 0 ? t('noEndpoint') : restarting ? t('restartHint') : t('startHint')}</span>
        {restarting && <button type="button" className="btn" onClick={() => setRestarting(false)}>{t('cancelRestart')}</button>}
        <button type="button" className="btn ink" onClick={start} disabled={busy || endpoints.length === 0}>{t('start')}</button>
      </div>
    </div>
  );

  return (
    <div className="page-shell">
      <div className="p-pg">
        <div className="phd">
          <div>
            <Link className="back" to={backId != null ? `/my-trader/${backId}` : '/my-trader'}>
              <ChevronLeft className="ic" />{t('back')}
            </Link>
            <h1>{t('title')}</h1>
            <p>{t('desc')}</p>
            {/* 为谁写：有对话说这段对话的，还没开说开始后的 */}
            {active ? (
              <p>{view?.traderName ? t('forTrader', { name: view.traderName }) : t('forNoTrader')}</p>
            ) : mine && (
              <p>{target ? t('willFor', { name: target.pub.name }) : t('willForNone')}</p>
            )}
            {switching && <p>{target ? t('switchTo', { name: target.pub.name }) : t('switchToNone')}</p>}
            {active && endpoint && <p>{endpoint.name} · {t(`lang.${view?.outputLang ?? outputLang}`)}</p>}
          </div>
          <span className={cn('stg', busy && 'on', stageKey === 'done' && 'ok')}><i /><span>{t(`stage.${stageKey}`)}</span></span>
        </div>

        <Seg className="mtabs" value={tab} onChange={setTab} label={t('paneSwitch')} options={[
          { value: 'chat', label: t('dialog') },
          { value: 'doc', label: <>{t('body')}{draft != null && <i className="pd" />}</> },
        ]} />

        <div className="ws" data-pane={tab}>
          {/* 左栏：对话 */}
          <div className="pane left">
            <div ref={scrollRef} className="talk"
                 onScroll={e => {
                   const el = e.currentTarget;
                   stuckRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
                 }}>
              {!view?.active && !busy ? setup : (
                <>
                  {rows.map(row => {
                    const signed = row.side === 'agent' && prevSide !== 'agent';
                    if (row.side !== 'mark') prevSide = row.side;
                    if (row.side === 'user') return <Fragment key={row.key}>{row.node}</Fragment>;
                    return (
                      <div key={row.key} className="ca">
                        {signed && <div className="who"><span className="cface" aria-hidden />{t('agentName')}</div>}
                        {row.node}
                      </div>
                    );
                  })}
                  {restarting && setup}
                </>
              )}
            </div>
            {active && (
              <Composer busy={busy} stopping={stopping} onSend={say} onStop={stop} onRestart={() => setRestarting(true)}
                        placeholder={question ? t('freePh') : view?.done ? t('doneHint') : t('composerPh')} />
            )}
          </div>

          {/* 右栏：版本与正文 */}
          <div className="pane right">
            <div className="doc blk">
              {!body && draft == null ? (
                <p className="dempty">{t('empty')}</p>
              ) : (
                <>
                  <div className="dh">
                    <b>{t('versions')}</b>
                    {/* 写新一版时点不动、也不亮哪一版：正文是正在写的那版 */}
                    {versions.length > 0 && shownNo != null && (
                      <Seg value={draft != null ? '' : String(shownNo)} disabled={draft != null} onChange={v => setViewing(Number(v))}
                           label={t('versions')} options={versions.map(v => ({
                             value: String(v.no),
                             label: <>{t('version', { no: v.no })}{v.no === view?.currentNo && <i className="cur" />}</>,
                           }))} />
                    )}
                    <small>{t('keepHint')}</small>
                    <span className="r">
                      {draft != null ? (
                        <span className="orb"><span className="cface think" aria-hidden />{draft ? t('writingNow') : t('orbHint')}</span>
                      ) : (
                        <>
                          {shown?.finalVersion && <span className="tag g"><Check className="ic" />{t('final')}</span>}
                          {canContinue && (
                            <SureButton className="btn sm" sureLabel={t('rollbackSure')} title={t('rollbackConfirm', { no: shown.no })}
                                        onConfirm={() => continueFrom(shown.no)}>
                              {t('continueFrom')}
                            </SureButton>
                          )}
                        </>
                      )}
                    </span>
                  </div>
                  <div className="dt num">
                    <b>{t('body')}</b>
                    <span className={cn(body.length > MAX_CHARS && 'over')}>{t('chars', { n: body.length, max: MAX_CHARS })}</span>
                    <span className="r">
                      <button type="button" className="btn sm" onClick={copy} disabled={!body || draft != null}>
                        <Copy className="ic" />{t('copy')}
                      </button>
                    </span>
                  </div>
                  <div ref={docRef} className="body sx"
                       onScroll={e => {
                         const el = e.currentTarget;
                         docStuckRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
                       }}>
                    <InstructionText text={doc.text} chunks={doc.chunks} />
                  </div>
                  {shown?.notes && draft == null && <div className="notes"><b>{t('notes')}</b>{shown.notes}</div>}
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** agent 说的话：无框正文，流式时字是一段段弹出来的 */
function AgentText({ text, streaming = false }: { text: string; streaming?: boolean }) {
  return <StreamText className="md" content={text} streaming={streaming} />;
}

/** 用户的话：右边一块气泡；note 是气泡下面一行小字 */
function UserBubble({ text, note }: { text: string; note?: string }) {
  return (
    <div className="cu">
      <p>{text}</p>
      {note && <small>{note}</small>}
    </div>
  );
}

/** 写出一版：一颗绿标签，点了右栏看这版；被挤掉的那版打不开，灰的 */
function VersionMark({ label, onOpen }: { label: string; onOpen?: () => void }) {
  return onOpen
    ? <button type="button" className="tag g" onClick={onOpen}><Check className="ic" />{label}</button>
    : <span className="tag"><Check className="ic" />{label}</span>;
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
    <div className="ccard qc">
      <h4>{question.question}{question.required && <span className="tag sm o">{t('required')}</span>}</h4>
      <div className="opts" role="radiogroup" aria-label={question.question}>
        <OptionRow text={question.recommended} on={picked === question.recommended} onPick={onPick}
                   badge={t('recommended')} reason={question.reason} />
        {question.options.map(op => (
          <OptionRow key={op} text={op} on={picked === op} onPick={onPick} />
        ))}
      </div>
      <div className="f">
        <small>{t('typeHint')}</small>
        {!question.required && <button type="button" className="btn" onClick={onSkip}>{t('skip')}</button>}
        <button type="button" className="btn ink" onClick={onSubmit} disabled={!picked}>{t('submit')}</button>
      </div>
    </div>
  );
}

/** 一个选项：圆形单选点 + 文字；推荐项多一个标和一行理由 */
function OptionRow({ text, on, onPick, badge, reason }: {
  text: string;
  on: boolean;
  onPick: (option: string) => void;
  badge?: string;
  reason?: string;
}) {
  return (
    <button type="button" role="radio" aria-checked={on} className="opt" onClick={() => onPick(text)}>
      <i />
      <div>
        <b>{text}{badge && <span className="tag sm g">{badge}</span>}</b>
        {reason && <span>{reason}</span>}
      </div>
    </button>
  );
}

/** 底部输入框：多行自适应，Enter 发送、Shift+Enter 换行；跑着时可以先打字，发送键换成停止键 */
function Composer({ busy, stopping, placeholder, onSend, onStop, onRestart }: {
  busy: boolean;
  stopping: boolean;
  placeholder: string;
  /** restore：后端没接下时把这句放回输入框 */
  onSend: (text: string, restore: () => void) => void;
  onStop: () => void;
  onRestart: () => void;
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
    <div className="comp">
      <div className="cbox">
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
          aria-label={placeholder}
        />
        <div className="crow">
          <button type="button" className="btn sm plain" onClick={onRestart} disabled={busy}>
            <RotateCcw className="ic" />{t('restart')}
          </button>
          {busy ? (
            <button type="button" className="csend stop" onClick={onStop} disabled={stopping}
                    title={stopping ? t('ai:chat.stopping') : t('ai:chat.stopTitle')}
                    aria-label={stopping ? t('ai:chat.stopping') : t('ai:chat.stopTitle')}>
              <Square className="ic" />
            </button>
          ) : (
            <button type="button" className="csend" onClick={send} disabled={!text.trim()} title={t('send')} aria-label={t('send')}>
              <ArrowUp className="ic" />
            </button>
          )}
        </div>
      </div>
      <div className="hintl">{t('ai:composer.hint')}</div>
    </div>
  );
}

/**
 * 正文按行排：单独一行的段名加粗当小标题，其余原样；复制出去的还是原文。
 * chunks 是流式写时刚放出来的几段（在整篇里的位置），落在里面的字包起来弹出来
 */
function InstructionText({ text, chunks }: { text: string; chunks: PopChunk[] }) {
  const lines = text.split('\n');
  // 每一行在整篇里从第几个字开始（行与行之间隔一个换行符）
  const starts = lines.map((_, i) => lines.slice(0, i).reduce((at, l) => at + l.length + 1, 0));
  return (
    <>
      {lines.map((line, i) => (
        <p key={i} className={cn(SECTION_LINE.test(line) && 'sh')}>{line ? popText(line, starts[i], chunks) : ' '}</p>
      ))}
    </>
  );
}
