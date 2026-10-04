import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ArrowRightFromLine, ArrowUpFromDot, ChevronDown, Equal, Eye, EyeOff, Magnet, Minus, MousePointer2, MoveUpRight,
  PencilRuler, RectangleHorizontal, Ruler, Slash, Trash2, TrendingDown, TrendingUp, Type, Undo2,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { useClickOutside } from '../../hooks/useClickOutside';
import type { DrawingKind } from '../../lib/chartDrawings';
import type { DrawingsApi, Tool } from './useDrawings';

/**
 * 画线工具清单（CandleChart / BacktestChart 共用一份，图标语义一致）。
 * 桌面端收进图表左侧 34px 竖栏（{@link DrawToolRail}，按组折叠）；手机端是顶栏一颗「画线」开关，
 * 展开后图表上方多一行带名字的工具条（{@link DrawToolStrip}）——一排十几个光图标在 375 宽的屏上认不出。
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
 * 工具分组（同 TradingView）：桌面竖栏一组一颗按钮，显示这组上次用的那个、点了直接选它，
 * 悬停弹出整组（工具一多平铺就比图还高，回测图只有 420px）；手机工具条按组之间画竖线隔开
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
  d: DrawingsApi;
  className?: string;
}

/** 删除钮：有选中删选中，没选中清空全部 */
const trashTitleKey = (d: DrawingsApi) => d.selected ? 'chart.deleteSelected' : 'chart.clearAll';

/** 竖栏按钮：34×32 无边无底，hover 上墨 + 浅底，选中填墨；矮屏（手机横屏）压到 26 高，不然 11 颗按钮比图还高 */
const RAIL_BTN = 'w-[34px] h-8 [@media(max-height:600px)]:h-[26px] flex items-center justify-center transition-colors cursor-pointer '
  + 'text-muted-foreground hover:text-foreground hover:bg-card-2 '
  + 'disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground';
const RAIL_ON = 'bg-foreground text-background hover:bg-foreground hover:text-background';

/** 工具按钮的颜色：选中填墨，多空工具平时带涨跌色 */
const railTone = (b: ToolDef, on: boolean) =>
  on ? RAIL_ON : b.tone === 'long' ? 'text-gain' : b.tone === 'short' ? 'text-loss' : '';

/** 鼠标移进组按钮多久展开整组；移出后多久收起（留点余量，斜着划向弹层不会半路收掉） */
const HOVER_OPEN_MS = 120;
const HOVER_CLOSE_MS = 180;

/**
 * 桌面：图表左侧 34px 竖栏。选择 + 6 组工具（组内折叠）+ 分隔线 + 磁吸/显隐/撤销/删除。
 * 多工具的组：鼠标悬停就弹出整组（带名字），点组按钮本身直接选它显示的那个；
 * 触屏（iPad、手机横屏）没有悬停，点组按钮是展开整组
 */
