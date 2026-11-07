import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { traderApi } from '../../api';
import { cn, fmtDate, fmtDateTime, fmtTime } from '../../lib/utils';
import type { AiTraderDecisionView } from '../../types';
import { beatsPerDay, minToHm, parseWindow } from './traderConfig';
import { doneTrades, toolName } from './traderTools';
import { useKindLabel } from './useKindLabel';

/**
 * 心跳条（配置台和工作台共用）：心跳档位 / 下次心跳倒计时 / 上次醒来干了什么 / 第四格由页面给。
 * 秒级倒计时只在这里面走，不带着整页每秒重渲染。refreshKey 一变就重拉「上次醒来」
 */
export function HeartbeatStrip({ traderId, intervalCode, wakeWindow, running, idleText, nextWakeAt, mine, live = false, refreshKey, extra }: {
  traderId: number;
  intervalCode: string;
  wakeWindow: string | null;
  running: boolean;
  /** 没在跑时「下次心跳」底下那句 */
  idleText: string;
  /** 后端算好的下次例行唤醒时刻 */
  nextWakeAt: number | null;
  mine: boolean;
  /** 它正醒着想事（只有主人看得到），顶线变橙 */
  live?: boolean;
  refreshKey: unknown;
  /** 第四格：工作台是在盯几条，配置台是今天醒了几次 */
  extra: { k: string; v: string; s: string };
}) {
  const { t } = useTranslation('ai');
  const kindLabel = useKindLabel();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // 最近几行里找第一条真醒来的：最近一行可能是 08:00 的复盘，也可能是跳过的
  const [last, setLast] = useState<AiTraderDecisionView | null>(null);
  useEffect(() => {
    let stale = false;
    traderApi.decisions(traderId, 5)
      .then(v => { if (!stale) setLast(v.find(d => d.kind !== 'REVIEW' && d.status !== 'SKIPPED') ?? null); })
      .catch(() => {});
    return () => { stale = true; };
  }, [traderId, refreshKey]);

  const win = parseWindow(wakeWindow);
  const perDay = beatsPerDay(intervalCode, win);
  const next = running ? nextWakeAt : null;
  /** 离下次心跳还有多久：一小时以上到分，以内到秒 */
  const leftText = () => {
    if (next == null) return t('wb.hb.noNext');
    const left = next - now;
    if (left <= 0) return t('wb.hb.due');
    const sec = Math.floor(left / 1000);
    const h = Math.floor(sec / 3600);
    const m = Math.floor(sec % 3600 / 60);
    return h > 0 ? t('wb.left.hm', { h, m }) : t('wb.left.ms', { m: String(m).padStart(2, '0'), s: String(sec % 60).padStart(2, '0') });
  };
  // 上次醒来干了什么：叫醒原因 + 做成的第一个交易动作，比如「盯盘触发 · 收紧止损 BTCUSDT」
  const act = last ? doneTrades(last)[0] : undefined;
  const lastWhat = last
    ? [kindLabel(last.kind, mine, last.intervalCode), act && [toolName(act.tool), act.args?.symbol].filter(Boolean).join(' ')]
      .filter(Boolean).join(' · ')
    : t('wb.hb.never');

  return (
    <div className={cn('tr-hb num', live && 'live')}>
      <div>
        <div className={cn('k', live && 'lv')}>{live ? t('wb.hb.thinking') : t('wb.hb.beat')}</div>
        <div className="v">{intervalCode}</div>
        <div className="s">
          {win ? t('wb.hb.beatWin', { from: minToHm(win.from), to: minToHm(win.to), n: perDay }) : t('wb.hb.beatAll', { n: perDay })}
        </div>
      </div>
      <div>
        <div className="k">{t('wb.hb.next')}</div>
        <div className="v">{next != null ? fmtTime(next) : '—'}</div>
        <div className="s">{running ? leftText() : idleText}</div>
      </div>
      <div>
        <div className="k">{t('wb.hb.last')}</div>
        <div className="v">{last ? (fmtDate(last.wakeTime) === fmtDate(now) ? fmtTime(last.wakeTime) : fmtDateTime(last.wakeTime)) : '—'}</div>
        <div className="s">{lastWhat}</div>
      </div>
      <div>
        <div className="k">{extra.k}</div>
        <div className="v">{extra.v}</div>
        <div className="s">{extra.s}</div>
      </div>
    </div>
  );
}
