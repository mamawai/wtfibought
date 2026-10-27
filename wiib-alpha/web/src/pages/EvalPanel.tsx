import { Fragment, useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Play, TriangleAlert } from 'lucide-react';
import { alpha, type DslReference, type EvalJob } from '../api';
import { usePoll } from '../poll';
import { useFail } from '../toast';
import { FactorCard, PassBadge } from '../components/FactorCard';
import { Button, Card, More, RawJson, Spinner, Tip } from '../components/ui';

/** 管理员的手填评估：后台跑，跑着就轮询；输入框底下折着能用的列和算子 */
export function EvalPanel({ loaded }: { loaded: boolean }) {
  const { t } = useTranslation();
  const fail = useFail();
  const [expr, setExpr] = useState('');
  const [job, setJob] = useState<EvalJob | null>(null);
  const [busy, setBusy] = useState(false);
  const [ref, setRef] = useState<DslReference | null>(null);

  const load = useCallback(() => alpha.evalJob().then(setJob).catch(fail), [fail]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { alpha.dsl().then(setRef).catch(fail); }, [fail]);

  const running = job?.running ?? false;
  usePoll(running, load, 2000);

  const run = async () => {
    setBusy(true);
    try {
      await alpha.evaluate(expr.trim());
      await load();
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  };

  const ready = loaded && expr.trim() !== '' && !busy && !running;
  const result = running ? null : job?.result;

  return (
    <div className="space-y-14">
      <Card title={t('eval.title')} hint={t('eval.hint')}>
        <div className="space-y-3">
          <textarea value={expr} onChange={e => setExpr(e.target.value)} placeholder={t('eval.ph')} rows={3}
                    onKeyDown={e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && ready) void run(); }}
                    className="input h-auto w-full py-2.5 font-mono text-[13px] leading-relaxed resize-y" />
          <div className="flex items-center gap-3">
            {!loaded && <span className="text-[12px] text-warn">{t('common.needData')}</span>}
            {running && (
              <span className="flex items-center gap-2 min-w-0 text-[12.5px] text-muted">
                <Spinner /><span className="truncate">{t(`eval.${job?.stage ?? 'queued'}`, { expr: job?.expr })}</span>
              </span>
            )}
            <Button variant="primary" icon={Play} loading={busy || running} disabled={!ready} onClick={() => void run()}
                    className="ml-auto">
              {t('eval.run')}
            </Button>
          </div>
          {!running && job?.error && (
            <p className="flex items-start gap-1.5 text-[13px] text-loss break-words">
              <TriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />{t('eval.error', { msg: job.error })}
            </p>
          )}
          {ref && (
            <More label={t('eval.refTitle')}>
              <p>{t('eval.refSyntax', { max: ref.maxWindow, windows: ref.windows, nodes: ref.complexity.nodes,
                w: ref.complexity.windows, c: ref.complexity.constants, cross: ref.complexity.cross })}</p>
              <div className="grid gap-x-10 gap-y-4 pt-3 lg:grid-cols-2">
                <RefList title={t('eval.refColumns')} items={ref.columns} mark="$" />
                <RefList title={t('eval.refOperators')} items={ref.operators} />
              </div>
            </More>
          )}
        </div>
      </Card>

      {result && (
        <FactorCard title={<code>{result.expr}</code>} badges={<PassBadge passed={result.passed} />}
                    gates={result.gates} brief={result.brief}>
          <p className="text-[12.5px] text-muted break-all">
            {t('eval.canonical')}<Tip text={t('library.canonicalTip')} />：<code>{result.canonical}</code>
          </p>
          <RawJson value={result.eval} />
        </FactorCard>
      )}
    </div>
  );
}

/** 参考里的一栏：名字和一句说明 */
function RefList({ title, items, mark = '' }: { title: string; items: { name: string; doc: string }[]; mark?: string }) {
  return (
    <div>
      <div className="microlabel">{title}</div>
      <dl className="mt-1.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-[12.5px]">
        {items.map(x => (
          <Fragment key={x.name}>
            <dt className="font-mono text-fg">{mark}{x.name}</dt>
            <dd>{x.doc}</dd>
          </Fragment>
        ))}
      </dl>
    </div>
  );
}
