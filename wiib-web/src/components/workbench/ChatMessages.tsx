import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, ChevronRight, Copy, Globe, Loader2, RefreshCw, ShieldQuestion, X } from 'lucide-react';
import { Markdown } from '../Markdown';
import { cn, fmtTime, fmtTokens } from '../../lib/utils';
import { type ChatItem } from './chatStore';
import type { SearchSource } from '../../types';
import { HUB_NAME, type RailStep } from './chatView';

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

/** 过程轨里的一次联网搜索：搜着时亮点+搜索词，搜完变成"搜索了 N 个网站"可展开站点列表 */
function SearchStep({ item }: { item: Extract<ChatItem, { kind: 'search' }> }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  if (item.active) {
    return (
      <span className="text-foreground font-bold inline-flex items-center gap-1">
        <Globe className="w-3 h-3" /> {item.query ? t('rail.searching', { query: item.query }) : t('rail.searchingNoQuery')}
      </span>
    );
  }
  // 搜完但没报站点（上游不给 sources 的协议形态）：只说搜过了什么，别说"没命中"
  if (item.sources.length === 0) {
    return (
      <span className="inline-flex items-center gap-1">
        <Globe className="w-3 h-3" /> {t('rail.searchedNone')}
        {item.query && <span className="text-muted-foreground/70">· {item.query}</span>}
      </span>
    );
  }
  return (
    <>
      <button onClick={() => setOpen(v => !v)} className="inline-flex items-center gap-1 hover:text-foreground">
        <Globe className="w-3 h-3" />
        {t('rail.searched', { count: item.sources.length })}
        {item.query && <span className="text-muted-foreground/70">· {item.query}</span>}
        <ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />
      </button>
      {open && (
        <div className="mt-1 rounded-lg border border-border bg-card-2 px-2.5 py-1.5 text-[11px] max-h-44 overflow-y-auto space-y-0.5">
          {item.sources.map(s => (
            <a key={s.url} href={s.url} target="_blank" rel="noreferrer" title={s.url}
               className="flex items-baseline gap-2 hover:text-primary min-w-0">
              <span className="num shrink-0 text-muted-foreground/80">{domainOf(s.url)}</span>
              <span className="truncate">{s.title || s.url}</span>
            </a>
          ))}
        </div>
      )}
    </>
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
            <span className="led" />
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
 * 工作过程轨：一行摘要 + 展开后的竖轨节点。默认展开——收不收由用户点，别在他正看过程时自己收起来。
 * <p>
 * 摘要按轨里有什么现挑：跑着就说正在做什么，跑完优先报搜了几个站，没搜过就退回"N 步"。
 */
export function ProcessRail({ steps, active, open, onToggle }: {
  steps: RailStep[]; active: boolean; open: boolean; onToggle: () => void;
}) {
  const { t } = useTranslation('ai');

  const sites = new Set<string>();
  let searching = false;
  for (const { item } of steps) {
    if (item.kind === 'search') {
      item.sources.forEach(s => sites.add(s.url));
      if (item.active) searching = true;
    }
  }
  const summary = active
    ? (searching ? t('rail.searchingNoQuery') : t('rail.sumWorking'))
    : sites.size > 0 ? t('rail.searched', { count: sites.size })
      : t('rail.title', { count: steps.length });

  return (
    <div>
      <button
        onClick={onToggle}
        className="flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground hover:text-foreground py-0.5"
      >
        <ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />
        {summary}
        {active && <Loader2 className="w-3 h-3 animate-spin text-primary" />}
      </button>
      <div className={cn(
        'grid transition-[grid-template-rows] duration-300 ease-out',
        open ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]',
      )}>
        <div className="overflow-hidden min-h-0">
          <div className="ml-[5px] mt-1.5 border-l-2 border-border pl-3.5 space-y-2.5 py-0.5">
            {steps.map(({ item, index }) => {
              const hot = (item.kind === 'progress' && item.active) || (item.kind === 'search' && item.active);
              return (
                <div key={index} className="relative text-[11.5px] leading-relaxed text-muted-foreground">
                  <span className={cn(
                    'absolute -left-[18.5px] top-[5px] w-[7px] h-[7px] rounded-full border-2',
                    hot ? 'bg-primary border-primary shadow-[0_0_6px_var(--color-primary)]' : 'bg-card border-border',
                  )} />
                  {/* keyed=前端自己立的说明行，存的是 key，这里现翻；其余是后端下发的阶段文案，原样显示 */}
                  {item.kind === 'progress' && (
                    <span className={cn(hot && 'text-foreground font-bold')}>
                      {item.keyed ? t(item.text) : item.text}
                    </span>
                  )}
                  {item.kind === 'search' && <SearchStep item={item} />}
                </div>
              );
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
