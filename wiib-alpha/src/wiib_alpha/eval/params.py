"""评估参数与硬门阈值。

EvalParams 的切窗数字是 Java EvalParams.defaults() 的 5m 数字换算到 15m，单位是 bar；截面评估按 h 换成网格点数。
Thresholds 是硬门默认值，按截面版校准（calibrate.py）定的初值。
"""

from __future__ import annotations

from dataclasses import dataclass

from ..dsl import COMPLEXITY

BARS_PER_HOUR = 4
HOURS_PER_YEAR = 8760


@dataclass(frozen=True)
class EvalParams:
    test_size: int = 192        # 2 天
    embargo: int = 4            # 1 小时
    min_train: int = 2880       # 30 天
    iterations: int = 1000      # 排列次数
    seed: int = 42
    # 单边成本 bps，按流动性档：当月排名 1~20 / 21~50 / 其余
    cost_tiers_bps: tuple[float, float, float] = (5.0, 8.0, 12.0)
    primary: int = 96           # 主档 24h，和交易方案的调仓周期一样
    lookahead_cuts: int = 20    # 前视自检抽的币数，每币截一刀
    min_points: int = 8         # 一个月/一段少于这么多有效时刻（时序报告是非重叠点）不算 IC
    min_names: int = 20         # 一个时刻有数的币少于这么多就跳过
    groups: int = 3             # 分组单调性的组数
    trim: float = 0.05          # 分组单调性：每期收益两头各截掉这么多，一两个暴涨暴跌的币拉不动组均值
    leg_enter: float = 0.2      # 多空腿：排进头部这么多才做多，空头看尾部
    leg_exit: float = 0.4       # 掉出头部这么多才平
    leg_cap: float = 2.0        # 按波动分仓时单个币最多是这一边平均仓位的这么多倍；inf 是不设
    segments: int = 4
    holdout_days: int = 180     # 最近这么多天锁成 holdout：门和模型都看不到，过门后只算一次 IC 给人看
    # IC、多空腿、G9 增量各再算一份只看这天（UTC 毫秒，2022-01-01）起的，和全段并排看：2021 年的行情太特殊，会把全段数字撑大
    recent_from: int = 1_640_995_200_000

    @property
    def costs(self) -> tuple[float, float, float]:
        """三档单边成本，小数"""
        return tuple(c / 1e4 for c in self.cost_tiers_bps)

    @property
    def ts_cost(self) -> float:
        """时序报告的方向腿每笔往返：产品币都按一档算，进出各付一次单边"""
        return 2 * self.cost_tiers_bps[0] / 1e4

    def grid(self, horizon_bars: int) -> tuple[int, int, int]:
        """切窗换成 h 档网格点数：(test, embargo, min_train)；purge 是 h 根，正好一个点"""
        return (max(1, self.test_size // horizon_bars), -(-self.embargo // horizon_bars),
                -(-self.min_train // horizon_bars))


@dataclass(frozen=True)
class Thresholds:
    min_samples: int = 300              # G1：24h 档样本外有效时刻数
    ic_min: float = 0.01                # G2：主档 |月度截面 IC 均值|
    t_min: float = 3.0                  # G2：主档 |ICIR| × √月数，月度 IC 均值的 t
    spread_t_min: float = 2.0           # G2：截尾后顶组减底组每期之差的 t，得和 IC 同向
    min_segments: int = 3               # G3：四段里同号的段数，最后一段必须同号
    perm_pct: float = 0.95              # G5：截面排列分位
    fdr_q: float = 0.10                 # G5：同批 BH-FDR
    sharpe_gain_min: float = 0.10       # G9：加进交易方案后搜索段年化净 Sharpe 至少升这么多；随机因子 95% 升不到（calibrate --g9）
    max_redundancy: float = 0.7         # G6
    # 入库：它没见过那段（发现那轮的切点起）主档截面 IC 至少这么多个时刻、|均值 / 标准误| 至少这么大，且和搜索段同号
    admit_min_points: int = 150
    admit_t_min: float = 2.0
    max_nodes: int = COMPLEXITY["nodes"]        # G7：解析时已经卡住，这里只做报告
    max_windows: int = COMPLEXITY["windows"]
    max_constants: int = COMPLEXITY["constants"]
    max_cross: int = COMPLEXITY["cross"]


def periods_per_year(horizon_bars: int) -> float:
    return HOURS_PER_YEAR / (horizon_bars / BARS_PER_HOUR)