export function DrawToolRail({ d, className }: DrawToolProps) {
  const { t } = useTranslation('market');
  // 每组上次选的工具（组按钮显示它）；不存盘，进页面各组回到第一个
  const [last, setLast] = useState<Record<number, DrawingKind>>({});
  const [openGroup, setOpenGroup] = useState<number | null>(null);
  const flyRef = useRef<HTMLDivElement>(null);
  const closeFly = useCallback(() => setOpenGroup(null), []);
  useClickOutside(flyRef, closeFly, openGroup !== null, 'pointerdown');
  // 悬停展开/收起的延时器
  const timerRef = useRef(0);
  useEffect(() => () => clearTimeout(timerRef.current), []);
  // 最近一次按下组按钮的是鼠标还是手指（键盘回车没有按下，按鼠标算）
  const ptrRef = useRef('mouse');

  const hoverOpen = (gi: number) => {
    clearTimeout(timerRef.current);
    // 已经开着别的组：直接换过去，不用再等
    if (openGroup !== null) setOpenGroup(gi);
    else timerRef.current = window.setTimeout(() => setOpenGroup(gi), HOVER_OPEN_MS);
  };
  const hoverClose = () => {
    clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => setOpenGroup(null), HOVER_CLOSE_MS);
  };
  const pickIn = (gi: number, k: DrawingKind) => {
    clearTimeout(timerRef.current);
    setLast(m => ({ ...m, [gi]: k }));
    d.setTool(k);
    setOpenGroup(null);
  };
  const pickDef = TOOLS[0];

  return (
    <div className={cn('flex flex-col border-r border-border pt-1.5 w-[34px]', className)}>
      <button type="button" title={`${t(pickDef.nameKey)}：${t(pickDef.titleKey)}`}
              onClick={() => d.setTool(null)} className={cn(RAIL_BTN, railTone(pickDef, d.tool === null))}>
        {pickDef.icon}
      </button>
      {GROUPS.map((g, gi) => {
        const inGroup = d.tool !== null && g.includes(d.tool);
        const cur = toolDef(inGroup ? d.tool : (last[gi] ?? g[0]));
        const multi = g.length > 1;
        const open = openGroup === gi;
        return (
          <div key={gi} ref={open ? flyRef : undefined} className="relative"
               onPointerEnter={multi ? e => { if (e.pointerType === 'mouse') hoverOpen(gi); } : undefined}
               onPointerLeave={multi ? e => { if (e.pointerType === 'mouse') hoverClose(); } : undefined}>
            {/* 多工具的组不挂 title：悬停弹出的整组已经带名字，系统提示再冒出来会压在上面 */}
            <button type="button" title={multi ? undefined : `${t(cur.nameKey)}：${t(cur.titleKey)}`}
                    aria-label={t(cur.nameKey)} aria-haspopup={multi || undefined} aria-expanded={multi ? open : undefined}
                    onPointerDown={e => { ptrRef.current = e.pointerType; }}
                    onClick={() => {
                      if (multi && ptrRef.current !== 'mouse') setOpenGroup(open ? null : gi);
                      else pickIn(gi, cur.k as DrawingKind);
                      ptrRef.current = 'mouse';
                    }}
                    className={cn(RAIL_BTN, railTone(cur, inGroup))}>
              {cur.icon}
            </button>
            {/* 右下角小三角：这颗按钮里还折着别的工具 */}
            {multi && (
              <span aria-hidden className={cn('pointer-events-none absolute right-[2px] bottom-[2px] w-0 h-0',
                'border-l-[4px] border-l-transparent border-b-[4px] border-b-current',
                inGroup ? 'text-background' : 'text-muted-foreground')} />
            )}
            {open && (
              // pl-1 是透明的桥：鼠标从按钮划到弹层，中间那道缝不算移出
              <div className="absolute left-full top-0 z-20 pl-1">
                <div className="min-w-[148px] py-1 border border-foreground bg-background">
                  {g.map(k => {
                    const b = toolDef(k);
                    return (
                      <button key={k} type="button" title={t(b.titleKey)} onClick={() => pickIn(gi, k)}
                              className={cn('w-full h-8 px-2.5 flex items-center gap-2 text-[12px] font-semibold whitespace-nowrap transition-colors cursor-pointer',
                                d.tool === k ? 'bg-foreground text-background'
                                  : b.tone === 'long' ? 'text-gain hover:bg-card-2'
                                    : b.tone === 'short' ? 'text-loss hover:bg-card-2'
                                      : 'text-muted-foreground hover:bg-card-2 hover:text-foreground')}>
                        <span className="w-[15px] flex justify-center shrink-0">{b.icon}</span>
                        {t(b.nameKey)}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        );
      })}
      <hr className="w-[18px] border-0 border-t border-border my-1.5 mx-auto" />
      <button type="button" onClick={() => d.setMagnet(!d.magnet)}
              title={d.magnet ? t('chart.magnetOn') : t('chart.magnetOff')}
              className={cn(RAIL_BTN, d.magnet && RAIL_ON)}>
        <Magnet className={ICON} />
      </button>
      <button type="button" onClick={() => d.setHiddenAll(!d.hiddenAll)} disabled={!d.count}
              title={d.hiddenAll ? t('chart.drawingsHidden') : t('chart.drawingsHide')}
              className={cn(RAIL_BTN, d.hiddenAll && RAIL_ON)}>
        {d.hiddenAll ? <EyeOff className={ICON} /> : <Eye className={ICON} />}
      </button>
      <button type="button" onClick={d.undo} disabled={!d.canUndo} title={t('chart.undo')}
              className={RAIL_BTN}>
        <Undo2 className={ICON} />
      </button>
      <button type="button" onClick={d.trash} disabled={!d.selected && !d.count} title={t(trashTitleKey(d))}
              className={RAIL_BTN}>
        <Trash2 className={ICON} />
      </button>
    </div>
  );
}

/** 手机工具条的格子：图标在上名字在下，最小 52 宽 50 高好点；名字长的（英文）格子跟着变宽 */
const STRIP_BTN = 'shrink-0 min-w-[52px] h-[50px] px-2 flex flex-col items-center justify-center gap-1.5 '
  + 'transition-colors cursor-pointer active:bg-card-2 '
  + 'disabled:opacity-30 disabled:cursor-default disabled:active:bg-transparent';
const STRIP_LABEL = 'text-[10px] font-bold leading-none whitespace-nowrap';
const STRIP_ON = 'bg-foreground text-background active:bg-foreground';

/** 组与组之间的竖线 */
const StripSep = () => <span className="shrink-0 w-px my-2.5 bg-border" />;

/**
 * 手机：顶栏一颗「画线」开关；展开后在顶栏最后一行（order-last 换到所有按钮之后，紧贴图表上沿）
 * 铺一条可横滑的工具条：选择 | 各组工具 | 磁吸/显隐/撤销/删除，全都带名字。
 * 画的过程中工具条一直开着，换工具、撤销都是一下；不用了再点开关收起。
 * className 同时作用于开关和工具条（调用方拿它控制在哪些宽度出现）
 */
export function DrawToolStrip({ d, className }: DrawToolProps) {
  const { t } = useTranslation('market');
  const [open, setOpen] = useState(false);

  const toolBtn = (b: ToolDef) => (
    <button key={b.k ?? 'pick'} type="button" title={t(b.titleKey)} onClick={() => d.setTool(b.k)}
            className={cn(STRIP_BTN, d.tool === b.k ? STRIP_ON
              : b.tone === 'long' ? 'text-gain' : b.tone === 'short' ? 'text-loss' : 'text-muted-foreground')}>
      {b.icon}
      <span className={STRIP_LABEL}>{t(b.nameKey)}</span>
    </button>
  );

  return (
    <>
      <button type="button" onClick={() => setOpen(o => !o)} aria-expanded={open}
              className={cn('ibtn w-auto gap-1 px-2', open && 'on', className)}>
        <PencilRuler className="ic" />
        <span className="text-[11px] font-bold">{t('drawAct.tools')}</span>
        <ChevronDown className={cn('w-3 h-3 transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className={cn('order-last basis-full flex items-stretch overflow-x-auto border border-foreground', className)}>
          {toolBtn(TOOLS[0])}
          {GROUPS.map((g, gi) => (
            <div key={gi} className="contents">
              <StripSep />
              {g.map(k => toolBtn(toolDef(k)))}
            </div>
          ))}
          <StripSep />
          <button type="button" onClick={() => d.setMagnet(!d.magnet)}
                  title={d.magnet ? t('chart.magnetOn') : t('chart.magnetOff')}
                  className={cn(STRIP_BTN, d.magnet ? STRIP_ON : 'text-muted-foreground')}>
            <Magnet className={ICON} />
            <span className={STRIP_LABEL}>{t('drawAct.magnet')}</span>
          </button>
          <button type="button" onClick={() => d.setHiddenAll(!d.hiddenAll)} disabled={!d.count}
                  title={d.hiddenAll ? t('chart.drawingsHidden') : t('chart.drawingsHide')}
                  className={cn(STRIP_BTN, d.hiddenAll ? STRIP_ON : 'text-muted-foreground')}>
            {d.hiddenAll ? <EyeOff className={ICON} /> : <Eye className={ICON} />}
            <span className={STRIP_LABEL}>{d.hiddenAll ? t('drawAct.show') : t('drawAct.hide')}</span>
          </button>
          <button type="button" onClick={d.undo} disabled={!d.canUndo} title={t('chart.undo')}
                  className={cn(STRIP_BTN, 'text-muted-foreground')}>
            <Undo2 className={ICON} />
            <span className={STRIP_LABEL}>{t('drawAct.undo')}</span>
          </button>
          <button type="button" onClick={d.trash} disabled={!d.selected && !d.count} title={t(trashTitleKey(d))}
                  className={cn(STRIP_BTN, 'text-muted-foreground')}>
            <Trash2 className={ICON} />
            <span className={STRIP_LABEL}>{d.selected ? t('drawAct.delete') : t('drawAct.clear')}</span>
          </button>
        </div>
      )}
    </>
  );
}
