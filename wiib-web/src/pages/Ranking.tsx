import { useState, useEffect } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { rankingApi } from '../api';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { useUserStore } from '../stores/userStore';
import { cn, fmtNum } from '../lib/utils';
import { ArrowDown, ArrowUpRight, ChevronLeft, ChevronRight, Clock, Info, Trophy } from 'lucide-react';
import type { RankingItem, RankingSort } from '../types';

const PAGE_SIZE = 20;

/**
 * 列宽模板。表头与数据行共用一份，各写各的迟早错位（同 PositionHistoryList）。
 * 窄屏用带m标签的两列；到 lg 才排成表，给金额和长用户名留够空间。
 */
const GRID = 'grid grid-cols-2 gap-x-4 gap-y-3 lg:gap-x-5 lg:gap-y-0 lg:items-center lg:grid-cols-[3rem_minmax(8rem,1.35fr)_minmax(0,1.2fr)_minmax(0,.75fr)_minmax(0,1.1fr)_minmax(0,1.15fr)_1rem]';

type Numeric = number | null | undefined;

const num = (v: Numeric) => Number.isFinite(v) ? v as number : 0;
const fmt = (v: Numeric) => fmtNum(num(v));  // 缺失值按 0.00 展示（榜单口径）

/** 窄屏紧凑数字：1.23M / 12.3K，避免小屏格子里大数字换行 */
const fmtCompact = (v: Numeric) => {
  const n = num(v);
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs >= 1e9) return sign + (abs / 1e9).toFixed(2) + 'B';
  if (abs >= 1e6) return sign + (abs / 1e6).toFixed(2) + 'M';
  if (abs >= 1e4) return sign + (abs / 1e3).toFixed(1) + 'K';
  return fmt(n);
};

/** 名次靠字号分层，橙色留给自己的位置。 */
function RankNum({ rank, className }: { rank: number; className?: string }) {
  return (
    <span className={cn('num cond', rank <= 3 ? 'text-foreground' : 'text-muted-foreground', className)}>
      {String(rank).padStart(2, '0')}
    </span>
  );
}

/** 头像沿用方形，尺寸由所在行决定。 */
function Avatar({ username, avatar, className }: { username: string; avatar?: string; className?: string }) {
  const base = 'border border-border shrink-0';
  if (avatar) {
    return <img src={avatar} alt="" className={cn(base, 'object-cover', className)} />;
  }
  return (
    <div className={cn(base, 'bg-card-2 flex items-center justify-center font-bold', className)}>
      {username.charAt(0).toUpperCase()}
    </div>
  );
}

function Pct({ value, className }: { value: Numeric; className?: string }) {
  const v = num(value);
  const up = v >= 0;
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss', className)}>
      {up ? '+' : ''}{v.toFixed(2)}%
    </span>
  );
}

function TradingProfit({ value, className }: { value: Numeric; className?: string }) {
  const v = num(value);
  const up = v >= 0;
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss', className)}>
      {up ? '+' : ''}{fmt(v)}
    </span>
  );
}

/** 独立的「我的排名」没有表头，宽屏也保留字段标签。 */
function Cell({ label, showLabel, className, children }: {
  label: string; showLabel?: boolean; className?: string; children: React.ReactNode;
}) {
  return (
    <div className={cn('min-w-0', className)}>
      <div className={cn('mb-1 text-[12px] font-normal text-muted-foreground', !showLabel && 'lg:hidden')}>{label}</div>
      {children}
    </div>
  );
}

const PLACE_LABEL_KEY = ['ranking.place.first', 'ranking.place.second', 'ranking.place.third'];

const SORT_TABS: { key: RankingSort; labelKey: string; hintKey: string }[] = [
  { key: 'ASSETS', labelKey: 'ranking.metric.assets', hintKey: 'ranking.hint.assets' },
  { key: 'TRADING_PROFIT', labelKey: 'ranking.metric.profit', hintKey: 'ranking.hint.profit' },
];

const METRIC_LABEL_KEY: Record<RankingSort, string> = {
  ASSETS: 'ranking.metric.assets',
  TRADING_PROFIT: 'ranking.metric.profit',
};

const ALL_METRICS: RankingSort[] = ['ASSETS', 'TRADING_PROFIT'];

/** 主数字跟着排序维度走，避免按盈利排榜却突出总资产。 */
function MetricValue({ metric, item, className }: { metric: RankingSort; item: RankingItem; className?: string }) {
  if (metric === 'TRADING_PROFIT') return <TradingProfit value={item.tradingProfit} className={className} />;
  return (
    <span className={cn('num', className)}>{fmt(item.totalAssets)}</span>
  );
}

