"""硬门：拿 evaluate 的结果逐门判，每门给数字、阈值、过没过、一句原因。门全按截面口径，时序报告不做门。

G5 的 BH-FDR 是同批候选一起判的，单条先按原始排列分位判，批次汇总时 apply_fdr 再改判。
单条多空腿扣完成本赚不赚只做标签（G5 的数字里）：能不能交易看 G9，加进交易方案后搜索段净 Sharpe 升不升。
"""

from __future__ import annotations

from typing import Any, Iterable

import numpy as np

from ..data.binance import day_name
from .cs_ic import SHAPES, shape
from .params import Thresholds
from .stats import bh_reject


def judge(ev: dict[str, Any], t: Thresholds, known_canonicals: Iterable[str] = (),
          direction: int | None = None) -> list[dict[str, Any]]:
    """direction 是候选按假设声明的方向（1 越大越强、-1 越弱）；手填评估、参照集没有假设，给 None"""
    primary = ev["primary"]
    h = ev["horizons"]
    main = h[primary]["ic"]
    mean_ic = main["mean"]
    mono = main["monotonic"]
    spread, spread_t, form = mono["spread"], mono["spreadT"], shape(mono, mean_ic)
    others = [b for b in h if b != primary]
    same_sign_h = [np.sign(h[b]["ic"]["mean"]) == np.sign(mean_ic) for b in others
                   if np.isfinite(h[b]["ic"]["mean"])]
    segs = main["segments"]
    seg_same = sum(1 for v in segs if np.isfinite(v) and np.isfinite(mean_ic) and np.sign(v) == np.sign(mean_ic))
    last_same = _finite(segs[-1]) and _finite(mean_ic) and np.sign(segs[-1]) == np.sign(mean_ic)
    # 月度 IC 均值的 t：ICIR × √月数
    t_stat = abs(main["icir"]) * np.sqrt(main["months"]) if _finite(main["icir"]) else np.nan
    leg = h[primary]["leg"]
    net = leg.get("netReturn", np.nan)
    perm = leg.get("permPct") or 0.0
    even = leg.get("breakEvenBps", np.nan)
    cx = ev["complexity"]
    red = ev["redundancy"]
    longest = max(h)
    samples = h[longest]["ic"]["samples"]

    gates = [
        _gate("G0", "前视自检", ev["lookaheadFree"], ev["lookaheadFree"], True,
              "截断重算与全量一致" if ev["lookaheadFree"] else "截断重算结果变了，算子偷看了未来"),
        _gate("G1", "最小样本", samples >= t.min_samples, {"samples": samples}, {"min": t.min_samples},
              f"{longest} 根档样本外有效时刻 {samples} 个"),
        # 顶组减底组：收益截尾后每期之差得显著、和 IC 同向，IC 不能靠一两个暴涨暴跌的币撑着；三组的形状只报不判
        _gate("G2", "IC",
              _finite(mean_ic) and abs(mean_ic) >= t.ic_min and _finite(t_stat) and t_stat >= t.t_min
              and _finite(spread_t) and spread_t * np.sign(mean_ic) >= t.spread_t_min,
              {"meanIc": mean_ic, "icir": main["icir"], "months": main["months"], "t": t_stat, "spread": spread,
               "spreadT": spread_t, "shape": form},
              {"icMin": t.ic_min, "tMin": t.t_min, "spreadTMin": t.spread_t_min},
              f"主档月度截面 IC 均值 {fmt(mean_ic)}，ICIR {fmt(main['icir'])}，t {fmt(t_stat, 2)}（{main['months']} 个月），"
              f"截尾后顶组减底组每期 {fmt(spread * 1e4, 1)} bps、t {fmt(spread_t, 1)}，三组形状：{SHAPES.get(form, '算不出')}"),
        _gate("G3", "四段稳定", seg_same >= t.min_segments and last_same,
              {"segments": segs, "sameSign": seg_same, "lastSame": last_same},
              {"min": t.min_segments, "last": "最后一段与总体同号"},
              f"四段 IC {[fmt(v) for v in segs]}，{seg_same} 段与总体同号，最后一段{'同号' if last_same else '不同号'}"),
        _gate("G4", "衰减", bool(same_sign_h) and all(same_sign_h),
              {b: h[b]["ic"]["mean"] for b in h}, "三档同号",
              "三档 IC " + "、".join(f"{b}:{fmt(h[b]['ic']['mean'])}" for b in h)),
        _gate("G5", "排列检验", perm >= t.perm_pct,
              {"permPct": perm, "netReturn": net, "hitRate": leg.get("hitRate"), "periods": leg.get("periods"),
               "turnover": leg.get("turnover"), "breakEvenBps": even},
              {"permPct": t.perm_pct, "fdrQ": t.fdr_q},
              f"主档多空腿（按波动分仓）截面排列分位 {fmt(perm)}；"
              f"扣成本和资金费后净收益 {fmt(net)}、盈亏平衡单边成本 {fmt(even, 1)} bps（只报不判）"),
        redundancy_gate(red, t),
        _gate("G7", "复杂度",
              cx["nodes"] <= t.max_nodes and cx["windows"] <= t.max_windows and cx["constants"] <= t.max_constants
              and cx["cross"] <= t.max_cross,
              cx, {"nodes": t.max_nodes, "windows": t.max_windows, "constants": t.max_constants, "cross": t.max_cross},
              f"节点 {cx['nodes']}，窗口 {cx['windows']}，常量 {cx['constants']}，截面算子 {cx['cross']}"),
        origin_gate(ev["canonical"], known_canonicals),
        gain_gate(ev["gain"], t),
        direction_gate(direction, mean_ic),
    ]
    return gates


