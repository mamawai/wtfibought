import numpy as np
import pandas as pd
import pytest

from wiib_alpha import dsl, ops
from wiib_alpha.dsl import DslError, parse

COLS = ["close", "volume", "ret"]


def frame(n: int = 40, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    close = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, n)))
    return pd.DataFrame({"close": close, "volume": rng.uniform(1, 10, n),
                         "ret": np.log(close / np.roll(close, 1))}, index=np.arange(n) * 15)


def ev(text: str, df: pd.DataFrame) -> np.ndarray:
    return parse(text, COLS).evaluate(df).to_numpy()


# ========== 解析、规范形、复杂度 ==========


def test_parse_renders_canonical_and_counts():
    e = parse("Mean($close, 48) / $close - Quantile($volume, 96, 0.8) * 2", COLS)
    assert str(e) == "((Mean($close, 48) / $close) - (Quantile($volume, 96, 0.8) * 2))"
    assert e.canonical() == "((Mean($close, w) / $close) - (Quantile($volume, w, c) * c))"
    assert e.columns() == {"close", "volume"}
    # 不同的节点：Bin, Bin, Call, Col（$close 两处算一个）, Bin, Call, Col, Const = 8；窗口 2；常量 = Const 1 + 分位 1
    assert e.complexity() == {"nodes": 8, "windows": 2, "constants": 2, "cross": 0}


def test_same_shape_different_window_is_same_canonical():
    a = parse("Mean($close, 4) / $close", COLS)
    b = parse("Mean($close, 96) / $close", COLS)
    assert a.canonical() == b.canonical() and str(a) != str(b)
    # 加法、乘法换个左右也是同一个规范形；减法、除法不是
    assert parse("$close * Mean($volume, 4)", COLS).canonical() == parse("Mean($volume, 8) * $close", COLS).canonical()
    assert parse("$close + $ret", COLS).canonical() == parse("$ret + $close", COLS).canonical()
    assert parse("$close - $ret", COLS).canonical() != parse("$ret - $close", COLS).canonical()
    assert str(parse("$ret * $close", COLS)) == "($ret * $close)"


@pytest.mark.parametrize("text, message", [
    ("Mean(close, 5)", "列要写成"),
    ("Mean($nope, 5)", "没有这个列"),
    ("Foo($close, 5)", "没有这个算子"),
    ("Mean($close, window=5)", "关键字"),
    ("$close ** 2", "幂用 Pow"),
    ("Mean($close, 0)", "窗口"),
    ("Mean($close, 5000)", "窗口"),
    ("Mean($close, 5.5)", "窗口"),
    ("Mean($close, -5)", "窗口"),
    ("Quantile($close, 5, 1.5)", "分位"),
    ("Mean($close)", "参数"),
    ("Corr($close, 5)", "参数"),
    ("$close + 'a'", "不支持的写法"),
    ("df.close", "不支持的写法"),
    ("$close > 1", "不支持的写法"),
    ("1 + 2", "至少引用一个列"),
    ("Mean($close, 5", "语法错误"),
    ("$", "语法错误"),
    ("__import__('os')", "没有这个算子"),
    ("__col__close", "列要写成"),
    ("Abs(" * 40 + "$close" + ")" * 40, "节点 41"),
    (" + ".join(f"Mean($close, {w})" for w in range(1, 8)), "窗口 7"),
    ("$close + 1 + 2 + 3 + 4 + 5 + 6 + 7", "常量 7"),
    ("CsRank($close) + CsZ($volume) - CsDemean($ret)", "截面算子 3"),
    ("CsRank($close, 4)", "参数"),
])
def test_rejects(text, message):
    with pytest.raises(DslError, match=message):
        parse(text, COLS)


def test_complexity_counts_distinct_and_limits_are_inclusive():
    # 同一个子式、窗口值、常量值重复写不多算
    same = parse("Mean($close, 4) / Sum($close, 4) + Mean($close, 4) * 2 - $volume * 2", COLS)
    assert same.complexity() == {"nodes": 10, "windows": 1, "constants": 1, "cross": 0}
    parse("Abs(" * 39 + "$close" + ")" * 39, COLS)                      # 40 个节点
    parse(" + ".join(f"Mean($close, {w})" for w in range(1, 7)), COLS)  # 6 个窗口
    parse("$close + 1 + 2 + 3 + 4 + 5 + 6", COLS)                       # 6 个常量
    parse("CsRank(CsZ($close))", COLS)                                  # 2 个截面算子


