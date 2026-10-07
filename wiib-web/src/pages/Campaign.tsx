import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { campaignApi } from '../api';
import i18n from '../i18n';
import { buildAuthorizeUrl, CLAIM_STATE_PREFIX, OAUTH_STATE_KEY } from './Login';
import { useUserStore } from '../stores/userStore';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { useToast } from '../components/ui/use-toast';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import { getCoin } from '../lib/coinConfig';
import {
  Gift, CalendarCheck, Trophy, TrendingUp, TrendingDown, Check, Loader2, TriangleAlert, RefreshCw,
} from 'lucide-react';
import type {
  CampaignInfo, CampaignReward, CampaignScore, CampaignScoreItem, MyCampaignView,
} from '../types';

type TaskGroup = 'trade' | 'daily' | 'vote' | 'penalty';

/** 分组渲染顺序。罚分固定垫底 —— 谁也不想一进来先看见自己被扣了多少 */
const GROUP_ORDER: { key: TaskGroup; labelKey: string }[] = [
  { key: 'trade', labelKey: 'campaign.group.trade' },
  { key: 'daily', labelKey: 'campaign.group.daily' },
  { key: 'vote', labelKey: 'campaign.group.vote' },
  { key: 'penalty', labelKey: 'campaign.group.penalty' },
];

interface TaskDef {
  /** 与后端 ScoreItem.code 对齐。后端只下发"已达成"的条目，未达成的靠这份清单占位 */
  code: string;
  /** 词表 key（community ns）。后端下发的清单外条目没有这两项，文案直接用它自己的 */
  labelKey?: string;
  hintKey?: string;
  group: TaskGroup;
  /** 子任务行，缩进渲染（三市通吃的三个市场桶） */
  sub?: boolean;
  /** 纯状态行（后端 score 恒 0，只为打勾），分值列恒显示 — */
  noScore?: boolean;
}

/**
 * 任务全集。后端 items 里只有已达成的条目，光渲染它的话新人进来是一片空白，
 * 「再做一个任务能多拿多少」这件事就无从谈起 —— 阶梯的推力全靠这份清单撑着。
 * 分值文案与 ScoreRules / TradeScorer.toItems 同源，改规则时两边一起改。
 * <p>
 * ROI 五行是占位制阶梯：每笔达标仓位只计入"还有名额的最高档"，高档满了往下顺延，
 * 顺序排成 100 → 60 → 40 → 配额外 → 20~40，读下来就是顺延的方向。
 */
const TASKS: TaskDef[] = [
  { code: 'ROI100', labelKey: 'campaign.task.roi100', hintKey: 'campaign.hint.roi100', group: 'trade' },
  { code: 'ROI60', labelKey: 'campaign.task.roi60', hintKey: 'campaign.hint.roi60', group: 'trade' },
  { code: 'ROI40', labelKey: 'campaign.task.roi40', hintKey: 'campaign.hint.roi40', group: 'trade' },
  { code: 'ROI40_EXTRA', labelKey: 'campaign.task.roi40Extra', hintKey: 'campaign.hint.roi40Extra', group: 'trade' },
  { code: 'ROI20', labelKey: 'campaign.task.roi20', hintKey: 'campaign.hint.roi20', group: 'trade' },
  { code: 'GODLY', labelKey: 'campaign.task.godly', hintKey: 'campaign.hint.godly', group: 'trade' },
  { code: 'TRIPLE', labelKey: 'campaign.task.triple', hintKey: 'campaign.hint.triple', group: 'trade' },
  { code: 'BUCKET_crypto', labelKey: 'campaign.task.bucketCrypto', hintKey: 'campaign.hint.bucketCrypto', group: 'trade', sub: true, noScore: true },
  { code: 'BUCKET_commodity', labelKey: 'campaign.task.bucketCommodity', hintKey: 'campaign.hint.bucketCommodity', group: 'trade', sub: true, noScore: true },
  { code: 'BUCKET_tradfi', labelKey: 'campaign.task.bucketTradfi', hintKey: 'campaign.hint.bucketTradfi', group: 'trade', sub: true, noScore: true },
  { code: 'SPOT', labelKey: 'campaign.task.spot', hintKey: 'campaign.hint.spot', group: 'trade' },
  { code: 'PREDICTION', labelKey: 'campaign.task.prediction', hintKey: 'campaign.hint.prediction', group: 'trade' },
  { code: 'STOP_LOSS_HERO', labelKey: 'campaign.task.stopLossHero', hintKey: 'campaign.hint.stopLossHero', group: 'trade' },
  { code: 'PNL_PROFIT', labelKey: 'campaign.task.pnlProfit', hintKey: 'campaign.hint.pnlProfit', group: 'trade' },
  { code: 'CHECKIN', labelKey: 'campaign.task.checkin', hintKey: 'campaign.hint.checkin', group: 'daily' },
  { code: 'STREAK', labelKey: 'campaign.task.streak', hintKey: 'campaign.hint.streak', group: 'daily' },
  { code: 'FIRST_COMMENT', labelKey: 'campaign.task.firstComment', hintKey: 'campaign.hint.firstComment', group: 'daily' },
  { code: 'VOTE', labelKey: 'campaign.task.vote', hintKey: 'campaign.hint.vote', group: 'vote' },
  { code: 'PNL_LOSS', labelKey: 'campaign.task.pnlLoss', hintKey: 'campaign.hint.pnlLoss', group: 'penalty' },
  { code: 'LIQ_TRIGGER', labelKey: 'campaign.task.liqTrigger', hintKey: 'campaign.hint.liqTrigger', group: 'penalty' },
  { code: 'RESET_EXTRA', labelKey: 'campaign.task.resetExtra', hintKey: 'campaign.hint.resetExtra', group: 'penalty' },
];

