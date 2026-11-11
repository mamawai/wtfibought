import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import {
  AlertTriangle, ArrowRight, BookCheck, ChevronLeft, ChevronRight, GraduationCap, Loader2, Lock, MessageSquarePlus, Pencil,
  RotateCcw, Trash2, WandSparkles, Zap,
} from 'lucide-react';
import { llmEndpointApi, traderApi } from '../api';
import { CoinStage } from '../components/arena/CoinStage';
import { ExperienceLedgerSection } from '../components/arena/ExperienceLedgerSection';
import { HeartbeatStrip } from '../components/arena/HeartbeatStrip';
import { STATUS_META } from '../components/arena/traderStatus';
import { ScaleSlider, SwitchRow, WakeWindowRuler } from '../components/arena/TraderControls';
import {
  ALERT_BASE, ALERT_INTERVALS, BASE_EQUITY, beatsPerDay, INTERVALS, MAX_SYMBOLS, minToHm, parseWindow,
  TRADER_SYMBOLS, windowText, type WakeWin,
} from '../components/arena/traderConfig';
import { DATA_TOOLS, TRADE_TOOLS, toolName } from '../components/arena/traderTools';
import { useKindLabel } from '../components/arena/useKindLabel';
import { GuidedTour, type TourStep } from '../components/GuidedTour';
import { LlmEndpointSelect } from '../components/LlmEndpointSelect';
import { useToast } from '../components/ui/use-toast';
import { COIN_MAP } from '../lib/coinConfig';
import { cn, fmtNum, fmtSignedPct } from '../lib/utils';
import type {
  LlmEndpointView, TraderActionPanel, TraderActionResult, TraderOwnerView, TraderSpec, TraderUpsertRequest,
} from '../types';

const TOUR_SEEN_KEY = 'wiib-trader-tour-seen';
/** 动作面板多久刷一次：下次心跳、上次醒来、今天醒了几次都从它来 */
const PANEL_REFRESH_MS = 60_000;

/** 杠杆刻度；值 1~125 */
const LEV_T = [1, 2, 3, 5, 10, 20, 50, 100, 125];
/** 每笔新仓保证金上限最高 50%，跟后端 TraderRiskConfig.MARGIN_PCT_HARD_MAX 同一个数 */
const MARGIN_MAX = 50;
const MAR_T = [0.1, 0.5, 1, 2, 5, 10, 20, 30, MARGIN_MAX];
/** 警报灵敏度系数 1.0~4.0，只能调高 */
const SENS_T = [1, 2, 3, 4];
/** 交易指令最多这么多字，跟后端 TraderService.MAX_CUSTOM_PROMPT 同一个数 */
const PROMPT_MAX = 4000;
/** 名字最多这么多字 */
const NAME_MAX = 32;
/** 时段默认 21:00–08:30（只盯夜盘） */
const DEFAULT_WIN: WakeWin = { from: 21 * 60, to: 8 * 60 + 30 };
/** 重置按住多久才生效 */
const HOLD_MS = 1200;

const DEFAULT_SPEC: TraderSpec = {
  leverageMin: 3, leverageMax: 20, marginPctMax: 20,
  allowMultiPosition: true, allowHedge: false,
};

// 默认心跳 1h
const EMPTY_FORM: TraderUpsertRequest = {
  name: '', symbols: 'BTCUSDT', intervalCode: '1h', customPrompt: '',
  llmEndpointId: null,
  spec: DEFAULT_SPEC, alertEnabled: true, alertThresholdMult: 1, reviewEnabled: true,
  learnedRestrictionHold: false,
  webSearchEnabled: false,
  wakeWindow: null,
};

function formOf(v: TraderOwnerView): TraderUpsertRequest {
  return {
    name: v.pub.name, symbols: v.pub.symbols, intervalCode: v.pub.intervalCode,
    customPrompt: v.customPrompt ?? '', llmEndpointId: v.llmEndpointId,
    spec: v.spec,
    alertEnabled: v.alertEnabled, alertThresholdMult: v.alertThresholdMult,
    reviewEnabled: v.reviewEnabled,
    learnedRestrictionHold: v.learnedRestrictionHold,
    webSearchEnabled: v.webSearchEnabled,
    wakeWindow: v.wakeWindow,
  };
}

/** 每一节管哪几个表单项：保存条按它列出改了什么，左侧索引按它点橙点 */
const SECTIONS: { id: string; keys: (keyof TraderUpsertRequest)[] }[] = [
  { id: 'name', keys: ['name'] },
  { id: 'coins', keys: ['symbols'] },
  { id: 'beat', keys: ['intervalCode', 'wakeWindow'] },
  { id: 'spec', keys: ['spec'] },
  { id: 'alert', keys: ['alertEnabled', 'alertThresholdMult', 'reviewEnabled', 'learnedRestrictionHold'] },
  { id: 'model', keys: ['llmEndpointId', 'webSearchEnabled'] },
  { id: 'prompt', keys: ['customPrompt'] },
];

