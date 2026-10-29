"""交易方案的回测：组合腿和基差 carry 腿按 carry 份额分资金（页面上设，默认 1/4），24h 调仓、每边按波动分仓、晚一根成交（选方案时那段脚本收进来）。

骨架就是挖掘装载的那份（晚一根成交：信号那根收盘后再过一根才建仓，资金费从建仓那刻起算）。
组合腿：勾选因子先转截面秩再 z 分数，按 24h 档学到的权重加权（combo 那一套：符号跟 IC，大小是单独做多空腿的净收益，
不赚钱的是 0）；搜索段 walk-forward 学权重和方向，holdout 用搜索段整段定权重、方向定 +1。carry 腿：basis_7d，方向定死 −1（做空溢价高的），
上市不满 CARRY_MIN_DAYS 天的币不碰（carry_signal 把它们当缺值；币龄按归档第一根 K 线算，Pool.listed）。
两腿都按缓冲带选币（cs_leg.weights），每边按 1/Std($ret, 672) 分仓、单币不超过这一边平均的 leg_cap 倍，按档扣成本、扣资金费；每期净收益按两腿的份额合成。
交易员的组合权重也从这里学（learned_weights）：全部历史、同一套骨架和函数。
挖掘的 G9 也用它：库里的因子加基差 carry 是基线（Base），候选加进组合腿后搜索段净 Sharpe 升多少（gain）。
python -m wiib_alpha.trade.scheme 在当前数据上把勾选因子的方案跑一遍，打印搜索段和 holdout。
"""

from __future__ import annotations

import argparse
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from .. import reference
from ..config import config
from ..data.panel import DELAY, FEATURE_COLUMNS, HORIZON_BARS, PanelStore, holdout_cut
from ..data.pool import Pool
from ..dsl import parse
from ..eval import combo, cs_ic, cs_leg
from ..eval.horizon import grid_windows
from ..eval.params import EvalParams, periods_per_year
from ..eval.stats import annualized_sharpe, compound
from ..store import snapshot

# 24h 调仓
BARS = HORIZON_BARS[24]
# carry 腿占净值的比例，没设过是这个数，组合腿占其余；线上的份额存 trade_carry.share
CARRY_SHARE = 0.25
CARRY = parse(reference.KNOWN_EFFECTS["basis_7d"][0], FEATURE_COLUMNS)
# carry 腿不碰上市不满这么多天的币
CARRY_MIN_DAYS = 365
VOL = parse("Std($ret, 672)", FEATURE_COLUMNS)


def carry_signal(pool: Pool) -> np.ndarray:
    """全量骨架上 carry 腿的信号：basis_7d，上市不满 CARRY_MIN_DAYS 天的币当缺值"""
    f = pool.values(CARRY).f
    f[pool.age_days(pool.rows) < CARRY_MIN_DAYS] = np.nan
    return f


@dataclass(frozen=True)
class Leg:
    """一条腿每个网格点一期：净收益、资金费（正数是收）、Σ|Δw|；active 是这一腿算数的点，recent 是 params.recent_from 起的点"""
    net: np.ndarray
    funding: np.ndarray
    traded: np.ndarray
    active: np.ndarray
    recent: np.ndarray


def positions(p: cs_ic.Points, vol: np.ndarray, direction: np.ndarray, params: EvalParams) -> np.ndarray:
    """缓冲带选币、每边按 1/波动分仓（单币有上限）；vol 与 p 的行对齐"""
    w = cs_leg.weights(p, direction, params.leg_enter, params.leg_exit)
    return cs_leg.inverse_vol(p.g, w, vol, p.total, params.leg_cap)


def leg(rows: pd.DataFrame, f: np.ndarray, vol: np.ndarray, params: EvalParams, fixed: float | None,
        since: int | None) -> Leg:
    """f、vol 与 rows 对齐，两样都有数的才进；fixed 是定死的方向（None 按 walk-forward 学），since 给了只算它之后的点（holdout）"""
    p, pv = cs_leg.sized(rows, f, vol, BARS, params.min_names)
    if since is not None:
        active = p.times > since
        direction = np.where(active, fixed, 0.0)
    else:
        wins = grid_windows(p.total, BARS, params)
        active = np.zeros(p.total, dtype=bool)
        for w in wins:
            active[w.test_start:w.test_end] = True
        direction = (np.where(active, fixed, 0.0) if fixed is not None
                     else cs_leg.directions(cs_ic.series(p), p.months, wins, params.min_points))
    w = positions(p, pv, direction, params)
    cost, traded = cs_leg.rebalance(p, w, params.costs)
    # 一个币持有一期的收益：涨跌减资金费，空头仓位 w < 0 正好变成收费
    r = np.expm1(p.y) - p.fund
    return Leg(np.bincount(p.g, w * r, p.total) - cost, -np.bincount(p.g, w * p.fund, p.total), traded, active,
               p.times >= params.recent_from)


