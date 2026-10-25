import pytest
from fakes import FUNDING_MS, FakeFapi

from wiib_alpha.data import binance as b
from wiib_alpha.data.binance import BAR_MS, DAY_MS
from wiib_alpha.eval.params import EvalParams
from wiib_alpha.trade import attrib

# 2025-03-02 00:00 UTC：信号是 3 月 1 日最后一根，3 月的榜
DAY = 1_740_873_600_000
PARAMS = EvalParams()


@pytest.fixture(autouse=True)
def no_rest_gap(monkeypatch):
    monkeypatch.setattr(b, "REST_GAP_S", 0.0)


def run(day: str, signal_ms: int, equity: float, coins: dict[str, tuple[float, float]]) -> dict:
    """coins 是 币 → (组合腿权重, carry 腿权重)，都已乘份额"""
    return {"day": day, "signal_ms": signal_ms, "equity_before": equity,
            "targets": {"coins": [{"symbol": s, "combo": c, "carry": k} for s, (c, k) in coins.items()]}}


def test_attribute_splits_a_day_into_legs_by_backtest_rules():
    today = run("2025-03-02", DAY - 1, 10000.0, {"A": (0.3, 0.1), "B": (-0.3, 0.0), "C": (0.0, -0.1), "E": (0.05, 0.0),
                                                 "Z": (0.0, 0.0)})
    prev = run("2025-03-01", DAY - DAY_MS - 1, 9900.0, {"A": (0.3, 0.0), "D": (0.2, 0.0)})
    nxt = run("2025-03-03", DAY + DAY_MS - 1, 10650.0, {})
    closes = {"A": (100.0, 110.0), "B": (100.0, 90.0), "C": (50.0, 50.0)}
    funding = {"A": 0.001, "B": -0.002, "C": 0.0005}
    out = attrib.attribute(today, nxt, prev["targets"], closes, funding, {"A": 1, "B": 2, "C": 3, "D": 1}, PARAMS)
    combo, carry = out["legs"]["combo"], out["legs"]["carry"]
    # 价格：A 多 0.3 涨 10%、B 空 0.3 跌 10% 各赚 300；carry 腿只有 A 的 0.1
    assert (combo["price"], carry["price"]) == (pytest.approx(600), pytest.approx(100))
    # 资金费多头付、空头收：A 付 0.001、B 空头在负费率下也付 0.002
    assert (combo["funding"], carry["funding"]) == (pytest.approx(-9), pytest.approx(-0.5))
    # 换仓按 |Δw| 付当月档的单边成本：组合腿 B 新开 0.3 二档 8 bps、D 平掉 0.2 一档 5 bps、E 新开 0.05 不在池按三档；
    # carry 腿 A 加 0.1 一档、C 新开 0.1 三档
    assert combo["cost"] == pytest.approx(0.3 * 8 + 0.2 * 5 + 0.05 * 12)
    assert carry["cost"] == pytest.approx(0.1 * 5 + 0.1 * 12)
    assert combo["net"] == pytest.approx(600 - 9 - combo["cost"]) and carry["net"] == pytest.approx(100 - 0.5 - 1.7)
    # 每条腿的日收益按自己那份资金算
    assert combo["ret"] == pytest.approx(combo["net"] / 7500) and carry["ret"] == pytest.approx(carry["net"] / 2500)
    assert out["model"] == pytest.approx(combo["net"] + carry["net"])
    assert (out["actual"], out["until"]) == (650, "2025-03-03") and out["residual"] == pytest.approx(650 - out["model"])
    # 没拉到价格的 E 记成 missing，权重为 0 的 Z 不算持仓
    assert out["missing"] == ["E"] and [c["symbol"] for c in out["coins"]] == ["A", "B", "C"]
    a = out["coins"][0]
    assert (a["entry"], a["exit"], a["ret"], a["fund"]) == (100, 110, pytest.approx(0.1), 0.001)
    assert a["legs"] == {"combo": pytest.approx(300 - 3), "carry": pytest.approx(100 - 1)}


def test_first_day_has_no_previous_targets_and_pays_to_open_everything():
    today = run("2025-03-02", DAY - 1, 10000.0, {"A": (0.3, -0.1)})
    nxt = run("2025-03-03", DAY + DAY_MS - 1, 10000.0, {})
    out = attrib.attribute(today, nxt, None, {"A": (100.0, 100.0)}, {"A": 0.0}, {"A": 2}, PARAMS)
    assert out["legs"]["combo"]["cost"] == pytest.approx(0.3 * 8) and out["legs"]["carry"]["cost"] == pytest.approx(0.1 * 8)
    assert out["model"] == pytest.approx(-3.2) and out["residual"] == pytest.approx(3.2)


def test_leg_returns_use_the_share_recorded_that_day():
    nxt = run("2025-03-03", DAY + DAY_MS - 1, 10000.0, {})
    half = run("2025-03-02", DAY - 1, 10000.0, {"A": (0.5, 0.5)})
    half["targets"]["carryShare"] = 0.5
    out = attrib.attribute(half, nxt, None, {"A": (100.0, 110.0)}, {"A": 0.0}, {"A": 1}, PARAMS)
    assert out["legs"]["combo"]["ret"] == pytest.approx(out["legs"]["combo"]["net"] / 5000)
    assert out["legs"]["carry"]["ret"] == pytest.approx(out["legs"]["carry"]["net"] / 5000)
    # 份额 0 的腿没有资金，收益率记 0
    none = run("2025-03-02", DAY - 1, 10000.0, {"A": (1.0, 0.0)})
    none["targets"]["carryShare"] = 0.0
    out = attrib.attribute(none, nxt, None, {"A": (100.0, 110.0)}, {"A": 0.0}, {"A": 1}, PARAMS)
    assert out["legs"]["carry"]["ret"] == 0.0
    assert out["legs"]["combo"]["ret"] == pytest.approx(out["legs"]["combo"]["net"] / 10000)


