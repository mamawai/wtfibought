import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// ---- 全站统一格式化口径：数字千分位；时间固定新加坡时区（UTC+8） ----

/** 千分位 + 固定小数位；string 自动 parseFloat；null/NaN 返回 '-'。 */
export function fmtNum(n: number | string | null | undefined, decimals = 2): string {
  const v = typeof n === 'string' ? parseFloat(n) : n;
  if (v == null || !Number.isFinite(v)) return '-';
  return v.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/** 新加坡时间 MM/DD HH:mm（withSeconds=true 时带秒），列表/卡片时间戳统一走这里。 */
export function fmtDateTime(ts: number | string | Date, withSeconds = false): string {
  return new Date(ts).toLocaleString('zh-CN', {
    timeZone: 'Asia/Singapore',
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' as const } : {}),
    hour12: false,
  });
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

