import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import type { FxPhase } from '../hooks/useSubmitFx';

/**
 * 下单大按钮（index.css 的 .cta）：做多/买入绿，做空/卖出红，换方向时字从下面浮上来。
 * fx 是提交的三段反馈（见 useSubmitFx）：转圈、打勾、晃一下，宽度都不变。
 */
interface FuturesActionButtonProps {
  side: 'LONG' | 'SHORT' | 'BUY' | 'SELL';
  leverage?: number;
  /** 合约=币对（开多 BTCUSDT · 5x），现货=币名（买入 BTC） */
  label?: string;
  fx?: FxPhase;
  disabled?: boolean;
  className?: string;
  onClick?: () => void;
}

export function FuturesActionButton({
  side,
  leverage,
  label,
  fx,
  disabled = false,
  className,
  onClick,
}: FuturesActionButtonProps) {
  const { t } = useTranslation('trade');
  const isSpot = side === 'BUY' || side === 'SELL';
  const isUp = side === 'LONG' || side === 'BUY';
  const text = isSpot
    ? `${t(side === 'BUY' ? 'side.buy' : 'side.sell')}${label ? ` ${label}` : ''}`
    : `${t(side === 'LONG' ? 'side.openLong' : 'side.openShort')}${label ? ` ${label}` : ''} · ${leverage}x`;

  return (
    <button type="button" onClick={onClick} disabled={disabled} className={cn('cta', !isUp && 'short', fx, className)}>
      <span key={side} className="lb">{text}</span>
    </button>
  );
}
