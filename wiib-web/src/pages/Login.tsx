import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { Typewriter } from '../components/ui/typewriter';
import { TrendingUp, Loader2, Globe, BarChart3, Wallet, LineChart } from 'lucide-react';

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

export function Login() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user, setToken, fetchUser } = useUserStore();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const callbackHandled = useRef(false);

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

  return (
    <div className="min-h-screen flex items-center justify-center p-4 relative overflow-hidden">
      {/* Vertical stripe gradient background */}
      <div className="absolute inset-0 bg-background">
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

      {/* Main Card */}
      <div className="w-full max-w-md relative z-10">
        <div className="rounded-3xl border-[3px] border-edge bg-card shadow-[8px_8px_0_0_var(--color-edge)] p-8 md:p-10">
          {/* Logo */}
          <div className="flex justify-center mb-8">
            <div className="w-20 h-20 rounded-2xl bg-primary/15 border-[3px] border-edge flex items-center justify-center shadow-[4px_4px_0_0_var(--color-edge)]">
              <TrendingUp className="w-10 h-10 text-primary" />
            </div>
          </div>

          {/* Title with Typewriter */}
          <div className="text-center mb-10">
            <h1 className="text-3xl md:text-4xl font-extrabold text-foreground mb-4 tracking-tight">
              What If I Bought
            </h1>
            <div className="text-muted-foreground text-sm md:text-base h-12 flex items-center justify-center">
              <Typewriter text="模拟股票交易，看看如果当初买了会怎样" speed={100} />
            </div>
          </div>

          {/* Features */}
          <div className="grid grid-cols-3 gap-3 mb-8">
            <div className="text-center p-4 rounded-xl border-[3px] border-edge bg-surface shadow-[2px_2px_0_0_var(--color-edge)]">
              <BarChart3 className="w-6 h-6 mx-auto mb-2 text-primary" />
              <span className="text-xs font-bold text-foreground">实时行情</span>
            </div>
            <div className="text-center p-4 rounded-xl border-[3px] border-edge bg-surface shadow-[2px_2px_0_0_var(--color-edge)]">
              <Wallet className="w-6 h-6 mx-auto mb-2 text-success" />
              <span className="text-xs font-bold text-foreground">模拟交易</span>
            </div>
            <div className="text-center p-4 rounded-xl border-[3px] border-edge bg-surface shadow-[2px_2px_0_0_var(--color-edge)]">
              <LineChart className="w-6 h-6 mx-auto mb-2 text-primary" />
              <span className="text-xs font-bold text-foreground">收益追踪</span>
            </div>
          </div>

          {/* Error */}
          {error && (
            <div className="p-4 rounded-xl border-[3px] border-destructive bg-destructive/10 text-destructive text-sm font-bold text-center mb-6 shadow-[2px_2px_0_0_var(--color-destructive)]">
              {error}
            </div>
          )}

          {/* Login Button */}
          {loading ? (
            <div className="flex flex-col items-center gap-4 py-8">
              <Loader2 className="w-12 h-12 text-primary animate-spin" />
              <p className="text-sm font-bold text-muted-foreground">正在登录...</p>
            </div>
          ) : (
            <button
              onClick={handleLinuxDoLogin}
              className="w-full h-14 rounded-xl border-[3px] border-edge bg-primary text-primary-foreground font-bold text-base shadow-[4px_4px_0_0_var(--color-edge)] hover:shadow-[2px_2px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px] active:shadow-[0px_0px_0_0_var(--color-edge)] active:translate-x-[4px] active:translate-y-[4px] transition-all flex items-center justify-center gap-2"
            >
              <Globe className="w-5 h-5" />
              使用 LinuxDo 登录
            </button>
          )}

          <p className="text-xs text-center text-muted-foreground mt-6">
            登录即表示同意我们的服务条款
          </p>
        </div>
      </div>
    </div>
  );
}
