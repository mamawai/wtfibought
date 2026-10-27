import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Activity, Check, CircleCheck, CircleX, Eye, PenLine, RotateCcw, TrendingDown, TrendingUp, X, type LucideIcon,
} from 'lucide-react';
import type {
  CampaignStatus, Candidate, CandidateStatus, EvalBrief, GateBrief, HoldoutBrief, HorizonBrief, Review, RunStatus,
  TimeseriesBrief,
} from '../api';
import { cx, HORIZONS, num, pct, primaryOf, signedPct, utcDay } from '../format';
import { Badge, type Tone } from './ui';

const STATUS: Record<CandidateStatus, { tone: Tone; icon: LucideIcon }> = {
  proposed: { tone: 'info', icon: Activity },
  evaluating: { tone: 'info', icon: Activity },
  rewrite: { tone: 'warn', icon: RotateCcw },
  reviewing: { tone: 'info', icon: Activity },
  revise: { tone: 'warn', icon: PenLine },
  passed: { tone: 'primary', icon: CircleCheck },
  observing: { tone: 'primary', icon: Eye },
  approved: { tone: 'gain', icon: Check },
  rejected_gates: { tone: 'loss', icon: X },
  rejected_review: { tone: 'loss', icon: X },
  rejected_fdr: { tone: 'loss', icon: X },
  failed: { tone: 'loss', icon: X },
  declined: { tone: 'muted', icon: CircleX },
};

/** 还在轮内走流程的状态：单条候选的标签前面带一颗呼吸的方点 */
const IN_FLIGHT = new Set<CandidateStatus>(['proposed', 'evaluating', 'rewrite', 'reviewing', 'revise']);

const VERDICT: Record<Review['verdict'], Tone> = { pass: 'gain', revise: 'warn', reject: 'loss' };

const RUN_TONE: Record<RunStatus, Tone> = { running: 'info', done: 'gain', failed: 'loss' };

const CAMPAIGN_TONE: Record<CampaignStatus, Tone> = {
  running: 'info', stopping: 'warn', stopped: 'muted', done: 'gain', failed: 'loss',
};

export function RunBadge({ status }: { status: RunStatus }) {
  const { t } = useTranslation();
  return <Badge tone={RUN_TONE[status]} pulse={status === 'running'}>{t(`runStatus.${status}`)}</Badge>;
}

export function CampaignBadge({ status }: { status: CampaignStatus }) {
  const { t } = useTranslation();
  return <Badge tone={CAMPAIGN_TONE[status]} pulse={status === 'running' || status === 'stopping'}>{t(`campaignStatus.${status}`)}</Badge>;
}

/** count 给了就在状态后面带条数（公开概览用） */
export function StatusBadge({ status, count }: { status: CandidateStatus; count?: number }) {
  const { t } = useTranslation();
  return (
    <Badge tone={STATUS[status].tone} icon={STATUS[status].icon} pulse={count == null && IN_FLIGHT.has(status)}>
      {t(`status.${status}`)}{count != null && <span className="num">{count}</span>}
    </Badge>
  );
}

export function PassBadge({ passed }: { passed: boolean }) {
  const { t } = useTranslation();
  return passed
    ? <Badge tone="gain" icon={Check}>{t('eval.passed')}</Badge>
    : <Badge tone="loss" icon={X}>{t('eval.failed')}</Badge>;
}

export function ReviewLine({ review }: { review: Review }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap items-start gap-x-2.5 gap-y-1 text-[13px] leading-relaxed">
      <span className="text-muted shrink-0">{t('cand.review')}</span>
      <Badge tone={VERDICT[review.verdict]}>{t(`cand.verdict.${review.verdict}`)}</Badge>
      <span className="flex-1 min-w-[12rem]">
        {review.comment}
        {review.suggestion && <span className="text-muted">（{t('cand.suggestion')}：{review.suggestion}）</span>}
      </span>
    </div>
  );
}

export function ExprBlock({ expr, note, direction }: { expr: string; note?: string | null; direction?: 1 | -1 | null }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-1.5">
      <code className="block border border-line bg-panel-2 px-3 py-2 text-[12.5px] break-all">{expr}</code>
      {direction != null && <DirectionLine direction={direction} />}
      {note && <p className="text-[12.5px] text-muted leading-relaxed">{t('cand.note')}：{note}</p>}
    </div>
  );
}

/** 按假设声明的方向；asked 是复评时问模型补的 */
export function DirectionLine({ direction, asked }: { direction: 1 | -1; asked?: boolean }) {
  const { t } = useTranslation();
  const Icon = direction > 0 ? TrendingUp : TrendingDown;
  return (
    <p className="flex items-center gap-1.5 text-[12.5px] text-muted">
      <Icon className="w-3.5 h-3.5 shrink-0" />
      {t('cand.direction')}：<span className="text-fg">{t(direction > 0 ? 'cand.up' : 'cand.down')}</span>
      {asked && <span>（{t('cand.directionAsked')}）</span>}
    </p>
  );
}

