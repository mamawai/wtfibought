import { cn } from '../../lib/utils';
import { Mic, MicOff, Loader2, WifiOff } from 'lucide-react';

interface MicButtonProps {
  micOn: boolean;
  connected: boolean;
  connecting: boolean;
  onClick: () => void;
  className?: string;
}

export function MicButton({ micOn, connected, connecting, onClick, className }: MicButtonProps) {
  // 状态: 未连接 / 连接中 / 已连接+静音 / 已连接+开麦
  const getStyle = () => {
    if (connecting) return 'bg-yellow-500/20 text-yellow-400 border border-yellow-500/50';
    if (!connected) return 'bg-muted/30 text-muted-foreground/50 border border-border/50 hover:bg-muted/50';
    if (micOn) return 'bg-green-500/20 text-green-400 border border-green-500/50 hover:bg-green-500/30';
    return 'bg-red-500/15 text-red-400 border border-red-500/40 hover:bg-red-500/25';
  };

  const getIcon = () => {
    if (connecting) return <Loader2 className="w-4 h-4 animate-spin" />;
    if (!connected) return <WifiOff className="w-4 h-4" />;
    if (micOn) return <Mic className="w-4 h-4" />;
    return <MicOff className="w-4 h-4" />;
  };

  const getTitle = () => {
    if (connecting) return '连接中...';
    if (!connected) return '点击连接语音';
    if (micOn) return '点击关闭麦克风';
    return '点击开启麦克风';
  };

  return (
    <button
      onClick={onClick}
      disabled={connecting}
      className={cn(
        'relative w-9 h-9 rounded-full flex items-center justify-center transition-all',
        getStyle(),
        connecting && 'cursor-wait',
        className
      )}
      title={getTitle()}
    >
      {getIcon()}
      {connected && (
        <span className={cn(
          'absolute -top-0.5 -right-0.5 w-2 h-2 rounded-full',
          micOn ? 'bg-green-400 animate-pulse' : 'bg-red-400'
        )} />
      )}
    </button>
  );
}
