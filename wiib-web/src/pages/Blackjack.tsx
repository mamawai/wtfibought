import {useCallback, useEffect, useState} from 'react';
import {Trans, useTranslation} from 'react-i18next';
import {blackjackApi} from '../api';
import {useToast} from '../components/ui/use-toast';
import {Button} from '../components/ui/button';
import {Card, CardContent, CardHeader, CardTitle} from '../components/ui/card';
import {Dialog, DialogContent, DialogFooter, DialogHeader} from '../components/ui/dialog';
import {PlayingCard} from '../components/blackjack/PlayingCard';
import {Skeleton} from '../components/ui/skeleton';
import {ArrowLeftRight, CopyPlus, Hand, RotateCcw, Shield, Spade, Split, Square} from 'lucide-react';
import {cn} from '../lib/utils';
import i18n from '../i18n';
import type {BlackjackStatus, GameState, HandResult} from '../types';

const BET_PRESETS = [50, 100, 500, 1000];
// 面额÷10对齐新经济，配色沿用原档位样式类
const CHIP_COLORS: Record<number, string> = {
  50: 'bj-chip-500',
  100: 'bj-chip-1000',
  500: 'bj-chip-5000',
  1000: 'bj-chip-10000',
};

// 后端结果枚举 → 词表 key：key 写死成字面量才 grep 得到，别拼 `result.${r}`
const RESULT_KEYS: Record<string, string> = {
  WIN: 'blackjack.result.win',
  LOSE: 'blackjack.result.lose',
  PUSH: 'blackjack.result.push',
  BLACKJACK: 'blackjack.result.blackjack',
};

