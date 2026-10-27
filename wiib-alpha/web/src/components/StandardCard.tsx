import { Fragment, useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Play, Save, TriangleAlert } from 'lucide-react';
import { alpha, type Busy, type Quantiles, type StandardContent, type StandardView } from '../api';
import { cx, localTime, num, utcDay } from '../format';
import { usePoll } from '../poll';
import { useFail } from '../toast';
import { Badge, Button, Card, More, Progress, Spinner, Waiting } from './ui';

const GROUPS = ['params', 'thresholds', 'scheme', 'reference'] as const;

/** 口径内容里一项的显示：2022 起那项是毫秒，别的按原样 */
const show = (key: string, v: unknown) => (key === 'recent_from' ? utcDay(v as number) : Array.isArray(v) ? v.join(' / ') : String(v));

/** 增量带正负号，三位小数 */
const gain = (v: number | null | undefined) => (v == null ? num(null) : `${v > 0 ? '+' : ''}${v.toFixed(3)}`);

/** 评估口径：当前第几版、哪些数字是旧版算的、G9 标定还作不作数、按一下重标；G9 的线在这里改（改了就是新的一版），别的参数还得改代码。
 *  busy 是各动作现在做不了的原因；切回这一页时重拉，别的页签复评、入库、改份额过的能看到 */
export function StandardCard({ busy, active }: { busy: Busy | null; active: boolean }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [view, setView] = useState<StandardView | null>(null);
  const [randoms, setRandoms] = useState('300');
  const [starting, setStarting] = useState(false);
  const blocked = busy?.calibrate;

  const load = useCallback(() => alpha.standard().then(setView).catch(fail), [fail]);
  useEffect(() => {
    if (active) void load();
  }, [active, load]);
  const running = view?.job.running ?? false;
  usePoll(running, load, 3000);

  const start = async () => {
    setStarting(true);
    try {
      await alpha.calibrate(Number(randoms));
      await load();
    } catch (err) {
      fail(err);
    } finally {
      setStarting(false);
    }
  };

  if (!view) return <Card title={t('standard.title')} hint={t('standard.hint')}><Spinner /></Card>;
  const { stale, calibration: cal, job } = view;
  // 上次标定之后变了的东西：空就还作数
  const changed = cal ? (['standard', 'data', 'library'] as const).filter(k => !cal.same[k]) : [];
  return (
    <Card title={t('standard.title')} hint={t('standard.hint')} more={t('standard.more')}>
      <div className="space-y-4">
        <div className="space-y-1.5 border-t border-fg pt-2 text-[13px]">
          <p>{t('standard.summary', { id: view.id, time: localTime(view.createdAt), ids: view.history.map(h => `#${h.id}`).join(' ') })}</p>
          {stale.library || stale.observing ? (
            <p className="flex items-start gap-1.5 text-warn">
              <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('standard.stale', stale)}
            </p>
          ) : <p className="flex items-center gap-1.5 text-muted"><Check className="w-3.5 h-3.5 text-gain" />{t('standard.fresh')}</p>}
        </div>
        <More label={t('standard.content')}><ContentTable content={view.content} /></More>
        <section className="space-y-2 border-t border-line pt-3">
          <div className="microlabel">{t('standard.calibration')}</div>
          <p className="text-[12px] text-muted">{t('standard.calibrationHint')}</p>
          {!cal ? <p className="text-[12.5px] text-muted">{t('standard.none')}</p> : (
            <div className="space-y-1 text-[12.5px]">
              <p className="num">
                {t('standard.last', { time: localTime(cal.createdAt), id: cal.standardId, cut: utcDay(cal.cutMs),
                  library: cal.library.map(x => x.name).join(', ') || t('standard.emptyLibrary'), randoms: cal.randoms })}
              </p>
              <Quantile label={t('standard.random')} q={cal.result.random} from={cal.result.base.recentFrom} />
              {cal.result.candidates.n > 0
                ? <Quantile label={t('standard.candidates')} q={cal.result.candidates} from={cal.result.base.recentFrom} />
                : <p className="text-muted">{t('standard.noCandidates')}</p>}
              <div>
                {changed.length === 0
                  ? <Badge tone="gain" icon={Check}>{t('standard.valid')}</Badge>
                  : <Badge tone="warn" icon={TriangleAlert}>{t('standard.outdated', { what: changed.map(k => t(`standard.changed.${k}`)).join(t('common.sep')) })}</Badge>}
              </div>
            </div>
          )}
          <div className="flex flex-wrap items-center gap-2 text-[12.5px]">
            <span className="text-muted">{t('standard.randoms')}</span>
            <input type="number" min={1} max={2000} step={1} value={randoms} onChange={e => setRandoms(e.target.value)}
                   disabled={running} className="input w-24 text-right font-mono" />
            <Button icon={Play} loading={starting || running} disabled={!!blocked || !(Number(randoms) >= 1)}
                    onClick={() => void start()}>
              {running ? t('standard.running') : t('standard.run')}
            </Button>
            {running && job.total != null && <span className="num text-muted">{t('standard.progress', { done: job.done ?? 0, total: job.total })}</span>}
          </div>
          {running && <Progress done={job.done ?? 0} total={job.total ?? 0} />}
          {!running && <Waiting why={blocked} />}
          {!running && job.error && (
            <p className="flex items-start gap-1.5 text-[13px] text-loss break-words">
              <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('standard.error', { msg: job.error })}
            </p>
          )}
        </section>
        <LineEditor key={view.id} view={view} valid={!!cal && changed.length === 0} blocked={busy?.standard} onSaved={setView} />
      </div>
    </Card>
  );
}

