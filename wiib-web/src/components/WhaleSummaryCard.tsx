import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { quantApi, type WhaleCoin, type WhaleLiqBand, type WhaleSide, type WhaleSummary } from '../api';
import { COIN_MAP, formatCoinPrice, type CoinCfg } from '../lib/coinConfig';
import { cn, fmtMoney, fmtTime } from '../lib/utils';

/** 后端 10 分钟出一轮快照，60 秒轮询让首页跟着换槽 */
const POLL_MS = 60_000;

/**
 * 首页大户持仓：Hyperliquid 池内地址的合约仓位，一行一个币，点行跳交易页。
 * 只给名义额、人数、近价强平名义这些数字，不给「支撑/阻力」也不给状态词。
 * 不放开仓均价。
 */
export function WhaleSummaryCard() {
  const { t } = useTranslation('home');
  const navigate = useNavigate();
  // undefined=还没拉回来，null=拉失败
  const [data, setData] = useState<WhaleSummary | null | undefined>(undefined);

  useEffect(() => {
    let alive = true;
    const load = () => quantApi.whaleSummary()
      .then(v => { if (alive) setData(v); })
      .catch(() => { if (alive) setData(prev => prev ?? null); });
    load();
    const timer = window.setInterval(load, POLL_MS);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const loading = data === undefined;
  const coins = data?.coins ?? [];
  // 功能关掉、还没快照、拉失败都是这条：整节不渲染，游客首页不留空壳
  if (!loading && !coins.length) return null;

  return (
    <section className="sec">
      <div className="sec-h">
        <h2>
          {t('whale.title')}
          {data && data.observedAt != null && (
            <small>{t('whale.note', { count: data.poolSize ?? 0, time: fmtTime(data.observedAt) })}</small>
          )}
        </h2>
      </div>

      {loading ? (
        Array.from({ length: 3 }).map((_, i) => <i key={i} className="sk mb-3" style={{ height: 36 }} />)
      ) : (
        // 窄了横向滚，六列都留着
        <div className="tw">
          <table className="tbl hov min-w-[560px]">
            <thead>
              <tr>
                <th>{t('whale.coin')}</th>
                <th className="r">{t('whale.price')}</th>
                <th className="r">{t('whale.long')}</th>
                <th className="r">{t('whale.short')}</th>
                <th className="r">{t('whale.liqAbove')}</th>
                <th className="r">{t('whale.liqBelow')}</th>
                <th className="chev" />
              </tr>
            </thead>
            <tbody>
              {coins.map(c => <Row key={c.coin} row={c} onOpen={() => navigate(`/coin/${c.symbol}`)} />)}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Row({ row, onOpen }: { row: WhaleCoin; onOpen: () => void }) {
  // 盯盘币在后端 yml 里配，平台没收录的币只出币码、不硬套别的币图标
  const cfg: CoinCfg | undefined = COIN_MAP[row.symbol];
  return (
    <tr onClick={onOpen}>
      <td>
        <span className="sym">
          {cfg && <cfg.icon className={cn('w-[18px] h-[18px] shrink-0', cfg.colorClass)} />}
          {cfg?.name ?? row.coin}
        </span>
      </td>
      <td className="r">{formatCoinPrice(row.symbol, row.price)}</td>
      <SideCell side={row.long} tone="up" />
      <SideCell side={row.short} tone="dn" />
      <LiqCell band={row.liqAbove} symbol={row.symbol} />
      <LiqCell band={row.liqBelow} symbol={row.symbol} />
      <td className="chev"><ChevronRight className="ic" /></td>
    </tr>
  );
}

/** 一侧的名义额 + 地址数；没样本出「无」不出 0 */
function SideCell({ side, tone }: { side: WhaleSide; tone: 'up' | 'dn' }) {
  const { t } = useTranslation('home');
  if (!side.count) return <td className="r mute">{t('whale.none')}</td>;
  return (
    <td className="r">
      <span className={cn('font-medium', tone)}>${fmtMoney(side.notional)}</span>
      <span className="sub">{t('whale.holders', { count: side.count })}</span>
    </td>
  );
}

/** 现价上下 5% 内的强平名义合计，小字是名义最大那个桶的价格；5% 内没桶出「无」 */
function LiqCell({ band, symbol }: { band: WhaleLiqBand | null; symbol: string }) {
  const { t } = useTranslation('home');
  if (!band) return <td className="r mute">{t('whale.none')}</td>;
  return (
    <td className="r">
      ${fmtMoney(band.notional)}
      <span className="sub">@ {formatCoinPrice(symbol, band.peakPrice)}</span>
    </td>
  );
}
