import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ExternalLink } from 'lucide-react';
import { bstockApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useCrossAccount } from '../hooks/useCrossAccount';
import { useToast } from '../components/ui/use-toast';
import { Skeleton } from '../components/ui/skeleton';
import { CandleChart } from '../components/CandleChart';
import { FuturesActionButton } from '../components/FuturesActionButton';
import { LeverageSlider } from '../components/LeverageSlider';
import { LoginPrompt } from '../components/LoginPrompt';
import { NumInput, PctRow } from '../components/coin/TradeFields';
import { useQuantityAnimation } from '../components/coin/useQuantityAnimation';
import { COMMISSION_RATE, POSITION_PCTS, SPOT_LEVERAGE_OPTIONS, calcMaxSpotBuyQty, calcSpotOrderEstimate, floorToStep } from '../components/coin/futuresMath';
import { useTradeFilter } from '../lib/tradeFilters';
import { getStockBrand } from '../lib/stockConfig';
import { cn, fmtNum, fmtSignedUsd } from '../lib/utils';
import type { BStock, CryptoPosition } from '../types';

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
  const navigate = useNavigate();
  const { t } = useTranslation(['market', 'trade']);
  const { toast } = useToast();
  const fetchUser = useUserStore(s => s.fetchUser);
  // 游客只看行情和公司信息，持仓不拉、交易面板换成去登录
  const loggedIn = useUserStore(s => !!s.token);

  const [info, setInfo] = useState<BStock | null>(null);
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  // 周期：图表顶栏自己切，页面只存当前档
  const [chartIv, setChartIv] = useState<'5m' | '15m' | '1h' | '4h' | '1d'>('5m');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [qty, setQty] = useState('');
  // 买入输入单位：股数 / USDT 预算。USDT 指"含手续费的现金占用"（用杠杆时即保证金+手续费）
  const [buyUnit, setBuyUnit] = useState<'SHARE' | 'USDT'>('SHARE');
  const [leverage, setLeverage] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(false);
  const animateQty = useQuantityAnimation(qty, setQty);

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

  useEffect(() => {
    if (!actionSuccess) return;
    const timer = window.setTimeout(() => setActionSuccess(false), 1600);
    return () => window.clearTimeout(timer);
  }, [actionSuccess]);

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
    // 卖出全量直接填精确持仓量，不走缓动
    if (side === 'SELL' && pct >= 1) { setQty(String(held)); return; }
    animateQty(Math.max(0, pctTarget(pct)), isUsdtInput ? 0.01 : step);
  };

  const submit = async () => {
    if (qtyNum <= 0) { toast(isUsdtInput ? t('bstockDetail.toastEnterAmount') : t('bstockDetail.toastEnterQty'), 'error'); return; }
    if (side === 'SELL' && qtyNum > held) { toast(t('bstockDetail.toastInsufficient'), 'error'); return; }
    setSubmitting(true);
    try {
      if (side === 'BUY') {
        await bstockApi.buy({ symbol, quantity: qtyNum, orderType: 'MARKET', ...(leverage > 1 ? { leverageMultiple: leverage } : {}) });
        toast(t('bstockDetail.toastBought'), 'success');
      } else {
        await bstockApi.sell({ symbol, quantity: qtyNum, orderType: 'MARKET' });
        toast(t('bstockDetail.toastSold'), 'success');
      }
      setActionSuccess(true);
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      setQty('');
      fetchUser();
      load();
    } catch (e) {
      toast((e as Error).message || t('bstockDetail.toastOrderFailed'), 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="wrap">
      {/* ====== 页头：代号 / 行情灯 / 报价大数 ====== */}
      <div className="grid grid-cols-1 xl:grid-cols-[1fr_auto] gap-8 items-end pt-8">
        <div>
          <button
            type="button"
            onClick={() => navigate('/bstock')}
            className="inline-flex items-center gap-1 mb-2.5 text-[13px] mute hover:text-foreground transition-colors cursor-pointer"
          >
            <ChevronLeft className="w-3.5 h-3.5" />{t('coin.backToList')}
          </button>

          <div className="flex items-baseline gap-4 flex-wrap">
            {/* data-reveal-icon：列表页的过渡层等它挂出来再整层淡出（lib/coinReveal） */}
            <span className="inline-flex items-center gap-3">
              <brand.icon data-reveal-icon={symbol} className="w-9 h-9 shrink-0" />
              <b className="cond text-[44px] font-bold leading-none">{ticker}</b>
            </span>
            {info && <span className="text-[15px] mute">{info.name}{info.industry && ` · ${info.industry}`}</span>}
            {/* 行情灯：只有现货流，连上亮绿、断了闪红 */}
            <span className="self-center text-[12.5px] font-semibold" title={t('coin.spotFeed')}>
              <i className={cn('inline-block w-[7px] h-[7px] mr-1.5 align-[1px]', tick?.ws ? 'bg-gain' : 'bg-loss animate-pulse')} />{t('coin.spot')}
            </span>
          </div>

          <div className="flex items-center gap-4 flex-wrap mt-2 text-[12px] mute">
            <span>BINANCE</span>
            <span>{t('bstockDetail.subtitle')}</span>
          </div>
        </div>

        <div className="num text-left xl:text-right">
          {livePrice > 0 ? (
            <>
              <b className="cond block text-[64px] font-bold leading-none">${fmtNum(livePrice)}</b>
              <div className="flex items-baseline flex-wrap gap-3 mt-2 text-[15px] font-semibold justify-start xl:justify-end">
                <span className={isUp ? 'up' : 'dn'}>
                  {t('coin.change', {
                    change: `${isUp ? '+' : ''}${fmtNum(change)}`,
                    pct: `${isUp ? '+' : ''}${changePct.toFixed(2)}`,
                  })}
                </span>
                <span className="mute font-medium text-[13px]">{t('coin.h24')}</span>
                <span className="mute font-medium text-[13px]">{t('coin.high')} <b className="text-foreground">{day.high > 0 ? fmtNum(day.high) : '-'}</b></span>
                <span className="mute font-medium text-[13px]">{t('coin.low')} <b className="text-foreground">{day.low > 0 ? fmtNum(day.low) : '-'}</b></span>
              </div>
            </>
          ) : (
            <div className="flex flex-col items-start xl:items-end gap-2">
              <Skeleton className="h-16 w-56" />
              <Skeleton className="h-5 w-40" />
            </div>
          )}
        </div>
      </div>

      {/* ====== 图 + 公司信息 | 持仓 + 下单面板 ====== */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-8 mt-7 border-t-2 border-foreground pt-[18px]">
        <div className="xl:col-span-8 flex flex-col gap-5">
          <div className="h-[600px] xl:h-[822px] [@media(max-height:600px)]:h-[360px] phone:h-auto">
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

          {/* 公司信息 */}
          <div className="border-t-2 border-foreground pt-3.5">
            <div className="sec-h">
              <h2>{t('bstockDetail.companyInfo')}</h2>
              {info?.homepage && (
                <a href={info.homepage} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1">
                  {t('bstockDetail.website')}<ExternalLink className="w-3 h-3" />
                </a>
              )}
            </div>
            {info ? (
              <>
                <div className="num grid grid-cols-1 sm:grid-cols-2 gap-x-8">
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
                    <div key={k} className="kv gap-4">
                      <span className="k shrink-0">{k}</span>
                      <span className="v min-w-0 truncate" title={v}>{v}</span>
                    </div>
                  ))}
                </div>
                {info.description && <p className="mt-4 text-[13px] leading-relaxed mute">{info.description}</p>}
                {info.multiplier != null && info.multiplier !== 1 && (
                  <p className="mt-2 text-[12px] mute">{t('bstockDetail.multiplier', { value: info.multiplier.toFixed(4) })}</p>
                )}
              </>
            ) : (
              <div className="space-y-2"><Skeleton className="h-16 w-full" /><Skeleton className="h-12 w-full" /></div>
            )}
          </div>
        </div>

        <aside className="xl:col-span-4 flex flex-col gap-[18px]">
          {/* 当前持仓 */}
          {held > 0 && (() => {
            const avgCost = position?.avgCost ?? 0;
            const pnlPct = avgCost > 0 && livePrice > 0 ? ((livePrice - avgCost) / avgCost) * 100 : 0;
            const pnlAmount = livePrice > 0 ? (livePrice - avgCost) * held : 0;
            const isPnlUp = pnlPct >= 0;
            return (
              <div className="border-t-2 border-foreground pt-3.5">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <b className="text-[16px] font-bold">{ticker}</b>
                    <span className="num ml-2 text-[13px] font-semibold mute">{held} {t('bstockDetail.shares')}</span>
                  </div>
                  <div className={cn('num shrink-0 text-right', isPnlUp ? 'up' : 'dn')}>
                    <div className="text-[16px] font-bold">{isPnlUp ? '+' : ''}{pnlPct.toFixed(2)}%</div>
                    <div className="text-[12px] font-semibold">{fmtSignedUsd(pnlAmount)}</div>
                  </div>
                </div>
                <div className="num mt-3">
                  <div className="kv"><span className="k">{t('coin.avgCost')}</span><span className="v">${fmtNum(avgCost)}</span></div>
                  <div className="kv"><span className="k">{t('coin.lastPrice')}</span><span className="v">${fmtNum(livePrice)}</span></div>
                  <div className="kv"><span className="k">{t('coin.marketValue')}</span><span className="v">${fmtNum(held * livePrice)}</span></div>
                </div>
              </div>
            );
          })()}

          {!loggedIn ? (
            <LoginPrompt text={t('bstockDetail.loginToTrade')} className="border-t-2 border-foreground pt-3.5" />
          ) : (
            <>
              {/* 可用 / 持有 */}
              <div className="flex justify-between items-center gap-4 text-[12.5px] text-muted-foreground">
                <span>{t('bstockDetail.available')} <b className="num text-foreground font-semibold">{fmtNum(available)}</b> USDT</span>
                <span>{t('bstockDetail.held')} <b className="num text-foreground font-semibold">{held}</b> {t('bstockDetail.shares')}</span>
              </div>

              {/* 买入 / 卖出 */}
              <div className="grid grid-cols-2 border-[1.5px] border-foreground">
                {(['BUY', 'SELL'] as const).map(sd => (
                  <button
                    key={sd}
                    type="button"
                    onClick={() => { setSide(sd); setQty(''); }}
                    className={cn(
                      'h-12 text-[17px] font-extrabold cursor-pointer transition-colors',
                      side === sd
                        ? (sd === 'BUY' ? 'bg-gain text-white' : 'bg-loss text-white')
                        : 'text-muted-foreground hover:text-foreground',
                    )}
                  >
                    {sd === 'BUY' ? t('bstockDetail.buy') : t('bstockDetail.sell')}
                  </button>
                ))}
              </div>

              {/* 数量：买入时右侧单位可点，股数 ↔ USDT 预算换算 */}
              <div className="field">
                <label>{isUsdtInput ? t('bstockDetail.amount') : t('bstockDetail.qty')}</label>
                <NumInput
                  value={qty}
                  onChange={setQty}
                  placeholder={isUsdtInput ? t('bstockDetail.amountPlaceholder') : t('bstockDetail.qty')}
                  step={isUsdtInput ? '0.01' : String(step)}
                  min={0}
                  unit={isUsdtInput ? 'USDT' : t('bstockDetail.unitShare')}
                  unitTitle={side === 'BUY' ? t('trade:open.switchUnit') : undefined}
                  onUnitClick={side === 'BUY' ? () => switchUnit(buyUnit === 'USDT' ? 'SHARE' : 'USDT') : undefined}
                />
                {livePrice > 0 && <PctRow active={activePct} onPick={setPct} />}
              </div>

              {/* 杠杆借款：仅买入 */}
              {side === 'BUY' && (
                <div className="flex flex-col gap-2">
                  <div className="flex justify-between items-baseline text-[12.5px] font-semibold text-muted-foreground">
                    <span>{t('bstockDetail.leverage')}</span>
                    <b className="num text-[20px] font-bold text-foreground">{leverage}x</b>
                  </div>
                  <LeverageSlider value={leverage} max={MAX_LEVERAGE} ticks={SPOT_LEVERAGE_OPTIONS} onChange={setLeverage} />
                </div>
              )}

              {/* 预估：数字随数量实时跳动（百分比按钮触发缓动） */}
              {qtyNum > 0 && livePrice > 0 && (
                <div className="num border-t border-foreground pt-1">
                  {isUsdtInput && (
                    <div className="kv py-[7px]">
                      <span className="k">{t('bstockDetail.estFill')}{leverage > 1 ? ` (${leverage}x)` : ''}</span>
                      <span className="v">{t('bstockDetail.estShares', { qty: fmtNum(qtyNum) })}</span>
                    </div>
                  )}
                  <div className="kv py-[7px]"><span className="k">{t('bstockDetail.orderValue')}</span><span className="v">{fmtNum(est.amount)}</span></div>
                  <div className="kv py-[7px]"><span className="k">{t('bstockDetail.fee')}</span><span className="v">{fmtNum(est.commission)}</span></div>
                  {isLevBuy && <div className="kv py-[7px]"><span className="k">{t('bstockDetail.borrowed')}</span><span className="v wn">{fmtNum(est.amount - est.margin)}</span></div>}
                  <div className="kv py-[7px] border-b-0 text-[16px]">
                    <span className="k">{side === 'BUY' ? t('bstockDetail.cashNeeded') : t('bstockDetail.proceeds')}</span>
                    <span className="v text-[20px] font-bold [font-stretch:85%]">{fmtNum(side === 'BUY' ? marginCost : proceeds)} USDT</span>
                  </div>
                </div>
              )}

              {/* 下单：与 crypto 现货同款动画按钮（悬停刷卡动效 + 成交对勾） */}
              <FuturesActionButton
                onClick={submit}
                disabled={submitting || qtyNum <= 0 || livePrice <= 0}
                loading={submitting}
                success={actionSuccess}
                side={side}
                label={ticker}
              />
              <p className="text-[12px] text-muted-foreground leading-[1.6]">{t('bstockDetail.footer')}</p>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
