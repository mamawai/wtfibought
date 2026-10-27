import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowUpRight, Bot, Database, Eye, LayoutGrid, Library, Pickaxe, Ruler, SquareFunction, TriangleAlert } from 'lucide-react';
import { alpha, ApiError, clearToken, getToken, type DataStatus } from './api';
import i18n from './i18n';
import { usePoll } from './poll';
import { RevealGate } from './reveal';
import { AuthErrorContext, useFail, useToast } from './toast';
import { Header } from './components/Header';
import { Splash } from './components/Splash';
import { Badge } from './components/ui';
import { DataPanel } from './pages/DataPanel';
import { MinePanel } from './pages/MinePanel';
import { ObservationPanel } from './pages/ObservationPanel';
import { BoardPanel } from './pages/BoardPanel';
import { GuestMine } from './pages/Overview';
import { LibraryPanel } from './pages/LibraryPanel';
import { ReferencePanel } from './pages/ReferencePanel';
import { EvalPanel } from './pages/EvalPanel';
import { TraderPanel } from './pages/TraderPanel';

// 交易员是首页，放第一个；其余按流程排：数据 → 挖掘 → 观察区 → 因子库 → 看板，工具放最后
const TABS = [
  { id: 'trader', icon: Bot },
  { id: 'data', icon: Database },
  { id: 'mine', icon: Pickaxe },
  { id: 'observe', icon: Eye },
  { id: 'library', icon: Library },
  { id: 'board', icon: LayoutGrid },
  { id: 'reference', icon: Ruler },
  { id: 'eval', icon: SquareFunction },
] as const;

type Tab = (typeof TABS)[number]['id'];

/** 只给管理员的分区，访客连 tab 都不显示 */
const ADMIN_TABS = new Set<Tab>(['observe', 'library', 'eval']);

/** 当前分区记在地址的 # 上，刷新页还在原处；没带 # 是首页（交易员） */
const tabFromHash = (): Tab => TABS.find(t => `#${t.id}` === location.hash)?.id ?? 'trader';

