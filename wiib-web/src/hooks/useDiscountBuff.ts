import { useEffect, useState } from 'react';
import { buffApi } from '../api';
import type { UserBuff } from '../types';

/**
 * 今日未使用的折扣券（StockDetail/Coin 共用）。
 * enabled=false（如未登录）时视图层直接归 null，不触发请求；
 * refreshKey 变化时重新拉取；下单用掉后调用返回的 setter 手动清空。
 */
export function useDiscountBuff(enabled: boolean, refreshKey?: unknown) {
  const [buff, setBuff] = useState<UserBuff | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    buffApi.status().then(s => {
      if (cancelled) return;
      const b = s.todayBuff;
      setBuff(b && b.buffType.startsWith('DISCOUNT_') && !b.isUsed ? b : null);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [enabled, refreshKey]);

  return [enabled ? buff : null, setBuff] as const;
}
