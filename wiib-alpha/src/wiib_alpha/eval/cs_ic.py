"""截面 IC：每个网格时刻 t 跨在池币算 Spearman(因子, 之后 h 根的收益)，月度 IC = 当月各 t 的均值。

只用因子、答案都有数的 (t, 币)；有数的币不到 min_names 个的时刻整个丢掉（2021 年初池子里只有 30 个）。
网格点按全局 h 档网格编号，切窗按编号切，所以样本外区间、四段都是时间上的划分。
分组单调性：每个 t 按因子从小到大分组，收益截尾、减掉当期均值后各组取平均，跨 t 再平均；
G2 只要顶组减底组每期之差显著、和 IC 同向，三组的形状（按序排开、只有一头有效、中间反了）只做标签。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from ..data.binance import BAR_MS
from ..data.pool import month_label, month_of, on_grid
from .params import EvalParams
from .stats import spearman, t_stat

# 相邻两组每期之差的 t 到这个数算分得开，三组形状的标签按它判
SEPARATED_T = 2.0
# 三组的形状，按 IC 方向从弱的一头看到强的一头
SHAPES = {
    "monotone": "三组按序排开",
    "weakEnd": "只有弱的一头有效（中、强两组分不开）",
    "strongEnd": "只有强的一头有效（弱、中两组分不开）",
    "flat": "相邻两组都分不开，只有两头拉得开",
    "midHigh": "中间组显著强过强的一头",
    "midLow": "中间组显著弱过弱的一头",
}


@dataclass(frozen=True)
class Points:
    """一档 h 的有效样本，一行一个 (t, 币)，按 (g, 币) 排；g 是 t 在这一档网格上的序号"""
    g: np.ndarray
    sym: np.ndarray
    tier: np.ndarray
    f: np.ndarray
    y: np.ndarray
    # 持有期内结算的资金费之和，只给多空腿用；IC 与单调性只看价格答案 y
    fund: np.ndarray
    # 每个网格点的收盘时刻与月份，长度是网格点总数
    times: np.ndarray
    months: np.ndarray

    @property
    def total(self) -> int:
        return len(self.times)


def grid_index(t: np.ndarray, bars: int, first: int) -> np.ndarray:
    """落在 h 档网格上的时刻 → 从 first 起数的网格序号"""
    return ((t + 1) // BAR_MS - (first + 1) // BAR_MS) // bars


def points(rows: pd.DataFrame, f: np.ndarray, bars: int, min_names: int) -> Points:
    """rows 是骨架（t、sym、tier、y{bars}、fund{bars}），f 与它对齐"""
    t = rows["t"].to_numpy(dtype="int64")
    on = on_grid(t, bars)
    first, last = int(t[on].min()), int(t[on].max())
    times = np.arange(first, last + 1, bars * BAR_MS, dtype="int64")
    g = grid_index(t[on], bars, first)
    ff = np.asarray(f, dtype="float64")[on]
    y = rows[f"y{bars}"].to_numpy(dtype="float64")[on]
    ok = np.isfinite(ff) & np.isfinite(y)
    ok &= np.bincount(g[ok], minlength=len(times))[g] >= min_names
    sym, tier = rows["sym"].to_numpy()[on][ok], rows["tier"].to_numpy()[on][ok]
    fund = rows[f"fund{bars}"].to_numpy(dtype="float64")[on][ok]
    g, ff, y = g[ok], ff[ok], y[ok]
    order = np.lexsort((sym, g))
    return Points(g=g[order], sym=sym[order], tier=tier[order], f=ff[order], y=y[order], fund=fund[order],
                  times=times, months=month_of(times))


def ranks(g: np.ndarray, x: np.ndarray, method: str = "average") -> np.ndarray:
    """每个网格点内的秩，1 起；NaN 还是 NaN"""
    return pd.Series(x).groupby(g).rank(method=method).to_numpy()


def corr_by(g: np.ndarray, x: np.ndarray, y: np.ndarray, total: int) -> tuple[np.ndarray, np.ndarray]:
    """每个网格点内 x 与 y 的 Pearson 和参与的行数；没波动的点是 NaN"""
    n = np.bincount(g, minlength=total).astype("float64")
    sx, sy = np.bincount(g, x, total), np.bincount(g, y, total)
    with np.errstate(invalid="ignore", divide="ignore"):
        cov = np.bincount(g, x * y, total) - sx * sy / n
        vx = np.bincount(g, x * x, total) - sx * sx / n
        vy = np.bincount(g, y * y, total) - sy * sy / n
        out = cov / np.sqrt(vx * vy)
    out[~((vx > 1e-12) & (vy > 1e-12))] = np.nan
    return out, n


def series(p: Points) -> np.ndarray:
    """每个网格点一个截面 rank IC，没样本的是 NaN"""
    return corr_by(p.g, ranks(p.g, p.f), ranks(p.g, p.y), p.total)[0]


def _by_month(ic: np.ndarray, months: np.ndarray, min_points: int) -> tuple[np.ndarray, np.ndarray]:
    """够数的月（1970-01 起数）与各月 IC 均值"""
    ok = np.isfinite(ic)
    if not ok.any():
        return np.empty(0, dtype="int64"), np.empty(0)
    base = int(months[0])
    code = months[ok] - base
    n = np.bincount(code)
    s = np.bincount(code, ic[ok])
    keep = np.flatnonzero(n >= min_points)
    return keep + base, s[keep] / n[keep]


def monthly(ic: np.ndarray, months: np.ndarray, min_points: int) -> pd.Series:
    """当月各网格点 IC 的均值；有效点不到 min_points 的月不算。index 是 YYYY-MM"""
    keys, means = _by_month(ic, months, min_points)
    return pd.Series(means, index=[month_label(k) for k in keys], dtype="float64")


def trained(ic: np.ndarray, months: np.ndarray, end: int, min_points: int) -> float:
    """[0, end) 里的月度 IC 均值：多空腿的方向、组合的权重都从这里学"""
    means = _by_month(ic[:end], months[:end], min_points)[1]
    return float(means.mean()) if len(means) else np.nan


def segments(ic: np.ndarray, k: int, min_points: int) -> list[float]:
    """有 IC 的网格点范围按时间等分 k 段，每段 IC 均值"""
    idx = np.flatnonzero(np.isfinite(ic))
    if not len(idx):
        return [np.nan] * k
    edges = np.linspace(idx[0], idx[-1] + 1, k + 1)
    out = []
    for i in range(k):
        part = ic[idx[(idx >= edges[i]) & (idx < edges[i + 1])]]
        out.append(float(part.mean()) if len(part) >= min_points else np.nan)
    return out


def monotonic(p: Points, lo: int, hi: int, groups: int, trim: float) -> dict[str, Any]:
    """[lo, hi) 里每个网格点按因子从小到大分 groups 组。收益每期先截在当期 trim ~ 1 − trim 分位之间、再减当期均值，
    组均值是跑赢当期平均多少；groups 是各组跨点的平均，spreadT 是顶组减底组每期之差的 t，
    steps 是相邻两组每期之差的 t（从小到大），ρ 是组均值与组序的 Spearman"""
    inside = (p.g >= lo) & (p.g < hi)
    if not inside.any():
        return {"rho": np.nan, "spread": np.nan, "spreadT": np.nan, "steps": [np.nan] * (groups - 1),
                "groups": [np.nan] * groups}
    g = p.g[inside]
    n = np.bincount(g)[g]
    # 平均秩 − 0.5 再除以币数落在 (0, 1)，乘组数取整就是第几组；并列的落同一组
    group = ((ranks(g, p.f[inside]) - 0.5) / n * groups).astype("int64")
    key = g * groups + group
    cells = (g.max() + 1) * groups
    size = np.bincount(key, minlength=cells).reshape(-1, groups)
    total = np.bincount(key, _trimmed_excess(g, np.expm1(p.y[inside]), trim), minlength=cells).reshape(-1, groups)
    # 每期每组的均值；因子大面积并列时有的组是空的，记 NaN
    cell = np.where(size > 0, total / np.maximum(size, 1), np.nan)
    count = (size > 0).sum(axis=0)
    means = np.where(count > 0, np.nansum(cell, axis=0) / np.maximum(count, 1), np.nan)
    return {"rho": spearman(np.arange(groups), means), "spread": float(means[-1] - means[0]),
            "spreadT": t_stat(cell[:, -1] - cell[:, 0]),
            "steps": [t_stat(cell[:, j + 1] - cell[:, j]) for j in range(groups - 1)],
            "groups": [float(v) for v in means]}


def _trimmed_excess(g: np.ndarray, r: np.ndarray, trim: float) -> np.ndarray:
    """每期把收益截在当期 trim 与 1 − trim 分位之间，再减当期均值"""
    by = pd.Series(r).groupby(g)
    clipped = np.clip(r, by.transform("quantile", trim).to_numpy(), by.transform("quantile", 1 - trim).to_numpy())
    return clipped - pd.Series(clipped).groupby(g).transform("mean").to_numpy()


def shape(mono: dict[str, Any], ic: float | None) -> str | None:
    """三组的形状（SHAPES 的键），只做标签。ic 是这一档的 IC：负的时候因子大的那头才是弱的一头；
    老结果里没有 steps、落过库的 NaN 成了 None，算不出来是 None"""
    steps = mono.get("steps") or []
    finite = [v is not None and np.isfinite(v) for v in (*steps, ic)]
    if len(steps) != 2 or not all(finite) or ic == 0:
        return None
    low, high = (steps[0], steps[1]) if ic > 0 else (-steps[1], -steps[0])
    if high <= -SEPARATED_T:
        return "midHigh"
    if low <= -SEPARATED_T:
        return "midLow"
    return {(True, True): "monotone", (True, False): "weakEnd", (False, True): "strongEnd",
            (False, False): "flat"}[(low >= SEPARATED_T, high >= SEPARATED_T)]


def summary(p: Points, ic: np.ndarray, lo: int, hi: int, params: EvalParams) -> dict[str, Any]:
    """只看 [lo, hi) 的网格点（样本外，或 holdout 之后）；recent 是其中 params.recent_from 起的那段，口径一样"""
    part = np.full(p.total, np.nan)
    part[lo:hi] = ic[lo:hi]
    recent = np.where(p.times >= params.recent_from, part, np.nan)
    table = monthly(part, p.months, params.min_points)
    return {
        **_stats(part, table),
        "monthly": {m: float(v) for m, v in table.items()},
        "segments": segments(part, params.segments, params.min_points),
        "monotonic": monotonic(p, lo, hi, params.groups, params.trim),
        "recent": _stats(recent, monthly(recent, p.months, params.min_points)),
    }


def _stats(part: np.ndarray, table: pd.Series) -> dict[str, Any]:
    """一段网格点 IC 的汇总：table 是它的月度均值"""
    mean = float(table.mean()) if len(table) else np.nan
    std = float(table.std(ddof=1)) if len(table) > 1 else np.nan
    return {
        "mean": mean,
        "icir": float(mean / std) if np.isfinite(std) and std > 0 else np.nan,
        # 月度 IC 均值的标准误
        "se": float(std / np.sqrt(len(table))) if np.isfinite(std) else np.nan,
        # 逐网格点 IC 的均值 ÷ 标准误：不按月分，几个月的短段也算得出，入库条件看它
        "t": t_stat(part),
        "months": int(len(table)),
        "samples": int(np.isfinite(part).sum()),
    }


def compact(summary_: dict[str, Any]) -> dict[str, Any]:
    """holdout 和入库后复查只要这几个数"""
    return {"mean": summary_["mean"], "icir": summary_["icir"], "se": summary_["se"], "t": summary_["t"],
            "months": summary_["months"],
            "samples": summary_["samples"], "monotonic": summary_["monotonic"]["rho"]}
