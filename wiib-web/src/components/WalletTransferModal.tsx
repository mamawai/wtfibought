import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { walletApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from './ui/use-toast';
import { NumInput } from './coin/TradeFields';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from './ui/dialog';
import { ArrowLeft, ArrowRight, Gamepad2, Wallet } from 'lucide-react';
import { fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import type { WalletTransferPreview } from '../types';

type Direction = 'TO_GAME' | 'TO_BALANCE';

interface Props {
  open: boolean;
  onClose: () => void;
}

/** 余额钱包 ⇌ 游戏钱包 双向划转弹窗，各页面共用 */
export function WalletTransferModal({ open, onClose }: Props) {
  const { t } = useTranslation(['trade', 'common']);
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();
  const [direction, setDirection] = useState<Direction>('TO_GAME');
  const [amount, setAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<WalletTransferPreview | null>(null);

  // 打开时刷一次用户：预测盘刚结算时 store 里的 gameBalance 是旧的
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

  // 余额→游戏 才要预检：可转额度被全仓占用压低时给出上限和强平价影响，防抖 400ms 问后端
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

  // 目标钱包名：toast、方向行、提交按钮共用一份
  const targetWallet = toGame ? t('wallet.gameWallet') : t('wallet.balanceWallet');

  const handleSubmit = async () => {
    if (submitting || amt <= 0) return;
    setSubmitting(true);
    try {
      await walletApi.transfer(direction, amt);
      toast(t('toast.transferOk', { amount: fmtNum(amt), received: fmtNum(receiveAmt), target: targetWallet }), 'success');
      setAmount('');
      await fetchUser();
      onClose();
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.transferFailed'), 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} className="bg-background border-foreground shadow-none">
      <DialogHeader>
        <h2 className="text-[17px] font-extrabold">{t('wallet.title')}</h2>
      </DialogHeader>
      <DialogContent>
        <div className="flex flex-col gap-4">
          {/* 两个钱包 + 中间方向切换（转出/转入标签跟随方向翻转，按钮带"换向"提示） */}
          <div className="flex items-stretch gap-2">
            <div className="relative flex-1 border border-border p-3 text-center">
              <span className={`chip absolute top-1.5 right-1.5 ${toGame ? 'mute' : 'up'}`}>
                {toGame ? t('wallet.out') : t('wallet.in')}
              </span>
              <div className="text-[12.5px] mute flex items-center justify-center gap-1">
                <Wallet className="w-3 h-3" /> {t('wallet.balanceWallet')}
              </div>
              <div className="num text-[17px] font-bold mt-1">{fmtNum(balance)}</div>
            </div>
            <button
              onClick={() => setDirection(d => d === 'TO_GAME' ? 'TO_BALANCE' : 'TO_GAME')}
              className="self-center px-2 py-1.5 border border-foreground hover:bg-card-2 transition-colors flex flex-col items-center gap-0.5 cursor-pointer"
              title={t('wallet.switchDirTitle')}
              aria-label={t('wallet.switchDir')}
            >
              {toGame ? <ArrowRight className="ic" /> : <ArrowLeft className="ic" />}
              <span className="text-[9px] font-bold leading-none">{t('wallet.flip')}</span>
            </button>
            <div className="relative flex-1 border border-border p-3 text-center">
              <span className={`chip absolute top-1.5 right-1.5 ${toGame ? 'up' : 'mute'}`}>
                {toGame ? t('wallet.in') : t('wallet.out')}
              </span>
              <div className="text-[12.5px] mute flex items-center justify-center gap-1">
                <Gamepad2 className="w-3 h-3" /> {t('wallet.gameWallet')}
              </div>
              <div className="num text-[17px] font-bold mt-1">{fmtNum(gameBalance)}</div>
            </div>
          </div>

          <div className="text-[12.5px] mute text-center">
            {toGame
              ? `${t('wallet.balanceWallet')} → ${t('wallet.gameWallet')}`
              : `${t('wallet.gameWallet')} → ${t('wallet.balanceWallet')}`}
          </div>

          {/* 金额输入 + 全部（全部=源钱包全额，手续费从转出额里扣，不预留） */}
          <div className="flex gap-2">
            <NumInput
              className="flex-1"
              min={0}
              placeholder={t('wallet.amountPlaceholder')}
              value={amount}
              onChange={setAmount}
              unit="USDT"
            />
            <button
              type="button"
              className="btn"
              onClick={() => setAmount(String(Math.floor(sourceBalance * 100) / 100))}
            >
              {t('wallet.max')}
            </button>
          </div>

          {amt > 0 && (
            <div className="num text-[12.5px] mute text-center">
              {t('wallet.feeLine', { fee: fee.toFixed(2), received: receiveAmt.toFixed(2) })}
            </div>
          )}

          {/* 全仓占用预检：restricted=false（可转=全部余额）时什么都不显示 */}
          {toGame && preview?.restricted && (
            preview.allowed ? (
              <div className="num border border-border p-3 flex flex-col gap-1.5 text-[12.5px]">
                <div className="flex justify-between">
                  <span className="mute">{t('wallet.equityAfter')}</span>
                  <span>{fmtNum(preview.equityAfter)}</span>
                </div>
                {preview.positions?.map(p => (
                  <div key={p.positionId} className="flex justify-between">
                    <span className="mute">
                      {t('wallet.newLiq', {
                        symbol: p.symbol,
                        dir: p.side === 'LONG' ? t('sideShort.long') : t('sideShort.short'),
                      })}
                    </span>
                    <span className="wn">
                      {p.estLiqPrice > 0 ? formatCoinPrice(p.symbol, p.estLiqPrice) : 'N/A'}
                    </span>
                  </div>
                ))}
                {preview.maxTransferable != null && (
                  <div className="flex justify-between items-center pt-1 border-t border-border">
                    <span className="mute">{t('wallet.maxTransferable')}</span>
                    <button
                      type="button"
                      className="text-primary hover:underline underline-offset-2 cursor-pointer"
                      onClick={() => setAmount(String(preview.maxTransferable))}
                    >
                      {fmtNum(preview.maxTransferable)}
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <div className="num border border-loss p-3 text-[12.5px] dn">
                {t('wallet.overLimit')}{' '}
                <button
                  type="button"
                  className="text-primary hover:underline underline-offset-2 cursor-pointer"
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
        <button type="button" className="btn sm" onClick={onClose}>{t('common:cancel')}</button>
        <button
          type="button"
          className="btn sm fill disabled:opacity-40 disabled:cursor-default"
          onClick={handleSubmit}
          disabled={submitting || amt <= 0 || amt > sourceBalance || transferBlocked}
        >
          {submitting ? t('wallet.submitting') : t('wallet.transferTo', { target: targetWallet })}
        </button>
      </DialogFooter>
    </Dialog>
  );
}
