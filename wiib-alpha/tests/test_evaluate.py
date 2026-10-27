import copy

import numpy as np
import pandas as pd
import pytest
from conftest import PRODUCTS

from wiib_alpha import ops, reference
from wiib_alpha.data.binance import BAR_MS
from wiib_alpha.data.pool import on_grid
from wiib_alpha.data.panel import truncate
from wiib_alpha.dsl import parse
from wiib_alpha.eval.combo import Combo
from wiib_alpha.eval.evaluate import cross_corr, evaluate, redundancy
from wiib_alpha.eval.gates import apply_fdr, judge, passed
from wiib_alpha.eval.params import EvalParams, Thresholds
from wiib_alpha.trade import scheme

COLS = ["close", "ret", "volume", "trades"]
PARAMS = EvalParams(test_size=96, embargo=4, min_train=500, iterations=200, seed=7, holdout_days=10)
GATES = Thresholds(min_samples=30)


@pytest.fixture(scope="module")
def planted(planted_pool, planted_vol, planted_grid):
    return evaluate(parse("$volume", COLS), planted_pool, planted_vol, PARAMS, planted_grid)


def main_of(ev: dict) -> dict:
    """判门用的那一档"""
    return ev["horizons"][ev["primary"]]


def test_planted_signal_passes_every_gate(planted):
    ev = planted
    assert ev["lookaheadFree"] and ev["primary"] == 96
    main = main_of(ev)["ic"]
    assert main["mean"] > 0.3 and main["months"] >= 2 and main["monotonic"]["rho"] > 0.9
    assert main["monotonic"]["spread"] > 0 and len(main["monotonic"]["groups"]) == 3
    leg = main_of(ev)["leg"]
    assert leg["netReturn"] > 0 and leg["permPct"] >= 0.95 and leg["breakEvenBps"] > 12
    # 排列检验只给主档做
    assert ev["horizons"][24]["leg"]["permPct"] is None and ev["horizons"][24]["leg"]["netReturn"] > 0
    gates = judge(ev, GATES)
    assert passed(gates), [g for g in gates if not g["passed"]]
    # 去老效应只是标签，不在门里
    assert [g["id"] for g in gates] == [f"G{i}" for i in range(11)]
    # G5 只判排列检验，净收益、盈亏平衡成本只报
    assert gates[5]["name"] == "排列检验" and gates[5]["need"] == "主档排列分位 ≥ 0.95，同批 BH（q = 0.1）后还显著"
    assert gates[5]["value"]["breakEvenBps"] == leg["breakEvenBps"] and "盈亏平衡单边成本" in gates[5]["note"]
    assert gates[2]["value"]["t"] == pytest.approx(abs(main["icir"]) * np.sqrt(main["months"]))
    # 植入池子没有结算记录，资金费是 0
    assert leg["fundingBps"] == 0


def test_g5_does_not_judge_the_single_leg_net(planted):
    ev = copy.deepcopy(planted)
    main_of(ev)["leg"]["netReturn"] = -0.5
    g5 = judge(ev, GATES)[5]
    assert g5["passed"] and g5["value"]["netReturn"] == -0.5 and "只报不判" in g5["note"]
    main_of(ev)["leg"]["permPct"] = 0.9
    assert not judge(ev, GATES)[5]["passed"]


def test_g10_checks_the_declared_direction(planted):
    # 植入信号是越大越强：声明 1 对上、声明 -1 对不上；不声明（手填、参照集）不判
    assert judge(planted, GATES, direction=1)[10]["passed"]
    g10 = judge(planted, GATES, direction=-1)[10]
    assert g10["id"] == "G10" and not g10["passed"] and "越大越弱" in g10["note"] and "对不上" in g10["note"]
    assert judge(planted, GATES)[10] == {"id": "G10", "name": "方向", "passed": True, "value": None,
                                         "need": "主档 IC 的符号和假设声明的方向一致", "note": "没有声明方向，不判"}


