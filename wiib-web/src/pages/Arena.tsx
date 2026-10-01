import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { RefreshCw } from 'lucide-react';
import { traderApi } from '../api';
import { STATUS_META } from '../components/arena/traderStatus';
import { useStagger } from '../hooks/useStagger';
import { cn, fmtNum } from '../lib/utils';
import type { TraderPublicView } from '../types';

const REFRESH_MS = 60_000;

/** 币种列表 BTCUSDT,ETHUSDT → BTC / ETH */
const symbolList = (symbols: string) => symbols.split(',').map(s => s.replace('USDT', '')).join(' / ');

/**
 * AI Trader 竞技场：全部 trader 按收益率排行。
 * 卖点是"看 AI 怎么想、怎么下注"——排行只是记分牌，点进详情看决策时间线才是正餐。
 */
export function Arena() {
  const { t } = useTranslation(['ai', 'common']);
  const [traders, setTraders] = useState<TraderPublicView[]>([]);
  const [loading, setLoading] = useState(true);
  // 最近一次拉取失败了。失败保留上一份榜单，不能当成"一个 trader 都没有"
  const [failed, setFailed] = useState(false);
  const boardRef = useStagger<HTMLElement>();

  const load = useCallback(() => {
    traderApi.arena()
      .then(list => { setTraders(list); setFailed(false); })
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  // 手里一份榜单都没有又拉失败了：有没有 trader、有没有自己的都不知道
  const unknown = failed && traders.length === 0;
  const empty = traders.length === 0 && !loading && !unknown;
  const hasMine = traders.some(tr => tr.mine);
  // 表头与数据行同一套栅格：窄屏收成三列，模型/节奏/权益/局四列直接藏起来
  const row = 'grid grid-cols-[60px_1fr_1fr] xl:grid-cols-[90px_1.4fr_1fr_1.3fr_1fr_1fr_80px_1.2fr] gap-5';
  const hideNarrow = 'hidden xl:block';

  return (
    <div className="wrap">
      <div className="page-h flex-wrap">
        <h1>{t('arena.title')}</h1>
        <div className="r">
          <button type="button" className="btn sm" onClick={load}>
            <RefreshCw className={cn('ic', loading && 'animate-spin')} />{t('common:refresh')}
          </button>
          <Link className="btn sm fill" to="/my-trader">{t('arena.myTrader')}</Link>
        </div>
      </div>

      {unknown ? (
        <section className="sec">
          <div className="sec-h">
            <h2>{t('common:loadFailed')}</h2>
          </div>
        </section>
      ) : empty ? (
        <section className="sec">
          <div className="sec-h">
            <h2>{t('arena.empty')}</h2>
            <span>{t('arena.emptyHint')}</span>
          </div>
          <Link className="btn orange" to="/my-trader">{t('arena.create')}</Link>
        </section>
      ) : (
        <section ref={boardRef} className="mt-7 border-t-2 border-foreground">
          <div className={cn(row, 'py-3 text-[12.5px] mute font-semibold border-b border-foreground')}>
            <span>#</span>
            <span>Trader</span>
            <span className={hideNarrow}>{t('arena.model')}</span>
            <span className={hideNarrow}>{t('arena.tempo')}</span>
            <span className={cn(hideNarrow, 'text-right')}>{t('term.equity')}</span>
            <span className="text-right">{t('arena.return')}</span>
            <span className={cn(hideNarrow, 'text-right')}>{t('arena.round')}</span>
            <span>{t('arena.status')}</span>
          </div>

          {traders.map((tr, i) => {
            const st = STATUS_META[tr.status] ?? STATUS_META.PAUSED;
            return (
              <Link key={tr.id} to={`/arena/${tr.id}`} className={cn(row, 'hov items-center py-[22px] border-b border-border')}>
                <span className={cn('num cond text-[56px] font-bold leading-none', i < 3 ? 'text-foreground' : 'mute')}>{i + 1}</span>
                <span className="flex flex-col gap-1 min-w-0">
                  <b className="flex items-center gap-2.5 text-[22px] font-bold [font-stretch:90%]">
                    <span className="truncate">{tr.name}</span>
                    {tr.mine && <span className="chip fill orange">{t('arena.mine')}</span>}
                  </b>
                  <span className="text-[12.5px] mute">R{tr.roundNo} · {t(st.shortKey)}</span>
                </span>
                <span className={cn(hideNarrow, 'text-[14px] truncate', !tr.model && 'mute')}>{tr.model ?? t('term.noModel')}</span>
                <span className={cn(hideNarrow, 'text-[13.5px] mute')}>
                  <b className="text-foreground font-semibold">{tr.intervalCode}</b>
                  {tr.wakeWindow && ` · ${tr.wakeWindow}`} · {symbolList(tr.symbols)}
                </span>
                <span className={cn(hideNarrow, 'num text-right text-[18px] font-semibold [font-stretch:85%]')}>
                  {fmtNum(tr.equity)}
                  <small className="block text-[12px] mute font-medium [font-stretch:100%]">{t('arena.initial')}</small>
                </span>
                <span className={cn('num cond text-right text-[40px] font-bold leading-none', tr.pnlPct >= 0 ? 'up' : 'dn')}>
                  {tr.pnlPct >= 0 ? '+' : ''}{tr.pnlPct.toFixed(2)}%
                </span>
                <span className={cn(hideNarrow, 'num text-right text-[14px] mute')}>R{tr.roundNo}</span>
                <span className="flex flex-col gap-1.5 items-start">
                  <span className={cn('chip', st.chip)}>{t(st.labelKey)}</span>
                  {tr.pausedReason && <span className="text-[12px] mute leading-[1.4]">{tr.pausedReason}</span>}
                </span>
              </Link>
            );
          })}
        </section>
      )}

      {/* 自己还没有 trader 才出这一节：三步说明 + 创建入口。榜单还没拿到（首拉中/拉失败）时不知道有没有，不出 */}
      {!hasMine && !loading && !unknown && (
        <section className="sec">
          <div className="sec-h">
            <h2>{t('arena.noTraderTitle')}</h2>
          </div>
          <div className="grid grid-cols-1 xl:grid-cols-3 gap-10">
            {([1, 2, 3] as const).map(n => (
              <div key={n} className="border-t border-foreground pt-3.5">
                <b className="block text-[20px] font-bold [font-stretch:90%] mb-1.5">{t(`arena.step${n}Title`)}</b>
                <p className="m-0 text-[14px] mute leading-[1.6]">{t(`arena.step${n}`)}</p>
              </div>
            ))}
          </div>
          <div className="flex items-center gap-[18px] mt-7 flex-wrap">
            <Link className="btn orange" to="/my-trader">{t('arena.create')}</Link>
          </div>
        </section>
      )}
    </div>
  );
}
