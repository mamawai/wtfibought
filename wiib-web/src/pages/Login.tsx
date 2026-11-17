import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi, type AuthMode } from '../api';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { useLangToggle } from '../hooks/useLangToggle';
import { useWhatIfCycle } from '../hooks/useWhatIfCycle';
import { DotField } from '../components/fx/DotField';
import { GitHubLink } from '../components/GitHubLink';
import { LoginHero } from '../components/LoginHero';
import { REDUCED_MOTION } from '../lib/motion';
import { WHAT_IF } from '../lib/whatIfData';
import { cn } from '../lib/utils';
import { CircleAlert, Eye, EyeOff, LogIn, Moon, RefreshCw, Sun } from 'lucide-react';
import './Login.css';

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
function LinuxDoLogo() {
  return (
    <svg viewBox="0 0 120 120" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <clipPath id="linuxdo-clip"><circle cx="60" cy="60" r="47" /></clipPath>
      <circle fill="#f0f0f0" cx="60" cy="60" r="50" />
      <rect fill="#1c1c1e" clipPath="url(#linuxdo-clip)" x="10" y="10" width="100" height="30" />
      <rect fill="#f0f0f0" clipPath="url(#linuxdo-clip)" x="10" y="40" width="100" height="40" />
      <rect fill="#ffb003" clipPath="url(#linuxdo-clip)" x="10" y="80" width="100" height="30" />
    </svg>
  );
}

/** Google 官方四色 G */
function GoogleLogo() {
  return (
    <svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  );
}

function ProviderLogo({ provider }: { provider: Provider }) {
  return provider === 'google' ? <GoogleLogo /> : <LinuxDoLogo />;
}

