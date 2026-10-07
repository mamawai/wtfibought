import { ChevronRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useStagger } from '../hooks/useStagger';

/**
 * 教学条目：概念名 + 白话解释（结合本站玩法），按 交易基础 → 合约风险 → 玩法 → 账户 排布。
 * 数组在组件外拿不到 t，所以存词表 key 不存文案；key 写成字面量才 grep 得到。
 */
const FAQ_ITEMS: { id: string; qKey: string; aKey: string }[] = [
  // ---- 交易基础 ----
  { id: 'spotVsFutures', qKey: 'faq.spotVsFutures.q', aKey: 'faq.spotVsFutures.a' },
  { id: 'leverage', qKey: 'faq.leverage.q', aKey: 'faq.leverage.a' },
  { id: 'crossVsIsolated', qKey: 'faq.crossVsIsolated.q', aKey: 'faq.crossVsIsolated.a' },
  // ---- 合约风险 ----
  { id: 'markPrice', qKey: 'faq.markPrice.q', aKey: 'faq.markPrice.a' },
  { id: 'liqPrice', qKey: 'faq.liqPrice.q', aKey: 'faq.liqPrice.a' },
  { id: 'mmr', qKey: 'faq.mmr.q', aKey: 'faq.mmr.a' },
  { id: 'fundingRate', qKey: 'faq.fundingRate.q', aKey: 'faq.fundingRate.a' },
  { id: 'tpSl', qKey: 'faq.tpSl.q', aKey: 'faq.tpSl.a' },
  { id: 'doubleLiq', qKey: 'faq.doubleLiq.q', aKey: 'faq.doubleLiq.a' },
  { id: 'bankrupt', qKey: 'faq.bankrupt.q', aKey: 'faq.bankrupt.a' },
  // ---- 玩法 ----
  { id: 'prediction', qKey: 'faq.prediction.q', aKey: 'faq.prediction.a' },
  { id: 'dailyVote', qKey: 'faq.dailyVote.q', aKey: 'faq.dailyVote.a' },
  { id: 'wallets', qKey: 'faq.wallets.q', aKey: 'faq.wallets.a' },
  { id: 'campaign', qKey: 'faq.campaign.q', aKey: 'faq.campaign.a' },
  // ---- 账户 ----
  { id: 'resetAccount', qKey: 'faq.resetAccount.q', aKey: 'faq.resetAccount.a' },
];

/** 首页新手教学：三列手风琴（原生 details，无 JS 状态） */
export function HomeFaq() {
  const { t } = useTranslation('home');
  const gridRef = useStagger<HTMLDivElement>();

  return (
    <>
      <div className="sec-h">
        <h2>{t('faq.title')}</h2>
      </div>
      <div ref={gridRef} className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-11 gap-y-0">
        {FAQ_ITEMS.map(item => (
          <details key={item.id} className="group">
            <summary className="hov flex justify-between items-center gap-3 py-3.5 border-b border-border text-[15px] font-medium cursor-pointer list-none select-none [&::-webkit-details-marker]:hidden">
              {t(item.qKey)}
              <ChevronRight className="ic mute shrink-0 transition-transform group-open:rotate-90" />
            </summary>
            <p className="pb-3 text-sm text-muted-foreground leading-[1.6]">{t(item.aKey)}</p>
          </details>
        ))}
      </div>
    </>
  );
}
