import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import { NotificationList } from '../components/NotificationList';
import { mergeNotifications } from '../lib/notifications';
import { subscribe } from '../hooks/stompClient';
import { userApi, notificationApi } from '../api';
import type { NotificationItem } from '../types';
import { Trophy, Gamepad2, Sun, Moon, LogOut, LogIn, ChevronRight, User, LineChart, Monitor, RotateCcw, MessageSquare, Bell } from 'lucide-react';
import { cn } from '../lib/utils';

export function Me() {
  const navigate = useNavigate();
  const { user, logout, fetchUser } = useUserStore();
  const { ref: themeRef, toggleTheme, isDark } = useTheme();
  const { toast } = useToast();

  const [resetOpen, setResetOpen] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [resetting, setResetting] = useState(false);

  // 通知：手机端顶栏信封是 hidden md:flex 看不到，这里是手机用户唯一的通知入口
  const [unread, setUnread] = useState(0);
  const [notifOpen, setNotifOpen] = useState(false);
  const [notifs, setNotifs] = useState<NotificationItem[]>([]);
  const [notifLoading, setNotifLoading] = useState(false);

  // 只盯 id：fetchUser 每次返回新 user 对象，盯整个对象会反复退订重订，
  // 而退订到 0 时 stompClient 会直接断连，白抖一次 WS
  const userId = user?.id ?? null;

  useEffect(() => {
    if (userId == null) return;
    void notificationApi.unread().then(setUnread).catch(() => { /* 角标失败不打扰 */ });
    return subscribe('/user/queue/notification', msg => {
      const body = JSON.parse(msg.body) as { unread: number };
      setUnread(body.unread);
    });
  }, [userId]);

  // 展开才拉列表：进「我的」页不该无脑打一次 50 条查询
  const toggleNotif = async () => {
    if (notifOpen) { setNotifOpen(false); return; }
    setNotifOpen(true);
    setNotifLoading(true);
    try {
      // 先取列表再标已读，顺序反了刚到的通知会被读成已读，列表里分不出新旧
      const items = await notificationApi.recent();
      setNotifs(items);
      setUnread(0);
      await notificationApi.readAll();
    } catch {
      // 拉不到就是空列表
    } finally {
      setNotifLoading(false);
    }
  };

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

      {/* 通知（手机端唯一入口；PC 顶栏也有信封，两处共用同一份列表组件） */}
      {user && (
        <Card>
          <CardContent className="pt-5">
            <button
              onClick={() => void toggleNotif()}
              className="flex items-center gap-3 w-full text-left cursor-pointer group"
            >
              <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center relative">
                <Bell className="w-4 h-4 text-primary" />
                {unread > 0 && (
                  <span className="absolute -top-1 -right-1 min-w-4 h-4 px-1 rounded-full bg-destructive text-white text-[9px] font-black tabular-nums flex items-center justify-center">
                    {unread > 99 ? '99+' : unread}
                  </span>
                )}
              </div>
              <span className="flex-1 text-sm font-medium">通知</span>
              <ChevronRight className={cn(
                "w-4 h-4 text-muted-foreground transition-transform group-hover:text-primary",
                notifOpen && "rotate-90",
              )} />
            </button>

            {notifOpen && (
              <div className="mt-3 -mx-6 border-t border-border/50">
                <NotificationList
                  items={mergeNotifications(notifs)}
                  loading={notifLoading}
                  onSelect={commentId => navigate(`/comments?focus=${commentId}`)}
                  className="max-h-80"
                />
              </div>
            )}
          </CardContent>
        </Card>
      )}

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