def test_text_limit_is_measured_on_the_rewritten_form():
    # 原文 487 字，重写后每个乘号多一对括号变成 625 字；存下来的是重写后的样子，读回来得过不了才一致
    text = " * ".join(["$ret"] * 70)
    assert len(text) <= dsl.MAX_TEXT
    with pytest.raises(DslError, match="超过 500 字"):
        parse(text, COLS)
    short = parse(" * ".join(["$ret"] * 30), COLS)
    assert str(parse(str(short), COLS)) == str(short)


# ========== 截面算子 ==========


def test_cross_ops_by_moment():
    x = pd.Series([1.0, 3.0, 2.0, 5.0, 5.0, np.nan])
    t = np.array([0, 0, 0, 1, 1, 1])
    assert ops.OPERATORS["CsRank"].fn(x, t).tolist()[:5] == pytest.approx([1 / 3, 1.0, 2 / 3, 0.75, 0.75])
    assert ops.OPERATORS["CsDemean"].fn(x, t).tolist()[:5] == pytest.approx([-1.0, 1.0, 0.0, 0.0, 0.0])
    z = ops.OPERATORS["CsZ"].fn(x, t)
    assert z.tolist()[:3] == pytest.approx([-1.0, 1.0, 0.0])
    # 那一刻全一样：标准差 0 出 NaN；NaN 还是 NaN
    assert np.isnan(z.iloc[3:]).all() and np.isnan(ops.OPERATORS["CsRank"].fn(x, t).iloc[5])


def test_cross_nodes_inner_first_and_deduplicated():
    inner, outer = parse("CsRank(Mean(CsZ($close), 2))", COLS).cross_nodes()
    assert str(dsl.Expr(inner)) == "CsZ($close)" and str(dsl.Expr(outer)) == "CsRank(Mean(CsZ($close), 2))"
    twice = parse("CsZ($close) * CsZ($close)", COLS)
    assert twice.complexity()["cross"] == len(twice.cross_nodes()) == 1


def test_evaluate_takes_cross_values_and_nan_outside_the_pool():
    e = parse("Mean(CsZ($close), 2) + $close", COLS)
    node = e.cross_nodes()[0]
    df = frame(5)
    # 这个币只在后三根在池：截面值只给那三根，其余时刻是 NaN
    out = e.evaluate(df, {node: pd.Series([1.0, 2.0, 3.0], index=df.index[2:])}).to_numpy()
    close = df["close"].to_numpy()
    assert np.isnan(out[:3]).all() and out[3:] == pytest.approx([1.5 + close[3], 2.5 + close[4]])
    with pytest.raises(KeyError):
        e.evaluate(df)


# ========== 每个算子对手算值 ==========


def test_arithmetic_and_neg_and_div_zero():
    df = pd.DataFrame({"close": [2.0, 4.0], "volume": [0.0, 2.0], "ret": [0.0, 0.0]})
    assert ev("-$close + 1", df).tolist() == [-1.0, -3.0]
    out = ev("$close / $volume", df)
    assert np.isnan(out[0]) and out[1] == 2.0


def test_unary_ops():
    df = pd.DataFrame({"close": [-2.0, 0.0, np.e], "volume": [1.0] * 3, "ret": [0.0] * 3})
    assert ev("Abs($close)", df).tolist() == [2.0, 0.0, np.e]
    assert ev("Sign($close)", df).tolist() == [-1.0, 0.0, 1.0]
    out = ev("Log($close)", df)
    assert np.isnan(out[0]) and np.isnan(out[1]) and out[2] == pytest.approx(1.0)


def test_binary_and_if_with_nan():
    df = pd.DataFrame({"close": [1.0, 5.0, np.nan], "volume": [3.0, 2.0, 1.0], "ret": [0.0, 1.0, np.nan]})
    assert ev("Greater($close, $volume)", df)[:2].tolist() == [3.0, 5.0]
    assert ev("Less($close, $volume)", df)[:2].tolist() == [1.0, 2.0]
    assert np.isnan(ev("Greater($close, $volume)", df)[2])
    gt = ev("Gt($close, $volume)", df)
    assert gt[:2].tolist() == [0.0, 1.0] and np.isnan(gt[2])
    assert ev("Lt($close, $volume)", df)[:2].tolist() == [1.0, 0.0]
    assert ev("Pow($close, 2)", df)[:2].tolist() == [1.0, 25.0]
    chosen = ev("If($ret, $close, $volume)", df)
    assert chosen[:2].tolist() == [3.0, 5.0] and np.isnan(chosen[2])


