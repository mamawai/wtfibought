import { useTranslation } from 'react-i18next';
import { AlertTriangle, ChevronDown, Clock, Info, ShieldAlert } from 'lucide-react';
import { cn, fmtNum, fmtTime, toCents } from '../../lib/utils';
import { JevAnswerBar } from './JevAnswerBar';
import {
  ACTION_CHIP, CHOICE_STYLE, ENTRY_OPTIONS, EXIT_OPTIONS, MOMENTUM_BAR, MOMENTUM_TEXT, noticeKind, pct, reasonCode, reasonSide,
} from './format';
import type { JevAnswer, JevPredictionDecisionView } from '../../types';

/** R1 旧版决定题的选项 */
const R1_ENTRY_KEYS = ['BUY_UP', 'BUY_DOWN', 'WAIT'];

/** 四种提示：把握不够 / 被代码拦下 / 这次没问或没成交 / 出错 */
const NOTICE_STYLE = {
  unsure: { icon: Info, cls: 'mute' },
  blocked: { icon: ShieldAlert, cls: 'wn' },
  skipped: { icon: Clock, cls: 'wn' },
  error: { icon: AlertTriangle, cls: 'dn' },
} as const;

/** MISSED 的"Jev 看到的价→等完再看的价"换成美分，没价是 -- */
function missedPrices(reason?: string): { from: string; to: string } {
  const [from, to] = (reason?.split(' ')[3] ?? '').split('→');
  const c = (v?: string) => (v && v !== 'none' ? String(toCents(Number(v))) : '--');
  return { from: c(from), to: c(to) };
}

/** 一句提示：被拦、没问、没成交、出错时说为什么；数字取决策行自己的字段 */
function JevNotice({ d }: { d: JevPredictionDecisionView }) {
  const { t } = useTranslation(['community']);
  const kind = noticeKind(d);
  if (!kind) return null;
  const code = reasonCode(d);
  // 哪边：写在 reason 里，R1 旧版看 Jev 选的
  const side = reasonSide(d) ?? (d.jevChoice === 'BUY_DOWN' ? 'DOWN' : 'UP');
  const ask = side === 'UP' ? d.upAsk : d.downAsk;
  // Chainlink 停了多久写在 reason 里，盘口停了多久看 bookAgeMs
  const staleMs = code === 'STALE_CHAINLINK' ? Number(d.reason?.split(' ')[1]) : d.bookAgeMs;
  const vars = {
    side,
    ask: ask != null ? toCents(ask) : '--',
    p: pct(d.jevChoiceP),
    sec: staleMs != null && Number.isFinite(staleMs) ? (staleMs / 1000).toFixed(1) : '--',
    ...missedPrices(d.reason),
  };
  let key = code;
  if (code === 'STALE_BOOK' && d.bookAgeMs == null) key = 'STALE_BOOK_NONE';
  if (code === 'MISSED' && d.reason?.split(' ')[1] === 'SELL') key = 'MISSED_SELL';
  // R2 的 NO_QUOTE 是两边都没人卖，reason 里没写哪边
  if (code === 'NO_QUOTE' && !reasonSide(d) && !d.jevChoice) key = 'NO_QUOTE_BOTH';
  const text = d.error ? t('prediction.jev.notice.error', { msg: d.error }) : t(`prediction.jev.notice.${key}`, vars);
  const { icon: Icon, cls } = NOTICE_STYLE[kind];
  return (
    <div className={cn('mt-1.5 flex items-start gap-1.5 text-[13px]', cls)}>
      <Icon className="w-3.5 h-3.5 shrink-0 mt-[3px]" />
      <span className="break-words min-w-0">{text}</span>
    </div>
  );
}

/** Jev 选了什么、多大把握，后面是给它看的数学参考数：空仓是买那边每份比成本高多少，持仓是现在卖每份比估计多拿多少 */
function JevDecides({ d }: { d: JevPredictionDecisionView }) {
  const { t } = useTranslation(['community']);
  const choice = d.jevChoice!;
  const holding = EXIT_OPTIONS.includes(choice);
  const side = choice === 'BUY_UP' ? 'UP' : choice === 'BUY_DOWN' ? 'DOWN' : undefined;
  const c = d.edge != null ? toCents(d.edge) : null;
  return (
    <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-[14px]">
      <span className="whitespace-nowrap">
        <span className="mute">{t('prediction.jev.jevPicks')}</span>{' '}
        <b className={cn('font-bold', CHOICE_STYLE[choice].text)}>{t(`prediction.jev.choice.${choice}`)}</b>
      </span>
      <span className="whitespace-nowrap">
        <span className="mute">{t('prediction.jev.confidence')}</span> <b className="num font-semibold">{pct(d.jevChoiceP)}</b>
      </span>
      {c != null && (holding || side) && (
        <span className="whitespace-nowrap">
          <span className="mute">{holding ? t('prediction.jev.sellVsMath') : t('prediction.jev.buyVsMath', { side })}</span>{' '}
          <b className="num font-semibold">{c > 0 ? '+' : ''}{c}¢</b>
        </span>
      )}
    </div>
  );
}

