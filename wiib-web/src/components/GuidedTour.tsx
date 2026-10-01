import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { cn } from '../lib/utils';
import { useAnchoredPosition } from '../hooks/useAnchoredPosition';

export interface TourStep {
  /** 目标元素的 data-tour 值 */
  target: string;
  title: string;
  body: string;
}

interface Rect { top: number; left: number; width: number; height: number }

const PAD = 6;

/**
 * 分步高亮引导：遮住四周、亮出当前字段、旁边贴气泡说明。
 *
 * 高亮区可正常操作——这是"配置教程"不是走马灯，用户要能跟着一步步真填进去。
 * 实现上分两层：box-shadow 打洞的那层只管好看（pointer-events:none 全透传），
 * 挡交互靠四块透明矩形围出的框，中间的洞天然漏出去。单靠 box-shadow 做不到"只挡外面"。
 */
export function GuidedTour({ steps, open, onClose }: {
  steps: TourStep[];
  open: boolean;
  onClose: () => void;
}) {
  const [idx, setIdx] = useState(0);
  const [rect, setRect] = useState<Rect | null>(null);
  const [target, setTarget] = useState<HTMLElement | null>(null);
  const [bubble, setBubble] = useState<HTMLDivElement | null>(null);
  const scrolledFor = useRef<string>('');
  // 壳文案归本组件；步骤 title/body 由调用方翻好了传进来
  const { t } = useTranslation('layout');

  const step = steps[idx];
  const pos = useAnchoredPosition(target, bubble, open && rect != null, 'bottom', 12);

  // 目标定位：换步时滚动进视野，之后随滚动/缩放持续跟随
  const measure = useCallback(() => {
    if (!step) {
      return;
    }
    const el = document.querySelector<HTMLElement>(`[data-tour="${step.target}"]`);
    if (!el) {
      setTarget(null);
      setRect(null);
      return;
    }
    setTarget(el);
    const r = el.getBoundingClientRect();
    setRect({ top: r.top - PAD, left: r.left - PAD, width: r.width + PAD * 2, height: r.height + PAD * 2 });
  }, [step]);

  useLayoutEffect(() => {
    if (!open || !step) {
      return;
    }
    const el = document.querySelector<HTMLElement>(`[data-tour="${step.target}"]`);
    // 每步只滚一次：跟随重算时再滚会和用户的手动滚动打架
    if (el && scrolledFor.current !== step.target) {
      scrolledFor.current = step.target;
      el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
    // rAF 而非直接调：effect 体内同步 setState 会被判为级联渲染
    const raf = requestAnimationFrame(measure);
    const settle = window.setTimeout(measure, 400); // 等平滑滚动落定再校一次
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, true);
    return () => {
      cancelAnimationFrame(raf);
      window.clearTimeout(settle);
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure, true);
    };
  }, [open, step, measure]);

  const close = useCallback(() => {
    setIdx(0);
    scrolledFor.current = '';
    onClose();
  }, [onClose]);

  const next = useCallback(() => {
    if (idx >= steps.length - 1) {
      close();
    } else {
      setIdx(i => i + 1);
    }
  }, [idx, steps.length, close]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        close();
        return;
      }
      // 焦点在输入框里时左右键是移光标，不翻步
      const el = e.target as HTMLElement;
      if (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)) {
        return;
      }
      if (e.key === 'ArrowRight') {
        next();
      } else if (e.key === 'ArrowLeft') {
        setIdx(i => Math.max(0, i - 1));
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, close, next]);

  if (!open || !step || !rect) {
    return null;
  }

  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const blockers: Rect[] = [
    { top: 0, left: 0, width: vw, height: Math.max(0, rect.top) },
    { top: rect.top + rect.height, left: 0, width: vw, height: Math.max(0, vh - rect.top - rect.height) },
    { top: rect.top, left: 0, width: Math.max(0, rect.left), height: rect.height },
    { top: rect.top, left: rect.left + rect.width, width: Math.max(0, vw - rect.left - rect.width), height: rect.height },
  ];

  return createPortal(
    <>
      {/* 视觉层：打洞的暗幕，不吃事件 */}
      <div
        className="fixed z-[70] rounded-lg pointer-events-none transition-all duration-200"
        style={{
          top: rect.top, left: rect.left, width: rect.width, height: rect.height,
          boxShadow: '0 0 0 9999px rgba(0,0,0,0.62)',
          outline: '2px solid var(--color-primary)',
        }}
      />
      {/* 交互层：四块透明挡板围住洞口，洞内照常可点可输入 */}
      {blockers.map((b, i) => (
        <div key={i} className="fixed z-[71]" onClick={close}
             style={{ top: b.top, left: b.left, width: b.width, height: b.height }} />
      ))}

      <div
        ref={setBubble}
        style={{ top: pos?.top ?? 0, left: pos?.left ?? 0 }}
        className={cn(
          'fixed z-[72] w-[min(20rem,calc(100vw-16px))] rounded-lg border bg-card p-3.5 shadow-xl space-y-2',
          pos ? 'opacity-100' : 'opacity-0',
        )}
      >
        <div className="flex items-start gap-2">
          <span className="text-xs font-extrabold flex-1">{step.title}</span>
          <button onClick={close} aria-label={t('tour.skip')}
                  className="text-muted-foreground hover:text-foreground shrink-0">
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
        <p className="text-[11px] text-muted-foreground leading-relaxed whitespace-pre-line">{step.body}</p>
        <div className="flex items-center gap-2 pt-0.5">
          <span className="text-[10px] text-muted-foreground num">{idx + 1} / {steps.length}</span>
          <div className="ml-auto flex gap-1.5">
            {idx > 0 && (
              <button onClick={() => setIdx(i => i - 1)}
                      className="border border-border hover:bg-surface-hover rounded-lg px-2.5 py-1 text-[11px] font-bold text-muted-foreground">
                {t('tour.prev')}
              </button>
            )}
            <button onClick={next}
                    className="border border-primary/60 bg-card-2 text-primary hover:bg-surface-hover rounded-lg px-2.5 py-1 text-[11px] font-bold">
              {idx >= steps.length - 1 ? t('tour.done') : t('tour.next')}
            </button>
          </div>
        </div>
      </div>
    </>,
    document.body,
  );
}
