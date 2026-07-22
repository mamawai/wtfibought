import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import NumberFlow from '@number-flow/react';
import { authApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { DecryptedText } from '../components/fx/DecryptedText';
import { Input } from '../components/ui/input';
import { Button } from '../components/ui/button';
import { Loader2, Globe, BarChart3, Wallet, LineChart, LogIn } from 'lucide-react';

const LINUXDO_CONFIG = {
  clientId: 'toCFytIO9bCHpbUbFKM1mTgvy1ax8tG2',
  authorizeUrl: 'https://connect.linux.do/oauth2/authorize',
  redirectUri: 'https://wtfibought.com/login',
};
// const LINUXDO_CONFIG = {
//   clientId: 'NIrMpQ09Jgzjb7r1ZgU3QYnuejk8Z3qS',
//   authorizeUrl: 'https://connect.linux.do/oauth2/authorize',
//   redirectUri: 'http://localhost:3000/login',
// };

/** 登录前的实时报价角标：匿名 STOMP 流（后端不拒游客），进门先看见"活"的行情 */
function LiveQuote({ symbol, name }: { symbol: string; name: string }) {
  const tick = useCryptoStream(symbol, 'spot');
  return (
    <div className="flex items-center gap-2 px-3.5 py-2 rounded-md border border-border bg-card/70 backdrop-blur-sm">
      <span className="led" />
      <span className="text-xs font-bold">{name}</span>
      {tick?.price != null
        ? <NumberFlow
            value={tick.price}
            format={{ maximumFractionDigits: 2, minimumFractionDigits: 2 }}
            className="num text-xs text-muted-foreground"
          />
        : <span className="num text-xs text-muted-foreground">····</span>}
    </div>
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

  const segBtn = (active: boolean) =>
    `flex-1 h-10 text-sm font-bold transition-colors cursor-pointer ${
      active
        ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
        : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'
    }`;

  return (
    <div className="min-h-screen relative overflow-hidden bg-background lg:grid lg:grid-cols-[1.1fr_1fr]">
      {/* 背景：图纸网格（顶部渐隐）+ 橙色辉光 */}
      <div aria-hidden className="absolute inset-0 pointer-events-none">
        <div
          className="absolute inset-0 opacity-50"
          style={{
            backgroundImage:
              'linear-gradient(var(--color-border) 1px, transparent 1px), linear-gradient(90deg, var(--color-border) 1px, transparent 1px)',
            backgroundSize: '44px 44px',
            maskImage: 'radial-gradient(ellipse 90% 70% at 50% 0%, black 30%, transparent 75%)',
            WebkitMaskImage: 'radial-gradient(ellipse 90% 70% at 50% 0%, black 30%, transparent 75%)',
          }}
        />
        <div
          className="absolute -top-44 left-1/2 -translate-x-1/2 w-[52rem] h-[26rem] rounded-full"
          style={{ background: 'radial-gradient(closest-side, color-mix(in srgb, var(--color-primary) 15%, transparent), transparent)' }}
        />
      </div>

      {/* 左：品牌面板（桌面） */}
      <div className="hidden lg:flex relative flex-col justify-between p-14 border-r border-border/60">
        <div className="flex items-baseline gap-3">
          <span className="text-lg font-extrabold tracking-wide">WIIB<span className="text-primary">.</span></span>
          <span className="microlabel font-semibold">SIMULATED TRADING TERMINAL</span>
        </div>

        <div className="space-y-8">
          <h1 className="text-5xl xl:text-6xl font-extrabold tracking-tight leading-[1.08] uppercase">
            <DecryptedText text="What If" speed={45} />
            <br />
            <span className="text-primary"><DecryptedText text="I Bought" speed={45} /></span>
          </h1>
          <p className="text-sm text-muted-foreground max-w-sm leading-relaxed">
            虚拟资金 · 真实行情。股票、加密货币、永续合约与 AI 量化研判，
            零风险体验"如果当初买了会怎样"。
          </p>
          {/* 实时行情角标：未登录也在跳动 */}
          <div className="flex flex-wrap gap-3">
            <LiveQuote symbol="BTCUSDT" name="BTC" />
            <LiveQuote symbol="ETHUSDT" name="ETH" />
          </div>
        </div>

        <div className="flex items-center gap-6 text-[11px] font-semibold text-muted-foreground">
          <span className="flex items-center gap-1.5"><BarChart3 className="w-3.5 h-3.5" />Binance 实时行情</span>
          <span className="flex items-center gap-1.5"><Wallet className="w-3.5 h-3.5" />虚拟资金 零风险</span>
          <span className="flex items-center gap-1.5"><LineChart className="w-3.5 h-3.5" />AI 波动研判</span>
        </div>
      </div>

      {/* 右：登录卡 */}
      <div className="relative flex items-center justify-center p-4 py-14 min-h-screen lg:min-h-0">
        <div className="w-full max-w-sm">
          {/* 移动端顶部品牌 */}
          <div className="lg:hidden text-center mb-8">
            <div className="text-2xl font-extrabold tracking-wide">WIIB<span className="text-primary">.</span></div>
            <div className="microlabel font-semibold mt-1.5">SIMULATED TRADING TERMINAL</div>
          </div>

          <div className="pt-card rounded-lg p-7 space-y-5">
            <div>
              <div className="flex items-center gap-2">
                <span className="led" />
                <span className="microlabel font-semibold">TERMINAL ACCESS</span>
              </div>
              <h2 className="text-xl font-extrabold tracking-tight mt-2">
                {mode?.passwordLoginEnabled && isRegister ? '创建账户' : '登录终端'}
              </h2>
            </div>

            {error && (
              <div className="p-3 rounded-md border border-destructive/40 bg-destructive/10 text-destructive text-xs font-semibold animate-in slide-in-from-top-2 fade-in">
                {error}
              </div>
            )}

            {loading || mode === null ? (
              <div className="h-28 flex flex-col items-center justify-center gap-2 text-muted-foreground">
                <Loader2 className="w-5 h-5 animate-spin text-primary" />
                <span className="text-xs font-semibold">{loading ? '登录中...' : '加载中...'}</span>
              </div>
            ) : (
              <>
                {mode.passwordLoginEnabled && (
                  <form onSubmit={handlePasswordSubmit} className="space-y-3.5">
                    {/* 登录/注册段控件 */}
                    <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
                      <button type="button" onClick={() => { setIsRegister(false); setError(''); }} className={segBtn(!isRegister)}>
                        登录
                      </button>
                      <button type="button" onClick={() => { setIsRegister(true); setError(''); }} className={segBtn(isRegister)}>
                        注册
                      </button>
                    </div>
                    <Input
                      value={username}
                      onChange={e => setUsername(e.target.value)}
                      placeholder="用户名"
                      autoComplete="username"
                      required
                    />
                    <Input
                      type="password"
                      value={password}
                      onChange={e => setPassword(e.target.value)}
                      placeholder={isRegister ? '密码（至少6位）' : '密码'}
                      autoComplete={isRegister ? 'new-password' : 'current-password'}
                      required
                    />
                    {isRegister && (
                      <Input
                        value={inviteCode}
                        onChange={e => setInviteCode(e.target.value)}
                        placeholder="邀请码"
                        required
                      />
                    )}
                    <Button type="submit" className="w-full h-11">
                      <LogIn className="w-4 h-4" />
                      {isRegister ? '注册并进入' : '登录'}
                    </Button>
                  </form>
                )}

                {mode.passwordLoginEnabled && mode.linuxDoEnabled && (
                  <div className="flex items-center gap-3">
                    <div className="flex-1 h-px bg-border" />
                    <span className="text-[10px] font-semibold text-muted-foreground tracking-widest">或</span>
                    <div className="flex-1 h-px bg-border" />
                  </div>
                )}

                {mode.linuxDoEnabled && (
                  <Button variant="outline" className="w-full h-11" onClick={handleLinuxDoLogin}>
                    <Globe className="w-4 h-4" />
                    使用 LinuxDo 登录
                  </Button>
                )}

                {!mode.linuxDoEnabled && !mode.passwordLoginEnabled && (
                  <Button className="w-full h-11" onClick={handleLocalLogin}>
                    <LogIn className="w-4 h-4" />
                    进入终端
                  </Button>
                )}
              </>
            )}
          </div>

          <p className="text-[10px] font-semibold text-center text-muted-foreground mt-6 tracking-[0.25em] uppercase">
            Paper Trading · No Real Funds
          </p>
        </div>
      </div>
    </div>
  );
}
