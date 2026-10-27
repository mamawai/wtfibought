"""评估口径：决定一条因子过不过门、能不能入库、G9 基线怎么算的那整套参数，整份按内容算版本号（standard 表）。

内容：评估参数（EvalParams）、门和入库的阈值（Thresholds）、交易方案的常量（调仓周期、carry 公式、新币天数、分仓用的波动）、
carry 份额（trade_carry.share）、参照集（条数和内容指纹）。内容一样就是同一个版本号，改回去就回到原来的号。
每条评估结果记下自己是哪版算的（eval["standard"]），入库只认当前版复评过的，出题的成败表只统计当前版的。
内容是代码默认值加页面上改过的项（standard_override 表，现在只开了 G9 的线）：改一项就是新的一版，库和观察区要复评。
币池规则不在这里：改了得刷新，切点一变所有复评自然失效。
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from typing import Any

from . import reference
from .eval.params import EvalParams, Thresholds
from .trade import scheme


@dataclass(frozen=True)
class Standard:
    params: EvalParams
    thresholds: Thresholds
    carry_share: float
    # standard 表里的版本号，存过才有
    id: int | None = None

    def content(self) -> dict[str, Any]:
        refs = reference.parsed()
        digest = hashlib.sha256("\n".join(f"{name}={expr}" for name, expr in sorted(refs.items())).encode()).hexdigest()
        return {
            "params": asdict(self.params),
            "thresholds": asdict(self.thresholds),
            "scheme": {"bars": scheme.BARS, "carry": str(scheme.CARRY), "carryMinDays": scheme.CARRY_MIN_DAYS,
                       "vol": str(scheme.VOL), "carryShare": self.carry_share},
            "reference": {"count": len(refs), "digest": digest[:16]},
        }

    def fingerprint(self) -> str:
        return hashlib.sha256(json.dumps(self.content(), sort_keys=True).encode()).hexdigest()[:16]

    def basis(self) -> str:
        """口径里决定数字的那部分的指纹：评估参数和交易方案（含份额）。门槛、参照集只影响判定，不影响算出来的数字：
        G9 标定作不作数、出题的成败表统计哪些版本下评估的候选，都按它，只改 G9 的线不受影响"""
        content = self.content()
        basis = {"params": content["params"], "scheme": content["scheme"]}
        return hashlib.sha256(json.dumps(basis, sort_keys=True).encode()).hexdigest()[:16]


def standard_of(row: dict[str, Any]) -> int | None:
    """一条候选或因子最新的数字是哪版口径算的：复评过看复评，没有看评估（入库）那份；没有版本号的是老结果"""
    latest = row["reeval"] or row
    return latest["eval"].get("standard")