export function App() {
  const { t } = useTranslation();
  const toast = useToast();
  const fail = useFail();
  // 默认只读；主站登录留下的 token 经 /me 认了才进管理员模式
  const [admin, setAdmin] = useState(false);
  // 手里有 token 时 /me 回没回来：回来之前开屏不收
  const [authChecked, setAuthChecked] = useState(() => !getToken());
  // 开屏裂开了没有：裂开之后入场动效才开始揭开
  const [opened, setOpened] = useState(false);
  const open = useCallback(() => setOpened(true), []);
  const [status, setStatus] = useState<DataStatus | null>(null);
  // 头一次拉状态失败的原因，显示在开屏上；拉到过之后的失败只弹提示
  const [down, setDown] = useState<string | null>(null);
  const connected = useRef(false);
  const [tab, setTab] = useState<Tab>(tabFromHash);
  // 访客停在只给管理员的分区上（带着 #library 刷新、中途退回只读）就看首页；/me 认了管理员再回到原分区
  const shown: Tab = admin || !ADMIN_TABS.has(tab) ? tab : 'trader';

  // 启动时看手里的 token：401 当失效清掉，403 是主站的普通用户，静默留在只读，别打扰读者
  useEffect(() => {
    if (!getToken()) return;
    const ctrl = new AbortController();
    alpha.me(ctrl.signal)
      .then(() => setAdmin(true))
      .catch((err: Error) => {
        if (ctrl.signal.aborted) return;
        if (err instanceof ApiError && err.code === 401) clearToken();
        else if (!(err instanceof ApiError && err.code === 403)) toast(err.message, 'error');
      })
      .finally(() => { if (!ctrl.signal.aborted) setAuthChecked(true); });
    return () => ctrl.abort();
  }, [toast]);

  // 动手的接口中途 401/403：退回只读，401 的 token 清掉
  const onAuthError = useCallback((code: 401 | 403) => {
    if (code === 401) clearToken();
    setAdmin(false);
    toast(i18n.t(code === 401 ? 'auth.expired' : 'auth.notAdmin'), 'error');
  }, [toast]);

  const loadStatus = useCallback(() => alpha.dataStatus()
    .then(s => { connected.current = true; setStatus(s); setDown(null); })
    .catch((err: Error) => { if (connected.current) fail(err); else setDown(err.message); }), [fail]);

  useEffect(() => {
    void loadStatus();
  }, [loadStatus]);

  // 刷新数据或补算参照网格跑着的时候每 3 秒看一眼进度
  const refreshing = (status?.refresh.running || status?.pool.grid.running) ?? false;
  usePoll(refreshing, loadStatus, 3000);

  const go = (id: Tab) => {
    setTab(id);
    history.replaceState(null, '', `#${id}`);
  };

  const items = TABS.filter(({ id }) => admin || !ADMIN_TABS.has(id)).map(({ id, icon }) => ({ id, icon, label: t(`tabs.${id}`) }));

  return (
    <AuthErrorContext.Provider value={onAuthError}>
      <RevealGate.Provider value={opened}>
        <Splash ready={status !== null && authChecked} error={down} onRetry={() => void loadStatus()} onOpen={open} />
        {status && authChecked && (
          <div className="flex min-h-screen flex-col">
            <Header items={items} active={shown} onGo={go} admin={admin} badges={<>
              {refreshing && <Badge tone="info" pulse>{t('data.refreshing')}</Badge>}
              {!status.loaded && <Badge tone="warn" icon={TriangleAlert}>{t('data.none')}</Badge>}
            </>} />

            {/* 各块都常驻，切走只是藏起来：挖掘那块的事件流不断。
                挖掘两种模式是两套组件、交易员按模式换 key，切模式就整块换掉重拉，不留另一种模式的内容；
                观察区、因子库、手填评估只有管理员才挂 */}
            <main className="wrap flex-1">
              <div className="page-h"><h1>{t('title')}</h1></div>
              <div hidden={shown !== 'trader'}>
                <TraderPanel key={String(admin)} active={shown === 'trader'} admin={admin} />
              </div>
              <div hidden={shown !== 'data'}>
                <DataPanel status={status} active={shown === 'data'} admin={admin} onReload={loadStatus} />
              </div>
              <div hidden={shown !== 'mine'}>
                {admin ? <MinePanel active={shown === 'mine'} /> : <GuestMine active={shown === 'mine'} />}
              </div>
              {admin && (
                <>
                  <div hidden={shown !== 'observe'}><ObservationPanel active={shown === 'observe'} /></div>
                  <div hidden={shown !== 'library'}><LibraryPanel active={shown === 'library'} loaded={status.loaded} /></div>
                </>
              )}
              <div hidden={shown !== 'board'}>
                <BoardPanel key={String(admin)} active={shown === 'board'} admin={admin} />
              </div>
              <div hidden={shown !== 'reference'}><ReferencePanel active={shown === 'reference'} admin={admin} /></div>
              {admin && <div hidden={shown !== 'eval'}><EvalPanel loaded={status.loaded} /></div>}
            </main>

            <footer className="wrap">
              <div className="foot flex flex-wrap items-baseline justify-between gap-x-8 gap-y-3 text-[12.5px] text-muted">
                <span className="text-[15px] font-bold text-fg">{t('footer.brand')}</span>
                <nav className="flex flex-wrap gap-x-5 gap-y-1 text-fg">
                  <a href="https://wtfibought.com" className="underline underline-offset-3">wtfibought.com</a>
                  <a href="https://intro.wtfibought.com" target="_blank" rel="noopener noreferrer"
                     className="inline-flex items-center gap-0.5 underline underline-offset-3">
                    {t('footer.intro')}<ArrowUpRight className="w-3 h-3" />
                  </a>
                  <a href="https://github.com/mamawai/wtfibought" target="_blank" rel="noopener noreferrer"
                     className="inline-flex items-center gap-0.5 underline underline-offset-3">
                    GitHub<ArrowUpRight className="w-3 h-3" />
                  </a>
                </nav>
                <span>{t('footer.disclaimer')}</span>
              </div>
            </footer>
          </div>
        )}
      </RevealGate.Provider>
    </AuthErrorContext.Provider>
  );
}
