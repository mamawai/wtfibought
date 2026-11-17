import { useTranslation } from 'react-i18next';
import { Seg } from '../ui/seg';

/** 合约/现货模式切换（现货/合约两个下单面板头部共用） */
export function TradeModeSwitch({ mode, futuresOnly, onModeChange }: {
  mode: 'spot' | 'futures';
  futuresOnly?: boolean;
  onModeChange: (m: 'spot' | 'futures') => void;
}) {
  const { t } = useTranslation('trade');
  return (
    <Seg value={mode} onChange={onModeChange} options={[
      { value: 'futures', label: t('mode.futures') },
      // 纯合约标的（黄金/原油）无现货，藏掉现货切换
      ...(futuresOnly ? [] : [{ value: 'spot' as const, label: t('mode.spot') }]),
    ]} />
  );
}
