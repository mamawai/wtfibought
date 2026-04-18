import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useMemo, type ReactNode } from 'react';
import { Layout } from './components/Layout';
import { Home } from './pages/Home';
import { StockList } from './pages/StockList';
import { StockDetail } from './pages/StockDetail';
import { StockKline } from './pages/StockKline';
import { Portfolio } from './pages/Portfolio';
import { Options } from './pages/Options';
import { CoinRoute } from './pages/Coin';
import { CoinSelect } from './pages/CoinSelect';
import { Ranking } from './pages/Ranking';
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
import { AiTrader } from './pages/AiTrader';
import { ForceOrders } from './pages/ForceOrders';
import { QuantVerifications } from './pages/QuantVerifications';
import { useUserStore } from './stores/userStore';
import { useDedupedEffect } from './hooks/useDedupedEffect';

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useUserStore(s => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function App() {
  const { token, fetchUser } = useUserStore();
  const fetchKey = useMemo(() => (token ? `auth:current:${token}` : null), [token]);

  useDedupedEffect(
    fetchKey,
    () => {
      void fetchUser();
    },
    [fetchKey, fetchUser],
  );

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
                <Route path="/stocks" element={<StockList />} />
                <Route path="/stock/:id" element={<StockDetail />} />
                <Route path="/stock/:id/kline" element={<StockKline />} />
                <Route path="/portfolio" element={<Portfolio />} />
                <Route path="/options" element={<Options />} />
                <Route path="/coin" element={<CoinSelect />} />
                <Route path="/coin/:symbol" element={<CoinRoute />} />
                <Route path="/ranking" element={<Ranking />} />
                <Route path="/admin" element={<Admin />} />
                <Route path="/games" element={<Games />} />
                <Route path="/me" element={<Me />} />
                <Route path="/blackjack" element={<RequireAuth><Blackjack /></RequireAuth>} />
                <Route path="/mines" element={<RequireAuth><Mines /></RequireAuth>} />
                <Route path="/videopoker" element={<RequireAuth><VideoPoker /></RequireAuth>} />
                <Route path="/prediction" element={<RequireAuth><Prediction /></RequireAuth>} />
                <Route path="/ai" element={<RequireAuth><AiAgent /></RequireAuth>} />
                <Route path="/ai-trader" element={<RequireAuth><AiTrader /></RequireAuth>} />
                <Route path="/verifications" element={<RequireAuth><QuantVerifications /></RequireAuth>} />
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
