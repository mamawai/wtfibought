import { useCallback, useEffect, useState, type ReactElement } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Check, X } from 'lucide-react';
import { traderApi } from '../../api';
import i18n from '../../i18n';
import { cn } from '../../lib/utils';
import { Stepper } from '../Stepper';
import { TraderChooser, type ChooserRow } from './TraderPick';
import type { TraderActionPanel, TraderActionResult, TraderFormKind, TraderOwnerView } from '../../types';

/** 三张卡共用一个壳，只有正文分叉：标题、徽章（色调）、主按钮在这儿一次说清（存 key，渲染时翻） */
const META: Record<TraderFormKind, { titleKey: string; badgeKey: string; tone: string; actionKey: string }> = {
  note: { titleKey: 'card.noteTitle', badgeKey: 'card.noteBadge', tone: '', actionKey: 'card.noteAction' },
  wake: { titleKey: 'term.manualWake', badgeKey: 'card.wakeBadge', tone: 'w', actionKey: 'card.wakeAction' },
  review: { titleKey: 'term.reviewNow', badgeKey: 'card.reviewBadge', tone: '', actionKey: 'card.reviewAction' },
};

/** 选择的标题：主按钮按卡分，撤回留言单独一句（存 key） */
const CHOOSE_KEY: Record<TraderFormKind | 'clear', string> = {
  note: 'card.chooseNote', wake: 'card.chooseWake', review: 'card.chooseReview', clear: 'card.chooseClear',
};

/** 多个 trader 时选择最多开多久，与对话里选 trader 的卡同一个数 */
const CHOOSE_MS = 30_000;

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
  /** 模型草拟的初值：留言卡取 note(string) 与 rounds(number)，三张卡都可能带 trader(点了名的那个)，缺什么退回默认 */
  prefill?: Record<string, unknown>;
  status: 'pending' | 'done';
  /** status==='done' 时显示的落地文案 */
  result?: string;
  /** 提交完成后把 message 回传，宿主据此把卡置为 done */
  onSettle: (result: string) => void;
  onCancel: () => void;
}

/** 两个按钮：主按钮，和留言卡的撤回 */
type Tag = 'main' | 'clear';

/**
 * trader 动作表单卡（样式见 ChatMessages.css .ccard）：模型只有开卡的权限，真正执行由用户在卡上点按钮触发。
 * 挂载时先拉名下的 trader：
 * <ul>
 *   <li>一个都没有：只说一句、只给取消；</li>
 *   <li>只有一个：卡上直接显示它的面板（上次唤醒、拦因、剩余留言），可点条件全看面板——
 *       面板没拿到就禁用主按钮，宁可点不动，也不能瞎发真实交易请求；</li>
 *   <li>有几个：卡上只有通用说明，按下按钮再弹出选择，每行显示那个 trader 自己的状态，30 秒没选就收起、这次不执行。</li>
 * </ul>
 */
