import { useState } from 'react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  BarChart3, Bomb, ChevronRight, Coins, Dices, Gem, Rocket,
  ShieldAlert, Target, UserSearch,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Sparkline } from '../fx/Sparkline';
import { cn } from '../../lib/utils';
import type { BehaviorAnalysisReport } from '../../types';

/** 风险等级底色。模型写的是中英文字面量（保守/稳健/激进/赌徒），认不出就走中性 */
const RISK_TONE: Record<string, string> = {
  HIGH: 'bg-loss/15 text-loss',
  MEDIUM: 'bg-warning/15 text-warning',
  LOW: 'bg-gain/15 text-gain',
};

/** 带符号盈亏文本 + 颜色 tone */
function pnl(v: number): { text: string; tone: 'gain' | 'loss' } {
  return { text: `${v >= 0 ? '+' : ''}${v.toFixed(2)}`, tone: v >= 0 ? 'gain' : 'loss' };
}

/**
 * 金额文本。报告是模型照 schema 填的 JSON，后端只校验了几个关键字段，
 * 其余数字模型漏填就缺席（null 不下发），缺了显示横杠
 */
function usd(v: number | undefined): string {
  return v == null ? '—' : `$${v.toLocaleString()}`;
}

function Metric({ label, value, tone }: { label: string; value: ReactNode; tone?: 'gain' | 'loss' }) {
  return (
    <div className="min-w-0">
      <div className={cn('text-xs font-bold tabular-nums truncate',
        tone === 'gain' && 'text-gain', tone === 'loss' && 'text-loss')}>
        {value}
      </div>
      <div className="text-[10px] text-muted-foreground mt-0.5 truncate">{label}</div>
    </div>
  );
}

/** 明细里的一个品类：标题条 + 2 列指标网格。窄栏里 2 列是上限，3 列开始截字 */
function CategoryBlock({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children: ReactNode }) {
  return (
    <div className="rounded-md border border-border bg-card px-2.5 py-2">
      <div className="text-[10px] font-black text-muted-foreground flex items-center gap-1.5 mb-2">
        <Icon className="w-3 h-3" /> {title}
      </div>
      <div className="grid grid-cols-2 gap-x-2.5 gap-y-2">{children}</div>
    </div>
  );
}

/**
 * 行为分析报告卡：模型调 analyze_my_behavior 后随 SSE 到，摆在对话流里。
 * <p>
 * <b>为什么不是把数字全铺开</b>：这张卡活在对话气泡的宽度里（窄栏），而模型紧接着还会
 * 就着报告说一段话。所以卡只负责"一眼能看懂的那层"——总资产/收益率/30 天曲线/风险/建议，
 * 逐品类的明细收进折叠区，想看再点。解读归模型，数据归卡。
 * <p>
 * 30 天资产曲线是新加的：这段数据一直在报告里（overview.trend），旧的整页 UI 从没用过。
 */
