import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';
import { jevPredictionApi } from '../api';
import { cn, fmtDateTime, fmtNum, fmtSignedUsd, toCents } from '../lib/utils';
import { useCountUp } from '../hooks/useCountUp';
import { usePredictionMarket } from '../hooks/usePredictionMarket';
import { PredictionHero } from '../components/PredictionHero';
import { ScoreStrip } from '../components/arena/ScoreStrip';
import { JevTradesCard } from '../components/jev/JevTradesCard';
import { JevFeed } from '../components/jev/JevFeed';
import type { JevBet, JevPredictionDecisionView, JevPredictionOverview } from '../types';

/** 检查点 15 秒一个，5 秒刷一次流 */
const POLL_MS = 5_000;
/** 仪表条数字后的小字：手机上两格一行放不下，另起一行 */
const STRIP_SMALL = 'whitespace-nowrap max-sm:block max-sm:ml-0 max-sm:mt-1';

/**
 * Jev 预测员页：记分头（名字 / 状态 / 局次 / 一句话说明 / 本局盈亏）+ 四格仪表条 →
 * 左 Polymarket 5 分钟盘的实时走势（与预测页同一张头卡）+ Jev 的注单与记分，右 Jev 每个检查点看到什么、怎么决定。
 * 注单、决策和记分按局看，默认当前局；行情一直是实时的。只读，登录即可看；整页一起滚，窄屏两栏堆成一列。
 */
