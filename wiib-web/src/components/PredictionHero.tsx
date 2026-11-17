import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowUpRight } from 'lucide-react';
import { cn, fmtNum } from '../lib/utils';
import { BigNum } from './ui/big-num';
import { HelpTip } from './HelpTip';
import { WINDOW_SECONDS, fmtWindow, type PredictionMarket } from '../hooks/usePredictionMarket';

/** 走势图只画最近这么久 */
const CHART_MS = 60_000;
/** 走势图的画布坐标，preserveAspectRatio=none 拉满容器 */
const VW = 600;
const VH = 200;

const p2 = (n: number) => String(n).padStart(2, '0');

/**
 * 5 分钟盘的回合头：倒计时 + 现价对目标价，一条回合进度线，底下最近一分钟的走势。价格都是 Chainlink 60 秒 TWAP。
 * 三块直接排进页面左栏（样式见 Prediction.css）。
 */
export function PredictionHero({ market }: { market: PredictionMarket }) {
  const { t } = useTranslation(['community', 'trade']);
  const { round, windowStart, countdown, priceHistory, btcPrice } = market;
  const target = round?.startPrice ? parseFloat(round.startPrice) : null;
  // 推送的第一跳还没到时先用价格历史最后一个点顶上
  const price = btcPrice ?? priceHistory[priceHistory.length - 1]?.price ?? null;
  const diff = price != null && target != null ? price - target : null;
  const locked = round?.status === 'LOCKED';

  return (
    <>
      <div className="rh">
        <div className={cn('cd', locked && 'lock')}>
          <div className="lab">
            <span>{t('trade:pred.round', { range: windowStart != null ? fmtWindow(windowStart) : '--' })}</span>
            {locked && <span className="tag sm w">{t('prediction.locked')}</span>}
          </div>
          <b className="big nf">{p2(Math.floor(countdown / 60))}<i>:</i>{p2(countdown % 60)}</b>
        </div>
        <div className="pxr">
          <div className="lab">{t('trade:pred.btcNow')}</div>
          <b className="big nf">{price == null ? '--' : <BigNum value={price} />}</b>
          {diff != null && (
            <div className={cn('vs', diff >= 0 ? 'up' : 'dn')}>
              {diff >= 0 ? <ArrowUpRight className="ic" /> : <ArrowDownRight className="ic" />}
              {t(diff >= 0 ? 'trade:pred.above' : 'trade:pred.below', { diff: fmtNum(Math.abs(diff)) })}
            </div>
          )}
          <div className="tg">
            {target == null
              ? t('prediction.targetLoading')
              : <span>{t('trade:pred.target')} <b className="nf">₮{fmtNum(target)}</b></span>}
            <HelpTip text={t('prediction.targetTip')} />
          </div>
        </div>
      </div>
      {/* 这一回合走到哪了 */}
      <div className="tline" aria-hidden="true">
        <i style={{ width: `${(WINDOW_SECONDS - countdown) / WINDOW_SECONDS * 100}%` }} />
      </div>
      <PriceLine history={priceHistory} target={target} />
    </>
  );
}

/** 最近一分钟的走势：右端是最新一跳，虚线是目标价，线在目标价上方那块泛绿、下方泛红 */
function PriceLine({ history, target }: { history: { time: number; price: number }[]; target: number | null }) {
  const { t } = useTranslation('community');
  const clip = useId();
  const end = history[history.length - 1]?.time ?? 0;
  const pts = history.filter(p => p.time >= end - CHART_MS);
  if (!pts.length) return <div className="cv" />;

  // 纵轴把走势和目标价都装进去，上下各留一点
  const vals = pts.map(p => p.price);
  const mid = target ?? vals[0];
  const lo = Math.min(...vals, mid) - mid * 0.00025;
  const hi = Math.max(...vals, mid) + mid * 0.00025;
  const x = (tm: number) => (tm - (end - CHART_MS)) / CHART_MS * VW;
  const y = (p: number) => (hi - p) / (hi - lo) * VH;
  const line = pts.map(p => `${x(p.time).toFixed(1)},${y(p.price).toFixed(1)}`).join(' ');
  const last = pts[pts.length - 1];
  const ty = target == null ? null : y(target);
  // 线和目标价之间那块面：两份一样的，一份只留目标价上方、一份只留下方
  const area = ty == null ? '' : `${line} ${x(last.time).toFixed(1)},${ty.toFixed(1)} ${x(pts[0].time).toFixed(1)},${ty.toFixed(1)}`;

  return (
    <div className="cv">
      <svg viewBox={`0 0 ${VW} ${VH}`} preserveAspectRatio="none" aria-hidden="true">
        {ty != null && (
          <>
            <defs>
              <clipPath id={`${clip}a`}><rect x="0" y="0" width={VW} height={ty} /></clipPath>
              <clipPath id={`${clip}b`}><rect x="0" y={ty} width={VW} height={VH - ty} /></clipPath>
            </defs>
            <polygon points={area} clipPath={`url(#${clip}a)`} className="fill-gain" fillOpacity={0.12} />
            <polygon points={area} clipPath={`url(#${clip}b)`} className="fill-loss" fillOpacity={0.12} />
            <line className="tl" x1="0" x2={VW} y1={ty} y2={ty} />
          </>
        )}
        <polyline className="ln" points={line} />
      </svg>
      {/* 目标价标签贴在虚线上方，虚线太靠顶就挪到下方 */}
      {ty != null && (
        <span className={cn('tag0 nf', ty < 28 && 'lo')} style={{ top: `${ty / VH * 100}%` }}>
          {t('prediction.chartTarget', { price: fmtNum(target) })}
        </span>
      )}
      <i className="dot" style={{ left: `${x(last.time) / VW * 100}%`, top: `${y(last.price) / VH * 100}%` }} />
    </div>
  );
}
