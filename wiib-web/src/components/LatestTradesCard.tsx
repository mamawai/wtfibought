import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { fmtNum, fmtTime } from '../lib/utils';
import { orderSideView } from '../lib/orderSide';

/**
 * 一条成交要的最少信息：只装后端原始值，方向标签和展示名一律留到渲染期算。
 * 存译好的字会把文案冻在拉数那一刻，之后切语言这批卡片就不跟着变了。
 */
export interface TradeItem {
  id: string;
  /** 后端原始方向枚举（BUY/SELL/OPEN_LONG/...），渲染时交给 orderSideView 现算标签与配色 */
  orderSide: string;
  /** 去掉 USDT 的标的符号，既当展示名也当数量单位 */
  base: string;
  /** 合约单：展示名要带「合约」后缀，现货不带 */
  isFutures?: boolean;
  quantity: number | string;
  filledAmount?: number;
  createdAt: string;
  isAi?: boolean;
}

interface Props {
  trades: TradeItem[];
  loading: boolean;
}

/** 首页最新成交（与快讯并列；样式见 pages/Home.css .tr）：只给最新 20 条，全量分页在 /trades */
export function LatestTradesCard({ trades, loading }: Props) {
  // 「全部」全站通用，直接复用 common，不在本 ns 再写一份
  const { t } = useTranslation(['home', 'common']);

  return (
    <>
      <div className="sec-h">
        <h2>{t('trades.title')}</h2>
        <Link to="/trades" className="go">{t('common:all')}<ChevronRight className="ic" /></Link>
      </div>
      {loading ? (
        <div className="feed">
          {Array.from({ length: 5 }).map((_, i) => <i key={i} className="sk" style={{ height: 22 }} />)}
        </div>
      ) : trades.length === 0 ? (
        <div className="empty"><b>{t('trades.empty')}</b></div>
      ) : (
        <div className="feed tl">
          {trades.map(item => {
            // 方向标签渲染期现算：本组件订阅了 useTranslation，切语言会重渲染，这里就跟着重查一遍词表
            const { label, tone } = orderSideView(item.orderSide);
            return (
              <div key={item.id} className="tr">
                <span className={`sd ${tone === 'buy' ? 'up' : 'dn'}`}>{label}</span>
                <span className="nm">
                  {item.isFutures ? t('trades.futuresName', { base: item.base }) : item.base}
                  {item.isAi && <span className="tag sm" title={t('trades.aiTrader')}>AI</span>}
                </span>
                <span className="amt">
                  {item.filledAmount == null ? `${item.quantity}${item.base}` : `₮${fmtNum(item.filledAmount, 1)}`}
                  <span>{fmtTime(item.createdAt)}</span>
                </span>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
