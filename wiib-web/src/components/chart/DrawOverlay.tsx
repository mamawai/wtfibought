import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Copy, Pencil, Trash2, X } from 'lucide-react';
import { cn } from '../../lib/utils';
import { useClickOutside } from '../../hooks/useClickOutside';
import { DRAW_PALETTE, LINE_WIDTHS, PLACE_POINTS, STYLE_CAPS, type LineDash } from '../../lib/chartDrawings';
import { IS_COARSE, type DrawingsApi } from './useDrawings';

/**
 * 画线浮层（CandleChart / BacktestChart 共用）：文字标注输入框 + 绘制中提示条 + 选中图形的属性条。
 * 放在图表宿主 div 的兄弟位置：指针事件到不了画线层的监听，点这些条不会误触图表。
 * 提示条和属性条占同一个位置（图表底部居中），一个在画、一个在选，不会同时出现
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
      {d.tool && <DrawHint d={d} />}
      {d.selection && !d.tool && <PropsBar d={d} />}
    </>
  );
}

/** 浮条按钮：手机 36px 好点，桌面 28px */
const BTN = 'w-9 h-9 md:w-7 md:h-7 shrink-0 flex items-center justify-center transition-colors cursor-pointer '
  + 'text-muted-foreground hover:text-foreground hover:bg-card-2';
const BTN_ON = 'bg-card-2 text-foreground';

/**
 * 底部居中的浮条。w-max 不能省：left-1/2 的绝对定位元素按"收缩适应"算宽，
 * 可用宽度只剩容器的一半，内容一多就被挤窄
 */
const BAR = 'absolute left-1/2 -translate-x-1/2 bottom-8 z-[7] w-max max-w-[calc(100%-16px)]';

/** 绘制中提示条：工具名 + 落点进度 + 怎么操作 + 取消。手机上没有 Esc，取消全靠这颗 × */
function DrawHint({ d }: { d: DrawingsApi }) {
  const { t } = useTranslation('market');
  const tool = d.tool!;
  const need = PLACE_POINTS[tool];
  return (
    <div className={cn(BAR, 'flex items-center gap-2.5 pl-3 pr-0.5 py-0.5 border border-foreground bg-background')}>
      <b className="shrink-0 text-[12px] font-bold whitespace-nowrap">{t(`drawTool.${tool}`)}</b>
      {need > 1 && (
        // 落点进度：要几个点就几格，已落定的填墨
        <span className="shrink-0 flex gap-1">
          {Array.from({ length: need }, (_, i) => (
            <i key={i} className={cn('w-1.5 h-1.5 border border-foreground', i < d.placed && 'bg-foreground')} />
          ))}
        </span>
      )}
      <span className="min-w-0 truncate text-[11.5px] text-muted-foreground">
        {IS_COARSE ? t('draw.hintTouch') : t('draw.hintMouse')}
      </span>
      <button type="button" title={t('draw.cancel')} aria-label={t('draw.cancel')}
              onClick={() => d.setTool(null)} className={BTN}>
        <X className="w-[15px] h-[15px]" />
      </button>
    </div>
  );
}

/** 线宽/线型的样例：一截横线，用边框画出粗细和虚实 */
function LineSample({ width, dash, className = 'w-4' }: { width: number; dash: LineDash; className?: string }) {
  return <span className={cn('block', className)} style={{ borderTop: `${width}px ${dash} currentColor` }} />;
}

/** 属性条的弹层：统一压在属性条正上方居中。挂在按钮下的话定位基准只有按钮那么宽，色块会挤成一摞 */
const POP = 'absolute bottom-[calc(100%+6px)] left-1/2 -translate-x-1/2 w-max p-2 border border-foreground bg-background';

/** 弹层里的一个选项：选中填墨 */
const opt = (on: boolean) => cn('h-9 md:h-7 w-12 md:w-10 flex items-center justify-center transition-colors cursor-pointer',
  on ? 'bg-foreground text-background' : 'text-muted-foreground hover:bg-card-2 hover:text-foreground');

/**
 * 选中图形的属性条：颜色 / 线条（线宽+线型）/ 改字 / 复制 / 删除，压在图表底部居中（时间轴上方）。
 * 颜色、线条各点开一个弹层，统一摆在属性条正上方居中；换选中别的图形时弹层自动收起
 */
function PropsBar({ d }: { d: DrawingsApi }) {
  const { t } = useTranslation('market');
  const sel = d.selection!;
  const caps = STYLE_CAPS[sel.kind];
  // 弹层记在选中图形名下：换了选中，id 对不上就等于收起
  const [open, setOpen] = useState<{ id: string; which: 'color' | 'line' } | null>(null);
  const which = open?.id === sel.id ? open.which : null;
  const wrapRef = useRef<HTMLDivElement>(null);
  useClickOutside(wrapRef, () => setOpen(null), which !== null, 'pointerdown');
  const toggle = (w: 'color' | 'line') => setOpen(which === w ? null : { id: sel.id, which: w });

  return (
    <div ref={wrapRef} className={BAR}>
      {which === 'color' && (
        <div className={cn(POP, 'grid grid-cols-[repeat(4,auto)] gap-2')}>
          {DRAW_PALETTE.map(c => (
            <button key={c} type="button" aria-label={c} aria-pressed={c === sel.color}
                    onClick={() => { d.setStyle({ color: c }); setOpen(null); }}
                    className={cn('w-8 h-8 md:w-6 md:h-6 flex items-center justify-center cursor-pointer',
                      c === sel.color && 'outline-2 outline-offset-2 outline-foreground')}
                    style={{ background: c }}>
              {c === sel.color && <Check className="w-3.5 h-3.5 text-white" strokeWidth={3} />}
            </button>
          ))}
        </div>
      )}
      {/* 改线宽线型不收弹层：两样常常一起调，收了还得再点开 */}
      {which === 'line' && (
        <div className={cn(POP, 'grid grid-cols-[auto_auto] items-center gap-x-3 gap-y-1.5')}>
          <span className="microlabel">{t('drawProps.width')}</span>
          <div className="flex gap-0.5">
            {LINE_WIDTHS.map(w => (
              <button key={w} type="button" title={`${w}px`} aria-pressed={w === sel.width}
                      onClick={() => d.setStyle({ width: w })} className={opt(w === sel.width)}>
                <LineSample width={w} dash="solid" className="w-6" />
              </button>
            ))}
          </div>
          <span className="microlabel">{t('drawProps.style')}</span>
          <div className="flex gap-0.5">
            {(['solid', 'dashed', 'dotted'] as const).map(s => (
              <button key={s} type="button" title={t(`drawProps.${s}`)} aria-pressed={s === sel.dash}
                      onClick={() => d.setStyle({ dash: s })} className={opt(s === sel.dash)}>
                <LineSample width={2} dash={s} className="w-6" />
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="flex items-center gap-0.5 p-0.5 border border-foreground bg-background">
        {caps.color && (
          <button type="button" title={t('drawProps.color')} onClick={() => toggle('color')}
                  className={cn(BTN, which === 'color' && BTN_ON)}>
            <span className="w-4 h-4" style={{ background: sel.color }} />
          </button>
        )}
        {caps.line && (
          <button type="button" title={t('drawProps.line')} onClick={() => toggle('line')}
                  className={cn(BTN, which === 'line' && BTN_ON)}>
            <LineSample width={sel.width} dash={sel.dash} />
          </button>
        )}
        {/* 样式和动作之间隔一道线 */}
        {(caps.color || caps.line) && <span className="shrink-0 w-px h-5 mx-0.5 bg-border" />}
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
    </div>
  );
}
