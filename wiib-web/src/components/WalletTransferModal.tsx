import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowRight, CircleAlert } from 'lucide-react';
import { walletApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from './ui/use-toast';
import { NumInput } from './coin/TradeFields';
import { Dialog, DialogFooter, DialogHeader } from './ui/dialog';
import { cn, fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import type { WalletTransferPreview } from '../types';
import './WalletTransferModal.css';

type Direction = 'TO_GAME' | 'TO_BALANCE';

interface Props {
  open: boolean;
  onClose: () => void;
}

/** 余额钱包 ⇌ 游戏钱包 双向划转弹窗，各页面共用（样式见 WalletTransferModal.css） */
export function WalletTransferModal({ open, onClose }: Props) {
  const { t } = useTranslation(['trade', 'common']);
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();
  const amtId = useId();
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

  // 全仓占用预检：restricted=false（可转=全部余额）时什么都不显示
  const pv = toGame && preview?.restricted ? preview : null;
  // allowed=false 时禁提交，preview 还没回来不拦（后端最终还会兜底校验）
  const transferBlocked = pv != null && !pv.allowed;

  // 目标钱包名：toast、提交按钮共用一份
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

  /** 「最多可转」那个数：点一下填进金额框 */
  const maxLink = (v: number) => (
    <button type="button" className="linkb" onClick={() => setAmount(String(v))}>{fmtNum(v)}</button>
  );

  return (
    <Dialog open={open} onClose={onClose} className="xfer-dlg">
      <DialogHeader><h3>{t('wallet.title')}</h3></DialogHeader>

      {/* 两个钱包 + 中间换向钮：转出/转入标签跟着方向翻，箭头转半圈 */}
      <div className={cn('xfer', !toGame && 'rev')}>
        <div className="wal">
          <div className="wl">
            {t('wallet.balanceWallet')}
            <span className={cn('tag sm', !toGame && 'g')}>{toGame ? t('wallet.out') : t('wallet.in')}</span>
          </div>
          <b className="nf">{fmtNum(balance)}</b>
        </div>
        <button
          type="button"
          className="ib"
          onClick={() => setDirection(d => d === 'TO_GAME' ? 'TO_BALANCE' : 'TO_GAME')}
          title={t('wallet.switchDirTitle')}
          aria-label={t('wallet.switchDir')}
        >
          <ArrowRight className="ic" />
        </button>
        <div className="wal">
          <div className="wl">
            {t('wallet.gameWallet')}
            <span className={cn('tag sm', toGame && 'g')}>{toGame ? t('wallet.in') : t('wallet.out')}</span>
          </div>
          <b className="nf">{fmtNum(gameBalance)}</b>
        </div>
      </div>

      {/* 金额 + 全部（全部=源钱包全额，手续费从转出额里扣，不预留） */}
      <div className="fld">
        <label className="fl" htmlFor={amtId}>
          <span>{t('wallet.amountPlaceholder')}</span>
          <span>{amt > 0 ? t('wallet.feeLine', { fee: fee.toFixed(2), received: receiveAmt.toFixed(2) }) : t('wallet.fee')}</span>
        </label>
        <NumInput id={amtId} min={0} placeholder="0.00" value={amount} onChange={setAmount} unit="USDT">
          <button type="button" className="u" onClick={() => setAmount(String(Math.floor(sourceBalance * 100) / 100))}>
            {t('wallet.max')}
          </button>
        </NumInput>
      </div>

      <div className={cn('col', pv && 'open')}>
        <div>
          {pv && (pv.allowed ? (
            <div className="kv">
              <div><span>{t('wallet.equityAfter')}</span><b>{fmtNum(pv.equityAfter)}</b></div>
              {pv.positions?.map(p => (
                <div key={p.positionId}>
                  <span>{t('wallet.newLiq', { symbol: p.symbol, dir: p.side === 'LONG' ? t('sideShort.long') : t('sideShort.short') })}</span>
                  <b className="wn">{p.estLiqPrice > 0 ? formatCoinPrice(p.symbol, p.estLiqPrice) : 'N/A'}</b>
                </div>
              ))}
              {pv.maxTransferable != null && (
                <div><span>{t('wallet.maxTransferable')}</span><b>{maxLink(pv.maxTransferable)}</b></div>
              )}
            </div>
          ) : (
            <div className="note r">
              <CircleAlert className="ic" />
              <div>{t('wallet.overLimit')} {maxLink(pv.maxTransferable ?? 0)}</div>
            </div>
          ))}
        </div>
      </div>

      <DialogFooter>
        <button type="button" className="btn" onClick={onClose}>{t('common:cancel')}</button>
        <button
          type="button"
          className={cn('btn ink', submitting && 'busy')}
          onClick={handleSubmit}
          disabled={amt <= 0 || amt > sourceBalance || transferBlocked}
        >
          {t('wallet.transferTo', { target: targetWallet })}
        </button>
      </DialogFooter>
    </Dialog>
  );
}