def test_noise_factor_fails(planted_pool, planted_vol, planted_grid):
    ev = evaluate(parse("$trades", COLS), planted_pool, planted_vol, PARAMS, planted_grid)
    gates = judge(ev, GATES)
    # 24 个币、样本外两个月，噪音的 IC 与 ICIR 偶尔也够；排列检验不显著
    assert not passed(gates) and not gates[5]["passed"]
    assert abs(main_of(ev)["ic"]["mean"]) < 0.05
    # 参照网格里那条就是 trades 的均线，冗余对得上
    assert ev["redundancy"]["with"] == "noise4" and ev["redundancy"]["maxAbs"] > 0.3


def known_grid(pool, column: np.ndarray, name: str = "mom_7d") -> reference.Grid:
    """只有一条老效应的参照网格：参照网格那一档的每个时刻把 column 转成截面秩"""
    rows = pool.search
    main = on_grid(rows["t"].to_numpy(), reference.GRID_BARS)
    ranks = pd.Series(column[main]).groupby(rows["t"].to_numpy()[main]).rank(pct=True).to_numpy()
    return reference.Grid((name,), ranks[:, None].astype("float32"))


def test_g6_catches_sign_flipping_over_time(planted_pool):
    # 隔一个时刻取一次反：每个时刻 |ρ| 都是 1，先跨时刻平均再取绝对值会抵消成 0
    rows = planted_pool.search
    f = planted_pool.values(parse("Mean($volume, 4)", COLS)).f[planted_pool.in_search]
    t = rows["t"].to_numpy()
    flipped = np.where((t // (reference.GRID_BARS * BAR_MS)) % 2 == 1, -f, f)
    red = redundancy(rows, flipped, known_grid(planted_pool, f), {}, {}, PARAMS)
    assert red["maxAbs"] == pytest.approx(1.0)


def test_neutral_regresses_out_known_effects(planted_pool, planted_vol):
    expr = parse("Mean($volume, 4)", COLS)
    f = planted_pool.values(expr).f[planted_pool.in_search]
    raw = evaluate(expr, planted_pool, planted_vol, PARAMS, known_grid(planted_pool, f, "noise4"))
    # 网格里的那条不在老效应名单里：回归掉 0 条，去老效应后的 IC 就是原来主档的
    assert raw["neutral"]["against"] == 0
    assert raw["neutral"]["mean"] == pytest.approx(main_of(raw)["ic"]["mean"], abs=1e-9)
    # 老效应和它无关：系数在训练窗里整体估，回归掉以后 IC 几乎不变（逐时刻估会被机械地压掉一截）
    noise = np.random.default_rng(3).normal(size=len(f))
    kept = evaluate(expr, planted_pool, planted_vol, PARAMS, known_grid(planted_pool, noise))
    assert kept["neutral"]["against"] == 1
    assert kept["neutral"]["mean"] == pytest.approx(main_of(kept)["ic"]["mean"], rel=0.05)
    # 24h 反转也一起回归；它就是候选自己：回归掉以后几乎什么都不剩
    gone = evaluate(expr, planted_pool, planted_vol, PARAMS, known_grid(planted_pool, f, reference.REVERSAL))
    assert gone["neutral"]["against"] == 1
    assert abs(gone["neutral"]["mean"]) < 0.1 < main_of(gone)["ic"]["mean"]
    # 只是标签：几乎什么都不剩也不多挡一道门，挡它的只有 G6（参照里那条就是它自己）
    assert passed(judge(kept, GATES))
    assert [g["id"] for g in judge(gone, GATES) if not g["passed"]] == ["G6"]


def test_g9_needs_the_scheme_sharpe_to_rise_past_the_threshold(planted):
    ev = copy.deepcopy(planted)
    ev["gain"] = {"sharpe": {"base": 1.0, "with": 1.5, "gain": 0.5}, "recentSharpe": {"base": 1.0, "with": 0.9, "gain": -0.1},
                  "recentFrom": 1_640_995_200_000, "netReturn": {"base": 0.5, "with": 0.4}}
    g9 = judge(ev, GATES)[9]
    # 判的是 2022 起那份：全段升 0.5 也不过，全段的数写在备注里并排看
    assert g9["id"] == "G9" and not g9["passed"]
    assert "搜索段 2022-01-01 起净 Sharpe 1.00 → 0.90（增量 -0.10）；全段 1.00 → 1.50（增量 0.50）" in g9["note"]
    ev["gain"]["recentSharpe"] = {"base": 1.0, "with": 1.2, "gain": 0.2}
    assert judge(ev, GATES)[9]["passed"]
    # 升了但不到默认的 0.1：和随机因子分不开，不过
    ev["gain"]["recentSharpe"] = {"base": 1.0, "with": 1.05, "gain": 0.05}
    assert not judge(ev, GATES)[9]["passed"]
    assert judge(ev, GATES)[9]["need"] == "加进交易方案后搜索段 2022-01-01 起净 Sharpe 增量 > 0.1"
    ev["gain"]["recentSharpe"] = {"base": 1.0, "with": 1.2, "gain": 0.2}
    # 阈值抬到 0.3：升 0.2 不够
    g9 = judge(ev, Thresholds(min_samples=30, sharpe_gain_min=0.3))[9]
    assert not g9["passed"] and g9["need"].endswith("> 0.3")


def test_g6_compares_against_observing_candidates(planted_pool, planted_vol, planted_grid):
    # 观察区里已经有一条成交量均线：换个写法再交一遍，和它截面上几乎一样，G6 拒
    f = planted_pool.values(parse("Mean($volume, 4)", COLS)).f[planted_pool.in_search]
    observing = Combo.empty(planted_pool.search, planted_pool.symbols).plus("vol_ma", f)
    ev = evaluate(parse("EMA($volume, 8)", COLS), planted_pool, planted_vol, PARAMS, planted_grid, observing=observing)
    assert ev["redundancy"]["with"] == "obs:vol_ma" and ev["redundancy"]["maxAbs"] > 0.9
    assert not judge(ev, GATES)[6]["passed"]
    [(name, corr)] = cross_corr(planted_pool.search, f, observing, PARAMS)
    assert name == "vol_ma" and corr == pytest.approx(1.0)
    # 同名的两条各比各的，不会互相盖掉
    twins = observing.plus("vol_ma", -f)
    assert [n for n, _ in cross_corr(planted_pool.search, f, twins, PARAMS)] == ["vol_ma", "vol_ma"]


def test_g2_needs_a_clear_top_minus_bottom_and_t(planted):
    ev = copy.deepcopy(planted)
    main = main_of(ev)["ic"]
    g2 = judge(ev, GATES)[2]
    assert g2["passed"] and g2["value"]["shape"] == "monotone" and "三组形状：三组按序排开" in g2["note"]
    # 截尾后顶减底分不开（t 不到 2），或者和 IC 反向：不过
    for spread_t in (1.9, -5.0):
        main["monotonic"]["spreadT"] = spread_t
        assert not judge(ev, GATES)[2]["passed"]
    # 形状只报不判：中间组显著反了照样过，备注里写出来
    main["monotonic"].update(spreadT=5.0, steps=[5.0, -3.0])
    g2 = judge(ev, GATES)[2]
    assert g2["passed"] and g2["value"]["shape"] == "midHigh" and "中间组显著强过强的一头" in g2["note"]
    # t = ICIR × √月数 不到 3：不过
    main["icir"] = 2.9 / np.sqrt(main["months"])
    g2 = judge(ev, GATES)[2]
    assert not g2["passed"] and "t ≥ 3；" in g2["need"] and g2["value"]["t"] == pytest.approx(2.9)


def test_g3_needs_the_last_segment_in_the_same_direction(planted):
    ev = copy.deepcopy(planted)
    segs = main_of(ev)["ic"]["segments"]
    assert judge(ev, GATES)[3]["passed"]
    # 前三段同号、最近一段翻了：样本内已经在衰减，不过
    segs[-1] = -abs(segs[-1])
    g3 = judge(ev, GATES)[3]
    assert not g3["passed"] and g3["value"]["sameSign"] == 3 and "最后一段不同号" in g3["note"]


def test_evaluate_output_is_plain_data(planted_pool, planted_vol, planted_panels):
    products = {s: truncate(planted_panels[s], planted_pool.cut_ms) for s in PRODUCTS}
    ev = evaluate(parse("Mean($volume, 4) / 2", COLS), planted_pool, planted_vol, PARAMS, None, None, products,
                  holdout=True)
    assert ev["expr"] == "(Mean($volume, 4) / 2)" and ev["canonical"] == "(Mean($volume, w) / c)"
    assert ev["columns"] == ["volume"] and ev["complexity"] == {"nodes": 4, "windows": 1, "constants": 1, "cross": 0}
    assert set(ev["horizons"]) == {24, 48, 96} and ev["gain"] is None and ev["primary"] == 96
    # 参照网格没算好：G6 不过
    assert ev["redundancy"] == {"maxAbs": None, "with": None, "ready": False, "top": []}
    g6 = judge(ev, GATES)[6]
    assert not g6["passed"] and g6["note"] == "参照网格还没算好"
    # 时序报告：产品币按币算，只出数字
    ts = ev["timeseries"]
    assert ts["symbols"] == list(PRODUCTS) and set(ts["horizons"]) == {24, 48, 96}
    assert ts["horizons"][48]["mean"] > 0.3 and set(ts["horizons"][48]["bySymbol"]) == set(PRODUCTS)
    held = ev["holdout"]
    assert held["since"] == planted_pool.cut_ms and held["horizons"][48]["mean"] > 0.3 and held["horizons"][48]["samples"] > 0


def test_redundancy_and_gain_see_the_library(planted_pool, planted_vol, planted_grid):
    pick = planted_pool.in_search
    twin = planted_pool.values(parse("$volume * 3 + 1", COLS)).f[pick]
    library = scheme.Base.of(Combo.empty(planted_pool.search, planted_pool.symbols).plus("twin", twin),
                             planted_pool.values(scheme.CARRY).f[pick], planted_vol[pick], PARAMS, 0.25)
    ev = evaluate(parse("$volume", COLS), planted_pool, planted_vol, PARAMS, planted_grid, library,
                  peer_exprs={"lib:twin": "(($volume * 3) + 1)"})
    assert ev["redundancy"]["with"] == "lib:twin" and ev["redundancy"]["maxAbs"] == pytest.approx(1.0)
    assert not judge(ev, GATES)[6]["passed"]
    # 最像的几个按 |ρ| 从大到小，各带表达式：库里那条排第一，参照网格里那条也在
    top = ev["redundancy"]["top"]
    assert top[0] == {"name": "lib:twin", "abs": ev["redundancy"]["maxAbs"], "expr": "(($volume * 3) + 1)"}
    assert [x["name"] for x in top] == ["lib:twin", "noise4"] and 0 < top[1]["abs"] < top[0]["abs"]
    # 和库里的一样，交易方案加不进新东西
    assert ev["gain"]["sharpe"]["base"] == pytest.approx(library.result["sharpe"])
    assert abs(ev["gain"]["sharpe"]["gain"]) < 1e-6


def test_cross_section_expression_and_hidden_lookahead(planted_pool, planted_vol, planted_grid, monkeypatch):
    ev = evaluate(parse("CsZ(Mean($volume, 4)) - CsRank($trades)", COLS), planted_pool, planted_vol, PARAMS,
                  planted_grid)
    assert ev["lookaheadFree"] and ev["complexity"]["cross"] == 2 and main_of(ev)["ic"]["mean"] > 0.3
    monkeypatch.setitem(ops.OPERATORS, "Lead", ops.Op("rolling", lambda x, n: x.shift(-n)))
    leaky = evaluate(parse("CsRank(Lead($ret, 1))", COLS), planted_pool, planted_vol, PARAMS, planted_grid)
    assert not leaky["lookaheadFree"] and not judge(leaky, GATES)[0]["passed"]


def test_gates_structural_checks(planted):
    ev = planted
    assert not judge(ev, GATES, known_canonicals={"$volume"})[8]["passed"]
    fat = copy.deepcopy(ev)
    fat["complexity"] = {"nodes": 41, "windows": 1, "constants": 0, "cross": 0}
    assert not judge(fat, GATES)[7]["passed"]
    leaky = copy.deepcopy(ev)
    leaky["lookaheadFree"] = False
    g0 = judge(leaky, GATES)[0]
    assert not g0["passed"] and g0["value"] is False
    dup = copy.deepcopy(ev)
    dup["redundancy"] = {"maxAbs": 0.9, "with": "x", "ready": True}
    assert not judge(dup, GATES)[6]["passed"]


def test_apply_fdr_flips_the_marginal_one(planted):
    ev = copy.deepcopy(planted)
    ev["gates"] = judge(ev, GATES)
    batch = []
    for pct in (0.999, 0.955, 0.5, 0.4, 0.3):
        e = copy.deepcopy(ev)
        g5 = next(g for g in e["gates"] if g["id"] == "G5")
        g5["value"]["permPct"] = pct
        g5["passed"] = pct >= 0.95
        batch.append(e)
    apply_fdr(batch, q=0.10, iterations=1000)
    g5_flags = [next(g for g in e["gates"] if g["id"] == "G5")["passed"] for e in batch]
    assert g5_flags == [True, False, False, False, False]
    assert batch[0]["passed"] and not batch[1]["passed"]
    assert "BH" in next(g for g in batch[1]["gates"] if g["id"] == "G5")["note"]


def test_apply_fdr_extra_tests_only_make_it_stricter(planted):
    """被评审打回改掉的旧版本按 p=1 占位：m 变大，边缘的那条就过不去"""
    ev = copy.deepcopy(planted)
    ev["gates"] = judge(ev, GATES)

    def with_pct(pct: float) -> dict:
        e = copy.deepcopy(ev)
        g5 = next(g for g in e["gates"] if g["id"] == "G5")
        g5["value"]["permPct"], g5["passed"] = pct, True
        return e

    batch = [with_pct(0.988), with_pct(0.97)]  # p = 0.012、0.03
    apply_fdr(batch, q=0.10, iterations=1000)
    assert [e["passed"] for e in batch] == [True, True]
    batch = [with_pct(0.988), with_pct(0.97)]
    apply_fdr(batch, q=0.10, iterations=1000, extra_tests=6)  # m = 8，第二条的门槛变成 0.025
    assert [e["passed"] for e in batch] == [True, False]


def test_a_real_factor_survives_batch_fdr_at_production_k(planted, planted_pool, planted_vol, planted_grid):
    """默认 K=3：一条真信号加两条噪音一起做 BH，真信号必须还能过"""
    good = copy.deepcopy(planted)
    good["gates"] = judge(good, GATES)
    batch = [good]
    for seed in (1, 2):
        params = EvalParams(test_size=96, embargo=4, min_train=500, iterations=200, seed=seed, holdout_days=10)
        noise = evaluate(parse("$trades", COLS), planted_pool, planted_vol, params, planted_grid)
        noise["gates"] = judge(noise, GATES)
        batch.append(noise)
    apply_fdr(batch, q=0.10, iterations=200)
    assert batch[0]["passed"], [g for g in batch[0]["gates"] if not g["passed"]]
