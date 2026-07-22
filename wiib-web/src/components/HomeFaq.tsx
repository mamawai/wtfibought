import { BookOpen } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';

/** 教学条目：概念名 + 白话解释（结合本站玩法） */
const FAQ_ITEMS: { q: string; a: string }[] = [
  {
    q: '什么是杠杆？',
    a: '用一份本金撬动数倍仓位。比如 100 USDT 开 10x 杠杆，实际持有 1000 USDT 的仓位——价格每波动 1%，你的本金盈亏 10%。杠杆放大收益的同时同样放大亏损，倍数越高离强平越近。本站合约支持逐档杠杆（不同档位有持仓上限），现货买入也提供低倍借款杠杆（按日计息）。',
  },
  {
    q: '全仓和逐仓有什么区别？',
    a: '区别在"亏了拿什么赔"。逐仓：每个仓位只押自己那份保证金，最多亏光这一份，其他资金无恙；全仓：账户可用余额整体做后盾，单仓浮亏会吃掉整个账户的钱，抗波动更强但爆仓时损失也更大。新手建议先用逐仓，亏损上限清晰。',
  },
  {
    q: '什么是标记价格？为什么用它算强平？',
    a: '标记价格（Mark Price）是交易所综合现货指数算出的"公允价"，比最新成交价平滑，专门用来计算浮盈浮亏和触发强平。这样做是为了防止有人在流动性差的瞬间用一笔大单把成交价打穿、恶意触发别人爆仓（插针）。你的仓位盈亏跳动看的是标记价，不是 K 线最新价。',
  },
  {
    q: '什么是强平价格？怎么算出来的？',
    a: '当标记价格触及强平价，系统会强制平掉你的仓位（爆仓）。直观理解：强平价 = 你的保证金快亏完的那个价格，再扣掉一层"维持保证金率"（MMR）的安全垫。杠杆越高、保证金越薄，强平价离开仓价越近。开仓面板会实时预估强平价——下单前看一眼它离现价有多远。',
  },
  {
    q: '什么是维持保证金率（MMR）？',
    a: '仓位不被强平所需的最低保证金比例。它按仓位名义价值分档：仓位越大，档位越高，MMR 越高（大仓位更难平掉，所以要求更厚的安全垫）。当 保证金 + 浮动盈亏 < 仓位价值 × MMR 时触发强平。',
  },
  {
    q: '爆仓动态里的多空双爆是什么意思？',
    a: '爆仓动态展示的是全站（含 Binance 强平流）被强制平仓的单子。行情剧烈波动时，先杀一边（比如急跌爆多单），随后的反抽再爆掉追空的人，这就是"多空双爆"。它常被当作情绪极端、短期反转的参考信号之一。',
  },
  {
    q: '现货和合约有什么区别？',
    a: '现货是真的买入持有币（涨了卖出赚差价，最多亏掉买入的钱）；合约是保证金交易，不持有现货本身，可做多也可做空，用杠杆放大敞口，有强平风险。本站黄金/原油是纯合约标的（TradFi 永续），没有现货可买。',
  },
  {
    q: '优惠券（Buff）怎么用？',
    a: '每日福利抽到的折扣券在现货市价买入时勾选使用，按券面折扣减免成交金额；排行榜里"已省"统计的就是历史用券省下的钱。稀有度越高折扣越狠，传说券记得留给大单。',
  },
];

/** 首页 FAQ：新手教学手风琴（原生 details，无 JS 状态） */
export function HomeFaq() {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <BookOpen className="w-3.5 h-3.5 text-primary" />
          新手教学 · FAQ
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-0">
        <div className="divide-y divide-border/60">
          {FAQ_ITEMS.map(item => (
            <details key={item.q} className="group py-1">
              <summary className="flex items-center gap-2 py-2 text-sm font-semibold cursor-pointer list-none select-none hover:text-primary transition-colors [&::-webkit-details-marker]:hidden">
                <span className="text-primary text-xs transition-transform group-open:rotate-90">▸</span>
                {item.q}
              </summary>
              <p className="pb-3 pl-5 text-[13px] leading-relaxed text-muted-foreground">{item.a}</p>
            </details>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
