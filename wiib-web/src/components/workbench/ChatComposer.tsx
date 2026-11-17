import { useCallback, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowUp, MessageCircle, NotebookPen, Pencil, Plus, Square, Trash2, UserSearch, Zap } from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { HUB_NAME } from './chatView';
import type { ChatIntent, ContextUsage, TraderFormKind } from '../../types';
import './ChatComposer.css';

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
  { form: 'note', labelKey: 'composer.actionNote', icon: MessageCircle },
  { form: 'wake', labelKey: 'term.manualWake', icon: Zap },
  { form: 'review', labelKey: 'term.reviewNow', icon: NotebookPen },
];

/** 单条消息字符上限，与后端 ChatWorkbenchController.MAX_MESSAGE_CHARS 同值；提示词生成的输入框也用它 */
export const MAX_MESSAGE_CHARS = 10_000;

/** 输入框自动长高的上限 */
const INPUT_MAX_H = 200;

/** 上下文用到窗口的这个比例，下一轮开始前自动整理；与后端 agent.workbench.compact-trigger-ratio 同值 */
const COMPACT_RATIO = 0.9;

/** token 数按 K / M 显示：计量只要量级 */
function fmtK(n: number): string {
  return n >= 1_000_000 ? `${+(n / 1_000_000).toFixed(2)}M` : `${Math.round(n / 1000)}K`;
}

