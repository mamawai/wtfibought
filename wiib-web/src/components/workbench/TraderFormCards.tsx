import { useEffect, useState, type ReactElement } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Check, X } from 'lucide-react';
import { traderApi } from '../../api';
import i18n from '../../i18n';
import { cn } from '../../lib/utils';
import { Stepper } from '../Stepper';
import type { TraderActionPanel, TraderActionResult, TraderFormKind } from '../../types';

/** 三张卡共用一个壳，只有正文分叉：标题、徽章（色调）、主按钮在这儿一次说清（存 key，渲染时翻） */
const META: Record<TraderFormKind, { titleKey: string; badgeKey: string; tone: string; actionKey: string }> = {
  note: { titleKey: 'card.noteTitle', badgeKey: 'card.noteBadge', tone: '', actionKey: 'card.noteAction' },
  wake: { titleKey: 'term.manualWake', badgeKey: 'card.wakeBadge', tone: 'w', actionKey: 'card.wakeAction' },
  review: { titleKey: 'term.reviewNow', badgeKey: 'card.reviewBadge', tone: '', actionKey: 'card.reviewAction' },
};

/**
 * 相对时间。面板里「下次例行」是未来时刻，全局 fmtRelative 只算过去（未来会被压成"刚刚"），
 * 这儿按绝对差值算，方向决定用"之后"还是"之前"那组词条。
 * 词表在函数体里现查（不存模块级常量），过去/未来各一组整句——
 * 中文是"N分钟后"、英文是"in N minutes"，前后缀位置不同，拼不出来。
 */
function relTime(ms: number): string {
  const diff = ms - Date.now();
  const abs = Math.abs(diff);
  const future = diff > 0;
  if (abs < 60_000) return i18n.t('ai:card.justNow');
  if (abs < 3_600_000) {
    return i18n.t(future ? 'ai:card.inMinutes' : 'ai:card.agoMinutes', { count: Math.floor(abs / 60_000) });
  }
  if (abs < 86_400_000) {
    return i18n.t(future ? 'ai:card.inHours' : 'ai:card.agoHours', { count: Math.floor(abs / 3_600_000) });
  }
  return i18n.t(future ? 'ai:card.inDays' : 'ai:card.agoDays', { count: Math.floor(abs / 86_400_000) });
}

