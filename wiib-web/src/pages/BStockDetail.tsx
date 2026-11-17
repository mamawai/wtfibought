import { useState, useEffect, useCallback, useId } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ExternalLink } from 'lucide-react';
import { bstockApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useCrossAccount } from '../hooks/useCrossAccount';
import { useSubmitFx } from '../hooks/useSubmitFx';
import { useToast } from '../components/ui/use-toast';
import { Seg } from '../components/ui/seg';
import { Skeleton } from '../components/ui/skeleton';
import { BigNum } from '../components/ui/big-num';
import { CandleChart } from '../components/CandleChart';
import { FuturesActionButton } from '../components/FuturesActionButton';
import { LeverageSlider } from '../components/LeverageSlider';
import { LoginPrompt } from '../components/LoginPrompt';
import { NumInput, PctRow } from '../components/coin/TradeFields';
import { COMMISSION_RATE, POSITION_PCTS, SPOT_LEVERAGE_OPTIONS, calcMaxSpotBuyQty, calcSpotOrderEstimate, floorToStep } from '../components/coin/futuresMath';
import { useTradeFilter } from '../lib/tradeFilters';
import { getStockBrand } from '../lib/stockConfig';
import { cn, fmtNum, fmtSignedPct, fmtSignedUsd } from '../lib/utils';
import type { BStock, CryptoPosition } from '../types';
// 股票详情和币对交易页是同一个模板
import './Coin.css';

const MAX_LEVERAGE = SPOT_LEVERAGE_OPTIONS[SPOT_LEVERAGE_OPTIONS.length - 1];

const fmtCap = (v?: number) => {
  if (v == null) return '—';
  if (v >= 1e12) return `${(v / 1e12).toFixed(2)}T`;
  if (v >= 1e9) return `${(v / 1e9).toFixed(1)}B`;
  if (v >= 1e6) return `${(v / 1e6).toFixed(1)}M`;
  return fmtNum(v);
};

export function BStockRoute() {
  const { symbol } = useParams<{ symbol: string }>();
  return <BStockDetail key={symbol} symbol={symbol ?? ''} />;
}

