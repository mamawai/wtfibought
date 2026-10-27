from wiib_alpha.trade import sizing

# sim 表外的默认档；BTC 的头两档
DEFAULT = [{"notionalFloor": 0, "notionalCap": 1_000_000_000, "maxLeverage": 10, "mmr": 0.025}]
BTC = [{"notionalFloor": 0, "notionalCap": 300_000, "maxLeverage": 150, "mmr": 0.004},
       {"notionalFloor": 300_000, "notionalCap": 800_000, "maxLeverage": 100, "mmr": 0.005}]


def test_the_default_setting_keeps_the_backtest_notional():
    # 两条腿多空都满：Σ|w| = 2
    weights = {"BTCUSDT": 0.5, "AAA": 0.5, "BBB": -0.5, "CCC": -0.5}
    brackets = {s: BTC if s == "BTCUSDT" else DEFAULT for s in weights}
    out = sizing.size(weights, brackets, 4, 0.5, 10000.0)
    assert (out.leverage, out.margin, out.multiple) == (4, 0.5, 1.0)
    # 档位上限是篮子里最低的；强平距离：按名义平均的维持保证金率 (0.004 + 3 × 0.025) / 4，2 / (0.5 × 2 × (0.075 + 0.01975))
    assert out.caps == {"bracket": 10, "stress": 21}
    assert out.view() == {"leverage": 4, "margin": 0.5, "multiple": 1.0, "caps": {"bracket": 10, "stress": 21}}
    # 篮子是空的：按设定，没有上限；净权重全是 0（份额 0 的那条腿）一样
    empty = sizing.size({}, {}, 8, 0.5, 10000.0)
    assert (empty.leverage, empty.multiple, empty.caps) == (8, 2.0, {"bracket": None, "stress": None})
    assert sizing.size({"AAA": 0.0}, {"AAA": DEFAULT}, 8, 0.5, 10000.0) == empty


def test_a_setting_above_a_cap_trades_at_the_cap():
    weights = {"BTCUSDT": 0.5, "AAA": 0.5, "BBB": -0.5, "CCC": -0.5}
    brackets = {s: BTC if s == "BTCUSDT" else DEFAULT for s in weights}
    # 20 倍：表外的币最高 10 倍，按 10 倍下，名义 0.5 × 10 / 2
    out = sizing.size(weights, brackets, 20, 0.5, 10000.0)
    assert (out.leverage, out.multiple, out.caps["bracket"]) == (10, 2.5, 10)
    # 只有 BTC 的篮子、保证金 95%：档位给到 150，强平距离只给 2 / (0.95 × 1 × (0.075 + 0.004)) = 26.6
    out = sizing.size({"BTCUSDT": 1.0}, {"BTCUSDT": BTC}, 50, 0.95, 10000.0)
    assert (out.leverage, out.caps) == (26, {"bracket": 150, "stress": 26})
    # 档位按名义取：净值 100 万、权重 0.6、4 倍、保证金一半，BTC 名义 60 万落在二档，最高 100 倍、维持保证金率 0.5%
    out = sizing.size({"BTCUSDT": 0.6}, {"BTCUSDT": BTC}, 4, 0.5, 1_000_000.0)
    assert (out.leverage, out.caps) == (4, {"bracket": 100, "stress": 83})