/** R2：按 Jev 的胜率算、更划算那边的每份优势；持仓行、没人卖、钱包不够的行没有 */
function JevEdge({ d }: { d: JevPredictionDecisionView }) {
  const { t } = useTranslation(['community']);
  const side = reasonSide(d);
  if (d.edge == null || !side) return null;
  const c = toCents(d.edge);
  return (
    <div className="mt-2 text-[14px]">
      <span className="mute">{t('prediction.jev.edgeLabel')}</span>{' '}
      <b className={cn('num font-semibold', side === 'UP' ? 'up' : 'dn')}>{side} {c > 0 ? '+' : ''}{c}¢</b>
    </div>
  );
}

/** R1 旧版：Jev 的选择 + 把握 + 后劲最高档；持仓行没问决定，只有后劲。跟 ProbRow 一样"名 + 值"一对不拆开 */
function JevSays({ choice, choiceP, momentum }: { choice?: string; choiceP?: number; momentum: JevAnswer }) {
  const { t } = useTranslation(['community']);
  const ps = [0, 1, 2].map(i => momentum.probabilities[i]);
  const lv = ps.indexOf(Math.max(...ps));
  return (
    <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-[14px]">
      {choice && (
        <>
          <span className="whitespace-nowrap">
            <span className="mute">{t('prediction.jev.jevPicks')}</span>{' '}
            <b className={cn('font-bold', CHOICE_STYLE[choice].text)}>{t(`prediction.jev.choice.${choice}`)}</b>
          </span>
          <span className="whitespace-nowrap">
            <span className="mute">{t('prediction.jev.confidence')}</span> <b className="num font-semibold">{pct(choiceP)}</b>
          </span>
        </>
      )}
      <span className="whitespace-nowrap">
        <span className="mute">{t('prediction.jev.momentumLabel')}</span>{' '}
        <b className={cn('font-semibold', MOMENTUM_TEXT[lv])}>{t(`prediction.jev.levels.momentum.${lv}`)}</b>
      </span>
    </div>
  );
}

/** 涨的概率三个数：数学 / Jev / 市场。"名 + 数"一对不拆开，窄屏在对与对之间折行 */
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
 * 一个检查点一行：实际动作 / 注额 / 时间 → Jev 选了什么、多大把握、数学参考数（R2 是 Jev 眼里的每份优势，R1 是后劲）→
 * 涨的概率三个数 → 被拦、没问、没成交、出错时的一句提示。点开看 Jev 每道题的回答；没问 Jev 的行（盘口或 Chainlink 太旧、出错）没东西可展开。
 */
export function JevDecisionCard({ d, open, onToggle }: {
  d: JevPredictionDecisionView;
  open: boolean;
  onToggle: () => void;
}) {
  const { t } = useTranslation(['community']);
  const a = d.answers;
  const expandable = !!a;
  // R3 起有入场题或离场题；R1 决定题只认买 UP / 买 DOWN / 等
  const decided = !!(a?.entry || a?.exit) && !!d.jevChoice;
  const r1Choice = a?.momentum && d.jevChoice && R1_ENTRY_KEYS.includes(d.jevChoice) ? d.jevChoice : undefined;
  const bar = (keys: string[]) => keys.map(k => ({ key: k, label: t(`prediction.jev.choice.${k}`), bar: CHOICE_STYLE[k].bar }));
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
        {decided ? <JevDecides d={d} />
          : a?.momentum ? <JevSays choice={r1Choice} choiceP={d.jevChoiceP} momentum={a.momentum} /> : <JevEdge d={d} />}
        <ProbRow d={d} />
        <JevNotice d={d} />
      </button>

      {open && a && (
        <div className="pb-4 space-y-4">
          {a.entry && <JevAnswerBar title={t('prediction.jev.qEntryTitle')} a={a.entry} options={bar(ENTRY_OPTIONS)} />}
          {a.exit && <JevAnswerBar title={t('prediction.jev.qExitTitle')} a={a.exit} options={bar(EXIT_OPTIONS)} />}
          {a.up_wins && a.down_wins && (
            <JevAnswerBar title={t('prediction.jev.qWinsTitle')}
                          a={{ probabilities: { UP: a.up_wins.noul, DOWN: a.down_wins.noul } }}
                          options={[{ key: 'UP', label: t('prediction.jev.upWins'), bar: 'bg-gain' },
                                    { key: 'DOWN', label: t('prediction.jev.downWins'), bar: 'bg-loss' }]} />
          )}
          {r1Choice && a.decide && <JevAnswerBar title={t('prediction.jev.qEntryTitle')} a={a.decide} options={bar(R1_ENTRY_KEYS)} />}
          {a.momentum && (
            <JevAnswerBar title={t('prediction.jev.q1Title')} a={a.momentum}
                          options={[0, 1, 2].map(i => ({ key: String(i), label: t(`prediction.jev.levels.momentum.${i}`), bar: MOMENTUM_BAR[i] }))} />
          )}
          {d.upAsk != null && d.downAsk != null && (
            <p className="num text-[12.5px] mute">{t('prediction.jev.asksThen', { up: toCents(d.upAsk), down: toCents(d.downAsk) })}</p>
          )}
        </div>
      )}
    </div>
  );
}
