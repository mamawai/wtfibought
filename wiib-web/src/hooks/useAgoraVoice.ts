import { useState, useCallback, useRef, useEffect } from 'react';
import AgoraRTC, {
    type IAgoraRTCClient,
    type IMicrophoneAudioTrack,
    type IAgoraRTCRemoteUser,
} from 'agora-rtc-sdk-ng';

interface UseAgoraVoiceOptions {
  roomCode: string;
  playerUuid: string;
  enabled: boolean;
}

function uuidToUid(uuid: string): number {
  let hash = 0;
  for (let i = 0; i < uuid.length; i++) {
    hash = ((hash << 5) - hash) + uuid.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

export function useAgoraVoice({ roomCode, playerUuid, enabled }: UseAgoraVoiceOptions) {
  const [micOn, setMicOn] = useState(false);
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const clientRef = useRef<IAgoraRTCClient | null>(null);
  const localTrackRef = useRef<IMicrophoneAudioTrack | null>(null);
  const uidRef = useRef<number>(uuidToUid(playerUuid));

  const connect = useCallback(async () => {
    if (!enabled || !roomCode || clientRef.current) return;
    setConnecting(true);

    try {
      const channel = `414-${roomCode}`;
      const uid = uidRef.current;

      const resp = await fetch(`/api/agora/token?channel=${encodeURIComponent(channel)}&uid=${uid}`);
      if (!resp.ok) throw new Error('获取token失败');
      const json = await resp.json();
      const { token, appId } = json.data;

      const client = AgoraRTC.createClient({ mode: 'rtc', codec: 'vp8' });
      clientRef.current = client;

      client.on('user-published', async (user: IAgoraRTCRemoteUser, mediaType) => {
        if (mediaType === 'audio') {
          await client.subscribe(user, mediaType);
          user.audioTrack?.play();
        }
      });

      client.on('user-unpublished', (user: IAgoraRTCRemoteUser, mediaType) => {
        if (mediaType === 'audio') {
          user.audioTrack?.stop();
        }
      });

      await client.join(appId, channel, token, uid);
      setConnected(true);
    } catch (e) {
      console.error('声网连接失败:', e);
      clientRef.current = null;
    } finally {
      setConnecting(false);
    }
  }, [enabled, roomCode, playerUuid]);

  const disconnect = useCallback(async () => {
    if (localTrackRef.current) {
      localTrackRef.current.close();
      localTrackRef.current = null;
    }
    if (clientRef.current) {
      await clientRef.current.leave();
      clientRef.current = null;
    }
    setConnected(false);
    setMicOn(false);
  }, []);

  const toggleMic = useCallback(async () => {
    if (!clientRef.current) {
      await connect();
      return;
    }

    if (micOn) {
      if (localTrackRef.current) {
        await clientRef.current.unpublish(localTrackRef.current);
        localTrackRef.current.close();
        localTrackRef.current = null;
      }
      setMicOn(false);
    } else {
      try {
        const track = await AgoraRTC.createMicrophoneAudioTrack();
        localTrackRef.current = track;
        await clientRef.current.publish(track);
        setMicOn(true);
      } catch (e) {
        console.error('麦克风获取失败:', e);
      }
    }
  }, [micOn, connect]);

  useEffect(() => {
    if (enabled && roomCode) {
      connect();
    }
    return () => { disconnect(); };
  }, [enabled, roomCode]);

  return {
    micOn,
    connected,
    connecting,
    toggleMic,
    disconnect,
  };
}
