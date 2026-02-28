import { useCallback } from 'react';
import { Button } from '../ui/button';
import { useToast } from '../ui/use-toast';
import { WsIndicator } from './WsIndicator';
import { MicButton } from './MicButton';
import { cn } from '../../lib/utils';
import type { CardRoom } from '../../types';

interface VoiceState {
  micOn: boolean;
  connected: boolean;
  connecting: boolean;
  toggleMic: () => void;
}

interface Props {
  room: CardRoom;
  player: { uuid: string; nickname: string };
  sendWs: (dest: string, body: Record<string, unknown>) => void;
  onLeave: () => void;
  wsConnected: boolean;
  voice: VoiceState;
}

const TEAM_COLORS: Record<string, string> = {
  A: 'border-blue-500/40 bg-blue-500/5',
  B: 'border-red-500/40 bg-red-500/5',
};

const TEAM_LABELS: Record<string, string> = { A: 'A队', B: 'B队' };

// 座位布局: 上(2) 左(1) 右(3) 下(0) — 对座为队友
const SEAT_LAYOUT = [
  { idx: 2, pos: 'col-start-2 row-start-1', label: '对家' },
  { idx: 1, pos: 'col-start-1 row-start-2', label: '左手' },
  { idx: 3, pos: 'col-start-3 row-start-2', label: '右手' },
  { idx: 0, pos: 'col-start-2 row-start-3', label: '我' },
];

export function Card414Room({ room, player, sendWs, onLeave, wsConnected, voice }: Props) {
  const { toast } = useToast();
  const mySeat = room.seats.find(s => s.uuid === player.uuid);

  const toggleReady = useCallback(() => {
    sendWs('/app/414/ready', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const startGame = useCallback(() => {
    sendWs('/app/414/start', { roomCode: room.roomCode, uuid: player.uuid });
  }, [sendWs, room.roomCode, player.uuid]);

  const swapSeat = useCallback((targetSeat: number) => {
    sendWs('/app/414/swap-seat', { roomCode: room.roomCode, uuid: player.uuid, targetSeat });
  }, [sendWs, room.roomCode, player.uuid]);

  const copyCode = useCallback(() => {
    navigator.clipboard.writeText(room.roomCode).then(
      () => toast('房间号已复制', 'success'),
      () => {}
    );
  }, [room.roomCode, toast]);

  const isHost = player.uuid === room.host;
  const playerCount = room.seats.filter(s => s.uuid).length;
  const allReady = room.seats.every(s => !s.uuid || s.ready);

  return (
    <div className="max-w-lg mx-auto px-4 py-8 space-y-6">
      {/* 头部 */}
      <div className="flex items-center justify-between">
        <button onClick={onLeave} className="text-sm text-muted-foreground hover:text-foreground">
          ← 退出房间
        </button>
        <div className="flex items-center gap-2">
          <MicButton
            micOn={voice.micOn}
            connected={voice.connected}
            connecting={voice.connecting}
            onClick={voice.toggleMic}
          />
          <WsIndicator connected={wsConnected} />
          <span className="font-mono text-lg font-bold tracking-widest">{room.roomCode}</span>
          <button onClick={copyCode} className="text-xs text-muted-foreground hover:text-foreground">
            复制
          </button>
        </div>
      </div>

      {/* 座位 */}
      <div className="grid grid-cols-3 grid-rows-3 gap-3 py-4">
        {SEAT_LAYOUT.map(({ idx, pos, label }) => {
          const seat = room.seats[idx];
          const occupied = !!seat?.uuid;
          const isMe = seat?.uuid === player.uuid;
          const team = seat?.team || ((idx === 0 || idx === 2) ? 'A' : 'B');

          return (
            <div
              key={idx}
              onClick={() => {
                if (!occupied && mySeat) swapSeat(idx);
              }}
              className={cn(
                'rounded-xl border-2 p-4 text-center transition-all min-h-[100px] flex flex-col items-center justify-center',
                pos,
                occupied ? TEAM_COLORS[team] : 'border-dashed border-muted-foreground/20 cursor-pointer hover:border-muted-foreground/40',
                isMe && 'ring-2 ring-primary'
              )}
            >
              <div className="text-[10px] text-muted-foreground mb-1">
                {TEAM_LABELS[team]} · {label}
              </div>
              {occupied ? (
                <>
                  <div className={cn('font-bold text-sm', isMe && 'text-primary')}>
                    {seat.nickname}
                    {seat.uuid === room.host && <span className="ml-1 text-xs text-amber-500">★</span>}
                  </div>
                  <div className={cn(
                    'text-xs mt-1 font-medium',
                    seat.ready ? 'text-green-500' : 'text-muted-foreground'
                  )}>
                    {seat.ready ? '已准备' : '未准备'}
                  </div>
                </>
              ) : (
                <div className="text-xs text-muted-foreground">空位</div>
              )}
            </div>
          );
        })}
      </div>

      {/* 操作 */}
      <div className="flex gap-3">
        <Button
          onClick={toggleReady}
          variant={mySeat?.ready ? 'secondary' : 'default'}
          className="flex-1"
        >
          {mySeat?.ready ? '取消准备' : '准备'}
        </Button>
        {isHost && (
          <Button
            onClick={startGame}
            disabled={playerCount < 4 || !allReady}
            className="flex-1"
            variant="default"
          >
            开始游戏 ({playerCount}/4)
          </Button>
        )}
      </div>

      {/* 提示 */}
      <p className="text-xs text-muted-foreground text-center">
        对座为队友（A队：座位1+3，B队：座位2+4）· 点击空位可换座
      </p>
    </div>
  );
}
