import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"
import i18n from "../i18n"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * 当前是否走中文口径。语言码可能带地区（zh-CN/zh-TW），只认前缀。
 * <p>只给"两套数字分档不一样"的地方用（{@link fmtMoney} 的万/亿 vs K/M/B）；
 * 纯文案一律走词表，别在这里分叉。
 */
const isZhLocale = () => (i18n.resolvedLanguage ?? i18n.language ?? 'zh').startsWith('zh');

// ---- 全站统一格式化口径：数字千分位；时间固定新加坡时区（UTC+8） ----

/** 千分位 + 固定小数位；string 自动 parseFloat；null/NaN 返回 '-'。 */
export function fmtNum(n: number | string | null | undefined, decimals = 2): string {
  const v = typeof n === 'string' ? parseFloat(n) : n;
  if (v == null || !Number.isFinite(v)) return '-';
  return v.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/** 带正负号的美元金额：+$1,284.30 / -$460.00。别拿 '$'+fmtNum 拼，负号会跑到 $ 后面。 */
export function fmtSignedUsd(n: number, decimals = 2): string {
  return `${n < 0 ? '-$' : '+$'}${fmtNum(Math.abs(n), decimals)}`;
}

/** 预测盘份额价换成美分数，最多一位小数：0.54 → 54，0.001 → 0.1，和 Polymarket 官网一样。 */
export function toCents(p: number): number {
  return +(p * 100).toFixed(1);
}

/** 带正负号的百分比：+28.41% / -1.02%。 */
export function fmtSignedPct(n: number, decimals = 2): string {
  return `${n >= 0 ? '+' : ''}${n.toFixed(decimals)}%`;
}

/**
 * 新加坡时间 yyyy-MM-dd（不传参就是"今天"）。日历/网格切日、按日查接口走这里。
 * en-CA 的短日期格式就是 yyyy-MM-dd；不能用 toISOString().slice(0,10)——那是 UTC，东八区早 8 点前退到前一天。
 */
export function fmtDate(ts: number | string | Date = Date.now()): string {
  return new Date(ts).toLocaleDateString('en-CA', { timeZone: 'Asia/Singapore' });
}

export const DAY_MS = 86_400_000;

/** 新加坡时区 yyyy-MM-dd 那一天的 [起, 止) 毫秒——按天查询与 fmtDate/fmtDateTime 同一时区 */
export function dayBounds(day: string): { from: number; to: number } {
  const from = Date.parse(`${day}T00:00:00+08:00`);
  return { from, to: from + DAY_MS };
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

/**
 * 相对时间："刚刚 / N分钟前 / N小时前 / N天前"，超过 7 天回落到 {@link fmtDateTime} 的绝对时间。
 * 通知列表用——"3分钟前"比"07-21 14:23"更快让人判断这事新不新鲜。
 */
export function fmtRelative(ts: number | string | Date): string {
  const then = new Date(ts).getTime();
  if (!Number.isFinite(then)) return '-';
  const diff = Date.now() - then;
  // 词表必须在函数体里现查：存成模块级常量的话切语言后不会变
  // 时钟漂移/服务端时间超前时 diff 为负，按"刚刚"处理，不显示"-1分钟前"
  if (diff < 60_000) return i18n.t('time.justNow');
  if (diff < 3600_000) return i18n.t('time.minutesAgo', { count: Math.floor(diff / 60_000) });
  if (diff < 86400_000) return i18n.t('time.hoursAgo', { count: Math.floor(diff / 3600_000) });
  if (diff < 7 * 86400_000) return i18n.t('time.daysAgo', { count: Math.floor(diff / 86400_000) });
  return fmtDateTime(ts);
}

/**
 * 时长："3天4时 / 5时12分 / 8分30秒 / 42秒"，最多两级单位。
 * 持仓时长用——精确到毫秒没人看，"拿了3天"和"拿了8分钟"的区别才是要传达的。
 */
export function fmtDuration(from: number | string | Date, to: number | string | Date): string {
  const ms = new Date(to).getTime() - new Date(from).getTime();
  if (!Number.isFinite(ms)) return '-';
  // 时钟漂移能让平仓时间早于开仓，负数按 0 处理，不显示"-3分钟"
  const s = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(s / 86400);
  const h = Math.floor(s / 3600) % 24;
  const m = Math.floor(s / 60) % 60;
  const sec = s % 60;
  // 每种组合一条完整词条，不用"数字+单位"拼接：英文两级之间要空格、中文不要，拼起来必错一头
  if (d > 0) return h > 0 ? i18n.t('duration.dh', { d, h }) : i18n.t('duration.d', { d });
  if (h > 0) return m > 0 ? i18n.t('duration.hm', { h, m }) : i18n.t('duration.h', { h });
  if (m > 0) return sec > 0 ? i18n.t('duration.ms', { m, s: sec }) : i18n.t('duration.m', { m });
  return i18n.t('duration.s', { s: sec });
}

/**
 * token 数缩写：12480 → 12.5k，2345678 → 2.35M。一行小字里放得下，不带尾随空格，拼接由调用方管。
 * 单轮决策基本停在 k，整局/整天合计才会上 M（k/M 大小写按 SI 来）。
 */
export function fmtTokens(n: number): string {
  if (n >= 1e6) return `${(n / 1e6).toFixed(2)}M`;
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

/**
 * 大额缩写；null/NaN 返回 '-'。分档随语言走，因为这是**数字记法**不是文案：
 * 中文四位一进（≥1万 → X.XX万，≥1亿 → X.XX亿），英文三位一进（K/M/B）。
 * 拿中文的万/亿直译成英文没人看得懂，硬套英文的 K/M/B 到中文又丢了中文的进位习惯，
 * 所以这里只能按语言分两套，不能靠一份词表抹平。两套都保留两位小数，宽度接近。
 */
export function fmtMoney(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '-';
  const abs = Math.abs(n);
  if (isZhLocale()) {
    if (abs >= 1e8) return (n / 1e8).toFixed(2) + '亿';
    if (abs >= 1e4) return (n / 1e4).toFixed(2) + '万';
    return n.toFixed(2);
  }
  if (abs >= 1e9) return (n / 1e9).toFixed(2) + 'B';
  if (abs >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (abs >= 1e3) return (n / 1e3).toFixed(2) + 'K';
  return n.toFixed(2);
}

