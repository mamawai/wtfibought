"""目标仓位：UTC 0 点那根 15m 收盘（T）出信号，两条腿各自选币分仓，合成每币净权重。

和回测同一套函数：组合腿是勾选因子的截面秩 z 分数按学好的权重加权（combo.score），方向 +1（权重已带 IC 符号）；
carry 腿是 basis_7d，方向定死 −1，上市不满 CARRY_MIN_DAYS 天的币不碰（币龄按 Pool.listed，和回测同一口径）。
选币用 cs_leg.step（cs_leg.weights 的单步版，进头部 1/5、出头部 2/5），
每边按 1/Std($ret, 672) 分仓、单币不超过这一边平均的 leg_cap 倍（cs_leg.inverse_vol），多头加起来 1、空头 −1。
两条腿按 carry 份额分资金：每币净权重 = (1 − 份额) × 组合腿权重 + 份额 × carry 腿权重，乘净值就是目标名义，总敞口约 2 倍净值。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

import numpy as np
import pandas as pd

from ..data.binance import DAY_MS
from ..data.panel import FEATURE_COLUMNS, POOL_COLUMN
from ..data.pool import cross_at, cross_values
from ..dsl import Expr, parse
from ..eval import combo, cs_leg
from ..eval.params import EvalParams
from .scheme import CARRY, CARRY_MIN_DAYS, VOL

# 每条腿的方向
SIGNS = {"combo": 1.0, "carry": -1.0}


class TradeError(RuntimeError):
    """这一次不该下单：币池过期、T 那根数据不全"""


def values_at(panels: Mapping[str, pd.DataFrame], expr: Expr, t_ms: int) -> pd.Series:
    """t 那根在池的每个币的值，index 是币；截面节点照 Pool.values 跨全部面板算"""
    cross: dict = {}
    for node in expr.cross_nodes():
        cross[node] = cross_values(node, panels.items(), cross)
    return pd.Series({s: expr.evaluate(df, cross_at(cross, s)).get(t_ms, np.nan)
                      for s, df in panels.items() if df[POOL_COLUMN].get(t_ms) == 1.0}, dtype="float64")


def leg(signal: pd.Series, vol: pd.Series, sign: float, held: Mapping[str, Sequence[str]],
        params: EvalParams) -> dict[str, Any]:
    """一条腿这一期：信号和波动都有数的币按币名排（同回测的币序号），不到 min_names 个就空仓；
    held 是上一期的 {"long": [...], "short": [...]}，没有就从头建仓"""
    names = sorted(s for s in signal.index if np.isfinite(signal[s]) and np.isfinite(vol.get(s, np.nan)))
    if len(names) < params.min_names:
        return {"long": [], "short": [], "coins": []}
    f, v = signal[names].to_numpy(dtype="float64"), vol[names].to_numpy(dtype="float64")
    long_, short = cs_leg.step(f, sign, np.isin(names, held.get("long", [])), np.isin(names, held.get("short", [])),
                               params.leg_enter, params.leg_exit)
    w = cs_leg.inverse_vol(np.zeros(len(names), dtype="int64"), long_.astype("float64") - short, v, 1, params.leg_cap)
    # 名次：信号从大到小数，1 是信号最大的
    rank = pd.Series(f).rank(ascending=False, method="first").to_numpy(dtype="int64")
    return {"long": [s for s, x in zip(names, long_) if x], "short": [s for s, x in zip(names, short) if x],
            "coins": [{"symbol": s, "signal": float(f[i]), "rank": int(rank[i]), "vol": float(v[i]), "weight": float(w[i])}
                      for i, s in enumerate(names)]}


def compute(panels: Mapping[str, pd.DataFrame], factors: Sequence[Expr], weights: np.ndarray,
            held: Mapping[str, Mapping[str, Sequence[str]]], t_ms: int, params: EvalParams, carry: bool,
            listed: Mapping[str, int], carry_share: float) -> dict[str, Any]:
    """factors 与 weights 一一对应（勾选的因子、全部历史上学的 IC 权重），一条都没有时组合腿空仓、它那份资金闲着；
    carry 是 carry 腿开没开，停了也空仓、资金闲着；listed 是每个币第一根 K 线的时刻（Pool.listed），carry 腿不碰上市不满
    CARRY_MIN_DAYS 天的；carry_share 是 carry 腿占的资金比例。held 是上一期各腿的多空集合。
    出每条腿的多空与每币明细，和每币净权重（只列不是 0 的）。
    T 那根在池有波动的币不够 min_names 个、或者一条因子全池没值，就报错：数据没补全时出的空目标会把仓位全平掉，
    缺值按 0 合成等于把那条因子静默踢出组合"""
    vol = values_at(panels, VOL, t_ms)
    if int(np.isfinite(vol).sum()) < params.min_names:
        raise TradeError(f"T 那根在池有波动的币只有 {int(np.isfinite(vol).sum())} 个，不调仓")
    signal = pd.Series(dtype="float64")
    if factors:
        table = pd.concat([values_at(panels, e, t_ms) for e in factors], axis=1)
        for e, col in zip(factors, table.to_numpy(dtype="float64").T):
            if not np.isfinite(col).any():
                raise TradeError(f"因子 {e} 在 T 那根全池没有值（回看窗口比 live 面板还长？），不调仓")
        c = combo.Combo(pd.DataFrame({"t": np.full(len(table), t_ms)}), tuple(table.index),
                        tuple(str(e) for e in factors), table.to_numpy(dtype="float64"))
        signal = pd.Series(combo.score(c, weights), index=table.index)
    basis = values_at(panels, CARRY, t_ms)
    basis[[s for s in basis.index if (t_ms - listed[s]) / DAY_MS < CARRY_MIN_DAYS]] = np.nan
    legs = {"combo": leg(signal, vol, SIGNS["combo"], held.get("combo", {}), params),
            "carry": (leg(basis, vol, SIGNS["carry"], held.get("carry", {}), params) if carry
                      else {"long": [], "short": [], "coins": []})}
    shares = {"combo": 1 - carry_share, "carry": carry_share}
    coins: dict[str, dict[str, float]] = {}
    for name, out in legs.items():
        for c in out["coins"]:
            if c["weight"]:
                coins.setdefault(c["symbol"], {"combo": 0.0, "carry": 0.0})[name] = shares[name] * c["weight"]
    return {"legs": legs, "coins": {s: {"weight": x["combo"] + x["carry"], **x} for s, x in sorted(coins.items())}}


def stances(signal: pd.Series, products: Sequence[str], params: EvalParams) -> dict[str, dict[str, Any]]:
    """signal 越大越强：产品币在全池里从强数第几、共几个，排进头部 1/5 看多、尾部 1/5 看空、其余中性（和交易腿进场同口径）；
    有数的币不到 min_names 个就不给看法，不在池的产品币不出"""
    s = signal[np.isfinite(signal)].sort_index()
    n = len(s)
    if n < params.min_names:
        return {}
    head = s.rank(ascending=False, method="first")
    out = {}
    for p in products:
        if p in head.index:
            h = int(head[p])
            stance = "long" if h / n <= params.leg_enter else "short" if (n + 1 - h) / n <= params.leg_enter else "neutral"
            out[p] = {"stance": stance, "rank": h, "of": n}
    return out


def board(panels: Mapping[str, pd.DataFrame], factors: Sequence[Mapping[str, Any]], products: Sequence[str], t_ms: int,
          params: EvalParams) -> dict[str, Any]:
    """因子看板：每条在役因子在 T 那根全池截面上按它的方向排强弱，看产品币落在哪；combo 是进组合（权重不为 0）的那几条
    按学好的权重合成后的看法。factors 每条带 expr、sign、weight、live；live 尾巴补不出来的、学不出方向的不给看法"""
    out, used, weights = [], [], []
    for f in factors:
        if not (f["live"] and f["sign"]):
            out.append({**f, "stances": {}})
            continue
        values = values_at(panels, parse(f["expr"], FEATURE_COLUMNS), t_ms)
        out.append({**f, "stances": stances(values * f["sign"], products, params)})
        if f["weight"]:
            used.append(values)
            weights.append(f["weight"])
    combined = {}
    if used:
        table = pd.concat(used, axis=1)
        c = combo.Combo(pd.DataFrame({"t": np.full(len(table), t_ms)}), tuple(table.index), tuple(map(str, range(len(used)))),
                        table.to_numpy(dtype="float64"))
        combined = stances(pd.Series(combo.score(c, np.array(weights)), index=table.index), products, params)
    return {"coins": list(products), "factors": out, "combo": combined}
