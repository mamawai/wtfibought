"""一条表达式的完整截面评估：全池求值 → 前视自检 → 每档 h 的截面 IC、单调性与多空腿 → 冗余 → 去老效应后的 IC（标签，不做门）
→ 复杂度 → 加进交易方案的增量，再附 7 个产品币的时序报告（不做门）。

门只看搜索段（pool.search）。输出是可直接落库/下发的 dict，判门在 gates.py。
冗余、去老效应的截面秩在参照网格那一档（12h）上比，和主档是哪一档无关。
"""

from __future__ import annotations

from typing import Any, Mapping

import numpy as np
import pandas as pd

from .. import reference
from ..data.panel import HORIZON_BARS
from ..data.pool import Pool, Values, on_grid, release_memory
from ..dsl import Expr, lookahead_free
from ..reference import GRID_BARS, Grid
from ..trade import scheme
from . import combo, cs_ic, timeseries
from .horizon import grid_windows, horizon, oos
from .params import EvalParams
from .recheck import recheck

# 去老效应时回归掉的：全部老效应加 24h 反转；5 条手写基线不算，换皮由 G6 挡
KNOWN = (*reference.KNOWN_EFFECTS, reference.REVERSAL)


def evaluate(expr: Expr, pool: Pool, vol: np.ndarray, params: EvalParams = EvalParams(),
             reference_grid: Grid | None = None, library: scheme.Base | None = None,
             products: dict[str, pd.DataFrame] | None = None, holdout: bool = False,
             observing: combo.Combo | None = None, values: Values | None = None,
             peer_exprs: Mapping[str, str] | None = None) -> dict[str, Any]:
    """vol 是全量骨架上的 Std($ret, 672)，多空腿按它分仓；reference_grid 没算好是 None（G6 不过）；
    library 是 G9 的基线（库里因子 + 基差 carry 的交易方案），不给就不算增量；
    products 是产品币搜索段面板，给了才出时序报告；holdout 给手填评估用，顺手把 cut 之后的 IC 也算了；
    observing 是观察区候选在搜索段上的值，和库里因子一起进 G6 的对比集；
    values 是调用方已经算好的全池值（之后还要用它算 holdout、同批筛查），不给就在这里算；
    peer_exprs 是库里因子、观察区候选的表达式（lib:名字、obs:名字 → 表达式），冗余报最像的几个时带上"""
    if values is None:
        values = pool.values(expr)
    f = values.f[pool.in_search]
    v = vol[pool.in_search]
    out: dict[str, Any] = {
        "expr": str(expr),
        "canonical": expr.canonical(),
        "columns": sorted(expr.columns()),
        "complexity": expr.complexity(),
        "lookaheadFree": _lookahead_free(expr, pool, values, params),
        # 判门用的是哪一档：落过库的老结果没有这一项，是 12h
        "primary": params.primary,
        # IC、多空腿、G9 里 recent 那份从这天起算；老结果没有
        "recentFrom": params.recent_from,
        # 排列检验只给主档做：G5 只看主档，三档全做要多花一倍多的时间
        "horizons": {bars: horizon(pool.search, f, v, bars, params, params.iterations if bars == params.primary else 0)
                     for bars in HORIZON_BARS.values()},
        "redundancy": redundancy(pool.search, f, reference_grid,
                                 {"lib": library.combo if library else None, "obs": observing}, peer_exprs or {},
                                 params),
        "neutral": neutral(pool.search, f, reference_grid, params),
        "gain": scheme.gain(library, str(expr), f) if library is not None else None,
        "timeseries": timeseries.report(expr, products, params, values.at) if products else None,
    }
    if holdout:
        out["holdout"] = recheck(pool.rows, values.f, pool.cut_ms, params)
    release_memory()
    return out


