import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { BookOpen, Bot, ChevronDown, ChevronLeft, Database, GraduationCap, Loader2, Pause, Play, RotateCcw, Save, Trash2, Wrench } from 'lucide-react';
import { llmEndpointApi, traderApi } from '../api';
import { DATA_TOOLS, TRADE_TOOLS, toolName } from '../components/arena/traderTools';
import { GuidedTour, type TourStep } from '../components/GuidedTour';
import { LlmEndpointSelect } from '../components/LlmEndpointSelect';
import { useToast } from '../components/ui/use-toast';
import { cn, fmtNum } from '../lib/utils';
import type { LlmEndpointView, TraderOwnerView, TraderSpec, TraderUpsertRequest } from '../types';

const TOUR_SEEN_KEY = 'wiib-trader-tour-seen';

const SYMBOL_OPTIONS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'DOGEUSDT', 'XRPUSDT'];
/** 币种上限：一次唤醒的工具预算与注意力按币摊——与后端 TraderService.MAX_SYMBOLS 同一个数 */
const MAX_SYMBOLS = 3;
const INTERVAL_OPTIONS = ['5m', '15m', '1h', '4h'];
/** 波动哨兵每币基准阈值%（平台下限，只能经系数调高）——与后端 VolatilitySentinel 同一份数字 */
const ALERT_BASE: Record<string, number> = { BTCUSDT: 0.6, ETHUSDT: 0.8, XRPUSDT: 0.8, SOLUSDT: 0.9, DOGEUSDT: 1.0 };

/** 相关 skills 卡的两组：交易动作会动账本，行情数据只读；名字与时间线共用 toolName，说明走 skills.desc.* 词表 */
const SKILL_GROUPS = [
  { key: 'trade', icon: Wrench, tools: TRADE_TOOLS },
  { key: 'data', icon: Database, tools: DATA_TOOLS },
] as const;

const DEFAULT_SPEC: TraderSpec = {
  leverageMin: 3, leverageMax: 20, marginPctMin: 5, marginPctMax: 20,
  allowMultiPosition: true, allowHedge: false,
};

// 默认 15m 起步：5m 高频唤醒对"LLM+双边taker手续费"是绞肉机，保留仅为短期测试观察
const EMPTY_FORM: TraderUpsertRequest = {
  name: '', symbols: 'BTCUSDT', intervalCode: '15m', customPrompt: '',
  llmEndpointId: null,
  spec: DEFAULT_SPEC, alertEnabled: true, alertThresholdMult: 1, reviewEnabled: true, learningEnabled: true,
  wakeWindow: null,
};

/**
 * 我的 Trader：创建/配置（模型从 AI 页「模型配置」的端点库里选，不在这里填 key）+ 启停/重置。
 * 提示词存库即热生效——改完下一根K线自然按新提示词决策。
 */
