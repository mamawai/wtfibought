import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { StrategyBacktestPanel } from '../components/backtest/StrategyBacktestPanel';
import { ReplayPanel } from '../components/backtest/ReplayPanel';
import { Seg } from '../components/ui/seg';
import './Backtest.css';

/**
 * 回测页（样式见 Backtest.css）：一页双模式。
 * - 策略回测：项目几套策略跑历史行情，K线标注进出场+工作记录还原策略每一步；
 * - 手动复盘：按所选周期（5m~1d）逐根揭示行情，自己按收盘价开多开空练盘感。
 * 两模式共享 BacktestChart（画线工具/时间脱敏/回放游标都在图表层）。
 */
export function Backtest() {
  const { t } = useTranslation('strategy');
  const [params, setParams] = useSearchParams();
  const mode = params.get('mode') === 'replay' ? 'replay' : 'strategy';

  const switchMode = (m: 'strategy' | 'replay') => {
    setParams(m === 'replay' ? { mode: 'replay' } : {}, { replace: true });
  };

  return (
    <div className="page-shell p-bt">
      <div className="pg-h">
        <div>
          <h1>{t('backtest.title')}</h1>
          <p className="sub">{mode === 'strategy' ? t('backtest.subtitleStrategy') : t('backtest.subtitleReplay')}</p>
        </div>
        <div className="r">
          <Seg value={mode} onChange={switchMode} label={t('backtest.title')} options={[
            { value: 'strategy', label: t('backtest.modeStrategy') },
            { value: 'replay', label: t('backtest.modeReplay') },
          ]} />
        </div>
      </div>

      {mode === 'strategy' ? <StrategyBacktestPanel /> : <ReplayPanel />}
    </div>
  );
}
