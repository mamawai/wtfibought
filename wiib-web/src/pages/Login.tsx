import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { Typewriter } from '../components/ui/typewriter';
import { Loader2, Globe, BarChart3, Wallet, LineChart, LogIn } from 'lucide-react';

const LINUXDO_CONFIG = {
  clientId: 'toCFytIO9bCHpbUbFKM1mTgvy1ax8tG2',
  authorizeUrl: 'https://connect.linux.do/oauth2/authorize',
  redirectUri: 'https://linuxdo.stockgame.icu/login',
};
// const LINUXDO_CONFIG = {
//   clientId: 'NIrMpQ09Jgzjb7r1ZgU3QYnuejk8Z3qS',
//   authorizeUrl: 'https://connect.linux.do/oauth2/authorize',
//   redirectUri: 'http://localhost:3000/login',
// };

// 定制化新粗野主义商标组件：时光机+涨幅趋势
function ProjectLogo({ className, ...props }: React.SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg" className={className} {...props}>
      {/* 底部偏移阴影（时钟的背影） */}
      <circle cx="50" cy="58" r="32" fill="currentColor" opacity="0.15" />
      {/* 时钟表盘 */}
      <circle cx="46" cy="54" r="32" fill="#FFFFFF" stroke="currentColor" strokeWidth="7" />
      
      {/* 时钟刻度 */}
      <line x1="46" y1="30" x2="46" y2="36" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      <line x1="70" y1="54" x2="64" y2="54" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      <line x1="46" y1="78" x2="46" y2="72" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      <line x1="22" y1="54" x2="28" y2="54" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      
      {/* 时钟中心点 */}
      <circle cx="46" cy="54" r="6" fill="currentColor" />
      
      {/* 突破时间限制的增长箭头 */}
      <polyline points="32,68 46,54 74,26" fill="none" stroke="currentColor" strokeWidth="7" strokeLinecap="square" strokeLinejoin="miter" />
      <polygon points="61,23 81,19 77,39" fill="currentColor" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
    </svg>
  );
}

