import { fmtNum, fmtDateTime, fmtTime, toCents } from '../lib/utils';
import { HelpTip } from '../components/HelpTip';
import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { predictionApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { usePredictionMarket, WINDOW_SECONDS } from '../hooks/usePredictionMarket';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { PredictionHero } from '../components/PredictionHero';
import { TrendingUp, TrendingDown, Loader2, Wallet, ArrowLeftRight, Bot } from 'lucide-react';
import type { PredictionRound, PredictionBet, PageResult } from '../types';

/** Polymarket 吃单费：份数 × 0.07 × p × (1 − p)，与后端 PredictionFee 同一公式 */
const FEE_RATE = 0.07;
const feeOf = (sharesN: number, p: number) => sharesN * FEE_RATE * p * (1 - p);
/** 按钮上的价；没有报价显示 -- */
const cents = (p: number | null) => (p == null ? '--' : `${toCents(p)}¢`);

export function Prediction() {
  const { t } = useTranslation(['community', 'common']);
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();

  const [amount, setAmount] = useState('');
  const [shares, setShares] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [tradeTab, setTradeTab] = useState<'buy' | 'sell'>('buy');
  const [side, setSide] = useState<'UP' | 'DOWN'>('UP');
  const [tab, setTab] = useState<'bets' | 'rounds'>('bets');
  const [bets, setBets] = useState<PredictionBet[]>([]);
  const [betsPage, setBetsPage] = useState(1);
  const [betsTotalPages, setBetsTotalPages] = useState(1);
  const [rounds, setRounds] = useState<PredictionRound[]>([]);
  const [roundsPage, setRoundsPage] = useState(1);
  const [roundsTotalPages, setRoundsTotalPages] = useState(1);
  const [transferOpen, setTransferOpen] = useState(false);

  const fetchBets = useCallback(async (page = betsPage) => {
    try {
      const res = await predictionApi.bets(page, 10) as unknown as PageResult<PredictionBet>;
      setBets(res.records);
      setBetsTotalPages(res.pages);
    } catch { /* ignore */ }
  }, [betsPage]);

  const fetchRounds = useCallback(async (page = roundsPage) => {
    try {
      const res = await predictionApi.rounds(page, 10) as unknown as PageResult<PredictionRound>;
      setRounds(res.records);
      setRoundsTotalPages(res.pages);
    } catch { /* ignore */ }
  }, [roundsPage]);

  // 行情、倒计时、时钟校准都在 hook 里；旧回合结算推送到了刷注单和余额
  const market = usePredictionMarket(() => { fetchBets(); fetchUser(); });
  const { round, fetchRound, upBid, upAsk, downBid, downAsk, activities } = market;

  useEffect(() => { fetchBets(); }, [fetchBets]);

  // UP / DOWN 是盘口方向代号，只有露给人看的地方换成"看涨 / 看跌"
  const sideLabel = side === 'UP' ? t('prediction.up') : t('prediction.down');

  const handleBuy = async () => {
    const amt = parseFloat(amount);
    if (!amt || amt <= 0) { toast(t('prediction.toast.enterAmount'), 'error'); return; }
    setSubmitting(true);
    try {
      await predictionApi.buy({ side, amount: amt });
      toast(t('prediction.toast.buyOk', { side: sideLabel }), 'success');
      setAmount('');
      fetchBets();
      fetchUser();
      fetchRound();
    } catch (e: unknown) {
      toast((e as Error).message || t('prediction.toast.buyFailed'), 'error');
    } finally { setSubmitting(false); }
  };

  const handleSell = async (betId: number) => {
    try {
      await predictionApi.sell(betId);
      toast(t('prediction.toast.sold'), 'success');
      fetchBets();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || t('prediction.toast.sellFailed'), 'error');
    }
  };

  const handleSellSide = async () => {
    const target = parseFloat(shares) || 0;
    if (target <= 0) { toast(t('prediction.toast.enterShares'), 'error'); return; }
    const activeBets = bets.filter(b => b.status === 'ACTIVE' && b.side === side);
    if (activeBets.length === 0) { toast(t('prediction.toast.noPosition'), 'error'); return; }
    const available = activeBets.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
    if (target > available + 1e-8) {
      toast(t('prediction.toast.maxSellable', { qty: available.toFixed(2) }), 'error');
      return;
    }
    setSubmitting(true);
    try {
      let remaining = target;
      for (const bet of activeBets) {
        if (remaining <= 0) break;
        const betContracts = parseFloat(String(bet.contracts ?? 0));
        const sellContracts = Math.min(remaining, betContracts);
        if (sellContracts <= 0) continue;
        await predictionApi.sell(bet.id, sellContracts);
        remaining -= sellContracts;
      }
      toast(t('prediction.toast.sellOk', { side: sideLabel }), 'success');
      setShares('');
      fetchBets();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || t('prediction.toast.sellFailed'), 'error');
    } finally { setSubmitting(false); }
  };

  const askPrice = side === 'UP' ? upAsk : downAsk;
  const buyAmt = parseFloat(amount) || 0;
  const toWin = askPrice && buyAmt > 0 ? buyAmt / askPrice : 0;
  // 买入费在金额之外另扣；全部按钮按同一公式留足费
  const buyFee = askPrice ? feeOf(toWin, askPrice) : 0;
  const maxBuyAmount = () => {
    const bal = user?.gameBalance ? parseFloat(String(user.gameBalance)) : 0;
    const feePerCost = askPrice ? FEE_RATE * (1 - askPrice) : FEE_RATE;
    return String(Math.floor(bal / (1 + feePerCost) * 100) / 100);
  };

  const activeBetsForSide = bets.filter(b => b.status === 'ACTIVE' && b.side === side);
  const totalShares = activeBetsForSide.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
  const bidPrice = side === 'UP' ? upBid : downBid;
  const sellSharesNum = parseFloat(shares) || 0;
  // 到手 = 份数 × 卖价 − 吃单费，与后端 sell 同口径
  const youllReceive = bidPrice && sellSharesNum > 0 ? sellSharesNum * bidPrice - feeOf(sellSharesNum, bidPrice) : 0;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">

      <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-600 dark:text-amber-400 text-xs font-bold">
        <span className="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse" />
        {t('prediction.beta')}
      </div>

      {/* ── Hero: 标题 + 倒计时 + 价格 + 图表；右上角进 Jev 页 ── */}
      <PredictionHero market={market} extra={
        <Link to="/jev" className="hidden sm:inline-flex items-center gap-1 text-[11px] font-semibold text-muted-foreground hover:text-primary transition-colors">
          <Bot className="w-3.5 h-3.5" />{t('prediction.jev.jevView')}
        </Link>
      } />

      {/* ── 交易面板 + Live Trades ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* 交易面板 */}
        <div className="lg:col-span-2">
          <Card>
            <CardContent className="p-5">
              {/* 买入 / 卖出 Tab */}
              <div className="flex mb-5 bg-muted rounded-lg p-0.5">
                <button onClick={() => { setTradeTab('buy'); setShares(''); }}
                        className={`flex-1 text-sm font-bold py-2 rounded-md transition-all ${tradeTab === 'buy' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  {t('prediction.buy')}
                </button>
                <button onClick={() => { setTradeTab('sell'); setAmount(''); }}
                        className={`flex-1 text-sm font-bold py-2 rounded-md transition-all ${tradeTab === 'sell' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  {t('prediction.sell')}
                </button>
              </div>

              {/* 看涨 / 看跌 */}
              <div className="grid grid-cols-2 gap-3 mb-5">
                <button onClick={() => setSide('UP')}
                        className={`relative rounded-xl border p-4 text-center transition-all ${side === 'UP' ? 'border-green-500 bg-green-500/10 shadow-[0_0_12px_-3px_rgba(34,197,94,0.3)]' : 'border-border hover:border-green-500/40'}`}>
                  <TrendingUp className="mx-auto w-5 h-5 text-green-500 mb-1.5" />
                  <div className="text-[10px] text-muted-foreground tracking-wider mb-0.5 flex items-center justify-center gap-0.5">{t('prediction.up')} <HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.upTip')} /></div>
                  <div className="text-2xl font-black text-green-500 tabular-nums">
                    {tradeTab === 'buy' ? cents(upAsk) : cents(upBid)}
                  </div>
                </button>
                <button onClick={() => setSide('DOWN')}
                        className={`relative rounded-xl border p-4 text-center transition-all ${side === 'DOWN' ? 'border-red-500 bg-red-500/10 shadow-[0_0_12px_-3px_rgba(239,68,68,0.3)]' : 'border-border hover:border-red-500/40'}`}>
                  <TrendingDown className="mx-auto w-5 h-5 text-red-500 mb-1.5" />
                  <div className="text-[10px] text-muted-foreground tracking-wider mb-0.5 flex items-center justify-center gap-0.5">{t('prediction.down')} <HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.downTip')} /></div>
                  <div className="text-2xl font-black text-red-500 tabular-nums">
                    {tradeTab === 'buy' ? cents(downAsk) : cents(downBid)}
                  </div>
                </button>
              </div>

              {tradeTab === 'buy' ? (
                <>
                  <div className="flex items-center justify-between mb-2.5">
                    {/* 预测下注走游戏钱包，不是交易余额 */}
                    <span className="text-xs text-muted-foreground flex items-center gap-1">
                      <Wallet className="w-3 h-3" />
                      {t('prediction.gameWallet')} {user ? `${fmtNum(user.gameBalance)} USDT` : '--'}
                      <Button size="sm" variant="outline" onClick={() => setTransferOpen(true)} className="text-[10px] h-6 px-2 ml-1">
                        <ArrowLeftRight className="w-3 h-3" />{t('prediction.transfer')}
                      </Button>
                    </span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">{t('prediction.amount')}</span>
                      <Input type="number" placeholder="0.00" value={amount}
                             onChange={e => setAmount(e.target.value)}
                             className="w-28 h-8 text-sm text-right font-mono tabular-nums" />
                    </div>
                  </div>
                  <div className="flex gap-1.5 mb-4">
                    {[1, 5, 10, 100].map(v => (
                      <Button key={v} variant="outline" size="sm"
                              className="flex-1 h-9 sm:h-7 text-xs font-semibold"
                              onClick={() => setAmount(prev => String((parseFloat(prev) || 0) + v))}>
                        +${v}
                      </Button>
                    ))}
                    <Button variant="outline" size="sm" className="flex-1 h-9 sm:h-7 text-xs font-semibold"
                            onClick={() => setAmount(maxBuyAmount())}>
                      {t('prediction.max')}
                    </Button>
                  </div>
                  <div className="py-2.5 px-3 mb-4 rounded-lg bg-muted/50 space-y-1">
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-muted-foreground flex items-center gap-1">{t('prediction.estPayout')} <HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.estPayoutTip')} /></span>
                      <span className="text-sm font-bold font-mono tabular-nums">${toWin > 0 ? toWin.toFixed(2) : '--'}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-[11px] text-muted-foreground flex items-center gap-1">{t('prediction.fee')} <HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.feeTip')} /></span>
                      <span className="text-[11px] font-mono tabular-nums text-muted-foreground">${buyFee > 0 ? buyFee.toFixed(2) : '--'}</span>
                    </div>
                  </div>
                  <Button onClick={handleBuy} disabled={submitting || !user}
                          className={`w-full h-11 font-bold text-sm ${side === 'UP' ? 'bg-green-600 hover:bg-green-700' : 'bg-red-600 hover:bg-red-700'} text-white`}>
                    {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : t('prediction.buySide', { side: sideLabel })}
                  </Button>
                  {!user && <p className="text-[11px] text-muted-foreground mt-2 text-center">{t('prediction.loginToTrade')}</p>}
                </>
              ) : (
                <>
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">{t('prediction.myShares')} <HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.mySharesTip')} />: <span className="font-bold text-foreground">{totalShares.toFixed(2)}</span></span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">{t('prediction.shares')}</span>
                      <Input type="number" placeholder="0" value={shares}
                             onChange={e => setShares(e.target.value)}
                             className="w-28 h-8 text-sm text-right font-mono tabular-nums" />
                    </div>
                  </div>
                  <div className="flex gap-1.5 mb-4">
                    {[0.25, 0.5].map(pct => (
                      <Button key={pct} variant="outline" size="sm"
                              className="flex-1 h-9 sm:h-7 text-xs font-semibold"
                              onClick={() => setShares(String(Math.floor(totalShares * pct * 100) / 100))}>
                        {pct * 100}%
                      </Button>
                    ))}
                    <Button variant="outline" size="sm" className="flex-1 h-9 sm:h-7 text-xs font-semibold"
                            onClick={() => setShares(String(totalShares))}>
                      {t('prediction.max')}
                    </Button>
                  </div>
                  <div className="flex items-center justify-between py-2.5 px-3 mb-4 rounded-lg bg-muted/50">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">{t('prediction.estProceeds')} <HelpTip side="top" iconClassName="w-3 h-3" text={t('prediction.estProceedsTip')} /></span>
                    <span className="text-sm font-bold font-mono tabular-nums">${youllReceive > 0 ? youllReceive.toFixed(2) : '--'}</span>
                  </div>
                  <Button onClick={handleSellSide} disabled={submitting || activeBetsForSide.length === 0}
                          className={`w-full h-11 font-bold text-sm ${side === 'UP' ? 'bg-green-600 hover:bg-green-700' : 'bg-red-600 hover:bg-red-700'} text-white`}>
                    {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : t('prediction.sellSide', { side: sideLabel })}
                  </Button>
                  {activeBetsForSide.length === 0 && <p className="text-[11px] text-muted-foreground mt-2 text-center">{t('prediction.noSidePosition', { side: sideLabel })}</p>}
                </>
              )}
            </CardContent>
          </Card>
        </div>

        {/* Live Trades */}
        <Card className="flex flex-col min-h-[200px]">
          <div className="px-4 pt-4 pb-2">
            <span className="text-[10px] text-muted-foreground font-semibold tracking-widest uppercase">{t('prediction.liveTrades')}</span>
          </div>
          <CardContent className="flex-1 overflow-hidden px-1.5 pb-1.5 relative">
            {activities.length === 0 && <p className="text-xs text-muted-foreground text-center py-8">{t('prediction.waitingTrades')}</p>}
            <div className="absolute bottom-0 left-0 right-0 px-1.5 flex flex-col-reverse gap-px overflow-hidden" style={{ maxHeight: '100%' }}>
              {activities.map((a, i) => {
                const up = a.outcome === 'Up' || a.side === 'UP';
                const amt = a.amount != null ? Math.round(a.amount) : 0;
                const isLocal = a.source === 'local';
                return (
                  <div key={`${a.ts}-${i}`}
                       className="flex items-center justify-between py-1.5 px-2 rounded pred-feed-in">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-black shrink-0 ${up ? 'bg-green-500/15 text-green-500' : 'bg-red-500/15 text-red-500'}`}>
                        {up ? '↑' : '↓'}
                      </span>
                      <span className="text-[11px] text-muted-foreground truncate">
                        {isLocal ? (a.username || 'User') : 'Polymarket'}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5 shrink-0">
                      <span className={`text-xs font-mono font-bold tabular-nums ${up ? 'text-green-500' : 'text-red-500'}`}>
                        ${amt}
                      </span>
                      <span className={`text-[9px] font-bold px-1 py-0.5 rounded ${up ? 'bg-green-500/12 text-green-500' : 'bg-red-500/12 text-red-500'}`}>
                        {up ? 'UP' : 'DN'}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ── 底部：我的下注 / 往期回合 ── */}
      <Card>
        <CardContent className="p-0">
          <div className="flex border-b">
            <button onClick={() => { setTab('bets'); setBetsPage(1); fetchBets(1); }}
                    className={`px-5 py-3 text-sm font-medium transition-colors ${tab === 'bets' ? 'border-b-2 border-primary text-primary' : 'text-muted-foreground hover:text-foreground'}`}>
              {t('prediction.tabBets')}
            </button>
            <button onClick={() => { setTab('rounds'); setRoundsPage(1); fetchRounds(1); }}
                    className={`px-5 py-3 text-sm font-medium transition-colors ${tab === 'rounds' ? 'border-b-2 border-primary text-primary' : 'text-muted-foreground hover:text-foreground'}`}>
              {t('prediction.tabRounds')}
            </button>
          </div>

          <div className="p-4">
            {tab === 'bets' && (
              <div className="space-y-1.5">
                {bets.length === 0 && <p className="text-xs text-muted-foreground text-center py-6">{t('prediction.noBets')}</p>}
                {bets.map(b => {
                  const ws = b.windowStart;
                  const timeRange = ws ? (() => {
                    return `${fmtTime(ws * 1000)} - ${fmtTime((ws + WINDOW_SECONDS) * 1000)}`;
                  })() : '';
                  return (
                  // flex-wrap：手机上时间段+份数+状态一行放不下时折行，不再横向溢出
                  <div key={b.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs py-2.5 px-3 rounded-lg hover:bg-muted/40 transition-colors">
                    {timeRange && <span className="text-muted-foreground font-mono tabular-nums text-[11px] shrink-0">{timeRange}</span>}
                    <span className={`w-10 text-center text-[10px] font-bold py-0.5 rounded ${b.side === 'UP' ? 'bg-green-500/12 text-green-500' : 'bg-red-500/12 text-red-500'}`}>
                      {b.side}
                    </span>
                    <span className="font-mono tabular-nums font-semibold">{b.contracts}</span>
                    <span className="text-muted-foreground">@ {b.avgPrice}</span>
                    <span className="text-muted-foreground font-mono tabular-nums">${b.cost.toFixed(2)}</span>
                    {b.currentValue != null && <span className="text-muted-foreground font-mono tabular-nums">~${b.currentValue.toFixed(2)}</span>}
                    <span className="ml-auto">
                      <Badge variant={b.status === 'LOST' ? 'destructive' : 'outline'}
                             className={`text-[10px] px-1.5 py-0 ${b.status === 'WON' ? 'bg-green-500/15 text-green-500 border-green-500/50' : ''}`}>
                        {b.status}
                      </Badge>
                    </span>
                    {b.payout != null && b.status !== 'ACTIVE' && (
                      <span className={`font-mono tabular-nums font-semibold ${b.payout > b.cost ? 'text-green-500' : b.payout === 0 ? 'text-red-500' : 'text-muted-foreground'}`}>
                        {b.payout > 0 ? `+$${b.payout.toFixed(2)}` : '$0'}
                      </span>
                    )}
                    {b.status === 'ACTIVE' && round?.status === 'OPEN' && b.windowStart === round.windowStart && (
                      <Button size="sm" variant="outline" onClick={() => handleSell(b.id)} className="text-[10px] h-6 px-2">{t('prediction.sell')}</Button>
                    )}
                  </div>
                  );
                })}
                {betsTotalPages > 1 && (
                  <div className="flex items-center justify-center gap-3 pt-3">
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={betsPage <= 1}
                            onClick={() => { const p = betsPage - 1; setBetsPage(p); fetchBets(p); }}>{t('prediction.prevPage')}</Button>
                    <span className="text-xs text-muted-foreground">{betsPage} / {betsTotalPages}</span>
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={betsPage >= betsTotalPages}
                            onClick={() => { const p = betsPage + 1; setBetsPage(p); fetchBets(p); }}>{t('prediction.nextPage')}</Button>
                  </div>
                )}
              </div>
            )}

            {tab === 'rounds' && (
              <div className="space-y-1.5">
                {rounds.length === 0 && <p className="text-xs text-muted-foreground text-center py-6">{t('prediction.noRounds')}</p>}
                {rounds.map(r => (
                  <div key={r.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs py-2.5 px-3 rounded-lg hover:bg-muted/40 transition-colors">
                    <span className="text-muted-foreground font-mono tabular-nums text-[11px]">
                      {r.windowStart ? fmtDateTime(r.windowStart * 1000) : '--'}
                    </span>
                    <span className="text-muted-foreground">&rarr;</span>
                    <span className="font-mono tabular-nums">${fmtNum(r.startPrice)}</span>
                    <span className="text-muted-foreground">&rarr;</span>
                    <span className="font-mono tabular-nums">${fmtNum(r.endPrice)}</span>
                    <span className="ml-auto">
                      <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${r.outcome === 'UP' ? 'bg-green-500/12 text-green-500' : r.outcome === 'DOWN' ? 'bg-red-500/12 text-red-500' : 'bg-muted text-muted-foreground'}`}>
                        {r.outcome === 'VOID' ? t('prediction.void') : (r.outcome || '--')}
                      </span>
                    </span>
                  </div>
                ))}
                {roundsTotalPages > 1 && (
                  <div className="flex items-center justify-center gap-3 pt-3">
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={roundsPage <= 1}
                            onClick={() => { const p = roundsPage - 1; setRoundsPage(p); fetchRounds(p); }}>{t('prediction.prevPage')}</Button>
                    <span className="text-xs text-muted-foreground">{roundsPage} / {roundsTotalPages}</span>
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={roundsPage >= roundsTotalPages}
                            onClick={() => { const p = roundsPage + 1; setRoundsPage(p); fetchRounds(p); }}>{t('prediction.nextPage')}</Button>
                  </div>
                )}
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} />
    </div>
  );
}
