import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { createPortal } from 'react-dom';
import { HelpCircle } from 'lucide-react';
import { useAnchoredPosition } from '../hooks/useAnchoredPosition';
import './HelpTip.css';

/**
 * 说明小问号：鼠标停上去 80ms 后出墨色气泡、移开就收；触屏和键盘点一下开、点外面收（样式见 HelpTip.css）。
 *
 * 气泡走 portal 挂到 body + position:fixed：absolute 定位在图标里的话，
 * 图标一靠近屏幕边缘气泡就溢出屏幕（手机上几乎必现），外层只要有 overflow:hidden 还会被整块裁掉。
 * 现在由 useAnchoredPosition 按实测位置钳进视口。气泡默认在上方，放不下会自动翻面。
 */
export function HelpTip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  // 锚点也用回调 ref 存进 state：render 期间读 ref.current 在并发渲染下不安全
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null);
  const [bubble, setBubble] = useState<HTMLSpanElement | null>(null);
  const pos = useAnchoredPosition(anchor, bubble, open, 'top');
  // 只翻壳子上的图标可读名，气泡正文 text 由调用方翻好了传进来
  const { t } = useTranslation('layout');
  const hoverTimer = useRef(0);
  // 这一下点击是不是鼠标点的：鼠标靠悬停开合，点击就不再来回切
  const byMouse = useRef(false);

  useEffect(() => () => window.clearTimeout(hoverTimer.current), []);

  // 点击外部 / Esc 关闭。气泡在 body 上，不能靠祖先节点判断"外部"，改用两个 ref 各判一次
  useEffect(() => {
    if (!open) {
      return;
    }
    const onDown = (e: MouseEvent | TouchEvent) => {
      const node = e.target as Node;
      if (!anchor?.contains(node) && !bubble?.contains(node)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('touchstart', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('touchstart', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, anchor, bubble]);

  return (
    <>
      <button
        ref={setAnchor}
        type="button"
        className="tip"
        aria-label={t('helpTip')}
        aria-expanded={open}
        onPointerEnter={e => {
          if (e.pointerType !== 'mouse') return;
          hoverTimer.current = window.setTimeout(() => setOpen(true), 80);
        }}
        onPointerLeave={e => {
          if (e.pointerType !== 'mouse') return;
          window.clearTimeout(hoverTimer.current);
          setOpen(false);
        }}
        onPointerDown={e => { byMouse.current = e.pointerType === 'mouse'; }}
        onClick={e => {
          e.stopPropagation();
          if (byMouse.current) { byMouse.current = false; return; }
          setOpen(v => !v);
        }}
      >
        <HelpCircle className="ic" />
      </button>
      {open && createPortal(
        <span
          ref={setBubble}
          role="tooltip"
          className="tb2"
          // 首帧还没量出位置，先藏着，免得在左上角闪一下
          style={{ top: pos?.top ?? 0, left: pos?.left ?? 0, visibility: pos ? 'visible' : 'hidden' }}
        >
          {text}
        </span>,
        document.body,
      )}
    </>
  );
}
