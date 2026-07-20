import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import { userApi } from '../api';
import { Trophy, Gamepad2, Sun, Moon, LogOut, LogIn, ChevronRight, User, LineChart, Monitor, RotateCcw, MessageSquare } from 'lucide-react';
import { cn } from '../lib/utils';

export function Me() {
  const navigate = useNavigate();
  const { user, logout, fetchUser } = useUserStore();
  const { ref: themeRef, toggleTheme, isDark } = useTheme();
  const { toast } = useToast();

  const [resetOpen, setResetOpen] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [resetting, setResetting] = useState(false);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const closeReset = () => {
    setResetOpen(false);
    setConfirmName('');
  };

  const handleReset = async () => {
    setResetting(true);
    try {
      await userApi.resetAccount(confirmName);
      closeReset();
      await fetchUser();          // 立刻刷新余额显示
      toast('账户已重置为初始状态', 'success');
    } catch (e) {
      toast((e as Error).message || '重置失败', 'error');
    } finally {
      setResetting(false);
    }
  };

  const items = [
    // 移动端底栏只有5槽，策略/模拟盘与排行/游戏一样从这里进（桌面走头部导航）
    { icon: LineChart, label: '策略', to: '/strategies', color: 'text-violet-400' },
    { icon: Monitor, label: '模拟盘', to: '/testnet', color: 'text-sky-400' },
    { icon: Trophy, label: '排行榜', to: '/ranking', color: 'text-amber-400' },
    { icon: Gamepad2, label: '游戏中心', to: '/games', color: 'text-pink-400' },
    { icon: MessageSquare, label: '留言板', to: '/comments', color: 'text-teal-400' },
  ];

  return (
    <div className="max-w-lg mx-auto px-4 py-6 space-y-4">
      {/* 用户信息 */}
      <Card>
        <CardContent className="pt-5">
          {user ? (
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 rounded-2xl bg-linear-to-br from-primary/20 to-accent/10 flex items-center justify-center">
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

      {/* 危险操作：重置账户 */}
      {user && (
        <Card className="border-destructive/20">
          <CardContent className="pt-5 space-y-3">
            <div className="flex items-center gap-2">
              <RotateCcw className="w-4 h-4 text-destructive" />
              <h2 className="text-sm font-bold text-destructive">重置账户</h2>
            </div>
            <p className="text-xs text-muted-foreground leading-relaxed">
              清空全部持仓、订单、游戏记录与资产历史，余额恢复为初始资金。
              留言和禁言状态不受影响。每周只能重置一次。
            </p>
            <Button
              variant="ghost"
              size="sm"
              className="text-destructive hover:text-destructive hover:bg-destructive/8"
              onClick={() => setResetOpen(true)}
            >
              重置我的账户
            </Button>
          </CardContent>
        </Card>
      )}

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

      {/* 二次确认：必须逐字输入用户名，防误点 */}
      {user && (
        <Dialog open={resetOpen} onClose={closeReset}>
          <DialogHeader>
            <h2 className="text-lg font-bold text-destructive">确认重置账户</h2>
          </DialogHeader>
          <DialogContent>
            <div className="space-y-3">
              <p className="text-xs text-muted-foreground leading-relaxed">
                此操作不可撤销，将清空：现货与合约的全部持仓和订单、预测下注、
                21点/Mines/视频扑克记录、资产历史、每日 Buff、钱包划转流水。
              </p>
              <p className="text-xs">
                请输入你的用户名 <strong className="text-foreground">{user.username}</strong> 以确认：
              </p>
              <Input
                value={confirmName}
                onChange={e => setConfirmName(e.target.value)}
                placeholder="输入用户名"
                autoComplete="off"
              />
            </div>
          </DialogContent>
          <DialogFooter>
            <Button variant="ghost" size="sm" onClick={closeReset}>取消</Button>
            <Button
              size="sm"
              className="bg-destructive text-white hover:bg-destructive/90"
              disabled={confirmName !== user.username || resetting}
              onClick={handleReset}
            >
              {resetting ? '重置中…' : '确认重置'}
            </Button>
          </DialogFooter>
        </Dialog>
      )}
    </div>
  );
}
