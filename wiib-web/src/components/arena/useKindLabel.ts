import { useTranslation } from 'react-i18next';

/** 各种叫醒原因的徽章配色：例行墨色、盯盘触发橙、持仓有变化和剧烈波动黄、手动灰 */
export const KIND_CHIP: Record<string, string> = {
  TRADE: '', WATCH: 'border-primary text-primary', EVENT: 'wn', ALERT: 'wn', MANUAL: 'mute',
};

/**
 * 叫醒它的原因说人话：例行 · 1h / 盯盘触发 / 持仓有变化 / 剧烈波动 / 你叫醒的。
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