/** 整数不显示小数：任务分大多是整数，"+5.00" 读起来像金额 */
function fmtScore(n: number): string {
  return Number.isInteger(n) ? String(n) : fmtNum(n);
}

/** 进度文案。签到那两条的 count 是天数，写成 "×8" 会被读成签了 8 次不同的到 */
function progressText(code: string, count: number): string {
  // 词表在函数体里现查：存成模块级常量的话切语言后不会变（调用方 TaskRow 订了 t，会跟着重渲染）
  if (code === 'CHECKIN') return i18n.t('community:campaign.progress.checkin', { count });
  if (code === 'STREAK') return i18n.t('community:campaign.progress.streak', { count });
  return i18n.t('community:campaign.progress.times', { value: count });
}

function TaskRow({ def, item }: { def: TaskDef; item: CampaignScoreItem | null }) {
  const { t } = useTranslation('community');
  const done = item != null;
  const negative = (item?.score ?? 0) < 0;
  return (
    <div
      className={cn(
        'flex items-center gap-3 px-4 py-2.5 border-b border-border/25 last:border-b-0',
        // 子任务行缩进 + 压暗底色：一眼看出是上一行（三市通吃）的组成部分
        def.sub && 'pl-10 bg-card-2/40',
      )}
    >
      <span
        className={cn(
          'w-4 h-4 shrink-0 rounded-full border flex items-center justify-center',
          done
            ? negative ? 'border-loss/50 bg-loss/12 text-loss' : 'border-gain/50 bg-gain/12 text-gain'
            : 'border-border',
        )}
      >
        {done && <Check className="w-2.5 h-2.5" />}
      </span>

      <div className="min-w-0 flex-1">
        <div className={cn('text-[13px] font-semibold truncate', !done && 'text-muted-foreground')}>
          {/* 文案以前端词表为准：后端 label 只有中文，拿它当先会让英文界面一张表两种语言。
              code 仍是稳定标识，只是清单外的 code 前端没词条，才回落后端 label */}
          {def.labelKey ? t(def.labelKey) : (item?.label ?? def.code)}
        </div>
        <div className="text-[10px] text-muted-foreground truncate">{def.hintKey ? t(def.hintKey) : ''}</div>
      </div>

      {item != null && item.count > 0 && (
        <span className="num text-[11px] text-muted-foreground shrink-0 tabular-nums">
          {progressText(def.code, item.count)}
        </span>
      )}

      <span
        className={cn(
          'num text-[13px] font-bold tabular-nums w-14 text-right shrink-0',
          // 纯状态行（三个市场桶）没有分值可言，达成与否都显示 —，勾和 ×n 已经把话说完了
          !done || def.noScore ? 'text-muted-foreground/50' : negative ? 'text-loss' : 'text-gain',
        )}
      >
        {done && !def.noScore ? `${item.score > 0 ? '+' : ''}${fmtScore(item.score)}` : '—'}
      </span>
    </div>
  );
}

/**
 * 票盖的是<b>明天</b>的 UTC 日戳（后端 CampaignVoteService.votingDate 就是这么算的）。
 * 投当天没得玩：结算比的是当日收盘 vs 前日收盘，而那根 K 线在本站自己的图上就看得见，
 * 临收盘才投等于照着答案填。投明天则收票在这一天开始之前就截止了，谁都没有前瞻。
 * 这里独立算一遍只为把目标日显示出来 —— 不显示的话"投的是明天"这件事在界面上完全看不出来。
 */
function tomorrowUtc(now: Date): string {
  return new Date(now.getTime() + 86400_000).toISOString().slice(0, 10);
}

/**
 * 结算锁盘窗口 UTC [23:55, 00:05)：日线在切、00:05 那次投票结算在往库里写结果，
 * 而且这 10 分钟正好横跨"票落在哪一天"的翻页点。
 * 说了算的是后端那道闸（CampaignVoteService.requireNotLocked），这里只为提前把按钮压灰 ——
 * 让人点下去才被顶回来，比灰着并写明原因难受得多。
 */
