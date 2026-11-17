import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, X } from 'lucide-react';
import { useCoinQuote } from '../../hooks/useQuote';
import { COIN_MAP, formatCoinPrice } from '../../lib/coinConfig';
import { cn, fmtSignedPct } from '../../lib/utils';
import { MAX_SYMBOLS, TRADER_SYMBOLS } from './traderConfig';

/** 取消时勾变叉、再擦掉的时长，跟 CSS 的 wiib-tmk-out 一样长 */
const LEAVE_MS = 900;

/** 品牌色写进 --c，卡片顶线、底色、勾的底色、背后的代号都从它取 */
const brand = (symbol: string) => ({ '--c': COIN_MAP[symbol].chartColor }) as CSSProperties;

/** 一张币卡：左上 logo、右上代号和全称、底下实时价和 24 小时涨跌；选中亮起品牌色，logo 右下角打勾 */
function CoinTile({ symbol, on, leaving, locked, shake, onClick }: {
  symbol: string; on: boolean; leaving: boolean; locked: boolean; shake: boolean; onClick: () => void;
}) {
  const cfg = COIN_MAP[symbol];
  const q = useCoinQuote(symbol);
  const Icon = cfg.icon;
  return (
    <button type="button" onClick={onClick} aria-pressed={on} aria-disabled={locked} style={brand(symbol)}
            className={cn('tile', on && 'on', leaving && 'leaving', locked && 'locked', shake && 'shake')}>
      <i className="tint" />
      <i className="rule" />
      <span className="thd">
        <span className="cl">
          <Icon className="ci" />
          <span className="mk" aria-hidden="true">
            <Check className="ck" strokeWidth={3.2} />
            <X className="xx" strokeWidth={3.2} />
          </span>
        </span>
        <span className="id">
          <span className="sym">{cfg.name}</span>
          <span className="nmf">{cfg.fullName}</span>
        </span>
      </span>
      <span className="px">
        <span>{q.price != null ? formatCoinPrice(symbol, q.price) : '—'}</span>
        {q.pct != null && <em className={q.pct >= 0 ? 'up' : 'dn'}>{fmtSignedPct(q.pct)}</em>}
      </span>
    </button>
  );
}

/**
 * 选币舞台（样式见 MyTrader.css .stage）：8 张币卡，背后是选中币的代号大字，按选中先后往右错开。
 * 选中一张：顶边一条品牌色的线画出来、卡面浮起来、logo 由灰变彩、角上打勾；取消：勾变成叉再擦掉，代号在原位擦掉。
 * 最多选 MAX_SYMBOLS 个，选满后其余变淡，再点那张抖一下并提示。selected 的顺序就是选中先后
 */
export function CoinStage({ selected, onToggle }: { selected: string[]; onToggle: (symbol: string) => void }) {
  const { t } = useTranslation('ai');
  // 正在擦掉勾的币
  const [leaving, setLeaving] = useState<Record<string, boolean>>({});
  const [shake, setShake] = useState<string | null>(null);
  const [warn, setWarn] = useState(false);
  const timers = useRef<number[]>([]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);
  const later = (fn: () => void, ms: number) => { timers.current.push(window.setTimeout(fn, ms)); };
  // 取消那一刻它排第几：代号留在原位擦掉，不跟着别的币挪
  const [goneAt, setGoneAt] = useState<Record<string, number>>({});

  const unmarkLeaving = (s: string) => setLeaving(l => {
    if (!l[s]) return l;
    const next = { ...l };
    delete next[s];
    return next;
  });

  const click = (s: string) => {
    if (selected.includes(s)) {
      setGoneAt(g => ({ ...g, [s]: selected.indexOf(s) }));
      setLeaving(l => ({ ...l, [s]: true }));
      later(() => unmarkLeaving(s), LEAVE_MS);
      onToggle(s);
      return;
    }
    if (selected.length >= MAX_SYMBOLS) {
      setShake(s);
      setWarn(true);
      later(() => setShake(null), 450);
      later(() => setWarn(false), 2600);
      return;
    }
    unmarkLeaving(s);
    setWarn(false);
    onToggle(s);
  };

  const full = selected.length >= MAX_SYMBOLS;
  return (
    <div className="stage">
      <div className="wm" aria-hidden="true">
        {TRADER_SYMBOLS.map(s => {
          const i = selected.indexOf(s);
          return (
            <b key={s} className={cn(i >= 0 && 'on')} style={{ ...brand(s), '--o': i >= 0 ? i : (goneAt[s] ?? 0) } as CSSProperties}>
              {COIN_MAP[s].name}
            </b>
          );
        })}
      </div>
      <div className="tiles" role="group" aria-label={t('my.coins.title')}>
        {TRADER_SYMBOLS.map(s => {
          const on = selected.includes(s);
          return (
            <CoinTile key={s} symbol={s} on={on} leaving={!!leaving[s]} locked={!on && full}
                      shake={shake === s} onClick={() => click(s)} />
          );
        })}
      </div>
      <div className="stagef">
        {/* 三格：选了几个亮几格，亮的那格是那个币的颜色 */}
        <span className="pips" aria-hidden="true">
          {Array.from({ length: MAX_SYMBOLS }, (_, i) => (
            <i key={i} style={selected[i] ? { background: COIN_MAP[selected[i]].chartColor } : undefined} />
          ))}
        </span>
        <b>{t('my.coins.count', { n: selected.length, max: MAX_SYMBOLS })}</b>
        {warn
          ? <span className="dn">{t('my.coins.full', { max: MAX_SYMBOLS })}</span>
          : selected.length === 0 && <span className="mute">{t('my.coins.atLeastOne')}</span>}
      </div>
    </div>
  );
}
