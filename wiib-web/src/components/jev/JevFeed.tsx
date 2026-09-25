import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, CircleHelp } from 'lucide-react';
import { cn, fmtNum, toCents } from '../../lib/utils';
import { fmtWindow } from '../../hooks/usePredictionMarket';
import { JevDecisionCard } from './JevDecisionCard';
import { CHOICE_STYLE, ENTRY_OPTIONS, noteworthy, pct } from './format';
import type { JevPredictionDecisionView, JevPredictionOverview, JevThresholds } from '../../types';

interface Group {
  windowStart: number;
  outcome?: string;
  items: JevPredictionDecisionView[];
}

/** 按回合分组，回合内按时间倒序（最新在上） */
function groupByWindow(feed: JevPredictionDecisionView[]): Group[] {
  const map = new Map<number, Group>();
  for (const d of feed) {
    let g = map.get(d.windowStart);
    if (!g) { g = { windowStart: d.windowStart, items: [] }; map.set(d.windowStart, g); }
    g.items.push(d);
    if (d.outcome) g.outcome = d.outcome;
  }
  const groups = [...map.values()].sort((a, b) => b.windowStart - a.windowStart);
  for (const g of groups) {
    g.items.sort((a, b) => b.decidedAt - a.decidedAt);
  }
  return groups;
}

/** 一道题：标题 + 我们自己的话 + 选项 */
function QuestionNote({ title, desc, options }: { title: string; desc: string; options: { label: string; cls: string }[] }) {
  return (
    <div>
      <div className="font-semibold text-foreground">{title}</div>
      <p className="mt-0.5">{desc}</p>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        {options.map(o => <span key={o.label} className={cn('chip', o.cls)}>{o.label}</span>)}
      </div>
    </div>
  );
}

