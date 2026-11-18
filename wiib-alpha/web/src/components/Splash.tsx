import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RefreshCw, WifiOff } from 'lucide-react';
import logo from '../assets/logo.png';
import { cx } from '../format';
import { Button } from './ui';

type Phase = 'init' | 'go' | 'done' | 'out' | 'gone';

/**
 * 开屏（同主站）：logo 和字标从墨线后面升起，墨线当进度慢慢走；ready 了补满、沿线上下裂开；
 * 头一次就连不上，线下给原因和重试
 */
export function Splash({ ready, error, onRetry }: {
  ready: boolean;
  error: string | null;
  onRetry: () => void;
}) {
  const { t } = useTranslation();
  const [phase, setPhase] = useState<Phase>('init');
  const born = useRef(0);

  // 先画一帧线的起点，下一帧挂 go 开始走
  useEffect(() => {
    born.current = performance.now();
    const id = requestAnimationFrame(() => setPhase(p => (p === 'init' ? 'go' : p)));
    return () => cancelAnimationFrame(id);
  }, []);

  // 至少亮 0.95s，logo 升完才收
  useEffect(() => {
    if (!ready) return;
    const wait = Math.max(0, 950 - (performance.now() - born.current));
    const ids = [
      setTimeout(() => setPhase('done'), wait),
      setTimeout(() => setPhase('out'), wait + 320),
      setTimeout(() => setPhase('gone'), wait + 320 + 780),
    ];
    return () => ids.forEach(clearTimeout);
  }, [ready]);

  if (phase === 'gone') return null;
  return (
    <div className={cx('splash', phase)}>
      <div className="sp-top" aria-hidden="true">
        <div className="sp-mark">
          <img src={logo} alt="" />
          <span className="sp-word">WHATIFIBOUGHT<i>.</i><b>ALPHA</b></span>
        </div>
        <div className="sp-rule"><i /></div>
      </div>
      <div className="sp-bot">
        {error ? (
          <div className="flex max-w-md flex-col items-center gap-3 text-center">
            <p className="flex items-start gap-2 text-[13px] text-loss break-words">
              <WifiOff className="w-4 h-4 shrink-0 mt-0.5" />{t('auth.down', { msg: error })}
            </p>
            <Button icon={RefreshCw} onClick={onRetry}>{t('auth.retry')}</Button>
          </div>
        ) : !ready && <p className="sr-only" role="status">{t('auth.checking')}</p>}
      </div>
    </div>
  );
}
