import { useNavigate } from 'react-router-dom';
import { Pickaxe, Spade, Diamond, Gamepad2 } from 'lucide-react';
import { cn } from '../lib/utils';

const GAMES = [
  {
    key: 'mines',
    path: '/mines',
    icon: Pickaxe,
    title: '翻翻爆金币',
    desc: '5×5 格子藏 5 雷，翻得越多倍率越高',
    color: 'text-amber-400',
    bg: 'bg-amber-500/10 hover:bg-amber-500/20',
  },
  {
    key: 'blackjack',
    path: '/blackjack',
    icon: Spade,
    title: '21点',
    desc: '经典 Blackjack，积分对战庄家',
    color: 'text-emerald-400',
    bg: 'bg-emerald-500/10 hover:bg-emerald-500/20',
  },
  {
    key: 'videopoker',
    path: '/videopoker',
    icon: Diamond,
    title: '视频扑克',
    desc: "Joker's Wild，Joker牌百搭",
    color: 'text-purple-400',
    bg: 'bg-purple-500/10 hover:bg-purple-500/20',
  },
] as const;

export function Games() {
  const navigate = useNavigate();

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <div className="flex items-center justify-center gap-2.5">
        <div className="p-1.5 rounded-lg bg-pink-500/10">
          <Gamepad2 className="w-5 h-5 text-pink-500" />
        </div>
        <h1 className="text-xl font-bold">小游戏</h1>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        {GAMES.map(g => (
          <button
            key={g.key}
            onClick={() => navigate(g.path)}
            className={cn(
              'flex flex-col items-center gap-3 rounded-2xl p-6 transition-all cursor-pointer neu-btn-sm',
              g.bg,
            )}
          >
            <g.icon className={cn('w-10 h-10', g.color)} />
            <div className="text-center">
              <div className="font-bold text-lg">{g.title}</div>
              <div className="text-xs text-muted-foreground mt-1">{g.desc}</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
