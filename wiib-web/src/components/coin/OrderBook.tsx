import { useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { useDepthStream, type DepthRow } from '../../hooks/useDepthStream';
import { useMediaQuery } from '../../hooks/useMediaQuery';
import { Select } from '../ui/select';
import { HelpTip } from '../HelpTip';
import { cn } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';
import './OrderBook.css';

const STEP_KEY = 'wiib-book-step';

/** 挂单额：K / M / B 三位一进，中英文都这么写（行情里的习惯写法，列也窄） */
const fmtAmount = (v: number) =>
  v >= 1e9 ? `${(v / 1e9).toFixed(2)}B` : v >= 1e6 ? `${(v / 1e6).toFixed(2)}M` : v >= 1e3 ? `${(v / 1e3).toFixed(2)}K` : v.toFixed(2);

/** 一侧的行：挂单额（价 × 量，USDT）和从最优价累计到这一行的挂单额 */
function sideRows(rows: DepthRow[], n: number) {
  let cum = 0;
  return rows.slice(0, n).map(([price, qty]) => {
    const amount = price * qty;
    cum += amount;
    return { price, amount, cum };
  });
}

/**
 * 合约盘口（样式见 OrderBook.css），放在 K 线下面：买盘在左、卖盘在右，最优价都在最上面一行、挨着中缝。
 * 价格按选的单位归并，数量是这一格的挂单额（USDT），每行后面的淡色条是从最优价累计到这一行的量。头上是最新价和标记价。
 * 数据是行情服务每半秒推一次的（见 useDepthStream）；单位选第几档记在本地，换币沿用。
 */
export function OrderBook({ symbol, lastPrice, markPrice }: {
  symbol: string;
  /** 最新成交价，还没到传 undefined */
  lastPrice?: number;
  markPrice?: number;
}) {
  const { t } = useTranslation('market');
  const { ladders, live } = useDepthStream(symbol);
  // 手机上每边 5 行，别把下单面板挤得太靠下；其余 10 行
  const rowCount = useMediaQuery('(max-width:760px)') ? 5 : 10;

  // 每个币的单位不一样（BTC 0.1 起、DOGE 0.00001 起），记的是第几档
  const [stepIdx, setStepIdx] = useState(() => Number(localStorage.getItem(STEP_KEY)) || 0);
  const ladder = ladders.length > 0 ? ladders[Math.min(stepIdx, ladders.length - 1)] : null;
  const pickStep = (step: string) => {
    const i = ladders.findIndex(l => l.step === step);
    setStepIdx(i);
    localStorage.setItem(STEP_KEY, String(i));
  };

  // 最新价比上一跳涨了还是跌了
  const [seen, setSeen] = useState(lastPrice);
  const [dir, setDir] = useState<'up' | 'dn' | null>(null);
  if (lastPrice !== seen) {
    setSeen(lastPrice);
    if (lastPrice != null && seen != null) setDir(lastPrice > seen ? 'up' : 'dn');
  }

  const bids = ladder ? sideRows(ladder.b, rowCount) : [];
  const asks = ladder ? sideRows(ladder.a, rowCount) : [];
  // 两边的累计条用同一把尺
  const maxCum = Math.max(bids.at(-1)?.cum ?? 0, asks.at(-1)?.cum ?? 0);
  const decimals = ladder ? (ladder.step.split('.')[1] ?? '').length : 0;

  const side = (rows: typeof bids, cls: string) => (
    <div className={cn('ob-s', cls)}>
      <div className="th"><span>{t('book.price')}</span><span>{t('book.amount')}</span></div>
      {/* 行按位置固定（第几行就是第几行）：价变了只是换里面的字，后面的条才能从原来的长度滑到新的长度 */}
      {rows.map((r, i) => (
        <div key={i} className="r" style={{ '--w': `${(r.cum / maxCum) * 100}%` } as CSSProperties}>
          <span>{r.price.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}</span>
          <span>{fmtAmount(r.amount)}</span>
        </div>
      ))}
    </div>
  );

  return (
    <section className={cn('ob', !live && 'stale')} aria-label={t('book.title')}>
      <div className="ob-h">
        <b>{t('book.title')}</b>
        {ladder && !live && <span className="ob-off">{t('book.offline')}</span>}
        {ladder && (
          <Select className="sm" value={ladder.step} onChange={pickStep} label={t('book.step')}
                  options={ladders.map(l => ({ value: l.step, label: l.step }))} />
        )}
      </div>
      <div className="ob-mid">
        <b className={cn('nf', dir)}>
          {lastPrice != null ? formatCoinPrice(symbol, lastPrice) : '—'}
          {dir === 'up' && <ArrowUp className="ic" />}
          {dir === 'dn' && <ArrowDown className="ic" />}
        </b>
        {markPrice != null && (
          <span>{t('book.mark')} {formatCoinPrice(symbol, markPrice)}<HelpTip text={t('book.markHelp')} /></span>
        )}
      </div>
      {ladder ? (
        <>
          {side(bids, 'bids')}
          {side(asks, 'asks')}
        </>
      ) : (
        <div className="ob-wait">{t('book.waiting')}</div>
      )}
    </section>
  );
}