def _lookahead_free(expr: Expr, pool: Pool, values: Values, params: EvalParams) -> bool:
    """抽 lookahead_cuts 个币，每币随机截一刀重算，前面的值必须与全量算出来的一样；截面节点的输入也查"""
    rng = np.random.default_rng(params.seed)
    picks = rng.choice(len(pool.symbols), size=min(params.lookahead_cuts, len(pool.symbols)), replace=False)
    columns = sorted(expr.columns())
    for i in sorted(picks):
        s = pool.symbols[i]
        df = pool.frame(s, columns)
        n = len(df)
        lo = max(10, n // 10)
        c = n if n <= lo + 1 else int(rng.integers(lo, n))
        if not lookahead_free(expr, df, [c], values.at(s)):
            return False
    return True


def redundancy(rows: pd.DataFrame, f: np.ndarray, grid: Grid | None, peers: dict[str, combo.Combo | None],
               exprs: Mapping[str, str], params: EvalParams) -> dict[str, Any]:
    """与参照、库里因子、观察区候选在参照网格那一档上的截面相关，top 是最大的前 3 个带表达式，maxAbs、with 是第一个。
    grid 的行与 rows 里那一档的行对齐；peers 是 前缀 → 一组因子（lib 库里、obs 观察区），名字带前缀报出来；
    exprs 是带前缀的名字 → 表达式，参照集的从 reference.expressions() 取"""
    if grid is None:
        return {"maxAbs": None, "with": None, "ready": False, "top": []}
    main = on_grid(rows["t"].to_numpy(), GRID_BARS)
    g = np.unique(rows["t"].to_numpy()[main], return_inverse=True)[1]
    cand = cs_ic.ranks(g, f[main])
    scores = [(name, _mean_abs_corr(g, cand, grid.ranks[:, j], params)) for j, name in enumerate(grid.names)]
    for prefix, group in peers.items():
        if group is not None:
            scores += [(f"{prefix}:{name}", v) for name, v in _corr_with(g, cand, main, group, params)]
    top = [(name, v) for name, v in sorted(scores, key=lambda x: -x[1])[:3] if v > 0]
    known = {**reference.expressions(), **exprs}
    return {"maxAbs": top[0][1] if top else 0.0, "with": top[0][0] if top else None, "ready": True,
            "top": [{"name": name, "abs": v, "expr": known.get(name)} for name, v in top]}


def neutral(rows: pd.DataFrame, f: np.ndarray, grid: Grid | None, params: EvalParams) -> dict[str, Any] | None:
    """去老效应（标签，不做门）：参照网格那一档上，候选的截面秩对 KNOWN 的截面秩做回归，留残差，残差按单因子同一套切窗算主档截面 IC。
    系数不逐时刻估（每个时刻几十个币对十个回归量，纯随机也会把 IC 压掉一截），按 walk-forward 用训练窗里全部时刻一起估一组，
    用到 test 块上。秩先转成 (0,1] 再在各时刻内去均值（缺的按截面中间 0.5），不同时刻能放一起回归，也不用估截距。
    网格没算好是 None"""
    if grid is None:
        return None
    cols = [j for j, name in enumerate(grid.names) if name in KNOWN]
    t = rows["t"].to_numpy(dtype="int64")
    main = on_grid(t, GRID_BARS)
    tm = t[main]
    # 和 points 同一套网格序号，切窗才对得上
    gi = cs_ic.grid_index(tm, GRID_BARS, int(tm.min()))
    total = int(gi.max()) + 1
    ok = np.isfinite(f[main])
    n = np.bincount(gi[ok], minlength=total).astype("float64")
    with np.errstate(invalid="ignore", divide="ignore"):
        y = cs_ic.ranks(gi, f[main]) / n[gi]
        y -= (np.bincount(gi[ok], y[ok], total) / n)[gi]
    res = np.full(len(y), np.nan)
    order = np.flatnonzero(ok)[np.argsort(gi[ok], kind="stable")]
    if cols and len(order):
        x = np.nan_to_num(grid.ranks[:, cols].astype("float64"), nan=0.5)
        with np.errstate(invalid="ignore", divide="ignore"):
            x -= (np.stack([np.bincount(gi[ok], x[ok, k], total) for k in range(len(cols))], axis=1) / n[:, None])[gi]
        # 每个网格点的 X'X、X'y，按时间累加起来，训练窗 [0, e) 的就是第 e−1 个
        xtx, xty = np.zeros((total, len(cols), len(cols))), np.zeros((total, len(cols)))
        for idx in np.split(order, np.flatnonzero(np.diff(gi[order])) + 1):
            xtx[gi[idx[0]]], xty[gi[idx[0]]] = x[idx].T @ x[idx], x[idx].T @ y[idx]
        xtx, xty = np.cumsum(xtx, axis=0), np.cumsum(xty, axis=0)
        sorted_gi = gi[order]
        for w in grid_windows(total, GRID_BARS, params):
            beta = np.linalg.lstsq(xtx[w.train_end - 1], xty[w.train_end - 1], rcond=None)[0]
            rows_in = order[np.searchsorted(sorted_gi, w.test_start):np.searchsorted(sorted_gi, w.test_end)]
            res[rows_in] = y[rows_in] - x[rows_in] @ beta
    else:
        res[order] = y[order]
    out = np.full(len(rows), np.nan)
    out[main] = res
    # 主档网格是参照网格那一档的子集，只取落在主档上的残差算 IC
    p, ic, _, lo, hi = oos(rows, out, params.primary, params)
    s = cs_ic.summary(p, ic, lo, hi, params)
    return {"mean": s["mean"], "icir": s["icir"], "months": s["months"], "segments": s["segments"], "against": len(cols)}


def cross_corr(rows: pd.DataFrame, f: np.ndarray, others: combo.Combo, params: EvalParams) -> list[tuple[str, float]]:
    """候选与 others 里每一条的截面相关，按列序出 (名字, |ρ|)；同批筛查、观察区两两相关用。模型起的名字会重复，不进字典"""
    main = on_grid(rows["t"].to_numpy(), GRID_BARS)
    g = np.unique(rows["t"].to_numpy()[main], return_inverse=True)[1]
    return _corr_with(g, cs_ic.ranks(g, f[main]), main, others, params)


def _corr_with(g: np.ndarray, cand: np.ndarray, main: np.ndarray, group: combo.Combo,
               params: EvalParams) -> list[tuple[str, float]]:
    return [(name, _mean_abs_corr(g, cand, cs_ic.ranks(g, group.values[main, j]), params))
            for j, name in enumerate(group.names)]


def _mean_abs_corr(g: np.ndarray, a: np.ndarray, b: np.ndarray, params: EvalParams) -> float:
    """两条秩在参照网格每个时刻做 Pearson，取绝对值再跨时刻平均：按时刻翻号的写法照样算重合；有数的币不够 min_names 的时刻不算"""
    ok = np.isfinite(a) & np.isfinite(b)
    corr, n = cs_ic.corr_by(g[ok], a[ok], b[ok].astype("float64"), int(g.max()) + 1)
    corr = corr[(n >= params.min_names) & np.isfinite(corr)]
    return float(np.abs(corr).mean()) if len(corr) else 0.0