export function JevPrediction() {
  const { t } = useTranslation(['community']);
  const [overview, setOverview] = useState<JevPredictionOverview | null>(null);
  const [feed, setFeed] = useState<JevPredictionDecisionView[]>([]);
  const [bets, setBets] = useState<JevBet[]>([]);
  // 在看哪一局，undefined = 当前局
  const [viewRun, setViewRun] = useState<number | undefined>(undefined);

  const load = useCallback(() => {
    Promise.all([jevPredictionApi.overview(viewRun), jevPredictionApi.feed(51, viewRun), jevPredictionApi.bets(30, viewRun)])
      .then(([o, f, b]) => { setOverview(o); setFeed(f); setBets(b); })
      .catch(() => { /* 取不到就保留上一份 */ });
  }, [viewRun]);

  // 旧回合结算推送到了立刻刷一次，不等下一个轮询
  const market = usePredictionMarket(load);

  useEffect(() => {
    load();
    const iv = setInterval(load, POLL_MS);
    return () => clearInterval(iv);
  }, [load]);

  const { upAsk, downAsk } = market;
  const stats = overview?.stats;
  const pnl = stats?.pnl ?? 0;
  const runs = overview?.runs ?? [];
  const currentRunNo = runs[0]?.runNo;
  const viewing = overview?.run;
  const archived = viewing != null && viewing.runNo !== currentRunNo;
  // 大数滚动直接写 DOM：元素得一直挂着，没数据时 invisible 占位，不然盈亏恰好是 0 时不会重画
  const pnlRef = useCountUp<HTMLElement>(pnl, fmtSignedUsd);
  const winRate = stats && stats.settledBets > 0 ? Math.round(stats.wins / stats.settledBets * 100) : null;

  return (
    <div className="wrap">
      {/* 记分头：左身份 + 状态 + 局次 + 一句话说明，右本局盈亏 */}
      <div className="grid grid-cols-1 xl:grid-cols-[1fr_auto] gap-6 xl:gap-8 items-end pt-8">
        <div>
          <Link to="/prediction" className="inline-flex items-center gap-1 text-[13px] mute mb-2.5 hover:text-foreground transition-colors">
            <ChevronLeft className="w-3.5 h-3.5" />{t('prediction.jev.backToMarket')}
          </Link>
          <div className="flex items-baseline gap-x-4 gap-y-2 flex-wrap">
            <b className="cond text-[44px] md:text-[56px] font-bold leading-none">{t('prediction.jev.title')}</b>
            {overview && (archived
              ? <span className="chip wn">{t('prediction.jev.archived')}</span>
              : overview.enabled
                ? <span className="chip up"><i className="dot pulse" />{t('prediction.jev.running')}</span>
                : <span className="chip mute">{t('prediction.jev.off')}</span>)}
            {overview && <span className="text-[14px] mute">{overview.model} · {t('prediction.jev.market')}</span>}
          </div>
          {/* 局次：一局一个账户，重新开局后旧局留档；只有一局时不出切换 */}
          {viewing && (
            <div className="flex items-center gap-2.5 flex-wrap mt-3 text-[13px] mute">
              {runs.length > 1 ? (
                <div className="seg">
                  {[...runs].reverse().map(r => (
                    <button key={r.runNo} type="button" className={cn('num', r.runNo === viewing.runNo && 'on')}
                            onClick={() => setViewRun(r.runNo === currentRunNo ? undefined : r.runNo)}>R{r.runNo}</button>
                  ))}
                </div>
              ) : <b className="num text-foreground">R{viewing.runNo}</b>}
              {viewing.label && <span>{viewing.label}</span>}
              <span className="num">{t('prediction.jev.runSince', { at: fmtDateTime(viewing.startedAt) })}</span>
            </div>
          )}
          <p className="mt-3 max-w-[48rem] text-[14px] leading-relaxed mute">{t('prediction.jev.intro')}</p>
          {archived && (
            <div className="mt-3 border-l-4 border-warning pl-3 py-1 text-[13px] text-warning">
              {t('prediction.jev.archivedBanner', { n: viewing.runNo })}
            </div>
          )}
        </div>
        <div className={cn('num xl:text-right', !stats && 'invisible')}>
          <b ref={pnlRef} className={cn('cond block text-[56px] md:text-[72px] font-bold leading-none', pnl > 0 ? 'up' : pnl < 0 ? 'dn' : '')} />
          <div className="mt-2 text-[13px] mute">{t('prediction.jev.pnlLabel')}</div>
        </div>
      </div>

      {/* 仪表条：四格，窄屏两格两行（每行末格不要右边线）；手机上小字另起一行 */}
      <ScoreStrip className="grid-cols-2 xl:grid-cols-4 max-xl:[&>div:nth-child(2n)]:border-r-0" cells={[
        { label: t('prediction.jev.windows'), value: stats ? stats.windows : '—' },
        { label: t('prediction.jev.bets'), value: stats ? t('prediction.jev.betsN', { count: stats.bets }) : '—' },
        { label: t('prediction.jev.winRate'),
          value: stats && winRate != null
            ? <>{winRate}%<small className={STRIP_SMALL}>{t('prediction.jev.winDetail', { wins: stats.wins, settled: stats.settledBets })}</small></>
            : '—' },
        // sim 连不上时余额是空的
        { label: t('prediction.jev.balance'),
          value: overview?.gameBalance != null
            ? <>${fmtNum(overview.gameBalance)}<small className={STRIP_SMALL}>{t('prediction.jev.initial', { n: fmtNum(overview.initialGameBalance, 0) })}</small></>
            : '—' },
      ]} />

      <div className="grid grid-cols-1 xl:grid-cols-12 gap-10 mt-10">
        {/* 左：行情 + Jev 的注单 */}
        <div className="xl:col-span-7 min-w-0 space-y-11">
          <PredictionHero market={market} extra={upAsk != null && downAsk != null && (
            <span className="hidden sm:inline num text-[12px] font-semibold">
              <span className="up">UP {toCents(upAsk)}¢</span>
              <span className="mx-1 mute">·</span>
              <span className="dn">DOWN {toCents(downAsk)}¢</span>
            </span>
          )} />
          <JevTradesCard overview={overview} bets={bets} feed={feed} />
        </div>

        {/* 右：Jev 在看什么、怎么决定 */}
        <div className="xl:col-span-5 min-w-0">
          <JevFeed overview={overview} feed={feed} currentWindowStart={market.windowStart} />
        </div>
      </div>
    </div>
  );
}
