import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useMemo } from 'react';
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
import { Games } from './pages/Games';
import { Me } from './pages/Me';
import { Card414 } from './pages/Card414';
import { useUserStore } from './stores/userStore';
import { useDedupedEffect } from './hooks/useDedupedEffect';

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
        <Route path="/414" element={<Card414 />} />
        <Route
          path="/*"
          element={
            <Layout>
              <Routes>
                <Route path="/" element={<Home />} />
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
                <Route path="/blackjack" element={<Blackjack />} />
                <Route path="/mines" element={<Mines />} />
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
