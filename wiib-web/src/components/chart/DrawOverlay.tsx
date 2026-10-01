import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Copy, Pencil, Trash2 } from 'lucide-react';
import { cn } from '../../lib/utils';
import { useClickOutside } from '../../hooks/useClickOutside';
import { DRAW_PALETTE, LINE_WIDTHS, STYLE_CAPS, type LineDash } from '../../lib/chartDrawings';
import type { DrawingsApi } from './useDrawings';

/**
 * 画线浮层（CandleChart / BacktestChart 共用）：文字标注输入框 + 选中图形的属性条。
 * 放在图表宿主 div 的兄弟位置：指针事件到不了画线层的监听，点属性条不会误触图表。
 */
export function DrawOverlay({ d }: { d: DrawingsApi }) {
  const { t } = useTranslation('market');
  return (
    <>
      {/* 文字标注输入。Esc 会先把锚点清掉，所以随后 unmount 触发的 blur→commit 是空转。
          透明浮层：文字直接浮在图上，所见即所得（提交后的标注就长这样），只留一条虚线下划线。
          key 带位置：改另一条标注时输入框重挂，defaultValue 才会换成那条的原文 */}
      {d.textEdit && (
        <input key={`${d.textEdit.x},${d.textEdit.y}`} autoFocus placeholder={t('chart.textPlaceholder')}
               defaultValue={d.textEdit.value}
               onKeyDown={e => {
                 // 输入法组字时的回车是上屏，不是提交（Safari 组字结束后才发 keydown，只能靠 keyCode 229 认）
                 if (e.key === 'Enter' && !e.nativeEvent.isComposing && e.nativeEvent.keyCode !== 229) d.commitText(e.currentTarget.value);
                 else if (e.key === 'Escape') d.cancelText();
               }}
               onBlur={e => d.commitText(e.currentTarget.value)}
               className="absolute z-[6] w-[200px] py-0.5 border-0 outline-none bg-transparent text-foreground text-[12px] font-semibold"
               style={{
                 left: d.textEdit.x, top: d.textEdit.y - 12,
                 borderBottom: '1px dashed var(--color-primary)', caretColor: 'var(--color-primary)',
               }} />
      )}
      {d.selection && !d.tool && <PropsBar d={d} />}
    </>
  );
}

/** 属性条按钮：手机 36px 好点，桌面 28px */
const BTN = 'w-9 h-9 md:w-7 md:h-7 flex items-center justify-center transition-colors cursor-pointer '
  + 'text-muted-foreground hover:text-foreground hover:bg-card-2';
const BTN_ON = 'bg-card-2 text-foreground';

/** 线宽/线型的样例：一截横线，用边框画出粗细和虚实 */
function LineSample({ width, dash }: { width: number; dash: LineDash }) {
  return <span className="block w-4" style={{ borderTop: `${width}px ${dash} currentColor` }} />;
}

/**
 * 选中图形的属性条：颜色 / 线宽 / 线型 / 改字 / 复制 / 删除，压在图表底部居中（时间轴上方）。
 * 颜色、线宽、线型各自点开一个小弹层往上弹；换选中别的图形时弹层自动收起
 */
function PropsBar({ d }: { d: DrawingsApi }) {
  const { t } = useTranslation('market');
  const sel = d.selection!;
  const caps = STYLE_CAPS[sel.kind];
  // 弹层记在选中图形名下：换了选中，id 对不上就等于收起
  const [open, setOpen] = useState<{ id: string; which: 'color' | 'width' | 'dash' } | null>(null);
  const which = open?.id === sel.id ? open.which : null;
  const wrapRef = useRef<HTMLDivElement>(null);
  useClickOutside(wrapRef, () => setOpen(null), which !== null, 'pointerdown');
  const toggle = (w: 'color' | 'width' | 'dash') => setOpen(which === w ? null : { id: sel.id, which: w });

  return (
    <div ref={wrapRef}
         className="absolute left-1/2 -translate-x-1/2 bottom-8 z-[7] flex items-center gap-0.5 p-0.5 border border-foreground bg-background">
      {caps.color && (
        <div className="relative">
          <button type="button" title={t('drawProps.color')} onClick={() => toggle('color')}
                  className={cn(BTN, which === 'color' && BTN_ON)}>
            <span className="w-3.5 h-3.5 rounded-full" style={{ background: sel.color }} />
          </button>
          {which === 'color' && (
            <div className="absolute bottom-[calc(100%+6px)] left-0 grid grid-cols-4 gap-1 p-1.5 border border-foreground bg-background">
              {DRAW_PALETTE.map(c => (
                <button key={c} type="button" aria-label={c} onClick={() => { d.setStyle({ color: c }); setOpen(null); }}
                        className={cn('w-8 h-8 md:w-6 md:h-6 flex items-center justify-center rounded-full cursor-pointer',
                          c === sel.color && 'ring-2 ring-foreground')}>
                  <span className="w-5 h-5 md:w-4 md:h-4 rounded-full" style={{ background: c }} />
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {caps.line && (
        <>
          <div className="relative">
            <button type="button" title={t('drawProps.width')} onClick={() => toggle('width')}
                    className={cn(BTN, which === 'width' && BTN_ON)}>
              <LineSample width={sel.width} dash="solid" />
            </button>
            {which === 'width' && (
              <div className="absolute bottom-[calc(100%+6px)] left-0 flex flex-col p-0.5 border border-foreground bg-background">
                {LINE_WIDTHS.map(w => (
                  <button key={w} type="button" onClick={() => { d.setStyle({ width: w }); setOpen(null); }}
                          className={cn(BTN, 'w-14 md:w-12 gap-1.5', w === sel.width && BTN_ON)}>
                    <LineSample width={w} dash="solid" />
                    <span className="text-[10px] font-bold num">{w}px</span>
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="relative">
            <button type="button" title={t('drawProps.style')} onClick={() => toggle('dash')}
                    className={cn(BTN, which === 'dash' && BTN_ON)}>
              <LineSample width={2} dash={sel.dash} />
            </button>
            {which === 'dash' && (
              <div className="absolute bottom-[calc(100%+6px)] left-0 flex flex-col p-0.5 border border-foreground bg-background">
                {(['solid', 'dashed', 'dotted'] as const).map(s => (
                  <button key={s} type="button" title={t(`drawProps.${s}`)}
                          onClick={() => { d.setStyle({ dash: s }); setOpen(null); }}
                          className={cn(BTN, s === sel.dash && BTN_ON)}>
                    <LineSample width={2} dash={s} />
                  </button>
                ))}
              </div>
            )}
          </div>
        </>
      )}
      {sel.kind === 'text' && (
        <button type="button" title={t('drawProps.editText')} onClick={d.editText} className={BTN}>
          <Pencil className="w-[15px] h-[15px]" />
        </button>
      )}
      <button type="button" title={t('drawProps.clone')} onClick={d.cloneSelected} className={BTN}>
        <Copy className="w-[15px] h-[15px]" />
      </button>
      <button type="button" title={t('drawProps.delete')} onClick={d.deleteSelected} className={cn(BTN, 'hover:text-loss')}>
        <Trash2 className="w-[15px] h-[15px]" />
      </button>
    </div>
  );
}
