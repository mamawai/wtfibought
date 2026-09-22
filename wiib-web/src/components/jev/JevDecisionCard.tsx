import { useTranslation } from 'react-i18next';
import { AlertTriangle, ChevronDown, Clock, Info, ShieldAlert } from 'lucide-react';
import { cn, fmtNum, fmtTime, toCents } from '../../lib/utils';
import { JevAnswerBar } from './JevAnswerBar';
import { ACTION_CHIP, CHOICE_STYLE, MOMENTUM_BAR, MOMENTUM_TEXT, noticeKind, pct, reasonCode } from './format';
import type { JevAnswer, JevPredictionDecisionView, JevThresholds } from '../../types';

const ENTRY_KEYS = ['BUY_UP', 'BUY_DOWN', 'WAIT'];
const EXIT_KEYS = ['HOLD', 'SELL'];

/** 四种提示：把握不够 / 被代码拦下 / 这次没问 / 出错 */
const NOTICE_STYLE = {
  unsure: { icon: Info, cls: 'mute' },
  blocked: { icon: ShieldAlert, cls: 'wn' },
  skipped: { icon: Clock, cls: 'wn' },
  error: { icon: AlertTriangle, cls: 'dn' },
} as const;

/** 一句提示：没执行 Jev 的选择时说为什么；数字取决策行自己的字段 */
function JevNotice({ d, thresholds }: { d: JevPredictionDecisionView; thresholds: JevThresholds }) {
  const { t } = useTranslation(['community']);
  const kind = noticeKind(d);
  if (!kind) return null;
  const code = reasonCode(d);
  const side = d.jevChoice === 'BUY_DOWN' ? 'DOWN' : 'UP';
  const ask = side === 'UP' ? d.upAsk : d.downAsk;
  const vars = {
    side,
    ask: ask != null ? toCents(ask) : '--',
    act: pct(thresholds.actThreshold),
    minAsk: toCents(thresholds.minAsk),
    maxAsk: toCents(thresholds.maxAsk),
    sec: d.bookAgeMs != null ? (d.bookAgeMs / 1000).toFixed(1) : '--',
  };
  const key = code === 'STALE_BOOK' && d.bookAgeMs == null ? 'STALE_BOOK_NONE' : code;
  const text = d.error ? t('prediction.jev.notice.error', { msg: d.error }) : t(`prediction.jev.notice.${key}`, vars);
  const { icon: Icon, cls } = NOTICE_STYLE[kind];
  return (
    <div className={cn('mt-1.5 flex items-start gap-1.5 text-[13px]', cls)}>
      <Icon className="w-3.5 h-3.5 shrink-0 mt-[3px]" />
      <span className="break-words min-w-0">{text}</span>
    </div>
  );
}

/** Jev 的选择 + 把握 + 后劲最高档；跟 ProbRow 一样"名 + 值"一对不拆开 */
function JevSays({ choice, choiceP, momentum }: { choice: string; choiceP?: number; momentum: JevAnswer }) {
  const { t } = useTranslation(['community']);
  const ps = [0, 1, 2].map(i => momentum.probabilities[i]);
  const lv = ps.indexOf(Math.max(...ps));
  return (
    <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-[14px]">
      <span className="whitespace-nowrap">
        <span className="mute">{t('prediction.jev.jevPicks')}</span>{' '}
        <b className={cn('font-bold', CHOICE_STYLE[choice].text)}>{t(`prediction.jev.choice.${choice}`)}</b>
      </span>
      <span className="whitespace-nowrap">
        <span className="mute">{t('prediction.jev.confidence')}</span> <b className="num font-semibold">{pct(choiceP)}</b>
      </span>
      <span className="whitespace-nowrap">
        <span className="mute">{t('prediction.jev.momentumLabel')}</span>{' '}
        <b className={cn('font-semibold', MOMENTUM_TEXT[lv])}>{t(`prediction.jev.levels.momentum.${lv}`)}</b>
      </span>
    </div>
  );
}

/** 涨的概率三个数：数学 / 后劲修正 / 市场。"名 + 数"一对不拆开，窄屏在对与对之间折行 */
function ProbRow({ d }: { d: JevPredictionDecisionView }) {
  const { t } = useTranslation(['community']);
  const items = [['colModel', d.pModel], ['colJev', d.pJev], ['colMkt', d.pMkt]] as const;
  return (
    <div className="mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-[13px] mute">
      <span>{t('prediction.jev.upChance')}</span>
      {items.map(([k, v]) => (
        <span key={k} className="whitespace-nowrap">
          {t(`prediction.jev.${k}`)} <b className="num font-semibold text-foreground">{pct(v)}</b>
        </span>
      ))}
    </div>
  );
}

/**
 * 一个检查点一行：实际动作 / 注额 / 时间 → Jev 选了什么、多大把握、后劲 → 涨的概率三个数 → 没照 Jev 做时的一句提示。
 * 点开看两道题里 Jev 每个选项的概率条；没问 Jev 的行（盘口太旧、出错）没东西可展开。
 */
export function JevDecisionCard({ d, open, onToggle, thresholds }: {
  d: JevPredictionDecisionView;
  open: boolean;
  onToggle: () => void;
  thresholds: JevThresholds;
}) {
  const { t } = useTranslation(['community']);
  // 持仓时决定题的选项是拿着 / 卖掉
  const holding = d.jevChoice === 'HOLD' || d.jevChoice === 'SELL';
  const expandable = !!d.answers;
  return (
    <div className="border-b border-border">
      <button type="button" onClick={onToggle} disabled={!expandable} aria-expanded={expandable ? open : undefined}
              className="hov w-full text-left py-3.5 disabled:cursor-default">
        <div className="flex items-center gap-2.5 flex-wrap">
          <span className={cn('chip', ACTION_CHIP[d.action])}>{t(`prediction.jev.action.${d.action}`)}</span>
          {d.stake != null && <b className="num text-[14px] font-semibold">${fmtNum(d.stake)}</b>}
          <span className="num text-[13px] mute">
            {fmtTime(d.decidedAt, true)} · {t('prediction.jev.checkpointAt', { s: d.checkpoint.slice(1) })}
          </span>
          {expandable && <ChevronDown className={cn('ml-auto w-4 h-4 mute transition-transform', open && 'rotate-180')} />}
        </div>
        {d.jevChoice && d.answers && <JevSays choice={d.jevChoice} choiceP={d.jevChoiceP} momentum={d.answers.momentum} />}
        <ProbRow d={d} />
        <JevNotice d={d} thresholds={thresholds} />
      </button>

      {open && d.answers && (
        <div className="pb-4 space-y-4">
          <JevAnswerBar title={t(holding ? 'prediction.jev.qExitTitle' : 'prediction.jev.qEntryTitle')} a={d.answers.decide}
                        options={(holding ? EXIT_KEYS : ENTRY_KEYS).map(k => ({ key: k, label: t(`prediction.jev.choice.${k}`), bar: CHOICE_STYLE[k].bar }))} />
          <JevAnswerBar title={t('prediction.jev.q1Title')} a={d.answers.momentum}
                        options={[0, 1, 2].map(i => ({ key: String(i), label: t(`prediction.jev.levels.momentum.${i}`), bar: MOMENTUM_BAR[i] }))} />
          {d.upAsk != null && d.downAsk != null && (
            <p className="num text-[12.5px] mute">{t('prediction.jev.asksThen', { up: toCents(d.upAsk), down: toCents(d.downAsk) })}</p>
          )}
        </div>
      )}
    </div>
  );
}