/** 这些数字怎么看：每一行在说什么、涨的概率各是什么、Jev 要答的题、代码怎么执行。收起放在流的最上面 */
function Guide({ thresholds }: { thresholds: JevThresholds }) {
  const { t } = useTranslation(['community']);
  const [open, setOpen] = useState(false);
  const vars = { act: pct(thresholds.actThreshold), delay: thresholds.fillDelayMs / 1000, tol: toCents(thresholds.fillTolerance),
    stake: fmtNum(thresholds.baseStake, 0), max: fmtNum(thresholds.maxStakePerWindow, 0), jump: toCents(thresholds.jumpThreshold) };
  const chips = (keys: string[]) => keys.map(k => ({ label: t(`prediction.jev.choice.${k}`), cls: CHOICE_STYLE[k].text }));
  return (
    <div className="border-b border-border">
      <button type="button" onClick={() => setOpen(o => !o)} aria-expanded={open}
              className="w-full text-left py-3 flex items-center gap-2 text-[14px] font-semibold">
        <CircleHelp className="w-4 h-4 text-primary" />
        {t('prediction.jev.guideTitle')}
        <ChevronDown className={cn('ml-auto w-4 h-4 mute transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="pb-5 space-y-5 text-[13.5px] leading-[1.7] mute">
          <div className="space-y-1.5">
            <div className="font-semibold text-foreground">{t('prediction.jev.guideCardTitle')}</div>
            <p>{t('prediction.jev.guideAction')}</p>
            <p>{t('prediction.jev.guideDecides')}</p>
            <p>{t('prediction.jev.guideUpChance')}</p>
            {/* 名字一列、解释一列，名字跟卡片上那行的叫法一致 */}
            <dl className="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1.5 border-l-2 border-foreground pl-3 my-2">
              <dt className="font-semibold text-foreground">{t('prediction.jev.colModel')}</dt>
              <dd>{t('prediction.jev.guideModel')}</dd>
              <dt className="font-semibold text-foreground">{t('prediction.jev.colJev')}</dt>
              <dd>{t('prediction.jev.guideJev')}</dd>
              <dt className="font-semibold text-foreground">{t('prediction.jev.colMkt')}</dt>
              <dd>{t('prediction.jev.guideMkt')}</dd>
            </dl>
            <p>{t('prediction.jev.guideFold')}</p>
            <p>{t('prediction.jev.guideLegacy')}</p>
          </div>
          <div className="space-y-3">
            <div className="font-semibold text-foreground">{t('prediction.jev.guideQuestionsTitle')}</div>
            <QuestionNote title={t('prediction.jev.qEntryTitle')} desc={t('prediction.jev.qEntryDesc', vars)}
                          options={chips(ENTRY_OPTIONS)} />
          </div>
          <div className="space-y-1.5">
            <div className="font-semibold text-foreground">{t('prediction.jev.guideRulesTitle')}</div>
            <p>{t('prediction.jev.guideBuy', vars)}</p>
            <p>{t('prediction.jev.guideSell', vars)}</p>
            <p>{t('prediction.jev.guideFill', vars)}</p>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 右栏：Jev 在看什么、怎么决定。每 15 秒一行，按回合分组、最新在上，整页一起滚。
 * 每个回合默认只露出要紧的行和最新一行，其余"先等 / 拿着"折起；点"显示全部"展开。行本身默认收起，点开看细节。
 */
export function JevFeed({ overview, feed, currentWindowStart }: {
  overview: JevPredictionOverview | null;
  feed: JevPredictionDecisionView[];
  currentWindowStart: number | null;
}) {
  const { t } = useTranslation(['community']);
  const groups = useMemo(() => groupByWindow(feed), [feed]);
  const [opened, setOpened] = useState<Record<number, boolean>>({});
  const [showAll, setShowAll] = useState<Record<number, boolean>>({});

  return (
    <div>
      <div className="sec-h mb-1">
        <h2>{t('prediction.jev.feedTitle')}<small>{t('prediction.jev.feedSub')}</small></h2>
      </div>

      {/* 概览和流同一次请求回来，没概览就是还没加载 */}
      {overview && (
        <>
          <Guide thresholds={overview.thresholds} />

          {groups.length === 0 && <div className="py-10 text-center text-[14px] mute">{t('prediction.jev.noDecisions')}</div>}

          {groups.map(g => {
            const all = !!showAll[g.windowStart];
            const visible = all ? g.items : g.items.filter((d, i) => i === 0 || noteworthy(d));
            const hidden = g.items.length - visible.length;
            return (
              <div key={g.windowStart} className="mt-7">
                <div className="flex flex-wrap items-center gap-2.5 pb-2 border-b border-foreground">
                  <b className="num text-[15px] font-bold">{fmtWindow(g.windowStart)}</b>
                  {g.windowStart === currentWindowStart && (
                    <span className="chip up"><i className="dot pulse" />{t('prediction.jev.current')}</span>
                  )}
                  {g.outcome && (
                    <span className={cn('chip', g.outcome === 'UP' ? 'up' : g.outcome === 'DOWN' ? 'dn' : 'mute')}>
                      {g.outcome === 'VOID' ? t('prediction.void') : t('prediction.jev.outcome', { side: g.outcome })}
                    </span>
                  )}
                  {(hidden > 0 || all) && (
                    <button type="button" onClick={() => setShowAll(prev => ({ ...prev, [g.windowStart]: !all }))}
                            className="ml-auto text-[12.5px] mute underline underline-offset-[3px] hover:text-foreground transition-colors">
                      {all ? t('prediction.jev.showActions') : t('prediction.jev.showAll', { n: g.items.length })}
                    </button>
                  )}
                </div>
                {visible.map(d => (
                  <JevDecisionCard key={d.id} d={d} open={!!opened[d.id]}
                                   onToggle={() => setOpened(prev => ({ ...prev, [d.id]: !prev[d.id] }))} />
                ))}
              </div>
            );
          })}
        </>
      )}
    </div>
  );
}
