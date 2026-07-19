import { useEffect, useState } from 'react';
import { walletApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from './ui/use-toast';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from './ui/dialog';
import { ArrowLeft, ArrowRight, Gamepad2, Wallet } from 'lucide-react';
import { fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import type { WalletTransferPreview } from '../types';

type Direction = 'TO_GAME' | 'TO_BALANCE';

interface Props {
  open: boolean;
  onClose: () => void;
  /** 划转成功后页面自己的余额刷新（如 Mines/扑克的 status），user store 已由弹窗内部刷 */
  onSuccess?: () => void;
}

/** 余额钱包 ⇌ 游戏钱包 双向划转弹窗，各页面共用 */
export function WalletTransferModal({ open, onClose, onSuccess }: Props) {
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();
  const [direction, setDirection] = useState<Direction>('TO_GAME');
  const [amount, setAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<WalletTransferPreview | null>(null);

  // 打开时刷一次用户：刚玩完游戏时 store 里的 gameBalance 是旧的
  useEffect(() => {
    if (open) void fetchUser();
  }, [open, fetchUser]);

  const balance = user?.balance ?? 0;
  const gameBalance = user?.gameBalance ?? 0;
  const toGame = direction === 'TO_GAME';
  const sourceBalance = toGame ? balance : gameBalance;
  const amt = parseFloat(amount) || 0;
  // 后端 1% 手续费：转出全额扣，到账 = 金额 × 0.99
  const FEE_RATE = 0.01;
  const fee = Math.round(amt * FEE_RATE * 100) / 100;
  const receiveAmt = Math.round(amt * (1 - FEE_RATE) * 100) / 100;

  // 余额→游戏 才要预检：有全仓敞口时转出会动净值/强平价，防抖 400ms 问后端
  useEffect(() => {
    if (!open || !toGame || amt <= 0) { setPreview(null); return; }
    let stale = false;
    const timer = window.setTimeout(() => {
      walletApi.transferPreview('TO_GAME', amt)
        .then(p => { if (!stale) setPreview(p); })
        .catch(() => { if (!stale) setPreview(null); });
    }, 400);
    return () => { stale = true; window.clearTimeout(timer); };
  }, [open, toGame, amt]);

  // allowed=false 时禁提交，preview 还没回来不拦（后端最终还会兜底校验）
  const transferBlocked = toGame && preview != null && preview.restricted && !preview.allowed;

  const handleSubmit = async () => {
    if (submitting || amt <= 0) return;
    setSubmitting(true);
    try {
      await walletApi.transfer(direction, amt);
      toast(`已划转 ${fmtNum(amt)}，到账 ${fmtNum(receiveAmt)} 至${toGame ? '游戏钱包' : '余额钱包'}`, 'success');
      setAmount('');
      await fetchUser();
      onSuccess?.();
      onClose();
    } catch (e: unknown) {
      toast((e as Error).message || '划转失败', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose}>
      <DialogHeader>
        <h2 className="text-lg font-bold">钱包划转</h2>
      </DialogHeader>
      <DialogContent>
        <div className="space-y-4">
          {/* 两个钱包 + 中间方向切换（转出/转入标签跟随方向翻转，按钮带"换向"提示） */}
          <div className="flex items-stretch gap-2">
            <div className="relative flex-1 rounded-xl neu-inset bg-background p-3 text-center">
              <span className={`absolute top-1.5 right-1.5 text-[9px] font-bold px-1.5 py-0.5 rounded-full ${toGame ? 'bg-primary/10 text-primary' : 'bg-gain/10 text-gain'}`}>
                {toGame ? '转出' : '转入'}
              </span>
              <div className="text-xs text-muted-foreground flex items-center justify-center gap-1">
                <Wallet className="w-3 h-3" /> 余额钱包
              </div>
              <div className="text-base font-bold tabular-nums mt-1">{fmtNum(balance)}</div>
            </div>
            <button
              onClick={() => setDirection(d => d === 'TO_GAME' ? 'TO_BALANCE' : 'TO_GAME')}
              className="self-center px-2 py-1.5 rounded-full bg-card neu-btn-sm text-primary transition-all flex flex-col items-center gap-0.5"
              title="点击切换划转方向"
              aria-label="切换划转方向"
            >
              {toGame ? <ArrowRight className="w-4 h-4" /> : <ArrowLeft className="w-4 h-4" />}
              <span className="text-[9px] font-bold leading-none">换向</span>
            </button>
            <div className="relative flex-1 rounded-xl neu-inset bg-background p-3 text-center">
              <span className={`absolute top-1.5 right-1.5 text-[9px] font-bold px-1.5 py-0.5 rounded-full ${toGame ? 'bg-gain/10 text-gain' : 'bg-primary/10 text-primary'}`}>
                {toGame ? '转入' : '转出'}
              </span>
              <div className="text-xs text-muted-foreground flex items-center justify-center gap-1">
                <Gamepad2 className="w-3 h-3" /> 游戏钱包
              </div>
              <div className="text-base font-bold tabular-nums mt-1">{fmtNum(gameBalance)}</div>
            </div>
          </div>

          <div className="text-xs text-muted-foreground text-center">
            {toGame ? '余额钱包 → 游戏钱包' : '游戏钱包 → 余额钱包'}
          </div>

          {/* 金额输入 + 全部（全部=源钱包全额，手续费从转出额里扣，不预留） */}
          <div className="flex gap-2">
            <Input
              type="number"
              min={0}
              placeholder="划转金额"
              value={amount}
              onChange={e => setAmount(e.target.value)}
              className="flex-1 text-right font-mono tabular-nums"
            />
            <Button
              variant="outline"
              size="sm"
              className="h-11 px-4"
              onClick={() => setAmount(String(Math.floor(sourceBalance * 100) / 100))}
            >
              全部
            </Button>
          </div>

          {amt > 0 && (
            <div className="text-xs text-muted-foreground text-center tabular-nums">
              手续费 1%：{fee.toFixed(2)} ｜ 到账：{receiveAmt.toFixed(2)}
            </div>
          )}

          {/* 全仓敞口预检：restricted=false 时什么都不显示 */}
          {toGame && preview?.restricted && (
            preview.allowed ? (
              <div className="rounded-xl neu-inset bg-background p-3 space-y-1.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">划转后账户净值</span>
                  <span className="font-mono tabular-nums">{fmtNum(preview.equityAfter)}</span>
                </div>
                {preview.positions?.map(p => (
                  <div key={p.positionId} className="flex justify-between">
                    <span className="text-muted-foreground">{p.symbol} {p.side === 'LONG' ? '多' : '空'} 新强平价</span>
                    <span className="font-mono tabular-nums text-yellow-500">
                      {p.estLiqPrice > 0 ? formatCoinPrice(p.symbol, p.estLiqPrice) : 'N/A'}
                    </span>
                  </div>
                ))}
                {preview.maxTransferable != null && (
                  <div className="flex justify-between items-center pt-1 border-t border-border/40">
                    <span className="text-muted-foreground">最大可转</span>
                    <button
                      type="button"
                      className="font-mono tabular-nums text-primary hover:underline underline-offset-2"
                      onClick={() => setAmount(String(preview.maxTransferable))}
                    >
                      {fmtNum(preview.maxTransferable)}
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <div className="rounded-xl neu-inset bg-background p-3 text-xs text-loss">
                转出后将触发全仓强平，最多可转{' '}
                <button
                  type="button"
                  className="font-mono tabular-nums text-primary hover:underline underline-offset-2"
                  onClick={() => setAmount(String(preview.maxTransferable ?? 0))}
                >
                  {fmtNum(preview.maxTransferable ?? 0)}
                </button>
              </div>
            )
          )}
        </div>
      </DialogContent>
      <DialogFooter>
        <Button variant="ghost" size="sm" onClick={onClose}>取消</Button>
        <Button
          size="sm"
          onClick={handleSubmit}
          disabled={submitting || amt <= 0 || amt > sourceBalance || transferBlocked}
        >
          {submitting ? '划转中...' : `划转到${toGame ? '游戏钱包' : '余额钱包'}`}
        </Button>
      </DialogFooter>
    </Dialog>
  );
}
