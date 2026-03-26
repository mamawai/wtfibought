import {useCallback, useEffect, useState} from 'react';
import {blackjackApi} from '../api';
import {useToast} from '../components/ui/use-toast';
import {Button} from '../components/ui/button';
import {Card, CardContent, CardHeader, CardTitle} from '../components/ui/card';
import {Dialog, DialogContent, DialogFooter, DialogHeader} from '../components/ui/dialog';
import {PlayingCard} from '../components/blackjack/PlayingCard';
import {Skeleton} from '../components/ui/skeleton';
import {ArrowLeftRight, CopyPlus, Hand, RotateCcw, Shield, Spade, Split, Square} from 'lucide-react';
import {cn} from '../lib/utils';
import type {BlackjackStatus, GameState, HandResult} from '../types';

const BET_PRESETS = [100, 500, 1000, 5000, 10000];
const CHIP_COLORS: Record<number, string> = {
  100: 'bj-chip-100',
  500: 'bj-chip-500',
  1000: 'bj-chip-1000',
  5000: 'bj-chip-5000',
  10000: 'bj-chip-10000',
};

const RESULT_LABELS: Record<string, string> = {
  WIN: '赢了!',
  LOSE: '输了',
  PUSH: '平局',
  BLACKJACK: 'Blackjack!',
};

