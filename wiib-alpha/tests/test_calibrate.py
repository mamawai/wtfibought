import sqlite3

import numpy as np
import pytest

from wiib_alpha import calibrate
from wiib_alpha.data.panel import BENCHMARK, FEATURE_COLUMNS, TARGET_COLUMNS
from wiib_alpha.dsl import parse
from wiib_alpha.eval.combo import Combo
from wiib_alpha.eval.evaluate import evaluate
from wiib_alpha.eval.params import EvalParams
from wiib_alpha.store import SCHEMA
from wiib_alpha.trade import scheme


def test_synthetic_store_has_the_real_layout_and_a_planted_carry(tmp_path):
    store, universe = calibrate.synthetic_store(tmp_path, coins=25, days=40, seed=1, beta=2e-4)
    df = store.load("C001USDT")
    assert len(df) == 40 * 96 and df.index.name == "close_time" and (df["in_pool"] == 1.0).all()
    assert set(FEATURE_COLUMNS) <= set(df.columns) and set(TARGET_COLUMNS) <= set(df.columns)
    assert (df["high"] >= df[["open", "close"]].max(axis=1)).all() and df["liq_count"].isna().all()
    # 第一个币当 BTC 基准：它自己的 btc 两列是空的，别的币拿它的收益
    btc = store.load(BENCHMARK)
    assert btc["btc_ret"].isna().all() and np.allclose(df["btc_ret"].dropna(), btc["ret"].dropna())
    # 池子表每个月 25 个币，排名按成交额
    assert universe.groupby("month").size().tolist() == [25, 25] and universe["rank"].max() == 25
    # 植入的信号：费率截面排名高的币之后涨得多
    pool = calibrate.load_pool(store, universe, holdout_days=5)
    params = EvalParams(test_size=96, embargo=4, min_train=500, iterations=50, holdout_days=5)
    ev = evaluate(parse("$funding_rate", FEATURE_COLUMNS), pool, pool.values(scheme.VOL).f, params)
    assert ev["horizons"][ev["primary"]]["ic"]["mean"] > 0.1


def test_g9_prints_the_gain_of_the_library_candidates_and_random_factors(tmp_path, capsys):
    store, universe = calibrate.synthetic_store(tmp_path / "data", coins=25, days=40, seed=1, beta=2e-4)
    conn = sqlite3.connect(tmp_path / "alpha.sqlite")
    for statement in SCHEMA:
        conn.execute(statement)
    conn.executemany("INSERT INTO universe(month, symbol, rank, avg_quote_volume) VALUES (?, ?, ?, ?)",
                     list(universe.itertuples(index=False)))
    conn.execute("INSERT INTO factor(id, name, hypothesis, expr, canonical, columns_json, eval_json, gates_json, "
                 "approved_at, run_id) VALUES ('f1', 'carry', 'h', '$funding_rate', '', '[]', '{}', '[]', 1, 'r')")
    # 入库那条的候选记录不再算一遍；同一个表达式交了两次只算一次
    conn.executemany("INSERT INTO candidate(id, run_id, seq, name, hypothesis, expr, status, updated_at) "
                     "VALUES (?, 'r', 1, ?, 'h', ?, 'rejected_gates', 1)",
                     [("c1", "carry", "$funding_rate"), ("c2", "vol", "Mean($volume, 4)"),
                      ("c3", "vol_again", "Mean($volume, 4)")])
    conn.commit()
    conn.close()
    params = EvalParams(test_size=96, embargo=4, min_train=500, iterations=0, holdout_days=5)
    calibrate.g9(store, str(tmp_path / "alpha.sqlite"), params, 2, 0)
    out = capsys.readouterr().out
    assert "基线：库里 1 条 + carry" in out and "  carry: " in out
    assert "== 库外候选：1 条 ==" in out and "vol_again  Mean($volume, 4)" in out
    # 两组各出 2022 起（G9 判这份）和全段两行分位数
    assert "== 随机因子：2 条 ==" in out and out.count("2022-01-01 起增量：中位") == 2 and out.count("全段增量：中位") == 2


def test_g9_gains_skips_the_candidates_when_told_and_matches_one_by_one(planted_pool, planted_vol):
    """候选给 None 这次不算；随机因子几条一起算，结果按交进去的顺序、和逐条单算的一样，进度一条一条加"""
    pick = planted_pool.in_search
    params = EvalParams(test_size=96, embargo=4, min_train=500, iterations=0, holdout_days=10)
    planted = planted_pool.values(parse("Mean($volume, 4)", FEATURE_COLUMNS)).f[pick]
    vol, basis = planted_vol[pick], scheme.carry_signal(planted_pool)[pick]
    progress = {"done": 0}
    out = calibrate.g9_gains(planted_pool, vol, basis, {"planted": planted}, None, params, 0.25, 3, 0, progress)
    assert out["candidates"] is None and progress["done"] == 1 + 3
    assert [x["name"] for x in out["random"]["items"]] == ["rand000", "rand001", "rand002"]
    full = scheme.Base.of(Combo.empty(planted_pool.search, planted_pool.symbols).plus("planted", planted), basis, vol,
                          params, 0.25)
    for x in out["random"]["items"]:
        f = planted_pool.values(parse(x["expr"], FEATURE_COLUMNS)).f[pick]
        assert x["recent"]["gain"] == pytest.approx(scheme.gain(full, x["name"], f)["recentSharpe"]["gain"])


def test_random_expression_is_valid_and_not_degenerate(tmp_path):
    store, _ = calibrate.synthetic_store(tmp_path, coins=3, days=10, seed=2, beta=0.0)
    panel = store.load("C001USDT")
    rng = np.random.default_rng(3)
    for _ in range(5):
        expr = calibrate.random_expression(rng, panel)
        out = expr.evaluate(panel).to_numpy()
        assert np.isfinite(out).mean() > 0.5 and np.nanstd(out) > 0 and "funding_rate" not in expr.columns()