/** 前三名共用一个版面；手机和平板逐行展开，给完整金额留够宽度。 */
function TopEntry({ item, place, sort, me, onOpen }: {
  item: RankingItem; place: 0 | 1 | 2; sort: RankingSort; me?: boolean; onOpen: () => void;
}) {
  const { t } = useTranslation('community');
  return (
    <button
      type="button"
      onClick={onOpen}
      title={t('ranking.rowTitle', { name: item.username })}
      className={cn(
        'group grid min-w-0 grid-cols-[3rem_minmax(0,1fr)] gap-x-4 gap-y-4 py-5 text-left lg:grid-cols-[3.5rem_minmax(0,1fr)] lg:gap-x-3 lg:py-6 lg:px-5 lg:first:pl-0 lg:last:pr-0 xl:grid-cols-[4.5rem_minmax(0,1fr)] xl:px-7',
        'transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset',
      )}
    >
      <RankNum rank={item.rank} className="row-span-2 self-start text-[52px] font-bold leading-none lg:row-span-1 lg:text-[64px] xl:text-[80px]" />
      <div className="flex min-w-0 items-center gap-2.5 lg:self-center">
        <Avatar username={item.username} avatar={item.avatar} className="size-8 text-sm xl:size-10" />
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex items-center gap-2 text-[12px] text-muted-foreground">
            <span>{t(PLACE_LABEL_KEY[place])}</span>
            {me && <MeBadge />}
          </div>
          <div className="truncate text-[17px] font-bold leading-tight xl:text-[21px]" title={item.username}>
            {item.username}
          </div>
        </div>
        <ArrowUpRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5 motion-reduce:transform-none" />
      </div>

      <div className="min-w-0 lg:col-span-2 lg:mt-2">
        <div className="mb-1.5 text-[12px] text-muted-foreground">{t(METRIC_LABEL_KEY[sort])} <span className="ml-1">USD</span></div>
        <MetricValue metric={sort} item={item} className="cond block text-[clamp(26px,3.2vw,50px)] font-bold leading-none" />
      </div>

      <div className="col-span-2 grid grid-cols-2 gap-x-4 gap-y-3 border-t border-border pt-3">
        <div>
          <div className="mb-1 text-[12px] text-muted-foreground">{t('ranking.metric.return')}</div>
          <Pct value={item.profitPct} className="cond2 text-[18px] font-semibold" />
        </div>
        {ALL_METRICS.filter(m => m !== sort).map(m => (
          <div key={m} className="min-w-0 text-right">
            <div className="mb-1 text-[12px] text-muted-foreground">{t(METRIC_LABEL_KEY[m])}</div>
            <MetricValue metric={m} item={item} className="cond2 text-[16px] font-semibold" />
          </div>
        ))}
        <div className="col-span-2 hidden items-center justify-between gap-3 text-[12px] text-muted-foreground lg:flex">
          <span>{t('ranking.metric.wallet')}</span>
          <span className="num">{fmtCompact(item.balanceWallet)} / {fmtCompact(item.gameWallet)}</span>
        </div>
      </div>
    </button>
  );
}

/** 自己的标记跟着用户名，榜首和明细都能认出来。 */
function MeBadge() {
  const { t } = useTranslation('community');
  return (
    <span className="shrink-0 border border-primary px-1.5 py-0.5 text-[10px] font-semibold leading-none text-primary">
      {t('ranking.me.badge')}
    </span>
  );
}

