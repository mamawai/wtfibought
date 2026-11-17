import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown } from 'lucide-react';
import { Dialog, DialogHeader } from '../ui/dialog';
import {
  SESSION_META, fmtDuration, fmtHm, fmtMinOfDay, getSessionState, getWeekView,
  localTzLabel, sessionKindsOf, type MarketId, type SessionKind,
} from '../../lib/marketSession';
import './MarketSessionBadge.css';

/** 各时段的颜色：盘前黄、开盘绿、盘后蓝、夜盘淡灰、不交易更淡 */
const KIND_COLOR: Record<SessionKind, string> = {
  pre: 'var(--color-warning)',
  open: 'var(--color-gain)',
  post: 'var(--color-info)',
  overnight: 'var(--color-faint)',
  closed: 'var(--color-line2)',
};

/** 周一起排，存词表 key（数组是模块常量，拿不到 t，渲染时再查） */
const DAY_KEYS = ['week.mon', 'week.tue', 'week.wed', 'week.thu', 'week.fri', 'week.sat', 'week.sun'];

const dot = (kind: SessionKind) => ({ '--c': KIND_COLOR[kind] }) as CSSProperties;

/** TradFi 标的页头的时段小胶囊：当前时段 + 倒计时，点开看一周的时段图（样式见 MarketSessionBadge.css） */
export function MarketSessionBadge({ market }: { market: MarketId }) {
  const { t } = useTranslation('trade');
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
  const countdown = t(isOpen ? 'session.closesIn' : 'session.opensIn', { d: fmtDuration(st.targetAt - now) });
  const nowD = new Date(now);
  const nowPct = ((nowD.getHours() * 60 + nowD.getMinutes()) / 1440) * 100;
  const todayIdx = (nowD.getDay() + 6) % 7;
  const pct = (min: number) => `${(min / 1440) * 100}%`;

  return (
    <>
      <button type="button" className="sess" onClick={() => setOpen(true)} title={t('session.badgeTitle')}>
        <i style={dot(st.kind)} />
        {SESSION_META[st.kind].label}
        <span>· {countdown}</span>
        <ChevronDown className="ic" />
      </button>

      <Dialog open={open} onClose={() => setOpen(false)} className="lg sess-dlg">
        {/* 标题：当前时段 + 本段起止 + 倒计时 */}
        <DialogHeader>
          <h3><span className="sess-t"><i style={dot(st.kind)} />{SESSION_META[st.kind].label}</span></h3>
          <p>{fmtHm(st.start)} - {fmtHm(st.end)}, {localTzLabel()} · {t('session.marketWill', { countdown })}</p>
        </DialogHeader>

        {week && (
          <div className="wk">
            {/* 时间轴刻度：各时段边界在本地时区的时刻 */}
            <div className="tick">
              {week.ticks.map(m => <span key={m} style={{ left: pct(m) }}>{fmtMinOfDay(m)}</span>)}
            </div>
            {week.rows.map((row, i) => (
              <div key={row.dayStart} className={i === todayIdx ? 'day td' : 'day'}>
                <b>{t(DAY_KEYS[i])}</b>
                <div>
                  {row.bars.map((b, j) => <i key={j} style={{ ...dot(b.kind), left: pct(b.from), width: pct(b.to - b.from) }} />)}
                </div>
              </div>
            ))}
            {/* 现在：贯穿七行的竖虚线，顶上一枚时刻 */}
            <div className="nowl">
              <i style={{ left: `${nowPct}%` }} />
              <b style={{ left: `${nowPct}%` }}>{fmtHm(now)}</b>
            </div>
          </div>
        )}

        <div className="skinds">
          {sessionKindsOf(market).map(k => (
            <div key={k}>
              <i style={dot(k)} />
              <b>{SESSION_META[k].label}</b>
              <span>{SESSION_META[k].liquidity}</span>
            </div>
          ))}
        </div>

        <p className="txt">{t('session.note')}</p>
      </Dialog>
    </>
  );
}
