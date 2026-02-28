import { useState, useCallback, useEffect } from 'react';
import { Button } from '../ui/button';
import { Card414PlayingCard } from './Card414PlayingCard';
import { WsIndicator } from './WsIndicator';
import { MicButton } from './MicButton';
import { cn } from '../../lib/utils';
import type { CardRoom, Card414GameState } from '../../types';

interface VoiceState {
  micOn: boolean;
  connected: boolean;
  connecting: boolean;
  toggleMic: () => void;
}

interface Props {
  gameState: Card414GameState;
  room: CardRoom;
  player: { uuid: string; nickname: string };
  sendWs: (dest: string, body: Record<string, unknown>) => void;
  onForceQuit: () => void;
  gameOverInfo: { winner: string; winnerPlayers: string[] } | null;
  onBackToLobby: () => void;
  chaGouFlash: { type: string; seat: number } | null;
  wsConnected: boolean;
  voice: VoiceState;
}

function relativePositions(mySeat: number) {
  return [0, 1, 2, 3].map(i => (mySeat + i) % 4);
}

export function Card414Game({ gameState, room, player, sendWs, onForceQuit, gameOverInfo, onBackToLobby, chaGouFlash, wsConnected, voice }: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [roundCountdown, setRoundCountdown] = useState(0);

  const handleForceQuit = useCallback(() => {
    if (window.confirm('注意！退出后会销毁牌局，所有玩家将被踢出。确定退出吗？')) {
      onForceQuit();
    }
  }, [onForceQuit]);

  const gs = gameState;
  const mySeat = gs.mySeat ?? 0;
  const positions = relativePositions(mySeat);
  const isMyTurn = gs.turn === mySeat && gs.state === 'PLAY';
  const isFree = gs.lastPlay === null || gs.lightSeat === mySeat;

  useEffect(() => {
    if (gs.state === 'ROUND_OVER') {
      setRoundCountdown(5);
      const iv = setInterval(() => {
        setRoundCountdown(prev => {
          if (prev <= 1) { clearInterval(iv); return 0; }
          return prev - 1;
        });
      }, 1000);
      return () => clearInterval(iv);
    }
    setRoundCountdown(0);
  }, [gs.state, gs.round]);

  useEffect(() => setSelected(new Set()), [gs.turn]);

  const toggleCard = useCallback((card: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(card)) next.delete(card); else next.add(card);
      return next;
    });
  }, []);

  const playCards = useCallback(() => {
    if (selected.size === 0) return;
    sendWs('/app/414/play', {
      roomCode: room.roomCode,
      uuid: player.uuid,
      cards: Array.from(selected),
    });
    setSelected(new Set());
  }, [selected, sendWs, room.roomCode, player.uuid]);

  const doPass = useCallback(() => {
    sendWs('/app/414/pass', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const doCha = useCallback(() => {
    sendWs('/app/414/cha', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const doGou = useCallback(() => {
    sendWs('/app/414/gou', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const doPassCha = useCallback(() => {
    sendWs('/app/414/pass-cha', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const doPassGou = useCallback(() => {
    sendWs('/app/414/pass-gou', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const seatNickname = (seat: number) => room.seats[seat]?.nickname || `P${seat + 1}`;
  const seatTeam = (seat: number) => (seat === 0 || seat === 2) ? 'A' : 'B';
  const isFinished = (seat: number) => gs.finishOrder.includes(seat);

  const canCha = gs.state === 'CHA_WAIT' && gs.chaWaiters?.includes(mySeat);
  const canGou = gs.state === 'GOU_WAIT' && gs.gouWaiters?.includes(mySeat);

  const hunRank = gs.hunRank;

  const chaWaiterCount = gs.state === 'CHA_WAIT' ? (gs.chaWaiters?.length ?? 0) : 0;
  const gouWaiterCount = gs.state === 'GOU_WAIT' ? (gs.gouWaiters?.length ?? 0) : 0;

  if (!gs.handCounts || !gs.hand) {
    return <div className="min-h-screen flex items-center justify-center text-muted-foreground">加载中...</div>;
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center px-2 py-3 sm:px-6 sm:py-4">
     <div className="w-full max-w-5xl space-y-3">

      {/* 顶部状态栏 */}
      <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-card/60 border border-border/50">
        <div className="flex items-center gap-4 text-sm">
          <span className={cn(
            'px-2 py-0.5 rounded',
            gs.hunTeam === 'A' ? 'bg-blue-500/15 text-blue-400 font-bold' : 'text-muted-foreground'
          )}>
            A队混: {gs.hunA}
          </span>
          <span className="text-muted-foreground font-medium">第 {gs.round} 轮</span>
          <span className={cn(
            'px-2 py-0.5 rounded',
            gs.hunTeam === 'B' ? 'bg-red-500/15 text-red-400 font-bold' : 'text-muted-foreground'
          )}>
            B队混: {gs.hunB}
          </span>
        </div>
        <div className="flex items-center gap-3">
          <MicButton
            micOn={voice.micOn}
            connected={voice.connected}
            connecting={voice.connecting}
            onClick={voice.toggleMic}
          />
          <WsIndicator connected={wsConnected} />
          <button
          onClick={handleForceQuit}
          className="text-xs text-red-400/70 hover:text-red-400 border border-red-400/20 hover:border-red-400/50 rounded-md px-3 py-1 transition-colors"
        >
          退出
          </button>
        </div>
      </div>

      {/* 牌桌 */}
      <div className="card414-table px-4 py-6 sm:px-8 sm:py-8 space-y-5">

        {/* 对家(上) */}
        <div className="flex justify-center">
          <PlayerPanel
            nickname={seatNickname(positions[2])}
            team={seatTeam(positions[2])}
            handCount={gs.handCounts[positions[2]]}
            isCurrentTurn={gs.turn === positions[2]}
            isFinished={isFinished(positions[2])}
          />
        </div>

        {/* 中间一行: 左手 + 出牌区 + 右手 */}
        <div className="flex items-center gap-3 sm:gap-4">
          <PlayerPanel
            nickname={seatNickname(positions[3])}
            team={seatTeam(positions[3])}
            handCount={gs.handCounts[positions[3]]}
            isCurrentTurn={gs.turn === positions[3]}
            isFinished={isFinished(positions[3])}
          />

          {/* 中央出牌区 */}
          <div className="flex-1 flex flex-col items-center justify-center min-h-[80px] sm:min-h-[100px]">
            {gs.lastPlay ? (
              <div className="text-xs text-muted-foreground">{seatNickname(gs.lastPlay.seat)} · {gs.lastPlay.type}</div>
            ) : (
              <div className="text-sm text-muted-foreground/60">
                {gs.state === 'ROUND_OVER' ? '本轮结束' : '自由出牌'}
              </div>
            )}

            {gs.lightSeat === mySeat && (
              <div className="mt-2 px-4 py-1.5 rounded-full bg-green-500/10 border border-green-500/30">
                <span className="text-sm font-bold text-green-400">你获得了光！自由出牌</span>
              </div>
            )}
          </div>

          <PlayerPanel
            nickname={seatNickname(positions[1])}
            team={seatTeam(positions[1])}
            handCount={gs.handCounts[positions[1]]}
            isCurrentTurn={gs.turn === positions[1]}
            isFinished={isFinished(positions[1])}
          />
        </div>

        {/* 出牌展示 + 叉/勾提示 */}
        <div className="flex flex-col items-center gap-1.5 min-h-[28px]">
          {gs.lastPlay && (
            <div className="flex justify-center">
              {gs.lastPlay.cards.map((c, i) => (
                <div key={i} className={cn('relative', i > 0 && '-ml-4')} style={{ zIndex: i }}>
                  <Card414PlayingCard card={c} size="sm" isHun={cardRank(c) === hunRank} />
                </div>
              ))}
            </div>
          )}
          <div className="flex items-center justify-center gap-2 flex-wrap">
            {gs.state === 'CHA_WAIT' && (
            <div className="px-3 py-1 rounded-full bg-amber-500/10 border border-amber-500/30">
              <span className="text-xs font-bold text-amber-400 animate-pulse">
                等待叉 · {gs.chaRank} · {chaWaiterCount}人待响应
              </span>
            </div>
          )}

          {gs.state === 'GOU_WAIT' && (
            <div className="px-3 py-1 rounded-full bg-amber-500/10 border border-amber-500/30">
              <span className="text-xs font-bold text-amber-400 animate-pulse">
                {gs.chaSeat != null && gs.chaSeat >= 0 ? `${seatNickname(gs.chaSeat)} 叉了 · ` : ''}等待勾 · {gouWaiterCount}人待响应
              </span>
            </div>
          )}

          {chaGouFlash && (
            <div className={cn(
              'px-3 py-1 rounded-full border animate-pulse',
              chaGouFlash.type === 'CHA'
                ? 'bg-red-500/15 border-red-500/40'
                : 'bg-amber-500/15 border-amber-500/40'
            )}>
              <span className={cn(
                'text-xs font-bold',
                chaGouFlash.type === 'CHA' ? 'text-red-400' : 'text-amber-400'
              )}>
                {seatNickname(chaGouFlash.seat)} {chaGouFlash.type === 'CHA' ? '叉了！' : '勾了！'}
              </span>
            </div>
          )}
          </div>
        </div>

        {/* 我的手牌(下) */}
        <div className="pt-3 border-t border-white/5">
          <div className="flex items-center justify-center gap-2 mb-3">
            <span className={cn(
              'text-sm font-medium px-3 py-0.5 rounded-full',
              gs.turn === mySeat
                ? 'bg-green-500/15 text-green-400 border border-green-500/30'
                : 'text-muted-foreground'
            )}>
              {seatNickname(mySeat)}
              <span className={cn('ml-1 text-xs', seatTeam(mySeat) === 'A' ? 'text-blue-400' : 'text-red-400')}>
                ({seatTeam(mySeat)}队)
              </span>
              {isFinished(mySeat) && <span className="ml-1 text-green-400">(已出完)</span>}
            </span>
          </div>
          <div className="flex justify-center">
            {[...gs.hand].reverse().map((card, i) => (
              <div
                key={card + i}
                className={cn('relative', i > 0 && '-ml-8 sm:-ml-7')}
                style={{ zIndex: i }}
              >
                <Card414PlayingCard
                  card={card}
                  selected={selected.has(card)}
                  isHun={cardRank(card) === hunRank}
                  onClick={isMyTurn || canCha || canGou ? () => toggleCard(card) : undefined}
                />
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 操作区 */}
      <div className="flex gap-3 justify-center min-h-[48px] items-center">
        {isMyTurn && (
          <>
            <Button onClick={playCards} disabled={selected.size === 0} className="px-6">
              出牌
            </Button>
            {!isFree && (
              <Button onClick={doPass} variant="secondary" className="px-6">
                不要
              </Button>
            )}
          </>
        )}
        {canCha && (
          <>
            <Button onClick={doCha} className="px-6 bg-red-600 hover:bg-red-700 text-white">
              叉！
            </Button>
            <Button onClick={doPassCha} variant="secondary" className="px-6">
              不叉
            </Button>
          </>
        )}
        {canGou && (
          <>
            <Button onClick={doGou} className="px-6 bg-amber-600 hover:bg-amber-700 text-white">
              勾！
            </Button>
            <Button onClick={doPassGou} variant="secondary" className="px-6">
              不勾
            </Button>
          </>
        )}
        {gs.state === 'ROUND_OVER' && (
          <div className="text-center space-y-1.5 py-2">
            {(() => {
              const dagong = gs.finishOrder[0];
              const dagongTeam = (dagong === 0 || dagong === 2) ? 'A' : 'B';
              const partner = (dagong + 2) % 4;
              const partnerIdx = gs.finishOrder.indexOf(partner);
              const caught = partnerIdx === 1 ? 2 : partnerIdx === 2 ? 1 : 0;
              return (
                <>
                  <div className="text-base font-bold">
                    第{gs.round}轮结束 —{' '}
                    <span className={dagongTeam === 'A' ? 'text-blue-400' : 'text-red-400'}>
                      {dagongTeam}队
                    </span>
                    {' '}大贡{caught > 0 ? `，抓${caught}个` : '，土了'}
                  </div>
                  <div className="text-sm text-muted-foreground">
                    A队混: <span className="text-blue-400 font-bold">{gs.hunA}</span>
                    {'   '}
                    B队混: <span className="text-red-400 font-bold">{gs.hunB}</span>
                  </div>
                  <div className="text-sm text-muted-foreground">
                    {roundCountdown > 0 ? `${roundCountdown}秒后自动发牌...` : '发牌中...'}
                  </div>
                </>
              );
            })()}
          </div>
        )}
      </div>
     </div>

      {/* 胜利遮罩 */}
      {gameOverInfo && (
        <div className="fixed inset-0 bg-black/80 flex items-center justify-center z-50">
          <div className="text-center space-y-6">
            <div className="text-4xl sm:text-5xl font-bold tracking-wide">
              <span className={gameOverInfo.winner === 'A' ? 'text-blue-400' : 'text-red-400'}>
                {gameOverInfo.winner}队
              </span>
              {' '}胜利！
            </div>
            <div className="text-xl text-muted-foreground">
              {gameOverInfo.winnerPlayers.join(' & ')}
            </div>
            <Button onClick={onBackToLobby} className="px-8 py-2 mt-2">
              返回大厅
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

function PlayerPanel({ nickname, team, handCount, isCurrentTurn, isFinished }: {
  nickname: string;
  team: string;
  handCount: number;
  isCurrentTurn: boolean;
  isFinished: boolean;
}) {
  return (
    <div className={cn(
      'card414-player flex flex-col items-center gap-1.5',
      isCurrentTurn && 'active',
      isFinished && 'finished'
    )}>
      <div className={cn(
        'text-xs font-medium',
        team === 'A' ? 'text-blue-400' : 'text-red-400'
      )}>
        {nickname}
      </div>
      {isFinished ? (
        <div className="text-xs text-green-400 font-medium">已出完</div>
      ) : (
        <div className="flex">
          {Array.from({ length: Math.min(handCount, 6) }).map((_, i) => (
            <div key={i} className={cn('w-3.5 h-5 rounded-sm bg-gradient-to-b from-blue-800/80 to-blue-900/80 border border-blue-600/40', i > 0 && '-ml-1.5')} />
          ))}
          {handCount > 6 && <span className="text-[10px] text-muted-foreground ml-1">+{handCount - 6}</span>}
        </div>
      )}
      <div className="text-[10px] text-muted-foreground">{handCount}张</div>
    </div>
  );
}

function cardRank(card: string): string {
  if (card === 'JK' || card === 'BK') return card;
  return card.substring(1);
}
