import { useState } from 'react';
import { Gift, Loader2, Sparkles } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader } from './ui/dialog';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { buffApi } from '../api';
import type { BuffStatus, UserBuff } from '../types';

const rarityStyles: Record<string, string> = {
  COMMON: 'bg-secondary text-secondary-foreground',
  RARE: 'bg-blue-100 text-blue-700 dark:bg-blue-500/15 dark:text-blue-300',
  EPIC: 'bg-purple-100 text-purple-700 dark:bg-purple-500/15 dark:text-purple-300',
  LEGENDARY: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-500/15 dark:text-yellow-300 animate-pulse',
};

const rarityNames: Record<string, string> = {
  COMMON: '普通',
  RARE: '稀有',
  EPIC: '史诗',
  LEGENDARY: '传说',
};

function BuffRow({ buff }: { buff: UserBuff }) {
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
    <div className="flex items-center justify-center gap-2 flex-wrap">
      <Badge className={rarityStyles[buff.rarity]}>{rarityNames[buff.rarity]}</Badge>
      <span className="font-semibold">{buff.buffName}</span>
      {extra && <span className="text-muted-foreground text-sm">{extra}</span>}
      {buff.buffType.startsWith('DISCOUNT_') && !buff.isUsed && (
        <Badge variant="outline" className="text-xs">未使用</Badge>
      )}
    </div>
  );
}

/**
 * 每日福利弹窗：快捷入口点开后抽取/查看今日 Buff。
 * status 由父级（Home）传入，抽完调 onDrawn 让父级刷新。
 */
export function DailyBuffModal({ status, open, onClose, onDrawn }: {
  status: BuffStatus | null;
  open: boolean;
  onClose: () => void;
  onDrawn: () => void;
}) {
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

  return (
    <Dialog open={open} onClose={onClose} className="max-w-sm">
      <DialogHeader>
        <div className="flex items-center gap-2">
          <Gift className="w-4 h-4 text-primary" />
          <span className="text-sm font-bold">每日福利</span>
        </div>
      </DialogHeader>
      <DialogContent>
        <div className="py-6 text-center space-y-5">
          {/* 礼盒主视觉：抽取中晃动 */}
          <div className={`w-16 h-16 mx-auto rounded-lg border border-border bg-card-2 machined flex items-center justify-center ${drawing ? 'animate-pulse' : ''}`}>
            {drawing
              ? <Loader2 className="w-7 h-7 text-primary animate-spin" />
              : <Gift className="w-7 h-7 text-primary" />}
          </div>

          {status?.canDraw ? (
            drawing ? (
              <p className="text-sm text-muted-foreground">抽奖中...</p>
            ) : showResult && result ? (
              <div className="space-y-2 animate-in fade-in zoom-in-95">
                <p className="text-xs text-muted-foreground flex items-center justify-center gap-1">
                  <Sparkles className="w-3.5 h-3.5 text-warning" /> 恭喜获得
                </p>
                <BuffRow buff={result} />
              </div>
            ) : (
              <>
                <p className="text-sm text-muted-foreground">今日还未抽取，试试手气</p>
                <Button onClick={handleDraw}>
                  <Gift className="w-4 h-4" /> 抽取今日 Buff
                </Button>
              </>
            )
          ) : status?.todayBuff ? (
            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">今日已抽取</p>
              <BuffRow buff={status.todayBuff} />
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">登录后可抽奖</p>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
