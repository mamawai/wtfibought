import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { RefreshCw } from 'lucide-react';
import { useUserStore } from '../stores/userStore';
import { userApi, cryptoOrderApi, cryptoApi, futuresApi, predictionApi, bstockApi } from '../api';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { PortfolioChart } from '../components/PortfolioChart';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { FuturesPositionsCard } from '../components/coin/FuturesPositionsCard';
import { ProfitChart } from '../components/ProfitChart';
import { RadarChart } from '../components/RadarChart';
import { ProfilePublicToggle } from '../components/ProfilePublicToggle';
import { useCountUp } from '../hooks/useCountUp';
import { useStagger } from '../hooks/useStagger';
import { cn, fmtNum, fmtSignedPct, fmtSignedUsd } from '../lib/utils';
import type { CryptoPosition, FuturesPosition, PredictionPnl, AssetSnapshot, CategoryAverages, BStock } from '../types';
import { formatCoinPrice, getCoin } from '../lib/coinConfig';

interface CryptoRow extends CryptoPosition {
  /** 持有总量 = 可用 + 挂限价卖单冻结的 */
  heldQty: number;
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

/** 现货持仓估值：按持有总量算（同后端估值，卖单冻结的币也算）；toFixed 抹掉浮点尾差 */
function valueRow(cp: CryptoPosition, currentPrice: number) {
  const heldQty = Number((cp.quantity + cp.frozenQuantity).toFixed(8));
  const marketValue = currentPrice * heldQty;
  const costValue = cp.avgCost * heldQty;
  const profit = marketValue - costValue;
  const profitPct = costValue > 0 ? (profit / costValue) * 100 : 0;
  return { heldQty, currentPrice, marketValue, profit, profitPct };
}

interface BStockRow extends CryptoRow {
  name: string;
  ticker: string;
}

/** 右栏三个可切换面板；null = 默认的今日收益 + 资产分布 */
type Panel = 'dist' | 'profit' | 'ability';

/** 分布条四段的颜色，条和图例共用一份 */
const ALLOC_COLORS = {
  crypto: 'var(--color-foreground)',
  bstock: 'color-mix(in oklab, var(--color-foreground) 60%, var(--color-card-2))',
  futures: 'var(--color-primary)',
  cash: 'var(--color-border)',
};

export function Portfolio() {
  const navigate = useNavigate();
  const { t } = useTranslation(['portfolio', 'common']);
  const { user } = useUserStore();
  const { toast } = useToast();
  const [cryptoRows, setCryptoRows] = useState<CryptoRow[]>([]);
  const [bstockRows, setBstockRows] = useState<BStockRow[]>([]);
  const [futuresPositions, setFuturesPositions] = useState<FuturesPosition[]>([]);
  const [predictionPnl, setPredictionPnl] = useState<PredictionPnl | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [panel, setPanel] = useState<Panel | null>(null);
  const [profitData, setProfitData] = useState<AssetSnapshot[]>([]);
  const [profitLoaded, setProfitLoaded] = useState(false);
  const [realtimeSnapshot, setRealtimeSnapshot] = useState<AssetSnapshot | null>(null);
  const [categoryAverages, setCategoryAverages] = useState<CategoryAverages | null>(null);
  const [transferOpen, setTransferOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [resetting, setResetting] = useState(false);

  const cryptoBodyRef = useStagger<HTMLTableSectionElement>();
  const bstockBodyRef = useStagger<HTMLTableSectionElement>();

  const userId = user?.id;

  // 今日收益那块一进页就要，不再等收益面板打开
  useEffect(() => {
    if (userId == null) return;
    userApi.assetRealtime()
      .then(setRealtimeSnapshot)
      .catch(() => setRealtimeSnapshot(null));
  }, [userId, refreshNonce]);

  // 收益曲线按需拉一次，刷新/重置时作废重来
  useEffect(() => {
    if (panel !== 'profit' || profitLoaded) return;
    userApi.assetHistory(30)
      .then(setProfitData)
      .catch(() => setProfitData([]))
      .finally(() => setProfitLoaded(true));
  }, [panel, profitLoaded]);

  useEffect(() => {
    if (panel !== 'ability') return;
    userApi.categoryAverages(30)
      .then(setCategoryAverages)
      .catch(() => setCategoryAverages(null));
  }, [panel]);

  // 现货持仓：crypto_position 里混着 crypto 与 bStock，按 bstock 列表符号拆分
  const loadSpotPositions = useCallback(async () => {
    try {
      const [cps, blist] = await Promise.all([
        cryptoOrderApi.positions(),
        bstockApi.list().catch(() => [] as BStock[]),
      ]);
      const bmap = new Map<string, BStock>((blist ?? []).map(b => [b.symbol, b]));
      const all = cps ?? [];

      // 纯 crypto：逐只取现价
      const cryptoCps = all.filter(cp => !bmap.has(cp.symbol));
      const crows = await Promise.all(cryptoCps.map(async (cp) => {
        let currentPrice = 0;
        try {
          const res = await cryptoApi.price(cp.symbol);
          if (res && res.price) currentPrice = parseFloat(res.price);
        } catch { /* skip */ }
        return { ...cp, ...valueRow(cp, currentPrice) };
      }));
      setCryptoRows(crows);

      // bStock：现价/名称取自 bstock 列表（已含实时价）
      const brows: BStockRow[] = all.filter(cp => bmap.has(cp.symbol)).map(cp => {
        const b = bmap.get(cp.symbol)!;
        return { ...cp, name: b.name, ticker: b.ticker, ...valueRow(cp, b.price ?? 0) };
      });
      setBstockRows(brows);
    } catch {
      setCryptoRows([]);
      setBstockRows([]);
    }
  }, []);

  const requestKey = user ? `portfolio:user=${user.id}:refresh=${refreshNonce}` : null;
  useEffect(() => {
      if (requestKey == null) return;
      let cancelled = false;

      setLoading(true);
      userApi.portfolio()
        .then((u) => {
          if (cancelled) return;
          useUserStore.setState({ user: u });
          futuresApi.positions().then(setFuturesPositions).catch(() => setFuturesPositions([]));
          predictionApi.pnl().then(setPredictionPnl).catch(() => setPredictionPnl(null));
          // loading 只管现货持仓那节：等它拉完再收骨架屏
          return loadSpotPositions();
        })
        .catch(() => {
          if (cancelled) return;
          setCryptoRows([]);
          setBstockRows([]);
          setFuturesPositions([]);
          setPredictionPnl(null);
          toast(t('toast.loadFailed'), 'error', { description: t('toast.loadFailedHint') });
        })
        .finally(() => {
          if (cancelled) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps -- requestKey 已编码 user/refresh 全部刷新条件；effect 内会回写 user store，加 user 依赖会自触发死循环
    }, [requestKey]);

  // 大数滚动：hook 不能挂在 user 判空之后，值先兜 0
  const totalRef = useCountUp<HTMLSpanElement>(user?.totalAssets ?? 0, v => fmtNum(v));
  const pnlRef = useCountUp<HTMLSpanElement>(user?.profit ?? 0, fmtSignedUsd);
  const dailyRef = useCountUp<HTMLSpanElement>(realtimeSnapshot?.dailyProfit ?? 0, fmtSignedUsd);

  const closeReset = () => {
    setResetOpen(false);
    setConfirmName('');
  };

  const refresh = () => {
    setRefreshNonce(n => n + 1);
    setProfitLoaded(false);
    toast(t('toast.refreshed'), 'info');
  };

  // 重置后整页重拉：refreshNonce 一变就重走 portfolio（顺带回写 user store）+ 持仓，
  // 收益面板的缓存也一并作废，否则图上还挂着重置前的曲线
  const handleReset = async () => {
    setResetting(true);
    try {
      await userApi.resetAccount(confirmName);
      closeReset();
      setRefreshNonce(n => n + 1);
      setProfitLoaded(false);
      toast(t('toast.resetDone'), 'success');
    } catch (e) {
      toast((e as Error).message || t('toast.resetFailed'), 'error');
    } finally {
      setResetting(false);
    }
  };

  if (!user) return null;

  const isProfit = user.profit >= 0;
  const startCapital = user.totalAssets - user.profit;
  const cryptoTotal = cryptoRows.reduce((s, c) => s + c.marketValue, 0);
  const bstockTotal = bstockRows.reduce((s, b) => s + b.marketValue, 0);
  const futuresMargin = futuresPositions.reduce((s, f) => s + f.margin, 0);
  const futuresProfit = futuresPositions.reduce((s, f) => s + f.unrealizedPnl, 0);
  // 合约仓位算进资产的部分（同后端 futuresPositionValue）：逐仓=保证金+浮盈亏；
  // 全仓只算浮盈亏——占用制下保证金一直躺在余额里，再加就和"余额"那格重复了
  const futuresValue = (f: FuturesPosition) => f.marginMode === 'CROSS' ? f.unrealizedPnl : f.margin + f.unrealizedPnl;
  const futuresTotal = futuresPositions.reduce((s, f) => s + futuresValue(f), 0);
  const futuresChartRows = Array.from(
    futuresPositions.reduce((map, f) => {
      map.set(f.symbol, (map.get(f.symbol) ?? 0) + futuresValue(f));
      return map;
    }, new Map<string, number>())
  ).map(([symbol, marketValue]) => ({ symbol, marketValue }));
  const hasPrediction = predictionPnl != null && predictionPnl.totalBets > 0;
  const hasCrypto = cryptoRows.length > 0;
  const hasBstock = bstockRows.length > 0;
  const hasFutures = futuresPositions.length > 0;

  const rt = realtimeSnapshot;
  const dailyProfit = rt?.dailyProfit ?? 0;
  // 五分类当日拆解，0 的那格压成灰
  const cats = [
    { k: t('cat.crypto'), v: rt?.dailyCryptoProfit ?? 0 },
    { k: t('cat.commodity'), v: rt?.dailyCommodityProfit ?? 0 },
    { k: t('cat.bstock'), v: rt?.dailyBstockProfit ?? 0 },
    { k: t('cat.prediction'), v: rt?.dailyPredictionProfit ?? 0 },
    { k: t('cat.game'), v: rt?.dailyGameProfit ?? 0 },
  ];

  // 余额钱包 = 余额 + 冻结（限价买单、逐仓限价开仓冻着的钱），同后端总资产口径；钱包明细、分布条、饼图都用它
  const walletBalance = user.balance + user.frozenBalance;
  const allocRaw = [
    { k: t('alloc.crypto'), v: cryptoTotal, c: ALLOC_COLORS.crypto },
    { k: t('alloc.bstock'), v: bstockTotal, c: ALLOC_COLORS.bstock },
    { k: t('alloc.futures'), v: futuresTotal, c: ALLOC_COLORS.futures },
    { k: t('alloc.cash'), v: walletBalance, c: ALLOC_COLORS.cash },
  ];
  // 全仓浮亏会让合约那格成负数：占比只在正数里分，负的照实显示金额、占比记 0
  const allocTotal = allocRaw.reduce((s, a) => s + Math.max(0, a.v), 0);
  const alloc = allocRaw.map(a => ({ ...a, pct: allocTotal > 0 ? (Math.max(0, a.v) / allocTotal) * 100 : 0 }));

  const togglePanel = (p: Panel) => setPanel(cur => cur === p ? null : p);
  const panelBtn = (p: Panel, label: string) => (
    <button type="button" className={cn('btn sm', panel === p && 'fill')} onClick={() => togglePanel(p)}>{label}</button>
  );

  // 钱包明细一行：金额 17px 窄体，颜色由调用方给
  const walletRow = (label: string, value: string, cls?: string) => (
    <div className="kv text-[15px]">
      <span className="k">{label}</span>
      <span className={cn('v text-[17px] [font-stretch:85%]', cls)}>{value}</span>
    </div>
  );

  const signed = (v: number) => `${v >= 0 ? '+' : ''}${fmtNum(v)}`;

  return (
    <div className="wrap">
      <div className="page-h flex-wrap">
        <h1>{t('page.title')}</h1>
        <div className="r">
          <button type="button" className="btn sm" onClick={() => navigate('/ledger')}>{t('page.ledger')}</button>
          <button type="button" className="btn sm" onClick={() => navigate('/portfolio/history')}>{t('page.history')}</button>
          <button type="button" className="btn sm" onClick={refresh}><RefreshCw className="ic" />{t('common:refresh')}</button>
        </div>
      </div>

      {user.bankrupt && (
        <div className="mt-3.5 border-l-4 border-loss pl-3 py-1 text-[13px]">
          <div className="font-bold text-loss">{t('bankrupt.title')}</div>
          <div className="mute">
            {t('bankrupt.detail', {
              times: user.bankruptCount,
              date: user.bankruptResetDate ?? t('bankrupt.nextTradingDay'),
            })}
          </div>
        </div>
      )}

      {/* ====== 总资产 / 钱包明细 ｜ 今日收益 + 资产分布（或三个面板之一） ====== */}
      <section className="grid grid-cols-1 xl:grid-cols-12 gap-8 mt-7 border-t-2 border-foreground pt-[22px]">
        <div className="xl:col-span-5">
          <div className="mute text-[13px]">{t('ov.totalAssets')}</div>
          <div className="num cond mt-1.5 text-[clamp(64px,6.4vw,120px)] font-bold leading-[0.96]">
            <small className="text-[0.36em] font-medium mute align-[1em] mr-[0.06em] [font-stretch:90%] tracking-normal">$</small>
            <span ref={totalRef} />
          </div>

          <div className={cn('num flex items-baseline flex-wrap gap-3.5 mt-3.5 text-[22px] font-semibold [font-stretch:80%]', isProfit ? 'up' : 'dn')}>
            <span ref={pnlRef} />
            <span>{fmtSignedPct(user.profitPct)}</span>
            <span className="text-[13px] font-medium mute [font-stretch:100%]">{t('ov.since', { amount: `$${fmtNum(startCapital)}` })}</span>
          </div>

          <div className="num mt-[26px] grid grid-cols-2 gap-x-10">
            {walletRow(t('ov.balanceWallet'), fmtNum(walletBalance))}
            {walletRow(t('ov.gameWallet'), fmtNum(user.gameBalance))}
            {walletRow(t('ov.futuresMargin'), fmtNum(futuresMargin))}
            {walletRow(t('ov.futuresUnrealized'), signed(futuresProfit), futuresProfit >= 0 ? 'up' : 'dn')}
            {walletRow(t('ov.marginLoan'), fmtNum(user.marginLoanPrincipal), user.marginLoanPrincipal > 0 ? 'wn' : 'mute')}
            {walletRow(t('ov.interestAccrued'), fmtNum(user.marginInterestAccrued), user.marginInterestAccrued > 0 ? 'dn' : 'mute')}
          </div>

          <div className="flex flex-wrap gap-2.5 mt-[22px]">
            <button type="button" className="btn sm fill" onClick={() => setTransferOpen(true)}>{t('ov.transfer')}</button>
            {panelBtn('dist', t('ov.dist'))}
            {panelBtn('profit', t('ov.profit'))}
            {panelBtn('ability', t('ov.ability'))}
          </div>
        </div>

        <div className="xl:col-span-6 xl:col-start-7">
          {panel ? (
            <>
              <div className="flex items-baseline justify-between mb-3">
                <b className="text-[15px] font-extrabold">{t(`ov.${panel}`)}</b>
              </div>
              {panel === 'dist' && (
                <div className="h-[320px]">
                  <PortfolioChart
                    cryptoPositions={cryptoRows}
                    bstockRows={bstockRows}
                    futuresRows={futuresChartRows}
                    balance={walletBalance}
                    gameBalance={user.gameBalance}
                  />
                </div>
              )}
              {panel === 'profit' && (profitLoaded ? <ProfitChart data={profitData} /> : <Skeleton className="w-full h-[320px]" />)}
              {panel === 'ability' && (categoryAverages ? <RadarChart userData={categoryAverages} /> : <Skeleton className="w-full h-[320px]" />)}
            </>
          ) : (
            <>
              <div>
                <div className="flex items-baseline justify-between">
                  <b className="text-[15px] font-extrabold">{t('daily.title')}</b>
                </div>
                <div className={cn('num cond mt-2.5 text-[44px] font-bold leading-none', rt == null ? 'mute' : dailyProfit >= 0 ? 'up' : 'dn')}>
                  <span ref={dailyRef} />
                  <small className="ml-2.5 text-[16px] font-semibold">{fmtSignedPct(rt?.dailyProfitPct ?? 0)}</small>
                </div>
                <div className="num grid grid-cols-2 xl:grid-cols-5 gap-3.5 mt-[18px] pt-4 border-t border-border">
                  {cats.map(c => (
                    <div key={c.k}>
                      <div className="text-[12.5px] mute">{c.k}</div>
                      <div className={cn('mt-0.5 text-[19px] font-semibold [font-stretch:85%]', c.v === 0 ? 'mute' : c.v > 0 ? 'up' : 'dn')}>{signed(c.v)}</div>
                    </div>
                  ))}
                </div>
              </div>

              <div className="mt-[30px]">
                <div className="flex items-baseline justify-between mb-3">
                  <b className="text-[15px] font-extrabold">{t('alloc.title')}</b>
                </div>
                <div className="flex h-[22px] gap-0.5">
                  {alloc.map(a => <i key={a.k} className="block h-full" style={{ width: `${a.pct}%`, background: a.c }} />)}
                </div>
                <div className="num grid grid-cols-2 xl:grid-cols-4 gap-3.5 mt-3.5">
                  {alloc.map(a => (
                    <div key={a.k} className="flex flex-col gap-0.5 pl-3 border-l-4" style={{ borderColor: a.c }}>
                      <span className="text-[12.5px] mute">{a.k}</span>
                      <span className="text-[17px] font-semibold [font-stretch:85%]">
                        {fmtNum(a.v)}<small className="ml-1.5 text-[12px] font-medium mute">{a.pct.toFixed(1)}%</small>
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>
      </section>

      {/* 合约持仓整节由卡片自己渲染（空仓时 return null） */}
      {hasFutures && (
        <FuturesPositionsCard
          refreshKey={refreshNonce}
          showCloseAll
          onOrdersChanged={() => setRefreshNonce(n => n + 1)}
        />
      )}

      {/* ====== 现货持仓：左币种右股票 ====== */}
      <section className="sec">
        {loading ? (
          <div className="flex flex-col gap-3">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12 w-full" />)}
          </div>
        ) : !hasCrypto && !hasBstock ? (
          <div className="py-10 text-center text-[14px] mute">{t('holdings.empty')}</div>
        ) : (
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-12">
            {hasCrypto && (
              <div>
                <div className="sec-h">
                  <h2>{t('spot.cryptoTitle')}<small>{t('spot.cryptoCount', { count: cryptoRows.length })}</small></h2>
                </div>
                <div className="overflow-x-auto">
                  <table className="tbl num min-w-[560px]">
                    <thead>
                      <tr>
                        <th>{t('spot.colCoin')}</th>
                        <th className="r">{t('spot.colQty')}</th>
                        <th className="r">{t('spot.colAvg')}</th>
                        <th className="r">{t('spot.colLast')}</th>
                        <th className="r">{t('spot.colValue')}</th>
                        <th className="r">{t('spot.colPnl')}</th>
                      </tr>
                    </thead>
                    <tbody ref={cryptoBodyRef}>
                      {cryptoRows.map(c => {
                        const coin = getCoin(c.symbol);
                        const up = c.profit >= 0;
                        return (
                          <tr key={c.id} className="cursor-pointer" onClick={() => navigate(`/coin/${c.symbol}`)}>
                            <td>
                              <span className="sym">{coin.name}</span>
                              <span className="sub">
                                {coin.pair}
                                {coin.unitLabel && ` · ${t('spot.approx', { value: (c.heldQty * coin.unitFactor!).toFixed(1), unit: coin.unitLabel })}`}
                              </span>
                            </td>
                            <td className="r">{c.heldQty}</td>
                            <td className="r">{formatCoinPrice(c.symbol, c.avgCost)}</td>
                            <td className="r">{formatCoinPrice(c.symbol, c.currentPrice)}</td>
                            <td className="r">{fmtNum(c.marketValue)}</td>
                            <td className={cn('r font-bold', up ? 'up' : 'dn')}>
                              {signed(c.profit)}
                              <span className={cn('sub', up ? 'text-gain' : 'text-loss')}>{fmtSignedPct(c.profitPct)}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {hasBstock && (
              <div>
                <div className="sec-h">
                  <h2>{t('spot.bstockTitle')}<small>{t('spot.bstockCount', { count: bstockRows.length })}</small></h2>
                </div>
                <div className="overflow-x-auto">
                  <table className="tbl num min-w-[560px]">
                    <thead>
                      <tr>
                        <th>{t('spot.colStock')}</th>
                        <th className="r">{t('spot.colShares')}</th>
                        <th className="r">{t('spot.colAvg')}</th>
                        <th className="r">{t('spot.colLast')}</th>
                        <th className="r">{t('spot.colValue')}</th>
                        <th className="r">{t('spot.colPnl')}</th>
                      </tr>
                    </thead>
                    <tbody ref={bstockBodyRef}>
                      {bstockRows.map(b => {
                        const up = b.profit >= 0;
                        return (
                          <tr key={b.id} className="cursor-pointer" onClick={() => navigate(`/bstock/${b.symbol}`)}>
                            <td>
                              <span className="sym">{b.ticker}</span>
                              <span className="sub">{b.name}</span>
                            </td>
                            <td className="r">{b.heldQty}</td>
                            <td className="r">{fmtNum(b.avgCost)}</td>
                            <td className="r">{fmtNum(b.currentPrice)}</td>
                            <td className="r">{fmtNum(b.marketValue)}</td>
                            <td className={cn('r font-bold', up ? 'up' : 'dn')}>
                              {signed(b.profit)}
                              <span className={cn('sub', up ? 'text-gain' : 'text-loss')}>{fmtSignedPct(b.profitPct)}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        )}
      </section>

      {/* ====== BTC 涨跌预测 ====== */}
      {hasPrediction && predictionPnl && (
        <section className="sec">
          <div className="sec-h">
            <h2>{t('prediction.title')}<small>{t('prediction.wallet')}</small></h2>
          </div>
          <div className="strip num grid-cols-2 md:grid-cols-4 cursor-pointer" onClick={() => navigate('/prediction')}>
            <div>
              <div className="k">{t('prediction.realized')}</div>
              <div className={cn('v', predictionPnl.realizedPnl >= 0 ? 'up' : 'dn')}>{fmtSignedUsd(predictionPnl.realizedPnl)}</div>
            </div>
            <div>
              <div className="k">{t('prediction.totalBets')}</div>
              <div className="v">{t('prediction.bets', { count: predictionPnl.totalBets })}</div>
            </div>
            <div>
              <div className="k">{t('prediction.winRate')}</div>
              <div className="v">
                {Math.round(predictionPnl.winRate)}%
                <small>{t('prediction.record', { won: predictionPnl.wonBets, lost: predictionPnl.lostBets })}</small>
              </div>
            </div>
            <div>
              <div className="k">{t('prediction.active')}</div>
              <div className="v">
                {t('prediction.bets', { count: predictionPnl.activeBets })}
                <small>{t('prediction.activeHint', { cost: fmtNum(predictionPnl.activeCost), value: fmtNum(predictionPnl.activeValue) })}</small>
              </div>
            </div>
          </div>
        </section>
      )}

      <ProfilePublicToggle />

      {/* ====== 重置账户 ====== */}
      <section className="sec">
        <div className="sec-h">
          <h2>{t('reset.title')}</h2>
        </div>
        <div className="flex items-center flex-wrap gap-8">
          <p className="m-0 text-[14px] mute max-w-[70ch] leading-[1.6]">{t('reset.desc')}</p>
          <button type="button" className="btn ml-auto border-loss text-loss" onClick={() => setResetOpen(true)}>{t('reset.action')}</button>
        </div>
      </section>

      {/* 二次确认：必须逐字输入用户名，防误点 */}
      <Dialog open={resetOpen} onClose={closeReset} className="bg-background border-foreground shadow-none">
        <DialogHeader>
          <h2 className="text-[17px] font-extrabold text-loss">{t('reset.dialogTitle')}</h2>
        </DialogHeader>
        <DialogContent>
          <div className="flex flex-col gap-3">
            <p className="text-[13px] mute leading-relaxed">{t('reset.warnClears')}</p>
            <p className="text-[13px] wn leading-relaxed">{t('reset.warnCost')}</p>
            <p className="text-[13px]">
              {/* 用户名夹在句子中间，中英语序不同，整句交给 Trans 摆位 */}
              <Trans
                ns="portfolio"
                i18nKey="reset.confirmName"
                values={{ name: user.username }}
                components={[<strong key="name" className="text-foreground" />]}
              />
            </p>
            <div className="input">
              <input
                className="flex-1 min-w-0 bg-transparent border-0 outline-none [font:inherit]"
                value={confirmName}
                onChange={e => setConfirmName(e.target.value)}
                placeholder={t('reset.namePlaceholder')}
                autoComplete="off"
              />
            </div>
          </div>
        </DialogContent>
        <DialogFooter>
          <button type="button" className="btn sm" onClick={closeReset}>{t('common:cancel')}</button>
          <button
            type="button"
            className="btn sm loss disabled:opacity-40 disabled:cursor-default"
            disabled={confirmName !== user.username || resetting}
            onClick={handleReset}
          >
            {resetting ? t('reset.submitting') : t('reset.submit')}
          </button>
        </DialogFooter>
      </Dialog>

      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} />
    </div>
  );
}