def combo_signal(rows: pd.DataFrame, symbols: tuple[str, ...], values: Sequence[np.ndarray], vol: np.ndarray,
                 params: EvalParams, cut_ms: int | None, known: combo.Tracked | None = None) -> np.ndarray:
    """组合腿的信号，与 rows 对齐：cut_ms 不给是 walk-forward（只在 test 块有值），给了是搜索段整段定权重、只在 cut 之后有值；
    一条因子都没有是全 NaN，组合腿空仓。known 是算好的 tracks()（walk-forward 才用）"""
    if not len(values):
        return np.full(len(rows), np.nan)
    c = combo.Combo(rows, symbols, tuple(map(str, range(len(values)))), np.column_stack(values))
    z = combo.zscores(c)
    if cut_ms is None:
        return combo.signal(c, z, vol, BARS, params, known)
    return combo.holdout_signal(c, z, vol, BARS, cut_ms, params)


def backtest(pool: Pool, values: Sequence[np.ndarray], basis: np.ndarray, vol: np.ndarray, params: EvalParams,
             holdout: bool, carry_share: float) -> dict[str, Any]:
    """values（每条勾选因子）、basis、vol 是全量骨架上的值。搜索段只用 cut 之前的数据走 walk-forward，holdout 只看 cut 之后"""
    if holdout:
        return run(pool.rows, pool.symbols, values, basis, vol, params, pool.cut_ms, carry_share)
    pick = pool.in_search
    return run(pool.search, pool.symbols, [v[pick] for v in values], basis[pick], vol[pick], params, None, carry_share)


def run(rows: pd.DataFrame, symbols: tuple[str, ...], values: Sequence[np.ndarray], basis: np.ndarray, vol: np.ndarray,
        params: EvalParams, since: int | None, carry_share: float, known: combo.Tracked | None = None,
        carry: Leg | None = None) -> dict[str, Any]:
    """values、basis、vol 与 rows 对齐；since 不给是 walk-forward，给了是 holdout（只算它之后、组合腿方向定 +1）；
    carry_share 是 carry 腿占的资金比例，组合腿占其余。出净收益、年化 Sharpe、最大回撤、换手、carry 腿每期资金费，
    再加 params.recent_from 起那段的净收益和 Sharpe。known 是算好的 tracks()，carry 是算好的 carry 腿（同一份 rows、since）"""
    signal = combo_signal(rows, symbols, values, vol, params, since, known)
    a = leg(rows, signal, vol, params, None if since is None else 1.0, since)
    b = carry if carry is not None else leg(rows, basis, vol, params, -1.0, since)
    active = a.active & b.active
    every = (1 - carry_share) * a.net + carry_share * b.net
    net = every[active]
    recent = every[active & a.recent]
    traded = ((1 - carry_share) * a.traded + carry_share * b.traded)[active]
    curve = np.cumprod(np.concatenate(([1.0], 1 + net)))
    return {
        "periods": int(active.sum()),
        "netReturn": compound(net),
        "sharpe": annualized_sharpe(net, periods_per_year(BARS)),
        "maxDrawdown": float(np.max(1 - curve / np.maximum.accumulate(curve))),
        # Σ|Δw| / 4：两条腿各自整个换掉是 1
        "turnover": float(np.mean(traded) / 4) if net.size else np.nan,
        # carry 腿每期平均资金费 bps，正数是收
        "carryFundingBps": float(np.mean(b.funding[active]) * 1e4) if net.size else np.nan,
        "recentFrom": params.recent_from,
        "recentNetReturn": compound(recent),
        "recentSharpe": annualized_sharpe(recent, periods_per_year(BARS)),
        "carryShare": carry_share,
    }


@dataclass(frozen=True)
class Base:
    """G9 的基线：库里的因子（combo，搜索段）加基差 carry 的交易方案，按 params 和 carry 份额算；basis、vol 与 combo.rows 对齐，
    result 是它的回测。tracks 是库里每条的 Track、points 是网格样本、carry 是 carry 腿，算一次存着：plus、without 只算变的那条"""
    combo: combo.Combo
    basis: np.ndarray
    vol: np.ndarray
    params: EvalParams
    # carry 腿占的资金比例
    carry_share: float
    tracks: list[combo.Track]
    # 最后一条的样本（网格点的时刻、月份），库空着是 None
    points: cs_ic.Points | None
    carry: Leg
    result: dict[str, Any]

    @classmethod
    def of(cls, library: combo.Combo, basis: np.ndarray, vol: np.ndarray, params: EvalParams, carry_share: float) -> "Base":
        trs, p = combo.tracks(library, vol, BARS, params) if len(library.names) else ([], None)
        return cls._make(library, basis, vol, params, carry_share, trs, p, leg(library.rows, basis, vol, params, -1.0, None))

    @classmethod
    def _make(cls, c: combo.Combo, basis: np.ndarray, vol: np.ndarray, params: EvalParams, carry_share: float,
              trs: list[combo.Track], p: cs_ic.Points | None, carry: Leg) -> "Base":
        result = run(c.rows, c.symbols, [c.values[:, j] for j in range(len(c.names))], basis, vol, params, None, carry_share,
                     (trs, p), carry)
        return cls(c, basis, vol, params, carry_share, trs, p, carry, result)

    def plus(self, name: str, f: np.ndarray) -> "Base":
        """组合腿再加一条（f 与 rows 对齐），只算新的那条"""
        c = self.combo.plus(name, f)
        trs, p = combo.tracks(c, self.vol, BARS, self.params, self.tracks)
        return Base._make(c, self.basis, self.vol, self.params, self.carry_share, trs, p, self.carry)

    def without(self, j: int) -> "Base":
        """拿掉第 j 条的方案，别的照用"""
        c = combo.Combo(self.combo.rows, self.combo.symbols, self.combo.names[:j] + self.combo.names[j + 1:],
                        np.delete(self.combo.values, j, axis=1))
        return Base._make(c, self.basis, self.vol, self.params, self.carry_share,
                          [t for k, t in enumerate(self.tracks) if k != j], self.points, self.carry)


