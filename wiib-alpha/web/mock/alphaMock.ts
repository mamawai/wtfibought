/**
 * 本地预览用的假后端（只给 vite.config.mock.ts 用，不进正式构建）：/api/alpha 下的接口都回假数据，形状照 src/api.ts 的类型。
 * 两条推送也有：一轮挖掘的事件流（在跑的那一轮隔几秒吐一条日志、换一次候选状态），交易员每 3 秒一帧的实时数。
 * 地址带 ?guest 是访客（不塞 token，只读），默认是管理员；?en 英文，?dark / ?light 钉死主题。
 * 没写到的 /api/alpha 一律回 code:0 data:null，免得拿到 index.html 当成接口出错
 */
import type { Plugin } from 'vite';
import type { IncomingMessage, ServerResponse } from 'node:http';

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

const r2 = (v: number) => Math.round(v * 100) / 100;
const r3 = (v: number) => Math.round(v * 1000) / 1000;
const r4 = (v: number) => Math.round(v * 1e4) / 1e4;

/** 带种子的随机数：刷新页面数字不变 */
function rng(n: number) {
  return () => {
    n = (n + 0x6d2b79f5) | 0;
    let t = Math.imul(n ^ (n >>> 15), 1 | n);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 今天 UTC 0 点 */
const utc0 = () => {
  const d = new Date();
  d.setUTCHours(0, 0, 0, 0);
  return d.getTime();
};
const dayStr = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** 产品币：交易员和看板只看这几个 */
const PRODUCTS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT', 'XRPUSDT', 'DOGEUSDT', 'HYPEUSDT'];
/** 池子里的币：数据页的面板表、交易员的持仓和信号从这里取 */
const POOL = [
  ...PRODUCTS, 'ADAUSDT', 'AVAXUSDT', 'LINKUSDT', 'DOTUSDT', 'LTCUSDT', 'BCHUSDT', 'TRXUSDT', 'UNIUSDT', 'NEARUSDT',
  'APTUSDT', 'ARBUSDT', 'OPUSDT', 'SUIUSDT', 'INJUSDT', 'TIAUSDT', 'SEIUSDT', 'FILUSDT', 'ATOMUSDT', 'ETCUSDT', 'AAVEUSDT',
  'LDOUSDT', '1000PEPEUSDT', 'WIFUSDT', '1000BONKUSDT', 'ORDIUSDT', 'JUPUSDT', 'ENAUSDT', 'ONDOUSDT', 'TAOUSDT',
  'RENDERUSDT', 'FETUSDT', 'WLDUSDT',
];
const PRICE: Record<string, number> = {
  BTCUSDT: 63714.6, ETHUSDT: 2421.81, SOLUSDT: 201.74, BNBUSDT: 612.3, XRPUSDT: 0.6123, DOGEUSDT: 0.1532, HYPEUSDT: 31.42,
  ADAUSDT: 0.4521, AVAXUSDT: 27.85, LINKUSDT: 13.42, DOTUSDT: 5.231, LTCUSDT: 71.2, BCHUSDT: 382.5, TRXUSDT: 0.1623,
  UNIUSDT: 8.31, NEARUSDT: 5.12, APTUSDT: 9.87, ARBUSDT: 0.7123, OPUSDT: 1.842, SUIUSDT: 2.341, INJUSDT: 21.33,
  TIAUSDT: 6.12, SEIUSDT: 0.4123, FILUSDT: 4.21, ATOMUSDT: 6.71, ETCUSDT: 21.42, AAVEUSDT: 152.3, LDOUSDT: 1.523,
  '1000PEPEUSDT': 0.01123, WIFUSDT: 2.123, '1000BONKUSDT': 0.02312, ORDIUSDT: 31.2, JUPUSDT: 0.8123, ENAUSDT: 0.5123,
  ONDOUSDT: 1.023, TAOUSDT: 412.3, RENDERUSDT: 6.82, FETUSDT: 1.312, WLDUSDT: 2.012,
};

const STANDARD = 7;
const HOLDOUT_FROM = '2026-05-21';
const RECENT_FROM = '2022-01-01';

// ==================== 评估摘要、门 ====================

interface BriefOpts {
  /** 主档 IC，正负带方向 */
  ic: number;
  seed: number;
  /** 截面相关最高的那条和 |ρ| */
  peer?: [string, number];
  /** 加进交易方案后 2022 起的 Sharpe 增量 */
  gain?: number;
  holdout?: boolean;
  timeseries?: boolean;
  /** 四段里和总体反号的那一段（下标） */
  flip?: number;
  nodes?: number;
  windows?: number;
}

const SHAPES = ['monotone', 'strongEnd', 'weakEnd', 'monotone', 'midHigh'] as const;

/** 一档的数字：scale 是这一档 IC 相对主档的比例 */
function horizon(o: BriefOpts, scale: number, rand: () => number, primary: boolean) {
  const meanIc = r4(o.ic * scale * (0.9 + rand() * 0.2));
  const months = 64;
  const icir = r2(Math.abs(meanIc) / (0.03 + rand() * 0.015) * Math.sign(meanIc));
  const t = r2(Math.abs(icir) * Math.sqrt(months));
  const segments = [0, 1, 2, 3].map(i => r4(meanIc * (0.6 + rand() * 0.9) * (i === o.flip ? -0.4 : 1)));
  const sharpe = r2(Math.abs(meanIc) * 38 + rand() * 0.3);
  return {
    meanIc, icir, t, months,
    monotonic: r2(Math.sign(meanIc) * (0.5 + rand() * 0.5)),
    spread: r4(meanIc * 0.021),
    spreadT: r2(Math.sign(meanIc) * t * (0.6 + rand() * 0.2)),
    shape: SHAPES[Math.floor(rand() * SHAPES.length)],
    segments,
    netReturn: r4(Math.abs(meanIc) * 14 * (0.7 + rand() * 0.5)),
    sharpe,
    permPct: primary ? r3(Math.min(0.999, 0.93 + Math.abs(meanIc) * 2)) : null,
    hitRate: r3(0.5 + Math.abs(meanIc) * 1.2),
    exposure: r3(0.38 + rand() * 0.06),
    turnover: r3(0.12 + rand() * 0.1),
    fundingBps: r2((rand() - 0.4) * 2.4),
    breakEvenBps: r2(6 + Math.abs(meanIc) * 260),
    recentIc: r4(meanIc * (0.7 + rand() * 0.4)),
    recentT: r2(t * 0.8),
    recentMonths: 52,
    recentNetReturn: r4(Math.abs(meanIc) * 9 * (0.6 + rand() * 0.5)),
    recentSharpe: r2(sharpe * (0.8 + rand() * 0.3)),
  };
}

function holdoutBrief(o: BriefOpts, rand: () => number) {
  const one = (scale: number) => {
    const meanIc = r4(o.ic * scale * (0.5 + rand() * 0.6));
    return {
      meanIc, icir: r2(meanIc / 0.06), se: r4(0.006 + rand() * 0.004), t: r2(Math.abs(meanIc) / 0.008), months: 6,
      monotonic: r2(Math.sign(meanIc) * (0.3 + rand() * 0.6)), samples: 718,
    };
  };
  return { since: Date.parse(`${HOLDOUT_FROM}T00:00:00Z`), horizons: { '6h': one(0.7), '12h': one(0.85), '24h': one(1) } };
}

function brief(o: BriefOpts) {
  const rand = rng(o.seed);
  const gain = o.gain ?? 0.12;
  const [peer, rho] = o.peer ?? ['mom_7d', 0.38];
  return {
    primary: '24h',
    horizons: { '6h': horizon(o, 0.68, rand, false), '12h': horizon(o, 0.85, rand, false), '24h': horizon(o, 1, rand, true) },
    redundancy: { maxAbs: rho, with: peer, ready: true },
    complexity: { nodes: o.nodes ?? 9, windows: o.windows ?? 1, constants: 0, cross: 1 },
    gates: [] as unknown[],
    neutral: { meanIc: r4(o.ic * 0.8), icir: r2(o.ic * 18), t: r2(Math.abs(o.ic) * 140), retained: r2(0.7 + rand() * 0.25), against: 8 },
    gainSharpe: { base: 1.18, with: r2(1.18 + gain * 0.7), gain: r2(gain * 0.7) },
    gainSharpeRecent: { base: 1.42, with: r2(1.42 + gain), gain: r2(gain) },
    recentFrom: RECENT_FROM,
    ...(o.holdout ? { holdout: holdoutBrief(o, rand) } : {}),
    ...(o.timeseries ? {
      timeseries: {
        horizons: Object.fromEntries(['6h', '12h', '24h'].map((h, i) => [h, {
          meanIc: r4(o.ic * 0.6 * (0.7 + i * 0.15)), icir: r2(o.ic * 9), sameSignSymbols: 4 + i, netReturn: r4(o.ic * 6 * (1 + i * 0.2)),
        }])),
        bySymbol: Object.fromEntries(PRODUCTS.map((s, i) => [s, r3(o.ic * (0.4 + ((i * 37) % 10) / 8) * (i === 4 ? -1 : 1))])),
      },
    } : {}),
  };
}

type Brief = ReturnType<typeof brief>;

/** 十一道门；fail 里的几道按没过写 */
function gates(b: Brief, direction: 1 | -1, fail: string[] = []) {
  const p = b.horizons['24h'];
  const no = (id: string) => !fail.includes(id);
  const f = (v: number | null, d = 4) => (v == null ? '—' : v.toFixed(d));
  const same = p.segments.filter(v => Math.sign(v) === Math.sign(p.meanIc)).length;
  return [
    { id: 'G0', name: '前视自检', passed: true, need: '截断数据重算，结果和全量一致（算子没偷看未来）', note: '截断重算与全量一致' },
    { id: 'G1', name: '最小样本', passed: true, need: '最长那档样本外有效时刻 ≥ 300', note: '96 根档样本外有效时刻 1312 个' },
    {
      id: 'G2', name: 'IC', passed: no('G2'), need: '主档 |IC| ≥ 0.01、t ≥ 3；截尾后顶组减底组和 IC 同向、t ≥ 2',
      note: `主档月度截面 IC 均值 ${f(p.meanIc)}，ICIR ${f(p.icir)}，t ${f(no('G2') ? p.t : 2.14, 2)}（${p.months} 个月），`
        + `截尾后顶组减底组每期 ${f(p.spread * 1e4, 1)} bps、t ${f(p.spreadT, 1)}，三组形状：单调`,
    },
    {
      id: 'G3', name: '四段稳定', passed: no('G3') && same >= 3, need: '四段至少 3 段和总体同号，最后一段也同号',
      note: `四段 IC [${p.segments.map(v => f(v)).join(', ')}]，${same} 段与总体同号，最后一段同号`,
    },
    {
      id: 'G4', name: '衰减', passed: true, need: '三档 IC 同号',
      note: `三档 IC 24:${f(b.horizons['6h'].meanIc)}、48:${f(b.horizons['12h'].meanIc)}、96:${f(p.meanIc)}`,
    },
    {
      id: 'G5', name: '排列检验', passed: no('G5'), need: '主档排列分位 ≥ 0.95，同批 BH（q = 0.1）后还显著',
      note: `主档多空腿（按波动分仓）截面排列分位 ${no('G5') ? f(p.permPct, 3) : '0.912'}；`
        + `扣成本和资金费后净收益 ${f(p.netReturn)}、盈亏平衡单边成本 ${f(p.breakEvenBps, 1)} bps（只报不判）`,
    },
    {
      id: 'G6', name: '冗余', passed: no('G6') && (b.redundancy.maxAbs ?? 0) < 0.7, need: '和参照集、库、观察区里每一条的截面 |ρ| < 0.7',
      note: `与 ${b.redundancy.with} 的截面 |ρ| = ${f(b.redundancy.maxAbs)}`,
    },
    {
      id: 'G7', name: '复杂度', passed: true, need: '节点 ≤ 40、窗口 ≤ 6、常量 ≤ 6、截面算子 ≤ 2',
      note: `节点 ${b.complexity.nodes}，窗口 ${b.complexity.windows}，常量 ${b.complexity.constants}，截面算子 ${b.complexity.cross}`,
    },
    { id: 'G8', name: '结构原创', passed: true, need: '规范形和已知因子都不一样', note: '规范形是新的' },
    {
      id: 'G9', name: '增量', passed: no('G9') && b.gainSharpeRecent.gain > 0.1, need: `加进交易方案后搜索段 ${RECENT_FROM} 起净 Sharpe 增量 > 0.1`,
      note: `加进交易方案后搜索段 ${RECENT_FROM} 起净 Sharpe ${f(b.gainSharpeRecent.base, 2)} → ${f(b.gainSharpeRecent.with, 2)}`
        + `（增量 ${f(b.gainSharpeRecent.gain, 2)}）；全段 ${f(b.gainSharpe.base, 2)} → ${f(b.gainSharpe.with, 2)}`
        + `（增量 ${f(b.gainSharpe.gain, 2)}），净收益 0.3810 → ${f(0.381 + b.gainSharpe.gain * 0.5)}`,
    },
    {
      id: 'G10', name: '方向', passed: Math.sign(p.meanIc) === direction, need: '主档 IC 的符号和假设声明的方向一致',
      note: `假设说因子值${direction > 0 ? '越大越强' : '越大越弱'}，主档截面 IC ${f(p.meanIc)}，`
        + `${Math.sign(p.meanIc) === direction ? '对上了' : '对不上'}`,
    },
  ];
}

// ==================== 因子：候选、观察区、因子库 ====================

interface Seed {
  name: string;
  family: string;
  expr: string;
  hypothesis: string;
  note?: string;
  direction: 1 | -1;
  o: BriefOpts;
}

const SEEDS: Seed[] = [
  {
    name: 'funding_crowding', family: 'funding', expr: 'CsRank(-Mean($funding_rate, 96))', direction: 1,
    hypothesis: '资金费连着一天偏高说明多头挤在一边，杠杆多头要么平仓要么被强平，接下来一天这类币相对跑输；费率越低（越负）的币反而有补涨。',
    note: '取负号，因子值越大 = 过去一天费率越低', o: { ic: 0.0301, seed: 11, peer: ['premium_carry', 0.41], gain: 0.18, holdout: true, timeseries: true },
  },
  {
    name: 'basis_reversal', family: 'basis', expr: 'CsZ(-Delta($premium, 48))', direction: 1,
    hypothesis: '永续溢价在半天里被快速拉高，多半是追涨的杠杆资金，溢价回落时价格跟着回吐；溢价被砸下去的币反过来。',
    o: { ic: 0.0244, seed: 12, peer: ['funding_crowding', 0.33], gain: 0.14, holdout: true, timeseries: true },
  },
  {
    name: 'oi_divergence', family: 'positioning', expr: 'CsRank(Delta(Log($oi_value), 96) - Sum($ret, 96))', direction: -1,
    hypothesis: '持仓名义一天里涨得比价格多，说明新开的仓位没推动价格，是在被动接盘，接下来一天偏弱。',
    o: { ic: -0.0212, seed: 13, peer: ['turnover_oi', 0.29], gain: 0.12, holdout: true, timeseries: true, nodes: 12 },
  },
  {
    name: 'spot_lead', family: 'spot_flow', expr: 'CsRank(Sum($spot_taker_buy_quote, 96) / Sum($taker_buy_quote, 96))', direction: 1,
    hypothesis: '现货主动买入额占永续主动买入额的比例高，说明是真金白银的买盘而不是杠杆，后面一天相对更强。',
    o: { ic: 0.0187, seed: 14, peer: ['size_quote', 0.22], gain: 0.11, holdout: true, nodes: 11 },
  },
  {
    name: 'liq_exhaust', family: 'liquidation', expr: 'CsRank(Sum($liq_long, 96) / Mean($quote_volume, 672))', direction: 1,
    hypothesis: '多头强平额相对平时成交额大，说明这一波杠杆多头已经被洗掉，抛压出清后接下来一天反弹。',
    o: { ic: 0.0163, seed: 15, peer: ['lottery_7d', 0.27], gain: 0.1, holdout: true, nodes: 10, windows: 2 },
  },
  {
    name: 'vol_compress', family: 'volatility', expr: 'CsZ(-Std($ret, 96) / Std($ret, 672))', direction: 1,
    hypothesis: '最近一天的波动比过去一周低得多，是在蓄势，横截面上这类币后面一天的收益更好。',
    o: { ic: 0.0151, seed: 16, peer: ['vol_7d', 0.52], gain: 0.13, holdout: true, nodes: 10, windows: 2 },
  },
  {
    name: 'ls_contrarian', family: 'positioning', expr: 'CsRank(-Delta($ls_acct, 192))', direction: 1,
    hypothesis: '散户多空账户比两天里升得越快，越是散户在抄底追多，反着做。',
    o: { ic: 0.0118, seed: 17, peer: ['oi_divergence', 0.24], gain: 0.04, holdout: true },
  },
  {
    name: 'hour_flow', family: 'seasonality', expr: 'CsDemean(Corr($ret, $btc_ret, 672))', direction: -1,
    hypothesis: '和 BTC 联动越紧的币，在 BTC 横盘的日子里越没有自己的行情。',
    o: { ic: -0.0104, seed: 18, peer: ['beta_btc', 0.66], gain: 0.02, holdout: true },
  },
  {
    name: 'taker_pressure', family: 'flow', expr: 'CsZ(Mean($taker_ls_vol, 48)) * Sign(Sum($ret, 48))', direction: 1,
    hypothesis: '主动买卖量比和价格方向一致的时候，趋势还没走完，顺着做半天到一天。',
    o: { ic: 0.0198, seed: 19, peer: ['mom_7d', 0.36], gain: 0.15, holdout: true, timeseries: true, nodes: 13 },
  },
  {
    name: 'premium_carry', family: 'basis', expr: 'CsRank(-Mean($premium, 192))', direction: 1,
    hypothesis: '两天平均溢价低（甚至贴水）的币，做多能收资金费，价格上也更少被杠杆多头压着。',
    o: { ic: 0.0176, seed: 20, peer: ['funding_crowding', 0.61], gain: 0.12, holdout: true },
  },
  {
    name: 'spot_volume_shock', family: 'spot_flow', expr: 'CsRank(Sum($spot_quote_volume, 96) / Mean($spot_quote_volume, 672))', direction: 1,
    hypothesis: '现货成交额突然放大到平时的几倍，是新的资金进场，后面一天还有惯性。',
    o: { ic: 0.0139, seed: 21, peer: ['size_quote', 0.44], gain: 0.07, holdout: true },
  },
];

const seed = (name: string) => SEEDS.find(s => s.name === name)!;

/** 一条候选：status 之外的字段都从 Seed 来；fail 是没过的门 */
function candidate(s: Seed, i: number, runId: string, status: string, extra: { fail?: string[]; reason?: string; review?: object | null; attempts?: number } = {}) {
  const b = brief(s.o);
  const evaluated = !['proposed', 'evaluating'].includes(status);
  return {
    id: `${runId}-${i}`, seq: i, name: s.name, hypothesis: s.hypothesis, family: s.family, expr: status === 'proposed' ? null : s.expr,
    note: s.note ?? null, direction: s.direction, status, attempts: extra.attempts ?? 1, reason: extra.reason ?? null,
    review: extra.review ?? null,
    ...(evaluated ? { gates: gates(b, s.direction, extra.fail), brief: b, standard: STANDARD } : {}),
  };
}

const REVIEW_PASS = { verdict: 'pass', comment: '机制讲得通，数字也撑得住：费率越高越拥挤，横截面上做反向符合直觉。', suggestion: '' };
const REVIEW_REVISE = { verdict: 'revise', comment: '窗口 96 和假设里说的"一天"对上了，但 Mean 会被一两次极端费率拉偏。', suggestion: '换成 Med 或者先 CsRank 再平均' };
const REVIEW_REJECT = { verdict: 'reject', comment: '和 vol_7d 换皮：只是把一周波动换成了一天对一周的比，信息没变。', suggestion: '' };

function recheck(s: Seed, since: number) {
  const rand = rng(s.o.seed + 100);
  const one = (scale: number) => ({
    mean: r4(s.o.ic * scale * (0.6 + rand() * 0.5)), icir: r2(s.o.ic * 14 * scale), months: 5, samples: 610,
    monotonic: r2(Math.sign(s.o.ic) * (0.3 + rand() * 0.6)),
  });
  return { since, at: Date.now() - 2 * DAY, horizons: { 24: one(0.7), 48: one(0.85), 96: one(1) } };
}

function reeval(s: Seed, peers: [string, number][], passed = true) {
  const b = brief({ ...s.o, seed: s.o.seed + 50 });
  return {
    at: Date.now() - 5 * HOUR, direction: s.direction, asked: false, passed, reason: passed ? null : 'G9 没过：2022 起 Sharpe 增量 0.04',
    peers, gates: gates(b, s.direction, passed ? [] : ['G9']), brief: b, standard: STANDARD,
  };
}

const LIBRARY = ['funding_crowding', 'basis_reversal', 'oi_divergence', 'spot_lead', 'liq_exhaust', 'vol_compress'];
const RETIRED: Record<string, { at: number; reason: string }> = {
  ls_contrarian: { at: 9, reason: '入库后复查两次主档 IC 都掉到 0.003 上下，不显著了' },
  hour_flow: { at: 23, reason: '和 beta_btc 越来越像，组合里权重一直是 0' },
};
/** 退役、恢复改的是这一份 */
const retiredState: Record<string, { at: number; reason: string } | null> = { ...RETIRED };

function factor(name: string, i: number) {
  const s = seed(name);
  const approved = Date.now() - (40 - i * 6) * DAY;
  const r = retiredState[name];
  return {
    id: `f-${name}`, name, hypothesis: s.hypothesis, expr: s.expr,
    canonical: s.expr.replace(/\d+/g, 'W').replace(/\$/g, ''),
    columns: [...new Set(s.expr.match(/\$[a-z_]+/g)!.map(c => c.slice(1)))],
    approved_at: approved, run_id: `${(0x3fa1c2 + i * 977).toString(16)}e1`, review: REVIEW_PASS,
    recheck: i % 3 === 2 ? null : recheck(s, approved),
    reeval: i === 4 ? reeval(s, [], false) : i === 1 ? reeval(s, []) : null,
    brief: brief(s.o), standard: i === 5 ? 6 : STANDARD,
    retired_at: r ? Date.now() - r.at * DAY : null, retire_reason: r?.reason ?? null,
  };
}

const allFactors = () => [...LIBRARY, ...Object.keys(RETIRED)].map(factor);

function library() {
  const all = allFactors();
  const live = all.filter(f => f.retired_at == null);
  const combo = brief({ ic: 0.0412, seed: 77, holdout: true });
  const rand = rng(78);
  return {
    factors: live,
    combo: {
      brief: { primary: '24h', recentFrom: RECENT_FROM, horizons: combo.horizons, holdout: combo.holdout },
      asOf: utc0() - 1,
      weights: live.map((f, i) => ({ name: f.name, weight: r4((i % 4 === 3 ? -1 : 1) * (0.0002 + rand() * 0.0004)) * (i === 5 ? 0 : 1) })),
      latest: Object.fromEntries(POOL.map((s, i) => [s, i === 9 ? null : r3((rand() - 0.5) * 3.2)])),
    },
    computing: false,
    retired: all.filter(f => f.retired_at != null),
    reeval: { running: false, startedAt: Date.now() - 26 * HOUR, finishedAt: Date.now() - 25 * HOUR, total: 6, done: 6 },
    standard: STANDARD,
  };
}

function factorDetail(id: string) {
  const f = allFactors().find(x => x.id === id);
  if (!f) return null;
  return {
    ...f,
    gates: gates(f.brief, seed(f.name).direction).map(g => ({ ...g, value: null })),
    eval: { expr: f.expr, canonical: f.canonical, lookaheadFree: true, horizons: Object.keys(f.brief.horizons), standard: f.standard },
  };
}

/** 观察区：approve / decline 之后从这里删 */
let observing = ['taker_pressure', 'premium_carry', 'spot_volume_shock'];

function observation() {
  return {
    items: observing.map((name, i) => {
      const s = seed(name);
      const c = candidate(s, i + 1, ['9d2e41b7', '9d2e41b7', '5c8a03f2'][i], 'observing', { review: REVIEW_PASS });
      const blocked = name === 'spot_volume_shock';
      return {
        ...c, run_id: ['9d2e41b7', '9d2e41b7', '5c8a03f2'][i], created_at: Date.now() - (i + 1) * 19 * HOUR,
        updated_at: Date.now() - (i + 1) * 18 * HOUR,
        reeval: i === 0 ? reeval(s, [['premium_carry', 0.58], ['funding_crowding', 0.21]]) : null,
        admission: {
          ok: !blocked,
          checks: [
            { ok: true, text: 'holdout 段主档 IC 和搜索段同号' },
            { ok: !blocked, text: blocked ? 'holdout 段主档 t 1.37，没到 2' : 'holdout 段主档 t ≥ 2（2.41）' },
            { ok: blocked ? null : true, text: 'holdout 段有效时刻 ≥ 150' },
          ],
        },
      };
    }),
    reeval: { running: false, startedAt: Date.now() - 6 * HOUR, finishedAt: Date.now() - 5 * HOUR, total: 3, done: 3 },
  };
}

// ==================== 挖掘：campaign、轮次、事件流 ====================

const LIVE_CAMPAIGN = 'c7f1a9';
const LIVE_RUN = 'b81f30c4';

const campaignsData = () => [
  {
    id: LIVE_CAMPAIGN, created_at: Date.now() - 3 * HOUR, status: 'running', rounds: 10, k: 5, note: '多看资金费和基差，少碰纯价格',
    lang: 'zh', max_survivors: 5, deadline_ms: Date.now() + 9 * HOUR, done_rounds: 3, survivors: 2, stop_reason: null, error: null,
  },
  {
    id: 'a41d77', created_at: Date.now() - 30 * HOUR, status: 'done', rounds: 8, k: 5, note: '现货流向', lang: 'zh',
    max_survivors: null, deadline_ms: null, done_rounds: 8, survivors: 3, stop_reason: '跑满 8 轮', error: null,
  },
  {
    id: '6e09b3', created_at: Date.now() - 3 * DAY, status: 'stopped', rounds: 10, k: 4, note: '', lang: 'zh',
    max_survivors: null, deadline_ms: Date.now() - 2.8 * DAY, done_rounds: 4, survivors: 1, stop_reason: '到了时限', error: null,
  },
  {
    id: '2b5f18', created_at: Date.now() - 6 * DAY, status: 'failed', rounds: 5, k: 5, note: '', lang: 'zh',
    max_survivors: null, deadline_ms: null, done_rounds: 1, survivors: 0, stop_reason: null, error: '生成模型连不上：401 Unauthorized',
  },
];

const COUNTS_DONE = [
  { rejected_gates: 3, rejected_review: 1, observing: 1 },
  { rejected_gates: 2, rejected_fdr: 1, observing: 1, failed: 1 },
  { rejected_gates: 4, rejected_review: 1 },
  { rejected_gates: 3, declined: 1, approved: 1 },
];

/** campaign 下的各轮，最新在前 */
function runsOf(campaignId: string) {
  const c = campaignsData().find(x => x.id === campaignId)!;
  const n = c.status === 'running' ? c.done_rounds + 1 : c.done_rounds;
  return Array.from({ length: n }, (_, i) => {
    const running = c.status === 'running' && i === 0;
    const id = running ? LIVE_RUN : `${campaignId}${(i + 1).toString(16)}0`;
    return {
      id, created_at: c.created_at + (n - 1 - i) * 52 * MIN,
      status: running ? 'running' : c.status === 'failed' && i === 0 ? 'failed' : 'done', k: c.k, note: c.note, lang: 'zh',
      error: c.status === 'failed' && i === 0 ? c.error : null, campaign_id: campaignId,
      counts: running ? { rejected_gates: 1, evaluating: 2, reviewing: 1, proposed: 1 } : COUNTS_DONE[i % COUNTS_DONE.length],
    };
  });
}

const SOLO_RUNS = [
  { id: '5c8a03f2', created_at: Date.now() - 9 * DAY, status: 'done', k: 5, note: '', lang: 'zh', error: null, campaign_id: null, counts: { rejected_gates: 4, observing: 1 } },
  { id: '9d2e41b7', created_at: Date.now() - 11 * DAY, status: 'done', k: 5, note: '试试强平数据', lang: 'zh', error: null, campaign_id: null, counts: { rejected_gates: 3, observing: 2 } },
];

const allRuns = () => [...campaignsData().flatMap(c => runsOf(c.id)), ...SOLO_RUNS];

const LESSONS = [
  { kind: 'avoid', text: '纯价格动量在 24h 档上几乎都过不了 G6，和 mom_7d、mom_21d 的 |ρ| 动不动 0.8 以上，别再出这类题。' },
  { kind: 'try', text: '资金费和溢价一起用时，先各自 CsRank 再相减，比直接相减稳，四段同号能多一段。' },
  { kind: 'note', text: '强平列只有产品币有数，用到它的因子在池子里样本不够，G1 会卡住。' },
  { kind: 'try', text: '现货主动买入额比永续的，窗口从 96 拉到 192 后 holdout 段 t 从 1.6 升到 2.4。' },
  { kind: 'avoid', text: 'Corr 套 Corr 的写法复杂度很快爆，评审也一直说看不懂机制。' },
];

function campaignDetail(id: string) {
  const c = campaignsData().find(x => x.id === id);
  if (!c) return null;
  const runs = runsOf(id);
  return {
    campaign: c, runs,
    lessons: runs.flatMap((r, i) => LESSONS.slice(i % 3, i % 3 + 2).map((l, j) => ({
      id: i * 10 + j, run_id: r.id, campaign_id: id, kind: l.kind, text: l.text, created_at: r.created_at + 40 * MIN,
    }))),
  };
}

/** 一轮的候选终态：在跑的那一轮拿不到这里，看事件流 */
function runCandidates(runId: string) {
  const pick = ['funding_crowding', 'ls_contrarian', 'hour_flow', 'premium_carry', 'spot_volume_shock'];
  return [
    candidate(seed(pick[0]), 1, runId, 'observing', { review: REVIEW_PASS }),
    candidate(seed(pick[1]), 2, runId, 'rejected_gates', { fail: ['G9'], reason: 'G9 没过：2022 起 Sharpe 增量 0.04', attempts: 2 }),
    candidate(seed(pick[2]), 3, runId, 'rejected_review', { review: REVIEW_REJECT }),
    candidate(seed(pick[3]), 4, runId, 'rejected_fdr', { fail: ['G5'], reason: '同批 BH 后排列检验不显著', review: REVIEW_PASS }),
    candidate(seed(pick[4]), 5, runId, 'rejected_gates', { fail: ['G2', 'G3'], reason: 'G2 没过：主档 t 2.14；G3 只有 2 段同号' }),
  ];
}

const LIVE_PICK = ['taker_pressure', 'spot_lead', 'vol_compress', 'oi_divergence', 'liq_exhaust'];

/** 在跑那一轮的剧本：一帧一条，前几帧开流就发，后面隔 2.5 秒一条 */
function liveScript() {
  const c = (i: number, status: string, extra?: Parameters<typeof candidate>[4]) =>
    ({ event: 'candidate', data: candidate(seed(LIVE_PICK[i - 1]), i, LIVE_RUN, status, extra) });
  const log = (text: string) => ({ event: 'log', data: { text } });
  return [
    { event: 'status', data: { stage: 'proposing' } },
    log('读研究方针 #4、上一轮 4 条经验，按机制挑 5 个方向出题'),
    ...[1, 2, 3, 4, 5].map(i => c(i, 'proposed')),
    log('1. taker_pressure 写好表达式，开始评估'),
    c(1, 'evaluating'),
    log('2. spot_lead 写好表达式，开始评估'),
    c(2, 'evaluating'),
    c(1, 'reviewing', { review: null }),
    log('1. taker_pressure 门全过，交给评审'),
    c(1, 'passed', { review: REVIEW_PASS }),
    log('3. vol_compress 第一次写的窗口太多（7 个），退回重写'),
    c(3, 'rewrite', { attempts: 2, reason: 'G7：窗口 7 个，上限 6' }),
    c(2, 'rejected_gates', { fail: ['G6'], reason: 'G6 没过：和 size_quote 的截面 |ρ| 0.74' }),
    log('2. spot_lead 和 size_quote 太像，丢掉'),
    c(3, 'evaluating', { attempts: 2 }),
    c(4, 'evaluating'),
    log('等 3、4 条评估，5 条排队'),
    c(4, 'revise', { review: REVIEW_REVISE, attempts: 2 }),
    log('4. oi_divergence 评审让改：Mean 会被极端值拉偏'),
  ];
}

/** 跑完的一轮：回放用 */
function doneScript(runId: string) {
  const cands = runCandidates(runId);
  return [
    { event: 'status', data: { stage: 'proposing' } },
    { event: 'log', data: { text: '读研究方针 #4，按机制挑 5 个方向出题' } },
    ...cands.map(c => ({ event: 'candidate', data: c })),
    { event: 'status', data: { stage: 'summarizing' } },
    { event: 'log', data: { text: '5 条里 1 条过了全部门和评审，同批 BH 后还剩 1 条' } },
    { event: 'status', data: { stage: 'reflecting' } },
    { event: 'lessons', data: { items: LESSONS.slice(0, 2) } },
    { event: 'status', data: { stage: 'committing' } },
    { event: 'done', data: { observing: 1 } },
  ];
}

const sseHead = (res: ServerResponse) => {
  res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
};
const frame = (res: ServerResponse, id: number, e: { event: string; data: unknown }) =>
  res.write(`id: ${id}\nevent: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`);

function runEvents(req: IncomingMessage, res: ServerResponse, runId: string, after: number) {
  sseHead(res);
  if (runId !== LIVE_RUN) {
    const events = doneScript(runId);
    events.forEach((e, i) => { if (i + 1 > after) frame(res, i + 1, e); });
    frame(res, events.length + 1, { event: 'end', data: { status: 'done', lastId: events.length } });
    res.end();
    return;
  }
  const events = liveScript();
  // 开流先发前 8 帧，剩下的隔 2.5 秒一帧；发完挂着，每 2.5 秒一个注释行保活
  let i = Math.max(after, 0);
  const first = Math.max(8, after);
  for (; i < Math.min(first, events.length); i++) frame(res, i + 1, events[i]);
  const timer = setInterval(() => {
    if (i < events.length) {
      frame(res, i + 1, events[i]);
      i++;
    } else res.write(': ping\n\n');
  }, 2500);
  req.on('close', () => clearInterval(timer));
}

// ==================== 数据页 ====================

function panelStatus(symbol: string, i: number) {
  const rand = rng(1000 + i);
  const late = i > 20 ? (i - 20) * 40 * DAY : 0;
  const from = Date.parse('2021-01-01T00:15:00Z') + late;
  const to = utc0() + 14 * HOUR - 1;
  const cov = (base: number) => r3(Math.min(1, base + rand() * (1 - base)));
  const product = PRODUCTS.includes(symbol);
  // 没有现货对的币现货列整列是空的；强平列只有产品币有
  const noSpot = symbol === 'HYPEUSDT' || symbol === '1000BONKUSDT';
  return {
    rows: Math.round((to - from) / (15 * MIN)), from, to,
    liqFrom: product ? Date.parse('2023-06-01T00:00:00Z') : null,
    spotFrom: noSpot ? null : from,
    spotSymbol: noSpot ? null : symbol.replace(/^1000/, ''),
    gaps: i % 11 === 7 ? 3 : i % 17 === 9 ? 1 : 0,
    coverage: {
      funding_rate: cov(0.9), premium: i === 33 ? 0.72 : cov(0.85), oi: cov(0.8), ls_acct: cov(0.75),
      ...(noSpot ? {} : { spot_quote_volume: cov(0.7) }),
      ...(product ? { liq_count: cov(0.6) } : {}),
    },
    poolMonths: Math.max(1, 71 - (i > 20 ? (i - 20) * 2 : 0) - (i % 5)),
  };
}

function dataStatus() {
  const months: Record<string, number> = {};
  const rand = rng(2021);
  for (let y = 2021; y <= 2026; y++) {
    for (let m = 1; m <= 12; m++) {
      if (y === 2026 && m > new Date().getUTCMonth() + 1) break;
      const idx = (y - 2021) * 12 + m;
      months[`${y}-${String(m).padStart(2, '0')}`] = Math.min(80, Math.round(52 + idx * 0.6 + rand() * 6));
    }
  }
  const today = utc0();
  // 每天 UTC 14 点起刷新，两个钟头刷完装载
  const loadedAt = today + 16 * HOUR > Date.now() ? today - 8 * HOUR : today + 16 * HOUR;
  return {
    loaded: true, loadedAt,
    facts: {
      symbols: PRODUCTS, from: '2021-01-01', to: dayStr(today - DAY), rows: 1_434_912, liqFrom: '2023-06-01', spotFrom: '2021-01-01',
      spotMissing: 1, holdoutFrom: HOLDOUT_FROM, costTiers: [5, 8, 12], poolSize: 80, poolSymbols: 482,
    },
    symbols: Object.fromEntries(PRODUCTS.map((s, i) => [s, panelStatus(s, i)])),
    pool: {
      months, symbols: 482,
      status: Object.fromEntries(POOL.map((s, i) => [s, panelStatus(s, i)])),
      loaded: { symbols: 482, months: Object.keys(months).length, gridPoints: 8_520, rows: 642_380 },
      grid: { ready: true, running: false, startedAt: loadedAt - HOUR, finishedAt: loadedAt - 0.5 * HOUR, total: 158, done: 158 },
    },
    referenceCount: 158,
    refresh: {
      running: false, startedAt: loadedAt - 2 * HOUR, finishedAt: loadedAt, error: null, stage: 'reference', total: 482, done: 482,
      symbol: null, underlyingTypes: ['COIN', 'MEME', 'AI', 'DEFI'], candidates: 512, members: 482,
      thin: { TAOUSDT: 'premium 72%', HYPEUSDT: 'funding_rate 61%' }, spotMissing: ['HYPEUSDT', '1000BONKUSDT'],
    },
  };
}

const overview = () => ({
  runs: allRuns().sort((a, b) => b.created_at - a.created_at).slice(0, 10)
    .map(r => ({ id: r.id, created_at: r.created_at, status: r.status, k: r.k, counts: r.counts })),
  trials: 1268, factors: LIBRARY.length, observing: observing.length,
});

// ==================== 看板、参照集、手填评估 ====================

function board(admin: boolean) {
  const rand = rng(404);
  const of = 78;
  const stance = (rank: number) => ({ stance: rank <= of / 5 ? 'long' : rank > of * 4 / 5 ? 'short' : 'neutral', rank, of });
  const ranks = () => Object.fromEntries(PRODUCTS.map(c => [c, stance(1 + Math.floor(rand() * of))]));
  return {
    day: dayStr(utc0()), signalMs: utc0() - 1, coins: PRODUCTS,
    factors: LIBRARY.map((name, i) => ({
      no: i + 1, live: name !== 'spot_lead', inCombo: i !== 5, stances: name === 'spot_lead' ? {} : ranks(),
      ...(admin ? { id: `f-${name}`, name, expr: seed(name).expr, sign: seed(name).direction, weight: r4(0.0002 + rand() * 0.0004) } : {}),
    })),
    combo: ranks(),
  };
}

const REFERENCE: [string, string][] = [
  ['size_quote', 'Log(Sum($quote_volume, 672))'], ['size_oi', 'Log($oi_value)'], ['turnover_oi', 'Sum($quote_volume, 672) / $oi_value'],
  ['vol_7d', 'Std($ret, 672)'], ['lottery_7d', 'Max($ret, 672)'], ['mom_7d', 'Sum($ret, 672)'], ['mom_21d', 'Sum($ret, 2016)'],
  ['beta_btc', 'Cov($ret, $btc_ret, 672) / Var($btc_ret, 672)'], ['basis_7d', 'Mean($premium, 672)'],
  ...[4, 16, 48, 96, 192].flatMap(w => [
    [`ROC${w}`, `Ref($close, ${w}) / $close`], [`MA${w}`, `Mean($close, ${w}) / $close`], [`STD${w}`, `Std($close, ${w}) / $close`],
    [`BETA${w}`, `Slope($close, ${w}) / $close`], [`RSQR${w}`, `Rsquare($close, ${w})`], [`MAX${w}`, `Max($high, ${w}) / $close`],
    [`QTLU${w}`, `Quantile($close, ${w}, 0.8) / $close`], [`CORR${w}`, `Corr($close, Log($volume + 1), ${w})`],
  ] as [string, string][]),
  ['KMID', '($close - $open) / $open'], ['KLEN', '($high - $low) / $open'], ['KUP', '($high - Greater($open, $close)) / $open'],
];

function reference() {
  return {
    job: { running: false, startedAt: Date.now() - 2 * DAY, finishedAt: Date.now() - 2 * DAY + 41 * MIN, total: REFERENCE.length, done: REFERENCE.length },
    items: REFERENCE.map(([name, expr], i) => {
      const rand = rng(3000 + i);
      const ic = r4((rand() - 0.45) * 0.05);
      const passed = Math.abs(ic) > 0.019 && i % 3 !== 1;
      const b = brief({ ic, seed: 3000 + i, peer: [REFERENCE[(i + 5) % REFERENCE.length][0], r2(0.2 + rand() * 0.6)], gain: passed ? 0.12 : 0.03 });
      return {
        name, expr, passed, updatedAt: Date.now() - 2 * DAY + i * MIN,
        gates: gates(b, ic >= 0 ? 1 : -1, passed ? [] : ['G2', 'G9'].slice(0, 1 + (i % 2))), brief: b, standard: i % 9 === 4 ? 6 : STANDARD,
      };
    }),
    standard: STANDARD,
  };
}

const COLUMNS: [string, string][] = [
  ['open', '开盘价'], ['high', '最高价'], ['low', '最低价'], ['close', '收盘价'], ['volume', '成交量（币）'], ['quote_volume', '成交额（USDT）'],
  ['trades', '成交笔数'], ['taker_buy_base', '主动买入量（币）'], ['taker_buy_quote', '主动买入额（USDT）'],
  ['ret', '本根 bar 的对数收益 log(close/上一根 close)'], ['funding_rate', '最近一次已结算的资金费率，折算成每 8 小时'],
  ['btc_close', '同一时刻 BTC 收盘价'], ['btc_ret', '同一时刻 BTC 的对数收益'], ['liq_long', '本根 bar 内多头被强平的名义额，只有产品币有数'],
  ['liq_short', '本根 bar 内空头被强平的名义额，只有产品币有数'], ['liq_count', '本根 bar 内强平笔数，只有产品币有数'],
  ['premium', '永续价相对指数价的溢价率，正数是永续更贵'], ['oi', '全市场持仓量（币）'], ['oi_value', '全市场持仓名义（USDT）'],
  ['ls_top_acct', '大户多空账户数之比'], ['ls_top_pos', '大户多空持仓量之比'], ['ls_acct', '全体账户多空数之比'],
  ['taker_ls_vol', 'taker 买量与卖量之比'], ['spot_quote_volume', '同名现货对本根 bar 的成交额（USDT）'],
  ['spot_taker_buy_quote', '同名现货对本根 bar 的主动买入额（USDT）'], ['spot_trades', '同名现货对本根 bar 的成交笔数'],
  ['hour', 'bar 开始时刻的 UTC 小时 0~23'],
];
const OPERATORS: [string, string][] = [
  ['Abs', '绝对值'], ['Sign', '符号 -1/0/1'], ['Log', '自然对数，非正数为 NaN'], ['Pow', 'x 的 y 次方'], ['Greater', '两者取大'],
  ['Less', '两者取小'], ['Gt', 'x > y 出 1 否则 0'], ['Lt', 'x < y 出 1 否则 0'], ['If', 'If(c, a, b)：c 非零取 a 否则取 b'],
  ['Ref', 'n 根 bar 之前的值'], ['Delta', '当前值减 n 根之前的值'], ['Mean', '窗内均值'], ['Sum', '窗内求和'], ['Std', '窗内样本标准差'],
  ['Var', '窗内样本方差'], ['Max', '窗内最大'], ['Min', '窗内最小'], ['Med', '窗内中位数'], ['Mad', '窗内平均绝对偏差'],
  ['Rank', '当前值在窗内的分位'], ['Skew', '窗内偏度'], ['Kurt', '窗内峰度'], ['EMA', '指数加权均值'], ['WMA', '线性加权均值'],
  ['IdxMax', '窗内最大值离现在几根'], ['IdxMin', '窗内最小值离现在几根'], ['Slope', '窗内对时间回归的斜率'], ['Rsquare', '窗内对时间回归的 R²'],
  ['Resi', '窗内对时间回归的残差'], ['Quantile', 'Quantile(x, n, q)：窗内 q 分位'], ['Corr', '两列窗内相关'], ['Cov', '两列窗内协方差'],
  ['CsRank', '同一时刻全池的分位'], ['CsDemean', '减去同一时刻全池的均值'], ['CsZ', '同一时刻全池标准化'],
];

/** 手填评估：POST 之后跑 3 秒出结果 */
let evalJob: { running: boolean; startedAt: number; finishedAt: number | null; expr: string; stage: string | null; error: null } = {
  running: false, startedAt: Date.now() - 2 * HOUR, finishedAt: Date.now() - 2 * HOUR + 40_000,
  expr: 'CsRank(-Mean($funding_rate, 48)) - CsRank(Mean($premium, 48))', stage: null, error: null,
};

function evalResult(expr: string) {
  const b = brief({ ic: 0.0172, seed: expr.length, peer: ['funding_crowding', 0.76], gain: 0.05 });
  return {
    expr, canonical: expr.replace(/\d+/g, 'W').replace(/\$/g, ''), passed: false, brief: b,
    gates: gates(b, 1, ['G6', 'G9']).map(g => ({ ...g, value: null })),
    eval: { expr, lookaheadFree: true, samples: 1312, primary: 96, standard: STANDARD },
  };
}

// ==================== 口径、方针、模型 ====================

let sharpeGainMin = 0.1;
let standardId = STANDARD;

function standard() {
  const q = (p95: number) => ({ median: r3(p95 * 0.08), p90: r3(p95 * 0.66), p95, p99: r3(p95 * 1.55), max: r3(p95 * 2.2) });
  return {
    id: standardId, createdAt: Date.now() - 12 * DAY,
    content: {
      params: {
        test_size: 192, embargo: 4, min_train: 2880, iterations: 1000, seed: 42, cost_tiers_bps: [5, 8, 12], primary: 96, lookahead_cuts: 20,
        min_points: 8, min_names: 20, groups: 3, trim: 0.05, leg_enter: 0.2, leg_exit: 0.4, leg_cap: 2, segments: 4, holdout_days: 180,
        recent_from: Date.parse(`${RECENT_FROM}T00:00:00Z`),
      },
      thresholds: {
        min_samples: 300, ic_min: 0.01, t_min: 3, spread_t_min: 2, min_segments: 3, perm_pct: 0.95, fdr_q: 0.1,
        sharpe_gain_min: sharpeGainMin, max_redundancy: 0.7, admit_min_points: 150, admit_t_min: 2,
      },
      scheme: { bars: 96, carry: 'Mean($premium, 672)', carryMinDays: 365, vol: 'Std($ret, 672)', carryShare: carry.share },
      reference: { count: 158, digest: '3f9a1c7e5b20' },
    },
    history: Array.from({ length: standardId }, (_, i) => ({ id: standardId - i, createdAt: Date.now() - (12 + i * 9) * DAY })),
    stale: { library: 1, observing: 0 },
    calibration: {
      standardId: STANDARD, cutMs: utc0() - 30 * DAY, randoms: 300, seed: 42, createdAt: Date.now() - 4 * DAY,
      library: LIBRARY.map(name => ({ name, expr: seed(name).expr })),
      result: {
        base: { recentSharpe: 1.42, sharpe: 1.18, recentFrom: RECENT_FROM, carryShare: 0.25 },
        library: LIBRARY.map((name, i) => ({
          name, recent: { base: 1.3, with: 1.42, gain: r3(0.08 + i * 0.02) }, full: { base: 1.1, with: 1.18, gain: r3(0.05 + i * 0.01) },
        })),
        candidates: { n: 3, items: [], recent: q(0.142), full: q(0.11) },
        random: { n: 300, items: [], recent: q(0.093), full: q(0.071) },
      },
      same: { standard: standardId === STANDARD, data: true, library: true },
    },
    job: { running: false, startedAt: Date.now() - 4 * DAY, finishedAt: Date.now() - 4 * DAY + 52 * MIN, total: 309, done: 309 },
  };
}

let policy = {
  id: 4, createdAt: Date.now() - 8 * DAY,
  families: [
    { id: 'funding', what: '资金费拥挤之后的反转：费率高说明多头挤在一边', cols: 'funding_rate, premium', hold: '1~3 天' },
    { id: 'basis', what: '永续溢价偏离指数价之后的回归', cols: 'premium', hold: '半天~2 天' },
    { id: 'positioning', what: '持仓和多空比的变化里谁在接盘', cols: 'oi, oi_value, ls_acct, ls_top_pos', hold: '1 天' },
    { id: 'spot_flow', what: '现货买盘领先永续：真金白银比杠杆更持久', cols: 'spot_quote_volume, spot_taker_buy_quote', hold: '1~2 天' },
    { id: 'liquidation', what: '强平出清后的反弹', cols: 'liq_long, liq_short, liq_count', hold: '半天~1 天' },
    { id: 'price', what: '纯价格形态（已证做不成，只作对照）', cols: '', hold: '' },
  ],
  genRules: '一次只讲一个机制，假设里写清楚谁在买、谁在卖、为什么接下来一天会怎样。\n窗口按 4=1h、16=4h、48=12h、96=24h、192=48h、672=7d 取，别用奇怪的数。',
  implementChecks: '截面算子最多两个；先 CsRank 再做加减；用到强平列的因子只在产品币上有数，别当全池信号。',
  reviewRules: '看机制讲不讲得通、和已知效应是不是换皮；数字好看但讲不出谁在亏钱的，打回。',
  history: [4, 3, 2, 1].map(id => ({ id, createdAt: Date.now() - (8 + (4 - id) * 15) * DAY })),
};

const models: Record<string, object | null> = {
  gen: { protocol: 'anthropic', baseUrl: 'https://api.anthropic.com', model: 'claude-opus-5', effort: 'high', apiKeyTail: 'a9F2', updatedAt: Date.now() - 6 * DAY },
  review: { protocol: 'openai', baseUrl: 'https://api.openai.com/v1', model: 'gpt-5', effort: 'medium', apiKeyTail: '7Qx1', updatedAt: Date.now() - 6 * DAY },
};

// ==================== 交易员 ====================

const INITIAL = 10_000;
const USER_ID = 31415;
const carry: { enabled: boolean; share: number; changedAt: number | null } = { enabled: true, share: 0.25, changedAt: Date.now() - 20 * DAY };
let sizing: { leverage: number; margin: number; changedAt: number | null } = { leverage: 3, margin: 0.4, changedAt: Date.now() - 15 * DAY };
const selected = new Set(['funding_crowding', 'basis_reversal', 'oi_divergence', 'liq_exhaust', 'vol_compress']);

/** 过去 45 天的净值：每天从调仓前的净值走到下一次调仓前 */
function dailyPath() {
  const rand = rng(45);
  const today = utc0();
  let eq = INITIAL;
  return Array.from({ length: 45 }, (_, i) => {
    const start = eq;
    const pct = (rand() - 0.46) * 0.022;
    eq = r2(eq * (1 + pct));
    const pnl = r2(eq - start);
    const combo = r2(pnl * (0.7 + rand() * 0.2));
    const carryNet = r2(pnl * 0.25 + (rand() - 0.5) * 6);
    const model = r2(combo + carryNet);
    const day = dayStr(today - (45 - i) * DAY);
    const leg = (net: number) => ({ price: r2(net * 1.1), funding: r2(net * 0.06 + 0.8), cost: r2(-Math.abs(net) * 0.08 - 1.2), net, ret: r4(net / (start * 0.6)) });
    return {
      day, start, end: eq, pnl, pct: r4(pnl / start),
      attrib: i === 44 ? null : {
        until: dayStr(today - (44 - i) * DAY), actual: pnl, model, residual: r2(pnl - model),
        legs: { combo: leg(combo), carry: leg(carryNet) }, missing: i === 30 ? ['HYPEUSDT'] : [],
      },
    };
  });
}

function traderDaily() {
  return {
    initialBalance: INITIAL, maxDrawdown: 0.0418, days: dailyPath(),
    legs: {
      combo: { days: 45, cumReturn: 0.0312, drawdown: 0.0121, maxDrawdown: 0.0384, stop: 0.15, hit: false },
      carry: { days: 45, cumReturn: 0.0087, drawdown: 0.0043, maxDrawdown: 0.0112, stop: 0.08, hit: false },
    },
  };
}

/** 持仓：两条腿合起来的净头寸 */
const POSITIONS: [string, 'LONG' | 'SHORT', number, number][] = [
  ['BTCUSDT', 'LONG', 0.031, 0.012], ['ETHUSDT', 'SHORT', -0.4, 0.21], ['SOLUSDT', 'LONG', 4.2, 0], ['XRPUSDT', 'LONG', 1210, 380],
  ['DOGEUSDT', 'SHORT', -5100, -1200], ['AVAXUSDT', 'LONG', 21.4, 0], ['LINKUSDT', 'SHORT', -38, 0], ['SUIUSDT', 'LONG', 260, 90],
  ['ARBUSDT', 'SHORT', -820, 0], ['INJUSDT', 'LONG', 18.2, 6.1], ['TAOUSDT', 'SHORT', -1.1, 0], ['WIFUSDT', 'SHORT', -210, -60],
];

function livePositions(tick: number) {
  return POSITIONS.map(([symbol, side, combo, carryQty], i) => {
    const quantity = r3(Math.abs(combo + carryQty));
    const entry = PRICE[symbol];
    const mark = +(entry * (1 + Math.sin(tick / 3 + i) * 0.004 + (i % 3 - 1) * 0.006)).toPrecision(6);
    const notional = r2(quantity * mark);
    return {
      symbol, side, quantity, notional, entryPrice: entry, markPrice: mark,
      unrealizedPnl: r2((mark - entry) * quantity * (side === 'LONG' ? 1 : -1)),
      legs: { combo: r2(combo * entry), carry: r2(carryQty * entry) },
    };
  });
}

function liveFrame(tick: number) {
  const days = dailyPath();
  const dayStart = days[days.length - 1].end;
  const positions = livePositions(tick);
  const upnl = positions.reduce((s, p) => s + p.unrealizedPnl, 0);
  const balance = r2(dayStart - 12.4);
  const equity = r2(balance + upnl);
  return {
    ok: true, ts: Date.now(), userId: USER_ID, equity, balance, available: r2(equity * 0.58),
    day: dayStr(utc0()), dayStart, dayPnl: r2(equity - dayStart), positions, positionsDay: dayStr(utc0()),
  };
}

function traderLive(req: IncomingMessage, res: ServerResponse) {
  sseHead(res);
  let tick = 0;
  const send = () => res.write(`data: ${JSON.stringify(liveFrame(tick++))}\n\n`);
  send();
  const timer = setInterval(send, 3000);
  req.on('close', () => clearInterval(timer));
}

/** 每天 UTC 0 点调一次仓，今天这次也在：它的盈亏还没走完，在实时数里 */
const runBriefs = () => {
  const days = dailyPath();
  const starts = [...days.slice(-19).map(d => ({ day: d.day, start: d.start })), { day: dayStr(utc0()), start: days[days.length - 1].end }];
  return starts.reverse().map((d, i) => ({
    day: d.day, status: i === 6 ? 'partial' : i === 13 ? 'failed' : 'done', signalMs: Date.parse(`${d.day}T00:00:00Z`) - 1,
    startedAt: Date.parse(`${d.day}T00:00:10Z`), finishedAt: Date.parse(`${d.day}T00:03:${String(20 + i).padStart(2, '0')}Z`),
    equityBefore: i === 13 ? null : d.start, equityAfter: i === 13 ? null : r2(d.start - 3.1), error: i === 13 ? 'sim 下单接口超时，一笔没成' : null,
    factorNames: [...selected], orderCount: i === 13 ? 0 : 18 + (i % 5), failedCount: i === 6 ? 2 : 0,
  }));
};

function runDetail(day: string) {
  const b = runBriefs().find(r => r.day === day);
  if (!b) return null;
  const rand = rng(day.length + Number(day.slice(-2)));
  const signals = (leg: string) => POOL.slice(0, 24).map((symbol, i) => ({
    symbol, signal: r4((rand() - 0.5) * (leg === 'carry' ? 0.004 : 3)), rank: i + 1, vol: r4(0.004 + rand() * 0.01),
    weight: i < 4 ? r4(0.06 - i * 0.008) : i >= 20 ? r4(-0.05 + (i - 20) * 0.007) : 0,
  }));
  const coins = POSITIONS.map(([symbol, , combo, carryQty], i) => {
    const w = r4(combo * PRICE[symbol] / 10_000 * 0.6);
    const c = r4(carryQty * PRICE[symbol] / 10_000 * 0.6);
    return {
      symbol, weight: r4(w + c), combo: w, carry: c, notional: r2((combo + carryQty) * PRICE[symbol]), price: PRICE[symbol],
      refPrice: +(PRICE[symbol] * 1.0007).toPrecision(6), current: r3(combo * 0.8), target: i === 7 ? null : r3(combo + carryQty),
      note: i === 7 ? '差额太小，没下单' : i === 3 && b.status === 'partial' ? '下单失败：价格偏离太大' : null, failed: i === 3 && b.status === 'partial',
    };
  });
  const attrib = dailyPath().find(d => d.day === day)?.attrib ?? null;
  return {
    ...b, userId: USER_ID,
    factors: [...selected].map(name => ({ id: `f-${name}`, name, expr: seed(name).expr, weight: r4(0.15 + rand() * 0.25) })),
    legs: {
      combo: { long: ['SOLUSDT', 'AVAXUSDT', 'SUIUSDT', 'INJUSDT'], short: ['ETHUSDT', 'LINKUSDT', 'ARBUSDT', 'TAOUSDT'] },
      carry: { long: ['XRPUSDT', 'SUIUSDT', 'INJUSDT'], short: ['DOGEUSDT', 'WIFUSDT'] },
    },
    targets: b.status === 'failed' ? null : {
      coins, signals: { combo: signals('combo'), carry: signals('carry') }, notTrading: ['HYPEUSDT'], carry: true, carryShare: 0.25,
      sizing: { leverage: 3, margin: 0.4, multiple: 0.6, caps: { bracket: 20, stress: 7.5 }, setting: { leverage: 3, margin: 0.4 } },
    },
    orders: b.status === 'failed' ? [] : [
      ...coins.slice(0, 3).map(c => ({ symbol: c.symbol, action: 'leverage', side: null, quantity: null, requestId: null, leverage: 3, ok: true })),
      ...coins.slice(0, 10).map((c, i) => ({
        symbol: c.symbol, action: i === 2 ? 'close' : 'open', side: c.combo >= 0 ? 'LONG' : 'SHORT', quantity: i === 2 ? null : Math.abs(r3(c.combo * 0.2)),
        requestId: `rb-${day}-${i}`, ok: !(b.status === 'partial' && i === 3), orderId: 880_000 + i, filled: Math.abs(r3(c.combo * 0.2)),
        price: c.price, commission: r3(c.price * 0.0004), ...(b.status === 'partial' && i === 3 ? { error: '价格偏离标记价超过 1%，拒单' } : {}),
      })),
    ],
    attrib: attrib && { ...attrib, coins: coins.slice(0, 8).map((c, i) => ({
      symbol: c.symbol, combo: c.combo, carry: c.carry, entry: c.price, exit: +(c.price * (1 + (i % 3 - 1) * 0.012)).toPrecision(6),
      ret: r4((i % 3 - 1) * 0.012), fund: r4((i % 2 ? 1 : -1) * 0.0003), legs: { combo: r2((i % 3 - 1) * 14.2), carry: r2((i % 2 ? 1 : -1) * 2.1) },
    })) },
  };
}

function traderFactors() {
  const scheme = (base: boolean) => ({
    periods: 1580, netReturn: base ? 0.412 : 0.468, sharpe: base ? 1.21 : 1.33, maxDrawdown: base ? 0.142 : 0.131, turnover: 0.21,
    carryFundingBps: 1.42, recentFrom: Date.parse(`${RECENT_FROM}T00:00:00Z`), recentNetReturn: base ? 0.311 : 0.352,
    recentSharpe: base ? 1.42 : 1.57, carryShare: carry.share,
  });
  return {
    items: [...LIBRARY].map(name => ({
      id: `f-${name}`, name, expr: seed(name).expr, selected: selected.has(name), missing: name === 'spot_lead' ? ['spot_taker_buy_quote'] : [],
    })),
    carry,
    compare: {
      running: false, startedAt: Date.now() - 3 * HOUR, finishedAt: Date.now() - 3 * HOUR + 4 * MIN, factorId: 'f-oi_divergence',
      name: 'oi_divergence', stage: null, error: null,
      result: {
        action: 'add', better: true,
        base: { factors: ['funding_crowding', 'basis_reversal'], search: scheme(true), holdout: { ...scheme(true), periods: 180, netReturn: 0.031, sharpe: 0.84 } },
        alt: { factors: ['funding_crowding', 'basis_reversal', 'oi_divergence'], search: scheme(false), holdout: { ...scheme(false), periods: 180, netReturn: 0.044, sharpe: 1.02 } },
      },
    },
  };
}

function traderSizing() {
  return {
    ...sizing, maxMargin: 0.8, previewError: null,
    preview: { leverage: Math.min(sizing.leverage, 20), margin: sizing.margin, multiple: r2(sizing.margin * Math.min(sizing.leverage, 20) / 2), caps: { bracket: 20, stress: 7.5 }, day: dayStr(utc0()) },
  };
}

// ==================== 插件 ====================

const ok = (res: ServerResponse, data: unknown) => {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ code: 0, msg: 'ok', data }));
};