function inSettleLock(now: Date): boolean {
  const minutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  return minutes >= 23 * 60 + 55 || minutes < 5;
}

function VoteSide({ dir, count, mine, disabled, onClick }: {
  dir: 'UP' | 'DOWN';
  count: number;
  mine: 'UP' | 'DOWN' | null;
  disabled: boolean;
  onClick: () => void;
}) {
  const { t } = useTranslation('community');
  const up = dir === 'UP';
  const picked = mine === dir;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        'flex flex-col items-center gap-0.5 rounded-md border py-2.5 transition-colors',
        picked
          ? up ? 'border-gain/50 bg-gain/12 text-gain' : 'border-loss/50 bg-loss/12 text-loss'
          : 'border-border text-muted-foreground',
        // 已投过的另一侧压暗，跟"还没投但现在不能投"区分开
        !picked && mine !== null && 'opacity-40',
        !disabled && !picked && (up
          ? 'cursor-pointer hover:border-gain/50 hover:text-gain'
          : 'cursor-pointer hover:border-loss/50 hover:text-loss'),
        disabled && 'cursor-not-allowed',
      )}
    >
      <span className="text-[13px] font-bold flex items-center gap-1">
        {up ? <TrendingUp className="w-3.5 h-3.5" /> : <TrendingDown className="w-3.5 h-3.5" />}
        {up ? t('campaign.vote.up') : t('campaign.vote.down')}
      </span>
      <span className="num text-[11px] tabular-nums">{t('campaign.vote.votes', { count })}</span>
    </button>
  );
}

/** 一维分数块（交易/日常/投票/罚分）。罚分是 0 时不上红，没被扣过不该看着像被扣了 */
function ScoreCell({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="microlabel font-semibold">{label}</div>
      <div className={cn('num text-lg font-bold mt-0.5 tabular-nums', value < 0 && 'text-loss')}>
        {fmtScore(value)}
      </div>
    </div>
  );
}

/**
 * 把一次请求收成 [值, 是否失败]。
 * <p>拉失败与"确实是空"必须分开：合成同一个 null 的话，一次 500 就变成
 * 「当前没有进行中的活动」——活动进行中后端抖一下，页面就对着用户否认活动的存在，
 * 而这是一个真发钱的页面。
 */
function tryLoad<T>(p: Promise<T>, fallback: T): Promise<[T, boolean]> {
  return p.then(v => [v, false] as [T, boolean]).catch(() => [fallback, true]);
}

