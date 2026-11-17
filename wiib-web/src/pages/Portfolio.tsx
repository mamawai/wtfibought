import { useEffect, useState, useCallback, type CSSProperties } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeftRight, ChevronRight, Inbox, RefreshCw, TriangleAlert } from 'lucide-react';
import { useUserStore } from '../stores/userStore';
import { userApi, cryptoOrderApi, cryptoApi, futuresApi, predictionApi, bstockApi } from '../api';
import { Skeleton } from '../components/ui/skeleton';
import { Seg } from '../components/ui/seg';
import { BigNum } from '../components/ui/big-num';
import { useToast } from '../components/ui/use-toast';
import { EmptyState } from '../components/EmptyState';
import { PortfolioChart } from '../components/PortfolioChart';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { FuturesPositionsCard } from '../components/coin/FuturesPositionsCard';
import { ProfitChart } from '../components/ProfitChart';
import { RadarChart } from '../components/RadarChart';
import { ProfilePublicToggle } from '../components/ProfilePublicToggle';
import { ResetAccountDialog } from '../components/ResetAccountDialog';
import { cn, fmtNum, fmtSigned, fmtSignedPct, fmtSignedUsd, spinIcon } from '../lib/utils';
import type { CryptoPosition, FuturesPosition, PredictionPnl, AssetSnapshot, CategoryAverages, BStock } from '../types';
import { formatCoinPrice, getCoin } from '../lib/coinConfig';
import './Portfolio.css';

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

/** 右边那块的四个面板 */
type Panel = 'day' | 'dist' | 'profit' | 'ability';

/** 分布条四段的颜色，条和图例共用一份 */
const ALLOC_COLORS = {
  crypto: 'var(--color-foreground)',
  bstock: 'color-mix(in srgb,var(--color-foreground) 40%,var(--color-background))',
  futures: 'var(--color-primary)',
  cash: 'var(--color-line2)',
};

