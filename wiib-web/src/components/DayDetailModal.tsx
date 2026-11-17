import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Dialog, DialogHeader } from './ui/dialog';
import { BigNum } from './ui/big-num';
import { cn, fmtSignedPct, fmtSignedUsd } from '../lib/utils';
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
  /** 翻到前 / 后一个有数的日子；到头了就不传，那颗钮置灰 */
  onPrev?: () => void;
  onNext?: () => void;
  onClose: () => void;
}

/**
 * 月历点某一天的弹窗：当天赚亏多少，以及这个数由哪几类拼出来的；右上角能直接前后翻。
 * 数据全部来自父组件已有的当月快照，弹窗自己不发请求。
 */
export function DayDetailModal({ date, snapshot, onPrev, onNext, onClose }: Props) {
  // hook 得在提前 return 之前调，否则 date 由 null 变成有值时 hook 调用顺序就变了
  const { t, i18n } = useTranslation('home');

  if (!date) return null;

  const pnl = snapshot?.dailyProfit ?? 0;
  const tone = pnl >= 0 ? 'up' : 'dn';
  // 月份名/星期名跟着界面语言走，钉死 zh-CN 会在英文界面漏出"8月20日星期三"。
  // 取 resolvedLanguage：language 可能是没落在支持列表里的原始值（与 lib/utils.ts 同口径）
  const title = new Date(`${date}T00:00:00`).toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  });

  return (
    <Dialog open onClose={onClose} className="sm day-dlg">
      <DialogHeader actions={
        <>
          <button type="button" className="xb" disabled={!onPrev} onClick={onPrev} aria-label={t('dayDetail.prevDay')}><ChevronLeft className="ic" /></button>
          <button type="button" className="xb" disabled={!onNext} onClick={onNext} aria-label={t('dayDetail.nextDay')}><ChevronRight className="ic" /></button>
        </>
      }>
        <div className="dd-date">{title}</div>
        <div className="dd-pnl">
          <b className={cn('big nf', tone)}><BigNum value={pnl} signed /></b>
          {snapshot?.dailyProfitPct != null && <span className={tone}>{fmtSignedPct(snapshot.dailyProfitPct)}</span>}
        </div>
        <div className="dd-note">{t('dayDetail.note')}</div>
      </DialogHeader>

      <div className="kv">
        {BUCKETS.map(({ key, labelKey }) => {
          const v = snapshot?.[key] ?? 0;
          return (
            <div key={key}>
              <span>{t(labelKey)}</span>
              {v === 0 ? <b className="faint">—</b> : <b className={v > 0 ? 'up' : 'dn'}>{fmtSignedUsd(v)}</b>}
            </div>
          );
        })}
      </div>
    </Dialog>
  );
}