export function Login() {
  const { t } = useTranslation(['account', 'layout']);
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user, setToken, fetchUser } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const { next: nextLang, toggle: toggleLang } = useLangToggle();
  // 左边演的是哪个标的、哪档时间；scatter 是亮够了、背景里的山该散了
  const { coin, span, scatter, choose } = useWhatIfCycle();
  // 背景里的山按这三块定位置：左边那几行字、左下那句话、登录卡
  const heroRef = useRef<HTMLDivElement>(null);
  const mottoRef = useRef<HTMLDivElement>(null);
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

  let body: React.ReactNode;
  if (pending) {
    body = (
      <div role="status" className="state">
        <ProviderLogo provider={pending} />
        {t('login.signingIn', { provider: PROVIDER_NAME[pending] })}
      </div>
    );
  } else if (modeFailed) {
    body = (
      <>
        <div className="note r"><CircleAlert className="ic" /><div>{t('login.modeFailed')}</div></div>
        <button type="button" className="btn lg" onClick={retryMode}><RefreshCw className="ic" />{t('login.retry')}</button>
      </>
    );
  } else if (mode === null) {
    body = <div className="state mute">{t('login.loading')}</div>;
  } else if (adminOnly) {
    body = (
      <>
        <p className="intro">{t('login.adminIntro')}</p>
        <button type="button" className={cn('btn ink lg', submitting && 'busy')} disabled={submitting} onClick={handleLocalLogin}>
          <LogIn className="ic" />{t('login.enterAsAdmin')}
        </button>
        <p className="intro">{t('login.adminHint')}</p>
      </>
    );
  } else {
    body = (
      <>
        {oauthProviders.map(p => (
          <button key={p} type="button" className={cn('btn oa', painting === p && 'go')}
                  onClick={() => handleOAuth(p, authorizeUrl(mode, p)!)}>
            <span aria-hidden="true" className={cn('paint', p === 'linuxdo' && 'rows')}>
              {PAINT[p].map(c => <i key={c} style={{ background: c }} />)}
            </span>
            <span className="lab"><ProviderLogo provider={p} />{t(p === 'google' ? 'login.google' : 'login.linuxDo')}</span>
          </button>
        ))}

        {mode.passwordLoginEnabled && (
          <>
            {oauthProviders.length > 0 && <div className="or">{isRegister ? t('login.orRegister') : t('login.orPassword')}</div>}
            {/* 表单不另占一层，几栏直接跟卡片里的别的东西一样排 */}
            <form onSubmit={handlePasswordSubmit} className="contents">
              <div className="fld">
                <label className="fl" htmlFor="login-username">
                  <span>{t('login.username')}</span>
                  {isRegister && <small>{t('login.usernameHint')}</small>}
                </label>
                <label className="inp">
                  <input id="login-username" value={username} onChange={e => setUsername(e.target.value)}
                         autoComplete="username" spellCheck={false} required disabled={submitting} />
                </label>
              </div>
              <div className="fld">
                <label className="fl" htmlFor="login-password">
                  <span>{t('login.password')}</span>
                  {isRegister && <small>{t('login.passwordHint')}</small>}
                </label>
                <label className="inp">
                  <input id="login-password" type={showPassword ? 'text' : 'password'} value={password}
                         onChange={e => setPassword(e.target.value)}
                         autoComplete={isRegister ? 'new-password' : 'current-password'} required disabled={submitting} />
                  <button type="button" className="eye" onClick={() => setShowPassword(v => !v)}
                          aria-label={showPassword ? t('login.hidePassword') : t('login.showPassword')}
                          title={showPassword ? t('login.hidePassword') : t('login.showPassword')}>
                    {showPassword ? <EyeOff className="ic" /> : <Eye className="ic" />}
                  </button>
                </label>
              </div>
              {isRegister && (
                <div className="fld">
                  <label className="fl" htmlFor="login-invite"><span>{t('login.inviteCode')}</span></label>
                  <label className="inp">
                    <input id="login-invite" value={inviteCode} onChange={e => setInviteCode(e.target.value)}
                           autoComplete="off" spellCheck={false} required disabled={submitting} />
                  </label>
                </div>
              )}
              <button type="submit" className={cn('btn ink lg', submitting && 'busy')} disabled={submitting}>
                {isRegister ? t('login.registerSubmit') : t('login.logIn')}
              </button>
            </form>
            <button type="button" className="linkb sw2" onClick={switchForm}>
              {isRegister ? t('login.toLogin') : t('login.toRegister')}
            </button>
          </>
        )}
      </>
    );
  }

  return (
    <div className="page-shell p-lo">
      {/* 背景：一张圆点网，烟在飘，左下一道山是选中那笔钱的走势。下面带 data-clear 的块是有字的地方，烟到跟前会让开 */}
      <DotField values={WHAT_IF[coin].spans[span].values} scatter={scatter}
                cardRef={cardRef} heroRef={heroRef} mottoRef={mottoRef} />

      {/* 登录页不在 Layout 里，顶栏那几颗到不了这儿，单独摆一份（未登录也能用）。中英那颗上写的是点了会换成哪种语言 */}
      <div className="tr0" data-clear>
        <GitHubLink className="ib" iconClassName="ic" />
        <button type="button" className="ib tx" onClick={toggleLang} aria-label={t('common:language.switch')}>
          {nextLang === 'en' ? 'EN' : '中文'}
        </button>
        <button type="button" className="ib" onClick={toggleTheme}
                aria-label={isDark ? t('layout:header.toLight') : t('layout:header.toDark')}
                title={isDark ? t('layout:header.toLight') : t('layout:header.toDark')}>
          {isDark ? <Sun className="ic" /> : <Moon className="ic" />}
        </button>
      </div>

      <div className="left">
        {/* 站名全称：下面那句「如果…买了」就是这个名字的意思 */}
        <div className="wm" data-clear>WHATIFIBOUGHT<u>.</u></div>
        <LoginHero ref={heroRef} coin={coin} span={span} onPick={choose} />
        {/* 这句写死英文，不跟着语言换 */}
        <div ref={mottoRef} className="motto" data-clear>Making money with LLM</div>
      </div>

      <div className="rightc">
        <div>
          <div ref={cardRef} className="card">
            <img className="mark" src="/logo.png" alt="WhatIfIBought" />
            {error && <div role="alert" className="note r"><CircleAlert className="ic" /><div>{error}</div></div>}
            {body}
          </div>

          <p className="lfoot" data-clear>
            <span>{t('login.paperOnly')}</span>
            {/* 两个静态页在 public/ 下，不走前端路由 */}
            <a className="linkb" href="/privacy.html">{t('login.privacy')}</a>
            <a className="linkb" href="/terms.html">{t('login.terms')}</a>
          </p>
        </div>
      </div>
    </div>
  );
}