export function Blackjack() {
  const { toast } = useToast();
  const [status, setStatus] = useState<BlackjackStatus | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [betAmount, setBetAmount] = useState(1000);
  const [convertAmount, setConvertAmount] = useState('');
  const [convertOpen, setConvertOpen] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);

  const fetchStatus = useCallback(async () => {
    try {
      const s = await blackjackApi.status();
      setStatus(s);
      if (s.activeGame) setResumeOpen(true);
    } catch (e: unknown) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { void fetchStatus(); }, [fetchStatus]);

  const act = async (fn: () => Promise<GameState>) => {
    if (acting) return;
    setActing(true);
    try {
      const state = await fn();
      setGame(state);
      if (status) setStatus({ ...status, chips: state.chips });
    } catch (e: unknown) {
      toast((e as Error).message || '操作失败', 'error');
    } finally {
      setActing(false);
    }
  };

  const handleBet = () => act(async () => {
    return await blackjackApi.bet(betAmount);
  });

  const handleNewGame = () => {
    setGame(null);
    void fetchStatus();
  };

  const handleResume = () => {
    if (status?.activeGame) setGame(status.activeGame);
    setResumeOpen(false);
  };

  const handleForfeit = async () => {
    try {
      await blackjackApi.forfeit();
      setResumeOpen(false);
      setGame(null);
      void fetchStatus();
    } catch (e: unknown) {
      toast((e as Error).message || '操作失败', 'error');
    }
  };

  const handleConvert = async () => {
    const amt = parseInt(convertAmount);
    if (!amt || amt <= 0) return;
    try {
      const result = await blackjackApi.convert(amt);
      toast(`成功转出 ${amt.toLocaleString()} 积分`, 'success');
      setConvertOpen(false);
      setConvertAmount('');
      if (status) {
        setStatus({ ...status, chips: result.chips, todayConverted: result.todayConverted, convertable: Math.max(0, result.chips - 20000) });
      }
    } catch (e: unknown) {
      toast((e as Error).message || '转出失败', 'error');
    }
  };

  if (loading) {
    return (
      <div className="max-w-2xl mx-auto p-4 space-y-4">
        <Skeleton className="h-16 w-full rounded-xl" />
        <Skeleton className="h-80 w-full rounded-2xl" />
      </div>
    );
  }

  const chips = game ? game.chips : (status?.chips ?? 0);
  const isSettled = game?.phase === 'SETTLED';
  const isPlaying = game && !isSettled;
  const poolExhausted = (status?.dailyPool ?? 1) <= 0;

  return (
    <div className="max-w-2xl mx-auto p-4 space-y-4">
      <div className="rounded-2xl bg-red-50 dark:bg-red-500/10 p-5 neu-raised">
        <h3 className="text-base font-bold text-red-800 dark:text-red-400 mb-2">郑重声明与风险提示</h3>
        <ul className="list-disc list-inside text-sm text-red-900 dark:text-red-200/90 space-y-1 leading-relaxed">
          <li>本小游戏不涉及任何赌博行为，不涉及任何现实资金下注或交易。</li>
          <li>仅用于为用户提供一个每日资金获取途径的趣味化体验，所有结算均为站内机制。</li>
          <li>赌博可能导致成瘾、债务风险、家庭关系破裂及心理健康问题，请远离任何现实赌博活动。</li>
        </ul>
      </div>

      {/* 顶栏 */}
      <div className="flex items-center justify-between px-1">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-xl bg-emerald-500/20">
            <Spade className="w-5 h-5 text-emerald-400" />
          </div>
          <div>
            <div className="text-xs text-muted-foreground">积分</div>
            <div className="text-xl font-bold tabular-nums">{chips.toLocaleString()}</div>
          </div>
          {status && (
            <div className="ml-2 pl-3 border-l border-white/10">
              <div className="text-xs text-muted-foreground">今日积分池</div>
              <div className={cn('text-sm font-bold tabular-nums', (status.dailyPool ?? 0) <= 0 ? 'text-red-400' : 'text-emerald-400')}>
                {(status.dailyPool ?? 0).toLocaleString()}
              </div>
            </div>
          )}
        </div>
        <div className="flex items-center gap-2">
          {status && (
            <div className="text-xs text-muted-foreground text-right space-y-0.5 mr-2">
              <div>{status.totalHands}局 | 赢{status.totalWon.toLocaleString()} | 输{status.totalLost.toLocaleString()}</div>
            </div>
          )}
          {status && status.convertable > 0 && !isPlaying && (
            <Button variant="outline" size="sm" onClick={() => setConvertOpen(true)}>
              <ArrowLeftRight className="w-3.5 h-3.5" />
              转出
            </Button>
          )}
        </div>
      </div>

      {/* 牌桌 */}
      {game ? (
        <div className="bj-table casino-felt rounded-2xl p-5 sm:p-6">
          {/* 庄家 */}
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-white/60 uppercase tracking-wider">庄家</span>
              {game.dealerScore != null && (
                <span className="text-sm font-bold text-white bg-black/30 px-2 py-0.5 rounded-md">
                  {game.dealerScore}
                </span>
              )}
            </div>
            <div className="flex gap-2 sm:gap-3 flex-wrap min-h-24 sm:min-h-28">
              {game.dealerCards.map((c, i) => (
                <PlayingCard key={`d-${i}`} card={c} delay={i * 100} />
              ))}
            </div>
          </div>

          {/* 中间分隔 */}
          <div className="my-5 flex items-center gap-3">
            <div className="flex-1 h-px bg-white/10" />
            <div className="w-1.5 h-1.5 rounded-full bg-white/15" />
            <div className="flex-1 h-px bg-white/10" />
          </div>

          {/* 玩家 */}
          {game.playerHands.map((hand, hi) => {
            const isActive = hi === game.activeHandIndex && !isSettled;
            return (
              <div key={hi} className={cn(
                'space-y-3 rounded-xl p-3 -mx-1 transition-colors border-l-2',
                isActive ? 'bg-white/5 border-l-amber-400' : 'border-l-transparent'
              )}>
                <div className="flex items-center gap-2 flex-wrap">
                  <span className={cn(
                    'text-xs font-semibold tracking-wider',
                    isActive ? 'text-amber-300' : 'text-white/60'
                  )}>
                    {game.playerHands.length > 1 ? `手牌 ${hi + 1}` : '玩家'}
                    {isActive && game.playerHands.length > 1 && ' · 当前'}
                  </span>
                  {hand.score > 0 && (
                    <span className={cn(
                      'text-sm font-bold px-2 py-0.5 rounded-md',
                      hand.isBust ? 'bg-red-500/30 text-red-300' : 'bg-black/30 text-white'
                    )}>
                      {hand.score}
                    </span>
                  )}
                  {hand.isBlackjack && (
                    <span className="text-xs font-bold text-amber-300 bg-amber-500/20 px-2 py-0.5 rounded-md">BJ!</span>
                  )}
                  {hand.isBust && (
                    <span className="text-xs font-bold text-red-300">爆牌</span>
                  )}
                  {hand.isDoubled && (
                    <span className="text-xs text-blue-300 bg-blue-500/20 px-1.5 py-0.5 rounded">x2</span>
                  )}
                  <span className="text-xs text-white/40 ml-auto tabular-nums">
                    本轮积分 {hand.bet.toLocaleString()}
                  </span>
                </div>
                <div className="flex gap-2 sm:gap-3 flex-wrap min-h-24 sm:min-h-28">
                  {hand.cards.map((c, ci) => (
                    <PlayingCard key={`p-${hi}-${ci}-${c}`} card={c} delay={ci * 100} />
                  ))}
                </div>
              </div>
            );
          })}

          {/* 保险 */}
          {game.insurance != null && (
            <div className="text-xs text-white/40 mt-2 flex items-center gap-1">
              <Shield className="w-3 h-3" /> 保险: {game.insurance.toLocaleString()}
            </div>
          )}

          {/* 结算 */}
          {isSettled && game.results && (
            <div className="mt-4 bj-result-in">
              <div className="bg-black/40 backdrop-blur-sm rounded-xl p-4 space-y-2 border border-white/10">
                {game.results.map((r: HandResult) => {
                  const isWin = r.result === 'WIN' || r.result === 'BLACKJACK';
                  const isLose = r.result === 'LOSE';
                  return (
                    <div key={r.handIndex} className="flex justify-between items-center">
                      <span className={cn(
                        'font-bold text-base',
                        isWin && 'text-green-400',
                        isLose && 'text-red-400',
                        r.result === 'PUSH' && 'text-white/60'
                      )}>
                        {game.playerHands.length > 1 ? `#${r.handIndex + 1} ` : ''}
                        {RESULT_LABELS[r.result]}
                      </span>
                      <span className={cn(
                        'font-bold text-lg tabular-nums',
                        r.net > 0 && 'text-green-400',
                        r.net < 0 && 'text-red-400',
                        r.net === 0 && 'text-white/50'
                      )}>
                        {r.net > 0 ? '+' : ''}{r.net.toLocaleString()}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* 操作按钮 */}
          <div className="mt-5">
            {isSettled ? (
              <Button
                onClick={handleNewGame}
                className="w-full h-12 text-base bg-emerald-600 hover:bg-emerald-500"
              >
                <RotateCcw className="w-4 h-4" />
                继续
              </Button>
            ) : (
              <div className="grid grid-cols-2 gap-2">
                {game.actions.includes('HIT') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.hit())} disabled={acting} color="blue" label="要牌">
                    <Hand className="w-4 h-4" /> 要牌
                  </ActionBtn>
                )}
                {game.actions.includes('STAND') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.stand())} disabled={acting} color="amber" label="我要验牌">
                    <Square className="w-4 h-4" /> 我要验牌
                  </ActionBtn>
                )}
                {game.actions.includes('DOUBLE') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.double())} disabled={acting} color="purple" label="加倍">
                    <CopyPlus className="w-4 h-4" /> 加倍
                  </ActionBtn>
                )}
                {game.actions.includes('SPLIT') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.split())} disabled={acting} color="green" label="分牌">
                    <Split className="w-4 h-4" /> 分牌
                  </ActionBtn>
                )}
                {game.actions.includes('INSURANCE') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.insurance())} disabled={acting} color="zinc" label="保险" span>
                    <Shield className="w-3.5 h-3.5" /> 保险
                  </ActionBtn>
                )}
              </div>
            )}
          </div>
        </div>
      ) : (
        /* 下注面板 */
        <div className="bj-table casino-felt rounded-2xl p-6 sm:p-8">
          <div className="text-center space-y-4">
            <div>
              <div className="text-xs text-white/40 uppercase tracking-widest mb-1">本轮积分</div>
              <div className="text-4xl sm:text-5xl font-bold text-white tabular-nums bj-count-up">
                {betAmount.toLocaleString()}
              </div>
            </div>

            {/* 筹码选择 */}
            <div className="flex flex-wrap justify-center gap-3 py-4">
              {BET_PRESETS.map((v, i) => (
                <button
                  key={v}
                  className={cn(
                    'bj-chip bj-chip-pop',
                    CHIP_COLORS[v],
                    betAmount === v && 'selected',
                  )}
                  style={{ animationDelay: `${i * 50}ms` }}
                  onClick={() => setBetAmount(v)}
                  disabled={v > chips}
                  aria-label={`本轮积分 ${v.toLocaleString()}`}
                >
                  {v >= 1000 ? `${v / 1000}K` : v}
                </button>
              ))}
            </div>

            {/* 发牌 */}
            {poolExhausted ? (
              <div className="text-sm text-red-400 text-center py-3">今日积分池已耗尽，明日重置</div>
            ) : (
              <Button
                className={cn(
                  'w-full max-w-xs mx-auto h-12 text-base font-bold',
                  'bg-amber-500 hover:bg-amber-400 text-black',
                  betAmount <= chips && betAmount >= 100 && !acting && 'bj-pulse-glow'
                )}
                onClick={handleBet}
                disabled={acting || betAmount > chips || betAmount < 100}
              >
                发牌
              </Button>
            )}
          </div>
        </div>
      )}

      {/* 规则与风险提示 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">小游戏规则与说明</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 text-sm leading-relaxed">
          <section>
            <h3 className="font-semibold mb-1">基础规则</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>目标是让手牌点数尽量接近 21 且不超过 21，超过即爆牌。</li>
              <li>A 可按 1 或 11 计算，J/Q/K 记作 10 点。</li>
              <li>玩家与庄家开局各发两张牌，玩家根据当前手牌执行动作。</li>
              <li>玩家全部手牌结束后进入庄家回合，最后按结算规则判定输赢。</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold mb-1">可执行动作</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>要牌（HIT）：再拿一张牌。</li>
              <li>停牌（STAND）：不再要牌，结束当前手。</li>
              <li>加倍（DOUBLE）：补一倍下注，仅再拿一张牌后自动停牌。</li>
              <li>分牌（SPLIT）：起手可分时拆成两手，分别继续操作并独立结算。</li>
              <li>保险（INSURANCE）：仅在庄家明牌 A 且首决策窗口可用。</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold mb-1">积分机制</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>积分为站内虚拟数值，用于小游戏内结算与展示。</li>
              <li>支持每日积分转出限制，用于提供日常趣味玩法与参与感。</li>
            </ul>
          </section>
        </CardContent>
      </Card>

      {/* 恢复牌局弹窗 */}
      <Dialog open={resumeOpen} onClose={() => setResumeOpen(false)}>
        <DialogHeader>
          <h2 className="text-lg font-bold">未完成的牌局</h2>
        </DialogHeader>
        <DialogContent>
          <p className="text-sm text-muted-foreground">
            你有一局未完成的牌局
            {status?.activeGame?.playerHands?.[0]?.bet && (
              <span>（本轮积分: {status.activeGame.playerHands[0].bet.toLocaleString()}）</span>
            )}
            ，要继续还是放弃？放弃将损失本轮已投入积分。
          </p>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={handleForfeit}>放弃</Button>
          <Button size="sm" onClick={handleResume}>继续游戏</Button>
        </DialogFooter>
      </Dialog>

      {/* 转出弹窗 */}
      <Dialog open={convertOpen} onClose={() => setConvertOpen(false)}>
        <DialogHeader>
          <h2 className="text-lg font-bold">积分转出</h2>
        </DialogHeader>
        <DialogContent>
          <div className="space-y-3">
            <div className="text-sm text-muted-foreground space-y-1">
              <p>可转出: <span className="font-bold text-foreground">{(status?.convertable ?? 0).toLocaleString()}</span></p>
              <p>今日已转: {(status?.todayConverted ?? 0).toLocaleString()} / {(status?.todayConvertLimit ?? 20000).toLocaleString()}</p>
            </div>
            <input
              type="number"
              value={convertAmount}
              onChange={e => setConvertAmount(e.target.value)}
              placeholder="输入转出金额"
              className="w-full px-3 py-2 rounded-lg bg-background text-sm neu-inset"
              min={1}
              max={Math.min(status?.convertable ?? 0, (status?.todayConvertLimit ?? 20000) - (status?.todayConverted ?? 0))}
            />
            <div className="flex gap-2">
              {[1000, 5000, 10000].map(v => (
                <Button key={v} variant="outline" size="sm" onClick={() => setConvertAmount(String(v))} className="flex-1">
                  {v >= 1000 ? `${v / 1000}K` : v}
                </Button>
              ))}
              <Button
                variant="outline"
                size="sm"
                onClick={() => setConvertAmount(String(Math.min(status?.convertable ?? 0, (status?.todayConvertLimit ?? 20000) - (status?.todayConverted ?? 0))))}
                className="flex-1"
              >
                MAX
              </Button>
            </div>
          </div>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => setConvertOpen(false)}>取消</Button>
          <Button size="sm" onClick={handleConvert} disabled={!convertAmount || parseInt(convertAmount) <= 0}>确认转出</Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

const ACTION_COLORS: Record<string, string> = {
  blue: 'text-blue-500',
  amber: 'text-amber-500',
  purple: 'text-purple-500',
  green: 'text-green-500',
  zinc: 'text-zinc-400',
};

function ActionBtn({ onClick, disabled, color, label, span, children }: {
  onClick: () => void;
  disabled: boolean;
  color: string;
  label: string;
  span?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      className={cn(
        'inline-flex items-center justify-center gap-1.5 rounded-lg font-bold transition-all neu-btn-sm',
        'h-11 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        'disabled:opacity-40 disabled:cursor-not-allowed',
        span && 'col-span-2 h-9 text-xs',
        ACTION_COLORS[color],
      )}
    >
      {children}
    </button>
  );
}
