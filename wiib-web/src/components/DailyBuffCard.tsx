import { useState } from 'react';
import { Gift, Loader2 } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { buffApi } from '../api';
import type { BuffStatus, UserBuff } from '../types';

const rarityStyles: Record<string, string> = {
  COMMON: 'bg-gray-100 text-gray-700',
  RARE: 'bg-blue-100 text-blue-700',
  EPIC: 'bg-purple-100 text-purple-700',
  LEGENDARY: 'bg-yellow-100 text-yellow-700 animate-pulse',
};

const rarityNames: Record<string, string> = {
  COMMON: '普通',
  RARE: '稀有',
  EPIC: '史诗',
  LEGENDARY: '传说',
};

interface Props {
  status: BuffStatus | null;
  onDrawn: () => void;
}

export function DailyBuffCard({ status, onDrawn }: Props) {
  const [drawing, setDrawing] = useState(false);
  const [result, setResult] = useState<UserBuff | null>(null);
  const [showResult, setShowResult] = useState(false);

  const handleDraw = async () => {
    setDrawing(true);
    setShowResult(false);
    try {
      const buff = await buffApi.draw();
      setResult(buff);
      setTimeout(() => {
        setShowResult(true);
        setDrawing(false);
        onDrawn();
      }, 1000);
    } catch (e) {
      setDrawing(false);
      alert(e instanceof Error ? e.message : '抽奖失败');
    }
  };

  const renderBuff = (buff: UserBuff) => {
    let extra = '';
    if (buff.extraData) {
      try {
        const data = JSON.parse(buff.extraData);
        if (data.stockCode) {
          extra = ` (${data.stockName} ${data.quantity}股)`;
        }
      } catch { /* ignore */ }
    }
    return (
      <div className="flex items-center gap-2">
        <Badge className={rarityStyles[buff.rarity]}>{rarityNames[buff.rarity]}</Badge>
        <span className="font-medium">{buff.buffName}</span>
        {extra && <span className="text-muted-foreground text-sm">{extra}</span>}
        {buff.buffType.startsWith('DISCOUNT_') && !buff.isUsed && (
          <Badge variant="outline" className="text-xs">未使用</Badge>
        )}
      </div>
    );
  };

  return (
    <Card id="daily-buff-card">
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm">
          <div className="p-1 rounded-md bg-primary/10">
            <Gift className="w-3.5 h-3.5 text-primary" />
          </div>
          每日福利
        </CardTitle>
      </CardHeader>
      <CardContent>
        {status?.canDraw ? (
          <div className="space-y-3">
            {drawing ? (
              <div className="flex items-center justify-center py-4">
                <Loader2 className="w-6 h-6 animate-spin text-primary" />
                <span className="ml-2">抽奖中...</span>
              </div>
            ) : showResult && result ? (
              <div className="space-y-2">
                <div className="text-center text-sm text-muted-foreground">恭喜获得</div>
                <div className="flex justify-center">{renderBuff(result)}</div>
              </div>
            ) : (
              <Button onClick={handleDraw} className="w-full">
                <Gift className="w-4 h-4 mr-2" />
                抽取今日Buff
              </Button>
            )}
          </div>
        ) : status?.todayBuff ? (
          <div className="space-y-2">
            <div className="text-sm text-muted-foreground">今日已抽取</div>
            {renderBuff(status.todayBuff)}
          </div>
        ) : (
          <div className="text-sm text-muted-foreground">登录后可抽奖</div>
        )}
      </CardContent>
    </Card>
  );
}
