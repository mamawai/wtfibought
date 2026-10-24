"""按腿归因：一个调仓日结束（下一次调仓前的净值有了）后，把这一天拆成组合腿、carry 腿各赚亏多少，和回测口径的"应得"对一对。

每币两条腿的目标权重（trade_run.targets_json 里的 combo、carry，已乘份额）乘调仓前净值是名义。
回测口径和 scheme.run 一样：00:15 那根收盘成交，持有到下一次调仓的 00:15 收盘；持有期内实际结算的资金费多头付、空头收；
换仓按 |Δw| 付当月成交额档的单边成本（5 / 8 / 12 bps）。应得 = 两腿价格盈亏 + 资金费 − 成本。
实际 = 下一次调仓前净值 − 这一次的；差额是执行偏差：成交价对收盘价、sim 手续费对回测成本、碎单、两腿合并下单省掉的成本。
每条腿的日收益按自己那份资金算（net ÷ 份额 ÷ 净值），前向累计回撤对止损线（STOP）。
"""

from __future__ import annotations

import logging
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

import numpy as np

from ..data.binance import BAR_MS, Binance, BinanceError
from ..data.pool import month_label, month_of, tier_of
from ..eval.params import EvalParams
from .scheme import SHARES

logger = logging.getLogger(__name__)

LEGS = ("combo", "carry")
# 止损线：前向累计回撤超过它就停这条腿。组合腿是剔新币前搜索段整套方案的最大回撤，carry 腿是剔新币前只跑 carry 的回撤
STOP = {"combo": 0.228, "carry": 0.37}


def held(targets: Mapping[str, Any]) -> dict[str, dict[str, float]]:
    """每币两条腿的权重（已乘份额），只留有仓位的"""
    return {c["symbol"]: {leg: c[leg] for leg in LEGS} for c in targets["coins"] if any(c[leg] for leg in LEGS)}


def inputs(binance: Binance, run: Mapping[str, Any], next_run: Mapping[str, Any]
           ) -> tuple[dict[str, tuple[float, float]], dict[str, float]]:
    """持仓币的建仓价、平仓价（这一次和下一次调仓的 00:15 那根收盘）和持有期内结算的费率之和。
    fapi 回 400 的币（下架的）不出，归因里记成 missing；别的错（限流、5xx、网络）抛出去"""
    entry, exit_ = run["signal_ms"] + BAR_MS, next_run["signal_ms"] + BAR_MS
    closes, funding = {}, {}
    for s in held(run["targets"]):
        try:
            bars = binance.recent_klines(s, entry + 1 - BAR_MS, exit_ + 1)
            rates = binance.recent_funding(s, entry + 1, exit_ + 1)
        except BinanceError as exc:
            if exc.status != 400:
                raise
            logger.warning("归因拉 %s 失败：%s", s, exc)
            continue
        close = bars.set_index("close_time")["close"]
        if entry in close.index and exit_ in close.index:
            closes[s] = (float(close[entry]), float(close[exit_]))
            funding[s] = float(rates["funding_rate"].sum())
    return closes, funding


def tiers(universe: Iterable[Mapping[str, Any]], signal_ms: int) -> dict[str, int]:
    """每个币的成本档（1 / 2 / 3）：按信号那根所在月的成交额排名；这个月不在池、上个月留下来要平的用上个月的档"""
    m = month_of(np.array([signal_ms]))[0]
    by_month = {month_label(m - 1): {}, month_label(m): {}}
    for r in universe:
        if r["month"] in by_month:
            by_month[r["month"]][r["symbol"]] = int(tier_of(np.array([r["rank"]]))[0])
    return {**by_month[month_label(m - 1)], **by_month[month_label(m)]}


def attribute(run: Mapping[str, Any], next_run: Mapping[str, Any], prev_targets: Mapping[str, Any] | None,
              closes: Mapping[str, tuple[float, float]], funding: Mapping[str, float], tiers_: Mapping[str, int],
              params: EvalParams) -> dict[str, Any]:
    """run 这一天按回测口径应得多少、实际多少。prev_targets 是上一次出了目标的调仓，换仓成本按它算，第一天没有。
    closes 里没有的币算不了价格和资金费（记进 missing），换仓成本照算"""
    equity = run["equity_before"]
    now_w, prev_w = held(run["targets"]), held(prev_targets) if prev_targets else {}
    legs = {leg: {"price": 0.0, "funding": 0.0, "cost": 0.0} for leg in LEGS}
    coins, missing = [], []
    for s, w in now_w.items():
        if s not in closes:
            missing.append(s)
            continue
        a, b = closes[s]
        ret, fund = b / a - 1, funding[s]
        row: dict[str, Any] = {"symbol": s, **w, "entry": a, "exit": b, "ret": ret, "fund": fund, "legs": {}}
        for leg in LEGS:
            price, fee = w[leg] * equity * ret, -w[leg] * equity * fund
            legs[leg]["price"] += price
            legs[leg]["funding"] += fee
            row["legs"][leg] = price + fee
        coins.append(row)
    for s in set(now_w) | set(prev_w):
        # 两个月都不在池的币按最贵的档
        unit = params.costs[tiers_.get(s, 3) - 1]
        for leg in LEGS:
            legs[leg]["cost"] += abs(now_w.get(s, {}).get(leg, 0.0) - prev_w.get(s, {}).get(leg, 0.0)) * unit * equity
    for leg, x in legs.items():
        x["net"] = x["price"] + x["funding"] - x["cost"]
        x["ret"] = x["net"] / (equity * SHARES[leg])
    model = sum(x["net"] for x in legs.values())
    actual = next_run["equity_before"] - equity
    return {"until": next_run["day"], "actual": actual, "model": model, "residual": actual - model, "legs": legs,
            "missing": sorted(missing), "coins": coins}


def summary(rows: Sequence[Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    """每条腿前向到现在：累计收益、当前回撤、最大回撤、止损线、碰没碰过线；rows 是按日排好的归因"""
    out = {}
    for leg in LEGS:
        curve = np.cumprod([1.0, *(1 + r["legs"][leg]["ret"] for r in rows)])
        drawdown = 1 - curve / np.maximum.accumulate(curve)
        out[leg] = {"days": len(rows), "cumReturn": float(curve[-1] - 1), "drawdown": float(drawdown[-1]),
                    "maxDrawdown": float(drawdown.max()), "stop": STOP[leg], "hit": bool(drawdown.max() >= STOP[leg])}
    return out
