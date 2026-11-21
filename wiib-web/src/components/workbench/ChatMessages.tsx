import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Activity, Bot, ChartCandlestick, Check, ChevronRight, Copy, Gauge, Globe, Layers, Newspaper, Percent,
  RefreshCw, Scale, Sparkle, Sparkles, UserSearch, Waves, Wrench, X, type LucideIcon,
} from 'lucide-react';
import { StreamText } from '../StreamText';
import { useToast } from '../ui/use-toast';
import { cn, fmtTime, fmtTokens } from '../../lib/utils';
import { type ChatItem, type ChildStep } from './chatStore';
import type { ChildRunKind, SearchSource } from '../../types';
import { HUB_NAME, type RailStep } from './chatView';
import './ChatMessages.css';

type ChildItem = Extract<ChatItem, { kind: 'child' }>;

/** 工具名 → 图标；认不出的用扳手 */
const TOOL_ICON: Record<string, LucideIcon> = {
  klines: ChartCandlestick, kline_structure: ChartCandlestick, indicators: Activity, market_snapshot: Gauge,
  option_iv: Waves, funding_history: Percent, orderbook_depth: Layers, news_search: Newspaper, web_search: Globe,
  trader_list: Bot, trader_overview: Bot, trader_positions: Bot, trader_trades: Bot, trader_plans: Bot, trader_decisions: Bot,
  trader_play_stats: Bot, analyze_my_behavior: UserSearch,
};
/** 子运行按种类取图标：派出去的 worker 是星星，深研判是天平 */
const CHILD_ICON: Record<ChildRunKind, LucideIcon> = { DELEGATE: Sparkles, DEEP_ANALYSIS: Scale };

/** 工具参数摘要只挑这几个字段、按这个顺序：整份参数铺出来太长 */
const ARG_KEYS = ['symbol', 'interval', 'kind', 'limit', 'query'];

/** 来源链接的展示名：域名（去 www.），解析不了的原样给 */
function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** 答案底部的来源：按域名去重（同站多篇算一个，链到第一篇），超过 3 个折起来 */
function SourceChips({ sources }: { sources: SearchSource[] }) {
  const { t } = useTranslation('ai');
  const [all, setAll] = useState(false);
  const byDomain = new Map<string, SearchSource>();
  for (const s of sources) {
    const d = domainOf(s.url);
    if (!byDomain.has(d)) byDomain.set(d, s);
  }
  const entries = [...byDomain.entries()];
  const shown = all ? entries : entries.slice(0, 3);
  const hidden = entries.length - shown.length;
  return (
    <div className="csrc">
      <span>{t('chat.sources')}</span>
      {shown.map(([domain, s]) => (
        <a key={domain} href={s.url} target="_blank" rel="noreferrer" title={s.title || s.url}>{domain}</a>
      ))}
      {(hidden > 0 || all) && (
        <button type="button" onClick={() => setAll(v => !v)}>
          {all ? t('chat.lessSources') : t('chat.moreSources', { count: hidden })}
        </button>
      )}
    </div>
  );
}

/** 每秒刷一次的"现在"；不在跑就停表 */
function useClock(running: boolean): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!running) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [running]);
  return now;
}

/** 00:42 / 03:05 这种计时 */
function fmtClock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

function argsSummary(args?: Record<string, unknown>): string {
  if (!args) return '';
  return ARG_KEYS.map(k => args[k])
    .filter((v): v is string | number => (typeof v === 'string' && v !== '') || typeof v === 'number')
    .join(' · ');
}

/** 工具名翻成"做了什么"；词表里没有的工具显示"调用 xxx" */
function useToolLabel() {
  const { t } = useTranslation('ai');
  return useCallback((name: string) => t(`rail.tool.${name}`, { defaultValue: t('rail.tool.unknown', { name }) }), [t]);
}

/** 子运行内部最后一条亮着的步 */
function lastActive(steps: ChildStep[]): ChildStep | undefined {
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i].active) return steps[i];
  }
  return undefined;
}

/** 主 agent 的一次工具调用：图标 + 做了什么 + 参数，做着的那条变橙、右边标进行中 */
function ToolRow({ item }: { item: Extract<ChatItem, { kind: 'tool' }> }) {
  const { t } = useTranslation('ai');
  const label = useToolLabel();
  const Icon = TOOL_ICON[item.name] ?? Wrench;
  return (
    <div className={cn('cstep', item.active && 'hot')}>
      <Icon className="ic" /><b>{label(item.name)}</b><span>{argsSummary(item.args)}</span>
      {item.active && <em>{t('rail.status.running')}</em>}
    </div>
  );
}