function RankRow({ item, sort, me, showLabels, onOpen }: {
  item: RankingItem; sort: RankingSort; me?: boolean; showLabels?: boolean; onOpen: () => void;
}) {
  const { t } = useTranslation('community');
  return (
    <button
      type="button"
      onClick={onOpen}
      title={t('ranking.rowTitle', { name: item.username })}
      className={cn(GRID, 'group w-full border-b border-border py-4 text-left',
        'transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset',
        // card-2 跟 surface-hover 是同一个色值，自己那条得换主色淡底，否则划过普通行就跟它撞脸
        me ? 'bg-primary/8 hover:bg-primary/14' : 'hover:bg-surface-hover')}
    >
      {/* 名次 + 用户：窄屏并成一行占满，箭头也跟着挪到这行尾（宽屏那个在表格最后一列） */}
      <div className="col-span-2 flex items-center gap-3 lg:col-span-1 lg:gap-0">
        <RankNum rank={item.rank} className={cn('text-[30px] font-semibold leading-none', me && 'text-primary')} />
        <div className="flex min-w-0 flex-1 items-center gap-2.5 lg:hidden">
          <Avatar username={item.username} avatar={item.avatar} className="size-8 text-[12px]" />
          <span className="truncate text-[15px] font-semibold">{item.username}</span>
          {me && <MeBadge />}
          <ChevronRight aria-hidden="true" className="ml-auto size-4 shrink-0 text-muted-foreground" />
        </div>
      </div>
      <div className="hidden min-w-0 items-center gap-2.5 lg:flex">
        <Avatar username={item.username} avatar={item.avatar} className="size-8 text-[12px]" />
        <span className="truncate text-[15px] font-semibold" title={item.username}>
          {item.username}
        </span>
        {me && <MeBadge />}
      </div>

      <Cell label={t('ranking.metric.assets')} showLabel={showLabels} className="lg:text-right">
        <span className={cn('num cond2', sort === 'ASSETS' ? 'text-[20px] font-bold' : 'text-[16px] font-semibold')}>
          <span className="lg:hidden" title={fmt(item.totalAssets)}>{fmtCompact(item.totalAssets)}</span>
          <span className="hidden lg:inline">{fmt(item.totalAssets)}</span>
        </span>
      </Cell>

      <Cell label={t('ranking.metric.return')} showLabel={showLabels} className="lg:text-right">
        <Pct value={item.profitPct} className="cond2 text-[16px] font-semibold" />
      </Cell>

      <Cell label={t('ranking.metric.profit')} showLabel={showLabels} className="lg:text-right">
        <TradingProfit value={item.tradingProfit} className={cn('cond2', sort === 'TRADING_PROFIT' ? 'text-[20px] font-bold' : 'text-[16px] font-semibold')} />
      </Cell>

      <Cell label={t('ranking.metric.wallet')} showLabel={showLabels} className="lg:text-right">
        <span className="num text-[13px] text-muted-foreground">
          {fmtCompact(item.balanceWallet)} / {fmtCompact(item.gameWallet)}
        </span>
      </Cell>

      <div className="hidden justify-end lg:flex">
        <ChevronRight aria-hidden="true" className="size-4 text-muted-foreground transition-colors group-hover:text-foreground" />
      </div>
    </button>
  );
}

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

  const openUser = (userId: number) => navigate(`/user/${userId}`);

  // 换维度必须回到第 1 页：停在第 3 页换榜，看到的是新榜的第 41 名开始，
  // 而前三名只在第 1 页出现，换完一片空
  const switchSort = (next: RankingSort) => {
    if (next === sort) return;
    setSort(next);
    setPage(1);
  };

  // 第 2 页往后的前三条不是全榜前三，不能放进榜首区。
  const showTop = page === 1;
  const top = showTop ? ranking.slice(0, 3) : [];
  const rest = showTop ? ranking.slice(3) : ranking;

  return (
    <div className="page-shell pb-6">
      <div className="page-h flex-wrap justify-between gap-y-4">
        <div className="min-w-0">
          <h1>{t('ranking.title')}</h1>
          <div className="mt-2 text-[13px] text-muted-foreground">
            <Trans
              ns="community"
              i18nKey="ranking.totalUsers"
              values={{ total }}
              components={[<span key="total" className="num font-semibold text-foreground" />]}
            />
          </div>
        </div>
        <div className="flex max-w-xl flex-col gap-2 text-[12px] text-muted-foreground lg:items-end">
          <span className="flex items-center gap-2">
            <Clock aria-hidden="true" className="size-3.5 shrink-0" />
            {t('ranking.updateHint')}
          </span>
          <span className="leading-relaxed">{t('ranking.rowHint')}</span>
        </div>
      </div>

      {/* 排序口径直接展示，触屏用户也能看见。 */}
      <div className="mt-6 flex flex-col gap-3 border-t-2 border-foreground py-5 md:flex-row md:items-center md:gap-6">
        <div className="seg w-full shrink-0 sm:w-auto" role="group" aria-label={t('ranking.sortLabel')}>
          {SORT_TABS.map(tab => (
            <button
              key={tab.key}
              type="button"
              onClick={() => switchSort(tab.key)}
              title={t(tab.hintKey)}
              aria-pressed={sort === tab.key}
              aria-controls="ranking-results"
              className={cn('h-10 flex-1 px-5 text-sm sm:flex-none', sort === tab.key && 'on')}
            >
              {t(tab.labelKey)}
            </button>
          ))}
        </div>
        <p className="m-0 text-[13px] leading-relaxed text-muted-foreground">
          {t(sort === 'ASSETS' ? 'ranking.hint.assets' : 'ranking.hint.profit')}
        </p>
      </div>

      <div id="ranking-results" aria-busy={loading}>
        {loading ? (
          <div role="status">
            <span className="sr-only">{t('common:loading')}</span>
            <div aria-hidden="true">
              {showMine && <Skeleton className="mb-8 h-24 w-full motion-reduce:animate-none" />}
              {showTop && (
                <div className="grid grid-cols-1 gap-5 border-t-2 border-foreground py-6 lg:grid-cols-3 lg:gap-10">
                  {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-44 w-full motion-reduce:animate-none lg:h-56" />)}
                </div>
              )}
              <div className="mt-8 space-y-4 border-t-2 border-foreground pt-5">
                {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-16 w-full motion-reduce:animate-none" />)}
              </div>
            </div>
          </div>
        ) : ranking.length === 0 ? (
          <section className="border-y border-border py-10">
            <EmptyState icon={<Trophy />} text={t('ranking.empty')} />
          </section>
        ) : (
          <>
            {/* 单独提取自己的位置，榜首与明细仍保留完整名次。 */}
            {showMine && myRow !== undefined && (
              <section className="mb-8 border-l-2 border-primary pl-4" aria-label={t('ranking.me.title')}>
                <h2 className="text-[13px] font-semibold">{t('ranking.me.title')}</h2>
                {myRow ? (
                  <RankRow item={myRow} sort={sort} me showLabels onOpen={() => openUser(myRow.userId)} />
                ) : (
                  <div className="flex items-center gap-2 border-b border-border py-4 text-[13px] text-muted-foreground">
                    <Info aria-hidden="true" className="size-4 shrink-0" />
                    {t('ranking.me.notRanked')}
                  </div>
                )}
              </section>
            )}

            {top.length > 0 && (
              <section className="border-t-2 border-foreground" aria-labelledby="ranking-leaders-heading">
                <div className="flex items-baseline justify-between gap-4 border-b border-border py-3">
                  <h2 id="ranking-leaders-heading" className="text-[17px] font-extrabold">{t('ranking.leaders')}</h2>
                  <span className="text-[12px] text-muted-foreground">{t(METRIC_LABEL_KEY[sort])} / USD</span>
                </div>
                <div className="grid grid-cols-1 divide-y divide-border border-b border-border lg:grid-cols-3 lg:divide-x lg:divide-y-0">
                  {top.map((item, i) => (
                    <TopEntry key={item.userId} item={item} place={i as 0 | 1 | 2} sort={sort} me={item.userId === myUserId} onOpen={() => openUser(item.userId)} />
                  ))}
                </div>
              </section>
            )}

            {rest.length > 0 && (
              <section className="mt-8 border-t-2 border-foreground" aria-labelledby="ranking-board-heading">
                <div className="flex items-baseline justify-between gap-4 py-4">
                  <h2 id="ranking-board-heading" className="text-[17px] font-extrabold">{t('ranking.board')}</h2>
                  <span className="num text-[12px] text-muted-foreground">
                    {t('ranking.range', { from: rest[0].rank, to: rest[rest.length - 1].rank })}
                  </span>
                </div>
                <div className={cn(GRID, 'hidden border-b border-foreground pb-3 text-[12.5px] font-semibold text-muted-foreground lg:grid')}>
                  <span>#</span>
                  <span>{t('ranking.metric.user')}</span>
                  <span className={cn('flex items-center justify-end gap-1', sort === 'ASSETS' && 'text-foreground')}>
                    {t('ranking.metric.assets')}
                    {sort === 'ASSETS' && <ArrowDown aria-hidden="true" className="size-3 shrink-0" />}
                  </span>
                  <span className="text-right">{t('ranking.metric.return')}</span>
                  <span className={cn('flex items-center justify-end gap-1', sort === 'TRADING_PROFIT' && 'text-foreground')} title={t('ranking.hint.profitCol')}>
                    {t('ranking.metric.profit')}
                    {sort === 'TRADING_PROFIT' && <ArrowDown aria-hidden="true" className="size-3 shrink-0" />}
                  </span>
                  <span className="text-right" title={t('ranking.hint.walletCol')}>{t('ranking.metric.wallet')}</span>
                  <span />
                </div>
                {rest.map(item => (
                  <RankRow key={item.userId} item={item} sort={sort} me={item.userId === myUserId} onOpen={() => openUser(item.userId)} />
                ))}
              </section>
            )}

            {pages > 1 && (
              <nav className="mt-6 flex flex-wrap items-center justify-between gap-3" aria-label={t('ranking.pagination')}>
                <span className="text-[13px] text-muted-foreground">
                  <Trans
                    ns="community"
                    i18nKey="ranking.page"
                    values={{ page, pages }}
                    components={[<span key="page" className="num font-semibold text-foreground" />, <span key="pages" className="num" />]}
                  />
                </span>
                <div className="flex items-center gap-2">
                  <Button variant="outline" className="border-foreground"
                    disabled={page <= 1} onClick={() => setPage(p => p - 1)}>
                    <ChevronLeft aria-hidden="true" className="size-4" />{t('ranking.prevPage')}
                  </Button>
                  <Button variant="outline" className="border-foreground"
                    disabled={page >= pages} onClick={() => setPage(p => p + 1)}>
                    {t('ranking.nextPage')}<ChevronRight aria-hidden="true" className="size-4" />
                  </Button>
                </div>
              </nav>
            )}
          </>
        )}
      </div>
    </div>
  );
}
