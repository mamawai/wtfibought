import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { WhaleCoinDetail, WhaleSide } from '../../api';
import { WhaleDistribution, type WhaleView } from './WhaleDistribution';
import { cn, fmtMoney, fmtTime } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';

/**
 * Coin 页「大户持仓」块：Hyperliquid 池内地址在这个币上的名义额、成本与分布。
 * 只摆数字，不给「支撑/阻力」「套牢/拥挤」这类判断。
 */
export function WhaleBlock({ data, symbol }: { data: WhaleCoinDetail; symbol: string }) {
  const { t } = useTranslation('market');
  const [view, setView] = useState<WhaleView>('entry');

  /** 覆盖率：池内名义占全市场持仓量；OI 为 0 时后端给 null，显示「无」不显示 0 */
  const pct = (v: number | null) => (v == null ? t('whale.none') : `${(v * 100).toFixed(1)}%`);

  return (
    <div className="border-t-2 border-foreground pt-3.5">
      <div className="sec-h">
        <h2>{t('whale.title')}</h2>
        <span>{t('whale.snapshot', { time: fmtTime(data.observedAt) })}</span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-[1fr_1fr_auto] gap-x-8 gap-y-5">
        <Side side={data.long} symbol={symbol} label={t('whale.long')} tone="up" />
        <Side side={data.short} symbol={symbol} label={t('whale.short')} tone="dn" />
        <div className="sm:text-right">
          <div className="text-[13px] mute leading-[1.4]">{t('whale.snapshotPrice')}</div>
          <div className="num cond2 mt-2 text-[26px] font-semibold leading-none">${formatCoinPrice(symbol, data.price)}</div>
        </div>
      </div>

      <WhaleDistribution
        className="mt-6"
        entry={data.entryBuckets}
        liq={data.liqBuckets}
        price={data.price}
        symbol={symbol}
        view={view}
        onViewChange={setView}
      />

      <div className="mt-5 text-[12px] mute">
        {t('whale.footer', { count: data.poolSize, long: pct(data.coverage.long), short: pct(data.coverage.short) })}
      </div>
    </div>
  );
}

/** 一侧：名义额大数 + 地址数/均价/中位数/前一名那行；这一侧没人时大数就是「无」 */
function Side({ side, symbol, label, tone }: {
  side: WhaleSide;
  symbol: string;
  label: string;
  tone: 'up' | 'dn';
}) {
  const { t } = useTranslation('market');
  const px = (v: number | null) => (v == null ? t('whale.none') : formatCoinPrice(symbol, v));

  return (
    <div>
      <span className={cn('chip', tone)}>{label}</span>
      <div className="num cond2 mt-2 text-[26px] font-semibold leading-none">
        {side.count > 0 ? `$${fmtMoney(side.notional)}` : t('whale.none')}
      </div>
      <div className="mt-2 text-[13px] mute">
        {t('whale.addresses', { count: side.count })}
        {side.count > 0 && ` · ${t('whale.stats', {
          wavg: px(side.wavgEntry),
          median: px(side.medianEntry),
          top1: side.top1Share == null ? t('whale.none') : `${(side.top1Share * 100).toFixed(0)}%`,
        })}`}
      </div>
    </div>
  );
}
