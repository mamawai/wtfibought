import { NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useState, useRef, useEffect } from 'react';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Button } from './ui/button';
import { cn } from '../lib/utils';
import {
  Home, Briefcase, LogOut, LogIn, TrendingUp, Sun, Moon,
  BarChart3, User, ChevronDown, List, DollarSign, LineChart,
} from 'lucide-react';

interface Props { children: React.ReactNode }

const MARKET_PATHS = ['/stocks', '/coin', '/options'];

export function Layout({ children }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useUserStore();
  const { ref: themeRef, toggleTheme, isDark } = useTheme();
  const [scrolled, setScrolled] = useState(false);
  const [headerHover, setHeaderHover] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 60);
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const expanded = !scrolled || headerHover;

  const handleLogout = async () => { await logout(); navigate('/login'); };
  const isMarketActive = MARKET_PATHS.some(p => location.pathname === p || location.pathname.startsWith(p + '/'));

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* ===== Header — thick border floating pill ===== */}
      <header className="sticky top-0 z-50 w-full pt-3 px-3 md:px-5">
        <div className="max-w-5xl mx-auto">
          <div
            className={cn(
              "flex h-14 items-center justify-center md:justify-between rounded-full border-[3px] border-edge bg-card px-5 shadow-[4px_4px_0_0_var(--color-edge)]",
              "mx-auto transition-[max-width] max-w-[17rem] overflow-hidden",
              expanded && "md:max-w-full md:overflow-visible"
            )}
            style={{ transitionDuration: '600ms', transitionTimingFunction: 'cubic-bezier(0.34, 1.56, 0.64, 1)' }}
            onMouseEnter={() => setHeaderHover(true)}
            onMouseLeave={() => setHeaderHover(false)}
          >
            {/* Logo */}
            <button
              type="button"
              className="flex items-center gap-2.5 cursor-pointer group focus-visible:outline-none shrink-0"
              onClick={() => navigate('/')}
              aria-label="返回首页"
            >
              <div className="w-8 h-8 rounded-lg bg-primary/15 border-2 border-edge flex items-center justify-center">
                <TrendingUp className="w-4.5 h-4.5 text-primary" />
              </div>
              <span className="text-base font-extrabold tracking-tight">WhatIfIBought</span>
            </button>

            {/* Desktop Nav */}
            <nav className={cn(
              "hidden md:flex items-center gap-1 overflow-hidden whitespace-nowrap transition-all",
              expanded ? "opacity-100 max-w-[40rem]" : "opacity-0 max-w-0"
            )} style={{ transitionDuration: '400ms', transitionTimingFunction: 'cubic-bezier(0.34, 1.56, 0.64, 1)' }}>
              <HeaderNavItem to="/" label="首页" />
              <MarketDropdown isActive={isMarketActive} />
              <HeaderNavItem to="/portfolio" label="持仓" />
              <HeaderNavItem to="/ranking" label="排行" />
              <HeaderNavItem to="/games" label="游戏" />
            </nav>

            {/* Actions */}
            <div className={cn(
              "hidden md:flex items-center gap-2",
              "overflow-hidden whitespace-nowrap transition-all",
              expanded ? "opacity-100 max-w-[20rem]" : "opacity-0 max-w-0"
            )} style={{ transitionDuration: '400ms', transitionTimingFunction: 'cubic-bezier(0.34, 1.56, 0.64, 1)' }}>
              <a
                href="https://github.com/mamawai/wiib"
                target="_blank"
                rel="noopener noreferrer"
                className="hidden sm:inline-flex items-center justify-center w-8 h-8 rounded-lg text-muted-foreground hover:text-foreground transition-colors"
                aria-label="GitHub"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>
              </a>

              <Button
                ref={themeRef}
                variant="ghost"
                size="icon"
                onClick={toggleTheme}
                className="hidden md:inline-flex w-8 h-8"
                aria-label={isDark ? '切换到亮色模式' : '切换到暗色模式'}
              >
                {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
              </Button>

              {user ? (
                <div className="hidden md:flex items-center gap-2">
                  <span className="text-xs font-bold text-muted-foreground hidden lg:inline">{user.username}</span>
                  <Button variant="ghost" size="icon" className="w-8 h-8" onClick={handleLogout}>
                    <LogOut className="w-4 h-4" />
                  </Button>
                </div>
              ) : (
                <Button size="sm" className="hidden md:inline-flex" onClick={() => navigate('/login')}>
                  <LogIn className="w-3.5 h-3.5" />
                  登录
                </Button>
              )}
            </div>
          </div>
        </div>
      </header>

      {/* Main */}
      <main className="flex-1 pb-24 md:pb-6 pt-4">
        {children}
      </main>

      {/* ===== Bottom Nav — Mobile floating pill ===== */}
      <nav className="fixed bottom-3 left-3 right-3 md:hidden z-50">
        <div className="flex items-center rounded-full border-[3px] border-edge bg-card shadow-[4px_4px_0_0_var(--color-edge)] p-1.5 gap-1">
          <BottomNavItem to="/" icon={<Home className="w-5 h-5" />} label="首页" />
          <BottomNavItem to="/stocks" icon={<BarChart3 className="w-5 h-5" />} label="市场" forceActive={isMarketActive} />
          <BottomNavItem to="/portfolio" icon={<Briefcase className="w-5 h-5" />} label="持仓" />
          <BottomNavItem to="/me" icon={<User className="w-5 h-5" />} label="我的" />
        </div>
      </nav>
    </div>
  );
}

