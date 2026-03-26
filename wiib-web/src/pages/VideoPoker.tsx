import { useState, useEffect, useCallback, useRef } from 'react';
import { videoPokerApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { Wallet, ChevronDown } from 'lucide-react';
import { CardContent } from '../components/ui/card';
import { cn } from '../lib/utils';
import type { VideoPokerGameState, VideoPokerStatus } from '../types';

const BET_PRESETS = [100, 500, 1000, 5000, 10000, 50000];
const CHIP_COLORS: Record<number, string> = {
  100: 'vp-chip-100', 500: 'vp-chip-500', 1000: 'vp-chip-1000',
  5000: 'vp-chip-5000', 10000: 'vp-chip-10000', 50000: 'vp-chip-50000',
};

const fmt = (n: number) => n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const SUIT_SYMBOLS: Record<string, string> = { H: '♥', D: '♦', C: '♣', S: '♠' };
const SUIT_COLORS: Record<string, string> = { H: 'text-red-500', D: 'text-red-500', C: 'text-zinc-800', S: 'text-zinc-800' };
const RANK_DISPLAY: Record<string, string> = { T: '10', A: 'A', J: 'J', Q: 'Q', K: 'K' };

const PAYOUT_TABLE = [
  { name: '皇家同花顺', mult: '800x' },
  { name: '小丑皇家同花顺', mult: '100x' },
  { name: '五张相同', mult: '50x' },
  { name: '同花顺', mult: '50x' },
  { name: '四条', mult: '20x' },
  { name: '葫芦', mult: '7x' },
  { name: '同花', mult: '5x' },
  { name: '顺子', mult: '3.5x' },
  { name: '三条', mult: '2.5x' },
  { name: '两对', mult: '1.5x' },
  { name: 'J或更大', mult: '1x' },
];

function parseCard(card: string) {
  if (card.startsWith('JK')) return { rank: '🃏', suit: '', isJoker: true, color: 'text-purple-400' };
  const rank = card[0];
  const suit = card[1];
  return {
    rank: RANK_DISPLAY[rank] ?? rank,
    suit: SUIT_SYMBOLS[suit] ?? suit,
    isJoker: false,
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
      ) : parsed?.isJoker ? (
        <div className="vp-card-joker">
          <span className="text-2xl sm:text-4xl">🃏</span>
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
  const [status, setStatus] = useState<VideoPokerStatus | null>(null);
  const [game, setGame] = useState<VideoPokerGameState | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [betAmount, setBetAmount] = useState(1000);
  const [held, setHeld] = useState<Set<number>>(new Set());
  const [replacing, setReplacing] = useState<Set<number>>(new Set());
  const [showDisclaimer, setShowDisclaimer] = useState(false);

  const prevCardsRef = useRef<string[]>([]);

  const balance = game?.balance ?? status?.balance ?? 0;
  const isDealing = game?.phase === 'DEALING';
  const isSettled = game?.phase === 'SETTLED';

  const fetchStatus = useCallback(async () => {
    try {
      const s = await videoPokerApi.status();
      setStatus(s);
      if (s.activeGame) {
        setGame(s.activeGame);
        setHeld(new Set());
      }
    } catch (e: unknown) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

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
  }, [game?.cards, game?.heldPositions]);

  const handleBet = async () => {
    if (acting) return;
    setActing(true);
    prevCardsRef.current = [];
    try {
      const state = await videoPokerApi.bet(betAmount);
      setGame(state);
      setHeld(new Set());
    } catch (e: unknown) {
      toast((e as Error).message || '下注失败', 'error');
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
      setGame(state);
    } catch (e: unknown) {
      toast((e as Error).message || '换牌失败', 'error');
    } finally {
      setActing(false);
    }
  };

  const handleNewGame = () => {
    setGame(null);
    setHeld(new Set());
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
  const cards = game?.cards ?? [];

  return (
    <div className="max-w-2xl mx-auto px-3 py-4 space-y-4">
      {/* 顶栏：余额 + 规则 */}
      <div className="flex items-center justify-between px-1">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-lg bg-purple-500/15">
            <span className="text-xl">🃏</span>
          </div>
          <div>
            <div className="text-[10px] text-muted-foreground uppercase tracking-wider">Joker&apos;s Wild</div>
            <div className="text-xl font-bold tabular-nums flex items-center gap-1.5">
              <Wallet className="w-4 h-4 text-muted-foreground" />
              {fmt(balance)}
            </div>
          </div>
        </div>
      </div>

      {/* 机台主体 */}
      <div className="vp-machine">
        {/* 赔率表 - 紧凑两列 */}
        <div className="vp-payout-bar">
          <div className="grid grid-cols-2 gap-x-6 gap-y-0.5">
            {PAYOUT_TABLE.map(row => (
              <div
                key={row.name}
                className={cn(
                  'flex justify-between text-xs px-2 py-0.5 rounded',
                  winRank === row.name
                    ? 'bg-emerald-500/20 text-emerald-700 dark:text-emerald-300 font-bold'
                    : 'text-muted-foreground',
                )}
              >
                <span className={winRank === row.name ? 'text-emerald-800 dark:text-emerald-200' : 'text-foreground/70'}>{row.name}</span>
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
                  <div className="text-lg">{winRank}</div>
                  <div className="text-sm font-normal text-emerald-800 dark:text-emerald-300/80">+{fmt(game.payout)} ({game.multiplier}x)</div>
                </div>
              ) : (
                <span className="text-sm">未中奖 · -{fmt(game.betAmount)}</span>
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
              点击选择保留的牌
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
              再来一局
            </Button>
          ) : !game ? (
            <div className="space-y-4">
              {/* 下注金额 */}
              <div className="text-center">
                <div className="text-xs text-muted-foreground uppercase tracking-widest mb-1">下注金额</div>
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
                disabled={acting || betAmount < 100 || betAmount > 50000 || betAmount > balance}
                className="w-full h-12 text-base font-bold bg-amber-500 hover:bg-amber-400 text-black"
              >
                🃏 发牌
              </Button>
            </div>
          ) : isDealing ? (
            <Button
              onClick={handleDraw}
              disabled={acting}
              className="w-full h-12 text-base font-bold bg-purple-600 hover:bg-purple-500"
            >
              换牌（已 HOLD {held.size} 张）
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
          <span>风险提示与免责声明</span>
          <ChevronDown className={cn('w-3.5 h-3.5 transition-transform', showDisclaimer && 'rotate-180')} />
        </button>
        {showDisclaimer && (
          <div className="px-3 pb-2.5">
            <ul className="list-disc list-inside text-[11px] text-red-600/60 dark:text-red-300/70 space-y-0.5 leading-relaxed">
              <li>本小游戏不涉及任何赌博行为，不涉及任何现实资金下注或交易。</li>
              <li>仅用于为用户提供一个每日资金获取途径的趣味化体验，所有结算均为站内机制。</li>
              <li>赌博可能导致成瘾、债务风险、家庭关系破裂及心理健康问题，请远离任何现实赌博活动。</li>
            </ul>
          </div>
        )}
      </div>

      {/* 游戏规则 */}
      <div className="rounded-lg border border-border/50 bg-muted/30">
        <CardContent className="space-y-3 text-sm leading-relaxed pt-4 pb-3">
          <section>
            <h3 className="font-semibold mb-1.5 text-xs uppercase tracking-wider text-muted-foreground">基础规则</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-0.5 text-xs">
              <li>一副牌牌（含小丑牌），目标凑最高牌型</li>
              <li>下注后发5张，选择保留（HOLD）或不保留</li>
              <li>换牌后未保留的牌被替换，按赔率表结算</li>
            </ul>
          </section>
          <section>
            <h3 className="font-semibold mb-1.5 text-xs uppercase tracking-wider text-muted-foreground">牌型说明</h3>
            <ul className="text-muted-foreground space-y-0.5 text-xs">
              <li><strong className="text-foreground/80">皇家同花顺</strong> A K Q J 10 同花 · <strong className="text-foreground/80">小丑皇家</strong> 小丑替代 · <strong className="text-foreground/80">五张相同</strong> 四条+小丑</li>
              <li><strong className="text-foreground/80">同花顺</strong> 连续五张同花 · <strong className="text-foreground/80">四条</strong> 四张同点 · <strong className="text-foreground/80">葫芦</strong> 三条+一对</li>
              <li><strong className="text-foreground/80">同花</strong> 五张同花 · <strong className="text-foreground/80">顺子</strong> 连续五张 · <strong className="text-foreground/80">三条</strong> 三张同点</li>
              <li><strong className="text-foreground/80">两对</strong> 两组对子 · <strong className="text-foreground/80">J或更大</strong> J以上的一对（最低奖励）</li>
            </ul>
          </section>
        </CardContent>
      </div>
    </div>
  );
}
