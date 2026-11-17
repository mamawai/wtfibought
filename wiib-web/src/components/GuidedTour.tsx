import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { createPortal } from 'react-dom';
import { useAnchoredPosition } from '../hooks/useAnchoredPosition';
import './GuidedTour.css';

export interface TourStep {
  /** 目标元素的 data-tour 值 */
  target: string;
  title: string;
  body: string;
}

interface Rect { top: number; left: number; width: number; height: number }

const PAD = 10;

/**
 * 分步引导（样式见 GuidedTour.css）：四周压暗、当前那一块描一圈橙，旁边一张浮卡讲这一步。
 *
 * 描圈的那块照常能操作——这是"配置教程"不是走马灯，用户要能跟着一步步真填进去。
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
  const [card, setCard] = useState<HTMLDivElement | null>(null);
  const scrolledFor = useRef<string>('');
  // 壳文案归本组件；步骤 title/body 由调用方翻好了传进来
  const { t } = useTranslation('layout');

  const step = steps[idx];
  const pos = useAnchoredPosition(target, card, open && rect != null, 'bottom', 12);

  // 目标定位：换步时滚动进视野，之后随滚动/缩放持续跟随
  const measure = useCallback(() => {
    if (!step) return;
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
    if (!open || !step) return;
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
    if (idx >= steps.length - 1) close();
    else setIdx(i => i + 1);
  }, [idx, steps.length, close]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        close();
        return;
      }
      // 焦点在输入框里时左右键是移光标，不翻步
      const el = e.target as HTMLElement;
      if (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)) return;
      if (e.key === 'ArrowRight') next();
      else if (e.key === 'ArrowLeft') setIdx(i => Math.max(0, i - 1));
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, close, next]);

  if (!open || !step || !rect) return null;

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
      {/* 视觉层：打洞的暗幕 + 一圈橙，不吃事件 */}
      <div className="tour-ring" style={{ top: rect.top, left: rect.left, width: rect.width, height: rect.height }} />
      {/* 交互层：四块透明挡板围住洞口，洞内照常可点可输入 */}
      {blockers.map((b, i) => (
        <div key={i} className="tour-block" onClick={close}
             style={{ top: b.top, left: b.left, width: b.width, height: b.height }} />
      ))}

      <div ref={setCard} role="dialog" aria-label={step.title} className="tour-card"
           style={{ top: pos?.top ?? 0, left: pos?.left ?? 0, opacity: pos ? 1 : 0 }}>
        <small>{idx + 1} / {steps.length}</small>
        <h3>{step.title}</h3>
        <p>{step.body}</p>
        <div className="f">
          <button type="button" className="btn plain" onClick={close}>{t('tour.skip')}</button>
          <span>
            {idx > 0 && <button type="button" className="btn" onClick={() => setIdx(i => i - 1)}>{t('tour.prev')}</button>}
            <button type="button" className="btn ink" onClick={next}>{idx >= steps.length - 1 ? t('tour.done') : t('tour.next')}</button>
          </span>
        </div>
      </div>
    </>,
    document.body,
  );
}
