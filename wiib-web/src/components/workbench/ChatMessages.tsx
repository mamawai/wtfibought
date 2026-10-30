import { useCallback, useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Activity, Bot, ChartCandlestick, Check, ChevronRight, Copy, Gauge, Globe, Layers, Loader2, Newspaper, Percent,
  RefreshCw, Scale, ShieldQuestion, Sparkle, Sparkles, UserSearch, Waves, Wrench, X, type LucideIcon,
} from 'lucide-react';
import { Markdown } from '../Markdown';
import { cn, fmtTime, fmtTokens } from '../../lib/utils';
import { type ChatItem, type ChildStep } from './chatStore';
import type { ChildRunKind, SearchSource } from '../../types';
import { HUB_NAME, PULSE_DOT, type RailStep } from './chatView';

type ChildItem = Extract<ChatItem, { kind: 'child' }>;

/** 工具名 → 图标；认不出的用扳手 */
const TOOL_ICON: Record<string, LucideIcon> = {
  klines: ChartCandlestick, kline_structure: ChartCandlestick, indicators: Activity, market_snapshot: Gauge,
  option_iv: Waves, funding_history: Percent, orderbook_depth: Layers, news_search: Newspaper, web_search: Globe,
  trader_overview: Bot, trader_positions: Bot, trader_trades: Bot, trader_plans: Bot, trader_decisions: Bot,
  trader_play_stats: Bot, analyze_my_behavior: UserSearch,
};
/** 子运行按种类取图标：派出去的 worker 是星星，深研判是天平 */
const CHILD_ICON: Record<ChildRunKind, LucideIcon> = { DELEGATE: Sparkles, DEEP_ANALYSIS: Scale };

/** 工具参数摘要只挑这几个字段、按这个顺序：整份参数铺出来太长 */
const ARG_KEYS = ['symbol', 'interval', 'kind', 'limit', 'query'];

/** 研判中的星环：Polaris 星不动，一道橙弧沿外圈转（.wiib-star-sweep）。size 是整体边长 px，星占中间 64% */
function StarSweep({ size }: { size: number }) {
  return (
    <span aria-hidden className="relative inline-block shrink-0" style={{ width: size, height: size }}>
      <Sparkle className="absolute inset-[18%] w-[64%] h-[64%] text-primary fill-current" strokeWidth={1.5} />
      <span className="absolute inset-0 rounded-full wiib-star-sweep" style={{ '--sweep-w': size >= 20 ? '1.5px' : '1px' } as CSSProperties} />
    </span>
  );
}

