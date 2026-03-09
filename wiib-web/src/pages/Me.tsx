import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Trophy, Gamepad2, Sun, Moon, LogOut, LogIn, ChevronRight, User } from 'lucide-react';
import { cn } from '../lib/utils';

export function Me() {
  const navigate = useNavigate();
  const { user, logout } = useUserStore();
  const { ref: themeRef, toggleTheme, isDark } = useTheme();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const items = [
    { icon: Trophy, label: '排行榜', to: '/ranking', color: 'text-amber-400' },
    { icon: Gamepad2, label: '游戏中心', to: '/games', color: 'text-pink-400' },
  ];

  return (
    <div className="max-w-lg mx-auto px-4 py-6 space-y-4">
      {/* 用户信息 */}
      <Card>
        <CardContent className="pt-5">
          {user ? (
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-primary/20 to-accent/10 flex items-center justify-center">
                <User className="w-6 h-6 text-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="font-bold text-lg truncate">{user.username}</div>
                <div className="text-xs text-muted-foreground">ID: {user.id}</div>
              </div>
            </div>
          ) : (
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">未登录</span>
              <Button size="sm" onClick={() => navigate('/login')}>
                <LogIn className="w-4 h-4" />
                登录
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 功能入口 */}
      <Card>
        <CardContent className="pt-5 divide-y divide-border/50">
          {items.map(({ icon: Icon, label, to, color }) => (
            <button
              key={to}
              onClick={() => navigate(to)}
              className="flex items-center gap-3 w-full py-3.5 first:pt-0 last:pb-0 text-left hover:text-primary transition-colors cursor-pointer group"
            >
              <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center">
                <Icon className={cn("w-4 h-4", color)} />
              </div>
              <span className="flex-1 text-sm font-medium">{label}</span>
              <ChevronRight className="w-4 h-4 text-muted-foreground group-hover:text-primary transition-colors" />
            </button>
          ))}
        </CardContent>
      </Card>

      {/* 主题切换 */}
      <Card>
        <CardContent className="pt-5">
          <button
            ref={themeRef}
            onClick={toggleTheme}
            className="flex items-center gap-3 w-full text-left cursor-pointer"
          >
            <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center">
              {isDark ? <Moon className="w-4 h-4 text-violet-400" /> : <Sun className="w-4 h-4 text-amber-400" />}
            </div>
            <span className="flex-1 text-sm font-medium">
              {isDark ? '深色模式' : '浅色模式'}
            </span>
            <div className={cn(
              "w-11 h-6 rounded-full relative transition-colors",
              isDark ? "bg-primary" : "bg-border"
            )}>
              <div className={cn(
                "absolute top-1 w-4 h-4 rounded-full bg-white shadow-sm transition-transform",
                isDark ? "translate-x-5.5" : "translate-x-1"
              )} />
            </div>
          </button>
        </CardContent>
      </Card>

      {/* 退出 */}
      {user && (
        <Button
          variant="ghost"
          className="w-full text-destructive hover:text-destructive hover:bg-destructive/8"
          onClick={handleLogout}
        >
          <LogOut className="w-4 h-4" />
          退出登录
        </Button>
      )}
    </div>
  );
}
