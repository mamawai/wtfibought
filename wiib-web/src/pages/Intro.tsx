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

      {/* 桌面双列：左交易规则，右游戏规则+每日福利；移动端自然单列 */}
      <div className="grid md:grid-cols-2 gap-5 items-start">
      {/* 交易规则 */}
      <Card>
        <CardContent className="pt-5 space-y-4 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">交易规则</h2>

          <section>
            <h3 className="font-bold mb-1">交易时间</h3>
            <p className="text-muted-foreground">周一至周五 9:30-11:30、13:00-15:00，每10秒更新行情</p>
          </section>

          <section>
            <h3 className="font-bold mb-1">股票交易（bStock）</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li><strong>代币化美股</strong>：真实 Binance 现货行情，NVDA / TSLA / QQQ 等</li>
              <li><strong>市价 / 限价单</strong>：支持杠杆借款 1-10 倍</li>
              <li><strong>手续费</strong>：0.1%</li>
              <li><strong>瞬时结算</strong>：卖出即时到账，无 T+1</li>
              <li><strong>24/7</strong>：全天候交易</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">杠杆交易</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>市价买入可选1-10倍杠杆</li>
              <li>借款按日计息0.05%</li>
              <li>爆仓：资产低于借款时自动清仓，次日9:00恢复</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">BTC模拟交易</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li><strong>实时行情</strong>：接入Binance WebSocket</li>
              <li><strong>支持小数</strong>：最小0.00001 BTC</li>
              <li><strong>手续费</strong>：0.1%</li>
              <li><strong>限价单</strong>：50%-150%市价，24小时有效</li>
              <li><strong>杠杆</strong>：1-10倍，日息0.05%</li>
              <li><strong>卖出到账</strong>：5分钟到账，优先偿还借款</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">合约交易</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>支持开多/开空，1-250倍杠杆</li>
              <li>实时盈亏计算，爆仓自动平仓</li>
              <li>手续费：开仓/平仓各0.04%</li>
            </ul>
          </section>
        </CardContent>
      </Card>

      <div className="space-y-5">
      {/* 游戏规则 */}
      <Card>
        <CardContent className="pt-5 space-y-4 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">游戏规则</h2>

          <section>
            <h3 className="font-bold mb-1">21点</h3>
            <p className="text-muted-foreground">经典Blackjack，点数最接近21点不爆牌即赢。支持分牌、加倍、保险。</p>
          </section>

          <section>
            <h3 className="font-bold mb-1">视频扑克</h3>
            <p className="text-muted-foreground">发5张牌，选择保留后换牌一次，按最终牌型赔付。</p>
          </section>

          <section>
            <h3 className="font-bold mb-1">翻翻爆金币</h3>
            <p className="text-muted-foreground">5×5方格隐藏地雷，每翻开一个安全格奖金递增，踩雷则全部归零。随时可收手。</p>
          </section>

          <section>
            <h3 className="font-bold mb-1">BTC 5分钟涨跌预测 <span className="text-[10px] text-amber-500 font-bold ml-1">NEW</span></h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>每5分钟一轮，预测BTC价格涨跌</li>
              <li>基于 Polymarket 实时概率定价</li>
              <li>买入看涨/看跌合约，预测正确每份值$1</li>
              <li>可随时卖出，按当前市场价成交</li>
              <li>手续费根据概率动态计算（0.1%~2%）</li>
            </ul>
          </section>
        </CardContent>
      </Card>

      {/* 每日福利 */}
      <Card>
        <CardContent className="pt-5 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary mb-2">每日福利</h2>
          <p className="text-muted-foreground">每天可抽一次，有机会获得红包、股票或折扣券</p>
        </CardContent>
      </Card>
      </div>
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
