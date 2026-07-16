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

/** 新加坡时间 HH:mm（withSeconds=true 时带秒），日内图表轴/tooltip 走这里。 */
export function fmtTime(ts: number | string | Date, withSeconds = false): string {
  return new Date(ts).toLocaleTimeString('en-GB', {
    timeZone: 'Asia/Singapore',
    hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' as const } : {}),
    hour12: false,
  });
}

/** 大额缩写：≥1亿 → X.XX亿，≥1万 → X.XX万，其余两位小数；null/NaN 返回 '-'。 */
export function fmtMoney(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '-';
  if (Math.abs(n) >= 1e8) return (n / 1e8).toFixed(2) + '亿';
  if (Math.abs(n) >= 1e4) return (n / 1e4).toFixed(2) + '万';
  return n.toFixed(2);
}