def test_rolling_basics():
    x = np.array([1.0, 3.0, 2.0, 5.0, 4.0])
    df = pd.DataFrame({"close": x, "volume": x * 2, "ret": x})
    assert ev("Ref($close, 2)", df)[2:].tolist() == [1.0, 3.0, 2.0]
    assert ev("Delta($close, 1)", df)[1:].tolist() == [2.0, -1.0, 3.0, -1.0]
    assert ev("Mean($close, 3)", df)[2:].tolist() == [2.0, 10 / 3, 11 / 3]
    assert ev("Sum($close, 3)", df)[2:].tolist() == [6.0, 10.0, 11.0]
    assert ev("Std($close, 3)", df)[2] == pytest.approx(np.std(x[:3], ddof=1))
    assert ev("Var($close, 3)", df)[2] == pytest.approx(np.var(x[:3], ddof=1))
    assert ev("Max($close, 3)", df)[2:].tolist() == [3.0, 5.0, 5.0]
    assert ev("Min($close, 3)", df)[2:].tolist() == [1.0, 2.0, 2.0]
    assert ev("Med($close, 3)", df)[2:].tolist() == [2.0, 3.0, 4.0]
    assert ev("Mad($close, 3)", df)[2] == pytest.approx(np.abs(x[:3] - 2.0).mean())
    assert np.isnan(ev("Mean($close, 3)", df)[:2]).all()


def test_rank_quantile_idx():
    x = np.array([1.0, 3.0, 2.0, 3.0, 4.0])
    df = pd.DataFrame({"close": x, "volume": x, "ret": x})
    # 窗口 [3,2,3]：当前 3 的平均秩 2.5，/3
    assert ev("Rank($close, 3)", df)[3] == pytest.approx(2.5 / 3)
    assert ev("Rank($close, 3)", df)[4] == pytest.approx(1.0)
    assert ev("Quantile($close, 3, 0.5)", df)[2:].tolist() == [2.0, 3.0, 3.0]
    # 窗口 [1,3,2]：最大在中间，离现在 1 根；最小在最前，离现在 2 根
    assert ev("IdxMax($close, 3)", df)[2] == 1.0 and ev("IdxMin($close, 3)", df)[2] == 2.0
    assert ev("IdxMax($close, 3)", df)[4] == 0.0


def test_skew_kurt_match_pandas_on_window():
    df = frame(12)
    win = df["close"].iloc[2:7]
    assert ev("Skew($close, 5)", df)[6] == pytest.approx(win.skew())
    assert ev("Kurt($close, 5)", df)[6] == pytest.approx(win.kurt())


def test_ema_wma():
    x = np.array([1.0, 2.0, 3.0, 4.0])
    df = pd.DataFrame({"close": x, "volume": x, "ret": x})
    alpha = 2 / (3 + 1)
    e1 = 1.0
    e2 = alpha * 2 + (1 - alpha) * e1
    e3 = alpha * 3 + (1 - alpha) * e2
    out = ev("EMA($close, 3)", df)
    assert np.isnan(out[1]) and out[2] == pytest.approx(e3)
    assert ev("WMA($close, 3)", df)[2] == pytest.approx((1 * 1 + 2 * 2 + 3 * 3) / 6)


def test_ema_does_not_invent_values_at_nan():
    x = np.array([1.0, 2.0, 2.0, 5.0, np.nan, 7.0, 3.0, 3.0])
    df = pd.DataFrame({"close": x, "volume": x, "ret": x})
    out = ev("EMA($close, 3)", df)
    assert np.isnan(out[4]) and np.isfinite(out[3]) and np.isfinite(out[5])


def test_large_windows_stay_within_memory_budget():
    """分块算的算子在 2016 窗上不能实体化 (n, w)；这里只验结果与小窗口口径一致"""
    df = frame(3000, seed=3)
    for name in ("Mad", "IdxMax", "IdxMin", "Rsquare"):
        out = ev(f"{name}($close, 2016)", df)
        assert np.isnan(out[:2015]).all() and np.isfinite(out[2015:]).all()
    assert ev("IdxMax($close, 2016)", df)[2015] == 2015 - int(np.argmax(df["close"].to_numpy()[:2016]))