/** 后端下发的阶段文案（行为分析等）；keyed 的是前端自己立的说明行，现翻 */
function PhaseRow({ item }: { item: Extract<ChatItem, { kind: 'progress' }> }) {
  const { t } = useTranslation('ai');
  return (
    <div className={cn('cstep', item.active && 'hot')}>
      <Sparkle className="ic" /><b>{item.keyed ? t(item.text) : item.text}</b>
    </div>
  );
}

/** 一次联网搜索：搜着时亮着搜索词，搜完变成"搜索了 N 个网站"，点开是站点列表 */
function SearchRow({ item }: { item: Extract<ChatItem, { kind: 'search' }> }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  if (item.active) {
    return (
      <div className="cstep hot">
        <Globe className="ic" /><b>{item.query ? t('rail.searching', { query: item.query }) : t('rail.searchingNoQuery')}</b>
      </div>
    );
  }
  // 搜失败、或搜完但没报站点（上游不给 sources 的协议形态）：只说搜过了什么，别说"没命中"
  if (item.failed || item.sources.length === 0) {
    return (
      <div className={cn('cstep', item.failed && 'bad')}>
        <Globe className="ic" /><b>{item.failed ? t('rail.searchFailed') : t('rail.searchedNone')}</b><span>{item.query}</span>
      </div>
    );
  }
  return (
    <>
      <button type="button" className="cstep" aria-expanded={open} onClick={() => setOpen(v => !v)}>
        <Globe className="ic" /><b>{t('rail.searched', { count: item.sources.length })}</b><span>{item.query}</span>
        <em><ChevronRight className="ic" /></em>
      </button>
      {open && (
        <div className="csites">
          {item.sources.map(s => (
            <a key={s.url} href={s.url} target="_blank" rel="noreferrer" title={s.url}>
              <b>{domainOf(s.url)}</b><span>{s.title || s.url}</span>
            </a>
          ))}
        </div>
      )}
    </>
  );
}

/** 子运行内部的一步：工具调用按工具取图标，阶段文案做完打勾、做着是小星 */
function ChildStepRow({ step, text }: { step: ChildStep; text: string }) {
  const Icon = step.kind === 'tool' ? (TOOL_ICON[step.name] ?? Wrench) : step.active ? Sparkle : Check;
  return <div className={cn('cstep', step.active && 'hot')}><Icon className="ic" /><span>{text}</span></div>;
}

/**
 * 后台子运行一块：右边是状态和计时，收着时在名字后面露一句——跑着是它当前在干什么，失败了是原因；
 * 点开是任务书、它内部的每一步、结局。
 */
function ChildRow({ item, open, onToggle }: { item: ChildItem; open: boolean; onToggle: () => void }) {
  const { t } = useTranslation('ai');
  const toolLabel = useToolLabel();
  const running = item.status === 'running';
  const now = useClock(running);
  const elapsed = fmtClock((running ? now : item.endedAt ?? now) - item.at);
  const stepText = (s: ChildStep) =>
    s.kind === 'tool' ? [toolLabel(s.name), argsSummary(s.args)].filter(Boolean).join(' · ') : s.text;
  const current = !open && running ? lastActive(item.steps) : undefined;
  const error = item.keyed && item.error ? t(item.error) : item.error;
  const detail = current ? stepText(current) : !open && item.status === 'failed' ? error : undefined;
  const timed = item.status === 'running' || item.status === 'done';
  const Icon = CHILD_ICON[item.runKind];

  return (
    <div className="cchild">
      <button type="button" onClick={onToggle} aria-expanded={open}>
        <Icon className="ic" /><b>{item.label}</b>{detail && <span>{detail}</span>}
        <em className={cn(running && 'on', item.status === 'failed' && 'bad')}>
          {t(`rail.status.${item.status}`)}{timed && ` · ${elapsed}`}
        </em>
      </button>
      {open && (
        <div className="in">
          {item.brief && <p className="brief"><b>{t('rail.brief')}</b>{item.brief}</p>}
          {item.steps.map((s, i) => <ChildStepRow key={i} step={s} text={stepText(s)} />)}
          {item.status === 'done' && <div className="cstep"><Check className="ic" /><span>{t('rail.reportDelivered', { hub: HUB_NAME })}</span></div>}
          {item.status === 'failed' && <p className="bad">{error ?? t('rail.status.failed')}</p>}
          {item.status === 'cancelled' && <p className="brief">{t('rail.status.cancelled')}</p>}
        </div>
      )}
    </div>
  );
}

