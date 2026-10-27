import { useCallback, useEffect, useState } from 'react';
import { alpha, type Busy } from './api';
import { usePoll } from './poll';

/** 各动作现在为什么做不了：在这一页时先问一次、之后每 5 秒一次；问不到就当没挡着，点了接口照样会说为什么 */
export function useBusy(active: boolean): Busy | null {
  const [busy, setBusy] = useState<Busy | null>(null);
  const load = useCallback(() => alpha.busy().then(setBusy).catch(() => undefined), []);
  useEffect(() => {
    if (active) void load();
  }, [active, load]);
  usePoll(active, load, 5000);
  return busy;
}