const fail = (res: ServerResponse, code: number, msg: string) => {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ code, msg, data: null }));
};

function body(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise(resolve => {
    let raw = '';
    req.on('data', c => { raw += c; });
    req.on('end', () => resolve(raw ? JSON.parse(raw) : {}));
  });
}

export function alphaMock(): Plugin {
  return {
    name: 'wiib-alpha-mock',

    // 默认塞一个假 token（/me 认它是管理员），?guest 不塞；挖掘页默认打开在跑的那一轮；语言、主题按地址钉死
    transformIndexHtml() {
      return [{
        tag: 'script',
        injectTo: 'head-prepend' as const,
        children: `if (location.search.includes('guest')) localStorage.removeItem('wiib-user');
else localStorage.setItem('wiib-user', JSON.stringify({ state: { token: 'mock-token' }, version: 0 }));
if (!sessionStorage.getItem('wiib-alpha-run')) sessionStorage.setItem('wiib-alpha-run', '${LIVE_RUN}');
localStorage.setItem('wiib-lang', location.search.includes('en') ? 'en' : 'zh');
if (location.search.includes('dark')) localStorage.setItem('theme', 'dark');
else if (location.search.includes('light')) localStorage.setItem('theme', 'light');`,
      }];
    },

    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = req.url || '';
        if (!url.startsWith('/api/alpha/')) return next();
        const path = url.split('?')[0].slice('/api/alpha'.length);
        const q = new URLSearchParams(url.split('?')[1] ?? '');
        const admin = !!req.headers.satoken;
        const method = req.method ?? 'GET';
        let m: RegExpMatchArray | null;

        // ---- 推送 ----
        if ((m = path.match(/^\/runs\/([^/]+)\/events$/))) return runEvents(req, res, m[1], Number(q.get('after') ?? 0));
        if (path === '/trader/live') return traderLive(req, res);

        if (method === 'GET') {
          if (path === '/me') return admin ? ok(res, { admin: true, lang: 'zh' }) : fail(res, 401, '没登录');
          if (path === '/busy') {
            return ok(res, {
              refresh: null, campaign: '已经有一个 campaign 在跑', reeval: null, libraryReeval: null, reference: null, calibrate: null,
              recheck: null, policy: '有 campaign 在跑，方针等它停了再改', standard: null, carryShare: '有 campaign 在跑时改不了份额', compare: null,
            });
          }
          if (path === '/overview') return ok(res, overview());
          if (path === '/data/status') return ok(res, dataStatus());
          if (path === '/campaigns') return ok(res, { campaigns: campaignsData() });
          if ((m = path.match(/^\/campaigns\/([^/]+)$/))) return ok(res, campaignDetail(m[1]));
          if (path === '/runs') return ok(res, { runs: allRuns(), trials: 1268 });
          if ((m = path.match(/^\/runs\/([^/]+)$/))) {
            const run = allRuns().find(r => r.id === m![1]);
            if (!run) return fail(res, 404, '没有这一轮');
            return ok(res, { run, candidates: run.id === LIVE_RUN ? [] : runCandidates(run.id), trials: 1268 });
          }
          if (path === '/observation') return ok(res, observation());
          if (path === '/library') return ok(res, library());
          if ((m = path.match(/^\/library\/([^/]+)$/))) return ok(res, factorDetail(m[1]));
          if (path === '/board') return ok(res, board(admin));
          if (path === '/eval') {
            if (evalJob.running && Date.now() - evalJob.startedAt > 3000) evalJob = { ...evalJob, running: false, finishedAt: Date.now(), stage: null };
            return ok(res, { ...evalJob, result: evalJob.running ? null : evalResult(evalJob.expr) });
          }
          if (path === '/dsl') {
            return ok(res, {
              columns: COLUMNS.map(([name, doc]) => ({ name, doc })), operators: OPERATORS.map(([name, doc]) => ({ name, doc })),
              windows: '4=1h、16=4h、48=12h、96=24h、192=48h、672=7d', maxWindow: 2016,
              complexity: { nodes: 40, windows: 6, constants: 6, cross: 2 },
            });
          }
          if (path === '/reference') return ok(res, reference());
          if (path === '/models') return ok(res, models);
          if (path === '/standard') return ok(res, standard());
          if (path === '/policy') return ok(res, policy);
          if (path === '/trader/account') {
            return ok(res, {
              userId: USER_ID, initialBalance: INITIAL,
              job: { running: false, startedAt: utc0() + 10_000, finishedAt: utc0() + 210_000, day: dayStr(utc0()), stage: null, error: null },
            });
          }
          if (path === '/trader/daily') return ok(res, traderDaily());
          if (path === '/trader/runs') return ok(res, { items: runBriefs(), job: { running: false } });
          if ((m = path.match(/^\/trader\/runs\/([^/]+)$/))) return ok(res, runDetail(m[1]));
          if (path === '/trader/factors') return ok(res, traderFactors());
          if (path === '/trader/sizing') return ok(res, traderSizing());
          return ok(res, null);
        }

        // ---- 动手的接口：能记的记在内存里，后台任务一律回"起了" ----
        const b = await body(req);
        if ((m = path.match(/^\/candidates\/([^/]+)\/(approve|decline)$/))) {
          const item = observation().items.find(x => x.id === m![1]);
          if (item) observing = observing.filter(n => n !== item.name);
          return ok(res, m[2] === 'approve' ? { id: m[1], name: item?.name ?? '' } : { id: m[1] });
        }
        if ((m = path.match(/^\/library\/f-([^/]+)\/(retire|restore|recheck)$/))) {
          const name = m[1];
          if (m[2] === 'recheck') return ok(res, recheck(seed(name), Date.now() - 30 * DAY));
          retiredState[name] = m[2] === 'retire' ? { at: 0, reason: String(b.reason ?? '') } : null;
          return ok(res, { id: `f-${name}` });
        }
        if ((m = path.match(/^\/trader\/factors\/f-([^/]+)$/))) {
          if (b.selected) selected.add(m[1]);
          else selected.delete(m[1]);
          return ok(res, { id: `f-${m[1]}`, selected: !!b.selected });
        }
        if (path === '/trader/carry') {
          if (typeof b.enabled === 'boolean') carry.enabled = b.enabled;
          if (typeof b.share === 'number') carry.share = b.share;
          carry.changedAt = Date.now();
          return ok(res, carry);
        }
        if (path === '/trader/sizing') {
          if (Number(b.margin) > 0.8) return fail(res, 422, '保证金比例最多 80%');
          sizing = { leverage: Number(b.leverage), margin: Number(b.margin), changedAt: Date.now() };
          return ok(res, traderSizing());
        }
        if (path === '/eval') {
          evalJob = { running: true, startedAt: Date.now(), finishedAt: null, expr: String(b.expr ?? ''), stage: 'evaluating', error: null };
          return ok(res, { started: true });
        }
        if ((m = path.match(/^\/models\/(gen|review)(\/test)?$/))) {
          if (m[2]) return ok(res, { reply: '收到，连得上。', ms: 1840 });
          models[m[1]] = { ...b, apiKey: undefined, apiKeyTail: String(b.apiKey || '****').slice(-4), updatedAt: Date.now() };
          return ok(res, models[m[1]]);
        }
        if (path === '/policy') {
          policy = {
            ...policy, ...(b as Partial<typeof policy>), id: policy.id + 1, createdAt: Date.now(),
            history: [{ id: policy.id + 1, createdAt: Date.now() }, ...policy.history],
          };
          return ok(res, policy);
        }
        if (path === '/standard') {
          sharpeGainMin = Number(b.sharpeGainMin);
          standardId += 1;
          return ok(res, standard());
        }
        if (path === '/campaigns') return fail(res, 409, '已经有一个 campaign 在跑');
        if ((m = path.match(/^\/campaigns\/([^/]+)\/stop$/))) return ok(res, { stopping: true });
        return ok(res, { started: true, total: 0 });
      });
    },
  };
}
