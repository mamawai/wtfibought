import { COIN_LIST, type CoinCfg } from '../lib/coinConfig';
import { CoinMarketRow } from './MarketRow';

/** 币种行情表：实时价（STOMP）+24h涨跌+迷你走势线，点行进交易页。/coin、/commodity、/tradfi 三个选择页共用。 */
export function CoinMarketGrid({ list = COIN_LIST }: { list?: CoinCfg[] }) {
  return (
    <div className="mkt-list @container border-t border-border">
      {list.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
    </div>
  );
}
