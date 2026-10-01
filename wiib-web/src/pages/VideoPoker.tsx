import { useState, useEffect, useCallback, useRef } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { videoPokerApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { Wallet, ChevronDown, ArrowLeftRight } from 'lucide-react';
import { CardContent } from '../components/ui/card';
import { cn, fmtNum } from '../lib/utils';
import i18n from '../i18n';
import type { VideoPokerGameState, VideoPokerStatus } from '../types';

const BET_PRESETS = [10, 50, 100, 500, 1000, 5000];
// 面额÷10对齐新经济，配色沿用原档位样式类
const CHIP_COLORS: Record<number, string> = {
  10: 'vp-chip-100', 50: 'vp-chip-500', 100: 'vp-chip-1000',
  500: 'vp-chip-5000', 1000: 'vp-chip-10000', 5000: 'vp-chip-50000',
};


const SUIT_SYMBOLS: Record<string, string> = { H: '♥', D: '♦', C: '♣', S: '♠' };
const SUIT_COLORS: Record<string, string> = { H: 'text-red-500', D: 'text-red-500', C: 'text-zinc-800', S: 'text-zinc-800' };
const RANK_DISPLAY: Record<string, string> = { T: '10', A: 'A', J: 'J', Q: 'Q', K: 'K' };

// key 对齐后端 handRank（中奖行高亮靠它匹配）；8/5 Jacks or Better 标准表，倍率含本金
// nameKey 存词表 key 字面量，渲染时才 t()，别拼动态 key
const PAYOUT_TABLE = [
  { key: 'Royal Flush', nameKey: 'videoPoker.hands.royalFlush', mult: '800x' },
  { key: 'Straight Flush', nameKey: 'videoPoker.hands.straightFlush', mult: '50x' },
  { key: 'Four of a Kind', nameKey: 'videoPoker.hands.fourOfAKind', mult: '25x' },
  { key: 'Full House', nameKey: 'videoPoker.hands.fullHouse', mult: '8x' },
  { key: 'Flush', nameKey: 'videoPoker.hands.flush', mult: '5x' },
  { key: 'Straight', nameKey: 'videoPoker.hands.straight', mult: '4x' },
  { key: 'Three of a Kind', nameKey: 'videoPoker.hands.threeOfAKind', mult: '3x' },
  { key: 'Two Pair', nameKey: 'videoPoker.hands.twoPair', mult: '2x' },
  { key: 'Jacks or Better', nameKey: 'videoPoker.hands.jacksOrBetter', mult: '1x' },
];

// 牌型说明每条固定三个加粗名，样式留在这儿，词表里只写 <0>/<1>/<2>
const HAND_DESC_TAGS = [
  <strong key="a" className="text-foreground/80" />,
  <strong key="b" className="text-foreground/80" />,
  <strong key="c" className="text-foreground/80" />,
];

function parseCard(card: string) {
  const rank = card[0];
  const suit = card[1];
  return {
    rank: RANK_DISPLAY[rank] ?? rank,
    suit: SUIT_SYMBOLS[suit] ?? suit,
    color: SUIT_COLORS[suit] ?? 'text-zinc-800',
  };
}

function PokerCard({ card, isHeld, isOldHeld, isReplacing, onClick, disabled, delay }: {
  card?: string;
  isHeld: boolean;
  isOldHeld?: boolean;
  isReplacing?: boolean;
  onClick?: () => void;
  disabled?: boolean;
  delay?: number;
}) {
  const parsed = card ? parseCard(card) : null;
  const animStyle = !isReplacing && delay != null && delay > 0
    ? { animationDelay: `${delay}ms`, animationFillMode: 'both' as const }
    : undefined;

  const showBack = isReplacing || !card;

  return (
    <button
      onClick={onClick}
      disabled={disabled || !card}
      className={cn(
        'vp-card',
        isHeld && 'vp-card-held',
        isOldHeld && 'vp-card-old-held',
        isReplacing && 'animate-card-replace',
        card && !disabled && 'cursor-pointer',
        (!card || disabled) && 'cursor-default',
      )}
      style={animStyle}
    >
      {showBack ? (
        <div className="vp-card-back">
          <div className="vp-card-back-pattern" />
        </div>
      ) : parsed ? (
        <div className="vp-card-front">
          <div className={cn('vp-card-corner vp-card-corner-tl', parsed.color)}>
            <div>{parsed.rank}</div>
            <div className="text-xs">{parsed.suit}</div>
          </div>
          <div className={cn('vp-card-center', parsed.color)}>
            {parsed.suit}
          </div>
          <div className={cn('vp-card-corner vp-card-corner-br', parsed.color)}>
            <div>{parsed.rank}</div>
            <div className="text-xs">{parsed.suit}</div>
          </div>
        </div>
      ) : null}
      {isHeld && !isReplacing && card && (
        <div className="vp-card-hold-label">HOLD</div>
      )}
    </button>
  );
}

export function VideoPoker() {
  const { toast } = useToast();
  const { t } = useTranslation(['games', 'common']);
  const [status, setStatus] = useState<VideoPokerStatus | null>(null);
  const [game, setGame] = useState<VideoPokerGameState | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [betAmount, setBetAmount] = useState(100);
  const [held, setHeld] = useState<Set<number>>(new Set());
  const [replacing, setReplacing] = useState<Set<number>>(new Set());
  const [showDisclaimer, setShowDisclaimer] = useState(false);
  const [transferOpen, setTransferOpen] = useState(false);

  const prevCardsRef = useRef<string[]>([]);

  // 顶栏余额以 status 为准：每手牌返回都同步进来，划转后 fetchStatus 也刷它
  const balance = status?.balance ?? 0;
  const isDealing = game?.phase === 'DEALING';
  const isSettled = game?.phase === 'SETTLED';

  // 不依赖 t：切语言会换新 t，依赖了就会重拉、打断牌局
  // 不清 held：进页、再来一局时本来就空，划转后刷新还是同一手
  const fetchStatus = useCallback(async () => {
    try {
      const s = await videoPokerApi.status();
      setStatus(s);
      if (s.activeGame) setGame(s.activeGame);
    } catch (e: unknown) {
      toast((e as Error).message || i18n.t('common:loadFailed'), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const applyGame = (state: VideoPokerGameState) => {
    setGame(state);
    setStatus(s => (s ? { ...s, balance: state.balance } : s));
  };

  useEffect(() => { void fetchStatus(); }, [fetchStatus]);

  useEffect(() => {
    if (game && prevCardsRef.current.length === 5 && game.cards.length === 5) {
      const newReplacing = new Set<number>();
      game.cards.forEach((card, i) => {
        if (card !== prevCardsRef.current[i] && !game.heldPositions?.includes(i)) {
          newReplacing.add(i);
        }
      });
      if (newReplacing.size > 0) {
        setReplacing(newReplacing);
        const t = setTimeout(() => setReplacing(new Set()), 600);
        return () => clearTimeout(t);
      }
    }
    prevCardsRef.current = [...game?.cards ?? []];
  }, [game]);

  const handleBet = async () => {
    if (acting) return;
    setActing(true);
    prevCardsRef.current = [];
    try {
      const state = await videoPokerApi.bet(betAmount);
      applyGame(state);
      setHeld(new Set());
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.betFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  const toggleHold = (i: number) => {
    if (!isDealing || acting) return;
    setHeld(prev => {
      const next = new Set(prev);
      if (next.has(i)) next.delete(i); else next.add(i);
      return next;
    });
  };

  const handleDraw = async () => {
    if (acting || !game) return;
    setActing(true);
    prevCardsRef.current = [...game.cards];
    try {
      const state = await videoPokerApi.draw(Array.from(held));
      applyGame(state);
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.drawFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  const handleNewGame = () => {
    setGame(null);
    setHeld(new Set());
    // 换牌动画 600ms 内点再来一局，定时器被清掉没来得及收，这里收掉
    setReplacing(new Set());
    prevCardsRef.current = [];
    void fetchStatus();
  };

  if (loading) {
    return (
      <div className="max-w-lg mx-auto px-4 py-6 space-y-3">
        <Skeleton className="h-12 w-full rounded-xl" />
        <Skeleton className="h-56 w-full rounded-xl" />
        <Skeleton className="h-32 w-full rounded-xl" />
      </div>
    );
  }

  const winRank = isSettled && game?.handRank && game.handRank !== 'No Win' ? game.handRank : null;
  // 赔率表里找不到这个 handRank（后端加了新牌型）就原样显示后端下发的名字
  const winRankKey = winRank ? PAYOUT_TABLE.find(r => r.key === winRank)?.nameKey : undefined;
  const cards = game?.cards ?? [];

  return (
    <div className="max-w-2xl mx-auto px-3 py-4 space-y-4">
      {/* 顶栏：余额 + 规则 */}
      <div className="flex items-center justify-between px-1">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-lg bg-purple-500/15">
            <span className="text-xl">♠️</span>
          </div>
          <div>
            <div className="text-[10px] text-muted-foreground uppercase tracking-wider">{t('gameWallet')}</div>
            <div className="text-xl font-bold tabular-nums flex items-center gap-1.5">
              <Wallet className="w-4 h-4 text-muted-foreground" />
              {fmtNum(balance)}
            </div>
          </div>
        </div>
        <Button variant="outline" size="sm" onClick={() => setTransferOpen(true)}>
          <ArrowLeftRight className="w-3.5 h-3.5" />
          {t('transfer')}
        </Button>
      </div>

      {/* 机台主体 */}
      <div className="vp-machine">
        {/* 赔率表 - 紧凑两列 */}
        <div className="vp-payout-bar">
          <div className="grid grid-cols-2 gap-x-6 gap-y-0.5">
            {PAYOUT_TABLE.map(row => (
              <div
                key={row.key}
                className={cn(
                  'flex justify-between text-xs px-2 py-0.5 rounded',
                  winRank === row.key
                    ? 'bg-emerald-500/20 text-emerald-700 dark:text-emerald-300 font-bold'
                    : 'text-muted-foreground',
                )}
              >
                <span className={winRank === row.key ? 'text-emerald-800 dark:text-emerald-200' : 'text-foreground/70'}>{t(row.nameKey)}</span>
                <span className="tabular-nums font-medium ml-2">{row.mult}</span>
              </div>
            ))}
          </div>
        </div>

        {/* 绿毡牌桌 */}
        <div className="vp-table casino-felt px-4 py-6 sm:px-6 sm:py-8">
          {/* 结果横幅 */}
          {isSettled && game && (
            <div className={cn(
              'vp-result-in rounded-lg px-4 py-3 text-center font-bold mb-5',
              winRank
                ? 'bg-emerald-500/30 text-emerald-900 dark:text-emerald-200 vp-result-win'
                : 'bg-red-500/20 text-red-600 dark:text-red-300'
            )}>
              {winRank ? (
                <div>
                  <div className="text-lg">{winRankKey ? t(winRankKey) : winRank}</div>
                  <div className="text-sm font-normal text-emerald-800 dark:text-emerald-300/80">+{fmtNum(game.payout)} ({game.multiplier}x)</div>
                </div>
              ) : (
                <span className="text-sm">{t('videoPoker.noWin', { amount: fmtNum(game.betAmount) })}</span>
              )}
            </div>
          )}

          {/* 5张牌 */}
          <div className="flex gap-2 sm:gap-4 justify-center pt-1">
            {[0, 1, 2, 3, 4].map(i => (
              <PokerCard
                key={i}
                card={cards[i]}
                isHeld={held.has(i)}
                isOldHeld={isSettled && !!game?.heldPositions?.includes(i)}
                isReplacing={replacing.has(i)}
                onClick={() => toggleHold(i)}
                disabled={!isDealing || acting}
                delay={i * 100}
              />
            ))}
          </div>

          {/* DEALING阶段提示 */}
          {isDealing && (
            <div className="text-center mt-3 text-xs text-muted-foreground">
              {t('videoPoker.holdHint')}
            </div>
          )}
        </div>

        {/* 控制台 */}
        <div className="vp-controls">
          {isSettled ? (
            <Button
              onClick={handleNewGame}
              className="w-full h-12 text-base font-bold bg-emerald-600 hover:bg-emerald-500"
            >
              {t('playAgain')}
            </Button>
          ) : !game ? (
            <div className="space-y-4">
              {/* 下注金额 */}
              <div className="text-center">
                <div className="text-xs text-muted-foreground uppercase tracking-widest mb-1">{t('betAmount')}</div>
                <div className="text-4xl sm:text-5xl font-bold text-foreground tabular-nums">
                  {betAmount.toLocaleString()}
                </div>
              </div>

              {/* 筹码选择 */}
              <div className="flex flex-wrap justify-center gap-2.5">
                {BET_PRESETS.map((v, i) => (
                  <button
                    key={v}
                    onClick={() => setBetAmount(v)}
                    disabled={v > balance}
                    className={cn(
                      'vp-chip vp-chip-pop',
                      CHIP_COLORS[v],
                      betAmount === v && 'vp-chip-selected',
                    )}
                    style={{ animationDelay: `${i * 40}ms` }}
                  >
                    {v >= 1000 ? `${v / 1000}K` : v}
                  </button>
                ))}
              </div>

              <Button
                onClick={handleBet}
                disabled={acting || betAmount < 10 || betAmount > 5000 || betAmount > balance}
                className="w-full h-12 text-base font-bold bg-amber-500 hover:bg-amber-400 text-black"
              >
                ♠️ {t('videoPoker.deal')}
              </Button>
            </div>
          ) : isDealing ? (
            <Button
              onClick={handleDraw}
              disabled={acting}
              className="w-full h-12 text-base font-bold bg-purple-600 hover:bg-purple-500"
            >
              {t('videoPoker.draw', { n: held.size })}
            </Button>
          ) : null}
        </div>
      </div>

      {/* 折叠式风险提示 */}
      <div className="rounded-lg border border-red-500/20 bg-red-500/5 overflow-hidden">
        <button
          onClick={() => setShowDisclaimer(!showDisclaimer)}
          className="w-full flex items-center justify-between px-3 py-2 text-xs text-red-600/70 dark:text-red-400/80 hover:text-red-600 dark:hover:text-red-400 transition-colors"
        >
          <span>{t('disclaimer.toggle')}</span>
          <ChevronDown className={cn('w-3.5 h-3.5 transition-transform', showDisclaimer && 'rotate-180')} />
        </button>
        {showDisclaimer && (
          <div className="px-3 pb-2.5">
            <ul className="list-disc list-inside text-[11px] text-red-600/60 dark:text-red-300/70 space-y-0.5 leading-relaxed">
              <li>{t('disclaimer.item1')}</li>
              <li>{t('disclaimer.item2')}</li>
              <li>{t('disclaimer.item3')}</li>
            </ul>
          </div>
        )}
      </div>

      {/* 游戏规则 */}
      <div className="rounded-lg border border-border/50 bg-muted/30">
        <CardContent className="space-y-3 text-sm leading-relaxed pt-4 pb-3">
          <section>
            <h3 className="font-semibold mb-1.5 text-xs uppercase tracking-wider text-muted-foreground">{t('videoPoker.rules.basicsTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-0.5 text-xs">
              <li>{t('videoPoker.rules.basics1')}</li>
              <li>{t('videoPoker.rules.basics2')}</li>
              <li>{t('videoPoker.rules.basics3')}</li>
            </ul>
          </section>
          <section>
            <h3 className="font-semibold mb-1.5 text-xs uppercase tracking-wider text-muted-foreground">{t('videoPoker.rules.handsTitle')}</h3>
            <ul className="text-muted-foreground space-y-0.5 text-xs">
              {/* 牌型名加粗夹在解释里，中英语序不同，整条交给 Trans 摆位 */}
              <li><Trans ns="games" i18nKey="videoPoker.rules.handDesc1" components={HAND_DESC_TAGS} /></li>
              <li><Trans ns="games" i18nKey="videoPoker.rules.handDesc2" components={HAND_DESC_TAGS} /></li>
              <li><Trans ns="games" i18nKey="videoPoker.rules.handDesc3" components={HAND_DESC_TAGS} /></li>
            </ul>
          </section>
        </CardContent>
      </div>

      {/* 划转成功后刷 status：顶栏余额取自游戏接口而非 user store */}
      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} onSuccess={() => void fetchStatus()} />
    </div>
  );
}