/** 左侧索引：own=只有建好的 trader 才有这一节 */
const INDEX: { id: string; no?: string; own?: boolean; red?: boolean }[] = [
  { id: 'ledger', own: true },
  { id: 'coins', no: '01' },
  { id: 'beat', no: '02' },
  { id: 'spec', no: '03' },
  { id: 'alert', no: '04' },
  { id: 'model', no: '05' },
  { id: 'prompt', no: '06' },
  { id: 'tools' },
  { id: 'danger', own: true, red: true },
];

const secId = (id: string) => `my-${id}`;

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

function snapLev(v: number) {
  return Math.max(1, Math.min(125, Math.round(v)));
}

/** 保证金上限：1% 以下 0.1 一档，10% 以下 0.5 一档，再往上 1 一档 */
function snapMargin(v: number) {
  if (v < 1) return Math.max(0.1, Math.round(v * 10) / 10);
  if (v < 10) return Math.round(v * 2) / 2;
  return Math.min(MARGIN_MAX, Math.round(v));
}

function stepMargin(v: number, dir: number) {
  const st = v < 1 ? 0.1 : v < 10 ? 0.5 : 1;
  return Math.max(0.1, Math.min(MARGIN_MAX, snapMargin(v + dir * st)));
}

function snapSens(v: number) {
  return Math.max(SENS_T[0], Math.min(SENS_T[SENS_T.length - 1], Math.round(v * 10) / 10));
}

function fmtPct(v: number) {
  return `${v < 1 || v % 1 !== 0 ? v.toFixed(1) : v}%`;
}

const symbolsOf = (s: string) => s.split(',').filter(Boolean);

/**
 * 我的 Trader 配置台：页头（可直接改名）→ 心跳条 + 动作一排 → 左侧分节索引 ‖ 经验账本、币种、心跳与时段、仓位规格、
 * 警报与复盘、模型、交易指令、工具箱、危险区 → 有改动才升起的保存条。
 * 模型从 AI 页「模型配置」的端点库里选，不在这里填 key；提示词存库即热生效，下一根心跳就按新的来
 */
