import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useMemo, type ReactNode, useEffect } from 'react';
import { Layout } from './components/Layout';
import { Home } from './pages/Home';
import { BStockList } from './pages/BStockList';
import { BStockRoute } from './pages/BStockDetail';
import { Portfolio } from './pages/Portfolio';
import { CoinRoute } from './pages/Coin';
import { CoinSelect } from './pages/CoinSelect';
import { CommoditySelect } from './pages/CommoditySelect';
import { Ranking } from './pages/Ranking';
import { Comments } from './pages/Comments';
import { Login } from './pages/Login';
import { Admin } from './pages/Admin';
import { Blackjack } from './pages/Blackjack';
import { Mines } from './pages/Mines';
import { VideoPoker } from './pages/VideoPoker';
import { Games } from './pages/Games';
import { Intro } from './pages/Intro';
import { Me } from './pages/Me';
import { Prediction } from './pages/Prediction';
import { AiAgent } from './pages/AiAgent';
import { Scorecard } from './pages/Scorecard';
import { Strategies } from './pages/Strategies';
import { TestnetMonitor } from './pages/TestnetMonitor';
import { ForceOrders } from './pages/ForceOrders';
import { useUserStore } from './stores/userStore';

/**
 * 全站唯一登录守卫，挂在 /* 上。
 * 判 token 不判 user：token 是 localStorage 同步恢复的，user 要等 fetchUser 异步回来，
 * 判 user 会让每次刷新都先被弹一下。页面内要用 user 的自己判 null 等它到（见 Portfolio）
 */
function RequireAuth({ children }: { children: ReactNode }) {
  const token = useUserStore(s => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function App() {
  const { token, fetchUser } = useUserStore();
  const fetchKey = useMemo(() => (token ? `auth:current:${token}` : null), [token]);

  useEffect(() => {
      if (fetchKey == null) return;
      void fetchUser();
    }, [fetchKey, fetchUser]);

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        {/* 全站唯一免登录页。其余页面进来都要发 API，游客第一个 401 就被响应拦截器弹去 /login，
            与其让人卡在半路被莫名弹走，不如在路由这层一次挡干净 */}
        <Route path="/intro" element={<Layout><Intro /></Layout>} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <Layout>
                <Routes>
                  <Route path="/" element={<Home />} />
                  <Route path="/bstock" element={<BStockList />} />
                  <Route path="/bstock/:symbol" element={<BStockRoute />} />
                  <Route path="/portfolio" element={<Portfolio />} />
                  <Route path="/coin" element={<CoinSelect />} />
                  <Route path="/coin/:symbol" element={<CoinRoute />} />
                  <Route path="/commodity" element={<CommoditySelect />} />
                  <Route path="/ranking" element={<Ranking />} />
                  <Route path="/comments" element={<Comments />} />
                  <Route path="/admin" element={<Admin />} />
                  <Route path="/games" element={<Games />} />
                  <Route path="/me" element={<Me />} />
                  <Route path="/blackjack" element={<Blackjack />} />
                  <Route path="/mines" element={<Mines />} />
                  <Route path="/videopoker" element={<VideoPoker />} />
                  <Route path="/prediction" element={<Prediction />} />
                  <Route path="/ai" element={<AiAgent />} />
                  <Route path="/scorecard" element={<Scorecard />} />
                  <Route path="/strategies" element={<Strategies />} />
                  <Route path="/testnet" element={<TestnetMonitor />} />
                  <Route path="/force-orders" element={<ForceOrders />} />
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
              </Layout>
            </RequireAuth>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
