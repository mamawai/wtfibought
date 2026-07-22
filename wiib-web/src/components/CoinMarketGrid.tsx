import { COIN_LIST, type CoinCfg } from '../lib/coinConfig';
import { CoinMarketRow } from './MarketRow';

/** 币种行情表：实时价（STOMP）+24h涨跌+迷你走势线，点行直达交易页。/coin 与 /commodity 选择页共用。 */
export function CoinMarketGrid({ list = COIN_LIST }: { list?: CoinCfg[] }) {
  return (
    <div className="pt-card rounded-lg overflow-hidden">
      {list.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
    </div>
  );
}
