import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { COIN_MAP, COMMODITY_LIST, formatCoinPrice } from '../lib/coinConfig';
import { useCoinQuote, useStockQuote } from '../hooks/useQuote';
import { bstockApi } from '../api';
import type { BStock } from '../types';

/** 一行报价：名字 + 灰色副名 / 现价 / 涨跌幅，整行可点直达交易页（样式见 pages/Home.css .q） */
function QuoteRow({ to, name, sub, price, pct }: {
  to: string;
  name: string;
  sub: string;
  /** 已经格式化好的价；null = 还没数 */
  price: string | null;
  pct: number | null;
}) {
  return (
    <Link to={to} className="q">
      <b>{name}<span>{sub}</span></b>
      <i>{price ?? '—'}</i>
      {/* 没数就一个破折号，别画成 0% */}
      <em className={pct == null ? 'mute' : pct >= 0 ? 'up' : 'dn'}>
        {pct == null ? '—' : `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`}
      </em>
    </Link>
  );
}

/** 币种/商品/TradFi 一行 */
function CoinRow({ symbol }: { symbol: string }) {
  const cfg = COIN_MAP[symbol];
  const q = useCoinQuote(symbol);
  return <QuoteRow to={q.to} name={cfg.name} sub={cfg.pair} pct={q.pct}
                   price={q.price == null ? null : formatCoinPrice(symbol, q.price)} />;
}

/** 代币化美股一行：代号当主名，公司名作灰字 */
function StockRow({ stock }: { stock: BStock }) {
  const q = useStockQuote(stock);
  return <QuoteRow to={q.to} name={stock.ticker} sub={stock.name} pct={q.pct}
                   price={q.price == null ? null : q.price.toLocaleString('en-US', { maximumFractionDigits: 2 })} />;
}

/** 分类栏头：分类名 + 副标题 + 右侧「全部」 */
function CatHead({ title, sub, to }: { title: string; sub: string; to: string }) {
  const { t } = useTranslation('common');
  return (
    <h3>
      {title}
      <span>{sub}</span>
      <Link to={to} className="go">{t('all')}<ChevronRight className="ic" /></Link>
    </h3>
  );
}

/**
 * 首页市场：股票 / Crypto / 大宗商品 / TradFi 四类各两只代表，点行直达交易页。
 * 大屏四列并排，中屏两列，手机单列。
 */
export function HomeMarketSection() {
  const { t } = useTranslation('home');
  const [topStocks, setTopStocks] = useState<BStock[]>([]);

  // 市值前 2 作代表。只拉一次：这里要的是"哪两只 + 名称"这类静态元数据，
  // 价格和涨跌由 StockRow 自己走 Spot 流实时刷，不需要轮询
  useEffect(() => {
    bstockApi.list()
      .then(list => setTopStocks([...list].sort((a, b) => (b.marketCap ?? 0) - (a.marketCap ?? 0)).slice(0, 2)))
      .catch(() => {});
  }, []);

  return (
    <>
      <div className="sec-h"><h2>{t('market.title')}</h2></div>
      <div className="mk">
        <div>
          <CatHead title={t('market.stocks')} sub={t('market.stocksSub')} to="/bstock" />
          {topStocks.length
            ? topStocks.map(s => <StockRow key={s.symbol} stock={s} />)
            : Array.from({ length: 2 }).map((_, i) => <i key={i} className="sk" />)}
        </div>

        <div>
          <CatHead title={t('market.crypto')} sub={t('market.cryptoSub')} to="/coin" />
          <CoinRow symbol="BTCUSDT" />
          <CoinRow symbol="ETHUSDT" />
        </div>

        <div>
          <CatHead title={t('market.commodity')} sub={t('market.commoditySub')} to="/commodity" />
          {COMMODITY_LIST.map(c => <CoinRow key={c.symbol} symbol={c.symbol} />)}
        </div>

        <div>
          <CatHead title={t('market.tradfi')} sub={t('market.tradfiSub')} to="/tradfi" />
          {/* TradFi 代表：SpaceX（话题标的）+ SK海力士（成交最活跃） */}
          <CoinRow symbol="SPCXUSDT" />
          <CoinRow symbol="SKHYNIXUSDT" />
        </div>
      </div>
    </>
  );
}
