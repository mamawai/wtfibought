from dataclasses import replace

import numpy as np
import pandas as pd
import pytest

from wiib_alpha.data.binance import BAR_MS
from wiib_alpha.eval import cs_ic
from wiib_alpha.eval.params import EvalParams

# 2025-01-01 00:00 UTC
BASE = 1_735_689_600_000
PARAMS = EvalParams(min_points=2, segments=2)


def grid_t(i: int, bars: int = 24) -> int:
    """第 i 个 bars 档网格点的收盘时刻"""
    return BASE + (i + 1) * bars * BAR_MS - 1


def skeleton(cells: list[tuple[int, np.ndarray, np.ndarray]]) -> tuple[pd.DataFrame, np.ndarray]:
    """每个元素 (6h 网格序号, 因子, 6h 答案)，一个币一个值"""
    rows, f = [], []
    for i, fs, ys in cells:
        for s, (fv, yv) in enumerate(zip(fs, ys)):
            rows.append({"t": grid_t(i), "sym": s, "tier": 1, "y24": yv, "y48": np.nan, "y96": np.nan,
                         "fund24": 0.0, "fund48": 0.0, "fund96": 0.0})
            f.append(fv)
    return pd.DataFrame(rows), np.array(f)


def test_ic_per_moment_and_thin_moments_dropped():
    y = np.random.default_rng(1).normal(0, 0.01, 20)
    rows, f = skeleton([(0, y, y), (1, -y, y), (3, y[:19], y[:19])])
    p = cs_ic.points(rows, f, 24, min_names=20)
    # 第 3 个点只有 19 个币，整个丢掉；第 2 个点没数据，网格照样占位
    assert p.total == 4 and sorted(set(p.g)) == [0, 1]
    ic = cs_ic.series(p)
    assert ic[0] == pytest.approx(1.0) and ic[1] == pytest.approx(-1.0) and np.isnan(ic[2:]).all()
    assert (np.diff(p.g) >= 0).all()


def test_nan_factor_or_answer_leaves_the_cross_section():
    y = np.random.default_rng(2).normal(0, 0.01, 22)
    f = y.copy()
    f[0] = np.nan
    ys = y.copy()
    ys[1] = np.nan
    rows, ff = skeleton([(0, f, ys)])
    p = cs_ic.points(rows, ff, 24, min_names=20)
    assert len(p.f) == 20 and cs_ic.series(p)[0] == pytest.approx(1.0)


def test_twelve_hour_grid_is_every_other_six_hour_point():
    y = np.random.default_rng(3).normal(0, 0.01, 20)
    rows, f = skeleton([(i, y, y) for i in range(6)])
    rows["y48"] = rows["y24"]
    p = cs_ic.points(rows, f, 48, min_names=20)
    # 6h 序号 1、3、5 落在 12h 网格上
    assert p.total == 3 and p.times.tolist() == [grid_t(i, 48) for i in range(3)]


def test_monthly_trained_and_segments():
    months = np.array([0, 0, 0, 1, 1, 1, 2])
    ic = np.array([0.1, 0.3, np.nan, -0.2, -0.2, -0.2, 0.5])
    table = cs_ic.monthly(ic, months, min_points=2)
    # 第 3 个月只有一个点，不够数
    assert table.index.tolist() == ["1970-01", "1970-02"] and table.tolist() == pytest.approx([0.2, -0.2])
    assert cs_ic.trained(ic, months, 3, 2) == pytest.approx(0.2)
    assert np.isnan(cs_ic.trained(ic, months, 1, 2))
    # 有 IC 的点范围 [0, 7) 等分两段
    assert cs_ic.segments(ic, 2, 1) == pytest.approx([np.mean([0.1, 0.3, -0.2]), np.mean([-0.2, -0.2, 0.5])])


def test_monotonic_groups_follow_the_factor():
    rng = np.random.default_rng(4)
    cells = []
    for i in range(30):
        y = rng.normal(0, 0.01, 25)
        cells.append((i, y + 0.002 * rng.normal(size=25), y))
    rows, f = skeleton(cells)
    p = cs_ic.points(rows, f, 24, min_names=20)
    mono = cs_ic.monotonic(p, 0, p.total, 5, 0.05)
    assert mono["rho"] == pytest.approx(1.0) and mono["spread"] > 0 and mono["spreadT"] > 2
    assert mono["groups"] == sorted(mono["groups"]) and len(mono["groups"]) == 5 and len(mono["steps"]) == 4
    # 组均值是跑赢当期平均多少：各组一样大时加起来是 0
    assert sum(mono["groups"]) == pytest.approx(0.0, abs=1e-12)
    # 因子全部并列：都落中间一组，其余组是空的，算不出单调性
    rows, flat = skeleton([(0, np.ones(25), cells[0][2])])
    mono = cs_ic.monotonic(cs_ic.points(rows, flat, 24, 20), 0, 1, 5, 0.05)
    assert np.isnan(mono["rho"]) and np.isnan(mono["spreadT"]) and np.isfinite(mono["groups"][2])


