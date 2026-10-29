from dataclasses import replace

import numpy as np
import pandas as pd
import pytest

from wiib_alpha.data.binance import BAR_MS, DAY_MS
from wiib_alpha.data.panel import FEATURE_COLUMNS
from wiib_alpha.data.pool import on_grid
from wiib_alpha.dsl import parse
from wiib_alpha.eval.combo import Combo
from wiib_alpha.eval.cs_ic import Points
from wiib_alpha.eval.params import EvalParams
from wiib_alpha.trade import scheme, targets

BASE = 1_735_689_600_000
PARAMS = EvalParams(test_size=96, embargo=4, min_train=500, iterations=0, seed=7, holdout_days=10)
PLANTED = parse("Mean($volume, 4)", FEATURE_COLUMNS)


def test_target_leg_day_by_day_matches_the_backtest_positions():
    # 交易员每天算的一条腿（缓冲选币 + 每边按 1/波动分仓、单币有上限）和回测整段算的仓位逐点一样，两个方向都比；
    # 有的点缺几个币，上一期持有的币缺席就不带
    rng = np.random.default_rng(5)
    total, n = 30, 25
    f, vol = np.zeros((total, n)), rng.uniform(0.002, 0.02, (total, n))
    for i in range(1, total):
        f[i] = 0.7 * f[i - 1] + rng.normal(size=n)
    present = [np.flatnonzero(rng.random(n) > 0.08) if g % 4 == 1 else np.arange(n) for g in range(total)]
    g = np.concatenate([np.full(len(k), i) for i, k in enumerate(present)])
    sym = np.concatenate(present)
    p = Points(g=g, sym=sym, tier=np.ones(len(g), dtype="int8"), f=f[g, sym], y=np.zeros(len(g)), fund=np.zeros(len(g)),
               times=BASE + (np.arange(total) + 1) * 96 * BAR_MS - 1, months=np.zeros(total, dtype="int64"))
    names = np.array([f"C{i:02d}" for i in range(n)])
    params = EvalParams()
    capped = False
    for sign in (1.0, -1.0):
        w = scheme.positions(p, vol[g, sym], np.full(total, sign), params)
        held: dict = {}
        for k in range(total):
            at = p.g == k
            signal = pd.Series(p.f[at], index=names[p.sym[at]])
            out = targets.leg(signal, pd.Series(vol[k, p.sym[at]], index=signal.index), sign, held, params)
            got = pd.Series({c["symbol"]: c["weight"] for c in out["coins"]})[signal.index].to_numpy()
            np.testing.assert_allclose(got, w[at], rtol=0, atol=1e-15, err_msg=f"{sign} {k}")
            assert got[got > 0].sum() == pytest.approx(1.0) and got[got < 0].sum() == pytest.approx(-1.0)
            for side in (got > 0, got < 0):
                capped |= bool(np.isclose(np.abs(got[side]), params.leg_cap / side.sum()).any())
            held = {"long": out["long"], "short": out["short"]}
    # 上限真的截到过
    assert capped


def test_board_views_by_direction_and_skips_what_it_cannot_compute(planted_pool, planted_store):
    panels = {s: planted_store.load(s) for s in planted_pool.symbols}
    t = int(planted_pool.rows["t"].max())
    products = list(planted_pool.symbols[:3])
    factor = {"no": 1, "expr": "Mean($volume, 4)", "sign": 1.0, "weight": 0.5, "live": True}
    out = targets.board(panels, [factor,
                                 {**factor, "no": 2, "sign": -1.0, "weight": 0.0},
                                 {**factor, "no": 3, "live": False},
                                 {**factor, "no": 4, "sign": 0.0}], products, t, PARAMS)
    views = [f["stances"] for f in out["factors"]]
    # 方向反过来，强弱名次正好颠倒
    n = len(planted_pool.symbols)
    assert all(views[1][p]["rank"] == n + 1 - views[0][p]["rank"] for p in products)
    # live 补不出来的、学不出方向的不给看法；权重 0 的不进组合，只剩一条时组合的看法就是它的
    assert views[2] == views[3] == {} and out["combo"] == views[0]
    # 有数的币不到 min_names 个：谁都不给看法
    assert targets.stances(pd.Series({"A": 1.0, "B": 2.0}), ["A"], PARAMS) == {}


def test_values_at_one_moment_match_pool_values_with_cross_nodes(planted_pool, planted_store):
    # 带截面算子的表达式跨全部面板算，和评估器全池求值在同一时刻的值一样
    expr = parse("CsRank(Mean($volume, 4)) - CsZ($premium)", FEATURE_COLUMNS)
    rows = planted_pool.rows
    t = int(rows["t"].max())
    full = planted_pool.values(expr).f[rows["t"].to_numpy() == t]
    panels = {s: planted_store.load(s) for s in planted_pool.symbols}
    got = targets.values_at(panels, expr, t)
    np.testing.assert_allclose(got[list(planted_pool.symbols)].to_numpy(), full, rtol=1e-12)
    # 不在池的币不出
    panels["AAA"] = panels["AAA"].assign(in_pool=0.0)
    assert "AAA" not in targets.values_at(panels, expr, t).index


