import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Bot, ChevronLeft, Lock, Receipt, Trophy } from 'lucide-react';
import { rankingApi } from '../api';
import { Avatar } from '../components/Avatar';
import { EmptyState } from '../components/EmptyState';
import { HelpTip } from '../components/HelpTip';
import { PositionHistoryList } from '../components/PositionHistoryList';
import { SideTag, SymIcon } from '../components/TradeBits';
import { BigNum } from '../components/ui/big-num';
import { Pager } from '../components/ui/pager';
import { Skeleton } from '../components/ui/skeleton';
import { Tabs } from '../components/ui/tabs';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDateTime, fmtNum, fmtSigned, fmtSignedPct } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { tradeHref, tradeSymbolName } from '../lib/orderSide';
import type {
  PageResult, PositionHistoryItem, ProfilePosition, PublicTrade, UserProfile as UserProfileData,
} from '../types';
import './records.css';
import './UserProfile.css';

const TRADE_PAGE_SIZE = 20;

const EMPTY_TRADES: PageResult<PublicTrade> = {
  records: [], total: 0, size: TRADE_PAGE_SIZE, current: 1, pages: 0,
};

const EMPTY_POSITIONS: PageResult<PositionHistoryItem> = {
  records: [], total: 0, size: TRADE_PAGE_SIZE, current: 1, pages: 0,
};

/** 一笔持仓：点了去那个标的的交易页 */
function PositionRow({ p, onOpen }: { p: ProfilePosition; onOpen: () => void }) {
  const { t } = useTranslation('account');
  return (
    <button type="button" className="hr" onClick={onOpen}>
      <SymIcon symbol={p.symbol} />
      <div className="nm">
        <b>
          {tradeSymbolName(p.symbol)}
          <small className="num">{fmtNum(p.quantity, 4)}</small>
          {p.side != null && (
            <>
              <span className={cn('tag sm', p.side === 'LONG' ? 'g' : 'r')}>
                {p.side === 'LONG' ? t('profile.long') : t('profile.short')} {p.leverage}x
              </span>
              <span className="tag sm">{p.marginMode === 'CROSS' ? t('profile.cross') : t('profile.isolated')}</span>
            </>
          )}
        </b>
        <span className="num">
          {t('profile.avgPrice', { price: formatCoinPrice(p.symbol, p.entryPrice) })}
          {p.currentPrice != null && <> · {t('profile.lastPrice', { price: formatCoinPrice(p.symbol, p.currentPrice) })}</>}
        </span>
      </div>
      <div className="vr num">
        <b>{p.value == null ? '—' : `₮${fmtNum(p.value)}`}</b>
        {/* 缺价时后端给 null，显示 "—"。填 0 会被读成"这仓一分不赚"，那是另一回事 */}
        {p.profit == null ? <span className="mute">—</span> : <span className={p.profit >= 0 ? 'up' : 'dn'}>{fmtSigned(p.profit)}</span>}
      </div>
    </button>
  );
}

function PositionSection({ title, positions, onOpen }: {
  title: string;
  positions: ProfilePosition[];
  onOpen: (symbol: string) => void;
}) {
  const { t } = useTranslation('account');
  return (
    <section className="sec">
      <div className="sec-h"><h2>{title}<small>{t('profile.count', { count: positions.length })}</small></h2></div>
      {positions.length > 0
        ? positions.map(p => <PositionRow key={`${p.symbol}-${p.side ?? 'SPOT'}`} p={p} onOpen={() => onOpen(p.symbol)} />)
        : <div className="empty left"><b>{t('profile.noPositions')}</b></div>}
    </section>
  );
}

