import { useNavigate } from 'react-router-dom';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Bell } from 'lucide-react';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
function hideNoticeToday() { localStorage.setItem(HIDE_NOTICE_KEY, new Date().toDateString()); }

export function Intro() {
  const navigate = useNavigate();
  const goHome = () => navigate('/', { replace: true });

  return (
    <div className="page-shell px-4 md:px-6 py-6 pb-36 md:pb-24 space-y-5">

      <h1 className="text-2xl font-extrabold flex items-center gap-2">
        <Bell className="w-6 h-6 text-primary" />
        玩法说明
      </h1>

      {/* 欢迎 + 风险提示 */}
      <Card>
        <CardContent className="pt-5 space-y-3 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">欢迎</h2>
          <p className="text-muted-foreground">虚拟股票交易模拟器，体验"如果当初买了会怎样"。所有数据均为模拟。</p>
          <div className="bg-primary/8 p-3 rounded-xl border-2 border-primary/20">
            <p className="text-primary/80 text-xs font-medium">仅供娱乐，不构成投资建议。杠杆有风险，可能爆仓。</p>
          </div>
        </CardContent>
      </Card>

      {/* 桌面双列：左交易（重点），右游戏+福利；移动端自然单列 */}
      <div className="grid md:grid-cols-2 gap-5 items-start">
      {/* 交易规则 */}
      <Card>
        <CardContent className="pt-5 space-y-4 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">交易规则</h2>

          <section>
            <h3 className="font-bold mb-1">现货 · 代币化美股（bStock）</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>真实 Binance 现货行情，NVDA / TSLA / QQQ 等，24/7 全天候</li>
              <li>市价 / 限价单，市价买入可加 1-10 倍杠杆（借款日息 0.05%）</li>
              <li>手续费 0.1%，卖出即时到账，无 T+1</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">现货 · 加密货币</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>BTC 等主流币，Binance WebSocket 实时行情</li>
              <li>限价单 50%-150% 市价内有效 24 小时</li>
              <li>杠杆 1-10 倍日息 0.05%，手续费 0.1%</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">USDT 永续合约</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li><strong>开多 / 开空</strong>：市价、限价单，全仓保证金——整个账户净值为仓位兜底</li>
              <li><strong>杠杆 1-150 倍</strong>：按仓位价值分档，对齐 Binance 档位表</li>
              <li><strong>止盈止损</strong>：开仓即可预设、支持分批，实时预估强平价</li>
              <li><strong>资金费率</strong>：真实费率，每 8 小时多空互付</li>
              <li><strong>手续费</strong>：挂单 maker 0.02%，市价 taker 0.04%</li>
              <li>保证金不足自动强平，请控制杠杆</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">双钱包</h3>
            <p className="text-muted-foreground">余额钱包管交易、游戏钱包管游戏，互相隔离，随时划转。</p>
          </section>

          <section>
            <h3 className="font-bold mb-1">量化策略</h3>
            <p className="text-muted-foreground">「策略」页可围观四套量化策略（通道突破 / 挤压动量 / 清算级联反向 / 斐波那契回撤）的实时信号与模拟盘自动执行。</p>
          </section>
        </CardContent>
      </Card>

      {/* 游戏与福利 */}
      <Card>
        <CardContent className="pt-5 space-y-3 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">游戏与福利</h2>
          <ul className="list-disc list-inside text-muted-foreground space-y-1.5">
            <li><strong>21点</strong>：经典 Blackjack，支持分牌、加倍、保险</li>
            <li><strong>视频扑克</strong>：发 5 张选保留换一次，按牌型赔付</li>
            <li><strong>翻翻爆金币</strong>：翻开安全格奖金递增，踩雷归零，随时收手</li>
            <li><strong>BTC 涨跌预测</strong>：每 5 分钟一轮，Polymarket 实时概率定价，可随时卖出</li>
            <li><strong>每日福利</strong>：每天一抽，红包 / 股票 / 折扣券</li>
          </ul>
        </CardContent>
      </Card>
      </div>

      {/* 风险声明 */}
      <div className="bg-red-500/10 border-2 border-red-500/20 rounded-2xl p-4 text-xs text-red-500 dark:text-red-400 font-medium leading-relaxed">
        风险声明：本平台所有交易均为虚拟模拟，不涉及真实资金。任何数据、行情、收益均不构成投资建议。杠杆与合约交易存在爆仓风险，请谨慎体验。
      </div>

      {/* sticky 底部按钮 */}
      <div className="fixed left-0 right-0 bottom-20 md:bottom-6 px-4 md:px-6 z-50">
        <div className="max-w-2xl mx-auto flex gap-3">
          <Button variant="outline" className="flex-1" onClick={() => { hideNoticeToday(); goHome(); }}>今日不展示</Button>
          <Button className="flex-1" onClick={goHome}>我知道了</Button>
        </div>
      </div>
    </div>
  );
}