/** 一条过程都还没有时的等待行：想事的小脸 + 一句 + 计时（刷新后发现的后台轮没有起点，不计） */
export function ThinkingRow({ text, since }: { text: string; since: number | null }) {
  const now = useClock(since != null);
  return (
    <div className="cthink">
      <span className="cface think" aria-hidden />
      <span>{text}</span>
      {since != null && <span className="ck">{fmtClock(now - since)}</span>}
    </div>
  );
}

/** 用户提问：右边一块气泡，下面挂时刻；排队中的能撤 */
export function UserBubble({ item, onCancelQueued }: {
  item: Extract<ChatItem, { kind: 'user' }>;
  /** 只有排队中的能撤：已经发出去的那条正在烧钱，撤不回来 */
  onCancelQueued?: () => void;
}) {
  const { t } = useTranslation('ai');
  return (
    <div className={cn('cu', item.queued && 'q')}>
      <p>{item.content}</p>
      {item.queued ? (
        <small>
          {t('chat.queued')}
          {onCancelQueued && <> · <button type="button" className="linkb" aria-label={t('chat.cancelQueuedAria')} onClick={onCancelQueued}>{t('chat.cancelQueued')}</button></>}
        </small>
      ) : <small>{fmtTime(item.at)}</small>}
    </div>
  );
}

/**
 * agent 回答：不带框的正文 + 来源 + 一排小动作（复制 / 重新生成，模型名、耗时、用量、时刻挤在同一行右端）。
 * <p>
 * 正文不套卡片——同一屏里只有用户提问是气泡，答案铺满可用宽度，读起来才像正文而不是聊天记录。
 * 动作行只在流结束后出现：耗时/token 要等 done 事件才有值，流式期间挂个空壳会让布局在出字过程中跳一下。
 * last=会话最后一条答案，动作一直露着
 */