def gain(base: Base, name: str, f: np.ndarray) -> dict[str, Any]:
    """G9：f 加进组合腿后，交易方案搜索段的年化净 Sharpe、净收益比基线变了多少，recentSharpe 是只看 recent_from 起的那份；
    Sharpe 算不出来（一期都没有）按 0"""
    after = base.plus(name, f).result

    def delta(key: str) -> dict[str, float]:
        before, with_ = float(np.nan_to_num(base.result[key])), float(np.nan_to_num(after[key]))
        return {"base": before, "with": with_, "gain": with_ - before}

    return {"sharpe": delta("sharpe"), "recentSharpe": delta("recentSharpe"), "recentFrom": base.params.recent_from,
            "carryShare": base.carry_share, "netReturn": {"base": base.result["netReturn"], "with": after["netReturn"]}}


def learned_weights(pool: Pool, values: Sequence[np.ndarray], vol: np.ndarray, params: EvalParams) -> np.ndarray:
    """交易员的组合权重：全部历史当训练窗，24h 档、晚一根成交的答案学（combo.learned）；values、vol 与 pool.rows 对齐，至少一条"""
    c = combo.Combo(pool.rows, pool.symbols, tuple(map(str, range(len(values)))), np.column_stack(values))
    return combo.learned(c, vol, BARS, params)


def learned_signs(pool: Pool, values: Sequence[np.ndarray], params: EvalParams) -> np.ndarray:
    """看板用的方向：全部历史、24h 档学的 IC 符号（combo.learned_signs）；values 与 pool.rows 对齐，至少一条"""
    c = combo.Combo(pool.rows, pool.symbols, tuple(map(str, range(len(values)))), np.column_stack(values))
    return combo.learned_signs(c, BARS, params)


# ========== 命令行 ==========


def read_db(path: str) -> tuple[pd.DataFrame, list[dict[str, Any]], float]:
    """币池表、交易员勾选且还在库里的因子、carry 腿的份额"""
    with snapshot(path) as db:
        universe = pd.read_sql("SELECT month, symbol, rank, avg_quote_volume FROM universe", db)
        factors = [{"id": r[0], "name": r[1], "expr": r[2]} for r in db.execute(
            """SELECT f.id, f.name, f.expr FROM factor f JOIN trade_factor t ON t.factor_id = f.id
               ORDER BY f.approved_at DESC""")]
        row = db.execute("SELECT share FROM trade_carry WHERE id = 1").fetchone()
    return universe, factors, float(row[0]) if row else CARRY_SHARE


def _line(label: str, r: dict[str, Any]) -> str:
    return (f"{label}：净收益 {r['netReturn']:+.3f}  Sharpe {r['sharpe']:+.2f}  最大回撤 {r['maxDrawdown']:.1%}  "
            f"换手 {r['turnover']:.3f}  carry 资金费每期 {r['carryFundingBps']:+.2f} bps（{r['periods']} 期）")


def main() -> None:
    parser = argparse.ArgumentParser(description="交易方案回测：勾选因子的组合 + 基差 carry，24h，按波动")
    parser.add_argument("--delay", type=int, default=DELAY, help="晚几根成交，0 是按信号那根收盘成交")
    args = parser.parse_args()
    cfg = config()
    params = EvalParams(cost_tiers_bps=cfg.cost_tiers_bps)
    universe, factors, share = read_db(cfg.db_path)
    store = PanelStore(cfg.data_dir)
    # 切点与服务装载时一样：产品币面板最后一根往前数 holdout_days 天
    cut = holdout_cut({s: store.load(s, ["close"]) for s in cfg.symbols if store.has(s)}, params.holdout_days)
    pool = Pool.load(store, universe, cut, args.delay)
    print(f"因子：{', '.join(f['name'] for f in factors) or '（没有勾选，组合腿空仓）'}；carry 腿占 {share:.0%}；晚 {args.delay} 根成交",
          flush=True)
    values = [pool.values(parse(f["expr"], FEATURE_COLUMNS)).f for f in factors]
    basis, vol = carry_signal(pool), pool.values(VOL).f
    print(_line("搜索段（walk-forward 样本外）", backtest(pool, values, basis, vol, params, False, share)), flush=True)
    print(_line(f"holdout（{np.datetime64(cut, 'ms')} 之后）", backtest(pool, values, basis, vol, params, True, share)))


if __name__ == "__main__":
    main()
