import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Switch } from '../components/ui/switch';
import { NotificationList } from '../components/NotificationList';
import { ProfilePublicToggle } from '../components/ProfilePublicToggle';
import { LanguageSettingRow } from '../components/LanguageSwitcher';
import { ResetAccountDialog } from '../components/ResetAccountDialog';
import { Avatar } from '../components/Avatar';
import { useNotificationPanel } from '../hooks/useNotificationPanel';
import {
  Bell, BookOpen, ChevronRight, ExternalLink, FlaskConical, History, Layers, LogOut, Megaphone, MessageCircle, Pickaxe,
  RotateCcw, Swords, Trophy, Wallet,
} from 'lucide-react';
import { useReleaseStore } from '../stores/releaseStore';
import { LATEST_VERSION } from '../lib/releases';
import { cn, fmtNum, fmtSignedPct } from '../lib/utils';
import './Me.css';

/** 介绍站是单独部署的静态站，手机端从这里进 */
const INTRO_URL = 'https://intro.wtfibought.com';

export function Me() {
  const { t } = useTranslation(['account', 'common']);
  const navigate = useNavigate();
  const { user, logout, fetchUser } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const [resetOpen, setResetOpen] = useState(false);
  const openNotes = useReleaseStore(s => s.openNotes);

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

  // 整页都是本人数据，user 没到之前没什么可显示的（路由已挡住未登录，null 只可能是还在拉）
  if (!user) return null;

  // 一行入口的里子：图标、名字、右边去向箭头（站外是跳出去的箭头）
  const rowBody = (icon: ReactNode, label: string, external?: boolean) => (
    <>
      {icon}{label}
      <span className="go2">{external ? <ExternalLink className="ic" /> : <ChevronRight className="ic" />}</span>
    </>
  );

  return (
    <div className="page-shell">
      <div className="p-me">
        <div className="who">
          <Avatar name={user.username} src={user.avatar} />
          <div className="min-w-0">
            <b className="truncate">{user.username}</b>
            <span className="nf">ID: {user.id}</span>
          </div>
          <div className="as">
            <b className="nf">₮{fmtNum(user.totalAssets)}</b>
            <span className={cn('nf', user.profitPct >= 0 ? 'up' : 'dn')}>{fmtSignedPct(user.profitPct)}</span>
          </div>
        </div>

        {/* 通知（手机端唯一入口；PC 顶栏也有信封，两处共用同一份列表组件），点开就地展开 */}
        <div className="blk grp">
          <button type="button" className="row" aria-expanded={notifOpen} onClick={() => void toggleNotif()}>
            <Bell className="ic" />{t('notif.title')}
            <span className="go2">
              {unread > 0 && <em className="nf">{unread > 99 ? '99+' : unread}</em>}
              <ChevronRight className="ic" />
            </span>
          </button>
          <div className={cn('col', notifOpen && 'open')}>
            <div>
              <div className="nl">
                <NotificationList
                  items={notifItems}
                  loading={notifLoading}
                  onSelect={commentId => navigate(`/comments?focus=${commentId}`)}
                  className="max-h-80"
                />
              </div>
            </div>
          </div>
        </div>

        {/* 各处入口：手机底栏只有 5 格，其余页面从这里进 */}
        <div className="blk grp">
          {/* 账单没进顶栏/底栏（导航已经满了），另一个入口在持仓页仓位历史旁边 */}
          <Link className="row" to="/ledger">{rowBody(<Wallet className="ic" />, t('me.nav.ledger'))}</Link>
          <Link className="row" to="/portfolio/history">{rowBody(<History className="ic" />, t('me.nav.history'))}</Link>
          <Link className="row" to="/arena">{rowBody(<Swords className="ic" />, t('me.nav.arena'))}</Link>
          <Link className="row" to="/strategies">{rowBody(<Layers className="ic" />, t('me.nav.strategies'))}</Link>
          <Link className="row" to="/backtest">{rowBody(<FlaskConical className="ic" />, t('me.nav.backtest'))}</Link>
          {/* 因子挖掘是同域下 wiib-alpha 托管的页面，不是本应用的路由，整页跳过去 */}
          <a className="row" href="/alpha/">{rowBody(<Pickaxe className="ic" />, t('me.nav.alpha'))}</a>
          <Link className="row" to="/ranking">{rowBody(<Trophy className="ic" />, t('me.nav.ranking'))}</Link>
          <Link className="row" to="/comments">{rowBody(<MessageCircle className="ic" />, t('me.nav.comments'))}</Link>
          <a className="row" href={INTRO_URL} target="_blank" rel="noopener noreferrer">
            {rowBody(<BookOpen className="ic" />, t('me.nav.intro'), true)}
          </a>
          {/* 更新日志：点开更新弹窗，右边带当前版本号 */}
          <button type="button" className="row" onClick={openNotes}>
            <Megaphone className="ic" />{t('me.nav.releases')}
            <span className="go2"><span className="nf">v{LATEST_VERSION}</span><ChevronRight className="ic" /></span>
          </button>
        </div>

        {/* 设置：公开持仓、语言、深色模式，即点即存 */}
        <div className="blk set">
          <ProfilePublicToggle row />
          <LanguageSettingRow />
          <div className="sw-row">
            <div><b>{t('me.themeDark')}</b></div>
            <Switch checked={isDark} onChange={toggleTheme} label={t('me.themeDark')} />
          </div>
        </div>

        <div className="blk set rst">
          <div>
            <b>{t('me.reset.title')}</b>
            <p>{t('me.reset.desc')}</p>
            <button type="button" className="btn risk" onClick={() => setResetOpen(true)}>
              <RotateCcw className="ic" />{t('me.reset.action')}
            </button>
          </div>
        </div>

        <button type="button" className="btn plain wide lg" onClick={handleLogout}>
          <LogOut className="ic" />{t('me.logout')}
        </button>
      </div>

      <ResetAccountDialog open={resetOpen} onClose={() => setResetOpen(false)} onDone={() => void fetchUser()} />
    </div>
  );
}