/** 来源链接的展示名：域名（去 www.），解析不了的原样给 */
function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** 答案底部的来源芯片：按域名去重（同站多篇算一个，链到第一篇），超过 8 个折起来 */
function SourceChips({ sources }: { sources: SearchSource[] }) {
  const { t } = useTranslation('ai');
  const [all, setAll] = useState(false);
  const byDomain = new Map<string, SearchSource>();
  for (const s of sources) {
    const d = domainOf(s.url);
    if (!byDomain.has(d)) byDomain.set(d, s);
  }
  const entries = [...byDomain.entries()];
  const shown = all ? entries : entries.slice(0, 8);
  const hidden = entries.length - shown.length;
  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
      <span className="microlabel inline-flex items-center gap-1"><Globe className="w-3 h-3" /> {t('chat.sources')}</span>
      {shown.map(([domain, s]) => (
        <a key={domain} href={s.url} target="_blank" rel="noreferrer" title={s.title || s.url}
           className="inline-flex items-center h-5 px-1.5 rounded-md border border-border bg-card-2 text-[10px] num text-muted-foreground hover:text-primary hover:border-primary/60 transition-colors">
          {domain}
        </a>
      ))}
      {(hidden > 0 || all) && (
        <button onClick={() => setAll(v => !v)}
                className="h-5 px-1.5 rounded-md border border-dashed border-border text-[10px] num text-muted-foreground hover:text-foreground">
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

/** 0:42 / 3:05 这种计时 */
function fmtClock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
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

/** 过程条目的骨架：左图标、右文字；做着的那条图标变橙 */
function RailRow({ icon: Icon, hot, children }: { icon: LucideIcon; hot?: boolean; children: ReactNode }) {
  return (
    <div className="flex items-start gap-2 text-[12px] leading-relaxed animate-in fade-in slide-in-from-bottom-2">
      <Icon className={cn('w-3.5 h-3.5 shrink-0 mt-[3px]', hot ? 'text-primary' : 'text-muted-foreground')} />
      <div className="flex-1 min-w-0">{children}</div>
    </div>
  );
}

/** 一行：做了什么 + 参数（灰、截断）+ 右侧状态。做着的文字扫光 */
function StepLine({ label, detail, hot, detailHot, trailing }: {
  label: string; detail?: string; hot?: boolean; detailHot?: boolean; trailing?: ReactNode;
}) {
  return (
    <div className="flex items-center gap-1.5 min-w-0">
      {/* 带参数的行保持一行：参数先让（shrink 3 倍），再轮到名字截断；没参数的阶段文案可以折行 */}
      <span className={cn(detail ? 'min-w-0 truncate' : 'min-w-0 break-words', hot && 'wiib-shimmer')}>{label}</span>
      {detail && <span className={cn('min-w-0 truncate shrink-[3]', detailHot ? 'wiib-shimmer' : 'text-muted-foreground')}>{detail}</span>}
      {trailing && <span className="ml-auto shrink-0 pl-2 inline-flex items-center gap-1.5 microlabel">{trailing}</span>}
    </div>
  );
}

/** 主 agent 的一次工具调用 */
function ToolRow({ item }: { item: Extract<ChatItem, { kind: 'tool' }> }) {
  const label = useToolLabel();
  return (
    <RailRow icon={TOOL_ICON[item.name] ?? Wrench} hot={item.active}>
      <StepLine label={label(item.name)} detail={argsSummary(item.args)} hot={item.active} />
    </RailRow>
  );
}

/** 后端下发的阶段文案（行为分析等）；keyed 的是前端自己立的说明行，现翻 */
function PhaseRow({ item }: { item: Extract<ChatItem, { kind: 'progress' }> }) {
  const { t } = useTranslation('ai');
  return (
    <RailRow icon={Sparkle} hot={item.active}>
      <StepLine label={item.keyed ? t(item.text) : item.text} hot={item.active} />
    </RailRow>
  );
}

/** 一次联网搜索：搜着时亮着搜索词，搜完变成"搜索了 N 个网站"，点开是站点列表 */
function SearchRow({ item }: { item: Extract<ChatItem, { kind: 'search' }> }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  if (item.active) {
    return (
      <RailRow icon={Globe} hot>
        <StepLine label={item.query ? t('rail.searching', { query: item.query }) : t('rail.searchingNoQuery')} hot />
      </RailRow>
    );
  }
  if (item.failed) {
    return (
      <RailRow icon={Globe}>
        <StepLine label={t('rail.searchFailed')} detail={item.query ?? undefined} />
      </RailRow>
    );
  }
  // 搜完但没报站点（上游不给 sources 的协议形态）：只说搜过了什么，别说"没命中"
  if (item.sources.length === 0) {
    return (
      <RailRow icon={Globe}>
        <StepLine label={t('rail.searchedNone')} detail={item.query ?? undefined} />
      </RailRow>
    );
  }
  return (
    <RailRow icon={Globe}>
      <button onClick={() => setOpen(v => !v)} className="w-full text-left hover:text-foreground" aria-expanded={open}>
        <StepLine
          label={t('rail.searched', { count: item.sources.length })}
          detail={item.query ?? undefined}
          trailing={<ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />}
        />
      </button>
      {open && (
        <div className="mt-1.5 ml-[6px] border-l-2 border-border pl-3 text-[11px] max-h-44 overflow-y-auto space-y-0.5">
          {item.sources.map(s => (
            <a key={s.url} href={s.url} target="_blank" rel="noreferrer" title={s.url}
               className="flex items-baseline gap-2 hover:text-primary min-w-0">
              <span className="num shrink-0 text-muted-foreground/80">{domainOf(s.url)}</span>
              <span className="truncate">{s.title || s.url}</span>
            </a>
          ))}
        </div>
      )}
    </RailRow>
  );
}

/** 子运行内部的一步：工具调用按工具取图标，阶段文案做完打勾、做着是小星 */
function ChildStepRow({ step, text }: { step: ChildStep; text: string }) {
  const Icon = step.kind === 'tool' ? (TOOL_ICON[step.name] ?? Wrench) : step.active ? Sparkle : Check;
  return (
    <div className="flex items-start gap-2 text-[11.5px] leading-relaxed">
      <Icon className={cn('w-3 h-3 shrink-0 mt-[3px]', step.active ? 'text-primary' : 'text-muted-foreground')} />
      <span className={cn('min-w-0 break-words', step.active && 'wiib-shimmer')}>{text}</span>
    </div>
  );
}

/**
 * 后台子运行一条：右侧是状态和计时，收着时在名字后面露一句——跑着是它当前在干什么，失败了是原因；
 * 点开是一道细竖线下的内部——任务书、它内部的每一步、结局。
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

  return (
    <RailRow icon={CHILD_ICON[item.runKind]} hot={running}>
      <button onClick={onToggle} className="w-full text-left" aria-expanded={open}>
        <StepLine
          label={item.label}
          detail={detail}
          detailHot={current != null}
          trailing={<>
            {running && <span className={PULSE_DOT} />}
            <span className={cn(item.status === 'failed' && 'text-loss')}>{t(`rail.status.${item.status}`)}</span>
            {timed && <span className="num">· {elapsed}</span>}
            <ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />
          </>}
        />
      </button>
      {open && (
        <div className="mt-1.5 ml-[6px] border-l-2 border-border pl-3 py-0.5 space-y-[7px]">
          {item.brief && (
            <p className="text-[11.5px] leading-relaxed text-muted-foreground">
              <span className="microlabel text-foreground font-bold mr-1.5">{t('rail.brief')}</span>{item.brief}
            </p>
          )}
          {item.steps.map((s, i) => <ChildStepRow key={i} step={s} text={stepText(s)} />)}
          {item.status === 'done' && (
            <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
              <Check className="w-3 h-3 shrink-0" />{t('rail.reportDelivered', { hub: HUB_NAME })}
            </p>
          )}
          {item.status === 'failed' && <p className="text-[11px] text-loss">{error ?? t('rail.status.failed')}</p>}
          {item.status === 'cancelled' && <p className="text-[11px] text-muted-foreground">{t('rail.status.cancelled')}</p>}
        </div>
      )}
    </RailRow>
  );
}

/** 一条过程都还没有时的等待行：星环 + 扫光文字 + 计时（刷新后发现的后台轮没有起点，不计） */
export function ThinkingRow({ text, since }: { text: string; since: number | null }) {
  const now = useClock(since != null);
  return (
    <div className="flex items-center gap-2 text-[13px]">
      <StarSweep size={20} />
      <span className="wiib-shimmer font-semibold">{text}</span>
      {since != null && <span className="ml-auto microlabel num">{fmtClock(now - since)}</span>}
    </div>
  );
}

/** 用户提问：右侧气泡（无框底色块），下面挂时刻 */
export function UserBubble({ item, onCancelQueued }: {
  item: Extract<ChatItem, { kind: 'user' }>;
  /** 只有排队中的能撤：已经发出去的那条正在烧钱，撤不回来 */
  onCancelQueued?: () => void;
}) {
  const { t } = useTranslation('ai');
  return (
    <div className="flex flex-col items-end gap-1">
      <div className={cn(
        'max-w-[80%] bg-card-2 px-4 py-2.5',
        'text-[15px] leading-relaxed whitespace-pre-wrap break-words',
        item.queued && 'opacity-60',
      )}>
        {item.content}
      </div>
      {/* 时刻才挂 .num（等宽数字）；中文套上它会掉到 mono 的回退字体 */}
      {item.queued ? (
        <span className="flex items-center gap-1.5">
          <span className="microlabel">{t('chat.queued')}</span>
          {onCancelQueued && (
            <button
              onClick={onCancelQueued}
              className="text-muted-foreground/60 hover:text-loss transition-colors"
              title={t('chat.cancelQueued')}
              aria-label={t('chat.cancelQueuedAria')}
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </span>
      ) : (
        <span className="microlabel font-mono tabular-nums">{fmtTime(item.at)}</span>
      )}
    </div>
  );
}

/**
 * agent 回答：署名行 + 无框正文 + 一排小动作（复制 / 重新生成，读数挤在同一行右端）。
 * <p>
 * 正文不套卡片——同一屏里只有用户提问是气泡，答案铺满可用宽度，读起来才像正文而不是聊天记录。
 * 动作行只在流结束后出现：耗时/token 要等 done 事件才有值，流式期间挂个空壳会让布局在出字过程中跳一下。
 */
export function AssistantAnswer({ item, canRegenerate, onRegenerate }: {
  item: Extract<ChatItem, { kind: 'assistant' }>;
  /** 只有会话最后一条答案能重新生成：回退上下文只回得到末尾那一轮 */
  canRegenerate: boolean;
  onRegenerate: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const [copied, setCopied] = useState(false);
  const copy = useCallback(() => {
    void navigator.clipboard.writeText(item.content).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }).catch(() => { /* 无剪贴板权限时静默，按钮保持原样 */ });
  }, [item.content]);

  // 补答行对应的提问不在会话末尾，后端回退会误伤中间轮次，所以它没有重新生成
  const deferred = item.deferred === true;
  const meta = item.meta;
  // 取不到值＝上游没报用量或这一轮的账不可信，不是 0：整段不显示，不要拿 0 冒充
  const readout = [
    meta?.latencyMs != null ? `${(meta.latencyMs / 1000).toFixed(1)}s` : null,
    meta?.totalTokens != null ? `${fmtTokens(meta.totalTokens)} tok` : null,
    fmtTime(item.at),
  ].filter(Boolean).join(' · ');

  return (
    <div className="min-w-0">
      <div className="flex items-center gap-2 mb-1.5">
        <span className="w-[7px] h-[7px] bg-primary shrink-0" />
        {/* 署名要压得住，不用微标签那档灰 */}
        <span className="microlabel text-foreground font-bold uppercase shrink-0">{HUB_NAME}</span>
        {meta?.modelLabel && <span className="microlabel font-mono tabular-nums truncate min-w-0">{meta.modelLabel}</span>}
        {item.streaming && (
          <span className="ml-auto flex items-center gap-1.5 shrink-0">
            <span className={PULSE_DOT} />
            <span className="microlabel">{t('chat.generating')}</span>
          </span>
        )}
      </div>

      <div className="text-[15px] leading-relaxed">
        <Markdown content={item.content} />
        {item.streaming && item.content && (
          <span className="inline-block w-1.5 h-3.5 bg-primary/80 rounded-[1px] ml-0.5 align-middle animate-pulse" />
        )}
      </div>

      {/* 来源随 done 到（历史回放从库里带）：流式期间不显示，与动作行同理 */}
      {!item.streaming && item.sources && item.sources.length > 0 && <SourceChips sources={item.sources} />}

      {/* 动作是一排淡灰小图标，悬停才亮；读数挤在同一行右端 */}
      {!item.streaming && (
        <div className="mt-2 flex items-center gap-0.5 text-muted-foreground/60">
          <button
            onClick={copy}
            className="w-7 h-7 flex items-center justify-center hover:text-foreground hover:bg-surface-hover transition-colors"
            title={copied ? t('common:copied') : t('chat.copyAnswer')}
            aria-label={t('chat.copyAnswer')}
          >
            {copied ? <Check className="w-3.5 h-3.5 text-success" /> : <Copy className="w-3.5 h-3.5" />}
          </button>
          {canRegenerate && !deferred && (
            <button
              onClick={onRegenerate}
              className="w-7 h-7 flex items-center justify-center hover:text-foreground hover:bg-surface-hover transition-colors"
              title={t('chat.regenTitle')}
              aria-label={t('chat.regen')}
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          )}
          <span className="microlabel num ml-auto truncate min-w-0 pl-2">{readout}</span>
        </div>
      )}
    </div>
  );
}

/**
 * 工作过程：一行摘要 + 一列条目（图标 + 做了什么 + 参数 + 右侧状态）。
 * 默认展开——收不收由用户点，别在他正看过程时自己收起来；子运行那条默认收着，点开看里面。
 * <p>
 * 摘要：live（本轮还在跑、答案没开始）时"正在研判 · N 步"扫光 + 星环 + 从本轮开跑起算的秒表
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

  return (
    <div>
      <button
        onClick={onToggle}
        className="w-full flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground hover:text-foreground py-0.5"
      >
        <ChevronRight className={cn('w-3 h-3 shrink-0 transition-transform duration-200', open && 'rotate-90')} />
        {live ? (
          <>
            <span className="wiib-shimmer">{label?.live ?? t('rail.live', { count: steps.length })}</span>
            <StarSweep size={12} />
          </>
        ) : (
          <>
            <span>{label?.done ?? t('rail.title', { count: steps.length })}</span>
            {runningChildren > 0 ? (
              <span className="inline-flex items-center gap-1.5 font-normal">
                <span className={PULSE_DOT} />{t('rail.childrenRunning', { count: runningChildren })}
              </span>
            ) : sites.size > 0 && <span className="font-normal">· {t('rail.searched', { count: sites.size })}</span>}
          </>
        )}
        {live && since != null && <span className="ml-auto microlabel num font-normal">{fmtClock(now - since)}</span>}
      </button>
      <div className={cn(
        'grid transition-[grid-template-rows] duration-300 ease-out',
        open ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]',
      )}>
        <div className="overflow-hidden min-h-0">
          {/* 左边距 = 轨头的箭头宽 + 间距，条目和轨头的字对齐 */}
          <div className="ml-[18px] mt-2 space-y-2 pb-0.5">
            {steps.map(({ item }) => {
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
            })}
          </div>
        </div>
      </div>
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
    <div className={cn(
      'rounded-xl border border-border border-l-[3px] p-3.5 space-y-2.5',
      item.status === 'pending' ? 'border-l-primary bg-card' : 'border-l-muted-foreground/30 opacity-70',
    )}>
      <div className="flex items-center gap-2">
        <ShieldQuestion className="w-4 h-4 text-primary shrink-0" />
        <span className="text-xs font-black">{t('hitl.title')}</span>
        <span className="text-[10px] font-bold px-2 py-0.5 bg-primary/10 text-primary">{item.symbol}</span>
      </div>
      <p className="text-xs text-muted-foreground leading-relaxed">{item.reason}</p>
      {item.status === 'pending' ? (
        <div className="flex gap-2">
          <button
            disabled={busy}
            onClick={() => onDecide(true)}
            className="border border-border hover:bg-primary/8 flex-1 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50 transition-colors inline-flex items-center justify-center gap-1.5"
          >
            {submitting === 'approve' && <Loader2 className="w-3 h-3 animate-spin" />}
            {t('hitl.approve')}
          </button>
          <button
            disabled={busy}
            onClick={() => onDecide(false)}
            className="border border-border hover:bg-surface-hover flex-1 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50 transition-colors inline-flex items-center justify-center gap-1.5"
          >
            {submitting === 'reject' && <Loader2 className="w-3 h-3 animate-spin" />}
            {t('term.reject')}
          </button>
        </div>
      ) : (
        <p className="text-[10px] font-bold text-muted-foreground">
          {item.status === 'approved' ? t('hitl.approved') : t('hitl.rejected')}
        </p>
      )}
    </div>
  );
}
