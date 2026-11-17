import { useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { cn } from '../lib/utils';

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
  { id: 'wallets', qKey: 'faq.wallets.q', aKey: 'faq.wallets.a' },
  // ---- 账户 ----
  { id: 'resetAccount', qKey: 'faq.resetAccount.q', aKey: 'faq.resetAccount.a' },
];

/**
 * 首页新手教学：几列手风琴。条目按顺序轮流发到各列，每列自己往下排——点开一条只顶开它下面的，不动旁边那列。
 */
export function HomeFaq() {
  const { t } = useTranslation('home');
  // 按屏宽分几列：大屏 3、中屏 2、手机 1（断点同 pages/Home.css）
  const narrow = useMediaQuery('(max-width:1180px)');
  const phone = useMediaQuery('(max-width:760px)');
  const cols = phone ? 1 : narrow ? 2 : 3;
  // 开着的条目：换列数时条目会挪到别的列里，开合状态得留在外面
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());

  const toggle = (id: string) => setOpen(prev => {
    const next = new Set(prev);
    if (!next.delete(id)) next.add(id);
    return next;
  });

  return (
    <>
      <div className="sec-h"><h2>{t('faq.title')}</h2></div>
      <div className="faq">
        {Array.from({ length: cols }, (_, c) => (
          <div key={c}>
            {FAQ_ITEMS.filter((_, i) => i % cols === c).map(item => (
              <div key={item.id} className="fq">
                <button type="button" aria-expanded={open.has(item.id)} onClick={() => toggle(item.id)}>
                  {t(item.qKey)}
                  <ChevronRight className="ic" />
                </button>
                <div className={cn('col', open.has(item.id) && 'open')}><div><p>{t(item.aKey)}</p></div></div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}
