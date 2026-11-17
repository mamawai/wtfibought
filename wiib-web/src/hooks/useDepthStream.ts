import { useEffect, useState } from 'react';
import { subscribe } from './stompClient';

/** 盘口的一行：[价, 量]，量是币的个数 */
export type DepthRow = [number, number];

/** 一个价格单位下归并好的买卖盘，两边都是最优价在前 */
export interface DepthLadder {
  /** 价格单位，原样的十进制串（"0.1" / "10"），小数位数就是这一档价格该显示几位 */
  step: string;
  b: DepthRow[];
  a: DepthRow[];
}

export interface DepthBook {
  /** 单位从小到大，一般四份；还没收到是空的 */
  ladders: DepthLadder[];
  /** 在正常收：后端半秒发一次，3 秒没来新的就当断了 */
  live: boolean;
}

const STALE_MS = 3000;
const EMPTY: DepthBook = { ladders: [], live: false };

/** 订阅某个合约币的盘口（/topic/depth/{symbol}，行情服务的 OrderBooks 发的）。 */
export function useDepthStream(symbol: string): DepthBook {
  const [state, setState] = useState<DepthBook & { symbol: string }>({ ...EMPTY, symbol: '' });

  useEffect(() => {
    let stale = 0;
    const unsub = subscribe(`/topic/depth/${symbol}`, msg => {
      const { ladders } = JSON.parse(msg.body) as { ladders: DepthLadder[] };
      setState({ symbol, ladders, live: true });
      window.clearTimeout(stale);
      stale = window.setTimeout(() => setState(s => ({ ...s, live: false })), STALE_MS);
    });
    return () => {
      window.clearTimeout(stale);
      unsub();
    };
  }, [symbol]);

  // 换币后旧币的盘口不能接着显示
  return state.symbol === symbol ? state : EMPTY;
}