export function MyTrader() {
  const { toast } = useToast();
  const { t } = useTranslation(['ai', 'common']);
  /**
   * 配置引导：讲清楚"这是什么 + 配大配小会怎样 + 和别的字段怎么互相咬"。
   * 必须 memo 住：GuidedTour 的定位 effect 认 step 对象身份，每次渲染新建数组会让它反复重算。
   * 依赖 t——切语言时它换身份，引导文案跟着重建。
   */
  const tourSteps = useMemo<TourStep[]>(() => [
    { target: 'name', title: t('tour.name.title'), body: t('tour.name.body') },
    { target: 'interval', title: t('tour.interval.title'), body: t('tour.interval.body') },
    { target: 'wake-window', title: t('tour.wakeWindow.title'), body: t('tour.wakeWindow.body') },
    { target: 'symbols', title: t('tour.symbols.title'), body: t('tour.symbols.body') },
    { target: 'leverage', title: t('tour.leverage.title'), body: t('tour.leverage.body') },
    { target: 'margin', title: t('tour.margin.title'), body: t('tour.margin.body') },
    { target: 'position-rules', title: t('tour.positionRules.title'), body: t('tour.positionRules.body') },
    { target: 'byok', title: t('tour.byok.title'), body: t('tour.byok.body') },
    { target: 'prompt', title: t('tour.prompt.title'), body: t('tour.prompt.body') },
    { target: 'save', title: t('tour.save.title'), body: t('tour.save.body') },
  ], [t]);
  const [mine, setMine] = useState<TraderOwnerView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [form, setForm] = useState<TraderUpsertRequest>(EMPTY_FORM);
  const [busy, setBusy] = useState<string | null>(null);
  const [template, setTemplate] = useState('');
  const [tour, setTour] = useState(false);
  // 重置确认面板：打开时笔记默认带入，每次重新打开都回到默认
  const [resetAsk, setResetAsk] = useState(false);
  const [carryNotes, setCarryNotes] = useState(true);
  // 删除确认面板：名字打对了才放行，每次重新打开都清空重打
  const [deleteAsk, setDeleteAsk] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  /** 端点库（下拉选项）；进页面拉一次，改动在 AI 页做 */
  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  useEffect(() => { llmEndpointApi.list().then(setEndpoints).catch(() => setEndpoints([])); }, []);

  const load = useCallback(() => {
    traderApi.mine().then(v => {
      setMine(v);
      if (v) {
        setForm({
          name: v.pub.name, symbols: v.pub.symbols, intervalCode: v.pub.intervalCode,
          customPrompt: v.customPrompt ?? '', llmEndpointId: v.llmEndpointId,
          spec: v.spec,
          alertEnabled: v.alertEnabled, alertThresholdMult: v.alertThresholdMult,
          reviewEnabled: v.reviewEnabled, learningEnabled: v.learningEnabled,
          wakeWindow: v.wakeWindow,
        });
      }
    }).finally(() => setLoaded(true));
  }, []);

  useEffect(() => { load(); }, [load]);

  // 平台默认交易指令按主人的 agent 语言取（与模板预览同一门），不跟界面语言
  const [defaultInstructions, setDefaultInstructions] = useState('');
  useEffect(() => {
    traderApi.defaultInstructions().then(setDefaultInstructions).catch(() => setDefaultInstructions(''));
  }, []);
  // 新建态：指令框还空着就预填平台默认，可改可删
  useEffect(() => {
    if (loaded && !mine && defaultInstructions) {
      setForm(f => f.customPrompt ? f : { ...f, customPrompt: defaultInstructions });
    }
  }, [loaded, mine, defaultInstructions]);

  // 首次进入自动跑一遍引导；标记落 localStorage，之后靠标题栏按钮重放
  useEffect(() => {
    if (loaded && !localStorage.getItem(TOUR_SEEN_KEY)) {
      setTour(true);
    }
  }, [loaded]);

  const closeTour = useCallback(() => {
    localStorage.setItem(TOUR_SEEN_KEY, '1');
    setTour(false);
  }, []);

  // 平台提示词预览随级别/币种/仓位规格/唤醒时段联动（与唤醒组装同一份文本，所见即所得）
  useEffect(() => {
    traderApi.promptTemplate(form.intervalCode, form.symbols || 'BTCUSDT', form.spec, form.wakeWindow)
      .then(setTemplate).catch(() => setTemplate(''));
  }, [form.intervalCode, form.symbols, form.spec, form.wakeWindow]);

  const run = useCallback(async (name: string, action: () => Promise<unknown>, okMsg: string) => {
    setBusy(name);
    try {
      await action();
      toast(okMsg, 'success');
      load();
    } catch (e) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setBusy(null);
    }
  }, [toast, load, t]);

  const set = (patch: Partial<TraderUpsertRequest>) => setForm(f => ({ ...f, ...patch }));
  const setSpec = (patch: Partial<TraderSpec>) => setForm(f => {
    const spec = { ...f.spec, ...patch };
    // 单仓模式下双开无从谈起（双开本身要两个仓位），关多仓位时顺手把它归位
    if (!spec.allowMultiPosition) {
      spec.allowHedge = false;
    }
    return { ...f, spec };
  });

  const toggleSymbol = (s: string) => {
    const cur = new Set(form.symbols.split(',').filter(Boolean));
    if (!cur.has(s) && cur.size >= MAX_SYMBOLS) return;
    if (cur.has(s)) {
      cur.delete(s);
    } else {
      cur.add(s);
    }
    set({ symbols: [...cur].join(',') });
  };
  const selected = new Set(form.symbols.split(',').filter(Boolean));
  const exists = mine != null;
  const status = mine?.pub.status;

  if (!loaded) {
    return <div className="page-shell p-6 flex justify-center"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>;
  }

  return (
    <div className="page-shell p-4 md:p-6 space-y-4 max-w-3xl">
      <div className="flex items-center gap-2.5">
        <Link to="/arena" className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label={t('term.backToArena')}>
          <ChevronLeft className="w-4 h-4" />
        </Link>
        <Bot className="w-5 h-5 text-primary" />
        <h1 className="text-lg font-black">{t('arena.myTrader')}</h1>
        {exists && (
          <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded',
            status === 'RUNNING' ? 'bg-gain/15 text-gain' : status === 'LIQUIDATED' ? 'bg-loss/15 text-loss' : 'bg-amber-500/15 text-amber-600')}>
            {status === 'RUNNING' ? t('status.running') : status === 'LIQUIDATED' ? t('status.liquidated') : t('status.paused')}
          </span>
        )}
        <button onClick={() => setTour(true)}
                className="ml-auto border border-border hover:bg-surface-hover rounded-lg px-2.5 py-1.5 text-[11px] font-bold text-muted-foreground hover:text-primary flex items-center gap-1.5">
          <GraduationCap className="w-3.5 h-3.5" /> {t('trader.tourBtn')}
        </button>
      </div>

      <GuidedTour steps={tourSteps} open={tour} onClose={closeTour} />

      {mine?.pub.pausedReason && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-600 font-bold">
          {mine.pub.pausedReason}
        </div>
      )}

      {/* 运行控制 */}
      {exists && (
        <div className="rounded-lg pt-card p-4 flex items-center gap-2 flex-wrap">
          <span className="microlabel mr-2">{t('trader.runControl')}</span>
          {status !== 'RUNNING' && status !== 'LIQUIDATED' && (
            <button onClick={() => void run('start', traderApi.start, t('toast.started'))} disabled={busy != null}
                    className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-gain flex items-center gap-1.5 disabled:opacity-50">
              {busy === 'start' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />} {t('trader.start')}
            </button>
          )}
          {status === 'RUNNING' && (
            <button onClick={() => void run('pause', traderApi.pause, t('status.paused'))} disabled={busy != null}
                    className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-amber-600 flex items-center gap-1.5 disabled:opacity-50">
              {busy === 'pause' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Pause className="w-3.5 h-3.5" />} {t('trader.pause')}
            </button>
          )}
          <button
            onClick={() => { setCarryNotes(true); setDeleteAsk(false); setResetAsk(true); }}
            disabled={busy != null}
            className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-muted-foreground flex items-center gap-1.5 disabled:opacity-50">
            {busy === 'reset' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RotateCcw className="w-3.5 h-3.5" />} {t('trader.reset')}
          </button>
          {/* 删除与重置摆一起但描红：重置是换一局接着跑，这个是把这只 trader 整个抹掉 */}
          <button
            onClick={() => { setConfirmName(''); setResetAsk(false); setDeleteAsk(true); }}
            disabled={busy != null}
            className="border border-loss/40 hover:bg-loss/10 rounded-lg px-3 py-1.5 text-xs font-bold text-loss flex items-center gap-1.5 disabled:opacity-50">
            {busy === 'delete' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />} {t('trader.delete')}
          </button>
          {mine && (
            <Link to={`/arena/${mine.pub.id}`} className="ml-auto text-xs font-bold text-primary hover:underline">
              {t('trader.viewTimeline')}
            </Link>
          )}
          {resetAsk && (
            <div className="w-full rounded-lg border border-border bg-card p-3 space-y-2.5 text-xs">
              <p className="font-bold">{t('trader.resetConfirm', { round: (mine?.pub.roundNo ?? 1) + 1 })}</p>
              <label className="flex items-start gap-2 cursor-pointer">
                <input type="checkbox" checked={carryNotes} onChange={e => setCarryNotes(e.target.checked)}
                       className="mt-0.5 accent-primary" />
                <span>
                  <span className="font-bold">{t('trader.resetCarryNotes')}</span>
                  <span className="block text-[11px] text-muted-foreground mt-0.5">{t('trader.resetCarryHint')}</span>
                </span>
              </label>
              <div className="flex gap-2">
                <button
                  onClick={() => { setResetAsk(false); void run('reset', () => traderApi.reset(carryNotes), t('toast.reset')); }}
                  disabled={busy != null}
                  className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 font-bold text-loss disabled:opacity-50">
                  {t('trader.reset')}
                </button>
                <button onClick={() => setResetAsk(false)}
                        className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 font-bold text-muted-foreground">
                  {t('common:cancel')}
                </button>
              </div>
            </div>
          )}
          {deleteAsk && (
            <div className="w-full rounded-lg border border-loss/40 bg-loss/5 p-3 space-y-2.5 text-xs">
              <p className="font-bold text-loss">{t('trader.deleteConfirm')}</p>
              <p className="text-[11px] text-muted-foreground leading-relaxed">{t('trader.deleteWarn')}</p>
              <label className="block space-y-1.5">
                <span className="block">
                  <Trans t={t} i18nKey="trader.deleteConfirmName" values={{ name: mine?.pub.name ?? '' }}>
                    <span className="font-bold text-foreground" />
                  </Trans>
                </span>
                <input value={confirmName} onChange={e => setConfirmName(e.target.value)}
                       maxLength={32} placeholder={mine?.pub.name}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs" />
              </label>
              <div className="flex gap-2">
                {/* 名字一字不差才点得动；后端也拦一道 */}
                <button
                  onClick={() => {
                    setDeleteAsk(false);
                    void run('delete', async () => {
                      await traderApi.remove(confirmName.trim());
                      setForm(EMPTY_FORM);   // 删干净了，配置表单跟着回到空白
                    }, t('toast.deleted'));
                  }}
                  disabled={busy != null || confirmName.trim() !== mine?.pub.name}
                  className="border border-loss/40 hover:bg-loss/10 rounded-lg px-3 py-1.5 font-bold text-loss disabled:opacity-50">
                  {t('trader.delete')}
                </button>
                <button onClick={() => setDeleteAsk(false)}
                        className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 font-bold text-muted-foreground">
                  {t('common:cancel')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 相关 skills：它每次唤醒拿到的全部工具——配置前先知道它会什么；默认折叠 */}
      <details className="rounded-lg pt-card group">
        <summary className="list-none cursor-pointer px-4 py-3 flex items-center gap-2.5 flex-wrap">
          <BookOpen className="w-3 h-3 text-primary" />
          <span className="microlabel">{t('skills.title')}</span>
          <span className="text-[11px] text-muted-foreground">{t('skills.summary', { trade: TRADE_TOOLS.length, data: DATA_TOOLS.length })}</span>
          <ChevronDown className="ml-auto w-3.5 h-3.5 text-muted-foreground transition-transform group-open:rotate-180" />
        </summary>
        <div className="px-4 pb-4 space-y-3">
          {SKILL_GROUPS.map(g => (
            <div key={g.key} className="rounded-md border border-border overflow-hidden">
              <div className="flex items-center gap-2 px-3 py-1.5 bg-card-2">
                <g.icon className="w-3 h-3 text-primary" />
                <b className="text-[11px] font-extrabold">{t(`skills.${g.key}`)}</b>
                <span className="text-[10px] text-muted-foreground">{t(`skills.${g.key}Hint`)}</span>
                <span className="ml-auto num text-[10px] text-muted-foreground">{g.tools.length}</span>
              </div>
              {/* 一行三列对齐：中文名 | 工具 id | 一句作用；手机竖排 */}
              {g.tools.map(id => (
                <div key={id} className="grid sm:grid-cols-[6rem_8.5rem_1fr] gap-x-3 gap-y-0.5 items-baseline px-3 py-1.5 border-t border-border/60 text-[11px] leading-relaxed">
                  <b className="text-xs font-extrabold">{toolName(id)}</b>
                  <code className="num text-[10px] text-muted-foreground">{id}</code>
                  <p className="text-muted-foreground">
                    <Trans ns="ai" i18nKey={`skills.desc.${id}`} components={[<span className="text-amber-600 font-bold" />]} />
                  </p>
                </div>
              ))}
            </div>
          ))}
          <p className="text-[10px] leading-relaxed text-muted-foreground border-l-2 border-border pl-2.5">{t('skills.foot')}</p>
        </div>
      </details>

      {/* 配置表单 */}
      <div className="rounded-lg pt-card p-4 space-y-4">
        <span className="microlabel">{exists ? t('cfg.editTitle') : t('cfg.createTitle')}</span>

        <div className="grid sm:grid-cols-2 gap-3">
          <label className="space-y-1 text-xs" data-tour="name">
            <span className="text-muted-foreground font-bold">{t('cfg.name')}</span>
            <input value={form.name} onChange={e => set({ name: e.target.value })} maxLength={32} placeholder={t('cfg.namePh')}
                   className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs" />
          </label>
          <label className="space-y-1 text-xs" data-tour="interval">
            <span className="text-muted-foreground font-bold">{t('cfg.interval')}</span>
            <div className="flex gap-1.5">
              {INTERVAL_OPTIONS.map(iv => (
                <button key={iv} type="button" onClick={() => set({ intervalCode: iv })}
                        className={cn('flex-1 h-9 rounded-lg border text-xs font-bold',
                          form.intervalCode === iv ? 'border-primary/60 bg-card-2 text-primary' : 'border-border text-muted-foreground hover:text-foreground')}>
                  {iv}
                </button>
              ))}
            </div>
            {form.intervalCode === '5m' && (
              <p className="text-[10px] text-amber-600 leading-relaxed">
                {t('cfg.interval5mWarn')}
              </p>
            )}
          </label>
        </div>

        {/* 唤醒时段：只管例行/警报唤醒；复盘学习不看它；末次唤醒时模型会被告知即将休眠 */}
        <div className="space-y-2 rounded-lg border border-border/60 bg-card-2/40 p-3" data-tour="wake-window">
          <div className="flex flex-wrap items-baseline justify-between gap-x-2">
            <span className="microlabel">{t('cfg.wakeWindow')}</span>
            <span className="text-[10px] text-muted-foreground">{t('cfg.wakeWindowHint')}</span>
          </div>
          <WakeWindowField value={form.wakeWindow} onChange={v => set({ wakeWindow: v })} />
        </div>

        <div className="space-y-1 text-xs" data-tour="symbols">
          <div className="flex flex-wrap items-baseline justify-between gap-x-2">
            <span className="text-muted-foreground font-bold">{t('cfg.symbols')}</span>
            <span className="text-[10px] text-muted-foreground">{t('cfg.symbolsHint', { max: MAX_SYMBOLS })}</span>
          </div>
          <div className="flex gap-1.5 flex-wrap">
            {SYMBOL_OPTIONS.map(s => (
              <button key={s} type="button" onClick={() => toggleSymbol(s)}
                      disabled={!selected.has(s) && selected.size >= MAX_SYMBOLS}
                      className={cn('px-3 h-9 rounded-lg border text-xs font-bold disabled:opacity-40 disabled:cursor-not-allowed',
                        selected.has(s) ? 'border-primary/60 bg-card-2 text-primary' : 'border-border text-muted-foreground hover:text-foreground')}>
                {s.replace('USDT', '')}
              </button>
            ))}
          </div>
        </div>

        {/* 仓位规格：主人说了算的硬参数，模型无权评价，越界一律拒（不截断） */}
        <div className="space-y-2.5 rounded-lg border border-border/60 bg-card-2/40 p-3">
          <div className="flex items-baseline justify-between">
            <span className="microlabel">{t('cfg.specTitle')}</span>
            <span className="text-[10px] text-muted-foreground">{t('cfg.specHint')}</span>
          </div>

          <div className="grid sm:grid-cols-2 gap-3">
            <label className="space-y-1 text-xs" data-tour="leverage">
              <span className="text-muted-foreground font-bold">{t('cfg.leverage')}</span>
              <div className="flex items-center gap-1.5">
                <input type="number" min={1} max={125} step={1} value={form.spec.leverageMin}
                       onChange={e => setSpec({ leverageMin: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
                <span className="text-muted-foreground">~</span>
                <input type="number" min={1} max={125} step={1} value={form.spec.leverageMax}
                       onChange={e => setSpec({ leverageMax: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
              </div>
            </label>
            <label className="space-y-1 text-xs" data-tour="margin">
              <span className="text-muted-foreground font-bold">{t('cfg.marginPct')}</span>
              <div className="flex items-center gap-1.5">
                <input type="number" min={0.1} max={100} step={0.5} value={form.spec.marginPctMin}
                       onChange={e => setSpec({ marginPctMin: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
                <span className="text-muted-foreground">~</span>
                <input type="number" min={0.1} max={100} step={0.5} value={form.spec.marginPctMax}
                       onChange={e => setSpec({ marginPctMax: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
              </div>
            </label>
          </div>

          {/* 配置的后果一眼可见：按 10000U 权益换算成名义价值区间 */}
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            <Trans
              ns="ai"
              i18nKey="cfg.notional"
              values={{
                minMargin: fmtNum(form.spec.marginPctMin * 100, 0),
                maxMargin: fmtNum(form.spec.marginPctMax * 100, 0),
                minNotional: fmtNum(form.spec.marginPctMin * 100 * form.spec.leverageMin, 0),
                maxNotional: fmtNum(form.spec.marginPctMax * 100 * form.spec.leverageMax, 0),
              }}
              components={[
                <span className="num text-foreground" />, <span className="num text-foreground" />,
                <span className="num text-foreground" />, <span className="num text-foreground" />,
              ]}
            />
          </p>

          <div className="space-y-2 pt-1" data-tour="position-rules">
            <SpecToggle checked={form.spec.allowMultiPosition} onChange={v => setSpec({ allowMultiPosition: v })}
                        label={t('cfg.multiPos')}
                        hint={t('cfg.multiPosHint')} />
            <SpecToggle checked={form.spec.allowHedge} onChange={v => setSpec({ allowHedge: v })}
                        disabled={!form.spec.allowMultiPosition}
                        label={t('cfg.hedge')}
                        hint={form.spec.allowMultiPosition ? t('cfg.hedgeHint') : t('cfg.hedgeNeedMulti')} />
          </div>
        </div>

        {/* 波动哨兵：极端行情临时唤醒（例行K线唤醒的补充）——只对 1h/4h 档生效 */}
        <div className="space-y-2 rounded-lg border border-border/60 bg-card-2/40 p-3">
          <div className="flex items-baseline justify-between">
            <span className="microlabel">{t('cfg.alertTitle')}</span>
            <span className="text-[10px] text-muted-foreground">{t('cfg.alertHint')}</span>
          </div>
          <div className="grid sm:grid-cols-2 gap-3 items-start">
            <SpecToggle checked={form.alertEnabled} onChange={v => set({ alertEnabled: v })}
                        label={t('cfg.alertOn')}
                        hint={t('cfg.alertOnHint')} />
            <label className="space-y-1 text-xs">
              <span className="text-muted-foreground font-bold">{t('cfg.alertMult')}</span>
              <input type="number" min={1} step={0.1} value={form.alertThresholdMult}
                     onChange={e => set({ alertThresholdMult: Number(e.target.value) })}
                     disabled={!form.alertEnabled}
                     className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num disabled:opacity-45" />
            </label>
          </div>
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {t('cfg.alertBase', {
              list: SYMBOL_OPTIONS.map(s => `${s.replace('USDT', '')} ${ALERT_BASE[s]}%`).join(' · '),
              mult: form.alertThresholdMult >= 1 ? form.alertThresholdMult.toFixed(1) : '1.0',
              cur: SYMBOL_OPTIONS.filter(s => selected.has(s))
                .map(s => `${s.replace('USDT', '')} ${(ALERT_BASE[s] * Math.max(1, form.alertThresholdMult)).toFixed(2)}%`)
                .join(' · ') || t('cfg.noSymbol'),
            })}
          </p>
        </div>

        {/* 两条学习轨并列：复盘看自己（reviewer），学习看同侪（learning agent），各自单独开关、各自一份笔记 */}
        <div className="grid sm:grid-cols-2 gap-3 items-start">
          {/* 每日复盘：reviewer 在日线边界读全天交易痕迹，复盘上时间线、教训写进记忆笔记 */}
          <div className="space-y-2 rounded-lg border border-border/60 bg-card-2/40 p-3">
            <div className="flex flex-wrap items-baseline justify-between gap-x-2">
              <span className="microlabel">{t('term.dailyReview')}</span>
              <span className="text-[10px] text-muted-foreground">{t('cfg.reviewHint')}</span>
            </div>
            <SpecToggle checked={form.reviewEnabled} onChange={v => set({ reviewEnabled: v })}
                        label={t('cfg.reviewOn')}
                        hint={t('cfg.reviewOnHint')} />
          </div>

          {/* 同侪学习：全体复盘跑完后 learning agent 读别人的成绩与复盘，学到的写进学习笔记 */}
          <div className="space-y-2 rounded-lg border border-border/60 bg-card-2/40 p-3">
            <div className="flex flex-wrap items-baseline justify-between gap-x-2">
              <span className="microlabel">{t('term.peerLearn')}</span>
              <span className="text-[10px] text-muted-foreground">{t('cfg.learnHint')}</span>
            </div>
            <SpecToggle checked={form.learningEnabled} onChange={v => set({ learningEnabled: v })}
                        label={t('cfg.learnOn')}
                        hint={t('cfg.learnOnHint')} />
          </div>
        </div>

        {/* 模型：从端点库选一条（协议/URL/key/模型都在 AI 页配），不选=跟随默认端点 */}
        <div data-tour="byok" className="space-y-1.5 text-xs">
          <div className="flex items-baseline justify-between gap-2 flex-wrap">
            <span className="text-muted-foreground font-bold">{t('cfg.endpoint')}</span>
            <Link to="/ai" className="text-[10px] text-primary font-bold hover:underline">{t('cfg.manageEndpoints')}</Link>
          </div>
          <LlmEndpointSelect endpoints={endpoints} value={form.llmEndpointId}
            onChange={id => set({ llmEndpointId: id })} className="w-full sm:w-auto sm:min-w-[320px]" />
          <span className="text-[10px] text-muted-foreground/70 block">
            {t('cfg.endpointHint')}
          </span>
        </div>

        {/* 平台系统提示词只讲事实（环境/工具/规格/护栏），可展开看；方法与纪律全在下面主人的交易指令里 */}
        <div className="space-y-4" data-tour="prompt">
        <details className="rounded-lg border border-border bg-card-2/50 text-xs">
          <summary className="px-3 py-2 text-[11px] font-bold text-muted-foreground cursor-pointer">
            {t('cfg.viewPrompt')}
          </summary>
          <pre className="max-h-56 overflow-y-auto px-3 pb-3 text-[11px] leading-relaxed text-muted-foreground whitespace-pre-wrap font-sans">{template}</pre>
        </details>

        <div className="space-y-1 text-xs">
          <div className="flex items-center justify-between gap-2">
            <label htmlFor="custom-prompt" className="text-muted-foreground font-bold">{t('cfg.customPrompt')}</label>
            {/* 整段换回平台默认；框里有字先确认 */}
            <button type="button" disabled={!defaultInstructions}
                    onClick={() => {
                      if (!form.customPrompt || window.confirm(t('cfg.resetDefaultPromptConfirm'))) {
                        set({ customPrompt: defaultInstructions });
                      }
                    }}
                    className="inline-flex items-center gap-1 text-[11px] text-primary hover:underline shrink-0 disabled:opacity-50">
              <RotateCcw className="w-3 h-3" />{t('cfg.resetDefaultPrompt')}
            </button>
          </div>
          <textarea id="custom-prompt" value={form.customPrompt ?? ''} onChange={e => set({ customPrompt: e.target.value })} rows={10} maxLength={4000}
                    placeholder={t('cfg.customPromptPh')}
                    className="w-full rounded-lg border border-border bg-card-2 px-3 py-2 text-xs leading-relaxed" />
        </div>
        </div>

        <button
          data-tour="save"
          onClick={() => void run('save',
            () => exists ? traderApi.updateConfig(form) : traderApi.create(form),
            exists ? t('toast.saved') : t('toast.created'))}
          disabled={busy != null}
          className="border border-border hover:bg-surface-hover rounded-lg px-4 py-2 text-xs font-black text-primary flex items-center gap-1.5 disabled:opacity-50">
          {busy === 'save' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
          {exists ? t('cfg.save') : t('cfg.create')}
        </button>

        <p className="text-[10px] leading-relaxed text-muted-foreground border-l-2 border-border pl-2.5">
          {t('cfg.rules')}
        </p>
      </div>
    </div>
  );
}

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINUTES = Array.from({ length: 12 }, (_, i) => String(i * 5).padStart(2, '0'));

/** 唤醒时段：全天开关 + 起止时/分（分钟只给 0/5 的倍数——K 线按 5 分钟收盘）。值形如 "21:00-08:30"，null=全天 */
function WakeWindowField({ value, onChange }: { value: string | null; onChange: (v: string | null) => void }) {
  const { t } = useTranslation('ai');
  // 开着时起止就是 value 本身（表单是唯一真值）；关掉时 value 变 null，把关掉那一刻的起止记住，再打开还是刚才那组
  const [remembered, setRemembered] = useState<[string, string]>(['21:00', '08:30']);
  const enabled = value != null;
  const [from, to] = enabled ? value.split('-') as [string, string] : remembered;
  const toggle = (on: boolean) => {
    setRemembered([from, to]);
    onChange(on ? `${from}-${to}` : null);
  };
  const setPart = (which: 'from' | 'to', hh: string, mm: string) => {
    const t = `${hh}:${mm}`;
    onChange(which === 'from' ? `${t}-${to}` : `${from}-${t}`);
  };
  const [fh, fm] = from.split(':');
  const [th, tm] = to.split(':');
  return (
    <div className="space-y-2">
      <SpecToggle checked={enabled} onChange={toggle}
                  label={enabled ? t('cfg.windowOn') : t('cfg.windowOff')}
                  hint={t('cfg.windowHint')} />
      {enabled && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <TimeSelect hh={fh} mm={fm} onChange={(h, m) => setPart('from', h, m)} />
          <span className="text-muted-foreground">{t('cfg.to')}</span>
          <TimeSelect hh={th} mm={tm} onChange={(h, m) => setPart('to', h, m)} />
          {from > to && <span className="text-[10px] text-muted-foreground">{t('cfg.nextDay')}</span>}
          {from === to && <span className="text-[10px] text-loss">{t('cfg.sameTime')}</span>}
        </div>
      )}
    </div>
  );
}

function TimeSelect({ hh, mm, onChange }: { hh: string; mm: string; onChange: (hh: string, mm: string) => void }) {
  const cls = 'h-9 rounded-lg border border-border bg-card-2 px-2 text-xs num';
  return (
    <span className="inline-flex items-center gap-1">
      <select value={hh} onChange={e => onChange(e.target.value, mm)} className={cls}>
        {HOURS.map(h => <option key={h} value={h}>{h}</option>)}
      </select>
      <span>:</span>
      <select value={mm} onChange={e => onChange(hh, e.target.value)} className={cls}>
        {MINUTES.map(m => <option key={m} value={m}>{m}</option>)}
      </select>
    </span>
  );
}

/** 规格开关：一行一个，说明写在标签下方——配置的后果得当场看得懂 */
function SpecToggle({ checked, onChange, label, hint, disabled }: {
  checked: boolean; onChange: (v: boolean) => void; label: string; hint: string; disabled?: boolean;
}) {
  return (
    <label className={cn('flex items-start gap-2 text-xs select-none',
      disabled ? 'opacity-45 cursor-not-allowed' : 'cursor-pointer')}>
      <input type="checkbox" checked={checked} disabled={disabled}
             onChange={e => onChange(e.target.checked)} className="mt-0.5" />
      <span className="space-y-0.5">
        <span className="font-bold block">{label}</span>
        <span className="text-[10px] text-muted-foreground leading-relaxed block">{hint}</span>
      </span>
    </label>
  );
}
