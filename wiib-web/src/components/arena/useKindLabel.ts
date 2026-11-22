import { useTranslation } from 'react-i18next';

/** 各种叫醒原因的标签配色（.tag 的色）：例行灰、盯盘触发和预案执行橙、持仓有变化、剧烈波动和权益回落黄、手动蓝 */
export const KIND_TONE: Record<string, string> = {
  TRADE: '', WATCH: 'o', PRESET: 'o', EVENT: 'w', ALERT: 'w', DRAWDOWN: 'w', MANUAL: 'b',
};

/**
 * 叫醒它的原因说人话：例行 · 1h / 盯盘触发 / 持仓有变化 / 剧烈波动 / 权益回落 / 你叫醒的；预案执行那一行没叫醒它，是平台代它动的手。
 * mine=看的是自己的 trader：手动唤醒说「你叫醒的」，别人看是「主人叫醒的」
 */
export function useKindLabel() {
  const { t } = useTranslation('ai');
  return (kind: string, mine: boolean, interval?: string) => {
    if (kind === 'TRADE') return interval ? t('wb.kind.TRADE', { iv: interval }) : t('wb.kind.TRADE_PLAIN');
    if (kind === 'MANUAL') return t(mine ? 'wb.kind.MANUAL_MINE' : 'wb.kind.MANUAL');
    return t(`wb.kind.${kind}`);
  };
}
