import {useCallback, useEffect, useRef, useState} from 'react';
import {v4 as uuidv4} from 'uuid';
import {Client} from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {card414Api} from '../api';
import {useToast} from '../components/ui/use-toast';
import {Card414Lobby} from '../components/card414/Card414Lobby';
import {Card414Room} from '../components/card414/Card414Room';
import {Card414Game} from '../components/card414/Card414Game';
import {useAgoraVoice} from '../hooks/useAgoraVoice';
import type {Card414GameState, Card414WsMessage, CardRoom} from '../types';

const STORAGE_KEY = '414-player';

function getPlayer(): { uuid: string; nickname: string } {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored) {
    try { return JSON.parse(stored); } catch { /* */ }
  }
  const p = { uuid: uuidv4(), nickname: '' };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(p));
  return p;
}

function savePlayer(p: { uuid: string; nickname: string }) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(p));
}

export function Card414() {
  const { toast } = useToast();
  const [player, setPlayer] = useState(getPlayer);
  const [phase, setPhase] = useState<'lobby' | 'room' | 'game'>('lobby');
  const [room, setRoom] = useState<CardRoom | null>(null);
  const [gameState, setGameState] = useState<Card414GameState | null>(null);
  const [gameOverInfo, setGameOverInfo] = useState<{ winner: string; winnerPlayers: string[] } | null>(null);
  const [chaGouFlash, setChaGouFlash] = useState<{ type: string; seat: number } | null>(null);
  const [wsConnected, setWsConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const roomCodeRef = useRef<string>('');
  const wsMsgRef = useRef<(msg: Card414WsMessage) => void>(() => {});

  const voiceEnabled = phase !== 'lobby' && !!room?.roomCode;
  const voice = useAgoraVoice({
    roomCode: room?.roomCode || '',
    playerUuid: player.uuid,
    enabled: voiceEnabled,
  });

  const updateNickname = useCallback((n: string) => {
    const p = { ...player, nickname: n };
    setPlayer(p);
    savePlayer(p);
  }, [player]);

  // WS 连接
  const connectWs = useCallback((roomCode: string) => {
    if (clientRef.current?.connected) return;
    if (clientRef.current) {
      clientRef.current.deactivate();
      clientRef.current = null;
    }
    roomCodeRef.current = roomCode;

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/414') as WebSocket,
      reconnectDelay: 3000,
      onConnect: () => {
        setWsConnected(true);        client.subscribe(`/topic/414/room/${roomCode}`, (msg) => {
          const payload: Card414WsMessage = JSON.parse(msg.body);
          wsMsgRef.current(payload);
        });
        client.subscribe(`/topic/414/game/${roomCode}`, (msg) => {
          const payload: Card414WsMessage = JSON.parse(msg.body);
          wsMsgRef.current(payload);
        });
        // 重连后拉最新状态
        card414Api.getRoom(roomCode).then(setRoom).catch(() => {});
        card414Api.getGameState(roomCode, getPlayer().uuid).then(setGameState).catch(() => {});
      },
      onDisconnect: () => {
        setWsConnected(false);
      },
    });
    client.activate();
    clientRef.current = client;
  }, []);

  const disconnectWs = useCallback(() => {
    if (clientRef.current) {
      clientRef.current.deactivate();
      clientRef.current = null;
    }
    setWsConnected(false);
  }, []);

  const sendWs = useCallback((dest: string, body: Record<string, unknown>) => {
    if (clientRef.current?.connected) {
      clientRef.current.publish({
        destination: dest,
        body: JSON.stringify(body),
      });
    } else {
      toast('连接已断开，正在重连...', 'error');
    }
  }, [toast]);

  // WS 消息处理
  wsMsgRef.current = useCallback((msg: Card414WsMessage) => {
      const d = msg.data;
      switch (msg.type) {
          case 'PLAYER_JOIN':
          case 'PLAYER_LEAVE':
          case 'READY':
          case 'SEAT_SWAP':
              // 重新拉取房间状态
              if (roomCodeRef.current) {
                  card414Api.getRoom(roomCodeRef.current).then(setRoom).catch(() => {
                  });
              }
              break;

          case 'GAME_START':
              setPhase('game');
              // 拉取完整游戏状态+手牌
              if (roomCodeRef.current) {
                  card414Api.getGameState(roomCodeRef.current, getPlayer().uuid).then(setGameState).catch(() => {
                  });
              }
              break;

          case 'PLAY':
          case 'PASS':
          case 'CHA_WAIT':
          case 'GOU_WAIT':
          case 'CHA_TIMEOUT':
          case 'GOU_TIMEOUT':
          case 'PASS_CHA':
          case 'PASS_GOU':
          case 'TURN':
          case 'LIGHT':
              // 重拉游戏状态
              if (roomCodeRef.current) {
                  card414Api.getGameState(roomCodeRef.current, getPlayer().uuid).then(setGameState).catch(() => {
                  });
              }
              break;

          case 'CHA':
          case 'GOU': {
              const seat = (d as Record<string, unknown>).seat as number;
              setChaGouFlash({ type: msg.type, seat });
              setTimeout(() => setChaGouFlash(null), 2500);
              if (roomCodeRef.current) {
                  card414Api.getGameState(roomCodeRef.current, getPlayer().uuid).then(setGameState).catch(() => {
                  });
              }
              break;
          }

          case 'ROUND_OVER':
              if (roomCodeRef.current) {
                  card414Api.getGameState(roomCodeRef.current, getPlayer().uuid).then(setGameState).catch(() => {
                  });
              }
              break;

          case 'GAME_OVER': {
              const data = d as Record<string, unknown>;
              if (data.reason === 'player_quit') {
                  toast('有玩家退出，牌局已销毁', 'error');
                  setTimeout(() => {
                      disconnectWs();
                      setRoom(null);
                      setGameState(null);
                      setGameOverInfo(null);
                      setPhase('lobby');
                      roomCodeRef.current = '';
                      localStorage.removeItem('414-room');
                  }, 1500);
              } else {
                  setGameOverInfo({
                      winner: String(data.winner),
                      winnerPlayers: (data.winnerPlayers as string[]) || [],
                  });
              }
              break;
          }

          case 'ERROR': {
              const errData = d as Record<string, unknown>;
              if (!errData.uuid || errData.uuid === getPlayer().uuid) {
                  toast(String(errData.msg || '操作失败'), 'error');
              }
              break;
          }
      }
  }, [toast]);

  // 进入房间
  const enterRoom = useCallback((r: CardRoom) => {
    setRoom(r);
    setPhase('room');
    roomCodeRef.current = r.roomCode;
    connectWs(r.roomCode);
  }, [connectWs]);

  // 离开房间
  const leaveRoom = useCallback(async () => {
    if (roomCodeRef.current) {
      try {
        await card414Api.leaveRoom(player.uuid, roomCodeRef.current);
      } catch { /* */ }
    }
    voice.disconnect();
    disconnectWs();
    setRoom(null);
    setPhase('lobby');
    roomCodeRef.current = '';
    localStorage.removeItem('414-room');
  }, [player.uuid, disconnectWs, voice]);

  // 强制退出(销毁牌局)
  const forceQuit = useCallback(async () => {
    if (roomCodeRef.current) {
      try {
        await card414Api.forceQuit(player.uuid, roomCodeRef.current);
      } catch { /* */ }
    }
    voice.disconnect();
    disconnectWs();
    setRoom(null);
    setGameState(null);
    setPhase('lobby');
    roomCodeRef.current = '';
    localStorage.removeItem('414-room');
  }, [player.uuid, disconnectWs, voice]);

  // 游戏结束后返回大厅
  const backToLobby = useCallback(() => {
    voice.disconnect();
    disconnectWs();
    setRoom(null);
    setGameState(null);
    setGameOverInfo(null);
    setPhase('lobby');
    roomCodeRef.current = '';
    localStorage.removeItem('414-room');
  }, [disconnectWs, voice]);

  // 页面加载恢复
  useEffect(() => {
    const stored = localStorage.getItem('414-room');
    if (stored) {
      card414Api.getRoom(stored).then((r) => {
        if (r.status !== 'FINISHED') {
          enterRoom(r);
          if (r.status === 'PLAYING') {
            setPhase('game');
            card414Api.getGameState(stored, getPlayer().uuid).then(setGameState).catch(() => {});
          }
        } else {
          localStorage.removeItem('414-room');
        }
      }).catch(() => {
        localStorage.removeItem('414-room');
      });
    }
  }, [enterRoom]);

  // 保存房间码
  useEffect(() => {
    if (room?.roomCode) {
      localStorage.setItem('414-room', room.roomCode);
    }
  }, [room?.roomCode]);

  // 页面恢复可见时检测WS连接
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      if (!roomCodeRef.current) return;
      if (clientRef.current?.connected) {
        card414Api.getRoom(roomCodeRef.current).then(setRoom).catch(() => {});
        card414Api.getGameState(roomCodeRef.current, getPlayer().uuid).then(setGameState).catch(() => {});
      } else {
        clientRef.current?.deactivate();
        clientRef.current = null;
        connectWs(roomCodeRef.current);
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [connectWs]);

  // 卸载清理
  useEffect(() => () => disconnectWs(), [disconnectWs]);

  return (
    <div className="min-h-screen bg-background text-foreground">
      {phase === 'lobby' && (
        <Card414Lobby
          player={player}
          onNicknameChange={updateNickname}
          onEnterRoom={enterRoom}
        />
      )}
      {phase === 'room' && room && (
        <Card414Room
          room={room}
          player={player}
          sendWs={sendWs}
          onLeave={leaveRoom}
          wsConnected={wsConnected}
          voice={voice}
        />
      )}
      {phase === 'game' && gameState && room && (
        <Card414Game
          gameState={gameState}
          room={room}
          player={player}
          sendWs={sendWs}
          onForceQuit={forceQuit}
          gameOverInfo={gameOverInfo}
          onBackToLobby={backToLobby}
          chaGouFlash={chaGouFlash}
          wsConnected={wsConnected}
          voice={voice}
        />
      )}
    </div>
  );
}
