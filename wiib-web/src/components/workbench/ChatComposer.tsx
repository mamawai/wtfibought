import { useCallback, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowUp, BookOpenCheck, MessageSquarePlus, Pencil, Plus, Square, Trash2, UserSearch, Zap } from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { HUB_NAME, PULSE_DOT } from './chatView';
import type { ChatIntent, ContextUsage, TraderFormKind } from '../../types';

/**
 * 出厂的四条快捷提问：行情 / 新闻 / 交易员 / 深研判 四种能力各一条。
 * 存的是词表 key——出厂文案要随语言走（用户自己改过的存 localStorage，那是用户的字，不翻）。
 */
const DEFAULT_SUGGEST_KEYS = ['composer.qMarket', 'composer.qNews', 'composer.qTrader', 'composer.qDeep'];
const SUGGEST_KEY = 'wiib-chat-suggests';
/** 最多四条：空态那一组只占输入框上方一两行，再多就把问候语挤没了 */
const SUGGEST_MAX = 4;

/** trader 动作入口的三项：点了只是把表单卡放进对话，执行要在卡上再按一次（存 key，渲染时翻） */
const TRADER_ACTIONS: { form: TraderFormKind; labelKey: string; icon: typeof Zap }[] = [
  { form: 'note', labelKey: 'composer.actionNote', icon: MessageSquarePlus },
  { form: 'wake', labelKey: 'term.manualWake', icon: Zap },
  { form: 'review', labelKey: 'term.reviewNow', icon: BookOpenCheck },
];

/** 单条消息字符上限，与后端 ChatWorkbenchController.MAX_MESSAGE_CHARS 同值；提示词生成的输入框也用它 */
export const MAX_MESSAGE_CHARS = 10_000;

/** 输入框自动长高的上限 */
const INPUT_MAX_H = 200;

/** Plus 菜单里的一项 */
const MENU_ITEM = 'w-full flex items-center gap-2 px-3 py-2 text-xs text-left hover:bg-surface-hover transition-colors';
/** 发送键与停止键同一个壳，轮流占同一个位置；提示词生成的输入框也用它 */
export const SEND_BTN = 'ml-auto w-8 h-8 bg-foreground text-background flex items-center justify-center shrink-0 active:scale-95 transition-all disabled:opacity-40 disabled:active:scale-100';

/** 上下文用到窗口的这个比例，下一轮开始前自动整理；与后端 agent.workbench.compact-trigger-ratio 同值 */
const COMPACT_RATIO = 0.9;

/** token 数按 K / M 显示：计量条只要量级 */
function fmtK(n: number): string {
  return n >= 1_000_000 ? `${+(n / 1_000_000).toFixed(2)}M` : `${Math.round(n / 1000)}K`;
}

/** 底栏的上下文计量条：数字加一截细条，用到整理线变警示色；估算的数字前带 ≈ */
function ContextMeter({ context }: { context: ContextUsage }) {
  const { t } = useTranslation('ai');
  const ratio = Math.min(context.tokens / context.window, 1);
  const full = ratio >= COMPACT_RATIO;
  return (
    <span className={cn('num shrink-0 inline-flex items-center gap-1.5', full && 'text-warning')}
          title={full ? t('composer.contextFull') : t('composer.contextTitle')}>
      {t('composer.context', { used: `${context.estimated ? '≈' : ''}${fmtK(context.tokens)}`, window: fmtK(context.window) })}
      <span className="w-10 h-1 rounded-full bg-muted overflow-hidden">
        <span className={cn('block h-full rounded-full', full ? 'bg-warning' : 'bg-primary/60')}
              style={{ width: `${ratio * 100}%` }} />
      </span>
    </span>
  );
}

/** 用户自己配过的那份；null=没配过，交给出厂四条（它们要随语言走，不能在这儿定死） */
function loadSuggests(): string[] | null {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(SUGGEST_KEY) || '');
    // 四条删光了存的就是 []，那就真一条都不显示——把"空"当成"没配过"的话，用户永远删不掉
    if (Array.isArray(v)) return v.filter((s): s is string => typeof s === 'string' && !!s.trim()).slice(0, SUGGEST_MAX);
  } catch { /* 没存过 / 存坏了都退回出厂四条 */ }
  return null;
}

