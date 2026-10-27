"""仓位规模：所有币同一个杠杆 L，保证金最多占净值 r（交易员页设）。

名义倍数 multiple = r × L ÷ 2，每币名义 = 目标净权重 × 净值 × multiple：回测口径是多空各 1 倍净值、总名义 2 倍，
L = 4、r = 0.5 正好是 1。两条腿在同一个币上互相抵掉的部分，只是让保证金少用一点。
两条限制：r 不超过 1 − HEADROOM（存设定时就拦），留给手续费、成交价漂移、先平后开；
L 不超过篮子里最低的档位上限（sim 的档位，按名义取档，超了拒单），也不超过强平距离给的上限：
篮子整体朝不利方向走 STRESS（占总名义）之后，净值还在维持保证金之上。设定的 L 比这两条宽时按上限下
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

# 保证金比例最多 1 − 这个数
HEADROOM = 0.05
# 要扛住的一天不利波动，占总名义：回测最差一天亏净值 7.6%，那时总名义 2 倍净值，折成 3.8%，取两倍
STRESS = 0.075
# 回测口径的总名义：多空各 1 倍净值
BASE_GROSS = 2.0


@dataclass(frozen=True)
class Sizing:
    """一天的下单规模：leverage 是下单用的杠杆，margin 是保证金比例，multiple 是名义倍数；
    caps 是当天杠杆的两条上限（档位、强平距离），篮子是空的就都是 None"""
    leverage: int
    margin: float
    multiple: float
    caps: dict[str, int | None]

    def view(self) -> dict[str, Any]:
        return {"leverage": self.leverage, "margin": self.margin, "multiple": self.multiple, "caps": self.caps}


def bracket_of(brackets: Sequence[Mapping[str, Any]], notional: float) -> Mapping[str, Any]:
    """名义落在哪一档，超过最高档按最高档，和 sim 的 findBracket 一样"""
    return next((b for b in brackets if float(b["notionalFloor"]) <= notional < float(b["notionalCap"])), brackets[-1])


def size(weights: Mapping[str, float], brackets: Mapping[str, Sequence[Mapping[str, Any]]], leverage: int,
         margin: float, equity: float) -> Sizing:
    """weights 是每币目标净权重（是 0 的不算），brackets 是 sim 给的这些币的档位；leverage、margin 是设定"""
    basket = {s: w for s, w in weights.items() if w}
    if not basket:
        return Sizing(leverage, margin, margin * leverage / BASE_GROSS, {"bracket": None, "stress": None})
    notional = {s: abs(w) * equity * margin * leverage / BASE_GROSS for s, w in basket.items()}
    rows = {s: bracket_of(brackets[s], n) for s, n in notional.items()}
    by_bracket = min(int(b["maxLeverage"]) for b in rows.values())
    # 按名义平均的维持保证金率
    mmr = sum(n * float(rows[s]["mmr"]) for s, n in notional.items()) / sum(notional.values())
    # 总名义 × (STRESS + mmr) ≤ 净值，总名义 = 净值 × Σ|w| × r × L ÷ 2
    by_stress = math.floor(BASE_GROSS / (margin * sum(abs(w) for w in basket.values()) * (STRESS + mmr)))
    lev = min(leverage, by_bracket, by_stress)
    return Sizing(lev, margin, margin * lev / BASE_GROSS, {"bracket": by_bracket, "stress": by_stress})
