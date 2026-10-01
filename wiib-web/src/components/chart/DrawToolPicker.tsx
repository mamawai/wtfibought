import { useCallback, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ArrowRightFromLine, ArrowUpFromDot, ChevronDown, Equal, Eye, EyeOff, Magnet, Minus, MousePointer2, MoveUpRight,
  RectangleHorizontal, Ruler, Slash, Trash2, TrendingDown, TrendingUp, Type, Undo2,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { useClickOutside } from '../../hooks/useClickOutside';
import type { DrawingKind } from '../../lib/chartDrawings';
import type { Tool } from './useDrawings';

/**
 * 画线工具清单（CandleChart / BacktestChart 共用一份，图标语义一致）。
 * 桌面端收进图表左侧 34px 竖栏（{@link DrawToolRail}，按组折叠）；手机端收成一个"当前工具 + 展开"
 * 按钮（{@link DrawToolPopover}），弹层里带名字——一排十几个图标在 375 宽的屏上既挤又认不出。
 * <p>常量在组件外拿不到 t，所以存词表 key 字面量，渲染时再查（key 写死才 grep 得到谁在用）。
 */
const ICON = 'w-[15px] h-[15px]';
/** 斐波这类没有合适图标的，用缩写字当图标 */
const TEXT_ICON = 'text-[9.5px] font-extrabold leading-none tracking-[-.02em]';
type ToolDef = { k: Tool; icon: ReactNode; nameKey: string; titleKey: string; tone?: 'long' | 'short' };
const TOOLS: ToolDef[] = [
  { k: null, icon: <MousePointer2 className={ICON} />, nameKey: 'drawTool.pick', titleKey: 'drawTip.pick' },
  { k: 'trend', icon: <Slash className={ICON} />, nameKey: 'drawTool.trend', titleKey: 'drawTip.trend' },
  // 射线 = 从一点出发往一个方向，跟箭头工具的图标错开
  { k: 'ray', icon: <ArrowUpFromDot className={`${ICON} rotate-45`} />, nameKey: 'drawTool.ray', titleKey: 'drawTip.ray' },
  { k: 'hray', icon: <ArrowRightFromLine className={ICON} />, nameKey: 'drawTool.hray', titleKey: 'drawTip.hray' },
  { k: 'arrow', icon: <MoveUpRight className={ICON} />, nameKey: 'drawTool.arrow', titleKey: 'drawTip.arrow' },
  { k: 'hline', icon: <Minus className={ICON} />, nameKey: 'drawTool.hline', titleKey: 'drawTip.hline' },
  { k: 'vline', icon: <Minus className={`${ICON} rotate-90`} />, nameKey: 'drawTool.vline', titleKey: 'drawTip.vline' },
  { k: 'channel', icon: <Equal className={`${ICON} -rotate-45`} />, nameKey: 'drawTool.channel', titleKey: 'drawTip.channel' },
  { k: 'rect', icon: <RectangleHorizontal className={ICON} />, nameKey: 'drawTool.rect', titleKey: 'drawTip.rect' },
  { k: 'fib', icon: <span className={TEXT_ICON}>FIB</span>, nameKey: 'drawTool.fib', titleKey: 'drawTip.fib' },
  { k: 'fibext', icon: <span className={TEXT_ICON}>EXT</span>, nameKey: 'drawTool.fibext', titleKey: 'drawTip.fibext' },
  { k: 'long', icon: <TrendingUp className={ICON} />, nameKey: 'drawTool.long', titleKey: 'drawTip.long', tone: 'long' },
  { k: 'short', icon: <TrendingDown className={ICON} />, nameKey: 'drawTool.short', titleKey: 'drawTip.short', tone: 'short' },
  { k: 'range', icon: <Ruler className={ICON} />, nameKey: 'drawTool.range', titleKey: 'drawTip.range' },
  { k: 'text', icon: <Type className={ICON} />, nameKey: 'drawTool.text', titleKey: 'drawTip.text' },
];
const toolDef = (k: Tool) => TOOLS.find(x => x.k === k) ?? TOOLS[0];

/**
 * 桌面竖栏的分组（同 TradingView）：一组一颗按钮，显示这组上次用的那个、点了直接选它，
 * 右下角小三角展开整组。工具一多平铺就比图还高（回测图只有 420px）
 */
const GROUPS: DrawingKind[][] = [
  ['trend', 'ray', 'hray', 'arrow', 'hline', 'vline'],
  ['channel', 'rect'],
  ['fib', 'fibext'],
  ['long', 'short'],
  ['range'],
  ['text'],
];

/** 磁吸 / 显隐 / 撤销 / 删除与工具同住一处：选工具和管画线是一件事，不该分两个地方点 */
export interface DrawToolProps {
  tool: Tool;
  onSelect: (t: Tool) => void;
  magnet: boolean;
  onToggleMagnet: () => void;
  hiddenAll: boolean;
  onToggleHidden: () => void;
  hideDisabled: boolean;
  onUndo: () => void;
  undoDisabled: boolean;
  onTrash: () => void;
  trashDisabled: boolean;
  /** 删除按钮的提示：有选中时"删除选中"，否则"清空全部"，由调用方决定 */
  trashTitle: string;
  className?: string;
}

/** 竖栏按钮：34×32 无边无底，hover 上墨 + 浅底，选中填墨 */
const RAIL_BTN = 'w-[34px] h-8 flex items-center justify-center transition-colors cursor-pointer '
  + 'text-muted-foreground hover:text-foreground hover:bg-card-2 '
  + 'disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground';
const RAIL_ON = 'bg-foreground text-background hover:bg-foreground hover:text-background';

/** 竖栏按钮的颜色：选中填墨，多空工具平时带涨跌色 */
const railTone = (b: ToolDef, on: boolean) =>
  on ? RAIL_ON : b.tone === 'long' ? 'text-gain' : b.tone === 'short' ? 'text-loss' : '';

/** 桌面：图表左侧 34px 竖栏。选择 + 6 组工具（组内折叠）+ 分隔线 + 磁吸/显隐/撤销/删除 */
export function DrawToolRail(p: DrawToolProps) {
  const { t } = useTranslation('market');
  // 每组上次选的工具（组按钮显示它）；不存盘，进页面各组回到第一个
  const [last, setLast] = useState<Record<number, DrawingKind>>({});
  const [openGroup, setOpenGroup] = useState<number | null>(null);
  const flyRef = useRef<HTMLDivElement>(null);
  const closeFly = useCallback(() => setOpenGroup(null), []);
  useClickOutside(flyRef, closeFly, openGroup !== null, 'pointerdown');
  const pickIn = (gi: number, k: DrawingKind) => {
    setLast(m => ({ ...m, [gi]: k }));
    p.onSelect(k);
    setOpenGroup(null);
  };
  const pickDef = TOOLS[0];

  return (
    <div className={cn('flex flex-col border-r border-border pt-1.5 w-[34px]', p.className)}>
      <button type="button" title={`${t(pickDef.nameKey)}：${t(pickDef.titleKey)}`}
              onClick={() => p.onSelect(null)} className={cn(RAIL_BTN, railTone(pickDef, p.tool === null))}>
        {pickDef.icon}
      </button>
      {GROUPS.map((g, gi) => {
        const inGroup = p.tool !== null && g.includes(p.tool);
        const cur = toolDef(inGroup ? p.tool : (last[gi] ?? g[0]));
        return (
          <div key={gi} ref={openGroup === gi ? flyRef : undefined} className="relative">
            <button type="button" title={`${t(cur.nameKey)}：${t(cur.titleKey)}`}
                    onClick={() => pickIn(gi, cur.k as DrawingKind)} className={cn(RAIL_BTN, railTone(cur, inGroup))}>
              {cur.icon}
            </button>
            {g.length > 1 && (
              <button type="button" aria-label={t('drawTool.more')} title={t('drawTool.more')}
                      onClick={() => setOpenGroup(openGroup === gi ? null : gi)}
                      className={cn('absolute right-0 bottom-0 w-3 h-3.5 flex items-end justify-end p-[2px] cursor-pointer',
                        inGroup ? 'text-background' : 'text-muted-foreground hover:text-foreground')}>
                {/* 右下角小三角：这颗按钮里还折着别的工具 */}
                <span className="block w-0 h-0 border-l-[4px] border-l-transparent border-b-[4px] border-b-current" />
              </button>
            )}
            {openGroup === gi && (
              <div className="absolute left-full top-0 ml-1 z-20 min-w-[148px] py-1 border border-foreground bg-background">
                {g.map(k => {
                  const b = toolDef(k);
                  return (
                    <button key={k} type="button" title={t(b.titleKey)} onClick={() => pickIn(gi, k)}
                            className={cn('w-full h-8 px-2.5 flex items-center gap-2 text-[12px] font-semibold transition-colors cursor-pointer',
                              p.tool === k ? 'bg-foreground text-background'
                                : b.tone === 'long' ? 'text-gain hover:bg-card-2'
                                  : b.tone === 'short' ? 'text-loss hover:bg-card-2'
                                    : 'text-muted-foreground hover:bg-card-2 hover:text-foreground')}>
                      <span className="w-[15px] flex justify-center shrink-0">{b.icon}</span>
                      {t(b.nameKey)}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      <hr className="w-[18px] border-0 border-t border-border my-1.5 mx-auto" />
      <button type="button" onClick={p.onToggleMagnet}
              title={p.magnet ? t('chart.magnetOn') : t('chart.magnetOff')}
              className={cn(RAIL_BTN, p.magnet && RAIL_ON)}>
        <Magnet className={ICON} />
      </button>
      <button type="button" onClick={p.onToggleHidden} disabled={p.hideDisabled}
              title={p.hiddenAll ? t('chart.drawingsHidden') : t('chart.drawingsHide')}
              className={cn(RAIL_BTN, p.hiddenAll && RAIL_ON)}>
        {p.hiddenAll ? <EyeOff className={ICON} /> : <Eye className={ICON} />}
      </button>
      <button type="button" onClick={p.onUndo} disabled={p.undoDisabled} title={t('chart.undo')}
              className={RAIL_BTN}>
        <Undo2 className={ICON} />
      </button>
      <button type="button" onClick={p.onTrash} disabled={p.trashDisabled} title={p.trashTitle}
              className={RAIL_BTN}>
        <Trash2 className={ICON} />
      </button>
    </div>
  );
}

/** 方格图标钮：与 .ibtn 同款，宽度按内容走（当前工具那颗要塞下名字） */
const IBTN = 'inline-flex items-center justify-center h-[30px] border border-foreground bg-transparent '
  + 'text-muted-foreground cursor-pointer transition-colors hover:bg-card-2 hover:text-foreground '
  + 'disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground';
const IBTN_ON = 'bg-foreground text-background hover:bg-foreground hover:text-background';

/** 手机：当前工具一颗按钮，弹层里 3 列工具，底下一排磁吸/显隐/撤销/删除 */
export function DrawToolPopover(p: DrawToolProps) {
  const { t } = useTranslation('market');
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(wrapRef, close, open, 'pointerdown');
  const cur = TOOLS.find(x => x.k === p.tool) ?? TOOLS[0];

  return (
    <div ref={wrapRef} className={cn('relative', p.className)}>
      <button type="button" onClick={() => setOpen(o => !o)} title={t(cur.titleKey)}
              className={cn(IBTN, 'gap-1 px-2', p.tool !== null && IBTN_ON)}>
        {cur.icon}
        <span className="text-[10px] font-bold">{t(cur.nameKey)}</span>
        <ChevronDown className={`w-3 h-3 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <div className="absolute left-0 top-[calc(100%+6px)] z-20 w-[248px] p-1.5 border border-foreground bg-background">
          <div className="grid grid-cols-3 gap-1">
            {TOOLS.map(b => (
              <button key={b.k ?? 'pick'} type="button" title={t(b.titleKey)}
                      onClick={() => { p.onSelect(b.k); setOpen(false); }}
                      className={cn('h-12 flex flex-col items-center justify-center gap-1 transition-colors',
                        p.tool === b.k ? 'bg-foreground text-background'
                          : b.tone === 'long' ? 'text-gain hover:bg-card-2'
                            : b.tone === 'short' ? 'text-loss hover:bg-card-2'
                              : 'text-muted-foreground hover:bg-card-2 hover:text-foreground')}>
                {b.icon}
                <span className="text-[10px] font-bold leading-none">{t(b.nameKey)}</span>
              </button>
            ))}
          </div>
          <div className="flex gap-1.5 mt-1.5 pt-1.5 border-t border-border">
            <button type="button" onClick={p.onToggleMagnet} title={p.magnet ? t('chart.magnetOn') : t('chart.magnetOff')}
                    className={cn(IBTN, 'w-[30px]', p.magnet && IBTN_ON)}>
              <Magnet className={ICON} />
            </button>
            <button type="button" onClick={p.onToggleHidden} disabled={p.hideDisabled}
                    title={p.hiddenAll ? t('chart.drawingsHidden') : t('chart.drawingsHide')}
                    className={cn(IBTN, 'w-[30px]', p.hiddenAll && IBTN_ON)}>
              {p.hiddenAll ? <EyeOff className={ICON} /> : <Eye className={ICON} />}
            </button>
            <button type="button" onClick={p.onUndo} disabled={p.undoDisabled} title={t('chart.undo')}
                    className={cn(IBTN, 'w-[30px]')}>
              <Undo2 className={ICON} />
            </button>
            <button type="button" onClick={p.onTrash} disabled={p.trashDisabled} title={p.trashTitle}
                    className={cn(IBTN, 'w-[30px]')}>
              <Trash2 className={ICON} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