export function Login() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user, setToken, fetchUser } = useUserStore();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  // null=模式加载中；两个开关决定展示哪些登录入口
  const [mode, setMode] = useState<{ linuxDoEnabled: boolean; passwordLoginEnabled: boolean } | null>(null);
  const callbackHandled = useRef(false);
  // 账号密码表单
  const [isRegister, setIsRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [inviteCode, setInviteCode] = useState('');

  const handleOAuthCallback = useCallback(async (code: string, state: string) => {
    const savedState = localStorage.getItem('oauth_state');
    if (state !== savedState) {
      setError('安全验证失败，请重试');
      return;
    }
    localStorage.removeItem('oauth_state');

    setLoading(true);
    setError('');

    try {
      const token = await authApi.linuxDoCallback(code);
      if (token) {
        setToken(token);
        await fetchUser();
        navigate('/');
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'LinuxDo 登录失败';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [fetchUser, navigate, setToken]);

  useEffect(() => {
    if (user) {
      navigate('/');
    }
  }, [user, navigate]);

  // 拉登录模式：两个开关都关才展示管理员直登；失败兜底回 OAuth（既有行为）
  useEffect(() => {
    authApi.mode()
      .then(m => setMode({ linuxDoEnabled: m.linuxDoEnabled, passwordLoginEnabled: m.passwordLoginEnabled ?? false }))
      .catch(() => setMode({ linuxDoEnabled: true, passwordLoginEnabled: false }));
  }, []);

  useEffect(() => {
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    if (code && state && !callbackHandled.current) {
      callbackHandled.current = true;
      handleOAuthCallback(code, state);
    }
  }, [searchParams, handleOAuthCallback]);

  const handleLinuxDoLogin = () => {
    const state = Math.random().toString(36).substring(2, 10);
    localStorage.setItem('oauth_state', state);
    window.location.href = `${LINUXDO_CONFIG.authorizeUrl}?client_id=${LINUXDO_CONFIG.clientId}&redirect_uri=${encodeURIComponent(LINUXDO_CONFIG.redirectUri)}&response_type=code&state=${state}`;
  };

  // 管理员直登：无 OAuth 跳转，直接调后端拿 token 进站
  const handleLocalLogin = async () => {
    setLoading(true);
    setError('');
    try {
      const token = await authApi.localLogin();
      if (token) {
        setToken(token);
        await fetchUser();
        navigate('/');
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '登录失败';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // 账号密码登录 / 邀请码注册（注册成功即登录）
  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      const token = isRegister
        ? await authApi.register(username.trim(), password, inviteCode.trim())
        : await authApi.passwordLogin(username.trim(), password);
      if (token) {
        setToken(token);
        await fetchUser();
        navigate('/');
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : (isRegister ? '注册失败' : '登录失败');
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4 relative overflow-hidden bg-background">
      {/* 恢复原版的渐变垂直条纹背景 */}
      <div className="absolute inset-0 z-0 pointer-events-none">
        <div className="absolute inset-0 flex">
          {Array.from({ length: 26 }).map((_, i) => (
            <div
              key={i}
              className="flex-1"
              style={{
                background: `linear-gradient(to bottom,
                  rgba(249, 115, 22, 0) 0%,
                  rgba(253, 186, 116, ${Math.sin((i / 26) * Math.PI) * 0.35 + 0.25}) 50%,
                  rgba(249, 115, 22, 0) 100%)`,
              }}
            />
          ))}
        </div>
      </div>

      {/* 主卡片 */}
      <div className="w-full max-w-md relative z-10">
        {/* 卡片后置阴影块（纯物理错位感） */}


        <div className="relative rounded-4xl bg-card neu-raised-lg p-8 md:p-10 flex flex-col items-center">
          
          {/* Logo - 倾斜重叠设计 + 专属商标 */}
          <div className="mb-8 relative group cursor-pointer">
            <div className="absolute inset-0 bg-warning rounded-2xl rotate-6 transition-transform duration-300 group-hover:rotate-12 neu-flat" />
            <div className="relative w-24 h-24 rounded-2xl bg-primary flex items-center justify-center neu-raised-sm -rotate-6 transition-transform duration-300 group-hover:rotate-0">
              <ProjectLogo className="w-16 h-16 text-foreground" />
            </div>
          </div>

          {/* 标题 */}
          <div className="text-center mb-10 w-full flex flex-col items-center">
            <h1 className="text-3xl sm:text-4xl md:text-[2.5rem] font-black text-foreground mb-6 tracking-tighter uppercase flex flex-wrap items-center justify-center gap-x-3 gap-y-4">
              <span className="relative inline-block z-10" style={{ textShadow: '4px 4px 0 var(--color-warning)' }}>
                What If
              </span>
              <span className="px-4 py-1 bg-primary text-primary-foreground neu-raised-sm -rotate-2 inline-block relative z-20 hover:rotate-0 hover:translate-y-1 transition-all">
                I Bought
              </span>
            </h1>
            <div className="inline-flex items-center justify-center px-4 py-2.5 rounded-xl bg-surface neu-flat">
              <span className="text-sm font-bold text-foreground">
                <Typewriter text="模拟股票交易，看看如果当初买了会怎样" speed={80} />
              </span>
            </div>
          </div>

          {/* 功能点矩阵 */}
          <div className="grid grid-cols-3 gap-3 w-full mb-10">
            <div className="text-center p-4 rounded-xl bg-[#FEF08A] neu-btn-sm transition-all">
              <BarChart3 className="w-7 h-7 mx-auto mb-2 text-foreground" strokeWidth={2.5} />
              <span className="text-xs font-black text-foreground">真实行情</span>
            </div>
            <div className="text-center p-4 rounded-xl bg-[#86EFAC] neu-btn-sm transition-all">
              <Wallet className="w-7 h-7 mx-auto mb-2 text-foreground" strokeWidth={2.5} />
              <span className="text-xs font-black text-foreground">无损模拟</span>
            </div>
            <div className="text-center p-4 rounded-xl bg-[#93C5FD] neu-btn-sm transition-all">
              <LineChart className="w-7 h-7 mx-auto mb-2 text-foreground" strokeWidth={2.5} />
              <span className="text-xs font-black text-foreground">收益复盘</span>
            </div>
          </div>

          {/* 错误提示 */}
          {error && (
            <div className="w-full p-4 rounded-xl bg-destructive text-white text-sm font-black text-center mb-8 neu-raised animate-in slide-in-from-top-2">
              {error}
            </div>
          )}

          {/* 登录区：加载中→spinner；密码模式→表单（可与 LinuxDo 并列）；都没开→管理员直登 */}
          <div className="w-full">
            {loading || mode === null ? (
              <div className="flex flex-col items-center justify-center h-16 rounded-2xl bg-surface neu-raised gap-2">
                <Loader2 className="w-6 h-6 text-foreground animate-spin" strokeWidth={3} />
                <p className="text-sm font-black text-foreground">{loading ? '登录中...' : '加载中...'}</p>
              </div>
            ) : (
              <>
                {mode.passwordLoginEnabled && (
                  <form onSubmit={handlePasswordSubmit} className="w-full space-y-4">
                    {/* 登录/注册切换 */}
                    <div className="grid grid-cols-2 gap-1.5 p-1.5 rounded-2xl bg-surface neu-inset">
                      <button
                        type="button"
                        onClick={() => { setIsRegister(false); setError(''); }}
                        className={`h-10 rounded-xl font-black text-sm transition-all ${!isRegister ? 'bg-primary text-primary-foreground neu-raised-sm' : 'text-muted-foreground'}`}
                      >
                        登录
                      </button>
                      <button
                        type="button"
                        onClick={() => { setIsRegister(true); setError(''); }}
                        className={`h-10 rounded-xl font-black text-sm transition-all ${isRegister ? 'bg-primary text-primary-foreground neu-raised-sm' : 'text-muted-foreground'}`}
                      >
                        注册
                      </button>
                    </div>
                    <input
                      value={username}
                      onChange={e => setUsername(e.target.value)}
                      placeholder="用户名"
                      autoComplete="username"
                      required
                      className="w-full h-12 px-4 rounded-xl bg-surface neu-inset font-bold text-foreground placeholder:text-muted-foreground outline-none"
                    />
                    <input
                      type="password"
                      value={password}
                      onChange={e => setPassword(e.target.value)}
                      placeholder={isRegister ? '密码（至少6位）' : '密码'}
                      autoComplete={isRegister ? 'new-password' : 'current-password'}
                      required
                      className="w-full h-12 px-4 rounded-xl bg-surface neu-inset font-bold text-foreground placeholder:text-muted-foreground outline-none"
                    />
                    {isRegister && (
                      <input
                        value={inviteCode}
                        onChange={e => setInviteCode(e.target.value)}
                        placeholder="邀请码"
                        required
                        className="w-full h-12 px-4 rounded-xl bg-surface neu-inset font-bold text-foreground placeholder:text-muted-foreground outline-none"
                      />
                    )}
                    <button
                      type="submit"
                      className="group relative w-full h-14 rounded-2xl bg-primary text-primary-foreground font-black text-lg neu-btn-sm transition-all flex items-center justify-center gap-3 overflow-hidden"
                    >
                      {/* 按钮内扫光效果 */}
                      <div className="absolute inset-0 w-full h-full bg-white/20 -translate-x-full group-hover:translate-x-full transition-transform duration-700" />
                      <LogIn className="w-5 h-5 relative z-10" strokeWidth={2.5} />
                      <span className="relative z-10 tracking-wide">{isRegister ? '注册并进入' : '登录'}</span>
                    </button>
                  </form>
                )}
                {mode.passwordLoginEnabled && mode.linuxDoEnabled && (
                  <div className="flex items-center gap-3 my-5">
                    <div className="flex-1 h-0.5 bg-muted-foreground/20 rounded-full" />
                    <span className="text-xs font-black text-muted-foreground">或</span>
                    <div className="flex-1 h-0.5 bg-muted-foreground/20 rounded-full" />
                  </div>
                )}
                {mode.linuxDoEnabled && (
                  <button
                    onClick={handleLinuxDoLogin}
                    className="group relative w-full h-16 rounded-2xl bg-primary text-primary-foreground font-black text-lg neu-btn-sm transition-all flex items-center justify-center gap-3 overflow-hidden"
                  >
                    {/* 按钮内扫光效果 */}
                    <div className="absolute inset-0 w-full h-full bg-white/20 -translate-x-full group-hover:translate-x-full transition-transform duration-700" />
                    <Globe className="w-6 h-6 relative z-10" strokeWidth={2.5} />
                    <span className="relative z-10 tracking-wide">使用 LinuxDo 登录</span>
                  </button>
                )}
                {!mode.linuxDoEnabled && !mode.passwordLoginEnabled && (
                  <button
                    onClick={handleLocalLogin}
                    className="group relative w-full h-16 rounded-2xl bg-primary text-primary-foreground font-black text-lg neu-btn-sm transition-all flex items-center justify-center gap-3 overflow-hidden"
                  >
                    {/* 按钮内扫光效果 */}
                    <div className="absolute inset-0 w-full h-full bg-white/20 -translate-x-full group-hover:translate-x-full transition-transform duration-700" />
                    <LogIn className="w-6 h-6 relative z-10" strokeWidth={2.5} />
                    <span className="relative z-10 tracking-wide">进入</span>
                  </button>
                )}
              </>
            )}
          </div>

          <p className="text-[11px] font-black text-center text-muted-foreground mt-8 uppercase tracking-[0.2em]">
            Welcome To The Playground
          </p>
        </div>
      </div>
    </div>
  );
}