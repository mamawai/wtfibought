import { useState, useEffect } from 'react';
import { rankingApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { cn, fmtNum } from '../lib/utils';
import { Trophy, TrendingUp, TrendingDown, Clock } from 'lucide-react';
import type { RankingItem } from '../types';

type Numeric = number | null | undefined;

const num = (v: Numeric) => Number.isFinite(v) ? v as number : 0;

const fmt = (v: Numeric) => fmtNum(num(v));  // 缺失值仍按 0.00 展示（奖台口径）

// 移动端紧凑展示：1.23M / 12.3K，避免奖台窄列里大数字溢出
const fmtCompact = (v: Numeric) => {
  const n = num(v);
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs >= 1e9) return sign + (abs / 1e9).toFixed(2) + 'B';
  if (abs >= 1e6) return sign + (abs / 1e6).toFixed(2) + 'M';
  if (abs >= 1e4) return sign + (abs / 1e3).toFixed(1) + 'K';
  return fmt(n);
};

function ProfitBadge({ pct }: { pct: Numeric }) {
  const safePct = num(pct);
  const up = safePct >= 0;
  return (
    <Badge variant="secondary" className={cn("text-xs gap-0.5", up ? "text-green-500" : "text-red-500")}>
      {up ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
      {up ? '+' : ''}{safePct.toFixed(2)}%
    </Badge>
  );
}

/* ── 合约+现货+预测 实战盈亏 + 优惠券省下金额（全部基于Binance实时价格） ── */
function HardcoreLine({ hardcore, buff, compact = false }: { hardcore: Numeric; buff: Numeric; compact?: boolean }) {
  const safeHardcore = num(hardcore);
  const safeBuff = num(buff);
  const up = safeHardcore >= 0;
  const color = up ? 'text-emerald-500' : 'text-rose-500';
  const fs = compact ? 'text-[10px]' : 'text-[11px]';
  return (
    <div
      className={cn(fs, "flex flex-wrap items-center justify-end gap-x-1.5 gap-y-0 leading-tight tabular-nums")}
      title="盈亏 = 合约 + 现货 + 预测"
    >
      {!compact && (
        <span className="text-muted-foreground/70 whitespace-nowrap">合约+现货+预测</span>
      )}
      <span className={cn('font-semibold whitespace-nowrap', color)}>
        {up ? '+' : ''}{fmt(safeHardcore)}
      </span>
      {safeBuff > 0 && (
        <span className="text-amber-500 font-medium whitespace-nowrap" title="优惠券累计省下">
          🎟+{fmt(safeBuff)}
        </span>
      )}
    </div>
  );
}

/* ── 双钱包现金构成：总资产里的现金部分，不含持仓市值，两者相加≠总资产 ── */
function WalletLine({ balance, game, compact = false }: { balance: Numeric; game: Numeric; compact?: boolean }) {
  const fs = compact ? 'text-[9px]' : 'text-[10px]';
  return (
    <div
      className={cn(fs, "flex flex-wrap items-center justify-end gap-x-1.5 leading-tight tabular-nums text-muted-foreground")}
      title="账户现金构成（不含持仓市值）"
    >
      <span className="whitespace-nowrap">余额 {fmt(num(balance))}</span>
      <span className="whitespace-nowrap">游戏 {fmt(num(game))}</span>
    </div>
  );
}

/* ── Avatar with theme-consistent styling ── */
function RankAvatar({ username, avatar, size = 'md', accent }: {
  username: string; avatar?: string;
  size?: 'sm' | 'md' | 'lg';
  accent?: string;          // ring color override for podium medals
}) {
  const dim = size === 'lg' ? 'w-16 h-16' : size === 'md' ? 'w-11 h-11' : 'w-8 h-8';
  const text = size === 'lg' ? 'text-xl' : size === 'md' ? 'text-sm' : 'text-xs';
  const ringCls = accent ?? 'ring-primary/30';

  if (avatar) {
    return (
      <img
        src={avatar} alt=""
        className={cn(dim, "rounded-full object-cover ring-2 ring-offset-2 ring-offset-card", ringCls)}
      />
    );
  }
  return (
    <div className={cn(
      dim, text,
      "rounded-full bg-linear-to-br from-primary/20 to-accent/10",
      "flex items-center justify-center font-bold",
      "ring-2 ring-offset-2 ring-offset-card", ringCls,
    )}>
      {username.charAt(0).toUpperCase()}
    </div>
  );
}

/* ── Podium card for top 3 ── */
function PodiumCard({ item, place }: { item: RankingItem; place: 1 | 2 | 3 }) {
  const cfg = {
    1: { order: 'order-2', height: 'h-32', bg: 'from-amber-500/20 to-amber-500/5', ring: 'ring-amber-500/40', accent: 'ring-amber-400/60', label: '🥇' },
    2: { order: 'order-1', height: 'h-28', bg: 'from-slate-400/20 to-slate-400/5', ring: 'ring-slate-400/40', accent: 'ring-slate-400/50', label: '🥈' },
    3: { order: 'order-3', height: 'h-28', bg: 'from-amber-700/20 to-amber-700/5', ring: 'ring-amber-700/40', accent: 'ring-amber-600/50', label: '🥉' },
  }[place];

  return (
    <div className={cn("flex-1 flex flex-col items-center gap-2.5", cfg.order)}>
      {/* avatar + medal badge */}
      <div className="relative">
        <RankAvatar username={item.username} avatar={item.avatar} size={place === 1 ? 'lg' : 'md'} accent={cfg.accent} />
        <span className="absolute -bottom-1 -right-1 text-base leading-none drop-shadow">{cfg.label}</span>
      </div>

      {/* name + rank label */}
      <div className="flex flex-col items-center gap-0.5 min-w-0 max-w-full w-full">
        <span className={cn("font-semibold truncate max-w-full text-center", place === 1 ? "text-sm" : "text-xs")}>
          {item.username}
        </span>
        <span className="text-[10px] text-muted-foreground tracking-wide">
          {place === 1 ? '冠军' : place === 2 ? '亚军' : '季军'}
        </span>
      </div>

      {/* pedestal */}
      <div className={cn(
        "w-full rounded-t-xl bg-gradient-to-t flex flex-col items-center justify-end pb-3 pt-4 gap-1 ring-1 ring-inset",
        cfg.bg, cfg.ring, cfg.height,
      )}>
        {/* 窄屏紧凑数字 (123.4K)，>=sm 显示完整千分位 */}
        <span className="text-sm font-bold tabular-nums max-w-full truncate">
          <span className="sm:hidden">{fmtCompact(item.totalAssets)}</span>
          <span className="hidden sm:inline">{fmt(item.totalAssets)}</span>
        </span>
        <WalletLine balance={item.balanceWallet} game={item.gameWallet} compact />
        <ProfitBadge pct={item.profitPct} />
        <HardcoreLine hardcore={item.hardcoreProfit} buff={item.buffProfit} compact />
      </div>
    </div>
  );
}

/* ── Row for rank 4+ ── */
function RankingRow({ item }: { item: RankingItem }) {
  return (
    <div className="flex items-center gap-2 sm:gap-3 px-2 sm:px-4 py-3 rounded-lg hover:bg-muted/50 transition-colors">
      {/* rank number */}
      <span className="w-7 text-center text-sm font-bold tabular-nums text-muted-foreground shrink-0">
        {item.rank}
      </span>

      {/* avatar */}
      <RankAvatar username={item.username} avatar={item.avatar} size="sm" />

      {/* name + sub label */}
      <div className="flex-1 min-w-0">
        <div className="text-sm font-semibold truncate">{item.username}</div>
        <div className="text-[11px] text-muted-foreground leading-tight">NO.{item.rank} · 模拟账户</div>
      </div>

      {/* assets + profit */}
      <div className="text-right shrink-0">
        <div className="text-sm font-semibold tabular-nums">{fmt(item.totalAssets)}</div>
        <WalletLine balance={item.balanceWallet} game={item.gameWallet} />
        <ProfitBadge pct={item.profitPct} />
        <HardcoreLine hardcore={item.hardcoreProfit} buff={item.buffProfit} />
      </div>
    </div>
  );
}

export function Ranking() {
  const [ranking, setRanking] = useState<RankingItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    rankingApi.list().then(setRanking).catch(() => setRanking([])).finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto p-4 space-y-4">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="h-48 w-full" />
        {[...Array(7)].map((_, i) => <Skeleton key={i} className="h-14 w-full" />)}
      </div>
    );
  }

  const top3 = ranking.slice(0, 3);
  const rest = ranking.slice(3);

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2">
            <div className="p-1.5 rounded-lg bg-amber-500/10">
              <Trophy className="w-5 h-5 text-amber-500" />
            </div>
            资产排行榜
          </CardTitle>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
            <span className="flex items-center gap-1">
              <Clock className="w-3 h-3" />
              交易时段每10分钟更新
            </span>
          </div>
        </CardHeader>

        <CardContent className="space-y-4">
          {ranking.length === 0 ? (
            <div className="py-12 text-center text-muted-foreground">暂无排行数据</div>
          ) : (
            <>
              {/* ── Podium ── */}
              {top3.length > 0 && (
                <div className="flex items-end gap-2 sm:gap-3 px-1 sm:px-2 pt-4 pb-0">
                  {top3.length > 1 && <PodiumCard item={top3[1]} place={2} />}
                  <PodiumCard item={top3[0]} place={1} />
                  {top3.length > 2 && <PodiumCard item={top3[2]} place={3} />}
                </div>
              )}

              {/* ── Divider ── */}
              {rest.length > 0 && <div className="border-t border-border/50" />}

              {/* ── Rest of the list ── */}
              <div className="space-y-1">
                {rest.map((item) => <RankingRow key={item.userId} item={item} />)}
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
