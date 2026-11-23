import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClickOutside } from '../hooks/useClickOutside';
import { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { useSystemHealth, type HealthLevel } from '../hooks/useSystemHealth';
import { NotificationBell } from './NotificationBell';
import { GitHubLink } from './GitHubLink';
import { ReleaseButton, ReleaseDialog, ReleaseVersion } from './ReleaseNotes';
import { useHasUpdate } from '../stores/releaseStore';
import { useLangToggle } from '../hooks/useLangToggle';
import { TickerStrip } from './TickerStrip';
import { OfflineBanner } from './OfflineBanner';
import { ChatDock } from './workbench/ChatDock';
import { cn } from '../lib/utils';
import { MARKET_PATHS, lastMarket, marketOf, rememberMarket } from '../lib/markets';
import { REDUCED_MOTION } from '../lib/motion';
import {
  Home, Briefcase, Sun, Moon,
  BarChart3, User, ChevronDown, List, DollarSign,
  Settings2, Gem, Globe, Ellipsis,
  ExternalLink, LogOut,
  ChartCandlestick, Swords, Trophy, Route, History, MessageSquare, BookOpen, Pickaxe,
} from 'lucide-react';

interface Props { children: React.ReactNode }

/** 介绍站是单独部署的静态站，不是本应用的路由，只能走外链 */
const INTRO_URL = 'https://intro.wtfibought.com';

/** 因子挖掘是同域下 wiib-alpha 托管的页面，不是本应用的路由，整页跳过去 */
const ALPHA_URL = '/alpha/';

/** 桌面 logo 展开态是整词，往下滚收拢成 WIIB.：留 W(hat) I(f) I B(ought)，其余字母挂 fd 淡掉 */
const WORDMARK = 'WHATIFIBOUGHT';
const WORDMARK_KEEP = new Set([0, 4, 6, 7]);

/** 往下滚过这么多 px 顶栏就收拢、底边出细线，回到这以内展开；不是距离进度，只是防顶部回弹抖动的阈值 */
const COLLAPSE_AT = 8;

/** 当前项前面那颗橙点连同间距占的宽，算导航放不放得下时要加上 */
const NAV_DOT_W = 12;

/** 当前路由是否落在这组前缀里 */
const matchPaths = (pathname: string, paths: string[]) =>
  paths.some(p => pathname === p || pathname.startsWith(p + '/'));

/** 顶栏导航的一项。to 是站内路由；href 是不归本应用管的整页跳转（blank = 开新标签，文字后面带个小箭头）；menu 有值就是下拉 */
interface NavEntry {
  key: string;
  labelKey: string;
  icon: React.ReactNode;
  to?: string;
  href?: string;
  blank?: boolean;
  menu?: { to: string; icon: React.ReactNode; labelKey: string }[];
  active: (pathname: string) => boolean;
}

const under = (to: string) => (p: string) => matchPaths(p, [to]);
const never = () => false;

/** 顺序就是优先级：一行放不下时从后往前收进「更多」。
    竞技场紧跟持仓（自己的仓位和 AI 的仓位挨着看），配置紧跟竞技场（trader 全靠它的模型端点） */
const NAV: NavEntry[] = [
  { key: 'home', labelKey: 'nav.home', icon: <Home className="ic" />, to: '/', active: p => p === '/' },
  {
    key: 'markets', labelKey: 'nav.markets', icon: <ChartCandlestick className="ic" />, active: p => marketOf(p) !== null,
    menu: [
      { to: '/bstock', icon: <List className="ic" />, labelKey: 'marketMenu.stocks' },
      { to: '/coin', icon: <DollarSign className="ic" />, labelKey: 'marketMenu.crypto' },
      { to: '/commodity', icon: <Gem className="ic" />, labelKey: 'marketMenu.commodity' },
      { to: '/tradfi', icon: <Globe className="ic" />, labelKey: 'marketMenu.tradfi' },
    ],
  },
  { key: 'portfolio', labelKey: 'nav.portfolio', icon: <Briefcase className="ic" />, to: '/portfolio', active: under('/portfolio') },
  { key: 'arena', labelKey: 'nav.arena', icon: <Swords className="ic" />, to: '/arena', active: under('/arena') },
  { key: 'config', labelKey: 'nav.config', icon: <Settings2 className="ic" />, to: '/ai', active: under('/ai') },
  { key: 'ranking', labelKey: 'nav.ranking', icon: <Trophy className="ic" />, to: '/ranking', active: under('/ranking') },
  { key: 'strategies', labelKey: 'nav.strategies', icon: <Route className="ic" />, to: '/strategies', active: under('/strategies') },
  { key: 'backtest', labelKey: 'nav.backtest', icon: <History className="ic" />, to: '/backtest', active: under('/backtest') },
  { key: 'alpha', labelKey: 'nav.alpha', icon: <Pickaxe className="ic" />, href: ALPHA_URL, active: never },
  { key: 'comments', labelKey: 'nav.comments', icon: <MessageSquare className="ic" />, to: '/comments', active: under('/comments') },
  { key: 'intro', labelKey: 'nav.intro', icon: <BookOpen className="ic" />, href: INTRO_URL, blank: true, active: never },
];

/**
 * 手机底部 Tab 的五格；match 判断当前页落在哪一格（市场那格认四个市场，含详情页）。
 * 市场那格的 to 是活的（见 GlassTabBar）：在哪个市场里就回哪个市场的列表，在市场外就回上次看的那个
 */
const TAB_IC = 'w-[22px] h-[22px]';
const TABS: { to: string; icon: React.ReactNode; labelKey: string; match: (p: string) => boolean; market?: true }[] = [
  { to: '/', icon: <Home className={TAB_IC} />, labelKey: 'nav.home', match: p => p === '/' },
  { to: MARKET_PATHS[0], icon: <BarChart3 className={TAB_IC} />, labelKey: 'nav.markets', match: p => marketOf(p) !== null, market: true },
  { to: '/portfolio', icon: <Briefcase className={TAB_IC} />, labelKey: 'nav.portfolio', match: p => matchPaths(p, ['/portfolio']) },
  { to: '/me', icon: <User className={TAB_IC} />, labelKey: 'nav.me', match: p => matchPaths(p, ['/me']) },
  { to: '/ai', icon: <Settings2 className={TAB_IC} />, labelKey: 'nav.config', match: p => matchPaths(p, ['/ai']) },
];

/** 按住时透镜鼓到几倍（同 iOS：玻璃泡鼓出 Tab 栏上下沿） */
const LENS_LIFT = 1.22;
/** 透镜弹簧：刚度、每帧留下的速度（按 60fps 定，帧率不同按时长折算）；这组落定约 0.3s、过冲一成 */
const LENS_K = 0.2, LENS_DAMP = 0.62;
/** 拖出两头只跟手指走这一成，像拉橡皮筋 */
const LENS_BAND = 0.3;
/** 松手后浏览器补的那个 click，这么久以内都算这次手势的 */
const TAB_CLICK_SWALLOW_MS = 500;

/** 一次按压里透镜的账：p/v 透镜左缘位置和速度，target 要去的位置，s 鼓起倍数；left/iw/w 按下时量的 Tab 左缘、一格宽、整排宽 */
interface LensGesture {
  id: number; down: boolean;
  p: number; v: number; target: number; s: number;
  left: number; iw: number; w: number;
  t: number; raf: number;
}

/** 透镜左缘限在 [0, max]，拖出两头按橡皮筋算 */
const clampLens = (x: number, max: number) =>
  x < 0 ? x * LENS_BAND : x > max ? max + (x - max) * LENS_BAND : x;

/** 推一帧弹簧并写进 CSS 变量；返回 true = 已经松手且落定 */
function stepLens(g: LensGesture, nav: HTMLElement, now: number): boolean {
  // 动画帧的时间戳是这一帧开始的时刻，可能比按下事件里取的 performance.now() 还早一点，负的按 0 算
  const f = Math.min(Math.max(now - g.t, 0) / 16.7, 3);
  g.t = now;
  g.v = (g.v + (g.target - g.p) * LENS_K * f) * Math.pow(LENS_DAMP, f);
  g.p += g.v * f;
  g.s += ((g.down && !REDUCED_MOTION ? LENS_LIFT : 1) - g.s) * Math.min(1, .25 * f);
  // 跑得越快横向拉得越长、纵向跟着收，像液体被甩出去
  const st = REDUCED_MOTION ? 0 : Math.min(Math.abs(g.v) * .015, .28);
  const sx = g.s * (1 + st);
  nav.style.setProperty('--lx', `${g.p}px`);
  nav.style.setProperty('--sx', String(sx));
  nav.style.setProperty('--sy', String(g.s * (1 - st * .45)));
  // 底下那排图标在泡的范围里挖空（左右各收进 4px 再渐隐），泡里只剩放大的那份，不叠影
  const c = g.p + g.iw / 2, half = g.iw * sx / 2;
  nav.style.setProperty('--hl', `${c - half + 4}px`);
  nav.style.setProperty('--hr', `${c + half - 4}px`);
  return !g.down && Math.abs(g.target - g.p) < .5 && Math.abs(g.v) < .3 && Math.abs(g.s - 1) < .005;
}

/** 模块级常量存 key 不存文案：存文案的话切语言不会变 */
const LED_LABEL_KEY: Record<HealthLevel, string> = {
  ok: 'led.ok', warn: 'led.warn', down: 'led.down', unknown: 'led.unknown',
};

/** 状态灯变了要稳住这么久才报 */
const LED_SETTLE_MS = 6000;
/** 报的时候胶囊自己拉开这么久再缩回去 */
const LED_SAY_MS = 2400;

/**
 * 三服务状态灯：feed(行情上游) / agent(AI 与策略) / sim(交易主进程)。
 * 平时只有三颗点；鼠标一碰或键盘走到，往左拉开露出名字和状态（样式见 index.css .leds）。
 */
function SystemLeds() {
  const { feed, agent, sim } = useSystemHealth();
  const { t, i18n } = useTranslation('layout');
  const capRef = useRef<HTMLDivElement>(null);
  // 上一次稳住时三盏灯的状态；null = 打开页面后还没稳住过
  const settledRef = useRef<string | null>(null);
  const sayTimerRef = useRef(0);

  // 每项文字拉开后的宽度：量它的自然宽写进 --w（状态文案长短不一，换语言也跟着变）
  useLayoutEffect(() => {
    capRef.current?.querySelectorAll<HTMLElement>('.lamp').forEach(b =>
      b.style.setProperty('--w', `${b.querySelector('.in')!.scrollWidth}px`));
  }, [feed, agent, sim, i18n.language]);

  // 状态稳住 LED_SETTLE_MS 以后，跟上一次稳住时不一样才拉开报一下：
  // 刚打开页面三盏灯陆续亮起来那一轮只记不报，重连那种一闪而过的也不报
  useEffect(() => {
    const now = `${feed},${agent},${sim}`;
    const timer = window.setTimeout(() => {
      const prev = settledRef.current, cap = capRef.current;
      settledRef.current = now;
      if (prev == null || prev === now || !cap) return;
      cap.classList.add('say');
      window.clearTimeout(sayTimerRef.current);
      sayTimerRef.current = window.setTimeout(() => cap.classList.remove('say'), LED_SAY_MS);
    }, LED_SETTLE_MS);
    return () => window.clearTimeout(timer);
  }, [feed, agent, sim]);
  useEffect(() => () => window.clearTimeout(sayTimerRef.current), []);

  // feed / agent / sim 是服务名，不翻
  const lamp = (name: string, lv: HealthLevel) => (
    <button type="button" className="lamp" data-lv={lv} aria-label={`${name} ${t(LED_LABEL_KEY[lv])}`}>
      <i />
      <span className="in"><b>{name}</b><em>{t(LED_LABEL_KEY[lv])}</em></span>
    </button>
  );
  return (
    <div className="leds" role="group">
      <div ref={capRef} className="led-cap">
        {lamp('feed', feed)}
        {lamp('agent', agent)}
        {lamp('sim', sim)}
      </div>
    </div>
  );
}

/** 导航项里面那两层：图标钉在左边（收拢后才显出来），文字在流里撑宽度 */
function NavInner({ icon, label, tail }: { icon: React.ReactNode; label: string; tail?: React.ReactNode }) {
  return (
    <span className="it">
      <span className="ico">{icon}</span>
      <span className="lab">{label}{tail}</span>
    </span>
  );
}

/** 顶栏里带下拉面板的一项：「市场」，和放不下时的「更多」。off = 这一项自己被收走了 / 用不着 */
function NavMenu({ label, icon, on, off, more, children }: {
  label: string;
  icon: React.ReactNode;
  on: boolean;
  off: boolean;
  more?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside(ref, () => setOpen(false), open);

  return (
    <div ref={ref} data-nav={more ? undefined : ''} data-more={more ? '' : undefined}
         className={cn('dd', open && 'open', off && 'off')}
         onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
      <button type="button" data-label={label} aria-haspopup="true" aria-expanded={open}
              className={cn(on && 'on')} onClick={() => setOpen(v => !v)}>
        <NavInner icon={icon} label={label} tail={<ChevronDown className="ic car" />} />
      </button>
      {/* 点了面板里哪一项都收起 */}
      <div className={cn('menu', more && 'rt')} role="menu" onClick={() => setOpen(false)}>
        {children}
      </div>
    </div>
  );
}

export function Layout({ children }: Props) {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, token, logout } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const { t, i18n } = useTranslation('layout');
  const { toggle: toggleLang } = useLangToggle();
  // 顶栏有没有挂更新图标
  const hasUpdate = useHasUpdate();

  const { pathname } = location;

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const hdrRef = useRef<HTMLElement>(null);
  const topRef = useRef<HTMLDivElement>(null);
  const navRef = useRef<HTMLElement>(null);
  // 排在前面的这么多项留在栏上，其余收进「更多」
  const [keep, setKeep] = useState(NAV.length);
  // 路由/语言/登录态变了要重新排一次，但监听不想重挂，把排的函数留一份在这
  const fitRef = useRef<(() => void) | null>(null);

  // 顶栏的收拢、底边细线、导航收纳，一个 effect 管：都是量 DOM、改类名，滚动不走 React 状态。
  // useLayoutEffect：收拢后的宽度靠量出来的 --w，放不放得下也得在首帧画出来之前算好
  useLayoutEffect(() => {
    const hdr = hdrRef.current!, top = topRef.current!, nav = navRef.current!;

    const fit = () => {
      // 手机上这一行是藏着的，量不出东西
      if (!top.offsetParent) return;
      // 收拢动画要知道各处的自然宽：字母写在自己身上，导航项量文字层写在外面那层上
      top.querySelectorAll<HTMLElement>('.logo .fd').forEach(el =>
        el.style.setProperty('--w', `${el.getBoundingClientRect().width}px`));
      const labW = (el: Element) => el.querySelector('.lab')!.getBoundingClientRect().width;
      nav.querySelectorAll<HTMLElement>('.it').forEach(el => el.style.setProperty('--w', `${labW(el)}px`));

      // 每项一律按展开时的宽度算（收拢着量到的是图标宽，不能用）：左右内边距 + 文字宽，当前项再加那颗橙点
      const full = (el: HTMLElement) => {
        const b = el.matches('.dd') ? el.querySelector<HTMLElement>(':scope > button')! : el;
        const cs = getComputedStyle(b);
        return parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight) + labW(b) + (b.classList.contains('on') ? NAV_DOT_W : 0);
      };
      // 能用的宽度 = 这一行的宽 − 整词 logo − 状态灯 − 右边工具 − 三道间距
      const logoW = [...top.querySelector('.logo')!.children].reduce((a, c) => a + c.getBoundingClientRect().width, 0);
      const gap = parseFloat(getComputedStyle(top).columnGap) || 0;
      const avail = top.clientWidth - logoW - top.querySelector<HTMLElement>('.leds')!.offsetWidth
        - top.querySelector<HTMLElement>('.tools')!.offsetWidth - gap * 3;
      const ws = [...nav.querySelectorAll<HTMLElement>(':scope > [data-nav]')].map(full);
      if (ws.reduce((a, w) => a + w, 0) <= avail) { setKeep(ws.length); return; }
      const moreW = full(nav.querySelector<HTMLElement>(':scope > [data-more]')!);
      let used = 0, n = 0;
      while (n < ws.length && used + ws[n] + moreW <= avail) used += ws[n++];
      setKeep(n);
    };
    fitRef.current = fit;

    const onScroll = () => { hdr.classList.toggle('collapsed', window.scrollY > COLLAPSE_AT); };

    fit();
    // 刷新时页面可能已在半路：先按位摆好，下一帧再挂 ready 开过渡，不然会从头播一遍
    onScroll();
    const readyRaf = requestAnimationFrame(() => hdr.classList.add('ready'));

    window.addEventListener('resize', fit);
    window.addEventListener('scroll', onScroll, { passive: true });
    // 字体到位后每处宽度都会变，再排一次
    void document.fonts.ready.then(fit);
    return () => {
      window.removeEventListener('resize', fit);
      window.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(readyRaf);
    };
  }, []);

  // 切路由当前项换了（多一颗橙点）、切语言文字换了、登录态变了或更新图标没了右边工具宽度变了，都要重排
  useLayoutEffect(() => { fitRef.current?.(); }, [pathname, i18n.language, user, token, hasUpdate]);

  const rest = NAV.slice(keep);

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
      {/* ===== 顶栏（sticky，往下滚收拢成 WIIB. + 图标）+ 行情副条 ===== */}
      {/* 安全区：装成 PWA 后页面顶到屏幕边缘，顶栏自己让开刘海和横屏圆角 */}
      <header ref={hdrRef} className="hdr pt-[env(safe-area-inset-top)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <div className="page-shell">
          {/* 桌面：logo + 文字导航 + 状态灯 + 右侧工具 */}
          <div ref={topRef} className="top hidden lg:flex">
            {/* 一个字母一个 span，收拢时要去的字母原地淡出，留下的滑拢成 WIIB. */}
            <Link to="/" className="logo" aria-label={t('header.logoHome')}>
              {[...WORDMARK].map((c, i) => <span key={i} className={WORDMARK_KEEP.has(i) ? undefined : 'fd'}>{c}</span>)}
              <i>.</i>
            </Link>

            <div className="navwrap">
              <nav ref={navRef} className="nav">
                {NAV.map((n, i) => {
                  const label = t(n.labelKey), on = n.active(pathname), off = i >= keep;
                  if (n.menu) return (
                    <NavMenu key={n.key} label={label} icon={n.icon} on={on} off={off}>
                      {n.menu.map(m => <Link key={m.to} to={m.to} role="menuitem">{m.icon}{t(m.labelKey)}</Link>)}
                    </NavMenu>
                  );
                  // data-label 是收拢后悬停时底下那枚小标签；外链尾巴上的小箭头告诉用户这一下会跳出站
                  const inner = <NavInner icon={n.icon} label={label} tail={n.blank && <ExternalLink className="ic" />} />;
                  return n.to
                    ? <Link key={n.key} to={n.to} data-nav="" data-label={label} aria-current={on ? 'page' : undefined}
                            className={cn(on && 'on', off && 'off')}>{inner}</Link>
                    : <a key={n.key} href={n.href} data-nav="" data-label={label} className={cn(off && 'off')}
                         {...(n.blank && { target: '_blank', rel: 'noopener noreferrer' })}>{inner}</a>;
                })}
                {/* 一行放不下时，排在后面的项收进这里；面板里只列文字，当前页前面带橙点 */}
                <NavMenu more label={t('nav.more')} icon={<Ellipsis className="ic" />} on={rest.some(n => n.active(pathname))} off={rest.length === 0}>
                  {rest.flatMap(n => n.menu
                    ? n.menu.map(m => <Link key={m.to} to={m.to} role="menuitem">{t(m.labelKey)}</Link>)
                    : [n.to
                      ? <Link key={n.key} to={n.to} role="menuitem" className={cn(n.active(pathname) && 'on')}>{t(n.labelKey)}</Link>
                      : <a key={n.key} href={n.href} role="menuitem" {...(n.blank && { target: '_blank', rel: 'noopener noreferrer' })}>
                          {t(n.labelKey)}{n.blank && <ExternalLink className="ic" />}
                        </a>])}
                </NavMenu>
              </nav>
            </div>

            <SystemLeds />

            <div className="tools">
              {langButton}
              {themeButton}
              {user && <NotificationBell />}
              {/* 更新图标：有新版本才出现，挨在仓库入口左边 */}
              <ReleaseButton />
              {/* 仓库入口，登没登录都给：登录后夹在铃铛和用户名中间，游客态落在"登录"左边 */}
              <GitHubLink iconClassName="ic" />
              {/* 有 token 但 user 还没拉回来时两边都不显示，否则每次刷新都要闪一下"登录"再变回用户名 */}
              {user ? (
                <>
                  {/* 用户名只作标识不可点，退出就摆在旁边（「我的」页是手机端入口） */}
                  <b>{user.username}</b>
                  <button type="button" onClick={handleLogout} title={t('header.logout')} aria-label={t('header.logout')}>
                    <LogOut className="ic" />
                  </button>
                </>
              ) : !token && (
                <Link to="/login" className="btn sm ink">{t('header.login')}</Link>
              )}
            </div>

            {/* 图形 logo：往下滚、导航收拢后在栏正中浮出来，回到顶上先淡掉。纯装饰，回首页点左边的字标 */}
            <img className="hmark" src="/logo.png" alt="" aria-hidden="true" />
          </div>

          {/* 手机：logo + 语言 + 主题 + 更新 + 仓库，其余入口在底部 Tab 和「我的」页 */}
          <div className="mtop lg:hidden">
            <Link to="/" className="logo" aria-label={t('header.logoHome')}>WIIB<i>.</i></Link>
            <div className="tools">
              {langButton}
              {themeButton}
              <ReleaseButton />
              <GitHubLink iconClassName="ic" />
            </div>
          </div>
        </div>
      </header>
      {/* 副条和断网条不钉住，跟页面一起滚走 */}
      <TickerStrip />
      <OfflineBanner />

      {/* 底部留白：手机给悬浮 Tab 让位，桌面留 72px 再接页脚 */}
      <main className="flex-1 pb-[calc(var(--tabbar-space)+28px)] lg:pb-[72px] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        {children}
      </main>

      {/* 登录后有悬浮球，页脚两头各缩进一点给它让位 */}
      <footer className={cn('page-shell foot hidden lg:flex', user && 'dock')}>
        <span>{t('footer.brand')}<ReleaseVersion /></span>
        <span>{t('footer.disclaimer')}</span>
      </footer>

      {/* ===== 手机端底部 Tab：iOS 液态玻璃悬浮胶囊 ===== */}
      <GlassTabBar />

      {/* 全站悬浮研判对话（BYOK）：对话要登录，游客不给气泡 */}
      {user && <ChatDock />}

      <ReleaseDialog />
    </div>
  );
}

