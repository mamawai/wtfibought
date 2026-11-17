import { useState, useEffect, useCallback, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowUpRight } from 'lucide-react';
import { cn, fmtDateTime, fmtNum, fmtTime, toCents } from '../lib/utils';
import { predictionApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { usePredictionMarket, WINDOW_SECONDS, fmtWindow } from '../hooks/usePredictionMarket';
import { useToast } from '../components/ui/use-toast';
import { Seg } from '../components/ui/seg';
import { Tabs } from '../components/ui/tabs';
import { Pager } from '../components/ui/pager';
import { NumInput } from '../components/coin/TradeFields';
import { HelpTip } from '../components/HelpTip';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { PredictionHero } from '../components/PredictionHero';
import type { PredictionRound, PredictionBet, PageResult } from '../types';
import './Prediction.css';

/** Polymarket 吃单费：份数 × 0.07 × p × (1 − p)，与后端 PredictionFee 同一公式 */
const FEE_RATE = 0.07;
const feeOf = (sharesN: number, p: number) => sharesN * FEE_RATE * p * (1 - p);
/** 概率价换成美分；没有报价显示 -- */
const cents = (p: number | null) => (p == null ? '--' : `${toCents(p)}¢`);
/** 快捷填数的几档，1 = 全部 */
const PCTS = [0.1, 0.25, 0.5, 1];
/** 概率条下面那排小点画几回合 */
const RECENT = 18;
/** 回合结果点的颜色 */
const OUTCOME_COLOR: Record<string, string> = { UP: 'var(--color-gain)', DOWN: 'var(--color-loss)', VOID: 'var(--color-faint)' };

type Side = 'UP' | 'DOWN';

export function Prediction() {
  const { t } = useTranslation(['community', 'trade', 'common']);
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();

  const [amount, setAmount] = useState('');
  const [shares, setShares] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [tradeTab, setTradeTab] = useState<'buy' | 'sell'>('buy');
  const [side, setSide] = useState<Side>('UP');
  const [tab, setTab] = useState<'bets' | 'rounds'>('bets');
  const [bets, setBets] = useState<PredictionBet[]>([]);
  const [betsPage, setBetsPage] = useState(1);
  const [betsTotalPages, setBetsTotalPages] = useState(1);
  const [rounds, setRounds] = useState<PredictionRound[]>([]);
  const [roundsPage, setRoundsPage] = useState(1);
  const [roundsTotalPages, setRoundsTotalPages] = useState(1);
  // 最近几回合的结果，新的在前
  const [recent, setRecent] = useState<PredictionRound[]>([]);
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

  const fetchRecent = useCallback(async () => {
    try {
      const res = await predictionApi.rounds(1, RECENT) as unknown as PageResult<PredictionRound>;
      setRecent(res.records.filter(r => r.outcome));
    } catch { /* ignore */ }
  }, []);

  // 行情、倒计时、时钟校准都在 hook 里；旧回合结算推送到了刷注单、余额和最近回合
  const market = usePredictionMarket(() => { fetchBets(); fetchUser(); fetchRecent(); });
  const { round, fetchRound, upBid, upAsk, downBid, downAsk, activities } = market;

  useEffect(() => { fetchBets(); }, [fetchBets]);
  useEffect(() => { fetchRecent(); }, [fetchRecent]);

  const locked = round?.status === 'LOCKED';
  // 当前回合还没结算的注单
  const inRound = (b: PredictionBet) => b.status === 'ACTIVE' && b.windowStart === round?.windowStart;
  // 只有当前 OPEN 回合的注单卖得掉（跟后端同口径），锁定待结算的旧回合注单不算
  const canSell = (b: PredictionBet) => inRound(b) && round?.status === 'OPEN';

  // UP / DOWN 是盘口方向代号，只有露给人看的地方换成"看涨 / 看跌"
  const sideName = (s: Side) => (s === 'UP' ? t('prediction.up') : t('prediction.down'));
  const sideLabel = sideName(side);

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
    const activeBets = bets.filter(b => canSell(b) && b.side === side);
    if (activeBets.length === 0) { toast(t('prediction.toast.noPosition'), 'error'); return; }
    const available = activeBets.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
    if (target > available + 1e-8) {
      toast(t('prediction.toast.maxSellable', { qty: available.toFixed(2) }), 'error');
      return;
    }
    setSubmitting(true);
    let remaining = target;
    try {
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
    } catch (e: unknown) {
      const reason = (e as Error).message || t('prediction.toast.sellFailed');
      // 前面几笔已成交：报卖掉多少，输入框改成没卖掉的那些；份数后端是 4 位小数，这里取整去掉浮点尾巴
      const sold = Math.round((target - remaining) * 1e4) / 1e4;
      if (sold > 0) {
        const left = Math.round(remaining * 1e4) / 1e4;
        toast(t('prediction.toast.partialSold', { qty: sold, reason }), 'error');
        setShares(left > 0 ? String(left) : '');
      } else {
        toast(reason, 'error');
      }
    } finally {
      setSubmitting(false);
      // 逐笔卖，中途失败时前面几笔已经成交了，成败都要刷
      fetchBets();
      fetchUser();
    }
  };

  const buying = tradeTab === 'buy';
  const askPrice = side === 'UP' ? upAsk : downAsk;
  const buyAmt = parseFloat(amount) || 0;
  const toWin = askPrice && buyAmt > 0 ? buyAmt / askPrice : 0;
  // 买入费在金额之外另扣；快捷档按同一公式留足费
  const buyFee = askPrice ? feeOf(toWin, askPrice) : 0;
  const buyAmountAt = (pct: number) => {
    const bal = user?.gameBalance ? parseFloat(String(user.gameBalance)) : 0;
    const feePerCost = askPrice ? FEE_RATE * (1 - askPrice) : FEE_RATE;
    return String(Math.floor(bal * pct / (1 + feePerCost) * 100) / 100);
  };

  const activeBetsForSide = bets.filter(b => canSell(b) && b.side === side);
  const totalShares = activeBetsForSide.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
  const bidPrice = side === 'UP' ? upBid : downBid;
  const sellSharesNum = parseFloat(shares) || 0;
  const sellFee = bidPrice && sellSharesNum > 0 ? feeOf(sellSharesNum, bidPrice) : 0;
  // 到手 = 份数 × 卖价 − 吃单费，与后端 sell 同口径
  const youllReceive = bidPrice && sellSharesNum > 0 ? sellSharesNum * bidPrice - sellFee : 0;

  const pickPct = (pct: number) => {
    if (buying) setAmount(buyAmountAt(pct));
    else setShares(pct === 1 ? String(totalShares) : String(Math.floor(totalShares * pct * 100) / 100));
  };

  // 概率条：买看卖一价、卖看买一价；两半的宽度按两边价的占比分，没报价各占一半
  const upPx = buying ? upAsk : upBid;
  const downPx = buying ? downAsk : downBid;
  const upShare = upPx != null && downPx != null ? upPx / (upPx + downPx) : 0.5;
  const ctaPx = buying ? askPrice : bidPrice;

  return (
    <div className="page-shell p-pd">
      <div className="pg-h">
        <div>
          <h1>{t('prediction.title')}</h1>
          <p className="sub">{t('prediction.beta')}</p>
        </div>
      </div>

      <div className="lay">
        <div className="left">
          <PredictionHero market={market} />

          <div className="odds" role="group" aria-label={t('trade:pred.pickSide')}>
            <button type="button" className="od up0" aria-pressed={side === 'UP'} title={t('prediction.upTip')}
                    style={{ flexGrow: upShare }} onClick={() => setSide('UP')}>
              <span><ArrowUpRight className="ic" />{t('prediction.up')}</span>
              <b className="nf">{upPx == null ? '--' : <>{toCents(upPx)}<small>¢</small></>}</b>
            </button>
            <button type="button" className="od dn0" aria-pressed={side === 'DOWN'} title={t('prediction.downTip')}
                    style={{ flexGrow: 1 - upShare }} onClick={() => setSide('DOWN')}>
              <span>{t('prediction.down')}<ArrowDownRight className="ic" /></span>
              <b className="nf">{downPx == null ? '--' : <>{toCents(downPx)}<small>¢</small></>}</b>
            </button>
          </div>

          {/* 最近几回合的结果，从左到右由旧到新 */}
          {recent.length > 0 && (
            <div className="dots">
              <span>{t('trade:pred.recent', { count: recent.length })}</span>
              {[...recent].reverse().map(r => (
                <i key={r.windowStart} style={{ '--c': OUTCOME_COLOR[r.outcome!] } as CSSProperties}
                   title={`${fmtWindow(r.windowStart)} ${t(`trade:pred.outcome.${r.outcome}`)}`} />
              ))}
            </div>
          )}

          <section>
            <Tabs className="tabline" value={tab} items={[
              { value: 'bets', label: t('prediction.tabBets') },
              { value: 'rounds', label: t('prediction.tabRounds') },
            ]} onChange={v => {
              setTab(v);
              if (v === 'bets') { setBetsPage(1); fetchBets(1); } else { setRoundsPage(1); fetchRounds(1); }
            }} />

            {tab === 'bets' && (bets.length === 0 ? <div className="empty"><b>{t('prediction.noBets')}</b></div> : (
              <>
                {bets.map(b => {
                  const settled = b.status !== 'ACTIVE';
                  const payout = b.payout ?? 0;
                  return (
                    <div key={b.id} className="bet">
                      <span className={cn('tag sm', b.side === 'UP' ? 'g' : 'r')}>{sideName(b.side as Side)}</span>
                      <span className="nf">{t('trade:pred.sharesAt', { shares: fmtNum(b.contracts), price: toCents(b.avgPrice) })}</span>
                      <span className="rs nf">
                        {settled ? (
                          <>
                            <span className={payout > b.cost ? 'up' : payout === 0 ? 'dn' : 'mute'}>{payout > 0 ? `+₮${payout.toFixed(2)}` : '₮0'}</span>
                            <small className="mute">{t(`trade:pred.status.${b.status}`)}</small>
                          </>
                        ) : (
                          <>
                            <span className="tag sm">{t('trade:pred.status.ACTIVE')}</span>
                            {canSell(b) && (
                              <small><button type="button" className="linkb" onClick={() => handleSell(b.id)}>{t('prediction.sell')}</button></small>
                            )}
                          </>
                        )}
                      </span>
                      <small className="nf">
                        {t('trade:pred.betMeta', { window: b.windowStart != null ? fmtWindow(b.windowStart) : '--', cost: b.cost.toFixed(2) })}
                        {!settled && b.currentValue != null && ` · ~₮${b.currentValue.toFixed(2)}`}
                      </small>
                    </div>
                  );
                })}
                <Pager page={betsPage} pages={betsTotalPages} onChange={p => { setBetsPage(p); fetchBets(p); }} />
              </>
            ))}

            {tab === 'rounds' && (rounds.length === 0 ? <div className="empty"><b>{t('prediction.noRounds')}</b></div> : (
              <>
                {rounds.map(r => {
                  const start = r.startPrice != null ? parseFloat(r.startPrice) : null;
                  const close = r.endPrice != null ? parseFloat(r.endPrice) : null;
                  const move = start != null && close != null ? close - start : null;
                  return (
                    <div key={r.windowStart} className="bet">
                      <span className={cn('tag sm', r.outcome === 'UP' ? 'g' : r.outcome === 'DOWN' && 'r')}>
                        {r.outcome ? t(`trade:pred.outcome.${r.outcome}`) : '--'}
                      </span>
                      <span className="nf">{fmtDateTime(r.windowStart * 1000)}–{fmtTime((r.windowStart + WINDOW_SECONDS) * 1000)}</span>
                      <span className={cn('rs nf', move != null && (move >= 0 ? 'up' : 'dn'))}>
                        {move == null ? '--' : `${move >= 0 ? '+' : '-'}${fmtNum(Math.abs(move))}`}
                      </span>
                      <small className="nf">{t('trade:pred.roundMeta', { target: fmtNum(start), close: fmtNum(close) })}</small>
                    </div>
                  );
                })}
                <Pager page={roundsPage} pages={roundsTotalPages} onChange={p => { setRoundsPage(p); fetchRounds(p); }} />
              </>
            ))}
          </section>
        </div>

        <aside className="side">
          <div className="blk ord">
            {/* 预测下注走游戏钱包，不是交易余额 */}
            <div className="wl">
              <span>{t('prediction.gameWallet')} <b className="nf">{user ? `₮${fmtNum(user.gameBalance)}` : '--'}</b></span>
              <button type="button" className="btn sm" onClick={() => setTransferOpen(true)}>{t('prediction.transfer')}</button>
            </div>
            <Seg className="full" value={tradeTab} onChange={v => { setTradeTab(v); setAmount(''); setShares(''); }} options={[
              { value: 'buy', label: t('prediction.buy') },
              { value: 'sell', label: t('prediction.sell') },
            ]} />
            <div className="fld">
              <label className="fl" htmlFor="pd-in">
                <span>{buying ? t('prediction.amount') : t('prediction.shares')}</span>
                {!buying && <span className="nf">{t('trade:pred.holding', { qty: totalShares.toFixed(2) })}</span>}
              </label>
              <NumInput id="pd-in" value={buying ? amount : shares} onChange={buying ? setAmount : setShares}
                        placeholder={buying ? '0.00' : '0'} unit={buying ? 'USDT' : t('trade:pred.sharesUnit')} />
              <div className="pct">
                {PCTS.map(p => (
                  <button key={p} type="button" onClick={() => pickPct(p)}>{p === 1 ? t('prediction.max') : `${p * 100}%`}</button>
                ))}
              </div>
            </div>
            <div className="kv">
              {buying ? (
                <>
                  <div><span>{t('prediction.shares')}</span><b className="nf">{toWin > 0 ? `≈ ${toWin.toFixed(2)}` : '—'}</b></div>
                  <div>
                    <span>{t('prediction.estPayout')}<HelpTip text={t('prediction.estPayoutTip')} /></span>
                    <b className="nf">{toWin > 0 ? <>₮{toWin.toFixed(2)}<span className="up">+{((toWin / buyAmt - 1) * 100).toFixed(0)}%</span></> : '—'}</b>
                  </div>
                  <div><span>{t('prediction.fee')}<HelpTip text={t('prediction.feeTip')} /></span><b className="nf">{buyFee > 0 ? `₮${buyFee.toFixed(2)}` : '—'}</b></div>
                </>
              ) : (
                <>
                  <div><span>{t('prediction.fee')}</span><b className="nf">{sellFee > 0 ? `₮${sellFee.toFixed(2)}` : '—'}</b></div>
                  <div>
                    <span>{t('prediction.estProceeds')}<HelpTip text={t('prediction.estProceedsTip')} /></span>
                    <b className="nf">{youllReceive > 0 ? `₮${youllReceive.toFixed(2)}` : '—'}</b>
                  </div>
                </>
              )}
            </div>
            <button type="button" className={cn('cta', side === 'DOWN' && 'short', submitting && 'busy')}
                    disabled={!user || locked || (!buying && activeBetsForSide.length === 0)}
                    onClick={buying ? handleBuy : handleSellSide}>
              {!user ? t('prediction.loginToTrade')
                : locked ? t('prediction.locked')
                : `${t(buying ? 'prediction.buySide' : 'prediction.sellSide', { side: sideLabel })} · ${cents(ctaPx)}`}
            </button>
            {/* 这一回合两边各拿着多少份 */}
            <div className="mine">
              {(['UP', 'DOWN'] as const).map(s => {
                const held = bets.filter(b => inRound(b) && b.side === s);
                const qty = held.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
                const cost = held.reduce((sum, b) => sum + b.cost, 0);
                const bid = s === 'UP' ? upBid : downBid;
                return (
                  <div key={s}>
                    <span>{sideName(s)} · {t('prediction.myShares')}</span>
                    <b className="nf">{held.length ? qty.toFixed(2) : '—'}</b>
                    <small className="nf">
                      {held.length
                        ? t('trade:pred.costValue', { cost: fmtNum(cost), value: bid == null ? '--' : fmtNum(qty * bid) })
                        : t('prediction.noSidePosition', { side: sideName(s) })}
                    </small>
                  </div>
                );
              })}
            </div>
          </div>

          <section>
            <div className="sec-h"><h2>{t('prediction.liveTrades')}</h2></div>
            {activities.length === 0
              ? <p className="mute text-[13.5px]">{t('prediction.waitingTrades')}</p>
              : activities.slice(0, 9).map((a, i) => {
                const up = a.outcome === 'Up' || a.side === 'UP';
                return (
                  // 新来的一条排在最上面，滑进来
                  <div key={`${a.ts}-${i}`} className={cn('fd', i === 0 && 'new')}>
                    <span className={cn('tag sm', up ? 'g' : 'r')}>{up ? t('prediction.up') : t('prediction.down')}</span>
                    <span className="who">{a.source === 'local' ? a.username : 'Polymarket'}</span>
                    <span className="q nf">₮{fmtNum(a.amount, 0)}</span>
                  </div>
                );
              })}
          </section>
        </aside>
      </div>

      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} />
    </div>
  );
}