/** 每门一行：过没过 + 门名 + 一句带数字的原因 */
export function GateList({ gates }: { gates: (GateBrief & { name?: string | null })[] }) {
  const { t } = useTranslation();
  const passed = gates.filter(g => g.passed).length;
  return (
    <div className="space-y-2">
      <div className="flex items-baseline gap-2">
        <span className="microlabel">{t('gates.title')}</span>
        <span className={cx('text-[12.5px] font-bold num', passed === gates.length ? 'text-gain' : 'text-loss')}>
          {passed}/{gates.length}
        </span>
      </div>
      <ul className="border-t border-fg">
        {gates.map(g => (
          <li key={g.id} className="flex items-start gap-2 border-b border-line py-1.5 text-[12.5px] leading-relaxed">
            {g.passed
              ? <Check className="w-3 h-3 shrink-0 mt-[4px] text-gain" />
              : <X className="w-3 h-3 shrink-0 mt-[4px] text-loss" />}
            <span className="num font-mono font-bold shrink-0 w-7 text-[12px]">{g.id}</span>
            {g.name && <span className="text-muted shrink-0">{g.name}</span>}
            <span className={cx('min-w-0 break-words', g.passed ? 'text-muted' : 'text-fg')}>{g.note}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 四段 IC，两行两列按时间先后排；与总体反号的段标黄（G3 数的就是同号段） */
function Segments({ h }: { h: HorizonBrief }) {
  return (
    <span className="inline-grid grid-cols-2 justify-items-end gap-x-2">
      {h.segments.map((v, i) => (
        <span key={i} className={cx(v != null && h.meanIc != null && Math.sign(v) !== Math.sign(h.meanIc) && 'text-warn')}>
          {num(v, 3)}
        </span>
      ))}
    </span>
  );
}

/** 三组形状只做标签，按 IC 方向从弱的一头看到强的一头；老结果没有 */
function Shape({ h }: { h: HorizonBrief }) {
  const { t } = useTranslation();
  return <>{h.shape ? t(`brief.shapes.${h.shape}`) : num(null)}</>;
}

const ROWS: { key: string; get: (h: HorizonBrief) => ReactNode }[] = [
  { key: 'meanIc', get: h => (h.t == null ? num(h.meanIc) : `${num(h.meanIc)} · t ${h.t.toFixed(1)}`) },
  { key: 'icir', get: h => num(h.icir, 2) },
  { key: 'months', get: h => h.months },
  { key: 'spread', get: h => (h.spreadT == null ? signedPct(h.spread, 2) : `${signedPct(h.spread, 2)} · t ${h.spreadT.toFixed(1)}`) },
  { key: 'shape', get: h => <Shape h={h} /> },
  { key: 'segments', get: h => <Segments h={h} /> },
  { key: 'net', get: h => (h.sharpe == null ? signedPct(h.netReturn) : `${signedPct(h.netReturn)} · Sharpe ${h.sharpe.toFixed(2)}`) },
  { key: 'recentIc', get: h => (h.recentT == null ? num(h.recentIc) : `${num(h.recentIc)} · t ${h.recentT.toFixed(1)}`) },
  { key: 'recentNet', get: h => (h.recentSharpe == null ? signedPct(h.recentNetReturn)
    : `${signedPct(h.recentNetReturn)} · Sharpe ${h.recentSharpe.toFixed(2)}`) },
  { key: 'perm', get: h => num(h.permPct, 3) },
  { key: 'hit', get: h => pct(h.hitRate) },
  { key: 'exposure', get: h => pct(h.exposure) },
  { key: 'turnover', get: h => pct(h.turnover) },
  { key: 'funding', get: h => bps(h.fundingBps, true) },
  { key: 'breakEven', get: h => bps(h.breakEvenBps) },
];

function bps(v: number | null, signed = false): string {
  if (v == null) return num(null);
  return `${signed && v > 0 ? '+' : ''}${v.toFixed(1)} bps`;
}

/** primary 是判门用的那一档，表头标橙、整列淡橙底 */
function HorizonHead({ label, primary }: { label: ReactNode; primary: string }) {
  const { t } = useTranslation();
  return (
    <thead>
      <tr>
        <th>{label}</th>
        {HORIZONS.map(h => (
          <th key={h} className={cx('r', h === primary && 'text-primary')}>
            {h}{h === primary && <span className="ml-1 font-normal">{t('brief.primary')}</span>}
          </th>
        ))}
      </tr>
    </thead>
  );
}

const primaryCell = (h: string, primary: string) => cx('r num', h === primary && 'pri');

/** 切点之后的 IC；titleKey 换标题：holdout 是当前切点起，unseen 是发现它那一轮的切点起 */
export function HoldoutTable({ holdout, primary, titleKey = 'brief.holdout' }: {
  holdout: HoldoutBrief;
  primary: string;
  titleKey?: string;
}) {
  const { t } = useTranslation();
  const rows: { key: string; get: (h: HoldoutBrief['horizons'][string]) => ReactNode }[] = [
    { key: 'meanIc', get: h => num(h.meanIc) },
    { key: 'se', get: h => (h.se == null ? num(null) : `± ${h.se.toFixed(4)}`) },
    { key: 'icir', get: h => num(h.icir, 2) },
    { key: 'months', get: h => h.months },
    { key: 'monotonic', get: h => num(h.monotonic, 2) },
    { key: 'samples', get: h => h.samples },
  ];
  return (
    <table className="tbl">
      <HorizonHead label={t(titleKey, { from: utcDay(holdout.since) })} primary={primary} />
      <tbody>
        {rows.map(r => (
          <tr key={r.key}>
            <td className="text-muted">{t(`brief.${r.key}`)}</td>
            {HORIZONS.map(h => <td key={h} className={primaryCell(h, primary)}>{holdout.horizons[h] ? r.get(holdout.horizons[h]) : '—'}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** 产品币时序报告：三档 IC 与方向腿，主档再按币列 IC；只报告不做门 */
function TimeseriesTable({ ts, primary }: { ts: TimeseriesBrief; primary: string }) {
  const { t } = useTranslation();
  const rows: { key: string; get: (h: TimeseriesBrief['horizons'][string]) => ReactNode }[] = [
    { key: 'meanIc', get: h => num(h.meanIc) },
    { key: 'icir', get: h => num(h.icir, 2) },
    { key: 'sameSign', get: h => h.sameSignSymbols },
    { key: 'net', get: h => signedPct(h.netReturn) },
  ];
  return (
    <div className="space-y-2">
      <table className="tbl">
        <HorizonHead label={t('brief.timeseries')} primary={primary} />
        <tbody>
          {rows.map(r => (
            <tr key={r.key}>
              <td className="text-muted whitespace-nowrap">{t(`brief.${r.key}`)}</td>
              {HORIZONS.map(h => <td key={h} className={primaryCell(h, primary)}>{ts.horizons[h] ? r.get(ts.horizons[h]) : '—'}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="flex flex-wrap gap-x-3.5 gap-y-0.5 text-[12px] text-muted num">
        <span>{t('brief.timeseriesBySymbol')}</span>
        {Object.entries(ts.bySymbol).map(([s, v]) => <span key={s}>{s} <span className="font-semibold text-fg">{num(v, 3)}</span></span>)}
      </p>
    </div>
  );
}

type BriefLike = Pick<EvalBrief, 'horizons'>
  & Partial<Pick<EvalBrief, 'primary' | 'gain' | 'gainSharpe' | 'gainSharpeRecent' | 'recentFrom' | 'holdout' | 'unseen'
    | 'timeseries' | 'redundancy' | 'complexity' | 'neutral'>>;

/** 三档截面数字表；单因子再带交易方案增量一行、冗余/复杂度两项；算过 holdout、没见过的那段、时序报告的再各来一张 */
export function BriefTable({ brief }: { brief: BriefLike }) {
  const { t } = useTranslation();
  const { gain, gainSharpe, gainSharpeRecent, recentFrom, holdout, unseen, timeseries, redundancy, complexity, neutral } = brief;
  const primary = primaryOf(brief);
  // recent 那几行从哪一年起；老结果没算过就不显示
  const from = recentFrom?.slice(0, 4);
  const rows = from ? ROWS : ROWS.filter(r => !r.key.startsWith('recent'));
  // 交易方案净 Sharpe 那一行：主档写加进去前 → 后，别的档是 —
  const sharpeRow = (label: string, g: { base: number | null; with: number | null }) => (
    <tr key={label}>
      <td className="text-muted whitespace-nowrap">{label}</td>
      {HORIZONS.map(h => (
        <td key={h} className={primaryCell(h, primary)}>{h === primary ? `${num(g.base, 2)} → ${num(g.with, 2)}` : '—'}</td>
      ))}
    </tr>
  );
  return (
    <div className="space-y-5 min-w-0">
      <div className="overflow-x-auto">
        <table className="tbl">
          <HorizonHead label={t('brief.metric')} primary={primary} />
          <tbody>
            {rows.map(r => (
              <tr key={r.key}>
                <td className="text-muted whitespace-nowrap">{t(`brief.${r.key}`, { from })}</td>
                {HORIZONS.map(h => <td key={h} className={primaryCell(h, primary)}>{brief.horizons[h] ? r.get(brief.horizons[h]) : '—'}</td>)}
              </tr>
            ))}
            {gain && (
              <tr>
                <td className="text-muted whitespace-nowrap">{t('brief.gain')}</td>
                {HORIZONS.map(h => <td key={h} className={primaryCell(h, primary)}>{num(gain[h])}</td>)}
              </tr>
            )}
            {gainSharpeRecent && from && sharpeRow(t('brief.gainSharpeRecent', { from }), gainSharpeRecent)}
            {gainSharpe && sharpeRow(t(gainSharpeRecent && from ? 'brief.gainSharpeFull' : 'brief.gainSharpe'), gainSharpe)}
          </tbody>
        </table>
      </div>
      {redundancy && complexity && (
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-[12.5px]">
          <dt className="text-muted">{t('brief.redundancy')}</dt>
          <dd className={cx('num break-all', !redundancy.ready && 'text-warn')}>
            {!redundancy.ready ? t('brief.gridNotReady')
              : redundancy.with ? t('brief.redundancyValue', { name: redundancy.with, r: num(redundancy.maxAbs, 3) })
                : t('brief.noRedundancy')}
          </dd>
          {neutral && (
            <>
              <dt className="text-muted">{t('brief.neutral')}</dt>
              <dd className="num">
                {t('brief.neutralValue', {
                  n: neutral.against, ic: num(neutral.meanIc), kept: pct(neutral.retained, 0), t: num(neutral.t, 1),
                })}
              </dd>
            </>
          )}
          <dt className="text-muted">{t('brief.complexity')}</dt>
          <dd className="num">{t('brief.complexityValue', complexity)}</dd>
        </dl>
      )}
      {holdout && <div className="overflow-x-auto"><HoldoutTable holdout={holdout} primary={primary} /></div>}
      {unseen && <div className="overflow-x-auto"><HoldoutTable holdout={unseen} primary={primary} titleKey="brief.unseen" /></div>}
      {timeseries && <div className="overflow-x-auto"><TimeseriesTable ts={timeseries} primary={primary} /></div>}
    </div>
  );
}

/** 一条因子：顶上一道墨线开头，观察中的换成 2px 橙线；候选、手填评估共用 */
export function FactorCard({ title, badges, meta, hypothesis, expr, note, direction, reason, review, gates, brief, highlight,
  children }: {
  title: ReactNode;
  badges?: ReactNode;
  meta?: ReactNode;
  hypothesis?: string | null;
  expr?: string | null;
  note?: string | null;
  direction?: 1 | -1 | null;
  reason?: string | null;
  review?: Review | null;
  gates?: (GateBrief & { name?: string | null })[];
  brief?: BriefLike;
  highlight?: boolean;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <article className={cx('pt-4', highlight ? 'border-t-2 border-primary' : 'border-t border-fg')}>
      <header className="flex flex-wrap items-center gap-2">
        <h3 className="text-[15px] font-bold break-all">{title}</h3>
        {badges}
        {meta && <span className="ml-auto text-[12px] text-muted num">{meta}</span>}
      </header>
      <div className="mt-3 space-y-3.5">
        {hypothesis && <p className="max-w-[90ch] text-[14px] leading-relaxed">{hypothesis}</p>}
        {expr && <ExprBlock expr={expr} note={note} direction={direction} />}
        {reason && <p className="text-[13px] text-loss leading-relaxed">{t('cand.reason')}：{reason}</p>}
        {review && <ReviewLine review={review} />}
        {(gates || brief) && (
          <div className="grid gap-x-8 gap-y-5 pt-1 xl:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
            {gates && <GateList gates={gates} />}
            {brief && <BriefTable brief={brief} />}
          </div>
        )}
        {children}
      </div>
    </article>
  );
}

/** 一条候选：轮次详情与观察区共用；meta 不给就显示写了几次，children 放在卡片底部；观察中的橙线开头 */
export function CandidateCard({ c, meta, children }: { c: Candidate; meta?: ReactNode; children?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <FactorCard
      title={`${c.seq}. ${c.name}`}
      badges={<>{c.family && <Badge>{c.family}</Badge>}<StatusBadge status={c.status} /></>}
      meta={meta ?? t('cand.attempts', { n: c.attempts })}
      hypothesis={c.hypothesis}
      expr={c.expr}
      note={c.note}
      direction={c.direction}
      reason={c.reason}
      review={c.review}
      gates={c.gates}
      brief={c.brief}
      highlight={c.status === 'observing'}>
      {children}
    </FactorCard>
  );
}
