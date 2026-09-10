import type { LedgerBizTypeOption, LedgerEntry, RankingItem } from '../src/types';

/** 预览数据也遵守真实接口形状，避免页面被兜底的 null 弄白屏。 */
export const LEDGER_BIZ_TYPES: LedgerBizTypeOption[] = [
  { name: 'FUTURES_OPEN_FEE', label: '合约开仓手续费', group: '合约' },
  { name: 'FUTURES_CLOSE_SETTLE', label: '合约平仓结算', group: '合约' },
  { name: 'FUTURES_CLOSE_FEE', label: '合约平仓手续费', group: '合约' },
  { name: 'FUNDING_FEE_PAY', label: '资金费支出', group: '合约' },
  { name: 'SPOT_BUY', label: '现货买入', group: '现货' },
  { name: 'SPOT_SETTLE', label: '现货卖出到账', group: '现货' },
  { name: 'INITIAL_GRANT', label: '初始资金', group: '其他' },
];

const ledgerSamples: Pick<LedgerEntry, 'bizType' | 'delta' | 'symbol' | 'fee'>[] = [
  { bizType: 'FUTURES_OPEN_FEE', delta: -6.8, symbol: 'BTCUSDT', fee: null },
  { bizType: 'FUNDING_FEE_PAY', delta: -2.1, symbol: 'BTCUSDT', fee: null },
  { bizType: 'FUTURES_CLOSE_SETTLE', delta: 128.46, symbol: 'ETHUSDT', fee: null },
  { bizType: 'SPOT_BUY', delta: -501, symbol: 'SOLUSDT', fee: 1 },
  { bizType: 'SPOT_SETTLE', delta: 545.4, symbol: 'SOLUSDT', fee: 1.09 },
  { bizType: 'FUTURES_CLOSE_FEE', delta: -3.25, symbol: 'ETHUSDT', fee: null },
];

/** ID 和时刻只在服务启动时生成，翻页期间保持稳定；从当前余额倒推每笔余额。 */
function makeLedger(): LedgerEntry[] {
  const anchor = Date.now();
  let balanceAfter = 8799.46;
  return Array.from({ length: 48 }, (_, index) => {
    const sample = ledgerSamples[index % ledgerSamples.length];
    const entry: LedgerEntry = {
      ...sample,
      id: 5048 - index,
      userId: 9001,
      wallet: 'BALANCE',
      bizTypeLabel: LEDGER_BIZ_TYPES.find(option => option.name === sample.bizType)!.label,
      balanceAfter,
      refType: null,
      refId: null,
      remark: null,
      createdAt: new Date(anchor - index * 30 * 60_000).toISOString(),
    };
    balanceAfter = Math.round((balanceAfter - sample.delta) * 100) / 100;
    return entry;
  });
}

export const LEDGER_ENTRIES = makeLedger();

const names = ['Northstar', 'Orion', 'Atlas', 'River', 'Juniper', 'Lumen'];
const accounts: RankingItem[] = Array.from({ length: 24 }, (_, index) => {
  const profit = Math.round((24 - index) * 137.72 * 100) / 100;
  if (index === 6) {
    return {
      rank: 0, userId: 9001, username: 'mock', totalAssets: 11842.36,
      profitPct: 18.42, tradingProfit: 1842.36, balanceWallet: 8799.46, gameWallet: 0,
    };
  }
  return {
    rank: 0,
    userId: 9100 + index,
    username: names[index] ?? 'Trader ' + (index + 1),
    totalAssets: 10000 + profit,
    profitPct: profit / 100,
    tradingProfit: index % 5 === 0 ? -83.24 : Math.round(profit * 0.85 * 100) / 100,
    balanceWallet: Math.round((7000 + profit) * 100) / 100,
    gameWallet: 250,
  };
});

export function rankingRows(sort: string | null): RankingItem[] {
  const metric = sort === 'TRADING_PROFIT' ? 'tradingProfit' : 'totalAssets';
  return [...accounts].sort((a, b) => b[metric] - a[metric])
    .map((account, index) => ({ ...account, rank: index + 1 }));
}

