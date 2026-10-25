from dataclasses import replace

import numpy as np
import pytest

from wiib_alpha.data.binance import BAR_MS
from wiib_alpha.eval import cs_ic, cs_leg
from wiib_alpha.eval.cs_ic import Points
from wiib_alpha.eval.horizon import grid_windows
from wiib_alpha.eval.params import EvalParams
from wiib_alpha.eval.walkforward import Window

BASE = 1_735_689_600_000
COSTS = (0.0005, 0.0008, 0.0012)
PARAMS = EvalParams(test_size=96, embargo=4, min_train=500, iterations=300, seed=3, min_points=2)


def points(cells: list[tuple[np.ndarray, np.ndarray, np.ndarray]], syms: list[np.ndarray] | None = None,
           fund: list[np.ndarray] | None = None) -> Points:
    """每个网格点一组 (因子, 答案, 档)；币序号默认就是位置，syms 给了按它；fund 是每个点各币持有期的资金费，默认 0"""
    g = np.concatenate([np.full(len(f), i) for i, (f, _, _) in enumerate(cells)])
    sym = syms or [np.arange(len(f)) for f, _, _ in cells]
    times = BASE + (np.arange(len(cells)) + 1) * 24 * BAR_MS - 1
    y = np.concatenate([y for _, y, _ in cells])
    return Points(g=g, sym=np.concatenate(sym), tier=np.concatenate([t for _, _, t in cells]),
                  f=np.concatenate([f for f, _, _ in cells]), y=y,
                  fund=np.concatenate(fund) if fund else np.zeros(len(y)),
                  times=times, months=np.zeros(len(cells), dtype="int64"))


def unit(p: Points) -> np.ndarray:
    """每个币波动一样：按波动分仓就是等权"""
    return np.ones(len(p.g))


