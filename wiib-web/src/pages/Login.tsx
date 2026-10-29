import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import NumberFlow from '@number-flow/react';
import { authApi, type AuthMode } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { DecryptedText } from '../components/fx/DecryptedText';
import { DitherSmoke } from '../components/fx/DitherSmoke';
import { LanguageSwitcher } from '../components/LanguageSwitcher';
import { GitHubLink } from '../components/GitHubLink';
import { cn } from '../lib/utils';
import { Loader2, BarChart3, Wallet, Bot, MessagesSquare, LogIn, Eye, EyeOff, CircleAlert, RotateCw } from 'lucide-react';

type Provider = 'google' | 'linuxdo';

/** 两个 OAuth 按钮的上下顺序 */
const PROVIDERS: Provider[] = ['google', 'linuxdo'];
const PROVIDER_NAME: Record<Provider, string> = { google: 'Google', linuxdo: 'LinuxDo' };
/** 点 OAuth 按钮后的刷色：Google 四色竖条，LinuxDo 照 logo 的黑白黄三条横带 */
const PAINT: Record<Provider, string[]> = {
  google: ['#4285F4', '#EA4335', '#FBBC05', '#34A853'],
  linuxdo: ['#1c1c1e', '#f0f0f0', '#ffb003'],
};
/** 刷色放完再跳授权页 */
const PAINT_MS = 520;
/** OAuth state 的 localStorage 键，值是"来源:随机串" */
const OAUTH_STATE_KEY = 'oauth_state';
const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const INPUT = 'flex-1 min-w-0 bg-transparent border-0 outline-none [font:inherit] disabled:opacity-50';

const authorizeUrl = (mode: AuthMode, p: Provider) => (p === 'google' ? mode.googleAuthorizeUrl : mode.linuxDoAuthorizeUrl);

/** 跳授权页：授权地址由后端给，这里只拼 state */
function startOAuth(provider: Provider, url: string) {
  const state = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
  localStorage.setItem(OAUTH_STATE_KEY, `${provider}:${state}`);
  const target = new URL(url);
  target.searchParams.set('state', state);
  window.location.href = target.toString();
}

/** 取出并作废存着的 state；跟回调带回来的对得上才返回是哪一家 */
function takeOAuthProvider(state: string): Provider | null {
  const saved = localStorage.getItem(OAUTH_STATE_KEY);
  localStorage.removeItem(OAUTH_STATE_KEY);
  const [provider, savedState] = (saved ?? '').split(':');
  if (savedState !== state) return null;
  return provider === 'google' || provider === 'linuxdo' ? provider : null;
}

/** LinuxDo 官方三色圆 Logo（取自 linux.do favicon SVG） */
function LinuxDoLogo({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 120 120" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <clipPath id="linuxdo-clip"><circle cx="60" cy="60" r="47" /></clipPath>
      <circle fill="#f0f0f0" cx="60" cy="60" r="50" />
      <rect fill="#1c1c1e" clipPath="url(#linuxdo-clip)" x="10" y="10" width="100" height="30" />
      <rect fill="#f0f0f0" clipPath="url(#linuxdo-clip)" x="10" y="40" width="100" height="40" />
      <rect fill="#ffb003" clipPath="url(#linuxdo-clip)" x="10" y="80" width="100" height="30" />
    </svg>
  );
}

/** Google 官方四色 G */
function GoogleLogo({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  );
}

function ProviderLogo({ provider }: { provider: Provider }) {
  return provider === 'google'
    ? <GoogleLogo className="w-[18px] h-[18px]" />
    : <LinuxDoLogo className="w-[18px] h-[18px]" />;
}