export function TraderFormCard({ form, prefill, status, result, onSettle, onCancel }: TraderFormCardProps): ReactElement {
  // 订阅词表：卡里的 relTime 是本地那份相对时间，切语言要跟着刷新
  const { t } = useTranslation(['ai', 'common']);
  /** 名下的 trader，null=还在取 */
  const [mine, setMine] = useState<TraderOwnerView[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  /** 每个 trader 的面板，按 id；取不到记 failed */
  const [panels, setPanels] = useState<Record<number, TraderActionPanel | 'failed'>>({});
  // 哪个按钮在提交：留言卡有"留言"和"撤回"两个入口，转圈要落在按下的那个上
  const [busy, setBusy] = useState<Tag | null>(null);
  /** 这次没办成的原因，就地显示；办成了就落地成 result，不走这儿 */
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState(() => (typeof prefill?.note === 'string' ? prefill.note : ''));
  const [rounds, setRounds] = useState(() => (typeof prefill?.rounds === 'number' ? prefill.rounds : 1));
  /** 有几个 trader 时正在选：为哪个按钮选、几点截止 */
  const [choosing, setChoosing] = useState<{ tag: Tag; deadline: number } | null>(null);

  const done = status === 'done';

  /** 逐个取面板，回来一个填一个 */
  const loadPanels = useCallback((list: TraderOwnerView[]) => {
    for (const tr of list) {
      const id = tr.pub.id;
      traderApi.actionPanel(id)
        .then(p => setPanels(prev => ({ ...prev, [id]: p })))
        .catch(() => setPanels(prev => ({ ...prev, [id]: 'failed' })));
    }
  }, []);

  // 已落地的卡只是历史留痕，不必再打接口。
  // 先只取第一个的面板：只有一个时就是它；有几个时只为拿留言字数和轮次上限，各行的状态弹选择时再取
  useEffect(() => {
    if (done) return;
    let alive = true;
    traderApi.mine()
      .then(list => {
        if (!alive) return;
        setMine(list);
        loadPanels(list.slice(0, 1));
      })
      .catch(() => { if (alive) setLoadFailed(true); });
    return () => { alive = false; };
  }, [done, loadPanels]);

  // 选择到点收起，这次不执行
  useEffect(() => {
    if (!choosing) return;
    const id = window.setTimeout(() => setChoosing(null), choosing.deadline - Date.now());
    return () => window.clearTimeout(id);
  }, [choosing]);

  /**
   * 提交统一出口：只有真办成了才落地这张卡。
   * 业务拒绝（trader 暂停中、上次复盘还在跑…）和网络失败都就地显示、卡留着可编辑——
   * 落地是单向的，卡一关草稿跟着没，而这些拒绝多半去处理一下就能重试。
   */
  const submit = (tag: Tag, call: () => Promise<TraderActionResult>, settled: (message: string) => string) => {
    setBusy(tag);
    setError(null);
    call()
      .then(res => (res.ok ? onSettle(settled(res.message)) : setError(res.message)))
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

  const list = mine ?? [];
  const noTrader = mine != null && list.length === 0;
  const multi = list.length > 1;
  /** 只有一个时就是它 */
  const only = list.length === 1 ? list[0] : null;
  const onlyPanel = only ? panels[only.pub.id] : undefined;
  /** 只有一个时它的面板；有几个时卡上不显示哪一个的面板 */
  const panel = onlyPanel === 'failed' ? undefined : onlyPanel;
  /** 留言字数、轮次上限：各个 trader 一样，取到哪个用哪个 */
  const limits = Object.values(panels).find((p): p is TraderActionPanel => p !== 'failed');
  const hint = loadFailed || onlyPanel === 'failed' ? t('card.loadFailed') : t('card.loading');
  // 撤回露不露：一个看它有没有未读留言；几个时总露着，没留言的那几行在选择里点不了
  const hasNote = multi || !!panel?.note;
  // 轮次在前端也钳一道，纯粹为了让"接下来 N 次"这句不说假话；后端一样会钳。上限没到时先原样显示
  const maxRounds = limits?.noteMaxRounds ?? Math.max(1, rounds);
  const roundsShown = Math.min(Math.max(1, rounds), maxRounds);
  const overChars = limits != null && note.length > limits.noteMaxChars;
  // 主按钮能不能点：只有一个要等它的面板、没被拦；有几个时被拦不拦看选择里那一行
  const ok = {
    note: !overChars && !!note.trim(),
    wake: !panel?.wakeBlockedReason,
    review: !panel?.reviewBlockedReason,
  }[form];
  const mainReady = multi ? ok : panel != null && ok;
  const lastReview = panel
    ? (panel.lastReviewAt != null ? relTime(panel.lastReviewAt) : '—')
      + (panel.lastReviewStatus === 'ERROR' ? t('card.lastFailed') : '')
    : hint;

  /** 对这个 trader 真动手 */
  const run = (tag: Tag, id: number): Promise<TraderActionResult> => {
    if (tag === 'clear') return traderApi.clearNote(id);
    return {
      note: () => traderApi.saveNote(id, note.trim(), roundsShown),
      wake: () => traderApi.wake(id),
      review: () => traderApi.review(id),
    }[form]();
  };

  /** 按下按钮：只有一个直接办；有几个先弹选择，每行的状态现取 */
  const act = (tag: Tag) => {
    if (only) {
      submit(tag, () => run(tag, only.pub.id), m => m);
      return;
    }
    setChoosing({ tag, deadline: Date.now() + CHOOSE_MS });
    loadPanels(list);
  };

  /** 选择里点了一个：收起选择再办，落地文案带上是哪个 */
  const pick = (tag: Tag, id: number) => {
    const name = list.find(tr => tr.pub.id === id)?.pub.name;
    setChoosing(null);
    submit(tag, () => run(tag, id), message => t('card.doneFor', { name, message }));
  };

  /** 选择里的一行：被拦的显示拦因；留言提交会覆盖未读的那条要说清 */
  const row = (tag: Tag, tr: TraderOwnerView): ChooserRow => {
    const p = panels[tr.pub.id];
    const base = { id: tr.pub.id, name: tr.pub.name, status: p && p !== 'failed' ? p.status : tr.pub.status };
    if (p === undefined) return { ...base, loading: true };
    if (p === 'failed') return { ...base, blocked: t('card.loadFailed') };
    if (tag === 'clear') {
      return p.note
        ? { ...base, detail: t('card.noteOf', { rounds: p.noteRounds, note: p.note }) }
        : { ...base, blocked: t('card.noNote') };
    }
    return {
      note: { ...base, detail: p.note ? <span className="wn">{t('card.noteOverwrite', { rounds: p.noteRounds })}</span> : t('card.noNote') },
      wake: {
        ...base, blocked: p.wakeBlockedReason,
        detail: t('card.lastWakeAgo', { when: p.lastWakeAt != null ? relTime(p.lastWakeAt) : '—' }),
      },
      review: {
        ...base, blocked: p.reviewBlockedReason,
        detail: t('card.lastReviewAgo', { when: p.lastReviewAt != null ? relTime(p.lastReviewAt) : '—' })
          + (p.hasReviewMaterial ? '' : ` · ${t('card.noMaterialShort')}`),
      },
    }[form];
  };

  const submitting = busy !== null;

  return (
    <div className="ccard">
      <h4>{t(meta.titleKey)}<span className={cn('tag sm', meta.tone)}>{t(meta.badgeKey)}</span></h4>
      {noTrader ? <p>{t('card.noTrader')}</p> : form === 'note' ? (
        <>
          {!multi && panel?.note && (
            <p>
              <Trans ns="ai" i18nKey="card.noteLeft" values={{ rounds: panel.noteRounds, note: panel.note }} components={[<b />]} />
            </p>
          )}
          <label className="inp ta">
            <textarea rows={2} value={note} onChange={e => setNote(e.target.value)}
                      placeholder={t('card.notePh')} aria-label={t('card.noteLabel')} />
            <span className={cn('cnt', overChars && 'over')}>{note.length}/{limits?.noteMaxChars ?? '—'}</span>
          </label>
          <div className="rounds">
            <span>{t('card.roundsLabel')}</span>
            <Stepper value={roundsShown} onChange={setRounds} min={1} max={maxRounds} disabled={!limits}
                     label={t('card.roundsLabel')} lessLabel={t('my.note.less')} moreLabel={t('my.note.more')} />
            <span>{t('card.roundsHint', { count: roundsShown })}</span>
          </div>
          {multi && <p>{t('card.multiHint')}</p>}
        </>
      ) : form === 'wake' ? (
        <>
          {!multi && (
            <div className="kv">
              <div><span>{t('card.lastWake')}</span><b>{panel ? stamp(panel.lastWakeAt) : hint}</b></div>
              <div><span>{t('card.nextWake')}</span><b>{panel ? stamp(panel.nextWakeAt) : hint}</b></div>
            </div>
          )}
          <p>{t('card.wakeHint')}</p>
          {multi && <p>{t('card.multiHint')}</p>}
          {panel?.wakeBlockedReason && <p className="err">{panel.wakeBlockedReason}</p>}
        </>
      ) : (
        <>
          {!multi && (
            <div className="kv">
              <div><span>{t('card.lastReview')}</span><b>{lastReview}</b></div>
              <div><span>{t('card.material')}</span><b>{panel ? (panel.hasReviewMaterial ? t('card.yes') : t('card.no')) : hint}</b></div>
            </div>
          )}
          {/* 无新素材不禁用按钮：后端会直接跳过，既免费又安全，没必要拦人 */}
          {panel && !panel.hasReviewMaterial && <p>{t('card.skipHint')}</p>}
          <p>{t('card.reviewAdvice')}</p>
          {multi && <p>{t('card.multiHint')}</p>}
          {panel?.reviewBlockedReason && <p className="err">{panel.reviewBlockedReason}</p>}
        </>
      )}
      {choosing && (
        <TraderChooser
          title={t(CHOOSE_KEY[choosing.tag === 'clear' ? 'clear' : form])}
          rows={list.map(tr => row(choosing.tag, tr))}
          deadline={choosing.deadline}
          highlight={typeof prefill?.trader === 'string' ? prefill.trader : undefined}
          onPick={id => pick(choosing.tag, id)}
          onClose={() => setChoosing(null)}
        />
      )}
      {error && <p className="err">{error}</p>}
      <div className="f">
        {/* 撤回：只有一个时它有未读留言才露出；几个时总露着，选的时候再看哪个有 */}
        {form === 'note' && hasNote && !choosing && (
          <button type="button" className={cn('btn plain', busy === 'clear' && 'busy')} disabled={submitting}
                  onClick={() => act('clear')}>
            <span>{t('card.clear')}</span>
          </button>
        )}
        <span className="sp" />
        <button type="button" className="btn" disabled={submitting} onClick={onCancel}>{t('common:cancel')}</button>
        {!noTrader && !choosing && (
          <button type="button" className={cn('btn ink', busy === 'main' && 'busy')} disabled={submitting || !mainReady}
                  onClick={() => act('main')}>
            <span>{t(meta.actionKey)}</span>
          </button>
        )}
      </div>
    </div>
  );
}
