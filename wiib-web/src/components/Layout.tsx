import { NavLink, Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClickOutside } from '../hooks/useClickOutside';
import { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { useSystemHealth, type HealthLevel } from '../hooks/useSystemHealth';
import { NotificationBell } from './NotificationBell';
import { useLangToggle } from '../hooks/useLangToggle';
import { TickerStrip } from './TickerStrip';
import { OfflineBanner } from './OfflineBanner';
import { ChatDock } from './workbench/ChatDock';
import { cn } from '../lib/utils';
import {
  Home, Briefcase, Sun, Moon,
  BarChart3, User, ChevronDown, List, DollarSign,
  Settings2, Gem, Globe,
  ExternalLink, LogOut,
  ChartCandlestick, Swords, Trophy, Gamepad2, Route, History, MessageSquare, BookOpen,
} from 'lucide-react';

interface Props { children: React.ReactNode }

const MARKET_PATHS = ['/bstock', '/coin', '/commodity', '/tradfi'];

/** 介绍站是单独部署的静态站，不是本应用的路由，只能走外链 */
const INTRO_URL = 'https://intro.wtfibought.com';

/** 桌面 logo 展开态是整词，往下滚折叠成 WIIB.：留 W(hat) I(f) I B(ought)，其余字母挂 go 淡掉 */
const WORDMARK = 'WHATIFIBOUGHT';
const WORDMARK_KEEP = new Set([0, 4, 6, 7]);

/** 往下滚过这么多 px 顶栏就折叠，回到这以内展开；不是距离进度，只是防顶部回弹抖动的阈值 */
const COLLAPSE_AT = 8;

/** 下拉里的图标一律 15px：外面 .nav .ic 是 13px 的，面板里那个尺寸太小 */
const MENU_IC = 'size-[15px]';

/** 当前路由是否落在这组前缀里——下拉自身要跟着亮激活态，不然进了子页顶栏就没了着落 */
const matchPaths = (pathname: string, paths: string[]) =>
  paths.some(p => pathname === p || pathname.startsWith(p + '/'));

/** 模块级常量存 key 不存文案：存文案的话切语言不会变 */
const LED_LABEL_KEY: Record<HealthLevel, string> = {
  ok: 'led.ok', warn: 'led.warn', down: 'led.down', unknown: 'led.unknown',
};

/** 三服务状态灯组：feed(行情上游) / quant(量化) / sim(交易主进程)，悬停看明细 */
function SystemLeds() {
  const { feed, quant, sim } = useSystemHealth();
  const { t } = useTranslation('layout');
  const cls = (l: HealthLevel) =>
    l === 'ok' ? 'led' : l === 'warn' ? 'led led-warn' : l === 'down' ? 'led led-down' : 'led led-off';
  return (
    <span
      className="leds"
      // feed / quant / sim 是服务名，不翻
      title={`feed ${t(LED_LABEL_KEY[feed])} · quant ${t(LED_LABEL_KEY[quant])} · sim ${t(LED_LABEL_KEY[sim])}`}
    >
      <i className={cls(feed)} />
      <i className={cls(quant)} />
      <i className={cls(sim)} />
    </span>
  );
}

export function Layout({ children }: Props) {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, token, logout } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const { t, i18n } = useTranslation('layout');
  const { toggle: toggleLang } = useLangToggle();

  const isMarketActive = matchPaths(location.pathname, MARKET_PATHS);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const hdrRef = useRef<HTMLElement>(null);
  const navRef = useRef<HTMLElement>(null);
  const dotRef = useRef<HTMLSpanElement>(null);
  // 路由/语言变了要重新量宽、重新对位，但监听不想重挂，把当前的归位函数留一份在这
  const homeRef = useRef<(() => void) | null>(null);

  // 顶栏折叠 + 橙方块，一个 effect 管，全部直接操作 DOM，滚动不走 React 状态：
  // 折叠：滚过阈值给 header 挂 collapsed，回顶摘掉，动画本身是 CSS transition（见 index.css .hdr）
  // 橙方块：停在激活项左侧 13px；悬停到别的项就滑过去，移开滑回；折叠动画期间每帧跟着激活项走
  // useLayoutEffect：导航项的折叠宽度靠量出来的 --w，得在首帧画出来之前量好
  useLayoutEffect(() => {
    const hdr = hdrRef.current, nav = navRef.current, dot = dotRef.current;
    if (!hdr || !nav || !dot) return;

    // 折叠动画要知道各处的自然宽：字母写在自己身上，导航项量文案层写在项上
    const measure = () => {
      hdr.querySelectorAll<HTMLElement>('.logo .go').forEach(el =>
        el.style.setProperty('--w', `${el.getBoundingClientRect().width}px`));
      nav.querySelectorAll<HTMLElement>('.item').forEach(el => {
        const lab = el.querySelector('.lab');
        if (lab) el.style.setProperty('--w', `${lab.getBoundingClientRect().width}px`);
      });
    };

    let hover: HTMLElement | null = null;
    // 用 rect 差而不是 offsetLeft：下拉那一项外面套了个 relative 壳，offsetLeft 会算成 0
    const place = (animate: boolean) => {
      const el = hover ?? nav.querySelector<HTMLElement>('[data-nav-item].on');
      // 当前页不在导航里（/me、/login 这些）就把方块收掉
      if (!el) { dot.style.opacity = '0'; return; }
      dot.style.transition = animate ? '' : 'none';
      dot.style.left = `${el.getBoundingClientRect().left - nav.getBoundingClientRect().left - 13}px`;
      dot.style.opacity = '1';
    };

    // 折叠过渡期间每帧读一次两条进度，把方块摆到激活项当前位置，都到位就停
    let raf = 0;
    const track = () => {
      const cs = getComputedStyle(hdr);
      const ps = parseFloat(cs.getPropertyValue('--ps')), pf = parseFloat(cs.getPropertyValue('--pf'));
      place(false);
      const want = hdr.classList.contains('collapsed') ? 1 : 0;
      raf = Math.abs(ps - want) > .001 || Math.abs(pf - want) > .001 ? requestAnimationFrame(track) : 0;
    };
    const onScroll = () => {
      const want = window.scrollY > COLLAPSE_AT;
      if (want === hdr.classList.contains('collapsed')) return;
      hdr.classList.toggle('collapsed', want);
      if (!raf) raf = requestAnimationFrame(track);
    };

    // 委托到 nav 上：下拉面板里的项不带 data-nav-item，扫过去不会把方块带跑
    const over = (e: MouseEvent) => {
      const item = (e.target as HTMLElement).closest<HTMLElement>('[data-nav-item]');
      if (item && item !== hover) { hover = item; place(true); }
    };
    const leave = () => { hover = null; place(true); };
    const relayout = () => { measure(); place(false); };
    homeRef.current = () => { measure(); place(true); };

    measure();
    // 刷新时页面可能已在半路：先按位摆好，下一帧再挂 ready 开过渡，不然会从头播一遍
    hdr.classList.toggle('collapsed', window.scrollY > COLLAPSE_AT);
    place(false);
    const readyRaf = requestAnimationFrame(() => hdr.classList.add('ready'));

    nav.addEventListener('mouseover', over);
    nav.addEventListener('mouseleave', leave);
    window.addEventListener('resize', relayout);
    window.addEventListener('scroll', onScroll, { passive: true });
    // 字体换成 Archivo 后每处宽度都会变，等字体就位再量一次
    void document.fonts.ready.then(relayout);
    return () => {
      nav.removeEventListener('mouseover', over);
      nav.removeEventListener('mouseleave', leave);
      window.removeEventListener('resize', relayout);
      window.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(raf);
      cancelAnimationFrame(readyRaf);
    };
  }, []);

  // 切路由激活项换了（加粗后宽度也变），切语言文案换了，都要重量并让方块滑过去
  useEffect(() => { homeRef.current?.(); }, [location.pathname, i18n.language]);

  // 语言/主题两颗，桌面和手机顶栏共用
  const langButton = (
    <button type="button" onClick={toggleLang} aria-label={t('common:language.switch')}>
      中 / EN
    </button>
  );
  const themeButton = (
    <button type="button" onClick={toggleTheme} aria-label={isDark ? t('header.toLight') : t('header.toDark')}>
      {isDark ? <Sun className="ic" /> : <Moon className="ic" />}
    </button>
  );

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* ===== 顶栏（sticky，往下滚折叠成 WIIB. + 图标）+ 行情副条 ===== */}
      {/* 安全区：装成 PWA 后页面顶到屏幕边缘，顶栏自己让开刘海和横屏圆角 */}
      <header ref={hdrRef} className="hdr pt-[env(safe-area-inset-top)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <div className="wrap">
          {/* 桌面：logo + 文字导航 + 右侧工具 */}
          <div className="top hidden lg:flex">
            {/* 一个字母一个 span，折叠时要去的字母原地淡出，留下的滑拢成 WIIB. */}
            <Link to="/" className="logo" aria-label={t('header.logoHome')}>
              {[...WORDMARK].map((c, i) => <span key={i} className={WORDMARK_KEEP.has(i) ? undefined : 'go'}>{c}</span>)}
              <i>.</i>
            </Link>

            <nav ref={navRef} className="nav">
              <span ref={dotRef} className="nav-dot" style={{ opacity: 0 }} />
              <HeaderNavItem to="/" icon={<Home />} label={t('nav.home')} />
              <NavDropdown
                icon={<ChartCandlestick />}
                label={t('nav.markets')}
                isActive={isMarketActive}
                items={[
                  { to: '/bstock', icon: <List className={MENU_IC} />, label: t('marketMenu.stocks') },
                  { to: '/coin', icon: <DollarSign className={MENU_IC} />, label: t('marketMenu.crypto') },
                  { to: '/commodity', icon: <Gem className={MENU_IC} />, label: t('marketMenu.commodity') },
                  { to: '/tradfi', icon: <Globe className={MENU_IC} />, label: t('marketMenu.tradfi') },
                ]}
              />
              <HeaderNavItem to="/portfolio" icon={<Briefcase />} label={t('nav.portfolio')} />
              {/* 竞技场紧跟持仓：自己的仓位与 AI 的仓位是同一件事的两面，挨着看 */}
              <HeaderNavItem to="/arena" icon={<Swords />} label={t('nav.arena')} />
              {/* 配置＝BYOK 模型端点，竞技场里的 trader 全靠它，所以紧跟竞技场 */}
              <HeaderNavItem to="/ai" icon={<Settings2 />} label={t('nav.config')} />
              <HeaderNavItem to="/ranking" icon={<Trophy />} label={t('nav.ranking')} />
              <HeaderNavItem to="/games" icon={<Gamepad2 />} label={t('nav.games')} />
              <HeaderNavItem to="/strategies" icon={<Route />} label={t('nav.strategies')} />
              <HeaderNavItem to="/backtest" icon={<History />} label={t('nav.backtest')} />
              <HeaderNavItem to="/comments" icon={<MessageSquare />} label={t('nav.comments')} />
              {/* 外链：结构样式和普通项一样，但永远不带 on；data-nav-item 是给橙方块的抓手 */}
              <a href={INTRO_URL} target="_blank" rel="noopener noreferrer" data-nav-item data-label={t('nav.intro')} className="item">
                <span className="ico"><BookOpen /></span>
                <span className="lab">
                  {t('nav.intro')}
                  {/* 尾巴上的小箭头告诉用户这一下会跳出站 */}
                  <ExternalLink className="ic" />
                </span>
              </a>
            </nav>

            <div className="tools">
              <SystemLeds />
              {langButton}
              {themeButton}
              {/* 有 token 但 user 还没拉回来时两边都不显示，否则每次刷新都要闪一下"登录"再变回用户名 */}
              {user ? (
                <>
                  <NotificationBell />
                  {/* 用户名只作标识不可点，退出就摆在旁边（「我的」页是手机端入口） */}
                  <span>{user.username}</span>
                  <button type="button" onClick={handleLogout} title={t('header.logout')} aria-label={t('header.logout')}>
                    <LogOut className="ic" />
                  </button>
                </>
              ) : !token && (
                <Link to="/login" className="btn sm">{t('header.login')}</Link>
              )}
            </div>
          </div>

          {/* 手机：logo + 语言 + 主题，其余入口在底部 Tab 和「我的」页 */}
          <div className="flex lg:hidden items-center h-14">
            <Link to="/" className="logo" aria-label={t('header.logoHome')}>WIIB<i>.</i></Link>
            <div className="tools">
              {langButton}
              {themeButton}
            </div>
          </div>
        </div>

        <div className="rule" />
      </header>
      {/* 副条和离线横幅不钉住，跟页面一起滚走 */}
      <TickerStrip />
      <OfflineBanner />

      {/* pb-24 是给底部 Tab 让位，桌面端不留内边距，页内自己定 */}
      <main className="flex-1 pb-24 lg:pb-0 pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        {children}
      </main>

      {/* w-full 不能省：外层是 flex-col，.wrap 的 margin:auto 会让它缩成内容宽 */}
      <div className="wrap w-full hidden lg:block">
        <footer className="pt">
          <span>{t('footer.brand')}</span>
          <span>{t('footer.disclaimer')}</span>
        </footer>
      </div>

      {/* ===== 手机端底部 Tab：贴边实条 ===== */}
      <nav className="fixed bottom-0 inset-x-0 lg:hidden z-50 flex items-stretch border-t-2 border-foreground bg-background pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <BottomNavItem to="/" icon={<Home className="w-5 h-5" />} label={t('nav.home')} />
        <BottomNavItem to="/bstock" icon={<BarChart3 className="w-5 h-5" />} label={t('nav.markets')} forceActive={isMarketActive} />
        <BottomNavItem to="/portfolio" icon={<Briefcase className="w-5 h-5" />} label={t('nav.portfolio')} />
        <BottomNavItem to="/me" icon={<User className="w-5 h-5" />} label={t('nav.me')} />
        <BottomNavItem to="/ai" icon={<Settings2 className="w-5 h-5" />} label={t('nav.config')} />
      </nav>

      {/* 全站悬浮研判对话（BYOK）：对话要登录，游客不给气泡 */}
      {user && <ChatDock />}
    </div>
  );
}

