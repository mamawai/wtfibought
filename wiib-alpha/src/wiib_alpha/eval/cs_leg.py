"""多空腿：walk-forward 只学方向，样本外每个网格点按缓冲带持仓，每边按 1/波动分仓、美元中性，持有到下一个网格点再调。

方向 = 训练窗里月度截面 IC 均值的符号。答案是对数收益，先 expm1 成算术收益，再减持有期内结算的资金费（多头付、空头收）。
缓冲带：排进头部 leg_enter 才做多、掉出头部 leg_exit 才平，空头对称看尾部，中间的不动；
方向换号、上一点没仓位或被跳过时从头建仓，本点不在样本里的币自动出场。交易员一天只算一期，用单步版 step（规则同 weights）；
挖掘的腿和交易方案一样按波动分仓（inverse_vol，单币有上限 leg_cap），只用因子和波动都有数的行（sized）。
成本按流动性分档：每次调仓每个名字付 |w_t − w_{t−1}| × 该币当月档的单边成本，名字不换不付；
没仓位的网格点收益是 0，从有仓位变成没仓位那一点付平仓成本。毛收益 = 涨跌 + 资金费，扣交易成本之前；
盈亏平衡成本 = Σ毛收益 / Σ换手量，折成单边 bps。
排列检验：每个 t 内把仓位在币之间打乱、收益不动，看真实毛收益在随机里排第几；同一时刻币数相同的点拼成矩阵一起打乱。
"""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from .cs_ic import Points, points, ranks, trained
from .params import EvalParams, periods_per_year
from .stats import annualized_sharpe, compound
from .walkforward import Window

# 排列检验一块最多这么多个元素（迭代 × 点 × 币），约 32 MB
_CHUNK = 4_000_000


def directions(ic: np.ndarray, months: np.ndarray, wins: list[Window], min_points: int) -> np.ndarray:
    """每个网格点的方向：样本外是所在 test 块训练窗的月度 IC 均值符号，学不出来或不在样本外是 0"""
    out = np.zeros(len(ic))
    for w in wins:
        mean = trained(ic, months, w.train_end, min_points)
        out[w.test_start:w.test_end] = np.sign(mean) if np.isfinite(mean) else 0.0
    return out


def _zones(n: np.ndarray, order: np.ndarray, d: np.ndarray, enter: float,
           exit_: float) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """每行在不在多头进场区、多头留存区、空头进场区、空头留存区；n 是同一时刻的币数，order 是从 1 起的名次"""
    # 多头那头从 1 数起的名次：方向为正时因子大的在头部；空头那头是倒过来数
    head = np.where(d > 0, n - order + 1, order)
    tail = n + 1 - head
    trading = d != 0
    return (trading & (head / n <= enter), trading & (head / n <= exit_),
            trading & (tail / n <= enter), trading & (tail / n <= exit_))


def weights(p: Points, direction: np.ndarray, enter: float, exit_: float) -> np.ndarray:
    """每行的仓位：多头每个 +1/多头数，空头每个 −1/空头数；并列按币序号先后排"""
    n = np.bincount(p.g, minlength=p.total)[p.g]
    order = ranks(p.g, p.f, method="first")
    enter_long, keep_long, enter_short, keep_short = _zones(n, order, direction[p.g], enter, exit_)
    long_, short = enter_long.copy(), enter_short.copy()
    # 上一点每个币是不是在多头 / 空头里，按币序号放
    held_long = np.zeros(int(p.sym.max(initial=-1)) + 1, dtype=bool)
    held_short = held_long.copy()
    starts = np.searchsorted(p.g, np.arange(p.total + 1))
    before = 0.0
    for g in range(p.total):
        a, b = starts[g], starts[g + 1]
        sym = p.sym[a:b]
        sign = np.sign(direction[g]) if a < b else 0.0
        if sign != 0 and sign == before:
            long_[a:b] |= keep_long[a:b] & held_long[sym]
            short[a:b] |= keep_short[a:b] & held_short[sym]
        held_long[:] = False
        held_short[:] = False
        held_long[sym] = long_[a:b]
        held_short[sym] = short[a:b]
        before = sign
    longs = np.bincount(p.g, long_, p.total)[p.g]
    shorts = np.bincount(p.g, short, p.total)[p.g]
    return np.where(long_, 1 / np.maximum(longs, 1), 0.0) - np.where(short, 1 / np.maximum(shorts, 1), 0.0)


