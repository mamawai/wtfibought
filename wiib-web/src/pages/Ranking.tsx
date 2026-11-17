import { useState, useEffect } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, Crown, Medal, Trophy } from 'lucide-react';
import { rankingApi } from '../api';
import { Avatar } from '../components/Avatar';
import { EmptyState } from '../components/EmptyState';
import { BigNum } from '../components/ui/big-num';
import { Pager } from '../components/ui/pager';
import { Seg } from '../components/ui/seg';
import { Skeleton } from '../components/ui/skeleton';
import { useUserStore } from '../stores/userStore';
import { cn, fmtNum } from '../lib/utils';
import type { RankingItem, RankingSort } from '../types';
import './Ranking.css';

const PAGE_SIZE = 20;

type Numeric = number | null | undefined;

const num = (v: Numeric) => Number.isFinite(v) ? v as number : 0;
const fmt = (v: Numeric, decimals = 2) => fmtNum(num(v), decimals);  // 缺失值按 0.00 展示（榜单口径）

/** 带号百分比，涨绿跌红 */
function Pct({ value }: { value: Numeric }) {
  const v = num(value);
  return <span className={v >= 0 ? 'up' : 'dn'}>{v >= 0 ? '+' : ''}{v.toFixed(2)}%</span>;
}

/** 带号盈亏，涨绿跌红 */
function Pnl({ value }: { value: Numeric }) {
  const v = num(value);
  return <span className={v >= 0 ? 'up' : 'dn'}>{v >= 0 ? '+' : ''}{fmt(v)}</span>;
}

/** 名字 + 自己那条的「我」 */
function Who({ item, me, small }: { item: RankingItem; me: boolean; small?: boolean }) {
  const { t } = useTranslation('community');
  return (
    <>
      <Avatar name={item.username} src={item.avatar} small={small} />
      <span className="nm" title={item.username}>{item.username}</span>
      {me && <span className="tag new">{t('ranking.me.badge')}</span>}
    </>
  );
}

const PLACE = [
  { icon: Crown, key: 'ranking.place.first' },
  { icon: Medal, key: 'ranking.place.second' },
  { icon: Medal, key: 'ranking.place.third' },
];

