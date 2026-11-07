import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, X } from 'lucide-react';
import { useCoinQuote } from '../../hooks/useQuote';
import { COIN_MAP, formatCoinPrice } from '../../lib/coinConfig';
import { cn, fmtSignedPct } from '../../lib/utils';
import { MAX_SYMBOLS, TRADER_SYMBOLS } from './traderConfig';

/** 取消时勾变叉、再擦掉的时长，跟 CSS 的 tr-mk-out 一样长 */
const LEAVE_MS = 900;
/** 水印按选中先后往右下错开 */
const WM_STEP_X = 236;
const WM_STEP_Y = 14;

/** 品牌色写进 --c，卡片顶线、底色、勾的底色、水印都从它取 */
const brand = (symbol: string) => ({ '--c': COIN_MAP[symbol].chartColor }) as CSSProperties;

/** 一张币种卡：左上 logo、右上代号和全称、底下实时价和 24 小时涨跌；选中亮起品牌色，logo 右下角打勾 */
function CoinTile({ symbol, on, leaving, locked, shake, onClick }: {
  symbol: string; on: boolean; leaving: boolean; locked: boolean; shake: boolean; onClick: () => void;
}) {
  const cfg = COIN_MAP[symbol];
  const q = useCoinQuote(symbol);
  const Icon = cfg.icon;
  return (
    <button type="button" onClick={onClick} aria-pressed={on} aria-disabled={locked} style={brand(symbol)}
            className={cn('tr-tile', on && 'on', leaving && 'leaving', locked && 'locked', shake && 'shake')}>
      <i className="tr-tile-tint" />
      <i className="tr-tile-rule" />
      <span className="tr-tile-top">
        <span className="tr-tile-logo">
          <Icon className="tr-coin" />
          <span className="tr-mk" aria-hidden="true">
            <Check className="tr-ck" strokeWidth={3.5} />
            <X className="tr-x" strokeWidth={3.5} />
          </span>
        </span>
        <span className="tr-tile-id">
          <span className="tr-tile-sym">{cfg.name}</span>
          <span className="tr-tile-name">{cfg.fullName}</span>
        </span>
      </span>
      <span className="tr-tile-px num">
        <span>{q.price != null ? formatCoinPrice(symbol, q.price) : '—'}</span>
        {q.pct != null && <em className={q.pct >= 0 ? 'up' : 'dn'}>{fmtSignedPct(q.pct)}</em>}
      </span>
    </button>
  );
}

/**
 * 选币舞台：8 张币种卡，背后是选中币的代号大水印。最多选 MAX_SYMBOLS 个，选满再点第 4 个卡片抖一下并提示。
 * selected 的顺序就是选中先后，水印按它排。
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
  // 取消那一刻它排第几：水印留在原位擦掉，不跟着别的币挪
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
    <div className="tr-stage">
      <div className="tr-stage-wm" aria-hidden="true">
        {TRADER_SYMBOLS.map(s => {
          const i = selected.indexOf(s);
          const o = i >= 0 ? i : (goneAt[s] ?? 0);
          return (
            <b key={s} className={cn(i >= 0 && 'on')}
               style={{ ...brand(s), transform: `translate(${o * WM_STEP_X}px, ${o * WM_STEP_Y}px)` }}>
              {COIN_MAP[s].name}
            </b>
          );
        })}
      </div>
      <div className="tr-tiles" role="group" aria-label={t('my.coins.title')}>
        {TRADER_SYMBOLS.map(s => {
          const on = selected.includes(s);
          return (
            <CoinTile key={s} symbol={s} on={on} leaving={!!leaving[s]} locked={!on && full}
                      shake={shake === s} onClick={() => click(s)} />
          );
        })}
      </div>
      <div className="tr-stage-f">
        <span className="tr-pips" aria-hidden="true">
          {Array.from({ length: MAX_SYMBOLS }, (_, i) => <i key={i} className={cn(i < selected.length && 'on')} />)}
        </span>
        <b className="num font-extrabold">{t('my.coins.count', { n: selected.length, max: MAX_SYMBOLS })}</b>
        {warn
          ? <span className="dn font-bold">{t('my.coins.full', { max: MAX_SYMBOLS })}</span>
          : selected.length === 0 && <span className="mute">{t('my.coins.atLeastOne')}</span>}
      </div>
    </div>
  );
}