function HeaderNavItem({ to, label }: { to: string; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "px-3.5 py-1.5 rounded-full text-sm font-bold transition-colors",
          isActive
            ? "text-primary bg-primary/10"
            : "text-muted-foreground hover:text-foreground"
        )
      }
    >
      {label}
    </NavLink>
  );
}

function MarketDropdown({ isActive }: { isActive: boolean }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(v => !v)}
        className={cn(
          "flex items-center gap-1 px-3.5 py-1.5 rounded-full text-sm font-bold transition-colors cursor-pointer",
          isActive ? "text-primary bg-primary/10" : "text-muted-foreground hover:text-foreground"
        )}
      >
        市场
        <ChevronDown className={cn("w-3.5 h-3.5 transition-transform", open && "rotate-180")} />
      </button>

      {open && (
        <div className="absolute top-full left-0 mt-2 w-44 rounded-2xl border-[3px] border-edge bg-card shadow-[4px_4px_0_0_var(--color-edge)] py-1.5 animate-in fade-in zoom-in-95">
          {[
            { to: '/stocks', icon: <List className="w-4 h-4" />, label: '股票' },
            { to: '/coin', icon: <DollarSign className="w-4 h-4" />, label: '币种' },
            { to: '/options', icon: <LineChart className="w-4 h-4" />, label: '期权' },
          ].map(({ to, icon, label }) => (
            <NavLink
              key={to}
              to={to}
              onClick={() => setOpen(false)}
              className={({ isActive: a }) =>
                cn(
                  "flex items-center gap-2.5 px-4 py-2.5 text-sm font-bold transition-colors",
                  a ? "text-primary bg-primary/5" : "text-muted-foreground hover:text-foreground hover:bg-surface-hover"
                )
              }
            >
              {icon}
              {label}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}

function BottomNavItem({ to, icon, label, forceActive }: { to: string; icon: React.ReactNode; label: string; forceActive?: boolean }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex-1 flex flex-col items-center gap-0.5 py-2 rounded-full transition-all",
          (forceActive || isActive)
            ? "text-primary bg-primary/10"
            : "text-muted-foreground"
        )
      }
    >
      {icon}
      <span className="text-[10px] font-bold">{label}</span>
    </NavLink>
  );
}
