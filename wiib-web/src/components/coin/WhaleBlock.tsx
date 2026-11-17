import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { WhaleCoinDetail, WhaleSide } from '../../api';
import { WhaleDistribution, type WhaleView } from './WhaleDistribution';
import { cn, fmtMoney, fmtTime } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';
import './WhaleBlock.css';

/**
 * Coin 页「大户持仓」节（样式见 WhaleBlock.css）：Hyperliquid 池内地址在这个币上的名义额、成本与分布。
 * 只摆数字，不给「支撑/阻力」「套牢/拥挤」这类判断。
 */
export function WhaleBlock({ data, symbol }: { data: WhaleCoinDetail; symbol: string }) {
  const { t } = useTranslation('market');
  const [view, setView] = useState<WhaleView>('entry');

  /** 覆盖率：池内名义占全市场持仓量；OI 为 0 时后端给 null，显示「无」不显示 0 */
  const pct = (v: number | null) => (v == null ? t('whale.none') : `${(v * 100).toFixed(1)}%`);

  return (
    <section className="whale">
      <div className="sec-h">
        <h2>{t('whale.title')}<small>{t('whale.snapshot', { time: fmtTime(data.observedAt) })}</small></h2>
      </div>

      <div className="wh3">
        <Side side={data.long} symbol={symbol} label={t('whale.long')} tone="g" />
        <Side side={data.short} symbol={symbol} label={t('whale.short')} tone="r" />
        <div>
          <span className="cap">{t('whale.snapshotPrice')}</span>
          <b className="nf">${formatCoinPrice(symbol, data.price)}</b>
        </div>
      </div>

      <WhaleDistribution
        entry={data.entryBuckets}
        liq={data.liqBuckets}
        price={data.price}
        symbol={symbol}
        view={view}
        onViewChange={setView}
      />

      <p className="wfoot">
        {t('whale.footer', { count: data.poolSize, long: pct(data.coverage.long), short: pct(data.coverage.short) })}
      </p>
    </section>
  );
}

/** 一侧：名义额大数 + 地址数/均价/中位数/前一名那行；这一侧没人时大数就是「无」 */
function Side({ side, symbol, label, tone }: {
  side: WhaleSide;
  symbol: string;
  label: string;
  tone: 'g' | 'r';
}) {
  const { t } = useTranslation('market');
  const px = (v: number | null) => (v == null ? t('whale.none') : formatCoinPrice(symbol, v));

  return (
    <div>
      <span className={cn('tag', tone)}>{label}</span>
      <b className="nf">{side.count > 0 ? `$${fmtMoney(side.notional)}` : t('whale.none')}</b>
      <p>
        {t('whale.addresses', { count: side.count })}
        {side.count > 0 && ` · ${t('whale.stats', {
          wavg: px(side.wavgEntry),
          median: px(side.medianEntry),
          top1: side.top1Share == null ? t('whale.none') : `${(side.top1Share * 100).toFixed(0)}%`,
        })}`}
      </p>
    </div>
  );
}