export function Blackjack() {
  const { toast } = useToast();
  const { t } = useTranslation(['games', 'common']);
  const [status, setStatus] = useState<BlackjackStatus | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [betAmount, setBetAmount] = useState(100);
  const [convertAmount, setConvertAmount] = useState('');
  const [convertOpen, setConvertOpen] = useState(false);
  const [converting, setConverting] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);

  // 不依赖 t：切语言会换新 t，依赖了就会重拉、又弹恢复牌局框
  const fetchStatus = useCallback(async () => {
    try {
      const s = await blackjackApi.status();
      setStatus(s);
      if (s.activeGame) setResumeOpen(true);
    } catch (e: unknown) {
      toast((e as Error).message || i18n.t('common:loadFailed'), 'error');
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
      toast((e as Error).message || t('toast.actionFailed'), 'error');
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
      toast((e as Error).message || t('toast.actionFailed'), 'error');
    }
  };

  const handleConvert = async () => {
    const amt = parseInt(convertAmount);
    if (!amt || amt <= 0 || converting) return;
    setConverting(true);
    try {
      const result = await blackjackApi.convert(amt);
      toast(t('toast.converted', { amount: amt.toLocaleString() }), 'success');
      setConvertOpen(false);
      setConvertAmount('');
      if (status) {
        setStatus({ ...status, chips: result.chips, todayConverted: result.todayConverted, convertable: result.convertable });
      }
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.convertFailed'), 'error');
    } finally {
      setConverting(false);
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

  const isSettled = game?.phase === 'SETTLED';
  const isPlaying = game && !isSettled;
  // 不在牌局中就认 status：转出只更新它，act 每步也会把 chips 同步进去
  const chips = isPlaying ? game.chips : (status?.chips ?? 0);
  const poolExhausted = (status?.dailyPool ?? 1) <= 0;

  return (
    <div className="max-w-2xl mx-auto p-4 space-y-4">
      <div className="rounded-lg border border-loss/30 bg-loss/10 p-5">
        <h3 className="text-base font-bold text-red-800 dark:text-red-400 mb-2">{t('disclaimer.title')}</h3>
        <ul className="list-disc list-inside text-sm text-red-900 dark:text-red-200/90 space-y-1 leading-relaxed">
          <li>{t('disclaimer.item1')}</li>
          <li>{t('disclaimer.item2')}</li>
          <li>{t('disclaimer.item3')}</li>
        </ul>
      </div>

      {/* 顶栏：手机上积分+战绩+转出钮允许换行 */}
      <div className="flex flex-wrap items-center justify-between gap-2 px-1">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-xl bg-emerald-500/20">
            <Spade className="w-5 h-5 text-emerald-400" />
          </div>
          <div>
            <div className="text-xs text-muted-foreground">{t('blackjack.points')}</div>
            <div className="text-xl font-bold tabular-nums">{chips.toLocaleString()}</div>
          </div>
          {status && (
            <div className="ml-2 pl-3 border-l border-white/10">
              <div className="text-xs text-muted-foreground">{t('blackjack.dailyPool')}</div>
              <div className={cn('text-sm font-bold tabular-nums', (status.dailyPool ?? 0) <= 0 ? 'text-red-400' : 'text-emerald-400')}>
                {(status.dailyPool ?? 0).toLocaleString()}
              </div>
            </div>
          )}
        </div>
        <div className="flex items-center gap-2">
          {status && (
            <div className="text-xs text-muted-foreground text-right space-y-0.5 mr-2">
              <div>{t('blackjack.stats', {
                hands: status.totalHands,
                won: status.totalWon.toLocaleString(),
                lost: status.totalLost.toLocaleString(),
              })}</div>
            </div>
          )}
          {status && status.convertable > 0 && !isPlaying && (
            <Button variant="outline" size="sm" onClick={() => setConvertOpen(true)}>
              <ArrowLeftRight className="w-3.5 h-3.5" />
              {t('blackjack.convert')}
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
              <span className="text-xs font-semibold text-white/60 uppercase tracking-wider">{t('blackjack.dealer')}</span>
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
                    {game.playerHands.length > 1 ? t('blackjack.handN', { n: hi + 1 }) : t('blackjack.player')}
                    {isActive && game.playerHands.length > 1 && t('blackjack.handActive')}
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
                    <span className="text-xs font-bold text-red-300">{t('blackjack.bust')}</span>
                  )}
                  {hand.isDoubled && (
                    <span className="text-xs text-blue-300 bg-blue-500/20 px-1.5 py-0.5 rounded">x2</span>
                  )}
                  <span className="text-xs text-white/40 ml-auto tabular-nums">
                    {t('blackjack.handBet', { amount: hand.bet.toLocaleString() })}
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
              <Shield className="w-3 h-3" /> {t('blackjack.insurance', { amount: game.insurance.toLocaleString() })}
            </div>
          )}

          {/* 结算 */}
          {isSettled && game.results && (
            <div className="mt-4 bj-result-in">
              <div className="bg-black/40 rounded-xl p-4 space-y-2 border border-white/10">
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
                        {t(RESULT_KEYS[r.result])}
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
                {t('blackjack.continue')}
              </Button>
            ) : (
              <div className="grid grid-cols-2 gap-2">
                {game.actions.includes('HIT') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.hit())} disabled={acting} color="blue" label={t('blackjack.action.hit')}>
                    <Hand className="w-4 h-4" /> {t('blackjack.action.hit')}
                  </ActionBtn>
                )}
                {game.actions.includes('STAND') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.stand())} disabled={acting} color="amber" label={t('blackjack.action.stand')}>
                    <Square className="w-4 h-4" /> {t('blackjack.action.stand')}
                  </ActionBtn>
                )}
                {game.actions.includes('DOUBLE') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.double())} disabled={acting} color="purple" label={t('blackjack.action.double')}>
                    <CopyPlus className="w-4 h-4" /> {t('blackjack.action.double')}
                  </ActionBtn>
                )}
                {game.actions.includes('SPLIT') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.split())} disabled={acting} color="green" label={t('blackjack.action.split')}>
                    <Split className="w-4 h-4" /> {t('blackjack.action.split')}
                  </ActionBtn>
                )}
                {game.actions.includes('INSURANCE') && (
                  <ActionBtn onClick={() => act(() => blackjackApi.insurance())} disabled={acting} color="zinc" label={t('blackjack.action.insurance')} span>
                    <Shield className="w-3.5 h-3.5" /> {t('blackjack.action.insurance')}
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
              <div className="text-xs text-white/40 uppercase tracking-widest mb-1">{t('blackjack.betLabel')}</div>
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
                  aria-label={t('blackjack.handBet', { amount: v.toLocaleString() })}
                >
                  {v >= 1000 ? `${v / 1000}K` : v}
                </button>
              ))}
            </div>

            {/* 发牌 */}
            {poolExhausted ? (
              <div className="text-sm text-red-400 text-center py-3">{t('blackjack.poolExhausted')}</div>
            ) : (
              <Button
                className={cn(
                  'w-full max-w-xs mx-auto h-12 text-base font-bold',
                  'bg-amber-500 hover:bg-amber-400 text-black',
                  betAmount <= chips && !acting && 'bj-pulse-glow'
                )}
                onClick={handleBet}
                disabled={acting || betAmount > chips}
              >
                {t('blackjack.deal')}
              </Button>
            )}
          </div>
        </div>
      )}

      {/* 规则与风险提示 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">{t('blackjack.rules.title')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 text-sm leading-relaxed">
          <section>
            <h3 className="font-semibold mb-1">{t('blackjack.rules.basicsTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>{t('blackjack.rules.basics1')}</li>
              <li>{t('blackjack.rules.basics2')}</li>
              <li>{t('blackjack.rules.basics3')}</li>
              <li>{t('blackjack.rules.basics4')}</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold mb-1">{t('blackjack.rules.actionsTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>{t('blackjack.rules.actions1')}</li>
              <li>{t('blackjack.rules.actions2')}</li>
              <li>{t('blackjack.rules.actions3')}</li>
              <li>{t('blackjack.rules.actions4')}</li>
              <li>{t('blackjack.rules.actions5')}</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold mb-1">{t('blackjack.rules.pointsTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>{t('blackjack.rules.points1')}</li>
              <li>{t('blackjack.rules.points2')}</li>
              <li>{t('blackjack.rules.points3')}</li>
              <li>{t('blackjack.rules.points4')}</li>
              <li>{t('blackjack.rules.points5')}</li>
            </ul>
          </section>
        </CardContent>
      </Card>

      {/* 恢复牌局弹窗 */}
      {/* 点 X / 遮罩关掉 = 恢复：牌局还挂在后端，只关掉就回不去了 */}
      <Dialog open={resumeOpen} onClose={handleResume}>
        <DialogHeader>
          <h2 className="text-lg font-bold">{t('blackjack.resume.title')}</h2>
        </DialogHeader>
        <DialogContent>
          {/* 下注额夹在句子中间，中英语序不同，整句两版本切换而不是拼半句 */}
          <p className="text-sm text-muted-foreground">
            {status?.activeGame?.playerHands?.[0]?.bet
              ? t('blackjack.resume.bodyWithBet', { amount: status.activeGame.playerHands[0].bet.toLocaleString() })
              : t('blackjack.resume.body')}
          </p>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={handleForfeit}>{t('blackjack.resume.forfeit')}</Button>
          <Button size="sm" onClick={handleResume}>{t('blackjack.resume.resume')}</Button>
        </DialogFooter>
      </Dialog>

      {/* 转出弹窗 */}
      <Dialog open={convertOpen} onClose={() => setConvertOpen(false)}>
        <DialogHeader>
          <h2 className="text-lg font-bold">{t('blackjack.convertDialog.title')}</h2>
        </DialogHeader>
        <DialogContent>
          <div className="space-y-3">
            <div className="text-sm text-muted-foreground space-y-1">
              <p>
                <Trans
                  ns="games"
                  i18nKey="blackjack.convertDialog.available"
                  values={{ amount: (status?.convertable ?? 0).toLocaleString() }}
                  components={[<span key="amount" className="font-bold text-foreground" />]}
                />
              </p>
              <p>{t('blackjack.convertDialog.today', {
                used: (status?.todayConverted ?? 0).toLocaleString(),
                limit: (status?.todayConvertLimit ?? 500).toLocaleString(),
              })}</p>
              <p>{t('blackjack.convertDialog.note')}</p>
            </div>
            <input
              type="number"
              value={convertAmount}
              onChange={e => setConvertAmount(e.target.value)}
              placeholder={t('blackjack.convertDialog.placeholder')}
              className="w-full px-3 py-2 rounded-md bg-input border border-border text-sm"
              min={1}
              max={Math.min(status?.convertable ?? 0, (status?.todayConvertLimit ?? 500) - (status?.todayConverted ?? 0))}
            />
            <div className="flex gap-2">
              {[100, 200, 500].map(v => (
                <Button key={v} variant="outline" size="sm" onClick={() => setConvertAmount(String(v))} className="flex-1">
                  {v >= 1000 ? `${v / 1000}K` : v}
                </Button>
              ))}
              <Button
                variant="outline"
                size="sm"
                onClick={() => setConvertAmount(String(Math.min(status?.convertable ?? 0, (status?.todayConvertLimit ?? 500) - (status?.todayConverted ?? 0))))}
                className="flex-1"
              >
                MAX
              </Button>
            </div>
          </div>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => setConvertOpen(false)}>{t('common:cancel')}</Button>
          <Button size="sm" onClick={handleConvert} disabled={converting || !convertAmount || parseInt(convertAmount) <= 0}>{t('blackjack.convertDialog.confirm')}</Button>
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
        'inline-flex items-center justify-center gap-1.5 rounded-md font-bold border border-border machined hover:bg-surface-hover active:translate-y-px transition-all',
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