/** 顶栏导航项：图标 + 文案两层，样式全在 .nav .item 里；激活挂 on，data-nav-item 给橙方块抓手，data-label 给折叠后的悬停标签 */
function HeaderNavItem({ to, icon, label }: { to: string; icon: React.ReactNode; label: string }) {
  return (
    <NavLink to={to} data-nav-item data-label={label} className={({ isActive }) => cn('item', isActive && 'on')}>
      <span className="ico">{icon}</span>
      <span className="lab">{label}</span>
    </NavLink>
  );
}

interface NavDropdownItem { to: string; icon: React.ReactNode; label: string }

/** 顶栏下拉壳子：目前只有「市场」在用，四个子市场收在面板里 */
function NavDropdown({ icon, label, isActive, items }:
  { icon: React.ReactNode; label: string; isActive: boolean; items: NavDropdownItem[] }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false));

  return (
    <div ref={ref} className="relative flex items-center">
      {/* 按钮和普通项同一套 .nav .item 结构样式，小箭头放文案层里跟着一起淡 */}
      <button
        type="button"
        data-nav-item
        data-label={label}
        onClick={() => setOpen(v => !v)}
        className={cn('item', isActive && 'on')}
      >
        <span className="ico">{icon}</span>
        <span className="lab">
          {label}
          <ChevronDown className={cn('ic transition-transform', open && 'rotate-180')} />
        </span>
      </button>

      {open && (
        // z-50 不能省：NumberFlow 的 transform 会创建层叠上下文，副条数字会盖到面板上
        <div className="absolute top-full left-0 mt-1.5 min-w-44 border border-foreground bg-background py-2 z-50 animate-in fade-in slide-in-from-top-2">
          {items.map(({ to, icon, label: itemLabel }) => (
            <NavLink
              key={to}
              to={to}
              onClick={() => setOpen(false)}
              className={({ isActive: a }) =>
                cn(
                  'flex items-center gap-2.5 px-3.5 py-2 text-sm transition-colors',
                  a ? 'text-foreground font-bold' : 'text-muted-foreground font-medium hover:text-foreground',
                )
              }
            >
              {icon}
              {itemLabel}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}

/** 底部 Tab 项：激活 = 墨色字 + 图标上方一颗橙方块 */
function BottomNavItem({ to, icon, label, forceActive }: { to: string; icon: React.ReactNode; label: string; forceActive?: boolean }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'flex-1 flex flex-col items-center gap-0.5 py-1.5 transition-colors',
          (forceActive || isActive) ? 'text-foreground' : 'text-muted-foreground',
        )
      }
    >
      {({ isActive }) => (
        <>
          {/* 不激活也占着这 7px，免得切格子时整列跳一下 */}
          <span className={cn('w-[7px] h-[7px]', (forceActive || isActive) && 'bg-primary')} />
          {icon}
          <span className="text-[10px] font-semibold">{label}</span>
        </>
      )}
    </NavLink>
  );
}