/** 一组的 95 分位：2022 起（G9 判这份）和全段 */
function Quantile({ label, q, from }: { label: string; q: { recent: Quantiles | null; full: Quantiles | null; n: number }; from: string }) {
  const { t } = useTranslation();
  return (
    <p className="num">
      {t('standard.quantiles', { label, n: q.n, recent: gain(q.recent?.p95), full: gain(q.full?.p95), from: from.slice(0, 4) })}
    </p>
  );
}

/** G9 的线：填一个数保存，或标定作数时一键采用随机因子 2022 起增量的 95 分位；存完口径换成新的一版。
 *  blocked 是现在改不了的原因 */
function LineEditor({ view, valid, blocked, onSaved }: {
  view: StandardView;
  valid: boolean;
  blocked: string | null | undefined;
  onSaved: (v: StandardView) => void;
}) {
  const { t } = useTranslation();
  const fail = useFail();
  const line = view.content.thresholds.sharpe_gain_min;
  const [value, setValue] = useState(String(line));
  const [saving, setSaving] = useState(false);
  // 随机因子 2022 起增量的 95 分位，三位小数；没标过或标定不作数就没有
  const recent = valid ? view.calibration?.result.random.recent : null;
  const p95 = recent ? Number(recent.p95.toFixed(3)) : null;

  const save = async (v: number) => {
    setSaving(true);
    try {
      onSaved(await alpha.saveStandard({ sharpeGainMin: v }));
    } catch (err) {
      fail(err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="space-y-2 border-t border-line pt-3">
      <div className="microlabel">{t('standard.line')}</div>
      <p className="text-[12px] text-muted">{t('standard.lineHint')}</p>
      <div className="flex flex-wrap items-center gap-2 text-[12.5px]">
        <span className="text-muted">{t('standard.lineNow', { line })}</span>
        <input type="number" min={0} max={1} step={0.001} value={value} onChange={e => setValue(e.target.value)}
               disabled={saving} className="input w-24 text-right font-mono" />
        <Button icon={Save} loading={saving} disabled={!!blocked || value === '' || Number(value) === line}
                onClick={() => void save(Number(value))}>
          {t('standard.lineSave')}
        </Button>
        {p95 != null && (
          <Button variant="primary" loading={saving} disabled={!!blocked || p95 === line} onClick={() => void save(p95)}>
            {t('standard.adopt', { p95: gain(p95) })}
          </Button>
        )}
      </div>
      <Waiting why={blocked} />
    </section>
  );
}

/** 口径的全部内容，按四组列出来 */
function ContentTable({ content }: { content: StandardContent }) {
  const { t } = useTranslation();
  return (
    <div className="grid gap-x-10 gap-y-4 pt-2 lg:grid-cols-2">
      {GROUPS.map(g => (
        <div key={g}>
          <div className="microlabel">{t(`standard.group.${g}`)}</div>
          <dl className="mt-1.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-[12.5px]">
            {Object.entries(content[g]).map(([k, v]) => (
              <Fragment key={k}>
                <dt className="font-mono text-fg">{k}</dt>
                <dd className={cx('num break-all', typeof v === 'string' && 'font-mono')}>{show(k, v)}</dd>
              </Fragment>
            ))}
          </dl>
        </div>
      ))}
    </div>
  );
}
