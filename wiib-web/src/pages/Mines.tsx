import { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { minesApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { Wallet, TrendingUp, Pickaxe, ArrowLeftRight } from 'lucide-react';
import { cn, fmtNum } from '../lib/utils';
import i18n from '../i18n';
import type { MinesGameState, MinesStatus } from '../types';

const BET_PRESETS = [10, 50, 100, 500, 1000, 5000];

const fmtMult = (n: number) => n.toFixed(2);

export function Mines() {
  const { toast } = useToast();
  const { t } = useTranslation(['games', 'common']);
  const [status, setStatus] = useState<MinesStatus | null>(null);
  const [game, setGame] = useState<MinesGameState | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [betInput, setBetInput] = useState('100');
  const [showResult, setShowResult] = useState(false);
  const [transferOpen, setTransferOpen] = useState(false);
  // 记录已翻开过的格子（用于翻转动画，避免页面恢复时重播）
  const flippedRef = useRef<Set<number>>(new Set());

  // 下注额就是输入框里的数，清空或乱填算 0
  const betAmount = parseFloat(betInput) || 0;
  // 顶栏余额以 status 为准：每次牌局返回都同步进来，划转后 fetchStatus 也刷它
  const balance = status?.balance ?? 0;

  // 不依赖 t：切语言会换新 t，依赖了就会重拉
  const fetchStatus = useCallback(async () => {
    try {
      const s = await minesApi.status();
      setStatus(s);
      if (s.activeGame) setGame(s.activeGame);
    } catch (e: unknown) {
      toast((e as Error).message || i18n.t('common:loadFailed'), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { void fetchStatus(); }, [fetchStatus]);

  const applyGame = (state: MinesGameState) => {
    setGame(state);
    setStatus(s => (s ? { ...s, balance: state.balance } : s));
  };

  const handleBet = async () => {
    if (acting) return;
    setActing(true);
    try {
      const state = await minesApi.bet(betAmount);
      applyGame(state);
      setShowResult(false);
      flippedRef.current = new Set();
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.betFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  const handleReveal = async (cell: number) => {
    if (acting || !game || game.phase !== 'PLAYING') return;
    if (game.revealed.includes(cell)) return;
    setActing(true);
    try {
      const state = await minesApi.reveal(cell);
      applyGame(state);
      if (state.phase === 'SETTLED') {
        setShowResult(true);
      }
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  const handleCashout = async () => {
    if (acting || !game) return;
    setActing(true);
    try {
      const state = await minesApi.cashout();
      applyGame(state);
      setShowResult(true);
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.cashoutFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  const handleNewGame = () => {
    setGame(null);
    setShowResult(false);
    void fetchStatus();
  };

  if (loading) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
        <Skeleton className="h-16 w-full rounded-xl" />
        <Skeleton className="h-80 w-full rounded-2xl" />
        <Skeleton className="h-24 w-full rounded-xl" />
      </div>
    );
  }

  const isPlaying = game?.phase === 'PLAYING';
  const isSettled = game?.phase === 'SETTLED';
  const revealedSet = new Set(game?.revealed ?? []);
  const mineSet = new Set(game?.minePositions ?? []);

  const getCellState = (i: number) => {
    if (isSettled && mineSet.has(i)) return 'mine';
    if (revealedSet.has(i)) return 'safe';
    if (isSettled) return 'hidden-done';
    if (isPlaying) return 'clickable';
    return 'idle';
  };

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-6">
      {/* 顶栏 */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-xl bg-amber-500/20">
            <Pickaxe className="w-5 h-5 text-amber-400" />
          </div>
          <div>
            <div className="text-xs text-muted-foreground">{t('gameWallet')}</div>
            <div className="text-xl font-bold tabular-nums flex items-center gap-1.5">
              <Wallet className="w-4 h-4 text-muted-foreground" />
              {fmtNum(balance)}
            </div>
          </div>
          <Button variant="outline" size="sm" onClick={() => setTransferOpen(true)}>
            <ArrowLeftRight className="w-3.5 h-3.5" />
            {t('transfer')}
          </Button>
        </div>

        {isPlaying && game && (
          <div className="text-right">
            <div className="text-xs text-muted-foreground">{t('mines.currentMultiplier')}</div>
            <div className="text-xl font-bold tabular-nums text-amber-400">
              {fmtMult(game.currentMultiplier)}×
            </div>
          </div>
        )}

        {isPlaying && game && game.revealed.length > 0 && (
          <div className="text-right">
            <div className="text-xs text-muted-foreground">{t('mines.potential')}</div>
            <div className="text-xl font-bold tabular-nums text-emerald-400">
              {fmtNum(game.potentialPayout)}
            </div>
          </div>
        )}
      </div>

      {/* 结果横幅 */}
      {showResult && isSettled && game && (
        <div className={cn(
          'rounded-xl px-4 py-3 text-center font-bold text-lg transition-all',
          game.result === 'CASHED_OUT'
            ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
            : 'bg-red-500/20 text-red-400 border border-red-500/30'
        )}>
          {game.result === 'CASHED_OUT' ? (
            <span>{t('mines.cashedOut', { amount: fmtNum(game.payout ?? 0), mult: fmtMult(game.currentMultiplier) })}</span>
          ) : (
            <span>{t('mines.hitMine', { amount: fmtNum(game.betAmount) })}</span>
          )}
        </div>
      )}

      {/* 5×5 棋盘 */}
      <div className="grid grid-cols-5 gap-2 sm:gap-3 p-3 sm:p-4 rounded-lg border border-border bg-card-2">
        {Array.from({ length: 25 }, (_, i) => {
          const state = getCellState(i);
          const isFlipped = state === 'safe' || state === 'mine';
          const shouldAnimate = isFlipped && !flippedRef.current.has(i);
          if (isFlipped) flippedRef.current.add(i);
          const mineDelay = state === 'mine' ? `${(game?.minePositions?.indexOf(i) ?? 0) * 80}ms` : '0ms';

          return (
            <button
              key={i}
              disabled={state !== 'clickable' || acting}
              onClick={() => handleReveal(i)}
              className="aspect-square perspective-[600px] cursor-pointer disabled:cursor-default"
            >
              <div
                className={cn(
                  'relative w-full h-full transform-3d',
                  shouldAnimate ? 'transition-transform duration-500' : '',
                  isFlipped && 'transform-[rotateY(180deg)]',
                )}
                style={shouldAnimate ? { transitionDelay: mineDelay } : undefined}
              >
                {/* 正面：牌背 */}
                <div className={cn(
                  'absolute inset-0 backface-hidden rounded-xl overflow-hidden',
                  state === 'idle' && 'opacity-40',
                  state === 'clickable' && 'hover:scale-[1.06] active:scale-95 transition-transform',
                  state === 'hidden-done' && 'opacity-25',
                  acting && state === 'clickable' && 'opacity-50',
                )}>
                  <div className="w-full h-full border border-border bg-card machined rounded-lg flex items-center justify-center">
                    <span className="text-2xl sm:text-3xl select-none">⛏️</span>
                  </div>
                </div>
                {/* 背面：翻开内容 */}
                <div className={cn(
                  'absolute inset-0 backface-hidden transform-[rotateY(180deg)] rounded-xl overflow-hidden',
                )}>
                  {state === 'safe' && (
                    <div className="w-full h-full border border-warning/40 bg-amber-500/10 rounded-lg flex items-center justify-center">
                      <span className="text-2xl sm:text-3xl drop-shadow-[0_0_8px_rgba(255,215,0,0.6)]">💰</span>
                    </div>
                  )}
                  {state === 'mine' && (
                    <div className={cn(
                      'w-full h-full border border-loss/40 bg-loss/10 rounded-lg flex items-center justify-center',
                      game?.result === 'MINE' && 'animate-pulse',
                    )}>
                      <span className="text-2xl sm:text-3xl drop-shadow-[0_0_8px_rgba(255,60,60,0.6)]">💣</span>
                    </div>
                  )}
                </div>
              </div>
            </button>
          );
        })}
      </div>

      {/* 下注区 / 提现区 */}
      {!game || (isSettled && showResult) ? (
        <div className="space-y-4 rounded-lg pt-card p-5">
          <div className="text-center">
            <div className="text-xs text-muted-foreground mb-2">{t('betAmount')}</div>
            <input
              type="number"
              value={betInput}
              onChange={e => setBetInput(e.target.value)}
              className="w-full max-w-xs mx-auto block text-center text-2xl font-bold bg-input border border-border rounded-md px-4 py-2 tabular-nums focus:outline-none focus:ring-2 focus:ring-primary/50"
              min={10}
              max={5000}
            />
          </div>

          <div className="flex flex-wrap justify-center gap-2">
            {BET_PRESETS.map(v => (
              <button
                key={v}
                onClick={() => setBetInput(String(v))}
                disabled={v > balance}
                className={cn(
                  'px-3 py-1.5 rounded-lg text-sm font-medium transition-all',
                  betAmount === v
                    ? 'bg-primary text-primary-foreground machined'
                    : 'bg-muted border border-border hover:bg-surface-hover',
                  v > balance && 'opacity-40 cursor-not-allowed',
                )}
              >
                {v >= 1000 ? `${v / 1000}K` : v}
              </button>
            ))}
          </div>

          {isSettled && showResult ? (
            <Button onClick={handleNewGame} className="w-full h-12 text-base">
              {t('playAgain')}
            </Button>
          ) : (
            <Button
              onClick={handleBet}
              disabled={acting || betAmount < 10 || betAmount > 5000 || betAmount > balance}
              className="w-full h-12 text-base"
            >
              <Pickaxe className="w-4 h-4" />
              {t('mines.start')}
            </Button>
          )}
        </div>
      ) : isPlaying && game ? (
        <div className="space-y-3">
          <div className="text-center">
            <div className="text-xs text-muted-foreground mb-1">{t('mines.cashoutTitle')}</div>
            <div className="text-3xl font-bold tabular-nums text-emerald-400">
              {game.revealed.length > 0 ? fmtNum(game.potentialPayout) : fmtNum(0)}
            </div>
            {game.nextMultiplier && (
              <div className="text-xs text-muted-foreground mt-1 flex items-center justify-center gap-1">
                <TrendingUp className="w-3 h-3" />
                {t('mines.nextTile', { mult: fmtMult(game.nextMultiplier) })}
              </div>
            )}
          </div>
          <Button
            onClick={handleCashout}
            disabled={acting || game.revealed.length === 0}
            className="w-full h-12 text-base bg-emerald-600 hover:bg-emerald-500 border-emerald-500/30 text-white"
          >
            {game.revealed.length > 0
              ? t('mines.cashoutAmount', { amount: fmtNum(game.potentialPayout) })
              : t('mines.cashout')}
          </Button>
        </div>
      ) : null}

      {/* 倍率进度 */}
      {isPlaying && game && (
        <div className="space-y-2">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{t('mines.revealed', { n: game.revealed.length })}</span>
            <span>{fmtMult(game.currentMultiplier)}×</span>
          </div>
          <div className="h-2 bg-muted rounded-full overflow-hidden">
            <div
              className="h-full bg-linear-to-r from-emerald-500 to-amber-500 rounded-full transition-all duration-500"
              style={{ width: `${(game.revealed.length / 20) * 100}%` }}
            />
          </div>
        </div>
      )}

      {/* 规则 */}
      <div className="rounded-lg pt-card p-5 space-y-3">
        <h3 className="font-semibold text-sm">{t('mines.rules.title')}</h3>
        <ul className="text-xs text-muted-foreground space-y-1 leading-relaxed">
          <li>{t('mines.rules.item1')}</li>
          <li>{t('mines.rules.item2')}</li>
          <li>{t('mines.rules.item3')}</li>
        </ul>
      </div>

      {/* 划转成功后刷 status：顶栏余额取自游戏接口而非 user store */}
      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} onSuccess={() => void fetchStatus()} />
    </div>
  );
}
