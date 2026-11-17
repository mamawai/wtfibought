import { useRef, useState, type CSSProperties, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { BigNum } from './ui/big-num';
import { COIN_MAP } from '../lib/coinConfig';
import { useClickOutside } from '../hooks/useClickOutside';
import { cn, fmtNum, fmtSignedPct } from '../lib/utils';
import { WHAT_IF, WHAT_IF_AS_OF, WHAT_IF_SPANS, WHAT_IF_STAKE } from '../lib/whatIfData';

/** 涨跌幅：上千的不带小数、加千分位（+39,499%），别的一位小数 */
function pct(now: number) {
  const p = (now / WHAT_IF_STAKE - 1) * 100;
  return Math.abs(p) >= 1000 ? `${p < 0 ? '-' : '+'}${fmtNum(Math.abs(p), 0)}%` : fmtSignedPct(p, 1);
}

interface Props {
  /** 现在演的是第几个标的、第几档时间（下标对 lib/whatIfData） */
  coin: number;
  span: number;
  onPick: (to: { coin?: number; span?: number }) => void;
  ref: Ref<HTMLDivElement>;
}

/**
 * 登录页左边的主角（样式见 pages/Login.css）：把站名那句话演出来——那时候用 ₮10,000 买了它，现在是多少。
 * 一句话，里面的时间点开能换六档；一个大数；一行别的标的各是涨是跌，点了就换；最底下两行小字：哪天买的、按哪天的价格算。
 * 数是写死的（lib/whatIfData），不取实时行情；这笔钱的走势画在背景里（fx/DotField）
 */
export function LoginHero({ coin, span, onPick, ref }: Props) {
  const { t } = useTranslation('account');
  const [open, setOpen] = useState(false);
  const whenRef = useRef<HTMLSpanElement>(null);
  useClickOutside(whenRef, () => setOpen(false), open, 'pointerdown');

  const line = WHAT_IF[coin].spans[span];
  const name = COIN_MAP[WHAT_IF[coin].symbol].name;
  const now = line.values[line.values.length - 1];
  const up = now >= WHAT_IF_STAKE;
  // 整数位太长（从上线那天算的 BNB 是七位数）就缩一号，不折行
  const digits = fmtNum(now, 0).length;

  return (
    <div ref={ref} className="hero" data-clear>
      {/* 句子在词表里是一整句，<0/> 是标的、<1/> 是时间（中英文语序不一样），拆开把两处换成元素 */}
      <h1 className="say">
        {t('login.ifBought').split(/(<[01]\/>)/).map(part =>
          part === '<0/>' ? <b key="coin">{name}</b>
          : part === '<1/>' ? (
            <span key="when" ref={whenRef} className={cn('pw', open && 'open')}
                  onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
              <button type="button" className="when" aria-haspopup="menu" aria-expanded={open}
                      title={t('login.spanSwitch')} onClick={() => setOpen(o => !o)}>
                {t(`login.span.${WHAT_IF_SPANS[span]}`)}
              </button>
              <span className="menu" role="menu">
                {WHAT_IF_SPANS.map((key, i) => (
                  <button key={key} type="button" role="menuitem" className={cn(i === span && 'on')}
                          onClick={() => { setOpen(false); onPick({ span: i }); }}>
                    {t(`login.span.${key}`)}
                  </button>
                ))}
              </span>
            </span>
          ) : part)}
      </h1>
      <b className="big nf" style={{ '--k': digits > 9 ? 0.62 : digits > 7 ? 0.78 : 1 } as CSSProperties}>
        <BigNum value={now} />
      </b>
      <div className={cn('res', up ? 'up' : 'dn')}>
        <span>{pct(now)}</span>
        <span>{t(up ? 'login.gained' : 'login.lost', { amount: fmtNum(Math.abs(now - WHAT_IF_STAKE)) })}</span>
      </div>

      <div className="alts" role="group" aria-label={t('login.pickAsset')}>
        {WHAT_IF.map((c, i) => {
          const values = c.spans[span].values, end = values[values.length - 1];
          return (
            <button key={c.symbol} type="button" aria-pressed={i === coin} onClick={() => onPick({ coin: i })}>
              {COIN_MAP[c.symbol].name}
              <em className={end >= WHAT_IF_STAKE ? 'up' : 'dn'}>{pct(end)}</em>
            </button>
          );
        })}
      </div>
      {/* 一行一句：哪天买的、按哪天的价格算。没那么久行情的标的是从它最早那天算的，中间多一句说明 */}
      <p className="cap">
        <span>{t('login.bought', { since: line.since })}</span>
        {line.short && <span>{t('login.firstListed', { coin: name })}</span>}
        <span>{t('login.pricedAt', { asOf: WHAT_IF_AS_OF })}</span>
      </p>
    </div>
  );
}