/** OAuth 按钮：点下去先刷一遍品牌色（样式在 index.css 的 .oauth-paint），刷完由外面跳授权页 */
function OAuthButton({ provider, label, painting, onClick }: {
  provider: Provider;
  label: string;
  painting: boolean;
  onClick: () => void;
}) {
  return (
    <button type="button" onClick={onClick} className={cn('btn relative overflow-hidden w-full h-11', painting && 'oauth-go')}>
      <span aria-hidden className={cn('oauth-paint', provider === 'linuxdo' && 'rows')}>
        {PAINT[provider].map(c => <i key={c} style={{ background: c }} />)}
      </span>
      <span className="oauth-label relative inline-flex items-center gap-2.5">
        <ProviderLogo provider={provider} />
        {label}
      </span>
    </button>
  );
}

/** 表单一栏：标签在上（右边可带规则提示），墨线输入框在下 */
function Field({ id, label, hint, children }: { id: string; label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id} className="text-foreground/70">
        {label}
        {hint && <span className="font-normal">{hint}</span>}
      </label>
      <div className="input h-11">{children}</div>
    </div>
  );
}

function Alert({ children }: { children: React.ReactNode }) {
  return (
    <div role="alert" className="mt-5 flex items-start gap-2 px-3 py-2.5 border border-loss bg-loss/5 text-loss text-xs font-semibold animate-in fade-in slide-in-from-top-2">
      <CircleAlert className="w-4 h-4 shrink-0 mt-0.5" />
      <span>{children}</span>
    </div>
  );
}