export function Campaign() {
  const { t } = useTranslation(['community', 'common']);
  const { toast } = useToast();
  const { user } = useUserStore();

  const [view, setView] = useState<MyCampaignView | null>(null);
  const [info, setInfo] = useState<CampaignInfo | null>(null);
  const [reward, setReward] = useState<CampaignReward | null>(null);
  const [board, setBoard] = useState<CampaignScore[]>([]);
  // 四个"没拉到"标记一个都不省：settled 喂着 canAct、状态徽标和预估卡，
  // 任何一支拉挂宁可挂"没拉全"提示，也别假装页面是准的
  const [viewFailed, setViewFailed] = useState(false);
  const [currentFailed, setCurrentFailed] = useState(false);
  const [rewardFailed, setRewardFailed] = useState(false);
  const [boardFailed, setBoardFailed] = useState(false);

  const [nonce, setNonce] = useState(0);
  // loading 由"已加载 nonce 是否追上请求 nonce"派生：在 effect 里同步 setLoading(true)
  // 会被 eslint 的 react-hooks/set-state-in-effect 判错（同 Ledger / ForceOrders）
  const [loadedNonce, setLoadedNonce] = useState(-1);
  const loading = loadedNonce !== nonce;

  // 签到/投票 与 领取跳转 分两个忙标记：共用一个的话点了签到，领取按钮会跟着写"跳转授权中"
  const [acting, setActing] = useState(false);
  const [redirecting, setRedirecting] = useState(false);
  const reload = useCallback(() => setNonce(n => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      tryLoad(campaignApi.me(), null as MyCampaignView | null),
      tryLoad(campaignApi.current(), null as CampaignInfo | null),
      tryLoad(campaignApi.reward(), null as CampaignReward | null),
      tryLoad(campaignApi.board(), [] as CampaignScore[]),
    ]).then(([[me, meErr], [cur, curErr], [rw, rwErr], [bd, bdErr]]) => {
      if (cancelled) return;
      // 只在成功时覆盖：签到/投票之后走的是同一条刷新链路，网络抖一下不该把
      // 已经看到的内容清成空白，更不该把"有活动"翻成"没活动"
      if (!meErr) setView(me);
      if (!curErr) setInfo(cur);
      if (!rwErr) setReward(rw);
      if (!bdErr) setBoard(bd);
      setViewFailed(meErr);
      setCurrentFailed(curErr);
      setRewardFailed(rwErr);
      setBoardFailed(bdErr);
      setLoadedNonce(nonce);
    });
    return () => { cancelled = true; };
  }, [nonce]);

  // 锁盘状态得自己走一步：页面别处的时间都是每次渲染现取的，不刷新就不动 ——
  // 别处不动只是显示旧了，这里不动却会把按钮一直灰着，人就投不成票了。30 秒一跳，
  // 两端最多差半分钟：起点那半分钟点下去后端会顶回来（有 toast），终点那半分钟等一下就好
  const [voteLocked, setVoteLocked] = useState(() => inSettleLock(new Date()));
  // 上一跳的锁盘状态。用 ref 不用 state：只为认出"解锁那一跳"，进 effect 依赖会把 30 秒的计时器重置掉
  const lockedRef = useRef(voteLocked);
  useEffect(() => {
    const timer = setInterval(() => {
      const locked = inSettleLock(new Date());
      // true→false 这一跳只可能发生在 UTC 00:05：中间刚跨过 0 点，voteDay 已经翻页，
      // 可票数和"我投了没"还是上一天那份 —— 不重拉的话，昨天投过的人会看着新日期被告知
      // "UTC xx-xx 的看涨已投"，而那天他一票没投。只在这一跳拉，不做轮询
      if (lockedRef.current && !locked) reload();
      lockedRef.current = locked;
      setVoteLocked(locked);
    }, 30_000);
    return () => clearInterval(timer);
  }, [reload]);

  const handleCheckin = async () => {
    setActing(true);
    try {
      const streak = await campaignApi.checkin();
      toast(t('campaign.toast.checkin', { count: streak }), 'success');
      reload();
    } catch (e) {
      toast((e as Error).message || t('campaign.toast.checkinFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  const handleVote = async (symbol: string, direction: 'UP' | 'DOWN') => {
    setActing(true);
    try {
      await campaignApi.vote(symbol, direction);
      toast(t('campaign.toast.voted'), 'success');
      reload();
    } catch (e) {
      toast((e as Error).message || t('campaign.toast.voteFailed'), 'error');
    } finally {
      setActing(false);
    }
  };

  /** 领取要走一次 LinuxDo 授权：平台存的 username 可能是旧的，分发接口拿它做二次校验会失败 */
  const startClaim = () => {
    setRedirecting(true);
    const state = CLAIM_STATE_PREFIX + Math.random().toString(36).slice(2);
    localStorage.setItem(OAUTH_STATE_KEY, state);
    window.location.href = buildAuthorizeUrl(state);
  };

  // 任务清单：按 code 把后端条目并进任务全集；后端出了清单外的 code 也照样渲染，
  // 负分的一律归到罚分组（将来日常侧加扣分规则时不用改这里）
  const grouped = useMemo(() => {
    const items = view?.me.items ?? [];
    const byCode = new Map(items.map(i => [i.code, i]));
    const known = new Set(TASKS.map(t => t.code));
    const rows: { def: TaskDef; item: CampaignScoreItem | null }[] = [
      ...TASKS.map(def => ({ def, item: byCode.get(def.code) ?? null })),
      ...items.filter(i => !known.has(i.code)).map(i => ({
        def: { code: i.code, group: (i.score < 0 ? 'penalty' : 'trade') as TaskGroup },
        item: i,
      })),
    ];
    return GROUP_ORDER.map(g => ({
      group: g.key,
      labelKey: g.labelKey,
      rows: rows.filter(r => ((r.item?.score ?? 0) < 0 ? 'penalty' : r.def.group) === g.key),
    })).filter(g => g.rows.length > 0);
  }, [view]);

  const now = Date.now();
  /**
   * 这一票管的是哪天。跨 UTC 0 点那次翻页由上面锁盘计时器的"解锁那一跳"带着 reload 一起走，
   * 所以页面挂一整晚也不会出现"日期是新的、票况是旧的"
   */
  const voteDay = tomorrowUtc(new Date(now));
  const startMs = view ? new Date(view.startAt).getTime() : 0;
  const endMs = view ? new Date(view.endAt).getTime() : 0;
  const notStarted = view != null && now < startMs;
  const ended = view != null && now >= endMs;
  // 结算与否只有 status 认得：/reward 为 null 时，"还没结算"和"结算了但你一分没分到"长得一模一样
  const settled = info?.status === 'SETTLING';
  // 只有明确 SETTLING 才关签到/投票的门。/current 万一没拉到（info 为 null）就照常放行，
  // 真不该点后端会拿准确的时间窗顶回来，总好过一次网络抖动把活动页锁死
  const canAct = view != null && !notStarted && !ended && !settled;

  /**
   * "有没有分到钱这件事，现在说不准"。预估卡与领取卡必须共用这一个判断，
   * 分支顺序统一为 reward → rewardUnknown → settled → 兜底，两张卡各判各的会互相打架。
   * 带 settled：结算前 /reward 本来就返回 null，它失败没有信息量。
   */
  const rewardUnknown = rewardFailed && !reward && settled;

  const statusBadge = notStarted
    ? { text: t('campaign.status.notStarted'), variant: 'warning' as const }
    : settled
      ? { text: t('campaign.status.settled'), variant: 'success' as const }
      : ended
        ? { text: t('campaign.status.ended'), variant: 'secondary' as const }
        : { text: t('campaign.status.running'), variant: 'default' as const };

  const daysLeft = view
    ? Math.max(0, Math.ceil(((notStarted ? startMs : endMs) - now) / 86400_000))
    : 0;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      {/* ① 顶部提示：无条件显示，提前讲明白邀请码账号不参与活动。
             不要写成 {!view.me.claimable && ...}：名单已筛过 claimable 恒 true，条件永不触发 */}
      <div className="pt-card rounded-lg p-3 flex items-start gap-2.5 text-xs">
        <TriangleAlert className="w-4 h-4 shrink-0 text-warning mt-px" />
        <div className="leading-relaxed">
          <span className="font-bold text-warning">{t('campaign.notice')}</span>
          <span className="text-muted-foreground ml-1">
            {t('campaign.noticeDetail')}
          </span>
        </div>
      </div>

      {/* 骨架只在首屏出（loading && !view）。签到/投票之后走的是同一条刷新链路，
          在这儿只判 loading 的话，每天必点一次的签到会把整页连同滚动位置掀掉一遍 */}
      {loading && !view ? (
        <div className="space-y-4">
          <Skeleton className="h-24 w-full rounded-lg" />
          <Skeleton className="h-40 w-full rounded-lg" />
          <Skeleton className="h-64 w-full rounded-lg" />
        </div>
      ) : !view ? (
        // 拉失败与"确实没有活动"必须分开说：合成一句"当前没有进行中的活动"，
        // 活动期间后端抖一下就是对着用户否认活动的存在
        <Card>
          <CardContent className="p-0">
            {viewFailed ? (
              <>
                <EmptyState icon={<TriangleAlert />} text={t('campaign.loadFailed')} />
                <div className="pb-8 flex justify-center">
                  <Button variant="outline" size="sm" onClick={reload} disabled={loading}>
                    {loading
                      ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      : <RefreshCw className="w-3.5 h-3.5" />}
                    {t('common:retry')}
                  </Button>
                </div>
              </>
            ) : (
              <EmptyState icon={<Gift />} text={t('campaign.none')} />
            )}
          </CardContent>
        </Card>
      ) : (
        <>
          {/* 手里有数据、但这一轮有请求没拉到：不清屏，明说页面可能不准。
              /current 挂掉也要报 —— 它喂着状态徽标与预估卡，首屏挂掉时页面是错的但看不出来 */}
          {(viewFailed || currentFailed) && (
            <div className="pt-card rounded-lg px-3 py-2 flex items-center gap-2 text-[11px]">
              <TriangleAlert className="w-3.5 h-3.5 shrink-0 text-warning" />
              <span className="text-muted-foreground">
                {viewFailed
                  ? t('campaign.staleView')
                  : t('campaign.staleStatus')}
              </span>
              <Button variant="ghost" size="sm" className="ml-auto h-7" onClick={reload} disabled={loading}>
                {loading
                  ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  : <RefreshCw className="w-3.5 h-3.5" />}
                {t('common:retry')}
              </Button>
            </div>
          )}

          {/* ===== 活动头 ===== */}
          <Card>
            <CardContent className="pt-4 pb-4">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                <span className="p-1.5 rounded-xl bg-primary/10 text-primary"><Gift className="w-4 h-4" /></span>
                <h1 className="text-lg font-black tracking-tight">{view.campaignName}</h1>
                <Badge variant={statusBadge.variant}>{statusBadge.text}</Badge>
                <span className="num text-[11px] text-muted-foreground">
                  {fmtDateTime(view.startAt)} — {fmtDateTime(view.endAt)}
                </span>
              </div>
              <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-4">
                <div>
                  <div className="microlabel font-semibold">{t('campaign.stat.pool')}</div>
                  <div className="num text-lg font-bold mt-0.5">{fmtNum(view.prizePool)} <span className="text-xs text-muted-foreground">LDC</span></div>
                </div>
                <div>
                  <div className="microlabel font-semibold">{t('campaign.stat.participants')}</div>
                  <div className="num text-lg font-bold mt-0.5">{view.participants}</div>
                </div>
                <div>
                  <div className="microlabel font-semibold">{t('campaign.stat.eligible')}</div>
                  <div className="num text-lg font-bold mt-0.5">{fmtScore(view.eligibleTotal)}</div>
                </div>
                <div>
                  <div className="microlabel font-semibold">
                    {notStarted ? t('campaign.stat.toStart') : ended ? t('campaign.stat.ended') : t('campaign.stat.left')}
                  </div>
                  <div className="num text-lg font-bold mt-0.5">{ended ? '—' : t('campaign.days', { count: daysLeft })}</div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* ===== ② 我的积分 + 预估到手 ===== */}
          <div className="grid lg:grid-cols-[1.6fr_1fr] gap-4 items-stretch">
            <Card>
              <CardHeader className="pb-2"><CardTitle>{t('campaign.myPoints')}</CardTitle></CardHeader>
              <CardContent className="pb-4">
                <div className="flex items-baseline gap-3">
                  <span className="num text-4xl font-bold tracking-tighter tabular-nums">
                    {fmtScore(view.me.finalScore)}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {view.rank > 0 ? t('campaign.rank', { rank: view.rank }) : t('campaign.unranked')}
                  </span>
                </div>
                <div className="mt-4 pt-4 grid grid-cols-4 gap-3 border-t border-border/50">
                  <ScoreCell label={t('campaign.group.trade')} value={view.me.tradeScore} />
                  <ScoreCell label={t('campaign.group.daily')} value={view.me.dailyScore} />
                  <ScoreCell label={t('campaign.group.vote')} value={view.me.voteScore} />
                  <ScoreCell label={t('campaign.group.penalty')} value={view.me.penalty} />
                </div>
                {/* 活动进行中的分只是下限：临近结束的预测下注与最后一天的投票要隔天才结算得出 */}
                <p className="mt-3 text-[10px] text-muted-foreground leading-relaxed">
                  {t('campaign.scoreNote')}
                </p>
              </CardContent>
            </Card>

            {/* 结算后一律以 /reward 为准：预估是除法，真实分配走最大余额法补零头，两者能差一分。
                标题、数字、说明三处必须同源同序（reward → rewardUnknown → settled → 兜底），
                各判各的就会拼出"实际到手 0.00"配"奖励信息加载失败"这种自相矛盾的一屏 */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle>
                  {reward
                    ? t('campaign.payout.actual')
                    : rewardUnknown
                      ? t('campaign.payout.unknown')
                      : settled ? t('campaign.payout.actual') : t('campaign.payout.estimated')}
                </CardTitle>
              </CardHeader>
              <CardContent className="pb-4">
                <div className="flex items-baseline gap-1.5">
                  <span className="num text-3xl font-bold tracking-tighter tabular-nums text-primary">
                    {/* 说不准的时候就给"—"。这一格宁可什么都不说，也不能报一个关于别人钱的数字 */}
                    {reward
                      ? fmtNum(reward.ldcAmount)
                      : rewardUnknown ? '—' : fmtNum(settled ? 0 : view.estimatedLdc)}
                  </span>
                  <span className="text-sm font-bold text-muted-foreground">LDC</span>
                </div>
                <p className="mt-2 text-[10px] text-muted-foreground leading-relaxed">
                  {reward
                    ? t('campaign.payout.actualNote')
                    : rewardUnknown
                      ? t('campaign.payout.unknownNote')
                      : settled
                        ? t('campaign.payout.noneNote')
                        : t('campaign.payout.estimatedNote')}
                </p>
                {/* 算式只在"还有得算"的时候给：没结算、没拿到奖励行、不在说不准的状态、且分母为正。
                    不判分母的话开赛第一天全站 0 分，这里会渲染成 500.00 × 0 ÷ 0 */}
                {!reward && !rewardUnknown && !settled && view.eligibleTotal > 0 && (
                  <p className="mt-2 num text-[10px] text-muted-foreground">
                    {fmtNum(view.prizePool)} × {fmtScore(view.me.finalScore)} ÷ {fmtScore(view.eligibleTotal)}
                  </p>
                )}
              </CardContent>
            </Card>
          </div>

          {/* ===== ⑤ 领取 ===== */}
          <Card>
            <CardHeader className="pb-2"><CardTitle>{t('campaign.claim.title')}</CardTitle></CardHeader>
            <CardContent className="pb-4">
              {reward ? (
                reward.status === 'SUCCESS' ? (
                  <div className="space-y-1">
                    <div className="text-sm font-bold text-gain">
                      {t('campaign.claim.credited', { amount: fmtNum(reward.ldcAmount) })}
                    </div>
                    {reward.externalRef && (
                      <div className="num text-[10px] text-muted-foreground">
                        {t('campaign.claim.ref', { ref: reward.externalRef })}
                      </div>
                    )}
                  </div>
                ) : reward.status === 'CLAIMED' ? (
                  <div className="space-y-1">
                    <div className="text-sm font-bold">{t('campaign.claim.sending')}</div>
                    <p className="text-[11px] text-muted-foreground leading-relaxed">
                      {t('campaign.claim.sendingNote')}
                    </p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {reward.status === 'FAILED' && (
                      <div className="text-[11px] text-loss leading-relaxed">
                        {reward.errorMsg
                          ? t('campaign.claim.lastFailedMsg', { msg: reward.errorMsg })
                          : t('campaign.claim.lastFailed')}
                      </div>
                    )}
                    {/* 显示收款账号让人先确认：授权错号 = 把钱发给陌生人。
                        后端还会拿授权回来的 linux_do_id 跟当前账号核对一次，不符直接拒 */}
                    <div className="text-sm">
                      <Trans
                        ns="community"
                        i18nKey="campaign.claim.payTo"
                        values={{ name: user?.username ?? '—' }}
                        components={[
                          <strong key="name" className="font-bold" />,
                          <span key="hint" className="text-[11px] text-muted-foreground ml-2" />,
                        ]}
                      />
                    </div>
                    <p className="text-[11px] text-muted-foreground leading-relaxed">
                      {t('campaign.claim.authNote')}
                    </p>
                    <Button onClick={startClaim} disabled={redirecting}>
                      {redirecting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Gift className="w-4 h-4" />}
                      {redirecting
                        ? t('campaign.claim.redirecting')
                        : t('campaign.claim.button', { amount: fmtNum(reward.ldcAmount) })}
                    </Button>
                  </div>
                )
              ) : rewardUnknown ? (
                // 没拉到奖励行 ≠ 没分到钱。这一支要是漏了，一次超时就会告诉一个真有钱的人他没分到。
                // 判据与上面预估卡同一个 rewardUnknown，两张卡不会各说各的
                <div className="space-y-1">
                  <div className="text-sm font-bold">{t('campaign.claim.unknownTitle')}</div>
                  <p className="text-[11px] text-muted-foreground leading-relaxed">
                    {t('campaign.claim.unknownNote')}
                  </p>
                </div>
              ) : settled ? (
                // 0.00 不落行（见 CampaignSettleService），所以真参与了也可能查不到奖励行 ——
                // 这跟"你不在名单里"是两回事，别写成后者
                <div className="space-y-1">
                  <div className="text-sm font-bold">{t('campaign.claim.noneTitle')}</div>
                  <p className="text-[11px] text-muted-foreground leading-relaxed">
                    {t('campaign.claim.noneNote')}
                  </p>
                </div>
              ) : (
                <p className="text-[11px] text-muted-foreground leading-relaxed">
                  {t('campaign.claim.pending')}
                </p>
              )}
            </CardContent>
          </Card>

          {/* ===== ③ 明日投票 + 签到 ===== */}
          <div className="grid md:grid-cols-3 gap-4">
            <Card>
              <CardHeader className="pb-2"><CardTitle>{t('campaign.checkin.title')}</CardTitle></CardHeader>
              <CardContent className="pb-4 space-y-3">
                {/* disabled 里带 loading：页面不再清屏了，刷新那零点几秒里 checkedToday 还是旧的 false，
                    不锁的话手快的人能再点一次，换回一句"今天已经签到过了" */}
                <Button
                  className="w-full"
                  variant={view.checkedToday ? 'outline' : 'default'}
                  disabled={view.checkedToday || acting || loading || !canAct}
                  onClick={handleCheckin}
                >
                  {acting ? <Loader2 className="w-4 h-4 animate-spin" /> : <CalendarCheck className="w-4 h-4" />}
                  {view.checkedToday ? t('campaign.checkin.done') : t('campaign.checkin.action')}
                </Button>
                <p className="text-[10px] text-muted-foreground leading-relaxed">
                  {t('campaign.checkin.note')}
                </p>
              </CardContent>
            </Card>

            {view.voteBoard.map(v => (
              <Card key={v.symbol}>
                <CardHeader className="pb-2">
                  {/* 目标日必须摆在标题上：投的是明天，不写出来是哪天，用户只会当成今天 */}
                  <CardTitle className="flex items-baseline justify-between gap-2">
                    <span>{t('campaign.vote.title', { label: getCoin(v.symbol).name })}</span>
                    <span className="num text-[10px] tracking-normal normal-case tabular-nums">
                      UTC {voteDay}
                    </span>
                  </CardTitle>
                </CardHeader>
                <CardContent className="pb-4 space-y-2">
                  <div className="grid grid-cols-2 gap-2">
                    <VoteSide
                      dir="UP"
                      count={v.upCount}
                      mine={v.myDirection}
                      disabled={acting || loading || !canAct || voteLocked || v.myDirection !== null}
                      onClick={() => handleVote(v.symbol, 'UP')}
                    />
                    <VoteSide
                      dir="DOWN"
                      count={v.downCount}
                      mine={v.myDirection}
                      disabled={acting || loading || !canAct || voteLocked || v.myDirection !== null}
                      onClick={() => handleVote(v.symbol, 'DOWN')}
                    />
                  </div>
                  {/* 锁盘那句排在最前：按钮灰着的时候，人第一件想知道的事是"为什么点不了" */}
                  <p className="text-[10px] text-muted-foreground leading-relaxed">
                    {voteLocked
                      ? t('campaign.vote.locked')
                      : v.myDirection
                        ? t('campaign.vote.voted', {
                            day: voteDay,
                            side: v.myDirection === 'UP' ? t('campaign.vote.up') : t('campaign.vote.down'),
                          })
                        : t('campaign.vote.hint', { day: voteDay })}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>

          {/* ===== ④ 任务清单 ===== */}
          <Card className="overflow-hidden">
            <CardHeader className="pb-3">
              <CardTitle>{t('campaign.taskList')}</CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              {grouped.map(g => (
                <div key={g.group}>
                  <div className="px-4 py-1.5 bg-card-2 border-y border-border/30">
                    <span className={cn('microlabel font-bold', g.group === 'penalty' && 'text-loss')}>
                      {t(g.labelKey)}
                    </span>
                  </div>
                  {g.rows.map(r => <TaskRow key={r.def.code} def={r.def} item={r.item} />)}
                </div>
              ))}
            </CardContent>
          </Card>

          {/* ===== 计分细则：占位制 + 两个收益率的口径 ===== */}
          {/* 文案与后端同源：合约 = CampaignStatsMapper.listClosedPositions（与仓位历史页同口径），
              占位 = ScoreRules.roiLadder，现货 = TradeScorer.countSpotUnits。
              改口径时两边一起改，别让页面变成过期承诺 */}
          <Card>
            <CardHeader className="pb-3"><CardTitle>{t('campaign.rules.title')}</CardTitle></CardHeader>
            <CardContent className="pb-4 space-y-4 text-[11px] leading-relaxed text-muted-foreground">
              <div className="space-y-1.5">
                <div className="microlabel font-bold text-foreground">{t('campaign.rules.roiTitle')}</div>
                <div className="num rounded-md bg-card-2 px-2.5 py-1.5 text-foreground">
                  {t('campaign.rules.roiFormula')}
                </div>
                <p>{t('campaign.rules.roiDef')}</p>
                <p>
                  <Trans
                    ns="community"
                    i18nKey="campaign.rules.roiClosed"
                    components={[<strong key="closed" className="font-bold text-foreground" />]}
                  />
                </p>
                <p>
                  <Trans
                    ns="community"
                    i18nKey="campaign.rules.roiLadder"
                    components={[<strong key="ladder" className="font-bold text-foreground" />]}
                  />
                </p>
                <p className="text-foreground/80">{t('campaign.rules.roiExample')}</p>
              </div>
              <div className="space-y-1.5">
                <div className="microlabel font-bold text-foreground">{t('campaign.rules.spotTitle')}</div>
                <div className="num rounded-md bg-card-2 px-2.5 py-1.5 text-foreground">
                  {t('campaign.rules.spotFormula')}
                </div>
                <p>
                  <Trans
                    ns="community"
                    i18nKey="campaign.rules.spotDef"
                    components={[
                      <strong key="symbol" className="font-bold text-foreground" />,
                      <strong key="single" className="font-bold text-foreground" />,
                      <strong key="realized" className="font-bold text-foreground" />,
                    ]}
                  />
                </p>
                <p>
                  <Trans
                    ns="community"
                    i18nKey="campaign.rules.spotStep"
                    components={[<strong key="step" className="font-bold text-foreground" />]}
                  />
                </p>
                <p>
                  <Trans
                    ns="community"
                    i18nKey="campaign.rules.spotSymbols"
                    components={[<span key="crypto" className="num" />, <span key="bstock" className="num" />]}
                  />
                </p>
                <p className="text-foreground/80">{t('campaign.rules.spotExample')}</p>
              </div>
              <p>
                <Trans
                  ns="community"
                  i18nKey="campaign.rules.fillTime"
                  components={[<strong key="fill" className="font-bold text-foreground" />]}
                />
              </p>
            </CardContent>
          </Card>

          {/* ===== 积分榜 ===== */}
          <Card className="overflow-hidden">
            <CardHeader className="pb-3">
              <CardTitle className="flex items-center gap-2">
                <Trophy className="w-3.5 h-3.5" />{t('campaign.board.title')}
              </CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              {board.length === 0 ? (
                <EmptyState
                  icon={boardFailed ? <TriangleAlert /> : <Trophy />}
                  text={boardFailed ? t('campaign.board.failed') : t('campaign.board.empty')}
                />
              ) : (
                board.slice(0, 20).map((s, i) => (
                  <div
                    key={s.userId}
                    className={cn(
                      'flex items-center gap-3 px-4 py-2.5 border-b border-border/25 last:border-b-0',
                      s.userId === user?.id && 'bg-primary/8',
                    )}
                  >
                    <span className="num text-[11px] text-muted-foreground w-6 shrink-0 tabular-nums">
                      {i + 1}
                    </span>
                    <span className="text-[13px] font-semibold truncate flex-1 min-w-0">
                      {s.username || `#${s.userId}`}
                    </span>
                    <span className="num text-[13px] font-bold tabular-nums shrink-0">
                      {fmtScore(s.finalScore)}
                    </span>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