def test_regression_ops_on_a_straight_line():
    x = np.array([1.0, 3.0, 5.0, 7.0, 9.0, 10.0])
    df = pd.DataFrame({"close": x, "volume": x, "ret": x})
    assert ev("Slope($close, 4)", df)[3] == pytest.approx(2.0)
    assert ev("Rsquare($close, 4)", df)[3] == pytest.approx(1.0)
    assert ev("Resi($close, 4)", df)[3] == pytest.approx(0.0)
    # [5,7,9,10]：斜率 1.7，均值 7.75，拟合末点 7.75 + 1.7*1.5 = 10.3，残差 -0.3
    assert ev("Slope($close, 4)", df)[5] == pytest.approx(1.7)
    assert ev("Resi($close, 4)", df)[5] == pytest.approx(-0.3)
    flat = pd.DataFrame({"close": [2.0] * 4, "volume": [1.0] * 4, "ret": [0.0] * 4})
    assert np.isnan(ev("Rsquare($close, 3)", flat)[3])


def test_pair_ops():
    x = np.array([1.0, 2.0, 4.0, 3.0, 5.0])
    df = pd.DataFrame({"close": x, "volume": 2 * x + 1, "ret": -x})
    assert ev("Corr($close, $volume, 3)", df)[4] == pytest.approx(1.0)
    assert ev("Corr($close, $ret, 3)", df)[4] == pytest.approx(-1.0)
    assert ev("Cov($close, $volume, 3)", df)[4] == pytest.approx(2 * np.var(x[2:], ddof=1))


# ========== 前视自检：每个算子截断重算都不变 ==========


def expression_for(name: str) -> str:
    kind = ops.OPERATORS[name].kind
    return {
        "unary": f"{name}($close)",
        "binary": f"{name}($close, $volume)",
        "ternary": f"{name}($ret, $close, $volume)",
        "rolling": f"{name}($close, 5)",
        "quantile": f"{name}($close, 5, 0.3)",
        "pair": f"{name}($close, $volume, 5)",
        "cross": f"{name}(Mean($close, 3))",
    }[kind]


def single_coin_cross(expr: dsl.Expr, df: pd.DataFrame) -> dict:
    """只有这一个币的截面：截面节点的值就按这个币自己算"""
    out = {}
    for node in expr.cross_nodes():
        x = dsl.Expr(node.args[0]).evaluate(df, out).reset_index(drop=True)
        out[node] = pd.Series(ops.OPERATORS[node.name].fn(x, df.index.to_numpy()).to_numpy(), index=df.index)
    return out


@pytest.mark.parametrize("name", sorted(ops.OPERATORS))
def test_every_operator_is_lookahead_free(name):
    df = frame(60)
    expr = parse(expression_for(name), COLS)
    assert dsl.lookahead_free(expr, df, [6, 17, 40, 59], single_coin_cross(expr, df))


def test_lookahead_check_catches_a_leading_operator(monkeypatch):
    monkeypatch.setitem(ops.OPERATORS, "Lead", ops.Op("rolling", lambda x, n: x.shift(-n)))
    df = frame(60)
    assert not dsl.lookahead_free(parse("Lead($close, 1)", COLS), df, [6, 17, 40])
    assert not dsl.lookahead_free(parse("Mean($close, 3) - Lead($ret, 2)", COLS), df, [30])
    # 偷看藏在截面算子里面：截面值是全量算好传进来的，靠查它的输入抓
    hidden = parse("CsRank(Lead($ret, 1))", COLS)
    assert not dsl.lookahead_free(hidden, df, [30], single_coin_cross(hidden, df))


def test_evaluate_keeps_index_and_turns_inf_into_nan():
    df = frame(10)
    df.loc[df.index[3], "volume"] = 0.0
    out = parse("$close / $volume", COLS).evaluate(df)
    assert out.index.equals(df.index) and np.isnan(out.iloc[3])
    # 中间节点的 inf 也是 NaN：Gt 拿到 NaN 出 NaN，不会把 inf > 0 当成 1
    assert np.isnan(parse("Gt($close / $volume, 0)", COLS).evaluate(df).iloc[3])
