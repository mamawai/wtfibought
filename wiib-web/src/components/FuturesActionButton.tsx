import { Loader2, Coins, HandCoins } from 'lucide-react';
import { useState, useRef, useEffect, type CSSProperties } from 'react';

const MOBILE_ANIM_DURATION = 1200;

/**
 * 下单动画按钮（刷卡机）：桌面 hover 播动画、点击即下单；手机端第一次点按播动画、
 * 动画结束触发下单。视觉样式集中在 index.css 的 .fab-btn（styled-components 已迁出）。
 */
interface FuturesActionButtonProps {
  side: 'LONG' | 'SHORT' | 'BUY' | 'SELL';
  leverage?: number;
  label?: string;
  loading?: boolean;
  success?: boolean;
  disabled?: boolean;
  className?: string;
  onClick?: () => void;
}

export function FuturesActionButton({
  side,
  leverage,
  label,
  loading = false,
  success = false,
  disabled = false,
  className,
  onClick,
}: FuturesActionButtonProps) {
  const [activated, setActivated] = useState(false);
  const activatedRef = useRef(false);
  const timerRef = useRef<number | null>(null);
  const isMobileRef = useRef(window.matchMedia('(hover: none)').matches);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const handlePointerDown = () => {
    if (disabled || loading) return;
    if (!isMobileRef.current) {
      onClick?.();
      return;
    }
    if (activatedRef.current) return;
    activatedRef.current = true;
    setActivated(true);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      activatedRef.current = false;
      setActivated(false);
    }, MOBILE_ANIM_DURATION);
  };

  const prevActivatedRef = useRef(false);
  useEffect(() => {
    if (prevActivatedRef.current && !activated && onClick) {
      onClick();
    }
    prevActivatedRef.current = activated;
  }, [activated, onClick]);

  const isSpot = side === 'BUY' || side === 'SELL';
  const actionText = isSpot
    ? `${side === 'BUY' ? '买入' : '卖出'}${label ? ` ${label}` : ''}`
    : `${side === 'LONG' ? '做多' : '做空'} ${leverage}x`;
  const accent = isSpot ? '#f59e0b' : side === 'LONG' ? '#089981' : '#f23645';
  const accentSoft = isSpot ? '#fef3c7' : side === 'LONG' ? '#bbf7d0' : '#fecaca';
  const accentLine = isSpot ? '#fbbf24' : side === 'LONG' ? '#4ade80' : '#f87171';
  const accentDark = isSpot ? '#b45309' : side === 'LONG' ? '#0a7a5c' : '#b91c1c';
  const accentShadow = isSpot
    ? 'rgba(245, 158, 11, 0.34)'
    : side === 'LONG'
      ? 'rgba(8, 153, 129, 0.34)'
      : 'rgba(242, 54, 69, 0.38)';
  const screenIcon = isSpot
    ? (side === 'BUY' ? <Coins size={14} /> : <HandCoins size={14} />)
    : (side === 'LONG' ? '↑' : '↓');

  return (
    <button
      className={`fab-btn ${className ?? ''} ${activated ? 'activated' : ''} ${success ? 'success' : ''}`}
      type="button"
      onPointerDown={handlePointerDown}
      disabled={disabled || success}
      style={{
        '--accent': accent,
        '--accent-soft': accentSoft,
        '--accent-line': accentLine,
        '--accent-dark': accentDark,
        '--accent-shadow': accentShadow,
      } as CSSProperties}
    >
      <span className="content">
        <span className="left-side" aria-hidden="true">
          <span className="card">
            <span className="card-line" />
            <span className="buttons" />
          </span>
          <span className="post">
            <span className="post-line" />
            <span className="screen">
              <span className="dollar">{screenIcon}</span>
            </span>
            <span className="numbers" />
            <span className="numbers-line2" />
          </span>
        </span>
        <span className="right-side">
          {loading ? <Loader2 className="spinner" /> : <span className="label">{actionText}</span>}
        </span>
      </span>
      <span className="success-layer" aria-hidden={!success}>
        <svg className="success-icon" viewBox="0 0 52 52" fill="none">
          <circle className="success-circle" cx="26" cy="26" r="20" />
          <path className="success-check" d="M16 26.5L23 33.5L36.5 19.5" />
        </svg>
      </span>
    </button>
  );
}