/** 底栏的上下文用量：用到整理线换成一句提醒；估算的数字前带 ≈ */
function ContextMeter({ context }: { context: ContextUsage }) {
  const { t } = useTranslation('ai');
  const full = context.tokens / context.window >= COMPACT_RATIO;
  return (
    <span className={cn('ctx', full && 'full')} title={t('composer.contextTitle')}>
      {full ? t('composer.contextFull')
        : t('composer.context', { used: `${context.estimated ? '≈' : ''}${fmtK(context.tokens)}`, window: fmtK(context.window) })}
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
 * 输入区（样式见 ChatComposer.css）：一块浅灰输入框（多行自适应）+ 框内底部一行（左「+」菜单、右发送 / 停止）+ 一行小字。
 * <p>
 * 敲字只动这一小块的本地 state，不带着上面整条消息列表重画；
 * 同时同步一份到 store —— 关面板会把整个面板卸载，只留本地 state 的话草稿就没了。
 * <p>
 * 快捷提问只在空态露面（有对话之后它就是噪音），可改可删（最多四条，存 localStorage）；
 * trader 三个动作、行为分析、编辑快捷提问都收进「+」菜单。
 * <p>
 * 行为分析是菜单里唯一一个"点了就真发消息"的项：它发的那句话带 BEHAVIOR 意图，
 * 后端据此给本轮钉一句"必须调 analyze_my_behavior、不许凭上下文里的旧结论作答"——按钮点下去就是明确的动作，不落到模型的自由裁量。
 */
export function ChatComposer({ loading, onSend, empty, stopping, onStop }: {
  loading: boolean;
  onSend: (text: string, intent?: ChatIntent) => void;
  /** 对话还一条都没有：这时候才出快捷提问那一组 */
  empty?: boolean;
  /** 点了停止还没等到收尾：停止键这期间不可再点 */
  stopping?: boolean;
  /** 不传=这一轮没有可中断的本地流（后台轮在跑），发送键照常可用，用户能直接插话 */
  onStop?: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  // 只订这一个字段：用量变了才重画输入区，消息列表的流式更新不牵连它
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
    // 编辑卡浮在这一块上方，圈住的范围要连触发它的铅笔一起——只圈输入框的话，
    // 点铅笔会同时触发 onOutside 与 onClick，卡一闪就没
    <div ref={boxRef} className="ccomp">
      {editing && <SuggestEditor initial={suggests} defaults={defaults} onSave={saveSuggests} onCancel={closeEditing} />}

      {/* 快捷提问只在空态出现，点一下直接发；末尾那支铅笔是编辑入口（「+」菜单里还有一个） */}
      {empty && (
        <div className="csug">
          {suggests.length > 0
            ? suggests.map(q => <button key={q} type="button" className="chip" onClick={() => submit(q)}>{q}</button>)
            : <span>{t('composer.noSuggests')}</span>}
          <button type="button" className="pen" aria-pressed={editing} onClick={() => setEditing(v => !v)}
                  title={t('composer.editSuggests')} aria-label={t('composer.editSuggests')}>
            <Pencil className="ic" />
          </button>
        </div>
      )}

      <div className="cbox">
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
          aria-label={t('composer.ph')}
        />
        <div className="crow">
          <div ref={menuRef} className={menuOpen ? 'open' : undefined}>
            <button type="button" className="mb" onClick={() => setMenuOpen(v => !v)}
                    title={t('composer.more')} aria-label={t('composer.more')} aria-expanded={menuOpen}>
              <Plus className="ic" />
            </button>
            {menuOpen && (
              <div className="menu" role="menu">
                {TRADER_ACTIONS.map(a => (
                  <button key={a.form} type="button" role="menuitem" onClick={() => { chatStore.openForm(a.form); setMenuOpen(false); }}>
                    <a.icon className="ic" />{t(a.labelKey)}
                  </button>
                ))}
                <hr />
                <button type="button" role="menuitem" title={t('composer.behaviorTitle')}
                        onClick={() => { setMenuOpen(false); onSend(t('composer.behaviorAsk'), 'BEHAVIOR'); }}>
                  <UserSearch className="ic" />{t('composer.behaviorLabel')}
                </button>
                <button type="button" role="menuitem" onClick={() => { setMenuOpen(false); setEditing(true); }}>
                  <Pencil className="ic" />{t('composer.editSuggests')}
                </button>
              </div>
            )}
          </div>

          {/* 跑着且这一轮可中断时，发送键换成停止键；一旦敲了字就换回发送键，插话/排队照常能发 */}
          {loading && onStop && !input.trim() ? (
            <button type="button" className="csend stop" onClick={onStop} disabled={stopping}
                    title={t('chat.stopTitle')} aria-label={stopping ? t('chat.stopping') : t('chat.stop')}>
              <Square className="ic" />
            </button>
          ) : (
            <button type="button" className="csend" onClick={() => submit()} disabled={!input.trim()}
                    title={t('composer.sendTitle')} aria-label={t('composer.send')}>
              <ArrowUp className="ic" />
            </button>
          )}
        </div>
      </div>

      <div className="cfoot">
        <span>{t('composer.hint')}</span>
        <span>
          {context && <ContextMeter context={context} />}
          <span className="st">
            {loading && <i />}
            {loading ? t('composer.busy', { hub: HUB_NAME }) : t('composer.ready', { hub: HUB_NAME })}
          </span>
        </span>
      </div>
    </div>
  );
}

/** 快捷提问编辑卡：浮在输入区上方。改 / 删 / 加，最多四条，按保存才落 localStorage */
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
    <div className="cedit">
      <h4>{t('composer.editorTitle')}</h4>
      <small>{t('composer.editorHint', { max: SUGGEST_MAX })}</small>
      {rows.map(r => (
        <div key={r.id} className="r">
          <label className="inp">
            <input value={r.text} placeholder={t('composer.editorPh')}
                   onChange={e => setRows(prev => prev.map(x => (x.id === r.id ? { ...x, text: e.target.value } : x)))} />
          </label>
          <button type="button" className="ib" onClick={() => setRows(prev => prev.filter(x => x.id !== r.id))}
                  title={t('composer.removeRow')} aria-label={t('composer.removeRowAria')}>
            <Trash2 className="ic" />
          </button>
        </div>
      ))}
      <div className="f">
        <button type="button" className="btn sm" disabled={rows.length >= SUGGEST_MAX}
                onClick={() => setRows(prev => [...prev, { id: seq.current++, text: '' }])}>
          <Plus className="ic" />{t('composer.addRow')}
        </button>
        <button type="button" className="btn sm plain" onClick={() => setRows(defaults.map(text => ({ id: seq.current++, text })))}>
          {t('composer.restore')}
        </button>
        <span className="sp" />
        <button type="button" className="btn sm" onClick={onCancel}>{t('common:cancel')}</button>
        <button type="button" className="btn sm ink" onClick={() => onSave(rows.map(r => r.text.trim()).filter(Boolean).slice(0, SUGGEST_MAX))}>
          {t('common:save')}
        </button>
      </div>
    </div>
  );
}
