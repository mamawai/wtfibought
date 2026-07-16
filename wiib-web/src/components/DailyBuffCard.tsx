import { useState } from 'react';
import { Gift, Loader2 } from 'lucide-react';
import { Card, CardContent } from './ui/card';
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

  // 紧凑单行条：左标题 / 中状态与结果 / 右抽奖按钮
  return (
    <Card id="daily-buff-card">
      <CardContent className="py-3 px-5 pt-3 flex items-center gap-3 flex-wrap">
        <div className="flex items-center gap-2 shrink-0">
          <div className="p-1 rounded-md bg-primary/10">
            <Gift className="w-3.5 h-3.5 text-primary" />
          </div>
          <span className="text-sm font-bold">每日福利</span>
        </div>

        <div className="flex-1 min-w-0 flex items-center gap-2 text-sm">
          {status?.canDraw ? (
            drawing ? (
              <span className="flex items-center gap-2 text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin text-primary" /> 抽奖中...
              </span>
            ) : showResult && result ? (
              <>
                <span className="text-muted-foreground shrink-0">恭喜获得</span>
                {renderBuff(result)}
              </>
            ) : (
              <span className="text-muted-foreground">今日还未抽取，试试手气</span>
            )
          ) : status?.todayBuff ? (
            <>
              <span className="text-muted-foreground shrink-0">今日已抽取</span>
              {renderBuff(status.todayBuff)}
            </>
          ) : (
            <span className="text-muted-foreground">登录后可抽奖</span>
          )}
        </div>

        {status?.canDraw && !drawing && !(showResult && result) && (
          <Button size="sm" onClick={handleDraw} className="shrink-0">
            <Gift className="w-3.5 h-3.5" /> 抽取今日Buff
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