def direction_gate(direction: int | None, mean_ic: float) -> dict[str, Any]:
    """G10：主档截面 IC 的符号得和假设说的方向一样；反了说明赚的不是假设里那个机制"""
    if direction is None:
        return _gate("G10", "方向", True, None, None, "没有声明方向，不判")
    ok = _finite(mean_ic) and np.sign(mean_ic) == direction
    said = "越大越强" if direction > 0 else "越大越弱"
    return _gate("G10", "方向", ok, {"direction": direction, "meanIc": mean_ic}, {"sign": direction},
                 f"假设说因子值{said}，主档截面 IC {fmt(mean_ic)}，{'对上了' if ok else '对不上'}")


def redundancy_gate(red: dict[str, Any], t: Thresholds) -> dict[str, Any]:
    """G6：参照网格没算好就不过"""
    if not red["ready"]:
        return _gate("G6", "冗余", False, red, {"max": t.max_redundancy}, "参照网格还没算好")
    return _gate("G6", "冗余", red["maxAbs"] < t.max_redundancy, red, {"max": t.max_redundancy},
                 f"与 {red['with']} 的截面 |ρ| = {fmt(red['maxAbs'])}" if red["with"] else "没有参照因子")


def origin_gate(canonical: str, known: Iterable[str]) -> dict[str, Any]:
    """G8：规范形不能和已知的重合。同批阶段再对排在前面的候选查一次"""
    seen = canonical in set(known)
    return _gate("G8", "结构原创", not seen, canonical, "不与已知重合", "规范形与已知因子重合" if seen else "规范形是新的")


def gain_gate(gain: dict[str, Any] | None, t: Thresholds) -> dict[str, Any]:
    """G9：加进交易方案（库里因子的组合 + 基差 carry，24h、按波动）的组合腿后，搜索段 recent_from 起的年化净 Sharpe 要升过阈值；
    全段的增量写在备注里并排看，不判。同批阶段排在别的候选后面会重算一次写进备注"""
    threshold = {"sharpeGain": f"> {t.sharpe_gain_min:g}"}
    if gain is None:
        return _gate("G9", "增量", True, None, threshold, "没算对交易方案的增量")
    s, full, net = gain["recentSharpe"], gain["sharpe"], gain["netReturn"]
    return _gate("G9", "增量", _finite(s["gain"]) and s["gain"] > t.sharpe_gain_min, gain, threshold,
                 f"加进交易方案后搜索段 {day_name(gain['recentFrom'])} 起净 Sharpe "
                 f"{fmt(s['base'], 2)} → {fmt(s['with'], 2)}（增量 {fmt(s['gain'], 2)}）；"
                 f"全段 {fmt(full['base'], 2)} → {fmt(full['with'], 2)}（增量 {fmt(full['gain'], 2)}），"
                 f"净收益 {fmt(net['base'])} → {fmt(net['with'])}")


def apply_fdr(evals: list[dict[str, Any]], q: float, iterations: int, extra_tests: int = 0) -> None:
    """同批候选的排列 p 做 BH，没扛住的把 G5 改成不过；就地改 gates 与 passed。

    extra_tests 是同批里重写掉、改掉的旧版本数：各按 p=1 占一个位置，只把 m 撑大。
    """
    perm_ps = [max(1.0 - _gate_value(ev, "G5", "permPct", 0.0), 1.0 / iterations) for ev in evals]
    for ev, keep in zip(evals, bh_reject(perm_ps + [1.0] * extra_tests, q)):
        _flip(ev, "G5", keep)
        ev["passed"] = all(g["passed"] for g in ev["gates"])


def passed(gates: list[dict[str, Any]]) -> bool:
    return all(g["passed"] for g in gates)


def _flip(ev: dict[str, Any], gate_id: str, keep: bool) -> None:
    gate = next(g for g in ev["gates"] if g["id"] == gate_id)
    if gate["passed"] and not keep:
        gate["passed"] = False
        gate["note"] += "；同批 BH-FDR 没扛住"


def _gate_value(ev: dict[str, Any], gate_id: str, key: str, default: float) -> float:
    gate = next(g for g in ev["gates"] if g["id"] == gate_id)
    value = gate["value"].get(key)
    return float(value) if value is not None and np.isfinite(value) else default


def _gate(id_: str, name: str, ok: Any, value: Any, threshold: Any, note: str) -> dict[str, Any]:
    return {"id": id_, "name": name, "passed": bool(ok), "value": value, "threshold": threshold, "note": note}


def _finite(v: Any) -> bool:
    return v is not None and np.isfinite(v)


def fmt(v: Any, digits: int = 4) -> str:
    return "NaN" if v is None or not np.isfinite(v) else f"{v:.{digits}f}"
