import { useState, useEffect } from 'react';
import { cn } from '../../lib/utils';

const SUIT_SYMBOLS: Record<string, string> = { H: '\u2665', D: '\u2666', C: '\u2663', S: '\u2660' };
const SUIT_COLORS: Record<string, string> = { H: 'text-red-500', D: 'text-red-500', C: 'text-zinc-800', S: 'text-zinc-800' };
const RANK_DISPLAY: Record<string, string> = { T: '10', A: 'A', J: 'J', Q: 'Q', K: 'K' };

interface Props {
  card: string;
  className?: string;
  delay?: number;
}

export function PlayingCard({ card, className, delay = 0 }: Props) {
  // render 期 prev 比较（React 文档模式）：暗牌翻开的瞬间触发 flip 动画
  const [prevCard, setPrevCard] = useState(card);
  const [flip, setFlip] = useState(false);
  if (prevCard !== card) {
    setPrevCard(card);
    if (prevCard === '??' && card !== '??') setFlip(true);
  }

  useEffect(() => {
    if (!flip) return;
    const t = setTimeout(() => setFlip(false), 500);
    return () => clearTimeout(t);
  }, [flip]);

  const isHidden = card === '??';
  const animStyle = !flip && delay > 0
    ? { animationDelay: `${delay}ms`, animationFillMode: 'both' as const }
    : undefined;

  if (isHidden) {
    return (
      <div className={cn('bj-card bj-deal-in', className)} style={animStyle}>
        <div className="bj-card-back">
          <div className="bj-card-back-pattern" />
        </div>
      </div>
    );
  }

  const rank = card[0];
  const suit = card[1];
  const displayRank = RANK_DISPLAY[rank] || rank;
  const suitSymbol = SUIT_SYMBOLS[suit] || '';
  const colorClass = SUIT_COLORS[suit] || '';

  return (
    <div
      className={cn('bj-card', flip ? 'bj-flip' : 'bj-deal-in', className)}
      style={flip ? undefined : animStyle}
    >
      <div className="bj-card-front">
        <div className={cn('absolute top-1.5 left-2 leading-none font-bold text-sm bj-card-label', colorClass)}>
          <div>{displayRank}</div>
          <div className="text-xs mt-0.5">{suitSymbol}</div>
        </div>
        <div className={cn('text-3xl sm:text-4xl', colorClass)}>
          {suitSymbol}
        </div>
        <div className={cn('absolute bottom-1.5 right-2 leading-none font-bold text-sm rotate-180 bj-card-label', colorClass)}>
          <div>{displayRank}</div>
          <div className="text-xs mt-0.5">{suitSymbol}</div>
        </div>
      </div>
    </div>
  );
}
