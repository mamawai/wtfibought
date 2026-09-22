import { useCallback, useEffect, useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import NumberFlow from '@number-flow/react';
import { authApi, campaignApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from '../components/ui/use-toast';
import { fmtNum } from '../lib/utils';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { DecryptedText } from '../components/fx/DecryptedText';
import { DitherSmoke } from '../components/fx/DitherSmoke';
import { Input } from '../components/ui/input';
import { Button } from '../components/ui/button';
import { LanguageSwitcher } from '../components/LanguageSwitcher';
import { GitHubLink } from '../components/GitHubLink';
import { Loader2, BarChart3, Wallet, Bot, MessagesSquare, LogIn } from 'lucide-react';

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

/** OAuth state 的 localStorage 键。登录与活动领取共用同一个键，不许各存各的 */
export const OAUTH_STATE_KEY = 'oauth_state';

/**
 * 活动领取的 state 前缀。项目只在 LinuxDo 那边注册了 /login 这一个 redirect_uri，
 * 登录与领取两条链路共用这个落点，回调靠这个前缀区分该走哪边。
 */
export const CLAIM_STATE_PREFIX = 'campaign-claim:';

/**
 * 拼 LinuxDo 授权跳转 URL。登录与活动领取共用，全站只此一处拼。
 * 抄第二份的下场是切生产配置时漏改一处，领取链路静默指向 localhost。
 * <p>本文件导出非组件会让 react-refresh 退化成整页刷新（下面那行 disable）：
 * 挪进 lib 就得把 LINUXDO_CONFIG 一起挪或再导出一遍，等于给"只此一处"开口子，不划算。
 */
// eslint-disable-next-line react-refresh/only-export-components -- 理由见上
export function buildAuthorizeUrl(state: string): string {
  const p = new URLSearchParams({
    client_id: LINUXDO_CONFIG.clientId,
    response_type: 'code',
    redirect_uri: LINUXDO_CONFIG.redirectUri,
    state,
  });
  return `${LINUXDO_CONFIG.authorizeUrl}?${p.toString()}`;
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
  const [searchParams] = useSearchParams();
  const { toast } = useToast();
  const { user, setToken, fetchUser } = useUserStore();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  // 首次渲染定格"这趟是不是活动领取的回调"。必须定格：下面处理回调时会清掉 oauth_state，
  // 现算的话判断会中途翻转，"已登录就回首页"那个 effect 就把正在领取的人弹走了。
  // 判据与下面那个回调 effect 逐字一致（code + state 都在），否则会定格成一个永不开始的领取
  const [claimCallback] = useState(() => {
    const q = new URLSearchParams(window.location.search);
    return !!q.get('code') && !!q.get('state')
      && (localStorage.getItem(OAUTH_STATE_KEY) ?? '').startsWith(CLAIM_STATE_PREFIX);
  });
  // 领取中：服务端最坏要 ~2 分钟（重试 8 次），这段时间要给个说法，不能干等一个"登录中"。
  // 初值就取定格值，省掉"先闪一下登录卡再变成领取中"
  const [claiming, setClaiming] = useState(claimCallback);
  // null=模式加载中；两个开关决定展示哪些登录入口
  const [mode, setMode] = useState<{ linuxDoEnabled: boolean; passwordLoginEnabled: boolean } | null>(null);
  const callbackHandled = useRef(false);
  // 账号密码表单
  const [isRegister, setIsRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [inviteCode, setInviteCode] = useState('');

  const handleOAuthCallback = useCallback(async (code: string, state: string) => {
    const savedState = localStorage.getItem(OAUTH_STATE_KEY);
    if (state !== savedState) {
      // 这里必须把 claiming 落下来：领取回调的初值是 true，不清就永远转圈、错误提示谁也看不见
      setClaiming(false);
      setError(t('login.stateMismatch'));
      return;
    }
    localStorage.removeItem(OAUTH_STATE_KEY);

    // 回调分流：普通登录走 authApi，活动领取走 campaignApi。
    // 只有一个 redirect_uri，两条链路共用 /login 这个落点，靠 state 前缀区分。
    // 成功失败都回活动页：结果由 toast 讲，人不该被扔在登录页上
    if (savedState.startsWith(CLAIM_STATE_PREFIX)) {
      setClaiming(true);
      try {
        const reward = await campaignApi.claim(code);
        toast(t('login.claimOk', { amount: fmtNum(reward.ldcAmount) }), 'success');
      } catch (e: unknown) {
        toast(e instanceof Error ? e.message : t('login.claimFailed'), 'error');
      } finally {
        setClaiming(false);
        navigate('/campaign', { replace: true });
      }
      return;
    }

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
      const msg = e instanceof Error ? e.message : t('login.linuxDoFailed');
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [fetchUser, navigate, setToken, toast, t]);

  // 领取回调不能走这条：领取的人本来就是登录状态，弹回首页会把还没跑完的领取请求连页面一起掀掉
  useEffect(() => {
    if (user && !claimCallback) {
      navigate('/');
    }
  }, [user, navigate, claimCallback]);

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
    localStorage.setItem(OAUTH_STATE_KEY, state);
    window.location.href = buildAuthorizeUrl(state);
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
      const msg = e instanceof Error ? e.message : t('login.failed');
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
      const msg = e instanceof Error ? e.message : (isRegister ? t('login.registerFailed') : t('login.failed'));
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
      {/* 背景：抖色烟雾（WebGL）+ 点阵纹理（顶部渐隐）。点阵和烟雾颗粒是同一套像素语言 */}
      <div aria-hidden className="absolute inset-0 pointer-events-none">
        <DitherSmoke className="absolute inset-0 w-full h-full" />
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
      <div className="relative flex items-center justify-center p-4 py-14 min-h-screen lg:min-h-0">
        <div className="w-full max-w-sm">
          {/* 移动端顶部标语：桌面版这句在左侧品牌面板里，窄屏没有那块面板，补一行 */}
          <div className="lg:hidden text-center mb-7">
            <div className="microlabel font-semibold">SIMULATED MARKETS · AI AGENTS</div>
          </div>

          <div className="pt-card rounded-lg p-7 space-y-5">
            {/* 品牌标志：登录框顶部居中。120px 是卡宽(328 内容区)的 37%，再大压过表单 */}
            <img src="/logo.png" alt="WhatIfIBought" className="w-[120px] h-auto mx-auto" />

            <div>
              <div className="flex items-center gap-2">
                <span className="led" />
                <span className="microlabel font-semibold">ACCOUNT ACCESS</span>
              </div>
              <h2 className="text-xl font-extrabold tracking-tight mt-2">
                {claiming ? t('login.titleClaim') : mode?.passwordLoginEnabled && isRegister ? t('login.titleRegister') : t('login.titleLogin')}
              </h2>
            </div>

            {error && (
              <div className="p-3 rounded-md border border-destructive/40 bg-destructive/10 text-destructive text-xs font-semibold animate-in slide-in-from-top-2 fade-in">
                {error}
              </div>
            )}

            {claiming || loading || mode === null ? (
              <div className="h-28 flex flex-col items-center justify-center gap-2 text-muted-foreground">
                <Loader2 className="w-5 h-5 animate-spin text-primary" />
                <span className="text-xs font-semibold">
                  {claiming ? t('login.claiming') : loading ? t('login.loggingIn') : t('login.loading')}
                </span>
              </div>
            ) : (
              <>
                {mode.passwordLoginEnabled && (
                  <form onSubmit={handlePasswordSubmit} className="space-y-3.5">
                    {/* 登录/注册段控件 */}
                    <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
                      <button type="button" onClick={() => { setIsRegister(false); setError(''); }} className={segBtn(!isRegister)}>
                        {t('login.logIn')}
                      </button>
                      <button type="button" onClick={() => { setIsRegister(true); setError(''); }} className={segBtn(isRegister)}>
                        {t('login.register')}
                      </button>
                    </div>
                    <Input
                      value={username}
                      onChange={e => setUsername(e.target.value)}
                      placeholder={t('login.usernamePh')}
                      autoComplete="username"
                      required
                    />
                    <Input
                      type="password"
                      value={password}
                      onChange={e => setPassword(e.target.value)}
                      placeholder={isRegister ? t('login.passwordNewPh') : t('login.passwordPh')}
                      autoComplete={isRegister ? 'new-password' : 'current-password'}
                      required
                    />
                    {isRegister && (
                      <Input
                        value={inviteCode}
                        onChange={e => setInviteCode(e.target.value)}
                        placeholder={t('login.invitePh')}
                        required
                      />
                    )}
                    <Button type="submit" className="w-full h-11">
                      <LogIn className="w-4 h-4" />
                      {isRegister ? t('login.registerSubmit') : t('login.logIn')}
                    </Button>
                  </form>
                )}

                {mode.passwordLoginEnabled && mode.linuxDoEnabled && (
                  <div className="flex items-center gap-3">
                    <div className="flex-1 h-px bg-border" />
                    <span className="text-[10px] font-semibold text-muted-foreground tracking-widest">{t('login.or')}</span>
                    <div className="flex-1 h-px bg-border" />
                  </div>
                )}

                {mode.linuxDoEnabled && (
                  <Button variant="outline" className="w-full h-11" onClick={handleLinuxDoLogin}>
                    <LinuxDoLogo className="w-4 h-4" />
                    {t('login.linuxDo')}
                  </Button>
                )}

                {!mode.linuxDoEnabled && !mode.passwordLoginEnabled && (
                  <Button className="w-full h-11" onClick={handleLocalLogin}>
                    <LogIn className="w-4 h-4" />
                    {t('login.enterTerminal')}
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