def test_targets_split_capital_between_the_legs(planted_store, planted_pool):
    panels = {s: planted_store.load(s) for s in planted_pool.symbols}
    index = panels["AAA"].index.to_numpy()
    t = int(index[on_grid(index, 96)].max())
    signal = targets.values_at(panels, PLANTED, t).sort_values(ascending=False)
    # 上一期组合腿多头里有个现在排第 7 的币（24 个币的头部 2/5 以内）：留着
    kept = signal.index[6]
    listed = dict.fromkeys(panels, 0)
    out = targets.compute(panels, [PLANTED], np.array([0.03]), {"combo": {"long": [kept]}}, t, PARAMS, True, listed, 0.25)
    legs = out["legs"]
    assert legs["combo"]["long"] == sorted([*signal.index[:4], kept])
    for leg in legs.values():
        w = np.array([c["weight"] for c in leg["coins"]])
        assert w[w > 0].sum() == pytest.approx(1.0) and w[w < 0].sum() == pytest.approx(-1.0)
    # 每币净权重 = 组合腿 3/4 + carry 腿 1/4
    for s, c in out["coins"].items():
        combo = next((x["weight"] for x in legs["combo"]["coins"] if x["symbol"] == s), 0.0)
        carry = next((x["weight"] for x in legs["carry"]["coins"] if x["symbol"] == s), 0.0)
        assert (c["combo"], c["carry"], c["weight"]) == pytest.approx((0.75 * combo, 0.25 * carry,
                                                                        0.75 * combo + 0.25 * carry))
    # 多空两边总名义各是净值的 1 倍
    weights = np.array([c["weight"] for c in out["coins"].values()])
    assert weights.sum() == pytest.approx(0.0, abs=1e-12) and np.abs(weights).sum() <= 2.0 + 1e-12
    # 一条因子都没勾：组合腿空仓，carry 腿照旧
    bare = targets.compute(panels, [], np.empty(0), {}, t, PARAMS, True, listed, 0.25)
    assert bare["legs"]["combo"]["long"] == [] and bare["legs"]["carry"] == legs["carry"]
    # carry 腿停了：空仓，每币只剩组合腿那份
    off = targets.compute(panels, [PLANTED], np.array([0.03]), {}, t, PARAMS, False, listed, 0.25)
    assert off["legs"]["carry"] == {"long": [], "short": [], "coins": []}
    assert all(c["carry"] == 0 and c["weight"] == c["combo"] for c in off["coins"].values())
    # 份额 0：carry 腿照样选币，但每币只有组合腿那份，组合腿占全部资金
    all_in = targets.compute(panels, [PLANTED], np.array([0.03]), {}, t, PARAMS, True, listed, 0.0)
    assert all_in["legs"]["carry"] == legs["carry"]
    assert all(c["carry"] == 0 and c["weight"] == c["combo"] for c in all_in["coins"].values())
    assert sum(c["combo"] for c in all_in["coins"].values() if c["combo"] > 0) == pytest.approx(1.0)
    # 上市不满一年的币 carry 腿不碰，组合腿照旧
    young = targets.compute(panels, [PLANTED], np.array([0.03]), {}, t, PARAMS, True, {**listed, "AAA": t - 100 * DAY_MS},
                            0.25)
    assert "AAA" not in {c["symbol"] for c in young["legs"]["carry"]["coins"]}
    assert "AAA" in {c["symbol"] for c in young["legs"]["combo"]["coins"]}
    # T 那根有数的币不够：不出空目标（那会把仓位全平掉），报错
    with pytest.raises(targets.TradeError):
        targets.compute(dict(list(panels.items())[:10]), [PLANTED], np.array([0.03]), {}, t, PARAMS, True, listed, 0.25)


def test_carry_signal_skips_coins_listed_under_a_year(planted_pool):
    full = planted_pool.values(scheme.CARRY).f
    # 合成面板全在数据起点就有：都算老币，一个都不剔
    assert (planted_pool.listed == 0).all()
    assert np.array_equal(scheme.carry_signal(planted_pool), full, equal_nan=True)
    # AAA 当作数据起点后 10 天才上市：之后 365 天内的行当缺值，别的币不动
    rows = planted_pool.rows
    start = int(rows["t"].min())
    pool = replace(planted_pool, listed=np.where(np.arange(len(planted_pool.symbols)) == 0, start + 10 * DAY_MS, 0))
    f = scheme.carry_signal(pool)
    young = (rows["sym"].to_numpy() == 0) & (rows["t"].to_numpy() < start + 375 * DAY_MS)
    assert young.any() and np.isnan(f[young]).all()
    assert np.array_equal(f[~young], full[~young], equal_nan=True)