export function BehaviorReportCard({ report }: { report: BehaviorAnalysisReport }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);

  const { overview, tradeBehavior, gameBehavior, riskProfile, suggestions } = report;
  // 模型漏填的点跳过：混进一个缺数，整条曲线就画不出来
  const trend = overview.trend?.map(p => p.totalAssets).filter(v => Number.isFinite(v)) ?? [];
  const up = overview.totalProfitPct >= 0;

  return (
    <div className="rounded-xl border border-border border-l-[3px] border-l-primary bg-card overflow-hidden">
      {/* 标头：一行说清"这是什么 + 你是哪一类" */}
      <div className="flex items-center gap-2 px-3.5 py-2.5 border-b border-border">
        <UserSearch className="w-4 h-4 text-primary shrink-0" />
        <span className="text-xs font-black">{t('behavior.title')}</span>
        <span className={cn('ml-auto text-[10px] font-black px-2 py-0.5 rounded-full shrink-0',
          RISK_TONE[riskProfile.riskLevel] || 'bg-muted text-muted-foreground')}>
          {riskProfile.riskLevel}
        </span>
      </div>

      <div className="p-3.5 space-y-3">
        {/* 两个大数字压在曲线上：曲线只做背景纹理，不标刻度——它答的是"走成什么形状"，不是"某天多少钱" */}
        <div className="relative rounded-lg border border-border bg-card-2 px-3 py-2.5 overflow-hidden">
          {trend.length > 1 && (
            <Sparkline
              data={trend}
              dot={false}
              className="absolute inset-x-0 bottom-0 h-10 w-full opacity-60 pointer-events-none"
            />
          )}
          <div className="relative flex items-end gap-4">
            <div className="min-w-0">
              <div className="text-lg font-black tabular-nums leading-tight truncate">
                ${overview.totalAssets.toLocaleString()}
              </div>
              <div className="text-[10px] text-muted-foreground mt-0.5">{t('behavior.totalAssets')}</div>
            </div>
            <div className="min-w-0">
              <div className={cn('text-lg font-black tabular-nums leading-tight', up ? 'text-gain' : 'text-loss')}>
                {up ? '+' : ''}{overview.totalProfitPct.toFixed(2)}%
              </div>
              <div className="text-[10px] text-muted-foreground mt-0.5">{t('behavior.totalReturn')}</div>
            </div>
          </div>
        </div>

        {/* 资产分布：chips 比饼图诚实——品类数不定，两三个品类的饼图纯属装饰 */}
        {overview.distribution.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {overview.distribution.map((d, i) => (
              <span key={i} className="border border-border rounded-full px-2 py-0.5 text-[10px] font-bold tabular-nums">
                <span className="text-muted-foreground">{d.category}</span> {usd(d.value)}
              </span>
            ))}
          </div>
        )}

        {/* 风险画像：等级已经在标头，这里只补两个可量化的事实 */}
        <div className="flex items-center gap-4 rounded-md border border-border bg-card-2 px-3 py-2">
          <ShieldAlert className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          <Metric label={t('behavior.bankruptCount')} value={riskProfile.bankruptCount} />
          <Metric label={t('behavior.maxDrawdown')} value={riskProfile.maxDrawdown} />
        </div>

        {/* 建议：报告里唯一的"话"，不折叠 */}
        {suggestions.length > 0 && (
          <ul className="space-y-1.5">
            {suggestions.map((s, i) => (
              <li key={i} className="flex items-start gap-2 text-[11.5px] leading-relaxed">
                <span className="mt-[7px] w-1 h-1 rounded-full bg-primary shrink-0" />
                {s}
              </li>
            ))}
          </ul>
        )}

        {/* 明细：逐品类的数字，想看再展开 */}
        <div>
          <button
            onClick={() => setOpen(v => !v)}
            className="flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground hover:text-foreground py-0.5"
          >
            <ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />
            {t('behavior.detail')}
          </button>
          <div className={cn('grid transition-[grid-template-rows] duration-300 ease-out',
            open ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]')}>
            <div className="overflow-hidden min-h-0">
              <div className="mt-2 space-y-1.5">
                {/* 空品类整块不画：没玩过的东西列一排 0 只是噪音 */}
                {tradeBehavior.crypto.positionCount > 0 && (
                  <CategoryBlock icon={Coins} title={t('behavior.crypto')}>
                    <Metric label={t('behavior.positions')} value={tradeBehavior.crypto.positionCount} />
                    <Metric label={t('behavior.leverage')} value={tradeBehavior.crypto.leverageUsage} />
                    <Metric label={t('behavior.buy')} value={usd(tradeBehavior.crypto.totalBuyAmount)} />
                    <Metric label={t('behavior.sell')} value={usd(tradeBehavior.crypto.totalSellAmount)} />
                  </CategoryBlock>
                )}
                {tradeBehavior.bstock.positionCount > 0 && (
                  <CategoryBlock icon={BarChart3} title={t('behavior.bstock')}>
                    <Metric label={t('behavior.positions')} value={tradeBehavior.bstock.positionCount} />
                    <Metric label={t('behavior.buy')} value={usd(tradeBehavior.bstock.totalBuyAmount)} />
                    <Metric label={t('behavior.sell')} value={usd(tradeBehavior.bstock.totalSellAmount)} />
                  </CategoryBlock>
                )}
                {tradeBehavior.futures.orderCount > 0 && (
                  <CategoryBlock icon={Rocket} title={t('behavior.futures')}>
                    <Metric label={t('behavior.orders')} value={tradeBehavior.futures.orderCount} />
                    <Metric label={t('behavior.direction')} value={tradeBehavior.futures.direction} />
                    <Metric label={t('behavior.realizedPnl')} {...pnlProps(tradeBehavior.futures.realizedPnl)} />
                    <Metric label={t('behavior.avgLeverage')} value={`${tradeBehavior.futures.avgLeverage}x`} />
                    {(['crypto', 'commodity', 'tradfi'] as const).map(cat => {
                      const c = tradeBehavior.futures.byCategory?.[cat];
                      if (!c || c.orderCount <= 0) return null;
                      const label = cat === 'crypto' ? t('behavior.cryptoPnl')
                        : cat === 'commodity' ? t('behavior.commodityPnl') : t('behavior.stockPnl');
                      return <Metric key={cat} label={label} {...pnlProps(c.realizedPnl)} />;
                    })}
                  </CategoryBlock>
                )}
                {tradeBehavior.prediction.frequency > 0 && (
                  <CategoryBlock icon={Target} title={t('behavior.prediction')}>
                    <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: tradeBehavior.prediction.frequency })} />
                    <Metric label={t('behavior.winRate')} value={`${tradeBehavior.prediction.winRate}%`} />
                    <Metric label={t('behavior.netPnl')} {...pnlProps(tradeBehavior.prediction.netProfit)} />
                    <Metric label={t('behavior.preference')} value={tradeBehavior.prediction.directionPreference} />
                  </CategoryBlock>
                )}
                {/* gameBehavior 是软字段，模型可能整块不给 */}
                {gameBehavior?.blackjack?.totalHands > 0 && (
                  <CategoryBlock icon={Dices} title="Blackjack">
                    <Metric label={t('behavior.hands')} value={gameBehavior.blackjack.totalHands} />
                    <Metric label={t('behavior.biggestWin')} value={`$${gameBehavior.blackjack.biggestWin}`} />
                    <Metric label={t('behavior.won')} value={gameBehavior.blackjack.totalWon} tone="gain" />
                    <Metric label={t('behavior.lost')} value={gameBehavior.blackjack.totalLost} tone="loss" />
                  </CategoryBlock>
                )}
                {gameBehavior?.mines?.frequency > 0 && (
                  <CategoryBlock icon={Bomb} title={t('behavior.mines')}>
                    <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: gameBehavior.mines.frequency })} />
                    <Metric label={t('behavior.netPnl')} {...pnlProps(gameBehavior.mines.netProfit)} />
                  </CategoryBlock>
                )}
                {gameBehavior?.videoPoker?.frequency > 0 && (
                  <CategoryBlock icon={Gem} title={t('behavior.videoPoker')}>
                    <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: gameBehavior.videoPoker.frequency })} />
                    <Metric label={t('behavior.netPnl')} {...pnlProps(gameBehavior.videoPoker.netProfit)} />
                  </CategoryBlock>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Metric 的 value+tone 一对：盈亏字段每处都要这两个值，散着写四五遍。缺数同 usd 显示横杠 */
function pnlProps(v: number | undefined) {
  if (v == null) return { value: '—' };
  const p = pnl(v);
  return { value: p.text, tone: p.tone };
}