export function MyTrader() {
  const { toast } = useToast();
  const { t } = useTranslation(['ai', 'common']);
  const kindLabel = useKindLabel();
  /**
   * 配置引导：讲清楚"这是什么 + 配大配小会怎样 + 和别的字段怎么互相咬"。
   * 必须 memo 住：GuidedTour 的定位 effect 认 step 对象身份，每次渲染新建数组会让它反复重算。
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
  // 上次存下的那份：改动按它比，放弃改动回到它
  const [base, setBase] = useState<TraderUpsertRequest>(EMPTY_FORM);
  const [busy, setBusy] = useState<string | null>(null);
  const [tour, setTour] = useState(false);
  const [endpoints, setEndpoints] = useState<LlmEndpointView[]>([]);
  const [panel, setPanel] = useState<TraderActionPanel | null>(null);
  const [noteOpen, setNoteOpen] = useState(false);
  const [noteText, setNoteText] = useState('');
  const [noteRounds, setNoteRounds] = useState(1);
  const [active, setActive] = useState('coins');
  // 关掉时段前的那组起止，再打开还是它
  const [lastWin, setLastWin] = useState<WakeWin>(DEFAULT_WIN);

  useEffect(() => { llmEndpointApi.list().then(setEndpoints).catch(() => setEndpoints([])); }, []);

  /** 只刷状态（运行与否、上次唤醒、留言），表单里没存的改动不动 */
  const refreshOwner = useCallback(() => {
    traderApi.mine().then(v => setMine(v)).catch(() => {});
    traderApi.actionPanel().then(setPanel).catch(() => setPanel(null));
  }, []);
  useEffect(() => {
    const timer = setInterval(refreshOwner, PANEL_REFRESH_MS);
    return () => clearInterval(timer);
  }, [refreshOwner]);

  /** 整页重载：表单回到存下的那份 */
  const load = useCallback(() => {
    traderApi.mine().then(v => {
      setMine(v);
      const f = v ? formOf(v) : EMPTY_FORM;
      setForm(f);
      setBase(f);
      const w = parseWindow(f.wakeWindow);
      if (w) setLastWin(w);
    }).finally(() => setLoaded(true));
    traderApi.actionPanel().then(setPanel).catch(() => setPanel(null));
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
      setBase(f => f.customPrompt ? f : { ...f, customPrompt: defaultInstructions });
    }
  }, [loaded, mine, defaultInstructions]);

  // 首次进入自动跑一遍引导；标记落 localStorage，之后靠按钮重放
  useEffect(() => {
    if (loaded && !localStorage.getItem(TOUR_SEEN_KEY)) setTour(true);
  }, [loaded]);
  const closeTour = useCallback(() => {
    localStorage.setItem(TOUR_SEEN_KEY, '1');
    setTour(false);
  }, []);

  const exists = mine != null;

  // 左侧索引跟着滚动亮当前那节；建好 / 删掉 trader 时账本、危险区两节会出现或消失，重新挂一遍
  const mainRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const root = mainRef.current;
    if (!root || !loaded) return;
    const io = new IntersectionObserver(entries => {
      for (const e of entries) if (e.isIntersecting) setActive((e.target as HTMLElement).id.replace('my-', ''));
    }, { rootMargin: '-25% 0px -65% 0px' });
    root.querySelectorAll('section[id^="my-"]').forEach(s => io.observe(s));
    return () => io.disconnect();
  }, [loaded, exists]);

  const run = useCallback(async (name: string, action: () => Promise<unknown>, okMsg: string | null, after: () => void) => {
    setBusy(name);
    try {
      await action();
      if (okMsg) toast(okMsg, 'success');
      after();
    } catch (e) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setBusy(null);
    }
  }, [toast, t]);

  /** 动作面板的三个动作：结果原话一律显示 */
  const runAction = (name: string, action: () => Promise<TraderActionResult>) => void run(name, async () => {
    const r = await action();
    toast(r.message, r.ok ? 'success' : 'error');
  }, null, refreshOwner);

  const set = (patch: Partial<TraderUpsertRequest>) => setForm(f => ({ ...f, ...patch }));
  const setSpec = (patch: Partial<TraderSpec>) => setForm(f => {
    const spec = { ...f.spec, ...patch };
    // 单仓模式下双开无从谈起（双开本身要两个仓位），关多仓位时顺手把它归位
    if (!spec.allowMultiPosition) spec.allowHedge = false;
    return { ...f, spec };
  });
  const toggleSymbol = (s: string) => {
    const cur = symbolsOf(form.symbols);
    set({ symbols: (cur.includes(s) ? cur.filter(x => x !== s) : [...cur, s]).join(',') });
  };

  const status = mine?.pub.status;
  const running = status === 'RUNNING';
  const selected = symbolsOf(form.symbols);
  const win = parseWindow(form.wakeWindow);
  const perDay = beatsPerDay(form.intervalCode, win);
  // 表单里选的那条端点（没选就是默认端点）开没开联网搜索；没开的话联网搜索开关点不动
  const endpointSearch = (endpoints.find(e => e.id === form.llmEndpointId)
    ?? endpoints.find(e => e.isDefault) ?? endpoints[0])?.webSearch ?? false;

  const dirty = SECTIONS.filter(s => s.keys.some(k => !same(form[k], base[k])));
  const errors = [
    !form.name.trim() && t('my.err.name'),
    selected.length === 0 && t('my.err.coins'),
    win && win.from === win.to && t('my.err.sameTime'),
    win && win.from !== win.to && perDay === 0 && t('my.err.noBeat'),
    form.spec.marginPctMax > MARGIN_MAX && t('my.err.margin', { max: MARGIN_MAX }),
  ].filter((e): e is string => !!e);

  const save = () => void run('save', () => exists ? traderApi.updateConfig(form) : traderApi.create(form),
    exists ? t('toast.saved') : t('toast.created'), load);

  if (!loaded) {
    return <div className="page-shell p-6 flex justify-center"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>;
  }

  const st = status ? (STATUS_META[status] ?? STATUS_META.PAUSED) : null;
  // 心跳条第四格：今天醒了几次，按叫醒原因分
  const todayWakes = Object.entries(panel?.todayWakes ?? {});
  const todayN = todayWakes.reduce((s, [, n]) => s + n, 0);
  const goSection = (id: string) => document.getElementById(secId(id))?.scrollIntoView({ behavior: 'smooth', block: 'start' });

  return (
    <div className="wrap pb-24">
      <GuidedTour steps={tourSteps} open={tour} onClose={closeTour} />

      {/* 页头：大名字直接点着改；右边是本局收益 */}
      <section className="tr-hero">
        <div className="min-w-0">
          <Link to="/arena" className="inline-flex items-center gap-1 text-[13px] mute">
            <ChevronLeft className="w-3.5 h-3.5" />{t('term.backToArena')}
          </Link>
          <div className="tr-eyebrow">{exists ? t('my.hero.eyebrow') : t('my.hero.eyebrowNew')}</div>
          <div className="tr-hero-n">
            <label className="tr-nm" data-v={`${form.name || t('my.hero.namePh')} `} title={t('my.hero.nameTip')} data-tour="name">
              <input size={1} value={form.name} maxLength={NAME_MAX} placeholder={t('my.hero.namePh')}
                     aria-label={t('my.hero.nameAria')} onChange={e => set({ name: e.target.value })} />
              <Pencil className="tr-nm-pen" />
            </label>
            {st ? (
              <span className={cn('chip', st.chip)}>{running && <i className="dot pulse" />}{t(st.labelKey)}</span>
            ) : <span className="chip mute">{t('my.hero.notCreated')}</span>}
            {mine && <span className="chip mute num">R{mine.pub.roundNo}</span>}
          </div>
          <div className="tr-hero-meta">
            {exists ? (
              <>
                <span>{mine.pub.model ?? t('term.noModel')}</span>
                <span>{t('wb.meta.beat', { iv: base.intervalCode })}</span>
                <span>{base.wakeWindow ? t('wb.meta.window', { win: base.wakeWindow.replace('-', '–') }) : t('detail.allDayWake')}</span>
                <span>{symbolsOf(base.symbols).map(s => COIN_MAP[s]?.name ?? s).join(' / ')}</span>
              </>
            ) : <span>{t('my.hero.metaNew')}</span>}
          </div>
        </div>
        <div className="tr-hero-r num">
          {mine ? (
            <>
              <b className={mine.pub.pnlPct >= 0 ? 'up' : 'dn'}>{fmtSignedPct(mine.pub.pnlPct)}</b>
              <div>{t('term.equity')} <strong>{fmtNum(mine.pub.equity)}</strong> · {t('detail.initialLabel')}</div>
            </>
          ) : (
            <button type="button" className="btn sm" onClick={() => setTour(true)}>
              <GraduationCap className="ic" />{t('trader.tourBtn')}
            </button>
          )}
        </div>
      </section>

      {mine?.pub.pausedReason && (
        <div className="mt-4 border-l-4 border-warning pl-3 py-1 text-[13px] text-warning">{mine.pub.pausedReason}</div>
      )}

      {exists ? (
        <>
          {/* 心跳条：看的是已经存下的那份配置 */}
          <HeartbeatStrip traderId={mine.pub.id} intervalCode={mine.pub.intervalCode} wakeWindow={mine.pub.wakeWindow}
                          running={running} idleText={t('my.hb.stopped')} nextWakeAt={panel?.nextWakeAt ?? null}
                          mine refreshKey={panel}
                          extra={{
                            k: t('my.hb.today'), v: t('my.hb.todayN', { n: todayN }),
                            s: todayWakes.map(([k, n]) => `${kindLabel(k, true, mine.pub.intervalCode)} ${n}`).join(' · ')
                              || t('my.hb.todayNone'),
                          }} />

          {/* 动作一排：运行开关 + 三个动作 + 去工作台 */}
          <div className="tr-acts">
            <button type="button" role="switch" aria-checked={running} aria-label={t('my.act.powerAria')}
                    className={cn('tr-power', !running && 'off')} disabled={busy != null || status === 'LIQUIDATED'}
                    onClick={() => running
                      ? void run('power', traderApi.pause, t('status.paused'), refreshOwner)
                      : void run('power', traderApi.start, t('toast.started'), refreshOwner)}>
              <span className="a"><i className={cn('dot', running && 'pulse')} />{t('my.act.run')}</span>
              <span className="b">{t('my.act.pause')}</span>
            </button>
            <button type="button" className="btn sm" disabled={busy != null || !!panel?.wakeBlockedReason}
                    title={panel?.wakeBlockedReason ?? undefined} onClick={() => runAction('wake', traderApi.wake)}>
              {busy === 'wake' ? <Loader2 className="ic animate-spin" /> : <Zap className="ic" />}{t('term.manualWake')}
            </button>
            <button type="button" className={cn('btn sm', noteOpen && 'fill')} aria-expanded={noteOpen}
                    onClick={() => {
                      if (!noteOpen) {
                        setNoteText(panel?.note ?? '');
                        setNoteRounds(panel?.note ? panel.noteRounds : 1);
                      }
                      setNoteOpen(o => !o);
                    }}>
              <MessageSquarePlus className="ic" />{t('my.act.note')}
            </button>
            <button type="button" className="btn sm" disabled={busy != null || !!panel?.reviewBlockedReason}
                    title={panel?.reviewBlockedReason ?? undefined} onClick={() => runAction('review', traderApi.review)}>
              {busy === 'review' ? <Loader2 className="ic animate-spin" /> : <BookCheck className="ic" />}{t('term.reviewNow')}
            </button>
            <span className="flex-1" />
            <button type="button" className="btn sm" onClick={() => setTour(true)}>
              <GraduationCap className="ic" />{t('trader.tourBtn')}
            </button>
            <Link className="btn sm fill" to={`/arena/${mine.pub.id}`}>{t('my.act.workbench')}<ArrowRight className="ic" /></Link>
          </div>
          {noteOpen && panel && (
            <div className="tr-note">
              <div>
                <div className="text-[12.5px] mute mb-1.5">
                  {panel.note ? t('my.note.current', { n: panel.noteRounds }) : t('my.note.lead')}
                </div>
                <textarea value={noteText} maxLength={panel.noteMaxChars} placeholder={t('my.note.ph')}
                          aria-label={t('my.act.note')} onChange={e => setNoteText(e.target.value)} />
              </div>
              <div className="tr-note-r">
                <span className="mute text-[12.5px]">{t('my.note.for')}</span>
                <span className="tr-stepper">
                  <button type="button" aria-label={t('my.note.less')} disabled={noteRounds <= 1}
                          onClick={() => setNoteRounds(n => n - 1)}>−</button>
                  <b className="num">{noteRounds}</b>
                  <button type="button" aria-label={t('my.note.more')} disabled={noteRounds >= panel.noteMaxRounds}
                          onClick={() => setNoteRounds(n => n + 1)}>+</button>
                </span>
                <span className="mute text-[12.5px]">{t('my.note.wakes')}</span>
                {panel.note && (
                  <button type="button" className="btn sm" disabled={busy != null}
                          onClick={() => { setNoteOpen(false); runAction('note', traderApi.clearNote); }}>{t('my.note.clear')}</button>
                )}
                <button type="button" className="btn sm orange" disabled={busy != null || !noteText.trim()}
                        onClick={() => { setNoteOpen(false); runAction('note', () => traderApi.saveNote(noteText.trim(), noteRounds)); }}>
                  {t('my.act.note')}
                </button>
              </div>
            </div>
          )}
        </>
      ) : (
        <div className="tr-steps3">
          {[1, 2, 3].map(n => (
            <div key={n}><b>{n}</b><span>{t(`my.steps.s${n}`)}</span><em>{t(`my.steps.s${n}Sub`)}</em></div>
          ))}
        </div>
      )}

      <div className="tr-body">
        <nav className="tr-idx" aria-label={t('my.idx.aria')}>
          {INDEX.filter(x => exists || !x.own).map(x => (
            <a key={x.id} href={`#${secId(x.id)}`}
               className={cn(active === x.id && 'on', dirty.some(d => d.id === x.id) && 'dirty', x.red && 'red')}
               onClick={e => { e.preventDefault(); goSection(x.id); }}>
              <span className="no">{x.no}</span>{t(`my.idx.${x.id}`)}<i />
            </a>
          ))}
          <p>{t('my.idx.dirtyCap')}</p>
        </nav>

        <main ref={mainRef} className="tr-main">
          {exists && <ExperienceLedgerSection id={secId('ledger')} className="first" />}

          <section id={secId('coins')} className={cn('tr-sec', !exists && 'first')} data-tour="symbols">
            <SecHead no="01" title={t('my.idx.coins')} desc={t('my.coins.desc', { n: TRADER_SYMBOLS.length, max: MAX_SYMBOLS })} />
            <CoinStage selected={selected} onToggle={toggleSymbol} />
          </section>

          <section id={secId('beat')} className="tr-sec">
            <SecHead no="02" title={t('my.idx.beat')} desc={t('my.beat.desc')} />
            <div className="tr-iv" role="radiogroup" aria-label={t('my.beat.aria')} data-tour="interval">
              {INTERVALS.map(iv => (
                <button key={iv} type="button" role="radio" aria-checked={form.intervalCode === iv}
                        className={cn(form.intervalCode === iv && 'on')} onClick={() => set({ intervalCode: iv })}>{iv}</button>
              ))}
            </div>
            <p className="tr-hint mt-2.5">{t('my.beat.hint', { iv: form.intervalCode, n: perDay, note: t(`my.beat.note.${form.intervalCode}`) })}</p>
            <div className="mt-7" data-tour="wake-window">
              <SwitchRow bare on={win != null}
                         title={win ? t('my.window.onTitle') : t('my.window.offTitle')}
                         desc={win ? t('my.window.onDesc') : t('my.window.offDesc')}
                         onChange={on => {
                           if (!on && win) setLastWin(win);
                           set({ wakeWindow: on ? windowText(lastWin) : null });
                         }} />
              {win && <WakeWindowRuler win={win} interval={form.intervalCode} onChange={w => set({ wakeWindow: windowText(w) })} />}
              <p className={cn('tr-win-r', win && (win.from === win.to || perDay === 0) && 'bad')}>
                {!win ? t('my.window.readAll', { iv: form.intervalCode, n: perDay })
                  : win.from === win.to ? t('my.window.readSame')
                    : perDay === 0 ? t('my.window.readNone', { iv: form.intervalCode })
                      : t('my.window.read', {
                        from: minToHm(win.from), to: minToHm(win.to), n: perDay,
                        cross: win.from > win.to ? t('my.window.cross') : '',
                      })}
              </p>
            </div>
          </section>

          <section id={secId('spec')} className="tr-sec">
            <SecHead no="03" title={t('my.idx.spec')} desc={t('my.spec.desc')} />
            <div className="tr-spec">
              <div className="tr-fld" data-tour="leverage">
                <div className="lab"><span>{t('my.spec.lev')}</span><span>{t('my.spec.levSub')}</span></div>
                <b className="tr-big num">
                  {form.spec.leverageMin === form.spec.leverageMax
                    ? `${form.spec.leverageMin}×` : `${form.spec.leverageMin}× – ${form.spec.leverageMax}×`}
                </b>
                <ScaleSlider ticks={LEV_T} values={[form.spec.leverageMin, form.spec.leverageMax]}
                             onChange={([lo, hi]) => setSpec({ leverageMin: lo, leverageMax: hi })}
                             snap={snapLev} step={(v, d) => snapLev(v + d)} tickLabel={v => `${v}×`}
                             ariaLabels={[t('my.spec.levMinAria'), t('my.spec.levMaxAria')]} />
              </div>
              <div className="tr-fld" data-tour="margin">
                <div className="lab"><span>{t('my.spec.margin')}</span><span>{t('my.spec.marginSub', { max: MARGIN_MAX })}</span></div>
                <b className="tr-big num">{fmtPct(form.spec.marginPctMax)}</b>
                <ScaleSlider ticks={MAR_T} values={[form.spec.marginPctMax]} onChange={([v]) => setSpec({ marginPctMax: v })}
                             snap={snapMargin} step={stepMargin} tickLabel={v => `${v}%`}
                             ariaLabels={[t('my.spec.marginAria')]} />
              </div>
            </div>
            <div className="tr-conseq num">
              <div>
                <div className="k">{t('my.spec.maxMargin')}</div>
                <div className="v">{fmtNum(BASE_EQUITY * form.spec.marginPctMax / 100, 0)}<small>U</small></div>
              </div>
              <div>
                <div className="k">{t('my.spec.maxNotional', { lev: form.spec.leverageMax })}</div>
                <div className="v">{fmtNum(BASE_EQUITY * form.spec.marginPctMax / 100 * form.spec.leverageMax, 0)}<small>U</small></div>
              </div>
            </div>
            <p className="tr-hint mt-2.5">{t('my.spec.hint')}</p>
            <div className="tr-swgrid" data-tour="position-rules">
              <SwitchRow on={form.spec.allowMultiPosition} onChange={v => setSpec({ allowMultiPosition: v })}
                         title={t('cfg.multiPos')} desc={t('my.spec.multiDesc')} />
              <SwitchRow on={form.spec.allowHedge} onChange={v => setSpec({ allowHedge: v })} disabled={!form.spec.allowMultiPosition}
                         title={t('cfg.hedge')} desc={form.spec.allowMultiPosition ? t('my.spec.hedgeDesc') : t('cfg.hedgeNeedMulti')} />
            </div>
          </section>

          <section id={secId('alert')} className="tr-sec">
            <SecHead no="04" title={t('my.idx.alert')} />
            {!ALERT_INTERVALS.includes(form.intervalCode) && (
              <p className="tr-note15"><AlertTriangle className="ic" />{t('my.alert.off15m')}</p>
            )}
            <SwitchRow on={form.alertEnabled} onChange={v => set({ alertEnabled: v })} title={t('my.alert.title')} desc={t('my.alert.desc')} />
            <div className={cn('tr-asub tr-fld', (!form.alertEnabled || !ALERT_INTERVALS.includes(form.intervalCode)) && 'dim')}>
              <div className="lab"><span>{t('my.alert.mult')}</span><span>{t('my.alert.multSub')}</span></div>
              <b className="tr-big num text-[34px]">×{form.alertThresholdMult.toFixed(1)}</b>
              <ScaleSlider ticks={SENS_T} values={[form.alertThresholdMult]} onChange={([v]) => set({ alertThresholdMult: v })}
                           snap={snapSens} step={(v, d) => snapSens(v + d * 0.1)} tickLabel={v => `×${v.toFixed(1)}`}
                           ariaLabels={[t('my.alert.multAria')]} disabled={!form.alertEnabled} />
              <div className="tr-thr num">
                {selected.map(s => {
                  const cfg = COIN_MAP[s];
                  const Icon = cfg.icon;
                  return <span key={s}><Icon />{cfg.name} {(ALERT_BASE[s] * Math.max(1, form.alertThresholdMult)).toFixed(2)}%</span>;
                })}
              </div>
              <p className="tr-hint mt-2">{t('my.alert.hint')}</p>
            </div>
            <div className="tr-swgrid">
              <SwitchRow on={form.reviewEnabled} onChange={v => set({ reviewEnabled: v })} title={t('cfg.reviewOn')} desc={t('my.alert.reviewDesc')} />
              <SwitchRow on={form.learnedRestrictionHold} onChange={v => set({ learnedRestrictionHold: v })}
                         title={t('cfg.restrictionHold')} desc={t('my.alert.restrictDesc')} />
            </div>
          </section>

          <section id={secId('model')} className="tr-sec" data-tour="byok">
            <div className="tr-sec-h">
              <h2><span className="no">05</span>{t('my.idx.model')}</h2>
              <Link to="/ai" className="d underline underline-offset-[3px]">{t('cfg.manageEndpoints')}</Link>
            </div>
            <LlmEndpointSelect endpoints={endpoints} value={form.llmEndpointId} onChange={id => set({ llmEndpointId: id })}
                               className="w-full max-w-[480px] h-[46px] border-foreground bg-transparent px-3 text-[15px] font-semibold" />
            <p className="tr-hint mt-2.5">{t('my.model.hint')}</p>
            <div className="mt-5">
              <SwitchRow on={endpointSearch && form.webSearchEnabled} onChange={v => set({ webSearchEnabled: v })}
                         disabled={!endpointSearch} title={t('my.model.search')}
                         desc={endpointSearch ? t('my.model.searchDesc') : t('my.model.searchNeedEndpoint')} />
            </div>
          </section>

          <PromptSection form={form} set={set} defaultInstructions={defaultInstructions} />

          <Toolbox />

          {exists && mine && (
            <DangerZone mine={mine} busy={busy != null}
                        onReset={carry => void run('reset', () => traderApi.reset(carry), t('toast.reset'), load)}
                        onDelete={name => void run('delete', () => traderApi.remove(name), t('toast.deleted'), load)} />
          )}

          {/* 保存条：有改动（或还没创建）才从底部升起来；跑引导时也亮着，好让最后一步指得到它 */}
          <div className={cn('tr-savebar', (!exists || dirty.length > 0 || tour) && 'show')} role="status" data-tour="save">
            <div>
              <div className="m">{exists ? t('my.save.dirty', { count: dirty.length }) : t('my.save.notCreated')}</div>
              <div className="s">{exists ? dirty.map(d => t(`my.idx.${d.id}`)).join('、') : t('my.save.createSub')}</div>
            </div>
            {errors.map(e => <span key={e} className="e"><AlertTriangle className="w-3.5 h-3.5" />{e}</span>)}
            <div className="r">
              {exists && (
                <button type="button" className="btn sm" disabled={dirty.length === 0 || busy != null} onClick={() => setForm(base)}>
                  {t('my.save.discard')}
                </button>
              )}
              <button type="button" className="btn sm orange" disabled={errors.length > 0 || busy != null || (exists && dirty.length === 0)}
                      onClick={save}>
                {busy === 'save' && <Loader2 className="ic animate-spin" />}{exists ? t('cfg.save') : t('cfg.create')}
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}