def test_inputs_take_the_0015_closes_and_the_settlements_in_between():
    fake = FakeFapi(["AAA", "BBB"], DAY + 2 * DAY_MS)
    today = run("2025-03-02", DAY - 1, 10000.0, {"AAA": (0.2, 0.0), "BBB": (0.0, -0.1), "GONE": (0.1, 0.0)})
    nxt = run("2025-03-03", DAY + DAY_MS - 1, 10000.0, {})
    closes, funding = attrib.inputs(fake.binance(), today, nxt)
    # 建仓价是 00:15 那根收盘（开在 0 点的那根），平仓价是次日同一根；下架的 GONE fapi 回 400，不出
    assert set(closes) == set(funding) == {"AAA", "BBB"}
    assert closes["AAA"] == (pytest.approx(fake.close("AAA", DAY)), pytest.approx(fake.close("AAA", DAY + DAY_MS)))
    # 持有期 (00:15, 次日 00:15] 里结算了 08:00、16:00、次日 00:00 三次；当天 0 点那次建仓前已经结算，不算
    settled = [DAY + k * FUNDING_MS for k in (1, 2, 3)]
    assert funding["BBB"] == pytest.approx(sum(fake.rate("BBB", t) for t in settled))
    # K 线一个币一次拉完（97 根，limit 99），费率一个币一次
    assert sum(1 for path, p in fake.log if path == "/fapi/v1/klines" and p["symbol"] == "AAA") == 1
    klines = next(p for path, p in fake.log if path == "/fapi/v1/klines" and p["symbol"] == "AAA")
    assert (int(klines["startTime"]), klines["limit"]) == (DAY, "99")
    # GONE 的 K 线就拉不到，费率不再问
    assert sum(1 for path, _ in fake.log if path == "/fapi/v1/fundingRate") == 2


def test_inputs_raise_on_errors_other_than_400(monkeypatch):
    monkeypatch.setattr(b, "_RETRIES", 1)
    fake = FakeFapi(["AAA", "BBB"], DAY + 2 * DAY_MS)
    fake.broken = {"BBB"}
    today = run("2025-03-02", DAY - 1, 10000.0, {"AAA": (0.2, 0.0), "BBB": (0.0, -0.1)})
    nxt = run("2025-03-03", DAY + DAY_MS - 1, 10000.0, {})
    # 500 重试完还不行不当成拉不到：抛出去，这一天不记 missing
    with pytest.raises(b.BinanceError):
        attrib.inputs(fake.binance(), today, nxt)


def test_tiers_follow_the_signal_month_and_fall_back_to_the_month_before():
    universe = [{"month": "2025-02", "symbol": "AAA", "rank": 1}, {"month": "2025-02", "symbol": "BBB", "rank": 30},
                {"month": "2025-03", "symbol": "AAA", "rank": 60}, {"month": "2025-01", "symbol": "CCC", "rank": 1}]
    # 信号在 3 月 1 日那根：AAA 用 3 月的档，BBB 3 月不在池用 2 月的，CCC 两个月都不在
    assert attrib.tiers(universe, DAY - 1) == {"AAA": 3, "BBB": 2}
    # 3 月 1 日的调仓信号是 2 月最后一根：用 2 月的榜，1 月兜底
    assert attrib.tiers(universe, DAY - DAY_MS - 1) == {"AAA": 1, "BBB": 2, "CCC": 1}


def test_summary_tracks_each_leg_against_its_stop_line():
    def day(combo: float, carry: float) -> dict:
        return {"legs": {"combo": {"ret": combo}, "carry": {"ret": carry}}}
    rows = [day(0.10, -0.20), day(-0.20, -0.25), day(0.05, 0.10)]
    out = attrib.summary(rows)
    combo, carry = out["combo"], out["carry"]
    # 组合腿 1.1 → 0.88 → 0.924：高点 1.1，现在回撤 16%，最深 20%，没到 22.8%
    assert combo["days"] == 3 and combo["cumReturn"] == pytest.approx(-0.076)
    assert (combo["drawdown"], combo["maxDrawdown"]) == (pytest.approx(0.16), pytest.approx(0.2))
    assert (combo["stop"], combo["hit"]) == (0.228, False)
    # carry 腿 0.8 → 0.6 → 0.66：最深回撤 40%，碰过 37% 的线就算碰了，回来一点也不算没碰
    assert carry["maxDrawdown"] == pytest.approx(0.4) and carry["drawdown"] == pytest.approx(0.34)
    assert (carry["stop"], carry["hit"]) == (0.37, True)
    assert attrib.summary([])["combo"] == {"days": 0, "cumReturn": 0.0, "drawdown": 0.0, "maxDrawdown": 0.0,
                                           "stop": 0.228, "hit": False}