def test_backtest_and_weights(planted_pool):
    planted = planted_pool.values(PLANTED).f
    basis, vol = planted_pool.values(scheme.CARRY).f, planted_pool.values(scheme.VOL).f
    with_factor = scheme.backtest(planted_pool, [planted], basis, vol, PARAMS, False, 0.25)
    carry_only = scheme.backtest(planted_pool, [], basis, vol, PARAMS, False, 0.25)
    # 份额 1 时组合腿不占资金，有没有因子一样
    assert scheme.backtest(planted_pool, [planted], basis, vol, PARAMS, False, 1.0)["netReturn"] == pytest.approx(
        scheme.backtest(planted_pool, [], basis, vol, PARAMS, False, 1.0)["netReturn"])
    assert set(with_factor) == {"periods", "netReturn", "sharpe", "maxDrawdown", "turnover", "carryFundingBps",
                                "recentFrom", "recentNetReturn", "recentSharpe", "carryShare"}
    assert with_factor["carryShare"] == 0.25
    assert with_factor["periods"] == carry_only["periods"] > 0 and 0 < with_factor["turnover"] <= 1
    # 埋的数据都在 recent_from 之后：recent 那份和全段一样
    assert with_factor["recentFrom"] == PARAMS.recent_from and with_factor["recentSharpe"] == pytest.approx(with_factor["sharpe"])
    # 埋了信号的组合腿：搜索段比只有 carry 腿好
    assert with_factor["sharpe"] > carry_only["sharpe"] and with_factor["netReturn"] > 0
    assert 0 <= with_factor["maxDrawdown"] < 1
    held = scheme.backtest(planted_pool, [planted], basis, vol, PARAMS, True, 0.25)
    # holdout 只算切点之后的点
    assert 0 < held["periods"] <= 10 and held["netReturn"] > 0
    # 交易员的组合权重：全部历史上学的 24h 档，方向跟着因子走
    weight = scheme.learned_weights(planted_pool, [planted], vol, PARAMS)[0]
    assert weight > 0
    assert scheme.learned_weights(planted_pool, [-planted, planted], vol, PARAMS) == pytest.approx([-weight, weight])


def test_gain_is_the_change_in_the_scheme_sharpe(planted_pool, planted_vol):
    pick = planted_pool.in_search
    search = lambda text: planted_pool.values(parse(text, FEATURE_COLUMNS)).f[pick]
    basis = planted_pool.values(scheme.CARRY).f
    empty = scheme.Base.of(Combo.empty(planted_pool.search, planted_pool.symbols), basis[pick], planted_vol[pick],
                           PARAMS, 0.25)
    # 库空时基线是只有 carry 的方案，和整段回测一样
    assert empty.result == scheme.backtest(planted_pool, [], basis, planted_vol, PARAMS, False, 0.25)
    first = scheme.gain(empty, "planted", search("Mean($volume, 4)"))
    assert first["sharpe"]["base"] == pytest.approx(empty.result["sharpe"]) and first["sharpe"]["gain"] > 0.5
    assert first["recentSharpe"] == pytest.approx(first["sharpe"]) and first["recentFrom"] == PARAMS.recent_from
    lib = empty.plus("planted", search("Mean($volume, 4)"))
    assert lib.result["sharpe"] == pytest.approx(first["sharpe"]["with"])
    # 换个窗口的翻版、噪音：加进去方案几乎不动
    twin = scheme.gain(lib, "twin", search("Mean($volume, 8)"))
    noise = scheme.gain(lib, "noise", search("$trades"))
    assert twin["sharpe"]["base"] == pytest.approx(first["sharpe"]["with"])
    # 用存着的库里 Track 和 carry 腿算的，和整段重算一样
    fresh = scheme.backtest(planted_pool, [planted_pool.values(PLANTED).f,
                                           planted_pool.values(parse("Mean($volume, 8)", FEATURE_COLUMNS)).f],
                            basis, planted_vol, PARAMS, False, 0.25)
    assert twin["sharpe"]["with"] == pytest.approx(fresh["sharpe"]) and twin["netReturn"]["with"] == pytest.approx(fresh["netReturn"])
    # 拿掉一条：别的 Track、样本和 carry 腿照用，和从头算一样
    both = lib.plus("twin", search("Mean($volume, 8)"))
    without = both.without(0)
    assert without.combo.names == ("twin",) and len(without.tracks) == 1 and without.tracks[0] is both.tracks[1]
    only_twin = Combo(planted_pool.search, planted_pool.symbols, ("twin",), both.combo.values[:, 1:])
    assert without.result == scheme.Base.of(only_twin, basis[pick], planted_vol[pick], PARAMS, 0.25).result
    assert abs(twin["sharpe"]["gain"]) < 0.1 * first["sharpe"]["gain"]
    assert abs(noise["sharpe"]["gain"]) < 0.1 * first["sharpe"]["gain"]
