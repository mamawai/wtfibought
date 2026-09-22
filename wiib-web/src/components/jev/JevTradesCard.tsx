import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown } from 'lucide-react';
import { cn, fmtNum, fmtSignedUsd, toCents } from '../../lib/utils';
import { fmtWindow } from '../../hooks/usePredictionMarket';
import { BrierTable, CalibrationRow } from './JevScoreboard';
import type { JevBet, JevPredictionDecisionView, JevPredictionOverview } from '../../types';

/** 默认露出最近这么多笔，其余点开 */
const FOLD = 10;

const TH = 'text-[12px] font-semibold mute text-left px-2 pb-2 border-b border-foreground first:pl-0 last:pr-0';
const TD = 'px-2 py-3 border-b border-border text-[14px] align-top first:pl-0 last:pr-0';

/** 注单状态的芯片配色：方向那格已经填色了，这格只描边 */
const STATUS_CHIP: Record<string, string> = { ACTIVE: '', WON: 'up', LOST: 'dn', SOLD: 'mute', DRAW: 'mute' };

/** 格子里的副行：小一号灰字压在主值下面 */
function Sub({ children }: { children: ReactNode }) {
  return <span className="block text-[12px] mute mt-0.5 whitespace-nowrap">{children}</span>;
}

/**
 * 左下：Jev 的注单（四列＋副行，手机也放得下：回合/何时买、方向/份数均价、结果、盈亏/成本），
 * 底下折叠的记分明细（三列 Brier 按检查点 + 校准），每块先用大白话说清楚怎么看。
 */
export function JevTradesCard({ overview, bets, feed }: {
  overview: JevPredictionOverview | null;
  bets: JevBet[];
  feed: JevPredictionDecisionView[];
}) {
  const { t } = useTranslation(['community']);
  const [allBets, setAllBets] = useState(false);
  const [detailsOpen, setDetailsOpen] = useState(false);
  // 注单 → 在哪个检查点开的仓；太早的注单对应的决策已经不在流里，就不标
  const openedAt = useMemo(() => {
    const m = new Map<number, string>();
    for (const d of feed) {
      if (d.betId != null && (d.action === 'BUY_UP' || d.action === 'BUY_DOWN')) {
        m.set(d.betId, d.checkpoint);
      }
    }
    return m;
  }, [feed]);

  const shown = allBets ? bets : bets.slice(0, FOLD);
  const stats = overview?.stats;

  return (
    <div>
      <div className="sec-h mb-4">
        <h2>{t('prediction.jev.trades')}{bets.length > 0 && <small>{t('prediction.jev.tradesSub', { n: bets.length })}</small>}</h2>
      </div>

      {bets.length === 0 ? (
        <div className="py-6 text-[14px] mute">{t('prediction.jev.noBets')}</div>
      ) : (
        <table className="num w-full border-collapse">
          <thead>
            <tr>
              <th className={TH}>{t('prediction.jev.colWindow')}</th>
              <th className={TH}>{t('prediction.jev.colBought')}</th>
              <th className={TH}>{t('prediction.jev.colResult')}</th>
              <th className={cn(TH, 'text-right')}>{t('prediction.jev.colPnl')}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map(b => {
              const at = openedAt.get(b.id);
              return (
                <tr key={b.id}>
                  <td className={TD}>
                    <span className="whitespace-nowrap">{fmtWindow(b.windowStart)}</span>
                    {at && <Sub>{t('prediction.jev.boughtAt', { s: at.slice(1) })}</Sub>}
                  </td>
                  <td className={TD}>
                    <span className={cn('chip fill', b.side === 'UP' ? 'up' : 'dn')}>{b.side}</span>
                    <Sub>{t('prediction.jev.sharesAt', { n: fmtNum(b.contracts), p: toCents(b.avgPrice) })}</Sub>
                  </td>
                  <td className={TD}>
                    <span className={cn('chip', STATUS_CHIP[b.status])}>
                      {b.status === 'ACTIVE' && <i className="dot pulse" />}{t(`prediction.jev.status.${b.status}`)}
                    </span>
                  </td>
                  <td className={cn(TD, 'text-right')}>
                    {b.pnl != null ? (
                      <b className={cn('font-bold', b.pnl > 0 ? 'up' : b.pnl < 0 ? 'dn' : 'mute')}>{fmtSignedUsd(b.pnl)}</b>
                    ) : b.currentValue != null ? (
                      <span className="mute whitespace-nowrap">{t('prediction.jev.worth', { n: fmtNum(b.currentValue) })}</span>
                    ) : <span className="mute">—</span>}
                    <Sub>{t('prediction.jev.cost', { n: fmtNum(b.cost) })}</Sub>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {bets.length > FOLD && (
        <button type="button" onClick={() => setAllBets(v => !v)} className="btn sm w-full mt-3">
          {allBets ? t('prediction.jev.showLess') : t('prediction.jev.showAllBets', { n: bets.length })}
        </button>
      )}

      {/* 记分明细：给想深究的人看，默认收起 */}
      {overview && (
        <div className="mt-8">
          <button type="button" onClick={() => setDetailsOpen(o => !o)} aria-expanded={detailsOpen}
                  className="w-full text-left py-3.5 border-y border-border flex items-center gap-3 text-[14px]">
            <b className="font-extrabold whitespace-nowrap">{t('prediction.jev.scoreDetails')}</b>
            <span className="mute flex-1 min-w-0 truncate">{t('prediction.jev.scoreDetailsSub', { n: stats?.scored ?? 0 })}</span>
            <ChevronDown className={cn('w-4 h-4 mute shrink-0 transition-transform', detailsOpen && 'rotate-180')} />
          </button>
          {detailsOpen && (
            <div className="pt-4 space-y-8">
              <div>
                <h3 className="text-[14px] font-bold">{t('prediction.jev.brier')}</h3>
                <p className="text-[13.5px] leading-[1.7] mute mt-1">{t('prediction.jev.brierExplain')}</p>
                <p className="text-[13.5px] leading-[1.7] mute mt-1 mb-3">{t('prediction.jev.brierHow')}</p>
                <BrierTable rows={overview.brierByCheckpoint} total={stats} />
              </div>
              {overview.calibration.length > 0 && (
                <div>
                  <h3 className="text-[14px] font-bold">{t('prediction.jev.calibration')}</h3>
                  <p className="text-[13.5px] leading-[1.7] mute mt-1 mb-4">{t('prediction.jev.calibrationExplain')}</p>
                  <div className="space-y-3.5">
                    {overview.calibration.map(b => <CalibrationRow key={b.bucket} b={b} />)}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
