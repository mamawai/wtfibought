import { Fragment, useEffect, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowDown, ArrowUp, ArrowUpDown, Braces, ChevronDown, CircleHelp, Clock, LoaderCircle, X, type LucideIcon,
} from 'lucide-react';
import { cx } from '../format';
import { useReveal } from '../reveal';
import type { SortState } from '../sort';

/** 一节：2px 墨线开头，标题、一句说明、折起来的详细说明、正文；进视口时线从左画到右 */
export function Card({ title, actions, hint, more, children, className }: {
  title?: ReactNode;
  actions?: ReactNode;
  hint?: ReactNode;
  more?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  const [ref, shown] = useReveal<HTMLElement>();
  // 线头橙方块跑完一趟了没有
  const [drawn, setDrawn] = useState(false);
  return (
    <section ref={ref} className={cx('sec rv', shown && 'in', drawn && 'drawn', className)}
             onAnimationEnd={e => { if (e.target === e.currentTarget && e.pseudoElement === '::after') setDrawn(true); }}>
      {(title || actions) && (
        <header className="sec-h">
          {title && <h2>{title}</h2>}
          {actions && <div className="sec-acts">{actions}</div>}
        </header>
      )}
      {hint && <p className="sec-hint">{hint}</p>}
      {more && <More>{more}</More>}
      <div className="sec-body">{children}</div>
    </section>
  );
}

/** 折起来的详细说明，点"展开说明"（或 label）才出来；文案里的换行照样换行 */
export function More({ label, children }: { label?: ReactNode; children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <details className="sec-more">
      <summary>{label ?? t('common.more')}<ChevronDown /></summary>
      <div className="whitespace-pre-line">{children}</div>
    </details>
  );
}

// 说明浮层的最大宽度，靠右放不下就往左挪
const TIP_W = 352;

/** 悬停说明：指上去（手机上点一下）出一块墨框说明，挂在 body 上，表格的滚动框裁不掉；没包东西就是一个小问号 */
export function Tip({ text, children }: { text: ReactNode; children?: ReactNode }) {
  const [at, setAt] = useState<{ x: number; y: number; up: boolean } | null>(null);
  const show = (e: { currentTarget: HTMLElement }) => {
    const r = e.currentTarget.getBoundingClientRect();
    // 下面放不下就放上面
    const up = r.bottom + 160 > window.innerHeight;
    setAt({ x: Math.max(8, Math.min(r.left, window.innerWidth - TIP_W - 8)), y: up ? r.top - 6 : r.bottom + 6, up });
  };
  const hide = () => setAt(null);
  return (
    <span className={children ? 'tip' : 'tip help'} tabIndex={children ? undefined : 0} onMouseEnter={show}
          onMouseLeave={hide} onFocus={show} onBlur={hide}>
      {children ?? <CircleHelp />}
      {at && createPortal(
        <span role="tooltip" className="tip-box" style={{ left: at.x, top: at.y, maxWidth: TIP_W,
          transform: at.up ? 'translateY(-100%)' : undefined }}>{text}</span>, document.body)}
    </span>
  );
}

/** 按钮被别的后台任务挡着时，写一句在等谁 */
export function Waiting({ why, className }: { why: string | null | undefined; className?: string }) {
  if (!why) return null;
  return <p className={cx('flex items-center gap-1 text-[12px] text-warn', className)}><Clock className="w-3 h-3 shrink-0" />{why}</p>;
}

type Variant = 'primary' | 'outline' | 'ghost' | 'gain' | 'loss' | 'icon';

const VARIANTS: Record<Variant, string> = {
  primary: 'btn orange',
  outline: 'btn',
  ghost: 'btn ghost',
  gain: 'btn gain',
  loss: 'btn loss',
  icon: 'ibtn',
};

/** icon 是只有图标的 30 方格钮，记得给 aria-label */
export function Button({ variant = 'outline', icon: Icon, loading, className, children, disabled, ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; icon?: LucideIcon; loading?: boolean }) {
  return (
    <button {...rest} disabled={disabled || loading} className={cx(VARIANTS[variant], className)}>
      {loading ? <LoaderCircle className="ic animate-spin" /> : Icon && <Icon className="ic" />}
      {children}
    </button>
  );
}

export type Tone = 'gain' | 'loss' | 'warn' | 'info' | 'muted' | 'primary';

/** pulse 是正在进行的：前面一颗方点在呼吸 */
export function Badge({ tone = 'muted', icon: Icon, pulse, children, className }: {
  tone?: Tone;
  icon?: LucideIcon;
  pulse?: boolean;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span className={cx('chip', tone, className)}>
      {pulse && <i className="dot" />}
      {Icon && <Icon />}
      {children}
    </span>
  );
}

/** 灰轨上一条墨线，线头橙方块 */
export function Progress({ done, total }: { done: number; total: number }) {
  const ratio = total > 0 ? Math.min(1, done / total) : 0;
  return <div className="prog"><i style={{ width: `${ratio * 100}%` }} /></div>;
}

export function Spinner({ className }: { className?: string }) {
  return <LoaderCircle className={cx('w-4 h-4 animate-spin text-muted', className)} />;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-2 text-[13px] text-muted">{children}</p>;
}

const DIGITS = [...'0123456789'].map(d => <i key={d}>{d}</i>);

/**
 * 数字一位一位滚到位：每位是一条 0-9 竖排带子，祖先挂上 in 才滚到目标位；读屏和复制拿的是原字符串。
 * 按空格切成词，词内不折行，只在空格处换行
 */
function Roll({ text }: { text: string }) {
  const words = text.split(' ');
  // 每个词第一个字在整串里的位置，按位置错开滚动
  const starts = words.map((_, w) => words.slice(0, w).reduce((n, x) => n + x.length + 1, 0));
  return (
    <>
      <span className="roll" aria-hidden="true">
        {words.map((word, w) => (
          <Fragment key={w}>
            {w > 0 && ' '}
            <span className="whitespace-nowrap">
              {[...word].map((c, i) => (/[0-9]/.test(c)
                ? <span key={i} className="dg"><span className={`dstrip n${c} p${Math.min(starts[w] + i, 9)}`}>{DIGITS}</span></span>
                : <span key={i} className="dc">{c}</span>))}
            </span>
          </Fragment>
        ))}
      </span>
      <span className="sr-only">{text}</span>
    </>
  );
}

/** 指标条：cols 是一行几格，格间细线；ruled 是单独站着的，顶上自己画墨线 */
export function Strip({ cols, ruled, className, children }: {
  cols: 2 | 3 | 4 | 5;
  ruled?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return <div className={cx('strip', `c${cols}`, ruled && 'ruled', className)}>{children}</div>;
}

/** 指标条的一格：小标签 + 窄体大数；sm 给日期这类长值，lg 给记分牌 */
export function Stat({ label, children, hint, size, tone }: {
  label: ReactNode;
  children: string | number;
  hint?: ReactNode;
  size?: 'sm' | 'lg';
  tone?: string;
}) {
  const [ref, shown] = useReveal<HTMLDivElement>();
  return (
    <div ref={ref} className={cx('cell rv', shown && 'in')}>
      <div className="k">{label}</div>
      <div className={cx('v num', size, tone)}><Roll text={String(children)} /></div>
      {hint && <div className="h">{hint}</div>}
    </div>
  );
}

/** 可排序表头 */
export function SortTh<K extends string>({ k, sort, onSort, children, right = true, title }: {
  k: K;
  sort: SortState<K>;
  onSort: (k: K) => void;
  children: ReactNode;
  right?: boolean;
  title?: string;
}) {
  const active = sort.key === k;
  const Arrow = !active ? ArrowUpDown : sort.dir === 'asc' ? ArrowUp : ArrowDown;
  return (
    <th className={right ? 'r' : undefined} title={title}>
      <button onClick={() => onSort(k)}
              className={cx('inline-flex items-center gap-1 cursor-pointer transition-colors hover:text-fg', active && 'text-fg')}>
        {children}
        <Arrow className={cx('w-3 h-3', !active && 'opacity-35')} />
      </button>
    </th>
  );
}

/** 折叠的原始 JSON */
export function RawJson({ value }: { value: unknown }) {
  const { t } = useTranslation();
  return (
    <details className="group border-t border-line">
      <summary className="flex items-center gap-1.5 py-2.5 text-[13px] text-muted cursor-pointer select-none transition-colors hover:text-fg">
        <Braces className="w-3.5 h-3.5" />
        {t('common.raw')}
        <ChevronDown className="w-3.5 h-3.5 ml-auto transition-transform group-open:rotate-180" />
      </summary>
      <pre className="max-h-[28rem] overflow-auto bg-panel-2 p-3 text-[11.5px] leading-relaxed">
        {JSON.stringify(value, null, 2)}
      </pre>
    </details>
  );
}

/** 右侧抽屉：从右滑进来，点遮罩或按 Esc 关 */
export function Drawer({ open, onClose, title, children }: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="drawer-mask" onClick={onClose} />
      <aside className="drawer">
        <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-fg bg-bg px-6 py-4">
          <div className="min-w-0 flex-1">{title}</div>
          <Button variant="icon" icon={X} onClick={onClose} aria-label={t('common.close')} />
        </header>
        <div className="space-y-6 px-6 py-6">{children}</div>
      </aside>
    </div>
  );
}