def step(f: np.ndarray, sign: float, held_long: np.ndarray, held_short: np.ndarray, enter: float,
         exit_: float) -> tuple[np.ndarray, np.ndarray]:
    """weights 的单步版，给交易员一天算一次：一个时刻按币序号排好的因子、方向，上一期每个币在不在多头 / 空头里，
    出这一期的多头、空头。上一期没仓位或方向换了号，held 传全 False，就是从头建仓"""
    n = len(f)
    order = ranks(np.zeros(n, dtype="int64"), f, method="first")
    enter_long, keep_long, enter_short, keep_short = _zones(np.full(n, n), order, np.full(n, sign), enter, exit_)
    return enter_long | (keep_long & held_long), enter_short | (keep_short & held_short)


def sized(rows: pd.DataFrame, f: np.ndarray, vol: np.ndarray, bars: int, min_names: int) -> tuple[Points, np.ndarray]:
    """多空腿的样本：f、vol 与 rows 对齐，两样都有数的行才进；出样本和与它逐行对齐的波动"""
    p = points(rows, np.where(np.isfinite(vol), f, np.nan), bars, min_names)
    return p, points(rows, np.where(np.isfinite(f), vol, np.nan), bars, min_names).f


def inverse_vol(g: np.ndarray, w: np.ndarray, vol: np.ndarray, total: int, cap: float) -> np.ndarray:
    """按波动分仓：每个点多头每个币 ∝ 1/波动、加起来 1，空头同理加起来 −1；w 只看正负。
    一边 n 个币时单个币最多 cap / n：超出的截掉，按 1/波动摊给没到上限的，摊到没有超的为止"""
    inv = 1.0 / np.maximum(vol, 1e-6)
    out = np.zeros(len(w))
    for s in (1, -1):
        m = np.sign(w) == s
        gm, iv = g[m], inv[m]
        x = iv / np.bincount(gm, iv, total)[gm]
        limit = cap / np.bincount(gm, minlength=total)[gm]
        while (over := x > limit + 1e-12).any():
            excess = np.bincount(gm, np.where(over, x - limit, 0.0), total)[gm]
            x = np.where(over, limit, x)
            free = x < limit - 1e-12
            x = np.where(free, x + excess * iv / np.bincount(gm, np.where(free, iv, 0.0), total)[gm], x)
        out[m] = s * x
    return out


def rebalance(p: Points, w: np.ndarray, unit_costs: tuple[float, float, float]) -> tuple[np.ndarray, np.ndarray]:
    """每个网格点的换仓成本与 Σ|Δw|：本点仓位减上一点仓位，按币当月档的单边成本计"""
    held = w != 0
    now = pd.DataFrame({"g": p.g[held], "sym": p.sym[held], "w": w[held], "tier": p.tier[held]})
    before = now.assign(g=now["g"] + 1)
    both = now.merge(before, on=["g", "sym"], how="outer", suffixes=("", "_prev"))
    delta = (both["w"].fillna(0.0) - both["w_prev"].fillna(0.0)).abs().to_numpy()
    # 离场的币这一点不在样本里，用它上一点的档
    tier = both["tier"].fillna(both["tier_prev"]).to_numpy(dtype="int64")
    unit = np.asarray(unit_costs)[tier - 1]
    g = both["g"].to_numpy(dtype="int64")
    keep = g < p.total
    return np.bincount(g[keep], delta[keep] * unit[keep], p.total), np.bincount(g[keep], delta[keep], p.total)


def fixed(p: Points, vol: np.ndarray, params: EvalParams) -> tuple[np.ndarray, np.ndarray]:
    """方向一直 +1 的多空腿：每个网格点的毛收益（含资金费）与换仓成本，没仓位也没换仓的点是 NaN；
    方向 −1 就是多空两头对调，毛收益取反、成本不变。p、vol 来自 sized"""
    w = inverse_vol(p.g, weights(p, np.ones(p.total), params.leg_enter, params.leg_exit), vol, p.total, params.leg_cap)
    gross = np.bincount(p.g, w * (np.expm1(p.y) - p.fund), p.total)
    cost = rebalance(p, w, params.costs)[0]
    active = (np.bincount(p.g, np.abs(w), p.total) > 0) | (cost > 0)
    return np.where(active, gross, np.nan), np.where(active, cost, np.nan)


