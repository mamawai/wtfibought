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
import { Login } from './pages/Login';
import { Admin } from './pages/Admin';
import { GraphObs } from './pages/GraphObs';
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
        <Route
          path="/*"
          element={
            <Layout>
              <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/intro" element={<Intro />} />
                <Route path="/bstock" element={<BStockList />} />
                <Route path="/bstock/:symbol" element={<BStockRoute />} />
                <Route path="/portfolio" element={<Portfolio />} />
                <Route path="/coin" element={<CoinSelect />} />
                <Route path="/coin/:symbol" element={<CoinRoute />} />
                <Route path="/commodity" element={<CommoditySelect />} />
                <Route path="/ranking" element={<Ranking />} />
                <Route path="/admin" element={<Admin />} />
                <Route path="/admin/graph-obs" element={<RequireAuth><GraphObs /></RequireAuth>} />
                <Route path="/games" element={<Games />} />
                <Route path="/me" element={<Me />} />
                <Route path="/blackjack" element={<RequireAuth><Blackjack /></RequireAuth>} />
                <Route path="/mines" element={<RequireAuth><Mines /></RequireAuth>} />
                <Route path="/videopoker" element={<RequireAuth><VideoPoker /></RequireAuth>} />
                <Route path="/prediction" element={<RequireAuth><Prediction /></RequireAuth>} />
                <Route path="/ai" element={<RequireAuth><AiAgent /></RequireAuth>} />
                <Route path="/scorecard" element={<RequireAuth><Scorecard /></RequireAuth>} />
                <Route path="/strategies" element={<RequireAuth><Strategies /></RequireAuth>} />
                <Route path="/testnet" element={<RequireAuth><TestnetMonitor /></RequireAuth>} />
                <Route path="/force-orders" element={<ForceOrders />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </Layout>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