export function UserProfile() {
  const { t } = useTranslation(['account', 'community']);
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const userId = Number(id);
  const myUserId = useUserStore(s => s.user?.id);

  const [profile, setProfile] = useState<UserProfileData | null>(null);
  // 对方关了公开开关 → 后端 403，这里要给一句人话，而不是空白页
  const [denied, setDenied] = useState(false);

  // 历史两个视角：仓位历史一行是一笔完整仓位（开仓到全部平掉），成交明细一行是一笔委托。
  // 默认落在仓位历史——「这人赚没赚钱」比「这人几点下了什么单」更该先回答
  const [tab, setTab] = useState<'positions' | 'trades'>('positions');

  const [positions, setPositions] = useState<PageResult<PositionHistoryItem>>(EMPTY_POSITIONS);
  const [positionPage, setPositionPage] = useState(1);
  const [trades, setTrades] = useState<PageResult<PublicTrade>>(EMPTY_TRADES);
  const [tradePage, setTradePage] = useState(1);

  // 三处 loading 都由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 ForceOrders）
  const [loadedUserId, setLoadedUserId] = useState<number | null>(null);
  const loading = loadedUserId !== userId;

  const positionKey = `${userId}:${positionPage}`;
  const [loadedPositionKey, setLoadedPositionKey] = useState<string | null>(null);
  const positionsLoading = loadedPositionKey !== positionKey;

  const tradeKey = `${userId}:${tradePage}`;
  const [loadedTradeKey, setLoadedTradeKey] = useState<string | null>(null);
  const tradesLoading = loadedTradeKey !== tradeKey;

  useEffect(() => {
    if (!Number.isFinite(userId)) return;
    let cancelled = false;
    rankingApi.userProfile(userId)
      .then(res => { if (!cancelled) { setProfile(res); setDenied(false); } })
      .catch(() => { if (!cancelled) { setProfile(null); setDenied(true); } })
      .finally(() => { if (!cancelled) setLoadedUserId(userId); });
    return () => { cancelled = true; };
  }, [userId]);

  // 两个 tab 各拉各的，且都只在被选中时才发请求：进页面默认只打仓位历史那条，
  // 成交明细第一次点过去才打。切回来时 key 没变，早退不重打
  useEffect(() => {
    if (tab !== 'positions' || !Number.isFinite(userId) || loadedPositionKey === positionKey) return;
    let cancelled = false;
    rankingApi.userPositionHistory(userId, positionPage, TRADE_PAGE_SIZE)
      .then(res => { if (!cancelled) setPositions(res); })
      .catch(() => { if (!cancelled) setPositions(EMPTY_POSITIONS); })
      .finally(() => { if (!cancelled) setLoadedPositionKey(positionKey); });
    return () => { cancelled = true; };
  }, [tab, positionKey, loadedPositionKey, userId, positionPage]);

  useEffect(() => {
    if (tab !== 'trades' || !Number.isFinite(userId) || loadedTradeKey === tradeKey) return;
    let cancelled = false;
    rankingApi.userTrades(userId, tradePage, TRADE_PAGE_SIZE)
      .then(res => { if (!cancelled) setTrades(res); })
      .catch(() => { if (!cancelled) setTrades(EMPTY_TRADES); })
      .finally(() => { if (!cancelled) setLoadedTradeKey(tradeKey); });
    return () => { cancelled = true; };
  }, [tab, tradeKey, loadedTradeKey, userId, tradePage]);

  const back = <Link className="back" to="/ranking"><ChevronLeft className="ic" />{t('profile.back')}</Link>;

  if (loading) {
    return (
      <div className="page-shell rec p-up">
        <div className="pg-h"><div>{back}<Skeleton className="h-12 w-56" /></div></div>
        <div className="st3">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-20" />)}</div>
      </div>
    );
  }

  // 403（对方关了公开）和 404（这人没交易过）都落在这里。不分开提示——
  // 分开等于把"这个 id 有没有人"当探针送出去，后端也是统一回应的
  if (denied || !profile) {
    return (
      <div className="page-shell rec p-up">
        <div className="pg-h"><div>{back}</div></div>
        <EmptyState icon={<Lock />} text={t('profile.private')} />
      </div>
    );
  }

  // 概览：口径与排行榜完全一致（后端直接复用榜单行）
  const s = profile.summary;
  const openTrade = (symbol: string) => navigate(tradeHref(symbol));

  return (
    <div className="page-shell rec p-up">
      <div className="pg-h">
        <div>
          {back}
          <div className="uh">
            <Avatar name={s.username} src={s.avatar} />
            <b>{s.username}</b>
            {s.userId === myUserId && <span className="tag new">{t('community:ranking.me.badge')}</span>}
            {s.rank != null && <span className="tag"><Trophy className="ic" />#{s.rank}</span>}
          </div>
        </div>
      </div>

      <div className="st3">
        <div><span>{t('profile.totalAssets')}</span><b className="big num"><BigNum value={s.totalAssets} /></b></div>
        <div>
          <span>{t('profile.return')}</span>
          <b className={cn('big num', s.profitPct >= 0 ? 'up' : 'dn')}>{fmtSignedPct(s.profitPct)}</b>
        </div>
        <div>
          <span>{t('profile.tradingProfit')}<HelpTip text={t('profile.tradingProfitHint')} /></span>
          <b className={cn('big num', s.tradingProfit >= 0 ? 'up' : 'dn')}>{fmtSigned(s.tradingProfit)}</b>
        </div>
      </div>

      <div className="two">
        <PositionSection title={t('profile.spotTitle')} positions={profile.spotPositions} onOpen={openTrade} />
        <PositionSection title={t('profile.futuresTitle')} positions={profile.futuresPositions} onOpen={openTrade} />
      </div>

      <section className="sec">
        <Tabs value={tab} onChange={setTab} className="tabline" items={[
          { value: 'positions', label: t('profile.tabPositions'), count: positionsLoading ? undefined : positions.total },
          { value: 'trades', label: t('profile.tabTrades'), count: tab === 'trades' && !tradesLoading ? trades.total : undefined },
        ]} />
        {tab === 'positions' ? (
          <PositionHistoryList
            records={positions.records}
            page={positions.current}
            pages={positions.pages}
            loading={positionsLoading}
            onPage={setPositionPage}
            emptyText={t('profile.emptyHistory')}
          />
        ) : (
          <TradesPanel trades={trades} loading={tradesLoading} onPage={setTradePage} onOpen={openTrade} />
        )}
      </section>
    </div>
  );
}

/** 成交明细：一行一笔委托。粒度比仓位历史更细，但单看一笔委托判断不出这笔仓位最终是赚是亏 */
function TradesPanel({ trades, loading, onPage, onOpen }: {
  trades: PageResult<PublicTrade>;
  loading: boolean;
  onPage: (p: number) => void;
  onOpen: (symbol: string) => void;
}) {
  const { t } = useTranslation('account');
  if (loading) {
    return <div className="skr">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-11" />)}</div>;
  }
  if (trades.records.length === 0) {
    return <EmptyState icon={<Receipt />} text={t('profile.emptyTrades')} />;
  }
  return (
    <>
      {trades.records.map(tr => (
        <button key={`${tr.kind}-${tr.tradeId}`} type="button" className="tr" onClick={() => onOpen(tr.symbol)}>
          <SideTag orderSide={tr.orderSide} />
          <b>{tradeSymbolName(tr.symbol)}</b>
          {tr.kind === 'FUTURES' && <span className="k">{t('profile.futuresTag')}</span>}
          {tr.isAi && <Bot className="ic" />}
          <span className="q num">{fmtNum(tr.quantity, 4)} @ {formatCoinPrice(tr.symbol, tr.filledPrice)}</span>
          <span className="am num">₮{fmtNum(tr.filledAmount)}</span>
          <span className="tm num">{fmtDateTime(tr.createdAt)}</span>
        </button>
      ))}
      <Pager page={trades.current} pages={trades.pages} onChange={onPage} />
    </>
  );
}
