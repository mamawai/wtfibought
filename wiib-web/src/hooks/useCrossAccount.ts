import { useEffect, useState } from 'react';
import { futuresApi } from '../api';
import { useUserStore } from '../stores/userStore';
import type { FuturesCrossAccount } from '../types';

/**
 * 全仓账户快照 + 余额钱包能花出去的钱。
 * <p>spendable = min(全仓可用, 余额)，不小于 0，同后端 CrossAccount.maxOutflow：现货/bStock 买入、逐仓开仓、
 * 追加保证金都先过全仓可用这道闸、再扣余额。没有全仓仓位和全仓挂单时可用就等于余额；快照没回来先按余额算。
 * <p>每次 fetchUser（成交、撤单、仓位变动后都会调）换了 user 对象就重拉快照。enabled=false 不发请求。
 */
export function useCrossAccount(enabled = true) {
  const user = useUserStore(s => s.user);
  const [acct, setAcct] = useState<FuturesCrossAccount | null>(null);

  useEffect(() => {
    if (!enabled || !user) return;
    let alive = true;
    futuresApi.crossAccount()
      .then(a => { if (alive) setAcct(a); })
      .catch(() => { if (alive) setAcct(null); });
    return () => { alive = false; };
  }, [enabled, user]);

  const spendable = user ? Math.max(0, Math.min(acct?.available ?? user.balance, user.balance)) : null;
  return { acct, spendable };
}