def permutation(p: Points, w: np.ndarray, r: np.ndarray, iterations: int, seed: int) -> float:
    """每个网格点内把收益在币之间打乱、仓位不动，随机毛收益 < 真实毛收益的比例；
    各币收益分布不一样时，调用方先把 r 除以波动、w 乘回去，打乱的就是同尺度的收益"""
    active = np.bincount(p.g, np.abs(w), p.total)[p.g] > 0
    if not active.any():
        return 0.0
    g, w, r = p.g[active], w[active], r[active]
    real = float(w @ r)
    starts = np.flatnonzero(np.r_[True, g[1:] != g[:-1]])
    sizes = np.diff(np.r_[starts, len(g)])
    rng = np.random.default_rng(seed)
    totals = np.zeros(iterations)
    for n in np.unique(sizes):
        idx = starts[sizes == n][:, None] + np.arange(n)
        wn, rn = w[idx], r[idx]
        step = max(1, _CHUNK // rn.size)
        for a in range(0, iterations, step):
            b = min(iterations, a + step)
            shuffled = rng.permuted(np.broadcast_to(rn, (b - a, *rn.shape)), axis=2)
            totals[a:b] += np.einsum("ktn,tn->k", shuffled, wn)
    return float(np.mean(totals < real))


def simulate(p: Points, vol: np.ndarray, ic: np.ndarray, wins: list[Window], bars: int, params: EvalParams,
             iterations: int) -> dict[str, Any]:
    """p、vol 来自 sized；ic 是全部样本每个网格点的 IC，方向从它学。样本外每个网格点一期；iterations = 0 不做排列检验"""
    oos = np.zeros(p.total, dtype=bool)
    for win in wins:
        oos[win.test_start:win.test_end] = True
    if not oos.any():
        return {"periods": 0}
    direction = directions(ic, p.months, wins, params.min_points)
    w = inverse_vol(p.g, weights(p, direction, params.leg_enter, params.leg_exit), vol, p.total, params.leg_cap)
    # 一个币持有一期的收益：涨跌减资金费，空头仓位 w < 0 正好变成收费
    r = np.expm1(p.y) - p.fund
    gross = np.bincount(p.g, w * r, p.total)
    funding = -np.bincount(p.g, w * p.fund, p.total)
    cost, traded = rebalance(p, w, params.costs)
    net = (gross - cost)[oos]
    active = (np.bincount(p.g, np.abs(w), p.total) > 0)[oos]
    volume = traded[oos].sum()
    # params.recent_from 起的样本外期，和全段并排看
    recent = (p.times >= params.recent_from)[oos]
    # 排列检验打乱的是收益 / 波动；w·r 不变
    scale = np.maximum(vol, 1e-6)
    return {
        "periods": int(oos.sum()),
        "netReturn": compound(net),
        "grossReturn": compound(gross[oos]),
        # 资金费每期平均多少 bps，正数是收、负数是付；已经算在毛收益里
        "fundingBps": float(funding[oos].mean() * 1e4),
        "sharpe": annualized_sharpe(net, periods_per_year(bars)),
        "hitRate": float(np.mean(net > 0)),
        "exposure": float(np.mean(active)),
        # Σ|Δw| / 4：两条腿整个换掉是 1，从空仓建仓是 0.5
        "turnover": float(np.mean(traded[oos]) / 4),
        # 每单位换手量赚的毛收益：单边成本到这个数，净收益正好是 0
        "breakEvenBps": float(gross[oos].sum() / volume * 1e4) if volume > 0 else np.nan,
        "permPct": permutation(p, w * scale, r / scale, iterations, params.seed) if iterations else None,
        "recentNetReturn": compound(net[recent]),
        "recentSharpe": annualized_sharpe(net[recent], periods_per_year(bars)),
    }