function SecHead({ no, title, desc }: { no?: string; title: string; desc?: string }) {
  return (
    <div className="tr-sec-h">
      <h2>{no && <span className="no">{no}</span>}{title}</h2>
      {desc && <span className="d">{desc}</span>}
    </div>
  );
}

/** 交易指令 + 平台系统提示词预览（跟着上面的配置实时变，变了的行闪一下） */
function PromptSection({ form, set, defaultInstructions }: {
  form: TraderUpsertRequest; set: (patch: Partial<TraderUpsertRequest>) => void; defaultInstructions: string;
}) {
  const { t } = useTranslation('ai');
  const [template, setTemplate] = useState<string[]>([]);
  const [open, setOpen] = useState(true);
  // 行号 → 闪了几次；单双数换着用两份一样的动画，连着变两次也能重播
  const [flash, setFlash] = useState<Record<number, number>>({});
  const prevRef = useRef<string[] | null>(null);

  // 与唤醒组装同一份文本，所见即所得。拖滑杆时连着变，停下来再拉；只认最后一次请求
  useEffect(() => {
    let stale = false;
    const timer = setTimeout(() => {
      traderApi.promptTemplate(form.intervalCode, form.symbols || 'BTCUSDT', form.spec, form.wakeWindow)
        .then(v => { if (!stale) setTemplate(v.split('\n')); })
        .catch(() => { if (!stale) setTemplate([]); });
    }, 300);
    return () => {
      stale = true;
      clearTimeout(timer);
    };
  }, [form.intervalCode, form.symbols, form.spec, form.wakeWindow]);

  useEffect(() => {
    const prev = prevRef.current;
    prevRef.current = template;
    if (!prev || prev.length === 0) return;
    const changed = template.map((l, i) => l !== prev[i] ? i : -1).filter(i => i >= 0);
    if (changed.length === 0) return;
    setFlash(f => {
      const next = { ...f };
      for (const i of changed) next[i] = (next[i] ?? 0) + 1;
      return next;
    });
    const timer = setTimeout(() => setFlash({}), 1800);
    return () => clearTimeout(timer);
  }, [template]);

  const prompt = form.customPrompt ?? '';
  const isDefault = !!defaultInstructions && prompt === defaultInstructions;
  return (
    <section id={secId('prompt')} className="tr-sec" data-tour="prompt">
      <SecHead no="06" title={t('my.idx.prompt')} desc={t('my.prompt.desc')} />
      <div className="tr-ph">
        <span className={cn('chip', isDefault ? 'mute' : 'border-primary text-primary')}>
          {isDefault ? t('my.prompt.isDefault') : t('my.prompt.edited')}
        </span>
        <span className="r">
          <button type="button" className="btn xs" disabled={!defaultInstructions || isDefault}
                  onClick={() => {
                    if (!prompt || window.confirm(t('cfg.resetDefaultPromptConfirm'))) set({ customPrompt: defaultInstructions });
                  }}>
            <RotateCcw className="w-3 h-3" />{t('cfg.resetDefaultPrompt')}
          </button>
          {/* 提示词生成：对话式写出交易指令，写完复制回这个框 */}
          <Link to="/prompt-gen" className="btn xs"><WandSparkles className="w-3 h-3" />{t('cfg.genPrompt')}</Link>
        </span>
      </div>
      <textarea className="tr-ta" value={prompt} maxLength={PROMPT_MAX} placeholder={t('cfg.customPromptPh')}
                aria-label={t('my.idx.prompt')} onChange={e => set({ customPrompt: e.target.value })} />
      <div className="tr-meter num">
        <span>{fmtNum(prompt.length, 0)} / {fmtNum(PROMPT_MAX, 0)}</span>
        <span className="bar"><i style={{ transform: `scaleX(${prompt.length / PROMPT_MAX})` }} /></span>
        <span>{t('my.prompt.live')}</span>
      </div>
      <div className={cn('tr-pv', open && 'open')}>
        <button type="button" className="tr-pv-h" aria-expanded={open} onClick={() => setOpen(o => !o)}>
          <Lock className="w-3.5 h-3.5" />{t('my.prompt.sys')}<span className="sub">{t('my.prompt.sysSub')}</span>
          <ChevronRight className="chev" />
        </button>
        {open && (
          <div className="tr-pv-b">
            {template.map((line, i) => (
              <div key={i} className={cn('tr-pv-l', flash[i] && (flash[i] % 2 ? 'flash' : 'flash2'))}>
                <span className="ln">{i + 1}</span><span>{line || ' '}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

/** 工具箱：它每次醒来拿到的全部工具，默认收着 */
function Toolbox() {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  const groups = [
    { key: 'trade', tools: TRADE_TOOLS },
    { key: 'data', tools: DATA_TOOLS },
  ] as const;
  return (
    <section id={secId('tools')} className="tr-sec">
      <SecHead title={t('my.idx.tools')} desc={t('skills.summary', { trade: TRADE_TOOLS.length, data: DATA_TOOLS.length })} />
      <button type="button" className="btn xs" aria-expanded={open} onClick={() => setOpen(o => !o)}>
        {open ? t('my.tools.close') : t('my.tools.open', { n: TRADE_TOOLS.length + DATA_TOOLS.length })}
      </button>
      {open && (
        <>
          <div className="tr-sk-grid">
            {groups.map(g => (
              <div key={g.key} className="tr-sk-g">
                <h3>{t(`skills.${g.key}`)}<span>{t(`skills.${g.key}Hint`)}</span></h3>
                {g.tools.map(id => (
                  <div key={id} className="tr-sk">
                    <b>{toolName(id)}</b>
                    <code>{id}</code>
                    <p><Trans ns="ai" i18nKey={`skills.desc.${id}`} components={[<span className="wn font-bold" />]} /></p>
                  </div>
                ))}
              </div>
            ))}
          </div>
          <p className="tr-sk-foot">{t('skills.foot')}</p>
        </>
      )}
    </section>
  );
}

/** 危险区：重置要按住一会儿，删除要打对名字；两个都撤不回 */
function DangerZone({ mine, busy, onReset, onDelete }: {
  mine: TraderOwnerView; busy: boolean; onReset: (carry: boolean) => void; onDelete: (name: string) => void;
}) {
  const { t } = useTranslation('ai');
  const [carry, setCarry] = useState(true);
  const [holding, setHolding] = useState(false);
  const [delName, setDelName] = useState('');
  const timer = useRef<number | null>(null);
  useEffect(() => () => { if (timer.current != null) clearTimeout(timer.current); }, []);

  const startHold = () => {
    if (busy || timer.current != null) return;
    setHolding(true);
    timer.current = window.setTimeout(() => {
      timer.current = null;
      setHolding(false);
      onReset(carry);
    }, HOLD_MS);
  };
  const stopHold = () => {
    if (timer.current != null) clearTimeout(timer.current);
    timer.current = null;
    setHolding(false);
  };
  const holdKey = (e: KeyboardEvent) => {
    if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) {
      e.preventDefault();
      startHold();
    }
  };

  return (
    <section id={secId('danger')} className="tr-sec danger">
      <SecHead title={t('my.idx.danger')} desc={t('my.danger.desc')} />
      <div className="tr-drow">
        <div>
          <b>{t('trader.reset')}</b>
          <p>{t('my.danger.resetDesc', { round: mine.pub.roundNo + 1 })}</p>
          <label className="tr-check">
            <input type="checkbox" checked={carry} onChange={e => setCarry(e.target.checked)} className="accent-primary" />
            {t('my.danger.carry')}
          </label>
        </div>
        <button type="button" className={cn('btn tr-hold', holding && 'holding')} disabled={busy}
                onPointerDown={startHold} onPointerUp={stopHold} onPointerLeave={stopHold} onPointerCancel={stopHold}
                onKeyDown={holdKey} onKeyUp={stopHold}>
          <i className="tr-hold-fill" />
          <span><RotateCcw className="ic" />{holding ? t('my.danger.holding') : t('my.danger.hold')}</span>
        </button>
      </div>
      <div className="tr-drow">
        <div>
          <b>{t('trader.delete')}</b>
          <p>{t('trader.deleteWarn')}</p>
          <div className="tr-delbox">
            <input value={delName} maxLength={NAME_MAX} onChange={e => setDelName(e.target.value)}
                   placeholder={t('my.danger.delPh', { name: mine.pub.name })} aria-label={t('my.danger.delAria')} />
          </div>
        </div>
        {/* 名字一字不差才点得动；后端也拦一道 */}
        <button type="button" className="btn border-loss text-loss" disabled={busy || delName.trim() !== mine.pub.name}
                onClick={() => onDelete(delName.trim())}>
          <Trash2 className="ic" />{t('my.danger.del')}
        </button>
      </div>
    </section>
  );
}
