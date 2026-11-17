import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { WifiOff } from 'lucide-react';

/**
 * 断网常驻条：贴在顶栏行情条下方。
 * 装成 PWA 后没有浏览器地址栏，断网时页面还能从缓存起来（壳被 SW 预缓存），
 * 但行情和接口全是死的——不明说的话用户会把冻住的旧价格当成真行情。
 * 用常驻条而不是 toast：toast 会自动消失，而断网是持续状态。
 */
export function OfflineBanner() {
  const [offline, setOffline] = useState(() => !navigator.onLine);
  const { t } = useTranslation('layout');

  useEffect(() => {
    const on = () => setOffline(false);
    const off = () => setOffline(true);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);

  if (!offline) return null;

  return (
    <div className="page-shell">
      <div role="status" className="offline">
        <WifiOff className="ic" />
        {t('offline')}
      </div>
    </div>
  );
}
