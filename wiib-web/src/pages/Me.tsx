import { useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import { NotificationList } from '../components/NotificationList';
import { ProfilePublicToggle } from '../components/ProfilePublicToggle';
import { LanguageSettingRow } from '../components/LanguageSwitcher';
import { useNotificationPanel } from '../hooks/useNotificationPanel';
import { userApi } from '../api';
import { Trophy, Sun, Moon, LogOut, ChevronRight, User, LineChart, RotateCcw, MessageSquare, Bell, Receipt, Gift, FlaskConical, Swords, Info, ExternalLink, type LucideIcon } from 'lucide-react';
import { cn } from '../lib/utils';

export function Me() {
  const { t } = useTranslation(['account', 'common']);
  const navigate = useNavigate();
  const { user, logout, fetchUser } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const { toast } = useToast();

  const [resetOpen, setResetOpen] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [resetting, setResetting] = useState(false);

  // 通知：手机端顶栏信封是 hidden lg:flex 看不到，这里是手机用户唯一的通知入口。
  // 订阅与开合逻辑跟顶栏信封共用一份 hook，未读数也是同一份（各存各的会导致
  // 在这儿标了已读、顶栏红点还挂着旧数字）
  // 只盯 id：fetchUser 每次返回新 user 对象，盯整个对象会反复退订重订，
  // 而退订到 0 时 stompClient 会直接断连，白抖一次 WS
  const userId = user?.id ?? null;
  const {
    unread, open: notifOpen, loading: notifLoading, items: notifItems, toggle: toggleNotif,
  } = useNotificationPanel(userId);

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
      toast(t('me.reset.done'), 'success');
    } catch (e) {
      toast((e as Error).message || t('me.reset.failed'), 'error');
    } finally {
      setResetting(false);
    }
  };

  // external=站外地址，走新标签页而不是路由跳转
  const items: { icon: LucideIcon; label: string; to: string; color: string; external?: boolean }[] = [
    // 活动同样只有这一个手机端入口（底栏 5 格已满），放第一位——限时的东西藏深了就没人看
    { icon: Gift, label: t('me.nav.campaign'), to: '/campaign', color: 'text-amber-400' },
    // 账单没进顶栏/底栏（导航已经满了），另一个入口在持仓页仓位历史旁边
    { icon: Receipt, label: t('me.nav.ledger'), to: '/ledger', color: 'text-primary' },
    // 竞技场原先只有桌面顶栏那一个入口，手机端零入口只能手敲 URL，排第三位补上
    { icon: Swords, label: t('me.nav.arena'), to: '/arena', color: 'text-cyan-400' },
    // 移动端底栏只有5槽，策略与排行一样从这里进（桌面走头部导航）
    { icon: LineChart, label: t('me.nav.strategies'), to: '/strategies', color: 'text-violet-400' },
    { icon: FlaskConical, label: t('me.nav.backtest'), to: '/backtest', color: 'text-orange-400' },
    { icon: Trophy, label: t('me.nav.ranking'), to: '/ranking', color: 'text-amber-400' },
    { icon: MessageSquare, label: t('me.nav.comments'), to: '/comments', color: 'text-teal-400' },
    // 介绍站是单独部署的静态站，手机端从这里进
    { icon: Info, label: t('me.nav.intro'), to: 'https://intro.wtfibought.com', color: 'text-sky-400', external: true },
  ];

  // 整页都是本人数据，user 没到之前没什么可显示的（路由已挡住未登录，null 只可能是还在拉）
  if (!user) return null;

  return (
    <div className="max-w-lg mx-auto px-4 py-6 space-y-4">
      {/* 用户信息 */}
      <Card>
        <CardContent className="pt-5">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-2xl bg-linear-to-br from-primary/20 to-accent/10 flex items-center justify-center">
              <User className="w-6 h-6 text-primary" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="font-bold text-lg truncate">{user.username}</div>
              <div className="text-xs text-muted-foreground">ID: {user.id}</div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 通知（手机端唯一入口；PC 顶栏也有信封，两处共用同一份列表组件） */}
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
            <span className="flex-1 text-sm font-medium">{t('notif.title')}</span>
            <ChevronRight className={cn(
              "w-4 h-4 text-muted-foreground transition-transform group-hover:text-primary",
              notifOpen && "rotate-90",
            )} />
          </button>

          {notifOpen && (
            <div className="mt-3 -mx-6 border-t border-border/50">
              <NotificationList
                items={notifItems}
                loading={notifLoading}
                onSelect={commentId => navigate(`/comments?focus=${commentId}`)}
                className="max-h-80"
              />
            </div>
          )}
        </CardContent>
      </Card>

      {/* 功能入口 */}
      <Card>
        <CardContent className="pt-5 divide-y divide-border/50">
          {items.map(({ icon: Icon, label, to, color, external }) => {
            // 尾巴区分去向：站内是往右进一层，站外是跳出去
            const Tail = external ? ExternalLink : ChevronRight;
            return (
              <button
                key={to}
                onClick={() => external ? window.open(to, '_blank', 'noopener,noreferrer') : navigate(to)}
                className="flex items-center gap-3 w-full py-3.5 first:pt-0 last:pb-0 text-left hover:text-primary transition-colors cursor-pointer group"
              >
                <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center">
                  <Icon className={cn("w-4 h-4", color)} />
                </div>
                <span className="flex-1 text-sm font-medium">{label}</span>
                <Tail className="w-4 h-4 text-muted-foreground group-hover:text-primary transition-colors" />
              </button>
            );
          })}
        </CardContent>
      </Card>

      {/* 隐私：详情页公开开关。关掉只挡详情页，仍照常上排行榜 */}
      <ProfilePublicToggle />

      {/* 语言切换：手机端唯一的正式入口（顶栏那个图标按钮小屏也在，但设置项才找得着） */}
      <LanguageSettingRow />

      {/* 主题切换 */}
      <Card>
        <CardContent className="pt-5">
          <button
            onClick={toggleTheme}
            className="flex items-center gap-3 w-full text-left cursor-pointer"
          >
            <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center">
              {isDark ? <Moon className="w-4 h-4 text-violet-400" /> : <Sun className="w-4 h-4 text-amber-400" />}
            </div>
            <span className="flex-1 text-sm font-medium">
              {isDark ? t('me.themeDark') : t('me.themeLight')}
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
              <h2 className="text-sm font-bold text-destructive">{t('me.reset.title')}</h2>
            </div>
            <p className="text-xs text-muted-foreground leading-relaxed">
              {t('me.reset.desc')}
            </p>
            <Button
              variant="ghost"
              size="sm"
              className="text-destructive hover:text-destructive hover:bg-destructive/8"
              onClick={() => setResetOpen(true)}
            >
              {t('me.reset.action')}
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
          {t('me.logout')}
        </Button>
      )}

      {/* 二次确认：必须逐字输入用户名，防误点 */}
      {user && (
        <Dialog open={resetOpen} onClose={closeReset}>
          <DialogHeader>
            <h2 className="text-lg font-bold text-destructive">{t('me.reset.dialogTitle')}</h2>
          </DialogHeader>
          <DialogContent>
            <div className="space-y-3">
              <p className="text-xs text-muted-foreground leading-relaxed">
                {t('me.reset.warnClears')}
              </p>
              <p className="text-xs leading-relaxed text-warning">
                {t('me.reset.warnCost')}
              </p>
              <p className="text-xs">
                {/* 用户名夹在句子中间，中英语序不同，整句交给 Trans 摆位 */}
                <Trans
                  ns="account"
                  i18nKey="me.reset.confirmName"
                  values={{ name: user.username }}
                  components={[<strong key="name" className="text-foreground" />]}
                />
              </p>
              <Input
                value={confirmName}
                onChange={e => setConfirmName(e.target.value)}
                placeholder={t('me.reset.namePlaceholder')}
                autoComplete="off"
              />
            </div>
          </DialogContent>
          <DialogFooter>
            <Button variant="ghost" size="sm" onClick={closeReset}>{t('common:cancel')}</Button>
            <Button
              size="sm"
              className="bg-destructive text-white hover:bg-destructive/90"
              disabled={confirmName !== user.username || resetting}
              onClick={handleReset}
            >
              {resetting ? t('me.reset.submitting') : t('me.reset.submit')}
            </Button>
          </DialogFooter>
        </Dialog>
      )}
    </div>
  );
}