/**
 * 输入区：一整块输入框（多行自适应）+ 框内底部工具行（左 Plus 菜单、右发送/停止）+ 状态行。
 * <p>
 * 敲字只动这一小块的本地 state，不带着上面整条消息列表重画；
 * 同时同步一份到 store —— 关面板会把整个面板卸载，只留本地 state 的话草稿就没了。
 * <p>
 * 快捷提问只在空态露面（有对话之后它就是噪音），可改可删（最多四条，存 localStorage）；
 * trader 三个动作、行为分析、编辑提示词都收进 Plus 菜单。
 * <p>
 * 行为分析是菜单里唯一一个"点了就真发消息"的项：它发的那句话带 BEHAVIOR 意图，
 * 后端据此给本轮钉一句"必须调 analyze_my_behavior、不许凭上下文里的旧结论作答"——按钮点下去就是明确的动作，不落到模型的自由裁量。
 */
export function ChatComposer({ loading, onSend, fullscreen, empty, stopping, onStop }: {
  loading: boolean;
  onSend: (text: string, intent?: ChatIntent) => void;
  /** 全屏时输入区跟正文共用一条限宽线，不然输入框会横跨整个屏幕 */
  fullscreen?: boolean;
  /** 对话还一条都没有：这时候才出快捷提问那一组 */
  empty?: boolean;
  /** 点了停止还没等到收尾：停止键这期间不可再点 */
  stopping?: boolean;
  /** 不传=这一轮没有可中断的本地流（后台轮在跑），发送键照常可用，用户能直接插话 */
  onStop?: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  // 只订这一个字段：计量条变了才重画输入区，消息列表的流式更新不牵连它
  const context = useSyncExternalStore(chatStore.subscribe, () => chatStore.getSnapshot().context);
  const [input, setInput] = useState(chatStore.getDraft);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // 没配过就用当前语言的出厂四条；配过的是用户自己的字，原样显示
  const [stored, setStored] = useState<string[] | null>(loadSuggests);
  const defaults = useMemo(() => DEFAULT_SUGGEST_KEYS.map(k => t(k)), [t]);
  const suggests = stored ?? defaults;
  const [editing, setEditing] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const closeEditing = useCallback(() => setEditing(false), []);
  const closeMenu = useCallback(() => setMenuOpen(false), []);
  useClickOutside(boxRef, closeEditing, editing);
  useClickOutside(menuRef, closeMenu, menuOpen);

  /** 内容撑到哪就多高，最多 200px（rows=1 是起始高度，靠这个函数往上长） */
  const fitHeight = (el: HTMLTextAreaElement) => {
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, INPUT_MAX_H)}px`;
  };

  // 挂载时把恢复回来的草稿撑开：只灌 value 不调高度的话，多行草稿回来只剩一行
  useLayoutEffect(() => {
    if (inputRef.current && inputRef.current.value) fitHeight(inputRef.current);
  }, []);

  const edit = useCallback((text: string) => {
    setInput(text);
    chatStore.setDraft(text);
  }, []);

  const submit = useCallback((text?: string) => {
    const msg = (text ?? input).trim();
    if (!msg) return;
    // 超限在发出前拦：发出去再被后端拒，输入框已经清空，用户手里就没那段文本了
    if (msg.length > MAX_MESSAGE_CHARS) {
      chatStore.pushError(t('chat.tooLong', { max: MAX_MESSAGE_CHARS }));
      return;
    }
    edit('');
    if (inputRef.current) inputRef.current.style.height = 'auto';
    onSend(msg);
  }, [input, edit, onSend, t]);

  const saveSuggests = useCallback((next: string[]) => {
    setStored(next);
    localStorage.setItem(SUGGEST_KEY, JSON.stringify(next));
    setEditing(false);
  }, []);

  return (
    // 底部 padding 避让 home indicator：移动端面板是 inset-0 铺满视口的（ChatDock 只配了
    // pt 那侧的安全区），10px 顶不住 34px 手势条。用 max() 而不是相加——手势条那块本身就是留白。
    // md 起固定回 10px：env() 是视口级的，iPad 上不贴底的浮窗也会拿到 34px，那是多余的
    <div className="border-t border-border px-3 pt-2 pb-[max(0.625rem,env(safe-area-inset-bottom))] md:pb-2.5 shrink-0">
      {/* 编辑面板浮在这一块上方，圈住的范围要连触发它的铅笔一起——只圈输入框的话，
          点铅笔会同时触发 onOutside 与 onClick，面板一闪就没 */}
      <div ref={boxRef} className={cn('relative w-full', fullscreen && 'mx-auto max-w-3xl')}>
        {editing && (
          <SuggestEditor initial={suggests} defaults={defaults} onSave={saveSuggests} onCancel={closeEditing} />
        )}

        {/* 快捷提问只在空态出现，点一下直接发；末尾那支铅笔是编辑入口（Plus 菜单里还有一个） */}
        {empty && (
          <div className="flex flex-wrap items-center gap-1.5 pb-2">
            {suggests.length > 0 ? (
              suggests.map(q => (
                <button
                  key={q}
                  onClick={() => submit(q)}
                  className="border border-border px-2.5 py-1 text-[11px] font-medium text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors"
                >
                  {q}
                </button>
              ))
            ) : (
              <span className="text-[11px] text-muted-foreground/70">{t('composer.noSuggests')}</span>
            )}
            <button
              onClick={() => setEditing(v => !v)}
              className={cn(
                'w-6 h-6 flex items-center justify-center transition-colors',
                editing ? 'text-primary bg-surface-hover' : 'text-muted-foreground hover:text-primary hover:bg-surface-hover',
              )}
              title={t('composer.editSuggests')}
              aria-label={t('composer.editSuggests')}
            >
              <Pencil className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        <div className="border border-border bg-card-2 transition-colors focus-within:border-foreground">
          <textarea
            ref={inputRef}
            rows={1}
            value={input}
            onChange={e => edit(e.target.value)}
            onInput={e => fitHeight(e.currentTarget)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder={loading ? t('composer.phBusy', { hub: HUB_NAME }) : t('composer.ph')}
            className="block w-full bg-transparent px-3 pt-2.5 text-[15px] leading-relaxed resize-none max-h-[200px] focus:outline-none placeholder:text-muted-foreground/60"
          />
          <div className="flex items-center gap-1 px-1.5 pb-1.5 pt-1">
            <div ref={menuRef} className="relative">
              {menuOpen && (
                <div className="absolute left-0 bottom-full mb-2 z-20 w-52 pt-card shadow-lg py-1 animate-in fade-in slide-in-from-bottom-2">
                  {TRADER_ACTIONS.map(a => (
                    <button
                      key={a.form}
                      onClick={() => { chatStore.openForm(a.form); setMenuOpen(false); }}
                      className={MENU_ITEM}
                    >
                      <a.icon className="w-3.5 h-3.5 text-primary shrink-0" /> {t(a.labelKey)}
                    </button>
                  ))}
                  <div className="my-1 border-t border-border" />
                  <button
                    onClick={() => { setMenuOpen(false); onSend(t('composer.behaviorAsk'), 'BEHAVIOR'); }}
                    className={MENU_ITEM}
                    title={t('composer.behaviorTitle')}
                  >
                    <UserSearch className="w-3.5 h-3.5 shrink-0" /> {t('composer.behaviorLabel')}
                  </button>
                  <button onClick={() => { setMenuOpen(false); setEditing(true); }} className={MENU_ITEM}>
                    <Pencil className="w-3.5 h-3.5 shrink-0" /> {t('composer.editSuggests')}
                  </button>
                </div>
              )}
              <button
                onClick={() => setMenuOpen(v => !v)}
                className={cn(
                  'w-8 h-8 flex items-center justify-center transition-colors',
                  menuOpen ? 'text-primary bg-surface-hover' : 'text-muted-foreground hover:text-foreground hover:bg-surface-hover',
                )}
                title={t('composer.more')}
                aria-label={t('composer.more')}
                aria-expanded={menuOpen}
              >
                <Plus className="w-4 h-4" />
              </button>
            </div>

            {/* 跑着且这一轮可中断时，发送键换成停止键；一旦敲了字就换回发送键，插话/排队照常能发 */}
            {loading && onStop && !input.trim() ? (
              <button
                onClick={onStop}
                disabled={stopping}
                className={SEND_BTN}
                title={t('chat.stopTitle')}
                aria-label={stopping ? t('chat.stopping') : t('chat.stop')}
              >
                <Square className="w-3 h-3 fill-current" />
              </button>
            ) : (
              <button
                onClick={() => submit()}
                disabled={!input.trim()}
                className={SEND_BTN}
                title={t('composer.sendTitle')}
                aria-label={t('composer.send')}
              >
                <ArrowUp className="w-4 h-4" />
              </button>
            )}
          </div>
        </div>

        <div className="flex justify-between gap-2 px-0.5 pt-1 text-[10px] text-muted-foreground/60">
          <span className="truncate">{t('composer.hint')}</span>
          <span className="shrink-0 inline-flex items-center gap-3">
            {context && <ContextMeter context={context} />}
            <span className="num inline-flex items-center gap-1.5">
              {loading && <span className={PULSE_DOT} />}
              {loading ? t('composer.busy', { hub: HUB_NAME }) : t('composer.ready', { hub: HUB_NAME })}
            </span>
          </span>
        </div>
      </div>
    </div>
  );
}

/** 快捷提问编辑面板：浮在输入区上方。改 / 删 / 加，最多四条，按保存才落 localStorage */
function SuggestEditor({ initial, defaults, onSave, onCancel }: {
  initial: string[];
  /** 「恢复默认」填回去的出厂四条（已按当前语言翻好） */
  defaults: string[];
  onSave: (next: string[]) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  // id 跟着行走：删中间一行时按下标做 key 会让 React 拿错输入框，把正在敲的字挪到别行去
  const [rows, setRows] = useState(
    () => (initial.length ? initial : ['']).map((text, i) => ({ id: i, text })),
  );
  // 后来新增的行从初始条数往后接着发号，撞不上已有的 id
  const seq = useRef(rows.length);

  return (
    <div className="absolute left-0 right-0 bottom-full mb-2 z-20 pt-card shadow-lg p-3 animate-in fade-in slide-in-from-bottom-2">
      <div className="flex items-center gap-2 mb-2">
        <Pencil className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        <span className="text-xs font-black shrink-0">{t('composer.editorTitle')}</span>
        <span className="text-[10px] text-muted-foreground truncate">{t('composer.editorHint', { max: SUGGEST_MAX })}</span>
      </div>

      <div className="space-y-1.5">
        {rows.map((r, i) => (
          <div key={r.id} className="flex items-center gap-1.5">
            <span className="num w-4 shrink-0 text-center text-[10px] text-muted-foreground">{i + 1}</span>
            <input
              value={r.text}
              onChange={e => setRows(prev => prev.map(x => (x.id === r.id ? { ...x, text: e.target.value } : x)))}
              placeholder={t('composer.editorPh')}
              className="flex-1 min-w-0 border border-border bg-card-2 px-2 py-1.5 text-xs focus:outline-none focus:border-foreground"
            />
            <button
              onClick={() => setRows(prev => prev.filter(x => x.id !== r.id))}
              className="shrink-0 w-6 h-6 flex items-center justify-center text-muted-foreground/60 hover:text-loss hover:bg-loss/10 transition-colors"
              title={t('composer.removeRow')}
              aria-label={t('composer.removeRowAria')}
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        ))}
      </div>

      {rows.length < SUGGEST_MAX && (
        <button
          onClick={() => setRows(prev => [...prev, { id: seq.current++, text: '' }])}
          className="mt-1.5 w-full border border-dashed border-border py-1.5 text-[11px] font-bold text-primary hover:bg-primary/6 flex items-center justify-center gap-1 transition-colors"
        >
          <Plus className="w-3 h-3" /> {t('composer.addRow')}
        </button>
      )}

      <div className="flex items-center gap-2 mt-2.5">
        <button
          onClick={() => setRows(defaults.map(text => ({ id: seq.current++, text })))}
          className="text-[11px] font-bold text-muted-foreground hover:text-foreground hover:underline"
        >
          {t('composer.restore')}
        </button>
        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={onCancel}
            className="border border-border px-3 h-7 text-[11px] font-bold text-muted-foreground hover:bg-surface-hover transition-colors"
          >
            {t('common:cancel')}
          </button>
          <button
            onClick={() => onSave(rows.map(r => r.text.trim()).filter(Boolean).slice(0, SUGGEST_MAX))}
            className="px-3 h-7 text-[11px] font-bold bg-primary text-primary-foreground hover:brightness-105 transition-all"
          >
            {t('common:save')}
          </button>
        </div>
      </div>
    </div>
  );
}
