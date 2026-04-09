import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function isTradingHours(): boolean {
  const now = new Date();
  const day = now.getDay();
  if (day === 0 || day === 6) return false; // 周末

  const hours = now.getHours();
  const minutes = now.getMinutes();
  const time = hours * 60 + minutes;

  const morningStart = 9 * 60 + 30;  // 09:30
  const morningEnd = 11 * 60 + 30;   // 11:30
  const afternoonStart = 13 * 60;    // 13:00
  const afternoonEnd = 15 * 60;      // 15:00

  return (time >= morningStart && time <= morningEnd) ||
         (time >= afternoonStart && time <= afternoonEnd);
}

const RISK_LABEL: Record<string, string> = {
  NORMAL: '正常',
  HIGH_DISAGREEMENT: 'agent分歧大',
  PARTIAL_DATA: '数据不全',
  CAUTIOUS: '谨慎',
};

export function translateRiskTag(tag: string): string {
  if (RISK_LABEL[tag]) return RISK_LABEL[tag];
  const m = tag.match(/^(.+?)_(0_10|10_20|20_30)$/);
  if (!m) return tag;
  const base: Record<string, string> = {
    SQUEEZE_REDUCE: '波动挤压降仓',
    HIGH_DISAGREEMENT_PENALTY: '分歧扣分',
    EXTREME_FEAR_SHORT_PENALTY: '恐慌做空扣分',
    DATA_PENALTY: '数据不全扣分',
  };
  return (base[m[1]] || m[1]) + `(${m[2].replace('_', '-')}min)`;
}

export function parseRiskTags(riskStatus: string | null | undefined): string[] {
  if (!riskStatus) return [];
  return riskStatus.split(',').map(s => s.trim()).filter(Boolean);
}
