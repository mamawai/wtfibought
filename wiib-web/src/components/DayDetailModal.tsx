import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader } from './ui/dialog';
import { cn, fmtMoney } from '../lib/utils';
import type { AssetSnapshot } from '../types';

/** 四分类日差，字段直接来自快照 DTO，不用再算。数组在组件外，标签存词表 key 渲染时再查 */
const BUCKETS = [
  { key: 'dailyBstockProfit', labelKey: 'dayDetail.stocks' },
  { key: 'dailyCryptoProfit', labelKey: 'dayDetail.crypto' },
  { key: 'dailyCommodityProfit', labelKey: 'dayDetail.commodity' },
  { key: 'dailyPredictionProfit', labelKey: 'dayDetail.prediction' },
] as const;

interface Props {
  /** 选中的日期 yyyy-MM-dd；null 即关闭 */
  date: string | null;
  /** 当天快照。父组件手上已有整月数据，没必要为了一天再问一次后端 */
  snapshot: AssetSnapshot | null;
  onClose: () => void;
}

/**
 * 月度网格点某一天的下钻弹窗：当天赚亏多少，以及这个数由哪几类拼出来的。
 * 数据全部来自父组件已有的当月快照，弹窗自己不发请求。
 */
export function DayDetailModal({ date, snapshot, onClose }: Props) {
  // hook 得在提前 return 之前调，否则 date 由 null 变成有值时 hook 调用顺序就变了
  const { t, i18n } = useTranslation('home');

  if (!date) return null;

  const pnl = snapshot?.dailyProfit ?? 0;
  const up = pnl >= 0;
  // 月份名/星期名跟着界面语言走，钉死 zh-CN 会在英文界面漏出"8月20日星期三"。
  // 取 resolvedLanguage：language 可能是没落在支持列表里的原始值（与 lib/utils.ts 同口径）
  const title = new Date(`${date}T00:00:00`).toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  });

  return (
    <Dialog open onClose={onClose} className="max-w-sm">
      <DialogHeader className="pb-3 border-b border-border/40">
        <div className="text-xs text-muted-foreground">{title}</div>
        <div className="flex items-baseline gap-2 mt-1">
          <span className={cn('num text-2xl font-black', up ? 'text-gain' : 'text-loss')}>
            {up ? '+' : ''}{fmtMoney(pnl)}
          </span>
          {snapshot?.dailyProfitPct != null && (
            <span className={cn('num text-xs font-bold', up ? 'text-gain' : 'text-loss')}>
              {up ? '+' : ''}{snapshot.dailyProfitPct.toFixed(2)}%
            </span>
          )}
        </div>
        <div className="text-[11px] text-muted-foreground mt-0.5">{t('dayDetail.note')}</div>
      </DialogHeader>

      <DialogContent className="pt-3">
        <div className="space-y-2">
          {BUCKETS.map(({ key, labelKey }) => {
            const v = snapshot?.[key] ?? 0;
            return (
              <div key={key} className="flex items-baseline justify-between">
                <span className="text-xs text-muted-foreground">{t(labelKey)}</span>
                {v === 0 ? (
                  <span className="num text-sm text-muted-foreground/40">—</span>
                ) : (
                  <span className={cn('num text-sm font-bold', v > 0 ? 'text-gain' : 'text-loss')}>
                    {v > 0 ? '+' : ''}{fmtMoney(v)}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      </DialogContent>
    </Dialog>
  );
}
