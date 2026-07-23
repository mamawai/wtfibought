import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, Moon, Sunrise, Sunset } from 'lucide-react';
import { Dialog } from '../ui/dialog';
import {
  SESSION_META, fmtDuration, fmtHm, fmtMinOfDay, getSessionState, getWeekView,
  localTzLabel, sessionKindsOf, type MarketId, type SessionKind,
} from '../../lib/marketSession';

/** 彩条配色，对齐 Binance 标的市场时段图 */
const BAR_COLOR: Record<SessionKind, string> = {
  pre: 'bg-amber-500',
  open: 'bg-emerald-500',
  post: 'bg-violet-500',
  overnight: 'bg-blue-500',
  closed: '',
};
const ICON_COLOR: Record<SessionKind, string> = {
  pre: 'text-amber-500',
  open: 'text-emerald-500',
  post: 'text-violet-500',
  overnight: 'text-blue-500',
  closed: 'text-muted-foreground',
};

const DAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
/** 行标列宽 3rem；刻度/气泡/竖线都得同宽缩进才能和彩条对齐（写死字面量，Tailwind 才扫得到） */
const LABEL_W = 'w-12';
const LABEL_INSET = 'left-12';
const LABEL_ML = 'ml-12';

function KindIcon({ kind, box }: { kind: SessionKind; box: string }) {
  const Ico = kind === 'pre' ? Sunrise : kind === 'post' ? Sunset : Moon;
  return (
    <span className={`${box} inline-flex items-center justify-center shrink-0`}>
      {kind === 'open'
        ? <span className="w-[45%] h-[45%] rounded-full bg-emerald-500" />
        : <Ico className={`w-full h-full ${ICON_COLOR[kind]}`} />}
    </span>
  );
}

export function MarketSessionBadge({ market }: { market: MarketId }) {
  const [open, setOpen] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  // 分钟级展示，30 秒刷一次够用
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);

  const st = useMemo(() => getSessionState(market, now), [market, now]);
  const week = useMemo(() => (open ? getWeekView(market, now) : null), [market, now, open]);

  const isOpen = st.kind === 'open';
  const countdown = `${fmtDuration(st.targetAt - now)} 后${isOpen ? '收盘' : '开盘'}`;
  const nowD = new Date(now);
  const nowPct = ((nowD.getHours() * 60 + nowD.getMinutes()) / 1440) * 100;
  const todayIdx = (nowD.getDay() + 6) % 7;

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full border border-border bg-card text-[10px] font-semibold hover:bg-surface-hover transition-colors cursor-pointer"
        title="标的市场交易时段"
      >
        <KindIcon kind={st.kind} box="w-3 h-3" />
        <span>{SESSION_META[st.kind].label}</span>
        <span className="hidden sm:inline font-normal text-muted-foreground">· {countdown}</span>
        <ChevronDown className="w-3 h-3 text-muted-foreground" />
      </button>

      <Dialog open={open} onClose={() => setOpen(false)} className="max-w-xl">
        <div className="p-5 pr-12 space-y-5 overflow-y-auto max-h-[80vh]">
          {/* 标题：当前时段 + 本段起止 + 倒计时 */}
          <div>
            <div className="flex items-center gap-2">
              <KindIcon kind={st.kind} box="w-6 h-6" />
              <span className="text-xl font-extrabold tracking-tight">{SESSION_META[st.kind].label}</span>
            </div>
            <div className="num text-sm font-bold mt-1.5">
              {fmtHm(st.start)} - {fmtHm(st.end)}, {localTzLabel()}
            </div>
            <div className="text-xs text-muted-foreground mt-0.5">市场将于 {countdown}</div>
          </div>

          {week && (
            <div className="relative">
              {/* 覆盖层：当前时刻竖虚线，贯穿 7 行；left 与行标列同宽保证对齐 */}
              <div className={`absolute top-6 bottom-0 ${LABEL_INSET} right-0 pointer-events-none`}>
                <div className="absolute top-0 bottom-0 border-l border-dashed border-muted-foreground/50" style={{ left: `${nowPct}%` }} />
              </div>

              {/* 当前时刻气泡 */}
              <div className={`relative h-6 ${LABEL_ML}`}>
                <div
                  className="num absolute -translate-x-1/2 px-1.5 py-0.5 rounded-md bg-foreground text-background text-[10px] font-bold whitespace-nowrap"
                  style={{ left: `${nowPct}%` }}
                >
                  {fmtHm(now)}
                </div>
              </div>

              {/* 时间轴刻度：各时段边界在本地时区的时刻 */}
              <div className={`relative h-4 mb-1 ${LABEL_ML}`}>
                {week.ticks.map(t => (
                  <span
                    key={t}
                    className="num absolute -translate-x-1/2 text-[11px] text-muted-foreground whitespace-nowrap"
                    style={{ left: `${(t / 1440) * 100}%` }}
                  >
                    {fmtMinOfDay(t)}
                  </span>
                ))}
              </div>

              {/* 7 行周视图：灰底槽 = 非交易时段，彩条叠在上面 */}
              {week.rows.map((row, i) => (
                <div key={row.dayStart} className={`flex items-center py-2 rounded-md ${i === todayIdx ? 'bg-surface-hover' : ''}`}>
                  <div className={`${LABEL_W} shrink-0 text-xs font-bold`}>{DAY_NAMES[i]}</div>
                  <div className="relative flex-1 h-2">
                    <div className="absolute inset-0 rounded-full bg-muted-foreground/15" />
                    {row.bars.map((b, j) => (
                      <div key={j} className="absolute inset-y-0" style={{ left: `${(b.from / 1440) * 100}%`, width: `${((b.to - b.from) / 1440) * 100}%` }}>
                        {/* 左右各缩 1.5px，相邻不同色之间留出细缝 */}
                        <div className={`absolute inset-y-0 left-[1.5px] right-[1.5px] rounded-full ${BAR_COLOR[b.kind]}`} />
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* 图例：只画该市场用得上的时段 */}
          <div className="grid grid-cols-3 gap-y-4 gap-x-2 pt-1">
            {sessionKindsOf(market).map(k => (
              <div key={k}>
                <KindIcon kind={k} box="w-5 h-5" />
                <div className="text-sm font-bold mt-1">{SESSION_META[k].label}</div>
                <div className="text-xs text-muted-foreground">{SESSION_META[k].liquidity}</div>
              </div>
            ))}
          </div>

          <p className="text-xs text-muted-foreground leading-relaxed">
            该永续合约全天候交易，但流动性会随标的市场交易时段而变化。
          </p>
        </div>
      </Dialog>
    </>
  );
}