/** 登录前的实时报价角标：匿名 STOMP 流（后端不拒游客），进门先看见"活"的行情 */
function LiveQuote({ symbol, name }: { symbol: string; name: string }) {
  const tick = useCryptoStream(symbol, 'spot');
  return (
    <div className="flex items-center gap-2 px-3.5 py-2 rounded-md border border-border bg-card/70">
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
  const { t } = useTranslation('account');
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user, setToken, fetchUser } = useUserStore();
  // 登录卡：烟雾把它当障碍物，贴着它绕过去
  const cardRef = useRef<HTMLDivElement>(null);
  const callbackHandled = useRef(false);
  const [mode, setMode] = useState<AuthMode | null>(null);
  const [modeFailed, setModeFailed] = useState(false);
  const [error, setError] = useState('');
  // 从授权页回来、正在换 token 的那一家
  const [pending, setPending] = useState<Provider | null>(null);
  // 点了 OAuth 按钮、正在刷色的那一家
  const [painting, setPainting] = useState<Provider | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 账号密码表单
  const [isRegister, setIsRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const enter = useCallback(async (token: string) => {
    setToken(token);
    await fetchUser();
    navigate('/');
  }, [fetchUser, navigate, setToken]);

  const handleOAuthCallback = useCallback(async (code: string | null, state: string, oauthError: string | null) => {
    const provider = takeOAuthProvider(state);
    // 地址栏去掉 code/state：失败后刷新不会拿用过的 code 再换一次
    setSearchParams({}, { replace: true });
    if (!provider) {
      setError(t('login.stateMismatch'));
      return;
    }
    if (!code) {
      // access_denied 是用户在授权页点了取消；别的是配置或策略问题，把错误码亮出来
      setError(oauthError === 'access_denied'
        ? t('login.oauthDenied', { provider: PROVIDER_NAME[provider] })
        : t('login.oauthError', { provider: PROVIDER_NAME[provider], error: oauthError }));
      return;
    }
    setPending(provider);
    setError('');
    try {
      await enter(provider === 'google' ? await authApi.googleCallback(code) : await authApi.linuxDoCallback(code));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('login.failed'));
    } finally {
      setPending(null);
    }
  }, [enter, setSearchParams, t]);

  useEffect(() => {
    if (user) {
      navigate('/');
    }
  }, [user, navigate]);

  const loadMode = useCallback(() => {
    authApi.mode().then(setMode).catch(() => setModeFailed(true));
  }, []);

  useEffect(() => {
    loadMode();
  }, [loadMode]);

  useEffect(() => {
    const state = searchParams.get('state');
    const code = searchParams.get('code');
    // 授权没成：回来只带 error 和 state
    const oauthError = searchParams.get('error');
    if (state && (code || oauthError) && !callbackHandled.current) {
      callbackHandled.current = true;
      handleOAuthCallback(code, state, oauthError);
    }
  }, [searchParams, handleOAuthCallback]);

  // 从授权页按返回键回来，页面可能是 bfcache 里的旧样子，按钮还停在刷完色的状态
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => {
      if (e.persisted) setPainting(null);
    };
    window.addEventListener('pageshow', onShow);
    return () => window.removeEventListener('pageshow', onShow);
  }, []);

  const retryMode = () => {
    setModeFailed(false);
    loadMode();
  };

  const handleOAuth = (provider: Provider, url: string) => {
    if (painting) return;
    setPainting(provider);
    setError('');
    window.setTimeout(() => startOAuth(provider, url), REDUCED_MOTION ? 0 : PAINT_MS);
  };

  // 管理员直登：无 OAuth 跳转，直接调后端拿 token 进站
  const handleLocalLogin = async () => {
    setSubmitting(true);
    setError('');
    try {
      await enter(await authApi.localLogin());
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('login.failed'));
    } finally {
      setSubmitting(false);
    }
  };

  // 账号密码登录 / 邀请码注册（注册成功即登录）
  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError('');
    try {
      await enter(isRegister
        ? await authApi.register(username.trim(), password, inviteCode.trim())
        : await authApi.passwordLogin(username.trim(), password));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : (isRegister ? t('login.registerFailed') : t('login.failed')));
    } finally {
      setSubmitting(false);
    }
  };

  const switchForm = () => {
    setIsRegister(v => !v);
    setError('');
  };

  const oauthProviders = mode ? PROVIDERS.filter(p => authorizeUrl(mode, p)) : [];
  // 三种登录都没开：只剩管理员直登
  const adminOnly = mode !== null && oauthProviders.length === 0 && !mode.passwordLoginEnabled;
  const intro = pending || modeFailed || !mode ? null
    : adminOnly ? t('login.adminIntro')
    : oauthProviders.length > 0
      ? t('login.oauthIntro', { providers: oauthProviders.map(p => PROVIDER_NAME[p]).join(` ${t('login.or')} `) })
      : null;

  let body: React.ReactNode;
  if (pending) {
    body = (
      <div role="status" className="mt-7 flex items-center gap-2.5 text-sm font-semibold">
        <ProviderLogo provider={pending} />
        {t('login.signingIn', { provider: PROVIDER_NAME[pending] })}
      </div>
    );
  } else if (modeFailed) {
    body = (
      <>
        <Alert>{t('login.modeFailed')}</Alert>
        <button type="button" onClick={retryMode} className="btn w-full h-11 mt-3.5">
          <RotateCw className="w-4 h-4" />
          {t('login.retry')}
        </button>
      </>
    );
  } else if (mode === null) {
    body = (
      <div className="h-24 flex items-center justify-center gap-2 text-xs font-semibold text-foreground/70">
        <Loader2 className="w-4 h-4 animate-spin text-primary" />
        {t('login.loading')}
      </div>
    );
  } else if (adminOnly) {
    body = (
      <>
        <button type="button" onClick={handleLocalLogin} disabled={submitting} className="btn fill w-full h-11 mt-7">
          {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <LogIn className="w-4 h-4" />}
          {t('login.enterAsAdmin')}
        </button>
        <p className="mt-3.5 text-xs text-foreground/70">{t('login.adminHint')}</p>
      </>
    );
  } else {
    body = (
      <>
        {oauthProviders.length > 0 && (
          <div className="mt-7 space-y-2.5">
            {oauthProviders.map(p => (
              <OAuthButton
                key={p}
                provider={p}
                label={t(p === 'google' ? 'login.google' : 'login.linuxDo')}
                painting={painting === p}
                onClick={() => handleOAuth(p, authorizeUrl(mode, p)!)}
              />
            ))}
          </div>
        )}

        {mode.passwordLoginEnabled && (
          <>
            {oauthProviders.length > 0 && (
              <div className="mt-7 flex items-center gap-3">
                <span className="text-[12.5px] font-semibold text-foreground/70 whitespace-nowrap">
                  {isRegister ? t('login.orRegister') : t('login.orPassword')}
                </span>
                <span aria-hidden className="flex-1 h-px bg-border" />
              </div>
            )}
            <form
              onSubmit={handlePasswordSubmit}
              className={cn('flex flex-col gap-3.5', oauthProviders.length > 0 ? 'mt-3.5' : 'mt-7')}
            >
              <Field id="login-username" label={t('login.username')} hint={isRegister ? t('login.usernameHint') : undefined}>
                <input
                  id="login-username"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  autoComplete="username"
                  required
                  disabled={submitting}
                  className={INPUT}
                />
              </Field>
              <Field id="login-password" label={t('login.password')} hint={isRegister ? t('login.passwordHint') : undefined}>
                <input
                  id="login-password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  autoComplete={isRegister ? 'new-password' : 'current-password'}
                  required
                  disabled={submitting}
                  className={INPUT}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(v => !v)}
                  aria-label={showPassword ? t('login.hidePassword') : t('login.showPassword')}
                  className="shrink-0 -mr-2 w-9 h-9 inline-flex items-center justify-center text-foreground/60 hover:text-foreground cursor-pointer"
                >
                  {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </Field>
              {isRegister && (
                <Field id="login-invite" label={t('login.inviteCode')}>
                  <input
                    id="login-invite"
                    value={inviteCode}
                    onChange={e => setInviteCode(e.target.value)}
                    autoComplete="off"
                    required
                    disabled={submitting}
                    className={INPUT}
                  />
                </Field>
              )}
              <button type="submit" disabled={submitting} className="btn fill w-full h-11 mt-1">
                {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
                {isRegister ? t('login.registerSubmit') : t('login.logIn')}
              </button>
            </form>
            {/* 上下 12px 撑够点按高度，-mb-3 再把卡片底部的空吃回来 */}
            <button
              type="button"
              onClick={switchForm}
              className="mt-1 -mb-3 py-3 text-[13px] font-semibold text-foreground/70 hover:text-foreground hover:underline underline-offset-[3px] cursor-pointer"
            >
              {isRegister ? t('login.toLogin') : t('login.toRegister')}
            </button>
          </>
        )}
      </>
    );
  }

  return (
    <div className="min-h-screen relative overflow-hidden bg-background lg:grid lg:grid-cols-[1.1fr_1fr]">
      {/* 背景：抖色烟雾（WebGL，绕着登录卡流）+ 点阵纹理（顶部渐隐）。点阵和烟雾颗粒是同一套像素语言 */}
      <div aria-hidden className="absolute inset-0 pointer-events-none">
        <DitherSmoke obstacleRef={cardRef} className="absolute inset-0 w-full h-full" />
        <div
          className="absolute inset-0"
          style={{
            backgroundImage:
              'radial-gradient(color-mix(in srgb, var(--color-foreground) 16%, transparent) 1.1px, transparent 1.1px)',
            backgroundSize: '22px 22px',
            maskImage: 'radial-gradient(ellipse 90% 75% at 50% 0%, black 30%, transparent 80%)',
            WebkitMaskImage: 'radial-gradient(ellipse 90% 75% at 50% 0%, black 30%, transparent 80%)',
          }}
        />
      </div>

      {/* 仓库入口 + 语言切换：登录页不在 Layout 里，顶栏那两个到不了这儿，单独摆一份（未登录也能用）。
          不留 gap，跟顶栏移动端那组图标一样贴着排 */}
      <div className="absolute top-3 right-3 z-10 flex items-center pt-[env(safe-area-inset-top)] pr-[env(safe-area-inset-right)]">
        <GitHubLink className="w-8 h-8 rounded-md" />
        <LanguageSwitcher />
      </div>

      {/* 左：品牌面板（桌面） */}
      <div className="hidden lg:flex relative flex-col justify-between p-14">
        <div className="flex items-baseline gap-3">
          <span className="text-lg font-extrabold tracking-wide">WIIB<span className="text-primary">.</span></span>
          <span className="microlabel font-semibold">SIMULATED MARKETS · AI AGENTS</span>
        </div>

        <div className="space-y-8">
          <h1 className="text-5xl xl:text-6xl font-extrabold tracking-tight leading-[1.08] uppercase">
            <DecryptedText text="What If" speed={45} />
            <br />
            <span className="text-primary"><DecryptedText text="I Bought" speed={45} /></span>
          </h1>
          <p className="text-sm text-muted-foreground max-w-sm leading-relaxed">
            {t('login.tagline')}
          </p>
          {/* 实时行情角标：未登录也在跳动 */}
          <div className="flex flex-wrap gap-3">
            <LiveQuote symbol="BTCUSDT" name="BTC" />
            <LiveQuote symbol="ETHUSDT" name="ETH" />
          </div>
        </div>

        {/* 四项特性条：一行装不下，靠 wrap 自然折成两行 */}
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-[11px] font-semibold text-muted-foreground">
          <span className="flex items-center gap-1.5"><BarChart3 className="w-3.5 h-3.5" />{t('login.featQuotes')}</span>
          <span className="flex items-center gap-1.5"><Wallet className="w-3.5 h-3.5" />{t('login.featFunds')}</span>
          <span className="flex items-center gap-1.5"><Bot className="w-3.5 h-3.5" />{t('login.featTrader')}</span>
          <span className="flex items-center gap-1.5"><MessagesSquare className="w-3.5 h-3.5" />{t('login.featChat')}</span>
        </div>
      </div>

      {/* 右：登录卡 */}
      <div className="relative flex items-center justify-center p-5 py-14 min-h-screen lg:min-h-0">
        <div className="w-full max-w-[400px]">
          {/* 手机上没有左侧品牌面板，顶上补一块：WIIB.、一行标语、两个实时行情角标 */}
          <div className="lg:hidden mb-10 space-y-3.5">
            <div className="text-lg font-extrabold tracking-wide">WIIB<span className="text-primary">.</span></div>
            <p className="text-[36px] leading-none font-extrabold cond uppercase [word-spacing:0.18em]">
              What If <span className="text-primary">I Bought</span>
            </p>
            <div className="flex flex-wrap gap-2">
              <LiveQuote symbol="BTCUSDT" name="BTC" />
              <LiveQuote symbol="ETHUSDT" name="ETH" />
            </div>
          </div>

          {/* 卡片不画边框：底色跟烟雾底色一样，边缘由绕过去的烟勾出来 */}
          <div ref={cardRef} className="relative bg-smoke p-6 sm:p-7">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-2">
                <h2 className="text-[28px] leading-[1.15] font-extrabold cond2">
                  {adminOnly ? t('login.titleEnter') : t('login.titleLogin')}
                </h2>
                {intro && <p className="text-xs text-foreground/70">{intro}</p>}
              </div>
              <img src="/logo.png" alt="" className="w-12 sm:w-14 h-auto shrink-0 mt-1" />
            </div>

            <div aria-hidden className={cn('login-rule mt-5', (submitting || pending) && 'busy')} />

            {error && <Alert>{error}</Alert>}
            {body}
          </div>

          <p className="mt-10 flex flex-wrap gap-x-4 gap-y-1 text-xs text-foreground/70">
            <span>{t('login.paperOnly')}</span>
            {/* 两个静态页在 public/ 下，不走前端路由 */}
            <a href="/privacy.html" className="hover:text-foreground hover:underline underline-offset-[3px]">{t('login.privacy')}</a>
            <a href="/terms.html" className="hover:text-foreground hover:underline underline-offset-[3px]">{t('login.terms')}</a>
          </p>
        </div>
      </div>
    </div>
  );
}