/** "3分钟前 · 14:23"：相对量判新鲜度，时刻用来对表；null 一律破折号 */
function stamp(ms: number | null): string {
  if (ms == null) return '—';
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${relTime(ms)} · ${hh}:${mm}`;
}

interface TraderFormCardProps {
  form: TraderFormKind;
  /** 模型草拟的初值：留言卡取 note(string) 与 rounds(number)，缺什么退回默认 */
  prefill?: Record<string, unknown>;
  status: 'pending' | 'done';
  /** status==='done' 时显示的落地文案 */
  result?: string;
  /** 提交完成后把 message 回传，宿主据此把卡置为 done */
  onSettle: (result: string) => void;
  onCancel: () => void;
}

/**
 * trader 动作表单卡（样式见 ChatMessages.css .ccard）：模型只有开卡的权限，真正执行由用户在卡上点按钮触发。
 * 三张卡（留言/唤醒/复盘）的可点条件全看挂载时拉到的 actionPanel——
 * 面板没拿到就一律禁用主按钮，宁可点不动，也不能瞎发真实交易请求。
 */
export function TraderFormCard({ form, prefill, status, result, onSettle, onCancel }: TraderFormCardProps): ReactElement {
  // 订阅词表：卡里的 relTime 是本地那份相对时间，切语言要跟着刷新
  const { t } = useTranslation(['ai', 'common']);
  const [panel, setPanel] = useState<TraderActionPanel | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  // 哪个按钮在提交：留言卡有"留言"和"撤回"两个入口，转圈要落在按下的那个上
  const [busy, setBusy] = useState<'main' | 'clear' | null>(null);
  /** 这次没办成的原因，就地显示；办成了就落地成 result，不走这儿 */
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState(() => (typeof prefill?.note === 'string' ? prefill.note : ''));
  const [rounds, setRounds] = useState(() => (typeof prefill?.rounds === 'number' ? prefill.rounds : 1));

  const done = status === 'done';

  // 已落地的卡只是历史留痕，不必再打接口
  useEffect(() => {
    if (done) return;
    let alive = true;
    traderApi.actionPanel()
      .then(p => { if (alive) setPanel(p); })
      .catch(() => { if (alive) setLoadFailed(true); });
    return () => { alive = false; };
  }, [done]);

  /**
   * 提交统一出口：只有真办成了才落地这张卡。
   * 业务拒绝（trader 暂停中、上次复盘还在跑…）和网络失败都就地显示、卡留着可编辑——
   * 落地是单向的，卡一关草稿跟着没，而这些拒绝多半去处理一下就能重试。
   */
  const submit = (tag: 'main' | 'clear', call: () => Promise<TraderActionResult>) => {
    setBusy(tag);
    setError(null);
    call()
      .then(res => (res.ok ? onSettle(res.message) : setError(res.message)))
      .catch((err: Error) => setError(err?.message || t('card.actionFailed')))
      .finally(() => setBusy(null));
  };

  const meta = META[form];

  if (done) {
    // 没有 result 的 done 只有一个来路：用户点了取消（宿主直接关卡，不带落地文案）
    return (
      <div className="ccard">
        <h4>{t(meta.titleKey)}</h4>
        <div className="done">{result ? <Check className="ic" /> : <X className="ic" />}{result || t('card.cancelled')}</div>
      </div>
    );
  }

  const submitting = busy !== null;
  const hint = loadFailed ? t('card.loadFailed') : t('card.loading');
  // 还没建 trader：只说一句、只给取消
  const noTrader = panel != null && !panel.hasTrader;
  const hasNote = !!panel?.note;
  // 轮次在前端也钳一道，纯粹为了让"接下来 N 次"这句不说假话；后端一样会钳。面板没到时上限未知，先原样显示
  const maxRounds = panel?.noteMaxRounds ?? Math.max(1, rounds);
  const roundsShown = Math.min(Math.max(1, rounds), maxRounds);
  const overChars = panel != null && note.length > panel.noteMaxChars;
  // 主按钮：能不能点、点了发什么
  const main = {
    note: { ok: !overChars && !!note.trim(), call: () => traderApi.saveNote(note.trim(), roundsShown) },
    wake: { ok: !panel?.wakeBlockedReason, call: () => traderApi.wake() },
    review: { ok: !panel?.reviewBlockedReason, call: () => traderApi.review() },
  }[form];
  const lastReview = panel
    ? (panel.lastReviewAt != null ? relTime(panel.lastReviewAt) : '—')
      + (panel.lastReviewStatus === 'ERROR' ? t('card.lastFailed') : '')
    : hint;

  return (
    <div className="ccard">
      <h4>{t(meta.titleKey)}<span className={cn('tag sm', meta.tone)}>{t(meta.badgeKey)}</span></h4>
      {noTrader ? <p>{t('card.noTrader')}</p> : form === 'note' ? (
        <>
          {hasNote && (
            <p>
              <Trans ns="ai" i18nKey="card.noteLeft" values={{ rounds: panel?.noteRounds, note: panel?.note }} components={[<b />]} />
            </p>
          )}
          <label className="inp ta">
            <textarea rows={2} value={note} onChange={e => setNote(e.target.value)}
                      placeholder={t('card.notePh')} aria-label={t('card.noteLabel')} />
            <span className={cn('cnt', overChars && 'over')}>{note.length}/{panel?.noteMaxChars ?? '—'}</span>
          </label>
          <div className="rounds">
            <span>{t('card.roundsLabel')}</span>
            <Stepper value={roundsShown} onChange={setRounds} min={1} max={maxRounds} disabled={!panel}
                     label={t('card.roundsLabel')} lessLabel={t('my.note.less')} moreLabel={t('my.note.more')} />
            <span>{t('card.roundsHint', { count: roundsShown })}</span>
          </div>
        </>
      ) : form === 'wake' ? (
        <>
          <div className="kv">
            <div><span>{t('card.lastWake')}</span><b>{panel ? stamp(panel.lastWakeAt) : hint}</b></div>
            <div><span>{t('card.nextWake')}</span><b>{panel ? stamp(panel.nextWakeAt) : hint}</b></div>
          </div>
          <p>{t('card.wakeHint')}</p>
          {panel?.wakeBlockedReason && <p className="err">{panel.wakeBlockedReason}</p>}
        </>
      ) : (
        <>
          <div className="kv">
            <div><span>{t('card.lastReview')}</span><b>{lastReview}</b></div>
            <div><span>{t('card.material')}</span><b>{panel ? (panel.hasReviewMaterial ? t('card.yes') : t('card.no')) : hint}</b></div>
          </div>
          {/* 无新素材不禁用按钮：后端会直接跳过，既免费又安全，没必要拦人 */}
          {panel && !panel.hasReviewMaterial && <p>{t('card.skipHint')}</p>}
          <p>{t('card.reviewAdvice')}</p>
          {panel?.reviewBlockedReason && <p className="err">{panel.reviewBlockedReason}</p>}
        </>
      )}
      {error && <p className="err">{error}</p>}
      <div className="f">
        {/* 撤回只在真有未读留言时露出：没留言可撤，按钮点了也是空操作 */}
        {form === 'note' && hasNote && !noTrader && (
          <button type="button" className={cn('btn plain', busy === 'clear' && 'busy')} disabled={submitting}
                  onClick={() => submit('clear', () => traderApi.clearNote())}>
            <span>{t('card.clear')}</span>
          </button>
        )}
        <span className="sp" />
        <button type="button" className="btn" disabled={submitting} onClick={onCancel}>{t('common:cancel')}</button>
        {!noTrader && (
          <button type="button" className={cn('btn ink', busy === 'main' && 'busy')} disabled={submitting || !panel || !main.ok}
                  onClick={() => submit('main', main.call)}>
            <span>{t(meta.actionKey)}</span>
          </button>
        )}
      </div>
    </div>
  );
}