const upDn = (v: number) => v >= 0 ? 'up' : 'dn';

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
  const [panel, setPanel] = useState<Panel>('day');
  const [profitData, setProfitData] = useState<AssetSnapshot[]>([]);
  const [profitLoaded, setProfitLoaded] = useState(false);
  const [realtimeSnapshot, setRealtimeSnapshot] = useState<AssetSnapshot | null>(null);
  const [categoryAverages, setCategoryAverages] = useState<CategoryAverages | null>(null);
  const [transferOpen, setTransferOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);

  const userId = user?.id;

  // 今日收益那块的数，一进页就拉
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

  const refresh = () => {
    setRefreshNonce(n => n + 1);
    setProfitLoaded(false);
    toast(t('toast.refreshed'), 'info');
  };

  if (!user) return null;

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

  const rt = realtimeSnapshot;
  const dailyProfit = rt?.dailyProfit ?? 0;
  // 四分类当日拆解，0 的那格压成灰
  const cats = [
    { k: t('cat.crypto'), v: rt?.dailyCryptoProfit ?? 0 },
    { k: t('cat.commodity'), v: rt?.dailyCommodityProfit ?? 0 },
    { k: t('cat.bstock'), v: rt?.dailyBstockProfit ?? 0 },
    { k: t('cat.prediction'), v: rt?.dailyPredictionProfit ?? 0 },
  ];

  // 余额钱包 = 余额 + 冻结（限价买单、逐仓限价开仓冻着的钱），同后端总资产口径；钱包明细、分布条、圆环都用它
  const walletBalance = user.balance + user.frozenBalance;
  const allocRaw = [
    { k: t('alloc.crypto'), v: cryptoTotal, c: ALLOC_COLORS.crypto },
    { k: t('alloc.bstock'), v: bstockTotal, c: ALLOC_COLORS.bstock },
    { k: t('alloc.futures'), v: futuresTotal, c: ALLOC_COLORS.futures },
    { k: t('alloc.cash'), v: walletBalance, c: ALLOC_COLORS.cash },
  ];
  // 占比按正数合计算；全仓浮亏会让合约那格成负数：不进条，图例里点和数字标红
  const allocTotal = allocRaw.reduce((s, a) => s + Math.max(0, a.v), 0);
  const alloc = allocRaw.map(a => ({ ...a, pct: allocTotal > 0 ? (a.v / allocTotal) * 100 : 0 }));

  const panelTitle = { day: t('daily.title'), dist: t('alloc.title'), profit: t('ov.profit'), ability: t('ov.ability') }[panel];

  return (
    <div className="page-shell p-pf">
      <div className="pg-h">
        <div><h1>{t('page.title')}</h1></div>
        <div className="r">
          <Link className="btn" to="/ledger">{t('page.ledger')}</Link>
          <Link className="btn" to="/portfolio/history">{t('page.history')}</Link>
          <button type="button" className="btn" onClick={e => { spinIcon(e); refresh(); }}><RefreshCw className="ic" />{t('common:refresh')}</button>
        </div>
      </div>

      {user.bankrupt && (
        <div className="note r mt-[18px]">
          <TriangleAlert className="ic" />
          <div>
            <b>{t('bankrupt.title')}</b><br />
            {t('bankrupt.detail', { times: user.bankruptCount, date: user.bankruptResetDate ?? t('bankrupt.nextDay') })}
          </div>
        </div>
      )}

      {/* ====== 总资产 / 钱包明细 ｜ 今日、分布、收益、能力 ====== */}
      <section className="ov">
        <div className="ov-l">
          <div className="cap">{t('ov.totalAssets')}</div>
          <b className="big total nf"><BigNum value={user.totalAssets} /></b>
          <div className={cn('pnl', upDn(user.profit))}>
            <span>{fmtSignedUsd(user.profit)}</span>
            <span>{fmtSignedPct(user.profitPct)}</span>
            <small>{t('ov.since', { amount: `₮${fmtNum(startCapital)}` })}</small>
          </div>
          <div className="wal">
            <div><span>{t('ov.balanceWallet')}</span><b>{fmtNum(walletBalance)}</b></div>
            <div><span>{t('ov.gameWallet')}</span><b>{fmtNum(user.gameBalance)}</b></div>
            <div><span>{t('ov.futuresMargin')}</span><b>{fmtNum(futuresMargin)}</b></div>
            <div>
              <span>{t('ov.futuresUnrealized')}</span>
              <b className={futuresPositions.length ? upDn(futuresProfit) : 'mute'}>{futuresPositions.length ? fmtSigned(futuresProfit) : fmtNum(0)}</b>
            </div>
            <div><span>{t('ov.marginLoan')}</span><b className={user.marginLoanPrincipal > 0 ? 'wn' : 'mute'}>{fmtNum(user.marginLoanPrincipal)}</b></div>
            <div><span>{t('ov.interestAccrued')}</span><b className={user.marginInterestAccrued > 0 ? 'dn' : 'mute'}>{fmtNum(user.marginInterestAccrued)}</b></div>
          </div>
          <div className="acts">
            <button type="button" className="btn ink" onClick={() => setTransferOpen(true)}><ArrowLeftRight className="ic" />{t('ov.transfer')}</button>
          </div>
        </div>

        <div className="ov-r">
          <div className="ov-h">
            <b>{panelTitle}</b>
            <Seg value={panel} onChange={setPanel} label={panelTitle} options={[
              { value: 'day', label: t('ov.today') },
              { value: 'dist', label: t('ov.dist') },
              { value: 'profit', label: t('ov.profit') },
              { value: 'ability', label: t('ov.ability') },
            ]} />
          </div>

          <div className="pv">
            {panel === 'day' && (
              <>
                <div className="dayrow">
                  <b className={cn('big day nf', rt == null ? 'mute' : upDn(dailyProfit))}><BigNum value={dailyProfit} signed /></b>
                  <span className={rt == null ? 'mute' : upDn(dailyProfit)}>{fmtSignedPct(rt?.dailyProfitPct ?? 0)}</span>
                </div>
                <div className="cats">
                  {cats.map(c => (
                    <div key={c.k}><span>{c.k}</span><b className={c.v === 0 ? 'mute' : upDn(c.v)}>{fmtSigned(c.v)}</b></div>
                  ))}
                </div>
                <div className="al-h">{t('alloc.title')}</div>
                <div className="al-bar">
                  {alloc.filter(a => a.v > 0).map(a => <i key={a.k} style={{ '--c': a.c, width: `${a.pct}%` } as CSSProperties} />)}
                </div>
                <div className="al-leg">
                  {alloc.map(a => (
                    <div key={a.k}>
                      <span style={{ '--c': a.v < 0 ? 'var(--color-loss)' : a.c } as CSSProperties}>{a.k}</span>
                      <b className={cn(a.v < 0 && 'dn')}>{fmtNum(a.v)}<small>{a.pct.toFixed(1)}%</small></b>
                    </div>
                  ))}
                </div>
              </>
            )}
            {panel === 'dist' && (
              <PortfolioChart
                cryptoPositions={cryptoRows}
                bstockRows={bstockRows}
                futuresRows={futuresChartRows}
                balance={walletBalance}
                gameBalance={user.gameBalance}
              />
            )}
            {panel === 'profit' && (profitLoaded ? <ProfitChart data={profitData} /> : <Skeleton />)}
            {panel === 'ability' && (categoryAverages ? <RadarChart userData={categoryAverages} /> : <Skeleton />)}
          </div>
        </div>
      </section>

      {/* ====== 合约持仓：和交易页同一张卡 ====== */}
      <FuturesPositionsCard
        refreshKey={refreshNonce}
        showCloseAll
        onOrdersChanged={() => setRefreshNonce(n => n + 1)}
      />

      {/* ====== 现货持仓：左币种右股票 ====== */}
      <section className="sec">
        {loading ? (
          <div className="spot-sk">
            {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} />)}
          </div>
        ) : !hasCrypto && !hasBstock ? (
          <EmptyState icon={<Inbox />} text={t('holdings.empty')}>
            <Link className="btn" to="/coin">{t('holdings.goMarkets')}</Link>
          </EmptyState>
        ) : (
          <div className="spot">
            {hasCrypto && (
              <div>
                <div className="sec-h">
                  <h2>{t('spot.cryptoTitle')}<small>{t('spot.cryptoCount', { count: cryptoRows.length })}</small></h2>
                </div>
                <div className="tw">
                  <table className="tbl hov min-w-[540px]">
                    <thead>
                      <tr>
                        <th>{t('spot.colCoin')}</th>
                        <th className="r">{t('spot.colQty')}</th>
                        <th className="r">{t('spot.colAvg')}</th>
                        <th className="r">{t('spot.colLast')}</th>
                        <th className="r">{t('spot.colValue')}</th>
                        <th className="r">{t('spot.colPnl')}</th>
                        <th className="chev" />
                      </tr>
                    </thead>
                    <tbody>
                      {cryptoRows.map(c => {
                        const coin = getCoin(c.symbol);
                        return (
                          <tr key={c.id} onClick={() => navigate(`/coin/${c.symbol}`)}>
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
                            <td className={cn('r pn', upDn(c.profit))}>
                              {fmtSigned(c.profit)}
                              <span className={cn('sub', upDn(c.profit))}>{fmtSignedPct(c.profitPct)}</span>
                            </td>
                            <td className="chev"><ChevronRight className="ic" /></td>
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
                <div className="tw">
                  <table className="tbl hov min-w-[540px]">
                    <thead>
                      <tr>
                        <th>{t('spot.colStock')}</th>
                        <th className="r">{t('spot.colShares')}</th>
                        <th className="r">{t('spot.colAvg')}</th>
                        <th className="r">{t('spot.colLast')}</th>
                        <th className="r">{t('spot.colValue')}</th>
                        <th className="r">{t('spot.colPnl')}</th>
                        <th className="chev" />
                      </tr>
                    </thead>
                    <tbody>
                      {bstockRows.map(b => (
                        <tr key={b.id} onClick={() => navigate(`/bstock/${b.symbol}`)}>
                          <td>
                            <span className="sym">{b.ticker}</span>
                            <span className="sub">{b.name}</span>
                          </td>
                          <td className="r">{b.heldQty}</td>
                          <td className="r">{fmtNum(b.avgCost)}</td>
                          <td className="r">{fmtNum(b.currentPrice)}</td>
                          <td className="r">{fmtNum(b.marketValue)}</td>
                          <td className={cn('r pn', upDn(b.profit))}>
                            {fmtSigned(b.profit)}
                            <span className={cn('sub', upDn(b.profit))}>{fmtSignedPct(b.profitPct)}</span>
                          </td>
                          <td className="chev"><ChevronRight className="ic" /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        )}
      </section>

      {/* ====== BTC 涨跌预测：整条能点 ====== */}
      {hasPrediction && predictionPnl && (
        <section className="sec">
          <div className="sec-h">
            <h2>{t('prediction.title')}<small>{t('prediction.wallet')}</small></h2>
          </div>
          <Link className="pred" to="/prediction">
            <div><span>{t('prediction.realized')}</span><b className={upDn(predictionPnl.realizedPnl)}>{fmtSignedUsd(predictionPnl.realizedPnl)}</b></div>
            <div><span>{t('prediction.totalBets')}</span><b>{t('prediction.bets', { count: predictionPnl.totalBets })}</b></div>
            <div>
              <span>{t('prediction.winRate')}</span>
              <b>{Math.round(predictionPnl.winRate)}%<small>{t('prediction.record', { won: predictionPnl.wonBets, lost: predictionPnl.lostBets })}</small></b>
            </div>
            <div>
              <span>{t('prediction.active')}</span>
              <b>
                {t('prediction.bets', { count: predictionPnl.activeBets })}
                <small>{t('prediction.activeHint', { cost: fmtNum(predictionPnl.activeCost), value: fmtNum(predictionPnl.activeValue) })}</small>
              </b>
            </div>
            <ChevronRight className="ic" />
          </Link>
        </section>
      )}

      <ProfilePublicToggle />

      {/* ====== 重置账户 ====== */}
      <section className="sec">
        <div className="sec-h"><h2>{t('reset.title')}</h2></div>
        <div className="line">
          <p>{t('reset.desc')}</p>
          <button type="button" className="btn risk" onClick={() => setResetOpen(true)}>{t('reset.action')}</button>
        </div>
      </section>

      {/* 重置后整页重拉：refreshNonce 一变就重走 portfolio（顺带回写 user store）+ 持仓，
          收益面板的缓存也一并作废，否则图上还挂着重置前的曲线 */}
      <ResetAccountDialog
        open={resetOpen}
        onClose={() => setResetOpen(false)}
        onDone={() => { setRefreshNonce(n => n + 1); setProfitLoaded(false); }}
      />
      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} />
    </div>
  );
}