def test_one_exploding_coin_cannot_carry_the_spread():
    # 因子和收益无关，只有因子最小的那个币每期涨 5 倍：不截尾顶减底被它一个拉成大负数，截尾后几乎是 0
    rng = np.random.default_rng(6)
    cells = []
    for i in range(20):
        y = rng.normal(0, 0.01, 60)
        y[0] = np.log(6.0)
        cells.append((i, np.arange(60.0), y))
    rows, f = skeleton(cells)
    p = cs_ic.points(rows, f, 24, min_names=20)
    raw, trimmed = cs_ic.monotonic(p, 0, p.total, 3, 0.0), cs_ic.monotonic(p, 0, p.total, 3, 0.05)
    assert raw["spread"] < -0.2 and abs(trimmed["spread"]) < 0.003


def test_shape_reads_from_the_weak_end_to_the_strong_end():
    def mono(low, high):
        return {"steps": [low, high]}

    assert cs_ic.shape(mono(3.0, 2.5), 0.02) == "monotone" and cs_ic.shape(mono(1.0, 1.0), 0.02) == "flat"
    # IC 为正：因子小的那头是弱的一头
    assert cs_ic.shape(mono(8.6, -0.8), 0.02) == "weakEnd" and cs_ic.shape(mono(1.2, 5.9), 0.02) == "strongEnd"
    assert cs_ic.shape(mono(5.0, -3.0), 0.02) == "midHigh" and cs_ic.shape(mono(-3.0, 5.0), 0.02) == "midLow"
    # IC 为负：因子大的那头才是弱的一头，顺序和符号都翻过来
    assert cs_ic.shape(mono(0.8, -8.6), -0.02) == "weakEnd" and cs_ic.shape(mono(5.0, -3.0), -0.02) == "midHigh"
    assert cs_ic.shape(mono(-3.0, 5.0), -0.02) == "midLow"
    # 老结果没有 steps、落过库的 NaN 成了 None：算不出
    assert cs_ic.shape({}, 0.02) is None and cs_ic.shape(mono(None, 1.0), 0.02) is None
    assert cs_ic.shape(mono(1.0, 1.0), None) is None and cs_ic.shape(mono(1.0, 1.0), 0.0) is None


def test_summary_only_counts_the_given_range():
    rng = np.random.default_rng(5)
    cells = []
    for i in range(8):
        y = rng.normal(0, 0.01, 20)
        cells.append((i, y if i < 4 else -y, y))
    rows, f = skeleton(cells)
    p = cs_ic.points(rows, f, 24, min_names=20)
    s = cs_ic.summary(p, cs_ic.series(p), 4, 8, PARAMS)
    assert s["mean"] == pytest.approx(-1.0) and s["samples"] == 4 and s["months"] == 1
    assert s["segments"] == pytest.approx([-1.0, -1.0]) and s["monotonic"]["rho"] == pytest.approx(-1.0)
    # 四个点的 IC 全是 -1，没波动：逐点的 t 算不出
    assert set(cs_ic.compact(s)) == {"mean", "icir", "se", "t", "months", "samples", "monotonic"} and np.isnan(s["t"])
    # recent 是 recent_from 起那段的同一套数：起点放在最早就和全段一样，放到未来就是空的
    since = lambda ms: cs_ic.summary(p, cs_ic.series(p), 4, 8, replace(PARAMS, recent_from=ms))["recent"]
    assert since(0)["samples"] == 4 and since(0)["months"] == 1 and since(0)["mean"] == pytest.approx(-1.0)
    assert since(int(p.times[6]))["samples"] == 2
    assert since(10 ** 15)["samples"] == 0 and np.isnan(since(10 ** 15)["mean"])
    empty = cs_ic.summary(p, cs_ic.series(p), 8, 8, PARAMS)
    assert np.isnan(empty["mean"]) and empty["samples"] == 0 and np.isnan(empty["monotonic"]["rho"])
