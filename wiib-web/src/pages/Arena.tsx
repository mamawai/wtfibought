import { useCallback, useEffect, useState, type MouseEvent } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bot, CloudOff, RefreshCw } from 'lucide-react';
import { traderApi } from '../api';
import { PnlPct } from '../components/arena/PnlPct';
import { STATUS_META } from '../components/arena/traderStatus';
import { cn, fmtNum, spinIcon } from '../lib/utils';
import type { TraderPublicView } from '../types';
import './Arena.css';

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

  const refresh = (e: MouseEvent<HTMLButtonElement>) => { spinIcon(e); load(); };

  // 手里一份榜单都没有又拉失败了：有没有 trader、有没有自己的都不知道
  const unknown = failed && traders.length === 0;
  const empty = traders.length === 0 && !loading && !unknown;

  let board;
  if (loading && traders.length === 0) {
    board = Array.from({ length: 5 }, (_, i) => (
      <div key={i} className="skr">
        <i className="sk" style={{ width: 28, height: 30 }} /><i className="sk t" style={{ width: '46%' }} /><i className="sk t" /><i className="sk" style={{ height: 30 }} />
      </div>
    ));
  } else if (unknown) {
    board = (
      <div className="empty">
        <span className="ib0"><CloudOff className="ic" /></span>
        <b>{t('common:loadFailed')}</b>
        <button type="button" className="btn" onClick={refresh}><RefreshCw className="ic" />{t('common:refresh')}</button>
      </div>
    );
  } else if (empty) {
    board = (
      <div className="empty">
        <span className="ib0"><Bot className="ic" /></span>
        <b>{t('arena.empty')}</b>
        <span>{t('arena.emptyHint')}</span>
        <Link className="btn ink" to="/my-trader/new">{t('arena.create')}</Link>
      </div>
    );
  } else {
    board = (
      <>
        <div className="hd">
          <span>#</span>
          <span>Trader</span>
          <span>{t('arena.model')}</span>
          <span>{t('arena.tempo')}</span>
          <span className="r">{t('term.equity')}</span>
          <span className="r k">{t('arena.return')}</span>
          <span>{t('arena.status')}</span>
        </div>
        {traders.map((tr, i) => <ArenaRow key={tr.id} tr={tr} rank={i + 1} />)}
      </>
    );
  }

  return (
    <div className="page-shell p-ar">
      <div className="pg-h">
        <h1>{t('arena.title')}</h1>
        <div className="r">
          <button type="button" className="ib" onClick={refresh} aria-label={t('common:refresh')} title={t('common:refresh')}>
            <RefreshCw className="ic" />
          </button>
          <Link className="btn ink" to="/my-trader">{t('arena.myTrader')}</Link>
        </div>
      </div>

      <div className="board">{board}</div>

      {/* 榜单拿到了、里面没有自己的才出三步说明；空榜自己带创建入口，不重复 */}
      {traders.length > 0 && !traders.some(tr => tr.mine) && (
        <section className="sec">
          <div className="sec-h"><h2>{t('arena.noTraderTitle')}</h2></div>
          <div className="steps">
            {([1, 2, 3] as const).map(n => (
              <div key={n}>
                <i className="nf">{n}</i>
                <b>{t(`arena.step${n}Title`)}</b>
                <p>{t(`arena.step${n}`)}</p>
              </div>
            ))}
          </div>
          <div className="cta-row"><Link className="btn ink lg" to="/my-trader/new">{t('arena.create')}</Link></div>
        </section>
      )}
    </div>
  );
}

/** 榜单一行：名次 / 名字 / 模型 / 节奏 / 权益 / 收益率 / 状态，点进它的工作台 */
function ArenaRow({ tr, rank }: { tr: TraderPublicView; rank: number }) {
  const { t } = useTranslation('ai');
  const st = STATUS_META[tr.status] ?? STATUS_META.PAUSED;
  const up = tr.pnlPct >= 0;
  return (
    <Link to={`/arena/${tr.id}`} className={cn('arow', rank <= 3 && 'top3', tr.status === 'LIQUIDATED' && 'out')}>
      <span className="rk nf">{rank}</span>
      <span className="nm">
        <b><span>{tr.name}</span>{tr.mine && <span className="tag new">{t('arena.mine')}</span>}</b>
        <small><i className={st.tone} />R{tr.roundNo} · {t(st.shortKey)}</small>
      </span>
      <span className={cn('md', !tr.model && 'mute')}>{tr.model ?? t('term.noModel')}</span>
      <span className="tp"><b>{tr.intervalCode}</b>{tr.wakeWindow && ` · ${tr.wakeWindow}`} · {symbolList(tr.symbols)}</span>
      <span className="eq nf"><b>{fmtNum(tr.equity)}</b><small>{t('arena.initial')}</small></span>
      <span className={cn('rt nf', up ? 'up' : 'dn')}>
        <b><PnlPct value={tr.pnlPct} /></b>
        {/* 小刻度：中点是初始资金，往右赚往左亏，±25% 到头 */}
        <span className="mt"><i className={up ? 'p' : 'n'} style={{ width: `${Math.min(50, Math.abs(tr.pnlPct) * 2)}%` }} /></span>
      </span>
      <span className="st">
        <span className={cn('tag', st.tone)}><i className={cn(tr.status === 'RUNNING' && 'live')} />{t(st.labelKey)}</span>
        {tr.pausedReason && <small>{tr.pausedReason}</small>}
      </span>
    </Link>
  );
}