function BStockDetail({ symbol }: { symbol: string }) {
  const { t } = useTranslation(['market', 'trade']);
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);
  // 游客只看行情和公司信息，持仓不拉、交易面板换成去登录
  const loggedIn = useUserStore(s => !!s.token);
  const qtyId = useId();

  const [info, setInfo] = useState<BStock | null>(null);
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  // 周期：图表顶栏自己切，页面只存当前档
  const [chartIv, setChartIv] = useState<'5m' | '15m' | '1h' | '4h' | '1d'>('5m');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [qty, setQty] = useState('');
  // 买入输入单位：股数 / USDT 预算。USDT 指"含手续费的现金占用"（用杠杆时即保证金+手续费）
  const [buyUnit, setBuyUnit] = useState<'SHARE' | 'USDT'>('SHARE');
  const [leverage, setLeverage] = useState(1);
  // 下单大按钮的转圈 / 打勾 / 晃一下
  const fx = useSubmitFx<'order'>();

  // bStock 符号是股票代号加 BUSDT（NVDABUSDT），详情没回来之前先按它认 logo
  const ticker = info?.ticker ?? symbol.replace(/BUSDT$/, '');
  const brand = getStockBrand(ticker);
  const tick = useCryptoStream(symbol, 'spot');
  const livePrice = tick?.price ?? info?.price ?? 0;
  // 数量步长（对齐Binance现货过滤器）
  const step = useTradeFilter('spot', symbol).stepSize;
  // 买入能花的钱 = min(全仓可用, 余额)：后端买入先过全仓可用这道闸再扣余额
  const available = useCrossAccount().spendable ?? 0;

  const load = useCallback(() => {
    bstockApi.detail(symbol).then(setInfo).catch(() => { /* keep */ });
    if (!loggedIn) return;
    bstockApi.positions()
      .then(ps => setPosition(ps.find(p => p.symbol === symbol) ?? null))
      .catch(() => setPosition(null));
  }, [symbol, loggedIn]);
  useEffect(load, [load]);

  // 24h 基准与高低：1h×25 根，首根收盘当基准（与列表行同口径），25 根里取最高/最低
  const [day, setDay] = useState({ base: 0, high: 0, low: 0 });
  useEffect(() => {
    let cancelled = false;
    bstockApi.klines(symbol, '1h', 25)
      .then(rows => {
        if (cancelled || !rows.length) return;
        setDay({
          base: Number(rows[0][4]),
          high: Math.max(...rows.map(r => Number(r[2]))),
          low: Math.min(...rows.map(r => Number(r[3]))),
        });
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol]);
  const change = day.base > 0 && livePrice > 0 ? livePrice - day.base : 0;
  const changePct = day.base > 0 ? (change / day.base) * 100 : 0;
  const isUp = change >= 0;

  // 切单位用的换算系数：买入现金占用 ≈ 数量 × 价格 × (1/杠杆 + 手续费率)
  // （bstock 输入的是总股数，保证金=成交额/杠杆、手续费按全额算）。
  // 预算换股数取付得起的最大股数（按步长对齐、现金占用按后端取整口径算），预估与提交用同一个数，界面不骗人
  const isUsdtInput = side === 'BUY' && buyUnit === 'USDT';
  const unitFactor = livePrice * (1 / (side === 'BUY' ? leverage : 1) + COMMISSION_RATE);
  const inputNum = parseFloat(qty) || 0;
  const held = position?.quantity ?? 0;
  // 实际下单量按步长向下对齐；全量卖出用精确持仓量（后端对全量卖出豁免步长）
  const qtyNum = isUsdtInput ? calcMaxSpotBuyQty(inputNum, livePrice, leverage, step)
    : side === 'SELL' && inputNum === held ? inputNum
    : floorToStep(inputNum, step);
  const isLevBuy = side === 'BUY' && leverage > 1;
  // 预估取整同后端：成交额、手续费四舍五入到分，杠杆单保证金向上取到分；卖出不吃杠杆
  const est = calcSpotOrderEstimate(qtyNum, livePrice, side === 'BUY' ? leverage : 1);
  const marginCost = est.margin + est.commission;   // 买入现金占用
  const proceeds = est.amount - est.commission;      // 卖出到账
  const hasEst = qtyNum > 0 && livePrice > 0;

  /** 切换单位时把已输入的值按当前价换算过去，不清空 */
  const switchUnit = (u: 'SHARE' | 'USDT') => {
    if (u === buyUnit) return;
    const v = parseFloat(qty);
    if (v > 0 && unitFactor > 0) {
      setQty(u === 'USDT' ? (v * unitFactor).toFixed(2) : String(floorToStep(v / unitFactor, step)));
    }
    setBuyUnit(u);
  };

  /**
   * 仓位 % 按钮的目标值。USDT 模式直接取可用的百分比当预算（向下取到分），换算成股数的事留给预估/提交；
   * 买入：现金占用 = 保证金(成交额/杠杆) + 手续费(按全额)，按后端口径取付得起的最大股数；卖出按持仓
   */
  const pctTarget = (pct: number) => {
    if (isUsdtInput) return Math.floor(available * pct * 100) / 100;
    if (side === 'SELL') return pct >= 1 ? held : floorToStep(held * pct, step);
    return calcMaxSpotBuyQty(available * pct, livePrice, leverage, step);
  };
  const activePct = inputNum > 0 && livePrice > 0
    ? (POSITION_PCTS.find(p => Math.abs(pctTarget(p) - inputNum) < 1e-9) ?? null)
    : null;
  const setPct = (pct: number) => {
    if (livePrice <= 0) return;
    setQty(String(Math.max(0, pctTarget(pct))));
  };

  /** 没过本地校验：说原因，按钮晃一下 */
  const reject = (msg: string) => { toast(msg, 'error'); fx.nope('order'); };

  const submit = async () => {
    if (qtyNum <= 0) return reject(isUsdtInput ? t('bstockDetail.toastEnterAmount') : t('bstockDetail.toastEnterQty'));
    if (side === 'SELL' && qtyNum > held) return reject(t('bstockDetail.toastInsufficient'));
    await fx.run('order', async () => {
      if (side === 'BUY') {
        await bstockApi.buy({ symbol, quantity: qtyNum, orderType: 'MARKET', ...(leverage > 1 ? { leverageMultiple: leverage } : {}) });
        toast(t('bstockDetail.toastBought'), 'success');
      } else {
        await bstockApi.sell({ symbol, quantity: qtyNum, orderType: 'MARKET' });
        toast(t('bstockDetail.toastSold'), 'success');
      }
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      setQty('');
      fetchUser();
      load();
    }, e => toast((e as Error).message || t('bstockDetail.toastOrderFailed'), 'error'));
  };

  const avgCost = position?.avgCost ?? 0;
  const posPnlPct = avgCost > 0 && livePrice > 0 ? ((livePrice - avgCost) / avgCost) * 100 : 0;
  const posPnl = livePrice > 0 ? (livePrice - avgCost) * held : 0;

  return (
    <div className="page-shell p-tr">
      {/* ====== 页头：代号 / 行情灯 / 报价大数 ====== */}
      <div className="ph">
        <div>
          <Link className="back" to="/bstock"><ChevronLeft className="ic" />{t('coin.backToList')}</Link>
          <div className="sym">
            <span className="n"><brand.icon className="ci" />{ticker}</span>
            {info && <span className="p">{info.name}{info.industry && ` · ${info.industry}`}</span>}
            {/* 行情灯：只有现货流，连上亮绿、断了闪红 */}
            <span className="feeds">
              <span className={tick?.ws ? 'live' : 'down'} title={t('coin.spotFeed')}><i />{t('coin.spot')}</span>
            </span>
          </div>
          <div className="meta">
            <span>BINANCE</span>
            <span>{t('bstockDetail.subtitle')}</span>
          </div>
        </div>

        <div className="px">
          {livePrice > 0 ? (
            <>
              <b className="big nf"><BigNum value={livePrice} /></b>
              <div className="sub">
                <span className={cn('chg', isUp ? 'up' : 'dn')}>
                  {t('coin.change', {
                    change: `${isUp ? '+' : ''}${fmtNum(change)}`,
                    pct: `${isUp ? '+' : ''}${changePct.toFixed(2)}`,
                  })}
                </span>
                <span>{t('coin.h24')}</span>
                <span>{t('coin.high')} <b>{day.high > 0 ? fmtNum(day.high) : '-'}</b></span>
                <span>{t('coin.low')} <b>{day.low > 0 ? fmtNum(day.low) : '-'}</b></span>
              </div>
            </>
          ) : (
            <>
              <Skeleton />
              <Skeleton className="t" />
            </>
          )}
        </div>
      </div>

      {/* ====== 图 + 公司信息 | 持仓 + 下单面板 ====== */}
      <div className="lay">
        <div className="left">
          <div className="chart h-[600px] xl:h-[822px] [@media(max-height:600px)]:h-[360px] phone:h-auto">
            {/* bstock 无后端K线广播：现货价格流驱动最后一根实时跳动 */}
            <CandleChart
              symbol={symbol}
              interval={chartIv}
              marketLabel={`BINANCE ${t('coin.spot')}`}
              klinesFn={bstockApi.klines}
              loadHistory={loggedIn}
              streamLive={false}
              onIntervalChange={setChartIv}
              tick={tick?.price != null && tick?.ts != null ? { price: tick.price, ts: tick.ts } : null}
              indicators
            />
          </div>

          {/* 公司信息：两列键值，下面一段简介 */}
          <section className="co">
            <div className="sec-h">
              <h2>{t('bstockDetail.companyInfo')}</h2>
              {info?.homepage && (
                <a className="go" href={info.homepage} target="_blank" rel="noopener noreferrer">
                  {t('bstockDetail.website')}<ExternalLink className="ic" />
                </a>
              )}
            </div>
            {info ? (
              <>
                <div className="co-kv">
                  {[
                    [t('bstockDetail.fMarketCap'), fmtCap(info.marketCap)],
                    [t('bstockDetail.fPe'), info.peRatio != null ? String(info.peRatio) : '—'],
                    [t('bstockDetail.fDividend'), info.dividendYield != null ? `${info.dividendYield}%` : '—'],
                    [t('bstockDetail.fIndustry'), info.industry ?? '—'],
                    [t('bstockDetail.fWeek52High'), info.week52High != null ? fmtNum(info.week52High) : '—'],
                    [t('bstockDetail.fWeek52Low'), info.week52Low != null ? fmtNum(info.week52Low) : '—'],
                    [t('bstockDetail.fCeo'), info.ceo ?? '—'],
                    [t('bstockDetail.fNameEn'), info.nameEn ?? info.ticker ?? '—'],
                  ].map(([k, v]) => (
                    <div key={k}><span className="shrink-0">{k}</span><b className="min-w-0 truncate" title={v}>{v}</b></div>
                  ))}
                </div>
                {info.description && <p className="co-desc">{info.description}</p>}
                {info.multiplier != null && info.multiplier !== 1 && (
                  <p className="mt-2 text-[12px] mute">{t('bstockDetail.multiplier', { value: info.multiplier.toFixed(4) })}</p>
                )}
              </>
            ) : (
              <div className="flex flex-col gap-2.5"><Skeleton className="h-16" /><Skeleton className="h-12" /></div>
            )}
          </section>
        </div>

        <aside className="side">
          {/* 当前持仓 */}
          {held > 0 && (
            <div className="blk spos">
              <div className="h">
                <div>
                  <b>{ticker}</b>
                  <span>{held} {t('bstockDetail.shares')}</span>
                </div>
                <div className={posPnlPct >= 0 ? 'up' : 'dn'}>
                  {fmtSignedPct(posPnlPct)}
                  <small>{fmtSignedUsd(posPnl)}</small>
                </div>
              </div>
              <div className="kv">
                <div><span>{t('coin.avgCost')}</span><b>₮{fmtNum(avgCost)}</b></div>
                <div><span>{t('coin.lastPrice')}</span><b>₮{fmtNum(livePrice)}</b></div>
                <div><span>{t('coin.marketValue')}</span><b>₮{fmtNum(held * livePrice)}</b></div>
              </div>
            </div>
          )}

          {!loggedIn ? (
            <LoginPrompt text={t('bstockDetail.loginToTrade')} className="blk" />
          ) : (
            <div className="ord blk">
              {/* 股票只有市价现货：面板头是可用 / 持有 */}
              <div className="rowb">
                <span className="avail">{t('bstockDetail.available')} <b>{fmtNum(available)}</b> USDT</span>
                <span className="avail">{t('bstockDetail.held')} <b>{held}</b> {t('bstockDetail.shares')}</span>
              </div>

              <Seg className="k-side" value={side} onChange={sd => { setSide(sd); setQty(''); }} options={[
                { value: 'BUY', label: t('bstockDetail.buy') },
                { value: 'SELL', label: t('bstockDetail.sell') },
              ]} />

              {/* 数量：买入时右侧单位可点，股数 ↔ USDT 预算换算 */}
              <div className="fld">
                <label className="fl" htmlFor={qtyId}>
                  <span>{isUsdtInput ? t('bstockDetail.amount') : t('bstockDetail.qty')}</span>
                  <span>{t('trade:open.minOrder', { step, unit: t('bstockDetail.unitShare') })}</span>
                </label>
                <NumInput
                  id={qtyId}
                  value={qty}
                  onChange={setQty}
                  placeholder={isUsdtInput ? t('bstockDetail.amountPlaceholder') : String(step)}
                  step={isUsdtInput ? '0.01' : String(step)}
                  min={0}
                  unit={isUsdtInput ? 'USDT' : t('bstockDetail.unitShare')}
                  unitTitle={side === 'BUY' ? t('trade:open.switchUnit') : undefined}
                  unitFlip={isUsdtInput}
                  onUnitClick={side === 'BUY' ? () => switchUnit(buyUnit === 'USDT' ? 'SHARE' : 'USDT') : undefined}
                />
                {livePrice > 0 && <PctRow active={activePct} onPick={setPct} />}
              </div>

              {/* 杠杆借款：只有买入有 */}
              <div className={cn('col', side === 'BUY' && 'open')}>
                <div>
                  <div className="lev gapb">
                    <div className="fl">{t('bstockDetail.leverage')}<b className="v">{leverage}x</b></div>
                    <LeverageSlider value={leverage} max={MAX_LEVERAGE} ticks={SPOT_LEVERAGE_OPTIONS} onChange={setLeverage} />
                  </div>
                </div>
              </div>

              {/* 预估：填了数量才展开 */}
              <div className={cn('col', hasEst && 'open')}>
                <div>
                  {hasEst && (
                    <div className="kv sum">
                      {isUsdtInput && (
                        <div>
                          <span>{t('bstockDetail.estFill')}{leverage > 1 ? ` (${leverage}x)` : ''}</span>
                          <b>{t('bstockDetail.estShares', { qty: fmtNum(qtyNum) })}</b>
                        </div>
                      )}
                      <div><span>{t('bstockDetail.orderValue')}</span><b>{fmtNum(est.amount)}</b></div>
                      <div><span>{t('bstockDetail.fee')}</span><b>{fmtNum(est.commission)}</b></div>
                      {isLevBuy && <div><span>{t('bstockDetail.borrowed')}</span><b className="wn">{fmtNum(est.amount - est.margin)}</b></div>}
                      <div>
                        <span>{side === 'BUY' ? t('bstockDetail.cashNeeded') : t('bstockDetail.proceeds')}</span>
                        <b className="tot">{fmtNum(side === 'BUY' ? marginCost : proceeds)}<small>USDT</small></b>
                      </div>
                    </div>
                  )}
                </div>
              </div>

              <FuturesActionButton
                onClick={submit}
                disabled={livePrice <= 0}
                fx={fx.cls('order')}
                side={side}
                label={ticker}
              />
              <p className="hint">{t('bstockDetail.footer')}</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
