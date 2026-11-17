/**
 * 量化策略的展示口径：名字、说明。
 * <p>
 * 后端只认 id（回测提交、策略账户、信号快照都按它走），给人看的字一律在这儿按词表现查——
 * 策略账户页与回测配置台共用同一份，同一个策略不会在两处叫两个名字。
 * <p>
 * 说明分两档，因为两处问的不是同一件事：账户页那行答"这是什么"，
 * 回测配置台那张卡答"我要跑的到底是什么规则"（周期/进出场/过滤都得写清）。
 */
export interface StrategyDisplay {
  /** 与 TradingStrategySpi.id() 同值 */
  id: string;
  nameKey: string;
  /** 一句话说明（策略账户页） */
  descKey: string;
  /** 机制说明（回测配置台） */
  mechKey: string;
}

/** 数组顺序即回测配置台的排布顺序 */
export const STRATEGIES: StrategyDisplay[] = [
  { id: 'FIBO', nameKey: 'strategies.name.fibo', descKey: 'strategies.desc.fibo', mechKey: 'backtest.strategy.fibo' },
  { id: 'TURTLE', nameKey: 'strategies.name.turtle', descKey: 'strategies.desc.turtle', mechKey: 'backtest.strategy.turtle' },
  { id: 'SQZMOM', nameKey: 'strategies.name.sqzmom', descKey: 'strategies.desc.sqzmom', mechKey: 'backtest.strategy.sqzmom' },
];

const BY_ID: Record<string, StrategyDisplay> =
  Object.fromEntries(STRATEGIES.map(s => [s.id, s]));

/** 查不到=后端新增了策略但前端还没登记，调用方自己退化成显示 id */
export function strategyDisplay(id: string): StrategyDisplay | undefined {
  return BY_ID[id];
}
