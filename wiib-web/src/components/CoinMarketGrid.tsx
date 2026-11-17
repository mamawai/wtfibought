import { COIN_LIST, type CoinCfg } from '../lib/coinConfig';
import { CoinMarketRow } from './MarketRow';

/** 币种行情表：一行说明 + 列表。实时价（STOMP）+24h涨跌+迷你走势线，点行进交易页。/coin、/commodity、/tradfi 三个市场共用。 */
export function CoinMarketGrid({ list = COIN_LIST, sub }: { list?: CoinCfg[]; sub?: string }) {
  return (
    <>
      <p className="sub">{sub}</p>
      <div className="list">
        {list.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
      </div>
    </>
  );
}
