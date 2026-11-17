import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Tabs } from '../components/ui/tabs';
import { MARKETS } from '../lib/markets';
import './Markets.css';

/**
 * 四个市场列表页（股票 / 币种 / 大宗 / TradFi）共用的一层：顶上大字页签，当前这个就是页面标题；下面是各市场自己的说明和列表。
 * 四个路由挂在它底下，切市场时这一层不重挂，页签的墨线才滑得过去。
 * 手机底部 Tab 的"市场"只有一格，四个市场也靠这一排来回切。
 */
export function Markets() {
  const { t } = useTranslation('layout');
  const { pathname } = useLocation();
  const navigate = useNavigate();

  return (
    <div className="page-shell">
      <div className="p-mk">
        <Tabs className="lg" label={t('nav.markets')} value={pathname} onChange={to => navigate(to)}
              items={MARKETS.map(m => ({ value: m.to, label: t(m.labelKey) }))} />
        <Outlet />
      </div>
    </div>
  );
}