export function Ranking() {
  const { t } = useTranslation('community');
  const navigate = useNavigate();
  const myUserId = useUserStore(s => s.user?.id);
  const [ranking, setRanking] = useState<RankingItem[]>([]);
  // 三态：undefined=这块整条不显示（未登录/请求失败），null=已登录但没上榜（显示提示条），有值=显示横条
  const [myRow, setMyRow] = useState<RankingItem | null | undefined>(undefined);
  const [sort, setSort] = useState<RankingSort>('ASSETS');
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(0);
  const [total, setTotal] = useState(0);
  // loading 由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 ForceOrders）
  const requestKey = `${sort}:${page}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  // 自己那条和前三名都只在第 1 页出现；未登录不请求个人名次。
  const showMine = page === 1 && myUserId != null;

  useEffect(() => {
    let cancelled = false;
    const mine = showMine
      ? rankingApi.me(sort).catch(() => undefined)   // 拉不到就整条不显示，别拿"未上榜"糊弄
      : Promise.resolve(undefined);
    Promise.all([rankingApi.list(sort, page, PAGE_SIZE), mine])
      .then(([res, row]) => {
        if (cancelled) return;
        setRanking(res.records);
        setPages(res.pages);
        setTotal(res.total);
        setMyRow(row);
      })
      .catch(() => { if (!cancelled) { setRanking([]); setMyRow(undefined); } })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, sort, page, showMine]);

  const userPath = (userId: number) => `/user/${userId}`;

  // 换维度必须回到第 1 页：停在第 3 页换榜，看到的是新榜的第 41 名开始，
  // 而前三名只在第 1 页出现，换完一片空
  const switchSort = (next: RankingSort) => {
    setSort(next);
    setPage(1);
  };

  const assets = sort === 'ASSETS';
  // 第 2 页往后的前三条不是全榜前三，不能放进榜首区。
  const top = page === 1 ? ranking.slice(0, 3) : [];
  const rest = page === 1 ? ranking.slice(3) : ranking;

  return (
    <div className="page-shell p-rk">
      <div className="pg-h">
        <div>
          <h1>{t('ranking.title')}</h1>
          <p className="sub">
            <Trans ns="community" i18nKey="ranking.totalUsers" values={{ total }} components={[<b key="total" className="num" />]} />
            {' · '}{t('ranking.updateHint')}
          </p>
        </div>
      </div>

      <div className="sortrow">
        <span>{t('ranking.sortLabel')}</span>
        <Seg value={sort} onChange={switchSort} label={t('ranking.sortLabel')} options={[
          { value: 'ASSETS', label: t('ranking.metric.assets') },
          { value: 'TRADING_PROFIT', label: t('ranking.metric.profit') },
        ]} />
        <span>{t(assets ? 'ranking.hint.assets' : 'ranking.hint.profit')}</span>
      </div>

      {/* 单独提取自己的位置，榜首与明细仍保留完整名次。 */}
      {!loading && showMine && myRow !== undefined && (myRow ? (
        <Link to={userPath(myRow.userId)} className="blk rme" title={t('ranking.rowTitle', { name: myRow.username })}>
          <span className="rno num"><small>#</small>{myRow.rank}</span>
          <span className="who"><Who item={myRow} me /></span>
          <div className="m"><span>{t('ranking.metric.assets')}</span><b className="num">{fmt(myRow.totalAssets)}</b></div>
          <div className="m"><span>{t('ranking.metric.return')}</span><b className="num"><Pct value={myRow.profitPct} /></b></div>
          <div className="m"><span>{t('ranking.metric.profit')}</span><b className="num"><Pnl value={myRow.tradingProfit} /></b></div>
        </Link>
      ) : (
        <div className="blk rme none">
          <span className="tag new">{t('ranking.me.badge')}</span>
          <span>{t('ranking.me.notRanked')}</span>
        </div>
      ))}

      {!loading && top.length > 0 && (
        <section className="sec">
          <div className="sec-h"><h2>{t('ranking.leaders')}</h2></div>
          <div className="top3">
            {top.map((item, i) => {
              const Icon = PLACE[i].icon;
              const up = num(item.tradingProfit) >= 0;
              return (
                <Link key={item.userId} to={userPath(item.userId)} className={cn('pod', i === 0 && 'first')}
                      title={t('ranking.rowTitle', { name: item.username })}>
                  <span className="pl"><Icon className="ic" />{t(PLACE[i].key)}</span>
                  <span className="who"><Who item={item} me={item.userId === myUserId} /></span>
                  {/* 主数字跟着排序维度走，避免按盈利排榜却突出总资产 */}
                  {assets
                    ? <b className="big num"><BigNum value={num(item.totalAssets)} /></b>
                    : <b className={cn('big num', up ? 'up' : 'dn')}>{up ? '+' : ''}{fmt(item.tradingProfit)}</b>}
                  <span className="ln num">
                    <span>{t('ranking.metric.return')} <b><Pct value={item.profitPct} /></b></span>
                    {assets
                      ? <span>{t('ranking.metric.profit')} <b><Pnl value={item.tradingProfit} /></b></span>
                      : <span>{t('ranking.metric.assets')} <b>{fmt(item.totalAssets)}</b></span>}
                    <span>{t('ranking.metric.wallet')} <b>{fmt(item.balanceWallet)} / {fmt(item.gameWallet, 0)}</b></span>
                  </span>
                </Link>
              );
            })}
          </div>
        </section>
      )}

      {(loading || ranking.length === 0 || rest.length > 0) && (
        <section className="sec">
          <div className="sec-h">
            <h2>
              {t('ranking.board')}
              {!loading && rest.length > 0 && <small>{t('ranking.range', { from: rest[0].rank, to: rest[rest.length - 1].rank })}</small>}
            </h2>
            <span className="rh">{t('ranking.rowHint')}</span>
          </div>
          {loading ? (
            <div className="skr">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-11" />)}</div>
          ) : ranking.length === 0 ? (
            <EmptyState icon={<Trophy />} text={t('ranking.empty')} />
          ) : (
            <>
              <div className="tw">
                <table className="tbl hov">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>{t('ranking.metric.user')}</th>
                      <th className="r"><span className={cn(assets && 'on')} title={t('ranking.hint.assets')}>{t('ranking.metric.assets')}</span></th>
                      <th className="r">{t('ranking.metric.return')}</th>
                      <th className="r"><span className={cn(!assets && 'on')} title={t('ranking.hint.profitCol')}>{t('ranking.metric.profit')}</span></th>
                      <th className="r hide" title={t('ranking.hint.walletCol')}>{t('ranking.metric.wallet')}</th>
                      <th className="chev" />
                    </tr>
                  </thead>
                  <tbody>
                    {rest.map(item => {
                      const me = item.userId === myUserId;
                      return (
                        <tr key={item.userId} className={cn(me && 'mine')} title={t('ranking.rowTitle', { name: item.username })}
                            onClick={() => navigate(userPath(item.userId))}>
                          <td className="rkc num">{item.rank}</td>
                          <td><span className="u"><Who item={item} me={me} small /></span></td>
                          <td className={cn('r num', assets && 'b')}>{fmt(item.totalAssets)}</td>
                          <td className="r num"><Pct value={item.profitPct} /></td>
                          <td className={cn('r num', !assets && 'b')}><Pnl value={item.tradingProfit} /></td>
                          <td className="r num mute hide">{fmt(item.balanceWallet)} / {fmt(item.gameWallet, 0)}</td>
                          <td className="chev"><ChevronRight className="ic" /></td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <Pager page={page} pages={pages} onChange={setPage} />
            </>
          )}
        </section>
      )}
    </div>
  );
}