export function AssistantAnswer({ item, last, canRegenerate, onRegenerate }: {
  item: Extract<ChatItem, { kind: 'assistant' }>;
  last: boolean;
  /** 只有会话最后一条答案能重新生成：回退上下文只回得到末尾那一轮 */
  canRegenerate: boolean;
  onRegenerate: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const { toast } = useToast();
  const copy = () => {
    navigator.clipboard.writeText(item.content)
      .then(() => toast(t('common:copied'), 'success'))
      .catch(() => { /* 无剪贴板权限时静默 */ });
  };

  // 补答行对应的提问不在会话末尾，后端回退会误伤中间轮次，所以它没有重新生成
  const deferred = item.deferred === true;
  const meta = item.meta;
  // 取不到值＝上游没报用量或这一轮的账不可信，不是 0：整段不显示，不要拿 0 冒充
  const readout = [
    meta?.modelLabel,
    meta?.latencyMs != null ? `${(meta.latencyMs / 1000).toFixed(1)}s` : null,
    meta?.totalTokens != null ? `${fmtTokens(meta.totalTokens)} tok` : null,
    meta?.cachedTokens != null ? t('detail.cachedTokens', { n: fmtTokens(meta.cachedTokens) }) : null,
    fmtTime(item.at),
  ].filter(Boolean).join(' · ');

  return (
    <div className={cn('ca', last && 'last')}>
      <StreamText className="md" content={item.content} streaming={item.streaming} />

      {/* 来源随 done 到（历史回放从库里带）：流式期间不显示，与动作行同理 */}
      {!item.streaming && item.sources && item.sources.length > 0 && <SourceChips sources={item.sources} />}

      {!item.streaming && (
        <div className="cact">
          <button type="button" onClick={copy} title={t('chat.copyAnswer')} aria-label={t('chat.copyAnswer')}>
            <Copy className="ic" />
          </button>
          {canRegenerate && !deferred && (
            <button type="button" onClick={onRegenerate} title={t('chat.regenTitle')} aria-label={t('chat.regen')}>
              <RefreshCw className="ic" />
            </button>
          )}
          <span>{readout}</span>
        </div>
      )}
    </div>
  );
}

/**
 * 工作过程：一行摘要 + 左边一根细线串起来的每一步（图标 + 做了什么 + 参数 + 右侧状态）。
 * 收不收由用户点，别在他正看过程时自己收起来；收着又还在跑时，正在做的那一步露一行。子运行那块默认收着，点开看里面。
 * <p>
 * 摘要：live（本轮还在跑、答案没开始）时"正在研判 · N 步"+ 闪着的橙点 + 从本轮开跑起算的秒表
 * （和"分析问题中"那行的表接得上）；有后台任务在跑就报几个；都完了报搜了几个站，不显示时长——答案底部的读数里有耗时。
 * label：调用方自己定的摘要文案，不给就用"研判"那套。
 */
export function ProcessRail({ steps, live, since, open, onToggle, label }: {
  steps: RailStep[]; live: boolean; since: number | null; open: boolean; onToggle: () => void;
  label?: { live: string; done: string };
}) {
  const { t } = useTranslation('ai');
  // 子运行块各自的开合，键是 runId
  const [openChildren, setOpenChildren] = useState<Record<string, boolean>>({});
  const now = useClock(live);

  const sites = new Set<string>();
  let runningChildren = 0;
  for (const { item } of steps) {
    if (item.kind === 'search') item.sources.forEach(s => sites.add(s.url));
    if (item.kind === 'child' && item.status === 'running') runningChildren++;
  }

  const row = ({ item }: RailStep) => {
    switch (item.kind) {
      case 'tool': return <ToolRow key={item.rid} item={item} />;
      case 'search': return <SearchRow key={item.rid} item={item} />;
      case 'progress': return <PhaseRow key={item.rid} item={item} />;
      case 'child': return (
        <ChildRow
          key={item.rid}
          item={item}
          open={!!openChildren[item.runId]}
          onToggle={() => setOpenChildren(prev => ({ ...prev, [item.runId]: !prev[item.runId] }))}
        />
      );
    }
  };
  // 收着又在跑：最后一条亮着的那一步露出来（后台任务看它是不是还在跑）
  const hot = !open && live
    ? [...steps].reverse().find(({ item }) => item.kind === 'child' ? item.status === 'running' : item.active)
    : undefined;

  return (
    <div className="cproc">
      <button type="button" className="crail" aria-expanded={open} onClick={onToggle}>
        {live && <i className="pd" />}
        {live ? (label?.live ?? t('rail.live', { count: steps.length })) : (label?.done ?? t('rail.title', { count: steps.length }))}
        {!live && (runningChildren > 0
          ? <span>· {t('rail.childrenRunning', { count: runningChildren })}</span>
          : sites.size > 0 && <span>· {t('rail.searched', { count: sites.size })}</span>)}
        {live && since != null && <span className="ck">{fmtClock(now - since)}</span>}
        <ChevronRight className="ic" />
      </button>
      <div className={cn('col', open && 'open')}>
        <div><div className="csteps">{steps.map(row)}</div></div>
      </div>
      {hot && <div className="csteps">{row(hot)}</div>}
    </div>
  );
}

/**
 * HITL 确认卡：深研判 3 次深模型调用是贵操作，人工把关后 agent 才继续。
 * submitting 是这张卡自己的在途状态——面板里可能挂着好几张，别一张在提交把所有卡都禁掉。
 */
export function HitlCard({ item, onDecide, submitting }: {
  item: Extract<ChatItem, { kind: 'hitl' }>;
  onDecide: (approved: boolean) => void;
  submitting: 'approve' | 'reject' | null;
}) {
  const { t } = useTranslation('ai');
  const busy = submitting !== null;
  return (
    <div className="ccard">
      <h4><Sparkles className="ic" />{t('hitl.title')}<span className="tag sm">{item.symbol}</span></h4>
      <p>{item.reason}</p>
      {item.status === 'pending' ? (
        <div className="f">
          <button type="button" className={cn('btn ink', submitting === 'approve' && 'busy')} disabled={busy} onClick={() => onDecide(true)}>
            <span>{t('hitl.approve')}</span>
          </button>
          <button type="button" className={cn('btn', submitting === 'reject' && 'busy')} disabled={busy} onClick={() => onDecide(false)}>
            <span>{t('term.reject')}</span>
          </button>
        </div>
      ) : (
        <div className="done">
          {item.status === 'approved' ? <Check className="ic" /> : <X className="ic" />}
          {item.status === 'approved' ? t('hitl.approved') : t('hitl.rejected')}
        </div>
      )}
    </div>
  );
}

