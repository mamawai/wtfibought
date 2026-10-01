import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Gift, Loader2, Sparkles } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader } from './ui/dialog';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { buffApi } from '../api';
import { useUserStore } from '../stores/userStore';
import type { BuffStatus, UserBuff } from '../types';

const rarityStyles: Record<string, string> = {
  COMMON: 'bg-secondary text-secondary-foreground',
  RARE: 'bg-blue-100 text-blue-700 dark:bg-blue-500/15 dark:text-blue-300',
  EPIC: 'bg-purple-100 text-purple-700 dark:bg-purple-500/15 dark:text-purple-300',
  LEGENDARY: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-500/15 dark:text-yellow-300 animate-pulse',
};

// 映射表存词表 key 不存文案：直接存 t() 的结果会定死在模块加载那一刻，切语言不跟着变
const rarityNameKeys: Record<string, string> = {
  COMMON: 'buff.rarity.common',
  RARE: 'buff.rarity.rare',
  EPIC: 'buff.rarity.epic',
  LEGENDARY: 'buff.rarity.legendary',
};

function BuffRow({ buff }: { buff: UserBuff }) {
  const { t } = useTranslation('home');

  let extra = '';
  if (buff.extraData) {
    try {
      const data = JSON.parse(buff.extraData);
      if (data.stockCode) {
        extra = t('buff.stockExtra', { name: data.stockName, count: Number(data.quantity) });
      }
    } catch { /* ignore */ }
  }
  return (
    <div className="flex items-center justify-center gap-2 flex-wrap">
      <Badge className={rarityStyles[buff.rarity]}>{t(rarityNameKeys[buff.rarity])}</Badge>
      <span className="font-semibold">{buff.buffName}</span>
      {extra && <span className="text-muted-foreground text-sm">{extra}</span>}
      {buff.buffType.startsWith('DISCOUNT_') && !buff.isUsed && (
        <Badge variant="outline" className="text-xs">{t('buff.unused')}</Badge>
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
  const { t } = useTranslation('home');
  const guest = useUserStore(s => !s.token);
  const [drawing, setDrawing] = useState(false);
  const [result, setResult] = useState<UserBuff | null>(null);
  const [showResult, setShowResult] = useState(false);

  // 父级的 status 还没到（还在拉，或者拉失败了）时，弹窗打开就自己拉一份；失败了点重试或者关掉重开再拉
  const [ownStatus, setOwnStatus] = useState<BuffStatus | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  // 弹窗一关就把失败标记清掉，下次打开自动再拉
  const [prevOpen, setPrevOpen] = useState(open);
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (!open) setLoadFailed(false);
  }
  const needOwn = open && !guest && !status && !loadFailed;
  useEffect(() => {
    if (!needOwn) return;
    let cancelled = false;
    buffApi.status()
      .then(s => { if (!cancelled) setOwnStatus(s); })
      .catch(() => { if (!cancelled) setLoadFailed(true); });
    return () => { cancelled = true; };
  }, [needOwn]);
  const shown = status ?? ownStatus;

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
      alert(e instanceof Error ? e.message : t('buff.drawFailed'));
    }
  };

  return (
    <Dialog open={open} onClose={onClose} className="max-w-sm">
      <DialogHeader>
        <div className="flex items-center gap-2">
          <Gift className="w-4 h-4 text-primary" />
          <span className="text-sm font-bold">{t('buff.title')}</span>
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

          {guest ? (
            <p className="text-sm text-muted-foreground">{t('buff.loginFirst')}</p>
          ) : shown?.canDraw ? (
            drawing ? (
              <p className="text-sm text-muted-foreground">{t('buff.drawing')}</p>
            ) : showResult && result ? (
              <div className="space-y-2 animate-in fade-in zoom-in-95">
                <p className="text-xs text-muted-foreground flex items-center justify-center gap-1">
                  <Sparkles className="w-3.5 h-3.5 text-warning" /> {t('buff.congrats')}
                </p>
                <BuffRow buff={result} />
              </div>
            ) : (
              <>
                <p className="text-sm text-muted-foreground">{t('buff.notDrawn')}</p>
                <Button onClick={handleDraw}>
                  <Gift className="w-4 h-4" /> {t('buff.draw')}
                </Button>
              </>
            )
          ) : shown?.todayBuff ? (
            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">{t('buff.drawnToday')}</p>
              <BuffRow buff={shown.todayBuff} />
            </div>
          ) : loadFailed ? (
            <>
              <p className="text-sm text-muted-foreground">{t('common:loadFailed')}</p>
              <Button variant="outline" size="sm" onClick={() => setLoadFailed(false)}>
                {t('common:retry')}
              </Button>
            </>
          ) : (
            <p className="text-sm text-muted-foreground">{t('common:loading')}</p>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
