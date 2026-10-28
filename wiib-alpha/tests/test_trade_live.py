import numpy as np
import pandas as pd
import pytest
from fakes import FakeFapi

from wiib_alpha.data import binance as b
from wiib_alpha.data.binance import BAR_MS, DAY_MS
from wiib_alpha.data.panel import PanelStore, assemble
from wiib_alpha.trade import live

DAY0 = 1_790_812_800_000  # 2026-10-01 00:00 UTC
SYMBOLS = ["AAA", "BBB", "BTCUSDT"]


@pytest.fixture(autouse=True)
def no_rest_gap(monkeypatch):
    monkeypatch.setattr(b, "REST_GAP_S", 0.0)
    monkeypatch.setattr(b, "DATA_GAP_S", 0.0)
    monkeypatch.setattr(b, "SPOT_GAP_S", 0.0)


@pytest.fixture
def store(tmp_path) -> PanelStore:
    return PanelStore(tmp_path)


def test_update_fills_29_days_then_continues_after_live_and_keeps_30_days(store):
    # 归档到 10-25 0 点；10-26 0:20 第一次补：不管归档，从 29 天前补起，2784 根按 1000 根一窗
    fake = FakeFapi(SYMBOLS, DAY0 + 25 * DAY_MS + 20 * 60_000)
    fake.write_raw(store, DAY0, DAY0 + 24 * DAY_MS)
    # live 里有一条 40 天前的旧数据：断太久也从 29 天前补起，旧的滚掉
    store.save_live("premium_15m", "AAA", pd.DataFrame({"close_time": [DAY0 - 15 * DAY_MS - 1], "premium": [0.1]}))
    # BBB 没有现货对
    fake.spot.discard("BBB")
    progress: dict = {}
    live.update(store, fake.binance(), ["AAA", "BBB"], fake.now, progress)
    floor = fake.now - 29 * DAY_MS
    klines = [p for path, p in fake.log if path == "/fapi/v1/klines" and p["symbol"] == "AAA"]
    assert [(int(p["startTime"]), p["limit"]) for p in klines] == [(floor + i * 1000 * BAR_MS, "1000") for i in range(3)]
    # 现货 K 线同样从 29 天前补起，走现货接口；现货对表一次更新只问一次；没有现货对的 BBB 不补
    spot = [p for path, p in fake.log if path == "/api/v3/klines"]
    assert [(p["symbol"], int(p["startTime"])) for p in spot] == [("AAA", floor + i * 1000 * BAR_MS) for i in range(3)]
    assert [path for path, _ in fake.log].count("/api/v3/exchangeInfo") == 1
    assert store.load_live("spot_15m", "BBB") is None
    metrics = [p for path, p in fake.log if path == "/futures/data/openInterestHist" and p["symbol"] == "AAA"]
    # 接口时间戳晚归档一格，请求也往后挪一格；taker 买卖量比不晚，不挪
    assert int(metrics[0]["startTime"]) == floor + b.METRIC_MS
    taker = [p for path, p in fake.log if path == "/futures/data/takerlongshortRatio" and p["symbol"] == "AAA"]
    assert int(taker[0]["startTime"]) == floor
    # 费率也从 fapi 补
    funding = [p for path, p in fake.log if path == "/fapi/v1/fundingRate" and p["symbol"] == "AAA"]
    assert int(funding[0]["startTime"]) == floor
    # 09-27 0:20 之后第一根开盘的是 0:30
    first = DAY0 - 4 * DAY_MS + 45 * 60_000 - 1
    got = store.load_live("klines_15m", "AAA")
    assert got["close_time"].iloc[0] == first and got["close_time"].iloc[-1] < fake.now
    assert store.load_live("premium_15m", "AAA")["close_time"].min() == first
    assert store.load_live("spot_15m", "AAA")["close_time"].min() == first
    assert progress == {"total": 2, "done": 2, "symbol": None}
    # 第二天：从 live 最后一根之后接，不再从归档末尾
    fake.now += DAY_MS
    fake.log.clear()
    live.update(store, fake.binance(), ["AAA"], fake.now, progress)
    start = [int(p["startTime"]) for path, p in fake.log if path == "/fapi/v1/klines"]
    assert start == [int(got["close_time"].iloc[-1]) + 1]
    again = store.load_live("klines_15m", "AAA")
    assert again["close_time"].is_unique and np.all(np.diff(again["close_time"]) == BAR_MS)
    # raw/ 一条没动
    assert int(store.load_raw("klines_15m", "AAA")["close_time"].max()) == DAY0 + 24 * DAY_MS - 1


def test_window_reads_only_live_and_matches_assembling_everything(store):
    t = DAY0 + 25 * DAY_MS - 1
    fake = FakeFapi(SYMBOLS, t + 1 + 20 * 60_000)
    fake.write_raw(store, DAY0, DAY0 + 23 * DAY_MS)
    # 归档的持仓量、全体多空比、费率是坏的（假装出了事故）：窗口里都看不到，全是接口的数
    raw = store.load_raw("metrics", "AAA")
    store.save_raw("metrics", "AAA", raw.assign(oi=raw["oi"] * 2, ls_acct=-5.0))
    store.save_raw("funding", "AAA", pd.DataFrame({"funding_time": [t - 3_600_000], "funding_rate": [0.0002]}))
    fake.spot.discard("BBB")
    live.update(store, fake.binance(), SYMBOLS, fake.now, {})
    # 28 天的窗口跨到 9 月：两个月都在池才整段在池
    months = {s: frozenset({"2026-09", "2026-10"}) for s in SYMBOLS}
    panels = live.window(live.loader(store, t), ["AAA", "BBB"], months, t)
    assert set(panels) == {"AAA", "BBB"}
    a = panels["AAA"]
    assert a.index.min() == t - 28 * DAY_MS + BAR_MS and a.index.max() == t and a["in_pool"].eq(1.0).all()
    assert (a["ls_acct"] != -5.0).all()
    # 没有现货对的币三列空；归档里有它的现货（raw）也不读
    assert panels["BBB"]["spot_quote_volume"].isna().all()
    # t 之前最近一次结算是当天 16 点那次（8 小时一次，折成每 8 小时等价就是它自己）
    assert a.loc[t, "funding_rate"] == pytest.approx(fake.rate("AAA", t + 1 - 8 * 3_600_000))
    # 全是接口的数：和整段一次装配的一样
    whole = FakeFapi(SYMBOLS, fake.now).binance()
    since = live.window_start(t)
    full = assemble(whole.recent_klines("AAA", since, t + 1), None, whole.recent_klines("BTCUSDT", since, t + 1),
                    premium=whole.recent_premium("AAA", since, t + 1), metrics=whole.recent_metrics("AAA", since, t + 1),
                    pool_months=months["AAA"], spot=whole.recent_spot_klines("AAA", since, t + 1))
    full = full.loc[a.index]
    for c in ("close", "quote_volume", "ret", "btc_ret", "premium", "oi", "ls_top_pos", "ls_top_acct", "ls_acct",
              "taker_ls_vol", "hour", "spot_quote_volume", "spot_taker_buy_quote", "spot_trades"):
        np.testing.assert_array_equal(a[c].to_numpy(), full[c].to_numpy(), err_msg=c)
    # 00:15 那根的收盘价一个币拉一根；拉不到的币（fapi 回 400）不出
    assert live.closes(fake.binance(), ["AAA", "CCC"], t + BAR_MS) == {"AAA": pytest.approx(fake.close("AAA", t + 1))}
