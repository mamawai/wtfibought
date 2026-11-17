import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ChartColumn, ChartLine, Coins, ShieldAlert, Sparkles, Target, UserSearch, type LucideIcon } from 'lucide-react';
import { Sparkline } from '../fx/Sparkline';
import { cn } from '../../lib/utils';
import type { BehaviorAnalysisReport } from '../../types';

/** 风险等级的标签色。模型写的是 HIGH/MEDIUM/LOW，认不出就走中性 */
const RISK_TONE: Record<string, string> = { HIGH: 'r', MEDIUM: 'w', LOW: 'g' };

/** 资产分布条按品类出现的顺序取色 */
const DIST_COLORS = ['var(--color-primary)', 'var(--color-info)', 'var(--color-gain)', 'var(--color-warning)', 'var(--color-line2)'];

/**
 * 金额文本。报告是模型照 schema 填的 JSON，后端只校验了几个关键字段，
 * 其余数字模型漏填就缺席（null 不下发），缺了显示横杠
 */
function usd(v: number | undefined): string {
  return v == null ? '—' : `₮${v.toLocaleString()}`;
}

/** 盈亏：带符号、涨绿跌红；缺数同 usd 显示横杠 */
function pnl(v: number | undefined): { value: string; tone?: 'up' | 'dn' } {
  if (v == null) return { value: '—' };
  return { value: `${v >= 0 ? '+' : ''}${v.toFixed(2)}`, tone: v >= 0 ? 'up' : 'dn' };
}

/** 一个指标：灰标签 + 墨色的数 */
function Metric({ label, value, tone }: { label: string; value: ReactNode; tone?: 'up' | 'dn' }) {
  return <span>{label} <b className={tone}>{value}</b></span>;
}

/** 一类一节：图标标题 + 一行指标 */
function Category({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children: ReactNode }) {
  return (
    <div className="ccat">
      <b><Icon className="ic" />{title}</b>
      <div className="row">{children}</div>
    </div>
  );
}

/**
 * 行为分析报告卡（样式见 ChatMessages.css .ccard/.cbr/.ccat）：模型调 analyze_my_behavior 后随 SSE 到，摆在对话流里。
 * 卡只负责数据——总资产/收益率/资产分布/30 天走势，逐品类一节，再是风险画像和建议；解读归模型，它紧接着会就着报告说一段话。
 */
export function BehaviorReportCard({ report }: { report: BehaviorAnalysisReport }) {
  const { t } = useTranslation('ai');
  const { overview, tradeBehavior, riskProfile, suggestions } = report;
  // 模型漏填的点跳过：混进一个缺数，整条曲线就画不出来
  const trend = overview.trend?.map(p => p.totalAssets).filter(v => Number.isFinite(v)) ?? [];
  const up = overview.totalProfitPct >= 0;
  const distTotal = overview.distribution.reduce((s, d) => s + d.value, 0);
  const { crypto, bstock, futures, prediction } = tradeBehavior;

  return (
    <div className="ccard">
      <h4>
        <UserSearch className="ic" />{t('behavior.title')}
        <span className={cn('tag sm', RISK_TONE[riskProfile.riskLevel])}>{riskProfile.riskLevel}</span>
      </h4>

      <div className="cbr">
        <div><span>{t('behavior.totalAssets')}</span><b className="num">₮{overview.totalAssets.toLocaleString()}</b></div>
        <div>
          <span>{t('behavior.totalReturn')}</span>
          <b className={cn('num', up ? 'up' : 'dn')}>{up ? '+' : ''}{overview.totalProfitPct.toFixed(2)}%</b>
        </div>
        {distTotal > 0 && (
          <>
            <div className="bar">
              {overview.distribution.map((d, i) => (
                <i key={i} style={{ width: `${(d.value / distTotal) * 100}%`, background: DIST_COLORS[i % DIST_COLORS.length] }} />
              ))}
            </div>
            <div className="lg">
              {overview.distribution.map((d, i) => (
                <span key={i} title={usd(d.value)}>
                  <i style={{ background: DIST_COLORS[i % DIST_COLORS.length] }} />{d.category} {Math.round((d.value / distTotal) * 100)}%
                </span>
              ))}
            </div>
          </>
        )}
        {trend.length > 1 && <Sparkline data={trend} dot={false} className="trend" />}
      </div>

      {/* 空品类整节不画：没玩过的东西列一排 0 只是噪音 */}
      {crypto.positionCount > 0 && (
        <Category icon={Coins} title={t('behavior.crypto')}>
          <Metric label={t('behavior.positions')} value={crypto.positionCount} />
          <Metric label={t('behavior.leverage')} value={crypto.leverageUsage} />
          <Metric label={t('behavior.buy')} value={usd(crypto.totalBuyAmount)} />
          <Metric label={t('behavior.sell')} value={usd(crypto.totalSellAmount)} />
        </Category>
      )}
      {bstock.positionCount > 0 && (
        <Category icon={ChartColumn} title={t('behavior.bstock')}>
          <Metric label={t('behavior.positions')} value={bstock.positionCount} />
          <Metric label={t('behavior.buy')} value={usd(bstock.totalBuyAmount)} />
          <Metric label={t('behavior.sell')} value={usd(bstock.totalSellAmount)} />
        </Category>
      )}
      {futures.orderCount > 0 && (
        <Category icon={ChartLine} title={t('behavior.futures')}>
          <Metric label={t('behavior.orders')} value={futures.orderCount} />
          <Metric label={t('behavior.direction')} value={futures.direction} />
          <Metric label={t('behavior.avgLeverage')} value={`${futures.avgLeverage}x`} />
          <Metric label={t('behavior.realizedPnl')} {...pnl(futures.realizedPnl)} />
          {(['crypto', 'commodity', 'tradfi'] as const).map(cat => {
            const c = futures.byCategory?.[cat];
            if (!c || c.orderCount <= 0) return null;
            const label = cat === 'crypto' ? t('behavior.cryptoPnl')
              : cat === 'commodity' ? t('behavior.commodityPnl') : t('behavior.stockPnl');
            return <Metric key={cat} label={label} {...pnl(c.realizedPnl)} />;
          })}
        </Category>
      )}
      {prediction.frequency > 0 && (
        <Category icon={Target} title={t('behavior.prediction')}>
          <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: prediction.frequency })} />
          <Metric label={t('behavior.winRate')} value={`${prediction.winRate}%`} />
          <Metric label={t('behavior.netPnl')} {...pnl(prediction.netProfit)} />
          <Metric label={t('behavior.preference')} value={prediction.directionPreference} />
        </Category>
      )}
      <Category icon={ShieldAlert} title={t('behavior.riskProfile')}>
        <Metric label={t('behavior.bankruptCount')} value={riskProfile.bankruptCount} />
        <Metric label={t('behavior.maxDrawdown')} value={riskProfile.maxDrawdown} />
      </Category>
      {suggestions.length > 0 && (
        <div className="ccat">
          <b><Sparkles className="ic" />{t('behavior.suggestions')}</b>
          <ul>{suggestions.map((s, i) => <li key={i}>{s}</li>)}</ul>
        </div>
      )}
    </div>
  );
}
