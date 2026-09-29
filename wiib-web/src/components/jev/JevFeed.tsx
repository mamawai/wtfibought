import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, CircleHelp } from 'lucide-react';
import { cn } from '../../lib/utils';
import { fmtWindow } from '../../hooks/usePredictionMarket';
import { JevDecisionCard, PathChip } from './JevDecisionCard';
import { ARM_CODE, PATTERN_OPTIONS, PATTERN_STYLE, noteworthy, thresholdVars } from './format';
import type { JevArm, JevPredictionDecisionView, JevPredictionOverview, JevThresholds } from '../../types';

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

/** 一道题：标题 + 我们自己的话 + 选项（是非题不列） */
function QuestionNote({ title, desc, options }: { title: string; desc: string; options?: { label: string; cls: string }[] }) {
  return (
    <div>
      <div className="font-semibold text-foreground">{title}</div>
      <p className="mt-0.5">{desc}</p>
      {options && (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {options.map(o => <span key={o.label} className={cn('chip', o.cls)}>{o.label}</span>)}
        </div>
      )}
    </div>
  );
}

/** 三组按这个顺序讲；突变两组先讲 */
const ARMS: JevArm[] = ['JUMP_CODE', 'JUMP_JEV', 'TIMER_JEV'];

/** 这些数字怎么看：每一行在说什么、涨的概率各是什么、Jev 要答的六道题（哪一组用哪道）、三组各自怎么买卖。收起放在流的最上面 */
function Guide({ thresholds }: { thresholds: JevThresholds }) {
  const { t } = useTranslation(['community']);
  const [open, setOpen] = useState(false);
  const vars = thresholdVars(thresholds);
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
            <p>{t('prediction.jev.guideQuestions')}</p>
            <QuestionNote title={t('prediction.jev.qWinTitle')} desc={t('prediction.jev.qWinDesc', vars)} />
            <QuestionNote title={t('prediction.jev.qPatternTitle')} desc={t('prediction.jev.qPatternDesc')}
                          options={PATTERN_OPTIONS.map(k => ({ label: t(`prediction.jev.pattern.${k}`), cls: PATTERN_STYLE[k].text }))} />
            <QuestionNote title={t('prediction.jev.qFadingTitle')} desc={t('prediction.jev.qFadingDesc', vars)} />
            <QuestionNote title={t('prediction.jev.qFlowTitle')} desc={t('prediction.jev.qFlowDesc')} />
            <QuestionNote title={t('prediction.jev.qDipTitle')} desc={t('prediction.jev.qDipDesc')} />
            <QuestionNote title={t('prediction.jev.qAgainstTitle')} desc={t('prediction.jev.qAgainstDesc', vars)} />
          </div>
          <div className="space-y-1.5">
            <div className="font-semibold text-foreground">{t('prediction.jev.guideRulesTitle')}</div>
            <p>{t('prediction.jev.guideArms', vars)}</p>
            {/* 三组各一段：代号和组名 + 叫醒方式芯片（跟卡片上那个一样）+ 规则 */}
            {ARMS.map(arm => (
              <p key={arm}>
                <b className="text-foreground">{ARM_CODE[arm]} {t(`prediction.jev.armName.${arm}`)}</b>{' '}
                <PathChip path={arm === 'TIMER_JEV' ? 'T' : 'J'} /> {t(`prediction.jev.guideArm.${arm}`, vars)}
              </p>
            ))}
            <p>{t('prediction.jev.guideJumpDef', vars)}</p>
            <p>{t('prediction.jev.guideCommon', vars)}</p>
            <p>{t('prediction.jev.guideFill', vars)}</p>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 右栏：Jev 在看什么、代码怎么决定。在看的这一局被叫醒一次一行（突变两组只在合格突变时有行，v5-3 开盘后第 60 秒起每 30 秒一行），
 * 按回合分组、最新在上，整页一起滚。
 * 每个回合默认只露出要紧的行和最新一行，其余条件没过、照常拿着、已经买过、照常不动的折起；点"显示全部"展开。行本身默认收起，点开看细节。
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
                  <JevDecisionCard key={d.id} d={d} arm={overview.run.arm} open={!!opened[d.id]}
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