/**
 * 手机底部 Tab：iOS 液态玻璃胶囊（样式见 index.css .glass-tab）。
 * 平时透镜停在当前格；手指按下它鼓成透明玻璃泡跟手走（弹簧），拖得越快拉得越长，泡里透出放大、染成主色的那一格；
 * 松手弹到最近一格并切过去，点的是当前格就回页顶。每帧直接写 CSS 变量，不走 React 渲染。
 * 链接留着给键盘用；指针那次的 click 吞掉（松手时已经导航过）。data-tabbar 给悬浮球量位置
 */
function GlassTabBar() {
  const { t } = useTranslation('layout');
  const location = useLocation();
  const navigate = useNavigate();
  const navRef = useRef<HTMLElement>(null);
  const gRef = useRef<LensGesture | null>(null);
  const upAtRef = useRef(0);
  const [lifted, setLifted] = useState(false);
  // 松手选中的格：路由真切过去之前先按它画，免得透镜落定那一下先跳回旧格；路由一变就作废
  const [pending, setPending] = useState<{ i: number; from: string } | null>(null);
  if (pending && pending.from !== location.pathname) setPending(null);
  const routeIdx = TABS.findIndex(x => x.match(location.pathname));
  const idx = pending ? pending.i : routeIdx;
  // 市场那格去哪：在哪个市场里（含详情页）就回哪个市场的列表，在市场外就回上次看的那个
  const curMarket = marketOf(location.pathname);
  const marketTo = curMarket ?? lastMarket();

  useEffect(() => { if (curMarket) rememberMarket(curMarket); }, [curMarket]);
  useEffect(() => () => cancelAnimationFrame(gRef.current?.raf ?? 0), []);

  const onDown = (e: React.PointerEvent<HTMLElement>) => {
    const nav = navRef.current;
    if (!nav || e.button !== 0 || gRef.current?.down) return;
    const r = nav.getBoundingClientRect();
    const w = r.width - 8, iw = w / TABS.length;
    const prev = gRef.current;
    if (prev) cancelAnimationFrame(prev.raf);
    const target = clampLens(e.clientX - r.left - 4 - iw / 2, w - iw);
    // 从透镜眼下的位置起步：上一下还在回弹就接着走；当前页不在 Tab 里（透镜藏着）就直接从手指下冒出来
    const p = prev ? prev.p : idx >= 0 ? idx * iw : target;
    const g: LensGesture = {
      id: e.pointerId, down: true, p, v: prev?.v ?? 0, target, s: prev?.s ?? 1,
      left: r.left + 4, iw, w, t: performance.now(), raf: 0,
    };
    gRef.current = g;
    nav.setPointerCapture(e.pointerId);
    // 先把位置写好，切到鼓起样式那一帧不跳
    stepLens(g, nav, g.t);
    setLifted(true);
    const tick = (now: number) => {
      if (gRef.current !== g) return;
      if (stepLens(g, nav, now)) { gRef.current = null; setLifted(false); return; }
      g.raf = requestAnimationFrame(tick);
    };
    g.raf = requestAnimationFrame(tick);
  };

  const onMove = (e: React.PointerEvent<HTMLElement>) => {
    const g = gRef.current;
    if (!g?.down || e.pointerId !== g.id) return;
    g.target = clampLens(e.clientX - g.left - g.iw / 2, g.w - g.iw);
  };

  const onUp = (e: React.PointerEvent<HTMLElement>) => {
    const g = gRef.current;
    if (!g?.down || e.pointerId !== g.id) return;
    g.down = false;
    upAtRef.current = performance.now();
    // 被系统手势打断：回原来那格，不切页
    if (e.type === 'pointercancel') {
      if (idx >= 0) g.target = idx * g.iw;
      return;
    }
    const i = Math.min(TABS.length - 1, Math.max(0, Math.round(g.target / g.iw)));
    g.target = i * g.iw;
    const to = TABS[i].market ? marketTo : TABS[i].to;
    if (to === location.pathname) { window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    if (i !== routeIdx) setPending({ i, from: location.pathname });
    navigate(to);
  };

  const onClickCapture = (e: React.MouseEvent) => {
    if (performance.now() - upAtRef.current < TAB_CLICK_SWALLOW_MS) { e.preventDefault(); e.stopPropagation(); }
  };

  return (
    <nav ref={navRef} data-tabbar data-lifted={lifted || undefined} className="glass-tab lg:hidden"
         style={{ '--n': TABS.length } as React.CSSProperties}
         onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}
         onClickCapture={onClickCapture}>
      <span className="glass-lens" style={{ '--i': Math.max(idx, 0), opacity: idx < 0 && !lifted ? 0 : 1 } as React.CSSProperties}>
        {/* 泡里那排：放大、染主色，只在按住时有 */}
        {lifted && (
          <span className="glass-lens-mag" aria-hidden>
            {TABS.map(({ icon, labelKey }) => (
              <span key={labelKey} className="flex-1 flex flex-col items-center justify-center gap-[3px] text-primary">
                {icon}
                <span className="text-[10px] font-semibold leading-none">{t(labelKey)}</span>
              </span>
            ))}
          </span>
        )}
      </span>
      {/* 那排真图标单独包一层：按住时在泡的位置挖空 */}
      <div className="glass-items">
        {TABS.map((tab, i) => (
          <Link key={tab.labelKey} to={tab.market ? marketTo : tab.to} draggable={false} aria-current={i === routeIdx ? 'page' : undefined}
                className={cn('relative flex-1 flex flex-col items-center justify-center gap-[3px]',
                  i === idx ? 'text-primary' : 'text-foreground/70')}>
            {tab.icon}
            <span className="text-[10px] font-semibold leading-none">{t(tab.labelKey)}</span>
          </Link>
        ))}
      </div>
    </nav>
  );
}