def flat(f: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    return f.astype("float64"), np.zeros(len(f)), np.ones(len(f), dtype="int8")


def swapped(n: int, a: int, b: int) -> np.ndarray:
    """0..n-1 的因子值，a、b 两个币对调"""
    f = np.arange(n)
    f[[a, b]] = f[[b, a]]
    return f


def held(p: Points, w: np.ndarray, g: int) -> tuple[list[int], list[int]]:
    """第 g 个点多头、空头的币序号"""
    at = p.g == g
    return p.sym[at][w[at] > 0].tolist(), p.sym[at][w[at] < 0].tolist()


def test_fresh_entry_takes_head_and_tail_fifths_by_direction():
    f = np.arange(20, dtype="float64")
    p = points([flat(f)] * 2)
    # 第二点方向翻了：从头建仓，不带上一点的仓位
    w = cs_leg.weights(p, np.array([1.0, -1.0]), 0.2, 0.4)
    assert w[:20].tolist() == [-0.25] * 4 + [0.0] * 12 + [0.25] * 4
    assert w[20:].tolist() == [0.25] * 4 + [0.0] * 12 + [-0.25] * 4
    # 美元中性
    assert w[:20].sum() == 0 and np.abs(w[:20]).sum() == 2


def test_buffer_holds_until_the_name_leaves_the_exit_zone():
    # 0 点 19 号在头；1 点 19 号掉到第 7 名（7/20 在 2/5 里）留着，13 号冲到第 1 名进来；
    # 2 点 19 号掉到第 10 名（出了 2/5）平掉，13 号回到第 7 名还拿着
    p = points([flat(np.arange(20)), flat(swapped(20, 13, 19)), flat(swapped(20, 10, 19))])
    w = cs_leg.weights(p, np.ones(3), 0.2, 0.4)
    assert held(p, w, 0) == ([16, 17, 18, 19], [0, 1, 2, 3])
    assert held(p, w, 1) == ([13, 16, 17, 18, 19], [0, 1, 2, 3])
    assert held(p, w, 2) == ([10, 13, 16, 17, 18], [0, 1, 2, 3])
    # 每边等权、各自归一
    assert w[20:40][w[20:40] > 0].tolist() == [0.2] * 5 and w[20:40].sum() == pytest.approx(0.0)


def test_direction_flip_rebuilds_instead_of_carrying():
    # 1 点方向翻负：多头是因子小的那头；16 号上一点在多头、这一点排倒数第 6（在 2/5 里），但方向换了不带过来
    f1 = np.arange(20)
    f1[16], f1[5] = 5, 16
    p = points([flat(np.arange(20)), flat(f1)])
    w = cs_leg.weights(p, np.array([1.0, -1.0]), 0.2, 0.4)
    assert held(p, w, 1) == ([0, 1, 2, 3], [5, 17, 18, 19])


def test_missing_name_leaves_and_a_skipped_point_rebuilds():
    # 1 点 19 号不在样本里、新来的 20 号排第一：19 号自动出场；
    # 2 点 19 号回来排第 6（在 2/5 里）也不算持有，它上一点不在多头里
    p = points([flat(np.arange(20)), flat(np.arange(20)), flat(swapped(20, 14, 19))],
               syms=[np.arange(20), np.r_[np.arange(19), 20], np.arange(20)])
    w = cs_leg.weights(p, np.ones(3), 0.2, 0.4)
    assert held(p, w, 1) == ([16, 17, 18, 20], [0, 1, 2, 3])
    assert held(p, w, 2)[0] == [14, 16, 17, 18]
    # 1 点：19 号平 0.25、20 号进 0.25，其余不动
    assert cs_leg.rebalance(p, w, COSTS)[1][1] == pytest.approx(0.5)
    # 中间整个点被跳过（币数不够）：下一点从头建仓，19 号排第 5 在 2/5 里也不带过来
    q = points([flat(np.arange(20)), flat(swapped(20, 15, 19))])
    q = replace(q, g=np.r_[np.zeros(20, dtype=int), np.full(20, 2)], times=np.r_[q.times, q.times[-1] + 24 * BAR_MS],
                months=np.zeros(3, dtype="int64"))
    w = cs_leg.weights(q, np.ones(3), 0.2, 0.4)
    assert held(q, w, 2)[0] == [15, 16, 17, 18]


def test_step_day_by_day_matches_the_whole_history():
    # 交易员一天算一步：上一点方向相同（有仓位）才把上一点的持仓带进来，和整段算的 weights 逐点一样；
    # 方向翻号、学不出方向（0）、整个点被跳过、有币缺席又回来、因子有并列都在里面
    rng = np.random.default_rng(21)
    total, n = 40, 30
    f = np.zeros((total, n))
    for i in range(1, total):
        f[i] = 0.8 * f[i - 1] + rng.normal(size=n)
    f[5, :6] = 0.0
    gs, syms, fs = [], [], []
    for g in range(total):
        if g == 25:
            continue
        keep = np.flatnonzero(rng.random(n) > 0.1) if g % 7 == 3 else np.arange(n)
        gs.append(np.full(len(keep), g))
        syms.append(keep)
        fs.append(f[g, keep])
    g = np.concatenate(gs)
    p = Points(g=g, sym=np.concatenate(syms), tier=np.ones(len(g), dtype="int8"), f=np.concatenate(fs),
               y=np.zeros(len(g)), fund=np.zeros(len(g)), times=BASE + (np.arange(total) + 1) * 24 * BAR_MS - 1,
               months=np.zeros(total, dtype="int64"))
    direction = np.ones(total)
    direction[10:13], direction[18] = -1.0, 0.0
    w = cs_leg.weights(p, direction, 0.2, 0.4)
    long_held, short_held, before = set(), set(), 0.0
    for k in range(total):
        at = p.g == k
        sym = p.sym[at]
        sign = direction[k] if at.any() else 0.0
        carry = sign != 0 and sign == before
        long_, short = cs_leg.step(p.f[at], sign, np.isin(sym, list(long_held)) & carry,
                                   np.isin(sym, list(short_held)) & carry, 0.2, 0.4)
        assert (long_ == (w[at] > 0)).all() and (short == (w[at] < 0)).all(), k
        long_held, short_held, before = set(sym[long_]), set(sym[short]), sign
    # 缓冲真的起了作用：有的点留着已经掉出头部 1/5 的币
    assert any(((w[p.g == k] > 0).sum() > 6) for k in range(total))


def test_rebalance_pays_only_for_names_that_change():
    tiers = np.array([1] * 10 + [3] * 10, dtype="int8")
    f1 = swapped(20, 3, 16).astype("float64")  # 3 号（一档）与 16 号（三档）对调
    p = points([(np.arange(20.0), np.zeros(20), tiers), (f1, np.zeros(20), tiers), (f1, np.zeros(20), tiers)])
    w = cs_leg.weights(p, np.array([1.0, 1.0, 0.0]), 0.2, 0.4)
    cost, traded = cs_leg.rebalance(p, w, COSTS)
    # 建仓：排尾 0~3 号一档、排头 16~19 号三档，各 0.25
    assert traded[0] == pytest.approx(2.0) and cost[0] == pytest.approx(0.25 * (4 * COSTS[0] + 4 * COSTS[2]))
    # 3 号空头翻多头、16 号多头翻空头，各付 0.5；其余 6 个名字不动不付
    assert traded[1] == pytest.approx(1.0) and cost[1] == pytest.approx(0.5 * COSTS[0] + 0.5 * COSTS[2])
    # 方向学不出来那一点全平：平仓也付钱，按离场币上一点的档
    assert traded[2] == pytest.approx(2.0) and cost[2] == pytest.approx(0.25 * (4 * COSTS[0] + 4 * COSTS[2]))


def test_buffer_trades_less_than_no_buffer():
    # 因子是慢慢变的 AR(1)：缓冲带的换手要比进出同一条线的少
    rng = np.random.default_rng(5)
    f = np.zeros((300, 30))
    for i in range(1, 300):
        f[i] = 0.9 * f[i - 1] + rng.normal(size=30)
    p = points([flat(row) for row in f])
    d = np.ones(300)
    buffered = cs_leg.rebalance(p, cs_leg.weights(p, d, 0.2, 0.4), COSTS)[1].sum()
    plain = cs_leg.rebalance(p, cs_leg.weights(p, d, 0.2, 0.2), COSTS)[1].sum()
    assert buffered < 0.7 * plain


def test_break_even_is_the_cost_that_zeroes_the_net():
    # 5 个币各取 1 个名额。1 点建仓：多头 +1%、空头 −1%，毛收益 0.02，换手量 2；2 点仓位不动、两头都 +0.5%，毛收益 0
    # Σ毛收益 / Σ换手量 = 0.01，单边 100 bps；成本正好设成 100 bps，净收益是 0
    up = np.log1p(np.array([-0.01, 0, 0, 0, 0.01]))
    same = np.log1p(np.full(5, 0.005))
    ones = np.ones(5, dtype="int8")
    p = points([(np.arange(5.0), np.zeros(5), ones), (np.arange(5.0), up, ones), (np.arange(5.0), same, ones)])
    ic = np.full(3, 0.5)
    params = EvalParams(min_points=1, cost_tiers_bps=(100.0, 100.0, 100.0))
    out = cs_leg.simulate(p, unit(p), ic, [Window(1, 1, 3)], 24, params, 0)
    assert out["periods"] == 2 and out["breakEvenBps"] == pytest.approx(100.0)
    assert out["netReturn"] == pytest.approx(0.0, abs=1e-12) and out["grossReturn"] == pytest.approx(0.02)
    # recent 只看 recent_from 起的样本外期：起点放在最早就和全段一样，放到未来就什么都没有
    early = cs_leg.simulate(p, unit(p), ic, [Window(1, 1, 3)], 24, replace(params, recent_from=0), 0)
    assert early["recentNetReturn"] == early["netReturn"] and early["recentSharpe"] == early["sharpe"]
    late = cs_leg.simulate(p, unit(p), ic, [Window(1, 1, 3)], 24, replace(params, recent_from=10 ** 15), 0)
    assert late["recentNetReturn"] == 0.0 and np.isnan(late["recentSharpe"])
    # 一直没仓位：换手量是 0，盈亏平衡算不出来
    none = cs_leg.simulate(p, unit(p), np.full(3, np.nan), [Window(1, 1, 3)], 24, params, 0)
    assert np.isnan(none["breakEvenBps"])


def test_funding_is_paid_by_longs_and_received_by_shorts():
    # 5 个币各取 1 个名额，价格不动：多头 4 号持有期结算 +0.1%（付），空头 0 号 +0.3%（收），每期净收 0.2%
    ones = np.ones(5, dtype="int8")
    fund = np.array([0.003, 0, 0, 0, 0.001])
    p = points([(np.arange(5.0), np.zeros(5), ones)] * 3, fund=[fund] * 3)
    params = EvalParams(min_points=1, cost_tiers_bps=(0.0, 0.0, 0.0))
    out = cs_leg.simulate(p, unit(p), np.full(3, 0.5), [Window(1, 1, 3)], 24, params, 0)
    assert out["fundingBps"] == pytest.approx(20.0) and out["breakEvenBps"] == pytest.approx(20.0)
    assert out["grossReturn"] == pytest.approx(1.002 ** 2 - 1) and out["netReturn"] == pytest.approx(out["grossReturn"])
    # 方向反过来：多头是 0 号付 0.3%，空头是 4 号收 0.1%，每期净付 0.2%
    flipped = cs_leg.simulate(p, unit(p), np.full(3, -0.5), [Window(1, 1, 3)], 24, params, 0)
    assert flipped["fundingBps"] == pytest.approx(-20.0) and flipped["netReturn"] == pytest.approx(0.998 ** 2 - 1)


def test_each_side_is_sized_by_inverse_vol():
    # 10 个币每边 2 个名额：多头 8、9 号，9 号付 0.2% 资金费、波动 0.01，8 号不付、波动 0.03；按 1/波动分仓 9 号占 3/4
    ones = np.ones(10, dtype="int8")
    fund = np.zeros(10)
    fund[9] = 0.002
    p = points([(np.arange(10.0), np.zeros(10), ones)] * 3, fund=[fund] * 3)
    vol = np.tile(np.r_[np.full(8, 0.02), 0.03, 0.01], 3)
    out = cs_leg.simulate(p, vol, np.full(3, 0.5), [Window(1, 1, 3)], 24, EvalParams(min_points=1), 0)
    assert out["fundingBps"] == pytest.approx(-15.0)


def test_inverse_vol_caps_a_name_and_spreads_the_excess_by_inverse_vol():
    # 一个点多头 5 个币按 1/波动是 0.5、0.35、0.05、0.05、0.05，上限 2/5 = 0.4：0 号截到 0.4，多出的 0.1 按 1/波动摊，
    # 1 号摊到 0.42 又超了再截，剩下的 0.2 三个平分；空头 2 个币按 1/波动是 3/4、1/4，上限 2/2 = 1 不截
    g = np.zeros(7, dtype="int64")
    w = np.r_[np.ones(5), -np.ones(2)]
    vol = 1 / np.array([100, 70, 10, 10, 10, 30, 10.0])
    assert cs_leg.inverse_vol(g, w, vol, 1, 2.0) == pytest.approx([0.4, 0.4, 0.2 / 3, 0.2 / 3, 0.2 / 3, -0.75, -0.25])
    # 不设上限就是纯按 1/波动分仓
    assert cs_leg.inverse_vol(g, w, vol, 1, np.inf) == pytest.approx([0.5, 0.35, 0.05, 0.05, 0.05, -0.75, -0.25])
    # 随机的 60 个点：每边仍加起来是 1，单币不超过上限，有的币正好截在上限上
    rng = np.random.default_rng(8)
    g = np.repeat(np.arange(60), 20)
    w = rng.choice([-1.0, 0.0, 1.0], size=len(g))
    out = cs_leg.inverse_vol(g, w, np.exp(rng.normal(-4, 1, len(g))), 60, 2.0)
    for s in (1, -1):
        side = np.sign(w) == s
        n = np.bincount(g[side], minlength=60)
        limit = 2.0 / n[g[side]]
        assert np.bincount(g[side], out[side], 60)[n > 0] == pytest.approx(s)
        assert (np.abs(out[side]) <= limit + 1e-12).all() and np.isclose(np.abs(out[side]), limit).any()


def test_permutation_matches_the_closed_form():
    # 5 个币各取 1 个名额：只有多头抽中 +5%、空头抽中 −5% 才不小于真实，概率 1/20；
    # 另一个 6 个币的点收益全 0，不改变分布，只验不同币数的点能一起打乱
    r5 = np.array([-0.05, 0, 0, 0, 0.05])
    p = points([(np.arange(5.0), np.log1p(r5), np.ones(5, dtype="int8")),
                (np.arange(6.0), np.zeros(6), np.ones(6, dtype="int8"))])
    w = cs_leg.weights(p, np.ones(2), 0.2, 0.4)
    pct = cs_leg.permutation(p, w, np.expm1(p.y), 20000, 7)
    assert pct == pytest.approx(0.95, abs=0.01)
    assert cs_leg.permutation(p, np.zeros_like(w), np.expm1(p.y), 100, 7) == 0.0


def test_permutation_shuffles_vol_scaled_returns():
    # 波动差几十倍的币混在一起，弱的真信号照样显著：打乱的是收益 / 波动，高波动币的收益不会配到低波动币的大仓位上
    rng = np.random.default_rng(4)
    n = 40
    vol = 0.02 * np.exp(rng.normal(0, 1.0, n))
    cells = []
    for _ in range(200):
        z = rng.normal(size=n)
        cells.append((z + rng.normal(size=n), vol * (0.08 * z + rng.normal(size=n)), np.ones(n, dtype="int8")))
    p = points(cells)
    out = cs_leg.simulate(p, np.tile(vol, 200), np.full(200, 0.1), [Window(10, 10, 200)], 24, PARAMS, PARAMS.iterations)
    assert out["permPct"] >= 0.95


def synthetic(n_points: int = 200, seed: int = 9) -> Points:
    rng = np.random.default_rng(seed)
    cells = []
    for _ in range(n_points):
        y = rng.normal(0, 0.02, 25)
        cells.append((y + rng.normal(0, 0.02, 25), y, rng.integers(1, 4, 25).astype("int8")))
    return points(cells)


def test_simulate_learns_the_sign_and_beats_cost():
    wins = grid_windows(200, 24, PARAMS)
    up = synthetic()
    down = replace(up, f=-up.f)
    a = cs_leg.simulate(up, unit(up), cs_ic.series(up), wins, 24, PARAMS, PARAMS.iterations)
    b = cs_leg.simulate(down, unit(down), cs_ic.series(down), wins, 24, PARAMS, PARAMS.iterations)
    assert a["periods"] == wins[-1].test_end - wins[0].test_start
    assert a["netReturn"] > 0 and a["permPct"] >= 0.99 and a["exposure"] == 1.0
    assert a["netReturn"] < a["grossReturn"] and 0 < a["turnover"] <= 1 and a["breakEvenBps"] > 12
    # 反号的因子学到反方向，仓位完全一样
    assert b["netReturn"] == pytest.approx(a["netReturn"]) and b["permPct"] == a["permPct"]
    assert cs_leg.simulate(up, unit(up), cs_ic.series(up), wins, 24, PARAMS, 0)["permPct"] is None
    assert cs_leg.simulate(up, unit(up), cs_ic.series(up), [], 24, PARAMS, 0) == {"periods": 0}


def test_noise_is_not_significant():
    rng = np.random.default_rng(11)
    cells = [(rng.normal(size=25), rng.normal(0, 0.02, 25), np.ones(25, dtype="int8")) for _ in range(200)]
    p = points(cells)
    out = cs_leg.simulate(p, unit(p), cs_ic.series(p), grid_windows(200, 24, PARAMS), 24, PARAMS, PARAMS.iterations)
    assert 0.02 < out["permPct"] < 0.98
